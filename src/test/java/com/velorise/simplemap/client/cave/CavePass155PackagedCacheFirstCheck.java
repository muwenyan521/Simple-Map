package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards the PASS155 packaged-cache-first pieces retained after PASS156. */
public final class CavePass155PackagedCacheFirstCheck {
    private CavePass155PackagedCacheFirstCheck() { }

    public static void main(String[] args) throws Exception {
        String importer = read("src/main/java/com/velorise/simplemap/client/cave/CaveNativeRegionImportService.java");
        String manager = read("src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java");
        String regionProjection = read("src/main/java/com/velorise/simplemap/client/cave/CaveRegionProjectionService.java");
        String schema = read("src/main/java/com/velorise/simplemap/client/cave/CaveCacheSchema.java");
        String recorder = read("src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java");

        require(importer.contains("NORMAL_ACTIVE_SOURCES = 16")
                        && importer.contains("SOURCE_SLICE = 16")
                        && !importer.contains("VISIBLE_ARCHIVE_DEFER_MS"),
                "PASS156 did not preserve the coherent sixteen-source page transaction");
        require(importer.contains("scheduleHotArchivePackaging")
                        && importer.contains("continueHotArchivePackaging")
                        && importer.contains("durableCavePackaging"),
                "durable archive maintenance helpers were removed instead of being detached from foreground");
        require(importer.contains("REGION_IMAGE_BOOTSTRAP_GRACE_MS = 350L")
                        && importer.contains("CAVE_CACHE_BOOTSTRAP_SOURCE_DEFERRED")
                        && importer.contains("xaero_render_cache_before_world_save")
                        && importer.contains("cimg_bootstrap_pages="),
                "packaged CIMG does not get first right of refusal before world-save reconstruction");

        int install = manager.indexOf("installCompletedRegionImages(regionCachePageBudget, deadline, now)");
        int drain = manager.indexOf("drainRegionProjectedPages(importedPageBudget, deadline, now)");
        require(install >= 0 && drain >= 0 && install < drain,
                "render thread still reconstructs fresh pages before installing finished CIMG products");
        require(manager.contains("branchBudgetExhausted = publishBranches(preDrainBranchBudget, deadline)")
                        && manager.contains("bootstrapBranchBudget > 0 && !branchBudgetExhausted"),
                "same frame can still double-probe an already exhausted branch GPU budget");
        require(manager.contains("peekEligiblePage(planner)")
                        && manager.contains("candidate.planOrdinal()")
                        && manager.contains("CAVE_PACKAGED_CACHE_PUBLICATION_WAVE")
                        && manager.contains("strict_scanline_prefix_ready_only"),
                "ready CIMG pages no longer participate in the deterministic viewport publication wave");
        require(regionProjection.contains("REGION_WRITE_MIN_PACKAGED_PAGES = 8")
                        && regionProjection.contains("REGION_WRITE_MIN_PROGRESS = 8")
                        && regionProjection.contains("lastWrittenPageMask")
                        && regionProjection.contains("coalesced_packaged_cimg"),
                "CIMG writer can still rewrite a full 1 MiB region for tiny partial progress");

        require(schema.contains("EPOCH = 8"),
                "PASS155 must reuse PASS154 Cave epoch-8 products instead of cold-invalidating them again");
        require(recorder.contains("PASS156_ordered_frontier_bounded_minimap"),
                "current runtime provenance is missing");

        System.out.println("CAVE_PASS155_PACKAGED_CACHE_FIRST_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
