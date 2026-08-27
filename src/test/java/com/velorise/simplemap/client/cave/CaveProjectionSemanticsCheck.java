package com.velorise.simplemap.client.cave;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

/** Regression gate for the Xaero-style one-surface cave projection contract. */
public final class CaveProjectionSemanticsCheck {
    private CaveProjectionSemanticsCheck() {
    }

    public static void main(String[] args) throws Exception {
        CaveColumnData column = new CaveColumnData(
                new short[] { 12, 9 }, new short[] { 11, 6 },
                new int[] { 0xFF777777, 0xFF555555 }, new byte[] { 0, 0 }, 2,
                0, 15, true);
        require(column.firstVisibleFullCandidate().bottomY() == 11,
                "Full Cave skipped Xaero's first underground floor");
        require(column.firstVisibleLayeredCandidate(12, 0).bottomY() == 11,
                "Layered Cave did not select the first floor below Top Y");
        require(column.firstVisibleLayeredCandidate(10, 0).bottomY() == 6,
                "Layered Cave leaked a floor from outside its downward scan");

        require(privateInt(CaveDisplayRegionStore.class, "REGION_VERSION") >= 5,
                "Dense Cave cache version was not invalidated");
        require(privateInt(CaveDisplayRegionStore.class, "TILE_VERSION") >= 6,
                "Dense Cave tile version was not invalidated");
        require(privateInt(CaveRegionStore.class, "REGION_VERSION") >= 2,
                "Raw Cave archive version was not invalidated");

        String liveScanner = Files.readString(Path.of("src/main/java/com/velorise/"
                + "simplemap/client/cave/CaveTileScanner.java"));
        String saveDecoder = Files.readString(Path.of("src/main/java/com/velorise/"
                + "simplemap/client/cave/CaveWorldSaveChunkDecoder.java"));
        String projector = Files.readString(Path.of("src/main/java/com/velorise/"
                + "simplemap/client/cave/CaveDisplayProjector.java"));
        require(liveScanner.contains("if (cursor.inOpenRun)")
                        && liveScanner.contains(
                                "CaveProjectionSemantics.isOpenDecoration")
                        && liveScanner.contains(
                                "kind == CaveStateClassifier.DYNAMIC"),
                "Live archive may let decoration open a false cave");
        require(saveDecoder.contains(
                        "inOpenRun && CaveProjectionSemantics.isOpenDecoration"),
                "World-save archive may let decoration open a false cave");
        require(!projector.contains("FULL_CAVE_MIN_HEADROOM"),
                "Full Cave still drops legitimate one-block openings unlike Xaero");
        require(projector.contains("cursor.shouldEnterGround = false")
                        && projector.contains("if (!cursor.underAir)"),
                "Dense projector lost the enter-ground/air/floor transaction");

        System.out.println("CAVE_PROJECTION_SEMANTICS_PASS");
    }

    private static int privateInt(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

}
