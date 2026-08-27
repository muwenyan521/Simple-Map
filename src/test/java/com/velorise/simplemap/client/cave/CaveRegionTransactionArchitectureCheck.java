package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** PASS75 guard for one-source/one-projection native-region cave import. */
public final class CaveRegionTransactionArchitectureCheck {
    private CaveRegionTransactionArchitectureCheck() { }

    public static void main(String[] args) throws Exception {
        verifyRegionMajorPlan();
        verifySourceAndProjectionAuthority();
        System.out.println("CAVE_REGION_TRANSACTION_ARCHITECTURE_PASS");
    }

    private static void verifyRegionMajorPlan() {
        long[] plan = CaveLoadHierarchy.buildRegionMajorPagePlan(
                -9, 11, -9, 11, 0, 0);
        require(plan.length == 21 * 21,
                "region-major plan lost visible pages");
        Set<Long> unique = new HashSet<>();
        for (long packed : plan) unique.add(packed);
        require(unique.size() == plan.length,
                "region-major plan contains duplicate pages");
        require(CaveLoadHierarchy.x(plan[0]) == 0
                        && CaveLoadHierarchy.z(plan[0]) == 0,
                "native region transaction does not start at the focus page");
        for (int ordinal = 0; ordinal < 64; ordinal++) {
            int pageX = CaveLoadHierarchy.x(plan[ordinal]);
            int pageZ = CaveLoadHierarchy.z(plan[ordinal]);
            require(Math.floorDiv(pageX, 8) == 0
                            && Math.floorDiv(pageZ, 8) == 0,
                    "nearest native 8x8-page region is not kept contiguous");
        }
    }

    private static void verifySourceAndProjectionAuthority() throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String decoded = Files.readString(root.resolve(
                "DecodedWorldChunkSource.java"));
        String reader = Files.readString(root.resolve("CaveWorldSaveReader.java"));
        String service = Files.readString(root.resolve(
                "CaveRegionProjectionService.java"));
        String manager = Files.readString(root.resolve(
                "UnifiedCaveTextureManager.java"));
        String archive = Files.readString(root.resolve(
                "archive/CaveArchiveV2Service.java"));
        String cimg = Files.readString(root.resolve("CaveRegionImageCache.java"));
        String lod = Files.readString(root.resolve("CaveLodTree.java"));
        String scheduler = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapWorkScheduler.java"));

        require(decoded.contains("stableSourceRevision(source, root, sectionTags")
                        && decoded.contains("source.contains(\"DataVersion\"")
                        && decoded.contains("section.getCompound(\"block_states\")")
                        && decoded.contains("section.getByteArray(\"BlockLight\")")
                        && !decoded.contains("sourceRevision = System.nanoTime()"),
                "Anvil source identity is still session/time dependent");
        int archiveFirst = decoded.indexOf(
                "CaveChunkTile.Snapshot archive = ensureVerticalArchive(token)");
        int projectionSecond = decoded.indexOf("CaveArchiveProjector.project",
                Math.max(0, archiveFirst));
        require(archiveFirst >= 0 && projectionSecond > archiveFirst,
                "visible projection can run before vertical archive authority");
        require(reader.contains("CAVE_SOURCE_WINDOW_CHUNKS = 6")
                        && reader.contains("buildVisiblePagePlan")
                        && reader.contains("CAVE_SOURCE_WINDOW_COUNT")
                        && reader.contains("CaveRegionProjectionService.getInstance().request"),
                "Anvil import lacks scanline page planning, deduplicated region reads or the 6x6 halo");
        require(service.contains("repository.resolvePage")
                        && service.contains("CavePageStyler.style")
                        && service.contains("stageRegionLocked(page)")
                        && service.contains("regionImageCache.save(snapshot)")
                        && service.contains("readyPages.offer(page)")
                        && service.contains("synchronized boolean owns("),
                "final cave pixels are not produced once by region authority");
        require(manager.indexOf("drainRegionProjectedPages(importedPageBudget")
                        < manager.indexOf("installCompletedRegionImages(regionCachePageBudget")
                        && manager.contains("pendingResult.regionImported()")
                        && manager.contains("regionAuthorityOwns")
                        && manager.contains("Local-world source import already owns resolve + final style")
                        && manager.contains("region_imported="),
                "exact GPU manager still independently rebuilds imported pages");
        require(archive.contains("indexedFingerprint == contentFingerprint")
                        && archive.contains("compact.contentFingerprint()")
                        && !archive.contains("indexedRevision >= compact.revision()"),
                "content fingerprints are still compared as counters");
        require(cimg.contains("private static final int VERSION = 8;")
                        && lod.contains("cave_v12_")
                        && scheduler.contains("REGION_PROJECTION(88, true)"),
                "old presentation generations can replay into PASS75");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
