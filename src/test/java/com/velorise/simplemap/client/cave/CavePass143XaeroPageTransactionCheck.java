package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-free guard for PASS143 Xaero-style 4x4 Cave source transactions. */
public final class CavePass143XaeroPageTransactionCheck {
    private CavePass143XaeroPageTransactionCheck() { }

    public static void main(String[] args) throws Exception {
        String caveRoot = "src/main/java/com/velorise/simplemap/client/cave/";
        String importer = read(caveRoot + "CaveNativeRegionImportService.java");

        require(importer.contains("NORMAL_ACTIVE_SOURCES = 28")
                        && importer.contains("PRESSURE_ACTIVE_SOURCES = 16")
                        && importer.contains("SOURCE_SLICE = 16"),
                "pressure/source window can no longer admit one complete 4x4 page");
        require(importer.contains("region.hasIncompleteVisibleCavePage()")
                        && importer.contains("? SOURCE_SLICE")
                        && importer.contains("region.hasFocusVisibleDemand() ? 2 : 1"),
                "16-source burst is not restricted to incomplete visible Cave work");
        require(importer.contains("bestForegroundCavePage(nowMs, limit)")
                        && importer.contains("eligible > capacity")
                        && importer.contains("if (hasIncompleteVisibleCavePage())"),
                "foreground source selector can fragment cold Cave pages");
        require(importer.contains("isForegroundCavePageBatch(selected)")
                        && importer.contains("if (coherentCaveBatch)")
                        && importer.contains("selected.clear();"),
                "failed page reservation can still degrade 16 -> 8 -> 4 -> 2 -> 1");
        require(importer.contains("schedulingReadyCaveChildren[ordinal] == 16")
                        && importer.contains("caveSourceClosedForPage("),
                "foreground publication is not bound to semantic 16-child closure");
        require(!importer.contains("CAVE_FOREGROUND_PAGE_PROGRESS_COALESCED")
                        && !importer.contains("boolean progressBurst")
                        && !importer.contains("boolean agedProgress"),
                "superseded partial source-revision publication waves remain active");
        require(importer.contains("VISIBLE_ARCHIVE_DEFER_MS = 5_000L")
                        && importer.contains("hasIncompleteVisibleCavePage())")
                        && (importer.contains("foregroundPresentationTarget(index) == null")
                                || importer.contains("foregroundTarget == null")),
                "archive refinement can still steal the visible 4x4 source window");
        require(importer.contains("CAVE_PAGE_SOURCE_BATCH_ADMITTED")
                        && importer.contains("policy=xaero_4x4_atomic"),
                "PASS143 page-transaction telemetry is missing");

        System.out.println("CAVE_PASS143_XAERO_PAGE_TRANSACTION_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
