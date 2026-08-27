package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.CaveMode;
import com.velorise.simplemap.client.GeneratedChunkIndex;
import com.velorise.simplemap.client.MapDebugRecorder;
import com.velorise.simplemap.client.MapConfig;
import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapVisualClassifier;
import com.velorise.simplemap.client.MapPerformanceGovernor;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapMutationBus;
import com.velorise.simplemap.client.MapViewportDemandPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.io.File;

/** Entry point for the rewritten cave subsystem. */
public final class CavePipeline {
    private static final CavePipeline INSTANCE = new CavePipeline();
    /* Periodic fallback only. Known block/fluid edits already use explicit column
     * rechecks, so continuously rescanning 80 full-height columns per second steals
     * frame time without improving a static world. */
    private static final int REVALIDATION_BATCH_COLUMNS = 2;
    private static final int REVALIDATION_INTERVAL_TICKS = 20;
    private static final int[][] REVALIDATION_CHUNK_OFFSETS = {
            { 0, 0 }, { 1, 0 }, { 0, 1 }, { -1, 0 }, { 0, -1 },
            { 1, 1 }, { -1, 1 }, { -1, -1 }, { 1, -1 }
    };

    private final CaveTileRepository repository = CaveTileRepository.getInstance();
    private final CaveTileScanner scanner = new CaveTileScanner();
    private final CaveChunkReadinessTracker readiness = new CaveChunkReadinessTracker();
    /** Multi-run archive is retained for compatibility/background analysis only. */
    private final CaveTileScheduler scheduler = new CaveTileScheduler(repository, scanner);
    /** Foreground renderer source: one dense transactionally published tile/mode. */
    private final CaveDisplayScheduler displayScheduler =
            new CaveDisplayScheduler(repository, readiness);
    private final CaveWorldSaveReader worldSaveReader = CaveWorldSaveReader.getInstance();
    private final CaveTelemetry telemetry = CaveTelemetry.getInstance();
    /** Cross-thread callbacks only append primitive keys; client work stays budgeted. */
    private final LiveRepairInbox liveRepairInbox = new LiveRepairInbox();

    private static final int MAX_VISIBLE_SOFT_REFRESH_TILES = 4096;
    private static final int MAX_TRANSITION_STALE_TILES = 4096;
    private static final long VIEWPORT_REFRESH_MEMORY_NANOS = 2_000_000_000L;
    /** Xaero warms only a compact map-chunk neighbourhood when Cave is entering. */
    private static final int AUTO_PREWARM_RADIUS_CHUNKS = 2;
    /** Fallback writer radius; the render viewport supplies the real minimap bounds. */
    private static final int GAMEPLAY_FALLBACK_RADIUS_CHUNKS = 4;
    private static final int MINIMAP_VIEWPORT_HALO_CHUNKS = 1;
    private static final long AUTO_PREWARM_BUDGET_NANOS = 350_000L;
    private static final int[][] AUTO_WARMUP_REQUIRED_OFFSETS = {
            { 0, 0 }, { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }
    };

    private long lastRevalidationTick = Long.MIN_VALUE;
    private int revalidationStep;
    private int lastViewportMinChunkX = Integer.MIN_VALUE;
    private int lastViewportMaxChunkX = Integer.MIN_VALUE;
    private int lastViewportMinChunkZ = Integer.MIN_VALUE;
    private int lastViewportMaxChunkZ = Integer.MIN_VALUE;
    private CaveView lastViewportView;
    private int lastViewportLayerY = Integer.MIN_VALUE;
    private long lastViewportNanos;
    private static final int LAYER_WARMUP_INITIAL_RADIUS = 2;
    private static final int LAYER_WARMUP_RADIUS_STEP = 2;
    private static final int LAYER_WARMUP_MAX_RADIUS = 128;
    private LayerWarmupState layerWarmup;

    private CavePipeline() {
    }

    public static CavePipeline getInstance() {
        return INSTANCE;
    }

    public CaveTileRepository repository() {
        return repository;
    }

    /** Packet path: retain only a primitive travel-frontier key. */
    public void enqueueLoadedChunk(int chunkX, int chunkZ) {
        displayScheduler.enqueueLoadedChunk(chunkX, chunkZ);
    }

    /** Callback path: no Minecraft execute(), world access, invalidation or fanout. */
    void repairTransientDiskAbsence(int chunkX, int chunkZ) {
        liveRepairInbox.offer(CaveTileRepository.pack(chunkX, chunkZ));
    }

    public CaveTelemetry.Snapshot telemetry() {
        return telemetry.snapshot();
    }

    public long generation() {
        return repository.generation();
    }

    public boolean isGenerationCurrent(long generation) {
        return repository.isGenerationCurrent(generation);
    }

    public void setCacheDirectory(File directory) {
        repository.setDirectory(directory);
        CaveRegionImageCache.getInstance().setBaseDirectory(directory);
        scheduler.reset();
        displayScheduler.reset();
        liveRepairInbox.clear();
        readiness.reset();
        worldSaveReader.reset();
        worldSaveReader.clearSourceCache();
        CaveStateClassifier.getInstance().clear();
        MapVisualClassifier.getInstance().clear();
        resetRevalidation();
        layerWarmup = null;
        UnifiedCaveTextureManager.getInstance().clear();
    }

    public void resetScanState() {
        scheduler.reset();
        displayScheduler.reset();
        // Mode/Top-Y changes use this path too. Preserve world/player readiness so a
        // normal layer switch is not misdetected as another teleport on the next tick.
        worldSaveReader.reset();
        resetRevalidation();
        layerWarmup = null;
    }

    /**
     * Runs before viewport/scanner work on the client tick. Teleports and world
     * replacements cancel only unpublished transactions; cached cave pages remain
     * visible while the new 3x3 neighbourhood reaches a stable FULL/light state.
     */
    public void tickClientState(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            readiness.reset();
            return;
        }
        if (!MapManager.getInstance().acceptsLiveLevel(minecraft.level)) {
            // Remote cave reconstruction is owned by the viewed dimension/session.
            // Movement or teleport packets from the live ClientLevel must not
            // cancel its display transactions or re-anchor its AUTO layer.
            return;
        }
        if (!readiness.observePlayer(minecraft)) return;
        scheduler.reset();
        displayScheduler.cancelInFlight();
        layerWarmup = null;

        // Freeze the old automatic Top-Y before any scanner/render caller can
        // evaluate the teleported player Y and fast-switch the active projection.
        CaveMode.holdAutomaticLayer(minecraft, 5);
        int centerChunkX = ((int) Math.floor(minecraft.player.getX())) >> 4;
        int centerChunkZ = ((int) Math.floor(minecraft.player.getZ())) >> 4;
        int radius = Math.max(2, minecraft.options.renderDistance().get() + 2);
        // A tile incorrectly committed by an older alpha must not remain permanently
        // "fresh" after teleport. Keep its pixels as fallback, but force every known
        // cave projection in the new neighbourhood through the stable live path when
        // that projection is next requested. This also covers the new Layered Top-Y
        // selected after the settle window ends.
        repository.markDisplayRangeStaleAllLayers(
                centerChunkX - radius, centerChunkX + radius,
                centerChunkZ - radius, centerChunkZ + radius,
                MAX_TRANSITION_STALE_TILES);
    }

    /**
     * Prepares the candidate Cave view behind the still-visible Surface map.
     * Xaero separates loadingCaving from loadedCaving and constrains a newly
     * entering cave write to a small centre window. This is the equivalent source
     * stage: no renderer ownership is changed and no broad render-distance square
     * is admitted before the display latch expires.
     */
    public void prewarmAroundPlayer(Minecraft minecraft) {
        if (!usable(minecraft) || CaveMode.isActive(minecraft)
                || !CaveMode.shouldPrepare(minecraft)) return;
        CaveView view = CaveMode.getCaveType(minecraft) == CaveMode.CaveType.FULL
                ? CaveView.FULL : CaveView.LAYERED;
        int layerY = view == CaveView.FULL
                ? Integer.MIN_VALUE : CaveMode.getLayerY(minecraft);
        int centerChunkX = ((int) Math.floor(minecraft.player.getX())) >> 4;
        int centerChunkZ = ((int) Math.floor(minecraft.player.getZ())) >> 4;
        int liveRadius = Math.max(2,
                minecraft.options.getEffectiveRenderDistance() + 2);
        int radius = Math.min(liveRadius, AUTO_PREWARM_RADIUS_CHUNKS);
        /*
         * Xaero separates loadingCaving from loadedCaving. Preparation must not
         * impersonate the visible minimap lane: PASS119 did that and created
         * thousands of foreground offers which the renderer correctly rejected
         * while Surface was still displayed. Warm only finite source transactions.
         */
        displayScheduler.enqueueWarmupWindow(minecraft.level, view, layerY,
                centerChunkX, centerChunkZ, radius,
                MapRequestLane.BACKGROUND.priorityBase() + 950_000);
        long governed = MapPerformanceGovernor.getInstance()
                .gameplayScanBudgetNanos(true);
        long budget = Math.min(AUTO_PREWARM_BUDGET_NANOS, governed);
        if (budget > 0L) {
            displayScheduler.process(minecraft.level,
                    System.nanoTime() + budget);
        }

        /*
         * Xaero does not swap loadedCaving until its loading canvas has already
         * been written. Pre-stage only the compact centre exact pages on the
         * BACKGROUND lane while Surface is still visible. This spreads CPU/GPU
         * publication across the one-second latch instead of making the first Cave
         * frame pay the entire exact-page cold start.
         */
        if (repository.hasFreshDisplayTileSource(view, layerY,
                centerChunkX, centerChunkZ, DenseCaveTile.Source.LIVE)) {
            double centerX = minecraft.player.getX();
            double centerZ = minecraft.player.getZ();
            double warmRadiusBlocks = (radius + 1) * 16.0;
            UnifiedCaveTextureManager textureManager =
                    UnifiedCaveTextureManager.getInstance();
            textureManager.requestVisiblePages(view, layerY,
                    centerX - warmRadiusBlocks, centerX + warmRadiusBlocks,
                    centerZ - warmRadiusBlocks, centerZ + warmRadiusBlocks,
                    1.0f, centerX, centerZ, MapRequestLane.BACKGROUND);
            textureManager.upload(false);
        }
        updateTelemetry();
    }

    /**
     * AUTO display readiness for the small centre cross. Surface remains visible
     * until these tiles are transactionally committed. This mirrors Xaero's
     * loadingCaving/loadedCaving split and prevents the switch frame from also being
     * the first frame that discovers/builds the player's Cave neighbourhood.
     */
    public boolean autoWarmupReady(Minecraft minecraft) {
        if (!usable(minecraft) || !CaveMode.shouldPrepare(minecraft)) return false;
        CaveView view = CaveMode.getCaveType(minecraft) == CaveMode.CaveType.FULL
                ? CaveView.FULL : CaveView.LAYERED;
        int layerY = view == CaveView.FULL
                ? Integer.MIN_VALUE : CaveMode.getLayerY(minecraft);
        int centerChunkX = ((int) Math.floor(minecraft.player.getX())) >> 4;
        int centerChunkZ = ((int) Math.floor(minecraft.player.getZ())) >> 4;
        // Centre + four cardinals are sufficient for a stable minimap handoff.
        // Diagonals continue warming without blocking AUTO forever.
        for (int[] offset : AUTO_WARMUP_REQUIRED_OFFSETS) {
            int chunkX = centerChunkX + offset[0];
            int chunkZ = centerChunkZ + offset[1];
            if (!minecraft.level.hasChunk(chunkX, chunkZ)) continue;
            if (!repository.hasFreshDisplayTileSource(view, layerY, chunkX, chunkZ,
                    DenseCaveTile.Source.LIVE)) return false;
        }
        if (!repository.hasFreshDisplayTileSource(view, layerY,
                centerChunkX, centerChunkZ, DenseCaveTile.Source.LIVE)) {
            return false;
        }
        int centerPageX = Math.floorDiv(centerChunkX, 4);
        int centerPageZ = Math.floorDiv(centerChunkZ, 4);
        return UnifiedCaveTextureManager.getInstance().isPageProjectionResolved(
                view, layerY, centerPageX, centerPageZ);
    }

    /** Foreground gameplay scan: complete nearby chunk tiles centre-first. */
    public void scanAroundPlayer(Minecraft minecraft, int blockRadius) {
        if (!usable(minecraft)) return;
        int centerChunkX = ((int) Math.floor(minecraft.player.getX())) >> 4;
        int centerChunkZ = ((int) Math.floor(minecraft.player.getZ())) >> 4;
        int liveRadius = Math.max(2, minecraft.options.getEffectiveRenderDistance() + 2);
        /*
         * Durable Cave collection still follows loaded chunks through packet and
         * background/archive lanes. This 20 Hz display scheduler is only the local
         * fallback/hot writer; the renderer publishes its exact MINIMAP rectangle
         * separately below, so this path must not duplicate the entire loaded set.
         */
        // This 20 Hz pass is only a fallback/hot writer. The minimap renderer
        // publishes its exact visible bounds through scanVisibleArea(), so walking
        // the entire render-distance square here duplicated hundreds of tiles every
        // AUTO Cave session and was the largest source of transition churn.
        int chunkRadius = Math.min(liveRadius, GAMEPLAY_FALLBACK_RADIUS_CHUNKS);
        CaveView view = CaveMode.isFullView(minecraft) ? CaveView.FULL : CaveView.LAYERED;
        int layerY = CaveMode.getLayerY(minecraft);
        /*
         * Gameplay is a moving viewport, not permanent background demand. The old
         * enqueueAround path walked the complete render-distance disc every client
         * tick and made every admitted tile persistent, so stale travel work kept
         * competing after the player moved. Reuse the cadence-bounded MINIMAP
         * viewport lane: unchanged shapes refresh at most every 150 ms and handoff
         * cancels only unpublished tiles outside the new live window.
         */
        // Newly received chunks are completed coherently before the broader
        // rolling viewport. This is the travel writer; it records the path while
        // keeping packet handlers allocation-free and deadline bounded.
        displayScheduler.admitLoadedChunk(minecraft.level, view, layerY,
                centerChunkX, centerChunkZ, 1_000_000);
        displayScheduler.enqueueViewport(minecraft.level, view, layerY,
                centerChunkX - chunkRadius, centerChunkX + chunkRadius,
                centerChunkZ - chunkRadius, centerChunkZ + chunkRadius,
                centerChunkX, centerChunkZ, 1_000_000,
                MapRequestLane.MINIMAP);

        // Keep a bounded canonical archive writer hot around the player. Display
        // pixels alone cannot repair a stale/missing style-independent archive.
        scheduler.enqueueAround(minecraft.level, centerChunkX, centerChunkZ,
                1, 1_100_000, CaveTileScheduler.Lane.FOREGROUND);

        long budget = MapPerformanceGovernor.getInstance().gameplayScanBudgetNanos(true);
        if (budget > 0L) {
            long started = System.nanoTime();
            long deadline = started + budget;
            drainLiveRepairInbox(minecraft,
                    started + Math.min(80_000L, budget / 10L), 16);
            repository.pumpArchivePublications(1);
            displayScheduler.process(minecraft.level,
                    started + Math.max(1L, budget * 4L / 5L));
            if (System.nanoTime() < deadline) {
                scheduler.process(minecraft.level, deadline);
            }
        }
        updateTelemetry();
    }

    /**
     * Render-frame continuation for already-admitted live Cave transactions.
     * Packet ingress and the 20 Hz viewport pass remain responsible for discovery;
     * this method only resumes the bounded hot frontier and active tile builders.
     */
    public void scanForegroundFrame(Minecraft minecraft, long budgetNanos) {
        if (budgetNanos <= 0L || !usable(minecraft)
                || !CaveMode.isActive(minecraft)) return;
        int centerChunkX = ((int) Math.floor(minecraft.player.getX())) >> 4;
        int centerChunkZ = ((int) Math.floor(minecraft.player.getZ())) >> 4;
        CaveView view = CaveMode.isFullView(minecraft)
                ? CaveView.FULL : CaveView.LAYERED;
        int layerY = CaveMode.getLayerY(minecraft);
        displayScheduler.admitLoadedChunk(minecraft.level, view, layerY,
                centerChunkX, centerChunkZ, 1_150_000);
        // Discovery belongs to the persistent render-distance viewport published
        // by scanAroundPlayer/scanVisibleArea. Replacing it every render frame with
        // a 7x7 hot window reset the rolling cursor before outer loaded chunks were
        // ever revisited. This path only resumes already-admitted work.
        long governed = CaveModeTransitionPolicy.foregroundBudget(budgetNanos);
        if (governed <= 0L) return;
        long started = System.nanoTime();
        long finalDeadline = started + governed;
        long repairSlice = Math.min(80_000L, governed / 8L);
        long archiveSlice = Math.min(120_000L, governed / 5L);
        if (repairSlice > 0L) {
            drainLiveRepairInbox(minecraft, started + repairSlice, 16);
        }
        repository.pumpArchivePublications(1);
        long displayDeadline = Math.max(started, finalDeadline - archiveSlice);
        displayScheduler.process(minecraft.level, displayDeadline);
        if (archiveSlice > 0L && System.nanoTime() < finalDeadline) {
            scheduler.process(minecraft.level, finalDeadline);
        }
        updateTelemetry();
    }

    /**
     * Starts a mode transition from the centre outward without invalidating warm
     * Surface/Cave caches and without a synchronous 20 ms scan/upload burst.
     */
    public void primeCurrentView(Minecraft minecraft) {
        if (!usable(minecraft) || !CaveMode.isActive(minecraft)) return;
        CaveView view = CaveMode.isFullView(minecraft)
                ? CaveView.FULL : CaveView.LAYERED;
        int layerY = CaveMode.getLayerY(minecraft);
        int centerChunkX = ((int) Math.floor(minecraft.player.getX())) >> 4;
        int centerChunkZ = ((int) Math.floor(minecraft.player.getZ())) >> 4;
        for (int ring = 0; ring <= 1; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;
                    if (!minecraft.level.hasChunk(chunkX, chunkZ)
                            || repository.hasFreshDisplayTileSource(view, layerY,
                                    chunkX, chunkZ, DenseCaveTile.Source.LIVE)) {
                        continue;
                    }
                    displayScheduler.enqueue(chunkX, chunkZ, view, layerY,
                            1_900_000 - ring * 20_000);
                }
            }
        }
        updateTelemetry();
    }

    /** Quiet archive capture while the surface map is active. */
    public void scanBackgroundAroundPlayer(Minecraft minecraft, int blockRadius) {
        if (!usable(minecraft)) return;
        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        if (!governor.allowBackgroundWork(minecraft)) return;
        long budget = governor.verticalArchiveBudgetNanos();
        if (budget <= 0L) return;
        int centerChunkX = ((int) Math.floor(minecraft.player.getX())) >> 4;
        int centerChunkZ = ((int) Math.floor(minecraft.player.getZ())) >> 4;
        int liveRadius = Math.max(2, minecraft.options.renderDistance().get() + 1);
        int requestedRadius = Math.max(1, (blockRadius + 15) >> 4);
        scheduler.enqueueAround(minecraft.level, centerChunkX, centerChunkZ,
                Math.min(liveRadius, requestedRadius), 250_000,
                CaveTileScheduler.Lane.BACKGROUND);
        scheduler.process(minecraft.level, System.nanoTime() + budget);
        updateTelemetry();
    }

    /**
     * Full-screen scan uses live LevelChunks near the player and the integrated
     * server's read-only .mca storage for generated chunks outside render distance.
     */
    public void scanVisibleArea(Minecraft minecraft, double minX, double maxX,
            double minZ, double maxZ, float scale) {
        scanVisibleArea(minecraft, minX, maxX, minZ, maxZ, scale,
                (minX + maxX) * 0.5, (minZ + maxZ) * 0.5,
                MapRequestLane.FULLSCREEN);
    }

    public void scanVisibleArea(Minecraft minecraft, double minX, double maxX,
            double minZ, double maxZ, float scale, MapRequestLane lane) {
        scanVisibleArea(minecraft, minX, maxX, minZ, maxZ, scale,
                (minX + maxX) * 0.5, (minZ + maxZ) * 0.5, lane);
    }

    public void scanVisibleArea(Minecraft minecraft, double minX, double maxX,
            double minZ, double maxZ, float scale,
            double focusX, double focusZ, MapRequestLane lane) {
        if (!usable(minecraft)) return;
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        MapViewportDemandPolicy.Bounds admittedViewport =
                MapViewportDemandPolicy.trimEdgeSlivers(
                        minX, maxX, minZ, maxZ, effectiveLane);
        int viewportMinChunkX = ((int) Math.floor(admittedViewport.minX())) >> 4;
        int viewportMaxChunkX = ((int) Math.floor(
                Math.nextDown(admittedViewport.maxX()))) >> 4;
        int viewportMinChunkZ = ((int) Math.floor(admittedViewport.minZ())) >> 4;
        int viewportMaxChunkZ = ((int) Math.floor(
                Math.nextDown(admittedViewport.maxZ()))) >> 4;
        double viewportCenterX = Math.max(viewportMinChunkX,
                Math.min(viewportMaxChunkX, focusX / 16.0));
        double viewportCenterZ = Math.max(viewportMinChunkZ,
                Math.min(viewportMaxChunkZ, focusZ / 16.0));

        CaveView view = CaveMode.isFullView(minecraft) ? CaveView.FULL : CaveView.LAYERED;
        int layerY = CaveMode.getLayerY(minecraft);
        lastViewportMinChunkX = viewportMinChunkX;
        lastViewportMaxChunkX = viewportMaxChunkX;
        lastViewportMinChunkZ = viewportMinChunkZ;
        lastViewportMaxChunkZ = viewportMaxChunkZ;
        lastViewportView = view;
        lastViewportLayerY = DenseCaveTile.normalizeLayer(view, layerY);
        lastViewportNanos = System.nanoTime();

        // Far zoom is rendered from branch/root textures, but Xaero still admits
        // an occasional leaf when no cached branch exists. The policy limits this to
        // one coherent page per slow pass, allowing cold areas to build LOD without
        // reopening the previous exact-page flood.
        // Saved-region reconstruction is already page-budgeted and runs off the
        // client thread. Never clip it to the live layer-warmup radius: under CPU
        // pressure warmup is intentionally paused, which used to strand fullscreen
        // loading forever in a tiny square around the player. Warmup now governs
        // only live LevelChunk replacement; the rolling saved-page frontier remains
        // free to cover the complete visible map like a region-backed world map.
        if (viewportMinChunkX <= viewportMaxChunkX
                && viewportMinChunkZ <= viewportMaxChunkZ) {
            worldSaveReader.requestVisible(minecraft, view, layerY,
                    viewportMinChunkX, viewportMaxChunkX,
                    viewportMinChunkZ, viewportMaxChunkZ,
                    viewportCenterX, viewportCenterZ, scale, effectiveLane);
        }

        /*
         * PASS148: fullscreen Cave is a stable world-save snapshot transaction.
         * Do not also advance the mutable live-column writer while the same viewport
         * is being reconstructed from Anvil. The 14:07 trace showed exactly why:
         * CAVE_RESULT_STALE=893 while only CAVE_PAGE_GPU_READY=208, with individual
         * pages rebuilding 10-20 times as live archive milestones changed the source
         * fingerprint. Xaero's WorldDataReader builds a MapTileChunk from one saved
         * snapshot and promotes it once; live writer updates are a later concern.
         *
         * The minimap/gameplay lane still owns the live writer when MapScreen is not
         * the current Cave consumer. When fullscreen closes, that lane resumes and
         * can refine any world changes made after the snapshot.
         */
        if (effectiveLane == MapRequestLane.FULLSCREEN) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent("CAVE_FULLSCREEN_SAVED_SOURCE_ONLY", 500L)) {
                recorder.event("CAVE_FULLSCREEN_SAVED_SOURCE_ONLY",
                        "view=" + view + " top_y=" + layerY
                                + " chunks=" + (viewportMaxChunkX - viewportMinChunkX + 1)
                                + 'x' + (viewportMaxChunkZ - viewportMinChunkZ + 1)
                                + " policy=anvil_snapshot_no_live_writer");
            }
            updateTelemetry();
            return;
        }

        int playerChunkX = ((int) Math.floor(minecraft.player.getX())) >> 4;
        int playerChunkZ = ((int) Math.floor(minecraft.player.getZ())) >> 4;
        int liveRadius = Math.max(2,
                minecraft.options.getEffectiveRenderDistance() + 2);
        // The player hot set is independent of a panned fullscreen frontier. Model
        // it as the MINIMAP lane instead of persistent demand. Unlike the old 3x3
        // seed, it owns the complete loaded render-distance square and cycles until
        // every still-loaded unfinished chunk has been admitted.
        if (effectiveLane == MapRequestLane.FULLSCREEN) {
            displayScheduler.enqueueViewport(minecraft.level, view, layerY,
                    playerChunkX - liveRadius, playerChunkX + liveRadius,
                    playerChunkZ - liveRadius, playerChunkZ + liveRadius,
                    playerChunkX, playerChunkZ,
                    MapRequestLane.MINIMAP.priorityBase() + 500_000,
                    MapRequestLane.MINIMAP);
        }
        int minChunkX;
        int maxChunkX;
        int minChunkZ;
        int maxChunkZ;
        if (effectiveLane == MapRequestLane.MINIMAP) {
            /*
             * Presentation demand must follow the real minimap rectangle, not the
             * complete Minecraft render-distance square. Durable vertical archive
             * capture is owned independently by scheduler/packet/background paths.
             * Xaero likewise constrains the cave writer around the centre when cave
             * mode is entering instead of rebuilding every loaded chunk at once.
             */
            minChunkX = Math.max(playerChunkX - liveRadius,
                    viewportMinChunkX - MINIMAP_VIEWPORT_HALO_CHUNKS);
            maxChunkX = Math.min(playerChunkX + liveRadius,
                    viewportMaxChunkX + MINIMAP_VIEWPORT_HALO_CHUNKS);
            minChunkZ = Math.max(playerChunkZ - liveRadius,
                    viewportMinChunkZ - MINIMAP_VIEWPORT_HALO_CHUNKS);
            maxChunkZ = Math.min(playerChunkZ + liveRadius,
                    viewportMaxChunkZ + MINIMAP_VIEWPORT_HALO_CHUNKS);
        } else {
            // Saved-data admission owns the complete fullscreen viewport. The
            // live projector owns every actually loaded chunk, independent of
            // the camera rectangle; level.hasChunk() remains the final guard in
            // CaveDisplayScheduler. Do not intersect these bounds with the
            // viewport or travelling/panning can leave valid loaded chunks
            // outside the durable cave frontier.
            minChunkX = playerChunkX - liveRadius;
            maxChunkX = playerChunkX + liveRadius;
            minChunkZ = playerChunkZ - liveRadius;
            maxChunkZ = playerChunkZ + liveRadius;
        }
        // Far-zoom fullscreen is a presentation/world-save viewport, not a second
        // mutable live writer. When fullscreen is active the complete loaded player
        // hot-set has already been admitted above through the MINIMAP lane. PASS101
        // admitted the exact same chunk square again as FULLSCREEN; TaskKey merging
        // avoided duplicate builders, but two live ViewportState owners then kept
        // old tasks mutually alive during zoom/layer handoff. The log consequently
        // sat at queued=256 with cancelled=0 even while the player was stationary.
        // Xaero has one live writer and lets world-map consumers read its retained
        // products, so keep one live authority here as well. Non-fullscreen callers
        // still admit their own lane normally.
        if (effectiveLane != MapRequestLane.FULLSCREEN
                && minChunkX <= maxChunkX && minChunkZ <= maxChunkZ) {
            displayScheduler.enqueueViewport(minecraft.level, view, layerY,
                    minChunkX, maxChunkX, minChunkZ, maxChunkZ,
                    (minChunkX + maxChunkX) * 0.5, (minChunkZ + maxChunkZ) * 0.5,
                    effectiveLane.priorityBase(), effectiveLane);
        }
        // The player-hot MINIMAP live writer above is independent of the
        // panned/zoomed fullscreen presentation frontier. Drain it even when the
        // inspected map is far from the player so live cave collection continues.
        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        long budget = effectiveLane == MapRequestLane.MINIMAP
                ? governor.gameplayScanBudgetNanos(true)
                : governor.fullscreenCaveBudgetNanos(
                        scale, MapConfig.fastFullscreenLoading);
        if (budget > 0L) {
            displayScheduler.process(minecraft.level, System.nanoTime() + budget);
        }
        updateTelemetry();
    }

    public void scanColumnNow(Level level, int blockX, int blockZ) {
        if (level == null || !level.hasChunk(blockX >> 4, blockZ >> 4)) return;
        CaveChunkTile tile = repository.getOrCreateLiveTile(blockX >> 4, blockZ >> 4);
        int index = CaveChunkTile.index(blockX & 15, blockZ & 15);
        CaveTileScanContext context = CaveTileScanContext.create(
                level, blockX >> 4, blockZ >> 4);
        long started = System.nanoTime();
        CaveColumnData data = scanner.scanColumn(level, blockX, blockZ, context);
        if (data != null) {
            boolean revalidation = tile.isColumnScanned(blockX & 15, blockZ & 15);
            boolean changed = repository.commitColumn(tile, index, data);
            if (changed) {
                enqueueCurrentDisplayPatch(blockX >> 4, blockZ >> 4,
                        blockX & 15, blockZ & 15, 1_600_000);
            }
            telemetry.recordColumnScan(System.nanoTime() - started, revalidation, changed);
        }
        updateTelemetry();
    }

    /** Compatibility entry point for direct callers outside MapMutationBus. */
    public void invalidateColumn(int blockX, int blockZ) {
        onColumnMutation(blockX, blockZ, 0);
    }

    /**
     * Event-driven mutation of one X/Z column. Archive data is invalidated at column
     * granularity; every known display layer stays visible but stale. The active
     * projection receives a dense one-column patch when a LIVE seed exists.
     */
    public void onColumnMutation(int blockX, int blockZ, int reasons) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        if (changesCaveGeometry(reasons)) {
            readiness.markChunkChanged(chunkX, chunkZ);
        }
        repository.invalidateColumn(blockX, blockZ);
        repository.markDisplayRangeStaleAllLayers(
                chunkX, chunkX, chunkZ, chunkZ, 4096);
        scheduler.enqueue(chunkX, chunkZ, 1_500_000);
        enqueueCurrentDisplayPatch(chunkX, chunkZ,
                blockX & 15, blockZ & 15, 1_700_000);
    }

    /**
     * Chunk/light replacement invalidates the resident archive tile and schedules a
     * complete dense transaction. MapMutationBus sends the changed chunk here and
     * repairs only its surface edge dependants separately.
     */
    public void onChunkMutation(int chunkX, int chunkZ, int reasons) {
        boolean geometryChanged = changesCaveGeometry(reasons);
        if (geometryChanged) {
            readiness.markChunkChanged(chunkX, chunkZ);
            repository.invalidateLoadedTile(chunkX, chunkZ);
        }
        if (geometryChanged) {
            repository.markDisplayRangeStaleAllLayers(
                    chunkX, chunkX, chunkZ, chunkZ, 4096);
        }
        // Light packets can arrive in bursts while a chunk is settling. Cave
        // topology/emissive flags come from BlockState, so rebuilding the complete
        // vertical archive for light-only changes is both redundant and extremely
        // allocation-heavy. Keep the old archive/display tile visible and restyle
        // only the active dense projection. A real block/chunk replacement still
        // invalidates and rebuilds the authoritative archive transactionally.
        if (geometryChanged) {
            scheduler.enqueue(chunkX, chunkZ, 1_650_000);
        }
        enqueueCurrentDisplayReplacement(chunkX, chunkZ, 1_800_000);
    }

    private static boolean changesCaveGeometry(int reasons) {
        int geometryReasons = MapMutationBus.BLOCK_STATE
                | MapMutationBus.CHUNK_REPLACE
                | MapMutationBus.BLOCK_ENTITY;
        return (reasons & geometryReasons) != 0;
    }

    /** Chunk unload revokes authority but deliberately preserves cached pixels. */
    public void onChunkUnavailable(int chunkX, int chunkZ, int reasons) {
        readiness.markChunkChanged(chunkX, chunkZ);
        long packed = CaveTileRepository.pack(chunkX, chunkZ);
        liveRepairInbox.cancel(packed);
        CaveChunkTile tile = repository.getLoadedTile(chunkX, chunkZ);
        if (tile != null) tile.markLiveUnavailable();
        CaveNativeRegionImportService.getInstance()
                .resumeDiskAfterLiveUnavailable(chunkX, chunkZ);
        // Unload revokes LIVE authority, not saved/archive correctness. Keep the
        // last-good display page authoritative so a render-distance edge does not
        // trigger an Anvil reread loop while the player stands or turns around.
        displayScheduler.cancelChunk(chunkX, chunkZ);
    }

    /**
     * Non-destructive recheck for block/fluid updates. The previous column remains
     * renderable until the live replacement scan completes.
     */
    public void requestColumnRecheck(int blockX, int blockZ) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        enqueueCurrentDisplayPatch(chunkX, chunkZ,
                blockX & 15, blockZ & 15, 1_400_000);
        if (repository.requestColumnRecheck(blockX, blockZ)) {
            scheduler.enqueueRevalidation(chunkX, chunkZ, 700_000);
        }
    }

    /**
     * Retargets Layered Cave inside the currently retained 16-block band.
     * Existing dense tiles, pages and LOD nodes stay visible. Only known/loaded
     * chunks are marked stale and replaced transactionally for the new exact Top-Y.
     */
    public void retargetLayer(Minecraft minecraft, int topY) {
        if (!usable(minecraft) || CaveMode.isFullView(minecraft)) return;
        int bandY = DenseCaveTile.normalizeLayer(CaveView.LAYERED, topY);
        int centerChunkX = ((int) Math.floor(minecraft.player.getX())) >> 4;
        int centerChunkZ = ((int) Math.floor(minecraft.player.getZ())) >> 4;
        int liveRadius = Math.max(2, minecraft.options.renderDistance().get() + 2);
        int maximumRadius = liveRadius;
        if (lastViewportView == CaveView.LAYERED
                && System.nanoTime() - lastViewportNanos <= VIEWPORT_REFRESH_MEMORY_NANOS) {
            maximumRadius = Math.max(maximumRadius, Math.max(
                    Math.max(Math.abs(lastViewportMinChunkX - centerChunkX),
                            Math.abs(lastViewportMaxChunkX - centerChunkX)),
                    Math.max(Math.abs(lastViewportMinChunkZ - centerChunkZ),
                            Math.abs(lastViewportMaxChunkZ - centerChunkZ))));
        }
        maximumRadius = Math.min(LAYER_WARMUP_MAX_RADIUS, maximumRadius);
        int initialRadius = Math.min(LAYER_WARMUP_INITIAL_RADIUS, maximumRadius);
        layerWarmup = new LayerWarmupState(CaveView.LAYERED, bandY, topY,
                centerChunkX, centerChunkZ, initialRadius, maximumRadius,
                minecraft.level.getGameTime() + 1L);

        markAndEnqueueWarmupRing(minecraft, layerWarmup, 0, initialRadius);
        updateTelemetry();
    }

    /** Advances retained Top-Y replacement from the player outward. */
    public void tickObservation(Minecraft minecraft, boolean allowWarmup) {
        if (!usable(minecraft)) return;
        LayerWarmupState state = layerWarmup;
        if (state == null) return;
        if (!state.matches(CaveView.LAYERED, CaveMode.getLayerY(minecraft))) {
            layerWarmup = null;
            return;
        }
        if (!allowWarmup || minecraft.level.getGameTime() < state.nextTick) return;
        if (state.currentRadius >= state.maximumRadius) {
            layerWarmup = null;
            return;
        }
        int previous = state.currentRadius;
        int next = Math.min(state.maximumRadius,
                previous + LAYER_WARMUP_RADIUS_STEP);
        state.currentRadius = next;
        state.nextTick = minecraft.level.getGameTime() + 2L;
        markAndEnqueueWarmupRing(minecraft, state, previous + 1, next);
        if (next >= state.maximumRadius) layerWarmup = null;
    }

    /**
     * Warms only retained disk/cache data for an adjacent band. It deliberately does
     * not change CaveMapManager's active layer and does not start world-save reads.
     */
    public void warmLayerBand(int topY, double minX, double maxX,
            double minZ, double maxZ, float scale) {
        int minRegionX = ((int) Math.floor(Math.min(minX, maxX))) >> 9;
        int maxRegionX = ((int) Math.floor(Math.max(minX, maxX))) >> 9;
        int minRegionZ = ((int) Math.floor(Math.min(minZ, maxZ))) >> 9;
        int maxRegionZ = ((int) Math.floor(Math.max(minZ, maxZ))) >> 9;
        int centerRegionX = (minRegionX + maxRegionX) >> 1;
        int centerRegionZ = (minRegionZ + maxRegionZ) >> 1;
        int admitted = 0;
        int maximumRing = Math.max(
                Math.max(Math.abs(centerRegionX - minRegionX),
                        Math.abs(maxRegionX - centerRegionX)),
                Math.max(Math.abs(centerRegionZ - minRegionZ),
                        Math.abs(maxRegionZ - centerRegionZ)));
        for (int ring = 0; ring <= maximumRing && admitted < 4; ring++) {
            for (int dz = -ring; dz <= ring && admitted < 4; dz++) {
                for (int dx = -ring; dx <= ring && admitted < 4; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int regionX = centerRegionX + dx;
                    int regionZ = centerRegionZ + dz;
                    if (regionX < minRegionX || regionX > maxRegionX
                            || regionZ < minRegionZ || regionZ > maxRegionZ) continue;
                    repository.requestRegionLoad(regionX, regionZ);
                    admitted++;
                }
            }
        }
        // Deliberately stop here. Adjacent-band warmup owns only retained
        // repository/source cache population. Sending a BACKGROUND viewport to
        // UnifiedCaveTextureManager used to call activateLayerProjection(), retire
        // the visible Top-Y and restart all exact/branch work every 500 ms.
    }

    private void markAndEnqueueWarmupRing(Minecraft minecraft,
            LayerWarmupState state, int minimumRing, int maximumRing) {
        for (int ring = Math.max(0, minimumRing); ring <= maximumRing; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int chunkX = state.centerChunkX + dx;
                    int chunkZ = state.centerChunkZ + dz;
                    repository.markDisplayTileStale(CaveView.LAYERED,
                            state.projectionTopY, chunkX, chunkZ);
                    if (!minecraft.level.hasChunk(chunkX, chunkZ)) continue;
                    int priority = 1_550_000 - (dx * dx + dz * dz) * 100;
                    displayScheduler.enqueueReplacement(chunkX, chunkZ,
                            CaveView.LAYERED, state.projectionTopY, priority);
                }
            }
        }
    }

    public void requestRefresh(Minecraft minecraft, int blockRadius) {
        if (!usable(minecraft)) return;
        int centerChunkX = ((int) Math.floor(minecraft.player.getX())) >> 4;
        int centerChunkZ = ((int) Math.floor(minecraft.player.getZ())) >> 4;
        int radius = Math.min(Math.max(1, (blockRadius + 15) >> 4),
                minecraft.options.renderDistance().get() + 3);
        CaveView view = CaveMode.isFullView(minecraft)
                ? CaveView.FULL : CaveView.LAYERED;
        int layerY = CaveMode.getLayerY(minecraft);
        int normalizedLayer = DenseCaveTile.normalizeLayer(view, layerY);
        if (lastViewportView == view && lastViewportLayerY == normalizedLayer
                && System.nanoTime() - lastViewportNanos <= VIEWPORT_REFRESH_MEMORY_NANOS) {
            repository.markDisplayRangeStale(view, layerY,
                    lastViewportMinChunkX, lastViewportMaxChunkX,
                    lastViewportMinChunkZ, lastViewportMaxChunkZ,
                    MAX_VISIBLE_SOFT_REFRESH_TILES);
        }
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                if (!minecraft.level.hasChunk(chunkX, chunkZ)) continue;
                repository.invalidateTile(chunkX, chunkZ);
                repository.markDisplayTileStale(view, layerY, chunkX, chunkZ);
                scheduler.enqueue(chunkX, chunkZ,
                        1_500_000 - (dx * dx + dz * dz) * 100);
                enqueueCurrentDisplayReplacement(chunkX, chunkZ,
                        1_600_000 - (dx * dx + dz * dz) * 100);
            }
        }
        long deadline = System.nanoTime() + 20_000_000L;
        displayScheduler.process(minecraft.level, deadline);
        if (System.nanoTime() < deadline) scheduler.process(minecraft.level, deadline);
        updateTelemetry();
    }

    public void requestRegionLoad(int regionX, int regionZ) {
        repository.requestRegionLoad(regionX, regionZ);
    }

    public int getColor(CaveView view, int layerY, int blockX, int blockZ) {
        return repository.getColor(view, layerY, Minecraft.getInstance().level, blockX, blockZ);
    }

    public int getHeight(CaveView view, int layerY, int blockX, int blockZ) {
        return repository.getHeight(view, layerY, Minecraft.getInstance().level, blockX, blockZ);
    }

    public CaveColumnData.Candidate getCandidate(int blockX, int blockZ,
            int maximumY, int minimumY) {
        return repository.getCandidate(blockX, blockZ, maximumY, minimumY);
    }

    public CaveColumnData.Candidate getFullCandidate(int blockX, int blockZ) {
        return repository.getFullCandidate(blockX, blockZ);
    }

    public CaveTileRepository.ResolvedRegion resolveRegion(CaveView view,
            int layerY, int regionX, int regionZ) {
        return repository.resolveRegion(view, layerY, Minecraft.getInstance().level,
                regionX, regionZ);
    }

    public boolean hasRegionData(int regionX, int regionZ) {
        return repository.hasRegionData(regionX, regionZ);
    }

    public boolean isRegionLoaded(int regionX, int regionZ) {
        return repository.isRegionLoaded(regionX, regionZ);
    }

    public long getRegionRevision(int regionX, int regionZ) {
        return repository.getRegionRevision(regionX, regionZ);
    }

    public void tickSave() {
        repository.tickSave();
        updateTelemetry();
    }

    public void clearRuntime(boolean preserveDisk) {
        scheduler.reset();
        displayScheduler.reset();
        liveRepairInbox.clear();
        readiness.reset();
        worldSaveReader.reset();
        worldSaveReader.clearSourceCache();
        layerWarmup = null;
        repository.clearRuntime(preserveDisk);
        CaveStateClassifier.getInstance().clear();
        MapVisualClassifier.getInstance().clear();
        resetRevalidation();
        UnifiedCaveTextureManager.getInstance().clear();
    }

    /** Flushes the active dimension's mutable repository/runtime state without
     * destroying dimension-keyed cave atlas pages and recursive LOD branches. */
    public void flushForDimensionSwitch() {
        scheduler.reset();
        displayScheduler.reset();
        liveRepairInbox.clear();
        readiness.reset();
        worldSaveReader.reset();
        worldSaveReader.clearSourceCache();
        layerWarmup = null;
        repository.flushAndClear();
        CaveStateClassifier.getInstance().clear();
        MapVisualClassifier.getInstance().clear();
        resetRevalidation();
        UnifiedCaveTextureManager.getInstance().suspendForDimensionSwitch();
    }

    public void flushAndClear() {
        scheduler.reset();
        displayScheduler.reset();
        liveRepairInbox.clear();
        readiness.reset();
        worldSaveReader.reset();
        layerWarmup = null;
        repository.flushAndClear();
        CaveStateClassifier.getInstance().clear();
        MapVisualClassifier.getInstance().clear();
        resetRevalidation();
        UnifiedCaveTextureManager.getInstance().clear();
    }

    public int activeLayer() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? 0 : CaveMode.getLayerY(minecraft);
    }

    private void scheduleNearPlayerRevalidation(Minecraft minecraft,
            int centerChunkX, int centerChunkZ, int liveRadius) {
        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        if (!governor.allowBackgroundWork(minecraft)) return;
        long gameTime = minecraft.level.getGameTime();
        if (lastRevalidationTick != Long.MIN_VALUE
                && gameTime - lastRevalidationTick < REVALIDATION_INTERVAL_TICKS) return;
        lastRevalidationTick = gameTime;

        int batchesPerTile = CaveChunkTile.COLUMN_COUNT / REVALIDATION_BATCH_COLUMNS;
        int tileStep = Math.floorDiv(revalidationStep, batchesPerTile)
                % REVALIDATION_CHUNK_OFFSETS.length;
        int batch = Math.floorMod(revalidationStep, batchesPerTile);
        revalidationStep = (revalidationStep + 1)
                % (REVALIDATION_CHUNK_OFFSETS.length * batchesPerTile);

        int[] offset = REVALIDATION_CHUNK_OFFSETS[tileStep];
        if (Math.abs(offset[0]) > liveRadius || Math.abs(offset[1]) > liveRadius) return;
        int chunkX = centerChunkX + offset[0];
        int chunkZ = centerChunkZ + offset[1];
        if (!minecraft.level.hasChunk(chunkX, chunkZ)) return;
        CaveChunkTile tile = repository.getLoadedTile(chunkX, chunkZ);
        if (tile == null || !tile.isComplete() || tile.recheckColumnCount() >= 32) return;

        boolean queued = false;
        int start = batch * REVALIDATION_BATCH_COLUMNS;
        for (int i = 0; i < REVALIDATION_BATCH_COLUMNS; i++) {
            // A coprime permutation spreads each batch across the tile rather than
            // repeatedly scanning one visible row from left to right.
            int column = ((start + i) * 73) & 255;
            int blockX = (chunkX << 4) + (column & 15);
            int blockZ = (chunkZ << 4) + (column >>> 4);
            queued |= repository.requestColumnRecheck(blockX, blockZ);
        }
        if (queued) scheduler.enqueueRevalidation(chunkX, chunkZ, 650_000);
    }

    /** Drains live authority transitions only on a governed client-thread lane. */
    private int drainLiveRepairInbox(Minecraft minecraft, long deadlineNanos,
            int maximum) {
        int repaired = 0;
        while (repaired < maximum && System.nanoTime() < deadlineNanos) {
            long packed = liveRepairInbox.poll();
            if (packed == LiveRepairInbox.NONE) break;
            int chunkX = (int) (packed >> 32);
            int chunkZ = (int) packed;
            if (!usable(minecraft) || !minecraft.level.hasChunk(chunkX, chunkZ)) {
                CaveNativeRegionImportService.getInstance()
                        .resumeDiskAfterLiveUnavailable(chunkX, chunkZ);
                continue;
            }

            GeneratedChunkIndex.getInstance().markLive(
                    minecraft.level, chunkX, chunkZ);
            CaveTileRepository.LiveAuthorityClaim claim =
                    repository.ensureLiveAuthority(chunkX, chunkZ);
            int routedCells = CaveNativeRegionImportService.getInstance()
                    .markLivePending(chunkX, chunkZ);
            if (claim.state() == CaveChunkTile.LiveAuthorityState.LIVE_COMPLETE) {
                CaveNativeRegionImportService.getInstance()
                        .acknowledgeLiveArchive(chunkX, chunkZ);
            } else if (claim.transitioned() && claim.tile().needsScanWork()) {
                scheduler.enqueue(chunkX, chunkZ, 1_900_000);
            }
            if (claim.transitioned() || routedCells > 0) {
                MapDebugRecorder.getInstance().event(
                        "CAVE_LIVE_REVOKED_DISK_ABSENCE",
                        "chunk=" + chunkX + ',' + chunkZ
                                + " state=" + claim.state()
                                + " routed_cells=" + routedCells
                                + " content_invalidated=false"
                                + " display_staled=false");
            }
            repaired++;
        }
        return repaired;
    }

    private void enqueueCurrentDisplayReplacement(int chunkX, int chunkZ,
            int priority) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!usable(minecraft) || !minecraft.level.hasChunk(chunkX, chunkZ)) return;
        CaveView view = CaveMode.isFullView(minecraft) ? CaveView.FULL : CaveView.LAYERED;
        displayScheduler.enqueueReplacement(chunkX, chunkZ, view,
                CaveMode.getLayerY(minecraft), priority);
    }

    private void enqueueCurrentDisplayPatch(int chunkX, int chunkZ,
            int localX, int localZ, int priority) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!usable(minecraft) || !minecraft.level.hasChunk(chunkX, chunkZ)) return;
        CaveView view = CaveMode.isFullView(minecraft) ? CaveView.FULL : CaveView.LAYERED;
        displayScheduler.enqueuePatch(chunkX, chunkZ, view,
                CaveMode.getLayerY(minecraft), localX, localZ, priority);
    }

    private void updateTelemetry() {
        UnifiedCaveTextureManager textureManager = UnifiedCaveTextureManager.getInstance();
        telemetry.updateQueues(scheduler.queuedTaskCount()
                        + displayScheduler.queuedTaskCount()
                        + worldSaveReader.queuedCount()
                        + worldSaveReader.inFlightCount()
                        + liveRepairInbox.size(),
                textureManager.requestCount(), textureManager.pendingBuildCount(),
                repository.loadedTileCount());
        telemetry.logIfEnabled();
    }

    private void resetRevalidation() {
        lastRevalidationTick = Long.MIN_VALUE;
        revalidationStep = 0;
        lastViewportMinChunkX = lastViewportMaxChunkX = Integer.MIN_VALUE;
        lastViewportMinChunkZ = lastViewportMaxChunkZ = Integer.MIN_VALUE;
        lastViewportView = null;
        lastViewportLayerY = Integer.MIN_VALUE;
        lastViewportNanos = 0L;
    }

    private static final class LiveRepairInbox {
        private static final long NONE = Long.MIN_VALUE;
        private static final int MAX_KEYS = 16_384;
        private final LongArrayFIFOQueue order = new LongArrayFIFOQueue();
        private final LongOpenHashSet queued = new LongOpenHashSet();

        private synchronized boolean offer(long key) {
            if (queued.size() >= MAX_KEYS || !queued.add(key)) return false;
            order.enqueue(key);
            return true;
        }

        private synchronized long poll() {
            while (!order.isEmpty()) {
                long key = order.dequeueLong();
                if (queued.remove(key)) return key;
            }
            return NONE;
        }

        private synchronized void cancel(long key) {
            queued.remove(key);
        }

        private synchronized int size() {
            return queued.size();
        }

        private synchronized void clear() {
            order.clear();
            queued.clear();
        }
    }

    private static final class LayerWarmupState {
        private final CaveView view;
        private final int bandY;
        private final int projectionTopY;
        private final int centerChunkX;
        private final int centerChunkZ;
        private int currentRadius;
        private final int maximumRadius;
        private long nextTick;

        private LayerWarmupState(CaveView view, int bandY, int projectionTopY,
                int centerChunkX, int centerChunkZ, int currentRadius,
                int maximumRadius, long nextTick) {
            this.view = view;
            this.bandY = bandY;
            this.projectionTopY = projectionTopY;
            this.centerChunkX = centerChunkX;
            this.centerChunkZ = centerChunkZ;
            this.currentRadius = currentRadius;
            this.maximumRadius = maximumRadius;
            this.nextTick = nextTick;
        }

        private boolean matches(CaveView requestedView, int requestedTopY) {
            return view == requestedView
                    && bandY == DenseCaveTile.normalizeLayer(requestedView, requestedTopY)
                    && projectionTopY == requestedTopY;
        }
    }

    private static boolean usable(Minecraft minecraft) {
        return minecraft != null && minecraft.level != null && minecraft.player != null
                && MapManager.getInstance().isViewingLiveDimension();
    }
}
