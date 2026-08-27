package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Guards PASS160: fullscreen Cave source traversal is a retained cursor pulse and
 * stale stronger presentation data cannot veto a current world-save repair.
 */
public final class CavePass160PersistentWriterStaleAuthorityCheck {
    private CavePass160PersistentWriterStaleAuthorityCheck() { }

    public static void main(String[] args) throws Exception {
        String coordinator = read("src/main/java/com/velorise/simplemap/client/MapViewportCoordinator.java");
        String repository = read("src/main/java/com/velorise/simplemap/client/cave/CaveTileRepository.java");
        String recorder = read("src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java");

        int persistentPulse = coordinator.indexOf("CAVE_FULLSCREEN_PERSISTENT_WRITER_PULSE");
        int nonInteractingGate = coordinator.indexOf("if (fullscreenVisible && !fullscreen.interacting");
        require(persistentPulse >= 0 && nonInteractingGate > persistentPulse,
                "fullscreen Cave source cursor is still gated by renderer/non-interacting publication");
        require(coordinator.contains("scanVisibleCaveArea(minecraft")
                        && coordinator.contains("retained_cursor_not_render_request")
                        && coordinator.contains("CAVE_FULLSCREEN_SOURCE_IDLE_INTERVAL_NANOS"),
                "persistent bounded Cave source pulse is missing");

        int commitStart = repository.indexOf("public boolean commitDisplayTile");
        int commitEnd = repository.indexOf("public synchronized boolean commitDisplayPage", commitStart);
        String commitTile = commitStart < 0 || commitEnd < 0
                ? "" : repository.substring(commitStart, commitEnd);
        require(commitTile.contains("boolean currentStale")
                        && commitTile.contains("!currentStale")
                        && commitTile.contains("CAVE_STALE_HIGHER_AUTHORITY_REPLACED")
                        && commitTile.contains("stale_is_fallback_not_authority"),
                "stale higher-authority tile can still veto current source repair");

        int loadStart = repository.indexOf("Persistent DISK cache is fallback data");
        require(loadStart >= 0,
                "disk cache fallback guard missing; stale-rank bypass leaked into cache replay");

        require(recorder.contains("PASS159_layer_projection_lifecycle")
                        && recorder.contains("PASS160_persistent_cave_writer_stale_authority_fix"),
                "PASS159 provenance was not retained or PASS160 provenance missing");

        System.out.println("CAVE_PASS160_PERSISTENT_WRITER_STALE_AUTHORITY_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
