package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.ChunkScanner;
import com.velorise.simplemap.client.GeneratedChunkIndex;
import com.velorise.simplemap.client.MapCancellationToken;
import com.velorise.simplemap.client.MapDebugRecorder;
import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapPerformanceGovernor;
import com.velorise.simplemap.client.MapPipelineStage;
import com.velorise.simplemap.client.MapPipelineTelemetry;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapWorkScheduler;
import com.velorise.simplemap.client.cave.archive.CaveArchiveV2Service;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Native 32x32-chunk Anvil region import authority for all world projections.
 *
 * <p>A requested native region owns one 34x34 source window: the complete 32x32
 * region plus one chunk of styling halo on every side. Chunks are decoded once
 * through Minecraft's shared RegionFileStorage-backed reader, converted to the
 * style-independent vertical cave archive, and then reused by every Full and
 * Layered projection. Overlapping 6x6 page windows no longer start independent
 * Anvil transactions for the same chunk.</p>
 *
 * <p>Only source cells required by the current visible page halo are admitted.
 * Decoded archive cells remain resident in the shared source cache, so a later
 * pan reuses overlap and adds only the newly exposed edge instead of draining the
 * rest of the native region in the background.</p>
 */
final class CaveNativeRegionImportService {
    private static final CaveNativeRegionImportService INSTANCE =
            new CaveNativeRegionImportService();

    static final int REGION_CHUNKS = 32;
    static final int REGION_PAGES = 8;
    static final int SOURCE_HALO = 1;
    static final int SOURCE_EDGE = REGION_CHUNKS + SOURCE_HALO * 2;
    static final int SOURCE_COUNT = SOURCE_EDGE * SOURCE_EDGE;
    /** One source cell influences only pages whose 4x4 centre or 1-chunk halo contains it. */
    private static final long[] AFFECTED_PAGE_MASKS = buildAffectedPageMasks();
    private static final int MAX_REGIONS = 48;
    /*
     * PASS102 could open 96 Anvil source transactions while every completed read
     * queued a SOURCE_DECODE and then SOURCE_PROJECTION onto the same eight-worker
     * global CPU domain. The PASS102 validation run accumulated 18,633 reads and a
     * SOURCE_QUEUE tail of 8.39 s while GPU utilization stayed low. Xaero's world
     * loader intentionally advances a bounded region queue instead of flooding all
     * visible source cells at once. Keep at most four CPU waves in flight here and
     * admit one compact region slice at a time.
     */
    /*
     * PASS139: exact visible pages already have their own retained page reader.
     * Native-region ingestion is durable archive/support work and must not launch
     * another 32 decoded-source fanouts beside it. Keep enough overlap to converge
     * quickly while leaving disk/CPU capacity for the visible page pipeline.
     */
    /*
     * PASS141: visible exact pages now own the 16-chunk foreground transaction.
     * Keep durable native archive ingestion behind that dependency and leave enough
     * decoded-source headroom for one coherent page even on a cold world.
     */
    private static final int NORMAL_ACTIVE_SOURCES = 16;
    /**
     * One Xaero-style 4x4 MapTileChunk transaction is sixteen Minecraft chunks.
     * Pressure may reduce cross-page overlap, but it must never make one visible
     * page mathematically impossible to admit as a coherent source unit.
     */
    private static final int PRESSURE_ACTIVE_SOURCES = 16;
    private static final int SOURCE_SLICE = 16;
    /**
     * PASS144: preserve a coherent near-to-far foreground wave for roughly the
     * first two seconds. PASS143's 250 ms region fairness immediately round-robined
     * the source window across many native regions and produced disconnected 64x64
     * islands even when IO was fast. Hard starvation protection still takes over
     * after this locality window.
     */
    private static final long REGION_SOURCE_FAIRNESS_MS = 2_000L;
    /**
     * PASS138-B1: source selection must remain bounded. Scoring every cell in a
     * 34x34 frontier merely to admit a handful of chunks turned scheduling into
     * its own hot path. Scan a small persistent window and advance the cursor.
     */
    private static final int SOURCE_SELECTION_LOOKAHEAD = 64;
    /** Visible central source older than this must receive an anti-starvation slot. */
    private static final long VISIBLE_SOURCE_FAIRNESS_MS = 500L;
    private static final int SURFACE_PROTO_ACK_SLICE = 32;
    private static final long FAILED_RETRY_MS = 2_000L;
    /** Decoded vertical archive is built in retained CPU slices, not failed work. */
    private static final long ARCHIVE_SLICE_RETRY_MS = 12L;
    /**
     * PASS155: package the durable all-height archive from the decoded source while
     * that source is still hot. The decoded object is retained immediately and archive
     * slices continue on SOURCE_PROJECTION workers without a second Anvil admission.
     * This is a cache-writer continuation, never a presentation gate.
     */
    private static final long HOT_ARCHIVE_CONTINUATION_BUDGET_NANOS = 4_000_000L;
    /**
     * Give an existing packaged 512x512 CIMG first right of refusal before opening
     * its world-save source. The fence is deliberately short: stale/missing cache
     * falls through to Anvil after this interval without becoming a dependency.
     */
    private static final long REGION_IMAGE_BOOTSTRAP_GRACE_MS = 350L;
    // An Anvil chunk whose Status is not FULL can be useful for Cave archive,
    // but it can never be authoritative Surface input. Do not reopen/DataFix the
    // same proto chunk every two seconds while waiting for Minecraft to save FULL.
    private static final long SURFACE_PROTO_RETRY_MS = 30_000L;
    /** Header/disk absence is transient and must never become Cave authority. */
    private static final long DISK_ABSENT_RETRY_MS = 30_000L;
    /** Visible absence is re-probed quickly if no presence/live event arrived. */
    private static final long FOREGROUND_DISK_ABSENT_RETRY_MS = 3_000L;
    private static final long COMPLETED_RETENTION_MS = 30_000L;

    private final DecodedWorldRegionCache sourceCache =
            DecodedWorldRegionCache.getInstance();
    private final CaveTileRepository repository = CaveTileRepository.getInstance();
    private final AnvilPagePresenceIndex presenceIndex =
            AnvilPagePresenceIndex.getInstance();
    private final CaveRegionProjectionService projections =
            CaveRegionProjectionService.getInstance();
    private final CaveDisplayProjector displayProjector =
            new CaveDisplayProjector();
    private final SurfaceWorldSaveReconstructor surfaceReconstructor =
            SurfaceWorldSaveReconstructor.getInstance();
    private final PriorityDecodeExecutor archiveWorkers = new PriorityDecodeExecutor(
            MapWorkScheduler.WorkType.SOURCE_PROJECTION, 18);
    private final LinkedHashMap<RegionKey, RegionImport> imports =
            new LinkedHashMap<>(16, 0.75f, true);
    /** Per packaged CIMG generation, first visible cache-first observation. */
    private final Map<CaveRegionImageCache.Key, Long> regionImageBootstrapStartedMs =
            new HashMap<>();
    /** O(1) route from a live chunk event to every overlapping 34x34 importer. */
    private final Long2ObjectOpenHashMap<ArrayList<RegionCellRef>> regionCellsByChunk =
            new Long2ObjectOpenHashMap<>();

    private long epoch = 1L;
    private long sequence;
    private long viewportGeneration;
    private long surfaceViewportGeneration;
    private int activeSources;

    private CaveNativeRegionImportService() {
    }

    static CaveNativeRegionImportService getInstance() {
        return INSTANCE;
    }

    synchronized void reset() {
        epoch++;
        int supersededSources = 0;
        for (RegionImport region : imports.values()) {
            supersededSources += region.inFlight.cardinality();
        }
        MapPipelineTelemetry.getInstance()
                .recordCaveSourceLeaseCancelledGenerationSuperseded(
                        supersededSources);
        if (supersededSources > 0) {
            MapDebugRecorder.getInstance().event(
                    "CAVE_SOURCE_LEASE_CANCELLED_GENERATION_SUPERSEDED",
                    "sources=" + supersededSources + " reason=service_reset");
        }
        for (RegionImport region : imports.values()) region.close();
        imports.clear();
        regionCellsByChunk.clear();
        regionImageBootstrapStartedMs.clear();
        activeSources = 0;
        viewportGeneration = 0L;
        surfaceViewportGeneration = 0L;
    }

    /**
     * Routes a disk miss to the already-present live writer for non-fullscreen
     * consumers. A fullscreen Cave projection owns a saved-world snapshot and must
     * never be converted into LIVE_PENDING after the snapshot barrier has completed.
     *
     * <p>PASS151 only prevented <em>new</em> fullscreen admissions from choosing the
     * live route. The live-repair inbox could still mark the same required cell as
     * LIVE_PENDING later, and historical MINIMAP LIVE_PENDING bits survived when
     * MapScreen took over. In the 15:38 validation run those bits strand 12-13 of 16
     * children for more than 45 seconds with source_inflight=0.</p>
     */
    synchronized int markLivePending(int chunkX, int chunkZ) {
        ArrayList<RegionCellRef> cells = regionCellsByChunk.get(
                CaveTileRepository.pack(chunkX, chunkZ));
        if (cells == null) return 0;
        int marked = 0;
        int fullscreenBypassed = 0;
        for (RegionCellRef cell : cells) {
            RegionImport region = cell.region;
            int index = cell.index;
            // The ABSENT completion enqueues the callback before its finally block
            // publishes transientDiskAbsent. Demand is the race-free routing proof.
            if (!isCurrent(region) || !region.caveRequiredSources.get(index)
                    || region.caveArchived.get(index)) continue;

            MapRequestLane demandLane = region.foregroundDemandLane(index);
            if (demandLane == MapRequestLane.FULLSCREEN) {
                if (region.liveSourcePending.get(index)) {
                    region.liveSourcePending.clear(index);
                }
                region.sourceSettled.clear(index);
                region.resolved.clear(index);
                region.sourceRetryAfterMs[index] = 0L;
                region.retryAfterMs = 0L;
                region.sourceSettledForPass = false;
                region.completedMs = 0L;
                fullscreenBypassed++;
                continue;
            }

            if (!region.liveSourcePending.get(index)) marked++;
            region.liveSourcePending.set(index);
            region.sourcePresent.set(index);
            region.sourceSettled.set(index);
            region.sourceRetryAfterMs[index] = Long.MAX_VALUE;
            region.retryAfterMs = 0L;
            region.reconcileResolution();
            region.sourceSettledForPass = region.requiredSourcesSettledForPass();
        }
        if (fullscreenBypassed > 0) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            String eventKey = "CAVE_FULLSCREEN_LIVE_ROUTE_BYPASSED:"
                    + chunkX + ':' + chunkZ;
            if (recorder.shouldEmitEvent(eventKey, 500L)) {
                recorder.event("CAVE_FULLSCREEN_LIVE_ROUTE_BYPASSED",
                        "chunk=" + chunkX + ',' + chunkZ
                                + " cells=" + fullscreenBypassed
                                + " action=keep_snapshot_disk_authority"
                                + " pass=PASS155");
            }
            pumpLocked();
        }
        return marked;
    }

    /** Completes the semantic side only after the live canonical tile is complete. */
    synchronized void acknowledgeLiveArchive(int chunkX, int chunkZ) {
        ArrayList<RegionCellRef> cells = regionCellsByChunk.get(
                CaveTileRepository.pack(chunkX, chunkZ));
        if (cells == null) return;
        int acknowledged = 0;
        for (RegionCellRef cell : cells) {
            RegionImport region = cell.region;
            int index = cell.index;
            if (!isCurrent(region)) continue;
            if (!region.caveRequiredSources.get(index)
                    || region.caveArchived.get(index)) continue;
            region.liveSourcePending.clear(index);
            region.transientDiskAbsent.clear(index);
            region.sourcePresent.set(index);
            region.sourceSettled.set(index);
            region.caveArchived.set(index);
            region.sourceRetryAfterMs[index] = 0L;
            region.reconcileResolution();
            publishReadyPagesLocked(region);
            if (region.requiredSourcesSettledForPass()) {
                region.sourceSettledForPass = true;
                onRegionSourceReadyLocked(region);
            }
            acknowledged++;
        }
        if (acknowledged > 0) pumpLocked();
    }

    /** Re-enables disk acquisition only when an unfinished live route disappears. */
    synchronized void resumeDiskAfterLiveUnavailable(int chunkX, int chunkZ) {
        ArrayList<RegionCellRef> cells = regionCellsByChunk.get(
                CaveTileRepository.pack(chunkX, chunkZ));
        if (cells == null) return;
        boolean resumed = false;
        for (RegionCellRef cell : cells) {
            RegionImport region = cell.region;
            int index = cell.index;
            if (!region.liveSourcePending.get(index)
                    || region.caveArchived.get(index)) continue;
            region.liveSourcePending.clear(index);
            if (region.presenceAbsent.get(index)) {
                region.transientDiskAbsent.set(index);
                region.sourcePresent.clear(index);
                region.sourceSettled.set(index);
                region.resolved.clear(index);
                region.sourceRetryAfterMs[index] = 0L;
                region.reconcileResolution();
                continue;
            }
            region.sourcePresent.clear(index);
            region.sourceSettled.clear(index);
            region.resolved.clear(index);
            region.sourceRetryAfterMs[index] = 0L;
            region.sourceSettledForPass = false;
            region.completedMs = 0L;
            region.retryAfterMs = 0L;
            resumed = true;
        }
        if (resumed) pumpLocked();
    }

    /**
     * Registers all native regions touched by the current generated-page viewport.
     * Raw vertical archive cells are reusable by every cave presentation, but only
     * the projection that is actually visible is admitted. This keeps mode changes
     * cheap without eagerly building a second Full/Layered image for every page.
     */
    synchronized void requestViewport(ServerLevel level, String dimension,
            CaveView requestedView, int requestedTopY, long[] sourcePagePlan,
            long[] foregroundPagePlan,
            int visibleMinPageX, int visibleMaxPageX,
            int visibleMinPageZ, int visibleMaxPageZ,
            boolean retainPresentationWindow,
            int focusPageX, int focusPageZ,
            MapRequestLane lane, long repositoryGeneration) {
        if (level == null || dimension == null || dimension.isBlank()
                || requestedView == null || sourcePagePlan == null
                || sourcePagePlan.length == 0
                || !repository.isGenerationCurrent(repositoryGeneration)) return;
        long[] effectiveForegroundPlan = foregroundPagePlan == null
                ? sourcePagePlan : foregroundPagePlan;
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        if (effectiveLane == MapRequestLane.FULLSCREEN) {
            /*
             * The hidden minimap/background Surface leases share RegionImport.lane
             * with Cave and were still making fullscreen Cave source continuations
             * execute as MINIMAP work. Drop those hidden product leases while the
             * world-map Cave viewport owns the screen; normal minimap/background
             * requests recreate them after the fullscreen view closes.
             */
            for (RegionImport existing : imports.values()) {
                existing.clearSurfaceDemand(MapRequestLane.MINIMAP);
                existing.clearSurfaceDemand(MapRequestLane.BACKGROUND);
                existing.clearSurfaceDemand(MapRequestLane.PREFETCH);
            }
        }
        long currentViewportGeneration = ++viewportGeneration;
        /*
         * PASS138-A1: cave demand is a retained structural lease. Never clear every
         * importer before re-applying the same viewport: that turns each observation
         * pulse into required->empty->required and destroys the persistent source
         * frontier added in PASS137. Regions that truly leave this viewport are
         * retired by the generation sweep after the new plan has been applied.
         */
        /*
         * PASS114: source-support ownership and presentation ownership are not the
         * same set. Fullscreen source enumeration intentionally keeps a two-page
         * sticky halo so a continuous pan can reuse decoded Anvil/archive cells.
         * That halo must never be advertised as foreground projection demand: the
         * renderer owns only its real page plan. PASS113 treated the support halo
         * as foreground, so 49k cached-page offers were rejected/re-offered by the
         * texture manager in one run. Xaero similarly reads neighbour/support
         * chunks while only publishing MapTiles that belong to the writer window.
         */
        /*
         * PASS151 cache-first authority. The native Anvil importer used to race the
         * exact/display cache and persisted Compact archive even though those stores
         * were already sufficient to render the requested product. On the supplied
         * PASS149 run that produced >1,700 source admissions during the first 7.5 s
         * while 66k persisted archive tiles were being indexed in the background.
         *
         * Preflight only true foreground pages. A pending exact-cache or SMR2 native
         * region load temporarily owns that page; complete resident projection source
         * owns it outright. Partial/missing cache data falls through to Anvil on the
         * next observation pulse.
         */
        /*
         * PASS152: cache authority and foreground demand are different concepts.
         * PASS151 removed every already-resident cache page from the native-region
         * demand graph. That prevented branch-only fullscreen rendering from ever
         * staging those pages again after FULL <-> LAYERED view changes: the cache
         * was correctly detected, Anvil was correctly skipped, but no writer owned
         * the ready page. The 15:38 validation run proves this directly: generation
         * 5 reports 106 cache-ready Layered pages and then publishes zero Cave GPU
         * pages before world exit.
         *
         * Only pages with cache IO genuinely in flight are temporarily excluded.
         * Ready Dense/archive pages remain in the projection demand graph, where
         * reconcileResolution()/publishReadyPagesLocked() consume them without an
         * Anvil read. Cache is therefore source authority, never demand suppression.
         */
        Set<Long> cacheIoPendingForegroundPages = new HashSet<>();
        int residentCachePages = 0;
        int displayIoPendingPages = 0;
        int archiveIoPendingPages = 0;
        int regionImageBootstrapPages = 0;
        int regionImageBootstrapRegions = 0;
        long now = System.currentTimeMillis();
        if (effectiveLane == MapRequestLane.FULLSCREEN) {
            /*
             * PASS155: Xaero normally renders an already-packaged map product and
             * lets source recovery/refinement happen behind it. CIMG is Simple Map's
             * equivalent 512x512 product. If that file exists, keep world-save reads
             * off this region for a bounded grace window so cache IO cannot lose its
             * first frame to hundreds of Anvil/CVD requests.
             */
            Set<CaveRegionImageCache.Key> bootstrapRegions = new HashSet<>();
            regionImageBootstrapStartedMs.entrySet().removeIf(entry ->
                    now - entry.getValue() > REGION_IMAGE_BOOTSTRAP_GRACE_MS * 8L);
            for (long packed : effectiveForegroundPlan) {
                int pageX = CaveLoadHierarchy.x(packed);
                int pageZ = CaveLoadHierarchy.z(packed);
                CaveRegionImageCache.Key imageKey = new CaveRegionImageCache.Key(
                        dimension, requestedView,
                        DenseCaveTile.normalizeLayer(requestedView, requestedTopY),
                        canonicalTopY(requestedView, requestedTopY),
                        CaveProjectionStyle.signature(),
                        Math.floorDiv(pageX, CaveRegionImageCache.PAGES_PER_EDGE),
                        Math.floorDiv(pageZ, CaveRegionImageCache.PAGES_PER_EDGE));
                Long bootstrapStarted = regionImageBootstrapStartedMs.get(imageKey);
                if (bootstrapStarted == null
                        && CaveRegionImageCache.getInstance().exists(imageKey)) {
                    bootstrapStarted = now;
                    regionImageBootstrapStartedMs.put(imageKey, now);
                }
                if (bootstrapStarted != null
                        && now - bootstrapStarted < REGION_IMAGE_BOOTSTRAP_GRACE_MS) {
                    cacheIoPendingForegroundPages.add(packed);
                    regionImageBootstrapPages++;
                    bootstrapRegions.add(imageKey);
                }
            }
            regionImageBootstrapRegions = bootstrapRegions.size();
            for (long packed : effectiveForegroundPlan) {
                int pageX = CaveLoadHierarchy.x(packed);
                int pageZ = CaveLoadHierarchy.z(packed);
                repository.requestDisplayPageLoad(requestedView, requestedTopY,
                        pageX, pageZ, effectiveLane);
                boolean displayPending = repository.hasPendingDisplayPageLoad(
                        requestedView, requestedTopY, pageX, pageZ);
                boolean displayReady = repository.hasFreshDisplayPageSource(
                        requestedView, requestedTopY, pageX, pageZ,
                        DenseCaveTile.Source.WORLD_SAVE);
                boolean projectionReady = repository.hasCompleteProjectionSourcePage(
                        requestedView, requestedTopY, pageX, pageZ);
                if (displayPending) {
                    cacheIoPendingForegroundPages.add(packed);
                    displayIoPendingPages++;
                    continue;
                }
                if (displayReady || projectionReady) {
                    residentCachePages++;
                    continue;
                }
                if (repository.requestPersistedArchivePageLoad(requestedView,
                        requestedTopY, pageX, pageZ, effectiveLane)) {
                    cacheIoPendingForegroundPages.add(packed);
                    archiveIoPendingPages++;
                }
            }
        }
        Map<Long, Long> sourceMasks = pageMasksExcluding(sourcePagePlan,
                cacheIoPendingForegroundPages);
        Map<Long, Long> foregroundMasks = pageMasksExcluding(effectiveForegroundPlan,
                cacheIoPendingForegroundPages);
        int cachePageCount = residentCachePages + displayIoPendingPages
                + archiveIoPendingPages;
        if (cachePageCount > 0) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent("CAVE_FOREGROUND_CACHE_FIRST", 250L)) {
                recorder.event("CAVE_FOREGROUND_CACHE_FIRST",
                        "view=" + requestedView + " top_y=" + requestedTopY
                                + " lane=" + effectiveLane
                                + " cache_pages=" + cachePageCount
                                + " resident_ready=" + residentCachePages
                                + " display_io_pending=" + displayIoPendingPages
                                + " archive_io_pending=" + archiveIoPendingPages
                                + " cimg_bootstrap_pages=" + regionImageBootstrapPages
                                + " cimg_bootstrap_regions=" + regionImageBootstrapRegions
                                + " ready_pages_remain_demanded=true"
                                + " policy=cache_authority_not_demand_suppression"
                                + " pass=PASS155");
            }
        }
        if (regionImageBootstrapPages > 0) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent("CAVE_CACHE_BOOTSTRAP_SOURCE_DEFERRED", 250L)) {
                recorder.event("CAVE_CACHE_BOOTSTRAP_SOURCE_DEFERRED",
                        "view=" + requestedView + " top_y=" + requestedTopY
                                + " pages=" + regionImageBootstrapPages
                                + " regions=" + regionImageBootstrapRegions
                                + " grace_ms=" + REGION_IMAGE_BOOTSTRAP_GRACE_MS
                                + " policy=xaero_render_cache_before_world_save"
                                + " pass=PASS155");
            }
        }
        int sourcePageCount = sourceMasks.values().stream()
                .mapToInt(Long::bitCount).sum();
        int foregroundPageCount = 0;
        for (Map.Entry<Long, Long> entry : sourceMasks.entrySet()) {
            foregroundPageCount += Long.bitCount(entry.getValue()
                    & foregroundMasks.getOrDefault(entry.getKey(), 0L));
        }
        if (sourcePageCount != foregroundPageCount) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            String eventKey = "CAVE_SOURCE_PRESENTATION_SPLIT:"
                    + effectiveLane + ':' + requestedView;
            if (recorder.shouldEmitEvent(eventKey, 1000L)) {
                recorder.event("CAVE_SOURCE_PRESENTATION_SPLIT",
                        "view=" + requestedView + " lane=" + effectiveLane
                                + " source_pages=" + sourcePageCount
                                + " foreground_pages=" + foregroundPageCount
                                + " source_only_pages="
                                + Math.max(0, sourcePageCount - foregroundPageCount));
            }
        }
        int regionOrdinal = 0;
        for (Map.Entry<Long, Long> entry : sourceMasks.entrySet()) {
            int regionX = CaveLoadHierarchy.x(entry.getKey());
            int regionZ = CaveLoadHierarchy.z(entry.getKey());
            RegionKey key = new RegionKey(dimension, regionX, regionZ,
                    repositoryGeneration);
            RegionImport region = getOrCreateRegion(key, level);
            region.level = level;
            region.lastSeenMs = now;
            region.viewportGeneration = currentViewportGeneration;
            region.refreshPresence(presenceIndex, effectiveLane);
            int cavePriority = effectiveLane.priorityBase()
                    + 900_000 - regionOrdinal++ * 4_000;
            long sourceMask = entry.getValue();
            long foregroundMask = foregroundMasks.getOrDefault(entry.getKey(), 0L)
                    & sourceMask;
            region.setCaveDemand(sourceMask, focusPageX, focusPageZ,
                    effectiveLane, cavePriority);
            region.retainProjection(requestedView, requestedTopY);
            long visibleRetentionMask = retainPresentationWindow
                    ? pageMaskForRegionBounds(regionX, regionZ,
                            visibleMinPageX, visibleMaxPageX,
                            visibleMinPageZ, visibleMaxPageZ)
                    : foregroundMask;
            region.setForegroundDemandRetained(requestedView, requestedTopY,
                    foregroundMask, visibleRetentionMask,
                    effectiveLane, cavePriority + 120_000);
            if (foregroundMask == 0L && !retainPresentationWindow) {
                projections.retireForegroundRegion(dimension, requestedView,
                        requestedTopY, regionX, regionZ);
            }
        }
        for (RegionImport existing : imports.values()) {
            if (!existing.key.dimension.equals(dimension)
                    || existing.key.repositoryGeneration
                            != repositoryGeneration) continue;
            if (existing.viewportGeneration == currentViewportGeneration) continue;

            /*
             * PASS159: the bounded source writer may move to the next scanline
             * window only after the previous source is satisfied. That must retire
             * Anvil ownership, not the exact projection that is still waiting for
             * CPU/GPU publication. Keep projection masks accumulated inside the
             * currently visible exact viewport and clip them only when the viewport
             * itself moves. Far/branch-only mode keeps the old bounded behaviour.
             */
            if (retainPresentationWindow
                    && regionIntersectsPageBounds(existing.key.regionX,
                            existing.key.regionZ,
                            visibleMinPageX, visibleMaxPageX,
                            visibleMinPageZ, visibleMaxPageZ)) {
                existing.clearCaveDemand();
                existing.retainProjection(requestedView, requestedTopY);
                long visibleRetentionMask = pageMaskForRegionBounds(
                        existing.key.regionX, existing.key.regionZ,
                        visibleMinPageX, visibleMaxPageX,
                        visibleMinPageZ, visibleMaxPageZ);
                existing.clipForegroundDemand(requestedView, requestedTopY,
                        visibleRetentionMask, effectiveLane);
                continue;
            }

            for (ProjectionDemand demand : existing.demands.values()) {
                projections.retireForegroundRegion(existing.key.dimension,
                        demand.key.view, demand.key.projectionTopY,
                        existing.key.regionX, existing.key.regionZ);
            }
            existing.retireProjectionDemands();
        }
        pumpLocked();
        trimLocked(now);
    }

    private static Map<Long, Long> pageMasks(long[] pagePlan) {
        Map<Long, Long> masks = new LinkedHashMap<>();
        if (pagePlan == null) return masks;
        for (long packed : pagePlan) {
            int pageX = CaveLoadHierarchy.x(packed);
            int pageZ = CaveLoadHierarchy.z(packed);
            int regionX = Math.floorDiv(pageX, REGION_PAGES);
            int regionZ = Math.floorDiv(pageZ, REGION_PAGES);
            int localX = Math.floorMod(pageX, REGION_PAGES);
            int localZ = Math.floorMod(pageZ, REGION_PAGES);
            long region = CaveLoadHierarchy.pack(regionX, regionZ);
            long bit = 1L << (localZ * REGION_PAGES + localX);
            masks.merge(region, bit, (first, second) -> first | second);
        }
        return masks;
    }

    private static Map<Long, Long> pageMasksExcluding(long[] pagePlan,
            Set<Long> excludedPages) {
        if (excludedPages == null || excludedPages.isEmpty()) {
            return pageMasks(pagePlan);
        }
        Map<Long, Long> masks = new LinkedHashMap<>();
        if (pagePlan == null) return masks;
        for (long packed : pagePlan) {
            if (excludedPages.contains(packed)) continue;
            int pageX = CaveLoadHierarchy.x(packed);
            int pageZ = CaveLoadHierarchy.z(packed);
            int regionX = Math.floorDiv(pageX, REGION_PAGES);
            int regionZ = Math.floorDiv(pageZ, REGION_PAGES);
            int localX = Math.floorMod(pageX, REGION_PAGES);
            int localZ = Math.floorMod(pageZ, REGION_PAGES);
            long region = CaveLoadHierarchy.pack(regionX, regionZ);
            long bit = 1L << (localZ * REGION_PAGES + localX);
            masks.merge(region, bit, (first, second) -> first | second);
        }
        return masks;
    }

    private static boolean regionIntersectsPageBounds(int regionX, int regionZ,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ) {
        int firstPageX = regionX * REGION_PAGES;
        int firstPageZ = regionZ * REGION_PAGES;
        int lastPageX = firstPageX + REGION_PAGES - 1;
        int lastPageZ = firstPageZ + REGION_PAGES - 1;
        return lastPageX >= minPageX && firstPageX <= maxPageX
                && lastPageZ >= minPageZ && firstPageZ <= maxPageZ;
    }

    private static long pageMaskForRegionBounds(int regionX, int regionZ,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ) {
        int firstPageX = regionX * REGION_PAGES;
        int firstPageZ = regionZ * REGION_PAGES;
        int localMinX = Math.max(0, minPageX - firstPageX);
        int localMaxX = Math.min(REGION_PAGES - 1, maxPageX - firstPageX);
        int localMinZ = Math.max(0, minPageZ - firstPageZ);
        int localMaxZ = Math.min(REGION_PAGES - 1, maxPageZ - firstPageZ);
        if (localMinX > localMaxX || localMinZ > localMaxZ) return 0L;
        long mask = 0L;
        for (int z = localMinZ; z <= localMaxZ; z++) {
            for (int x = localMinX; x <= localMaxX; x++) {
                mask |= 1L << (z * REGION_PAGES + x);
            }
        }
        return mask;
    }

    /**
     * Surface uses the same native-region source transaction as Cave. The source
     * cell is decoded once, then the transaction derives the Surface columns and
     * the reusable vertical archive before marking that cell resolved. Switching to
     * Layered or Full therefore adds only a projection demand; it never reopens the
     * .mca file or repeats palette decode.
     */
    synchronized void requestSurfaceViewport(ServerLevel level, String dimension,
            long[] pagePlan, int focusPageX, int focusPageZ, MapRequestLane lane,
            long repositoryGeneration) {
        if (level == null || dimension == null || dimension.isBlank()
                || pagePlan == null || pagePlan.length == 0
                || !repository.isGenerationCurrent(repositoryGeneration)) return;
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        long currentGeneration = ++surfaceViewportGeneration;
        Map<Long, Long> masks = new LinkedHashMap<>();
        for (long packed : pagePlan) {
            int pageX = CaveLoadHierarchy.x(packed);
            int pageZ = CaveLoadHierarchy.z(packed);
            int regionX = Math.floorDiv(pageX, REGION_PAGES);
            int regionZ = Math.floorDiv(pageZ, REGION_PAGES);
            int localX = Math.floorMod(pageX, REGION_PAGES);
            int localZ = Math.floorMod(pageZ, REGION_PAGES);
            long region = CaveLoadHierarchy.pack(regionX, regionZ);
            long bit = 1L << (localZ * REGION_PAGES + localX);
            masks.merge(region, bit, (first, second) -> first | second);
        }
        long now = System.currentTimeMillis();
        int regionOrdinal = 0;
        for (Map.Entry<Long, Long> entry : masks.entrySet()) {
            int regionX = CaveLoadHierarchy.x(entry.getKey());
            int regionZ = CaveLoadHierarchy.z(entry.getKey());
            RegionKey key = new RegionKey(dimension, regionX, regionZ,
                    repositoryGeneration);
            RegionImport region = getOrCreateRegion(key, level);
            region.level = level;
            region.lastSeenMs = now;
            region.refreshPresence(presenceIndex, effectiveLane);
            int priority = effectiveLane.priorityBase()
                    + 940_000 - regionOrdinal++ * 4_000;
            region.setSurfaceDemand(effectiveLane, entry.getValue(),
                    currentGeneration, priority, focusPageX, focusPageZ);
        }
        for (RegionImport region : imports.values()) {
            if (!region.key.dimension.equals(dimension)
                    || region.key.repositoryGeneration != repositoryGeneration) continue;
            region.retireSurfaceLaneIfStale(effectiveLane, currentGeneration);
        }
        pumpLocked();
        trimLocked(now);
    }

    synchronized void suspendSurfaceLane(MapRequestLane lane) {
        if (lane == null) return;
        for (RegionImport region : imports.values()) {
            region.clearSurfaceDemand(lane);
        }
    }

    synchronized void pauseCaveSourceLane(MapRequestLane lane) {
        if (lane == null) return;
        for (RegionImport region : imports.values()) {
            if (region.caveLane == lane) region.clearCaveDemand();
        }
    }

    synchronized void suspendCaveLane(MapRequestLane lane) {
        if (lane == null) return;
        for (RegionImport region : imports.values()) {
            if (region.caveLane == lane || region.hasProjectionLane(lane)) {
                region.retireProjectionDemands();
            }
        }
    }

    synchronized void maintain() {
        for (RegionImport region : imports.values()) {
            if (region.visiblePageMask != 0L && isCurrent(region)) {
                region.refreshPresence(presenceIndex, region.lane);
            }
        }
        pumpLocked();
        trimLocked(System.currentTimeMillis());
    }

    synchronized boolean ownsPage(String dimension, CaveView view,
            int projectionTopY, int globalPageX, int globalPageZ) {
        int regionX = Math.floorDiv(globalPageX, REGION_PAGES);
        int regionZ = Math.floorDiv(globalPageZ, REGION_PAGES);
        int canonicalTopY = canonicalTopY(view, projectionTopY);
        for (Map.Entry<RegionKey, RegionImport> entry : imports.entrySet()) {
            RegionKey key = entry.getKey();
            if (!key.dimension.equals(dimension) || key.regionX != regionX
                    || key.regionZ != regionZ) continue;
            RegionImport region = entry.getValue();
            ProjectionDemand demand = region.demands.get(
                    new ProjectionKey(view, canonicalTopY));
            if (demand == null) return false;
            int localX = Math.floorMod(globalPageX, REGION_PAGES);
            int localZ = Math.floorMod(globalPageZ, REGION_PAGES);
            return (demand.pageMask & (1L << (localZ * REGION_PAGES + localX))) != 0L;
        }
        return false;
    }

    /** Cache-only proof that all sixteen chunks of one generated page are absent. */
    synchronized boolean isPageKnownAbsent(String dimension,
            int globalPageX, int globalPageZ) {
        if (dimension == null || dimension.isBlank()) return false;
        RegionKey key = new RegionKey(dimension,
                Math.floorDiv(globalPageX, REGION_PAGES),
                Math.floorDiv(globalPageZ, REGION_PAGES),
                repository.generation());
        RegionImport region = imports.get(key);
        return region != null
                && region.isPageKnownAbsent(globalPageX, globalPageZ);
    }

    synchronized DebugSnapshot debugSnapshot() {
        int complete = 0;
        int sourceResolved = 0;
        int sourceInFlight = 0;
        int demands = 0;
        for (RegionImport region : imports.values()) {
            if (region.sourceSettledForPass) complete++;
            sourceResolved += region.resolved.cardinality();
            sourceInFlight += region.inFlight.cardinality();
            demands += region.demands.size();
        }
        return new DebugSnapshot(imports.size(), complete, activeSources,
                sourceResolved, sourceInFlight, demands);
    }

    synchronized PageSourceState pageSourceState(String dimension,
            int globalPageX, int globalPageZ) {
        if (dimension == null || dimension.isBlank()) return PageSourceState.EMPTY;
        int regionX = Math.floorDiv(globalPageX, REGION_PAGES);
        int regionZ = Math.floorDiv(globalPageZ, REGION_PAGES);
        /* PASS162: source-cursor probes run for every scanline prefix. Do not turn
         * that O(pages) walk into O(pages * importedRegions) by linearly scanning
         * the import map. RegionKey already includes the current repository epoch. */
        RegionImport region = imports.get(new RegionKey(dimension, regionX, regionZ,
                repository.generation()));
        if (region == null) return PageSourceState.EMPTY;
        int localPageX = Math.floorMod(globalPageX, REGION_PAGES);
        int localPageZ = Math.floorMod(globalPageZ, REGION_PAGES);
        int inFlight = 0;
        int archived = 0;
        int absent = 0;
        int resolved = 0;
        for (int chunkZ = 0; chunkZ < 4; chunkZ++) {
            for (int chunkX = 0; chunkX < 4; chunkX++) {
                int sourceX = localPageX * 4 + chunkX + SOURCE_HALO;
                int sourceZ = localPageZ * 4 + chunkZ + SOURCE_HALO;
                int index = sourceZ * SOURCE_EDGE + sourceX;
                if (region.inFlight.get(index)) inFlight++;
                if (region.caveArchived.get(index)) archived++;
                boolean resolvedCell = region.resolved.get(index);
                if (resolvedCell) resolved++;
                /* absentChunks is deliberately disjoint from resolvedChunks so
                 * resolved+absent is an exact settled-child count for PASS161/162. */
                if (!resolvedCell && (region.transientDiskAbsent.get(index)
                        || region.presenceKnown.get(index)
                                && region.presenceAbsent.get(index)
                                && !region.liveSourcePending.get(index))) {
                    absent++;
                }
            }
        }
        return new PageSourceState(inFlight, archived, absent, resolved);
    }

    /**
     * Retires disposable source leases while preserving bounded Cave ingestion.
     *
     * <p>Only leases already admitted by {@link #pumpLocked()} may become durable;
     * no new off-screen cells are opened here. When their Cave viewport disappears,
     * they are demoted to PREFETCH and allowed to commit the reusable vertical
     * archive. Surface-only or not-yet-useful leases are still cancelled promptly.</p>
     */
    private void cancelUnneededSourcesLocked() {
        for (RegionImport region : imports.values()) {
            for (int index = region.inFlight.nextSetBit(0); index >= 0;
                    index = region.inFlight.nextSetBit(index + 1)) {
                boolean caveNeededNow = region.caveRequiredSources.get(index)
                        && !region.caveArchived.get(index);
                boolean stillNeeded = region.surfaceRequiredSources.get(index)
                        && !region.surfaceProjected.get(index)
                        || caveNeededNow;
                DecodedWorldRegionCache.SourceLease lease = region.leases[index];
                if (stillNeeded) {
                    if (caveNeededNow && region.offscreenCaveInFlight.get(index)
                            && lease != null) {
                        lease.reclassify(region.lane);
                        region.offscreenCaveInFlight.clear(index);
                    }
                    continue;
                }
                if (region.durableCaveInFlight.get(index)
                        && !region.caveArchived.get(index) && lease != null) {
                    if (!region.offscreenCaveInFlight.get(index)) {
                        lease.reclassify(MapRequestLane.PREFETCH);
                        region.offscreenCaveInFlight.set(index);
                        MapPipelineTelemetry.getInstance()
                                .recordCaveSourceRetainedAfterViewportExit();
                        int chunkX = region.firstChunkX() + index % SOURCE_EDGE;
                        int chunkZ = region.firstChunkZ() + index / SOURCE_EDGE;
                        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                        String eventKey = "CAVE_SOURCE_INGESTION_RETAINED:"
                                + region.key.dimension + ':' + chunkX + ':' + chunkZ;
                        if (recorder.shouldEmitEvent(eventKey, 500L)) {
                            recorder.event("CAVE_SOURCE_INGESTION_RETAINED",
                                    "chunk=" + chunkX + ',' + chunkZ
                                            + " reason=viewport_exit"
                                            + " lane=PREFETCH"
                                            + " bounded=true");
                        }
                    }
                    continue;
                }
                region.leases[index] = null;
                region.inFlight.clear(index);
                region.durableCaveInFlight.clear(index);
                region.offscreenCaveInFlight.clear(index);
                activeSources = Math.max(0, activeSources - 1);
                if (lease != null) {
                    lease.close();
                    MapPipelineTelemetry.getInstance()
                            .recordCaveSourceLeaseCancelledViewportExit();
                }
            }
        }
    }

    private void pumpLocked() {
        cancelUnneededSourcesLocked();
        int maximum = MapPerformanceGovernor.getInstance().underPressure()
                ? PRESSURE_ACTIVE_SOURCES : NORMAL_ACTIVE_SOURCES;
        if (activeSources >= maximum) return;
        long now = System.currentTimeMillis();
        List<RegionImport> candidates = new ArrayList<>(imports.values());
        boolean fullscreenCaveTakeover = false;
        for (RegionImport region : candidates) {
            if (isCurrent(region)
                    && region.hasIncompleteForegroundCaveLane(
                            MapRequestLane.FULLSCREEN)) {
                fullscreenCaveTakeover = true;
                break;
            }
        }
        /*
         * PASS140: source fairness must exist at the native-region level as well
         * as inside one 34x34 frontier. PASS139 could repeatedly refill the same
         * early regions whenever all in-flight counters returned to zero, leaving
         * other visible regions with source_inflight=0 for 30-400 seconds. Rotate
         * the oldest visible region back to the front before applying focus/local
         * source scoring.
         */
        candidates.sort((first, second) -> {
            /*
             * PASS144: while MapScreen owns unfinished Cave foreground, do not let
             * the still-running minimap lane starve it. The validation run exposed
             * 330 FULLSCREEN Cave source pages / 198 foreground pages but every one
             * of the 104 coherent source batches was admitted as MINIMAP. Treat all
             * regions with unfinished FULLSCREEN Cave foreground as the top tier;
             * generic lane rank applies only outside that dependency.
             */
            boolean firstFullscreenCave =
                    first.hasIncompleteForegroundCaveLane(MapRequestLane.FULLSCREEN);
            boolean secondFullscreenCave =
                    second.hasIncompleteForegroundCaveLane(MapRequestLane.FULLSCREEN);
            int byFullscreenDependency = Boolean.compare(secondFullscreenCave,
                    firstFullscreenCave);
            if (byFullscreenDependency != 0) return byFullscreenDependency;

            if (firstFullscreenCave && secondFullscreenCave) {
                long firstScanline = first.earliestIncompleteFullscreenScanlineKey();
                long secondScanline = second.earliestIncompleteFullscreenScanlineKey();
                if (firstScanline != secondScanline) {
                    if (firstScanline == -1L) return 1;
                    if (secondScanline == -1L) return -1;
                    int byScanline = Long.compareUnsigned(firstScanline, secondScanline);
                    if (byScanline != 0) return byScanline;
                }
            }

            if (!firstFullscreenCave && !secondFullscreenCave) {
                int byLane = Integer.compare(second.lane.rank(), first.lane.rank());
                if (byLane != 0) return byLane;
            }

            boolean firstStarved = first.hasVisibleDemand()
                    && first.sourceServiceAgeMs(now) >= REGION_SOURCE_FAIRNESS_MS;
            boolean secondStarved = second.hasVisibleDemand()
                    && second.sourceServiceAgeMs(now) >= REGION_SOURCE_FAIRNESS_MS;
            int byStarvation = Boolean.compare(secondStarved, firstStarved);
            if (byStarvation != 0) return byStarvation;
            if (firstStarved && secondStarved) {
                int byInFlight = Integer.compare(first.inFlight.cardinality(),
                        second.inFlight.cardinality());
                if (byInFlight != 0) return byInFlight;
                int byAge = Long.compare(second.sourceServiceAgeMs(now),
                        first.sourceServiceAgeMs(now));
                if (byAge != 0) return byAge;
            }

            /*
             * PASS144: while neither region is hard-starved, finish the closest
             * incomplete Cave wave first. This is deliberately ahead of generic
             * in-flight/last-service fairness so the user sees one connected area
             * expand instead of isolated pages appearing across the viewport.
             */
            if (!firstStarved && !secondStarved) {
                int byLocality = Integer.compare(
                        first.closestIncompleteCavePageDistanceSquared(),
                        second.closestIncompleteCavePageDistanceSquared());
                if (byLocality != 0) return byLocality;
            }

            int byFocus = Boolean.compare(second.hasFocusVisibleDemand(),
                    first.hasFocusVisibleDemand());
            if (byFocus != 0) return byFocus;
            int byInFlight = Integer.compare(first.inFlight.cardinality(),
                    second.inFlight.cardinality());
            if (byInFlight != 0) return byInFlight;
            int byLastService = Long.compare(first.lastSourceAdmissionMs,
                    second.lastSourceAdmissionMs);
            if (byLastService != 0) return byLastService;
            int byPriority = Integer.compare(second.priority, first.priority);
            return byPriority != 0 ? byPriority
                    : Long.compare(first.sequence, second.sequence);
        });
        if (fullscreenCaveTakeover) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent("CAVE_FULLSCREEN_SOURCE_TAKEOVER", 500L)) {
                recorder.event("CAVE_FULLSCREEN_SOURCE_TAKEOVER",
                        "active_sources=" + activeSources + '/' + maximum
                                + " policy=fullscreen_visible_work_exclusive");
            }
        }
        for (RegionImport region : candidates) {
            if (activeSources >= maximum) break;
            if (!isCurrent(region)) continue;
            if (fullscreenCaveTakeover
                    && !region.hasIncompleteForegroundCaveLane(
                            MapRequestLane.FULLSCREEN)) {
                continue;
            }
            reconcileDeferredLiveSurfaceLocked(region);
            publishReadyPagesLocked(region);
            /*
             * PASS144: sourceSettled is a product-state cache, not immutable source
             * truth. Surface may settle a chunk before Cave demand exists; a later
             * Cave projection must reopen that same source if neither exact nor
             * archive authority exists. Repair stale historical settlement before
             * the fast-path skip, otherwise the region can sit at 15/16 forever with
             * source_inflight=0.
             */
            region.reopenMissingForegroundSources(now);
            region.wakeExpiredSettledSources(now);
            if (region.sourceSettledForPass || region.retryAfterMs > now) continue;
            /*
             * PASS143: foreground Cave scheduling is page-coherent, not region-breadth
             * first. Xaero's WorldDataReader starts the sixteen NBT futures belonging
             * to one 4x4 MapTileChunk together. PASS140's 1/2-source region slice did
             * the opposite: it scattered the global source window across many native
             * regions, so almost every page sat at 1..15/16 while no source was in
             * flight. Give a Cave-visible region enough capacity to close one complete
             * 4x4 source transaction; Surface-only work keeps the small fair slice.
             */
            // PASS143: reserve a Xaero-sized 4x4 burst only while this region
            // still owns an incomplete foreground Cave transaction. Once its
            // visible Cave pages are semantically closed, fall back to the old
            // small maintenance slice so durable archive/surface work cannot
            // turn the 16-source foreground fix into a background flood.
            int perRegionSlice = region.hasIncompleteVisibleCavePage()
                    ? SOURCE_SLICE : region.hasFocusVisibleDemand() ? 2 : 1;
            int capacity = Math.min(perRegionSlice, maximum - activeSources);
            if (capacity <= 0) break;
            List<Integer> selected = region.nextSourceIndexes(capacity, now);
            region.emitVisibleSourceWaitTelemetry(now);
            /*
             * Retained SimpleMap Surface storage is already a durable source for
             * chunks that were scanned/reconstructed earlier. PASS102 reopened the
             * Anvil chunk anyway, DataFixed/decoded it, projected 256 columns, then
             * SurfaceWorldSaveReconstructor discovered isChunkSurfaceComplete() and
             * threw the projection away. Resolve Surface-only cells before source
             * admission; Cave still opens the chunk when it needs the vertical
             * archive. This mirrors Xaero's preference for retained map-region data
             * over rebuilding known terrain from the world save.
             */
            boolean retainedSurfaceAdvanced = false;
            for (var iterator = selected.iterator(); iterator.hasNext();) {
                int index = iterator.next();
                if (!region.surfaceRequiredSources.get(index)) continue;
                int chunkX = region.firstChunkX() + index % SOURCE_EDGE;
                int chunkZ = region.firstChunkZ() + index / SOURCE_EDGE;
                if (!MapManager.getInstance().isChunkSurfaceComplete(chunkX, chunkZ)) {
                    continue;
                }
                /*
                 * Surface completeness is independent from Cave source need. PASS138
                 * only skipped disk work for Surface-only cells; a cell needed by
                 * Cave still rebuilt 256 Surface columns before discovering that the
                 * retained Surface chunk was already complete.
                 */
                region.surfaceProjected.set(index);
                region.surfaceProtoDeferred.clear(index);
                region.surfaceProtoRetryAfterMs[index] = 0L;
                retainedSurfaceAdvanced = true;
                if (!region.caveRequiredSources.get(index)) {
                    iterator.remove();
                }
            }
            if (retainedSurfaceAdvanced) region.reconcileResolution();

            /*
             * Minimap/live observation still prefers the live writer, but fullscreen
             * world-map Cave must not become hostage to it. Xaero's WorldDataReader
             * flushes/reads the world-save transaction and builds the requested cave
             * tile immediately. Do the same for an exact FULLSCREEN dependency: read
             * Anvil now, publish the snapshot, then let later live commits refine it.
             */
            boolean routedLiveSource = false;
            for (var iterator = selected.iterator(); iterator.hasNext();) {
                int index = iterator.next();
                if (!region.caveRequiredSources.get(index)
                        || region.caveArchived.get(index)) continue;
                MapRequestLane presentationLane =
                        region.foregroundPresentationLane(index);
                if (presentationLane == MapRequestLane.FULLSCREEN) {
                    continue;
                }
                int chunkX = region.firstChunkX() + index % SOURCE_EDGE;
                int chunkZ = region.firstChunkZ() + index / SOURCE_EDGE;
                if (GeneratedChunkIndex.getInstance().state(
                        region.level, chunkX, chunkZ)
                        != GeneratedChunkIndex.State.LIVE) {
                    continue;
                }

                if (region.surfaceRequiredSources.get(index)
                        && !region.surfaceProjected.get(index)) {
                    if (MapManager.getInstance().isChunkSurfaceComplete(
                            chunkX, chunkZ)) {
                        region.surfaceProjected.set(index);
                    } else {
                        region.surfaceProtoDeferred.set(index);
                        region.surfaceProtoRetryAfterMs[index] = now
                                + SURFACE_PROTO_RETRY_MS;
                        nudgeLiveSurfaceWriter(chunkX, chunkZ);
                    }
                }
                markLivePending(chunkX, chunkZ);
                nudgeLiveCaveWriter(chunkX, chunkZ);
                iterator.remove();
                routedLiveSource = true;
            }
            if (routedLiveSource) {
                region.reconcileResolution();
                region.sourceSettledForPass =
                        region.requiredSourcesSettledForPass();
            }

            if (selected.isEmpty()) {
                region.sourceSettledForPass =
                        region.requiredSourcesSettledForPass();
                if (region.sourceSettledForPass) {
                    onRegionSourceReadyLocked(region);
                } else {
                    long deferredUntil = region.nextSurfaceProtoRetryAfter(now);
                    long sourceRetryUntil = region.nextSourceRetryAfter(now);
                    if (deferredUntil == 0L || sourceRetryUntil > 0L
                            && sourceRetryUntil < deferredUntil) {
                        deferredUntil = sourceRetryUntil;
                    }
                    if (deferredUntil > now) {
                        // Keep the service responsive to a newly-entered source cell
                        // without reopening the known proto cell itself.
                        region.retryAfterMs = Math.max(region.retryAfterMs,
                                Math.min(deferredUntil, now + 1_000L));
                    }
                }
                continue;
            }
            DecodedWorldRegionCache.PageReservation reservation = null;
            boolean coherentCaveBatch = region.isForegroundCavePageBatch(selected);
            while (!selected.isEmpty() && reservation == null) {
                int requiredDecodes = 0;
                for (int index : selected) {
                    int chunkX = region.firstChunkX() + index % SOURCE_EDGE;
                    int chunkZ = region.firstChunkZ() + index / SOURCE_EDGE;
                    if (sourceCache.requiresForegroundDecode(
                            region.level, chunkX, chunkZ)) {
                        requiredDecodes++;
                    }
                }
                reservation = sourceCache.reserveForegroundDecodes(requiredDecodes);
                if (reservation == null) {
                    if (coherentCaveBatch) {
                        /*
                         * Never degrade a visible 4x4 transaction into 8/4/2/1 reads
                         * when the shared decode budget is temporarily occupied. That
                         * exact halving loop recreated the scattered completion pattern
                         * even after source selection became page-aware. Wait for the
                         * already-admitted batch to drain and retry atomically.
                         */
                        selected.clear();
                        break;
                    }
                    selected = new ArrayList<>(
                            selected.subList(0, Math.max(1, selected.size() / 2)));
                    if (selected.size() == 1) {
                        int index = selected.get(0);
                        int chunkX = region.firstChunkX() + index % SOURCE_EDGE;
                        int chunkZ = region.firstChunkZ() + index / SOURCE_EDGE;
                        if (sourceCache.requiresForegroundDecode(
                                region.level, chunkX, chunkZ)
                                && !sourceCache.canAdmitForegroundDecodes(1)) {
                            selected.clear();
                        }
                    }
                }
            }
            if (reservation == null || selected.isEmpty()) continue;
            int started = 0;
            long submittedEpoch = epoch;
            List<SourceBatchItem> coherentBatchItems = coherentCaveBatch
                    ? new ArrayList<>(selected.size()) : null;
            MapRequestLane coherentBatchLane = null;
            for (int index : selected) {
                if (activeSources >= maximum) break;
                int chunkX = region.firstChunkX() + index % SOURCE_EDGE;
                int chunkZ = region.firstChunkZ() + index / SOURCE_EDGE;
                /*
                 * PASS142: the native region importer is the default world-save Cave
                 * pipeline. PASS141 demoted every cave-only source to BACKGROUND under
                 * the assumption that CaveWorldSaveReader owned visible presentation,
                 * but that reader delegates here unless the legacy pipeline property
                 * is enabled. Keep a source in the visible lane while its exact
                 * Layered/Full Dense tile is still missing; only the durable
                 * all-height archive continuation is background work.
                 */
                boolean surfacePending = region.surfaceRequiredSources.get(index)
                        && !region.surfaceProjected.get(index);
                MapRequestLane presentationLane =
                        region.foregroundPresentationLane(index);
                boolean cavePresentationPending = presentationLane != null;
                MapRequestLane sourceLane = cavePresentationPending
                        ? presentationLane
                        : surfacePending ? region.lane : MapRequestLane.BACKGROUND;
                DecodedWorldRegionCache.SourceLease lease =
                        sourceCache.requestReservedLease(region.level, chunkX, chunkZ,
                                sourceLane, reservation);
                if (lease.isImmediatelyDeferred()) {
                    lease.close();
                    continue;
                }
                region.inFlight.set(index);
                region.leases[index] = lease;
                if (region.caveRequiredSources.get(index)
                        && !region.caveArchived.get(index)) {
                    region.durableCaveInFlight.set(index);
                }
                activeSources++;
                started++;
                region.recordSourceAdmission(index, now);
                if (coherentCaveBatch) {
                    coherentBatchItems.add(new SourceBatchItem(
                            index, chunkX, chunkZ, lease));
                    if (coherentBatchLane == null
                            || sourceLane.strongerThan(coherentBatchLane)) {
                        coherentBatchLane = sourceLane;
                    }
                } else {
                    lease.future().whenCompleteAsync((result, failure) ->
                                    completeSource(region, index, chunkX, chunkZ,
                                            submittedEpoch, result, failure),
                            archiveWorkers.dynamic(sourceLane::executorPriority));
                }
            }
            if (coherentCaveBatch && coherentBatchItems != null
                    && !coherentBatchItems.isEmpty()) {
                SourceBatchItem[] batch = coherentBatchItems.toArray(
                        SourceBatchItem[]::new);
                CompletableFuture<?>[] futures = new CompletableFuture<?>[batch.length];
                for (int item = 0; item < batch.length; item++) {
                    futures[item] = batch[item].lease().future();
                }
                MapRequestLane batchLane = coherentBatchLane == null
                        ? MapRequestLane.FULLSCREEN : coherentBatchLane;
                CompletableFuture.allOf(futures).whenComplete(
                        (ignored, aggregateFailure) -> {
                            long batchStartedNanos = System.nanoTime();
                            int failed = 0;
                            for (SourceBatchItem item : batch) {
                                DecodedWorldRegionCache.Result result = null;
                                Throwable failure = null;
                                try {
                                    result = item.lease().future().join();
                                } catch (CompletionException completionFailure) {
                                    failure = completionFailure.getCause() == null
                                            ? completionFailure
                                            : completionFailure.getCause();
                                } catch (RuntimeException runtimeFailure) {
                                    failure = runtimeFailure;
                                }
                                if (failure != null) failed++;
                                completeSource(region, item.index(), item.chunkX(),
                                        item.chunkZ(), submittedEpoch, result, failure);
                            }
                            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                            String eventKey = "CAVE_PAGE_SOURCE_BATCH_CONSUMED:"
                                    + region.key.regionX + ':' + region.key.regionZ;
                            if (recorder.shouldEmitEvent(eventKey, 100L)) {
                                recorder.event("CAVE_PAGE_SOURCE_BATCH_CONSUMED",
                                        "region=" + region.key.regionX + ','
                                                + region.key.regionZ
                                                + " chunks=" + batch.length
                                                + " failed=" + failed
                                                + " lane=" + batchLane
                                                + " consume_us="
                                                + ((System.nanoTime()
                                                        - batchStartedNanos) / 1_000L)
                                                + " policy=xaero_one_consumer_completion_thread"
                                                + " pass=PASS155");
                            }
                        });
            }
            region.emitSourceAdmissionSummary(now);
            if (coherentCaveBatch && started > 0) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String batchKey = "CAVE_PAGE_SOURCE_BATCH_ADMITTED:"
                        + region.key.regionX + ':' + region.key.regionZ;
                if (recorder.shouldEmitEvent(batchKey, 100L)) {
                    recorder.event("CAVE_PAGE_SOURCE_BATCH_ADMITTED",
                            "region=" + region.key.regionX + ',' + region.key.regionZ
                                    + " selected=" + selected.size()
                                    + " started=" + started
                                    + " active=" + activeSources + '/' + maximum
                                    + " lane=" + (coherentBatchLane == null
                                            ? region.lane : coherentBatchLane)
                                    + " policy=xaero_4x4_atomic_one_consumer_completion_driven pass=PASS155");
                }
            }
            reservation.close();
            if (started == 0) region.retryAfterMs = now + 16L;
        }
    }

    /**
     * Retains one already-decoded visible source until its universal CAV8 archive is
     * packaged. The continuation is intentionally detached from source admission: it
     * never consumes another SourceLease and therefore cannot create an Anvil reread.
     */
    private boolean scheduleHotArchivePackaging(RegionImport region, int index,
            int chunkX, int chunkZ, long submittedEpoch,
            DecodedWorldChunkSource source) {
        if (region == null || source == null) return false;
        synchronized (this) {
            if (submittedEpoch != epoch
                    || !repository.isGenerationCurrent(
                            region.key.repositoryGeneration)
                    || region.caveArchived.get(index)) {
                return false;
            }
            if (region.durableCavePackaging.get(index)) return true;
            region.durableCavePackaging.set(index);
        }
        long startedNanos = System.nanoTime();
        archiveWorkers.dynamic(() -> MapRequestLane.BACKGROUND.executorPriority())
                .execute(() -> continueHotArchivePackaging(region, index,
                        chunkX, chunkZ, submittedEpoch, source,
                        startedNanos, 0));
        return true;
    }

    private void continueHotArchivePackaging(RegionImport region, int index,
            int chunkX, int chunkZ, long submittedEpoch,
            DecodedWorldChunkSource source, long startedNanos, int priorSlices) {
        boolean generationCurrent = submittedEpoch == epoch
                && repository.isGenerationCurrent(
                        region.key.repositoryGeneration);
        if (!generationCurrent) {
            synchronized (this) {
                region.durableCavePackaging.clear(index);
            }
            return;
        }

        CaveChunkTile.Snapshot archive = null;
        int slices = 0;
        long workStartedNanos = System.nanoTime();
        long deadline = workStartedNanos + HOT_ARCHIVE_CONTINUATION_BUDGET_NANOS;
        try {
            MapCancellationToken token = new MapCancellationToken(() ->
                    submittedEpoch == epoch
                            && repository.isGenerationCurrent(
                                    region.key.repositoryGeneration));
            do {
                long sliceStarted = System.nanoTime();
                archive = source.ensureVerticalArchive(token);
                MapPipelineTelemetry.getInstance().recordStageNanos(
                        MapPipelineStage.CAVE_ARCHIVE_SCAN,
                        System.nanoTime() - sliceStarted);
                slices++;
            } while (archive == null && System.nanoTime() < deadline);
        } catch (RuntimeException ignored) {
            // World/generation cancellation is normal; unresolved work is discarded.
        }

        if (archive == null) {
            if (submittedEpoch == epoch
                    && repository.isGenerationCurrent(
                            region.key.repositoryGeneration)) {
                final int completedSlices = priorSlices + slices;
                archiveWorkers.dynamic(
                                () -> MapRequestLane.BACKGROUND.executorPriority())
                        .execute(() -> continueHotArchivePackaging(region, index,
                                chunkX, chunkZ, submittedEpoch, source,
                                startedNanos, completedSlices));
            } else {
                synchronized (this) {
                    region.durableCavePackaging.clear(index);
                }
            }
            return;
        }

        synchronized (this) {
            region.durableCavePackaging.clear(index);
            if (submittedEpoch != epoch
                    || !repository.isGenerationCurrent(
                            region.key.repositoryGeneration)) return;
            region.caveArchived.set(index);
            region.sourcePresent.set(index);
            region.transientDiskAbsent.clear(index);
            region.sourceRetryAfterMs[index] = 0L;
            region.reconcileResolution();
            if (isCurrent(region)) {
                publishReadyPagesLocked(region);
            }
            pumpLocked();
        }

        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        String eventKey = "CAVE_SOURCE_PACKAGED_FROM_HOT_DECODE:"
                + region.key.dimension + ':' + chunkX + ':' + chunkZ;
        if (recorder.shouldEmitEvent(eventKey, 250L)) {
            recorder.event("CAVE_SOURCE_PACKAGED_FROM_HOT_DECODE",
                    "chunk=" + chunkX + ',' + chunkZ
                            + " slices=" + (priorSlices + slices)
                            + " elapsed_ms="
                            + String.format(java.util.Locale.ROOT, "%.3f",
                                    (System.nanoTime() - startedNanos) / 1_000_000.0)
                            + " policy=exact_now_archive_retained_no_anvil_read"
                            + " pass=PASS155");
        }
    }

    private void completeSource(RegionImport region, int index,
            int chunkX, int chunkZ, long submittedEpoch,
            DecodedWorldRegionCache.Result result, Throwable failure) {
        boolean absent = false;
        boolean sourcePresentNow = false;
        boolean sourceAcquisitionSettled = false;
        boolean surfaceProjectionReady = false;
        boolean caveArchiveReady = false;
        boolean caveArchiveBuilding = false;
        boolean surfaceProtoDeferred = false;
        boolean presentationAdvanced = false;
        boolean surfaceNeeded;
        boolean caveNeeded;
        boolean caveNeededByViewport;
        boolean completingAfterViewportExit;
        ProjectionKey presentationTarget;
        MapRequestLane projectionLane;
        synchronized (this) {
            /*
             * Snapshot unresolved products, not just historical requirement bits.
             * PASS104 could finish a source after its viewport disappeared and still
             * fan out surface=false/cave=false work. It could also rebuild the cave
             * archive on every retry when only Surface was waiting for a newer FULL
             * .mca save.
             */
            surfaceNeeded = region.surfaceRequiredSources.get(index)
                    && !region.surfaceProjected.get(index);
            caveNeededByViewport = region.caveRequiredSources.get(index)
                    && !region.caveArchived.get(index);
            caveNeeded = (caveNeededByViewport
                    || region.durableCaveInFlight.get(index))
                    && !region.caveArchived.get(index);
            presentationTarget = region.foregroundPresentationTarget(index);
            MapRequestLane exactPresentationLane =
                    region.foregroundPresentationLane(index);
            completingAfterViewportExit = caveNeeded && !caveNeededByViewport
                    && presentationTarget == null;
            projectionLane = completingAfterViewportExit
                    ? MapRequestLane.PREFETCH
                    : exactPresentationLane != null
                            ? exactPresentationLane : region.lane;
        }
        boolean hadProductDemand = surfaceNeeded || caveNeeded;
        try {
            if (submittedEpoch != epoch || !isCurrent(region)) return;
            if (!hadProductDemand || failure != null || result == null) return;
            switch (result.state()) {
                case PRESENT -> {
                    MapCancellationToken token = new MapCancellationToken(
                            () -> submittedEpoch == epoch && isCurrent(region));
                    DecodedWorldChunkSource source = result.source();
                    if (source != null) {
                        sourcePresentNow = true;
                        /*
                         * Surface has a stronger coherence requirement than Cave.
                         * A proto-generation Anvil chunk can already contain useful
                         * vertical sections for retained cave source while its
                         * Status is not FULL and FEATURES (trees/vegetation) are not
                         * durable yet. Build/commit each requested product
                         * independently so the cave archive is retained once and
                         * only Surface retries when the save is not authoritative.
                         */
                        boolean surfaceAuthoritative =
                                source.hasAuthoritativeSurfaceSource();

                        /*
                         * PASS142 active-path split:
                         *
                         * Visible presentation consumes the decoded immutable chunk
                         * directly, exactly like Xaero's world-save reader builds the
                         * requested caveStart/caveDepth tile from its 4x4 NBT batch.
                         * The universal all-height archive is a reusable background
                         * product and must never be a prerequisite for the exact page
                         * currently on screen.
                         */
                        if (presentationTarget != null) {
                            long caveProjectionStart = System.nanoTime();
                            DenseCaveTile exactTile = source.projectCaveImmediate(
                                    displayProjector,
                                    presentationTarget.view,
                                    presentationTarget.projectionTopY,
                                    DenseCaveTile.Source.WORLD_SAVE,
                                    token);
                            MapPipelineTelemetry.getInstance().recordStageNanos(
                                    MapPipelineStage.CAVE_PROJECTION,
                                    System.nanoTime() - caveProjectionStart);
                            if (exactTile != null) {
                                presentationAdvanced = repository.commitDisplayTile(
                                        exactTile,
                                        region.key.repositoryGeneration);
                            }
                        }

                        /*
                         * PASS156: do NOT fan every visible exact source into an
                         * all-height archive build. PASS155 produced 28k hot-package
                         * completions in one run and turned cache refinement into the
                         * dominant allocation/GC workload. Xaero's foreground writer
                         * builds the requested cave representation; durable refill is
                         * separate maintenance. Keep the decoded exact path short.
                         */
                        boolean hotArchiveContinuation = false;

                        boolean buildArchiveNow = caveNeeded
                                && presentationTarget == null
                                && !caveArchiveReady;
                        long projectionStart = System.nanoTime();
                        DecodedWorldChunkSource.ProjectionBundle bundle = source
                                .prepareProjectionBundle(
                                        surfaceNeeded && surfaceAuthoritative,
                                        buildArchiveNow,
                                        com.velorise.simplemap.client.MapConfig.displayFlowers,
                                        token);
                        MapPipelineTelemetry.getInstance().recordStageNanos(
                                MapPipelineStage.WORLD_SOURCE_FANOUT,
                                System.nanoTime() - projectionStart);
                        if (surfaceNeeded) {
                            surfaceProjectionReady = surfaceAuthoritative
                                    && bundle != null
                                    && bundle.surfaceColumns() != null
                                    && surfaceReconstructor.acceptProjection(
                                            region.level, chunkX, chunkZ,
                                            bundle.surfaceColumns(),
                                            projectionLane);
                            if (!surfaceAuthoritative) {
                                surfaceProtoDeferred = true;
                                /*
                                 * PASS114: disk Status!=FULL is not negative Surface
                                 * authority when Minecraft already has the same chunk
                                 * as a live FULL LevelChunk. Do not wait 30 seconds for
                                 * the next Anvil probe in that case. Nudge the canonical
                                 * live Surface writer; the hook marshals itself onto the
                                 * client thread and no-ops when the live chunk is absent.
                                 */
                                nudgeLiveSurfaceWriter(chunkX, chunkZ);
                                MapDebugRecorder recorder =
                                        MapDebugRecorder.getInstance();
                                if (recorder.shouldEmitEvent(
                                        "SURFACE_MCA_PROTO_DEFERRED", 250L)) {
                                    recorder.event("SURFACE_MCA_PROTO_DEFERRED",
                                            "sample_chunk=" + chunkX + ',' + chunkZ
                                                    + " reason=status_not_full"
                                                    + " retry_ms=" + SURFACE_PROTO_RETRY_MS
                                                    + " live_writer_nudged=true");
                                }
                            }
                        }
                        caveArchiveReady = caveArchiveReady || buildArchiveNow
                                && bundle != null
                                && bundle.verticalArchive() != null;
                        /*
                         * Archive completion is retained by the hot-source continuation.
                         * It is no longer represented as a failed/retryable Anvil source.
                         */
                        caveArchiveBuilding = caveNeeded && !caveArchiveReady
                                && hotArchiveContinuation;
                        boolean exactPresentationSatisfied = presentationTarget != null
                                && region.foregroundPresentationTarget(index) == null;
                        sourceAcquisitionSettled =
                                (!surfaceNeeded || surfaceProjectionReady
                                        || surfaceProtoDeferred)
                                && (!caveNeeded || caveArchiveReady
                                        || exactPresentationSatisfied);
                    }
                }
                case ABSENT -> {
                    GeneratedChunkIndex.getInstance().markSavedAbsent(
                            region.level, chunkX, chunkZ);
                    absent = true;
                    sourceAcquisitionSettled = true;
                    surfaceProjectionReady = surfaceNeeded;
                    caveArchiveReady = false;
                    nudgeLiveCaveWriter(chunkX, chunkZ);
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    String eventKey = "CAVE_DISK_ABSENT_TRANSIENT:"
                            + region.key.dimension + ':' + chunkX + ':' + chunkZ;
                    if (recorder.shouldEmitEvent(eventKey, 1_000L)) {
                        recorder.event("CAVE_DISK_ABSENT_TRANSIENT",
                                "chunk=" + chunkX + ',' + chunkZ
                                        + " cave_authority=false"
                                        + " known_empty=false"
                                        + " retry_ms=" + DISK_ABSENT_RETRY_MS
                                        + " live_repair_nudged=true");
                    }
                }
                case FAILED, DEFERRED -> {
                    // The unresolved product remains pending and retries this cell.
                }
            }
        } catch (RuntimeException ignored) {
            // Product bits remain unresolved and are retried under current demand.
        } finally {
            synchronized (this) {
                DecodedWorldRegionCache.SourceLease lease = region.leases[index];
                region.leases[index] = null;
                if (lease != null) lease.close();
                if (region.inFlight.get(index)) {
                    region.inFlight.clear(index);
                    activeSources = Math.max(0, activeSources - 1);
                }
                boolean wasOffscreenCave = region.offscreenCaveInFlight.get(index)
                        || completingAfterViewportExit;
                region.durableCaveInFlight.clear(index);
                region.offscreenCaveInFlight.clear(index);
                if (submittedEpoch != epoch || !isCurrent(region)) {
                    pumpLocked();
                    return;
                }
                boolean coalesceForegroundCompletion =
                        region.hasInFlightForegroundPageSibling(index);
                if (!hadProductDemand) {
                    region.reconcileResolution();
                    region.retryAfterMs = 0L;
                    if (!coalesceForegroundCompletion) pumpLocked();
                    return;
                }

                boolean advanced = presentationAdvanced;
                long nowMs = System.currentTimeMillis();
                if (absent) {
                    region.transientDiskAbsent.set(index);
                    region.sourceSettled.set(index);
                    region.surfaceProjected.set(index);
                    region.caveArchived.clear(index);
                    if (region.liveSourcePending.get(index)) {
                        region.sourcePresent.set(index);
                        region.sourceRetryAfterMs[index] = Long.MAX_VALUE;
                    } else {
                        region.sourcePresent.clear(index);
                        region.sourceRetryAfterMs[index] = nowMs
                                + region.absenceRetryDelayMs(index);
                    }
                    region.surfaceProtoDeferred.clear(index);
                    region.surfaceProtoRetryAfterMs[index] = 0L;
                    advanced = true;
                } else {
                    region.liveSourcePending.clear(index);
                    region.transientDiskAbsent.clear(index);
                    if (sourcePresentNow) region.sourcePresent.set(index);
                    if (surfaceNeeded && surfaceProjectionReady) {
                        region.surfaceProjected.set(index);
                        region.surfaceProtoDeferred.clear(index);
                        region.surfaceProtoRetryAfterMs[index] = 0L;
                        advanced = true;
                    } else if (surfaceNeeded && surfaceProtoDeferred) {
                        region.surfaceProtoDeferred.set(index);
                        region.surfaceProtoRetryAfterMs[index] = nowMs
                                + SURFACE_PROTO_RETRY_MS;
                    }
                    if (caveNeeded && caveArchiveReady) {
                        region.caveArchived.set(index);
                        region.sourceRetryAfterMs[index] = 0L;
                        advanced = true;
                        if (wasOffscreenCave) {
                            MapPipelineTelemetry.getInstance()
                                    .recordCaveSourceArchiveCommittedOffscreen();
                            MapDebugRecorder recorder =
                                    MapDebugRecorder.getInstance();
                            String eventKey = "CAVE_SOURCE_ARCHIVE_COMMITTED_OFFSCREEN:"
                                    + region.key.dimension + ':' + chunkX + ':' + chunkZ;
                            if (recorder.shouldEmitEvent(eventKey, 500L)) {
                                recorder.event(
                                        "CAVE_SOURCE_ARCHIVE_COMMITTED_OFFSCREEN",
                                        "chunk=" + chunkX + ',' + chunkZ
                                                + " generation="
                                                + region.key.repositoryGeneration
                                                + " source_lifetime=viewport_independent");
                            }
                        }
                    }
                    if (sourceAcquisitionSettled) region.sourceSettled.set(index);
                    else region.sourceSettled.clear(index);
                }
                region.reconcileResolution();
                boolean resolvedNow = region.resolved.get(index);
                if (resolvedNow) region.sourceSettled.set(index);
                boolean settledNow = region.sourceSettled.get(index);
                region.retryAfterMs = settledNow ? 0L
                        : region.durableCavePackaging.get(index) ? 0L
                        : nowMs + (caveArchiveBuilding
                                ? ARCHIVE_SLICE_RETRY_MS
                                : FAILED_RETRY_MS);

                if (advanced) {
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    String fanoutKey = "WORLD_SOURCE_PROJECTION_FANOUT:"
                            + region.key.regionX + ':' + region.key.regionZ;
                    if (recorder.shouldEmitEvent(fanoutKey, 250L)) {
                        recorder.event("WORLD_SOURCE_PROJECTION_FANOUT",
                                "region=" + region.key.regionX + ','
                                        + region.key.regionZ
                                        + " surface=" + surfaceNeeded
                                        + " cave=" + caveNeeded
                                        + " resolved="
                                        + region.resolved.cardinality());
                    }
                    if (!coalesceForegroundCompletion) {
                        publishReadyPagesLocked(region);
                        if (region.requiredSourcesSettledForPass()) {
                            region.sourceSettledForPass = true;
                            onRegionSourceReadyLocked(region);
                        }
                    }
                }
                if (coalesceForegroundCompletion) {
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    String eventKey = "CAVE_PAGE_SOURCE_COMPLETION_COALESCED:"
                            + region.key.regionX + ':' + region.key.regionZ;
                    if (recorder.shouldEmitEvent(eventKey, 250L)) {
                        recorder.event("CAVE_PAGE_SOURCE_COMPLETION_COALESCED",
                                "region=" + region.key.regionX + ','
                                        + region.key.regionZ
                                        + " policy=publish_and_pump_on_last_child");
                    }
                    return;
                }
                pumpLocked();
            }
        }
    }

    /**
     * A proto Anvil read is only a disk-state deferral. The live writer may become
     * authoritative immediately after its nudge, so do not wait for the 30-second
     * disk retry before acknowledging that retained Surface chunk.
     */
    private static boolean reconcileDeferredLiveSurfaceLocked(RegionImport region) {
        if (region.surfaceProtoDeferred.isEmpty()) return false;
        boolean advanced = false;
        int acknowledged = 0;
        int checked = 0;
        int cursor = Math.max(0, Math.min(SOURCE_COUNT - 1,
                region.surfaceProtoReconcileCursor));
        int index = region.surfaceProtoDeferred.nextSetBit(cursor);
        boolean wrapped = false;
        if (index < 0) {
            index = region.surfaceProtoDeferred.nextSetBit(0);
            wrapped = true;
        }
        int nextCursor = cursor;
        while (index >= 0 && checked < SURFACE_PROTO_ACK_SLICE) {
            if (wrapped && index >= cursor) break;
            checked++;
            nextCursor = index + 1 >= SOURCE_COUNT ? 0 : index + 1;
            if (!region.surfaceRequiredSources.get(index)
                    || region.surfaceProjected.get(index)) {
                region.surfaceProtoDeferred.clear(index);
                region.surfaceProtoRetryAfterMs[index] = 0L;
            } else {
                int chunkX = region.firstChunkX() + index % SOURCE_EDGE;
                int chunkZ = region.firstChunkZ() + index / SOURCE_EDGE;
                if (MapManager.getInstance().isChunkSurfaceComplete(chunkX, chunkZ)) {
                    region.surfaceProjected.set(index);
                    region.surfaceProtoDeferred.clear(index);
                    region.surfaceProtoRetryAfterMs[index] = 0L;
                    advanced = true;
                    acknowledged++;
                }
            }
            int next = region.surfaceProtoDeferred.nextSetBit(index + 1);
            if (next < 0 && !wrapped) {
                wrapped = true;
                next = region.surfaceProtoDeferred.nextSetBit(0);
            }
            index = next;
        }
        region.surfaceProtoReconcileCursor = nextCursor;
        if (advanced) {
            region.reconcileResolution();
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            String eventKey = "SURFACE_PROTO_LIVE_WRITER_ACK:"
                    + region.key.regionX + ':' + region.key.regionZ;
            if (recorder.shouldEmitEvent(eventKey, 250L)) {
                recorder.event("SURFACE_PROTO_LIVE_WRITER_ACK",
                        "region=" + region.key.regionX + ',' + region.key.regionZ
                                + " acknowledged=" + acknowledged
                                + " checked=" + checked
                                + " deferred_remaining="
                                + region.surfaceProtoDeferred.cardinality());
            }
        }
        return advanced;
    }

    private static void nudgeLiveSurfaceWriter(int chunkX, int chunkZ) {
        int globalPageX = Math.floorDiv(chunkX, CaveLoadHierarchy.CHUNKS_PER_PAGE);
        int globalPageZ = Math.floorDiv(chunkZ, CaveLoadHierarchy.CHUNKS_PER_PAGE);
        int regionX = Math.floorDiv(globalPageX, CaveLoadHierarchy.PAGES_PER_REGION);
        int regionZ = Math.floorDiv(globalPageZ, CaveLoadHierarchy.PAGES_PER_REGION);
        int localPageX = Math.floorMod(globalPageX, CaveLoadHierarchy.PAGES_PER_REGION);
        int localPageZ = Math.floorMod(globalPageZ, CaveLoadHierarchy.PAGES_PER_REGION);
        int localChunkX = Math.floorMod(chunkX, CaveLoadHierarchy.CHUNKS_PER_PAGE);
        int localChunkZ = Math.floorMod(chunkZ, CaveLoadHierarchy.CHUNKS_PER_PAGE);
        int missingSubtileMask = 1 << (localChunkZ
                * CaveLoadHierarchy.CHUNKS_PER_PAGE + localChunkX);
        ChunkScanner.getInstance().nudgeRetainedSurfacePage(
                regionX, regionZ, localPageX, localPageZ, missingSubtileMask);
    }

    private static void nudgeLiveCaveWriter(int chunkX, int chunkZ) {
        CavePipeline.getInstance().repairTransientDiskAbsence(chunkX, chunkZ);
    }

    private void publishReadyPagesLocked(RegionImport region) {
        if (!isCurrent(region)) return;
        region.refreshSchedulingReadyChildren();
        /*
         * A visible 64x64 child only depends on its central 4x4 Minecraft chunks.
         * The one-chunk halo is refinement input, not a publication barrier. PASS86
         * waited for the complete 6x6 window, so a slow or absent neighbour kept an
         * otherwise complete child black while Xaero would publish the child and
         * continue filling neighbouring data independently.
         */
        long centralReadyPageMask = region.centralReadyPageMask();
        /* PASS118: presentation readiness is chunk-coherent, not page-coherent.
         * A 64x64 page is allowed into projection as soon as at least one of its
         * central 16x16 children has a durable Cave source. The projection service
         * already masks incomplete children atomically, so retaining the older
         * 16/16 admission gate here only hid generated Minecraft chunks. */
        long partialPresentReadyPageMask = region.partialPresentReadyPageMask();
        long haloReadyPageMask = region.haloReadyPageMask();
        for (ProjectionDemand demand : region.demands.values()) {
            /*
             * View switching must not wait for another Anvil pass when the shared
             * vertical archive already owns all 16 chunks of a Full page. Xaero
             * changes cave presentation from retained map tiles first and lets the
             * writer refine later. Admit the same RAM fast path here; source-region
             * decoding continues in the background for halo/absence refinement.
             */
            long archiveReadyMask = demand.key.view == CaveView.FULL
                    ? fullArchiveReadyMask(region, demand.pageMask) : 0L;
            /*
             * Central readiness accepts every stable composition of generated and
             * known-absent chunks. PASS86 admitted only all-archive or all-absent
             * Full pages, which rejected the common mixed pages along explored-world
             * edges and produced the narrow strips visible in the runtime capture.
             */
            long projectionReadyMask = centralReadyPageMask
                    | partialPresentReadyPageMask;
            if (demand.key.view == CaveView.FULL) {
                projectionReadyMask |= archiveReadyMask;
            }
            long partialOnlyReadyMask = partialPresentReadyPageMask
                    & ~centralReadyPageMask & demand.pageMask;
            if (partialOnlyReadyMask != 0L) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String eventKey = "CAVE_NATIVE_REGION_CHILD_READY:"
                        + region.key + ':' + demand.key;
                if (recorder.shouldEmitEvent(eventKey, 250L)) {
                    recorder.event("CAVE_NATIVE_REGION_CHILD_READY",
                            "region=" + region.key.regionX + ','
                                    + region.key.regionZ
                                    + " view=" + demand.key.view
                                    + " pages="
                                    + Long.bitCount(partialOnlyReadyMask)
                                    + " policy=chunk_coherent_before_page_complete");
                }
            }
            long archiveOnlyReadyMask = archiveReadyMask
                    & ~centralReadyPageMask;
            long centralBeforeHaloMask = centralReadyPageMask
                    & ~haloReadyPageMask & demand.pageMask;
            if (centralBeforeHaloMask != 0L) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String eventKey = "CAVE_PROJECTION_CENTRAL_READY:"
                        + region.key + ':' + demand.key;
                if (recorder.shouldEmitEvent(eventKey, 500L)) {
                    recorder.event("CAVE_PROJECTION_CENTRAL_READY",
                            "region=" + region.key.regionX + ','
                                    + region.key.regionZ
                                    + " view=" + demand.key.view
                                    + " pages="
                                    + Long.bitCount(centralBeforeHaloMask)
                                    + " policy=central_4x4_before_halo_6x6");
                }
            }
            if (demand.key.view == CaveView.FULL) {
                long mixedMask = mixedPresentAbsentReadyMask(region,
                        centralReadyPageMask & demand.pageMask);
                if (mixedMask != 0L) {
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    String eventKey = "CAVE_FULL_MIXED_AUTHORITY_READY:"
                            + region.key + ':' + demand.key;
                    if (recorder.shouldEmitEvent(eventKey, 500L)) {
                        recorder.event("CAVE_FULL_MIXED_AUTHORITY_READY",
                                "region=" + region.key.regionX + ','
                                        + region.key.regionZ
                                        + " pages=" + Long.bitCount(mixedMask)
                                        + " authority=archive_plus_known_absent");
                    }
                }
            }
            if (archiveOnlyReadyMask != 0L) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String eventKey = "CAVE_FULL_ARCHIVE_FASTPATH:"
                        + region.key + ':' + demand.key;
                if (recorder.shouldEmitEvent(eventKey, 500L)) {
                    recorder.event("CAVE_FULL_ARCHIVE_FASTPATH",
                            "region=" + region.key.regionX + ','
                                    + region.key.regionZ
                                    + " pages="
                                    + Long.bitCount(archiveOnlyReadyMask)
                                    + " source_ready="
                                    + Long.bitCount(centralReadyPageMask
                                            & demand.pageMask));
                }
            }
            /*
             * Publish source-stable children independently. Xaero tracks the version
             * of each region child; it does not restart all visible children because
             * one sibling finishes later. PASS82 waited for the whole native-region
             * foreground mask, then repeatedly resubmitted every page when any source
             * stamp changed. That produced holes, long centre-only phases and hundreds
             * of redundant Full projections.
             */
            /* PASS118: page admission is now child-progressive. The repository
             * revision and projection service coalesce arriving sibling revisions;
             * only complete 16x16 children become visible, so a missing sibling no
             * longer hides generated neighbours and sparse column confetti remains
             * impossible. */
            /*
             * PASS142: exact WORLD_SAVE Dense children are presentation products in
             * their own right. They may exist before the style-independent vertical
             * archive marks this native source cell complete, so drive foreground
             * projection from the repository's exact child mask rather than from the
             * durable archive masks above.
             *
             * Publication remains 16x16-child coherent. The texture manager stages
             * these children into the retained 64x64 page and never exposes mixed
             * Top-Y projections, so waiting for 16/16 here only creates large black
             * 64x64 islands without adding correctness.
             */
            long readyDemand = demand.pageMask;
            long foregroundProjectionMask = 0L;
            long foregroundReadyMask = 0L;
            long backgroundProjectionMask = 0L;
            long now = System.currentTimeMillis();
            while (readyDemand != 0L) {
                int ordinal = Long.numberOfTrailingZeros(readyDemand);
                readyDemand &= readyDemand - 1L;
                int localPageX = ordinal % REGION_PAGES;
                int localPageZ = ordinal / REGION_PAGES;
                int globalPageX = region.key.regionX * REGION_PAGES + localPageX;
                int globalPageZ = region.key.regionZ * REGION_PAGES + localPageZ;
                int firstChunkX = globalPageX * 4;
                int firstChunkZ = globalPageZ * 4;
                long bit = 1L << ordinal;
                int currentChildMask = repository.projectionChildReadyMask(
                        demand.key.view, demand.key.projectionTopY,
                        globalPageX, globalPageZ);
                if (currentChildMask == 0) continue;

                boolean complete = currentChildMask == 0xFFFF;
                boolean sourcePageReady = (centralReadyPageMask & bit) != 0L;
                boolean anyPresent = currentChildMask != 0;
                if (sourcePageReady) {
                    repository.commitDisplayPage(List.of(), demand.key.view,
                            demand.key.projectionTopY, firstChunkX, firstChunkZ,
                            region.key.repositoryGeneration);
                }

                long sourceRevision = repository.getPageRevision(
                        demand.key.view, demand.key.projectionTopY,
                        globalPageX, globalPageZ);
                if (sourceRevision == 0L) continue;

                if ((demand.foregroundMask & bit) != 0L) {
                    /*
                     * PASS143: publication follows the same 4x4 source transaction as
                     * admission. A known-absent Minecraft chunk closes its child
                     * semantically even though it contributes no knownRows; generated
                     * children must have exact/archive authority. Submit once when all
                     * sixteen children are closed, then only on a real post-completion
                     * source mutation. This removes the 1/16 -> 4/16 -> ... revision
                     * churn that made queued exact pages stale before GPU publication.
                     */
                    boolean transactionClosed =
                            region.schedulingReadyCaveChildren[ordinal] == 16;
                    if (!transactionClosed) continue;
                    if (currentChildMask != 0) foregroundReadyMask |= bit;
                    if (currentChildMask != 0
                            && demand.foregroundSubmittedSourceRevisions[ordinal]
                                    != sourceRevision) {
                        demand.foregroundSubmittedSourceRevisions[ordinal] =
                                sourceRevision;
                        demand.foregroundSubmittedChildMasks[ordinal] =
                                currentChildMask;
                        demand.foregroundProgressSinceMs[ordinal] = 0L;
                        demand.foregroundLastSubmitMs[ordinal] = now;
                        demand.foregroundSubmittedMask |= bit;
                        foregroundProjectionMask |= bit;
                    }
                } else if (complete && sourcePageReady && anyPresent
                        && demand.submittedSourceRevisions[ordinal]
                                != sourceRevision) {
                    /*
                     * Background/branch refinement stays complete-page only. Partial
                     * children are a foreground latency optimization and must not
                     * multiply coarse LOD work.
                     */
                    demand.submittedSourceRevisions[ordinal] = sourceRevision;
                    backgroundProjectionMask |= bit;
                }
            }

            boolean refreshForegroundLease = foregroundReadyMask != 0L
                    && (foregroundProjectionMask != 0L
                            || now - demand.lastProjectionLeaseRefreshMs >= 500L);
            if (refreshForegroundLease) {
                if (foregroundProjectionMask != 0L) {
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    String eventKey = "CAVE_NATIVE_REGION_INCREMENTAL_SUBMIT:"
                            + region.key + ':' + demand.key;
                    if (recorder.shouldEmitEvent(eventKey, 250L)) {
                        recorder.event("CAVE_NATIVE_REGION_INCREMENTAL_SUBMIT",
                                "region=" + region.key.regionX + ','
                                        + region.key.regionZ
                                        + " view=" + demand.key.view
                                        + " top_y=" + demand.key.projectionTopY
                                        + " changed_pages="
                                        + Long.bitCount(foregroundProjectionMask)
                                        + " archive_fast_pages="
                                        + Long.bitCount(archiveOnlyReadyMask
                                                & demand.foregroundMask)
                                        + " ready_pages="
                                        + Long.bitCount(foregroundReadyMask)
                                        + '/' + Long.bitCount(demand.foregroundMask)
                                        + " halo_ready="
                                        + Long.bitCount(haloReadyPageMask
                                                & demand.foregroundMask));
                    }
                }
                projections.requestRegion(region.level, region.key.dimension,
                        demand.key.view, demand.key.projectionTopY,
                        region.key.regionX, region.key.regionZ,
                        foregroundProjectionMask, foregroundReadyMask,
                        region.focusPageX, region.focusPageZ,
                        demand.foregroundLane, demand.priority + 120_000,
                        region.key.repositoryGeneration);
                demand.lastProjectionLeaseRefreshMs = now;
            }

            if (backgroundProjectionMask != 0L) {
                projections.requestRegion(region.level, region.key.dimension,
                        demand.key.view, demand.key.projectionTopY,
                        region.key.regionX, region.key.regionZ,
                        backgroundProjectionMask, 0L,
                        region.focusPageX, region.focusPageZ,
                        MapRequestLane.BACKGROUND, demand.priority - 80_000,
                        region.key.repositoryGeneration);
            }
        }
        if (region.visiblePageMask != 0L
                && region.foregroundPresentationComplete()
                && region.visibleSurfacePresentationComplete()
                && region.lane != MapRequestLane.BACKGROUND) {
            region.lane = MapRequestLane.BACKGROUND;
            region.priority -= 200_000;
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent(
                    "CAVE_NATIVE_REGION_VISIBLE_SOURCE_READY:" + region.key, 250L)) {
                recorder.event("CAVE_NATIVE_REGION_VISIBLE_SOURCE_READY",
                        "region=" + region.key.regionX + ',' + region.key.regionZ
                                + " visible_pages="
                                + Long.bitCount(region.visiblePageMask)
                                + " remaining_source_cells="
                                + (SOURCE_COUNT
                                        - region.sourceSettled.cardinality())
                                + " order=per_page_versioned_children");
            }
        }
    }

    private void onRegionSourceReadyLocked(RegionImport region) {
        if (region.completedMs != 0L) return;
        region.completedMs = System.currentTimeMillis();
        publishReadyPagesLocked(region);
        long generatedMask = region.generatedPageMask();
        /*
         * Source ingestion and presentation demand are deliberately separate.
         * Completing the currently required source halo must not expand every old
         * layer/mode demand to all 64 pages of the native region.
         */
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        recorder.event("CAVE_NATIVE_REGION_SOURCE_SETTLED",
                "region=" + region.key.regionX + ',' + region.key.regionZ
                        + " settled=" + region.sourceSettled.cardinality()
                        + " resolved=" + region.resolved.cardinality()
                        + " cave_archived="
                        + region.caveArchived.cardinality()
                        + " transient_absent="
                        + region.transientDiskAbsent.cardinality()
                        + " live_pending="
                        + region.liveSourcePending.cardinality()
                        + " generated_pages=" + Long.bitCount(generatedMask)
                        + " demands=" + region.demands.size()
                        + " authority=separate");
    }

    private static long mixedPresentAbsentReadyMask(RegionImport region,
            long pageMask) {
        long mixed = 0L;
        long remaining = pageMask;
        while (remaining != 0L) {
            int ordinal = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1L;
            int localPageX = ordinal % REGION_PAGES;
            int localPageZ = ordinal / REGION_PAGES;
            boolean anyAbsent = false;
            boolean anyPresent = false;
            for (int chunkZ = 0; chunkZ < 4; chunkZ++) {
                for (int chunkX = 0; chunkX < 4; chunkX++) {
                    int sourceX = localPageX * 4 + chunkX + SOURCE_HALO;
                    int sourceZ = localPageZ * 4 + chunkZ + SOURCE_HALO;
                    int sourceIndex = sourceZ * SOURCE_EDGE + sourceX;
                    if (region.transientDiskAbsent.get(sourceIndex)) anyAbsent = true;
                    else anyPresent = true;
                }
            }
            if (anyAbsent && anyPresent) mixed |= 1L << ordinal;
        }
        return mixed;
    }

    private static long fullArchiveReadyMask(RegionImport region,
            long pageMask) {
        CaveArchiveV2Service archive = CaveArchiveV2Service.getInstance();
        long ready = 0L;
        long remaining = pageMask;
        while (remaining != 0L) {
            int ordinal = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1L;
            int localPageX = ordinal % REGION_PAGES;
            int localPageZ = ordinal / REGION_PAGES;
            int globalPageX = region.key.regionX * REGION_PAGES + localPageX;
            int globalPageZ = region.key.regionZ * REGION_PAGES + localPageZ;
            if (archive.hasFullProjectionPage(globalPageX, globalPageZ)) {
                ready |= 1L << ordinal;
            }
        }
        return ready;
    }

    private boolean isCurrent(RegionImport region) {
        return region != null && repository.isGenerationCurrent(
                region.key.repositoryGeneration);
    }

    private RegionImport getOrCreateRegion(RegionKey key, ServerLevel level) {
        RegionImport region = imports.get(key);
        if (region != null) return region;
        region = new RegionImport(key, level, sequence++);
        imports.put(key, region);
        indexRegion(region);
        return region;
    }

    private void indexRegion(RegionImport region) {
        for (int index = 0; index < SOURCE_COUNT; index++) {
            int chunkX = region.firstChunkX() + index % SOURCE_EDGE;
            int chunkZ = region.firstChunkZ() + index / SOURCE_EDGE;
            long key = CaveTileRepository.pack(chunkX, chunkZ);
            regionCellsByChunk.computeIfAbsent(key,
                    ignored -> new ArrayList<>(2)).add(
                            new RegionCellRef(region, index));
        }
    }

    private void unindexRegion(RegionImport region) {
        for (int index = 0; index < SOURCE_COUNT; index++) {
            int chunkX = region.firstChunkX() + index % SOURCE_EDGE;
            int chunkZ = region.firstChunkZ() + index / SOURCE_EDGE;
            long key = CaveTileRepository.pack(chunkX, chunkZ);
            ArrayList<RegionCellRef> cells = regionCellsByChunk.get(key);
            if (cells == null) continue;
            cells.removeIf(cell -> cell.region == region);
            if (cells.isEmpty()) regionCellsByChunk.remove(key);
        }
    }

    private void trimLocked(long now) {
        var iterator = imports.entrySet().iterator();
        while (iterator.hasNext()) {
            RegionImport region = iterator.next().getValue();
            if (!isCurrent(region)) {
                region.close();
                unindexRegion(region);
                iterator.remove();
                continue;
            }
            if (imports.size() <= MAX_REGIONS) continue;
            if (!region.sourceSettledForPass || region.inFlight.cardinality() > 0
                    || now - region.completedMs < COMPLETED_RETENTION_MS) continue;
            region.close();
            unindexRegion(region);
            iterator.remove();
        }
    }

    private static int canonicalTopY(CaveView view, int topY) {
        return view == CaveView.FULL ? Integer.MIN_VALUE : topY;
    }

    record DebugSnapshot(int regions, int completeRegions, int activeSources,
            int resolvedSourceCells, int inFlightSourceCells,
            int projectionDemands) {
    }

    record PageSourceState(int inFlightChunks, int archivedChunks,
            int absentChunks, int resolvedChunks) {
        private static final PageSourceState EMPTY =
                new PageSourceState(0, 0, 0, 0);
    }

    private record RegionKey(String dimension, int regionX, int regionZ,
            long repositoryGeneration) {
    }

    private record RegionCellRef(RegionImport region, int index) {
    }

    private record ProjectionKey(CaveView view, int projectionTopY) {
    }

    private static final class ProjectionDemand {
        private final ProjectionKey key;
        private long pageMask;
        /** Source fingerprint submitted for each of the 64 native-region pages. */
        private final long[] submittedSourceRevisions = new long[64];
        /** Pages owned by the current visible viewport only. */
        private long foregroundMask;
        /** Foreground pages that have been submitted for their current source stamp. */
        private long foregroundSubmittedMask;
        /** Foreground source fingerprint submitted for each page. */
        private final long[] foregroundSubmittedSourceRevisions = new long[64];
        /** Complete 16x16 children included in the last foreground build. */
        private final int[] foregroundSubmittedChildMasks = new int[64];
        /** First observation time for progress waiting in the coalescer. */
        private final long[] foregroundProgressSinceMs = new long[64];
        private final long[] foregroundLastSubmitMs = new long[64];
        private long lastProjectionLeaseRefreshMs;
        private MapRequestLane foregroundLane = MapRequestLane.FULLSCREEN;
        private int priority;

        private ProjectionDemand(ProjectionKey key, long pageMask,
                int priority) {
            this.key = key;
            this.pageMask = pageMask;
            this.priority = priority;
        }
    }

    /** One child of a coherent 4x4 foreground page transaction. */
    private record SourceBatchItem(int index, int chunkX, int chunkZ,
            DecodedWorldRegionCache.SourceLease lease) { }

    private static final class RegionImport {
        private final RegionKey key;
        private final long sequence;
        /** Current acquisition probe no longer owns active CPU/IO work. */
        private final BitSet sourceSettled = new BitSet(SOURCE_COUNT);
        /** A usable decoded or live source exists; never inferred from absence. */
        private final BitSet sourcePresent = new BitSet(SOURCE_COUNT);
        /** Header presence is known for this source cell. Unknown cells await IO. */
        private final BitSet presenceKnown = new BitSet(SOURCE_COUNT);
        /** Header explicitly reports no saved chunk; do not reopen NBT on a timer. */
        private final BitSet presenceAbsent = new BitSet(SOURCE_COUNT);
        private final BitSet resolved = new BitSet(SOURCE_COUNT);
        /** Last disk/header probe was absent; retryable and non-authoritative. */
        private final BitSet transientDiskAbsent = new BitSet(SOURCE_COUNT);
        /** Disk acquisition is suspended while the canonical live writer runs. */
        private final BitSet liveSourcePending = new BitSet(SOURCE_COUNT);
        private final BitSet inFlight = new BitSet(SOURCE_COUNT);
        /** Cave products captured when a bounded source lease was admitted. */
        private final BitSet durableCaveInFlight = new BitSet(SOURCE_COUNT);
        /**
         * PASS155 retained cache writer. Once an exact visible source has been decoded,
         * the same immutable source object owns archive completion until CAV8 is built.
         * Source admission must never reopen the .mca chunk while this bit is set.
         */
        private final BitSet durableCavePackaging = new BitSet(SOURCE_COUNT);
        /** Durable leases currently demoted because their viewport moved away. */
        private final BitSet offscreenCaveInFlight = new BitSet(SOURCE_COUNT);
        private final DecodedWorldRegionCache.SourceLease[] leases =
                new DecodedWorldRegionCache.SourceLease[SOURCE_COUNT];
        private final Map<ProjectionKey, ProjectionDemand> demands =
                new HashMap<>();
        private final Map<Long, AppliedPresence> appliedPresenceByRegion =
                new HashMap<>();
        private final long[] surfacePageMasks =
                new long[MapRequestLane.values().length];
        private final long[] surfaceViewportGenerations =
                new long[MapRequestLane.values().length];
        private final int[] surfacePriorities =
                new int[MapRequestLane.values().length];
        private final BitSet surfaceRequiredSources = new BitSet(SOURCE_COUNT);
        private final BitSet caveRequiredSources = new BitSet(SOURCE_COUNT);
        private final BitSet surfaceProjected = new BitSet(SOURCE_COUNT);
        private final BitSet caveArchived = new BitSet(SOURCE_COUNT);
        private final BitSet surfaceProtoDeferred = new BitSet(SOURCE_COUNT);
        private final long[] surfaceProtoRetryAfterMs = new long[SOURCE_COUNT];
        private final long[] sourceRetryAfterMs = new long[SOURCE_COUNT];
        /** Retained source work authority; viewport pulses update it differentially. */
        private final RegionSourceFrontier sourceFrontier =
                new RegionSourceFrontier();
        private final int[] schedulingReadyChildren =
                new int[REGION_PAGES * REGION_PAGES];
        private final int[] schedulingReadyChildMasks =
                new int[REGION_PAGES * REGION_PAGES];
        /** Cave-only semantic closure for each 4x4 visible source transaction. */
        private final int[] schedulingReadyCaveChildren =
                new int[REGION_PAGES * REGION_PAGES];
        private final int[] schedulingReadyCaveChildMasks =
                new int[REGION_PAGES * REGION_PAGES];
        private final int[] selectionIndexes = new int[SOURCE_SLICE];
        private final long[] selectionScores = new long[SOURCE_SLICE];
        private final SourcePriorityScratch sourcePriorityScratch =
                new SourcePriorityScratch();
        private long nextVisibleSourceWaitSummaryMs;
        private long nextSourceAdmissionSummaryMs;
        private int sourceAdmissionSummaryCount;
        private int sourceAdmissionSummaryFocus;
        private int sourceAdmissionSummaryCentral;
        private long sourceAdmissionSummaryWaitTotalMs;
        private long sourceAdmissionSummaryWaitMaxMs;
        /** Last successful source admission; used for cross-region anti-starvation. */
        private long lastSourceAdmissionMs;
        private int surfaceProtoReconcileCursor;
        private ServerLevel level;
        private MapRequestLane lane = MapRequestLane.BACKGROUND;
        private int priority;
        private long caveVisiblePageMask;
        private MapRequestLane caveLane = MapRequestLane.BACKGROUND;
        private int cavePriority;
        private long visiblePageMask;
        private int focusPageX;
        private int focusPageZ;
        private long lastSeenMs;
        private long viewportGeneration;
        /** PASS157: Anvil presence is region metadata, not an 80 ms viewport pulse. */
        private long lastPresenceRefreshMs;
        private MapRequestLane lastPresenceLane;
        private long retryAfterMs;
        private long completedMs;
        private boolean sourceSettledForPass;

        private RegionImport(RegionKey key, ServerLevel level, long sequence) {
            this.key = key;
            this.level = level;
            this.sequence = sequence;
        }

        private int firstChunkX() {
            return key.regionX * REGION_CHUNKS - SOURCE_HALO;
        }

        private int firstChunkZ() {
            return key.regionZ * REGION_CHUNKS - SOURCE_HALO;
        }

        private void refreshPresence(AnvilPagePresenceIndex index,
                MapRequestLane requestLane) {
            if (index == null || level == null) return;
            long now = System.currentTimeMillis();
            MapRequestLane effectiveLane = requestLane == null
                    ? MapRequestLane.BACKGROUND : requestLane;
            long intervalMs = effectiveLane == MapRequestLane.FULLSCREEN ? 5_000L
                    : effectiveLane == MapRequestLane.MINIMAP ? 4_000L : 7_500L;
            boolean promoted = lastPresenceLane == null
                    || effectiveLane.strongerThan(lastPresenceLane);
            if (!promoted && now - lastPresenceRefreshMs < intervalMs) return;
            lastPresenceRefreshMs = now;
            lastPresenceLane = effectiveLane;
            int minimumRegionX = Math.floorDiv(firstChunkX(), REGION_CHUNKS);
            int maximumRegionX = Math.floorDiv(
                    firstChunkX() + SOURCE_EDGE - 1, REGION_CHUNKS);
            int minimumRegionZ = Math.floorDiv(firstChunkZ(), REGION_CHUNKS);
            int maximumRegionZ = Math.floorDiv(
                    firstChunkZ() + SOURCE_EDGE - 1, REGION_CHUNKS);
            for (int regionZ = minimumRegionZ; regionZ <= maximumRegionZ; regionZ++) {
                for (int regionX = minimumRegionX; regionX <= maximumRegionX;
                        regionX++) {
                    AnvilPagePresenceIndex.RegionSnapshot snapshot =
                            index.getCached(level, regionX, regionZ);
                    if (snapshot != null) applyPresenceDelta(snapshot, requestLane);
                    index.requestAsync(level, regionX, regionZ, requestLane);
                }
            }
        }

        private boolean isPageKnownAbsent(int globalPageX, int globalPageZ) {
            int firstPageX = Math.floorDiv(firstChunkX() + SOURCE_HALO, 4);
            int firstPageZ = Math.floorDiv(firstChunkZ() + SOURCE_HALO, 4);
            int localPageX = globalPageX - firstPageX;
            int localPageZ = globalPageZ - firstPageZ;
            if (localPageX < 0 || localPageX >= REGION_PAGES
                    || localPageZ < 0 || localPageZ >= REGION_PAGES) return false;
            int firstLocalX = SOURCE_HALO + localPageX * 4;
            int firstLocalZ = SOURCE_HALO + localPageZ * 4;
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    int index = (firstLocalZ + z) * SOURCE_EDGE + firstLocalX + x;
                    if (!presenceKnown.get(index) || !presenceAbsent.get(index)
                            || sourcePresent.get(index) || liveSourcePending.get(index)
                            || inFlight.get(index)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private void applyPresenceDelta(
                AnvilPagePresenceIndex.RegionSnapshot snapshot,
                MapRequestLane requestLane) {
            if (snapshot == null || !snapshot.ready()) return;
            long anvilRegionKey = CaveLoadHierarchy.pack(
                    snapshot.regionX(), snapshot.regionZ());
            AppliedPresence previous = appliedPresenceByRegion.get(anvilRegionKey);
            if (previous != null && previous.generation == snapshot.generation()) return;

            long startedNanos = System.nanoTime();
            long[] currentBits = snapshot.copyBits();
            long[] changedBits = new long[16];
            if (previous == null) {
                // The first local snapshot must establish known absence as well as
                // present bits, but only for cells intersecting this 34x34 import.
                for (int index = 0; index < SOURCE_COUNT; index++) {
                    int chunkX = firstChunkX() + index % SOURCE_EDGE;
                    int chunkZ = firstChunkZ() + index / SOURCE_EDGE;
                    if (Math.floorDiv(chunkX, REGION_CHUNKS) != snapshot.regionX()
                            || Math.floorDiv(chunkZ, REGION_CHUNKS)
                                    != snapshot.regionZ()) continue;
                    int local = Math.floorMod(chunkZ, REGION_CHUNKS) * REGION_CHUNKS
                            + Math.floorMod(chunkX, REGION_CHUNKS);
                    changedBits[local >>> 6] |= 1L << (local & 63);
                }
            } else {
                for (int word = 0; word < changedBits.length; word++) {
                    changedBits[word] = previous.chunkBits[word] ^ currentBits[word];
                }
            }

            boolean changed = false;
            long changedPageMask = 0L;
            int changedChunks = 0;
            int added = 0;
            int removed = 0;
            for (int word = 0; word < changedBits.length; word++) {
                long bits = changedBits[word];
                while (bits != 0L) {
                    int bit = Long.numberOfTrailingZeros(bits);
                    bits &= bits - 1L;
                    int local = (word << 6) + bit;
                    int chunkX = snapshot.regionX() * REGION_CHUNKS
                            + (local & (REGION_CHUNKS - 1));
                    int chunkZ = snapshot.regionZ() * REGION_CHUNKS
                            + (local >>> 5);
                    int localX = chunkX - firstChunkX();
                    int localZ = chunkZ - firstChunkZ();
                    if (localX < 0 || localX >= SOURCE_EDGE
                            || localZ < 0 || localZ >= SOURCE_EDGE) continue;
                    int index = localZ * SOURCE_EDGE + localX;
                    boolean present = (currentBits[word] & (1L << bit)) != 0L;
                    boolean wasPresent = previous != null
                            && (previous.chunkBits[word] & (1L << bit)) != 0L;
                    presenceKnown.set(index);
                    if (present) presenceAbsent.clear(index);
                    else presenceAbsent.set(index);
                    changedChunks++;
                    if (present && !wasPresent) added++;
                    else if (!present && wasPresent) removed++;
                    if (liveSourcePending.get(index)) continue;
                    if (present) {
                        if (transientDiskAbsent.get(index)) {
                            transientDiskAbsent.clear(index);
                            sourceSettled.clear(index);
                            sourcePresent.clear(index);
                            surfaceProjected.clear(index);
                            caveArchived.clear(index);
                            resolved.clear(index);
                            surfaceProtoDeferred.clear(index);
                            surfaceProtoRetryAfterMs[index] = 0L;
                            sourceRetryAfterMs[index] = 0L;
                            changed = true;
                            changedPageMask |= AFFECTED_PAGE_MASKS[index];
                        } else if (!wasPresent) {
                            changed = true;
                            changedPageMask |= AFFECTED_PAGE_MASKS[index];
                        }
                    } else if (!inFlight.get(index)) {
                        if (!sourceSettled.get(index)
                                || !transientDiskAbsent.get(index)) {
                            changed = true;
                            changedPageMask |= AFFECTED_PAGE_MASKS[index];
                        }
                        transientDiskAbsent.set(index);
                        sourceSettled.set(index);
                        sourcePresent.clear(index);
                        surfaceProjected.set(index);
                        caveArchived.clear(index);
                        resolved.clear(index);
                        // The region header refresh owns the retry. Do not reopen a
                        // header-proven absent NBT record every three seconds.
                        sourceRetryAfterMs[index] = 0L;
                        surfaceProtoDeferred.clear(index);
                        surfaceProtoRetryAfterMs[index] = 0L;
                    }
                }
            }
            appliedPresenceByRegion.put(anvilRegionKey,
                    new AppliedPresence(snapshot.generation(), currentBits));
            if (changed) {
                sourceSettledForPass = requiredSourcesSettledForPass();
                // Completion telemetry is emitted by onRegionSourceReadyLocked();
                // do not pre-claim it while applying a presence snapshot.
                completedMs = 0L;
                for (ProjectionDemand demand : demands.values()) {
                    long affected = changedPageMask & demand.pageMask;
                    long remaining = affected;
                    while (remaining != 0L) {
                        int ordinal = Long.numberOfTrailingZeros(remaining);
                        remaining &= remaining - 1L;
                        demand.submittedSourceRevisions[ordinal] = 0L;
                        demand.foregroundSubmittedSourceRevisions[ordinal] = 0L;
                        demand.foregroundSubmittedChildMasks[ordinal] = 0;
                        demand.foregroundProgressSinceMs[ordinal] = 0L;
                        demand.foregroundLastSubmitMs[ordinal] = 0L;
                    }
                    demand.foregroundSubmittedMask &= ~affected;
                }
            }
            long applyUs = (System.nanoTime() - startedNanos) / 1_000L;
            /*
             * PASS138-B4: normal profiling previously emitted tens of thousands of
             * per-import presence JSON records, including no-op deltas. Keep only
             * changed/slow samples and rate-limit overlapping importer observations.
             */
            if (changedChunks > 0 || applyUs >= 500L) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String eventKey = "ANVIL_PRESENCE_DELTA_APPLIED:"
                        + key.regionX + ':' + key.regionZ + ':'
                        + snapshot.regionX() + ':' + snapshot.regionZ();
                if (recorder.shouldEmitEvent(eventKey, 250L)) {
                    recorder.event("ANVIL_PRESENCE_DELTA_APPLIED",
                            "region=" + snapshot.regionX() + ',' + snapshot.regionZ()
                                    + " generation=" + snapshot.generation()
                                    + " changed_chunks=" + changedChunks
                                    + " added=" + added + " removed=" + removed
                                    + " affected_pages="
                                    + Long.bitCount(changedPageMask)
                                    + " apply_us=" + applyUs
                                    + " lane=" + requestLane
                                    + " thread="
                                    + Thread.currentThread().getName());
                }
            }
        }

        private void setCaveDemand(long pageMask, int globalFocusPageX,
                int globalFocusPageZ, MapRequestLane lane, int priority) {
            MapRequestLane effectiveLane = lane == null
                    ? MapRequestLane.FULLSCREEN : lane;
            boolean structurallySame = caveVisiblePageMask == pageMask
                    && focusPageX == globalFocusPageX
                    && focusPageZ == globalFocusPageZ;
            boolean schedulingSame = caveLane == effectiveLane
                    && cavePriority == priority;
            caveVisiblePageMask = pageMask;
            caveLane = effectiveLane;
            cavePriority = priority;
            focusPageX = globalFocusPageX;
            focusPageZ = globalFocusPageZ;
            /*
             * PASS138-A2: the minimap observes the same viewport many times per
             * second. An identical structural/scheduling demand is a hard no-op;
             * do not rescan the 34x34 source set or touch the retained frontier.
             */
            if (structurallySame && schedulingSame) return;
            recomputeCombinedDemand("CAVE_DEMAND");
        }

        private void clearCaveDemand() {
            if (caveVisiblePageMask == 0L
                    && caveLane == MapRequestLane.BACKGROUND
                    && cavePriority == 0) return;
            caveVisiblePageMask = 0L;
            caveLane = MapRequestLane.BACKGROUND;
            cavePriority = 0;
            recomputeCombinedDemand("CAVE_DEMAND_CLEARED");
        }

        private void setSurfaceDemand(MapRequestLane lane, long pageMask,
                long generation, int priority, int globalFocusPageX,
                int globalFocusPageZ) {
            MapRequestLane effective = lane == null
                    ? MapRequestLane.FULLSCREEN : lane;
            int laneIndex = effective.ordinal();
            surfacePageMasks[laneIndex] = pageMask;
            surfaceViewportGenerations[laneIndex] = generation;
            surfacePriorities[laneIndex] = priority;
            focusPageX = globalFocusPageX;
            focusPageZ = globalFocusPageZ;
            recomputeCombinedDemand("SURFACE_DEMAND");
        }

        private void retireSurfaceLaneIfStale(MapRequestLane lane,
                long currentGeneration) {
            if (lane == null) return;
            int index = lane.ordinal();
            if (surfaceViewportGenerations[index] == currentGeneration) return;
            clearSurfaceDemand(lane);
        }

        private void clearSurfaceDemand(MapRequestLane lane) {
            if (lane == null) return;
            int index = lane.ordinal();
            if (surfacePageMasks[index] == 0L
                    && surfaceViewportGenerations[index] == 0L) return;
            surfacePageMasks[index] = 0L;
            surfaceViewportGenerations[index] = 0L;
            surfacePriorities[index] = 0;
            recomputeCombinedDemand("SURFACE_DEMAND_CLEARED");
        }

        private void recomputeCombinedDemand(String trigger) {
            long combined = caveVisiblePageMask;
            MapRequestLane strongestLane = caveVisiblePageMask == 0L
                    ? MapRequestLane.BACKGROUND : caveLane;
            int strongestPriority = caveVisiblePageMask == 0L ? 0 : cavePriority;
            long surfaceMask = 0L;
            for (MapRequestLane candidate : MapRequestLane.values()) {
                int index = candidate.ordinal();
                long mask = surfacePageMasks[index];
                if (mask == 0L) continue;
                surfaceMask |= mask;
                combined |= mask;
                int candidatePriority = surfacePriorities[index];
                if (candidate.strongerThan(strongestLane)
                        || candidate == strongestLane
                                && candidatePriority > strongestPriority) {
                    strongestLane = candidate;
                    strongestPriority = candidatePriority;
                }
            }
            MapRequestLane previousLane = lane;
            visiblePageMask = combined;
            lane = strongestLane;
            priority = strongestPriority;
            boolean productDemandChanged = sourceFrontier.surfacePageMask()
                    != surfaceMask || sourceFrontier.cavePageMask()
                            != caveVisiblePageMask;
            boolean frontierChange = sourceFrontier.needsUpdate(surfaceMask,
                    caveVisiblePageMask, combined, focusPageX, focusPageZ);
            /*
             * PASS138-A2: lane/priority metadata may change without any structural
             * source change. Avoid unsettledRequiredCount() and frontier work in the
             * overwhelmingly common identical-view refresh case.
             */
            if (!productDemandChanged && !frontierChange) {
                if (previousLane != lane) {
                    accelerateForegroundTransientRetries(
                            System.currentTimeMillis());
                }
                return;
            }
            int remainingBefore = unsettledRequiredCount();
            BitSet nextRequired = null;
            if (productDemandChanged) {
                surfaceRequiredSources.clear();
                caveRequiredSources.clear();
                addRequiredSources(surfaceMask, surfaceRequiredSources);
                /*
                 * PASS150: a visible Cave page owns exactly the sixteen Minecraft
                 * chunks inside its 64x64 body. The one-chunk 66x66 styling border
                 * is opportunistic: resolvePage() may consume neighbour Dense/archive
                 * data when it is already resident, but it must not make those twenty
                 * halo chunks part of the Anvil admission dependency graph.
                 *
                 * Xaero's WorldDataReader reads sixteen NBT futures for one
                 * MapTileChunk and then builds that tile. Keeping a 6x6 caveRequired
                 * footprint here left 132 support-only cells per native region in
                 * source settlement/backlog even after PASS149 stopped actively
                 * loading them during fullscreen. That stale structural demand can
                 * later wake as BACKGROUND work and compete with the next viewport.
                 */
                addCentralRequiredSources(caveVisiblePageMask, caveRequiredSources);
                surfaceProtoDeferred.and(surfaceRequiredSources);
                reconcileResolution();
                nextRequired = (BitSet) surfaceRequiredSources.clone();
                nextRequired.or(caveRequiredSources);
            } else if (frontierChange) {
                nextRequired = sourceFrontier.requiredCopy();
            }
            FrontierUpdate update = sourceFrontier.updateDemand(nextRequired,
                    surfaceMask, caveVisiblePageMask, combined,
                    focusPageX, focusPageZ, key.regionX, key.regionZ,
                    System.currentTimeMillis());
            if (update.changed()) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String eventKey = "SOURCE_FRONTIER_REBUILT:"
                        + key.regionX + ':' + key.regionZ;
                if (recorder.shouldEmitEvent(eventKey, 500L)) {
                    recorder.event("SOURCE_FRONTIER_REBUILT",
                            "region=" + key.regionX + ',' + key.regionZ
                                    + " reason=" + trigger + ':' + update.reason()
                                    + " old_page_mask=0x"
                                    + Long.toHexString(update.oldPageMask())
                                    + " new_page_mask=0x"
                                    + Long.toHexString(update.newPageMask())
                                    + " focus_changed=" + update.focusChanged()
                                    + " cursor_before=" + update.cursorBefore()
                                    + " cursor_after=" + update.cursorAfter()
                                    + " remaining_before=" + remainingBefore
                                    + " added_sources=" + update.addedSources()
                                    + " removed_sources=" + update.removedSources()
                                    + " surface_required="
                                    + surfaceRequiredSources.cardinality()
                                    + " cave_required="
                                    + caveRequiredSources.cardinality()
                                    + " policy=visible_cave_central_4x4_only"
                                    + " generation=" + update.generation());
                }
            }
            if (productDemandChanged || previousLane != lane) {
                accelerateForegroundTransientRetries(System.currentTimeMillis());
            }
            if (productDemandChanged) {
                sourceSettledForPass = requiredSourcesSettledForPass();
                if (!sourceSettledForPass) completedMs = 0L;
            }
        }

        /** Reconciles source completion against the projections currently demanded. */
        private void reconcileResolution() {
            /*
             * Persistence replay / a previous Cave mode may already own the immutable
             * vertical archive for this exact chunk. PASS104 still left the source bit
             * unresolved, so every new viewport reopened the same .mca chunk only to
             * feed CaveArchiveV2Service an identical CompactCaveTile. The validation
             * run recorded 26,924 stale-ignored archive ingests out of 27,448 ingests.
             * Treat retained archive residency as source completion before Anvil
             * admission, exactly like Xaero consumes its retained map region first.
             */
            CaveView demandedCaveView = null;
            if (!demands.isEmpty()) {
                demandedCaveView = demands.keySet().iterator().next().view;
            }
            CaveArchiveV2Service archive = demandedCaveView == null
                    ? null : CaveArchiveV2Service.getInstance();

            BitSet required = (BitSet) surfaceRequiredSources.clone();
            required.or(caveRequiredSources);
            for (int index = required.nextSetBit(0); index >= 0;
                    index = required.nextSetBit(index + 1)) {
                if (archive != null && caveRequiredSources.get(index)
                        && !caveArchived.get(index)
                        && !liveSourcePending.get(index)) {
                    int localX = index % SOURCE_EDGE;
                    int localZ = index / SOURCE_EDGE;
                    int chunkX = firstChunkX() + localX;
                    int chunkZ = firstChunkZ() + localZ;
                    boolean retained = demandedCaveView == CaveView.FULL
                            ? archive.hasFullProjectionChunk(chunkX, chunkZ)
                            : archive.hasCompleteChunk(chunkX, chunkZ);
                    if (retained) caveArchived.set(index);
                }
                boolean complete = (!surfaceRequiredSources.get(index)
                                || surfaceProjected.get(index))
                        && (!caveRequiredSources.get(index)
                                || caveArchived.get(index)
                                || foregroundPresentationSatisfied(index))
                        && !liveSourcePending.get(index);
                if (complete) {
                    resolved.set(index);
                    sourceSettled.set(index);
                    if (caveArchived.get(index)) sourcePresent.set(index);
                } else {
                    if (!inFlight.get(index)) resolved.clear(index);
                    /*
                     * PASS144 root fix: sourceSettled previously survived a product
                     * transition (most importantly Surface -> Cave). The union source
                     * frontier often does not change at all, so no new source bit is
                     * added to wake the importer. A present, non-live source whose new
                     * required product is incomplete must become unsettled again.
                     * Header-proven absence remains settled and closes the Cave child
                     * semantically without a pointless Anvil read.
                     */
                    boolean knownAbsent = presenceKnown.get(index)
                            && presenceAbsent.get(index)
                            && !liveSourcePending.get(index);
                    if (!inFlight.get(index) && !knownAbsent
                            && !liveSourcePending.get(index)) {
                        sourceSettled.clear(index);
                    }
                }
            }
            sourceSettledForPass = requiredSourcesSettledForPass();
            if (!sourceSettledForPass) completedMs = 0L;
        }

        private void retainProjection(CaveView view, int topY) {
            ProjectionKey keep = new ProjectionKey(view,
                    canonicalTopY(view, topY));
            demands.entrySet().removeIf(entry -> !entry.getKey().equals(keep));
        }

        private void retireProjectionDemands() {
            demands.clear();
            clearCaveDemand();
            if (visiblePageMask == 0L) {
                sourceSettledForPass = true;
                completedMs = System.currentTimeMillis();
            }
        }

        /**
         * Fullscreen Cave owns a saved-world snapshot, so inherited MINIMAP live
         * routing cannot remain a prerequisite of the newly visible product.
         * Clear only the central 4x4 children of the exact fullscreen foreground;
         * unrelated live writer work is untouched and may continue normally.
         */
        private int preemptFullscreenLivePending(long pageMask) {
            if (pageMask == 0L || liveSourcePending.isEmpty()) return 0;
            int preempted = 0;
            long remaining = pageMask;
            while (remaining != 0L) {
                int ordinal = Long.numberOfTrailingZeros(remaining);
                remaining &= remaining - 1L;
                int pageX = ordinal % REGION_PAGES;
                int pageZ = ordinal / REGION_PAGES;
                int firstX = pageX * 4 + SOURCE_HALO;
                int firstZ = pageZ * 4 + SOURCE_HALO;
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        int index = (firstZ + z) * SOURCE_EDGE + firstX + x;
                        if (!liveSourcePending.get(index)) continue;
                        liveSourcePending.clear(index);
                        sourceSettled.clear(index);
                        resolved.clear(index);
                        sourceRetryAfterMs[index] = 0L;
                        preempted++;
                    }
                }
            }
            if (preempted > 0) {
                sourceSettledForPass = false;
                completedMs = 0L;
                retryAfterMs = 0L;
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String eventKey = "CAVE_FULLSCREEN_LIVE_PENDING_PREEMPTED:"
                        + key.regionX + ':' + key.regionZ;
                if (recorder.shouldEmitEvent(eventKey, 250L)) {
                    recorder.event("CAVE_FULLSCREEN_LIVE_PENDING_PREEMPTED",
                            "region=" + key.regionX + ',' + key.regionZ
                                    + " cells=" + preempted
                                    + " action=force_saved_snapshot_read"
                                    + " policy=fullscreen_snapshot_over_inherited_live"
                                    + " pass=PASS155");
                }
            }
            return preempted;
        }

        private void setForegroundDemandRetained(CaveView view, int topY,
                long incomingPageMask, long retentionMask,
                MapRequestLane lane, int priority) {
            int canonical = canonicalTopY(view, topY);
            ProjectionKey projectionKey = new ProjectionKey(view, canonical);
            ProjectionDemand demand = demands.computeIfAbsent(projectionKey,
                    key -> new ProjectionDemand(key, 0L, priority));
            long targetMask = (demand.foregroundMask | incomingPageMask)
                    & retentionMask;
            applyForegroundDemand(demand, targetMask, lane, priority);
            if (demand.foregroundLane == MapRequestLane.FULLSCREEN) {
                preemptFullscreenLivePending(incomingPageMask);
            }
        }

        private void clipForegroundDemand(CaveView view, int topY,
                long retentionMask, MapRequestLane lane) {
            ProjectionDemand demand = demands.get(new ProjectionKey(view,
                    canonicalTopY(view, topY)));
            if (demand == null) return;
            long targetMask = demand.foregroundMask & retentionMask;
            applyForegroundDemand(demand, targetMask, lane, demand.priority);
        }

        private void applyForegroundDemand(ProjectionDemand demand, long pageMask,
                MapRequestLane lane, int priority) {
            if (demand.foregroundMask != pageMask) {
                long removed = demand.foregroundMask & ~pageMask;
                while (removed != 0L) {
                    int ordinal = Long.numberOfTrailingZeros(removed);
                    removed &= removed - 1L;
                    demand.foregroundSubmittedSourceRevisions[ordinal] = 0L;
                    demand.foregroundSubmittedChildMasks[ordinal] = 0;
                    demand.foregroundProgressSinceMs[ordinal] = 0L;
                    demand.foregroundLastSubmitMs[ordinal] = 0L;
                }
                demand.foregroundSubmittedMask &= pageMask;
                demand.lastProjectionLeaseRefreshMs = 0L;
            }
            demand.pageMask = pageMask;
            demand.foregroundMask = pageMask;
            demand.foregroundLane = lane == null
                    ? MapRequestLane.FULLSCREEN : lane;
            demand.priority = priority;
        }

        /**
         * Returns the exact foreground projection this source cell can satisfy, or
         * {@code null} when the cell is support-only or a fresh exact Dense tile is
         * already retained. The default world-save pipeline is the native importer,
         * so this check is the presentation/source dependency boundary.
         */
        private boolean hasProjectionLane(MapRequestLane requestedLane) {
            if (requestedLane == null) return false;
            for (ProjectionDemand demand : demands.values()) {
                if (demand.foregroundMask != 0L
                        && demand.foregroundLane == requestedLane) return true;
            }
            return false;
        }

        /** Strongest foreground owner for one 64x64 page ordinal. */
        private MapRequestLane foregroundPageLane(int ordinal) {
            if (ordinal < 0 || ordinal >= REGION_PAGES * REGION_PAGES) return null;
            long bit = 1L << ordinal;
            MapRequestLane result = null;
            for (ProjectionDemand demand : demands.values()) {
                if ((demand.foregroundMask & bit) == 0L) continue;
                if (demand.foregroundLane.strongerThan(result)) {
                    result = demand.foregroundLane;
                }
            }
            return result;
        }

        /** Unsigned-comparable world scanline key: Z first, then X. */
        private long fullscreenScanlineKey(int ordinal) {
            int pageX = ordinal % REGION_PAGES;
            int pageZ = ordinal / REGION_PAGES;
            int globalX = key.regionX * REGION_PAGES + pageX;
            int globalZ = key.regionZ * REGION_PAGES + pageZ;
            long orderedZ = (globalZ ^ Integer.MIN_VALUE) & 0xffffffffL;
            long orderedX = (globalX ^ Integer.MIN_VALUE) & 0xffffffffL;
            return orderedZ << 32 | orderedX;
        }

        private long earliestIncompleteFullscreenScanlineKey() {
            refreshSchedulingReadyChildren();
            long remaining = foregroundCavePageMask();
            boolean found = false;
            long best = 0L;
            while (remaining != 0L) {
                int ordinal = Long.numberOfTrailingZeros(remaining);
                remaining &= remaining - 1L;
                if (foregroundPageLane(ordinal) != MapRequestLane.FULLSCREEN
                        || schedulingReadyCaveChildren[ordinal] >= 16) continue;
                long candidate = fullscreenScanlineKey(ordinal);
                if (!found || Long.compareUnsigned(candidate, best) < 0) {
                    found = true;
                    best = candidate;
                }
            }
            return found ? best : -1L;
        }

        /** Strongest visible foreground owner for this central source cell,
         * independent of whether an exact Dense tile is already resident. */
        private MapRequestLane foregroundDemandLane(int index) {
            int sourceX = index % SOURCE_EDGE;
            int sourceZ = index / SOURCE_EDGE;
            int centralX = sourceX - SOURCE_HALO;
            int centralZ = sourceZ - SOURCE_HALO;
            if (centralX < 0 || centralX >= REGION_CHUNKS
                    || centralZ < 0 || centralZ >= REGION_CHUNKS) {
                return null;
            }
            int ordinal = (centralZ / 4) * REGION_PAGES + centralX / 4;
            long bit = 1L << ordinal;
            MapRequestLane result = null;
            for (ProjectionDemand demand : demands.values()) {
                if ((demand.foregroundMask & bit) == 0L) continue;
                if (demand.foregroundLane.strongerThan(result)) {
                    result = demand.foregroundLane;
                }
            }
            return result;
        }

        private MapRequestLane foregroundPresentationLane(int index) {
            int sourceX = index % SOURCE_EDGE;
            int sourceZ = index / SOURCE_EDGE;
            int centralX = sourceX - SOURCE_HALO;
            int centralZ = sourceZ - SOURCE_HALO;
            if (centralX < 0 || centralX >= REGION_CHUNKS
                    || centralZ < 0 || centralZ >= REGION_CHUNKS) {
                return null;
            }
            int ordinal = (centralZ / 4) * REGION_PAGES + centralX / 4;
            long bit = 1L << ordinal;
            int chunkX = firstChunkX() + sourceX;
            int chunkZ = firstChunkZ() + sourceZ;
            CaveTileRepository tileRepository = CaveTileRepository.getInstance();
            MapRequestLane result = null;
            for (ProjectionDemand demand : demands.values()) {
                if ((demand.foregroundMask & bit) == 0L) continue;
                if (tileRepository.hasFreshDisplayTileOrKnownEmpty(
                        demand.key.view, demand.key.projectionTopY,
                        chunkX, chunkZ, DenseCaveTile.Source.DISK)) {
                    continue;
                }
                if (demand.foregroundLane.strongerThan(result)) {
                    result = demand.foregroundLane;
                }
            }
            return result;
        }

        private ProjectionKey foregroundPresentationTarget(int index) {
            int sourceX = index % SOURCE_EDGE;
            int sourceZ = index / SOURCE_EDGE;
            int centralX = sourceX - SOURCE_HALO;
            int centralZ = sourceZ - SOURCE_HALO;
            if (centralX < 0 || centralX >= REGION_CHUNKS
                    || centralZ < 0 || centralZ >= REGION_CHUNKS) {
                return null;
            }
            int ordinal = (centralZ / 4) * REGION_PAGES + centralX / 4;
            long bit = 1L << ordinal;
            int chunkX = firstChunkX() + sourceX;
            int chunkZ = firstChunkZ() + sourceZ;
            CaveTileRepository tileRepository = CaveTileRepository.getInstance();
            for (ProjectionDemand demand : demands.values()) {
                if ((demand.foregroundMask & bit) == 0L) continue;
                if (!tileRepository.hasFreshDisplayTileOrKnownEmpty(
                        demand.key.view, demand.key.projectionTopY,
                        chunkX, chunkZ, DenseCaveTile.Source.DISK)) {
                    return demand.key;
                }
            }
            return null;
        }

        private boolean hasForegroundPresentation(int index, int ordinal) {
            long bit = 1L << ordinal;
            int sourceX = index % SOURCE_EDGE;
            int sourceZ = index / SOURCE_EDGE;
            int chunkX = firstChunkX() + sourceX;
            int chunkZ = firstChunkZ() + sourceZ;
            CaveTileRepository tileRepository = CaveTileRepository.getInstance();
            boolean demanded = false;
            for (ProjectionDemand demand : demands.values()) {
                if ((demand.foregroundMask & bit) == 0L) continue;
                demanded = true;
                /* PASS163: DISK is presentation fallback, not saved-world source
                 * authority. Let a trusted compact archive settle this source via
                 * caveArchived; otherwise require LIVE/WORLD_SAVE before resolved
                 * can suppress another read. This is especially important for an
                 * all-zero exact DISK tile, because UnifiedCaveTextureManager is
                 * intentionally forbidden to publish it without a strong proof. */
                if (tileRepository.hasFreshDisplayTileOrKnownEmpty(
                        demand.key.view, demand.key.projectionTopY,
                        chunkX, chunkZ, DenseCaveTile.Source.WORLD_SAVE)) {
                    return true;
                }
            }
            return !demanded;
        }

        /** Current exact foreground product can settle source acquisition without
         * pretending that the universal all-height archive exists. A later Top-Y
         * demand automatically reopens the source when this predicate becomes false. */
        private boolean foregroundPresentationSatisfied(int index) {
            int sourceX = index % SOURCE_EDGE;
            int sourceZ = index / SOURCE_EDGE;
            int centralX = sourceX - SOURCE_HALO;
            int centralZ = sourceZ - SOURCE_HALO;
            if (centralX < 0 || centralX >= REGION_CHUNKS
                    || centralZ < 0 || centralZ >= REGION_CHUNKS) return false;
            int ordinal = (centralZ / 4) * REGION_PAGES + centralX / 4;
            long bit = 1L << ordinal;
            if ((foregroundCavePageMask() & bit) == 0L) return false;
            return hasForegroundPresentation(index, ordinal);
        }

        private boolean foregroundPresentationComplete() {
            refreshSchedulingReadyChildren();
            long remaining = foregroundCavePageMask();
            while (remaining != 0L) {
                int ordinal = Long.numberOfTrailingZeros(remaining);
                remaining &= remaining - 1L;
                if (schedulingReadyCaveChildren[ordinal] != 16) return false;
            }
            return true;
        }

        private boolean visibleSurfacePresentationComplete() {
            for (int index = surfaceRequiredSources.nextSetBit(0); index >= 0;
                    index = surfaceRequiredSources.nextSetBit(index + 1)) {
                if (!surfaceProjected.get(index)) return false;
            }
            return true;
        }

        private boolean isForegroundCavePageBatch(List<Integer> indexes) {
            if (indexes == null || indexes.isEmpty() || caveVisiblePageMask == 0L) {
                return false;
            }
            int pageOrdinal = -1;
            for (int index : indexes) {
                int sourceX = index % SOURCE_EDGE;
                int sourceZ = index / SOURCE_EDGE;
                int centralX = sourceX - SOURCE_HALO;
                int centralZ = sourceZ - SOURCE_HALO;
                if (centralX < 0 || centralX >= REGION_CHUNKS
                        || centralZ < 0 || centralZ >= REGION_CHUNKS) return false;
                int candidate = (centralZ / 4) * REGION_PAGES + centralX / 4;
                if ((foregroundCavePageMask() & (1L << candidate)) == 0L) return false;
                if (pageOrdinal < 0) pageOrdinal = candidate;
                else if (pageOrdinal != candidate) return false;
            }
            return true;
        }

        private List<Integer> nextSourceIndexes(int maximum, long nowMs) {
            List<Integer> selected = new ArrayList<>(maximum);
            int[] order = sourceFrontier.ordered();
            if (maximum <= 0 || order.length == 0) return selected;

            refreshSchedulingReadyChildren();
            int limit = Math.min(maximum, SOURCE_SLICE);

            /*
             * PASS143 / Xaero parity: close one visible 4x4 Minecraft-chunk page
             * before spending the same source window on another page. The previous
             * bounded 64-cell lookahead was cheap, but because the frontier can contain
             * 1,156 cells it routinely failed to revisit the one missing child of a
             * 15/16 page for tens of seconds. Page selection scans at most 64 page
             * records, then at most sixteen central children.
             */
            int pageOrdinal = bestForegroundCavePage(nowMs, limit);
            if (pageOrdinal >= 0) {
                int pageX = pageOrdinal % REGION_PAGES;
                int pageZ = pageOrdinal / REGION_PAGES;
                int firstX = pageX * 4 + SOURCE_HALO;
                int firstZ = pageZ * 4 + SOURCE_HALO;
                int bestCount = 0;
                for (int localZ = 0; localZ < 4; localZ++) {
                    for (int localX = 0; localX < 4; localX++) {
                        int index = (firstZ + localZ) * SOURCE_EDGE
                                + firstX + localX;
                        if (!sourceEligible(index, nowMs)) continue;
                        long score = scoreSource(index, nowMs);
                        bestCount = insertSelectionCandidate(index, score,
                                bestCount, limit);
                    }
                }
                for (int i = 0; i < bestCount; i++) {
                    selected.add(selectionIndexes[i]);
                }
                if (!selected.isEmpty()) return selected;
            }
            if (hasIncompleteVisibleCavePage()) {
                /*
                 * A cold visible page exists but the remaining global source capacity
                 * cannot reserve all of its currently eligible central children. Do
                 * not use that leftover capacity to seed another fragmented page. Let
                 * the already-admitted transaction drain, then admit the next page as
                 * one coherent batch.
                 */
                return selected;
            }

            /*
             * Fallback for Surface, halo and post-visible durable archive refinement.
             * Keep the persistent bounded frontier so native-region maintenance never
             * returns to an O(1,156) rescore on every small background admission.
             */
            int bestCount = 0;
            int cursor = sourceFrontier.cursor();
            int inspected = 0;
            int scoredEligible = 0;
            int fairnessIndex = -1;
            long fairnessAgeMs = -1L;

            while (inspected < order.length
                    && scoredEligible < SOURCE_SELECTION_LOOKAHEAD) {
                int position = (cursor + inspected) % order.length;
                inspected++;
                int index = order[position];
                if (!sourceEligible(index, nowMs)) continue;
                scoredEligible++;
                long sourceScore = scoreSource(index, nowMs);
                SourcePriorityScratch scratch = sourcePriorityScratch;
                if (scratch.central
                        && scratch.waitAgeMs >= VISIBLE_SOURCE_FAIRNESS_MS
                        && scratch.waitAgeMs > fairnessAgeMs) {
                    fairnessAgeMs = scratch.waitAgeMs;
                    fairnessIndex = index;
                }
                bestCount = insertSelectionCandidate(index, sourceScore,
                        bestCount, limit);
            }

            int focusProbe = Math.min(16, order.length);
            for (int position = 0; position < focusProbe; position++) {
                int index = order[position];
                if (!sourceEligible(index, nowMs)) continue;
                long sourceScore = scoreSource(index, nowMs);
                bestCount = insertSelectionCandidate(index, sourceScore,
                        bestCount, limit);
            }

            if (fairnessIndex >= 0) {
                bestCount = insertSelectionCandidate(fairnessIndex,
                        Long.MAX_VALUE, bestCount, limit);
            }

            for (int i = 0; i < bestCount; i++) selected.add(selectionIndexes[i]);
            if (inspected > 0) sourceFrontier.advanceCursor(cursor + inspected);
            return selected;
        }

        private int bestForegroundCavePage(long nowMs, int capacity) {
            long remaining = foregroundCavePageMask();
            int bestOrdinal = -1;
            boolean bestFullscreen = false;
            long bestScanline = 0L;
            int bestDistanceSquared = Integer.MAX_VALUE;
            int bestReady = -1;
            long bestOldestWaitMs = -1L;
            while (remaining != 0L) {
                int ordinal = Long.numberOfTrailingZeros(remaining);
                remaining &= remaining - 1L;
                int ready = schedulingReadyCaveChildren[ordinal];
                if (ready >= 16) continue;

                int pageX = ordinal % REGION_PAGES;
                int pageZ = ordinal / REGION_PAGES;
                int firstX = pageX * 4 + SOURCE_HALO;
                int firstZ = pageZ * 4 + SOURCE_HALO;
                int eligible = 0;
                long oldestWaitMs = 0L;
                for (int localZ = 0; localZ < 4; localZ++) {
                    for (int localX = 0; localX < 4; localX++) {
                        int index = (firstZ + localZ) * SOURCE_EDGE
                                + firstX + localX;
                        if (!sourceEligible(index, nowMs)) continue;
                        eligible++;
                        oldestWaitMs = Math.max(oldestWaitMs,
                                sourceFrontier.waitAgeMs(index, nowMs));
                    }
                }
                if (eligible == 0 || eligible > capacity) continue;

                boolean fullscreenPage = foregroundPageLane(ordinal)
                        == MapRequestLane.FULLSCREEN;
                if (fullscreenPage) {
                    long scanline = fullscreenScanlineKey(ordinal);
                    if (!bestFullscreen
                            || Long.compareUnsigned(scanline, bestScanline) < 0
                            || scanline == bestScanline && ready > bestReady
                            || scanline == bestScanline && ready == bestReady
                                    && oldestWaitMs > bestOldestWaitMs) {
                        bestFullscreen = true;
                        bestScanline = scanline;
                        bestReady = ready;
                        bestOldestWaitMs = oldestWaitMs;
                        bestOrdinal = ordinal;
                    }
                    continue;
                }
                if (bestFullscreen) continue;

                int globalX = key.regionX * REGION_PAGES + pageX;
                int globalZ = key.regionZ * REGION_PAGES + pageZ;
                int dx = globalX - focusPageX;
                int dz = globalZ - focusPageZ;
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared < bestDistanceSquared
                        || distanceSquared == bestDistanceSquared
                                && ready > bestReady
                        || distanceSquared == bestDistanceSquared
                                && ready == bestReady
                                && oldestWaitMs > bestOldestWaitMs) {
                    bestDistanceSquared = distanceSquared;
                    bestReady = ready;
                    bestOldestWaitMs = oldestWaitMs;
                    bestOrdinal = ordinal;
                }
            }
            return bestOrdinal;
        }

        private int insertSelectionCandidate(int index, long sourceScore,
                int bestCount, int limit) {
            for (int existing = 0; existing < bestCount; existing++) {
                if (selectionIndexes[existing] == index) return bestCount;
            }
            int insertion = bestCount;
            while (insertion > 0
                    && sourceScore > selectionScores[insertion - 1]) {
                insertion--;
            }
            if (insertion >= limit) return bestCount;
            int last = Math.min(bestCount, limit - 1);
            for (int move = last; move > insertion; move--) {
                selectionIndexes[move] = selectionIndexes[move - 1];
                selectionScores[move] = selectionScores[move - 1];
            }
            selectionIndexes[insertion] = index;
            selectionScores[insertion] = sourceScore;
            return bestCount < limit ? bestCount + 1 : bestCount;
        }

        /**
         * True while another source from the same visible 4x4 transaction is still
         * completing. The caller clears the current source bit before invoking this.
         * Coalescing on this boundary turns sixteen per-chunk orchestration passes
         * into one Xaero-style page completion pass.
         */
        private boolean hasInFlightForegroundPageSibling(int index) {
            int sourceX = index % SOURCE_EDGE;
            int sourceZ = index / SOURCE_EDGE;
            int centralX = sourceX - SOURCE_HALO;
            int centralZ = sourceZ - SOURCE_HALO;
            if (centralX < 0 || centralX >= REGION_CHUNKS
                    || centralZ < 0 || centralZ >= REGION_CHUNKS) {
                return false;
            }
            int ordinal = (centralZ / 4) * REGION_PAGES + centralX / 4;
            if ((foregroundCavePageMask() & (1L << ordinal)) == 0L) return false;
            int firstX = (ordinal % REGION_PAGES) * 4 + SOURCE_HALO;
            int firstZ = (ordinal / REGION_PAGES) * 4 + SOURCE_HALO;
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    int sibling = (firstZ + z) * SOURCE_EDGE + firstX + x;
                    if (inFlight.get(sibling)) return true;
                }
            }
            return false;
        }

        /** Exact Cave pages actually owned by a visible presentation lane.
         * Source-only sticky/halo pages are intentionally excluded: they are
         * maintenance support and must not delay the visible wave. */
        private long foregroundCavePageMask() {
            long mask = 0L;
            for (ProjectionDemand demand : demands.values()) {
                mask |= demand.foregroundMask;
            }
            return mask;
        }

        private boolean hasForegroundCaveDemand() {
            return foregroundCavePageMask() != 0L;
        }

        private boolean hasFullscreenCaveDemand() {
            for (ProjectionDemand demand : demands.values()) {
                if (demand.foregroundLane == MapRequestLane.FULLSCREEN
                        && demand.foregroundMask != 0L) return true;
            }
            return false;
        }

        private boolean hasMinimapCaveDemand() {
            for (ProjectionDemand demand : demands.values()) {
                if (demand.foregroundLane == MapRequestLane.MINIMAP
                        && demand.foregroundMask != 0L) return true;
            }
            return false;
        }

        /** True while a specific foreground lane still owns an unfinished Cave page. */
        private boolean hasIncompleteForegroundCaveLane(MapRequestLane requestedLane) {
            if (requestedLane == null || demands.isEmpty()) return false;
            for (ProjectionDemand demand : demands.values()) {
                if (demand.foregroundLane != requestedLane
                        || demand.foregroundMask == 0L) continue;
                long remaining = demand.foregroundMask;
                while (remaining != 0L) {
                    int ordinal = Long.numberOfTrailingZeros(remaining);
                    remaining &= remaining - 1L;
                    if (schedulingReadyCaveChildren[ordinal] < 16) return true;
                }
            }
            return false;
        }

        private boolean hasVisibleDemand() {
            return visiblePageMask != 0L;
        }

        private boolean hasVisibleCaveDemand() {
            return caveVisiblePageMask != 0L;
        }

        private boolean hasIncompleteVisibleCavePage() {
            long remaining = foregroundCavePageMask();
            while (remaining != 0L) {
                int ordinal = Long.numberOfTrailingZeros(remaining);
                remaining &= remaining - 1L;
                if (schedulingReadyCaveChildren[ordinal] < 16) return true;
            }
            return false;
        }

        /**
         * Minimum squared page distance of unfinished visible Cave work. This is a
         * cheap scheduling key: it reads the already-maintained 64-entry readiness
         * array and never touches NBT/IO. Regions without unfinished Cave work sort
         * behind regions that can still improve the visible projection.
         */
        private int closestIncompleteCavePageDistanceSquared() {
            long remaining = foregroundCavePageMask();
            int best = Integer.MAX_VALUE;
            while (remaining != 0L) {
                int ordinal = Long.numberOfTrailingZeros(remaining);
                remaining &= remaining - 1L;
                if (schedulingReadyCaveChildren[ordinal] >= 16) continue;
                int globalX = key.regionX * REGION_PAGES + ordinal % REGION_PAGES;
                int globalZ = key.regionZ * REGION_PAGES + ordinal / REGION_PAGES;
                int dx = globalX - focusPageX;
                int dz = globalZ - focusPageZ;
                best = Math.min(best, dx * dx + dz * dz);
            }
            return best;
        }

        /**
         * Repairs stale source-settlement inherited from an older product demand.
         * This is intentionally idempotent and bounded to visible 4x4 Cave children.
         */
        private int reopenMissingForegroundSources(long nowMs) {
            refreshSchedulingReadyChildren();
            long remaining = foregroundCavePageMask();
            int reopened = 0;
            while (remaining != 0L) {
                int ordinal = Long.numberOfTrailingZeros(remaining);
                remaining &= remaining - 1L;
                if (schedulingReadyCaveChildren[ordinal] >= 16) continue;
                int pageX = ordinal % REGION_PAGES;
                int pageZ = ordinal / REGION_PAGES;
                int firstX = pageX * 4 + SOURCE_HALO;
                int firstZ = pageZ * 4 + SOURCE_HALO;
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        int index = (firstZ + z) * SOURCE_EDGE + firstX + x;
                        if (caveSourceClosedForPage(index, ordinal)
                                || inFlight.get(index)
                                || liveSourcePending.get(index)
                                || !caveRequiredSources.get(index)
                                || !presenceKnown.get(index)
                                || presenceAbsent.get(index)) {
                            continue;
                        }
                        if (!sourceSettled.get(index)) continue;
                        sourceSettled.clear(index);
                        resolved.clear(index);
                        sourceRetryAfterMs[index] = 0L;
                        reopened++;
                    }
                }
            }
            if (reopened > 0) {
                sourceSettledForPass = false;
                completedMs = 0L;
                retryAfterMs = 0L;
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String eventKey = "CAVE_FOREGROUND_SOURCE_STATE_REOPENED:"
                        + key.regionX + ':' + key.regionZ;
                if (recorder.shouldEmitEvent(eventKey, 250L)) {
                    recorder.event("CAVE_FOREGROUND_SOURCE_STATE_REOPENED",
                            "region=" + key.regionX + ',' + key.regionZ
                                    + " reopened=" + reopened
                                    + " reason=required_product_missing_after_settled"
                                    + " policy=surface_to_cave_state_repair");
                }
            }
            return reopened;
        }

        private long sourceServiceAgeMs(long nowMs) {
            if (lastSourceAdmissionMs <= 0L) {
                return hasVisibleDemand() ? Long.MAX_VALUE / 4L : 0L;
            }
            return Math.max(0L, nowMs - lastSourceAdmissionMs);
        }

        private boolean hasFocusVisibleDemand() {
            int localX = focusPageX - key.regionX * REGION_PAGES;
            int localZ = focusPageZ - key.regionZ * REGION_PAGES;
            if (localX < 0 || localX >= REGION_PAGES
                    || localZ < 0 || localZ >= REGION_PAGES) return false;
            int ordinal = localZ * REGION_PAGES + localX;
            return (visiblePageMask & (1L << ordinal)) != 0L;
        }

        private boolean sourceEligible(int index, long nowMs) {
            if (!sourceFrontier.requires(index) || inFlight.get(index)
                    || durableCavePackaging.get(index)
                    || liveSourcePending.get(index)) return false;
            ProjectionKey foregroundTarget = foregroundPresentationTarget(index);
            MapRequestLane foregroundLane = foregroundPresentationLane(index);
            /*
             * PASS144 safety invariant: a historical Surface-only settlement must
             * never block a newly-required exact Cave product. If the visible exact
             * tile is missing and no durable Cave archive exists, permit the source
             * to reopen even if an older pass left sourceSettled=true.
             */
            boolean missingForegroundCaveSource = foregroundTarget != null
                    && caveRequiredSources.get(index)
                    && !caveArchived.get(index);
            if (sourceSettled.get(index) && !missingForegroundCaveSource) return false;
            /*
             * PASS152: the fullscreen save(false) snapshot is already the authority
             * boundary. Do not put the cached .mca header index in front of the actual
             * ChunkStorage read. A header observed before save(false) can still say
             * ABSENT for a live chunk that the barrier just persisted, which is how
             * PASS151 leaves 12-13 children parked with no source task. Xaero submits
             * all sixteen chunk reads and lets Optional.empty close a genuinely absent
             * leaf; fullscreen Cave now does the same. Presence remains an admission
             * optimization for minimap/background work only.
             */
            boolean fullscreenSnapshotDependency = foregroundTarget != null
                    && foregroundLane == MapRequestLane.FULLSCREEN;
            if (!fullscreenSnapshotDependency
                    && (!presenceKnown.get(index) || presenceAbsent.get(index))) {
                return false;
            }
            if (sourceRetryAfterMs[index] > nowMs && foregroundTarget == null) {
                return false;
            }
            boolean surfacePending = surfaceRequiredSources.get(index)
                    && !surfaceProjected.get(index);
            boolean cavePending = caveRequiredSources.get(index)
                    && !caveArchived.get(index);
            if (cavePending && !surfacePending
                    && foregroundTarget == null
                    && (hasIncompleteVisibleCavePage()
                            || hasFullscreenCaveDemand()
                            || hasMinimapCaveDemand())) {
                /*
                 * Xaero builds the requested cave representation first and does not
                 * construct a universal all-height archive while the fullscreen map
                 * is waiting. Keep archive-only continuation parked for the whole
                 * fullscreen Cave session; it can resume after the screen closes.
                 */
                return false;
            }
            return !surfacePending || cavePending
                    || surfaceProtoRetryAfterMs[index] <= nowMs;
        }

        private void refreshSchedulingReadyChildren() {
            long remaining = visiblePageMask;
            while (remaining != 0L) {
                int ordinal = Long.numberOfTrailingZeros(remaining);
                remaining &= remaining - 1L;
                int pageX = ordinal % REGION_PAGES;
                int pageZ = ordinal / REGION_PAGES;
                int ready = 0;
                int readyMask = 0;
                int caveReady = 0;
                int caveReadyMask = 0;
                int firstX = pageX * 4 + SOURCE_HALO;
                int firstZ = pageZ * 4 + SOURCE_HALO;
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        int sourceIndex = (firstZ + z) * SOURCE_EDGE + firstX + x;
                        int childBit = 1 << (z * 4 + x);
                        if (caveSourceClosedForPage(sourceIndex, ordinal)) {
                            caveReady++;
                            caveReadyMask |= childBit;
                        }
                        if (sourceClosedForPage(sourceIndex, ordinal)) {
                            ready++;
                            readyMask |= childBit;
                        }
                    }
                }
                schedulingReadyChildren[ordinal] = ready;
                schedulingReadyChildMasks[ordinal] = readyMask;
                schedulingReadyCaveChildren[ordinal] = caveReady;
                schedulingReadyCaveChildMasks[ordinal] = caveReadyMask;
            }
        }

        private boolean caveSourceClosedForPage(int index, int ordinal) {
            long bit = 1L << ordinal;
            return (caveVisiblePageMask & bit) == 0L
                    || caveArchived.get(index)
                    || hasForegroundPresentation(index, ordinal)
                    || presenceKnown.get(index) && presenceAbsent.get(index)
                            && sourceSettled.get(index)
                            && !liveSourcePending.get(index);
        }

        private boolean sourceClosedForPage(int index, int ordinal) {
            boolean caveClosed = caveSourceClosedForPage(index, ordinal);
            boolean surfaceClosed = !surfacePageRequired(ordinal)
                    || surfaceProjected.get(index);
            return caveClosed && surfaceClosed;
        }

        private boolean surfacePageRequired(int ordinal) {
            return (sourceFrontier.surfacePageMask() & (1L << ordinal)) != 0L;
        }

        private long scoreSource(int index, long nowMs) {
            int sourceX = index % SOURCE_EDGE;
            int sourceZ = index / SOURCE_EDGE;
            int centralX = sourceX - SOURCE_HALO;
            int centralZ = sourceZ - SOURCE_HALO;
            long waitAgeMs = sourceFrontier.waitAgeMs(index, nowMs);
            int pageOrdinal = -1;
            int childOrdinal = -1;
            boolean central = false;
            boolean focus = false;
            int ready = 0;
            int distanceSquared = Integer.MAX_VALUE;
            if (centralX >= 0 && centralX < REGION_CHUNKS
                    && centralZ >= 0 && centralZ < REGION_CHUNKS) {
                int candidateOrdinal = (centralZ / 4) * REGION_PAGES
                        + centralX / 4;
                if ((visiblePageMask & (1L << candidateOrdinal)) != 0L) {
                    central = true;
                    pageOrdinal = candidateOrdinal;
                    childOrdinal = (centralZ & 3) * 4 + (centralX & 3);
                }
            }
            if (!central) {
                long affected = AFFECTED_PAGE_MASKS[index] & visiblePageMask;
                long bestPageScore = Long.MIN_VALUE;
                while (affected != 0L) {
                    int candidate = Long.numberOfTrailingZeros(affected);
                    affected &= affected - 1L;
                    int globalX = key.regionX * REGION_PAGES
                            + candidate % REGION_PAGES;
                    int globalZ = key.regionZ * REGION_PAGES
                            + candidate / REGION_PAGES;
                    int dx = globalX - focusPageX;
                    int dz = globalZ - focusPageZ;
                    boolean candidateFocus = dx == 0 && dz == 0;
                    int candidateReady = (foregroundCavePageMask()
                            & (1L << candidate)) != 0L
                                    ? schedulingReadyCaveChildren[candidate]
                                    : schedulingReadyChildren[candidate];
                    long pageScore = (candidateFocus ? 100_000L : 0L)
                            + candidateReady * 2_000L
                            - (long) (dx * dx + dz * dz) * 100L;
                    if (pageScore > bestPageScore) {
                        bestPageScore = pageScore;
                        pageOrdinal = candidate;
                        focus = candidateFocus;
                        ready = candidateReady;
                        distanceSquared = dx * dx + dz * dz;
                    }
                }
            } else {
                int globalX = key.regionX * REGION_PAGES
                        + pageOrdinal % REGION_PAGES;
                int globalZ = key.regionZ * REGION_PAGES
                        + pageOrdinal / REGION_PAGES;
                int dx = globalX - focusPageX;
                int dz = globalZ - focusPageZ;
                focus = dx == 0 && dz == 0;
                ready = (foregroundCavePageMask() & (1L << pageOrdinal)) != 0L
                        ? schedulingReadyCaveChildren[pageOrdinal]
                        : schedulingReadyChildren[pageOrdinal];
                distanceSquared = dx * dx + dz * dz;
            }

            ProjectionKey presentationTarget =
                    foregroundPresentationTarget(index);
            boolean caveArchiveOnly = caveRequiredSources.get(index)
                    && !caveArchived.get(index)
                    && presentationTarget == null;

            long score = central ? 2_000_000L : 100_000L;
            if (presentationTarget != null) {
                // Visible exact source is a dependency. Durable archive refinement
                // may never outrank the source cell that can put a real child on
                // screen now.
                score += 4_000_000L;
            } else if (caveArchiveOnly
                    && !surfaceRequiredSources.get(index)) {
                score -= 1_500_000L;
            }
            if (focus) score += central ? 1_000_000L : 50_000L;
            score += (long) ready * (central ? 30_000L : 2_000L);
            if (distanceSquared != Integer.MAX_VALUE) {
                score -= (long) distanceSquared * (central ? 1_000L : 100L);
            }
            long ageBonus = central
                    ? Math.min(1_500_000L, waitAgeMs * 2_000L)
                    : Math.min(200_000L, waitAgeMs * 200L);
            score += ageBonus;
            sourcePriorityScratch.score = score;
            sourcePriorityScratch.pageOrdinal = pageOrdinal;
            sourcePriorityScratch.childOrdinal = childOrdinal;
            sourcePriorityScratch.focus = focus;
            sourcePriorityScratch.central = central;
            sourcePriorityScratch.readyChildren = ready;
            sourcePriorityScratch.waitAgeMs = waitAgeMs;
            return score;
        }

        private void recordSourceAdmission(int index, long nowMs) {
            lastSourceAdmissionMs = nowMs;
            scoreSource(index, nowMs);
            SourcePriorityScratch scratch = sourcePriorityScratch;
            sourceAdmissionSummaryCount++;
            if (scratch.focus) sourceAdmissionSummaryFocus++;
            if (scratch.central) sourceAdmissionSummaryCentral++;
            sourceAdmissionSummaryWaitTotalMs += scratch.waitAgeMs;
            sourceAdmissionSummaryWaitMaxMs = Math.max(
                    sourceAdmissionSummaryWaitMaxMs, scratch.waitAgeMs);
        }

        private void emitSourceAdmissionSummary(long nowMs) {
            if (sourceAdmissionSummaryCount == 0
                    || nowMs < nextSourceAdmissionSummaryMs) return;
            nextSourceAdmissionSummaryMs = nowMs + 1_000L;
            long averageWait = sourceAdmissionSummaryWaitTotalMs
                    / Math.max(1, sourceAdmissionSummaryCount);
            MapDebugRecorder.getInstance().event(
                    "SOURCE_ADMISSION_SUMMARY",
                    "region=" + key.regionX + ',' + key.regionZ
                            + " lane=" + lane
                            + " admitted=" + sourceAdmissionSummaryCount
                            + " focus=" + sourceAdmissionSummaryFocus
                            + " central=" + sourceAdmissionSummaryCentral
                            + " halo=" + (sourceAdmissionSummaryCount
                                    - sourceAdmissionSummaryCentral)
                            + " wait_avg_ms=" + averageWait
                            + " wait_max_ms=" + sourceAdmissionSummaryWaitMaxMs
                            + " frontier_required="
                            + sourceFrontier.debug().requiredSources());
            sourceAdmissionSummaryCount = 0;
            sourceAdmissionSummaryFocus = 0;
            sourceAdmissionSummaryCentral = 0;
            sourceAdmissionSummaryWaitTotalMs = 0L;
            sourceAdmissionSummaryWaitMaxMs = 0L;
        }

        private void emitVisibleSourceWaitTelemetry(long nowMs) {
            if (nowMs < nextVisibleSourceWaitSummaryMs) return;
            nextVisibleSourceWaitSummaryMs = nowMs + 1_000L;
            long remaining = foregroundCavePageMask();
            int incompletePages = 0;
            int worstOrdinal = -1;
            int worstReady = 16;
            int worstInFlight = 0;
            int worstLivePending = 0;
            int worstRetryWaiting = 0;
            int worstPresenceUnknown = 0;
            int worstArchivePackaging = 0;
            long worstMissingMs = 0L;
            while (remaining != 0L) {
                int ordinal = Long.numberOfTrailingZeros(remaining);
                remaining &= remaining - 1L;
                int ready = schedulingReadyCaveChildren[ordinal];
                if (ready >= 16) continue;
                incompletePages++;
                int localPageX = ordinal % REGION_PAGES;
                int localPageZ = ordinal / REGION_PAGES;
                int firstX = localPageX * 4 + SOURCE_HALO;
                int firstZ = localPageZ * 4 + SOURCE_HALO;
                int sourceInFlight = 0;
                int livePending = 0;
                int retryWaiting = 0;
                int presenceUnknownCount = 0;
                int archivePackaging = 0;
                long oldestMissingMs = 0L;
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        int index = (firstZ + z) * SOURCE_EDGE + firstX + x;
                        if (caveSourceClosedForPage(index, ordinal)) continue;
                        if (inFlight.get(index)) sourceInFlight++;
                        if (liveSourcePending.get(index)) livePending++;
                        if (durableCavePackaging.get(index)) archivePackaging++;
                        if (!presenceKnown.get(index)) presenceUnknownCount++;
                        if (sourceRetryAfterMs[index] > nowMs
                                && sourceRetryAfterMs[index] != Long.MAX_VALUE) {
                            retryWaiting++;
                        }
                        oldestMissingMs = Math.max(oldestMissingMs,
                                sourceFrontier.waitAgeMs(index, nowMs));
                    }
                }
                if (oldestMissingMs > worstMissingMs) {
                    worstMissingMs = oldestMissingMs;
                    worstOrdinal = ordinal;
                    worstReady = ready;
                    worstInFlight = sourceInFlight;
                    worstLivePending = livePending;
                    worstRetryWaiting = retryWaiting;
                    worstPresenceUnknown = presenceUnknownCount;
                    worstArchivePackaging = archivePackaging;
                }
            }
            if (worstOrdinal < 0 || worstMissingMs < 250L) return;
            int localPageX = worstOrdinal % REGION_PAGES;
            int localPageZ = worstOrdinal / REGION_PAGES;
            int globalPageX = key.regionX * REGION_PAGES + localPageX;
            int globalPageZ = key.regionZ * REGION_PAGES + localPageZ;
            MapDebugRecorder.getInstance().event(
                    "VISIBLE_CAVE_SOURCE_WAIT_SUMMARY",
                    "region=" + key.regionX + ',' + key.regionZ
                            + " incomplete_pages=" + incompletePages
                            + " worst_page=" + globalPageX + ',' + globalPageZ
                            + " ready_count=" + worstReady
                            + " missing_children=" + (16 - worstReady)
                            + " oldest_missing_ms=" + worstMissingMs
                            + " source_inflight=" + worstInFlight
                            + " live_pending=" + worstLivePending
                            + " retry_waiting=" + worstRetryWaiting
                            + " archive_packaging=" + worstArchivePackaging
                            + " presence_unknown=" + worstPresenceUnknown
                            + " focus=" + (globalPageX == focusPageX
                                    && globalPageZ == focusPageZ));
        }

        private long nextSurfaceProtoRetryAfter(long nowMs) {
            long earliest = Long.MAX_VALUE;
            for (int index = surfaceRequiredSources.nextSetBit(0); index >= 0;
                    index = surfaceRequiredSources.nextSetBit(index + 1)) {
                if (surfaceProjected.get(index) || inFlight.get(index)) continue;
                if (caveRequiredSources.get(index) && !caveArchived.get(index)) {
                    continue;
                }
                long retry = surfaceProtoRetryAfterMs[index];
                if (retry > nowMs && retry < earliest) earliest = retry;
            }
            return earliest == Long.MAX_VALUE ? 0L : earliest;
        }

        private long nextSourceRetryAfter(long nowMs) {
            long earliest = Long.MAX_VALUE;
            BitSet required = (BitSet) surfaceRequiredSources.clone();
            required.or(caveRequiredSources);
            for (int index = required.nextSetBit(0); index >= 0;
                    index = required.nextSetBit(index + 1)) {
                if (resolved.get(index) || inFlight.get(index)) continue;
                long retry = sourceRetryAfterMs[index];
                if (retry > nowMs && retry < earliest) earliest = retry;
            }
            return earliest == Long.MAX_VALUE ? 0L : earliest;
        }

        private long absenceRetryDelayMs(int index) {
            return lane == MapRequestLane.BACKGROUND
                    ? DISK_ABSENT_RETRY_MS
                    : FOREGROUND_DISK_ABSENT_RETRY_MS;
        }

        private void accelerateForegroundTransientRetries(long nowMs) {
            if (lane == MapRequestLane.BACKGROUND) return;
            BitSet required = (BitSet) caveRequiredSources.clone();
            required.and(transientDiskAbsent);
            required.andNot(liveSourcePending);
            required.andNot(presenceAbsent);
            long foregroundRetry = nowMs + FOREGROUND_DISK_ABSENT_RETRY_MS;
            for (int index = required.nextSetBit(0); index >= 0;
                    index = required.nextSetBit(index + 1)) {
                long current = sourceRetryAfterMs[index];
                if (current == 0L || current > foregroundRetry) {
                    sourceRetryAfterMs[index] = foregroundRetry;
                }
            }
        }

        /** Reopens only settled cells whose event-driven safety timer expired. */
        private void wakeExpiredSettledSources(long nowMs) {
            boolean woken = false;
            BitSet required = (BitSet) surfaceRequiredSources.clone();
            required.or(caveRequiredSources);
            for (int index = required.nextSetBit(0); index >= 0;
                    index = required.nextSetBit(index + 1)) {
                if (!sourceSettled.get(index) || inFlight.get(index)) continue;
                if (liveSourcePending.get(index)) continue;
                boolean transientRetry = transientDiskAbsent.get(index)
                        && !presenceAbsent.get(index)
                        && sourceRetryAfterMs[index] > 0L
                        && sourceRetryAfterMs[index] <= nowMs;
                boolean protoRetry = surfaceProtoDeferred.get(index)
                        && surfaceProtoRetryAfterMs[index] > 0L
                        && surfaceProtoRetryAfterMs[index] <= nowMs;
                if (!transientRetry && !protoRetry) continue;
                sourceSettled.clear(index);
                if (transientRetry) sourceRetryAfterMs[index] = 0L;
                if (protoRetry) surfaceProtoRetryAfterMs[index] = 0L;
                woken = true;
            }
            if (woken) {
                sourceSettledForPass = false;
                completedMs = 0L;
                retryAfterMs = 0L;
            }
        }

        /**
         * Pages with at least one durable, generated central child. Known-absent
         * children do not make a partial page present; a page containing only
         * unresolved/absent children waits for the normal complete-page path.
         * This mirrors Xaero's MapTileChunk rule: loaded children are publishable
         * without waiting for all sibling tiles.
         */
        private long partialPresentReadyPageMask() {
            long result = 0L;
            for (int pageZ = 0; pageZ < REGION_PAGES; pageZ++) {
                for (int pageX = 0; pageX < REGION_PAGES; pageX++) {
                    boolean anyPresent = false;
                    int firstX = pageX * 4 + SOURCE_HALO;
                    int firstZ = pageZ * 4 + SOURCE_HALO;
                    for (int localZ = 0; localZ < 4 && !anyPresent; localZ++) {
                        int row = firstZ + localZ;
                        for (int localX = 0; localX < 4; localX++) {
                            int index = row * SOURCE_EDGE + firstX + localX;
                            if (caveArchived.get(index)
                                    && !transientDiskAbsent.get(index)) {
                                anyPresent = true;
                                break;
                            }
                        }
                    }
                    if (anyPresent) {
                        result |= 1L << (pageZ * REGION_PAGES + pageX);
                    }
                }
            }
            return result;
        }

        private long centralReadyPageMask() {
            long result = 0L;
            for (int pageZ = 0; pageZ < REGION_PAGES; pageZ++) {
                for (int pageX = 0; pageX < REGION_PAGES; pageX++) {
                    boolean ready = true;
                    int firstX = pageX * 4 + SOURCE_HALO;
                    int firstZ = pageZ * 4 + SOURCE_HALO;
                    for (int localZ = 0; localZ < 4 && ready; localZ++) {
                        int row = firstZ + localZ;
                        for (int localX = 0; localX < 4; localX++) {
                            if (!caveArchived.get(
                                    row * SOURCE_EDGE + firstX + localX)) {
                                ready = false;
                                break;
                            }
                        }
                    }
                    if (ready) result |= 1L << (pageZ * REGION_PAGES + pageX);
                }
            }
            return result;
        }

        private long haloReadyPageMask() {
            long result = 0L;
            for (int pageZ = 0; pageZ < REGION_PAGES; pageZ++) {
                for (int pageX = 0; pageX < REGION_PAGES; pageX++) {
                    boolean ready = true;
                    int firstX = pageX * 4;
                    int firstZ = pageZ * 4;
                    for (int localZ = 0; localZ < 6 && ready; localZ++) {
                        int row = firstZ + localZ;
                        for (int localX = 0; localX < 6; localX++) {
                            if (!caveArchived.get(
                                    row * SOURCE_EDGE + firstX + localX)) {
                                ready = false;
                                break;
                            }
                        }
                    }
                    if (ready) result |= 1L << (pageZ * REGION_PAGES + pageX);
                }
            }
            return result;
        }

        private boolean requiredSourcesSettledForPass() {
            if (visiblePageMask == 0L) return true;
            boolean foregroundCave = hasForegroundCaveDemand();
            for (int index = 0; index < SOURCE_COUNT; index++) {
                if (surfaceRequiredSources.get(index)
                        && !sourceSettled.get(index)) return false;
                if (!caveRequiredSources.get(index)
                        || sourceSettled.get(index)) continue;
                /* PASS156: support-only Cave halo is optional refinement while a
                 * visible foreground page owns the pass. It must not keep the region
                 * control-plane 'unsettled' after all exact 4x4 children are ready. */
                if (foregroundCave && foregroundDemandLane(index) == null) continue;
                return false;
            }
            return true;
        }

        private int unsettledRequiredCount() {
            int count = 0;
            boolean foregroundCave = hasForegroundCaveDemand();
            for (int index = 0; index < SOURCE_COUNT; index++) {
                if (surfaceRequiredSources.get(index)
                        && !sourceSettled.get(index)) {
                    count++;
                    continue;
                }
                if (caveRequiredSources.get(index)
                        && !sourceSettled.get(index)
                        && !(foregroundCave && foregroundDemandLane(index) == null)) {
                    count++;
                }
            }
            return count;
        }

        private long generatedPageMask() {
            long result = 0L;
            for (int pageZ = 0; pageZ < REGION_PAGES; pageZ++) {
                for (int pageX = 0; pageX < REGION_PAGES; pageX++) {
                    boolean present = false;
                    for (int chunkZ = 0; chunkZ < 4 && !present; chunkZ++) {
                        for (int chunkX = 0; chunkX < 4; chunkX++) {
                            int sourceX = pageX * 4 + chunkX + SOURCE_HALO;
                            int sourceZ = pageZ * 4 + chunkZ + SOURCE_HALO;
                            int index = sourceZ * SOURCE_EDGE + sourceX;
                            if (resolved.get(index)
                                    && !transientDiskAbsent.get(index)) {
                                present = true;
                                break;
                            }
                        }
                    }
                    if (present) result |= 1L << (pageZ * REGION_PAGES + pageX);
                }
            }
            return result;
        }

        private void close() {
            for (int index = 0; index < leases.length; index++) {
                DecodedWorldRegionCache.SourceLease lease = leases[index];
                leases[index] = null;
                if (lease != null) lease.close();
            }
            inFlight.clear();
            appliedPresenceByRegion.clear();
            sourceFrontier.clear();
        }
    }

    /** Persistent structural frontier shared by repeated viewport observations. */
    static final class RegionSourceFrontier {
        private final BitSet required = new BitSet(SOURCE_COUNT);
        private final long[] requiredSinceMs = new long[SOURCE_COUNT];
        private int[] ordered = new int[0];
        private int cursor;
        private long surfacePageMask;
        private long cavePageMask;
        private long visiblePageMask;
        private int focusPageX = Integer.MIN_VALUE;
        private int focusPageZ = Integer.MIN_VALUE;
        private long generation;
        private long rebuilds;
        private long reusedRefreshes;

        boolean needsUpdate(long nextSurfacePageMask, long nextCavePageMask,
                long nextVisiblePageMask, int nextFocusPageX,
                int nextFocusPageZ) {
            return surfacePageMask != nextSurfacePageMask
                    || cavePageMask != nextCavePageMask
                    || visiblePageMask != nextVisiblePageMask
                    || focusPageX != nextFocusPageX
                    || focusPageZ != nextFocusPageZ;
        }

        FrontierUpdate updateDemand(BitSet nextRequired,
                long nextSurfacePageMask, long nextCavePageMask,
                long nextVisiblePageMask, int nextFocusPageX,
                int nextFocusPageZ, int regionX, int regionZ, long nowMs) {
            if (!needsUpdate(nextSurfacePageMask, nextCavePageMask,
                    nextVisiblePageMask, nextFocusPageX, nextFocusPageZ)) {
                reusedRefreshes++;
                return new FrontierUpdate(false, "UNCHANGED",
                        visiblePageMask, visiblePageMask, false,
                        cursor, cursor, 0, 0, generation);
            }
            if (nextRequired == null) {
                throw new IllegalArgumentException(
                        "structural frontier update requires source bits");
            }
            long oldVisiblePageMask = visiblePageMask;
            int cursorBefore = cursor;
            boolean focusChanged = focusPageX != nextFocusPageX
                    || focusPageZ != nextFocusPageZ;
            boolean productChanged = surfacePageMask != nextSurfacePageMask
                    || cavePageMask != nextCavePageMask;
            int anchor = ordered.length == 0 ? -1
                    : ordered[Math.floorMod(cursor, ordered.length)];

            BitSet added = (BitSet) nextRequired.clone();
            added.andNot(required);
            BitSet removed = (BitSet) required.clone();
            removed.andNot(nextRequired);
            for (int index = added.nextSetBit(0); index >= 0;
                    index = added.nextSetBit(index + 1)) {
                requiredSinceMs[index] = nowMs;
            }
            for (int index = removed.nextSetBit(0); index >= 0;
                    index = removed.nextSetBit(index + 1)) {
                requiredSinceMs[index] = 0L;
            }
            required.clear();
            required.or(nextRequired);
            surfacePageMask = nextSurfacePageMask;
            cavePageMask = nextCavePageMask;
            visiblePageMask = nextVisiblePageMask;
            focusPageX = nextFocusPageX;
            focusPageZ = nextFocusPageZ;
            ordered = buildSourceOrder(regionX, regionZ, visiblePageMask,
                    focusPageX, focusPageZ);
            cursor = 0;
            if (anchor >= 0) {
                for (int position = 0; position < ordered.length; position++) {
                    if (ordered[position] == anchor) {
                        cursor = position;
                        break;
                    }
                }
            }
            generation++;
            rebuilds++;
            boolean pageSetChanged = oldVisiblePageMask != visiblePageMask;
            String reason = pageSetChanged && focusChanged
                    ? "PAGE_SET_AND_FOCUS"
                    : pageSetChanged ? "PAGE_SET"
                    : focusChanged ? "FOCUS"
                    : productChanged ? "PRODUCT_DEMAND"
                    : "SOURCE_SET";
            return new FrontierUpdate(true, reason, oldVisiblePageMask,
                    visiblePageMask, focusChanged, cursorBefore, cursor,
                    added.cardinality(), removed.cardinality(), generation);
        }

        boolean requires(int sourceIndex) {
            return required.get(sourceIndex);
        }

        BitSet requiredCopy() {
            return (BitSet) required.clone();
        }

        int[] ordered() {
            return ordered;
        }

        int cursor() {
            return cursor;
        }

        void advanceCursor(int nextCursor) {
            cursor = ordered.length == 0 ? 0
                    : Math.floorMod(nextCursor, ordered.length);
        }

        long waitAgeMs(int sourceIndex, long nowMs) {
            long since = requiredSinceMs[sourceIndex];
            return since == 0L ? 0L : Math.max(0L, nowMs - since);
        }

        long surfacePageMask() {
            return surfacePageMask;
        }

        long cavePageMask() {
            return cavePageMask;
        }

        FrontierDebug debug() {
            return new FrontierDebug(generation, rebuilds, reusedRefreshes,
                    cursor, required.cardinality(), visiblePageMask,
                    focusPageX, focusPageZ);
        }

        void clear() {
            required.clear();
            java.util.Arrays.fill(requiredSinceMs, 0L);
            ordered = new int[0];
            cursor = 0;
            surfacePageMask = 0L;
            cavePageMask = 0L;
            visiblePageMask = 0L;
            focusPageX = Integer.MIN_VALUE;
            focusPageZ = Integer.MIN_VALUE;
        }
    }

    record FrontierUpdate(boolean changed, String reason,
            long oldPageMask, long newPageMask, boolean focusChanged,
            int cursorBefore, int cursorAfter, int addedSources,
            int removedSources, long generation) {
    }

    record FrontierDebug(long generation, long rebuilds, long reusedRefreshes,
            int cursor, int requiredSources, long pageMask,
            int focusPageX, int focusPageZ) {
    }

    private static final class SourcePriorityScratch {
        private long score;
        private int pageOrdinal;
        private int childOrdinal;
        private boolean focus;
        private boolean central;
        private int readyChildren;
        private long waitAgeMs;
    }

    private record AppliedPresence(long generation, long[] chunkBits) {
    }

    private static long[] buildAffectedPageMasks() {
        long[] masks = new long[SOURCE_COUNT];
        for (int sourceZ = 0; sourceZ < SOURCE_EDGE; sourceZ++) {
            for (int sourceX = 0; sourceX < SOURCE_EDGE; sourceX++) {
                long mask = 0L;
                for (int pageZ = 0; pageZ < REGION_PAGES; pageZ++) {
                    int firstZ = pageZ * CaveLoadHierarchy.CHUNKS_PER_PAGE;
                    if (sourceZ < firstZ
                            || sourceZ > firstZ
                                    + CaveLoadHierarchy.CHUNKS_PER_PAGE + 1) {
                        continue;
                    }
                    for (int pageX = 0; pageX < REGION_PAGES; pageX++) {
                        int firstX = pageX * CaveLoadHierarchy.CHUNKS_PER_PAGE;
                        if (sourceX >= firstX
                                && sourceX <= firstX
                                        + CaveLoadHierarchy.CHUNKS_PER_PAGE + 1) {
                            mask |= 1L << (pageZ * REGION_PAGES + pageX);
                        }
                    }
                }
                masks[sourceZ * SOURCE_EDGE + sourceX] = mask;
            }
        }
        return masks;
    }

    /**
     * Adds only the 4x4 chunk bodies owned by the requested Cave pages. Unlike
     * {@link #addRequiredSources(long, BitSet)}, this deliberately excludes the
     * one-chunk styling halo. Border data remains a cache hit optimization rather
     * than a foreground source prerequisite.
     */
    private static void addCentralRequiredSources(long pageMask,
            BitSet destination) {
        long remaining = pageMask;
        while (remaining != 0L) {
            int ordinal = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1L;
            int pageX = ordinal % REGION_PAGES;
            int pageZ = ordinal / REGION_PAGES;
            int firstX = pageX * CaveLoadHierarchy.CHUNKS_PER_PAGE + SOURCE_HALO;
            int firstZ = pageZ * CaveLoadHierarchy.CHUNKS_PER_PAGE + SOURCE_HALO;
            for (int localZ = 0; localZ < CaveLoadHierarchy.CHUNKS_PER_PAGE;
                    localZ++) {
                int sourceZ = firstZ + localZ;
                for (int localX = 0; localX < CaveLoadHierarchy.CHUNKS_PER_PAGE;
                        localX++) {
                    int sourceX = firstX + localX;
                    destination.set(sourceZ * SOURCE_EDGE + sourceX);
                }
            }
        }
    }

    private static void addRequiredSources(long pageMask, BitSet destination) {
        long remaining = pageMask;
        while (remaining != 0L) {
            int ordinal = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1L;
            int pageX = ordinal % REGION_PAGES;
            int pageZ = ordinal / REGION_PAGES;
            int firstX = pageX * CaveLoadHierarchy.CHUNKS_PER_PAGE;
            int firstZ = pageZ * CaveLoadHierarchy.CHUNKS_PER_PAGE;
            for (int localZ = 0; localZ < CaveLoadHierarchy.CHUNKS_PER_PAGE + 2;
                    localZ++) {
                int sourceZ = firstZ + localZ;
                for (int localX = 0;
                        localX < CaveLoadHierarchy.CHUNKS_PER_PAGE + 2; localX++) {
                    int sourceX = firstX + localX;
                    destination.set(sourceZ * SOURCE_EDGE + sourceX);
                }
            }
        }
    }

    static int[] buildSourceOrder(int regionX, int regionZ,
            long visiblePageMask, int focusPageX, int focusPageZ) {
        LinkedHashSet<Integer> order = new LinkedHashSet<>(SOURCE_COUNT * 2);
        List<Integer> pages = new ArrayList<>(REGION_PAGES * REGION_PAGES);
        for (int ordinal = 0; ordinal < REGION_PAGES * REGION_PAGES; ordinal++) {
            if ((visiblePageMask & (1L << ordinal)) != 0L) pages.add(ordinal);
        }
        pages.sort(Comparator
                .comparingInt((Integer ordinal) -> {
                    int globalX = regionX * REGION_PAGES
                            + ordinal % REGION_PAGES;
                    int globalZ = regionZ * REGION_PAGES
                            + ordinal / REGION_PAGES;
                    return globalX == focusPageX && globalZ == focusPageZ ? 0 : 1;
                })
                .thenComparingInt(ordinal -> {
                    int dx = regionX * REGION_PAGES
                            + ordinal % REGION_PAGES - focusPageX;
                    int dz = regionZ * REGION_PAGES
                            + ordinal / REGION_PAGES - focusPageZ;
                    return dx * dx + dz * dz;
                })
                .thenComparingInt(Integer::intValue));

        // Close the visible 4x4 central children before spending foreground
        // source budget on any styling halo. This makes the focus page usable
        // first while the retained 64x64 presentation can fill monotonically.
        int[] centralOrder = CaveLoadHierarchy.buildCenterOutCellOrder(4);
        for (int ordinal : pages) {
            int pageX = ordinal % REGION_PAGES;
            int pageZ = ordinal / REGION_PAGES;
            int firstX = pageX * 4;
            int firstZ = pageZ * 4;
            for (int packed : centralOrder) {
                int localX = packed % 4 + SOURCE_HALO;
                int localZ = packed / 4 + SOURCE_HALO;
                order.add((firstZ + localZ) * SOURCE_EDGE + firstX + localX);
            }
        }
        int[] haloOrder = CaveLoadHierarchy.buildCenterOutCellOrder(6);
        for (int ordinal : pages) {
            int pageX = ordinal % REGION_PAGES;
            int pageZ = ordinal / REGION_PAGES;
            int firstX = pageX * 4;
            int firstZ = pageZ * 4;
            for (int packed : haloOrder) {
                int localX = packed % 6;
                int localZ = packed / 6;
                if (localX > 0 && localX < 5
                        && localZ > 0 && localZ < 5) continue;
                order.add((firstZ + localZ) * SOURCE_EDGE + firstX + localX);
            }
        }
        /*
         * Do not append the other ~1,100 source cells of the native region. The
         * current projection needs only the union of each visible page's 6x6 halo.
         * The vertical archive remains reusable, so a later pan or mode change adds
         * only the newly demanded cells instead of prefetching an entire .mca file.
         */
        int[] result = new int[order.size()];
        int cursor = 0;
        for (int index : order) result[cursor++] = index;
        return result;
    }
}
