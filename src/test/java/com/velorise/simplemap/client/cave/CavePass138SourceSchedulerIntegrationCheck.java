package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-free guard for PASS138 viewport/source scheduler integration. */
public final class CavePass138SourceSchedulerIntegrationCheck {
    private CavePass138SourceSchedulerIntegrationCheck() { }

    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/"
                        + "CaveNativeRegionImportService.java"));

        int requestStart = source.indexOf("synchronized void requestViewport");
        int sourceMasks = source.indexOf("Map<Long, Long> sourceMasks", requestStart);
        require(requestStart >= 0 && sourceMasks > requestStart,
                "requestViewport structure not found");
        String requestPreamble = source.substring(requestStart, sourceMasks);
        require(!requestPreamble.contains("existing.clearCaveDemand()"),
                "viewport still clears all Cave demand before re-applying it");

        require(source.contains("boolean structurallySame = caveVisiblePageMask == pageMask")
                        && source.contains("if (structurallySame && schedulingSame) return;"),
                "identical Cave demand is not a hard no-op");
        require(source.contains("if (!productDemandChanged && !frontierChange)"),
                "combined demand still performs structural work on identical refreshes");

        require(source.contains("SOURCE_SELECTION_LOOKAHEAD = 64")
                        && source.contains("scoredEligible < SOURCE_SELECTION_LOOKAHEAD")
                        && source.contains("sourceFrontier.advanceCursor(cursor + inspected)"),
                "source selection is not bounded/persistent");
        require(source.contains("VISIBLE_SOURCE_FAIRNESS_MS = 500L")
                        && source.contains("fairnessIndex")
                        && source.contains("1_500_000L, waitAgeMs * 2_000L"),
                "visible-source anti-starvation is missing");

        require(!source.contains("SOURCE_CELL_ADMITTED")
                        && source.contains("SOURCE_ADMISSION_SUMMARY"),
                "per-source admission debug spam returned");
        require(!source.contains("VISIBLE_CAVE_PAGE_SOURCE_WAIT")
                        && source.contains("VISIBLE_CAVE_SOURCE_WAIT_SUMMARY"),
                "per-page wait debug spam returned");
        require(source.contains("changedChunks > 0 || applyUs >= 500L"),
                "no-op Anvil presence telemetry is not suppressed");

        System.out.println("CAVE_PASS138_SOURCE_SCHEDULER_INTEGRATION_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
