package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-free guard for PASS148 visible Cave saved-world ownership. */
public final class CavePass148VisibleWorldSavePathCheck {
    private CavePass148VisibleWorldSavePathCheck() { }

    public static void main(String[] args) throws Exception {
        String viewport = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapViewportCoordinator.java"));
        String scanner = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/ChunkScanner.java"));
        String cavePipeline = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CavePipeline.java"));
        String worldSave = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/"
                        + "WorldSaveProjectionPipeline.java"));
        String foregroundWriter = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapForegroundWriter.java"));

        require(viewport.contains("profile.allowSavedVisible()")
                        && count(viewport, "scanVisibleCaveArea(minecraft") >= 2
                        && viewport.contains("else if (profile.allowVisibleScan())"),
                "visible Cave is not routed independently from Surface scan admission");
        require(scanner.contains("public void scanVisibleCaveArea")
                        && scanner.contains("CavePipeline.getInstance().scanVisibleArea")
                        && scanner.contains("caveActive && mc.screen instanceof MapScreen"),
                "ChunkScanner can still skip or compete with the visible Cave saved-world path");
        require(cavePipeline.contains("worldSaveReader.requestVisible(minecraft")
                        && cavePipeline.contains("CAVE_FULLSCREEN_SAVED_SOURCE_ONLY")
                        && cavePipeline.contains("policy=anvil_snapshot_no_live_writer")
                        && cavePipeline.indexOf("worldSaveReader.requestVisible(minecraft")
                                < cavePipeline.indexOf("CAVE_FULLSCREEN_SAVED_SOURCE_ONLY"),
                "fullscreen Cave does not hand off to world-save source before freezing live writer");
        require(worldSave.contains("ensureFullscreenCaveSnapshot")
                        && worldSave.contains("level.getChunkSource().save(false)")
                        && worldSave.contains("CAVE_FULLSCREEN_WORLD_SAVE_FLUSH_STARTED")
                        && worldSave.contains("regionImporter.requestViewport"),
                "Xaero-style snapshot -> native importer path is no longer connected");
        require(foregroundWriter.contains(
                        "minecraft.screen instanceof MapScreen && CaveMode.isActive(minecraft)")
                        && foregroundWriter.contains("creditNanos = 0L"),
                "render-frame live Cave writer still mutates fullscreen snapshot source");

        System.out.println("CAVE_PASS148_VISIBLE_WORLD_SAVE_PATH_PASS");
    }

    private static int count(String text, String needle) {
        int count = 0;
        int cursor = 0;
        while ((cursor = text.indexOf(needle, cursor)) >= 0) {
            count++;
            cursor += needle.length();
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
