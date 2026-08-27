package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Guards PASS159: bounded Cave source ownership must never retire visible projection
 * ownership, and Layered fullscreen keeps one last-good layer during handoff.
 */
public final class CavePass159LayerProjectionLifecycleCheck {
    private CavePass159LayerProjectionLifecycleCheck() { }

    public static void main(String[] args) throws Exception {
        String pipeline = read("src/main/java/com/velorise/simplemap/client/cave/WorldSaveProjectionPipeline.java");
        String importer = read("src/main/java/com/velorise/simplemap/client/cave/CaveNativeRegionImportService.java");
        String facade = read("src/main/java/com/velorise/simplemap/client/CaveTextureManager.java");
        String unified = read("src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java");
        String recorder = read("src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java");

        require(pipeline.contains("pauseCaveSourceLane(MapRequestLane.FULLSCREEN)")
                        && pipeline.contains("source_complete_not_presentation_complete")
                        && !sourceCompleteBlock(pipeline).contains("suspendCaveLane(MapRequestLane.FULLSCREEN)"),
                "source-window completion can still retire fullscreen projection ownership");

        require(importer.contains("boolean retainPresentationWindow")
                        && importer.contains("setForegroundDemandRetained")
                        && importer.contains("clipForegroundDemand")
                        && importer.contains("pageMaskForRegionBounds")
                        && importer.contains("hasProjectionLane(lane)"),
                "bounded source window is still coupled to exact presentation lifetime");

        require(facade.contains("previousLayerY = oldLayer == Integer.MIN_VALUE")
                        && facade.contains("lane != MapRequestLane.MINIMAP && lane != MapRequestLane.FULLSCREEN")
                        && facade.contains("fallbackLayer(layerY, true, lane)"),
                "fullscreen Layered handoff still blanks the previous displayed layer");

        int keepPrevious = unified.indexOf("if (keepPrevious)");
        int nextBranch = unified.indexOf("continue;", keepPrevious);
        require(keepPrevious >= 0 && nextBranch > keepPrevious
                        && !unified.substring(keepPrevious, nextBranch)
                                .contains("releaseAtlasSlot()"),
                "previous Layered band still loses GPU residency during handoff");

        require(recorder.contains("PASS159_layer_projection_lifecycle"),
                "PASS159 build provenance missing");

        System.out.println("CAVE_PASS159_LAYER_PROJECTION_LIFECYCLE_PASS");
    }

    private static String sourceCompleteBlock(String text) {
        int start = text.indexOf("if (pagePlan.length == 0)");
        int end = text.indexOf("foregroundPagePlan = pagePlan;", start);
        return start < 0 || end < 0 ? "" : text.substring(start, end);
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
