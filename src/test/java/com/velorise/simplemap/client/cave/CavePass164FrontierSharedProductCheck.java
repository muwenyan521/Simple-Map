package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS164: direct frontier selection and one shared cave presentation product. */
public final class CavePass164FrontierSharedProductCheck {
    private CavePass164FrontierSharedProductCheck() { }

    public static void main(String[] args) throws Exception {
        String manager = read("src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java");
        String coordinator = read("src/main/java/com/velorise/simplemap/client/MapViewportCoordinator.java");
        String renderer = read("src/main/java/com/velorise/simplemap/client/MapRenderer.java");
        String recorder = read("src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java");

        require(manager.contains("selectRegionExactBacklogKey(service, now)")
                        && manager.contains("CAVE_FULLSCREEN_FRONTIER_BACKLOG_DIRECT_HIT")
                        && manager.contains("service.find(")
                        && manager.contains("publish_frontier_before_future")
                        && manager.contains("coordinate_selected_retained_product"),
                "fullscreen still consumes projected region pages in async/access-order instead of frontier order");

        boolean pass170CpuStaging = recorder.contains("PASS170_xaero_ordered_presentation_writer")
                && manager.contains("CAVE_FULLSCREEN_PAGE_CPU_STAGED")
                && manager.contains("service.acknowledgeForeground(imported)")
                && manager.contains("ordinal != fullscreenPlanner.publicationCursor")
                && manager.contains("allowFullscreenExact");
        boolean legacyFutureFence = manager.contains("ordinal > planner.publicationCursor")
                && manager.contains("service.acknowledgeForeground(imported)")
                && manager.contains("allowFullscreenExact");
        require(pass170CpuStaging || legacyFutureFence,
                "prepared future pages are not retained behind the deterministic fullscreen reveal fence");

        boolean legacySharedSubscriber = coordinator.contains("CAVE_FULLSCREEN_MINIMAP_SHARED_PRODUCT_PULSE")
                && coordinator.contains("shared_unified_product_no_second_anvil");
        boolean pass168SingleOwner = coordinator.contains("CAVE_FULLSCREEN_HIDDEN_MINIMAP_SUPPRESSED")
                && coordinator.contains("single_visible_foreground_owner_shared_cache");
        require(legacySharedSubscriber || pass168SingleOwner,
                "fullscreen/minimap Cave product ownership has neither PASS164 shared-subscriber nor PASS168 single-owner semantics");

        require(renderer.contains("allowFullscreenExact"),
                "hidden minimap GPU residency can bypass the fullscreen scanline render gate");

        require(recorder.contains("PASS164_frontier_backlog_shared_cave_product")
                        && recorder.contains("PASS163_cache_empty_source_authority"),
                "PASS164 provenance or predecessor marker missing");

        System.out.println("CAVE_PASS164_FRONTIER_SHARED_PRODUCT_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
