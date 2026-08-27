package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS84 guard that a Full mode switch can project from retained archive RAM. */
public final class CavePass84ArchiveFastPathCheck {
    private CavePass84ArchiveFastPathCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String importer = Files.readString(
                root.resolve("CaveNativeRegionImportService.java"));
        String repository = Files.readString(
                root.resolve("CaveTileRepository.java"));
        String style = Files.readString(root.resolve("CaveProjectionStyle.java"));

        require(importer.contains("fullArchiveReadyMask(region, demand.pageMask)")
                        && importer.contains("projectionReadyMask")
                        && importer.contains("projectionReadyMask |= archiveReadyMask")
                        && importer.contains("archive.hasFullProjectionPage"),
                "Full retained-archive mode-switch fast path is missing");
        require(repository.contains("hasProjectionAuthorityPage")
                        && repository.contains("archived.fullProjectionCoverage()")
                        && repository.contains("indexedProjectionMask"),
                "Full revision/resolution authority rejects mixed archive/absence pages");
        require(style.contains("STYLE_SIGNATURE_VERSION = 20"),
                "PASS86 incomplete Full CIMG cache is still valid");
        System.out.println("CAVE_PASS84_ARCHIVE_FAST_PATH_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
