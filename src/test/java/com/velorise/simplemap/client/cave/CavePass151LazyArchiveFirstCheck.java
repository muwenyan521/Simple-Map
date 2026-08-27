package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS151 lazy persisted-region authority and completion-driven cave batches. */
public final class CavePass151LazyArchiveFirstCheck {
    private CavePass151LazyArchiveFirstCheck() { }

    public static void main(String[] args) throws Exception {
        String importer = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveNativeRegionImportService.java"));
        String repository = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveTileRepository.java"));
        String persistence = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/persistence/v2/MapPersistenceV2Service.java"));
        String manager = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java"));
        String recorder = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java"));

        require(importer.contains("requestPersistedArchivePageLoad")
                        && importer.contains("CAVE_FOREGROUND_CACHE_FIRST")
                        && importer.contains("CompletableFuture.allOf(futures).whenComplete(")
                        && !importer.contains("CompletableFuture.allOf(futures).whenCompleteAsync("),
                "foreground Cave still queues Anvil ahead of persisted cache or adds a second control-plane hop");
        require(repository.contains("probedArchiveV2Regions")
                        && repository.contains("CAVE_ARCHIVE_REGION_DIRECT_REHYDRATE_REQUEST")
                        && repository.contains("visible_region_file_before_anvil pass=PASS152")
                        && !repository.contains(".loadCaveArchives("),
                "repository still performs the all-world CompactCaveTile startup decode");
        require(persistence.contains("caveArchiveRegionExists")
                        && persistence.contains("loadCaveArchiveRegionSnapshot"),
                "lazy SMR2 region discovery/read path is disconnected");
        require(manager.contains("requestPersistedArchivePageLoad")
                        && manager.contains("policy=lazy_region_before_no_source"),
                "exact renderer cannot discover a cold persisted SMR2 region without the global tile index");
        require(recorder.contains("BUILD_PROVENANCE"),
                "PASS151 runtime provenance is missing");

        System.out.println("CAVE_PASS151_LAZY_ARCHIVE_FIRST_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
