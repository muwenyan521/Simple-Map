package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS133-A-D preemption, lifecycle separation and revision coalescing. */
public final class CavePass133PerformanceInvariantCheck {
    private CavePass133PerformanceInvariantCheck() { }

    public static void main(String[] args) throws Exception {
        Path cave = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String scanner = Files.readString(cave.resolve("CaveTileScanner.java"));
        String scheduler = Files.readString(cave.resolve("CaveTileScheduler.java"));
        String pipeline = Files.readString(cave.resolve("CavePipeline.java"));
        String importer = Files.readString(
                cave.resolve("CaveNativeRegionImportService.java"));
        String repository = Files.readString(cave.resolve("CaveTileRepository.java"));
        String tile = Files.readString(cave.resolve("CaveChunkTile.java"));
        String projectionV2 = Files.readString(
                cave.resolve("projection/CaveProjectionServiceV2.java"));
        String stages = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapPipelineStage.java"));

        require(scanner.contains("scanColumnSlice(")
                        && scanner.contains("System.nanoTime() < deadlineNanos")
                        && scanner.contains("maximumVerticalSteps")
                        && scheduler.contains("COLUMN_VERTICAL_BURST = 16")
                        && scheduler.contains("CaveColumnScanCursor activeCursor")
                        && scheduler.contains("recordArchiveScanSlice")
                        && stages.contains("CAVE_ARCHIVE_SCAN")
                        && pipeline.contains("Math.min(120_000L, governed / 5L)")
                        && pipeline.contains("scheduler.process(minecraft.level, finalDeadline)"),
                "canonical scanner is not preemptible inside a vertical column");

        require(importer.contains("BitSet sourceSettled")
                        && importer.contains("BitSet sourcePresent")
                        && importer.contains("BitSet transientDiskAbsent")
                        && importer.contains("requiredSourcesSettledForPass()")
                        && importer.contains("markLivePending")
                        && importer.contains("acknowledgeLiveArchive")
                        && importer.contains("CAVE_NATIVE_REGION_SOURCE_SETTLED")
                        && !importer.contains("private final BitSet absent ="),
                "source acquisition settlement is still conflated with Cave authority");

        require(tile.contains("shouldPublishArchiveMilestone()")
                        && tile.contains("lastPublishedScannedCount")
                        && repository.contains("tile.shouldPublishArchiveMilestone()")
                        && repository.contains("recordArchiveCompactPublication")
                        && repository.contains("projectionChildReadyMask(")
                        && importer.contains("foregroundSubmittedChildMasks")
                        && importer.contains("newChildren >= quantum")
                        && importer.contains("CAVE_FOREGROUND_PAGE_PROGRESS_COALESCED"),
                "archive/page revision amplification is not coalesced");

        String topYActivation = projectionV2.substring(
                projectionV2.indexOf("int activateLayeredTopY"),
                projectionV2.indexOf("public synchronized int activateFull"));
        require(!topYActivation.contains("cacheEpoch++")
                        && projectionV2.contains("activateDimension")
                        && projectionV2.contains("cacheEpoch++"),
                "Top-Y movement still globally fences otherwise reusable projections");

        verifyArchiveMilestones();
        System.out.println("CAVE_PASS133_PERFORMANCE_INVARIANTS_PASS");
    }

    private static void verifyArchiveMilestones() {
        CaveChunkTile tile = new CaveChunkTile(0, 0, true);
        CaveColumnData empty = CaveColumnData.emptyScanned(-64, 80, true);
        for (int column = 0; column < 63; column++) {
            tile.commitColumn(column, empty);
            require(!tile.shouldPublishArchiveMilestone(),
                    "archive published before first 64-column milestone");
        }
        tile.commitColumn(63, empty);
        require(tile.shouldPublishArchiveMilestone(),
                "first 64-column archive milestone was not published");
        tile.markArchivePublished(tile.archiveRevision());
        require(!tile.shouldPublishArchiveMilestone(),
                "claimed archive milestone republished without progress");

        for (int column = 64; column < CaveChunkTile.COLUMN_COUNT; column++) {
            tile.commitColumn(column, empty);
            if (column == 127 || column == 191 || column == 255) {
                require(tile.shouldPublishArchiveMilestone(),
                        "missing archive milestone at column " + (column + 1));
                tile.markArchivePublished(tile.archiveRevision());
            }
        }

        CaveColumnData.Builder builder = new CaveColumnData.Builder();
        builder.add(40, 20, 0xFF335577, (byte) 0);
        CaveColumnData changed = builder.build(-64, 80, true);
        require(tile.requestRecheckColumn(0, 0),
                "complete tile did not admit mutation repair");
        require(tile.commitColumn(0, changed),
                "mutation repair did not change archive content");
        require(tile.shouldPublishArchiveMilestone(),
                "completed mutation repair was not published immediately");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
