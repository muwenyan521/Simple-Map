package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS170: async CPU prepare + one row-major fullscreen GPU/front writer. */
public final class CavePass170XaeroOrderedPresentationWriterCheck {
    private CavePass170XaeroOrderedPresentationWriterCheck() { }

    public static void main(String[] args) throws Exception {
        String manager = read("src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java");
        String renderer = read("src/main/java/com/velorise/simplemap/client/MapRenderer.java");
        String recorder = read("src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java");

        require(manager.contains("CAVE_FULLSCREEN_PAGE_CPU_STAGED")
                        && manager.contains("return ordinal <= planner.publicationCursor")
                        && manager.contains("single_writer_row_major_commit"),
                "CPU preparation and ordered GPU/front commit are not separated");
        require(renderer.contains("renderLane != MapRequestLane.FULLSCREEN")
                        && renderer.contains("fullCaveTextures.allowFullscreenExact")
                        && renderer.contains("caveTextures.allowFullscreenExact"),
                "fullscreen renderer can still bypass the presentation writer");
        require(manager.contains("previousBandUnderlay = coordinateInViewport")
                        && manager.contains("sameBandLastGood")
                        && manager.contains("loadedCaving"),
                "previous layer is not retained as a bounded fullscreen underlay");
        int pullStart = manager.indexOf("private boolean bufferRetainedFullscreenFrontier");
        int pullEnd = manager.indexOf("private PageKey selectRegionExactBacklogKey", pullStart);
        String pull = manager.substring(pullStart, pullEnd);
        require(pull.indexOf("service.find(") < pull.indexOf("info.pending != null"),
                "frontier still trusts stale pending state before retained final pixels");
        require(!manager.contains("CAVE_FULLSCREEN_READY_PAGE_INDEPENDENT_PUBLICATION")
                        && !manager.contains("CAVE_FULLSCREEN_READY_PAGE_PREUPLOADED"),
                "completion-order exact GPU publication still exists");
        require(recorder.contains("PASS170_xaero_ordered_presentation_writer")
                        && recorder.contains("PASS169_single_generation_ack_on_ingest"),
                "PASS170 provenance/predecessor marker missing");

        System.out.println("CAVE_PASS170_XAERO_ORDERED_PRESENTATION_WRITER_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
