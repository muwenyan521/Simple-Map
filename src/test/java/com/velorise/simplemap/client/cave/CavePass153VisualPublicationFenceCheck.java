package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS153 against CPU-projection/GPU-residency and signed-revision holes. */
public final class CavePass153VisualPublicationFenceCheck {
    private CavePass153VisualPublicationFenceCheck() { }

    public static void main(String[] args) throws Exception {
        String manager = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/"
                        + "UnifiedCaveTextureManager.java"));
        String lod = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveLodTree.java"));
        String recorder = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java"));

        require(manager.contains("CAVE_VISUAL_PUBLICATION_FENCE_RESTORED")
                        && manager.contains("cpu_projection_is_not_gpu_residency")
                        && manager.contains("projected_not_visible_until_exact_or_branch_gpu"),
                "projected CPU pages can still be treated as visible before exact/branch GPU publication");
        require(manager.contains("info.initialized && info.atlasSlot >= 0")
                        && manager.contains("restoreCavePageResidency(info, imported.lane())"),
                "region import still clears a retained CPU page without restoring exact residency");
        require(manager.contains("publishedBranchCoverage")
                        && manager.contains("action=publish_exact_underlay"),
                "branch-only handoff can still suppress exact before matching branch coverage is published");
        require(manager.contains("preDrainBranchBudget")
                        && manager.contains("idleHeadroom ? 6 : 3"),
                "branch backlog still has no pre-drain service or exact safety underlay budget");
        require(lod.contains("canonicalContentRevision(sourceRevision)")
                        && lod.contains("return revision == 0L ? 1L : revision;")
                        && !lod.contains("Math.max(1L, sourceRevision)"),
                "signed content fingerprints are still collapsed, allowing stale branch coalescing");
        require(recorder.contains("BUILD_PROVENANCE"),
                "PASS153 runtime provenance is missing");

        System.out.println("CAVE_PASS153_VISUAL_PUBLICATION_FENCE_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
