package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapVisualClassifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Client-thread-only, resumable vertical cave scanner. */
public final class CaveTileScanner {
    public enum ScanSliceResult {
        PAUSED,
        COMPLETE,
        INVALIDATED
    }

    private final CaveStateClassifier classifier = CaveStateClassifier.getInstance();
    private final MapVisualClassifier visualClassifier = MapVisualClassifier.getInstance();
    private final CaveColorResolver colors = CaveColorResolver.getInstance();
    private final CaveTelemetry telemetry = CaveTelemetry.getInstance();

    public CaveColumnData scanColumn(Level level, int blockX, int blockZ) {
        CaveTileScanContext context = CaveTileScanContext.create(level, blockX >> 4, blockZ >> 4);
        return scanColumn(level, blockX, blockZ, context);
    }

    /**
     * Scans one X/Z column while reusing section-palette information from the other
     * columns in the same chunk tile.
     */
    public CaveColumnData scanColumn(Level level, int blockX, int blockZ,
            CaveTileScanContext context) {
        CaveColumnScanCursor cursor = beginColumn(
                level, blockX, blockZ, context);
        if (cursor == null) return null;
        while (true) {
            ScanSliceResult result = scanColumnSlice(level, cursor,
                    Long.MAX_VALUE, Integer.MAX_VALUE);
            if (result == ScanSliceResult.COMPLETE) {
                return cursor.completedData();
            }
            if (result == ScanSliceResult.INVALIDATED) return null;
        }
    }

    /** Starts one column without doing its unbounded vertical traversal. */
    public CaveColumnScanCursor beginColumn(Level level, int blockX, int blockZ,
            CaveTileScanContext context) {
        if (level == null || context == null
                || !level.hasChunk(blockX >> 4, blockZ >> 4)) return null;
        int minimumY = level.getMinBuildHeight();
        int maximumY = level.getMaxBuildHeight() - 1;
        int topY = CaveDimensionProfile.shouldScanFromWorldTop(level)
                ? maximumY
                : Math.max(minimumY, Math.min(maximumY,
                        level.getHeight(Heightmap.Types.WORLD_SURFACE,
                                blockX, blockZ)));
        return new CaveColumnScanCursor(blockX, blockZ, context,
                minimumY, maximumY, topY);
    }

    /**
     * Advances at most {@code maximumVerticalSteps} and checks the hard deadline
     * before every palette/state operation. Incomplete work remains only in the
     * cursor and is safe to resume on a later render frame.
     */
    public ScanSliceResult scanColumnSlice(Level level,
            CaveColumnScanCursor cursor, long deadlineNanos,
            int maximumVerticalSteps) {
        if (level == null || cursor == null
                || cursor.phase == CaveColumnScanCursor.Phase.INVALIDATED
                || !level.hasChunk(cursor.chunkX, cursor.chunkZ)) {
            if (cursor != null) cursor.invalidate();
            return ScanSliceResult.INVALIDATED;
        }
        if (cursor.phase == CaveColumnScanCursor.Phase.COMPLETE) {
            cursor.lastSliceSteps = 0;
            return ScanSliceResult.COMPLETE;
        }

        int maximum = Math.max(1, maximumVerticalSteps);
        int steps = 0;
        while (steps < maximum && System.nanoTime() < deadlineNanos) {
            if (cursor.phase == CaveColumnScanCursor.Phase.FIND_TERRAIN_ENTRY) {
                if (cursor.y < cursor.minimumY) {
                    cursor.complete(CaveColumnData.emptyScanned(
                            cursor.minimumY, cursor.minimumY, true));
                    break;
                }
                int y = cursor.y;
                byte sectionKind = cursor.context.sectionKind(
                        level, y, classifier);
                int sectionBottom = cursor.context.sectionBottom(level, y);
                steps++;
                if (sectionKind == CaveTileScanContext.ALL_AIR) {
                    telemetry.recordAirSectionSkip();
                    cursor.y = sectionBottom - 1;
                    continue;
                }
                if (sectionKind == CaveTileScanContext.ALL_SOLID_FAST) {
                    cursor.beginBody(y - 1);
                } else {
                    cursor.probe.set(cursor.blockX, y, cursor.blockZ);
                    BlockState state = readState(level, cursor.probe);
                    MapVisualClassifier.VisualInfo visual =
                            visualClassifier.info(state);
                    boolean collisionEmpty = classifier.classify(state)
                            == CaveStateClassifier.DYNAMIC
                            && classifier.isCollisionEmpty(
                                    level, cursor.probe, state);
                    if (CaveProjectionSemantics.isTerrainEntry(
                            state, visual, collisionEmpty)) {
                        cursor.beginBody(y - 1);
                    } else {
                        cursor.y--;
                    }
                }
                if (cursor.phase == CaveColumnScanCursor.Phase.SCAN_BODY
                        && cursor.startY <= cursor.minimumY) {
                    cursor.complete(CaveColumnData.emptyScanned(
                            cursor.minimumY, cursor.startY, true));
                }
                continue;
            }

            if (cursor.y < cursor.minimumY) {
                cursor.complete(cursor.builder.build(cursor.minimumY,
                        cursor.startY, true));
                break;
            }

            int y = cursor.y;
            byte sectionKind = cursor.context.sectionKind(level, y, classifier);
            int sectionBottom = cursor.context.sectionBottom(level, y);
            steps++;

            if (sectionKind == CaveTileScanContext.ALL_AIR) {
                telemetry.recordAirSectionSkip();
                if (!cursor.inOpenRun) cursor.beginRun(y);
                cursor.y = sectionBottom - 1;
                continue;
            }

            if (sectionKind == CaveTileScanContext.ALL_SOLID_FAST) {
                telemetry.recordSolidSectionSkip();
                if (cursor.inOpenRun) {
                    /*
                     * The current Y is the first solid floor below the open interval.
                     * Resolve exactly this boundary once, then skip the remaining
                     * all-solid section in one step.
                     */
                    cursor.probe.set(cursor.blockX, y, cursor.blockZ);
                    BlockState floor = readState(level, cursor.probe);
                    int color = colors.resolveDense(level, cursor.probe, floor,
                            Integer.MIN_VALUE, 0);
                    byte flags = cursor.runHadWater
                            ? CaveColumnData.FLAG_WATER : 0;
                    if (cursor.runHadOtherFluid) flags |= CaveColumnData.FLAG_FLUID;
                    if (floor.getLightEmission() > 0) {
                        flags |= CaveColumnData.FLAG_EMISSIVE;
                    }
                    cursor.builder.add(cursor.runTopY, y, color, flags,
                            cursor.runFluidColor, cursor.runFluidAlpha,
                            cursor.runFluidY, cursor.runFluidLight,
                            cursor.runFluidDepth, cursor.runFluidEmissive
                                    ? CaveColumnData.FLUID_FLAG_EMISSIVE : 0,
                            cursor.runEmissiveColor,
                            cursor.runEmissiveAlpha, cursor.runEmissiveY,
                            cursor.runEmissiveLight);
                    cursor.resetRun();
                }
                cursor.y = sectionBottom - 1;
                continue;
            }

            cursor.probe.set(cursor.blockX, y, cursor.blockZ);
            BlockState state = readState(level, cursor.probe);
            byte kind = classifier.classify(state);

            if (kind == CaveStateClassifier.WATER) {
                if (!cursor.inOpenRun) {
                    cursor.beginRun(y);
                    cursor.waterTopY = y;
                    cursor.runHadWater = true;
                }
                if (!cursor.runHadWater) {
                    cursor.runHadWater = true;
                    cursor.waterTopY = y;
                }
                cursor.waterDepth++;
                captureFluid(level, cursor, state, y);
                cursor.y--;
                continue;
            }

            if (kind == CaveStateClassifier.OTHER_FLUID) {
                /* Xaero treats fluid below the terrain roof as an overlay/open
                 * cavity and continues to the solid floor. PASS109 instead made
                 * the fluid block itself the archived floor, producing lava/water
                 * sheets and authority-dependent Full Cave geometry. */
                if (!cursor.inOpenRun) cursor.beginRun(y);
                cursor.runHadOtherFluid = true;
                captureFluid(level, cursor, state, y);
                cursor.y--;
                continue;
            }

            if (kind == CaveStateClassifier.AIR) {
                if (!cursor.inOpenRun) cursor.beginRun(y);
                cursor.y--;
                continue;
            }

            /*
             * Xaero's cave writer lets only real air/fluid start an open run.
             * A rail, torch, flower, glass pane or another collision-empty state
             * may live inside an already-open cave, but it must never manufacture
             * a new cave opening while the scan is still inside solid terrain.
             */
            if (cursor.inOpenRun) {
                MapVisualClassifier.VisualInfo visual = visualClassifier.info(state);
                boolean collisionEmpty = kind == CaveStateClassifier.DYNAMIC
                        && classifier.isCollisionEmpty(level, cursor.probe, state);
                if (CaveProjectionSemantics.isOpenDecoration(
                        state, visual, collisionEmpty)) {
                    if (visual.emissive() && cursor.runEmissiveColor == 0) {
                        int overlay = colors.resolveDense(
                                level, cursor.probe, state,
                                Integer.MIN_VALUE, 0);
                        if (overlay != 0) {
                            cursor.runEmissiveColor = overlay;
                            cursor.runEmissiveAlpha = visual.overlayOpacity();
                            cursor.runEmissiveY = y;
                            cursor.runEmissiveLight = Math.max(
                                    state.getLightEmission(), level.getBrightness(
                                            LightLayer.BLOCK, cursor.probe));
                        }
                    }
                    cursor.y--;
                    continue;
                }
            }

            if (cursor.inOpenRun) {
                int color = colors.resolveDense(level, cursor.probe, state,
                        Integer.MIN_VALUE, 0);
                byte flags = cursor.runHadWater ? CaveColumnData.FLAG_WATER : 0;
                if (cursor.runHadOtherFluid) flags |= CaveColumnData.FLAG_FLUID;
                if (state.getLightEmission() > 0) {
                    flags |= CaveColumnData.FLAG_EMISSIVE;
                }
                cursor.builder.add(cursor.runTopY, y, color, flags,
                        cursor.runFluidColor, cursor.runFluidAlpha,
                        cursor.runFluidY, cursor.runFluidLight,
                        cursor.runFluidDepth, cursor.runFluidEmissive
                                ? CaveColumnData.FLUID_FLAG_EMISSIVE : 0,
                        cursor.runEmissiveColor,
                        cursor.runEmissiveAlpha, cursor.runEmissiveY,
                        cursor.runEmissiveLight);
                cursor.resetRun();
            }
            cursor.y--;
        }

        cursor.lastSliceSteps = steps;
        return cursor.phase == CaveColumnScanCursor.Phase.COMPLETE
                ? ScanSliceResult.COMPLETE : ScanSliceResult.PAUSED;
    }

    private BlockState readState(Level level, BlockPos pos) {
        telemetry.recordBlockStateRead();
        return level.getBlockState(pos);
    }

    private void captureFluid(Level level, CaveColumnScanCursor cursor,
            BlockState state, int y) {
        int fluidColor = colors.resolveDenseFluid(level, cursor.probe, state);
        if (fluidColor != 0 && cursor.runFluidColor == 0) {
            cursor.runFluidColor = fluidColor;
            cursor.runFluidAlpha = visualClassifier.fluidOverlayOpacity(state);
            cursor.runFluidY = y;
            cursor.runFluidLight = Math.max(state.getLightEmission(),
                    level.getBrightness(LightLayer.BLOCK, cursor.probe));
        }
        cursor.runFluidDepth++;
        if (state.getLightEmission() > 0
                || visualClassifier.info(state).emissive()) {
            cursor.runFluidEmissive = true;
        }
    }
}
