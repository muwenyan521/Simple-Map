package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS150 central-only Cave source dependencies and runtime provenance. */
public final class CavePass150CentralSourceFootprintCheck {
    private CavePass150CentralSourceFootprintCheck() { }

    public static void main(String[] args) throws Exception {
        String importer = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/"
                        + "CaveNativeRegionImportService.java"));
        String recorder = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java"));
        String worldSave = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/"
                        + "WorldSaveProjectionPipeline.java"));

        require(importer.contains(
                        "addCentralRequiredSources(caveVisiblePageMask, caveRequiredSources)")
                        && importer.contains("visible_cave_central_4x4_only")
                        && importer.contains("Border data remains a cache hit optimization"),
                "Cave source dependency graph still structurally requires the 6x6 styling halo");
        require(recorder.contains("BUILD_PROVENANCE")
                        && recorder.contains("build=\" + BUILD_PROVENANCE"),
                "runtime logs do not identify the actual pass under test");
        require(worldSave.contains(
                        "xaero_snapshot_barrier_direct_server_submit pass=PASS152"),
                "snapshot telemetry cannot distinguish PASS150 from a stale binary");

        System.out.println("CAVE_PASS150_CENTRAL_SOURCE_FOOTPRINT_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
