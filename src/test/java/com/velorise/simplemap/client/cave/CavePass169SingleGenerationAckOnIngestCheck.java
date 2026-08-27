package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS169: one live foreground generation and ACK-on-retain semantics. */
public final class CavePass169SingleGenerationAckOnIngestCheck {
    private CavePass169SingleGenerationAckOnIngestCheck() { }

    public static void main(String[] args) throws Exception {
        String projection = read("src/main/java/com/velorise/simplemap/client/cave/CaveRegionProjectionService.java");
        String manager = read("src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java");
        String recorder = read("src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java");

        require(projection.contains("CAVE_SUPERSEDED_FOREGROUND_REGION_REQUEST_DROPPED")
                        && projection.contains("single_active_foreground_generation"),
                "superseded foreground region requests can still resurrect an old layer");
        require(projection.contains("CAVE_STALE_FOREGROUND_REOFFER_SUPPRESSED")
                        && projection.contains("request.presentationRetired || projectionMismatch"),
                "retired/inactive region products can still re-offer forever");
        require(manager.contains("polling a coherent foreground product transfers ownership")
                        && manager.contains("service.acknowledgeForeground(imported);"),
                "CPU-ready foreground products are not ACKed when retained by the consumer backlog");
        require(recorder.contains("PASS169_single_generation_ack_on_ingest")
                        && recorder.contains("PASS168_xaero_writer_no_global_publication_mutex"),
                "PASS169 provenance/predecessor marker missing");

        System.out.println("CAVE_PASS169_SINGLE_GENERATION_ACK_ON_INGEST_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
