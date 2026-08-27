package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapCancellationToken;
import com.velorise.simplemap.client.MapVisualClassifier;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dense cave projector shared by live chunks and world-save NBT.
 *
 * <p>Geometry comes from {@link CaveStateClassifier}; visual participation comes
 * from the same {@link MapVisualClassifier} used by the surface map. Base material
 * and up to three ordered overlays remain separate until page styling.</p>
 */
final class CaveDisplayProjector {
    static final int LAYER_DEPTH = 32;

    private final CaveStateClassifier geometry = CaveStateClassifier.getInstance();
    private final MapVisualClassifier visuals = MapVisualClassifier.getInstance();

    DenseCaveTile project(ChunkSource source, CaveView view, int layerY,
            long revision, DenseCaveTile.Source tileSource) {
        return project(source, view, layerY, revision, tileSource,
                new MapCancellationToken(null));
    }

    DenseCaveTile project(ChunkSource source, CaveView view, int layerY,
            long revision, DenseCaveTile.Source tileSource,
            MapCancellationToken token) {
        MapCancellationToken effectiveToken = token == null
                ? new MapCancellationToken(null) : token;
        DenseCaveTile.Builder builder = new DenseCaveTile.Builder();
        for (int localZ = 0; localZ < 16; localZ++) {
            effectiveToken.checkpoint("cave-project-row-" + localZ);
            for (int localX = 0; localX < 16; localX++) {
                projectColumn(source, view, layerY, localX, localZ, builder);
            }
        }
        effectiveToken.checkpoint("cave-project-tile-ready");
        return builder.buildOwned(source.chunkX(), source.chunkZ(), view,
                layerY, layerY, revision, tileSource);
    }

    void projectColumn(ChunkSource source, CaveView view, int layerY,
            int localX, int localZ, DenseCaveTile.Builder output) {
        ColumnCursor cursor = beginColumn(source, view, layerY,
                localX, localZ, output);
        while (!projectColumnSlice(source, localX, localZ, output, cursor,
                Long.MAX_VALUE, Integer.MAX_VALUE)) {
            // The compatibility/worker path is intentionally unbounded.
        }
    }

    /**
     * Starts one resumable live-column projection. A full cave column can touch
     * hundreds of block states and invoke modded colour/collision hooks. Treating
     * that as one indivisible operation allowed a nominal 1 ms foreground slice to
     * hold the render thread for 80-100 ms. The display scheduler now resumes this
     * cursor in small vertical bursts and checks its physical-frame deadline between
     * them; worker-side callers retain the unbounded wrapper above.
     */
    ColumnCursor beginColumn(ChunkSource source, CaveView view, int layerY,
            int localX, int localZ, DenseCaveTile.Builder output) {
        boolean full = view == CaveView.FULL;
        int minimumY = source.minimumY();
        int maximumY = source.maximumY() - 1;
        int surfaceY = clamp(source.surfaceY(localX, localZ), minimumY, maximumY);
        int highY = full
                ? clamp(surfaceY + 3, minimumY, maximumY)
                : clamp(layerY, minimumY, maximumY);
        int lowY = full
                ? minimumY
                : Math.max(minimumY, highY + 1 - LAYER_DEPTH);
        /*
         * A Layered Top-Y above the terrain roof must not treat the sky-to-ground
         * transition as a cave. Enter the first opaque terrain transaction exactly
         * as Full Cave does, then accept only an air/fluid cavity below it. This is
         * the cave-start rule Xaero applies before producing a cave-layer texture.
         */
        boolean startsAboveTerrain = !full && highY >= surfaceY;
        output.beginColumn();
        return new ColumnCursor(full || startsAboveTerrain, highY, lowY);
    }

    boolean projectColumnSlice(ChunkSource source, int localX, int localZ,
            DenseCaveTile.Builder output, ColumnCursor cursor,
            long deadlineNanos, int maximumSteps) {
        if (cursor == null || cursor.done) return true;
        int steps = 0;
        int safeMaximum = Math.max(1, maximumSteps);
        while (cursor.y >= cursor.lowY && steps < safeMaximum
                && System.nanoTime() < deadlineNanos) {
            int y = cursor.y;
            steps++;
            byte sectionKind = source.sectionKind(localX, y, localZ);
            int sectionBottom = Math.max(cursor.lowY, source.sectionBottom(y));
            if (sectionKind == CaveTileScanContext.ALL_AIR) {
                if (!cursor.underAir) {
                    cursor.openTopY = y;
                }
                cursor.underAir = true;
                cursor.y = sectionBottom - 1;
                continue;
            }
            if (sectionKind == CaveTileScanContext.ALL_SOLID_FAST) {
                if (cursor.shouldEnterGround) {
                    cursor.shouldEnterGround = false;
                    cursor.underAir = false;
                    cursor.resetCavity();
                    output.beginColumn();
                    cursor.y = sectionBottom - 1;
                    continue;
                }
                if (!cursor.underAir) {
                    cursor.y = sectionBottom - 1;
                    continue;
                }
                BlockState state = source.stateAt(localX, y, localZ);
                BlockState visualState = source.visualStateAt(
                        localX, y, localZ, state);
                int color = source.resolveBlockColor(
                        visualState, localX, y, localZ);
                if (color != 0) {
                    byte flags = visualState.getLightEmission() > 0
                            ? DenseCaveTile.FLAG_EMISSIVE : 0;
                    int floorLight = Math.max(visualState.getLightEmission(),
                            source.lightAt(localX, y, localZ));
                    output.set(localX, localZ, color, y,
                            Math.max(y, cursor.openTopY), flags, floorLight);
                    cursor.done = true;
                    return true;
                }
                cursor.underAir = false;
                cursor.y = sectionBottom - 1;
                continue;
            }
            BlockState state = source.stateAt(localX, y, localZ);
            BlockState visualState = source.visualStateAt(localX, y, localZ, state);
            CaveStateClassifier.StateInfo geometryInfo = geometry.info(state);
            MapVisualClassifier.VisualInfo visual = visuals.info(visualState);
            byte kind = geometryInfo.kind();
            // Lighting is expensive for live chunks. Most scanned roof/terrain
            // blocks are discarded before they become a visible floor or overlay,
            // so defer block/sky light reads until a pixel is actually emitted.
            int emittedLight = Math.max(state.getLightEmission(),
                    visualState.getLightEmission());

            // Match Xaero's cave-entry semantics: only real air (and fluids below)
            // opens a cavity. A torch, flower, rail, leaf or glass block embedded in
            // terrain must not manufacture an artificial one-pixel cave opening.
            if (state.isAir()) {
                if (!cursor.underAir) {
                    cursor.openTopY = y;
                }
                cursor.underAir = true;
                cursor.y--;
                continue;
            }

            if (visual.fluid()) {
                // A waterlogged state can contribute both its block material and a
                // fluid overlay. Pure fluid states stop here; waterlogged geometry
                // continues through the normal base/overlay classification below.
                if (!cursor.shouldEnterGround) {
                    if (!cursor.underAir) {
                        cursor.openTopY = y;
                    }
                    cursor.underAir = true;
                    int layerColor = source.resolveFluidColor(
                            visualState, localX, y, localZ);
                    byte layerFlags = DenseCaveTile.OVERLAY_FLUID;
                    if (visual.emissive()) layerFlags |= DenseCaveTile.OVERLAY_EMISSIVE;
                    int visibleLayerLight = visibleLayerLight(source,
                            localX, y, localZ, emittedLight);
                    output.addOverlay(layerColor, visuals.fluidOverlayOpacity(visualState), y,
                            visibleLayerLight, layerFlags);
                    cursor.sawOverlay |= layerColor != 0;
                    if (kind == CaveStateClassifier.WATER || visual.water()) {
                        cursor.waterDepth++;
                    } else {
                        cursor.otherFluidDepth++;
                    }
                }
                if (visual.role() == MapVisualClassifier.Role.FLUID_OVERLAY) {
                    cursor.y--;
                    continue;
                }
            }

            // Torches and equivalent modded decorations contribute light but never
            // replace the floor material or open a cavity by themselves.
            if (visual.role() == MapVisualClassifier.Role.INVISIBLE) {
                cursor.y--;
                continue;
            }

            boolean collisionEmpty = kind == CaveStateClassifier.AIR
                    || (kind == CaveStateClassifier.DYNAMIC
                            && geometry.isCollisionEmpty(source.blockGetter(),
                                    source.position(localX, y, localZ), state));
            boolean visualOverlay = visual.role() == MapVisualClassifier.Role.TRANSPARENT_OVERLAY
                    || visual.role() == MapVisualClassifier.Role.EMISSIVE_OVERLAY;

            if (visualOverlay || collisionEmpty) {
                // Like Xaero's loadPixelHelp(), transparent material is collected
                // only after the scan is already under real air/fluid. It is not an
                // air substitute while traversing solid terrain.
                if (cursor.shouldEnterGround || !cursor.underAir) {
                    cursor.y--;
                    continue;
                }
                int layerColor = source.resolveBlockColor(
                        visualState, localX, y, localZ);
                byte layerFlags = visual.emissive()
                        ? DenseCaveTile.OVERLAY_EMISSIVE : 0;
                int visibleLayerLight = visibleLayerLight(source,
                        localX, y, localZ, emittedLight);
                output.addOverlay(layerColor, visual.overlayOpacity(), y,
                        visibleLayerLight, layerFlags);
                cursor.sawOverlay |= layerColor != 0;
                cursor.y--;
                continue;
            }

            if (cursor.shouldEnterGround) {
                // Shared Xaero-style terrain-entry predicate. Live projection,
                // vertical archive and decoded .mca projection must agree here or
                // Full Cave changes layer at source-authority boundaries.
                if (!CaveProjectionSemantics.isTerrainEntry(
                        state, visual, collisionEmpty)) {
                    cursor.y--;
                    continue;
                }
                cursor.shouldEnterGround = false;
                cursor.underAir = false;
                cursor.resetCavity();
                output.beginColumn();
                cursor.y--;
                continue;
            }

            if (!cursor.underAir) {
                cursor.y--;
                continue;
            }

            int color = source.resolveBlockColor(visualState, localX, y, localZ);
            if (color == 0) {
                cursor.sawOverlay = true;
                cursor.y--;
                continue;
            }

            byte flags = 0;
            if (cursor.waterDepth > 0) flags |= DenseCaveTile.FLAG_WATER;
            if (cursor.otherFluidDepth > 0) flags |= DenseCaveTile.FLAG_FLUID;
            if (visual.emissive()) flags |= DenseCaveTile.FLAG_EMISSIVE;
            if (cursor.sawOverlay) flags |= DenseCaveTile.FLAG_OVERLAY;
            int floorLight = Math.max(emittedLight,
                    source.lightAt(localX, y, localZ));
            output.set(localX, localZ, color, y,
                    Math.max(y, cursor.openTopY), flags, floorLight);
            cursor.done = true;
            return true;
        }

        if (cursor.y < cursor.lowY) {
            output.set(localX, localZ, 0, 0, 0, (byte) 0, 0);
            cursor.done = true;
        }
        return cursor.done;
    }

    static final class ColumnCursor {
        private final int lowY;
        private int y;
        private boolean underAir;
        private boolean shouldEnterGround;
        private int openTopY;
        private int waterDepth;
        private int otherFluidDepth;
        private boolean sawOverlay;
        private boolean done;

        private ColumnCursor(boolean full, int highY, int lowY) {
            this.lowY = lowY;
            this.y = highY;
            this.underAir = full;
            this.shouldEnterGround = full;
            this.openTopY = lowY;
        }

        private void resetCavity() {
            waterDepth = 0;
            otherFluidDepth = 0;
            sawOverlay = false;
        }
    }

    private static int visibleLayerLight(ChunkSource source,
            int localX, int y, int localZ, int emittedLight) {
        int blockLight = Math.max(emittedLight,
                source.lightAt(localX, y, localZ));
        if (blockLight >= 12) return blockLight;
        return Math.max(blockLight,
                Math.min(12, source.skyLightAt(localX, y, localZ)));
    }

    interface ChunkSource {
        int chunkX();
        int chunkZ();
        int minimumY();
        /** Exclusive maximum build height. */
        int maximumY();
        int surfaceY(int localX, int localZ);
        BlockState stateAt(int localX, int y, int localZ);
        default byte sectionKind(int y) {
            return CaveTileScanContext.MIXED;
        }
        /**
         * Column-aware section summary. Immutable Anvil sources precompute this once
         * during palette decode, allowing all-air/all-solid 16-block spans to be
         * skipped for every later Full/Layered projection. Live sources can keep the
         * section-wide default.
         */
        default byte sectionKind(int localX, int y, int localZ) {
            return sectionKind(y);
        }
        default int sectionBottom(int y) {
            return Math.floorDiv(y, 16) * 16;
        }
        default BlockState visualStateAt(int localX, int y, int localZ) {
            return stateAt(localX, y, localZ);
        }
        default BlockState visualStateAt(int localX, int y, int localZ,
                BlockState actualState) {
            return visualStateAt(localX, y, localZ);
        }
        net.minecraft.world.level.BlockGetter blockGetter();
        net.minecraft.core.BlockPos position(int localX, int y, int localZ);
        int lightAt(int localX, int y, int localZ);
        int skyLightAt(int localX, int y, int localZ);
        int resolveBlockColor(BlockState state, int localX, int y, int localZ);
        int resolveFluidColor(BlockState state, int localX, int y, int localZ);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
