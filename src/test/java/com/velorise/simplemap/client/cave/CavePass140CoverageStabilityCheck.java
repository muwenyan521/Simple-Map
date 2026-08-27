package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-free static guard for PASS140 coverage/stability fixes. */
public final class CavePass140CoverageStabilityCheck {
    private CavePass140CoverageStabilityCheck() { }

    public static void main(String[] args) throws Exception {
        String caveRoot = "src/main/java/com/velorise/simplemap/client/cave/";
        String gpuRoot = "src/main/java/com/velorise/simplemap/client/gpu/";

        String importer = read(caveRoot + "CaveNativeRegionImportService.java");
        require(importer.contains("NORMAL_ACTIVE_SOURCES = 28")
                        && importer.contains("PRESSURE_ACTIVE_SOURCES = 16")
                        && (importer.contains("REGION_SOURCE_FAIRNESS_MS = 250L")
                                || importer.contains("REGION_SOURCE_FAIRNESS_MS = 2_000L")),
                "visible native-region source window is still over-throttled");
        require(importer.contains("region.hasIncompleteVisibleCavePage()")
                        && importer.contains("region.hasFocusVisibleDemand() ? 2 : 1")
                        && importer.contains("lastSourceAdmissionMs")
                        && importer.contains("sourceServiceAgeMs(now)"),
                "cross-region/page-closure fairness scheduling is missing");
        require(importer.contains("live_pending=")
                        && importer.contains("retry_waiting=")
                        && importer.contains("presence_unknown="),
                "visible source wait diagnostics do not expose the real wait stage");

        String cache = read(caveRoot + "DecodedWorldRegionCache.java");
        require(cache.contains("SOURCE_CPU_QUEUE_SOFT_LIMIT = 24")
                        && cache.contains("SOURCE_CPU_QUEUE_PRESSURE_LIMIT = 12"),
                "retained source queue is still too narrow");
        require(!cache.contains("Math.min(maximumInFlight, 8)"),
                "MINIMAP async Anvil IO is still collapsed to eight reads");

        String executor = read(caveRoot + "PriorityDecodeExecutor.java");
        require(executor.contains("return pendingCount.get();")
                        && !executor.contains("pendingCount.get() + MapWorkScheduler.cpuQueuedCount()"),
                "source dependency admission is still blocked by unrelated global CPU work");

        String uploader = read(gpuRoot + "CaveAtlasPboUploader.java");
        require(uploader.contains("PBO_MIN_UPLOAD_BYTES = 64 * 1024")
                        && uploader.contains("byteCount >= PBO_MIN_UPLOAD_BYTES"),
                "tiny exact Cave uploads can still enter the PBO path");

        String atlas = read(caveRoot + "CaveTextureAtlas.java");
        require(atlas.contains("boolean touchesEdge")
                        && atlas.contains("pitch, pitch, guttered"),
                "edge dirty updates still fan out into multiple GL subuploads");

        String texture = read(caveRoot + "UnifiedCaveTextureManager.java");
        require(texture.contains("One bounding dirty rectangle per LOD")
                        && !texture.contains("private static final int MAX_RECTS = 8"),
                "exact Cave dirty updates still fragment into many GL calls");

        System.out.println("CAVE_PASS140_COVERAGE_STABILITY_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
