package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS83 compatibility guard updated for PASS132 transient absence semantics. */
public final class CavePass83PresenceReconciliationCheck {
    private CavePass83PresenceReconciliationCheck() { }

    public static void main(String[] args) throws Exception {
        String repository = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveTileRepository.java"));
        require(!repository.contains("absentDisplayTiles"),
                "transient absence can still be published as display authority");

        CaveTileRepository live = CaveTileRepository.getInstance();
        live.clearRuntime(false);
        long generation = live.generation();
        int firstChunkX = 40;
        int firstChunkZ = -24;
        int pageX = firstChunkX >> 2;
        int pageZ = firstChunkZ >> 2;
        live.markDisplayTileAbsent(CaveView.FULL, Integer.MIN_VALUE,
                firstChunkX, firstChunkZ, generation);
        long absentRevision = live.getPageRevision(
                CaveView.FULL, Integer.MIN_VALUE, pageX, pageZ);
        boolean reconciled = live.commitDisplayPage(java.util.List.of(),
                CaveView.FULL, Integer.MIN_VALUE, firstChunkX, firstChunkZ,
                generation);
        long presentRevision = live.getPageRevision(
                CaveView.FULL, Integer.MIN_VALUE, pageX, pageZ);
        require(!reconciled && presentRevision == absentRevision
                        && !live.hasProjectionAuthorityPage(CaveView.FULL,
                                Integer.MIN_VALUE, pageX, pageZ),
                "disk absence created a Full known-empty authority transition");
        System.out.println("CAVE_PASS83_PRESENCE_RECONCILIATION_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
