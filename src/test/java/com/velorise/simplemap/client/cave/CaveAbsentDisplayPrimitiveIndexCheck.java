package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level regression that remains dependency-light like the other architecture checks. */
public final class CaveAbsentDisplayPrimitiveIndexCheck {
    private CaveAbsentDisplayPrimitiveIndexCheck() {}

    public static void main(String[] args) throws Exception {
        Path source = Path.of("src/main/java/com/velorise/simplemap/client/cave/CaveTileRepository.java");
        String code = Files.readString(source);
        require(!code.contains("absentDisplayTiles"),
                "transient disk absence must not have a presentation index");
        String proof = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveEmptyProof.java"));
        require(proof.contains("COMPLETE_SAVED_SOURCE_EMPTY")
                        && proof.contains("COMPLETE_LIVE_SOURCE_EMPTY"),
                "proof-bearing empty authority is missing");
        require(code.contains("Long2LongOpenHashMap regionRevisions")
                        && code.contains("regionRevisions.addTo(pack("),
                "region source revisions must remain primitive-backed");
        require(code.contains("Long2IntOpenHashMap displayRegionChunkCounts"),
                "Cave region-presence indexes must remain primitive");
        System.out.println("CAVE_ABSENT_DISPLAY_PRIMITIVE_INDEX_PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
