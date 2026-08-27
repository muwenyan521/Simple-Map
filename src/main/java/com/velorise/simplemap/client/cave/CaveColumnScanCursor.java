package com.velorise.simplemap.client.cave;

import net.minecraft.core.BlockPos;

/**
 * Retained implementation state for one canonical X/Z column scan.
 *
 * <p>The cursor is never published. A {@link CaveColumnData} becomes visible only
 * after the cursor reaches {@link Phase#COMPLETE}, preserving column-atomic archive
 * semantics while allowing the client-thread writer to yield inside a deep column.</p>
 */
public final class CaveColumnScanCursor {
    enum Phase {
        FIND_TERRAIN_ENTRY,
        SCAN_BODY,
        COMPLETE,
        INVALIDATED
    }

    final int blockX;
    final int blockZ;
    final int chunkX;
    final int chunkZ;
    final CaveTileScanContext context;
    final CaveColumnData.Builder builder = new CaveColumnData.Builder();
    final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

    final int minimumY;
    final int maximumY;
    int startY;
    int y;

    boolean inOpenRun;
    int runTopY;
    int waterTopY = Integer.MIN_VALUE;
    int waterDepth;
    boolean runHadWater;
    boolean runHadOtherFluid;
    boolean runFluidEmissive;
    int runFluidColor;
    int runFluidAlpha;
    int runFluidY;
    int runFluidLight;
    int runFluidDepth;
    int runEmissiveColor;
    int runEmissiveAlpha;
    int runEmissiveY;
    int runEmissiveLight;

    Phase phase = Phase.FIND_TERRAIN_ENTRY;
    CaveColumnData completedData;
    int lastSliceSteps;

    CaveColumnScanCursor(int blockX, int blockZ, CaveTileScanContext context,
            int minimumY, int maximumY, int topY) {
        this.blockX = blockX;
        this.blockZ = blockZ;
        this.chunkX = blockX >> 4;
        this.chunkZ = blockZ >> 4;
        this.context = context;
        this.minimumY = minimumY;
        this.maximumY = maximumY;
        this.startY = topY;
        this.y = topY;
        builder.reset();
    }

    public boolean isComplete() {
        return phase == Phase.COMPLETE;
    }

    public CaveColumnData completedData() {
        return completedData;
    }

    public int lastSliceSteps() {
        return lastSliceSteps;
    }

    void beginBody(int firstBodyY) {
        startY = firstBodyY;
        y = firstBodyY;
        phase = Phase.SCAN_BODY;
    }

    void resetRun() {
        inOpenRun = false;
        waterTopY = Integer.MIN_VALUE;
        waterDepth = 0;
        runHadWater = false;
        runHadOtherFluid = false;
        runFluidEmissive = false;
        runFluidColor = 0;
        runFluidAlpha = 0;
        runFluidY = 0;
        runFluidLight = 0;
        runFluidDepth = 0;
        runEmissiveColor = 0;
        runEmissiveAlpha = 0;
        runEmissiveY = 0;
        runEmissiveLight = 0;
    }

    void beginRun(int topY) {
        inOpenRun = true;
        runTopY = topY;
        waterTopY = Integer.MIN_VALUE;
        waterDepth = 0;
        runHadWater = false;
        runHadOtherFluid = false;
        runFluidEmissive = false;
        runFluidColor = 0;
        runFluidAlpha = 0;
        runFluidY = 0;
        runFluidLight = 0;
        runFluidDepth = 0;
        runEmissiveColor = 0;
        runEmissiveAlpha = 0;
        runEmissiveY = 0;
        runEmissiveLight = 0;
    }

    void complete(CaveColumnData data) {
        completedData = data;
        phase = Phase.COMPLETE;
    }

    void invalidate() {
        completedData = null;
        phase = Phase.INVALIDATED;
    }
}
