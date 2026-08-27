package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS157 against restoring full-view leaf work at far Cave zoom. */
public final class CavePass157BoundedLodSourceWindowCheck {
    private CavePass157BoundedLodSourceWindowCheck() { }

    public static void main(String[] args) throws Exception {
        String pipeline = read("src/main/java/com/velorise/simplemap/client/cave/WorldSaveProjectionPipeline.java");
        String manager = read("src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java");
        String policy = read("src/main/java/com/velorise/simplemap/client/cave/CaveScreenSpacePolicy.java");
        String importer = read("src/main/java/com/velorise/simplemap/client/cave/CaveNativeRegionImportService.java");
        String renderer = read("src/main/java/com/velorise/simplemap/client/MapRenderer.java");
        String recorder = read("src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java");

        require(pipeline.contains("FULLSCREEN_CAVE_SOURCE_WINDOW_EXACT = 32")
                        && pipeline.contains("FULLSCREEN_CAVE_SOURCE_WINDOW_MID = 16")
                        && pipeline.contains("FULLSCREEN_CAVE_SOURCE_WINDOW_BRANCH = 8")
                        && pipeline.contains("xaero_style_bounded_scanline_writer")
                        && pipeline.contains("hasPublishedBranchCoverage")
                        && pipeline.contains("CAVE_MINIMAP_SOURCE_WINDOW")
                        && pipeline.contains("int maximum = pressured ? 2 : 4"),
                "fullscreen/minimap Cave source is not a bounded persistent writer window");
        require(manager.contains("if (!branchOnlyPlan) {\n                        repository.requestDisplayPageLoad")
                        && manager.contains("hasPublishedBranchCoverage"),
                "branch-only mode can still hydrate unused exact display leaves");
        require(policy.contains("branchOnly(scale, lane)) normal = pressured ? 2 : 8"),
                "far-zoom exact/source admission is still unbounded");
        require(importer.contains("lastPresenceRefreshMs")
                        && (importer.contains("1_500L")
                                || importer.contains("MapRequestLane.FULLSCREEN ? 5_000L"))
                        && (importer.contains("2_500L")
                                || importer.contains("7_500L")),
                "Anvil presence refresh still runs at viewport pulse cadence");
        require(renderer.contains("exactPagePixels <= 8.0f) return 256")
                        && renderer.contains("exactPagePixels <= 4.5f) return 128"),
                "far-zoom hierarchy traversal budget regressed");
        require(recorder.contains("PASS157_bounded_lod_source_window"),
                "PASS157 runtime provenance is missing");

        System.out.println("CAVE_PASS157_BOUNDED_LOD_SOURCE_WINDOW_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
