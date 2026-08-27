package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.cave.archive.CaveArchiveV2Service;

import com.velorise.simplemap.client.GeneratedChunkIndex;
import com.velorise.simplemap.client.MapCancellationToken;
import com.velorise.simplemap.client.MapDebugRecorder;
import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapPerformanceGovernor;
import com.velorise.simplemap.client.MapPipelineStage;
import com.velorise.simplemap.client.MapPipelineTelemetry;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapViewLoadPlanner;
import com.velorise.simplemap.client.MapWorkScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Read-only singleplayer bridge from Anvil world-save data to dense cave pages.
 *
 * <p>Fullscreen source scheduling is native-region based. One 32x32-chunk Anvil
 * region plus a one-chunk styling halo is imported through Minecraft's shared
 * RegionFileStorage-backed reader; visible 64x64 page dependencies are decoded
 * first and the remaining generated chunks continue in the background. Minimap
 * and compatibility paths retain bounded page transactions, while final cave
 * projection and CIMG persistence are owned by the region transaction service.</p>

 */
public final class CaveWorldSaveReader {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final CaveWorldSaveReader INSTANCE = new CaveWorldSaveReader();
    private static final boolean DISABLED =
            Boolean.getBoolean("simplemap.disableCaveWorldSaveReader");
    private static final int MAX_QUEUED_PAGES = 4096;
    /** Adaptive page transaction limits; global CPU/IO pressure can reduce these to one. */
    private static final int PRESSURE_IN_FLIGHT_PAGES = 1;
    private static final int GAMEPLAY_IN_FLIGHT_PAGES = 2;
    /*
     * PASS139: the old eight-page fullscreen window could start 8 * 36 = 288
     * chunkMap reads at once. The current log shows ANVIL_READ max >3.2 s and
     * whole-screen stalls during those waves. Keep multiple pages alive, but let
     * each retained PageAssembly advance through small source slices.
     */
    private static final int FULLSCREEN_IN_FLIGHT_PAGES = 4;
    private static final long FAILED_RETRY_MS = 8_000L;
    private static final long DISK_ABSENT_RETRY_MS = 30_000L;
    /**
     * A page that already contains useful leaves is not a failed page. Retry its
     * unresolved holes quickly while preserving leaves from the previous pass.
     * This keeps the player neighbourhood coherent when one
     * Anvil chunk is briefly busy or its decode is deferred by pressure control.
     */
    private static final long PARTIAL_REPAIR_RETRY_MS = 80L;
    private static final long DEFERRED_RETRY_MS = 16L;
    /** Healthy retained-page source slices continue quickly without burst IO. */
    private static final long SOURCE_SLICE_RETRY_MS = 12L;
    /**
     * A disk-absent source remains blocked from another Anvil read for 30 seconds,
     * but a visible page still polls retained/live authority cheaply so a newly
     * loaded LevelChunk can repair the hole immediately.
     */
    private static final long LIVE_REPAIR_POLL_MS = 250L;
    /** Live chunks use the canonical live writer instead of reopening Anvil. */
    private static final long LIVE_SOURCE_POLL_MS = 50L;
    /*
     * PASS141: one visible 64x64 page is one 4x4 Minecraft-chunk transaction.
     * Xaero's WorldDataReader starts all sixteen NBT futures for one MapTileChunk
     * before building it. Reading only 4-8 leaves per pass spread progress across
     * many pages and produced the sparse-island output seen in PASS140.
     */
    private static final int FULLSCREEN_SOURCE_READ_BATCH = 16;
    private static final int MINIMAP_SOURCE_READ_BATCH = 16;
    private static final int BACKGROUND_SOURCE_READ_BATCH = 4;
    private static final int PRESSURE_SOURCE_READ_BATCH = 16;
    private static final long VIEWPORT_REFRESH_MS = 50L;
    /** Two-page ownership halo absorbs small pans without cancelling useful reads. */
    private static final int FULLSCREEN_STICKY_HALO_PAGES = 2;
    /** Recenter source priority only after the inspected point moves meaningfully. */
    private static final int FULLSCREEN_RECENTER_THRESHOLD_PAGES = 2;
    private static final long SOURCE_PREFETCH_INTERVAL_MS = 40L;
    /** 4x4 page body plus the policy's one-chunk dependency halo. */
    private static final int SURFACE_SOURCE_WINDOW_CHUNKS = 6;
    private static final int[] SURFACE_SOURCE_WINDOW_ORDER =
            CaveLoadHierarchy.buildCenterOutCellOrder(SURFACE_SOURCE_WINDOW_CHUNKS);
    /** Native page body plus one decoded chunk on every side for final styling. */
    private static final int CAVE_SOURCE_WINDOW_CHUNKS = 6;
    private static final int CAVE_SOURCE_WINDOW_COUNT =
            CAVE_SOURCE_WINDOW_CHUNKS * CAVE_SOURCE_WINDOW_CHUNKS;
    private static final int CAVE_SOURCE_HALO = 1;
    /*
     * The exact page transaction only needs the sixteen central chunks to become
     * publishable. Halo source is opportunistic and is supplied by neighbouring
     * visible pages/native-region archive ingestion. Waiting for all 20 halo chunks
     * made one missing halo leaf hold a complete 64x64 page for up to 30 seconds.
     */
    private static final int[] CAVE_CENTRAL_SOURCE_ORDER =
            buildCentralSourceOrder();
    private final CaveTileRepository repository = CaveTileRepository.getInstance();
    private final CaveDisplayProjector projector = new CaveDisplayProjector();
    private final DecodedWorldRegionCache sourceCache = DecodedWorldRegionCache.getInstance();
    private final CaveNativeRegionImportService nativeRegionImporter =
            CaveNativeRegionImportService.getInstance();
    private final AnvilPagePresenceIndex anvilPresence =
            AnvilPagePresenceIndex.getInstance();
    private final SurfaceWorldSaveReconstructor surfaceReconstructor =
            SurfaceWorldSaveReconstructor.getInstance();
    private final PriorityQueue<PageTask> queue = new PriorityQueue<>();
    private final Map<PageTaskKey, PageTask> queued = new HashMap<>();
    private final Map<PageTaskKey, PageTask> inFlightTasks = new HashMap<>();
    /**
     * Full 4x4 presentation pages are assembled off-repository across deferred
     * Anvil passes. Repository authority changes only after all sixteen central
     * chunks have a real retained/present source. The one-chunk styling halo is
     * opportunistic and must never block visible page publication.
     */
    private final Map<PageRetryKey, PageAssembly> pendingPageAssemblies = new HashMap<>();
    /** Generation stamp for pages resolved entirely by archive plus known absence. */
    private final Map<PageRetryKey, Long> archiveResolvedPages = new HashMap<>();
    /** Stable resolved display-page fingerprint; suppresses identical Anvil replay even when raw archive revisions advance. */
    private final Map<PageRetryKey, Long> resolvedPageStamps = new HashMap<>();
    private final Map<PageRetryKey, Long> retryAfter = new HashMap<>();
    /* Projection callbacks compete with exact-page builds (whose captured queue
     * peaked at 3.1 s). Classify them separately so decoded-source work runs first;
     * global scheduler limits plus the page gate below bound their concurrency. */
    private final PriorityDecodeExecutor pageWorkers = new PriorityDecodeExecutor(
            MapWorkScheduler.WorkType.SOURCE_PROJECTION, 16);
    private final EnumMap<MapRequestLane, ViewportState> viewports =
            new EnumMap<>(MapRequestLane.class);
    private final MapPipelineTelemetry pipelineTelemetry = MapPipelineTelemetry.getInstance();

    private long epoch = 1L;
    private long sequence;
    private String prefetchDimension = "";
    private int prefetchMinPageX = Integer.MIN_VALUE;
    private int prefetchMaxPageX = Integer.MIN_VALUE;
    private int prefetchMinPageZ = Integer.MIN_VALUE;
    private int prefetchMaxPageZ = Integer.MIN_VALUE;
    private int prefetchCenterPageX = Integer.MIN_VALUE;
    private int prefetchCenterPageZ = Integer.MIN_VALUE;
    private long[] prefetchPagePlan = new long[0];
    private int prefetchPlanCursor;
    private long lastSourcePrefetchMs;
    private long lastSourcePrefetchRestartMs;

    private CaveWorldSaveReader() {
        for (MapRequestLane lane : MapRequestLane.values()) {
            viewports.put(lane, new ViewportState());
        }
    }

    public static CaveWorldSaveReader getInstance() {
        return INSTANCE;
    }

    public synchronized int queuedCount() {
        return queued.size();
    }

    public synchronized int inFlightCount() {
        return inFlightTasks.size();
    }

    /**
     * Returns true only when cached per-region Anvil headers prove that a fullscreen
     * page contains no generated chunk. Unknown/multiplayer
     * pages deliberately return false: presentation may wait for CVD/CIMG or live
     * data, but it must never skip an unresolved page and expose a black hole.
     */
    public synchronized boolean isFullscreenPageKnownAbsent(String dimension,
            CaveView view, int normalizedLayer, int projectionTopY,
            int globalPageX, int globalPageZ) {
        return nativeRegionImporter.isPageKnownAbsent(
                dimension, globalPageX, globalPageZ);
    }

    public synchronized void reset() {
        epoch++;
        for (PageTask task : queued.values()) task.cancel();
        for (PageTask task : inFlightTasks.values()) task.cancel();
        queue.clear();
        queued.clear();
        inFlightTasks.clear();
        pendingPageAssemblies.clear();
        archiveResolvedPages.clear();
        resolvedPageStamps.clear();
        retryAfter.clear();
        for (ViewportState state : viewports.values()) state.clear();
        prefetchDimension = "";
        prefetchMinPageX = prefetchMaxPageX = Integer.MIN_VALUE;
        prefetchMinPageZ = prefetchMaxPageZ = Integer.MIN_VALUE;
        prefetchCenterPageX = prefetchCenterPageZ = Integer.MIN_VALUE;
        prefetchPagePlan = new long[0];
        prefetchPlanCursor = 0;
        lastSourcePrefetchMs = 0L;
        lastSourcePrefetchRestartMs = 0L;
        anvilPresence.reset();
        surfaceReconstructor.reset();
        CaveRegionProjectionService.getInstance().reset();
        nativeRegionImporter.reset();
    }

    /** Removes queued/in-flight work no longer owned after a lane is hidden. */
    public synchronized void suspendLane(MapRequestLane lane) {
        if (lane == null) return;
        if (!Boolean.getBoolean("simplemap.useLegacyWorldSavePipelines")) {
            WorldSaveProjectionPipeline.getInstance().suspendLane(lane);
        } else {
            nativeRegionImporter.suspendSurfaceLane(lane);
            nativeRegionImporter.suspendCaveLane(lane);
        }
        ViewportState state = viewports.get(lane);
        if (state != null) state.clear();
        long now = System.currentTimeMillis();
        // A task admitted by FULLSCREEN may also satisfy a still-live minimap (or
        // vice versa), so ownership is recomputed from all remaining viewports
        // instead of cancelling merely by the lane stored at admission time.
        pruneUnwantedQueuedLocked(now);
        cancelUnwantedInFlightLocked(now);
    }

    /** Clears decoded world-save sources only when the world/dimension cache changes. */
    public synchronized void clearSourceCache() {
        sourceCache.reset();
        surfaceReconstructor.reset();
        anvilPresence.reset();
        prefetchDimension = "";
        prefetchCenterPageX = prefetchCenterPageZ = Integer.MIN_VALUE;
        prefetchPagePlan = new long[0];
        prefetchPlanCursor = 0;
        lastSourcePrefetchRestartMs = 0L;
        CaveRegionProjectionService.getInstance().reset();
        nativeRegionImporter.reset();
    }

    /**
     * Decodes visible generated chunks for the Surface projection. The work stays
     * source-only here (no cave projection or GPU allocation), but inherits the
     * viewport lane so centre tiles do not sit behind speculative prefetch.
     */
    public void prefetchVisibleSources(Minecraft minecraft,
            int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
            double centerChunkX, double centerChunkZ, float scale,
            MapRequestLane lane) {
        if (!Boolean.getBoolean("simplemap.useLegacyWorldSavePipelines")) {
            WorldSaveProjectionPipeline.getInstance().requestVisible(minecraft,
                    WorldProjection.SURFACE, Integer.MAX_VALUE,
                    minChunkX, maxChunkX, minChunkZ, maxChunkZ,
                    centerChunkX, centerChunkZ, scale, lane);
            return;
        }
        sourceCache.maintain();
        // Projection completions arrive in bursts from the Anvil workers. Apply
        // them through a small client-thread slice before admitting more work so
        // dozens of 16x16 palette commits cannot land in one render frame.
        surfaceReconstructor.drainReadyApplications();
        if (DISABLED || minecraft == null || minecraft.level == null
                || minecraft.getSingleplayerServer() == null) return;
        ServerLevel serverLevel = resolveViewedServerLevel(minecraft);
        if (serverLevel == null) return;
        boolean viewedLiveDimension = minecraft.level.dimension()
                .equals(serverLevel.dimension());
        GeneratedChunkIndex generated = GeneratedChunkIndex.getInstance();
        generated.observeLevel(serverLevel);
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastSourcePrefetchMs < SOURCE_PREFETCH_INTERVAL_MS) return;
            lastSourcePrefetchMs = now;
            int minPageX = Math.floorDiv(Math.min(minChunkX, maxChunkX), 4);
            int maxPageX = Math.floorDiv(Math.max(minChunkX, maxChunkX), 4);
            int minPageZ = Math.floorDiv(Math.min(minChunkZ, maxChunkZ), 4);
            int maxPageZ = Math.floorDiv(Math.max(minChunkZ, maxChunkZ), 4);
            int centerPageX = (int) Math.floor(centerChunkX / 4.0);
            int centerPageZ = (int) Math.floor(centerChunkZ / 4.0);
            String dimension = serverLevel.dimension().location().toString();
            boolean dimensionChanged = !dimension.equals(prefetchDimension);
            boolean retarget = !dimensionChanged
                    && AdaptiveDimensionLoadPolicy.shouldRetarget(
                            prefetchMinPageX, prefetchMaxPageX,
                            prefetchMinPageZ, prefetchMaxPageZ,
                            prefetchCenterPageX, prefetchCenterPageZ,
                            minPageX, maxPageX, minPageZ, maxPageZ,
                            centerPageX, centerPageZ);
            if (dimensionChanged || prefetchPagePlan.length == 0 || retarget) {
                prefetchDimension = dimension;
                prefetchMinPageX = minPageX;
                prefetchMaxPageX = maxPageX;
                prefetchMinPageZ = minPageZ;
                prefetchMaxPageZ = maxPageZ;
                prefetchCenterPageX = centerPageX;
                prefetchCenterPageZ = centerPageZ;
                prefetchPagePlan = CaveLoadHierarchy.buildVisiblePagePlan(
                        minPageX, maxPageX, minPageZ, maxPageZ,
                        centerPageX, centerPageZ, true);
                prefetchPlanCursor = 0;
                lastSourcePrefetchRestartMs = now;
            } else if (prefetchPlanCursor >= prefetchPagePlan.length) {
                // A pressured pass may have deferred some chunks. Revisit the same
                // deterministic viewport scanline plan instead of treating one attempted
                // pass as permanent completion.
                if (now - lastSourcePrefetchRestartMs < 500L) return;
                prefetchMinPageX = minPageX;
                prefetchMaxPageX = maxPageX;
                prefetchMinPageZ = minPageZ;
                prefetchMaxPageZ = maxPageZ;
                prefetchCenterPageX = centerPageX;
                prefetchCenterPageZ = centerPageZ;
                prefetchPagePlan = CaveLoadHierarchy.buildVisiblePagePlan(
                        minPageX, maxPageX, minPageZ, maxPageZ,
                        centerPageX, centerPageZ, true);
                prefetchPlanCursor = 0;
                lastSourcePrefetchRestartMs = now;
            }

            MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
            boolean movingFast = minecraft.player != null
                    && minecraft.player.getDeltaMovement().horizontalDistanceSqr() >= 0.18;
            AdaptiveDimensionLoadPolicy.Topology topology =
                    AdaptiveDimensionLoadPolicy.topology(
                            serverLevel.dimensionType().hasSkyLight(),
                            serverLevel.dimensionType().hasCeiling());
            int pageBudget = AdaptiveDimensionLoadPolicy.surfacePageBudget(
                    topology, governor.isFullscreenOpen(), governor.underPressure(),
                    movingFast, scale);
            MapRequestLane sourceLane = lane == null
                    ? MapRequestLane.BACKGROUND : lane;
            int admitted = 0;
            int halo = AdaptiveDimensionLoadPolicy.surfaceHaloChunks();
            while (prefetchPlanCursor < prefetchPagePlan.length
                    && admitted < pageBudget) {
                long packedPage = prefetchPagePlan[prefetchPlanCursor++];
                int pageX = CaveLoadHierarchy.x(packedPage);
                int pageZ = CaveLoadHierarchy.z(packedPage);
                int firstChunkX = (pageX << 2) - halo;
                int firstChunkZ = (pageZ << 2) - halo;
                int window = 4 + halo * 2;
                boolean sourceSaturated = false;
                int[] windowOrder = window == SURFACE_SOURCE_WINDOW_CHUNKS
                        ? SURFACE_SOURCE_WINDOW_ORDER
                        : CaveLoadHierarchy.buildCenterOutCellOrder(window);
                for (int packedLocal : windowOrder) {
                        int localX = packedLocal % window;
                        int localZ = packedLocal / window;
                        int sourceChunkX = firstChunkX + localX;
                        int sourceChunkZ = firstChunkZ + localZ;
                        if (MapManager.getInstance().isChunkSurfaceComplete(
                                sourceChunkX, sourceChunkZ)) continue;
                        GeneratedChunkIndex.State state = generated.state(
                                serverLevel, sourceChunkX, sourceChunkZ);
                        if (state == GeneratedChunkIndex.State.KNOWN_ABSENT) continue;
                        // Only a chunk that is loaded *now* belongs to the resumable
                        // live scanner. GeneratedChunkIndex.LIVE is historical: it
                        // remains LIVE after the client unloads the chunk. Treating
                        // that state as current residency permanently skipped disk
                        // repair for partially scanned fly-over chunks and left black
                        // holes along fast-travel routes.
                        if (viewedLiveDimension
                                && minecraft.level.hasChunk(
                                        sourceChunkX, sourceChunkZ)) {
                            continue;
                        }
                        if (surfaceReconstructor.isPending(
                                serverLevel, sourceChunkX, sourceChunkZ)) continue;
                        DecodedWorldRegionCache.SourceLease sourceLease =
                                sourceCache.requestLease(serverLevel,
                                        sourceChunkX, sourceChunkZ, sourceLane);
                        // Admission denial is a pressure signal, not useful work.
                        // Stop this coherent window at the first denial instead of
                        // allocating another 20-35 detached futures every 100 ms.
                        if (sourceLease.isImmediatelyDeferred()) {
                            sourceLease.close();
                            sourceSaturated = true;
                            break;
                        }
                        surfaceReconstructor.request(serverLevel,
                                sourceChunkX, sourceChunkZ, sourceLease);
                }
                if (sourceSaturated) {
                    // Revisit the same viewport scanline window after the current source
                    // leaves complete. Pending/complete leaves are skipped cheaply.
                    prefetchPlanCursor = Math.max(0, prefetchPlanCursor - 1);
                    break;
                }
                admitted++;
            }
        }
    }

    public void requestVisible(Minecraft minecraft, CaveView view, int layerY,
            int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
            double centerChunkX, double centerChunkZ, float scale) {
        requestVisible(minecraft, view, layerY,
                minChunkX, maxChunkX, minChunkZ, maxChunkZ,
                centerChunkX, centerChunkZ, scale, MapRequestLane.FULLSCREEN);
    }

    public void requestVisible(Minecraft minecraft, CaveView view, int layerY,
            int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
            double centerChunkX, double centerChunkZ, float scale,
            MapRequestLane lane) {
        if (!Boolean.getBoolean("simplemap.useLegacyWorldSavePipelines")) {
            WorldProjection projection = view == CaveView.FULL
                    ? WorldProjection.FULL : WorldProjection.LAYERED;
            WorldSaveProjectionPipeline.getInstance().requestVisible(minecraft,
                    projection, layerY, minChunkX, maxChunkX,
                    minChunkZ, maxChunkZ, centerChunkX, centerChunkZ,
                    scale, lane);
            return;
        }
        sourceCache.maintain();
        nativeRegionImporter.maintain();
        CaveRegionProjectionService.getInstance().maintain();
        if (DISABLED || minecraft == null || minecraft.level == null) return;
        ServerLevel serverLevel = resolveViewedServerLevel(minecraft);
        if (serverLevel != null) {
            GeneratedChunkIndex.getInstance().observeLevel(serverLevel);
        }

        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        int normalizedLayer = DenseCaveTile.normalizeLayer(view, layerY);
        int minimumPageX = Math.floorDiv(Math.min(minChunkX, maxChunkX), 4);
        int maximumPageX = Math.floorDiv(Math.max(minChunkX, maxChunkX), 4);
        int minimumPageZ = Math.floorDiv(Math.min(minChunkZ, maxChunkZ), 4);
        int maximumPageZ = Math.floorDiv(Math.max(minChunkZ, maxChunkZ), 4);
        int foregroundMinPageX = minimumPageX;
        int foregroundMaxPageX = maximumPageX;
        int foregroundMinPageZ = minimumPageZ;
        int foregroundMaxPageZ = maximumPageZ;
        int rawCenterPageX = (int) Math.floor(centerChunkX / 4.0);
        int rawCenterPageZ = (int) Math.floor(centerChunkZ / 4.0);
        if (effectiveLane == MapRequestLane.FULLSCREEN) {
            // Xaero-like sticky viewport ownership: source leaves just outside the
            // current screen remain useful during a continuous pan. This removes
            // the reset/cancel/requeue cycle each time one page crosses an edge.
            minimumPageX -= FULLSCREEN_STICKY_HALO_PAGES;
            maximumPageX += FULLSCREEN_STICKY_HALO_PAGES;
            minimumPageZ -= FULLSCREEN_STICKY_HALO_PAGES;
            maximumPageZ += FULLSCREEN_STICKY_HALO_PAGES;
        }
        if (effectiveLane == MapRequestLane.MINIMAP) {
            /* PASS156: cave minimap source ownership matches Xaero's bounded
             * MinimapWriter: centre +/-2 64x64 pages, no fullscreen-sized halo. */
            int caveRadius = MapViewLoadPlanner.CAVE_MINIMAP_MAX_RADIUS_PAGES;
            minimumPageX = Math.max(minimumPageX, rawCenterPageX - caveRadius);
            maximumPageX = Math.min(maximumPageX, rawCenterPageX + caveRadius);
            minimumPageZ = Math.max(minimumPageZ, rawCenterPageZ - caveRadius);
            maximumPageZ = Math.min(maximumPageZ, rawCenterPageZ + caveRadius);
            foregroundMinPageX = minimumPageX;
            foregroundMaxPageX = maximumPageX;
            foregroundMinPageZ = minimumPageZ;
            foregroundMaxPageZ = maximumPageZ;
        }
        int centerPageX = clamp(rawCenterPageX, minimumPageX, maximumPageX);
        int centerPageZ = clamp(rawCenterPageZ, minimumPageZ, maximumPageZ);
        String dimension = resolveViewedDimensionId(minecraft, serverLevel);
        if (dimension.isEmpty()) return;
        long now = System.currentTimeMillis();
        long repositoryGeneration = repository.generation();

        synchronized (this) {
            ViewportState state = viewports.get(effectiveLane);
            boolean sameBandRetarget = state.matchesBand(dimension, view,
                    normalizedLayer, minimumPageX, maximumPageX,
                    minimumPageZ, maximumPageZ, centerPageX, centerPageZ,
                    effectiveLane)
                    && state.projectionTopY != layerY;
            boolean changed = !sameBandRetarget
                    && !state.matches(dimension, view, normalizedLayer, layerY,
                    minimumPageX, maximumPageX, minimumPageZ, maximumPageZ,
                    centerPageX, centerPageZ, effectiveLane);
            boolean recentered = !changed && !sameBandRetarget
                    && state.shouldRecenter(centerPageX, centerPageZ,
                            effectiveLane, FULLSCREEN_RECENTER_THRESHOLD_PAGES);
            if (sameBandRetarget) {
                // Keep old same-band decodes alive as valid visual fallback. Restart
                // the viewport scanline source cursor for the new exact Top-Y, but do not
                // revoke in-flight ownership merely because caveStart changed inside
                // the same 16-block cache band.
                state.retargetProjection(layerY);
                pruneUnwantedQueuedLocked(now);
                cancelUnwantedInFlightLocked(now);
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent(
                        "CAVE_SOURCE_SAME_BAND_RETARGET:" + normalizedLayer, 100L)) {
                    recorder.event("CAVE_SOURCE_SAME_BAND_RETARGET",
                            "band=" + normalizedLayer + " top_y=" + layerY
                                    + " lane=" + effectiveLane);
                }
            } else if (changed) {
                state.reset(dimension, view, normalizedLayer, layerY,
                        minimumPageX, maximumPageX, minimumPageZ, maximumPageZ,
                        centerPageX, centerPageZ, effectiveLane);
                handoffLaneGenerationLocked(effectiveLane, state, now);
                pruneUnwantedQueuedLocked(now);
                cancelUnwantedInFlightLocked(now);
            } else if (recentered) {
                // Kept for non-wavefront compatibility; fullscreen currently never
                // recentres because focus does not affect fixed row order.
                state.recenter(centerPageX, centerPageZ, effectiveLane);
                handoffLaneGenerationLocked(effectiveLane, state, now);
            } else {
                state.centerPageX = centerPageX;
                state.centerPageZ = centerPageZ;
            }
            /* PASS114: the native reader may keep a sticky source halo, but only
             * the renderer-owned page rectangle is foreground presentation demand. */
            state.foregroundPagePlan = CaveLoadHierarchy.buildVisiblePagePlan(
                    foregroundMinPageX, foregroundMaxPageX,
                    foregroundMinPageZ, foregroundMaxPageZ,
                    clamp(rawCenterPageX, foregroundMinPageX, foregroundMaxPageX),
                    clamp(rawCenterPageZ, foregroundMinPageZ, foregroundMaxPageZ),
                    effectiveLane == MapRequestLane.FULLSCREEN);
            state.lastSeenMs = now;

            boolean pressured = MapPerformanceGovernor.getInstance().underPressure();
            long refreshInterval = Math.max(VIEWPORT_REFRESH_MS,
                    CaveScreenSpacePolicy.sourceEnumerationRetryMs(
                            scale, effectiveLane, pressured));
            boolean refreshDue = changed || recentered
                    || now - state.lastRequestMs >= refreshInterval;
            if (refreshDue && now >= state.nextPassMs) {
                state.lastRequestMs = now;
                retryAfter.entrySet().removeIf(entry -> entry.getValue() <= now);
                long currentEpoch = epoch;
                int admission = CaveScreenSpacePolicy.sourceAdmissionBudget(
                        scale, effectiveLane, pressured);
                if (effectiveLane == MapRequestLane.FULLSCREEN) {
                    /*
                     * PASS76 source authority is one native 32x32-chunk transaction,
                     * not sixty-four independent 6x6 page reads. Minecraft's
                     * RegionFileStorage keeps the .mca handle shared while the region
                     * importer decodes only the union of visible page halos. Overlap
                     * stays reusable in the source cache when the viewport moves.
                     */
                    if (serverLevel != null && state.pagePlan.length > 0) {
                        /*
                         * The reader's dimension is the raw resource id
                         * (minecraft:overworld), while every rendered cave key uses
                         * MapManager's cache namespace (overworld). Passing the raw
                         * id made the GPU drain reject every imported page.
                         */
                        String projectionDimension = MapManager.getInstance()
                                .getDimensionCacheKey();
                        nativeRegionImporter.requestViewport(serverLevel,
                                projectionDimension, view, layerY, state.pagePlan,
                                state.foregroundPagePlan,
                                foregroundMinPageX, foregroundMaxPageX,
                                foregroundMinPageZ, foregroundMaxPageZ,
                                !CaveScreenSpacePolicy.branchOnly(scale, effectiveLane),
                                centerPageX, centerPageZ, effectiveLane,
                                repositoryGeneration);
                        cancelLegacyFullscreenPageTasksLocked();
                        state.pageCursor = state.pagePlan.length;
                        state.updateSliceIndex = state.pageCursor
                                / MapViewLoadPlanner.FULLSCREEN_SLICE_SIZE;
                        state.completedCycles++;
                        state.nextPassMs = now
                                + CaveScreenSpacePolicy.completedSourcePlanPauseMs(
                                        scale, effectiveLane, pressured);
                    }
                } else {
                    int considered = 0;
                    if (state.pageCursor >= state.pagePlan.length) {
                        state.pageCursor = 0;
                    }
                    while (state.pageCursor < state.pagePlan.length
                            && considered < admission
                            && queued.size() < MAX_QUEUED_PAGES) {
                        int ordinal = state.pageCursor;
                        long packedPage = state.pagePlan[state.pageCursor++];
                        int globalPageX = CaveLoadHierarchy.x(packedPage);
                        int globalPageZ = CaveLoadHierarchy.z(packedPage);
                        considered++;
                        if (admitVisiblePageLocked(serverLevel, state,
                                dimension, view, layerY, normalizedLayer,
                                globalPageX, globalPageZ, ordinal,
                                effectiveLane, currentEpoch,
                                repositoryGeneration, now)) {
                            pipelineTelemetry.recordPageAdmission(effectiveLane);
                        }
                    }
                    if (state.pageCursor >= state.pagePlan.length) {
                        state.nextPassMs = now
                                + CaveScreenSpacePolicy.completedSourcePlanPauseMs(
                                        scale, effectiveLane, pressured);
                    }
                }

            }
        }
        if (serverLevel != null) pump(serverLevel);
    }

    /** Resolves the map's viewed dimension, which can differ from the player level. */
    private static ServerLevel resolveViewedServerLevel(Minecraft minecraft) {
        if (minecraft == null || minecraft.getSingleplayerServer() == null) return null;
        String viewed = MapManager.getInstance().getCurrentDimensionResourceId();
        ResourceLocation location = ResourceLocation.tryParse(viewed);
        if (location == null) return null;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, location);
        return minecraft.getSingleplayerServer().getLevel(key);
    }

    /**
     * Resolves the persistent-cache namespace even when no integrated server is
     * available. This intentionally does not claim access to a remote server's
     * Anvil files: a null {@code serverLevel} enables presentation-cache replay
     * only, while source decoding remains singleplayer/LAN-host authoritative.
     */
    private static String resolveViewedDimensionId(Minecraft minecraft,
            ServerLevel serverLevel) {
        if (serverLevel != null) {
            return serverLevel.dimension().location().toString();
        }
        String viewed = MapManager.getInstance().getCurrentDimensionResourceId();
        if (viewed != null && ResourceLocation.tryParse(viewed) != null) return viewed;
        return minecraft != null && minecraft.level != null
                ? minecraft.level.dimension().location().toString() : "";
    }

    private boolean admitVisiblePageLocked(ServerLevel serverLevel,
            ViewportState state, String dimension, CaveView view, int layerY,
            int normalizedLayer, int globalPageX, int globalPageZ, int ordinal,
            MapRequestLane lane, long currentEpoch, long repositoryGeneration,
            long now) {
        // Presentation-ready .cvd data wins the first race. Rebuilding the same
        // page from Anvil NBT while its region cache is already being read caused
        // thousands of superseding source commits and very few GPU-ready pages.
        repository.requestDisplayPageLoad(view, layerY,
                globalPageX, globalPageZ, lane);
        if (repository.hasPendingDisplayPageLoad(view, layerY,
                globalPageX, globalPageZ)) return false;
        if (repository.hasFreshDisplayPageSource(view, layerY,
                globalPageX, globalPageZ, DenseCaveTile.Source.WORLD_SAVE)) return false;

        // Persistent archive identity may outlive its resident compact tile. Refill
        // the one visible 4x4-chunk page from SMR2 before paying for another Anvil
        // decode. This path also works for remote clients where Anvil is unavailable.
        if (repository.requestPersistedArchivePageLoad(view, layerY,
                globalPageX, globalPageZ, lane)) return false;

        // A remote multiplayer client cannot read the server's region files.
        // Cache replay above is still valid and must not be blocked by that fact.
        if (serverLevel == null) return false;

        PageTaskKey key = new PageTaskKey(dimension,
                globalPageX, globalPageZ, view, normalizedLayer);
        PageRetryKey retryKey = new PageRetryKey(key, layerY);
        Long blockedUntil = retryAfter.get(retryKey);
        if (blockedUntil != null && blockedUntil > now) return false;
        Long resolvedStamp = resolvedPageStamps.get(retryKey);
        long currentResolutionStamp = repository.getDisplayPageResolutionStamp(
                view, layerY, globalPageX, globalPageZ);
        if (resolvedStamp != null && currentResolutionStamp != 0L
                && resolvedStamp.longValue() == currentResolutionStamp) {
            return false;
        }

        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        int knownPresent = 0;
        int knownAbsent = 0;
        int archivePresent = 0;
        CaveArchiveV2Service archive = CaveArchiveV2Service.getInstance();
        GeneratedChunkIndex generated = GeneratedChunkIndex.getInstance();
        for (int localX = 0; localX < 4; localX++) {
            for (int localZ = 0; localZ < 4; localZ++) {
                int chunkX = firstChunkX + localX;
                int chunkZ = firstChunkZ + localZ;
                GeneratedChunkIndex.State generatedState = generated.state(
                        serverLevel, chunkX, chunkZ);
                if (generatedState == GeneratedChunkIndex.State.LIVE
                        || generatedState == GeneratedChunkIndex.State.SAVED_PRESENT) {
                    knownPresent++;
                } else if (generatedState == GeneratedChunkIndex.State.KNOWN_ABSENT) {
                    knownAbsent++;
                }
                boolean archived = archiveCoversView(archive, view, chunkX, chunkZ);
                if (archived) archivePresent++;
            }
        }
        int sourceWindowCoverage = 0;
        int sourceWindowFirstChunkX = firstChunkX - CAVE_SOURCE_HALO;
        int sourceWindowFirstChunkZ = firstChunkZ - CAVE_SOURCE_HALO;
        for (int localZ = 0; localZ < CAVE_SOURCE_WINDOW_CHUNKS; localZ++) {
            for (int localX = 0; localX < CAVE_SOURCE_WINDOW_CHUNKS; localX++) {
                int chunkX = sourceWindowFirstChunkX + localX;
                int chunkZ = sourceWindowFirstChunkZ + localZ;
                if (archiveCoversView(archive, view, chunkX, chunkZ)) {
                    sourceWindowCoverage++;
                }
            }
        }
        /* Only actual archive coverage is authority. Header/disk absence remains
         * a TTL-bound retry hint and can never complete a Cave page. */
        if (archivePresent == 16) {
            PageRetryKey archiveKey = new PageRetryKey(key, layerY);
            long archiveResolutionStamp = repository.getDisplayPageResolutionStamp(
                    view, layerY, globalPageX, globalPageZ);
            Long previousArchiveStamp = archiveResolvedPages.get(archiveKey);
            if (archiveResolutionStamp != 0L) {
                archiveResolvedPages.put(archiveKey, archiveResolutionStamp);
                if (sourceWindowCoverage == CAVE_SOURCE_WINDOW_COUNT) {
                    resolvedPageStamps.put(archiveKey, archiveResolutionStamp);
                }
            }
            if (previousArchiveStamp == null
                    || previousArchiveStamp.longValue() != archiveResolutionStamp) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent("CAVE_SOURCE_ARCHIVE_BYPASS", 100L)) {
                    recorder.event("CAVE_SOURCE_ARCHIVE_BYPASS",
                            "page=" + globalPageX + ',' + globalPageZ
                                    + " view=" + view
                                    + " top_y=" + layerY
                                    + " archive=" + archivePresent
                                    + " absent=" + knownAbsent
                                    + " coverage=16 halo_coverage="
                                    + sourceWindowCoverage + "/"
                                    + CAVE_SOURCE_WINDOW_COUNT + " stamp="
                                    + archiveResolutionStamp);
                }
            }
            if (sourceWindowCoverage == CAVE_SOURCE_WINDOW_COUNT
                    && archiveResolutionStamp != 0L) {
                CaveRegionProjectionService.getInstance().request(serverLevel,
                        dimension, view, layerY, globalPageX, globalPageZ,
                        lane, lane.priorityBase() + 640_000 - ordinal * 250,
                        repositoryGeneration);
                return true;
            }
            // Central source is already authoritative, but the final leaf still
            // needs its one-chunk border. Fall through to one halo import task.
        }

        int dx = globalPageX - state.centerPageX;
        int dz = globalPageZ - state.centerPageZ;
        int priority;
        if (lane == MapRequestLane.FULLSCREEN) {
            // Fullscreen source order follows the fixed wavefront ordinal. Cached
            // leaves can complete early, but source admission never jumps from the
            // top row to a distant camera-centred ring.
            priority = lane.priorityBase() + 220_000
                    - Math.min(180_000, ordinal * 900);
        } else {
            int distancePenalty = Math.min(850_000,
                    (dx * dx + dz * dz) * 2_000);
            priority = lane.priorityBase() + 180_000 - distancePenalty
                    + knownPresent * 4_000;
        }
        return enqueueLocked(key, layerY, priority, lane,
                currentEpoch, repositoryGeneration);
    }

    private void cancelLegacyFullscreenPageTasksLocked() {
        var queuedIterator = queued.entrySet().iterator();
        while (queuedIterator.hasNext()) {
            Map.Entry<PageTaskKey, PageTask> entry = queuedIterator.next();
            PageTask task = entry.getValue();
            if (task.lane != MapRequestLane.FULLSCREEN) continue;
            task.cancel();
            queuedIterator.remove();
        }
        queue.removeIf(task -> task.lane == MapRequestLane.FULLSCREEN);
        var inFlightIterator = inFlightTasks.entrySet().iterator();
        while (inFlightIterator.hasNext()) {
            Map.Entry<PageTaskKey, PageTask> entry = inFlightIterator.next();
            PageTask task = entry.getValue();
            if (task.lane != MapRequestLane.FULLSCREEN) continue;
            task.cancel();
            inFlightIterator.remove();
            pipelineTelemetry.recordTaskCancelledBeforeRun();
        }
    }

    private int activeTaskCountLocked(MapRequestLane lane) {
        int count = 0;
        for (PageTask task : queued.values()) {
            if (task.lane == lane) count++;
        }
        for (PageTask task : inFlightTasks.values()) {
            if (task.lane == lane) count++;
        }
        return count;
    }

    private boolean enqueueLocked(PageTaskKey key, int projectionTopY, int priority,
            MapRequestLane lane, long taskEpoch, long repositoryGeneration) {
        PageTask inFlight = inFlightTasks.get(key);
        if (inFlight != null) {
            long now = System.currentTimeMillis();
            boolean sameProjection = inFlight.projectionTopY == projectionTopY;
            if (sameProjection && wantedByAnyViewportLocked(inFlight, now)) {
                return false;
            }
            // Do not let an obsolete Top-Y/mode transaction own one of the bounded
            // decode slots until all sixteen source futures eventually complete.
            // Its token prevents stale publication; removing the ownership here
            // lets the current wavefront page start immediately.
            if (inFlightTasks.remove(key, inFlight)) {
                inFlight.cancel();
                pipelineTelemetry.recordTaskCancelledBeforeRun();
            }
        }
        PageTask existing = queued.get(key);
        if (existing != null) {
            if (existing.projectionTopY == projectionTopY
                    && existing.priority >= priority
                    && !lane.strongerThan(existing.lane)) return false;
            existing.token.cancel();
            PageTask replacement = new PageTask(key, projectionTopY, priority, lane,
                    sequence++, taskEpoch, repositoryGeneration);
            queued.put(key, replacement);
            queue.offer(replacement);
            return true;
        }
        if (queued.size() >= MAX_QUEUED_PAGES) return false;
        PageTask task = new PageTask(key, projectionTopY, priority, lane,
                sequence++, taskEpoch, repositoryGeneration);
        queued.put(key, task);
        queue.offer(task);
        return true;
    }

    private void pump(ServerLevel serverLevel) {
        while (true) {
            PageTask task;
            synchronized (this) {
                int inFlightLimit = adaptiveInFlightLimitLocked();
                if (inFlightTasks.size() >= inFlightLimit) return;
                task = pollValidLocked();
                if (task == null) return;
                if (task.epoch != epoch
                        || !repository.isGenerationCurrent(task.repositoryGeneration)
                        || repository.hasPendingDisplayPageLoad(task.key.view(),
                                task.projectionTopY, task.key.globalPageX(),
                                task.key.globalPageZ())
                        || repository.hasFreshDisplayPageSource(task.key.view(),
                                task.projectionTopY, task.key.globalPageX(),
                                task.key.globalPageZ(), DenseCaveTile.Source.WORLD_SAVE)) continue;
                int requiredDecodes = requiredForegroundDecodes(serverLevel, task);
                DecodedWorldRegionCache.PageReservation reservation =
                        sourceCache.reserveForegroundDecodes(requiredDecodes);
                if (reservation == null) {
                    // Keep the earliest wavefront page at the head. The reservation
                    // is atomic: once admitted, all newly decoded leaves can start
                    // even if the adaptive limit changes during the loop.
                    queued.put(task.key, task);
                    queue.offer(task);
                    return;
                }
                inFlightTasks.put(task.key, task);
                task.reservation = reservation;
            }
            readPage(serverLevel, task);
        }
    }

    private int requiredForegroundDecodes(ServerLevel serverLevel,
            PageTask task) {
        int firstChunkX = (task.key.globalPageX() << 2) - CAVE_SOURCE_HALO;
        int firstChunkZ = (task.key.globalPageZ() << 2) - CAVE_SOURCE_HALO;
        int required = 0;
        int batchLimit = sourceReadBatchLimit(task.lane);
        CaveArchiveV2Service archive = CaveArchiveV2Service.getInstance();
        for (int order = 0; order < CAVE_CENTRAL_SOURCE_ORDER.length; order++) {
            int index = CAVE_CENTRAL_SOURCE_ORDER[order];
            int localX = index % CAVE_SOURCE_WINDOW_CHUNKS;
            int localZ = index / CAVE_SOURCE_WINDOW_CHUNKS;
            int chunkX = firstChunkX + localX;
            int chunkZ = firstChunkZ + localZ;
            if (repository.hasFreshDisplayTileOrKnownEmpty(
                    task.key.view(), task.projectionTopY, chunkX, chunkZ,
                    DenseCaveTile.Source.WORLD_SAVE)) continue;
            if (archiveCoversView(archive, task.key.view(), chunkX, chunkZ)) continue;
            if (GeneratedChunkIndex.getInstance().state(
                    serverLevel, chunkX, chunkZ)
                    == GeneratedChunkIndex.State.LIVE) {
                CavePipeline.getInstance().repairTransientDiskAbsence(
                        chunkX, chunkZ);
                continue;
            }
            if (sourceCache.requiresForegroundDecode(
                    serverLevel, chunkX, chunkZ)) {
                required++;
                if (required >= batchLimit) break;
            }
        }
        return required;
    }

    private static int sourceReadBatchLimit(MapRequestLane lane) {
        if (MapPerformanceGovernor.getInstance().underPressure()) {
            return PRESSURE_SOURCE_READ_BATCH;
        }
        if (lane == MapRequestLane.FULLSCREEN) {
            return FULLSCREEN_SOURCE_READ_BATCH;
        }
        if (lane == MapRequestLane.MINIMAP) {
            return MINIMAP_SOURCE_READ_BATCH;
        }
        return BACKGROUND_SOURCE_READ_BATCH;
    }

    private static int[] buildCentralSourceOrder() {
        int[] result = new int[CaveLoadHierarchy.CHUNKS_PER_PAGE_COUNT];
        for (int order = 0; order < result.length; order++) {
            int centralIndex = CaveLoadHierarchy.orderedChunkIndex(order);
            int localX = centralIndex % CaveLoadHierarchy.CHUNKS_PER_PAGE
                    + CAVE_SOURCE_HALO;
            int localZ = centralIndex / CaveLoadHierarchy.CHUNKS_PER_PAGE
                    + CAVE_SOURCE_HALO;
            result[order] = localZ * CAVE_SOURCE_WINDOW_CHUNKS + localX;
        }
        return result;
    }

    private static long buildCentralSourceMask() {
        long mask = 0L;
        for (int index : CAVE_CENTRAL_SOURCE_ORDER) {
            mask |= 1L << index;
        }
        return mask;
    }

    private static boolean archiveCoversView(CaveArchiveV2Service archive,
            CaveView view, int chunkX, int chunkZ) {
        return view == CaveView.FULL
                ? archive.hasFullProjectionChunk(chunkX, chunkZ)
                : archive.hasCompleteChunk(chunkX, chunkZ);
    }

    private static boolean isCentralSourceCell(int localX, int localZ) {
        return localX >= CAVE_SOURCE_HALO
                && localX < CAVE_SOURCE_HALO + CaveLoadHierarchy.CHUNKS_PER_PAGE
                && localZ >= CAVE_SOURCE_HALO
                && localZ < CAVE_SOURCE_HALO + CaveLoadHierarchy.CHUNKS_PER_PAGE;
    }

    private static int centralSourceIndex(int sourceIndex) {
        int localX = sourceIndex % CAVE_SOURCE_WINDOW_CHUNKS;
        int localZ = sourceIndex / CAVE_SOURCE_WINDOW_CHUNKS;
        if (!isCentralSourceCell(localX, localZ)) return -1;
        return (localZ - CAVE_SOURCE_HALO) * CaveLoadHierarchy.CHUNKS_PER_PAGE
                + (localX - CAVE_SOURCE_HALO);
    }

    /**
     * Separates fullscreen throughput from gameplay safety. The old fixed 1-page
     * gate left NVMe and multicore systems idle; this limit expands only while the
     * shared scheduler and frame governor report headroom.
     */
    private int adaptiveInFlightLimitLocked() {
        if (MapPerformanceGovernor.getInstance().underPressure()) {
            return PRESSURE_IN_FLIGHT_PAGES;
        }
        boolean schedulerBusy = MapWorkScheduler.cpuTotalCost() > 720
                || MapWorkScheduler.ioTotalCost() > 520;
        int limit = hasQueuedLaneLocked(MapRequestLane.FULLSCREEN)
                ? FULLSCREEN_IN_FLIGHT_PAGES : GAMEPLAY_IN_FLIGHT_PAGES;
        if (schedulerBusy) limit = Math.min(limit,
                hasQueuedLaneLocked(MapRequestLane.FULLSCREEN) ? 6
                        : GAMEPLAY_IN_FLIGHT_PAGES);
        // Preserve a dedicated player-centred transaction when fullscreen work is
        // already occupying the decode window.
        if (hasQueuedMinimapLocked() && !hasInFlightMinimapLocked()) {
            limit = Math.max(limit, 2);
        }
        return Math.max(1, limit);
    }

    private int laneAdmissionLimitLocked(MapRequestLane lane) {
        if (lane == MapRequestLane.MINIMAP) {
            return MapPerformanceGovernor.getInstance().underPressure() ? 1 : 3;
        }
        if (lane == MapRequestLane.FULLSCREEN) {
            MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
            if (governor.underPressure()) return 1;
            return governor.hasStreamingHeadroom() ? 4 : 3;
        }
        return lane == MapRequestLane.BACKGROUND ? 1 : 2;
    }

    private boolean hasQueuedLaneLocked(MapRequestLane lane) {
        for (PageTask task : queued.values()) {
            if (task.lane == lane) return true;
        }
        return false;
    }

    private PageTask pollValidLocked() {
        long now = System.currentTimeMillis();
        while (true) {
            PageTask task = queue.poll();
            if (task == null) return null;
            if (!queued.remove(task.key, task)) continue;
            if (!wantedByAnyViewportLocked(task, now)) {
                task.cancel();
                pipelineTelemetry.recordTaskCancelledBeforeRun();
                continue;
            }
            return task;
        }
    }

    @SuppressWarnings("unchecked")
    private void readPage(ServerLevel serverLevel, PageTask task) {
        int firstChunkX = (task.key.globalPageX() << 2) - CAVE_SOURCE_HALO;
        int firstChunkZ = (task.key.globalPageZ() << 2) - CAVE_SOURCE_HALO;
        DecodedWorldRegionCache.SourceLease[] leases =
                new DecodedWorldRegionCache.SourceLease[CAVE_SOURCE_WINDOW_COUNT];
        CompletableFuture<DecodedWorldRegionCache.Result>[] sources =
                new CompletableFuture[CAVE_SOURCE_WINDOW_COUNT];
        PageRetryKey assemblyKey = new PageRetryKey(task.key, task.projectionTopY);
        PageAssembly assembly;
        synchronized (this) {
            assembly = pendingPageAssemblies.get(assemblyKey);
            if (assembly == null || !assembly.matches(task.epoch,
                    task.repositoryGeneration)) {
                assembly = new PageAssembly(task.epoch, task.repositoryGeneration);
                pendingPageAssemblies.put(assemblyKey, assembly);
            }
            assembly.touch();
        }

        int unresolvedCount = 0;
        int batchLimit = sourceReadBatchLimit(task.lane);
        long sourceWaitStart = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        CaveArchiveV2Service archive = CaveArchiveV2Service.getInstance();
        for (int order = 0; order < CAVE_CENTRAL_SOURCE_ORDER.length; order++) {
            int index = CAVE_CENTRAL_SOURCE_ORDER[order];
            if (assembly.isResolved(index)) continue;
            int localX = index % CAVE_SOURCE_WINDOW_CHUNKS;
            int localZ = index / CAVE_SOURCE_WINDOW_CHUNKS;
            int chunkX = firstChunkX + localX;
            int chunkZ = firstChunkZ + localZ;
            if (repository.hasFreshDisplayTileOrKnownEmpty(
                    task.key.view(), task.projectionTopY, chunkX, chunkZ,
                    DenseCaveTile.Source.WORLD_SAVE)
                    || archiveCoversView(archive, task.key.view(), chunkX, chunkZ)) {
                assembly.stageRetained(index);
                continue;
            }
            if (GeneratedChunkIndex.getInstance().state(
                    serverLevel, chunkX, chunkZ)
                    == GeneratedChunkIndex.State.LIVE) {
                CavePipeline.getInstance().repairTransientDiskAbsence(
                        chunkX, chunkZ);
                assembly.stageLivePending(index);
                continue;
            }
            /*
             * One temporary ABSENT leaf must not trigger another disk read before
             * its per-leaf retry. The page itself still polls live/retained authority
             * at LIVE_REPAIR_POLL_MS, so a newly loaded LevelChunk repairs the
             * visible hole without waiting for the 30-second Anvil retry.
             */
            if (assembly.isRetryBlocked(index, nowMs)) continue;
            if (unresolvedCount >= batchLimit) continue;
            leases[index] = sourceCache.requestReservedLease(serverLevel,
                    chunkX, chunkZ, task.lane, task.reservation);
            sources[index] = leases[index].future();
            unresolvedCount++;
        }
        task.closeReservation();
        task.installLeases(leases);

        if (assembly.isComplete()) {
            task.releaseLeases();
            publishCompletedAssembly(serverLevel, task, assemblyKey, assembly);
            finish(task, 0L);
            pump(serverLevel);
            return;
        }
        if (unresolvedCount == 0) {
            task.releaseLeases();
            long blockedDelay = assembly.nextRetryDelayMs(nowMs);
            finish(task, blockedDelay > 0L
                    ? Math.min(blockedDelay, LIVE_REPAIR_POLL_MS)
                    : SOURCE_SLICE_RETRY_MS);
            pump(serverLevel);
            return;
        }

        PageReadTransaction transaction = new PageReadTransaction(
                unresolvedCount, sourceWaitStart, assembly);
        for (int index = 0; index < CAVE_SOURCE_WINDOW_COUNT; index++) {
            if (sources[index] == null) continue;
            final int sourceIndex = index;
            sources[index].whenCompleteAsync((result, sourceFailure) ->
                    completePageSource(serverLevel, task, transaction,
                            assemblyKey, sourceIndex, firstChunkX, firstChunkZ,
                            result, sourceFailure),
                    pageWorkers.dynamic(task.lane::executorPriority));
        }
    }

    private void publishCompletedAssembly(ServerLevel serverLevel, PageTask task,
            PageRetryKey assemblyKey, PageAssembly assembly) {
        if (!assembly.isComplete() || !isCurrent(task)) return;
        int centralFirstChunkX = task.key.globalPageX() << 2;
        int centralFirstChunkZ = task.key.globalPageZ() << 2;
        PageAssembly.Snapshot snapshot = assembly.snapshot();
        long preCommitStamp = repository.getDisplayPageResolutionStamp(
                task.key.view(), task.projectionTopY,
                task.key.globalPageX(), task.key.globalPageZ());
        boolean retainedOnly = snapshot.presentCount() == 0
                && snapshot.retainedCount() == 16;
        boolean changed = false;
        if (!retainedOnly || preCommitStamp == 0L) {
            changed = repository.commitDisplayPage(snapshot.replacements(),
                    task.key.view(), task.projectionTopY,
                    centralFirstChunkX, centralFirstChunkZ,
                    task.repositoryGeneration);
        }
        long committedPageRevision = repository.getPageRevision(
                task.key.view(), task.projectionTopY,
                task.key.globalPageX(), task.key.globalPageZ());
        long resolvedStamp = repository.getDisplayPageResolutionStamp(
                task.key.view(), task.projectionTopY,
                task.key.globalPageX(), task.key.globalPageZ());
        synchronized (this) {
            pendingPageAssemblies.remove(assemblyKey, assembly);
            if (resolvedStamp != 0L) resolvedPageStamps.put(
                    assemblyKey, resolvedStamp);
        }
        if (resolvedStamp != 0L && committedPageRevision != 0L) {
            CaveRegionProjectionService.getInstance().request(serverLevel,
                    task.key.dimension(), task.key.view(), task.projectionTopY,
                    task.key.globalPageX(), task.key.globalPageZ(), task.lane,
                    task.priority + 420_000, task.repositoryGeneration);
        }
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (retainedOnly && preCommitStamp != 0L) {
            if (recorder.shouldEmitEvent(
                    "CAVE_SOURCE_RETAINED_PAGE_SKIPPED:" + task.key, 500L)) {
                recorder.event("CAVE_SOURCE_RETAINED_PAGE_SKIPPED",
                        "page=" + task.key.globalPageX() + ','
                                + task.key.globalPageZ() + " lane=" + task.lane
                                + " stamp=" + resolvedStamp
                                + " halo_resolved=" + snapshot.haloResolvedCount());
            }
        } else if (recorder.shouldEmitEvent(
                "CAVE_SOURCE_PAGE_COMMIT:" + task.key, 100L)) {
            recorder.event("CAVE_SOURCE_PAGE_COMMIT",
                    "page=" + task.key.globalPageX() + ','
                            + task.key.globalPageZ() + " lane=" + task.lane
                            + " present=" + snapshot.presentCount()
                            + " retained=" + snapshot.retainedCount()
                            + " resolved=16 halo_resolved="
                            + snapshot.haloResolvedCount()
                            + " atomic=true changed=" + changed
                            + " page_revision=" + committedPageRevision);
        }
    }

    private void completePageSource(ServerLevel serverLevel, PageTask task,
            PageReadTransaction transaction, PageRetryKey assemblyKey, int index,
            int firstChunkX, int firstChunkZ,
            DecodedWorldRegionCache.Result result, Throwable sourceFailure) {
        boolean present = false;
        boolean absent = false;
        boolean failed = false;
        boolean deferred = false;
        try {
            task.token.checkpoint("cave-page-source-" + index);
            if (!isCurrent(task)) {
                deferred = true;
                return;
            }
            if (sourceFailure != null || result == null) {
                failed = true;
                return;
            }

            pipelineTelemetry.recordSourceState(result.state().name());
            int localX = index % CAVE_SOURCE_WINDOW_CHUNKS;
            int localZ = index / CAVE_SOURCE_WINDOW_CHUNKS;
            boolean central = isCentralSourceCell(localX, localZ);
            switch (result.state()) {
                case PRESENT -> {
                    /*
                     * PASS141: visible exact projection and durable vertical archive
                     * are separate products. Do not make the 4x4 page wait for all
                     * 256 all-height archive columns of every decoded chunk. Build the
                     * exact requested tile immediately; CaveNativeRegionImportService
                     * continues the reusable vertical archive in the background.
                     * This matches Xaero's world-save path: sixteen NBT futures feed
                     * one requested cave MapTileChunk directly.
                     */
                    task.token.checkpoint("cave-exact-start-" + index);
                    long projectionStart = System.nanoTime();
                    DenseCaveTile tile = central
                            ? result.source().projectCaveImmediate(
                                    projector, task.key.view(),
                                    task.projectionTopY,
                                    DenseCaveTile.Source.WORLD_SAVE,
                                    task.token)
                            : null;
                    pipelineTelemetry.recordStageNanos(
                            MapPipelineStage.CAVE_PROJECTION,
                            System.nanoTime() - projectionStart);
                    task.token.checkpoint("cave-exact-finished-" + index);
                    if (isCurrent(task) && (!central || tile != null)) {
                        transaction.stagePresent(index, tile);
                        present = true;
                    } else {
                        deferred = true;
                    }
                }
                case ABSENT -> {
                    if (isCurrent(task)) {
                        transaction.stageAbsent(index);
                        absent = true;
                    } else {
                        deferred = true;
                    }
                }
                case FAILED -> failed = true;
                case DEFERRED -> deferred = true;
            }
        } catch (CancellationException cancelled) {
            deferred = true;
        } catch (Throwable chunkFailure) {
            failed = true;
            LOGGER.debug("Could not build cave source leaf {},{} in page {},{}",
                    firstChunkX + index % CAVE_SOURCE_WINDOW_CHUNKS,
                    firstChunkZ + index / CAVE_SOURCE_WINDOW_CHUNKS,
                    task.key.globalPageX(), task.key.globalPageZ(), chunkFailure);
        } finally {
            task.releaseLease(index);
            if (transaction.complete(present, absent, failed, deferred)) {
                pipelineTelemetry.recordStageNanos(MapPipelineStage.SOURCE_WAIT,
                        System.nanoTime() - transaction.sourceWaitStartNanos());
                if (transaction.assembly().isComplete()) {
                    publishCompletedAssembly(serverLevel, task, assemblyKey,
                            transaction.assembly());
                } else {
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    if (recorder.shouldEmitEvent(
                            "CAVE_SOURCE_PAGE_STAGED:" + task.key, 250L)) {
                        recorder.event("CAVE_SOURCE_PAGE_STAGED",
                                "page=" + task.key.globalPageX() + ','
                                        + task.key.globalPageZ()
                                        + " lane=" + task.lane
                                        + " resolved="
                                        + transaction.assembly().resolvedCount()
                                        + "/" + CAVE_SOURCE_WINDOW_COUNT
                                        + " deferred="
                                        + transaction.deferredCount());
                    }
                }
                long retryDelay = transaction.assembly().isComplete()
                        ? transaction.completedRetryDelayMs()
                        : transaction.retryDelayMs();
                finish(task, retryDelay);
                pump(serverLevel);
            }
        }
    }

    private synchronized boolean isCurrent(PageTask task) {
        boolean current = task.epoch == epoch
                && repository.isGenerationCurrent(task.repositoryGeneration)
                && wantedByAnyViewportLocked(task, System.currentTimeMillis());
        if (!current) task.cancel();
        return current;
    }

    private void finish(PageTask task, long retryDelayMs) {
        synchronized (this) {
            boolean owned = inFlightTasks.remove(task.key, task);
            task.cancel();
            PageRetryKey retryKey = new PageRetryKey(task.key, task.projectionTopY);
            long now = System.currentTimeMillis();
            if (owned && retryDelayMs > 0L && task.epoch == epoch
                    && wantedByAnyViewportLocked(task, now)) {
                retryAfter.put(retryKey, now + retryDelayMs);
            } else {
                retryAfter.remove(retryKey);
                if (!wantedByAnyViewportLocked(task, now)
                        || task.epoch != epoch
                        || !repository.isGenerationCurrent(task.repositoryGeneration)) {
                    pendingPageAssemblies.remove(retryKey);
                }
            }
        }
    }


    private synchronized void pruneUnwantedQueuedLocked(long now) {
        var iterator = queued.entrySet().iterator();
        while (iterator.hasNext()) {
            PageTask task = iterator.next().getValue();
            if (wantedByAnyViewportLocked(task, now)) continue;
            iterator.remove();
            task.cancel();
            pipelineTelemetry.recordTaskCancelledBeforeRun();
        }
        pendingPageAssemblies.keySet().removeIf(
                key -> !wantedAssemblyByAnyViewportLocked(key, now));
        archiveResolvedPages.keySet().removeIf(
                key -> !wantedAssemblyByAnyViewportLocked(key, now));
        resolvedPageStamps.keySet().removeIf(
                key -> !wantedAssemblyByAnyViewportLocked(key, now));
    }

    private boolean wantedAssemblyByAnyViewportLocked(PageRetryKey key, long now) {
        if (key == null) return false;
        PageTaskKey page = key.page();
        for (Map.Entry<MapRequestLane, ViewportState> entry : viewports.entrySet()) {
            ViewportState state = entry.getValue();
            if (!state.isFresh(now, entry.getKey())) continue;
            if (!page.dimension().equals(state.dimension)
                    || page.view() != state.view
                    || page.layerY() != state.layerY
                    || key.projectionTopY() != state.projectionTopY) {
                continue;
            }
            if (page.globalPageX() >= state.minPageX
                    && page.globalPageX() <= state.maxPageX
                    && page.globalPageZ() >= state.minPageZ
                    && page.globalPageZ() <= state.maxPageZ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hands a moving viewport to its new rectangle without revoking overlapping
     * page transactions. Queued overlap work is reinserted with the new fixed-region
     * priority; in-flight overlap work keeps its sixteen source leases. Only pages
     * outside every live viewport are cancelled.
     */
    private void handoffLaneGenerationLocked(MapRequestLane lane,
            ViewportState state, long now) {
        int rebasedQueued = 0;
        int retainedInFlight = 0;
        int cancelled = 0;

        var queuedIterator = queued.entrySet().iterator();
        while (queuedIterator.hasNext()) {
            PageTask task = queuedIterator.next().getValue();
            if (state.contains(task)) {
                task.rebase(lane, state.priorityFor(task, lane), sequence++);
                rebasedQueued++;
                continue;
            }
            if (wantedByOtherViewportLocked(task, now, lane)) continue;
            queuedIterator.remove();
            task.cancel();
            retryAfter.remove(new PageRetryKey(task.key, task.projectionTopY));
            pipelineTelemetry.recordTaskCancelledBeforeRun();
            cancelled++;
        }
        /*
         * Reheapify once after all priority changes. Removing every PageTask from a
         * PriorityQueue was O(n) per task and made a large pan O(n^2). The queued map
         * is the ownership authority, so rebuilding from it also drops stale entries
         * left by replacement/cancellation in one bounded pass.
         */
        queue.clear();
        queue.addAll(queued.values());

        var inFlightIterator = inFlightTasks.entrySet().iterator();
        while (inFlightIterator.hasNext()) {
            PageTask task = inFlightIterator.next().getValue();
            if (state.contains(task)) {
                task.lane = lane;
                retainedInFlight++;
                continue;
            }
            if (wantedByOtherViewportLocked(task, now, lane)) continue;
            inFlightIterator.remove();
            task.cancel();
            retryAfter.remove(new PageRetryKey(task.key, task.projectionTopY));
            pipelineTelemetry.recordTaskCancelledBeforeRun();
            cancelled++;
        }

        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (recorder.shouldEmitEvent("CAVE_SOURCE_VIEWPORT_HANDOFF", 100L)) {
            recorder.event("CAVE_SOURCE_VIEWPORT_HANDOFF",
                    "lane=" + lane + " rebased_queued=" + rebasedQueued
                            + " retained_inflight=" + retainedInFlight
                            + " cancelled=" + cancelled);
        }
    }

    private boolean wantedByOtherViewportLocked(PageTask task, long now,
            MapRequestLane excludedLane) {
        for (Map.Entry<MapRequestLane, ViewportState> entry : viewports.entrySet()) {
            MapRequestLane candidate = entry.getKey();
            if (candidate == excludedLane) continue;
            ViewportState state = entry.getValue();
            if (state.isFresh(now, candidate) && state.contains(task)) return true;
        }
        return false;
    }

    /** Cancels source transactions no longer owned by a live viewport generation. */
    private void cancelUnwantedInFlightLocked(long now) {
        var iterator = inFlightTasks.entrySet().iterator();
        while (iterator.hasNext()) {
            PageTask task = iterator.next().getValue();
            if (wantedByAnyViewportLocked(task, now)) continue;
            iterator.remove();
            task.cancel();
            retryAfter.remove(new PageRetryKey(task.key, task.projectionTopY));
            pipelineTelemetry.recordTaskCancelledBeforeRun();
        }
    }

    private boolean wantedByAnyViewportLocked(PageTask task, long now) {
        for (Map.Entry<MapRequestLane, ViewportState> entry : viewports.entrySet()) {
            MapRequestLane lane = entry.getKey();
            ViewportState state = entry.getValue();
            if (state.isFresh(now, lane) && state.contains(task)) return true;
        }
        return false;
    }

    private boolean hasQueuedMinimapLocked() {
        for (PageTask task : queued.values()) {
            if (task.lane == MapRequestLane.MINIMAP) return true;
        }
        return false;
    }

    private boolean hasInFlightMinimapLocked() {
        for (PageTask task : inFlightTasks.values()) {
            if (task.lane == MapRequestLane.MINIMAP) return true;
        }
        return false;
    }

    /** Counts one asynchronous source slice while the durable PageAssembly keeps
     * resolved leaves across retries without exposing partial repository revisions. */
    private static final class PageReadTransaction {
        private int remaining;
        private final long sourceWaitStartNanos;
        private final PageAssembly assembly;
        private int present;
        private int absent;
        private int failed;
        private int deferred;

        private PageReadTransaction(int remaining, long sourceWaitStartNanos,
                PageAssembly assembly) {
            this.remaining = remaining;
            this.sourceWaitStartNanos = sourceWaitStartNanos;
            this.assembly = assembly;
        }

        private synchronized boolean complete(boolean present, boolean absent,
                boolean failed, boolean deferred) {
            if (present) this.present++;
            if (absent) this.absent++;
            if (failed) this.failed++;
            if (deferred) this.deferred++;
            remaining--;
            return remaining == 0;
        }

        private void stagePresent(int sourceIndex, DenseCaveTile tile) {
            assembly.stagePresent(sourceIndex, tile);
        }

        private void stageAbsent(int sourceIndex) {
            assembly.stageAbsent(sourceIndex);
        }

        private PageAssembly assembly() { return assembly; }
        private synchronized int deferredCount() { return deferred; }
        private long sourceWaitStartNanos() { return sourceWaitStartNanos; }

        private synchronized long retryDelayMs() {
            if (deferred > 0) return DEFERRED_RETRY_MS;
            /*
             * ABSENT is tracked per leaf by PageAssembly. Continue the retained
             * page immediately so other unresolved central cells can load. When
             * only blocked leaves remain, readPage() polls retained/live authority
             * cheaply while the actual Anvil re-read remains blocked per leaf.
             */
            if (absent > 0) return SOURCE_SLICE_RETRY_MS;
            if (failed > 0 && assembly.resolvedCount() > 0) {
                return PARTIAL_REPAIR_RETRY_MS;
            }
            if (failed > 0) return FAILED_RETRY_MS;
            return SOURCE_SLICE_RETRY_MS;
        }

        private synchronized long completedRetryDelayMs() {
            return 1_000L;
        }
    }

    private static final class PageAssembly {
        private static final long PRESENTATION_SOURCE_MASK =
                buildCentralSourceMask();
        private final long epoch;
        private final long repositoryGeneration;
        private final DenseCaveTile[] replacements =
                new DenseCaveTile[CaveLoadHierarchy.CHUNKS_PER_PAGE_COUNT];
        private final long[] retryAfterMs =
                new long[CAVE_SOURCE_WINDOW_COUNT];
        private long resolvedMask;
        private long retainedMask;
        private long lastTouchedMs;

        private PageAssembly(long epoch, long repositoryGeneration) {
            this.epoch = epoch;
            this.repositoryGeneration = repositoryGeneration;
            touch();
        }

        private boolean matches(long epoch, long generation) {
            return this.epoch == epoch && repositoryGeneration == generation;
        }

        private synchronized void touch() {
            lastTouchedMs = System.currentTimeMillis();
        }

        private synchronized boolean isResolved(int sourceIndex) {
            return sourceIndex >= 0 && sourceIndex < CAVE_SOURCE_WINDOW_COUNT
                    && (resolvedMask & (1L << sourceIndex)) != 0L;
        }

        private synchronized void stageRetained(int sourceIndex) {
            if (sourceIndex < 0 || sourceIndex >= CAVE_SOURCE_WINDOW_COUNT) return;
            resolvedMask |= 1L << sourceIndex;
            retainedMask |= 1L << sourceIndex;
            retryAfterMs[sourceIndex] = 0L;
            touch();
        }

        private synchronized void stagePresent(int sourceIndex, DenseCaveTile tile) {
            if (sourceIndex < 0 || sourceIndex >= CAVE_SOURCE_WINDOW_COUNT) return;
            int centralIndex = centralSourceIndex(sourceIndex);
            if (centralIndex >= 0 && tile != null) {
                replacements[centralIndex] = tile;
            }
            resolvedMask |= 1L << sourceIndex;
            retainedMask &= ~(1L << sourceIndex);
            retryAfterMs[sourceIndex] = 0L;
            touch();
        }

        private synchronized void stageLivePending(int sourceIndex) {
            if (sourceIndex < 0 || sourceIndex >= CAVE_SOURCE_WINDOW_COUNT) return;
            long nextPoll = System.currentTimeMillis() + LIVE_SOURCE_POLL_MS;
            long current = retryAfterMs[sourceIndex];
            if (current == 0L || current > nextPoll) {
                retryAfterMs[sourceIndex] = nextPoll;
            }
            touch();
        }

        private synchronized void stageAbsent(int sourceIndex) {
            // PASS132/PASS139: absence is transient source state, never Cave empty.
            if (sourceIndex < 0 || sourceIndex >= CAVE_SOURCE_WINDOW_COUNT) return;
            retryAfterMs[sourceIndex] = System.currentTimeMillis()
                    + DISK_ABSENT_RETRY_MS;
            touch();
        }

        private synchronized boolean isRetryBlocked(int sourceIndex, long nowMs) {
            if (sourceIndex < 0 || sourceIndex >= CAVE_SOURCE_WINDOW_COUNT) {
                return false;
            }
            long retry = retryAfterMs[sourceIndex];
            return retry > nowMs;
        }

        private synchronized long nextRetryDelayMs(long nowMs) {
            long earliest = Long.MAX_VALUE;
            for (int index : CAVE_CENTRAL_SOURCE_ORDER) {
                if ((resolvedMask & (1L << index)) != 0L) continue;
                long retry = retryAfterMs[index];
                if (retry > nowMs && retry < earliest) earliest = retry;
            }
            return earliest == Long.MAX_VALUE ? 0L
                    : Math.max(1L, earliest - nowMs);
        }

        private synchronized boolean isComplete() {
            return (resolvedMask & PRESENTATION_SOURCE_MASK)
                    == PRESENTATION_SOURCE_MASK;
        }

        private synchronized int resolvedCount() {
            return Long.bitCount(resolvedMask);
        }

        private synchronized Snapshot snapshot() {
            ArrayList<DenseCaveTile> result = new ArrayList<>();
            int retainedCentral = 0;
            for (int order = 0;
                    order < CaveLoadHierarchy.CHUNKS_PER_PAGE_COUNT; order++) {
                int centralIndex = CaveLoadHierarchy.orderedChunkIndex(order);
                DenseCaveTile tile = replacements[centralIndex];
                if (tile != null) result.add(tile);
                int localX = centralIndex % CaveLoadHierarchy.CHUNKS_PER_PAGE
                        + CAVE_SOURCE_HALO;
                int localZ = centralIndex / CaveLoadHierarchy.CHUNKS_PER_PAGE
                        + CAVE_SOURCE_HALO;
                int sourceIndex = localZ * CAVE_SOURCE_WINDOW_CHUNKS + localX;
                if ((retainedMask & (1L << sourceIndex)) != 0L) {
                    retainedCentral++;
                }
            }
            int haloResolved = Long.bitCount(
                    resolvedMask & ~PRESENTATION_SOURCE_MASK);
            return new Snapshot(result, result.size(), retainedCentral,
                    Math.max(0, haloResolved));
        }

        private record Snapshot(List<DenseCaveTile> replacements,
                int presentCount, int retainedCount, int haloResolvedCount) {
        }
    }


    private static final class ViewportState {
        private String dimension = "";
        private CaveView view;
        private int layerY = Integer.MIN_VALUE;
        private int projectionTopY = Integer.MIN_VALUE;
        private int minPageX = Integer.MIN_VALUE;
        private int maxPageX = Integer.MIN_VALUE;
        private int minPageZ = Integer.MIN_VALUE;
        private int maxPageZ = Integer.MIN_VALUE;
        private int centerPageX = Integer.MIN_VALUE;
        private int centerPageZ = Integer.MIN_VALUE;
        private long[] pagePlan = new long[0];
        /** Real presentation plan; pagePlan may additionally contain source-only halo. */
        private long[] foregroundPagePlan = new long[0];
        private CaveLoadHierarchy.OrdinalIndex pageOrdinals = CaveLoadHierarchy.buildOrdinalIndex(new long[0]);
        private int pageCursor;
        private int updateSliceIndex;
        private long completedCycles;
        private long nextPassMs;
        private long lastRequestMs;
        private long lastSeenMs;

        private boolean matches(String dimension, CaveView view, int layerY,
                int projectionTopY, int minPageX, int maxPageX,
                int minPageZ, int maxPageZ, int centerPageX, int centerPageZ,
                MapRequestLane lane) {
            boolean focusMatches = lane == MapRequestLane.FULLSCREEN
                    || (this.centerPageX == centerPageX
                            && this.centerPageZ == centerPageZ);
            return this.dimension.equals(dimension) && this.view == view
                    && this.layerY == layerY && this.projectionTopY == projectionTopY
                    && this.minPageX == minPageX && this.maxPageX == maxPageX
                    && this.minPageZ == minPageZ && this.maxPageZ == maxPageZ
                    && focusMatches;
        }

        private boolean matchesBand(String dimension, CaveView view, int layerY,
                int minPageX, int maxPageX, int minPageZ, int maxPageZ,
                int centerPageX, int centerPageZ, MapRequestLane lane) {
            if (view != CaveView.LAYERED) return false;
            boolean focusMatches = lane == MapRequestLane.FULLSCREEN
                    || (this.centerPageX == centerPageX
                            && this.centerPageZ == centerPageZ);
            return this.dimension.equals(dimension) && this.view == view
                    && this.layerY == layerY
                    && this.minPageX == minPageX && this.maxPageX == maxPageX
                    && this.minPageZ == minPageZ && this.maxPageZ == maxPageZ
                    && focusMatches;
        }

        private boolean shouldRecenter(int centerPageX, int centerPageZ,
                MapRequestLane lane, int thresholdPages) {
            // Fullscreen source order is fixed by the viewport's world-coordinate
            // rectangle. Moving the cursor/focus inside that rectangle must not
            // restart the viewport scanline read plan.
            return false;
        }

        private void recenter(int centerPageX, int centerPageZ, MapRequestLane lane) {
            this.centerPageX = centerPageX;
            this.centerPageZ = centerPageZ;
            pagePlan = buildSourcePlan(minPageX, maxPageX, minPageZ, maxPageZ,
                    centerPageX, centerPageZ, lane);
            pageOrdinals = CaveLoadHierarchy.buildOrdinalIndex(pagePlan);
            pageCursor = 0;
            updateSliceIndex = 0;
            completedCycles = 0L;
            nextPassMs = 0L;
            lastRequestMs = 0L;
        }

        private void retargetProjection(int projectionTopY) {
            this.projectionTopY = projectionTopY;
            pageCursor = 0;
            updateSliceIndex = 0;
            completedCycles = 0L;
            nextPassMs = 0L;
            lastRequestMs = 0L;
        }

        private void reset(String dimension, CaveView view, int layerY,
                int projectionTopY, int minPageX, int maxPageX,
                int minPageZ, int maxPageZ, int centerPageX, int centerPageZ,
                MapRequestLane lane) {
            boolean continuousPan = lane == MapRequestLane.FULLSCREEN
                    && this.dimension.equals(dimension) && this.view == view
                    && this.layerY == layerY
                    && this.projectionTopY == projectionTopY
                    && rectanglesOverlap(this.minPageX, this.maxPageX,
                            this.minPageZ, this.maxPageZ,
                            minPageX, maxPageX, minPageZ, maxPageZ);
            int previousMinPageX = this.minPageX;
            int previousMaxPageX = this.maxPageX;
            int previousMinPageZ = this.minPageZ;
            int previousMaxPageZ = this.maxPageZ;
            this.dimension = dimension;
            this.view = view;
            this.layerY = layerY;
            this.projectionTopY = projectionTopY;
            this.minPageX = minPageX;
            this.maxPageX = maxPageX;
            this.minPageZ = minPageZ;
            this.maxPageZ = maxPageZ;
            this.centerPageX = centerPageX;
            this.centerPageZ = centerPageZ;
            pagePlan = buildSourcePlan(minPageX, maxPageX, minPageZ, maxPageZ,
                    centerPageX, centerPageZ, lane);
            pageOrdinals = CaveLoadHierarchy.buildOrdinalIndex(pagePlan);
            pageCursor = 0;
            updateSliceIndex = 0;
            completedCycles = 0L;
            nextPassMs = 0L;
            lastRequestMs = 0L;
        }

        private static long[] buildSourcePlan(int minPageX, int maxPageX,
                int minPageZ, int maxPageZ, int centerPageX, int centerPageZ,
                MapRequestLane lane) {
            return CaveLoadHierarchy.buildVisiblePagePlan(
                    minPageX, maxPageX, minPageZ, maxPageZ,
                    centerPageX, centerPageZ,
                    lane == MapRequestLane.FULLSCREEN);
        }

        private static boolean rectanglesOverlap(int firstMinX, int firstMaxX,
                int firstMinZ, int firstMaxZ, int secondMinX, int secondMaxX,
                int secondMinZ, int secondMaxZ) {
            return firstMinX <= secondMaxX && firstMaxX >= secondMinX
                    && firstMinZ <= secondMaxZ && firstMaxZ >= secondMinZ;
        }

        private int ordinalOf(int pageX, int pageZ) {
            return pageOrdinals.getOrDefault(
                    CaveLoadHierarchy.pack(pageX, pageZ), -1);
        }

        private int priorityFor(PageTask task, MapRequestLane lane) {
            if (lane == MapRequestLane.FULLSCREEN) {
                int ordinal = ordinalOf(
                        task.key.globalPageX(), task.key.globalPageZ());
                return lane.priorityBase() + 220_000
                        - Math.min(180_000, Math.max(0, ordinal) * 900);
            }
            int dx = task.key.globalPageX() - centerPageX;
            int dz = task.key.globalPageZ() - centerPageZ;
            int distancePenalty = Math.min(850_000,
                    (dx * dx + dz * dz) * 2_000);
            return lane.priorityBase() + 180_000 - distancePenalty;
        }

        private boolean contains(PageTask task) {
            return task.key.dimension().equals(dimension)
                    && task.key.view() == view && task.key.layerY() == layerY
                    && (task.projectionTopY == projectionTopY
                            || view == CaveView.LAYERED)
                    && task.key.globalPageX() >= minPageX
                    && task.key.globalPageX() <= maxPageX
                    && task.key.globalPageZ() >= minPageZ
                    && task.key.globalPageZ() <= maxPageZ;
        }

        private boolean isFresh(long now, MapRequestLane lane) {
            return lastSeenMs != 0L && now - lastSeenMs <= lane.requestTtlMs();
        }

        private void clear() {
            dimension = "";
            view = null;
            layerY = Integer.MIN_VALUE;
            projectionTopY = Integer.MIN_VALUE;
            minPageX = maxPageX = minPageZ = maxPageZ = Integer.MIN_VALUE;
            centerPageX = centerPageZ = Integer.MIN_VALUE;
            pagePlan = new long[0];
            pageOrdinals = CaveLoadHierarchy.buildOrdinalIndex(new long[0]);
            pageCursor = 0;
            updateSliceIndex = 0;
            completedCycles = 0L;
            nextPassMs = 0L;
            lastRequestMs = 0L;
            lastSeenMs = 0L;
        }
    }

    public SourceCacheSnapshot sourceCacheSnapshot() {
        DecodedWorldRegionCache.Stats stats = sourceCache.stats();
        return new SourceCacheSnapshot(stats.regions(), stats.decodedChunks(),
                stats.residentBytes(), stats.targetBytes(), stats.inFlight(),
                stats.prefetchInFlight(), stats.decodeQueue(),
                stats.heapPressure(), stats.heapHeadroomBytes());
    }

    public record SourceCacheSnapshot(int regions, int decodedChunks,
            long residentBytes, long targetBytes, int inFlight,
            int prefetchInFlight, int decodeQueue, double heapPressure,
            long heapHeadroomBytes) {
    }
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record PageTaskKey(String dimension, int globalPageX, int globalPageZ,
            CaveView view, int layerY) {
    }

    private record PageRetryKey(PageTaskKey page, int projectionTopY) {
    }

    private static final class PageTask implements Comparable<PageTask> {
        private final PageTaskKey key;
        private final int projectionTopY;
        private int priority;
        private MapRequestLane lane;
        private long sequence;
        private final long epoch;
        private final long repositoryGeneration;
        private final MapCancellationToken token = new MapCancellationToken(null);
        private DecodedWorldRegionCache.SourceLease[] sourceLeases;
        private DecodedWorldRegionCache.PageReservation reservation;
        private boolean cancelled;

        private PageTask(PageTaskKey key, int projectionTopY, int priority,
                MapRequestLane lane, long sequence,
                long epoch, long repositoryGeneration) {
            this.key = key;
            this.projectionTopY = projectionTopY;
            this.priority = priority;
            this.lane = lane;
            this.sequence = sequence;
            this.epoch = epoch;
            this.repositoryGeneration = repositoryGeneration;
        }

        private void rebase(MapRequestLane lane, int priority, long sequence) {
            this.lane = lane;
            this.priority = priority;
            this.sequence = sequence;
        }

        /**
         * Installs the shared source leases after the sixteen chunk requests have
         * been assembled. A viewport cancellation racing this setup immediately
         * releases them so stale Anvil/datafix work loses its consumer priority.
         */
        private synchronized void installLeases(
                DecodedWorldRegionCache.SourceLease[] leases) {
            sourceLeases = leases;
            if (cancelled) releaseLeasesLocked();
        }

        private synchronized void closeReservation() {
            DecodedWorldRegionCache.PageReservation current = reservation;
            reservation = null;
            if (current != null) current.close();
        }

        private synchronized void cancel() {
            if (cancelled) return;
            cancelled = true;
            token.cancel();
            closeReservation();
            releaseLeasesLocked();
        }

        private synchronized void releaseLeases() {
            releaseLeasesLocked();
        }

        private synchronized void releaseLease(int index) {
            DecodedWorldRegionCache.SourceLease[] leases = sourceLeases;
            if (leases == null || index < 0 || index >= leases.length) return;
            DecodedWorldRegionCache.SourceLease lease = leases[index];
            leases[index] = null;
            if (lease != null) lease.close();
        }

        private void releaseLeasesLocked() {
            DecodedWorldRegionCache.SourceLease[] leases = sourceLeases;
            sourceLeases = null;
            if (leases == null) return;
            for (DecodedWorldRegionCache.SourceLease lease : leases) {
                if (lease != null) lease.close();
            }
        }

        @Override
        public int compareTo(PageTask other) {
            int byLane = Integer.compare(other.lane.rank(), lane.rank());
            if (byLane != 0) return byLane;
            int byPriority = Integer.compare(other.priority, priority);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }
}
