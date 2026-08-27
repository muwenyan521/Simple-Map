package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS85 guard for stable Full-page admission and latest-source coalescing. */
public final class CavePass85FullSourceCoalescingCheck {
    private CavePass85FullSourceCoalescingCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String importer = Files.readString(
                root.resolve("CaveNativeRegionImportService.java"));
        String projection = Files.readString(
                root.resolve("CaveRegionProjectionService.java"));
        String manager = Files.readString(
                root.resolve("UnifiedCaveTextureManager.java"));
        String style = Files.readString(root.resolve("CaveProjectionStyle.java"));

        require(importer.contains("centralReadyPageMask")
                        && importer.contains("projectionReadyMask")
                        && importer.contains("projectionReadyMask |= archiveReadyMask")
                        && importer.contains(
                                "long readyDemand = projectionReadyMask & demand.pageMask"),
                "Full pages are still admitted from an unstable or all-or-nothing source rule");
        require(projection.contains("for (int attempt = 0; attempt < 2; attempt++)")
                        && projection.contains("sourceAfterStyle != sourceBeforeStyle")
                        && projection.contains("existing.sourceRevision = sourceRevision")
                        && projection.contains("request.sourceRevision = currentSource")
                        && projection.contains("CAVE_PAGE_SOURCE_COALESCE_RETRY"),
                "region projection remains bound to an obsolete admission fingerprint");
        require(!manager.contains("for (int attempt = 0; attempt < 2; attempt++)")
                        && manager.contains("long sourceBeforeResolve = repository.getPageRevision")
                        && manager.contains("sourceBeforeResolve != sourceAfterResolve")
                        && manager.contains("sourceAfterStyle != sourceAfterResolve")
                        && manager.contains("return BuildResult.superseded")
                        && manager.contains("if (result.superseded())")
                        && manager.contains("restartSourceSettleWindow"),
                "direct exact projection no longer uses one-revision coalescing");
        require(style.contains("STYLE_SIGNATURE_VERSION = 20"),
                "PASS86 partial Full CIMG cache remains valid");
        System.out.println("CAVE_PASS85_FULL_SOURCE_COALESCING_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
