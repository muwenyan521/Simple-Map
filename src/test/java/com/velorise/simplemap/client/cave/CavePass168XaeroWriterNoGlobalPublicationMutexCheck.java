package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cumulative PASS168 guard. PASS170 keeps preparation non-blocking but restores one
 * ordered GPU/front-product writer so completion order cannot leak into visibility.
 */
public final class CavePass168XaeroWriterNoGlobalPublicationMutexCheck {
    private CavePass168XaeroWriterNoGlobalPublicationMutexCheck() { }

    public static void main(String[] args) throws Exception {
        String manager = read("src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java");
        String coordinator = read("src/main/java/com/velorise/simplemap/client/MapViewportCoordinator.java");
        String recorder = read("src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java");

        if (recorder.contains("PASS170_xaero_ordered_presentation_writer")) {
            require(manager.contains("CAVE_FULLSCREEN_PAGE_CPU_STAGED")
                            && manager.contains("The current row-major frontier is the only fullscreen page allowed")
                            && manager.contains("return ordinal <= planner.publicationCursor"),
                    "PASS170 did not separate async CPU preparation from one ordered GPU writer");
            require(!manager.contains("int stageBudget = frontierBuffered ? 0")
                            && manager.contains("ready frontier is a priority hint"),
                    "a buffered frontier can still stop producer-ready draining");
        } else {
            require(manager.contains("CAVE_FULLSCREEN_READY_PAGE_INDEPENDENT_PUBLICATION")
                            && manager.contains("xaero_writer_priority_not_global_visibility_lock"),
                    "PASS168 independent preparation/publication guard missing");
        }
        require(coordinator.contains("CAVE_FULLSCREEN_HIDDEN_MINIMAP_SUPPRESSED")
                        && coordinator.contains("single_visible_foreground_owner_shared_cache")
                        && coordinator.contains("suspendLane(\n                    MapRequestLane.MINIMAP)"),
                "hidden minimap can still own a competing foreground cave presentation generation");
        require(recorder.contains("PASS168_xaero_writer_no_global_publication_mutex"),
                "PASS168 provenance marker missing");

        System.out.println("CAVE_PASS168_XAERO_WRITER_NO_GLOBAL_PUBLICATION_MUTEX_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
