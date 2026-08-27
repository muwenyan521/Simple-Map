package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level guard for PASS129 Xaero-style writer-window ownership. */
public final class CavePass129PlannerOwnershipCheck {
    private CavePass129PlannerOwnershipCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String manager = Files.readString(root.resolve("UnifiedCaveTextureManager.java"));
        String regions = Files.readString(root.resolve("CaveRegionProjectionService.java"));

        require(manager.contains("CAVE_REQUEST_EXPIRED_VIEWPORT_RETAINED")
                        && manager.contains("CAVE_COMPLETION_NO_LONGER_OWNED")
                        && manager.contains("loadingPresentationGenerations")
                        && manager.contains("presentationGenerationOwned(")
                        && manager.contains("&& !isProjectionStillOwned(")
                        && manager.contains("repository.isGenerationCurrent(repositoryGeneration));"),
                "exact Cave lifetime lacks viewport or loading-generation ownership");
        require(regions.contains("CAVE_REGION_SUPERSEDED_PROJECTION_RETIRED")
                        && regions.contains("presentationRetired = true")
                        && regions.contains("if (request.presentationRetired)"),
                "superseded native-region foreground projection is not retired eagerly");
        System.out.println("CAVE_PASS129_PLANNER_OWNERSHIP_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
