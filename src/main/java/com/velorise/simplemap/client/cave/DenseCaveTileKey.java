package com.velorise.simplemap.client.cave;

/**
 * Exact dense projection identity.
 *
 * <p>{@code bandY} is only a retention/eviction group. Layered presentation
 * authority is identified by {@code projectionTopY}; collapsing that value into
 * the 16-block band caused neighbouring AUTO slices to replace one another.</p>
 */
record DenseCaveTileKey(int chunkX, int chunkZ, CaveView view,
        int bandY, int projectionTopY) {
    DenseCaveTileKey {
        view = view == null ? CaveView.FULL : view;
        if (view == CaveView.FULL) {
            bandY = Integer.MIN_VALUE;
            projectionTopY = Integer.MIN_VALUE;
        } else {
            bandY = DenseCaveTile.normalizeLayer(view, bandY);
        }
    }

    static DenseCaveTileKey of(int chunkX, int chunkZ, CaveView view,
            int requestedTopY) {
        CaveView effectiveView = view == null ? CaveView.FULL : view;
        return new DenseCaveTileKey(chunkX, chunkZ, effectiveView,
                DenseCaveTile.normalizeLayer(effectiveView, requestedTopY),
                effectiveView == CaveView.FULL
                        ? Integer.MIN_VALUE : requestedTopY);
    }

    static DenseCaveTileKey of(DenseCaveTile tile) {
        return new DenseCaveTileKey(tile.chunkX(), tile.chunkZ(), tile.view(),
                tile.layerY(), tile.projectionTopY());
    }

    boolean sameBand(DenseCaveTileKey other) {
        return other != null && chunkX == other.chunkX && chunkZ == other.chunkZ
                && view == other.view && bandY == other.bandY;
    }
}
