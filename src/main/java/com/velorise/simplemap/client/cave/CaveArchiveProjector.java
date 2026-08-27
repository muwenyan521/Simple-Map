package com.velorise.simplemap.client.cave;

/**
 * Projects a display tile from the colour-schema-isolated vertical cave archive.
 *
 * <p>The archive stores geometry plus the raw material/tint colour selected by the
 * current block-colour mode. Final depth, light, Accurate filtering and colour
 * profile are still applied by {@link CavePageStyler}. This path touches at most
 * the archived cavity runs for 256 columns. It performs no Anvil read, palette
 * decode, block-state lookup or collision/colour hook.</p>
 */
final class CaveArchiveProjector {
    private CaveArchiveProjector() {
    }

    static DenseCaveTile project(CaveChunkTile.Snapshot snapshot,
            CaveView view, int projectionTopY, DenseCaveTile.Source source) {
        if (snapshot == null) return null;
        CaveView effectiveView = view == null ? CaveView.FULL : view;
        int minimumY = effectiveView == CaveView.FULL ? Integer.MIN_VALUE
                : projectionTopY - CaveDisplayProjector.LAYER_DEPTH + 1;
        int maximumY = effectiveView == CaveView.FULL ? Integer.MAX_VALUE
                : projectionTopY;
        DenseCaveTile.Builder builder = new DenseCaveTile.Builder();
        CaveColumnData[] columns = snapshot.columns();

        /*
         * PASS104: every source authority must implement the same vertical
         * projection. The old archive path seeded a dominant Y per 16x16 tile and
         * then followed neighbour floors in a serpentine raster. Adjacent tiles
         * could therefore choose different cave levels and render visible squares.
         * Live/Xaero semantics are column-local: descend from the roof (or Top-Y)
         * and take the first visible cavity floor.
         */
        for (int z = 0; z < DenseCaveTile.SIZE; z++) {
            for (int x = 0; x < DenseCaveTile.SIZE; x++) {
                int index = z * DenseCaveTile.SIZE + x;
                builder.beginColumn();
                CaveColumnData column = columns[index];
                int run = column == null ? -1 : effectiveView == CaveView.FULL
                        ? column.firstVisibleFullIndex()
                        : column.firstVisibleLayeredIndex(maximumY, minimumY);
                if (run < 0) {
                    builder.set(x, z, 0, 0, 0, (byte) 0, 0);
                    continue;
                }
                builder.set(x, z, column.color(run),
                        column.bottomY(run), column.topY(run),
                        column.flags(run), 15);
            }
        }
        return builder.buildOwned(snapshot.chunkX(), snapshot.chunkZ(), effectiveView,
                projectionTopY, projectionTopY, snapshot.revision(), source);
    }

}
