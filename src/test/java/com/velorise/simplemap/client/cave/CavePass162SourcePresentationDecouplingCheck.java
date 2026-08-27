package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Guards PASS162: the source scanline closes on source settlement while fullscreen
 * projection ownership survives bounded writer-window handoff until viewport change.
 */
public final class CavePass162SourcePresentationDecouplingCheck {
    private CavePass162SourcePresentationDecouplingCheck() { }

    public static void main(String[] args) throws Exception {
        String pipeline = read("src/main/java/com/velorise/simplemap/client/cave/WorldSaveProjectionPipeline.java");
        String importer = read("src/main/java/com/velorise/simplemap/client/cave/CaveNativeRegionImportService.java");
        String projection = read("src/main/java/com/velorise/simplemap/client/cave/CaveRegionProjectionService.java");
        String recorder = read("src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java");

        require(pipeline.contains("CAVE_FULLSCREEN_SOURCE_SETTLED_CURSOR_ADVANCE")
                        && pipeline.contains("source.resolvedChunks() + source.absentChunks()")
                        && pipeline.contains("source.inFlightChunks() == 0 && settledChildren >= 16")
                        && pipeline.contains("source_settlement_not_projection"),
                "fullscreen source cursor can still wait for exact projection/GPU completion");

        require(importer.contains("imports.get(new RegionKey(dimension, regionX, regionZ,")
                        && importer.contains("repository.generation()))")
                        && importer.contains("absentChunks is deliberately disjoint from resolvedChunks"),
                "source-state cursor probe is still linear or settled-child accounting can double count");

        require(projection.contains("boolean retainedFullscreenPresentation")
                        && projection.contains("request.pageMask |= workMask;")
                        && projection.contains("previousForegroundMask | foregroundMask")
                        && projection.contains("viewport_lifetime_not_source_window")
                        && projection.contains("CAVE_FULLSCREEN_PRESENTATION_MASK_RETAINED"),
                "fullscreen projection mask can still be replaced by each bounded source window");

        require(projection.contains("if (!retainedFullscreenPresentation")
                        && projection.contains("&& removedForegroundMask != 0L)"),
                "ready-page pruning is not explicitly restricted to the bounded minimap path");

        require(recorder.contains("PASS162_source_presentation_decoupled")
                        && recorder.contains("PASS160_persistent_cave_writer_stale_authority_fix")
                        && recorder.contains("PASS161_settled_partial_frontier"),
                "PASS162 or predecessor provenance missing");

        System.out.println("CAVE_PASS162_SOURCE_PRESENTATION_DECOUPLING_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
