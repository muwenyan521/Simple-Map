package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS156 deterministic fullscreen commit and bounded cave gameplay work. */
public final class CavePass156OrderedFrontierBoundedMinimapCheck {
    private CavePass156OrderedFrontierBoundedMinimapCheck() { }

    public static void main(String[] args) throws Exception {
        String importer = read("src/main/java/com/velorise/simplemap/client/cave/CaveNativeRegionImportService.java");
        String manager = read("src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java");
        String projection = read("src/main/java/com/velorise/simplemap/client/cave/CaveRegionProjectionService.java");
        String policy = read("src/main/java/com/velorise/simplemap/client/cave/CaveScreenSpacePolicy.java");
        String reader = read("src/main/java/com/velorise/simplemap/client/cave/CaveWorldSaveReader.java");
        String worldPipeline = read("src/main/java/com/velorise/simplemap/client/cave/WorldSaveProjectionPipeline.java");
        String loadPlanner = read("src/main/java/com/velorise/simplemap/client/MapViewLoadPlanner.java");
        String caveTexture = read("src/main/java/com/velorise/simplemap/client/CaveTextureManager.java");
        String recorder = read("src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java");

        require(importer.contains("NORMAL_ACTIVE_SOURCES = 16")
                        && importer.contains("SOURCE_SLICE = 16")
                        && importer.contains("earliestIncompleteFullscreenScanlineKey")
                        && importer.contains("fullscreenScanlineKey")
                        && importer.contains("foregroundPresentationSatisfied")
                        && importer.contains("hasMinimapCaveDemand"),
                "source ownership is not one deterministic 4x4 page transaction at a time");

        int completeSource = importer.indexOf("private void completeSource(");
        int nextMethod = importer.indexOf("private ", completeSource + 20);
        require(completeSource >= 0 && nextMethod > completeSource
                        && !importer.substring(completeSource, nextMethod)
                                .contains("scheduleHotArchivePackaging("),
                "visible exact source still fans out into PASS155 all-height hot packaging");

        require(manager.contains("int publicationCursor")
                        && manager.contains("refreshFullscreenPublicationFrontier")
                        && manager.contains("FULLSCREEN_REVEAL_BURST = 4")
                        && manager.contains("strict_scanline_no_holes")
                        && manager.contains("strict_scanline_prefix_ready_only")
                        && manager.contains("planner.branchOnly")
                        && manager.contains("lodTree.hasPublishedCoverage"),
                "fullscreen async preparation is still leaking random completion order to the display");
        require(manager.contains("FULLSCREEN_BUILD_AHEAD_PAGES = 96")
                        && manager.contains("Math.min(FULLSCREEN_BUILD_AHEAD_PAGES, 32)"),
                "fullscreen exact CPU build-ahead is still effectively unbounded");

        require(loadPlanner.contains("CAVE_MINIMAP_MAX_RADIUS_PAGES = 2")
                        && reader.contains("CAVE_MINIMAP_MAX_RADIUS_PAGES")
                        && worldPipeline.contains("CAVE_MINIMAP_MAX_RADIUS_PAGES"),
                "cave minimap source footprint is not bounded to the Xaero-like 5x5 page working set");
        require(projection.contains("MAX_ACTIVE_MINIMAP_REGION_BUILDS = 1")
                        && projection.contains("MINIMAP_REGION_PAGE_SLICE = 4")
                        && policy.contains("lane == MapRequestLane.MINIMAP) normal = pressured ? 2 : 4")
                        && policy.contains("lane == MapRequestLane.MINIMAP) normal = pressured ? 2 : 6"),
                "gameplay cave projection/admission can still flood CPU/GC");

        require(caveTexture.contains("CAVE_MINIMAP_TARGET_SYNCHRONIZED")
                        && caveTexture.contains("shared_target_previous_visual_fallback")
                        && !caveTexture.contains("MINIMAP_LAYER_CANDIDATE_MS")
                        && !caveTexture.contains("MINIMAP_LAYER_STABLE_MS"),
                "minimap writer can still lag behind the shared fullscreen Top-Y target");
        require(recorder.contains("PASS156_ordered_frontier_bounded_minimap"),
                "PASS156 runtime provenance is missing");

        System.out.println("CAVE_PASS156_ORDERED_FRONTIER_BOUNDED_MINIMAP_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
