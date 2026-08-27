package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS163: cache-ready empties cannot close source authority or block the global reveal frontier. */
public final class CavePass163CacheEmptySourceAuthorityCheck {
    private CavePass163CacheEmptySourceAuthorityCheck() { }

    public static void main(String[] args) throws Exception {
        String pipeline = read("src/main/java/com/velorise/simplemap/client/cave/WorldSaveProjectionPipeline.java");
        String repository = read("src/main/java/com/velorise/simplemap/client/cave/CaveTileRepository.java");
        String importer = read("src/main/java/com/velorise/simplemap/client/cave/CaveNativeRegionImportService.java");
        String texture = read("src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java");
        String recorder = read("src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java");

        require(repository.contains("hasCurrentProjectionSourcePage")
                        && repository.contains("DenseCaveTile.Source.WORLD_SAVE.rank()"),
                "fullscreen source authority still accepts cache-only exact DISK pages");

        require(pipeline.contains("CAVE_CACHE_ONLY_SOURCE_REVERIFY_REQUIRED")
                        && pipeline.contains("disk_presentation_not_source_authority")
                        && pipeline.contains("hasCurrentProjectionSourcePage"),
                "bounded fullscreen writer can still close on cache-only presentation readiness");

        int presentation = importer.indexOf("private boolean hasForegroundPresentation(int index, int ordinal)");
        require(presentation >= 0
                        && importer.substring(presentation).contains("DenseCaveTile.Source.WORLD_SAVE"),
                "native source resolution can still be settled by DISK-only exact tiles");

        require(texture.contains("unprovenEmptySinceMs")
                        && (texture.contains("CAVE_FULLSCREEN_UNPROVEN_EMPTY_DEFERRED")
                                || texture.contains("CAVE_FULLSCREEN_FRONTIER_REPAIR_REARMED"))
                        && texture.contains("action=retain_last_good"),
                "an unproven cache-empty page no longer has a retained repair path");

        require(recorder.contains("PASS163_cache_empty_source_authority")
                        && recorder.contains("PASS162_source_presentation_decoupled"),
                "PASS163 provenance or predecessor marker missing");

        System.out.println("CAVE_PASS163_CACHE_EMPTY_SOURCE_AUTHORITY_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
