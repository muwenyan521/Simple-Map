package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.FullCaveMapManager;

import java.util.Arrays;

/**
 * Immutable 16x16 cave projection with material and overlay metadata kept apart.
 *
 * <p>Each pixel stores one opaque base plus up to three ordered transparent
 * layers. Layers are recorded top-to-bottom and styled/composited later at page
 * build time, so lighting, depth and colour-profile changes do not require a
 * chunk rescan. Extra lower layers are folded into the third slot.</p>
 */
public final class DenseCaveTile {
    public static final int SIZE = 16;
    public static final int COLUMN_COUNT = SIZE * SIZE;
    public static final int MAX_OVERLAYS = 3;
    public static final int OVERLAY_ENTRY_COUNT = COLUMN_COUNT * MAX_OVERLAYS;

    public static final byte FLAG_WATER = 1;
    public static final byte FLAG_FLUID = 1 << 1;
    /** Base material emits light. Overlay emission is stored per overlay entry. */
    public static final byte FLAG_EMISSIVE = 1 << 2;
    public static final byte FLAG_OVERLAY = 1 << 3;
    public static final byte FLAG_PRELIT_LEGACY = 1 << 4;

    public static final byte OVERLAY_EMISSIVE = 1;
    public static final byte OVERLAY_FLUID = 1 << 1;

    public enum Source {
        DISK(0),
        ARCHIVE_FALLBACK(1),
        WORLD_SAVE(2),
        LIVE(3);

        private final int rank;

        Source(int rank) {
            this.rank = rank;
        }

        int rank() {
            return rank;
        }
    }

    private final int chunkX;
    private final int chunkZ;
    private final CaveView view;
    private final int layerY;
    /** Exact Top-Y used to project this tile inside its retained 16-block band. */
    private final int projectionTopY;
    private final long revision;
    private final Source source;
    private final int[] baseColors;
    private final short[] floorY;
    private final short[] topY;
    private final byte[] flags;
    private final byte[] baseLight;
    private final byte[] overlayCounts;
    private final int[] overlayColors;
    private final byte[] overlayAlpha;
    private final short[] overlayY;
    private final byte[] overlayLight;
    private final byte[] overlayFlags;
    private final int populatedColumns;

    DenseCaveTile(int chunkX, int chunkZ, CaveView view, int layerY,
            int projectionTopY, long revision, Source source, int[] baseColors, short[] floorY,
            short[] topY, byte[] flags, byte[] baseLight, byte[] overlayCounts,
            int[] overlayColors, byte[] overlayAlpha, short[] overlayY,
            byte[] overlayLight, byte[] overlayFlags,
            int populatedColumns, boolean trustedArrays) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.view = view;
        this.layerY = normalizeLayer(view, layerY);
        this.projectionTopY = view == CaveView.FULL ? Integer.MIN_VALUE : projectionTopY;
        this.revision = CaveCacheSchema.canonicalContentRevision(revision);
        this.source = source == null ? Source.DISK : source;
        this.baseColors = trustedArrays ? baseColors : Arrays.copyOf(baseColors, COLUMN_COUNT);
        this.floorY = trustedArrays ? floorY : Arrays.copyOf(floorY, COLUMN_COUNT);
        this.topY = trustedArrays ? topY : Arrays.copyOf(topY, COLUMN_COUNT);
        this.flags = trustedArrays ? flags : Arrays.copyOf(flags, COLUMN_COUNT);
        this.baseLight = trustedArrays ? baseLight : Arrays.copyOf(baseLight, COLUMN_COUNT);
        this.overlayCounts = trustedArrays ? overlayCounts : Arrays.copyOf(overlayCounts, COLUMN_COUNT);
        this.overlayColors = trustedArrays ? overlayColors : Arrays.copyOf(overlayColors, OVERLAY_ENTRY_COUNT);
        this.overlayAlpha = trustedArrays ? overlayAlpha : Arrays.copyOf(overlayAlpha, OVERLAY_ENTRY_COUNT);
        this.overlayY = trustedArrays ? overlayY : Arrays.copyOf(overlayY, OVERLAY_ENTRY_COUNT);
        this.overlayLight = trustedArrays ? overlayLight : Arrays.copyOf(overlayLight, OVERLAY_ENTRY_COUNT);
        this.overlayFlags = trustedArrays ? overlayFlags : Arrays.copyOf(overlayFlags, OVERLAY_ENTRY_COUNT);
        this.populatedColumns = Math.max(0, Math.min(COLUMN_COUNT, populatedColumns));
    }

    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public CaveView view() { return view; }
    public int layerY() { return layerY; }
    public int projectionTopY() { return projectionTopY; }
    public long revision() { return revision; }
    public Source source() { return source; }
    public int populatedColumns() { return populatedColumns; }
    public boolean hasContent() { return populatedColumns > 0; }

    /** Compatibility colour composed without depth/light styling. */
    public int color(int localX, int localZ) {
        int pixel = index(localX, localZ);
        int color = baseColors[pixel];
        int count = Byte.toUnsignedInt(overlayCounts[pixel]);
        for (int layer = count - 1; layer >= 0; layer--) {
            int entry = overlayIndex(pixel, layer);
            int overlay = overlayColors[entry];
            if (overlay != 0) color = blendAbgr(color, overlay,
                    Byte.toUnsignedInt(overlayAlpha[entry]));
        }
        return color;
    }

    public int baseColor(int localX, int localZ) {
        return baseColors[index(localX, localZ)];
    }

    public short floorY(int localX, int localZ) { return floorY[index(localX, localZ)]; }
    public short topY(int localX, int localZ) { return topY[index(localX, localZ)]; }
    public byte flags(int localX, int localZ) { return flags[index(localX, localZ)]; }
    public byte light(int localX, int localZ) { return baseLight[index(localX, localZ)]; }
    public int overlayCount(int localX, int localZ) {
        return Byte.toUnsignedInt(overlayCounts[index(localX, localZ)]);
    }
    public int overlayColor(int localX, int localZ, int layer) {
        return overlayColors[overlayIndex(index(localX, localZ), layer)];
    }
    public byte overlayAlpha(int localX, int localZ, int layer) {
        return overlayAlpha[overlayIndex(index(localX, localZ), layer)];
    }
    public short overlayY(int localX, int localZ, int layer) {
        return overlayY[overlayIndex(index(localX, localZ), layer)];
    }
    public byte overlayLight(int localX, int localZ, int layer) {
        return overlayLight[overlayIndex(index(localX, localZ), layer)];
    }
    public byte overlayFlags(int localX, int localZ, int layer) {
        return overlayFlags[overlayIndex(index(localX, localZ), layer)];
    }

    int[] colorsUnsafe() { return baseColors; }
    short[] floorUnsafe() { return floorY; }
    short[] topUnsafe() { return topY; }
    byte[] flagsUnsafe() { return flags; }
    byte[] lightUnsafe() { return baseLight; }
    byte[] overlayCountsUnsafe() { return overlayCounts; }
    int[] overlayColorsUnsafe() { return overlayColors; }
    byte[] overlayAlphaUnsafe() { return overlayAlpha; }
    short[] overlayYUnsafe() { return overlayY; }
    byte[] overlayLightUnsafe() { return overlayLight; }
    byte[] overlayFlagsUnsafe() { return overlayFlags; }

    /**
     * Compares immutable projection payload while deliberately ignoring the
     * monotonic source revision. Re-decoding the same Anvil chunk creates a new
     * source object/revision; treating that as new cave geometry repeatedly
     * invalidated exact pages even though every pixel was identical.
     */
    boolean sameProjectionContent(DenseCaveTile other) {
        if (other == null || chunkX != other.chunkX || chunkZ != other.chunkZ
                || view != other.view || layerY != other.layerY
                || projectionTopY != other.projectionTopY
                || populatedColumns != other.populatedColumns) return false;
        return Arrays.equals(baseColors, other.baseColors)
                && Arrays.equals(floorY, other.floorY)
                && Arrays.equals(topY, other.topY)
                && Arrays.equals(flags, other.flags)
                && Arrays.equals(baseLight, other.baseLight)
                && Arrays.equals(overlayCounts, other.overlayCounts)
                && Arrays.equals(overlayColors, other.overlayColors)
                && Arrays.equals(overlayAlpha, other.overlayAlpha)
                && Arrays.equals(overlayY, other.overlayY)
                && Arrays.equals(overlayLight, other.overlayLight)
                && Arrays.equals(overlayFlags, other.overlayFlags);
    }

    public static int index(int localX, int localZ) {
        return (localZ & 15) * SIZE + (localX & 15);
    }

    public static int overlayIndex(int pixelIndex, int layer) {
        return pixelIndex * MAX_OVERLAYS + Math.max(0, Math.min(MAX_OVERLAYS - 1, layer));
    }

    public static int normalizeLayer(CaveView view, int layerY) {
        return CaveLayerBand.key(view, layerY);
    }

    public static final class Builder {
        private final int[] baseColors = new int[COLUMN_COUNT];
        private final short[] floorY = new short[COLUMN_COUNT];
        private final short[] topY = new short[COLUMN_COUNT];
        private final byte[] flags = new byte[COLUMN_COUNT];
        private final byte[] baseLight = new byte[COLUMN_COUNT];
        private final byte[] overlayCounts = new byte[COLUMN_COUNT];
        private final int[] overlayColors = new int[OVERLAY_ENTRY_COUNT];
        private final byte[] overlayAlpha = new byte[OVERLAY_ENTRY_COUNT];
        private final short[] overlayY = new short[OVERLAY_ENTRY_COUNT];
        private final byte[] overlayLight = new byte[OVERLAY_ENTRY_COUNT];
        private final byte[] overlayFlags = new byte[OVERLAY_ENTRY_COUNT];

        private final int[] scratchColors = new int[MAX_OVERLAYS];
        private final byte[] scratchAlpha = new byte[MAX_OVERLAYS];
        private final short[] scratchY = new short[MAX_OVERLAYS];
        private final byte[] scratchLight = new byte[MAX_OVERLAYS];
        private final byte[] scratchFlags = new byte[MAX_OVERLAYS];
        private int scratchCount;
        private int populated;

        public Builder() { reset(); }

        /** Seeds a patch transaction from an already committed dense LIVE tile. */
        public Builder(DenseCaveTile seed) {
            if (seed == null) {
                reset();
                return;
            }
            System.arraycopy(seed.baseColors, 0, baseColors, 0, COLUMN_COUNT);
            System.arraycopy(seed.floorY, 0, floorY, 0, COLUMN_COUNT);
            System.arraycopy(seed.topY, 0, topY, 0, COLUMN_COUNT);
            System.arraycopy(seed.flags, 0, flags, 0, COLUMN_COUNT);
            System.arraycopy(seed.baseLight, 0, baseLight, 0, COLUMN_COUNT);
            System.arraycopy(seed.overlayCounts, 0, overlayCounts, 0, COLUMN_COUNT);
            System.arraycopy(seed.overlayColors, 0, overlayColors, 0, OVERLAY_ENTRY_COUNT);
            System.arraycopy(seed.overlayAlpha, 0, overlayAlpha, 0, OVERLAY_ENTRY_COUNT);
            System.arraycopy(seed.overlayY, 0, overlayY, 0, OVERLAY_ENTRY_COUNT);
            System.arraycopy(seed.overlayLight, 0, overlayLight, 0, OVERLAY_ENTRY_COUNT);
            System.arraycopy(seed.overlayFlags, 0, overlayFlags, 0, OVERLAY_ENTRY_COUNT);
            populated = seed.populatedColumns;
            beginColumn();
        }

        public void reset() {
            Arrays.fill(baseColors, 0);
            Arrays.fill(floorY, FullCaveMapManager.NO_SURFACE);
            Arrays.fill(topY, FullCaveMapManager.NO_SURFACE);
            Arrays.fill(flags, (byte) 0);
            Arrays.fill(baseLight, (byte) 0);
            Arrays.fill(overlayCounts, (byte) 0);
            Arrays.fill(overlayColors, 0);
            Arrays.fill(overlayAlpha, (byte) 0);
            Arrays.fill(overlayY, FullCaveMapManager.NO_SURFACE);
            Arrays.fill(overlayLight, (byte) 0);
            Arrays.fill(overlayFlags, (byte) 0);
            populated = 0;
            beginColumn();
        }

        public void beginColumn() {
            scratchCount = 0;
            Arrays.fill(scratchColors, 0);
            Arrays.fill(scratchAlpha, (byte) 0);
            Arrays.fill(scratchY, FullCaveMapManager.NO_SURFACE);
            Arrays.fill(scratchLight, (byte) 0);
            Arrays.fill(scratchFlags, (byte) 0);
        }

        /** Adds a top-to-bottom visual layer without allocating per column. */
        public void addOverlay(int color, int alpha, int y, int light, byte layerFlags) {
            if (color == 0 || alpha <= 0) return;
            int clampedAlpha = Math.max(0, Math.min(232, alpha));
            int clampedLight = Math.max(0, Math.min(15, light));
            if (scratchCount < MAX_OVERLAYS) {
                int slot = scratchCount++;
                scratchColors[slot] = color;
                scratchAlpha[slot] = (byte) clampedAlpha;
                scratchY[slot] = clampShort(y);
                scratchLight[slot] = (byte) clampedLight;
                scratchFlags[slot] = layerFlags;
                return;
            }

            // Keep the top two exact. Fold all deeper material behind the third
            // layer so deep water/glass stacks still increase opacity.
            int slot = MAX_OVERLAYS - 1;
            int existingAlpha = Byte.toUnsignedInt(scratchAlpha[slot]);
            scratchColors[slot] = blendAbgr(color, scratchColors[slot], existingAlpha);
            scratchAlpha[slot] = (byte) combineAlpha(clampedAlpha, existingAlpha);
            scratchLight[slot] = (byte) Math.max(clampedLight,
                    Byte.toUnsignedInt(scratchLight[slot]));
            scratchFlags[slot] |= layerFlags;
        }

        public void set(int localX, int localZ, int baseColor,
                int floor, int top, byte pixelFlags, int pixelLight) {
            int pixel = DenseCaveTile.index(localX, localZ);
            if (baseColors[pixel] == 0 && baseColor != 0) populated++;
            if (baseColors[pixel] != 0 && baseColor == 0) populated--;
            baseColors[pixel] = baseColor;
            floorY[pixel] = baseColor == 0 ? FullCaveMapManager.NO_SURFACE : clampShort(floor);
            topY[pixel] = baseColor == 0 ? FullCaveMapManager.NO_SURFACE : clampShort(top);
            flags[pixel] = baseColor == 0 ? 0 : pixelFlags;
            baseLight[pixel] = baseColor == 0 ? 0
                    : (byte) Math.max(0, Math.min(15, pixelLight));
            int count = baseColor == 0 ? 0 : scratchCount;
            overlayCounts[pixel] = (byte) count;
            int target = pixel * MAX_OVERLAYS;
            for (int layer = 0; layer < MAX_OVERLAYS; layer++) {
                if (layer < count) {
                    overlayColors[target + layer] = scratchColors[layer];
                    overlayAlpha[target + layer] = scratchAlpha[layer];
                    overlayY[target + layer] = scratchY[layer];
                    overlayLight[target + layer] = scratchLight[layer];
                    overlayFlags[target + layer] = scratchFlags[layer];
                } else {
                    overlayColors[target + layer] = 0;
                    overlayAlpha[target + layer] = 0;
                    overlayY[target + layer] = FullCaveMapManager.NO_SURFACE;
                    overlayLight[target + layer] = 0;
                    overlayFlags[target + layer] = 0;
                }
            }
        }

        public DenseCaveTile build(int chunkX, int chunkZ, CaveView view,
                int layerY, int projectionTopY, long revision, Source source) {
            return new DenseCaveTile(chunkX, chunkZ, view, layerY, projectionTopY,
                    revision, source,
                    Arrays.copyOf(baseColors, COLUMN_COUNT),
                    Arrays.copyOf(floorY, COLUMN_COUNT),
                    Arrays.copyOf(topY, COLUMN_COUNT),
                    Arrays.copyOf(flags, COLUMN_COUNT),
                    Arrays.copyOf(baseLight, COLUMN_COUNT),
                    Arrays.copyOf(overlayCounts, COLUMN_COUNT),
                    Arrays.copyOf(overlayColors, OVERLAY_ENTRY_COUNT),
                    Arrays.copyOf(overlayAlpha, OVERLAY_ENTRY_COUNT),
                    Arrays.copyOf(overlayY, OVERLAY_ENTRY_COUNT),
                    Arrays.copyOf(overlayLight, OVERLAY_ENTRY_COUNT),
                    Arrays.copyOf(overlayFlags, OVERLAY_ENTRY_COUNT),
                    populated, true);
        }

        /**
         * Transfers this one-shot builder's backing arrays directly into the
         * immutable tile. Cave projection builders are discarded immediately after
         * publication (or nulled before a follow-up transaction), so copying eleven
         * arrays only doubled short-lived allocation and GC pressure.
         *
         * <p>The builder must not be mutated after this call.</p>
         */
        public DenseCaveTile buildOwned(int chunkX, int chunkZ, CaveView view,
                int layerY, int projectionTopY, long revision, Source source) {
            return new DenseCaveTile(chunkX, chunkZ, view, layerY, projectionTopY,
                    revision, source,
                    baseColors, floorY, topY, flags, baseLight, overlayCounts,
                    overlayColors, overlayAlpha, overlayY, overlayLight,
                    overlayFlags, populated, true);
        }
    }

    static DenseCaveTile fromStored(int chunkX, int chunkZ, CaveView view,
            int layerY, int projectionTopY, long revision, int[] baseColors, short[] floorY,
            short[] topY, byte[] flags, byte[] baseLight, byte[] overlayCounts,
            int[] overlayColors, byte[] overlayAlpha, short[] overlayY,
            byte[] overlayLight, byte[] overlayFlags) {
        int populated = 0;
        for (int color : baseColors) if (color != 0) populated++;
        return new DenseCaveTile(chunkX, chunkZ, view, layerY, projectionTopY,
                revision, Source.DISK, baseColors, floorY, topY, flags, baseLight,
                overlayCounts, overlayColors, overlayAlpha, overlayY,
                overlayLight, overlayFlags, populated, true);
    }

    private static int combineAlpha(int existing, int added) {
        int current = Math.max(0, Math.min(255, existing));
        int next = Math.max(0, Math.min(255, added));
        return Math.min(232, current + (255 - current) * next / 255);
    }

    private static int blendAbgr(int base, int overlay, int alpha) {
        if (base == 0) return overlay;
        int amount = Math.max(0, Math.min(255, alpha));
        int inverse = 255 - amount;
        int red = ((base & 0xFF) * inverse + (overlay & 0xFF) * amount) / 255;
        int green = (((base >>> 8) & 0xFF) * inverse
                + ((overlay >>> 8) & 0xFF) * amount) / 255;
        int blue = (((base >>> 16) & 0xFF) * inverse
                + ((overlay >>> 16) & 0xFF) * amount) / 255;
        return 0xFF000000 | (blue << 16) | (green << 8) | red;
    }

    private static short clampShort(int value) {
        return (short) Math.max(Short.MIN_VALUE + 1,
                Math.min(Short.MAX_VALUE, value));
    }
}
