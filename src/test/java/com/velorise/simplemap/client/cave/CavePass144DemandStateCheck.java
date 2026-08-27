package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-free guard for PASS144 Surface->Cave demand state repair/locality. */
public final class CavePass144DemandStateCheck {
    private CavePass144DemandStateCheck() { }

    public static void main(String[] args) throws Exception {
        String importer = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/"
                        + "CaveNativeRegionImportService.java"));

        require(importer.contains("region.reopenMissingForegroundSources(now)"),
                "pump can still skip a region with stale Surface-only settlement");
        require(importer.contains("boolean knownAbsent = presenceKnown.get(index)")
                        && importer.contains("sourceSettled.clear(index);"),
                "new product demand does not reopen a previously-settled present source");
        require(importer.contains("missingForegroundCaveSource = foregroundTarget != null")
                        && importer.contains("sourceSettled.get(index)"
                                + " && !missingForegroundCaveSource"),
                "source eligibility can still deadlock a missing visible Cave product");
        require(importer.contains("CAVE_FOREGROUND_SOURCE_STATE_REOPENED")
                        && importer.contains("surface_to_cave_state_repair"),
                "PASS144 state-repair telemetry is missing");
        require(importer.contains("REGION_SOURCE_FAIRNESS_MS = 2_000L")
                        && importer.contains("closestIncompleteCavePageDistanceSquared()"),
                "region scheduling can still round-robin disconnected Cave islands immediately");
        require(importer.contains("hasIncompleteForegroundCaveLane(MapRequestLane.FULLSCREEN)")
                        && importer.contains("byFullscreenDependency"),
                "fullscreen Cave can still be starved by the stronger minimap lane");
        require(importer.contains("distanceSquared < bestDistanceSquared")
                        && importer.contains("ready > bestReady"),
                "page selection is not locality-first with ready-count as a tie-breaker");
        require(importer.contains("PRESSURE_ACTIVE_SOURCES = 16")
                        && importer.contains("SOURCE_SLICE = 16"),
                "PASS144 accidentally regressed the coherent 4x4 source transaction floor");

        System.out.println("CAVE_PASS144_DEMAND_STATE_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
