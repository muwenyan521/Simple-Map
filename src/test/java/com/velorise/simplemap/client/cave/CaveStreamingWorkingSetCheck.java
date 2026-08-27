package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Static regression guard for the runtime-validated cave streaming working set. */
public final class CaveStreamingWorkingSetCheck {
    private CaveStreamingWorkingSetCheck() { }

    public static void main(String[] args) throws Exception {
        String transition = read("CaveModeTransitionPolicy.java");
        String scheduler = read("CaveDisplayScheduler.java");
        String pipeline = read("CavePipeline.java");
        String manager = read("UnifiedCaveTextureManager.java");
        String worldReader = read("CaveWorldSaveReader.java");
        String sourceCache = read("DecodedWorldRegionCache.java");
        String anvilIndex = read("AnvilPagePresenceIndex.java");
        String repository = read("CaveTileRepository.java");
        String displayStore = read("CaveDisplayRegionStore.java");
        String loadPlanner = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapViewLoadPlanner.java"));
        String mode = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/CaveMode.java"));
        String hierarchy = read("CaveLoadHierarchy.java");
        String regionProjection = read("CaveRegionProjectionService.java");

        require(transition.contains("exactAdmissionBudget(int normal)")
                        && transition.contains("sourceAdmissionBudget(int normal)")
                        && transition.contains("loadedFrontierBudget(int normal)")
                        && transition.contains("viewportChunkBudget(int normal)")
                        && transition.contains("liveRadius(int normal)")
                        && transition.contains("foregroundBudget(long normal)")
                        && transition.contains("return Math.max(0, normal)")
                        && transition.contains("return Math.max(0L, normal)"),
                "mode switch again throttles the visible viewport instead of the deadline");
        require(manager.contains("preferredView")
                        && manager.contains("onModeChanged(CaveView nextView)")
                        && manager.contains("request.clearLane(MapRequestLane.MINIMAP)")
                        && manager.contains("detachPendingLocked(info, true)"),
                "active-mode atlas working-set handoff was removed");
        require(mode.contains("UnifiedCaveTextureManager.getInstance()")
                        && mode.contains("onModeChanged(nextView)"),
                "cave mode no longer transfers GPU ownership immediately");
        require(manager.contains("visibleTileMask")
                        && manager.contains("CAVE_FULL_TILE_SWAP")
                        && manager.contains("hasCoherentVisiblePageLocked")
                        && manager.contains("commitStagedTiles"),
                "FULL projection lost coherent 64x64 fullscreen publication");
        require(manager.contains("isCompletionPublicationEligible")
                        && manager.contains("FULLSCREEN_PUBLICATION_BURST = 32")
                        && manager.contains("FULLSCREEN_BUILD_AHEAD_PAGES = 640")
                        && !manager.contains("FULLSCREEN_WAVEFRONT_GRACE_MS")
                        && manager.contains("publicationOrdinal")
                        && manager.contains("CAVE_PUBLICATION_WAVEFRONT_ADVANCE")
                        && manager.contains("largestConnectedMask")
                        && !manager.contains("WAIT_FOR_FRONTIER"),
                "fullscreen publication no longer uses bounded nearest-first refinement");
        require(worldReader.contains("FULLSCREEN_IN_FLIGHT_PAGES = 8")
                        && worldReader.contains("GAMEPLAY_IN_FLIGHT_PAGES = 4")
                        && worldReader.contains("CAVE_SOURCE_WINDOW_CHUNKS = 6")
                        && worldReader.contains("CAVE_SOURCE_WINDOW_COUNT")
                        && worldReader.contains("pendingPageAssemblies")
                        && worldReader.contains("resolved=16 halo_resolved=")
                        && worldReader.contains("atomic=true changed=")
                        && worldReader.contains("publishCompletedAssembly")
                        && !worldReader.contains("Commit every resolved source leaf")
                        && loadPlanner.contains("MINIMAP_MAX_RADIUS_PAGES = 10")
                        && loadPlanner.contains("minimapWorkingRadiusPages"),
                "atomic cave source assembly or loaded minimap working set regressed");
        require(!worldReader.contains("anvilPresence.snapshot(serverLevel)")
                        && anvilIndex.contains("MAX_CACHE = 256")
                        && anvilIndex.contains("getCached(ServerLevel level")
                        && anvilIndex.contains("requestAsync(ServerLevel level")
                        && anvilIndex.contains("MapWorkScheduler.tryIoFuture")
                        && anvilIndex.contains("HEADER_BYTES = 4_096")
                        && anvilIndex.contains("header.getInt() == 0")
                        && sourceCache.contains("PageReservation")
                        && sourceCache.contains("reservedForegroundDecodes")
                        && sourceCache.contains("? 64 : Math.min(maximumInFlight, 4)"),
                "cold cave build again probes empty pages or stalls below one atomic page");
        require(scheduler.contains("CaveLoadHierarchy.buildOrdinalIndex")
                        && scheduler.contains("pageOrdinals.getOrDefault")
                        && scheduler.contains("localDistancePenalty")
                        && scheduler.contains("buildVisiblePagePlan")
                        && hierarchy.contains("buildRegionPlanFromPagePlan")
                        && worldReader.contains("buildVisiblePagePlan")
                        && worldReader.contains("order=viewport_scanline_sweep_top_left"),
                "source import no longer follows deterministic scanline pages with deduplicated region IO");
        require(scheduler.contains("COLUMN_BURST = 64")
                        && scheduler.contains("LOADED_CHUNKS_ADMITTED_PER_PULSE = 48")
                        && scheduler.contains("chunkBudget =")
                        && scheduler.contains("? 48 : 128"),
                "coherent foreground cave throughput regressed");
        require(displayStore.contains("readMany(File directory")
                        && displayStore.contains(
                                "pointers.sort(Comparator.comparingLong(RecordPointer::offset))")
                        && repository.contains("requestDisplayBatchLoadLocked")
                        && repository.contains("CVD must load only the exact 6x6 tile window")
                        && !repository.contains("boolean expandRegions")
                        && repository.contains("changedPages.values()"),
                "presentation-ready cave cache lost page-batched IO or reintroduced region thrash");
        require(worldReader.contains("hasPendingDisplayPageLoad")
                        && worldReader.contains("requestDisplayPageLoad(view, layerY")
                        && worldReader.contains("CaveLoadHierarchy.buildOrdinalIndex")
                        && worldReader.contains("CaveRegionProjectionService.getInstance().request")
                        && regionProjection.contains("CavePageStyler.style")
                        && regionProjection.contains("regionImageCache.save(snapshot)")
                        && manager.contains("CaveLoadHierarchy.buildOrdinalIndex")
                        && manager.contains("drainRegionProjectedPages"),
                "cache-first replay or region-transaction projection authority was removed");
        require(pipeline.contains("getEffectiveRenderDistance() + 2")
                        && pipeline.contains("int chunkRadius = liveRadius")
                        && pipeline.contains("minChunkX = playerChunkX - liveRadius")
                        && !pipeline.contains(
                                "hotRadius = CaveModeTransitionPolicy.liveRadius(3)"),
                "minimap discovery no longer owns the complete loaded render-distance set");
        System.out.println("CAVE_STREAMING_WORKING_SET_PASS");
    }

    private static String read(String file) throws Exception {
        return Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave", file));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
