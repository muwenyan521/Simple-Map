package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dependency-free historical guard for PASS141/PASS142 world-save Cave routing.
 *
 * <p>PASS141 introduced the immediate exact projection primitive in the legacy
 * reader, but the default reader delegates to WorldSaveProjectionPipeline. PASS142
 * corrects that integration error by requiring the active native importer to own
 * the same fast path instead of enforcing the obsolete complete-page gate.</p>
 */
public final class CavePass141XaeroPageAtomicCheck {
    private CavePass141XaeroPageAtomicCheck() { }

    public static void main(String[] args) throws Exception {
        String caveRoot = "src/main/java/com/velorise/simplemap/client/cave/";

        String reader = read(caveRoot + "CaveWorldSaveReader.java");
        require(reader.contains("simplemap.useLegacyWorldSavePipelines")
                        && reader.contains("WorldSaveProjectionPipeline.getInstance()"),
                "default Cave reader no longer exposes the active projection pipeline");

        String decoded = read(caveRoot + "DecodedWorldChunkSource.java");
        require(decoded.contains("projectCaveImmediate(CaveDisplayProjector projector")
                        && decoded.contains("projector.project(this, effectiveView, layerY"),
                "decoded source has no direct exact Cave projection primitive");

        String importer = read(caveRoot + "CaveNativeRegionImportService.java");
        require(importer.contains("source.projectCaveImmediate(")
                        && importer.contains("cavePresentationPending")
                        && importer.contains("foregroundPresentationTarget(index)"),
                "active native importer still does not own visible exact Cave latency");

        String projection = read(caveRoot + "CaveRegionProjectionService.java");
        require(!projection.contains("if (!repository.hasCompleteProjectionSourcePage("),
                "active exact projection still blocks every partial child on 16/16 source");

        String repository = read(caveRoot + "CaveTileRepository.java");
        int readyMaskStart = repository.indexOf(
                "int projectionChildReadyMask(");
        int readyMaskEnd = repository.indexOf(
                "hasPendingDisplayPageLoad(", readyMaskStart);
        String readyMaskMethod = repository.substring(
                readyMaskStart, readyMaskEnd);
        require(!readyMaskMethod.contains(
                        "dense.source() != DenseCaveTile.Source.LIVE"),
                "WORLD_SAVE exact Dense children still cannot satisfy presentation readiness");

        System.out.println("CAVE_PASS141_XAERO_PAGE_ATOMIC_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
