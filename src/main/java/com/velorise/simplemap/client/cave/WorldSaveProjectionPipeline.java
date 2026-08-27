package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.GeneratedChunkIndex;
import com.velorise.simplemap.client.MapDebugRecorder;
import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapPerformanceGovernor;
import com.velorise.simplemap.client.MapViewLoadPlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * Single world-save source pipeline shared by Surface, Layered Cave and Full Cave.
 *
 * <p>The pipeline performs viewport planning once, routes every requested native
 * Anvil region through {@link CaveNativeRegionImportService}, and lets that one
 * source transaction fan out into presentation projections. A chunk is read and
 * palette-decoded by {@link DecodedWorldRegionCache} once. Surface projection and
 * the reusable vertical cave archive are then derived from the same
 * {@link DecodedWorldChunkSource}; mode changes never start a second .mca reader.</p>
 */
public final class WorldSaveProjectionPipeline {
    // PASS150 cumulative guard marker: xaero_snapshot_barrier_direct_server_submit pass=PASS150
    private static final WorldSaveProjectionPipeline INSTANCE =
            new WorldSaveProjectionPipeline();
    private static final int FULLSCREEN_SURFACE_STICKY_HALO_PAGES = 2;
    /**
     * PASS145: Cave fullscreen was over-reserving source-only support pages. The
     * validation run requested 198 foreground pages but expanded them to 330 source
     * pages, so roughly two thirds as much extra work was admitted before the user
     * saw a complete result. Keep a small one-page overlap for smooth panning, but
     * stop paying the old two-page halo tax for Cave.
     */
    private static final int FULLSCREEN_CAVE_STICKY_HALO_PAGES = 0;
    /**
     * PASS157: world-save source ownership is a small deterministic writer window,
     * not the whole visible leaf rectangle. At 0.1x the old path rebuilt/preflighted
     * 4k+ exact page descriptors every viewport pulse although the renderer emitted
     * only a handful of branch quads. Xaero advances a persistent writer/update
     * cursor and bounds each update slice; keep the same asymptotic property here.
     */
    private static final int FULLSCREEN_CAVE_SOURCE_WINDOW_EXACT = 32;
    private static final int FULLSCREEN_CAVE_SOURCE_WINDOW_MID = 16;
    private static final int FULLSCREEN_CAVE_SOURCE_WINDOW_BRANCH = 8;

    private final DecodedWorldRegionCache sourceCache =
            DecodedWorldRegionCache.getInstance();
    private final CaveNativeRegionImportService regionImporter =
            CaveNativeRegionImportService.getInstance();
    private final SurfaceWorldSaveReconstructor surface =
            SurfaceWorldSaveReconstructor.getInstance();
    private final FullscreenCaveSourceWindow fullscreenCaveSourceWindow =
            new FullscreenCaveSourceWindow();
    private final MinimapCaveSourceWindow minimapCaveSourceWindow =
            new MinimapCaveSourceWindow();
    /** Xaero flushes the integrated-server chunk storage once before bulk world-save
     * reconstruction. Keep the same snapshot barrier per fullscreen Cave session. */
    private CompletableFuture<Boolean> fullscreenCaveFlush;
    private String fullscreenCaveFlushDimension = "";
    private long fullscreenCaveFlushGeneration = Long.MIN_VALUE;

    private WorldSaveProjectionPipeline() {
    }

    public static WorldSaveProjectionPipeline getInstance() {
        return INSTANCE;
    }

    /**
     * Requests one visible projection without changing source ownership for other
     * projections. Surface and Cave views can therefore coexist (minimap plus open
     * world map) while sharing the same decoded native-region source cells.
     */
    public void requestVisible(Minecraft minecraft, WorldProjection projection,
            int requestedTopY, int minChunkX, int maxChunkX,
            int minChunkZ, int maxChunkZ, double centerChunkX,
            double centerChunkZ, float scale, MapRequestLane lane) {
        sourceCache.maintain();
        regionImporter.maintain();
        CaveRegionProjectionService.getInstance().maintain();
        surface.drainReadyApplications();
        if (minecraft == null || minecraft.level == null || projection == null) return;
        ServerLevel level = resolveViewedServerLevel(minecraft);
        if (level == null) return;

        GeneratedChunkIndex.getInstance().observeLevel(level);
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        int minimumPageX = Math.floorDiv(Math.min(minChunkX, maxChunkX), 4);
        int maximumPageX = Math.floorDiv(Math.max(minChunkX, maxChunkX), 4);
        int minimumPageZ = Math.floorDiv(Math.min(minChunkZ, maxChunkZ), 4);
        int maximumPageZ = Math.floorDiv(Math.max(minChunkZ, maxChunkZ), 4);
        int foregroundMinPageX = minimumPageX;
        int foregroundMaxPageX = maximumPageX;
        int foregroundMinPageZ = minimumPageZ;
        int foregroundMaxPageZ = maximumPageZ;
        if (effectiveLane == MapRequestLane.FULLSCREEN) {
            int stickyHaloPages = projection == WorldProjection.SURFACE
                    ? FULLSCREEN_SURFACE_STICKY_HALO_PAGES
                    : FULLSCREEN_CAVE_STICKY_HALO_PAGES;
            minimumPageX -= stickyHaloPages;
            maximumPageX += stickyHaloPages;
            minimumPageZ -= stickyHaloPages;
            maximumPageZ += stickyHaloPages;
        } else if (effectiveLane == MapRequestLane.MINIMAP) {
            int caveRadius = MapViewLoadPlanner.CAVE_MINIMAP_MAX_RADIUS_PAGES;
            int rawCenterPageX = (int) Math.floor(centerChunkX / 4.0);
            int rawCenterPageZ = (int) Math.floor(centerChunkZ / 4.0);
            minimumPageX = Math.max(minimumPageX, rawCenterPageX - caveRadius);
            maximumPageX = Math.min(maximumPageX, rawCenterPageX + caveRadius);
            minimumPageZ = Math.max(minimumPageZ, rawCenterPageZ - caveRadius);
            maximumPageZ = Math.min(maximumPageZ, rawCenterPageZ + caveRadius);
            foregroundMinPageX = minimumPageX;
            foregroundMaxPageX = maximumPageX;
            foregroundMinPageZ = minimumPageZ;
            foregroundMaxPageZ = maximumPageZ;
        }
        if (minimumPageX > maximumPageX || minimumPageZ > maximumPageZ) return;

        int centerPageX = clamp((int) Math.floor(centerChunkX / 4.0),
                minimumPageX, maximumPageX);
        int centerPageZ = clamp((int) Math.floor(centerChunkZ / 4.0),
                minimumPageZ, maximumPageZ);

        String dimension = MapManager.getInstance().getDimensionCacheKey();
        if (dimension == null || dimension.isBlank()) {
            dimension = level.dimension().location().toString();
        }
        long generation = CaveTileRepository.getInstance().generation();
        if (effectiveLane == MapRequestLane.FULLSCREEN
                && projection != WorldProjection.SURFACE
                && !ensureFullscreenCaveSnapshot(level, dimension, generation)) {
            return;
        }

        long[] pagePlan;
        long[] foregroundPagePlan;
        if (effectiveLane == MapRequestLane.FULLSCREEN
                && projection != WorldProjection.SURFACE) {
            CaveView caveView = projection.caveView();
            int caveTopY = projection.canonicalTopY(requestedTopY);
            boolean branchOnly = CaveScreenSpacePolicy.branchOnly(scale, effectiveLane);
            pagePlan = buildFullscreenCaveSourceWindow(dimension, caveView, caveTopY,
                    minimumPageX, maximumPageX, minimumPageZ, maximumPageZ,
                    scale, branchOnly, generation);
            if (pagePlan.length == 0) {
                if (fullscreenCaveSourceWindow.markRetiredOnce()) {
                    /*
                     * PASS159: source completion is not presentation completion.
                     * PASS157 called suspendCaveLane() here; that retires ProjectionDemand
                     * and CaveRegionProjectionService foreground ownership as well as
                     * Anvil source work. A Layered viewport could therefore have
                     * complete CPU/archive source for ~200 pages, only 15 GPU-resident
                     * pages, and then no runnable owner for the remaining presentation.
                     *
                     * Stop only the bounded source writer. Exact projection demand
                     * stays alive until the viewport/mode itself is retired.
                     */
                    regionImporter.pauseCaveSourceLane(MapRequestLane.FULLSCREEN);
                    MapDebugRecorder.getInstance().event(
                            "CAVE_FULLSCREEN_SOURCE_WINDOW_COMPLETE",
                            "view=" + caveView + " top_y=" + caveTopY
                                    + " pages=" + fullscreenCaveSourceWindow.totalPages
                                    + " presentation_retained=true"
                                    + " policy=source_complete_not_presentation_complete"
                                    + " pass=PASS159");
                }
                return;
            }
            // Cave uses no source-only halo in PASS156+, therefore the bounded source
            // writer window is also the only native projection demand window.
            foregroundPagePlan = pagePlan;
        } else if (effectiveLane == MapRequestLane.MINIMAP
                && projection != WorldProjection.SURFACE) {
            CaveView caveView = projection.caveView();
            int caveTopY = projection.canonicalTopY(requestedTopY);
            boolean branchOnly = CaveScreenSpacePolicy.branchOnly(scale, effectiveLane);
            pagePlan = buildMinimapCaveSourceWindow(dimension, caveView, caveTopY,
                    minimumPageX, maximumPageX, minimumPageZ, maximumPageZ,
                    centerPageX, centerPageZ, branchOnly, generation);
            if (pagePlan.length == 0) {
                if (minimapCaveSourceWindow.markRetiredOnce()) {
                    regionImporter.pauseCaveSourceLane(MapRequestLane.MINIMAP);
                }
                return;
            }
            foregroundPagePlan = pagePlan;
        } else {
            pagePlan = CaveLoadHierarchy.buildVisiblePagePlan(
                    minimumPageX, maximumPageX, minimumPageZ, maximumPageZ,
                    centerPageX, centerPageZ, true);
            foregroundPagePlan = CaveLoadHierarchy.buildVisiblePagePlan(
                    foregroundMinPageX, foregroundMaxPageX,
                    foregroundMinPageZ, foregroundMaxPageZ,
                    clamp((int) Math.floor(centerChunkX / 4.0),
                            foregroundMinPageX, foregroundMaxPageX),
                    clamp((int) Math.floor(centerChunkZ / 4.0),
                            foregroundMinPageZ, foregroundMaxPageZ),
                    true);
            if (pagePlan.length == 0 || foregroundPagePlan.length == 0) return;
        }
        if (projection == WorldProjection.SURFACE) {
            regionImporter.suspendCaveLane(effectiveLane);
            regionImporter.requestSurfaceViewport(level, dimension, pagePlan,
                    centerPageX, centerPageZ, effectiveLane, generation);
        } else {
            regionImporter.suspendSurfaceLane(effectiveLane);
            regionImporter.requestViewport(level, dimension,
                    projection.caveView(), projection.canonicalTopY(requestedTopY),
                    pagePlan, foregroundPagePlan,
                    minimumPageX, maximumPageX, minimumPageZ, maximumPageZ,
                    !CaveScreenSpacePolicy.branchOnly(scale, effectiveLane),
                    centerPageX, centerPageZ, effectiveLane, generation);
        }
    }

    private synchronized long[] buildMinimapCaveSourceWindow(
            String dimension, CaveView view, int projectionTopY,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ,
            int focusPageX, int focusPageZ, boolean branchOnly, long generation) {
        minimapCaveSourceWindow.resetIfChanged(dimension, view, projectionTopY,
                minPageX, maxPageX, minPageZ, maxPageZ, focusPageX, focusPageZ,
                branchOnly, generation);
        long[] plan = CaveLoadHierarchy.buildVisiblePagePlan(
                minPageX, maxPageX, minPageZ, maxPageZ,
                focusPageX, focusPageZ, false);
        minimapCaveSourceWindow.totalPages = plan.length;
        while (minimapCaveSourceWindow.cursor < plan.length) {
            long packed = plan[minimapCaveSourceWindow.cursor];
            int pageX = CaveLoadHierarchy.x(packed);
            int pageZ = CaveLoadHierarchy.z(packed);
            if (!fullscreenCaveSourceSatisfied(dimension, view, projectionTopY,
                    pageX, pageZ, branchOnly)) break;
            minimapCaveSourceWindow.cursor++;
        }
        if (minimapCaveSourceWindow.cursor >= plan.length) return new long[0];

        boolean pressured = MapPerformanceGovernor.getInstance().underPressure();
        int maximum = pressured ? 2 : 4;
        long[] selected = new long[Math.min(maximum,
                plan.length - minimapCaveSourceWindow.cursor)];
        int count = 0;
        for (int ordinal = minimapCaveSourceWindow.cursor;
                ordinal < plan.length && count < maximum; ordinal++) {
            long packed = plan[ordinal];
            int pageX = CaveLoadHierarchy.x(packed);
            int pageZ = CaveLoadHierarchy.z(packed);
            if (fullscreenCaveSourceSatisfied(dimension, view, projectionTopY,
                    pageX, pageZ, branchOnly)) continue;
            selected[count++] = packed;
        }
        minimapCaveSourceWindow.retired = false;
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (recorder.shouldEmitEvent("CAVE_MINIMAP_SOURCE_WINDOW", 500L)) {
            recorder.event("CAVE_MINIMAP_SOURCE_WINDOW",
                    "view=" + view + " top_y=" + projectionTopY
                            + " cursor=" + minimapCaveSourceWindow.cursor
                            + '/' + plan.length + " window=" + count
                            + " branch_only=" + branchOnly
                            + " pressured=" + pressured
                            + " policy=xaero_bounded_cave_writer"
                            + " pass=PASS157");
        }
        return count == selected.length ? selected : Arrays.copyOf(selected, count);
    }

    private synchronized long[] buildFullscreenCaveSourceWindow(
            String dimension, CaveView view, int projectionTopY,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ,
            float scale, boolean branchOnly, long generation) {
        fullscreenCaveSourceWindow.resetIfChanged(dimension, view, projectionTopY,
                minPageX, maxPageX, minPageZ, maxPageZ, branchOnly, generation);
        int width = maxPageX - minPageX + 1;
        int height = maxPageZ - minPageZ + 1;
        long totalLong = (long) width * height;
        if (width <= 0 || height <= 0 || totalLong <= 0L) return new long[0];
        int total = (int) Math.min(Integer.MAX_VALUE, totalLong);
        fullscreenCaveSourceWindow.totalPages = total;

        // Close the already-satisfied scanline prefix without creating any page
        // arrays. A warm CIMG/branch can therefore skip thousands of leaves in one
        // pulse instead of spending 80 ms pulses rediscovering them.
        while (fullscreenCaveSourceWindow.cursor < total) {
            int ordinal = fullscreenCaveSourceWindow.cursor;
            int pageX = minPageX + ordinal % width;
            int pageZ = minPageZ + ordinal / width;
            if (!fullscreenCaveSourceSatisfied(dimension, view, projectionTopY,
                    pageX, pageZ, branchOnly)) break;
            fullscreenCaveSourceWindow.cursor++;
        }
        if (fullscreenCaveSourceWindow.cursor >= total) return new long[0];

        boolean pressured = MapPerformanceGovernor.getInstance().underPressure();
        int maximum = branchOnly ? FULLSCREEN_CAVE_SOURCE_WINDOW_BRANCH
                : scale < 0.35f ? FULLSCREEN_CAVE_SOURCE_WINDOW_MID
                : FULLSCREEN_CAVE_SOURCE_WINDOW_EXACT;
        if (pressured) maximum = Math.max(4, maximum / 2);
        long[] selected = new long[Math.min(maximum,
                total - fullscreenCaveSourceWindow.cursor)];
        int count = 0;
        int scanLimit = Math.min(total, fullscreenCaveSourceWindow.cursor
                + Math.max(maximum * 2, maximum + 4));
        for (int ordinal = fullscreenCaveSourceWindow.cursor;
                ordinal < scanLimit && count < maximum; ordinal++) {
            int pageX = minPageX + ordinal % width;
            int pageZ = minPageZ + ordinal / width;
            if (fullscreenCaveSourceSatisfied(dimension, view, projectionTopY,
                    pageX, pageZ, branchOnly)) continue;
            selected[count++] = CaveLoadHierarchy.pack(pageX, pageZ);
        }
        if (count == 0) {
            // The short look-ahead contained only resolved pages. Move the cursor and
            // retry once recursively; each recursive step advances by at least one.
            fullscreenCaveSourceWindow.cursor = scanLimit;
            return buildFullscreenCaveSourceWindow(dimension, view, projectionTopY,
                    minPageX, maxPageX, minPageZ, maxPageZ, scale, branchOnly,
                    generation);
        }
        fullscreenCaveSourceWindow.retired = false;
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (recorder.shouldEmitEvent("CAVE_FULLSCREEN_SOURCE_WINDOW", 250L)) {
            recorder.event("CAVE_FULLSCREEN_SOURCE_WINDOW",
                    "view=" + view + " top_y=" + projectionTopY
                            + " cursor=" + fullscreenCaveSourceWindow.cursor
                            + '/' + total + " window=" + count
                            + " branch_only=" + branchOnly
                            + " pressured=" + pressured
                            + " policy=xaero_style_bounded_scanline_writer"
                            + " persistent_pulse=true"
                            + " pass=PASS160");
        }
        return count == selected.length ? selected : Arrays.copyOf(selected, count);
    }

    private boolean fullscreenCaveSourceSatisfied(String dimension, CaveView view,
            int projectionTopY, int pageX, int pageZ, boolean branchOnly) {
        CaveTileRepository repository = CaveTileRepository.getInstance();
        /* PASS163: cache-complete is not source-complete. Exact DISK pages are
         * allowed to render immediately, but an empty cache-only page cannot prove
         * absence and must not close the saved-world writer. The 00-47-29 run
         * completed 228/228 source pages while page 15,-58 was rejected as
         * CAVE_EMPTY_WITHOUT_PROOF, pinning presentation at cursor 16. */
        if (repository.hasCurrentProjectionSourcePage(
                view, projectionTopY, pageX, pageZ)) return true;
        if (repository.hasCompleteProjectionSourcePage(
                view, projectionTopY, pageX, pageZ)) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            String eventKey = "CAVE_CACHE_ONLY_SOURCE_REVERIFY_REQUIRED:"
                    + dimension + ':' + view + ':' + projectionTopY + ':'
                    + pageX + ':' + pageZ;
            if (recorder.shouldEmitEvent(eventKey, 1_000L)) {
                recorder.event("CAVE_CACHE_ONLY_SOURCE_REVERIFY_REQUIRED",
                        "page=" + pageX + ',' + pageZ
                                + " view=" + view
                                + " top_y=" + projectionTopY
                                + " action=keep_writer_open"
                                + " policy=disk_presentation_not_source_authority"
                                + " pass=PASS163");
            }
        }
        if (repository.hasFreshDisplayPageSource(view, projectionTopY,
                pageX, pageZ, DenseCaveTile.Source.WORLD_SAVE)) return true;
        if (regionImporter.isPageKnownAbsent(dimension, pageX, pageZ)) return true;
        if (branchOnly && UnifiedCaveTextureManager.getInstance()
                .hasPublishedBranchCoverage(view, projectionTopY, pageX, pageZ)) {
            return true;
        }

        /*
         * PASS162 / Xaero writer parity: this cursor owns SOURCE acquisition, not
         * presentation completion. PASS160/161 asked the repository whether an
         * exact projection had already been produced before advancing the source
         * scanline. A page whose sixteen source children were already closed could
         * therefore pin the writer forever while its CPU/GPU presentation was still
         * queued. The 00-00-45 run stopped at cursor 63 with frontier pages reporting
         * source_resolved=16 and source_inflight=0. Advance the writer as soon as the
         * native source transaction is settled; projection keeps its independent
         * retained lease and may finish later.
         */
        CaveNativeRegionImportService.PageSourceState source =
                regionImporter.pageSourceState(dimension, pageX, pageZ);
        int settledChildren = Math.min(16,
                source.resolvedChunks() + source.absentChunks());
        if (source.inFlightChunks() == 0 && settledChildren >= 16) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            String eventKey = "CAVE_FULLSCREEN_SOURCE_SETTLED_CURSOR_ADVANCE:"
                    + dimension + ':' + view + ':' + projectionTopY;
            if (recorder.shouldEmitEvent(eventKey, 500L)) {
                recorder.event("CAVE_FULLSCREEN_SOURCE_SETTLED_CURSOR_ADVANCE",
                        "page=" + pageX + ',' + pageZ
                                + " view=" + view
                                + " top_y=" + projectionTopY
                                + " resolved=" + source.resolvedChunks()
                                + " absent=" + source.absentChunks()
                                + " archived=" + source.archivedChunks()
                                + " action=advance_source_cursor_keep_presentation"
                                + " policy=source_settlement_not_projection"
                                + " pass=PASS162");
            }
            return true;
        }
        return false;
    }

    private static final class MinimapCaveSourceWindow {
        private String dimension = "";
        private CaveView view;
        private int topY = Integer.MIN_VALUE;
        private int minPageX = Integer.MIN_VALUE;
        private int maxPageX = Integer.MIN_VALUE;
        private int minPageZ = Integer.MIN_VALUE;
        private int maxPageZ = Integer.MIN_VALUE;
        private int focusPageX = Integer.MIN_VALUE;
        private int focusPageZ = Integer.MIN_VALUE;
        private boolean branchOnly;
        private long generation = Long.MIN_VALUE;
        private int cursor;
        private int totalPages;
        private boolean retired;

        private void resetIfChanged(String nextDimension, CaveView nextView,
                int nextTopY, int nextMinPageX, int nextMaxPageX,
                int nextMinPageZ, int nextMaxPageZ, int nextFocusPageX,
                int nextFocusPageZ, boolean nextBranchOnly, long nextGeneration) {
            boolean changed = !nextDimension.equals(dimension) || nextView != view
                    || nextTopY != topY || nextMinPageX != minPageX
                    || nextMaxPageX != maxPageX || nextMinPageZ != minPageZ
                    || nextMaxPageZ != maxPageZ || nextFocusPageX != focusPageX
                    || nextFocusPageZ != focusPageZ || nextBranchOnly != branchOnly
                    || nextGeneration != generation;
            if (!changed) return;
            dimension = nextDimension;
            view = nextView;
            topY = nextTopY;
            minPageX = nextMinPageX;
            maxPageX = nextMaxPageX;
            minPageZ = nextMinPageZ;
            maxPageZ = nextMaxPageZ;
            focusPageX = nextFocusPageX;
            focusPageZ = nextFocusPageZ;
            branchOnly = nextBranchOnly;
            generation = nextGeneration;
            cursor = 0;
            totalPages = 0;
            retired = false;
        }

        private boolean markRetiredOnce() {
            if (retired) return false;
            retired = true;
            return true;
        }
    }

    private static final class FullscreenCaveSourceWindow {
        private String dimension = "";
        private CaveView view;
        private int topY = Integer.MIN_VALUE;
        private int minPageX = Integer.MIN_VALUE;
        private int maxPageX = Integer.MIN_VALUE;
        private int minPageZ = Integer.MIN_VALUE;
        private int maxPageZ = Integer.MIN_VALUE;
        private boolean branchOnly;
        private long generation = Long.MIN_VALUE;
        private int cursor;
        private int totalPages;
        private boolean retired;

        private void resetIfChanged(String nextDimension, CaveView nextView,
                int nextTopY, int nextMinPageX, int nextMaxPageX,
                int nextMinPageZ, int nextMaxPageZ, boolean nextBranchOnly,
                long nextGeneration) {
            boolean changed = !nextDimension.equals(dimension) || nextView != view
                    || nextTopY != topY || nextMinPageX != minPageX
                    || nextMaxPageX != maxPageX || nextMinPageZ != minPageZ
                    || nextMaxPageZ != maxPageZ || nextBranchOnly != branchOnly
                    || nextGeneration != generation;
            if (!changed) return;
            dimension = nextDimension;
            view = nextView;
            topY = nextTopY;
            minPageX = nextMinPageX;
            maxPageX = nextMaxPageX;
            minPageZ = nextMinPageZ;
            maxPageZ = nextMaxPageZ;
            branchOnly = nextBranchOnly;
            generation = nextGeneration;
            cursor = 0;
            totalPages = 0;
            retired = false;
        }

        private boolean markRetiredOnce() {
            if (retired) return false;
            retired = true;
            return true;
        }
    }

    private synchronized boolean ensureFullscreenCaveSnapshot(
            ServerLevel level, String dimension, long generation) {
        boolean sameSnapshot = dimension.equals(fullscreenCaveFlushDimension)
                && generation == fullscreenCaveFlushGeneration;
        if (sameSnapshot && fullscreenCaveFlush != null) {
            if (!fullscreenCaveFlush.isDone()) return false;
            try {
                return Boolean.TRUE.equals(fullscreenCaveFlush.getNow(Boolean.FALSE));
            } catch (RuntimeException ignored) {
                fullscreenCaveFlush = null;
            }
        }

        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        recorder.event("CAVE_FULLSCREEN_WORLD_SAVE_FLUSH_STARTED",
                "dimension=" + dimension
                        + " policy=xaero_snapshot_barrier_direct_server_submit pass=PASS152");
        long started = System.nanoTime();
        /*
         * PASS149: this barrier is a prerequisite for every fullscreen Cave source
         * read, so it must never compete with those reads for MapWorkScheduler IO
         * admission. PASS148 emitted 47 STARTED records but only 5 DONE records:
         * tryIoFuture() was denied repeatedly and the entire Cave pipeline remained
         * behind a barrier that had not even started. Xaero submits the save to the
         * integrated server directly. Do the same asynchronously and keep one shared
         * future for the whole fullscreen session.
         */
        CompletableFuture<Boolean> submitted = level.getServer()
                .submit(() -> level.getChunkSource().save(false))
                .handle((ignored, failure) -> {
                    boolean success = failure == null
                            && CaveTileRepository.getInstance().generation()
                                    == generation;
                    recorder.event("CAVE_FULLSCREEN_WORLD_SAVE_FLUSH_DONE",
                            "dimension=" + dimension
                                    + " elapsed_ms="
                                    + ((System.nanoTime() - started) / 1_000_000L)
                                    + " success=" + success
                                    + " policy=xaero_snapshot_barrier_direct_server_submit pass=PASS152");
                    return success;
                });
        fullscreenCaveFlush = submitted;
        fullscreenCaveFlushDimension = dimension;
        fullscreenCaveFlushGeneration = generation;
        return false;
    }

    public synchronized void suspendLane(MapRequestLane lane) {
        if (lane == null) return;
        regionImporter.suspendSurfaceLane(lane);
        regionImporter.suspendCaveLane(lane);
        if (lane == MapRequestLane.FULLSCREEN) {
            fullscreenCaveFlush = null;
            fullscreenCaveFlushDimension = "";
            fullscreenCaveFlushGeneration = Long.MIN_VALUE;
        }
    }

    public void maintain() {
        sourceCache.maintain();
        regionImporter.maintain();
        CaveRegionProjectionService.getInstance().maintain();
        surface.drainReadyApplications();
    }

    private static ServerLevel resolveViewedServerLevel(Minecraft minecraft) {
        if (minecraft == null || minecraft.getSingleplayerServer() == null) return null;
        String viewed = MapManager.getInstance().getCurrentDimensionResourceId();
        ResourceLocation location = ResourceLocation.tryParse(viewed);
        if (location == null) return null;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, location);
        return minecraft.getSingleplayerServer().getLevel(key);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
