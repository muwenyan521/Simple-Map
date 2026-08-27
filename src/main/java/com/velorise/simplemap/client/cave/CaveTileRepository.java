package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.cave.archive.CaveArchiveV2Service;
import com.velorise.simplemap.client.cave.archive.CompactCaveTile;
import com.velorise.simplemap.client.cave.v2.CaveCacheService;
import com.velorise.simplemap.client.persistence.v2.MapPersistenceV2Service;
import com.velorise.simplemap.client.pipeline.RevisionStamp;
import com.velorise.simplemap.client.session.MapSessionManager;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import com.velorise.simplemap.client.CaveMode;
import com.velorise.simplemap.client.FullCaveMapManager;
import com.velorise.simplemap.client.MapDebugRecorder;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapWorkScheduler;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Shared persistent source of truth for Layered and Full Cave views. */
public final class CaveTileRepository {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final CaveTileRepository INSTANCE = new CaveTileRepository();
    private static final int MAX_LOADED_TILES = 8192;
    private static final int MAX_DISPLAY_TILES = 8192;
    /** Current, previous and two adjacent AUTO slices per chunk/band. */
    private static final int MAX_EXACT_VARIANTS_PER_BAND = 4;
    private static final int DISPLAY_SAVE_BATCH_SIZE = 16;
    private static final int PAGE_SIZE = 64;
    private static final int PROJECTION_SIZE = PAGE_SIZE + 2;
    /** Four central chunks plus a one-chunk border on every side. */
    private static final int DISPLAY_TILE_WINDOW = 6;
    private static final int MAX_RESOLVED_PAGE_CACHE = 128;
    private static final int SAVE_BATCH_SIZE = 8;
    private static final int REGION_COMPACTION_SAVE_INTERVAL = 64;
    /** Disk writes are pull-driven and bounded. Saturation keeps data dirty and
     * retries later instead of throwing through Minecraft's client tick. */
    private static final int MAX_PENDING_RAW_SAVE_BATCHES = 2;
    private static final int MAX_PENDING_DISPLAY_SAVE_BATCHES = 1;
    private static final int MAX_PENDING_COMPACTIONS = 1;
    private static final int MAX_PENDING_ARCHIVE_PUBLICATIONS = 64;
    private static final long MIN_SAVE_RETRY_MS = 10L;
    private static final long MAX_SAVE_RETRY_MS = 500L;
    private static final boolean DEBUG_TELEMETRY_DISABLED =
            Boolean.getBoolean("simplemap.disableDebugTelemetry");
    /** Small direct-projection workspace reused by each page-build worker. */
    private static final ThreadLocal<ProjectionWorkspace> PROJECTION_WORKSPACE =
            ThreadLocal.withInitial(ProjectionWorkspace::new);

    private final Map<Long, CaveChunkTile> tiles = new LinkedHashMap<>(256, 0.75f, true);
    /** Exact 2D output used by Layered/Full rendering; no run graph is involved. */
    private final Map<DenseCaveTileKey, DenseCaveTile> displayTiles =
            new LinkedHashMap<>(256, 0.75f, true);
    private final Set<DenseCaveTileKey> indexedDisplayTiles = new HashSet<>();
    /**
     * Union index for resident and disk-backed projections. Packet mutation is
     * chunk-local, so walking every retained Top-Y key (up to 8,192 entries) for
     * every light/block update was both unnecessary and a major client-thread
     * allocation source.
     */
    private final Map<Long, Set<DenseCaveTileKey>> displayKeysByChunk = new HashMap<>();
    /** O(1) region presence indexes used by render-plan pending checks. */
    private final Long2IntOpenHashMap displayRegionChunkCounts = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap loadedDisplayRegionCounts = new Long2IntOpenHashMap();
    private final Set<DenseCaveTileKey> dirtyDisplayTiles = new HashSet<>();
    /**
     * Soft-invalidated dense tiles remain renderable until a complete replacement
     * is committed. This mirrors Xaero's incremental map updates: refresh never
     * turns already known terrain into a temporary black square.
     */
    private final Set<DenseCaveTileKey> staleDisplayTiles = new HashSet<>();
    private final Map<DenseCaveTileKey, CaveDisplayRegionStore.RecordPointer>
            displayRecords = new HashMap<>();
    private final Map<DenseCaveTileKey, CompletableFuture<?>>
            pendingDisplayLoads = new HashMap<>();
    private final Map<DenseCaveTileKey, CompletableFuture<?>>
            pendingDisplaySaves = new HashMap<>();
    private final Set<Long> indexedTiles = new HashSet<>();
    private final Long2IntOpenHashMap indexedRegionCounts = new Long2IntOpenHashMap();
    private final Set<Long> loadedScannedTiles = new HashSet<>();
    private final Long2IntOpenHashMap loadedRawRegionCounts = new Long2IntOpenHashMap();
    /** Region requests received before the asynchronous disk index is usable. */
    private final Set<Long> deferredIndexRegionLoads = new HashSet<>();
    /** Latest random-access record for each tile stored in a packed .cvr region file. */
    private final Map<Long, CaveRegionStore.RecordPointer> regionRecords = new HashMap<>();
    private final Long2LongOpenHashMap regionRevisions = new Long2LongOpenHashMap();
    private final Map<Long, CompletableFuture<?>> pendingLoads = new HashMap<>();
    /**
     * Indexed SMR2 source is not resident authority. Refill is coalesced at the
     * native 32x32-chunk container boundary because one SMR2 read already parses
     * the complete region file.
     */
    private final Map<Long, CompletableFuture<Integer>> pendingArchiveRegionLoads =
            new HashMap<>();
    /**
     * Native SMR2 regions already probed by the lazy foreground cache path. A
     * successful probe indexes every cave record in that region; a miss falls
     * through to Anvil. This prevents rereading one partial/missing region every
     * observation pulse before the old global archive index would have finished.
     */
    private final Set<Long> probedArchiveV2Regions = new HashSet<>();
    private final Map<Long, CompletableFuture<?>> pendingSaves = new HashMap<>();
    private final Map<Long, CompletableFuture<?>> pendingCompactions = new HashMap<>();
    /** Lightweight snapshots waiting for variable-length compact materialization. */
    private final Map<Long, ArchivePublicationTicket> queuedArchivePublications =
            new LinkedHashMap<>();
    /** At most one compact builder owns a chunk; later milestones coalesce behind it. */
    private final Map<Long, CompletableFuture<CompactCaveTile>>
            activeArchivePublications = new HashMap<>();
    private final Long2IntOpenHashMap regionSaveCounts = new Long2IntOpenHashMap();
    private final Map<PageCacheKey, ResolvedPage> resolvedPageCache =
            new LinkedHashMap<>(32, 0.75f, true);
    private final List<TileListener> listeners = new CopyOnWriteArrayList<>();
    private final CaveTelemetry telemetry = CaveTelemetry.getInstance();
    private final AtomicLong generation = new AtomicLong(1L);
    private long nextSaveAdmissionNanos;
    private long saveRetryDelayMs = MIN_SAVE_RETRY_MS;
    private long denseVariantTrimCalls;
    private long denseVariantTrimKeysScanned;
    private long denseVariantTrimTotalNanos;
    private long denseVariantTrimMaxNanos;
    private long denseVariantTrimNextTelemetryNanos;
    private boolean indexRebuildPending;

    private volatile File directory;

    private CaveTileRepository() {
    }

    public static CaveTileRepository getInstance() {
        return INSTANCE;
    }

    public long generation() {
        return generation.get();
    }

    public boolean isGenerationCurrent(long value) {
        return generation.get() == value;
    }

    public void addListener(TileListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void setDirectory(File requested) {
        File previousDirectory;
        File indexDirectory;
        long indexGeneration;
        List<CaveChunkTile.Snapshot> snapshots;
        List<DenseCaveTile> displaySnapshots;
        List<CompletableFuture<?>> inFlight;
        List<CompletableFuture<?>> displayInFlight;
        List<CompletableFuture<CompactCaveTile>> archiveInFlight;
        synchronized (this) {
            if (requested != null && requested.equals(directory)) return;
            previousDirectory = directory;
            snapshots = collectDirtySnapshotsLocked();
            displaySnapshots = collectDirtyDisplayTilesLocked();
            inFlight = new ArrayList<>(pendingSaves.values());
            displayInFlight = new ArrayList<>(pendingDisplaySaves.values());
            archiveInFlight = new ArrayList<>(activeArchivePublications.values());

            generation.incrementAndGet();
            tiles.clear();
            displayTiles.clear();
            indexedDisplayTiles.clear();
            displayKeysByChunk.clear();
            displayRegionChunkCounts.clear();
            loadedDisplayRegionCounts.clear();
            dirtyDisplayTiles.clear();
            staleDisplayTiles.clear();
            displayRecords.clear();
            pendingDisplayLoads.clear();
            pendingDisplaySaves.clear();
            regionRevisions.clear();
            pendingLoads.clear();
            pendingArchiveRegionLoads.clear();
            probedArchiveV2Regions.clear();
            pendingSaves.clear();
            pendingCompactions.clear();
            queuedArchivePublications.clear();
            activeArchivePublications.clear();
            regionSaveCounts.clear();
            nextSaveAdmissionNanos = 0L;
            saveRetryDelayMs = MIN_SAVE_RETRY_MS;
            resetDenseVariantTrimStatsLocked();
            resolvedPageCache.clear();
            indexedTiles.clear();
            indexedRegionCounts.clear();
            loadedScannedTiles.clear();
            loadedRawRegionCounts.clear();
            deferredIndexRegionLoads.clear();
            regionRecords.clear();

            directory = requested;
            if (directory != null && !directory.exists() && !directory.mkdirs()) {
                LOGGER.warn("Could not create cave tile directory {}", directory);
            }
            indexDirectory = directory;
            indexRebuildPending = indexDirectory != null
                    && indexDirectory.isDirectory();
            indexGeneration = generation.incrementAndGet();
        }
        for (CompletableFuture<CompactCaveTile> future : archiveInFlight) {
            if (future != null) future.cancel(false);
        }
        flushSnapshotsAfter(previousDirectory, snapshots, inFlight);
        flushDisplayTilesAfter(previousDirectory, displaySnapshots, displayInFlight);
        scheduleIndexRebuild(indexDirectory, indexGeneration);
        if (indexDirectory != null) {
            /*
             * PASS151: do not decode the entire persisted cave history at world open
             * just to build an identity index. The supplied PASS149 run decoded
             * 66,405 CompactCaveTile records and did not finish that scan until
             * ~14.6 s, overlapping the first fullscreen Cave load. Foreground pages
             * now probe their addressed SMR2 native region directly; the region read
             * validates world identity and lazily installs the same fingerprints.
             */
            MapDebugRecorder.getInstance().event(
                    "CAVE_ARCHIVE_PERSISTENCE_LAZY_REGION_READY",
                    "directory=" + indexDirectory.getName()
                            + " policy=direct_smr2_region_on_visible_demand_no_global_tile_decode"
                            + " pass=PASS152");
        }
    }

    public synchronized File directory() {
        return directory;
    }

    public synchronized DenseVariantTrimStats denseVariantTrimStats() {
        return new DenseVariantTrimStats(denseVariantTrimCalls,
                denseVariantTrimKeysScanned, denseVariantTrimTotalNanos,
                denseVariantTrimMaxNanos);
    }

    public CaveChunkTile getOrCreateLiveTile(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        synchronized (this) {
            CaveChunkTile existing = tiles.get(key);
            if (existing != null) return existing;
            CaveChunkTile created = new CaveChunkTile(chunkX, chunkZ, true);
            tiles.put(key, created);
            trimLoadedTiles();
            if (indexedTiles.contains(key)) requestTileLoadLocked(chunkX, chunkZ, key);
            return created;
        }
    }

    /**
     * Claims a live source route without turning an authority observation into a
     * content invalidation. Existing complete columns remain immediately usable.
     */
    public LiveAuthorityClaim ensureLiveAuthority(int chunkX, int chunkZ) {
        CaveChunkTile tile = getOrCreateLiveTile(chunkX, chunkZ);
        boolean transitioned = tile.ensureLiveAuthority();
        return new LiveAuthorityClaim(tile, tile.liveAuthorityState(), transitioned);
    }

    public record LiveAuthorityClaim(CaveChunkTile tile,
            CaveChunkTile.LiveAuthorityState state, boolean transitioned) {
    }

    public synchronized CaveChunkTile getLoadedTile(int chunkX, int chunkZ) {
        return tiles.get(pack(chunkX, chunkZ));
    }

    public synchronized int loadedTileCount() {
        return tiles.size();
    }

    public synchronized int loadedDisplayTileCount() {
        return displayTiles.size();
    }

    /**
     * Returns true when a tile of at least the requested authority is resident, or
     * when the request may be satisfied by the persistent dense cache.
     *
     * <p>This broad availability probe is intentionally separate from the
     * scheduler-facing {@code hasFresh*} methods below. An index entry only proves
     * that a record exists on disk; it does not prove that its payload is resident
     * and usable by an exact page build.</p>
     */
    public synchronized boolean hasDisplayTileSource(CaveView view, int layerY,
            int chunkX, int chunkZ, DenseCaveTile.Source minimumSource) {
        DenseCaveTileKey key = DenseCaveTileKey.of(chunkX, chunkZ, view, layerY);
        return hasDisplayTileSourceLocked(key, minimumSource);
    }

    /**
     * Scheduler-facing authority check. A stale tile is still a valid render
     * fallback, but it must not suppress a replacement scan/read.
     */
    public synchronized boolean hasFreshDisplayTileSource(CaveView view, int layerY,
            int chunkX, int chunkZ, DenseCaveTile.Source minimumSource) {
        DenseCaveTileKey key = DenseCaveTileKey.of(chunkX, chunkZ, view, layerY);
        if (staleDisplayTiles.contains(key)) return false;
        DenseCaveTile loaded = displayTiles.get(key);
        if (loaded != null) {
            return loaded.source().rank() >= minimumSource.rank()
                    && (view == CaveView.FULL || loaded.projectionTopY() == layerY);
        }
        // An indexed/pending record is only IO intent. Treating it as resident
        // authority made fullscreen source transactions retain fifteen phantom
        // leaves, commit one real chunk, and then loop forever after LRU eviction.
        return false;
    }

    /**
     * Page-reader check for one already resolved leaf. Unlike
     * {@link #hasFreshDisplayTileSource}. A transient disk absence deliberately
     * does not count as resolved; only a real tile can satisfy this probe.
     */
    public synchronized boolean hasFreshDisplayTileOrKnownEmpty(CaveView view,
            int layerY, int chunkX, int chunkZ,
            DenseCaveTile.Source minimumSource) {
        DenseCaveTileKey key = DenseCaveTileKey.of(
                chunkX, chunkZ, view, layerY);
        if (staleDisplayTiles.contains(key)) return false;
        DenseCaveTile loaded = displayTiles.get(key);
        if (loaded != null) {
            return loaded.source().rank() >= minimumSource.rank()
                    && (view == CaveView.FULL
                            || loaded.projectionTopY() == layerY);
        }
        // Disk index membership is not a resolved source leaf. Pending cache IO is
        // gated at page level by hasPendingDisplayPageLoad(); if that IO does not
        // produce a resident tile, the Anvil reader must be allowed to repair it.
        return false;
    }

    /**
     * Returns true only when every central 16x16 tile of a 64x64 page is already
     * authoritative (or explicitly known empty). World-save scheduling uses this
     * page-level check so publication is coherent instead of exposing isolated
     * Minecraft chunks in worker completion order.
     */
    public synchronized boolean hasFreshDisplayPageSource(CaveView view, int layerY,
            int globalPageX, int globalPageZ, DenseCaveTile.Source minimumSource) {
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        for (int localX = 0; localX < 4; localX++) {
            for (int localZ = 0; localZ < 4; localZ++) {
                DenseCaveTileKey key = DenseCaveTileKey.of(
                        firstChunkX + localX, firstChunkZ + localZ, view, layerY);
                if (staleDisplayTiles.contains(key)) return false;
                DenseCaveTile loaded = displayTiles.get(key);
                if (loaded == null || loaded.source().rank() < minimumSource.rank()) return false;
                if (view != CaveView.FULL && loaded.projectionTopY() != layerY) return false;
            }
        }
        return true;
    }

    /**
     * Cheap central-page readiness probe used before scheduling an exact build.
     * Border data may refine later, but all sixteen central chunks must already be
     * authoritative for the requested projection. This prevents thousands of
     * empty/partial builds while one page source transaction is still in flight.
     */
    public synchronized boolean isPageProjectionReady(CaveView view, int layerY,
            int globalPageX, int globalPageZ) {
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        CaveArchiveV2Service archiveService = CaveArchiveV2Service.getInstance();
        int archiveMask = archiveService.residentProjectionMask(
                globalPageX, globalPageZ, view == CaveView.FULL);
        for (int localZ = 0; localZ < 4; localZ++) {
            for (int localX = 0; localX < 4; localX++) {
                int order = localX * 4 + localZ;
                int chunkX = firstChunkX + localX;
                int chunkZ = firstChunkZ + localZ;
                DenseCaveTileKey key = DenseCaveTileKey.of(
                        chunkX, chunkZ, view, layerY);
                DenseCaveTile dense = staleDisplayTiles.contains(key)
                        ? null : displayTiles.get(key);
                if (dense != null && (view == CaveView.FULL
                        || dense.projectionTopY() == layerY)) continue;
                if ((archiveMask & (1 << order)) != 0) continue;
                CaveChunkTile legacy = tiles.get(pack(chunkX, chunkZ));
                if (legacy != null && legacy.isComplete()) continue;
                return false;
            }
        }
        return true;
    }

    /**
     * Cheap 16-bit central-child coverage for foreground projection coalescing.
     * Complete compact authority or any exact, non-stale Dense projection may
     * contribute. WORLD_SAVE/DISK exact tiles are already presentation-ready and
     * must not wait for the reusable vertical archive to become resident.
     * Disk/header absence is deliberately absent from this identity.
     */
    public synchronized int projectionChildReadyMask(CaveView view, int layerY,
            int globalPageX, int globalPageZ) {
        CaveView effectiveView = view == null ? CaveView.FULL : view;
        int mask = CaveArchiveV2Service.getInstance().residentProjectionMask(
                globalPageX, globalPageZ, effectiveView == CaveView.FULL);
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        for (int localZ = 0; localZ < 4; localZ++) {
            for (int localX = 0; localX < 4; localX++) {
                int order = localX * 4 + localZ;
                int bit = 1 << order;
                if ((mask & bit) != 0) continue;
                DenseCaveTileKey key = DenseCaveTileKey.of(
                        firstChunkX + localX, firstChunkZ + localZ,
                        effectiveView, layerY);
                DenseCaveTile dense = staleDisplayTiles.contains(key)
                        ? null : displayTiles.get(key);
                if (dense == null) continue;
                if (effectiveView == CaveView.LAYERED
                        && dense.projectionTopY() != layerY) continue;
                mask |= bit;
            }
        }
        return mask;
    }

    /** True while presentation-ready cache data for this page is already in IO. */
    public synchronized boolean hasPendingDisplayPageLoad(CaveView view, int layerY,
            int globalPageX, int globalPageZ) {
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        for (int localZ = 0; localZ < 4; localZ++) {
            for (int localX = 0; localX < 4; localX++) {
                DenseCaveTileKey key = DenseCaveTileKey.of(
                        firstChunkX + localX, firstChunkZ + localZ,
                        view, layerY);
                if (pendingDisplayLoads.containsKey(key)) return true;
            }
        }
        return false;
    }

    private boolean hasDisplayTileSourceLocked(DenseCaveTileKey key,
            DenseCaveTile.Source minimumSource) {
        DenseCaveTile loaded = displayTiles.get(key);
        if (loaded != null && loaded.source().rank() >= minimumSource.rank()) return true;
        return minimumSource.rank() <= DenseCaveTile.Source.WORLD_SAVE.rank()
                && (indexedDisplayTiles.contains(key)
                        || pendingDisplayLoads.containsKey(key));
    }

    /**
     * Marks one mode/layer tile for a non-destructive rebuild. The existing dense
     * tile and its uploaded page stay visible until a complete replacement arrives.
     */
    public synchronized void markDisplayTileStale(CaveView view, int layerY,
            int chunkX, int chunkZ) {
        DenseCaveTileKey key = DenseCaveTileKey.of(chunkX, chunkZ, view, layerY);
        staleDisplayTiles.add(key);
    }

    public synchronized boolean isDisplayTileStale(CaveView view, int layerY,
            int chunkX, int chunkZ) {
        return staleDisplayTiles.contains(
                DenseCaveTileKey.of(chunkX, chunkZ, view, layerY));
    }

    /**
     * Marks every already-known cave projection in a chunk range stale, regardless
     * of cave view or Layered Top-Y. This is used after teleport/world transitions,
     * where the automatic layer may intentionally remain frozen for a few ticks.
     * Existing pixels remain resident as fallback until a stable live transaction
     * replaces the currently requested projection.
     */
    public synchronized int markDisplayRangeStaleAllLayers(
            int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
            int maximumTiles) {
        int limit = Math.max(0, maximumTiles);
        int marked = 0;
        for (int chunkX = minChunkX; chunkX <= maxChunkX && marked < limit; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ && marked < limit; chunkZ++) {
                Set<DenseCaveTileKey> keys = displayKeysByChunk.get(pack(chunkX, chunkZ));
                if (keys == null) continue;
                for (DenseCaveTileKey key : keys) {
                    if (marked >= limit) break;
                    if (staleDisplayTiles.add(key)) marked++;
                }
            }
        }
        return marked;
    }

    /** Marks only already known tiles in a viewport; unknown world areas are not
     * added to the stale set. The old pixels remain available as visual fallback. */
    public synchronized int markDisplayRangeStale(CaveView view, int layerY,
            int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
            int maximumTiles) {
        int normalized = DenseCaveTile.normalizeLayer(view, layerY);
        int limit = Math.max(0, maximumTiles);
        int marked = 0;
        for (int chunkX = minChunkX; chunkX <= maxChunkX && marked < limit; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ && marked < limit; chunkZ++) {
                Set<DenseCaveTileKey> keys = displayKeysByChunk.get(pack(chunkX, chunkZ));
                if (keys == null) continue;
                for (DenseCaveTileKey key : keys) {
                    if (marked >= limit) break;
                    if (key.view() == view && key.bandY() == normalized
                            && staleDisplayTiles.add(key)) marked++;
                }
            }
        }
        return marked;
    }

    public boolean commitDisplayTile(DenseCaveTile tile, long expectedGeneration) {
        if (tile == null) return false;
        synchronized (this) {
            if (!isGenerationCurrent(expectedGeneration)) return false;
            DenseCaveTileKey key = DenseCaveTileKey.of(tile);
            DenseCaveTile current = displayTiles.get(key);
            /*
             * PASS160: stale is not authority.
             *
             * A LIVE tile can have a stronger source rank than WORLD_SAVE while also
             * being explicitly stale for this exact Top-Y. PASS159 rejected the
             * snapshot replacement solely because LIVE > WORLD_SAVE. The scheduler
             * then observed "exact presentation missing", re-leased the same already
             * decoded source, projected it again, rejected it again, and repeated the
             * same 7 children every ~2 s forever. A stale tile remains a visual
             * fallback only; it must not veto a fresh replacement of lower rank.
             */
            boolean currentStale = current != null && staleDisplayTiles.contains(key);
            if (current != null && !currentStale
                    && current.source().rank() > tile.source().rank()) return false;
            if (current != null && !currentStale
                    && current.source() == tile.source()) {
                if (current.revision() >= tile.revision()) return false;
                if (current.sameProjectionContent(tile)) return false;
            }
            if (currentStale && current.source().rank() > tile.source().rank()) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String eventKey = "CAVE_STALE_HIGHER_AUTHORITY_REPLACED:"
                        + tile.chunkX() + ':' + tile.chunkZ() + ':'
                        + tile.projectionTopY();
                if (recorder.shouldEmitEvent(eventKey, 500L)) {
                    recorder.event("CAVE_STALE_HIGHER_AUTHORITY_REPLACED",
                            "chunk=" + tile.chunkX() + ',' + tile.chunkZ()
                                    + " top_y=" + tile.projectionTopY()
                                    + " stale_source=" + current.source()
                                    + " replacement_source=" + tile.source()
                                    + " policy=stale_is_fallback_not_authority"
                                    + " pass=PASS160");
                }
            }
            putDisplayTileLocked(key, tile);
            staleDisplayTiles.remove(key);
            if (tile.source() != DenseCaveTile.Source.DISK) dirtyDisplayTiles.add(key);
            touchDisplayTileLocked(DenseCaveTileKey.of(tile), tile.revision());
            trimDisplayTilesLocked();
            return true;
        }
    }

    /**
     * Transactionally merges the resolved leaves of one 64x64 page. Existing
     * higher-authority LIVE tiles and unresolved leaves are retained. Absence is
     * intentionally not accepted by this presentation transaction.
     */
    public synchronized boolean commitDisplayPage(List<DenseCaveTile> replacements,
            CaveView view, int layerY, int firstChunkX, int firstChunkZ,
            long expectedGeneration) {
        if (!isGenerationCurrent(expectedGeneration)) return false;
        int normalized = DenseCaveTile.normalizeLayer(view, layerY);
        boolean changed = false;
        java.util.LinkedHashMap<Long, Long> changedChunks =
                new java.util.LinkedHashMap<>();

        if (replacements != null) {
            for (DenseCaveTile tile : replacements) {
                if (tile == null || tile.view() != view || tile.layerY() != normalized) continue;
                DenseCaveTileKey key = DenseCaveTileKey.of(tile);
                DenseCaveTile current = displayTiles.get(key);
                boolean currentStale = current != null && staleDisplayTiles.contains(key);
                if (current != null && !currentStale
                        && current.source().rank() > tile.source().rank()) continue;
                if (current != null && !currentStale
                        && current.source() == tile.source()) {
                    if (current.revision() >= tile.revision()) continue;
                    if (current.sameProjectionContent(tile)) continue;
                }
                putDisplayTileLocked(key, tile);
                staleDisplayTiles.remove(key);
                if (tile.source() != DenseCaveTile.Source.DISK) dirtyDisplayTiles.add(key);
                changedChunks.put(pack(tile.chunkX(), tile.chunkZ()), tile.revision());
                changed = true;
            }
        }

        if (changed) {
            touchDisplayPageLocked(view, normalized,
                    firstChunkX >> 2, firstChunkZ >> 2, changedChunks);
            trimDisplayTilesLocked();
        }
        return changed;
    }

    public synchronized void invalidateDisplayTile(int chunkX, int chunkZ) {
        Set<DenseCaveTileKey> keys = removeDisplayChunkIndexLocked(chunkX, chunkZ);
        if (keys != null) {
            long revision = System.nanoTime();
            for (DenseCaveTileKey key : keys) {
                removeDisplayTileLocked(key);
                dirtyDisplayTiles.remove(key);
                staleDisplayTiles.remove(key);
                indexedDisplayTiles.remove(key);
                displayRecords.remove(key);
                touchDisplayTileLocked(key, revision);
            }
        }
    }

    public synchronized void markDisplayTileAbsent(CaveView view, int layerY,
            int chunkX, int chunkZ, long expectedGeneration) {
        // PASS132: disk/header absence is a retry hint, never a display tile.
    }

    /**
     * True when a complete live/cache tile or a pending .cvr read already owns this
     * chunk. A partially populated runtime tile is deliberately not authoritative:
     * the world-save reader may still fill its missing columns.
     */
    public synchronized boolean hasTileSource(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        CaveChunkTile tile = tiles.get(key);
        return (tile != null && tile.isComplete())
                || indexedTiles.contains(key) || pendingLoads.containsKey(key);
    }

    /**
     * Publishes a complete read-only world-save reconstruction. Missing columns are
     * filled, but live-scanned columns are never replaced. Imported data remains
     * dirty and is therefore batched into the normal .cvr save path.
     */
    public boolean mergeWorldSaveTile(CaveChunkTile.Snapshot snapshot,
            long expectedGeneration) {
        if (snapshot == null) return false;
        long key = pack(snapshot.chunkX(), snapshot.chunkZ());

        /* Build and populate a new tile before exposing it through the repository.
         * This prevents trimLoadedTiles() from retiring a momentarily empty tile. */
        CaveChunkTile candidate = new CaveChunkTile(
                snapshot.chunkX(), snapshot.chunkZ(), false);
        if (!candidate.mergeWorldSave(snapshot)) return false;

        while (true) {
            CaveChunkTile tile;
            synchronized (this) {
                if (!isGenerationCurrent(expectedGeneration)) return false;
                tile = tiles.get(key);
                if (tile == null) {
                    tiles.put(key, candidate);
                    touchLocked(candidate.chunkX(), candidate.chunkZ(),
                            candidate.revision());
                    trimLoadedTiles();
                    return true;
                }
            }

            boolean changed = tile.mergeWorldSave(snapshot);
            if (!changed) return false;
            synchronized (this) {
                if (!isGenerationCurrent(expectedGeneration)) return false;
                // A concurrent trim/reload may have replaced the tile. Retry against
                // the currently published instance instead of touching an orphan.
                if (tiles.get(key) != tile) continue;
                touchLocked(tile.chunkX(), tile.chunkZ(), tile.revision());
                return true;
            }
        }
    }

    public synchronized boolean isColumnScanned(int blockX, int blockZ) {
        CaveChunkTile tile = tiles.get(pack(blockX >> 4, blockZ >> 4));
        return tile != null && tile.isColumnScanned(blockX & 15, blockZ & 15);
    }

    public void invalidateTile(int chunkX, int chunkZ) {
        CaveChunkTile tile = getOrCreateLiveTile(chunkX, chunkZ);
        tile.invalidateAll();
        touch(chunkX, chunkZ, tile.revision());
    }

    /** Invalidates an archive tile only when it is already resident. */
    public boolean invalidateLoadedTile(int chunkX, int chunkZ) {
        CaveChunkTile tile;
        synchronized (this) {
            tile = tiles.get(pack(chunkX, chunkZ));
        }
        if (tile == null) return false;
        tile.invalidateAll();
        touch(chunkX, chunkZ, tile.revision());
        return true;
    }

    public boolean invalidateColumn(int blockX, int blockZ) {
        CaveChunkTile tile = getOrCreateLiveTile(blockX >> 4, blockZ >> 4);
        boolean changed = tile.invalidateColumn(blockX & 15, blockZ & 15);
        if (changed) touch(tile.chunkX(), tile.chunkZ(), tile.revision());
        return changed;
    }

    /** Queue a non-destructive live-world recheck while retaining current pixels. */
    public boolean requestColumnRecheck(int blockX, int blockZ) {
        CaveChunkTile tile = getLoadedTile(blockX >> 4, blockZ >> 4);
        if (tile == null || !tile.isComplete()) return false;
        return tile.requestRecheckColumn(blockX & 15, blockZ & 15);
    }

    public boolean commitColumn(CaveChunkTile tile, int columnIndex, CaveColumnData data) {
        boolean changed = tile.commitColumn(columnIndex, data);
        if (changed) publishTileChanges(tile);
        return changed;
    }

    /**
     * Commits one column without immediately invalidating page/region projections.
     *
     * The scheduler uses this inside a coherent tile burst and publishes one combined
     * tile change after the burst. This avoids hundreds of page revision increments,
     * listener walks and stale texture builds while a 16x16 tile is being completed.
     */
    public boolean commitColumnDeferred(CaveChunkTile tile, int columnIndex,
            CaveColumnData data) {
        return tile.commitColumn(columnIndex, data);
    }

    /** Publishes all deferred column commits from one scheduler burst. */
    public boolean publishTileChanges(CaveChunkTile tile) {
        return tile != null && tile.shouldPublishArchiveMilestone()
                && publishArchiveV2(tile);
    }

    /**
     * Publishes a complete immutable archive decoded from Anvil. Unlike live tiles,
     * these snapshots used to enter only the session cache and were never appended
     * to SMR2, so every restart repeated the same expensive vertical scan.
     */
    public void ingestDecodedArchive(CaveChunkTile.Snapshot snapshot,
            long expectedGeneration) {
        if (snapshot == null || !isGenerationCurrent(expectedGeneration)) return;
        CompactCaveTile compact = CompactCaveTile.fromLegacy(snapshot);
        if (compact == null || !compact.completeCoverage()) return;
        if (!CaveArchiveV2Service.getInstance().ingest(compact)) return;

        File target;
        synchronized (this) {
            if (!isGenerationCurrent(expectedGeneration)) return;
            target = directory;
        }
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (target == null || stamp == null || !stamp.isCurrent()) return;
        long worldIdentity = target.getAbsolutePath().hashCode()
                * 0x9E3779B97F4A7C15L;
        MapPersistenceV2Service.getInstance().appendCave(target,
                worldIdentity, compact, stamp.styleGeneration());
    }

    private boolean publishArchiveV2(CaveChunkTile tile) {
        if (tile == null || !tile.hasAnyScannedColumn()) return false;
        long archiveRevision = tile.archiveRevision();
        if (tile.archivePublicationCurrent(archiveRevision)) return false;
        long key = pack(tile.chunkX(), tile.chunkZ());
        File target;
        long expectedGeneration;
        synchronized (this) {
            if (tiles.get(key) != tile) return false;
            if (queuedArchivePublications.containsKey(key)
                    || activeArchivePublications.containsKey(key)) return true;
            if (queuedArchivePublications.size()
                    + activeArchivePublications.size()
                            >= MAX_PENDING_ARCHIVE_PUBLICATIONS) return false;
            target = directory;
            expectedGeneration = generation.get();
        }
        // CaveColumnData is immutable. This fixed-size reference snapshot is the
        // only publication work allowed on the client lane; variable-length run
        // arrays are materialized by the CPU worker below.
        CaveChunkTile.Snapshot snapshot = tile.snapshot();
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        ArchivePublicationTicket ticket = new ArchivePublicationTicket(tile,
                snapshot, expectedGeneration, archiveRevision, tile.revision(),
                tile.archivePublicationMilestone(), tile.scannedColumnCount(),
                tile.isComplete() || tile.scannedColumnCount() % 64 == 0,
                target, stamp);
        synchronized (this) {
            if (generation.get() != expectedGeneration || directory != target
                    || tiles.get(key) != tile) return false;
            if (queuedArchivePublications.containsKey(key)
                    || activeArchivePublications.containsKey(key)) return true;
            queuedArchivePublications.put(key, ticket);
            pumpArchivePublicationsLocked(1);
        }
        return true;
    }

    /** Retries bounded worker admission without doing compact work inline. */
    public synchronized int pumpArchivePublications(int maximum) {
        return pumpArchivePublicationsLocked(Math.max(0, maximum));
    }

    private int pumpArchivePublicationsLocked(int maximum) {
        int admitted = 0;
        var iterator = queuedArchivePublications.entrySet().iterator();
        while (admitted < maximum && iterator.hasNext()) {
            Map.Entry<Long, ArchivePublicationTicket> entry = iterator.next();
            long key = entry.getKey();
            ArchivePublicationTicket ticket = entry.getValue();
            CompletableFuture<CompactCaveTile> future =
                    MapWorkScheduler.tryCpuFuture(MapRequestLane.BACKGROUND,
                            MapWorkScheduler.WorkType.SOURCE_PROJECTION,
                            MapRequestLane.BACKGROUND.priorityBase(), 16,
                            () -> generation.get() == ticket.repositoryGeneration()
                                    && directory == ticket.targetDirectory(),
                            () -> CompactCaveTile.fromLegacy(ticket.snapshot()));
            if (future == null) break;
            iterator.remove();
            activeArchivePublications.put(key, future);
            future.whenComplete((compact, failure) -> completeArchivePublication(
                    key, ticket, future, compact, failure));
            admitted++;
        }
        return admitted;
    }

    private void completeArchivePublication(long key,
            ArchivePublicationTicket ticket,
            CompletableFuture<CompactCaveTile> future,
            CompactCaveTile compact, Throwable failure) {
        CaveChunkTile tile;
        synchronized (this) {
            if (activeArchivePublications.get(key) != future) return;
            activeArchivePublications.remove(key);
            tile = tiles.get(key);
        }
        boolean valid = failure == null && compact != null
                && generation.get() == ticket.repositoryGeneration()
                && directory == ticket.targetDirectory()
                && tile == ticket.tile();
        if (!valid) {
            pumpArchivePublications(1);
            return;
        }

        boolean fingerprintChanged = CaveCacheService.getInstance().ingest(compact);
        telemetry.recordArchiveCompactPublication(fingerprintChanged);
        tile.markArchivePublished(ticket.archiveRevision());
        if (fingerprintChanged) {
            touch(tile.chunkX(), tile.chunkZ(), ticket.tileRevision());
        }
        RevisionStamp stamp = ticket.sessionStamp();
        File target = ticket.targetDirectory();
        if (target != null && stamp != null && stamp.isCurrent()
                && ticket.persistenceDue()) {
            long worldIdentity = target.getAbsolutePath().hashCode()
                    * 0x9E3779B97F4A7C15L;
            MapPersistenceV2Service.getInstance().appendCave(target,
                    worldIdentity, compact, stamp.styleGeneration());
        }
        if (compact.completeCoverage()) {
            CaveNativeRegionImportService.getInstance()
                    .acknowledgeLiveArchive(tile.chunkX(), tile.chunkZ());
        }
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (recorder.shouldEmitEvent("CAVE_ARCHIVE_PUBLICATION", 250L)) {
            recorder.event("CAVE_ARCHIVE_PUBLICATION",
                    "chunk=" + tile.chunkX() + ',' + tile.chunkZ()
                            + " milestone=" + ticket.milestone()
                            + " scanned=" + ticket.scannedColumns()
                            + " complete=" + compact.completeCoverage()
                            + " fingerprint_changed=" + fingerprintChanged
                            + " thread=cpu_worker");
        }
        // Coalesce every revision that arrived while this compact product was being
        // built into one newest follow-up ticket.
        if (tile.shouldPublishArchiveMilestone()) publishArchiveV2(tile);
        pumpArchivePublications(1);
    }

    public CaveColumnData.Candidate getCandidate(int blockX, int blockZ,
            int maximumY, int minimumY) {
        CaveChunkTile tile;
        synchronized (this) {
            tile = tiles.get(pack(blockX >> 4, blockZ >> 4));
            if (tile == null) {
                requestTileLoadLocked(blockX >> 4, blockZ >> 4,
                        pack(blockX >> 4, blockZ >> 4));
                return null;
            }
        }
        CaveColumnData column = tile.getColumn(blockX & 15, blockZ & 15);
        return column == null ? null : column.firstVisibleLayeredCandidate(maximumY, minimumY);
    }

    public CaveColumnData.Candidate getFullCandidate(int blockX, int blockZ) {
        CaveChunkTile tile;
        synchronized (this) {
            tile = tiles.get(pack(blockX >> 4, blockZ >> 4));
            if (tile == null) {
                requestTileLoadLocked(blockX >> 4, blockZ >> 4,
                        pack(blockX >> 4, blockZ >> 4));
                return null;
            }
        }
        CaveColumnData column = tile.getColumn(blockX & 15, blockZ & 15);
        return column == null ? null : column.firstVisibleFullCandidate();
    }

    public int getColor(CaveView view, int layerY, Level level, int blockX, int blockZ) {
        DenseCaveTile dense = getDisplayTile(view, layerY, blockX >> 4, blockZ >> 4);
        if (dense != null) return dense.color(blockX & 15, blockZ & 15);
        CaveColumnData.Candidate candidate = resolveCandidate(view, layerY, level, blockX, blockZ);
        return candidate == null ? 0 : candidate.color();
    }

    public int getHeight(CaveView view, int layerY, Level level, int blockX, int blockZ) {
        DenseCaveTile dense = getDisplayTile(view, layerY, blockX >> 4, blockZ >> 4);
        if (dense != null) return dense.floorY(blockX & 15, blockZ & 15);
        CaveColumnData.Candidate candidate = resolveCandidate(view, layerY, level, blockX, blockZ);
        return candidate == null ? FullCaveMapManager.NO_SURFACE : candidate.bottomY();
    }

    synchronized DenseCaveTile getLoadedDisplayTile(CaveView view, int layerY,
            int chunkX, int chunkZ) {
        return displayTiles.get(DenseCaveTileKey.of(
                chunkX, chunkZ, view, layerY));
    }

    /** Exact authority lookup; never returns a neighbouring slice in the band. */
    public synchronized DenseCaveTile getExactDisplayTile(CaveView view,
            int projectionTopY, int chunkX, int chunkZ) {
        DenseCaveTileKey key = DenseCaveTileKey.of(
                chunkX, chunkZ, view, projectionTopY);
        return staleDisplayTiles.contains(key) ? null : displayTiles.get(key);
    }

    /**
     * Visual fallback lookup for continuity only. Callers must not treat the
     * returned tile as authority for {@code requestedTopY}.
     */
    public synchronized DenseCaveTile getLastGoodBandTile(CaveView view,
            int requestedTopY, int chunkX, int chunkZ) {
        DenseCaveTileKey requested = DenseCaveTileKey.of(
                chunkX, chunkZ, view, requestedTopY);
        Set<DenseCaveTileKey> keys = displayKeysByChunk.get(pack(chunkX, chunkZ));
        if (keys == null) return null;
        DenseCaveTile newest = null;
        for (DenseCaveTileKey candidate : keys) {
            if (!requested.sameBand(candidate) || candidate.equals(requested)
                    || staleDisplayTiles.contains(candidate)) continue;
            DenseCaveTile tile = displayTiles.get(candidate);
            if (tile != null && (newest == null
                    || tile.revision() > newest.revision())) newest = tile;
        }
        return newest;
    }

    synchronized boolean isCurrentDisplayTileRevision(CaveView view, int layerY,
            int chunkX, int chunkZ, long revision, DenseCaveTile.Source source) {
        DenseCaveTile current = displayTiles.get(
                DenseCaveTileKey.of(chunkX, chunkZ, view, layerY));
        return current != null && current.revision() == revision
                && current.source() == source;
    }

    private DenseCaveTile getDisplayTile(CaveView view, int layerY,
            int chunkX, int chunkZ) {
        DenseCaveTileKey key = DenseCaveTileKey.of(chunkX, chunkZ, view, layerY);
        synchronized (this) {
            DenseCaveTile tile = staleDisplayTiles.contains(key)
                    ? null : displayTiles.get(key);
            if (tile == null && !displayTiles.containsKey(key)
                    && indexedDisplayTiles.contains(key)) {
                requestDisplayTileLoadLocked(key);
            }
            return tile;
        }
    }

    private CaveColumnData.Candidate resolveCandidate(CaveView view, int layerY,
            Level level, int blockX, int blockZ) {
        if (view == CaveView.FULL) return getFullCandidate(blockX, blockZ);
        int maximum = level == null ? layerY : CaveMode.getScanMaximum(level, layerY);
        int minimum = level == null ? layerY - 31 : CaveMode.getScanMinimum(level, layerY);
        return getCandidate(blockX, blockZ, maximum, minimum);
    }

    public void requestPageLoad(int globalPageX, int globalPageZ) {
        int firstChunkX = (globalPageX << 2) - 1;
        int firstChunkZ = (globalPageZ << 2) - 1;
        synchronized (this) {
            for (int dz = 0; dz < 6; dz++) {
                for (int dx = 0; dx < 6; dx++) {
                    int chunkX = firstChunkX + dx;
                    int chunkZ = firstChunkZ + dz;
                    long key = pack(chunkX, chunkZ);
                    if (!tiles.containsKey(key) && indexedTiles.contains(key)) {
                        requestTileLoadLocked(chunkX, chunkZ, key);
                    }
                }
            }
        }
    }

    /**
     * Requests the exact 64x64 page admitted by the visible scheduler plus the
     * one-chunk halo needed by cave projection. Unlike the range method, this does
     * not turn sparse admitted pages into a large rectangular IO request.
     */
    public void requestDisplayPageLoad(CaveView view, int layerY,
            int globalPageX, int globalPageZ) {
        requestDisplayPageLoad(view, layerY, globalPageX, globalPageZ,
                MapRequestLane.FULLSCREEN);
    }

    public void requestDisplayPageLoad(CaveView view, int layerY,
            int globalPageX, int globalPageZ, MapRequestLane lane) {
        int normalizedLayer = DenseCaveTile.normalizeLayer(view, layerY);
        int firstChunkX = (globalPageX << 2) - 1;
        int lastChunkX = (globalPageX << 2) + 4;
        int firstChunkZ = (globalPageZ << 2) - 1;
        int lastChunkZ = (globalPageZ << 2) + 4;
        boolean hasDenseCacheSource = false;
        int sameBandLastGoodTiles = 0;
        synchronized (this) {
            Set<DenseCaveTileKey> requested = new HashSet<>();
            for (int chunkZ = firstChunkZ; chunkZ <= lastChunkZ; chunkZ++) {
                for (int chunkX = firstChunkX; chunkX <= lastChunkX; chunkX++) {
                    DenseCaveTileKey key = DenseCaveTileKey.of(
                            chunkX, chunkZ, view, layerY);
                    boolean stale = staleDisplayTiles.contains(key);
                    DenseCaveTile loaded = stale ? null : displayTiles.get(key);
                    boolean exactLoaded = loaded != null;
                    boolean physicalLoaded = displayTiles.containsKey(key);
                    boolean indexedCold = !physicalLoaded
                            && indexedDisplayTiles.contains(key);
                    boolean pending = pendingDisplayLoads.containsKey(key);
                    if (exactLoaded || indexedCold || pending) {
                        hasDenseCacheSource = true;
                    }
                    if (!exactLoaded && view == CaveView.LAYERED
                            && getLastGoodBandTile(view, layerY,
                                    chunkX, chunkZ) != null) {
                        sameBandLastGoodTiles++;
                    }
                    if (!physicalLoaded && indexedDisplayTiles.contains(key)) {
                        requested.add(key);
                    }
                }
            }
            requestDisplayBatchLoadLocked(requested,
                    lane == null ? MapRequestLane.FULLSCREEN : lane);
        }
        if (sameBandLastGoodTiles > 0) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            String eventKey = "CAVE_DENSE_SAME_BAND_LAST_GOOD_HIT:" + normalizedLayer
                    + ':' + globalPageX + ':' + globalPageZ;
            if (recorder.shouldEmitEvent(eventKey, 500L)) {
                recorder.event("CAVE_DENSE_SAME_BAND_LAST_GOOD_HIT",
                        "page=" + globalPageX + ',' + globalPageZ
                                + " top_y=" + layerY
                                + " band=" + normalizedLayer
                                + " fallback_tiles=" + sameBandLastGoodTiles
                                + " action=retain_visual_request_exact");
            }
        }
        CaveArchiveV2Service archive = CaveArchiveV2Service.getInstance();
        boolean hasCanonicalArchive = archive.residentAnyMask(
                globalPageX, globalPageZ) != 0
                || archive.indexedAnyMask(globalPageX, globalPageZ) != 0;
        // A same-band visual fallback is never a reason to rehydrate raw .cvr.
        // Exact projection will consume the canonical vertical archive when present.
        if (!hasDenseCacheSource && !hasCanonicalArchive) {
            requestPageRangeLoad(globalPageX, globalPageX, globalPageZ, globalPageZ);
        }
    }

    public void requestDisplayPageRangeLoad(CaveView view, int layerY,
            int minGlobalPageX, int maxGlobalPageX,
            int minGlobalPageZ, int maxGlobalPageZ) {
        int normalizedLayer = DenseCaveTile.normalizeLayer(view, layerY);
        int firstChunkX = (Math.min(minGlobalPageX, maxGlobalPageX) << 2) - 1;
        int lastChunkX = (Math.max(minGlobalPageX, maxGlobalPageX) << 2) + 4;
        int firstChunkZ = (Math.min(minGlobalPageZ, maxGlobalPageZ) << 2) - 1;
        int lastChunkZ = (Math.max(minGlobalPageZ, maxGlobalPageZ) << 2) + 4;
        boolean hasDenseCacheSource = false;
        synchronized (this) {
            Set<DenseCaveTileKey> requested = new HashSet<>();
            for (int chunkZ = firstChunkZ; chunkZ <= lastChunkZ; chunkZ++) {
                for (int chunkX = firstChunkX; chunkX <= lastChunkX; chunkX++) {
                    DenseCaveTileKey key = DenseCaveTileKey.of(
                            chunkX, chunkZ, view, layerY);
                    boolean stale = staleDisplayTiles.contains(key);
                    DenseCaveTile loaded = stale ? null : displayTiles.get(key);
                    boolean exactLoaded = loaded != null;
                    boolean physicalLoaded = displayTiles.containsKey(key);
                    boolean indexedCold = !physicalLoaded
                            && indexedDisplayTiles.contains(key);
                    boolean pending = pendingDisplayLoads.containsKey(key);
                    if (exactLoaded || indexedCold || pending) {
                        hasDenseCacheSource = true;
                    }
                    if (!physicalLoaded && indexedDisplayTiles.contains(key)) {
                        requested.add(key);
                    }
                }
            }
            requestDisplayBatchLoadLocked(requested,
                    MapRequestLane.BACKGROUND);
        }
        CaveArchiveV2Service archive = CaveArchiveV2Service.getInstance();
        boolean hasCanonicalArchive = false;
        for (int pageZ = Math.min(minGlobalPageZ, maxGlobalPageZ);
                pageZ <= Math.max(minGlobalPageZ, maxGlobalPageZ)
                        && !hasCanonicalArchive; pageZ++) {
            for (int pageX = Math.min(minGlobalPageX, maxGlobalPageX);
                    pageX <= Math.max(minGlobalPageX, maxGlobalPageX); pageX++) {
                if (archive.residentAnyMask(pageX, pageZ) != 0
                        || archive.indexedAnyMask(pageX, pageZ) != 0) {
                    hasCanonicalArchive = true;
                    break;
                }
            }
        }
        // Keep old .cvr history only as a cold migration/multiplayer fallback.
        if (!hasDenseCacheSource && !hasCanonicalArchive) {
            requestPageRangeLoad(minGlobalPageX, maxGlobalPageX,
                    minGlobalPageZ, maxGlobalPageZ);
        }
    }

    /**
     * Requests the union of all tile borders needed by a rectangle of 64x64 pages.
     * Adjacent pages overlap by five of their six chunk rows/columns, so checking the
     * rectangle once is substantially cheaper than issuing one 6x6 request per page.
     */
    public void requestPageRangeLoad(int minGlobalPageX, int maxGlobalPageX,
            int minGlobalPageZ, int maxGlobalPageZ) {
        int firstChunkX = (Math.min(minGlobalPageX, maxGlobalPageX) << 2) - 1;
        int lastChunkX = (Math.max(minGlobalPageX, maxGlobalPageX) << 2) + 4;
        int firstChunkZ = (Math.min(minGlobalPageZ, maxGlobalPageZ) << 2) - 1;
        int lastChunkZ = (Math.max(minGlobalPageZ, maxGlobalPageZ) << 2) + 4;
        synchronized (this) {
            for (int chunkZ = firstChunkZ; chunkZ <= lastChunkZ; chunkZ++) {
                for (int chunkX = firstChunkX; chunkX <= lastChunkX; chunkX++) {
                    long key = pack(chunkX, chunkZ);
                    if (!tiles.containsKey(key) && indexedTiles.contains(key)) {
                        requestTileLoadLocked(chunkX, chunkZ, key);
                    }
                }
            }
        }
    }

    public void requestRegionLoad(int regionX, int regionZ) {
        synchronized (this) {
            if (indexRebuildPending) {
                deferredIndexRegionLoads.add(pack(regionX, regionZ));
            }
            requestRegionLoadLocked(regionX, regionZ);
        }
    }

    private void requestRegionLoadLocked(int regionX, int regionZ) {
        int firstChunkX = regionX << 5;
        int firstChunkZ = regionZ << 5;
        for (int dz = 0; dz < 32; dz++) {
            for (int dx = 0; dx < 32; dx++) {
                int chunkX = firstChunkX + dx;
                int chunkZ = firstChunkZ + dz;
                long key = pack(chunkX, chunkZ);
                if (!tiles.containsKey(key) && indexedTiles.contains(key)) {
                    requestTileLoadLocked(chunkX, chunkZ, key);
                }
            }
        }
    }

    public synchronized boolean hasRegionData(int regionX, int regionZ) {
        long regionKey = pack(regionX, regionZ);
        return indexedRegionCounts.get(regionKey) > 0
                || displayRegionChunkCounts.get(regionKey) > 0
                || loadedRawRegionCounts.get(regionKey) > 0
                || (indexRebuildPending
                        && deferredIndexRegionLoads.contains(regionKey));
    }

    public synchronized boolean isRegionLoaded(int regionX, int regionZ) {
        long regionKey = pack(regionX, regionZ);
        return loadedRawRegionCounts.get(regionKey) > 0
                || loadedDisplayRegionCounts.get(regionKey) > 0;
    }

    public synchronized long getRegionRevision(int regionX, int regionZ) {
        return regionRevisions.get(pack(regionX, regionZ));
    }

    /**
     * Exact cave topology revision for one projection of one fixed 64x64 page.
     *
     * <p>Full Cave and every retained Layered band are independent sources. A dense
     * tile written for Layered Y=20 must not invalidate Full Cave or Layered Y=-20.
     * Xaero stores cave start/depth and texture versions per layer for the same
     * reason.</p>
     */
    public synchronized long getPageRevision(CaveView view, int layerY,
            int globalPageX, int globalPageZ) {
        CaveView effectiveView = view == null ? CaveView.FULL : view;
        int normalizedLayer = DenseCaveTile.normalizeLayer(effectiveView, layerY);
        /*
         * PASS167: once all sixteen visible leaves are resolved, exact/CIMG/branch
         * identity must be the stable display product -- not progress inside the
         * background all-height archive. PASS166 mixed CaveArchiveV2.pageRevision()
         * into every exact page revision, so 64/128/192-column compact milestones
         * changed source identity while the very same exact page was being built.
         * The result finished a sub-millisecond build and was then discarded as
         * CAVE_RESULT_STALE, repeatedly. Xaero mutates its writer working tile under
         * a time budget and only exposes a retained map product; intermediate writer
         * progress is not a new renderer source version.
         *
         * Zero means the page is still unresolved, in which case the mixed revision
         * below remains useful for invalidating provisional work. A non-zero settled
         * stamp changes only when a visible leaf/absence/Top-Y source actually changes.
         */
        long settledDisplayStamp = getDisplayPageResolutionStamp(
                effectiveView, layerY, globalPageX, globalPageZ);
        if (settledDisplayStamp != 0L) {
            long stable = settledDisplayStamp
                    ^ ((long) effectiveView.ordinal() << 61)
                    ^ Long.rotateLeft((long) normalizedLayer
                            * 0x94D049BB133111EBL, 7)
                    ^ Long.rotateLeft((long) layerY
                            * 0xC6BC279692B5CC83L, 31);
            return stable == 0L ? 1L : stable;
        }
        /*
         * A mutation counter is not source identity. Dense keys are normalized to a
         * 16-block Layered band, so a historical write for Top-Y=-13 used to leave a
         * non-zero page revision when Top-Y=-1 had no resident source at all. That
         * ghost revision made the exact scheduler wait forever for residency that no
         * index could load. Fingerprint only source that can serve this exact slice.
         */
        long displayRevision = currentDisplayProjectionRevisionLocked(
                effectiveView, normalizedLayer, layerY,
                globalPageX, globalPageZ);
        long liveDisplayRevision = currentLiveDisplayProjectionRevisionLocked(
                effectiveView, normalizedLayer, layerY,
                globalPageX, globalPageZ);
        CaveArchiveV2Service archiveService =
                CaveArchiveV2Service.getInstance();
        long archiveRevision = archiveService.pageRevision(
                globalPageX, globalPageZ);
        /*
         * Source authority is page-local and may be a mix of immutable archive
         * chunks and generated-index absences. PASS86 only considered a page
         * authoritative when all sixteen chunks were archived. A page containing
         * fifteen archived chunks and one proven-absent chunk therefore remained
         * coupled to mutable presentation revisions and invalidated its own Full
         * CIMG/exact jobs repeatedly.
         */
        long authoritativeRevision = projectionAuthorityRevisionLocked(
                effectiveView, normalizedLayer, layerY, globalPageX,
                globalPageZ, archiveRevision, archiveService);
        if (displayRevision == 0L && archiveRevision == 0L
                && authoritativeRevision == 0L) return 0L;
        long mixed = authoritativeRevision != 0L && liveDisplayRevision == 0L
                ? authoritativeRevision
                : displayRevision * 0xD6E8FEB86659FD93L
                        ^ Long.rotateLeft(
                                archiveRevision * 0x9E3779B97F4A7C15L, 23)
                        ^ Long.rotateLeft(
                                liveDisplayRevision * 0xA24BAED4963EE407L, 41);
        mixed ^= ((long) effectiveView.ordinal() << 61)
                ^ Long.rotateLeft((long) normalizedLayer * 0x94D049BB133111EBL, 7)
                ^ Long.rotateLeft((long) layerY * 0xC6BC279692B5CC83L, 31);
        return mixed == 0L ? 1L : mixed;
    }

    private long currentDisplayProjectionRevisionLocked(CaveView view,
            int normalizedLayer, int projectionTopY, int globalPageX,
            int globalPageZ) {
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        long hash = 0xcbf29ce484222325L;
        boolean any = false;
        for (int localZ = 0; localZ < 4; localZ++) {
            for (int localX = 0; localX < 4; localX++) {
                int chunkX = firstChunkX + localX;
                int chunkZ = firstChunkZ + localZ;
                int order = localX * 4 + localZ;
                DenseCaveTileKey key = new DenseCaveTileKey(
                        chunkX, chunkZ, view, normalizedLayer, projectionTopY);
                DenseCaveTile dense = staleDisplayTiles.contains(key)
                        ? null : displayTiles.get(key);
                if (dense == null || (view == CaveView.LAYERED
                        && dense.projectionTopY() != projectionTopY)) {
                    continue;
                }
                long component = dense.revision()
                        ^ ((long) dense.source().rank() << 56)
                        ^ Long.rotateLeft((long) dense.projectionTopY()
                                * 0x94D049BB133111EBL, 13)
                        ^ ((long) (order + 1) * 0xD6E8FEB86659FD93L);
                hash ^= component;
                hash *= 0x100000001b3L;
                any = true;
            }
        }
        if (!any) return 0L;
        hash ^= ((long) view.ordinal() << 61)
                ^ Long.rotateLeft((long) projectionTopY
                        * 0x9E3779B97F4A7C15L, 17);
        return hash == 0L ? 1L : hash;
    }

    /**
     * Current-session LIVE identity must remain visible even when all sixteen
     * retained archive children exist. Otherwise the archive-only fingerprint
     * reuses a cached resolved page and LIVE never gets a chance to revoke it.
     */
    private long currentLiveDisplayProjectionRevisionLocked(CaveView view,
            int normalizedLayer, int projectionTopY, int globalPageX,
            int globalPageZ) {
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        long hash = 0xcbf29ce484222325L;
        boolean any = false;
        for (int localZ = 0; localZ < 4; localZ++) {
            for (int localX = 0; localX < 4; localX++) {
                DenseCaveTileKey key = new DenseCaveTileKey(
                        firstChunkX + localX, firstChunkZ + localZ,
                        view, normalizedLayer, projectionTopY);
                DenseCaveTile dense = staleDisplayTiles.contains(key)
                        ? null : displayTiles.get(key);
                if (dense == null || dense.source() != DenseCaveTile.Source.LIVE
                        || view == CaveView.LAYERED
                                && dense.projectionTopY() != projectionTopY) {
                    continue;
                }
                int order = localX * 4 + localZ;
                hash ^= dense.revision()
                        ^ ((long) (order + 1) * 0x9E3779B97F4A7C15L);
                hash *= 0x100000001b3L;
                any = true;
            }
        }
        return !any ? 0L : hash == 0L ? 1L : hash;
    }

    private long projectionAuthorityRevisionLocked(CaveView view,
            int normalizedLayer, int projectionTopY, int globalPageX,
            int globalPageZ, long archiveRevision,
            CaveArchiveV2Service archiveService) {
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        int archiveMask = archiveService.indexedProjectionMask(
                globalPageX, globalPageZ, view == CaveView.FULL);
        if (archiveMask != 0xFFFF) return 0L;
        long authority = Long.rotateLeft(
                archiveRevision * 0x9E3779B97F4A7C15L, 23);
        return authority == 0L ? 1L : authority;
    }

    /** True when all sixteen central chunks are archived or proven absent. */
    public synchronized boolean hasProjectionAuthorityPage(CaveView view,
            int layerY, int globalPageX, int globalPageZ) {
        CaveView effectiveView = view == null ? CaveView.FULL : view;
        int normalizedLayer = DenseCaveTile.normalizeLayer(effectiveView, layerY);
        CaveArchiveV2Service archiveService = CaveArchiveV2Service.getInstance();
        return projectionAuthorityRevisionLocked(effectiveView, normalizedLayer,
                layerY, globalPageX, globalPageZ,
                archiveService.pageRevision(globalPageX, globalPageZ),
                archiveService) != 0L;
    }

    /**
     * True only for projection source that can be consumed now. Persistent index
     * identity deliberately does not count: an evicted compact archive is not a
     * loaded Xaero-style MapTile and cannot produce pixels until it is rehydrated.
     */
    public synchronized boolean hasAnyProjectionSourcePage(CaveView view,
            int layerY, int globalPageX, int globalPageZ) {
        CaveView effectiveView = view == null ? CaveView.FULL : view;
        int normalizedLayer = DenseCaveTile.normalizeLayer(effectiveView, layerY);
        CaveArchiveV2Service archiveService = CaveArchiveV2Service.getInstance();
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        for (int localZ = 0; localZ < 4; localZ++) {
            for (int localX = 0; localX < 4; localX++) {
                int chunkX = firstChunkX + localX;
                int chunkZ = firstChunkZ + localZ;
                DenseCaveTileKey displayKey = new DenseCaveTileKey(
                        chunkX, chunkZ, effectiveView, normalizedLayer, layerY);
                DenseCaveTile dense = staleDisplayTiles.contains(displayKey)
                        ? null : displayTiles.get(displayKey);
                if (dense != null && (effectiveView == CaveView.FULL
                        || dense.projectionTopY() == layerY)) return true;
                if (archiveService.get(chunkX, chunkZ) != null) return true;
                CaveChunkTile raw = tiles.get(pack(chunkX, chunkZ));
                if (raw != null && raw.hasAnyScannedColumn()) return true;
            }
        }
        return false;
    }

    /**
     * PASS151 foreground cache-first path. Unlike requestIndexedArchivePageLoad(),
     * this does not require the old all-world CompactCaveTile index to have finished.
     * One filesystem-addressable 32x32 SMR2 region is loaded at most once as a cold
     * probe; ingestBatch() installs tile fingerprints/coverage lazily. Subsequent LRU
     * refills use the indexed path below.
     */
    public synchronized boolean requestPersistedArchivePageLoad(CaveView view,
            int layerY, int globalPageX, int globalPageZ, MapRequestLane lane) {
        File target = directory;
        if (target == null) return false;
        CaveArchiveV2Service archiveService = CaveArchiveV2Service.getInstance();
        if (archiveService.indexedAnyMask(globalPageX, globalPageZ) != 0) {
            return requestIndexedArchivePageLoad(view, layerY, globalPageX,
                    globalPageZ, lane);
        }

        int regionX = Math.floorDiv(globalPageX, 8);
        int regionZ = Math.floorDiv(globalPageZ, 8);
        long regionLoadKey = pack(regionX, regionZ);
        if (pendingArchiveRegionLoads.containsKey(regionLoadKey)) return true;
        if (probedArchiveV2Regions.contains(regionLoadKey)) return false;

        MapPersistenceV2Service persistence = MapPersistenceV2Service.getInstance();
        if (!persistence.caveArchiveRegionExists(target, regionX, regionZ)) {
            probedArchiveV2Regions.add(regionLoadKey);
            return false;
        }

        long expectedGeneration = generation.get();
        long worldIdentity = target.getAbsolutePath().hashCode()
                * 0x9E3779B97F4A7C15L;
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.BACKGROUND : lane;
        CompletableFuture<Integer> future = persistence
                .loadCaveArchiveRegionSnapshot(target, worldIdentity, regionX, regionZ)
                .thenApply(tiles -> {
                    synchronized (CaveTileRepository.this) {
                        if (!isGenerationCurrent(expectedGeneration)
                                || directory != target) return 0;
                    }
                    return archiveService.ingestBatch(tiles);
                });
        pendingArchiveRegionLoads.put(regionLoadKey, future);
        MapDebugRecorder.getInstance().event(
                "CAVE_ARCHIVE_REGION_DIRECT_REHYDRATE_REQUEST",
                "region=" + regionX + ',' + regionZ
                        + " trigger_page=" + globalPageX + ',' + globalPageZ
                        + " view=" + (view == null ? CaveView.FULL : view)
                        + " top_y=" + layerY + " lane=" + effectiveLane
                        + " policy=visible_region_file_before_anvil pass=PASS152");
        future.whenComplete((loaded, throwable) -> {
            synchronized (CaveTileRepository.this) {
                if (pendingArchiveRegionLoads.get(regionLoadKey) == future) {
                    pendingArchiveRegionLoads.remove(regionLoadKey);
                }
                if (isGenerationCurrent(expectedGeneration) && directory == target) {
                    probedArchiveV2Regions.add(regionLoadKey);
                }
            }
            if (!isGenerationCurrent(expectedGeneration)) return;
            MapDebugRecorder.getInstance().event(
                    throwable == null ? "CAVE_ARCHIVE_REGION_DIRECT_REHYDRATE_READY"
                            : "CAVE_ARCHIVE_REGION_DIRECT_REHYDRATE_MISS",
                    "region=" + regionX + ',' + regionZ
                            + " trigger_page=" + globalPageX + ',' + globalPageZ
                            + " loaded=" + (loaded == null ? 0 : loaded)
                            + " resident_mask=0x"
                            + Integer.toHexString(archiveService.residentAnyMask(
                                    globalPageX, globalPageZ))
                            + " pass=PASS152");
        });
        return true;
    }

    /**
     * Requests random-access residency for indexed compact cave source. The stable
     * page fingerprint does not change when byte-identical source is reloaded, so
     * this wakes a missing page without creating another stale-revision cascade.
     */
    public synchronized boolean requestIndexedArchivePageLoad(CaveView view,
            int layerY, int globalPageX, int globalPageZ, MapRequestLane lane) {
        File target = directory;
        if (target == null) return false;
        CaveArchiveV2Service archiveService = CaveArchiveV2Service.getInstance();
        int indexedMask = archiveService.indexedAnyMask(globalPageX, globalPageZ);
        int residentMask = archiveService.residentAnyMask(globalPageX, globalPageZ);
        int missingMask = indexedMask & ~residentMask;
        if (missingMask == 0) return false;

        int regionX = Math.floorDiv(globalPageX, 8);
        int regionZ = Math.floorDiv(globalPageZ, 8);
        long regionLoadKey = pack(regionX, regionZ);
        if (pendingArchiveRegionLoads.containsKey(regionLoadKey)) return true;

        long expectedGeneration = generation.get();
        long worldIdentity = target.getAbsolutePath().hashCode()
                * 0x9E3779B97F4A7C15L;
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.BACKGROUND : lane;
        CompletableFuture<Integer> future = MapPersistenceV2Service.getInstance()
                .loadCaveArchiveRegionSnapshot(target, worldIdentity, regionX, regionZ)
                .thenApply(tiles -> {
                    synchronized (CaveTileRepository.this) {
                        if (!isGenerationCurrent(expectedGeneration)
                                || directory != target) return 0;
                    }
                    // Publish the complete persisted native region atomically. A
                    // projection worker cannot observe 100s of intermediate page
                    // fingerprints while this one SMR2 file is replayed.
                    return archiveService.ingestBatch(tiles);
                });
        pendingArchiveRegionLoads.put(regionLoadKey, future);
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (recorder.shouldEmitEvent(
                "CAVE_ARCHIVE_REGION_REHYDRATE_REQUEST:" + regionLoadKey, 250L)) {
            recorder.event("CAVE_ARCHIVE_REGION_REHYDRATE_REQUEST",
                    "region=" + regionX + ',' + regionZ
                            + " trigger_page=" + globalPageX + ',' + globalPageZ
                            + " view=" + (view == null ? CaveView.FULL : view)
                            + " top_y=" + layerY + " lane=" + effectiveLane
                            + " indexed_mask=0x" + Integer.toHexString(indexedMask)
                            + " resident_mask=0x" + Integer.toHexString(residentMask)
                            + " missing_mask=0x" + Integer.toHexString(missingMask)
                            + " policy=one_smr2_read_one_atomic_archive_publish");
        }
        future.whenComplete((loaded, throwable) -> {
            synchronized (CaveTileRepository.this) {
                if (pendingArchiveRegionLoads.get(regionLoadKey) == future) {
                    pendingArchiveRegionLoads.remove(regionLoadKey);
                }
            }
            if (!isGenerationCurrent(expectedGeneration)) return;
            int residentAfter = archiveService.residentAnyMask(
                    globalPageX, globalPageZ);
            String event = throwable == null && loaded != null && loaded > 0
                    ? "CAVE_ARCHIVE_REGION_REHYDRATE_READY"
                    : "CAVE_ARCHIVE_REGION_REHYDRATE_MISS";
            MapDebugRecorder.getInstance().event(event,
                    "region=" + regionX + ',' + regionZ
                            + " trigger_page=" + globalPageX + ',' + globalPageZ
                            + " view=" + (view == null ? CaveView.FULL : view)
                            + " top_y=" + layerY + " lane=" + effectiveLane
                            + " loaded=" + (loaded == null ? 0 : loaded)
                            + " trigger_resident_mask=0x"
                            + Integer.toHexString(residentAfter));
        });
        return true;
    }

    public synchronized boolean hasCompleteProjectionSourcePage(CaveView view,
            int layerY, int globalPageX, int globalPageZ) {
        CaveView effectiveView = view == null ? CaveView.FULL : view;
        int normalizedLayer = DenseCaveTile.normalizeLayer(effectiveView, layerY);
        CaveArchiveV2Service archiveService = CaveArchiveV2Service.getInstance();
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        for (int localZ = 0; localZ < 4; localZ++) {
            for (int localX = 0; localX < 4; localX++) {
                int chunkX = firstChunkX + localX;
                int chunkZ = firstChunkZ + localZ;
                DenseCaveTileKey displayKey = new DenseCaveTileKey(
                        chunkX, chunkZ, effectiveView, normalizedLayer, layerY);
                DenseCaveTile dense = staleDisplayTiles.contains(displayKey)
                        ? null : displayTiles.get(displayKey);
                if (dense != null && (effectiveView == CaveView.FULL
                        || dense.projectionTopY() == layerY)) {
                    continue;
                }

                CompactCaveTile compact = archiveService.get(chunkX, chunkZ);
                if (compact != null && (effectiveView == CaveView.FULL
                        ? archiveService.hasFullProjectionChunk(chunkX, chunkZ)
                        : archiveService.hasCompleteChunk(chunkX, chunkZ))) {
                    continue;
                }

                CaveChunkTile raw = tiles.get(pack(chunkX, chunkZ));
                if (raw != null && raw.isComplete()) continue;
                return false;
            }
        }
        return true;
    }

    /**
     * PASS163 source-authority probe for fullscreen Cave.
     *
     * <p>{@link #hasCompleteProjectionSourcePage(CaveView, int, int, int)} is a
     * presentation/cache readiness predicate. It deliberately accepts exact DISK
     * tiles so a warm map can be shown immediately. That predicate must not also
     * close the saved-world source cursor: a cache-only empty page has no strong
     * empty proof and can otherwise become a permanent scanline blocker.
     *
     * <p>This method only accepts exact LIVE/WORLD_SAVE dense data, a trusted
     * current-epoch vertical archive, or a complete raw archive. DISK exact tiles
     * remain valid visual fallback but do not suppress bounded source verification.
     */
    public synchronized boolean hasCurrentProjectionSourcePage(CaveView view,
            int layerY, int globalPageX, int globalPageZ) {
        CaveView effectiveView = view == null ? CaveView.FULL : view;
        int normalizedLayer = DenseCaveTile.normalizeLayer(effectiveView, layerY);
        CaveArchiveV2Service archiveService = CaveArchiveV2Service.getInstance();
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        for (int localZ = 0; localZ < 4; localZ++) {
            for (int localX = 0; localX < 4; localX++) {
                int chunkX = firstChunkX + localX;
                int chunkZ = firstChunkZ + localZ;
                DenseCaveTileKey displayKey = new DenseCaveTileKey(
                        chunkX, chunkZ, effectiveView, normalizedLayer, layerY);
                DenseCaveTile dense = staleDisplayTiles.contains(displayKey)
                        ? null : displayTiles.get(displayKey);
                if (dense != null
                        && dense.source().rank() >= DenseCaveTile.Source.WORLD_SAVE.rank()
                        && (effectiveView == CaveView.FULL
                                || dense.projectionTopY() == layerY)) {
                    continue;
                }

                CompactCaveTile compact = archiveService.get(chunkX, chunkZ);
                if (compact != null && (effectiveView == CaveView.FULL
                        ? archiveService.hasFullProjectionChunk(chunkX, chunkZ)
                        : archiveService.hasCompleteChunk(chunkX, chunkZ))) {
                    continue;
                }

                CaveChunkTile raw = tiles.get(pack(chunkX, chunkZ));
                if (raw != null && raw.isComplete()) continue;
                return false;
            }
        }
        return true;
    }

    /** Compatibility source revision for raw-archive diagnostics only. */
    public synchronized long getPageRevision(int globalPageX, int globalPageZ) {
        return CaveArchiveV2Service.getInstance()
                .pageRevision(globalPageX, globalPageZ);
    }

    /**
     * Stable fingerprint of the sixteen resolved display leaves that currently
     * satisfy one 64x64 cave page. Raw vertical-archive/region revisions are
     * deliberately excluded: only a visible leaf replacement, an exact Top-Y
     * change or an explicit absence transition changes this stamp.
     *
     * <p>A zero result means at least one central leaf is unresolved. The world-save
     * reader stores a non-zero stamp after an atomic page transaction and must not
     * decode the same page again while this fingerprint is unchanged.</p>
     */
    public synchronized long getDisplayPageResolutionStamp(CaveView view, int layerY,
            int globalPageX, int globalPageZ) {
        int normalized = DenseCaveTile.normalizeLayer(view, layerY);
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        long hash = 0xcbf29ce484222325L;
        for (int localZ = 0; localZ < 4; localZ++) {
            for (int localX = 0; localX < 4; localX++) {
                int chunkX = firstChunkX + localX;
                int chunkZ = firstChunkZ + localZ;
                DenseCaveTileKey key = new DenseCaveTileKey(
                        chunkX, chunkZ, view, normalized, layerY);
                /* A stale dense leaf is not a reason to erase a complete vertical
                 * archive from the page fingerprint. PASS67 returned zero here,
                 * so the world reader re-admitted the same archive-backed page on
                 * every 100 ms pulse, committed retained=16/changed=false, and
                 * never escaped the loop. Prefer a fresh dense projection; when it
                 * is stale or absent, the immutable archive is the authoritative
                 * replacement source. */
                boolean denseStale = staleDisplayTiles.contains(key);
                DenseCaveTile tile = denseStale ? null : displayTiles.get(key);
                if (tile != null && (view == CaveView.FULL
                        || tile.projectionTopY() == layerY)) {
                    hash ^= tile.revision();
                    hash *= 0x100000001b3L;
                    hash ^= ((long) tile.source().rank() << 56)
                            ^ ((long) tile.projectionTopY() << 24)
                            ^ tile.populatedColumns();
                    hash *= 0x100000001b3L;
                    continue;
                }

                var archived = CaveArchiveV2Service.getInstance()
                        .get(chunkX, chunkZ);
                boolean archiveReady = archived != null
                        && (view == CaveView.FULL
                                ? archived.fullProjectionCoverage()
                                : archived.completeCoverage());
                if (archiveReady) {
                    hash ^= 0x6C8E9CF570932BD5L
                            ^ archived.revision()
                            ^ ((long) view.ordinal() << 48)
                            ^ ((long) layerY << 16)
                            ^ ((long) localX << 8) ^ localZ;
                    hash *= 0x100000001b3L;
                    continue;
                }
                return 0L;
            }
        }
        return hash == 0L ? 1L : hash;
    }

    private static int semanticOverlayCount(CompactCaveTile tile, int run) {
        if (tile == null || run < 0) return 0;
        int count = tile.fluidColor(run) == 0 ? 0 : 1;
        if (tile.emissiveColor(run) != 0) count++;
        return Math.min(DenseCaveTile.MAX_OVERLAYS, count);
    }

    private static int semanticOverlayCount(CaveColumnData column, int run) {
        if (column == null || run < 0) return 0;
        int count = column.fluidColor(run) == 0 ? 0 : 1;
        if (column.emissiveColor(run) != 0) count++;
        return Math.min(DenseCaveTile.MAX_OVERLAYS, count);
    }

    private static void copySemanticOverlay(CompactCaveTile tile, int run,
            int layer, int[] colors, byte[] alpha, short[] y, byte[] light,
            byte[] flags, int target) {
        boolean hasFluid = tile.fluidColor(run) != 0;
        boolean hasEmissive = tile.emissiveColor(run) != 0;
        boolean fluidFirst = hasFluid && (!hasEmissive
                || tile.fluidY(run) >= tile.emissiveY(run));
        boolean selectFluid = hasFluid && (layer == 0
                ? fluidFirst : !fluidFirst);
        if (selectFluid) {
            colors[target] = tile.fluidColor(run);
            alpha[target] = tile.fluidAlpha(run);
            y[target] = tile.fluidY(run);
            light[target] = tile.fluidLight(run);
            byte overlayFlags = DenseCaveTile.OVERLAY_FLUID;
            if ((tile.fluidFlags(run)
                    & CaveColumnData.FLUID_FLAG_EMISSIVE) != 0) {
                overlayFlags |= DenseCaveTile.OVERLAY_EMISSIVE;
            }
            flags[target] = overlayFlags;
            return;
        }
        colors[target] = tile.emissiveColor(run);
        alpha[target] = tile.emissiveAlpha(run);
        y[target] = tile.emissiveY(run);
        light[target] = tile.emissiveLight(run);
        flags[target] = DenseCaveTile.OVERLAY_EMISSIVE;
    }

    private static void copySemanticOverlay(CaveColumnData column, int run,
            int layer, int[] colors, byte[] alpha, short[] y, byte[] light,
            byte[] flags, int target) {
        boolean hasFluid = column.fluidColor(run) != 0;
        boolean hasEmissive = column.emissiveColor(run) != 0;
        boolean fluidFirst = hasFluid && (!hasEmissive
                || column.fluidY(run) >= column.emissiveY(run));
        boolean selectFluid = hasFluid && (layer == 0
                ? fluidFirst : !fluidFirst);
        if (selectFluid) {
            colors[target] = column.fluidColor(run);
            alpha[target] = column.fluidAlpha(run);
            y[target] = column.fluidY(run);
            light[target] = column.fluidLight(run);
            byte overlayFlags = DenseCaveTile.OVERLAY_FLUID;
            if ((column.fluidFlags(run)
                    & CaveColumnData.FLUID_FLAG_EMISSIVE) != 0) {
                overlayFlags |= DenseCaveTile.OVERLAY_EMISSIVE;
            }
            flags[target] = overlayFlags;
            return;
        }
        colors[target] = column.emissiveColor(run);
        alpha[target] = column.emissiveAlpha(run);
        y[target] = column.emissiveY(run);
        light[target] = column.emissiveLight(run);
        flags[target] = DenseCaveTile.OVERLAY_EMISSIVE;
    }

    private static boolean compactProjectionComplete(
            CompactCaveTile tile, CaveView view) {
        if (tile == null) return false;
        return view == CaveView.FULL
                ? tile.fullProjectionCoverage() : tile.completeCoverage();
    }

    private static boolean compactColumnKnown(CompactCaveTile tile, int column) {
        if (tile == null || column < 0 || column >= CompactCaveTile.COLUMNS) {
            return false;
        }
        CompactCaveTile.ColumnStatus status = tile.status(column);
        return status != CompactCaveTile.ColumnStatus.UNKNOWN
                && status != CompactCaveTile.ColumnStatus.CORRUPT;
    }

    private static int compactProjectionColor(CompactCaveTile tile, int run) {
        int material = tile.materialId(run);
        if ((tile.flags(run) & CompactCaveTile.FLAG_LEGACY_COLOR) != 0) {
            return material == 0 ? 0 : material | 0xFF000000;
        }
        int hash = material * 0x9E3779B9;
        int red = 48 + ((hash >>> 16) & 0x7F);
        int green = 48 + ((hash >>> 8) & 0x7F);
        int blue = 48 + (hash & 0x7F);
        return 0xFF000000 | (blue << 16) | (green << 8) | red;
    }

    private static byte compactProjectionFlags(CompactCaveTile tile, int run) {
        int compactFlags = tile.flags(run) & 0xFF;
        byte denseFlags = 0;
        boolean semanticFluid = tile.fluidColor(run) != 0;
        boolean semanticEmissive = tile.emissiveColor(run) != 0;
        if (!semanticFluid
                && (compactFlags & CompactCaveTile.FLAG_WATER) != 0) {
            denseFlags |= DenseCaveTile.FLAG_WATER;
        }
        if (!semanticFluid
                && (compactFlags & CompactCaveTile.FLAG_FLUID) != 0) {
            denseFlags |= DenseCaveTile.FLAG_FLUID;
        }
        if (!semanticFluid && !semanticEmissive
                && (compactFlags & CompactCaveTile.FLAG_EMISSIVE) != 0) {
            denseFlags |= DenseCaveTile.FLAG_EMISSIVE;
        }
        return denseFlags;
    }

    private static byte compactProjectionLight(CompactCaveTile tile, int run) {
        return (byte) Math.max(Byte.toUnsignedInt(tile.blockLight(run)),
                Byte.toUnsignedInt(tile.skyLight(run)));
    }

    private static void emitArchiveAuthorityCacheHit(CaveView view, int layerY,
            int globalPageX, int globalPageZ, ResolvedPage cached) {
        if (cached == null || !cached.archiveAuthoritative()) return;
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        String eventKey = "CAVE_ARCHIVE_PROJECTION_AUTHORITY_CACHE:"
                + view + ':' + layerY + ':' + globalPageX + ':' + globalPageZ;
        if (recorder.shouldEmitEvent(eventKey, 500L)) {
            recorder.event("CAVE_ARCHIVE_PROJECTION_AUTHORITY",
                    "page=" + globalPageX + ',' + globalPageZ
                            + " view=" + view + " top_y=" + layerY
                            + " source=resolved_page_cache"
                            + " archive_authoritative=true");
        }
    }

    public ResolvedPage resolvePage(CaveView view, int layerY, Level level,
            int globalPageX, int globalPageZ) {
        int normalizedLayer = DenseCaveTile.normalizeLayer(view, layerY);
        /* Complete pages are immutable for one repository generation/revision.
         * Check this cache before gathering the 6x6 dense/archive window. PASS68
         * performed 36 projection-cache lookups and synchronized archive reads
         * before discovering that the exact same resolved page already existed. */
        CaveArchiveV2Service archiveService = CaveArchiveV2Service.getInstance();
        boolean fullProjectionPageReady = view == CaveView.FULL
                && hasProjectionAuthorityPage(view, layerY,
                        globalPageX, globalPageZ);
        long fastRevision = getPageRevision(
                view, layerY, globalPageX, globalPageZ);
        PageCacheKey fastCacheKey = new PageCacheKey(generation.get(), view,
                normalizedLayer, layerY, globalPageX, globalPageZ, fastRevision);
        synchronized (this) {
            ResolvedPage cached = resolvedPageCache.get(fastCacheKey);
            if (cached != null
                    && (!fullProjectionPageReady || cached.archiveAuthoritative())) {
                telemetry.recordResolvedPageCacheHit();
                emitArchiveAuthorityCacheHit(view, layerY,
                        globalPageX, globalPageZ, cached);
                return cached;
            }
        }

        ProjectionWorkspace workspace = PROJECTION_WORKSPACE.get();
        CaveChunkTile[] archiveTiles = workspace.pageTiles;
        DenseCaveTile[] denseTiles = workspace.displayTiles;
        CompactCaveTile[] compactArchiveTiles = workspace.compactArchiveTiles;
        java.util.Arrays.fill(archiveTiles, null);
        java.util.Arrays.fill(denseTiles, null);

        int firstChunkX = (globalPageX << 2) - 1;
        int firstChunkZ = (globalPageZ << 2) - 1;
        CaveArchiveV2Service.getInstance().fillWindow(firstChunkX, firstChunkZ,
                DISPLAY_TILE_WINDOW, compactArchiveTiles);
        boolean complete = true;
        long revision;
        synchronized (this) {
            for (int dz = 0; dz < DISPLAY_TILE_WINDOW; dz++) {
                for (int dx = 0; dx < DISPLAY_TILE_WINDOW; dx++) {
                    int chunkX = firstChunkX + dx;
                    int chunkZ = firstChunkZ + dz;
                    int tileIndex = dz * DISPLAY_TILE_WINDOW + dx;
                    DenseCaveTileKey displayKey = new DenseCaveTileKey(
                            chunkX, chunkZ, view, normalizedLayer, layerY);
                    DenseCaveTile dense = staleDisplayTiles.contains(displayKey)
                            ? null : displayTiles.get(displayKey);
                    CaveChunkTile archive = tiles.get(pack(chunkX, chunkZ));
                    denseTiles[tileIndex] = dense;
                    archiveTiles[tileIndex] = archive;
                }
            }
            revision = getPageRevision(view, layerY, globalPageX, globalPageZ);
        }

        /*
         * PASS149: page assembly no longer materializes a complete 16x16
         * CaveProjectionTile for every member of the 6x6 source window. A 64x64
         * page owns sixteen central chunks and needs only a one-block border from
         * the twenty neighbours. PASS148 projected all 36 chunks into six fresh
         * 256-entry arrays before copying 4,356 final columns, producing tens of
         * thousands of archive projection cache operations per viewport. Keep the
         * compact archive itself as the immutable source and project each column
         * directly while assembling the page. Exact LIVE/WORLD_SAVE Dense tiles are
         * preferred because they already represent the requested view/Top-Y.
         */
        int centralArchiveAuthorityTiles = 0;
        int archiveAuthorityTiles = 0;
        int denseFallbackTiles = 0;
        for (int dz = 0; dz < DISPLAY_TILE_WINDOW; dz++) {
            for (int dx = 0; dx < DISPLAY_TILE_WINDOW; dx++) {
                int tileIndex = dz * DISPLAY_TILE_WINDOW + dx;
                boolean central = dx >= 1 && dx <= 4 && dz >= 1 && dz <= 4;
                CompactCaveTile compact = compactArchiveTiles[tileIndex];
                boolean compactComplete = compactProjectionComplete(
                        compact, view);
                if (compact != null) {
                    archiveAuthorityTiles++;
                    if (central && compactComplete) {
                        centralArchiveAuthorityTiles++;
                    }
                } else if (denseTiles[tileIndex] != null) {
                    denseFallbackTiles++;
                }
            }
        }
        if (archiveAuthorityTiles > 0) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            String eventKey = "CAVE_ARCHIVE_PROJECTION_AUTHORITY:"
                    + view + ':' + layerY + ':' + globalPageX + ':' + globalPageZ;
            if (recorder.shouldEmitEvent(eventKey, 500L)) {
                recorder.event("CAVE_ARCHIVE_PROJECTION_AUTHORITY",
                        "page=" + globalPageX + ',' + globalPageZ
                                + " view=" + view + " top_y=" + layerY
                                + " archive_tiles=" + archiveAuthorityTiles
                                + " dense_fallback_tiles=" + denseFallbackTiles
                                + " policy=direct_compact_column_no_36_tile_projection");
            }
        }

        complete = true;
        boolean strongEmptyCoverage = true;
        for (int dz = 1; dz <= 4; dz++) {
            for (int dx = 1; dx <= 4; dx++) {
                int tileIndex = dz * DISPLAY_TILE_WINDOW + dx;
                DenseCaveTile dense = denseTiles[tileIndex];
                boolean denseMatches = dense != null && (view == CaveView.FULL
                        || dense.projectionTopY() == layerY);
                boolean tileComplete = denseMatches;
                boolean tileStrong = denseMatches
                        && dense.source() != DenseCaveTile.Source.DISK;
                CompactCaveTile compact = compactArchiveTiles[tileIndex];
                if (compactProjectionComplete(compact, view)) {
                    tileComplete = true;
                    int chunkX = firstChunkX + dx;
                    int chunkZ = firstChunkZ + dz;
                    tileStrong = archiveService.isStrongEmptyProofTrusted(
                            chunkX, chunkZ);
                }
                CaveChunkTile archive = archiveTiles[tileIndex];
                if (archive != null && archive.isComplete()) {
                    tileComplete = true;
                    tileStrong = true;
                }
                if (!tileComplete) complete = false;
                if (!tileStrong) strongEmptyCoverage = false;
            }
        }

        PageCacheKey cacheKey = new PageCacheKey(generation.get(), view,
                normalizedLayer, layerY, globalPageX, globalPageZ, revision);
        /*
         * Revision is part of the cache key, so a partial ResolvedPage is just as
         * immutable as a complete one for that source generation. PASS101 refused
         * to cache partial pages, forcing repeated 64x64 + 66x66 payload allocation
         * whenever minimap/fullscreen asked for the same still-filling leaf. Keep
         * them in the same bounded LRU; a new source revision naturally misses and
         * old partial generations are evicted.
         */
        synchronized (this) {
            ResolvedPage cached = resolvedPageCache.get(cacheKey);
            if (cached != null
                    && (!fullProjectionPageReady || cached.archiveAuthoritative())) {
                telemetry.recordResolvedPageCacheHit();
                emitArchiveAuthorityCacheHit(view, layerY,
                        globalPageX, globalPageZ, cached);
                return cached;
            }
        }
        telemetry.recordResolvedPageCacheMiss();
        long resolveStarted = System.nanoTime();

        int[] pixels = new int[PAGE_SIZE * PAGE_SIZE];
        long[] knownRows = new long[PAGE_SIZE];
        short[] heights = new short[PROJECTION_SIZE * PROJECTION_SIZE];
        short[] topHeights = new short[PAGE_SIZE * PAGE_SIZE];
        byte[] pixelFlags = new byte[PAGE_SIZE * PAGE_SIZE];
        byte[] pixelLight = new byte[PAGE_SIZE * PAGE_SIZE];
        /*
         * Most archive-driven cave pages have no transparent overlay at all. PASS98
         * allocated ~112 KiB of overlay arrays on every resolve miss anyway, then
         * immediately discarded them after styling. Xaero keeps optional texture
         * layers/buffers absent until a tile actually needs them. Follow the same
         * retained-buffer rule: allocate the six overlay arrays lazily on the first
         * real overlay pixel. CavePageStyler already treats null overlay arrays as
         * the no-overlay fast path.
         */
        byte[] overlayCounts = null;
        int[] overlayColors = null;
        byte[] overlayAlpha = null;
        short[] overlayY = null;
        byte[] overlayLight = null;
        byte[] overlayFlags = null;
        java.util.Arrays.fill(heights, FullCaveMapManager.NO_SURFACE);
        java.util.Arrays.fill(topHeights, FullCaveMapManager.NO_SURFACE);

        int borderedOriginX = (globalPageX << 6) - 1;
        int borderedOriginZ = (globalPageZ << 6) - 1;
        int maximum = view == CaveView.FULL ? Integer.MAX_VALUE
                : level == null ? layerY : CaveMode.getScanMaximum(level, layerY);
        int minimum = view == CaveView.FULL ? Integer.MIN_VALUE
                : level == null ? layerY - CaveDisplayProjector.LAYER_DEPTH + 1
                        : CaveMode.getScanMinimum(level, layerY);

        boolean hasContent = false;
        boolean liveAuthorityUsed = false;
        for (int targetZ = 0; targetZ < PROJECTION_SIZE; targetZ++) {
            int blockZ = borderedOriginZ + targetZ;
            int chunkZ = blockZ >> 4;
            int tileZ = chunkZ - firstChunkZ;
            int localZ = blockZ & 15;
            for (int targetX = 0; targetX < PROJECTION_SIZE; targetX++) {
                int blockX = borderedOriginX + targetX;
                int chunkX = blockX >> 4;
                int tileX = chunkX - firstChunkX;
                int localX = blockX & 15;
                int tileIndex = tileZ * DISPLAY_TILE_WINDOW + tileX;
                int color = 0;
                short floor = FullCaveMapManager.NO_SURFACE;
                short openTop = FullCaveMapManager.NO_SURFACE;
                byte flags = 0;
                byte light = 0;
                int denseOverlayCount = 0;
                CompactCaveTile semanticCompact = null;
                CaveColumnData semanticColumn = null;
                int semanticRun = -1;
                boolean known = false;

                DenseCaveTile dense = denseTiles[tileIndex];
                boolean denseExact = dense != null && (view == CaveView.FULL
                        || dense.projectionTopY() == layerY);
                boolean denseRenderable = false;
                int columnIndex = (localZ << 4) | localX;
                CompactCaveTile compact = compactArchiveTiles[tileIndex];
                boolean trustedDenseExact = denseExact
                        && dense.source() != DenseCaveTile.Source.DISK;
                if (trustedDenseExact) {
                    // Exact LIVE/WORLD_SAVE output was built directly for this
                    // requested view/Top-Y. Do not immediately replace it with a
                    // second archive projection of the same 16x16 chunk.
                    denseRenderable = true;
                    liveAuthorityUsed |= dense.source() == DenseCaveTile.Source.LIVE;
                    known = true;
                    color = dense.baseColor(localX, localZ);
                    floor = dense.floorY(localX, localZ);
                    openTop = dense.topY(localX, localZ);
                    flags = dense.flags(localX, localZ);
                    light = dense.light(localX, localZ);
                    denseOverlayCount = dense.overlayCount(localX, localZ);
                } else if (compactColumnKnown(compact, columnIndex)) {
                    // Project only the single column actually consumed by the 66x66
                    // page. This is especially important for the 20 halo chunks,
                    // where PASS148 built 5,120 archive columns to consume ~260.
                    known = true;
                    semanticCompact = compact;
                    semanticRun = view == CaveView.FULL
                            ? compact.firstVisibleFullRun(columnIndex)
                            : compact.firstVisibleLayeredRun(
                                    columnIndex, maximum, minimum);
                    if (semanticRun >= 0) {
                        color = compactProjectionColor(compact, semanticRun);
                        floor = compact.floorY(semanticRun);
                        openTop = compact.topY(semanticRun);
                        flags = compactProjectionFlags(compact, semanticRun);
                        light = compactProjectionLight(compact, semanticRun);
                        denseOverlayCount = semanticOverlayCount(
                                compact, semanticRun);
                    }
                } else if (denseExact) {
                    denseRenderable = true;
                    known = true;
                    color = dense.baseColor(localX, localZ);
                    floor = dense.floorY(localX, localZ);
                    openTop = dense.topY(localX, localZ);
                    flags = dense.flags(localX, localZ);
                    light = dense.light(localX, localZ);
                    denseOverlayCount = dense.overlayCount(localX, localZ);
                } else {
                    /*
                     * Never project a DenseCaveTile from another exact Top-Y just
                     * because it shares the same normalized band. The already-uploaded
                     * page remains the last-good visual during a layer transition; the
                     * new source transaction must stay exact. Feeding old slice pixels
                     * into knownRows makes them indistinguishable from current data and
                     * can permanently splice two cave heights into one page.
                     */
                    CaveChunkTile archive = archiveTiles[tileIndex];
                    CaveColumnData column = archive == null
                            ? null : archive.getColumn(localX, localZ);
                    if (column != null) {
                        known = true;
                        int run = view == CaveView.FULL
                                ? column.firstVisibleFullIndex()
                                : column.firstVisibleLayeredIndex(maximum, minimum);
                        if (run >= 0) {
                            color = column.color(run);
                            floor = column.bottomY(run);
                            openTop = column.topY(run);
                            // Current CVR columns store raw material/tint colours.
                            // Let CavePageStyler apply depth, light and Accurate
                            // finishing exactly as it does for live/Anvil pixels.
                            flags = column.flags(run);
                            light = 15;
                            semanticColumn = column;
                            semanticRun = run;
                            denseOverlayCount = semanticOverlayCount(column, run);
                            if (denseOverlayCount > 0) {
                                flags &= ~(DenseCaveTile.FLAG_WATER
                                        | DenseCaveTile.FLAG_FLUID
                                        | DenseCaveTile.FLAG_EMISSIVE);
                            }
                        }
                    }
                }

                heights[targetZ * PROJECTION_SIZE + targetX] = floor;
                if (targetX > 0 && targetX <= PAGE_SIZE
                        && targetZ > 0 && targetZ <= PAGE_SIZE) {
                    int pageX = targetX - 1;
                    int pageZ = targetZ - 1;
                    int pageIndex = pageZ * PAGE_SIZE + pageX;
                    pixels[pageIndex] = color;
                    topHeights[pageIndex] = openTop;
                    pixelFlags[pageIndex] = flags;
                    pixelLight[pageIndex] = light;
                    if (denseOverlayCount > 0) {
                        if (overlayCounts == null) {
                            int overlayEntries = PAGE_SIZE * PAGE_SIZE
                                    * DenseCaveTile.MAX_OVERLAYS;
                            overlayCounts = new byte[PAGE_SIZE * PAGE_SIZE];
                            overlayColors = new int[overlayEntries];
                            overlayAlpha = new byte[overlayEntries];
                            overlayY = new short[overlayEntries];
                            overlayLight = new byte[overlayEntries];
                            overlayFlags = new byte[overlayEntries];
                            java.util.Arrays.fill(overlayY,
                                    FullCaveMapManager.NO_SURFACE);
                        }
                        int count = Math.min(DenseCaveTile.MAX_OVERLAYS,
                                denseOverlayCount);
                        overlayCounts[pageIndex] = (byte) count;
                        int first = pageIndex * DenseCaveTile.MAX_OVERLAYS;
                        for (int layerIndex = 0; layerIndex < count; layerIndex++) {
                            int entry = first + layerIndex;
                            if (denseRenderable) {
                                overlayColors[entry] = dense.overlayColor(
                                        localX, localZ, layerIndex);
                                overlayAlpha[entry] = dense.overlayAlpha(
                                        localX, localZ, layerIndex);
                                overlayY[entry] = dense.overlayY(
                                        localX, localZ, layerIndex);
                                overlayLight[entry] = dense.overlayLight(
                                        localX, localZ, layerIndex);
                                overlayFlags[entry] = dense.overlayFlags(
                                        localX, localZ, layerIndex);
                            } else if (semanticCompact != null) {
                                copySemanticOverlay(semanticCompact, semanticRun,
                                        layerIndex, overlayColors, overlayAlpha,
                                        overlayY, overlayLight, overlayFlags, entry);
                            } else if (semanticColumn != null) {
                                copySemanticOverlay(semanticColumn, semanticRun,
                                        layerIndex, overlayColors, overlayAlpha,
                                        overlayY, overlayLight, overlayFlags, entry);
                            }
                        }
                    }
                    if (known) knownRows[pageZ] |= 1L << pageX;
                    hasContent |= color != 0;
                }
            }
        }

        if (complete) {
            for (long row : knownRows) {
                if (row != -1L) {
                    complete = false;
                    break;
                }
            }
        }
        CaveEmptyProof emptyProof = complete && strongEmptyCoverage && !hasContent
                ? (liveAuthorityUsed
                        ? CaveEmptyProof.COMPLETE_LIVE_SOURCE_EMPTY
                        : CaveEmptyProof.COMPLETE_SAVED_SOURCE_EMPTY)
                : CaveEmptyProof.NONE;
        ResolvedPage resolved = new ResolvedPage(
                pixels, heights, topHeights, pixelFlags, pixelLight,
                overlayCounts, overlayColors, overlayAlpha, overlayY,
                overlayLight, overlayFlags, knownRows,
                revision, hasContent, complete, emptyProof,
                centralArchiveAuthorityTiles == 16);
        telemetry.recordGraphResolve(System.nanoTime() - resolveStarted);
        if (revision != 0L
                && (!fullProjectionPageReady || resolved.archiveAuthoritative())) {
            synchronized (this) {
                resolvedPageCache.put(cacheKey, resolved);
                while (resolvedPageCache.size() > MAX_RESOLVED_PAGE_CACHE) {
                    var iterator = resolvedPageCache.entrySet().iterator();
                    if (!iterator.hasNext()) break;
                    iterator.next();
                    iterator.remove();
                }
            }
        }
        return resolved;
    }

    public ResolvedRegion resolveRegion(CaveView view, int layerY, Level level,
            int regionX, int regionZ) {
        int[] pixels = new int[512 * 512];
        short[] heights = new short[512 * 512];
        java.util.Arrays.fill(heights, FullCaveMapManager.NO_SURFACE);
        boolean content = false;
        long revision = getRegionRevision(regionX, regionZ);
        int firstPageX = regionX << 3;
        int firstPageZ = regionZ << 3;
        for (int pz = 0; pz < 8; pz++) {
            for (int px = 0; px < 8; px++) {
                ResolvedPage page = resolvePage(view, layerY, level,
                        firstPageX + px, firstPageZ + pz);
                content |= page.hasContent();
                int[] styled = CavePageStyler.style(
                        page.pixels(), page.heights(), page.topHeights(),
                        page.flags(), page.light(), page.overlayCounts(),
                        page.overlayColors(), page.overlayAlpha(), page.overlayY(),
                        page.overlayLight(), page.overlayFlags(), view, layerY);
                for (int z = 0; z < 64; z++) {
                    int target = (pz * 64 + z) * 512 + px * 64;
                    int pixelSource = z * 64;
                    int heightSource = (z + 1) * 66 + 1;
                    System.arraycopy(styled, pixelSource, pixels, target, 64);
                    System.arraycopy(page.heights(), heightSource, heights, target, 64);
                }
            }
        }
        return new ResolvedRegion(pixels, heights, revision, content);
    }

    public void tickSave() {
        synchronized (this) {
            drainDeferredIndexRegionLoadLocked();
            long now = System.nanoTime();
            if (now < nextSaveAdmissionNanos) return;

            SaveAdmission display = scheduleDisplaySaveLocked();
            SaveAdmission raw = scheduleNextRawSaveLocked();
            updateSaveBackoffLocked(display, raw, now);
        }
    }

    /**
     * A compatibility region request may arrive while its dimension index is still
     * being scanned. Admit only one representative tile per tick after the index is
     * ready; this wakes the region without recreating the old dimension-load burst.
     */
    private void drainDeferredIndexRegionLoadLocked() {
        if (indexRebuildPending || deferredIndexRegionLoads.isEmpty()) return;
        var iterator = deferredIndexRegionLoads.iterator();
        long regionKey = iterator.next();
        int regionX = (int) (regionKey >> 32);
        int regionZ = (int) regionKey;
        if (loadedRawRegionCounts.get(regionKey) > 0
                || loadedDisplayRegionCounts.get(regionKey) > 0) {
            iterator.remove();
            return;
        }
        if (indexedRegionCounts.get(regionKey) <= 0
                && displayRegionChunkCounts.get(regionKey) <= 0) {
            iterator.remove();
            return;
        }
        requestOneRegionTileLoadLocked(regionX, regionZ);
    }

    private void requestOneRegionTileLoadLocked(int regionX, int regionZ) {
        int firstChunkX = regionX << 5;
        int firstChunkZ = regionZ << 5;
        for (int dz = 0; dz < 32; dz++) {
            for (int dx = 0; dx < 32; dx++) {
                int chunkX = firstChunkX + dx;
                int chunkZ = firstChunkZ + dz;
                long chunkKey = pack(chunkX, chunkZ);
                if (!tiles.containsKey(chunkKey) && indexedTiles.contains(chunkKey)
                        && !pendingLoads.containsKey(chunkKey)) {
                    requestTileLoadLocked(chunkX, chunkZ, chunkKey);
                    return;
                }
                Set<DenseCaveTileKey> displayKeys = displayKeysByChunk.get(chunkKey);
                if (displayKeys == null) continue;
                for (DenseCaveTileKey displayKey : displayKeys) {
                    if (!displayTiles.containsKey(displayKey)
                            && indexedDisplayTiles.contains(displayKey)
                            && !pendingDisplayLoads.containsKey(displayKey)) {
                        requestDisplayTileLoadLocked(displayKey);
                        return;
                    }
                }
            }
        }
    }

    private SaveAdmission scheduleNextRawSaveLocked() {
        if (distinctFutureCount(pendingSaves) >= MAX_PENDING_RAW_SAVE_BATCHES) {
            return SaveAdmission.NONE;
        }
        CaveChunkTile first = null;
        int regionX = 0;
        int regionZ = 0;
        for (Map.Entry<Long, CaveChunkTile> entry : tiles.entrySet()) {
            CaveChunkTile candidate = entry.getValue();
            int candidateRegionX = candidate.chunkX() >> 5;
            int candidateRegionZ = candidate.chunkZ() >> 5;
            if (!candidate.isDirtyForSave()
                    || pendingSaves.containsKey(entry.getKey())
                    || pendingCompactions.containsKey(pack(
                            candidateRegionX, candidateRegionZ))) continue;
            first = candidate;
            regionX = candidateRegionX;
            regionZ = candidateRegionZ;
            break;
        }
        if (first == null) return SaveAdmission.NONE;

        List<CaveChunkTile> batch = new ArrayList<>(SAVE_BATCH_SIZE);
        batch.add(first);
        for (Map.Entry<Long, CaveChunkTile> entry : tiles.entrySet()) {
            if (batch.size() >= SAVE_BATCH_SIZE) break;
            CaveChunkTile tile = entry.getValue();
            if (tile == first || !tile.isDirtyForSave()
                    || pendingSaves.containsKey(entry.getKey())) continue;
            if ((tile.chunkX() >> 5) == regionX && (tile.chunkZ() >> 5) == regionZ) {
                batch.add(tile);
            }
        }
        return scheduleSaveBatchLocked(batch);
    }

    private void updateSaveBackoffLocked(SaveAdmission display,
            SaveAdmission raw, long now) {
        boolean saturated = display == SaveAdmission.SATURATED
                || raw == SaveAdmission.SATURATED;
        boolean accepted = display == SaveAdmission.ACCEPTED
                || raw == SaveAdmission.ACCEPTED;
        if (saturated) {
            nextSaveAdmissionNanos = now + TimeUnit.MILLISECONDS.toNanos(
                    saveRetryDelayMs);
            saveRetryDelayMs = Math.min(MAX_SAVE_RETRY_MS,
                    Math.max(MIN_SAVE_RETRY_MS, saveRetryDelayMs << 1));
        } else if (accepted) {
            nextSaveAdmissionNanos = 0L;
            saveRetryDelayMs = MIN_SAVE_RETRY_MS;
        }
    }

    public synchronized void clearRuntime(boolean preserveDiskIndex) {
        generation.incrementAndGet();
        tiles.clear();
        loadedScannedTiles.clear();
        loadedRawRegionCounts.clear();
        displayTiles.clear();
        dirtyDisplayTiles.clear();
        staleDisplayTiles.clear();
        pendingDisplayLoads.clear();
        pendingDisplaySaves.clear();
        regionRevisions.clear();
        resolvedPageCache.clear();
        pendingLoads.clear();
        pendingSaves.clear();
        pendingCompactions.clear();
        regionSaveCounts.clear();
        nextSaveAdmissionNanos = 0L;
        saveRetryDelayMs = MIN_SAVE_RETRY_MS;
        resetDenseVariantTrimStatsLocked();
        if (!preserveDiskIndex) {
            deferredIndexRegionLoads.clear();
            clearDiskIndexLocked();
            indexRebuildPending = directory != null && directory.isDirectory();
            scheduleIndexRebuild(directory, generation.get());
        } else if (indexRebuildPending) {
            // The generation bump invalidated the previous background scan.
            scheduleIndexRebuild(directory, generation.get());
        }
        rebuildDisplayChunkIndexLocked();
    }

    public void flushAndClear() {
        File targetDirectory;
        List<CaveChunkTile.Snapshot> snapshots;
        List<DenseCaveTile> displaySnapshots;
        List<CompletableFuture<?>> inFlight;
        List<CompletableFuture<?>> displayInFlight;
        synchronized (this) {
            targetDirectory = directory;
            snapshots = collectDirtySnapshotsLocked();
            displaySnapshots = collectDirtyDisplayTilesLocked();
            inFlight = new ArrayList<>(pendingSaves.values());
            displayInFlight = new ArrayList<>(pendingDisplaySaves.values());

            generation.incrementAndGet();
            tiles.clear();
            loadedScannedTiles.clear();
            loadedRawRegionCounts.clear();
            displayTiles.clear();
            dirtyDisplayTiles.clear();
            staleDisplayTiles.clear();
            pendingDisplayLoads.clear();
            pendingDisplaySaves.clear();
            regionRevisions.clear();
            resolvedPageCache.clear();
            pendingLoads.clear();
            pendingSaves.clear();
            pendingCompactions.clear();
            regionSaveCounts.clear();
            nextSaveAdmissionNanos = 0L;
            saveRetryDelayMs = MIN_SAVE_RETRY_MS;
            resetDenseVariantTrimStatsLocked();
            indexRebuildPending = false;
            deferredIndexRegionLoads.clear();
            rebuildDisplayChunkIndexLocked();
        }
        // Never wait for disk IO on Minecraft's render thread during a portal or
        // teleport. Old saves finish first, then the newest detached snapshots are
        // written to the directory that belonged to the old dimension.
        flushSnapshotsAfter(targetDirectory, snapshots, inFlight);
        flushDisplayTilesAfter(targetDirectory, displaySnapshots, displayInFlight);
    }

    private List<CaveChunkTile.Snapshot> collectDirtySnapshotsLocked() {
        List<CaveChunkTile.Snapshot> snapshots = new ArrayList<>();
        for (CaveChunkTile tile : tiles.values()) {
            if (tile.isDirtyForSave()) snapshots.add(tile.snapshot());
        }
        return snapshots;
    }

    private List<DenseCaveTile> collectDirtyDisplayTilesLocked() {
        List<DenseCaveTile> result = new ArrayList<>();
        for (DenseCaveTileKey key : dirtyDisplayTiles) {
            DenseCaveTile tile = displayTiles.get(key);
            if (tile != null) result.add(tile);
        }
        return result;
    }

    private void flushSnapshotsAfter(File targetDirectory,
            List<CaveChunkTile.Snapshot> snapshots,
            List<CompletableFuture<?>> inFlight) {
        if ((snapshots == null || snapshots.isEmpty())
                && (inFlight == null || inFlight.isEmpty())) return;
        CompletableFuture<?> barrier = inFlight == null || inFlight.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new));
        barrier.handle((ignored, failure) -> null).thenRun(() ->
                MapWorkScheduler.scheduleIo(0L, TimeUnit.MILLISECONDS,
                        MapRequestLane.BACKGROUND,
                        MapWorkScheduler.WorkType.DISK_WRITE, 0,
                        Math.max(12, snapshots == null ? 1 : snapshots.size()),
                        () -> true, () -> {
                            if (snapshots == null || snapshots.isEmpty()) return;
                            try {
                                CaveRegionStore.appendSnapshots(targetDirectory, snapshots);
                            } catch (IOException exception) {
                                LOGGER.warn("Could not asynchronously flush {} cave tiles",
                                        snapshots.size(), exception);
                            }
                        }));
    }

    private void flushDisplayTilesAfter(File targetDirectory,
            List<DenseCaveTile> tilesToSave, List<CompletableFuture<?>> inFlight) {
        if ((tilesToSave == null || tilesToSave.isEmpty())
                && (inFlight == null || inFlight.isEmpty())) return;
        CompletableFuture<?> barrier = inFlight == null || inFlight.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new));
        barrier.handle((ignored, failure) -> null).thenRun(() ->
                MapWorkScheduler.scheduleIo(0L, TimeUnit.MILLISECONDS,
                        MapRequestLane.BACKGROUND,
                        MapWorkScheduler.WorkType.DISK_WRITE, 0,
                        Math.max(12, tilesToSave == null ? 1 : tilesToSave.size()),
                        () -> true, () -> {
                            if (tilesToSave == null || tilesToSave.isEmpty()) return;
                            try {
                                CaveDisplayRegionStore.append(targetDirectory, tilesToSave);
                            } catch (IOException exception) {
                                LOGGER.warn("Could not asynchronously flush {} dense cave tiles",
                                        tilesToSave.size(), exception);
                            }
                        }));
    }

    private synchronized void requestDisplayTileLoadLocked(DenseCaveTileKey key) {
        if (!indexedDisplayTiles.contains(key)
                || pendingDisplayLoads.containsKey(key)) return;
        File sourceDirectory = directory;
        CaveDisplayRegionStore.RecordPointer pointer = displayRecords.get(key);
        long expectedGeneration = generation.get();
        CompletableFuture<DenseCaveTile> future = MapWorkScheduler.tryIoFuture(
                MapRequestLane.FULLSCREEN, MapWorkScheduler.WorkType.DISK_READ,
                MapRequestLane.FULLSCREEN.priorityBase(), 8,
                () -> generation.get() == expectedGeneration
                        && sourceDirectory == directory,
                () -> {
                    try {
                        return CaveDisplayRegionStore.read(sourceDirectory, pointer);
                    } catch (IOException exception) {
                        LOGGER.warn("Could not read dense cave tile {}", key, exception);
                        return null;
                    }
                });
        if (future == null) return;
        pendingDisplayLoads.put(key, future);
        future.whenComplete((tile, throwable) -> {
            synchronized (CaveTileRepository.this) {
                pendingDisplayLoads.remove(key, future);
                if (generation.get() != expectedGeneration
                        || sourceDirectory != directory
                        || !indexedDisplayTiles.contains(key)) return;
                if (throwable != null || tile == null) {
                    indexedDisplayTiles.remove(key);
                    displayRecords.remove(key);
                    unindexDisplayKeyIfUnknownLocked(key);
                    return;
                }
                DenseCaveTile current = displayTiles.get(key);
                boolean identical = current != null && current.source() == tile.source()
                        && (current.revision() >= tile.revision()
                                || current.sameProjectionContent(tile));
                if (!identical && (current == null
                        || current.source().rank() <= tile.source().rank())) {
                    putDisplayTileLocked(key, tile);
                    touchDisplayTileLocked(DenseCaveTileKey.of(tile), tile.revision());
                    trimDisplayTilesLocked();
                }
            }
        });
    }

    /**
     * Replays presentation-ready cave projections in page-bounded disk batches.
     *
     * <p>The store is chunk-record based, not a single GPU-ready 512x512 region
     * image. Expanding one fullscreen page request to every record in a region
     * repeatedly loaded hundreds of 8 KiB tiles, overflowed the 8,192-tile LRU and
     * immediately evicted the same records. The next frame then reopened the same
     * region and blocked Anvil fallback again. CIMG already owns region-wide visual
     * replay; CVD must load only the exact 6x6 tile window requested by the page.</p>
     */
    private synchronized void requestDisplayBatchLoadLocked(
            Set<DenseCaveTileKey> requested, MapRequestLane lane) {
        if (requested == null || requested.isEmpty()) return;

        Map<Long, List<CaveDisplayRegionStore.RecordPointer>> grouped =
                new LinkedHashMap<>();
        for (DenseCaveTileKey key : requested) {
            if (displayTiles.containsKey(key)
                    || pendingDisplayLoads.containsKey(key)) continue;
            CaveDisplayRegionStore.RecordPointer pointer = displayRecords.get(key);
            if (pointer == null) continue;
            grouped.computeIfAbsent(pack(pointer.regionX(), pointer.regionZ()),
                    ignored -> new ArrayList<>()).add(pointer);
        }

        File sourceDirectory = directory;
        long expectedGeneration = generation.get();
        List<Map.Entry<Long, List<CaveDisplayRegionStore.RecordPointer>>> batches =
                new ArrayList<>(grouped.entrySet());
        batches.sort((first, second) -> {
            int byZ = Integer.compare((int) (long) first.getKey(),
                    (int) (long) second.getKey());
            return byZ != 0 ? byZ : Integer.compare(
                    (int) (first.getKey() >> 32),
                    (int) (second.getKey() >> 32));
        });
        for (Map.Entry<Long, List<CaveDisplayRegionStore.RecordPointer>> batch
                : batches) {
            List<CaveDisplayRegionStore.RecordPointer> pointers = batch.getValue();
            if (pointers.isEmpty()) continue;
            int taskCost = Math.min(120, Math.max(12, 8 + pointers.size() / 8));
            CompletableFuture<Map<DenseCaveTileKey, DenseCaveTile>> future =
                    MapWorkScheduler.tryIoFuture(
                            lane, MapWorkScheduler.WorkType.DISK_READ,
                            lane.priorityBase(), taskCost,
                            () -> generation.get() == expectedGeneration
                                    && sourceDirectory == directory,
                            () -> {
                                try {
                                    return CaveDisplayRegionStore.readMany(
                                            sourceDirectory, pointers);
                                } catch (IOException exception) {
                                    LOGGER.warn("Could not batch-read {} dense cave tiles",
                                            pointers.size(), exception);
                                    return null;
                                }
                            });
            if (future == null) continue;
            for (CaveDisplayRegionStore.RecordPointer pointer : pointers) {
                pendingDisplayLoads.put(pointer.key(), future);
            }
            future.whenComplete((loaded, throwable) -> {
                synchronized (CaveTileRepository.this) {
                    for (CaveDisplayRegionStore.RecordPointer pointer : pointers) {
                        pendingDisplayLoads.remove(pointer.key(), future);
                    }
                    if (generation.get() != expectedGeneration
                            || sourceDirectory != directory || throwable != null
                            || loaded == null) return;

                    Map<Long, DenseCaveTile> changedPages = new LinkedHashMap<>();
                    for (CaveDisplayRegionStore.RecordPointer pointer : pointers) {
                        DenseCaveTileKey key = pointer.key();
                        DenseCaveTile tile = loaded.get(key);
                        if (tile == null) {
                            indexedDisplayTiles.remove(key);
                            displayRecords.remove(key);
                            unindexDisplayKeyIfUnknownLocked(key);
                            continue;
                        }
                        DenseCaveTile current = displayTiles.get(key);
                        // Persistent DISK cache is fallback data, not a repair authority.
                        // A stale higher-rank LIVE/WORLD_SAVE tile must be repaired by a
                        // current source transaction rather than silently replaced by an
                        // older disk record. PASS160's stale-rank bypass is therefore
                        // intentionally limited to commitDisplayTile/Page().
                        if (current != null
                                && current.source().rank() > tile.source().rank()) continue;
                        if (current != null && current.source() == tile.source()) {
                            if (current.revision() >= tile.revision()) continue;
                            if (current.sameProjectionContent(tile)) continue;
                        }
                        putDisplayTileLocked(key, tile);
                        staleDisplayTiles.remove(key);
                        changedPages.put(pack(tile.chunkX() >> 2, tile.chunkZ() >> 2), tile);
                    }
                    // One revision/listener notification per 64x64 page is enough;
                    // notifying all sixteen leaves caused exact builds to be
                    // repeatedly superseded while the cache batch was still landing.
                    for (DenseCaveTile tile : changedPages.values()) {
                        touchDisplayTileLocked(DenseCaveTileKey.of(tile), tile.revision());
                    }
                    trimDisplayTilesLocked();
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    if (recorder.shouldEmitEvent("CAVE_DISPLAY_REGION_CACHE_LOAD", 50L)) {
                        CaveDisplayRegionStore.RecordPointer first = pointers.get(0);
                        recorder.event("CAVE_DISPLAY_REGION_CACHE_LOAD",
                                "region=" + first.regionX() + ',' + first.regionZ()
                                        + " scope=page_window"
                                        + " requested=" + pointers.size()
                                        + " loaded=" + loaded.size()
                                        + " pages=" + changedPages.size()
                                        + " lane=" + lane);
                    }
                }
            });
        }
    }

    private synchronized void requestTileLoadLocked(int chunkX, int chunkZ, long key) {
        if (!indexedTiles.contains(key) || pendingLoads.containsKey(key)) return;
        File sourceDirectory = directory;
        CaveRegionStore.RecordPointer regionPointer = regionRecords.get(key);
        long expectedGeneration = generation.get();
        CompletableFuture<CaveChunkTile.Snapshot> future =
                MapWorkScheduler.tryIoFuture(MapRequestLane.FULLSCREEN,
                        MapWorkScheduler.WorkType.DISK_READ,
                        MapRequestLane.FULLSCREEN.priorityBase(), 8,
                        () -> generation.get() == expectedGeneration
                                && sourceDirectory == directory,
                        () -> {
                            try {
                                return readSnapshot(sourceDirectory, chunkX, chunkZ,
                                        regionPointer);
                            } catch (IOException exception) {
                                LOGGER.warn("Could not read cave tile {},{}",
                                        chunkX, chunkZ, exception);
                                return null;
                            }
                        });
        if (future == null) return;
        pendingLoads.put(key, future);
        future.whenComplete((snapshot, throwable) -> {
            synchronized (CaveTileRepository.this) {
                pendingLoads.remove(key, future);
                if (generation.get() != expectedGeneration
                        || sourceDirectory != directory) return;
                if (throwable != null || snapshot == null) {
                    /*
                     * Old cache versions and corrupt files must not remain indexed:
                     * otherwise every visible-page request reopens the same unusable
                     * file forever. A live tile, when present, remains available for
                     * a fresh world scan and will overwrite the old path on save.
                     */
                    removeIndexedTileLocked(chunkX, chunkZ);
                    return;
                }
                telemetry.recordTileLoad();
                CaveChunkTile existing = tiles.get(key);
                boolean changed;
                if (existing == null) {
                    existing = CaveChunkTile.fromSnapshot(snapshot);
                    tiles.put(key, existing);
                    changed = true;
                } else {
                    changed = existing.mergeMissing(snapshot);
                }
                trimLoadedTiles();
                if (changed) touchLocked(chunkX, chunkZ, existing.revision());
            }
        });
    }

    private SaveAdmission scheduleDisplaySaveLocked() {
        if (distinctFutureCount(pendingDisplaySaves)
                >= MAX_PENDING_DISPLAY_SAVE_BATCHES) {
            return SaveAdmission.NONE;
        }
        DenseCaveTileKey firstKey = null;
        DenseCaveTile firstTile = null;
        for (DenseCaveTileKey key : dirtyDisplayTiles) {
            if (pendingDisplaySaves.containsKey(key)) continue;
            DenseCaveTile tile = displayTiles.get(key);
            if (tile == null) continue;
            firstKey = key;
            firstTile = tile;
            break;
        }
        if (firstTile == null) return SaveAdmission.NONE;

        int regionX = firstTile.chunkX() >> 5;
        int regionZ = firstTile.chunkZ() >> 5;
        List<DenseCaveTile> batch = new ArrayList<>(DISPLAY_SAVE_BATCH_SIZE);
        List<DenseCaveTileKey> keys = new ArrayList<>(DISPLAY_SAVE_BATCH_SIZE);
        batch.add(firstTile);
        keys.add(firstKey);
        for (DenseCaveTileKey key : dirtyDisplayTiles) {
            if (batch.size() >= DISPLAY_SAVE_BATCH_SIZE || key.equals(firstKey)
                    || pendingDisplaySaves.containsKey(key)) continue;
            DenseCaveTile tile = displayTiles.get(key);
            if (tile == null || (tile.chunkX() >> 5) != regionX
                    || (tile.chunkZ() >> 5) != regionZ) continue;
            batch.add(tile);
            keys.add(key);
        }

        File targetDirectory = directory;
        long expectedGeneration = generation.get();
        CompletableFuture<Map<DenseCaveTileKey,
                CaveDisplayRegionStore.RecordPointer>> future =
                MapWorkScheduler.tryIoFuture(MapRequestLane.BACKGROUND,
                        MapWorkScheduler.WorkType.DISK_WRITE, 0,
                        Math.max(12, batch.size()),
                        () -> generation.get() == expectedGeneration
                                && targetDirectory == directory,
                        () -> {
                            try {
                                return CaveDisplayRegionStore.append(
                                        targetDirectory, batch);
                            } catch (IOException exception) {
                                throw new RuntimeException(exception);
                            }
                        });
        if (future == null) return SaveAdmission.SATURATED;
        for (DenseCaveTileKey key : keys) pendingDisplaySaves.put(key, future);
        future.whenComplete((records, throwable) -> {
            synchronized (CaveTileRepository.this) {
                for (DenseCaveTileKey key : keys) {
                    pendingDisplaySaves.remove(key, future);
                }
                if (throwable != null) {
                    if (!(throwable instanceof java.util.concurrent.CancellationException)) {
                        LOGGER.warn("Could not save dense cave tile batch of {}",
                                batch.size(), throwable);
                    }
                    return;
                }
                if (generation.get() != expectedGeneration
                        || targetDirectory != directory) return;
                for (int i = 0; i < keys.size(); i++) {
                    DenseCaveTileKey key = keys.get(i);
                    DenseCaveTile saved = batch.get(i);
                    DenseCaveTile current = displayTiles.get(key);
                    boolean stillCurrent = current != null
                            && current.revision() == saved.revision();
                    if (stillCurrent) dirtyDisplayTiles.remove(key);
                    CaveDisplayRegionStore.RecordPointer pointer = records == null
                            ? null : records.get(key);
                    if (stillCurrent && pointer != null) {
                        indexedDisplayTiles.add(key);
                        displayRecords.put(key, pointer);
                        indexDisplayKeyLocked(key);
                    }
                    trimExactBandVariantsForChunkLocked(key);
                }
                trimDisplayTilesLocked();
            }
        });
        return SaveAdmission.ACCEPTED;
    }

    private SaveAdmission scheduleSaveLocked(CaveChunkTile tile) {
        return scheduleSaveBatchLocked(List.of(tile));
    }

    private SaveAdmission scheduleSaveBatchLocked(List<CaveChunkTile> batch) {
        if (batch == null || batch.isEmpty()) return SaveAdmission.NONE;
        if (distinctFutureCount(pendingSaves) >= MAX_PENDING_RAW_SAVE_BATCHES) {
            return SaveAdmission.NONE;
        }
        int batchRegionX = batch.get(0).chunkX() >> 5;
        int batchRegionZ = batch.get(0).chunkZ() >> 5;
        if (pendingCompactions.containsKey(pack(batchRegionX, batchRegionZ))) {
            return SaveAdmission.NONE;
        }
        List<CaveChunkTile.Snapshot> snapshots = new ArrayList<>(batch.size());
        List<Long> keys = new ArrayList<>(batch.size());
        for (CaveChunkTile tile : batch) {
            long key = pack(tile.chunkX(), tile.chunkZ());
            if (pendingSaves.containsKey(key)) continue;
            snapshots.add(tile.snapshot());
            keys.add(key);
        }
        if (snapshots.isEmpty()) return SaveAdmission.NONE;

        File targetDirectory = directory;
        long expectedGeneration = generation.get();
        CompletableFuture<Map<Long, CaveRegionStore.RecordPointer>> future =
                MapWorkScheduler.tryIoFuture(MapRequestLane.BACKGROUND,
                        MapWorkScheduler.WorkType.DISK_WRITE, 0,
                        Math.max(12, snapshots.size()),
                        () -> generation.get() == expectedGeneration
                                && targetDirectory == directory,
                        () -> {
                            try {
                                return CaveRegionStore.appendSnapshots(
                                        targetDirectory, snapshots);
                            } catch (IOException exception) {
                                throw new RuntimeException(exception);
                            }
                        });
        if (future == null) return SaveAdmission.SATURATED;
        for (Long key : keys) pendingSaves.put(key, future);

        future.whenComplete((recordPointers, throwable) -> {
            synchronized (CaveTileRepository.this) {
                for (Long key : keys) pendingSaves.remove(key, future);
                if (throwable != null) {
                    if (!(throwable instanceof java.util.concurrent.CancellationException)) {
                        LOGGER.warn("Could not save cave tile batch of {}",
                                snapshots.size(), throwable);
                    }
                    return;
                }
                if (generation.get() != expectedGeneration
                        || targetDirectory != directory) return;

                int regionX = snapshots.get(0).chunkX() >> 5;
                int regionZ = snapshots.get(0).chunkZ() >> 5;
                for (CaveChunkTile.Snapshot snapshot : snapshots) {
                    long key = pack(snapshot.chunkX(), snapshot.chunkZ());
                    CaveChunkTile current = tiles.get(key);
                    if (current != null) current.markSaved(snapshot.revision());
                    CaveRegionStore.RecordPointer pointer = recordPointers == null
                            ? null : recordPointers.get(key);
                    if (pointer != null) regionRecords.put(key, pointer);
                    indexTileLocked(snapshot.chunkX(), snapshot.chunkZ());
                }
                noteRegionSaveLocked(regionX, regionZ, snapshots.size(),
                        targetDirectory, expectedGeneration);
                trimLoadedTiles();
            }
        });
        return SaveAdmission.ACCEPTED;
    }

    private void noteRegionSaveLocked(int regionX, int regionZ, int savedCount,
            File targetDirectory, long expectedGeneration) {
        long regionKey = pack(regionX, regionZ);
        int count = regionSaveCounts.get(regionKey)
                + Math.max(1, savedCount);
        if (count < REGION_COMPACTION_SAVE_INTERVAL
                || pendingCompactions.containsKey(regionKey)
                || hasPendingSavesInRegionLocked(regionX, regionZ)
                || pendingCompactions.size() >= MAX_PENDING_COMPACTIONS) {
            regionSaveCounts.put(regionKey, count);
            return;
        }

        CompletableFuture<CaveRegionStore.CompactionResult> future =
                MapWorkScheduler.tryIoFuture(MapRequestLane.BACKGROUND,
                        MapWorkScheduler.WorkType.CACHE_MAINTENANCE, -100,
                        24, () -> generation.get() == expectedGeneration
                                && targetDirectory == directory,
                        () -> {
                            try {
                                return CaveRegionStore.compactRegionIfNeeded(
                                        targetDirectory, regionX, regionZ);
                            } catch (IOException exception) {
                                throw new RuntimeException(exception);
                            }
                        });
        if (future == null) {
            // Compaction is optional maintenance. Preserve the accumulated count
            // and try again only after foreground reads/writes have drained.
            regionSaveCounts.put(regionKey, count);
            return;
        }
        regionSaveCounts.put(regionKey, 0);
        pendingCompactions.put(regionKey, future);
        future.whenComplete((result, throwable) -> {
            synchronized (CaveTileRepository.this) {
                pendingCompactions.remove(regionKey, future);
                if (throwable != null) {
                    if (!(throwable instanceof java.util.concurrent.CancellationException)) {
                        LOGGER.warn("Could not compact cave region {},{}",
                                regionX, regionZ, throwable);
                    }
                    if (regionSaveCounts.get(regionKey)
                            < REGION_COMPACTION_SAVE_INTERVAL) {
                        regionSaveCounts.put(regionKey,
                                REGION_COMPACTION_SAVE_INTERVAL);
                    }
                    return;
                }
                if (result == null || generation.get() != expectedGeneration
                        || targetDirectory != directory) return;

                regionRecords.entrySet().removeIf(entry -> {
                    CaveRegionStore.RecordPointer pointer = entry.getValue();
                    return pointer.regionX() == regionX && pointer.regionZ() == regionZ;
                });
                regionRecords.putAll(result.records());
                for (CaveRegionStore.RecordPointer pointer : result.records().values()) {
                    indexTileLocked(pointer.chunkX(), pointer.chunkZ());
                }
                telemetry.recordRegionCompaction(result.reclaimedBytes());
            }
        });
    }

    private boolean hasPendingSavesInRegionLocked(int regionX, int regionZ) {
        for (Long key : pendingSaves.keySet()) {
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) (long) key;
            if ((chunkX >> 5) == regionX && (chunkZ >> 5) == regionZ) return true;
        }
        return false;
    }

    /** Publishes one projection-page transaction with one source increment. */
    private void touchDisplayPageLocked(CaveView view, int layerY,
            int globalPageX, int globalPageZ,
            java.util.Map<Long, Long> changedChunks) {
        int regionX = Math.floorDiv(globalPageX, 8);
        int regionZ = Math.floorDiv(globalPageZ, 8);
        regionRevisions.addTo(pack(regionX, regionZ), 1L);
        if (changedChunks == null) return;
        for (java.util.Map.Entry<Long, Long> entry : changedChunks.entrySet()) {
            int chunkX = (int) (entry.getKey() >> 32);
            int chunkZ = (int) (long) entry.getKey();
            refreshLoadedRawIndexLocked(chunkX, chunkZ);
            for (TileListener listener : listeners) {
                try {
                    listener.onTileChanged(chunkX, chunkZ, entry.getValue());
                } catch (Throwable throwable) {
                    LOGGER.warn("Cave tile listener failed", throwable);
                }
            }
        }
    }

    private void touch(int chunkX, int chunkZ, long tileRevision) {
        synchronized (this) {
            touchLocked(chunkX, chunkZ, tileRevision);
        }
    }

    /**
     * Raw vertical-archive mutation. It advances the legacy region graph, but it
     * must not invalidate an already coherent dense display page. PASS63 shared
     * one page revision between raw CVR ingestion and styled display pixels; every
     * background archive merge therefore discarded exact builds whose pixels had
     * not changed.
     */
    private void touchLocked(int chunkX, int chunkZ, long tileRevision) {
        refreshLoadedRawIndexLocked(chunkX, chunkZ);
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        regionRevisions.addTo(pack(regionX, regionZ), 1L);
        notifyTileListenersLocked(chunkX, chunkZ, tileRevision);
    }

    /** One dense 16x16 display leaf changed; advance only its projection page. */
    private void touchDisplayTileLocked(DenseCaveTileKey key, long tileRevision) {
        if (key == null) return;
        int regionX = key.chunkX() >> 5;
        int regionZ = key.chunkZ() >> 5;
        regionRevisions.addTo(pack(regionX, regionZ), 1L);
        notifyTileListenersLocked(key.chunkX(), key.chunkZ(), tileRevision);
    }

    private void notifyTileListenersLocked(int chunkX, int chunkZ,
            long tileRevision) {
        for (TileListener listener : listeners) {
            try {
                listener.onTileChanged(chunkX, chunkZ, tileRevision);
            } catch (Throwable throwable) {
                LOGGER.warn("Cave tile listener failed", throwable);
            }
        }
    }

    private void trimDisplayTilesLocked() {
        while (displayTiles.size() > MAX_DISPLAY_TILES) {
            DenseCaveTileKey removable = null;
            for (Map.Entry<DenseCaveTileKey, DenseCaveTile> entry : displayTiles.entrySet()) {
                DenseCaveTileKey key = entry.getKey();
                if (!dirtyDisplayTiles.contains(key)
                        && !pendingDisplaySaves.containsKey(key)
                        && !pendingDisplayLoads.containsKey(key)) {
                    removable = key;
                    break;
                }
            }
            if (removable == null) {
                scheduleDisplaySaveLocked();
                break;
            }
            removeDisplayTileLocked(removable);
            unindexDisplayKeyIfUnknownLocked(removable);
        }
    }

    private void trimLoadedTiles() {
        while (tiles.size() > MAX_LOADED_TILES) {
            Long removableKey = null;
            CaveChunkTile dirtyToSave = null;
            long dirtyKey = 0L;

            for (Map.Entry<Long, CaveChunkTile> entry : tiles.entrySet()) {
                CaveChunkTile tile = entry.getValue();
                if (!tile.needsScanWork() && !tile.isDirtyForSave()
                        && !pendingSaves.containsKey(entry.getKey())) {
                    removableKey = entry.getKey();
                    break;
                }
                if (dirtyToSave == null && tile.isDirtyForSave()
                        && !pendingSaves.containsKey(entry.getKey())
                        && !pendingCompactions.containsKey(pack(
                                tile.chunkX() >> 5, tile.chunkZ() >> 5))) {
                    dirtyToSave = tile;
                    dirtyKey = entry.getKey();
                }
            }

            if (removableKey != null) {
                tiles.remove(removableKey);
                unindexLoadedRawTileLocked(removableKey);
                continue;
            }
            if (dirtyToSave != null && !pendingSaves.containsKey(dirtyKey)) {
                scheduleSaveLocked(dirtyToSave);
            }
            // Keep dirty tiles resident until their snapshot is safely persisted.
            break;
        }
    }

    private void clearDiskIndexLocked() {
        indexedTiles.clear();
        indexedRegionCounts.clear();
        regionRecords.clear();
        indexedDisplayTiles.clear();
        displayRecords.clear();
        displayKeysByChunk.clear();
        displayRegionChunkCounts.clear();
        loadedDisplayRegionCounts.clear();
        regionSaveCounts.clear();
    }

    /**
     * Region files can be hundreds of MiB. Rebuilding both packed indexes while
     * changing dimensions used to block the Minecraft client thread for several
     * seconds. Keep one IO worker reserved for visible reads and perform this
     * maintenance on the scheduler's weak/background permit instead.
     */
    private void scheduleIndexRebuild(File source, long expectedGeneration) {
        if (source == null || !source.isDirectory()) return;
        MapWorkScheduler.scheduleIo(0L, TimeUnit.MILLISECONDS,
                MapRequestLane.BACKGROUND,
                MapWorkScheduler.WorkType.CACHE_MAINTENANCE, -200, 160,
                () -> source.equals(directory)
                        && generation.get() == expectedGeneration,
                () -> {
                    long startedNanos = System.nanoTime();
                    RepositoryIndex rebuilt;
                    try {
                        rebuilt = loadIndex(source);
                    } catch (Throwable failure) {
                        synchronized (this) {
                            if (source.equals(directory)
                                    && generation.get() == expectedGeneration) {
                                indexRebuildPending = false;
                            }
                        }
                        LOGGER.warn("Could not asynchronously rebuild cave index {}",
                                source, failure);
                        MapDebugRecorder.getInstance().event("CAVE_INDEX_FAILED",
                                "directory=" + source.getName());
                        return;
                    }
                    int deferredRegions;
                    synchronized (this) {
                        if (!source.equals(directory)
                                || generation.get() != expectedGeneration) return;

                        // A live save may finish while the scan is running. Its
                        // pointer is newer, so never overwrite it with this snapshot.
                        for (Map.Entry<Long, CaveRegionStore.RecordPointer> entry
                                : rebuilt.rawRecords().entrySet()) {
                            regionRecords.putIfAbsent(entry.getKey(), entry.getValue());
                            CaveRegionStore.RecordPointer pointer = entry.getValue();
                            indexTileLocked(pointer.chunkX(), pointer.chunkZ());
                        }
                        for (Map.Entry<DenseCaveTileKey,
                                CaveDisplayRegionStore.RecordPointer> entry
                                : rebuilt.displayRecords().entrySet()) {
                            displayRecords.putIfAbsent(entry.getKey(), entry.getValue());
                            if (indexedDisplayTiles.add(entry.getKey())) {
                                indexDisplayKeyLocked(entry.getKey());
                            }
                        }
                        for (long packed : rebuilt.legacyTiles()) {
                            indexTileLocked((int) (packed >> 32), (int) packed);
                        }
                        indexRebuildPending = false;
                        deferredRegions = deferredIndexRegionLoads.size();
                    }
                    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - startedNanos);
                    MapDebugRecorder.getInstance().event("CAVE_INDEX_READY",
                            "raw=" + rebuilt.rawRecords().size()
                                    + " display=" + rebuilt.displayRecords().size()
                                    + " legacy=" + rebuilt.legacyTiles().size()
                                    + " deferred_regions=" + deferredRegions
                                    + " elapsed_ms=" + elapsedMs);
                });
    }

    private static RepositoryIndex loadIndex(File source) {
        Map<Long, CaveRegionStore.RecordPointer> rawRecords =
                CaveRegionStore.rebuildIndex(source);
        Map<DenseCaveTileKey, CaveDisplayRegionStore.RecordPointer> displayRecords =
                CaveDisplayRegionStore.rebuildIndex(source);
        Set<Long> legacyTiles = new HashSet<>();

        // Prefer the packed random-access region container. The latest complete
        // record for every tile is indexed by byte offset. Version-3 per-chunk
        // files remain a migration fallback until their next successful save.
        File[] files = source.listFiles((dir, name) -> name != null
                && name.matches("c\\.-?\\d+\\.-?\\d+\\.cvt"));
        if (files != null) {
            for (File file : files) {
                String[] parts = file.getName().split("\\.");
                if (parts.length != 4) continue;
                try {
                    legacyTiles.add(pack(Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2])));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new RepositoryIndex(rawRecords, displayRecords, legacyTiles);
    }

    private void indexTileLocked(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        if (!indexedTiles.add(key)) return;
        indexedRegionCounts.addTo(pack(chunkX >> 5, chunkZ >> 5), 1);
    }

    private void indexDisplayKeyLocked(DenseCaveTileKey key) {
        long chunkKey = pack(key.chunkX(), key.chunkZ());
        Set<DenseCaveTileKey> keys = displayKeysByChunk.get(chunkKey);
        if (keys == null) {
            keys = new LinkedHashSet<>();
            displayKeysByChunk.put(chunkKey, keys);
            incrementRegionCount(displayRegionChunkCounts,
                    pack(key.chunkX() >> 5, key.chunkZ() >> 5));
        }
        // Replacing an exact projection makes it the preferred local variant
        // without walking or perturbing the global access-ordered map.
        keys.remove(key);
        keys.add(key);
    }

    private void unindexDisplayKeyIfUnknownLocked(DenseCaveTileKey key) {
        if (displayTiles.containsKey(key) || indexedDisplayTiles.contains(key)) return;
        long chunkKey = pack(key.chunkX(), key.chunkZ());
        Set<DenseCaveTileKey> keys = displayKeysByChunk.get(chunkKey);
        if (keys == null) return;
        keys.remove(key);
        if (keys.isEmpty()) {
            displayKeysByChunk.remove(chunkKey);
            decrementRegionCount(displayRegionChunkCounts,
                    pack(key.chunkX() >> 5, key.chunkZ() >> 5));
        }
    }

    private void rebuildDisplayChunkIndexLocked() {
        displayKeysByChunk.clear();
        displayRegionChunkCounts.clear();
        loadedDisplayRegionCounts.clear();
        for (DenseCaveTileKey key : indexedDisplayTiles) indexDisplayKeyLocked(key);
        for (DenseCaveTileKey key : displayTiles.keySet()) {
            indexDisplayKeyLocked(key);
            incrementRegionCount(loadedDisplayRegionCounts,
                    pack(key.chunkX() >> 5, key.chunkZ() >> 5));
        }
    }

    private void putDisplayTileLocked(DenseCaveTileKey key, DenseCaveTile tile) {
        DenseCaveTile previous = displayTiles.put(key, tile);
        if (previous == null) {
            incrementRegionCount(loadedDisplayRegionCounts,
                    pack(key.chunkX() >> 5, key.chunkZ() >> 5));
        }
        indexDisplayKeyLocked(key);
        trimExactBandVariantsForChunkLocked(key);
    }

    private void trimExactBandVariantsForChunkLocked(DenseCaveTileKey preferred) {
        if (preferred == null || preferred.view() == CaveView.FULL) return;
        long startedNanos = System.nanoTime();
        Set<DenseCaveTileKey> chunkKeys = displayKeysByChunk.get(
                pack(preferred.chunkX(), preferred.chunkZ()));
        int scanned = 0;
        int count = 0;
        DenseCaveTileKey firstCandidate = null;
        if (chunkKeys != null) {
            for (DenseCaveTileKey key : chunkKeys) {
                scanned++;
                if (!preferred.sameBand(key) || !displayTiles.containsKey(key)) continue;
                count++;
                if (!preferred.equals(key)
                        && !pendingDisplaySaves.containsKey(key)
                        && !pendingDisplayLoads.containsKey(key)
                        && firstCandidate == null) firstCandidate = key;
            }
        }
        int residentVariants = count;
        int removed = 0;
        int excess = count - MAX_EXACT_VARIANTS_PER_BAND;
        if (excess == 1 && firstCandidate != null) {
            evictExactVariantLocked(firstCandidate);
            removed = 1;
        } else if (excess > 1 && firstCandidate != null) {
            ArrayList<DenseCaveTileKey> removals = new ArrayList<>(excess);
            for (DenseCaveTileKey key : chunkKeys) {
                scanned++;
                if (removals.size() >= excess) break;
                if (!preferred.sameBand(key) || preferred.equals(key)
                        || !displayTiles.containsKey(key)
                        || pendingDisplaySaves.containsKey(key)
                        || pendingDisplayLoads.containsKey(key)) continue;
                removals.add(key);
            }
            for (DenseCaveTileKey key : removals) {
                evictExactVariantLocked(key);
                removed++;
            }
        }
        recordDenseVariantTrimLocked(System.nanoTime() - startedNanos,
                scanned, residentVariants, removed);
    }

    private void evictExactVariantLocked(DenseCaveTileKey key) {
        removeDisplayTileLocked(key);
        boolean unsaved = dirtyDisplayTiles.remove(key);
        staleDisplayTiles.remove(key);
        if (unsaved) {
            indexedDisplayTiles.remove(key);
            displayRecords.remove(key);
        }
        unindexDisplayKeyIfUnknownLocked(key);
    }

    private void recordDenseVariantTrimLocked(long elapsedNanos, int scanned,
            int residentVariants, int removed) {
        denseVariantTrimCalls++;
        denseVariantTrimKeysScanned += scanned;
        denseVariantTrimTotalNanos += elapsedNanos;
        denseVariantTrimMaxNanos = Math.max(denseVariantTrimMaxNanos, elapsedNanos);
        if (DEBUG_TELEMETRY_DISABLED) return;
        long now = System.nanoTime();
        if (now < denseVariantTrimNextTelemetryNanos) return;
        denseVariantTrimNextTelemetryNanos = now + TimeUnit.SECONDS.toNanos(1L);
        MapDebugRecorder.getInstance().event("CAVE_DENSE_VARIANT_TRIM_SUMMARY",
                "calls=" + denseVariantTrimCalls
                        + " keys_scanned=" + denseVariantTrimKeysScanned
                        + " total_us=" + denseVariantTrimTotalNanos / 1_000L
                        + " max_us=" + denseVariantTrimMaxNanos / 1_000L
                        + " last_keys=" + scanned
                        + " last_variants=" + residentVariants
                        + " last_removed=" + removed);
    }

    private void resetDenseVariantTrimStatsLocked() {
        denseVariantTrimCalls = 0L;
        denseVariantTrimKeysScanned = 0L;
        denseVariantTrimTotalNanos = 0L;
        denseVariantTrimMaxNanos = 0L;
        denseVariantTrimNextTelemetryNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(1L);
    }

    private DenseCaveTile removeDisplayTileLocked(DenseCaveTileKey key) {
        DenseCaveTile removed = displayTiles.remove(key);
        if (removed != null) {
            decrementRegionCount(loadedDisplayRegionCounts,
                    pack(key.chunkX() >> 5, key.chunkZ() >> 5));
        }
        return removed;
    }

    private Set<DenseCaveTileKey> removeDisplayChunkIndexLocked(
            int chunkX, int chunkZ) {
        Set<DenseCaveTileKey> removed = displayKeysByChunk.remove(
                pack(chunkX, chunkZ));
        if (removed != null && !removed.isEmpty()) {
            decrementRegionCount(displayRegionChunkCounts,
                    pack(chunkX >> 5, chunkZ >> 5));
        }
        return removed;
    }

    private void refreshLoadedRawIndexLocked(int chunkX, int chunkZ) {
        long tileKey = pack(chunkX, chunkZ);
        CaveChunkTile tile = tiles.get(tileKey);
        boolean present = tile != null && tile.hasAnyScannedColumn();
        if (present) {
            if (loadedScannedTiles.add(tileKey)) {
                incrementRegionCount(loadedRawRegionCounts,
                        pack(chunkX >> 5, chunkZ >> 5));
            }
        } else if (loadedScannedTiles.remove(tileKey)) {
            decrementRegionCount(loadedRawRegionCounts,
                    pack(chunkX >> 5, chunkZ >> 5));
        }
    }

    private void unindexLoadedRawTileLocked(long tileKey) {
        if (!loadedScannedTiles.remove(tileKey)) return;
        int chunkX = (int) (tileKey >> 32);
        int chunkZ = (int) tileKey;
        decrementRegionCount(loadedRawRegionCounts,
                pack(chunkX >> 5, chunkZ >> 5));
    }

    private static void incrementRegionCount(Long2IntOpenHashMap counts,
            long regionKey) {
        counts.addTo(regionKey, 1);
    }

    private static void decrementRegionCount(Long2IntOpenHashMap counts,
            long regionKey) {
        int remaining = counts.get(regionKey) - 1;
        if (remaining <= 0) counts.remove(regionKey);
        else counts.put(regionKey, remaining);
    }

    private void removeIndexedTileLocked(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        regionRecords.remove(key);
        if (!indexedTiles.remove(key)) return;
        long regionKey = pack(chunkX >> 5, chunkZ >> 5);
        int remaining = indexedRegionCounts.get(regionKey) - 1;
        if (remaining <= 0) indexedRegionCounts.remove(regionKey);
        else indexedRegionCounts.put(regionKey, remaining);
    }

    private static CaveChunkTile.Snapshot readSnapshot(File directory,
            int chunkX, int chunkZ, CaveRegionStore.RecordPointer regionPointer)
            throws IOException {
        CaveChunkTile.Snapshot packed = CaveRegionStore.readSnapshot(directory, regionPointer);
        if (packed != null) return packed;
        return CaveRegionStore.readLegacySnapshot(directory, chunkX, chunkZ);
    }

    private static int distinctFutureCount(Map<?, CompletableFuture<?>> futures) {
        if (futures == null || futures.isEmpty()) return 0;
        return new HashSet<>(futures.values()).size();
    }

    private enum SaveAdmission {
        NONE,
        ACCEPTED,
        SATURATED
    }

    public static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    public record ResolvedPage(int[] pixels, short[] heights,
            short[] topHeights, byte[] flags, byte[] light,
            byte[] overlayCounts, int[] overlayColors, byte[] overlayAlpha,
            short[] overlayY, byte[] overlayLight, byte[] overlayFlags,
            long[] knownRows, long revision, boolean hasContent,
            boolean complete, CaveEmptyProof emptyProof,
            boolean archiveAuthoritative) {
        public ResolvedPage {
            emptyProof = emptyProof == null ? CaveEmptyProof.NONE : emptyProof;
        }
        public int knownColumnCount() {
            int count = 0;
            for (long row : knownRows) count += Long.bitCount(row);
            return count;
        }
    }

    public record ResolvedRegion(int[] pixels, short[] heights,
            long revision, boolean hasContent) {
    }

    public record DenseVariantTrimStats(long calls, long keysScanned,
            long totalNanos, long maxNanos) {
    }

    private static final class ProjectionWorkspace {
        private final CaveChunkTile[] pageTiles =
                new CaveChunkTile[DISPLAY_TILE_WINDOW * DISPLAY_TILE_WINDOW];
        private final DenseCaveTile[] displayTiles =
                new DenseCaveTile[DISPLAY_TILE_WINDOW * DISPLAY_TILE_WINDOW];
        private final CompactCaveTile[] compactArchiveTiles =
                new CompactCaveTile[DISPLAY_TILE_WINDOW * DISPLAY_TILE_WINDOW];
    }

    private record PageCacheKey(long generation, CaveView view, int layerY,
            int projectionTopY, int globalPageX, int globalPageZ, long revision) {
    }

    private record ArchivePublicationTicket(CaveChunkTile tile,
            CaveChunkTile.Snapshot snapshot, long repositoryGeneration,
            long archiveRevision, long tileRevision, int milestone,
            int scannedColumns, boolean persistenceDue, File targetDirectory,
            RevisionStamp sessionStamp) {
    }

    private record RepositoryIndex(
            Map<Long, CaveRegionStore.RecordPointer> rawRecords,
            Map<DenseCaveTileKey, CaveDisplayRegionStore.RecordPointer> displayRecords,
            Set<Long> legacyTiles) {
    }

    @FunctionalInterface
    public interface TileListener {
        void onTileChanged(int chunkX, int chunkZ, long tileRevision);
    }
}
