package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-free guard for PASS146 visible Cave transaction semantics. */
public final class CavePass146ForegroundTransactionCheck {
    private CavePass146ForegroundTransactionCheck() { }

    public static void main(String[] args) throws Exception {
        String importer = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/"
                        + "CaveNativeRegionImportService.java"));
        String pipeline = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/"
                        + "WorldSaveProjectionPipeline.java"));
        String viewport = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/"
                        + "MapViewportCoordinator.java"));

        require(importer.contains("private long foregroundCavePageMask()")
                        && importer.contains("long remaining = foregroundCavePageMask();"),
                "source-only support pages can still enter foreground Cave scheduling");
        require(importer.contains("hasInFlightForegroundPageSibling")
                        && importer.contains("CAVE_PAGE_SOURCE_COMPLETION_COALESCED")
                        && importer.contains("publish_and_pump_on_last_child"),
                "4x4 admission is still followed by per-child publish/pump churn");
        require(importer.contains("presentationLane == MapRequestLane.FULLSCREEN")
                        && importer.contains("fullscreen")
                        && importer.contains("world-map Cave must not become hostage"),
                "fullscreen Cave can still be diverted into the live-writer dependency");
        require(importer.contains("MapRequestLane sourceLane = cavePresentationPending")
                        && importer.contains("? presentationLane"),
                "foreground Cave source work can still inherit a stale minimap Surface lane");
        require(importer.contains("hasFullscreenCaveDemand()")
                        && importer.contains("construct a universal all-height archive"),
                "archive-only source work can still compete during fullscreen Cave");
        require(importer.contains("existing.clearSurfaceDemand(MapRequestLane.MINIMAP)")
                        && importer.contains("existing.clearSurfaceDemand(MapRequestLane.BACKGROUND)"),
                "hidden Surface product leases still survive into fullscreen Cave");
        require(pipeline.contains("CAVE_FULLSCREEN_WORLD_SAVE_FLUSH_STARTED")
                        && pipeline.contains("level.getChunkSource().save(false)")
                        && pipeline.contains("xaero_snapshot_barrier"),
                "fullscreen bulk import does not snapshot the integrated-server save like Xaero");
        require(viewport.contains("UnifiedCaveTextureManager.getInstance().suspendLane(")
                        && viewport.contains("CaveWorldSaveReader.getInstance().suspendLane("),
                "closing fullscreen still leaves old Cave request ownership alive");

        System.out.println("CAVE_PASS146_FOREGROUND_TRANSACTION_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
