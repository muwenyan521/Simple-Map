package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level guard for bounded deterministic planning with page-local publication. */
public final class CaveContiguousPublicationCheck {
    private CaveContiguousPublicationCheck() { }

    public static void main(String[] args) throws Exception {
        String hierarchy = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveLoadHierarchy.java"));
        String manager = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java"));
        String reader = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveWorldSaveReader.java"));
        String repository = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveTileRepository.java"));
        String renderer = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapRenderer.java"));
        String regionProjection = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveRegionProjectionService.java"));

        require(hierarchy.contains("buildViewportScanlinePlan")
                        && hierarchy.contains("scanlineOrdinal")
                        && hierarchy.contains("PAGES_PER_REGION"),
                "fullscreen source order is not deterministic top-left scanline");
        require(!manager.contains("FULLSCREEN_WAVEFRONT_GRACE_MS")
                        && manager.contains("PASS110 / Xaero local publication")
                        && manager.contains("bounded fixed-region build-ahead window")
                        && manager.contains("planner.pageCursor++")
                        && manager.contains("regionExactBacklog.put(key, imported)")
                        && manager.contains("isCompletionPublicationEligible")
                        && manager.contains("same page-local atomic rule as Xaero"),
                "fullscreen planning is not bounded/deterministic and page-local");
        require(!manager.contains("WAIT_FOR_FRONTIER")
                        && !manager.contains("advanceFullscreenPublicationFrontierLocked"),
                "obsolete viewport-wide hard frontier returned");
        require(regionProjection.contains(
                                "PASS110 / Xaero page-local commit")
                        && regionProjection.contains(
                                "if ((request.completedMask & bit) == 0L) continue;")
                        && regionProjection.contains("if (page == null) continue;"),
                "native region release is no longer page-local and bounded");
        require(manager.contains("lane == MapRequestLane.FULLSCREEN")
                        && manager.contains("? 0 : (int) Math.min(420_000L"),
                "fullscreen age promotion can still scramble wavefront priority");
        require(reader.contains("buildSourcePlan")
                        && reader.contains("buildVisiblePagePlan")
                        && reader.contains("requiredForegroundDecodes")
                        && reader.contains("reserveForegroundDecodes")
                        && reader.contains("requestReservedLease"),
                "Anvil page source is not atomically reserved and scanline ordered");
        require(repository.contains("CaveProjectionServiceV2.getInstance()")
                        && repository.contains("archiveV2Tiles"),
                "new Layered Top-Y still waits for another dense Anvil projection");
        require(!manager.contains("info == null && sourceRevision == 0L"),
                "unknown pages are still treated as confirmed empty");
        require(renderer.contains("branchOnly")
                        && renderer.contains("fallbackPlan"),
                "unresolved exact pages no longer retain branch/root visual fallback");
        System.out.println("CAVE_WAVEFRONT_PUBLICATION_PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
