package com.velorise.simplemap.client;

import com.velorise.simplemap.client.surface.SurfaceDemandController;
import com.velorise.simplemap.client.cave.v2.CaveProjectionController;
import com.velorise.simplemap.client.cave.CaveWorldSaveReader;
import com.velorise.simplemap.client.cave.CaveView;
import com.velorise.simplemap.client.cave.CaveScreenSpacePolicy;
import com.velorise.simplemap.client.cave.UnifiedCaveTextureManager;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stores viewport intent during rendering and performs scanning/cache requests
 * from client tick. Rendering remains cache-only, including static dimension
 * views and heavily zoomed layered-cave views.
 */
public final class MapViewportCoordinator {
    private static final MapViewportCoordinator INSTANCE = new MapViewportCoordinator();

    private volatile Request fullscreenRequest;
    private volatile Request minimapRequest;
    private volatile long fullscreenGeneration = 1L;
    private long lastFullscreenRun;
    /** PASS160: the Cave source cursor is a persistent writer pulse, independent
     * from render/publication interaction gating. */
    private long lastFullscreenCaveSourceRun;
    private long lastMinimapRun;
    private long lastLayerUploadRun;
    private long lastAdjacentWarmupRun;
    /**
     * Surface publication must not depend on opening MapScreen or on the current
     * minimap zoom. The scanner writes CPU metadata continuously; this bounded
     * hot-set publisher converts those mutations into exact 64x64 leaves and LOD
     * parents while normal gameplay continues.
     */
    private long lastBackgroundSurfaceRun;
    /** Player render-distance source maintenance is independent from MapScreen. */
    private long lastLoadedSurfaceHaloRun;
    private long lastHiddenCaveHaloRun;
    private long lastSuspendedTeleportEpoch = Long.MIN_VALUE;
    // Background publication repairs/widens the retained surface cache. It must
    // not compete one-for-one with the visible minimap request every client tick.
    private static final long BACKGROUND_SURFACE_INTERVAL_NANOS = 250_000_000L;
    /** Cave is the visible projection, so Surface receives a slower maintenance
     * cadence rather than being paused entirely. */
    private static final long CAVE_BACKGROUND_SURFACE_INTERVAL_NANOS = 500_000_000L;
    private static final int BACKGROUND_SURFACE_MIN_RADIUS = 96;
    private static final int BACKGROUND_SURFACE_MAX_RADIUS = 256;
    private static final int CAVE_BACKGROUND_SURFACE_MAX_RADIUS = 128;
    private static final long LOADED_SURFACE_HALO_INTERVAL_NANOS = 50_000_000L;
    private static final long HIDDEN_CAVE_HALO_INTERVAL_NANOS = 150_000_000L;
    private static final long CAVE_ADJACENT_WARMUP_INTERVAL_NANOS = 2_000_000_000L;
    private static final long CAVE_FULLSCREEN_SOURCE_IDLE_INTERVAL_NANOS = 100_000_000L;
    private static final long CAVE_FULLSCREEN_SOURCE_INTERACT_INTERVAL_NANOS = 250_000_000L;
    private static final long LOADED_SURFACE_HALO_GAME_BUDGET_NANOS = 6_000_000L;
    private static final long LOADED_SURFACE_HALO_FULLSCREEN_BUDGET_NANOS = 5_000_000L;
    private LayerStreamState layerStream = new LayerStreamState();

    private MapViewportCoordinator() {
    }

    public static MapViewportCoordinator getInstance() {
        return INSTANCE;
    }

    public void submitFullscreen(double minX, double maxX, double minZ, double maxZ,
            float scale, boolean interacting) {
        submitFullscreen(minX, maxX, minZ, maxZ, scale,
                (minX + maxX) * 0.5, (minZ + maxZ) * 0.5, interacting);
    }

    /**
     * Fullscreen traversal is stable for both Surface and Cave. The mouse is only
     * an attention hint inside the current update slice and never resets the scan.
     */
    public void submitFullscreen(double minX, double maxX, double minZ, double maxZ,
            float scale, double focusX, double focusZ, boolean interacting) {
        long now = System.nanoTime();
        double clampedFocusX = clamp(focusX, minX, maxX);
        double clampedFocusZ = clamp(focusZ, minZ, maxZ);
        Request previousFullscreen = fullscreenRequest;
        boolean openingFullscreen = previousFullscreen == null;
        if (interacting && (previousFullscreen == null || !previousFullscreen.interacting)) {
            beginFullscreenInteraction();
            previousFullscreen = fullscreenRequest;
            openingFullscreen = previousFullscreen == null;
        }
        long planningKey = planningKey(minX, maxX, minZ, maxZ, scale);
        boolean planningChanged = previousFullscreen == null
                || previousFullscreen.planningKey != planningKey;
        if (previousFullscreen == null
                || (previousFullscreen.planningKey != planningKey
                        && !pageRectanglesOverlap(previousFullscreen,
                                minX, maxX, minZ, maxZ))) {
            // A continuous pan keeps most of the old viewport useful. Purging the
            // shared fullscreen executor epoch on every page-boundary crossing
            // cancelled overlapping Surface/Cave work before it could publish.
            // Subsystems perform ownership handoff for the overlap; only a disjoint
            // jump needs the coarse global purge.
            MapWorkScheduler.bumpViewport(MapRequestLane.FULLSCREEN);
        }
        if (previousFullscreen == null) {
            fullscreenRequest = new Request(minX, maxX, minZ, maxZ, scale,
                    clampedFocusX, clampedFocusZ, interacting,
                    MapRequestLane.FULLSCREEN, now, planningKey);
        } else {
            previousFullscreen.update(minX, maxX, minZ, maxZ, scale,
                    clampedFocusX, clampedFocusZ, interacting, now, planningKey);
        }
        // Rendering can call this once per visual frame while the scheduler consumes
        // at 20-25 Hz. Count only a new planning generation instead of allocating
        // and recording identical demand 40-200 times per second.
        if (planningChanged) {
            MapPipelineTelemetry.getInstance().recordViewportRequest(
                    MapRequestLane.FULLSCREEN);
        }
        Request active = fullscreenRequest;
        if (active != null) active.generation = fullscreenGeneration;
        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        governor.setFullscreenState(true, interacting);
        governor.setFocus(clampedFocusX, clampedFocusZ);
        if (openingFullscreen) {
            /*
             * Do not revoke the player-local writer when MapScreen opens. Xaero
             * keeps its minimap loading set alive behind the fullscreen map; only
             * the visible renderer changes. Revoking MINIMAP ownership here caused
             * loaded chunks and completed cave pages to accumulate until the screen
             * closed, at which point they appeared in one sudden burst.
             */
            lastLoadedSurfaceHaloRun = 0L;
            lastHiddenCaveHaloRun = 0L;
        }
    }

    public void submitMinimap(double minX, double maxX, double minZ, double maxZ,
            float scale) {
        long now = System.nanoTime();
        double focusX = (minX + maxX) * 0.5;
        double focusZ = (minZ + maxZ) * 0.5;
        Request previousMinimap = minimapRequest;
        long planningKey = planningKey(minX, maxX, minZ, maxZ, scale);
        boolean planningChanged = previousMinimap == null
                || previousMinimap.planningKey != planningKey;
        if (previousMinimap == null
                || (planningChanged && !pageRectanglesOverlap(previousMinimap,
                        minX, maxX, minZ, maxZ))) {
            // Normal player movement crosses one 64x64 boundary at a time. Cancelling
            // the entire minimap epoch at every crossing discarded overlapping exact
            // builds faster than the 50 ms tick-side consumer could publish them.
            // Only a disjoint teleport/dimension jump needs the coarse purge.
            MapWorkScheduler.bumpViewport(MapRequestLane.MINIMAP);
        }
        if (previousMinimap == null) {
            minimapRequest = new Request(minX, maxX, minZ, maxZ, scale,
                    focusX, focusZ, false, MapRequestLane.MINIMAP,
                    now, planningKey);
        } else {
            previousMinimap.update(minX, maxX, minZ, maxZ, scale,
                    focusX, focusZ, false, now, planningKey);
        }
        if (planningChanged) {
            MapPipelineTelemetry.getInstance().recordViewportRequest(
                    MapRequestLane.MINIMAP);
        }
        if (!MapPerformanceGovernor.getInstance().isFullscreenOpen()) {
            MapPerformanceGovernor.getInstance().setFocus(
                    (minX + maxX) * 0.5, (minZ + maxZ) * 0.5);
        }
    }

    /** Immediately revokes old fullscreen ownership at drag/zoom start. */
    public void beginFullscreenInteraction() {
        fullscreenGeneration++;
        MapWorkScheduler.bumpViewport(MapRequestLane.FULLSCREEN);
        Request request = fullscreenRequest;
        if (request != null) request.interacting = true;
        MapPerformanceGovernor.getInstance().setFullscreenState(true, true);
    }

    /**
     * Cancels stale/background ownership at travel start while retaining the current
     * minimap mailbox. The next tick may therefore capture and publish one bounded
     * foreground slice instead of freezing the map for the full settle window.
     */
    public void prepareMovementStreaming() {
        MapWorkScheduler.bumpViewport(MapRequestLane.BACKGROUND);
        MapWorkScheduler.bumpViewport(MapRequestLane.PREFETCH);
        UnifiedCaveTextureManager.getInstance().suspendLane(MapRequestLane.BACKGROUND);
        UnifiedCaveTextureManager.getInstance().suspendLane(MapRequestLane.PREFETCH);
        CaveWorldSaveReader.getInstance().suspendLane(MapRequestLane.BACKGROUND);
        CaveWorldSaveReader.getInstance().suspendLane(MapRequestLane.PREFETCH);
        MapPublicationCoordinator.getInstance().beginTick();
        lastMinimapRun = 0L;
        lastFullscreenRun = 0L;
        lastFullscreenCaveSourceRun = 0L;
        lastLayerUploadRun = 0L;
        lastAdjacentWarmupRun = 0L;
        lastBackgroundSurfaceRun = 0L;
        lastLoadedSurfaceHaloRun = 0L;
        lastHiddenCaveHaloRun = 0L;
    }

    /**
     * Quarantines the discontinuous player-local lane once after teleport. The
     * fullscreen world-map viewport is independent from the player's position and
     * must retain its valid work/mailbox when the player teleports while MapScreen
     * remains open. Revoking FULLSCREEN here was the source of persistent black
     * rectangles: the unchanged viewer lost ownership while the new MINIMAP halo
     * simultaneously reused atlas slots.
     */
    public void suspendForMovement() {
        MapWorkScheduler.bumpViewport(MapRequestLane.MINIMAP);
        UnifiedCaveTextureManager.getInstance().suspendLane(MapRequestLane.MINIMAP);
        CaveWorldSaveReader.getInstance().suspendLane(MapRequestLane.MINIMAP);
        MapPublicationCoordinator.getInstance().beginTick();
        MapPublicationCoordinator.getInstance().setPublicationAllowed(false);
        lastMinimapRun = 0L;
        lastLoadedSurfaceHaloRun = 0L;
        lastHiddenCaveHaloRun = 0L;
    }

    public void closeFullscreen() {
        fullscreenRequest = null;
        fullscreenGeneration++;
        MapWorkScheduler.bumpViewport(MapRequestLane.FULLSCREEN);
        UnifiedCaveTextureManager.getInstance().suspendLane(
                MapRequestLane.FULLSCREEN);
        CaveWorldSaveReader.getInstance().suspendLane(
                MapRequestLane.FULLSCREEN);
        lastFullscreenCaveSourceRun = 0L;
        layerStream = new LayerStreamState();
        MapPerformanceGovernor.getInstance().setFullscreenState(false, false);
    }

    /** Drops only selected-Y traversal state while retaining warm GPU textures. */
    public void onLayerChanged() {
        fullscreenGeneration++;
        layerStream = new LayerStreamState();
        MapWorkScheduler.bumpViewport(MapRequestLane.FULLSCREEN);
        MapWorkScheduler.bumpViewport(MapRequestLane.MINIMAP);
        lastFullscreenCaveSourceRun = 0L;
        lastLayerUploadRun = 0L;
    }

    public void reset() {
        fullscreenRequest = null;
        minimapRequest = null;
        fullscreenGeneration++;
        lastFullscreenRun = 0L;
        lastFullscreenCaveSourceRun = 0L;
        lastMinimapRun = 0L;
        lastLayerUploadRun = 0L;
        lastAdjacentWarmupRun = 0L;
        lastBackgroundSurfaceRun = 0L;
        lastLoadedSurfaceHaloRun = 0L;
        lastHiddenCaveHaloRun = 0L;
        lastSuspendedTeleportEpoch = Long.MIN_VALUE;
        layerStream = new LayerStreamState();
        MapWorkScheduler.bumpViewport(MapRequestLane.FULLSCREEN);
        MapWorkScheduler.bumpViewport(MapRequestLane.MINIMAP);
        MapPerformanceGovernor.getInstance().setFullscreenState(false, false);
    }

    public void tick(Minecraft minecraft) {
        tick(minecraft, MapPerformanceGovernor.getInstance().observationProfile(minecraft));
    }

    public void tick(Minecraft minecraft,
            MapPerformanceGovernor.ObservationProfile profile) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) return;
        MapActivityGate activityGate = MapActivityGate.getInstance();
        if (activityGate.blocksForegroundStreaming()) {
            long teleportEpoch = activityGate.teleportEpoch();
            if (lastSuspendedTeleportEpoch != teleportEpoch) {
                lastSuspendedTeleportEpoch = teleportEpoch;
                suspendForMovement();
            } else {
                // Do not bump MINIMAP/FULLSCREEN generations on every quarantine
                // tick. That repeatedly invalidated the destination work we had just
                // submitted and is the main teleport-only consistency failure.
                MapPublicationCoordinator publication =
                        MapPublicationCoordinator.getInstance();
                publication.beginTick();
                publication.setPublicationAllowed(false);
            }
            return;
        }
        if (profile == null) profile = MapPerformanceGovernor.getInstance()
                .observationProfile(minecraft);
        MapPublicationCoordinator publication = MapPublicationCoordinator.getInstance();
        publication.beginTick();
        long now = System.nanoTime();
        Request fullscreen = fullscreenRequest;
        boolean fullscreenVisible = fullscreen != null
                && fullscreen.generation == fullscreenGeneration
                && now - fullscreen.submittedNanos < 10_000_000_000L;
        // The minimap renderer is hidden while MapScreen owns the screen, but its
        // bounded Cave presentation subscriber is retained below. Xaero consumes the
        // World Map MapProcessor product directly for minimap cave chunks; Simple Map
        // mirrors that with one UnifiedCaveTextureManager product rather than letting
        // the two views diverge until MapScreen closes.
        Request minimap = minimapRequest;
        tickLoadedPlayerHalo(minecraft, now, fullscreenVisible, publication);
        if (!fullscreenVisible && minimap != null
                && now - minimap.submittedNanos < 500_000_000L
                && now - lastMinimapRun >= profile.minimapIntervalNanos()) {
            lastMinimapRun = now;
            // Minimap always represents the player's live level.
            if (MapManager.getInstance().isViewingLiveDimension()) {
                boolean caveVisibleNow = CaveMode.isActive(minecraft);
                if (caveVisibleNow) {
                    if (profile.allowSavedVisible()) {
                        // PASS148: Cave source acquisition is saved-world work, not
                        // Surface capture. PASS147 skipped this whole call to remove
                        // hidden Surface churn and accidentally disabled the active
                        // CaveWorldSaveReader path. Tie it to allowSavedVisible so
                        // movement profiles that intentionally disable Anvil IO stay
                        // loaded-source-only.
                        ChunkScanner.getInstance().scanVisibleCaveArea(minecraft,
                                minimap.minX, minimap.maxX,
                                minimap.minZ, minimap.maxZ, minimap.scale,
                                minimap.focusX, minimap.focusZ, minimap.lane);
                    }
                } else if (profile.allowVisibleScan()) {
                    ChunkScanner.getInstance().scanVisibleArea(minecraft,
                            minimap.minX, minimap.maxX,
                            minimap.minZ, minimap.maxZ, minimap.scale,
                            minimap.focusX, minimap.focusZ, minimap.lane);
                }
                if (profile.allowSavedVisible()) {
                    long savedStarted = System.nanoTime();
                    requestTextures(minecraft, minimap);
                    MapObservationTelemetry.getInstance().record(
                            MapObservationTelemetry.Lane.SAVED_VISIBLE,
                            System.nanoTime() - savedStarted, 1);
                } else if (profile.allowPublication()) {
                    /*
                     * Travel deliberately disables Anvil/world-save IO, but the
                     * foreground writer is still committing already-loaded chunks
                     * into the live Surface/Cave repositories. Keep a tiny visible
                     * demand alive so those commits can become exact leaves and be
                     * uploaded while the player moves. PASS49 requested only the
                     * publication drain here; with no active page demand there was
                     * nothing to build, so the minimap retained a black/old texture
                     * until the three-second settle window ended.
                     */
                    requestLiveTextures(minecraft, minimap);
                }
                boolean cave = CaveMode.isActive(minecraft);
                boolean full = cave && CaveMode.isFullView(minecraft);
                if (full) {
                    publication.requestFullCave(false);
                } else if (cave) {
                    publication.requestLayeredCave();
                } else {
                    // requestTextures() already admitted the minimap exact hot set.
                    // Only publish intent here; a second request pass used to repeat
                    // the same center-out traversal every minimap tick.
                    publication.requestSurface(MapRequestLane.MINIMAP, false);
                }
            }
        }


        /*
         * PASS160: source acquisition is a persistent writer, not a one-shot side
         * effect of a renderer/publication pass. PASS159 could request the first
         * 32-page Cave window, fill the first rows, then stop permanently because
         * the next source window was only selected when this coordinator entered
         * the non-interacting fullscreen publication branch again. Xaero's
         * MinimapWriter/MapWriter advances its cursor independently under a hard
         * time/cadence budget. Do the same here: the retained fullscreen request is
         * enough to advance the Cave source cursor even while composition is being
         * reused or the viewport is briefly interacting.
         */
        boolean fullscreenCaveVisible = fullscreenVisible
                && CaveMode.isActive(minecraft);

        /*
         * PASS168 / Xaero parity: while the world-map Cave view is visible there is
         * exactly one foreground presentation owner. PASS164 kept a hidden MINIMAP
         * subscriber alive here, which started a second presentation generation and
         * repeatedly reclassified the same retained region products between MINIMAP
         * and FULLSCREEN. The 12:55 trace showed that handoff churn while fullscreen
         * exact residency stayed near zero. The atlas/repository remain shared, so
         * the minimap reuses finished products when the screen closes without owning
         * a competing foreground generation while it is hidden.
         */
        if (fullscreenCaveVisible && minimap != null
                && MapManager.getInstance().isViewingLiveDimension()
                && now - lastHiddenCaveHaloRun
                        >= HIDDEN_CAVE_HALO_INTERVAL_NANOS) {
            lastHiddenCaveHaloRun = now;
            UnifiedCaveTextureManager.getInstance().suspendLane(
                    MapRequestLane.MINIMAP);
            CaveWorldSaveReader.getInstance().suspendLane(
                    MapRequestLane.MINIMAP);
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent(
                    "CAVE_FULLSCREEN_HIDDEN_MINIMAP_SUPPRESSED", 1000L)) {
                recorder.event("CAVE_FULLSCREEN_HIDDEN_MINIMAP_SUPPRESSED",
                        "top_y=" + CaveMode.getLayerY(minecraft)
                                + " policy=single_visible_foreground_owner_shared_cache"
                                + " pass=PASS168");
            }
        }

        /*
         * PASS167: the visible fullscreen Cave writer is a liveness owner, not saved
         * background work. Do not let ObservationProfile.allowSavedVisible() park it.
         * The 12:06 trace changed Layered Top-Y with 228 visible pages, advanced only
         * 26-48 source pages, then emitted no writer pulses while CPU/IO were idle.
         * Xaero's MapWriter/MinimapWriter keeps its cursor alive every update and
         * merely shrinks work to the elapsed-time budget. Our source window is already
         * pressure-bounded (32/16/8 and halved under pressure), so keep pulsing until
         * the visible cursor closes. Teleport/world handoff quarantine remains the
         * only foreground hard stop and scanVisibleCaveArea() enforces it again.
         */
        if (fullscreenCaveVisible
                && MapManager.getInstance().isViewingLiveDimension()
                && !MapActivityGate.getInstance().blocksForegroundStreaming()) {
            long caveSourceInterval = fullscreen.interacting
                    ? CAVE_FULLSCREEN_SOURCE_INTERACT_INTERVAL_NANOS
                    : CAVE_FULLSCREEN_SOURCE_IDLE_INTERVAL_NANOS;
            if (now - lastFullscreenCaveSourceRun >= caveSourceInterval) {
                lastFullscreenCaveSourceRun = now;
                double sourceFocusX = (fullscreen.minX + fullscreen.maxX) * 0.5;
                double sourceFocusZ = (fullscreen.minZ + fullscreen.maxZ) * 0.5;
                ChunkScanner.getInstance().scanVisibleCaveArea(minecraft,
                        fullscreen.minX, fullscreen.maxX,
                        fullscreen.minZ, fullscreen.maxZ, fullscreen.scale,
                        sourceFocusX, sourceFocusZ, fullscreen.lane);
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent(
                        "CAVE_FULLSCREEN_PERSISTENT_WRITER_PULSE", 500L)) {
                    recorder.event("CAVE_FULLSCREEN_PERSISTENT_WRITER_PULSE",
                            "interacting=" + fullscreen.interacting
                                    + " interval_ms=" + caveSourceInterval / 1_000_000L
                                    + " profile_saved_visible=" + profile.allowSavedVisible()
                                    + " foreground_liveness=true"
                                    + " predecessor_policy=retained_cursor_not_render_request"
                                    + " policy=xaero_persistent_visible_writer"
                                    + " pass=PASS167");
                }
            }
        }

        if (fullscreenVisible && !fullscreen.interacting
                && now - lastFullscreenRun >= profile.fullscreenIntervalNanos()) {
            lastFullscreenRun = now;

            // A static dimension has no live ClientLevel to scan. Only saved cache
            // files are streamed; this prevents Overworld blocks being written into
            // a Nether/End/modded-dimension map while browsing it remotely.
            boolean caveVisibleNow = CaveMode.isActive(minecraft);
            if (MapManager.getInstance().isViewingLiveDimension()
                    && !caveVisibleNow && profile.allowVisibleScan()) {
                double schedulingFocusX = (fullscreen.minX + fullscreen.maxX) * 0.5;
                double schedulingFocusZ = (fullscreen.minZ + fullscreen.maxZ) * 0.5;
                ChunkScanner.getInstance().scanVisibleArea(minecraft,
                        fullscreen.minX, fullscreen.maxX,
                        fullscreen.minZ, fullscreen.maxZ, fullscreen.scale,
                        schedulingFocusX, schedulingFocusZ, fullscreen.lane);
            }

            if (profile.allowSavedVisible()) {
                long savedStarted = System.nanoTime();
                requestTextures(minecraft, fullscreen);
                MapObservationTelemetry.getInstance().record(
                        MapObservationTelemetry.Lane.SAVED_VISIBLE,
                        System.nanoTime() - savedStarted, 1);
            } else if (profile.allowPublication()) {
                // Same loaded-source-only path as the minimap. This keeps an open
                // fullscreen map following the player without admitting saved IO.
                requestLiveTextures(minecraft, fullscreen);
            }
            boolean cave = CaveMode.isActive(minecraft);
            boolean full = cave && CaveMode.isFullView(minecraft);
            boolean focus = MapConfig.fastFullscreenLoading
                    && MapPerformanceGovernor.getInstance()
                            .hasForegroundUploadHeadroom();
            if (full) {
                publication.requestFullCave(focus);
            } else if (cave) {
                // Layer publication remains zoom-paced, but it is drained only once.
                long uploadInterval = layerUploadIntervalNanos(fullscreen.scale);
                if (now - lastLayerUploadRun >= uploadInterval) {
                    lastLayerUploadRun = now;
                    publication.requestLayeredCave(focus);
                }
            } else {
                publication.requestSurface(MapRequestLane.FULLSCREEN, focus);
            }
        }

        if (profile.allowArchiveBackground()) {
            tickLiveSurfaceBackground(minecraft, now, fullscreen);
        }
        tickAdjacentLayerWarmup(minecraft, now, fullscreen, minimap, profile);
        publication.setPublicationAllowed(profile.allowPublication());
    }


    /**
     * Keeps every currently loaded Minecraft chunk flowing into the Surface
     * repository even while a fullscreen map owns the visible viewport. Loaded
     * render-distance coverage is a minimap invariant, not optional background
     * prefetch: BACKGROUND requests are intentionally centre-limited to 3x3 pages
     * by MapTextureManager and therefore stranded the outer halo whenever MapScreen
     * was open. Keep this demand on the MINIMAP lane; fullscreen still owns its own
     * visible lane and GPU publication budget.
     */
    private void tickLoadedPlayerHalo(Minecraft minecraft, long now,
            boolean fullscreenVisible, MapPublicationCoordinator publication) {
        if (!MapManager.getInstance().isViewingLiveDimension()) return;
        boolean caveActive = CaveMode.isActive(minecraft);
        boolean caveFullscreenVisible = fullscreenVisible && caveActive;
        int renderDistance = Math.max(2,
                minecraft.options.getEffectiveRenderDistance());
        int radius = Math.max(64, (renderDistance + 1) * 16);
        /*
         * PASS158: the active projection owns the gameplay foreground budget.
         * PASS157 still refreshed and published the hidden Surface halo every
         * 50 ms while the Cave minimap was visible. The latest capture shows
         * thousands of Surface batches/exact refreshes and 0.7-1.2 GiB/s
         * allocation during GAME/Cave even when Cave source wait is zero. Xaero
         * keeps one bounded map writer for the selected projection instead of
         * rebuilding an invisible alternative map in parallel. Surface catches
         * up from loaded chunks after Cave is disabled.
         */
        if (!caveActive
                && now - lastLoadedSurfaceHaloRun
                        >= LOADED_SURFACE_HALO_INTERVAL_NANOS) {
            lastLoadedSurfaceHaloRun = now;
            long budget = fullscreenVisible
                    ? LOADED_SURFACE_HALO_FULLSCREEN_BUDGET_NANOS
                    : LOADED_SURFACE_HALO_GAME_BUDGET_NANOS;
            ChunkScanner.getInstance().maintainLoadedSurfaceHalo(
                    minecraft, radius, budget);
            double centerX = minecraft.player.getX();
            double centerZ = minecraft.player.getZ();
            SurfaceDemandController.getInstance().submit(
                    new SurfaceDemandController.Request(
                            centerX - radius, centerX + radius,
                            centerZ - radius, centerZ + radius,
                            centerX, centerZ, 1.0f,
                            MapRequestLane.MINIMAP,
                            false));
            publication.requestSurface(MapRequestLane.MINIMAP, false);
        }

        if (caveActive) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent(
                    "PASS158_CAVE_HIDDEN_SURFACE_SUPPRESSED", 2000L)) {
                recorder.event("CAVE_HIDDEN_SURFACE_WORK_SUPPRESSED",
                        "surface_halo=false surface_background=false "
                                + "policy=active_projection_exclusive_foreground "
                                + "pass=PASS158");
            }
        }

        /*
         * PASS145: when fullscreen Cave is the visible task, the hidden minimap
         * surface halo and the extra player-centred cave halo add CPU churn but do
         * not improve the map the user is waiting on. Let the fullscreen viewport
         * own the source budget exclusively until its visible Cave work closes.
         */
        if (!fullscreenVisible || !CaveMode.isActive(minecraft)
                || caveFullscreenVisible
                || now - lastHiddenCaveHaloRun < HIDDEN_CAVE_HALO_INTERVAL_NANOS) {
            return;
        }
        lastHiddenCaveHaloRun = now;
        CaveView view = CaveMode.isFullView(minecraft)
                ? CaveView.FULL : CaveView.LAYERED;
        int topY = view == CaveView.FULL
                ? Integer.MIN_VALUE : CaveMode.getLayerY(minecraft);
        double centerX = minecraft.player.getX();
        double centerZ = minecraft.player.getZ();
        CaveProjectionController.getInstance().request(
                view, topY,
                centerX - radius, centerX + radius,
                centerZ - radius, centerZ + radius,
                1.0f, centerX, centerZ, MapRequestLane.BACKGROUND);
    }

    /**
     * Keeps the player-centred surface hot set current even when the minimap is
     * hidden or its scale selects only a high LOD. This is deliberately small: it
     * publishes only already-scanned dirty regions and never expands the gameplay
     * chunk load radius.
     */
    private void tickLiveSurfaceBackground(Minecraft minecraft, long now, Request fullscreen) {
        if (!MapManager.getInstance().isViewingLiveDimension()) return;
        boolean caveActive = CaveMode.isActive(minecraft);
        boolean fullscreenVisible = fullscreen != null
                && now - fullscreen.submittedNanos < 500_000_000L;
        // PASS158: Surface fullscreen owns visible Surface demand, while active Cave
        // owns gameplay foreground exclusively. Hidden Surface maintenance is not
        // latency-sensitive and resumes when Cave is disabled.
        if (fullscreenVisible || caveActive) return;
        long interval = BACKGROUND_SURFACE_INTERVAL_NANOS;
        if (now - lastBackgroundSurfaceRun < interval) return;
        lastBackgroundSurfaceRun = now;

        int renderDistance = minecraft.options.renderDistance().get();
        int maximumRadius = BACKGROUND_SURFACE_MAX_RADIUS;
        int radius = Math.max(BACKGROUND_SURFACE_MIN_RADIUS,
                Math.min(maximumRadius, (renderDistance + 1) * 16));
        double centerX = minecraft.player.getX();
        double centerZ = minecraft.player.getZ();
        double minX = centerX - radius;
        double maxX = centerX + radius;
        double minZ = centerZ - radius;
        double maxZ = centerZ + radius;

        SurfaceDemandController.getInstance().submit(
                new SurfaceDemandController.Request(minX, maxX, minZ, maxZ,
                        centerX, centerZ, 1.0f,
                        MapRequestLane.BACKGROUND, false));
        MapPublicationCoordinator.getInstance().requestSurface(MapRequestLane.BACKGROUND, false);

        // Do not decode cave Anvil sources while Surface is the active projection.
        // The old warmup could fan one newly explored surface viewport into many
        // 16-chunk cave source decodes and was a major CPU-spike source.
    }

    /**
     * Warm only already-cached adjacent cave bands. This lane never changes the
     * active layer and never starts Anvil reads; it prepares retained pages that can
     * become an immediate fallback after a vertical band transition.
     */
    private void tickAdjacentLayerWarmup(Minecraft minecraft, long now,
            Request fullscreen, Request minimap,
            MapPerformanceGovernor.ObservationProfile profile) {
        if (!profile.allowLayerWarmup() || !CaveMode.isActive(minecraft)
                || CaveMode.isFullView(minecraft)
                || MapPerformanceGovernor.getInstance().underPressure()
                || (fullscreen != null
                        && now - fullscreen.submittedNanos < 500_000_000L)
                || !MapManager.getInstance().isViewingLiveDimension()) return;
        if (now - lastAdjacentWarmupRun < CAVE_ADJACENT_WARMUP_INTERVAL_NANOS) return;
        lastAdjacentWarmupRun = now;

        Request source = fullscreen != null && now - fullscreen.submittedNanos < 500_000_000L
                ? fullscreen : minimap;
        double centerX = source == null ? minecraft.player.getX()
                : (source.minX + source.maxX) * 0.5;
        double centerZ = source == null ? minecraft.player.getZ()
                : (source.minZ + source.maxZ) * 0.5;
        double radius = source == null ? 128.0
                : Math.min(256.0, Math.max(96.0,
                        Math.max(source.maxX - source.minX,
                                source.maxZ - source.minZ) * 0.25));
        int activeTopY = CaveMode.getLayerY(minecraft);
        int bandY = com.velorise.simplemap.client.cave.DenseCaveTile.normalizeLayer(
                com.velorise.simplemap.client.cave.CaveView.LAYERED, activeTopY);
        com.velorise.simplemap.client.cave.CavePipeline pipeline =
                com.velorise.simplemap.client.cave.CavePipeline.getInstance();
        long warmupStarted = System.nanoTime();
        pipeline.warmLayerBand(bandY - 8,
                centerX - radius, centerX + radius,
                centerZ - radius, centerZ + radius, 0.25f);
        pipeline.warmLayerBand(bandY + 24,
                centerX - radius, centerX + radius,
                centerZ - radius, centerZ + radius, 0.25f);
        MapObservationTelemetry.getInstance().record(
                MapObservationTelemetry.Lane.LAYER_WARMUP,
                System.nanoTime() - warmupStarted, 2);
    }

    private void requestTextures(Minecraft minecraft, Request request) {
        if (requestLiveTextures(minecraft, request)) return;

        // The loaded-source demand above is always cheap and remains active while
        // travelling. Only this second half may touch world-save/Anvil data.
        // Exact leaves are the sole surface authority. Repair incomplete/legacy
        // Surface chunks from the same decoded Anvil source used by cave
        // projections. The reader owns a small rolling page budget and skips
        // complete/known-absent chunks.
        int minimumChunkX = ((int) Math.floor(Math.min(
                request.minX, request.maxX))) >> 4;
        int maximumChunkX = ((int) Math.floor(Math.nextDown(Math.max(
                request.minX, request.maxX)))) >> 4;
        int minimumChunkZ = ((int) Math.floor(Math.min(
                request.minZ, request.maxZ))) >> 4;
        int maximumChunkZ = ((int) Math.floor(Math.nextDown(Math.max(
                request.minZ, request.maxZ)))) >> 4;
        if (minimumChunkX <= maximumChunkX && minimumChunkZ <= maximumChunkZ) {
            CaveWorldSaveReader.getInstance().prefetchVisibleSources(
                    minecraft, minimumChunkX, maximumChunkX,
                    minimumChunkZ, maximumChunkZ,
                    ((request.minX + request.maxX) * 0.5) / 16.0,
                    ((request.minZ + request.maxZ) * 0.5) / 16.0,
                    request.scale, request.lane);
        }
    }

    /**
     * Converts only repositories already populated from loaded chunks into visible
     * exact demand. No Anvil read, broad decoder, warmup or persistence work is
     * admitted here, so it is safe for the bounded movement lane.
     *
     * @return {@code true} when the active projection is Cave (which has no
     *         additional Surface saved-source work to perform).
     */
    private boolean requestLiveTextures(Minecraft minecraft, Request request) {
        boolean cave = CaveMode.isActive(minecraft);
        boolean full = cave && CaveMode.isFullView(minecraft);
        int layerY = cave ? CaveMode.getLayerY(minecraft) : Integer.MIN_VALUE;

        if (cave) {
            // Cave page projection is much heavier than Xaero's cached leaf load.
            // Far zoom remains branch-rendered, but the screen-space policy admits
            // one slow coherent leaf seed so a cold viewport can build LOD instead
            // of staying black forever.
            // Minimap is camera/player-centred. Fullscreen uses a stable
            // viewport-owned traversal; cursor movement never changes admission.
            double schedulingFocusX = (request.minX + request.maxX) * 0.5;
            double schedulingFocusZ = (request.minZ + request.maxZ) * 0.5;
            if (!full) CaveMapManager.getInstance().setActiveLayer(layerY);
            CaveProjectionController.getInstance().request(
                    full ? com.velorise.simplemap.client.cave.CaveView.FULL
                            : com.velorise.simplemap.client.cave.CaveView.LAYERED,
                    full ? Integer.MIN_VALUE : layerY,
                    request.minX, request.maxX, request.minZ, request.maxZ,
                    request.scale, schedulingFocusX, schedulingFocusZ,
                    request.lane);
            return true;
        }

        // Exact leaves are the sole surface authority. At far zoom the manager
        // admits only a bounded attention hot set; recursive branches cover the
        // remainder without constructing legacy 512x512 overview textures.
        SurfaceDemandController.getInstance().submit(
                new SurfaceDemandController.Request(
                        request.minX, request.maxX,
                        request.minZ, request.maxZ,
                        (request.minX + request.maxX) * 0.5,
                        (request.minZ + request.maxZ) * 0.5,
                        request.scale, request.lane,
                        request.lane == MapRequestLane.FULLSCREEN));
        return false;
    }

    /**
     * Streams exact selected-Y cave regions center-out. It deliberately avoids
     * low-detail cave parents: thin tunnels retain the old sharp appearance while
     * the region request rate falls with zoom level.
     */
    private void requestLayerRegions(Request request, int layerY) {
        int minRx = (int) Math.floor(request.minX - 1.0) >> 9;
        int maxRx = (int) Math.floor(request.maxX + 1.0) >> 9;
        int minRz = (int) Math.floor(request.minZ - 1.0) >> 9;
        int maxRz = (int) Math.floor(request.maxZ + 1.0) >> 9;
        String dimension = MapManager.getInstance().getDimensionCacheKey();
        int focusRx = Math.max(minRx, Math.min(maxRx,
                (int) Math.floor(request.focusX) >> 9));
        int focusRz = Math.max(minRz, Math.min(maxRz,
                (int) Math.floor(request.focusZ) >> 9));

        if (!layerStream.matches(dimension, layerY, minRx, maxRx, minRz, maxRz,
                focusRx, focusRz)) {
            layerStream = LayerStreamState.create(dimension, layerY,
                    minRx, maxRx, minRz, maxRz, focusRx, focusRz);
        } else if (layerStream.cursor >= layerStream.regions.size()
                && System.nanoTime() - layerStream.completedAtNanos >= 4_000_000_000L) {
            // A slow maintenance pass catches previously saturated IO or newly
            // written archive data without producing a visible centre-out wave
            // every 750 ms over an already cached selected-Y layer.
            layerStream.cursor = 0;
        }

        int budget = layerRegionBudget(request.scale);
        CaveMapManager layers = CaveMapManager.getInstance();
        VerticalCaveArchiveManager archive = VerticalCaveArchiveManager.getInstance();
        CaveTextureManager textures = CaveTextureManager.getInstance();
        int processed = 0;
        while (processed < budget && layerStream.cursor < layerStream.regions.size()) {
            int[] region = layerStream.regions.get(layerStream.cursor++);
            int rx = region[0];
            int rz = region[1];
            processed++;

            textures.requestVisibleRegion(layerY, rx, rz);
            boolean warmExactTexture = layers.hasRegionFile(rx, rz)
                    && textures.peekRegionTexture(layerY, rx, rz) != null;
            // A warm exact-layer GPU texture is already the requested result. Keep it
            // visible without reloading/reprojecting the same file after every Top-Y
            // switch. Missing regions still stream in square region rings.
            if (!warmExactTexture && (layers.hasRegionFile(rx, rz)
                    || layers.isRegionLoaded(rx, rz) || archive.hasRegionData(rx, rz))) {
                int dx = rx - layerStream.centerRx;
                int dz = rz - layerStream.centerRz;
                int priority = 250_000 - (dx * dx + dz * dz) * 100;
                MapProcessor.getInstance().enqueueCaveLoad(
                        layerY, rx, rz, Math.max(1, priority));
            }
        }
        if (layerStream.cursor >= layerStream.regions.size()) {
            layerStream.completedAtNanos = System.nanoTime();
        }
    }

    private static int layerRegionBudget(float scale) {
        // Each exact layer region may project 262,144 columns/pixels and upload a
        // 512x512 texture. Keep far zoom intentionally conservative.
        if (scale < 0.12f) return 1;
        if (scale < 0.20f) return 1;
        if (scale < 0.35f) return 2;
        if (scale < 0.55f) return 4;
        return 8;
    }

    private static long layerUploadIntervalNanos(float scale) {
        if (scale < 0.12f) return 60_000_000L;
        if (scale < 0.20f) return 45_000_000L;
        if (scale < 0.35f) return 30_000_000L;
        return 20_000_000L;
    }

    /** Mutable render-to-tick mailbox. Reusing it removes one short-lived object
     * per rendered minimap/fullscreen frame while still publishing the newest
     * bounds and focus to the client-tick consumer. */
    private static final class Request {
        private double minX;
        private double maxX;
        private double minZ;
        private double maxZ;
        private float scale;
        private double focusX;
        private double focusZ;
        private boolean interacting;
        private final MapRequestLane lane;
        private volatile long submittedNanos;
        private long planningKey;
        private volatile long generation;

        private Request(double minX, double maxX, double minZ, double maxZ,
                float scale, double focusX, double focusZ,
                boolean interacting, MapRequestLane lane, long submittedNanos,
                long planningKey) {
            this.lane = lane;
            update(minX, maxX, minZ, maxZ, scale, focusX, focusZ,
                    interacting, submittedNanos, planningKey);
        }

        private void update(double minX, double maxX, double minZ, double maxZ,
                float scale, double focusX, double focusZ,
                boolean interacting, long submittedNanos, long planningKey) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.scale = scale;
            this.focusX = focusX;
            this.focusZ = focusZ;
            this.interacting = interacting;
            this.planningKey = planningKey;
            // Publish timestamp last so a tick that observes a fresh request also
            // observes all preceding viewport fields.
            this.submittedNanos = submittedNanos;
        }
    }


    private static boolean pageRectanglesOverlap(Request previous,
            double minX, double maxX, double minZ, double maxZ) {
        int previousMinPageX = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(previous.minX));
        int previousMaxPageX = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(previous.maxX));
        int previousMinPageZ = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(previous.minZ));
        int previousMaxPageZ = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(previous.maxZ));
        int currentMinPageX = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(minX));
        int currentMaxPageX = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(maxX));
        int currentMinPageZ = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(minZ));
        int currentMaxPageZ = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(maxZ));
        return previousMinPageX <= currentMaxPageX
                && previousMaxPageX >= currentMinPageX
                && previousMinPageZ <= currentMaxPageZ
                && previousMaxPageZ >= currentMinPageZ;
    }

    private static long planningKey(double minX, double maxX,
            double minZ, double maxZ, float scale) {
        int minPageX = MapPageLayout.globalPageFromBlock((int) Math.floor(minX));
        int maxPageX = MapPageLayout.globalPageFromBlock((int) Math.floor(maxX));
        int minPageZ = MapPageLayout.globalPageFromBlock((int) Math.floor(minZ));
        int maxPageZ = MapPageLayout.globalPageFromBlock((int) Math.floor(maxZ));
        int scaleBucket = Math.max(0, Math.min(15,
                (int) Math.floor(Math.log(Math.max(0.0001f, scale)) / Math.log(2.0) + 8.0)));
        long key = 0x9E3779B97F4A7C15L;
        key = Long.rotateLeft(key ^ minPageX, 13) * 0xC2B2AE3D27D4EB4FL;
        key = Long.rotateLeft(key ^ maxPageX, 17) * 0x165667B19E3779F9L;
        key = Long.rotateLeft(key ^ minPageZ, 21) * 0x85EBCA77C2B2AE63L;
        key = Long.rotateLeft(key ^ maxPageZ, 29) * 0x27D4EB2F165667C5L;
        return key ^ scaleBucket;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(Math.min(minimum, maximum),
                Math.min(Math.max(minimum, maximum), value));
    }

    private static final class LayerStreamState {
        private String dimension = "";
        private int layerY = Integer.MIN_VALUE;
        private int minRx;
        private int maxRx;
        private int minRz;
        private int maxRz;
        private int centerRx;
        private int centerRz;
        private List<int[]> regions = List.of();
        private int cursor;
        private long completedAtNanos;

        private static LayerStreamState create(String dimension, int layerY,
                int minRx, int maxRx, int minRz, int maxRz,
                int focusRx, int focusRz) {
            LayerStreamState state = new LayerStreamState();
            state.dimension = dimension;
            state.layerY = layerY;
            state.minRx = minRx;
            state.maxRx = maxRx;
            state.minRz = minRz;
            state.maxRz = maxRz;
            state.centerRx = Math.max(minRx, Math.min(maxRx, focusRx));
            state.centerRz = Math.max(minRz, Math.min(maxRz, focusRz));
            List<int[]> regions = new ArrayList<>();
            for (int rx = minRx; rx <= maxRx; rx++) {
                for (int rz = minRz; rz <= maxRz; rz++) {
                    regions.add(new int[] { rx, rz });
                }
            }
            regions.sort(Comparator
                    .comparingInt((int[] region) -> {
                        int dx = region[0] - state.centerRx;
                        int dz = region[1] - state.centerRz;
                        return dx * dx + dz * dz;
                    })
                    .thenComparingInt(region -> Math.max(
                            Math.abs(region[0] - state.centerRx),
                            Math.abs(region[1] - state.centerRz)))
                    .thenComparingInt(region -> region[1])
                    .thenComparingInt(region -> region[0]));
            state.regions = regions;
            return state;
        }

        private boolean matches(String dimension, int layerY,
                int minRx, int maxRx, int minRz, int maxRz,
                int focusRx, int focusRz) {
            return this.dimension.equals(dimension) && this.layerY == layerY
                    && this.minRx == minRx && this.maxRx == maxRx
                    && this.minRz == minRz && this.maxRz == maxRz
                    && this.centerRx == focusRx && this.centerRz == focusRz;
        }
    }
}
