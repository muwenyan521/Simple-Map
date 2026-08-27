package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-free guard for PASS147 Xaero-style cache-first page consumption. */
public final class CavePass147CacheFirstBatchCheck {
    private CavePass147CacheFirstBatchCheck() { }

    public static void main(String[] args) throws Exception {
        String importer = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/"
                        + "CaveNativeRegionImportService.java"));
        String textures = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/"
                        + "UnifiedCaveTextureManager.java"));
        String repository = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/"
                        + "CaveTileRepository.java"));
        String persistence = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/persistence/v2/"
                        + "MapPersistenceV2Service.java"));
        String viewport = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/"
                        + "MapViewportCoordinator.java"));
        String archive = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/archive/"
                        + "CaveArchiveV2Service.java"));

        require(importer.contains("CompletableFuture.allOf(futures)")
                        && importer.contains("CAVE_PAGE_SOURCE_BATCH_CONSUMED")
                        && importer.contains("policy=xaero_one_consumer"),
                "coherent 4x4 source futures are still consumed by per-chunk callbacks");
        require(importer.contains("xaero_4x4_atomic_one_consumer"),
                "foreground source telemetry no longer identifies the page consumer invariant");
        require(textures.contains("current == 0L || cached == current")
                        && textures.contains("CAVE_REGION_IMAGE_OPTIMISTIC_REPLAY")
                        && textures.contains("render_cache_then_verify")
                        && textures.contains("UNVERIFIED_REGION_IMAGE_SOURCE_REVISION")
                        && textures.contains("wasRegionImageFallback")
                        && textures.contains("falsely source-satisfied page"),
                "persisted CIMG fallback can still become source-authoritative from partial work");
        require(textures.contains("policy=active_plus_previous_cache_backed")
                        && textures.contains("retiredInfos")
                        && textures.contains("previousNormalizedLayerY"),
                "Layered hot working set still retains every visited Top-Y band");
        require(repository.contains("pendingArchiveRegionLoads")
                        && repository.contains("CAVE_ARCHIVE_REGION_REHYDRATE_REQUEST")
                        && repository.contains("one_smr2_read_one_atomic_archive_publish")
                        && persistence.contains("loadCaveArchiveRegionSnapshot")
                        && archive.contains("ingestBatch")
                        && archive.contains("persisted native-region snapshot"),
                "SMR2 archive refill is not one atomic native-region transaction");
        require(viewport.contains("caveVisibleNow")
                        && viewport.contains("scanVisibleCaveArea")
                        && viewport.contains("else if (profile.allowVisibleScan())"),
                "Surface capture and Cave saved-source acquisition are no longer separated");

        System.out.println("CAVE_PASS147_CACHE_FIRST_BATCH_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
