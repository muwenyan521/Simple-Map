package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-free static guard for PASS139 load-stability fixes. */
public final class CavePass139LoadStabilityCheck {
    private CavePass139LoadStabilityCheck() { }

    public static void main(String[] args) throws Exception {
        String caveRoot = "src/main/java/com/velorise/simplemap/client/cave/";
        String clientRoot = "src/main/java/com/velorise/simplemap/client/";

        String budget = read(caveRoot + "AdaptiveWorldSourceBudget.java");
        require(budget.contains("MAX_TARGET = 256L * MIB")
                        && budget.contains("Math.min(32,")
                        && budget.contains("pressure >= 0.78D"),
                "decoded-source heap/IO flood limits are missing");
        require(!budget.contains("Math.min(288,"),
                "288-read source flood returned");

        String cache = read(caveRoot + "DecodedWorldRegionCache.java");
        require(cache.contains("SOURCE_CPU_QUEUE_SOFT_LIMIT = 24")
                        && cache.contains("SOURCE_CPU_QUEUE_PRESSURE_LIMIT = 12")
                        && cache.contains("decodeWorkers.queuedTasks() >= queueLimit"),
                "decoded-source backpressure limits are missing");

        String reader = read(caveRoot + "CaveWorldSaveReader.java");
        require(reader.contains("CAVE_CENTRAL_SOURCE_ORDER")
                        && reader.contains("PRESENTATION_SOURCE_MASK")
                        && reader.contains("LIVE_REPAIR_POLL_MS = 250L"),
                "visible exact-page source is still gated by the full 6x6 halo");
        require(reader.contains(
                        "for (int order = 0; order < CAVE_CENTRAL_SOURCE_ORDER.length; order++)"),
                "foreground source loop is not central-page-first");
        require(reader.contains("Math.min(blockedDelay, LIVE_REPAIR_POLL_MS)"),
                "transient disk absence can still hide a live repair for 30 seconds");

        String importer = read(caveRoot + "CaveNativeRegionImportService.java");
        require(importer.contains("GeneratedChunkIndex.State.LIVE")
                        && importer.contains("markLivePending(chunkX, chunkZ)"),
                "native durable importer can still duplicate live/disk source work");
        /* PASS143 replaces PASS139/PASS142 progressive page waves entirely. */
        require(importer.contains("if (currentChildMask != 0)")
                        && !importer.contains("boolean mutationSubmit"),
                "zero-child foreground publication churn returned");

        String decoded = read(caveRoot + "DecodedWorldChunkSource.java");
        require(decoded.contains("ARCHIVE_COLUMNS_PER_SLICE = 32")
                        && decoded.contains("ARCHIVE_VERTICAL_STEPS_PER_SLICE = 64")
                        && decoded.contains("ARCHIVE_SLICE_BUDGET_NANOS = 2_000_000L")
                        && decoded.contains("ArchiveColumnCursor")
                        && decoded.contains("scanArchiveColumnSlice(")
                        && decoded.contains(
                                "if (verticalArchiveNextColumn < CaveChunkTile.COLUMN_COUNT)"),
                "decoded Cave archive is not retained/time-sliced");

        String executor = read(caveRoot + "PriorityDecodeExecutor.java");
        require(executor.contains("ConcurrentLinkedQueue<PendingTask>")
                        && executor.contains("new Semaphore(3)")
                        && executor.contains("SOURCE_TOTAL_PERMITS")
                        && executor.contains("new Semaphore(4)")
                        && executor.contains("MAX_RETRY_DELAY_MS = 32"),
                "source continuation backpressure is missing");
        require(!executor.contains("cpuExecutor("),
                "per-callback recursive scheduler retry path returned");

        String scheduler = read(clientRoot + "MapWorkScheduler.java");
        require(scheduler.contains("SOURCE_DECODE(87")
                        && scheduler.contains("SOURCE_PROJECTION(86")
                        && scheduler.contains("BRANCH_DERIVE(84"),
                "visible source dependencies are still ranked below branch refinement");

        String atlas = read(caveRoot + "CaveTextureAtlas.java");
        require(atlas.contains("atlasX + 1 + dirty.minX()")
                        && atlas.contains("width, height, pixels, pageSize")
                        && atlas.contains("boolean touchesEdge")
                        && atlas.contains("AtlasGutter.copyOnePixelBorder"),
                "dirty exact uploads are not using partial atlas rectangles");


        String dense = read(caveRoot + "DenseCaveTile.java");
        require(dense.contains("buildOwned(int chunkX")
                        && !read(caveRoot + "CaveDisplayScheduler.java")
                                .contains("task.builder.build(task.key.chunkX()"),
                "one-shot Dense projection still duplicates all result arrays");

        String presence = read(caveRoot + "AnvilPagePresenceIndex.java");
        require(presence.contains("result.headerRead() || changed")
                        && presence.contains("published != null && changed"),
                "unchanged Anvil presence polling still floods debug events");

        String archive = read(caveRoot + "archive/CaveArchiveV2Service.java");
        require(archive.contains("calculateResidentByteLimit()")
                        && archive.contains("heap / 32L")
                        && archive.contains("160L * MIB"),
                "Cave archive resident heap is still fixed at 192 MiB");

        System.out.println("CAVE_PASS139_LOAD_STABILITY_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
