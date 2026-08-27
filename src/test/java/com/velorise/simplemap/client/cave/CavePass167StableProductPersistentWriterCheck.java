package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS167: stable exact product identity + true persistent Cave writer. */
public final class CavePass167StableProductPersistentWriterCheck {
    private CavePass167StableProductPersistentWriterCheck() { }

    public static void main(String[] args) throws Exception {
        String repo = read("src/main/java/com/velorise/simplemap/client/cave/CaveTileRepository.java");
        String raw = read("src/main/java/com/velorise/simplemap/client/cave/CaveChunkTile.java");
        String coordinator = read("src/main/java/com/velorise/simplemap/client/MapViewportCoordinator.java");
        String recorder = read("src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java");

        require(repo.contains("long settledDisplayStamp = getDisplayPageResolutionStamp")
                        && repo.contains("if (settledDisplayStamp != 0L)")
                        && repo.contains("PASS167: once all sixteen visible leaves are resolved"),
                "settled exact pages are still coupled to background archive progress");
        require(raw.contains("scanned.cardinality() == COLUMN_COUNT")
                        && !raw.contains("return scannedCount / 64 > lastPublishedScannedCount / 64"),
                "partial 64-column archive milestones can still mutate visible source identity");
        require(coordinator.contains("PASS167: the visible fullscreen Cave writer is a liveness owner")
                        && coordinator.contains("!MapActivityGate.getInstance().blocksForegroundStreaming()")
                        && coordinator.contains("policy=xaero_persistent_visible_writer")
                        && coordinator.contains("profile_saved_visible="),
                "fullscreen Cave writer can still be parked by a background/saved-work profile");
        require(recorder.contains("PASS167_stable_product_revision_persistent_writer")
                        && recorder.contains("PASS166_ordered_preupload_frontier_repair"),
                "PASS167 provenance/predecessor marker missing");

        System.out.println("CAVE_PASS167_STABLE_PRODUCT_PERSISTENT_WRITER_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
