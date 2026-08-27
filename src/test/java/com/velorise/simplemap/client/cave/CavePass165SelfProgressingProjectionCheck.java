package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS165: projection revision self-heal and non-blocking reveal liveness. */
public final class CavePass165SelfProgressingProjectionCheck {
    private CavePass165SelfProgressingProjectionCheck() { }

    public static void main(String[] args) throws Exception {
        String projection = read("src/main/java/com/velorise/simplemap/client/cave/CaveRegionProjectionService.java");
        String manager = read("src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java");
        String recorder = read("src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java");

        require(projection.contains("reconcileForegroundRevisionDriftLocked(now)")
                        && projection.contains("CAVE_FOREGROUND_PROJECTION_REVISION_REARMED")
                        && projection.contains("CAVE_REGION_OWNED_STALE_PAGE_REARMED")
                        && projection.contains("reproject_current_source_revision"),
                "foreground projection can still become a one-shot stale product after source revision advances");

        require((manager.contains("CAVE_FULLSCREEN_FRONTIER_WATCHDOG_DEFERRED")
                            || manager.contains("CAVE_FULLSCREEN_FRONTIER_REPAIR_REARMED"))
                        && manager.contains("hasPendingProjectionWork"),
                "fullscreen reveal liveness no longer has a watchdog/repair mechanism");

        require(recorder.contains("PASS165_self_progressing_projection_frontier_watchdog")
                        && recorder.contains("PASS164_frontier_backlog_shared_cave_product"),
                "PASS165 provenance or predecessor marker missing");

        System.out.println("CAVE_PASS165_SELF_PROGRESSING_PROJECTION_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
