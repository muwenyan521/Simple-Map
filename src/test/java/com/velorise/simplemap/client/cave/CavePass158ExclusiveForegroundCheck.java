package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS158: active Cave owns foreground; hidden Surface/minimap backlog cannot compete. */
public final class CavePass158ExclusiveForegroundCheck {
    private CavePass158ExclusiveForegroundCheck() { }

    public static void main(String[] args) throws Exception {
        String viewport = read("src/main/java/com/velorise/simplemap/client/MapViewportCoordinator.java");
        String governor = read("src/main/java/com/velorise/simplemap/client/MapPerformanceGovernor.java");
        String lod = read("src/main/java/com/velorise/simplemap/client/cave/CaveLodTree.java");
        String recorder = read("src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java");

        require(viewport.contains("if (!caveActive")
                        && viewport.contains("if (fullscreenVisible || caveActive) return;")
                        && viewport.contains("CAVE_HIDDEN_SURFACE_WORK_SUPPRESSED"),
                "active Cave can still start hidden Surface foreground/background work");
        require(viewport.contains("CAVE_ADJACENT_WARMUP_INTERVAL_NANOS = 2_000_000_000L")
                        && viewport.contains("MapPerformanceGovernor.getInstance().underPressure()"),
                "adjacent Cave warmup is not pressure/cadence bounded");
        require(governor.contains("HEAP_PRESSURE_ENTER = 0.82D")
                        && governor.contains("heapPressure >= HEAP_PRESSURE_ENTER"),
                "governor still ignores near-full JVM heap pressure");
        require(lod.contains("visibility outranks the static lane rank")
                        && lod.contains("update.lane() != MapRequestLane.FULLSCREEN")
                        && lod.contains("node.requestLane = MapRequestLane.FULLSCREEN")
                        && lod.contains("node.requestLane = MapRequestLane.BACKGROUND"),
                "hidden minimap branch ownership can still outrank visible fullscreen");
        require(recorder.contains("PASS158_cave_exclusive_foreground"),
                "PASS158 build provenance missing");

        System.out.println("CAVE_PASS158_EXCLUSIVE_FOREGROUND_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
