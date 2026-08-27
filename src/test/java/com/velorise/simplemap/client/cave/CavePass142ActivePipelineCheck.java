package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-free integration guard for PASS142 active Cave source routing. */
public final class CavePass142ActivePipelineCheck {
    private CavePass142ActivePipelineCheck() { }

    public static void main(String[] args) throws Exception {
        String caveRoot = "src/main/java/com/velorise/simplemap/client/cave/";

        String pipeline = read(caveRoot + "WorldSaveProjectionPipeline.java");
        require(pipeline.contains("regionImporter.requestViewport("),
                "default world-save Cave pipeline no longer uses native importer");

        String importer = read(caveRoot + "CaveNativeRegionImportService.java");
        require(importer.contains("source.projectCaveImmediate(")
                        && importer.contains("boolean buildArchiveNow = caveNeeded")
                        && importer.contains("presentationTarget == null"),
                "foreground exact projection still waits for durable vertical archive");
        /*
         * PASS143 supersedes PASS142's progressive 1/16 -> 4/16 publication
         * policy with a coherent 4x4 transaction. This historical guard should
         * keep checking the active exact fast path, not pin the superseded
         * publication cadence.
         */
        require(importer.contains("publishReadyPagesLocked(RegionImport region)")
                        && importer.contains("foregroundProjectionMask"),
                "active native importer no longer owns foreground publication");
        require(importer.contains("foregroundPresentationComplete()")
                        && importer.contains("visibleSurfacePresentationComplete()"),
                "source lane demotion is not bound to actual visible presentation readiness");

        String projection = read(caveRoot + "CaveRegionProjectionService.java");
        require(projection.contains("coherentChunkRows(resolved.knownRows())")
                        && !projection.contains(
                                "if (!repository.hasCompleteProjectionSourcePage("),
                "projection service cannot build whole-child partial exact products");

        String repository = read(caveRoot + "CaveTileRepository.java");
        require(repository.contains("int projectionChildReadyMask(")
                        && repository.contains("if (dense == null) continue;"),
                "exact WORLD_SAVE/DISK children are not counted as ready");

        System.out.println("CAVE_PASS142_ACTIVE_PIPELINE_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
