package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS81 guard for canonical dimension handoff and bounded cave demand lifetime. */
public final class CavePass81DemandLifecycleCheck {
    private CavePass81DemandLifecycleCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String reader = Files.readString(root.resolve("CaveWorldSaveReader.java"));
        String importer = Files.readString(
                root.resolve("CaveNativeRegionImportService.java"));
        String manager = Files.readString(
                root.resolve("UnifiedCaveTextureManager.java"));
        String projection = Files.readString(
                root.resolve("CaveRegionProjectionService.java"));
        String cache = Files.readString(root.resolve("CaveRegionImageCache.java"));

        require(reader.contains("String projectionDimension = MapManager.getInstance()")
                        && reader.contains(".getDimensionCacheKey()")
                        && reader.contains("projectionDimension,")
                        && reader.contains("view, layerY"),
                "native-region pages are not handed to projection in cache-key namespace");
        require(manager.contains("matchesCurrentDimension(imported.dimension())")
                        && manager.contains("lodTree.updatePage(key.dimension()"),
                "render drain does not defensively normalize raw/canonical dimension ids");

        require(importer.contains("region.retainProjection(requestedView, requestedTopY)")
                        && importer.contains("existing.retireProjectionDemands()")
                        && importer.contains("demand.pageMask = pageMask")
                        && importer.contains("requiredSourcesSettledForPass()")
                        && importer.contains("union of each visible page's 6x6 halo"),
                "native-region projection demand still accumulates across pans/modes");
        require(!importer.contains("CaveView pairedView")
                        && !importer.contains("addBackgroundDemand(")
                        && !importer.contains("demand.pageMask |= remaining")
                        && !importer.contains("order.addAll(Arrays.asList(remainder))"),
                "eager paired/whole-region projection work is still admitted");

        require(projection.contains("REGION_WRITE_DEBOUNCE_MS = 1_200L")
                        && projection.contains("MAX_PENDING_REGION_WRITES = 1")
                        && projection.contains("pageSourceStamps[ordinal] == page.sourceRevision()")
                        && projection.contains("return false;"),
                "CIMG write coalescing or idempotent page staging is missing");
        require(cache.contains("ThreadLocal<byte[]> BODY_BUFFER")
                        && cache.contains("byte[] body = BODY_BUFFER.get()"),
                "CIMG encoder still allocates a new 1 MiB byte buffer per save");

        System.out.println("CAVE_PASS81_DEMAND_LIFECYCLE_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
