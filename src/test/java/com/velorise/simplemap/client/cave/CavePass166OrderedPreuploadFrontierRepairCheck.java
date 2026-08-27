package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Cumulative PASS166 guard; PASS170 replaces GPU preupload with CPU staging. */
public final class CavePass166OrderedPreuploadFrontierRepairCheck {
    private CavePass166OrderedPreuploadFrontierRepairCheck() { }

    public static void main(String[] args) throws Exception {
        String manager = read("src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java");
        String projection = read("src/main/java/com/velorise/simplemap/client/cave/CaveRegionProjectionService.java");
        String presence = read("src/main/java/com/velorise/simplemap/client/cave/AnvilPagePresenceIndex.java");
        String importer = read("src/main/java/com/velorise/simplemap/client/cave/CaveNativeRegionImportService.java");
        String recorder = read("src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java");

        require(manager.contains("bufferRetainedFullscreenFrontier")
                        && manager.contains("repair_never_skip_visible_page"),
                "fullscreen frontier can still skip visible holes");
        require(!manager.contains("CAVE_FULLSCREEN_FRONTIER_WATCHDOG_DEFERRED")
                        && !manager.contains("action=advance_one_keep_repair_owner"),
                "PASS165 timer-defer can still manufacture permanent visible holes");
        require(manager.contains("CAVE_FULLSCREEN_FRONTIER_REPAIR_REARMED")
                        && manager.contains("projectionService.request(level")
                        && projection.contains("CAVE_REGION_OWNED_STALE_PAGE_REARMED"),
                "blocked exact frontier is not explicitly re-armed/re-offered");
        if (recorder.contains("PASS170_xaero_ordered_presentation_writer")) {
            require(manager.contains("CAVE_FULLSCREEN_PAGE_CPU_STAGED")
                            && manager.contains("single_gpu_writer"),
                    "PASS170 must retain future work on CPU instead of completion-order GPU publication");
        } else {
            require(manager.contains("futureExactPreupload"),
                    "PASS166 preupload path unexpectedly missing");
        }
        require(presence.contains("REFRESH_INTERVAL_MS = 5_000L")
                        && presence.contains("boolean bitsChanged")
                        && presence.contains("current.generation()")
                        && importer.contains("MapRequestLane.FULLSCREEN ? 5_000L"),
                "Anvil presence timestamp churn is still waking the control plane every second");
        require(recorder.contains("PASS166_ordered_preupload_frontier_repair")
                        && recorder.contains("PASS165_self_progressing_projection_frontier_watchdog"),
                "PASS166 provenance/predecessor marker missing");

        System.out.println("CAVE_PASS166_ORDERED_PREUPLOAD_FRONTIER_REPAIR_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
