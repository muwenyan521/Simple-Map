package com.velorise.simplemap.client.cave;

import java.util.Arrays;

/**
 * Immutable primitive cavity archive for one X/Z column.
 *
 * Runs are stored highest-first. Each run describes an open interval whose first
 * solid/fluid-visible floor is {@code bottomY}. Geometry is reusable across cave
 * layers; the surrounding CVR namespace isolates the raw material colours by
 * colour mode/schema. The same archive is reused by
 * Layered and Full Cave projections, so changing Top-Y never re-reads the world.
 */
public final class CaveColumnData {
    public static final byte FLAG_WATER = 1;
    public static final byte FLAG_FLUID = 1 << 1;
    public static final byte FLAG_EMISSIVE = 1 << 2;
    public static final byte FLUID_FLAG_EMISSIVE = 1;
    public static final int MAX_RUNS = 255;

    private static final CaveColumnData EMPTY = new CaveColumnData(
            new short[0], new short[0], new int[0], new byte[0], 0,
            Short.MIN_VALUE, Short.MIN_VALUE, false);

    private final short[] topY;
    private final short[] bottomY;
    private final int[] colors;
    private final byte[] flags;
    /** Semantic presentation facts; never pre-composited into {@link #colors}. */
    private final int[] fluidColors;
    private final byte[] fluidAlpha;
    private final short[] fluidY;
    private final byte[] fluidLight;
    private final byte[] fluidDepth;
    private final byte[] fluidFlags;
    private final int[] emissiveColors;
    private final byte[] emissiveAlpha;
    private final short[] emissiveY;
    private final byte[] emissiveLight;
    private final int count;
    private final short scannedMinimumY;
    private final short scannedMaximumY;
    private final boolean fullHeightComplete;

    public CaveColumnData(short[] topY, short[] bottomY,
            int[] colors, byte[] flags, int count) {
        this(topY, bottomY, colors, flags, count,
                Short.MIN_VALUE, Short.MIN_VALUE, false);
    }

    public CaveColumnData(short[] topY, short[] bottomY,
            int[] colors, byte[] flags, int count,
            int scannedMinimumY, int scannedMaximumY,
            boolean fullHeightComplete) {
        this(topY, bottomY, colors, flags,
                new int[Math.max(0, count)], new byte[Math.max(0, count)],
                new short[Math.max(0, count)], new byte[Math.max(0, count)],
                new byte[Math.max(0, count)], new byte[Math.max(0, count)],
                new int[Math.max(0, count)],
                new byte[Math.max(0, count)], new short[Math.max(0, count)],
                new byte[Math.max(0, count)], count, scannedMinimumY,
                scannedMaximumY, fullHeightComplete);
    }

    public CaveColumnData(short[] topY, short[] bottomY,
            int[] colors, byte[] flags,
            int[] fluidColors, byte[] fluidAlpha, short[] fluidY,
            byte[] fluidLight, byte[] fluidDepth, byte[] fluidFlags,
            int[] emissiveColors, byte[] emissiveAlpha, short[] emissiveY,
            byte[] emissiveLight, int count,
            int scannedMinimumY, int scannedMaximumY,
            boolean fullHeightComplete) {
        int safeCount = Math.max(0, Math.min(MAX_RUNS, count));
        this.topY = Arrays.copyOf(topY, safeCount);
        this.bottomY = Arrays.copyOf(bottomY, safeCount);
        this.colors = Arrays.copyOf(colors, safeCount);
        this.flags = Arrays.copyOf(flags, safeCount);
        this.fluidColors = Arrays.copyOf(fluidColors, safeCount);
        this.fluidAlpha = Arrays.copyOf(fluidAlpha, safeCount);
        this.fluidY = Arrays.copyOf(fluidY, safeCount);
        this.fluidLight = Arrays.copyOf(fluidLight, safeCount);
        this.fluidDepth = Arrays.copyOf(fluidDepth, safeCount);
        this.fluidFlags = Arrays.copyOf(fluidFlags, safeCount);
        this.emissiveColors = Arrays.copyOf(emissiveColors, safeCount);
        this.emissiveAlpha = Arrays.copyOf(emissiveAlpha, safeCount);
        this.emissiveY = Arrays.copyOf(emissiveY, safeCount);
        this.emissiveLight = Arrays.copyOf(emissiveLight, safeCount);
        this.count = safeCount;
        this.scannedMinimumY = clampShort(scannedMinimumY);
        this.scannedMaximumY = clampShort(scannedMaximumY);
        this.fullHeightComplete = fullHeightComplete;
    }

    public static CaveColumnData empty() {
        return EMPTY;
    }

    public static CaveColumnData emptyScanned(int minimumY, int maximumY,
            boolean fullHeightComplete) {
        return new CaveColumnData(new short[0], new short[0], new int[0],
                new byte[0], 0, minimumY, maximumY, fullHeightComplete);
    }

    public int count() {
        return count;
    }

    public short scannedMinimumY() {
        return scannedMinimumY;
    }

    public short scannedMaximumY() {
        return scannedMaximumY;
    }

    public boolean fullHeightComplete() {
        return fullHeightComplete;
    }

    public short topY(int index) {
        return topY[index];
    }

    public short bottomY(int index) {
        return bottomY[index];
    }

    public int color(int index) {
        return colors[index];
    }

    public byte flags(int index) {
        return flags[index];
    }

    public int fluidColor(int index) { return fluidColors[index]; }
    public byte fluidAlpha(int index) { return fluidAlpha[index]; }
    public short fluidY(int index) { return fluidY[index]; }
    public byte fluidLight(int index) { return fluidLight[index]; }
    public byte fluidDepth(int index) { return fluidDepth[index]; }
    public byte fluidFlags(int index) { return fluidFlags[index]; }
    public int emissiveColor(int index) { return emissiveColors[index]; }
    public byte emissiveAlpha(int index) { return emissiveAlpha[index]; }
    public short emissiveY(int index) { return emissiveY[index]; }
    public byte emissiveLight(int index) { return emissiveLight[index]; }

    /**
     * Selects the most representative cavity for the 2D Full Cave projection.
     *
     * A full map cannot draw multiple vertical surfaces into one X/Z pixel. The
     * old implementation always selected run 0 (the highest cavity), which made
     * the map collapse into scattered ceiling cracks. We now score every run by
     * usable cavity height, depth, fluids/emission and continuity with neighbouring
     * pixels. This preserves large connected cave networks much more reliably.
     */
    public int fullIndex(int preferredY, int neighbourY) {
        if (count == 0) return -1;
        int selected = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            int top = topY[i];
            int bottom = bottomY[i];
            int height = Math.max(1, top - bottom);
            int center = (top + bottom) >> 1;

            int score = Math.min(96, height) * 24;
            // Prefer real rooms/tunnels over one-block cracks.
            if (height >= 2) score += 120;
            if (height >= 4) score += 180;
            if (height >= 8) score += 220;

            byte runFlags = flags[i];
            if ((runFlags & FLAG_EMISSIVE) != 0) score += 80;
            if ((runFlags & FLAG_WATER) != 0) score += 28;
            if ((runFlags & FLAG_FLUID) != 0) score += 42;

            // A weak preference prevents the projection from jumping to extreme
            // world-bottom pockets when two candidates are otherwise equivalent.
            if (preferredY != Integer.MIN_VALUE) {
                score -= Math.min(320, Math.abs(center - preferredY) * 2);
            }
            // Continuity is deliberately strong: adjacent columns usually belong
            // to the same cave layer and should form a connected map surface.
            if (neighbourY != Integer.MIN_VALUE) {
                score -= Math.min(1200, Math.abs(bottom - neighbourY) * 18);
            }

            if (score > bestScore) {
                bestScore = score;
                selected = i;
            }
        }
        return selected;
    }

    public int fullIndex() {
        return fullIndex(Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    /**
     * Xaero-style Full Cave projection: select the first underground cavity found
     * while descending from the terrain roof. Runs are archived highest-first, so
     * no graph scoring or player-Y preference is required.
     */
    public int firstVisibleFullIndex() {
        return count == 0 ? -1 : 0;
    }

    /**
     * Xaero-style Layered projection. Starting at Top-Y and scanning downward, the
     * first floor reached inside the selected band becomes the map pixel. A tall
     * cavern whose floor lies below the band is intentionally not projected.
     */
    public int firstVisibleLayeredIndex(int maximumY, int minimumY) {
        for (int i = 0; i < count; i++) {
            int floorY = bottomY[i];
            if (floorY > maximumY) continue;
            if (floorY < minimumY) break;
            if (topY[i] >= minimumY) return i;
        }
        return -1;
    }

    public Candidate firstVisibleFullCandidate() {
        int index = firstVisibleFullIndex();
        return index < 0 ? null : candidate(index);
    }

    public Candidate firstVisibleLayeredCandidate(int maximumY, int minimumY) {
        int index = firstVisibleLayeredIndex(maximumY, minimumY);
        return index < 0 ? null : candidate(index);
    }

    public Candidate fullCandidate() {
        return firstVisibleFullCandidate();
    }

    /**
     * Returns the highest discovered cavity intersecting the selected vertical band.
     * Tall caverns remain visible even when their floor lies below the band, because
     * the run interval itself intersects the requested Top-Y range.
     */
    public int layeredIndex(int maximumY, int minimumY) {
        int selected = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            int top = topY[i];
            int bottom = bottomY[i];
            if (top < minimumY || bottom > maximumY) continue;

            int overlapTop = Math.min(top, maximumY);
            int overlapBottom = Math.max(bottom, minimumY);
            int overlap = Math.max(1, overlapTop - overlapBottom + 1);
            int height = Math.max(1, top - bottom);
            int score = overlap * 32 + Math.min(64, height) * 8;
            // Prefer a floor inside the selected band, then a tall cavern crossing it.
            if (bottom >= minimumY && bottom <= maximumY) score += 320;
            score -= Math.abs(maximumY - top) * 2;
            if ((flags[i] & FLAG_EMISSIVE) != 0) score += 24;
            if (score > bestScore) {
                bestScore = score;
                selected = i;
            }
        }
        return selected;
    }


    /**
     * Layered selection with a continuity target supplied by neighbouring pixels.
     * This mirrors Xaero's tile-level cave continuity more closely than resolving
     * every X/Z column independently.
     */
    public int layeredIndex(int maximumY, int minimumY, int neighbourY) {
        int selected = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            int top = topY[i];
            int bottom = bottomY[i];
            if (top < minimumY || bottom > maximumY) continue;

            int overlapTop = Math.min(top, maximumY);
            int overlapBottom = Math.max(bottom, minimumY);
            int overlap = Math.max(1, overlapTop - overlapBottom + 1);
            int height = Math.max(1, top - bottom);
            int score = overlap * 32 + Math.min(64, height) * 8;
            if (bottom >= minimumY && bottom <= maximumY) score += 320;
            score -= Math.abs(maximumY - top) * 2;
            if ((flags[i] & FLAG_EMISSIVE) != 0) score += 24;
            if ((flags[i] & FLAG_WATER) != 0) score += 12;
            if ((flags[i] & FLAG_FLUID) != 0) score += 16;
            if (neighbourY != Integer.MIN_VALUE) {
                int delta = Math.abs(bottom - neighbourY);
                score -= Math.min(1600, delta * 28);
                if (delta <= 1) score += 240;
                else if (delta <= 3) score += 140;
                else if (delta <= 6) score += 60;
            }
            if (score > bestScore) {
                bestScore = score;
                selected = i;
            }
        }
        return selected;
    }

    public Candidate layeredCandidate(int maximumY, int minimumY) {
        int index = layeredIndex(maximumY, minimumY);
        return index < 0 ? null : candidate(index);
    }

    /** Base quality used before run-level graph connectivity is considered. */
    public int fullBaseScore(int index, int preferredY) {
        if (index < 0 || index >= count) return Integer.MIN_VALUE / 4;
        int top = topY[index];
        int bottom = bottomY[index];
        int height = Math.max(1, top - bottom);
        int center = (top + bottom) >> 1;
        int score = Math.min(96, height) * 24;
        if (height >= 2) score += 120;
        if (height >= 4) score += 180;
        if (height >= 8) score += 220;
        byte runFlags = flags[index];
        if ((runFlags & FLAG_EMISSIVE) != 0) score += 80;
        if ((runFlags & FLAG_WATER) != 0) score += 28;
        if ((runFlags & FLAG_FLUID) != 0) score += 42;
        if (preferredY != Integer.MIN_VALUE) {
            score -= Math.min(320, Math.abs(center - preferredY) * 2);
        }
        return score;
    }

    /** Base quality for one run inside a bounded Layered Cave band. */
    public int layeredBaseScore(int index, int maximumY, int minimumY) {
        if (index < 0 || index >= count) return Integer.MIN_VALUE / 4;
        int top = topY[index];
        int bottom = bottomY[index];
        if (top < minimumY || bottom > maximumY) return Integer.MIN_VALUE / 4;
        int overlapTop = Math.min(top, maximumY);
        int overlapBottom = Math.max(bottom, minimumY);
        int overlap = Math.max(1, overlapTop - overlapBottom + 1);
        int height = Math.max(1, top - bottom);
        int score = overlap * 32 + Math.min(64, height) * 8;
        if (bottom >= minimumY && bottom <= maximumY) score += 320;
        score -= Math.abs(maximumY - top) * 2;
        if ((flags[index] & FLAG_EMISSIVE) != 0) score += 24;
        if ((flags[index] & FLAG_WATER) != 0) score += 12;
        if ((flags[index] & FLAG_FLUID) != 0) score += 16;
        return score;
    }

    /**
     * Tests actual vertical compatibility between two cavity runs. Unlike the old
     * pixel-height graph, this compares the complete open intervals before either
     * column has discarded its alternate cave layers.
     */
    public boolean connectsTo(int index, CaveColumnData other, int otherIndex,
            int verticalTolerance) {
        if (other == null || index < 0 || index >= count
                || otherIndex < 0 || otherIndex >= other.count) return false;
        int firstTop = topY[index];
        int firstBottom = bottomY[index];
        int secondTop = other.topY[otherIndex];
        int secondBottom = other.bottomY[otherIndex];

        // Open space begins one block above the recorded floor. Intersecting open
        // intervals are strongly connected even if the two floor blocks are stepped.
        int overlapTop = Math.min(firstTop, secondTop);
        int overlapBottom = Math.max(firstBottom + 1, secondBottom + 1);
        if (overlapTop + Math.max(0, verticalTolerance) >= overlapBottom
                && Math.abs(firstBottom - secondBottom) <= 12) return true;

        // Narrow tunnels and stairs may not have overlapping vertical intervals,
        // but close floors and ceilings still form a traversable continuous surface.
        return Math.abs(firstBottom - secondBottom) <= Math.max(2, verticalTolerance)
                && Math.abs(firstTop - secondTop) <= 12;
    }

    /**
     * Full Cave keeps the graph-selected primary floor, then softly carries useful
     * fluid/emissive information from vertically stacked secondary runs. This is a
     * 2D composite, not an attempt to render multiple opaque floors at once.
     */
    public int compositeColor(int primaryIndex) {
        if (primaryIndex < 0 || primaryIndex >= count) return 0;
        int result = colors[primaryIndex];
        int bestSecondary = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            if (i == primaryIndex) continue;
            byte runFlags = flags[i];
            if ((runFlags & (FLAG_FLUID | FLAG_WATER | FLAG_EMISSIVE)) == 0) continue;
            int height = Math.max(1, topY[i] - bottomY[i]);
            int score = height * 8;
            if ((runFlags & FLAG_EMISSIVE) != 0) score += 160;
            if ((runFlags & FLAG_FLUID) != 0) score += 96;
            if ((runFlags & FLAG_WATER) != 0) score += 64;
            if (score > bestScore) {
                bestScore = score;
                bestSecondary = i;
            }
        }
        if (bestSecondary < 0) return result;
        byte secondaryFlags = flags[bestSecondary];
        int alpha = (secondaryFlags & FLAG_EMISSIVE) != 0 ? 72
                : (secondaryFlags & FLAG_FLUID) != 0 ? 52 : 40;
        return blendPacked(result, colors[bestSecondary], alpha);
    }

    private static int blendPacked(int base, int overlay, int alpha) {
        int inverse = 256 - Math.max(0, Math.min(255, alpha));
        int amount = 256 - inverse;
        int c0 = (((base) & 0xFF) * inverse + ((overlay) & 0xFF) * amount) >> 8;
        int c1 = (((base >>> 8) & 0xFF) * inverse
                + ((overlay >>> 8) & 0xFF) * amount) >> 8;
        int c2 = (((base >>> 16) & 0xFF) * inverse
                + ((overlay >>> 16) & 0xFF) * amount) >> 8;
        int c3 = (((base >>> 24) & 0xFF) * inverse
                + ((overlay >>> 24) & 0xFF) * amount) >> 8;
        return c0 | (c1 << 8) | (c2 << 16) | (c3 << 24);
    }

    private Candidate candidate(int index) {
        return new Candidate(topY[index], bottomY[index], colors[index], flags[index]);
    }

    /**
     * Equality of the fields actually serialized by CompactCaveTile. Scan-window
     * bounds are runtime acquisition metadata and are intentionally absent from the
     * compact archive. Revalidating only those bounds must therefore not rebuild and
     * re-ingest a byte-identical compact tile.
     */
    public boolean archiveContentEquals(CaveColumnData other) {
        if (other == this) return true;
        if (other == null || count != other.count
                || fullHeightComplete != other.fullHeightComplete) return false;
        for (int i = 0; i < count; i++) {
            if (topY[i] != other.topY[i]
                    || bottomY[i] != other.bottomY[i]
                    || colors[i] != other.colors[i]
                    || flags[i] != other.flags[i]
                    || fluidColors[i] != other.fluidColors[i]
                    || fluidAlpha[i] != other.fluidAlpha[i]
                    || fluidY[i] != other.fluidY[i]
                    || fluidLight[i] != other.fluidLight[i]
                    || fluidDepth[i] != other.fluidDepth[i]
                    || fluidFlags[i] != other.fluidFlags[i]
                    || emissiveColors[i] != other.emissiveColors[i]
                    || emissiveAlpha[i] != other.emissiveAlpha[i]
                    || emissiveY[i] != other.emissiveY[i]
                    || emissiveLight[i] != other.emissiveLight[i]) return false;
        }
        return true;
    }

    public boolean contentEquals(CaveColumnData other) {
        if (other == this) return true;
        if (other == null || count != other.count
                || scannedMinimumY != other.scannedMinimumY
                || scannedMaximumY != other.scannedMaximumY
                || fullHeightComplete != other.fullHeightComplete) return false;
        for (int i = 0; i < count; i++) {
            if (topY[i] != other.topY[i]
                    || bottomY[i] != other.bottomY[i]
                    || colors[i] != other.colors[i]
                    || flags[i] != other.flags[i]
                    || fluidColors[i] != other.fluidColors[i]
                    || fluidAlpha[i] != other.fluidAlpha[i]
                    || fluidY[i] != other.fluidY[i]
                    || fluidLight[i] != other.fluidLight[i]
                    || fluidDepth[i] != other.fluidDepth[i]
                    || fluidFlags[i] != other.fluidFlags[i]
                    || emissiveColors[i] != other.emissiveColors[i]
                    || emissiveAlpha[i] != other.emissiveAlpha[i]
                    || emissiveY[i] != other.emissiveY[i]
                    || emissiveLight[i] != other.emissiveLight[i]) return false;
        }
        return true;
    }

    public record Candidate(short topY, short bottomY, int color, byte flags) {
    }

    /** Reusable primitive writer used by the client-thread scanner. */
    public static final class Builder {
        private final short[] topY = new short[MAX_RUNS];
        private final short[] bottomY = new short[MAX_RUNS];
        private final int[] colors = new int[MAX_RUNS];
        private final byte[] flags = new byte[MAX_RUNS];
        private final int[] fluidColors = new int[MAX_RUNS];
        private final byte[] fluidAlpha = new byte[MAX_RUNS];
        private final short[] fluidY = new short[MAX_RUNS];
        private final byte[] fluidLight = new byte[MAX_RUNS];
        private final byte[] fluidDepth = new byte[MAX_RUNS];
        private final byte[] fluidFlags = new byte[MAX_RUNS];
        private final int[] emissiveColors = new int[MAX_RUNS];
        private final byte[] emissiveAlpha = new byte[MAX_RUNS];
        private final short[] emissiveY = new short[MAX_RUNS];
        private final byte[] emissiveLight = new byte[MAX_RUNS];
        private int count;
        private boolean overflowed;

        public void reset() {
            count = 0;
            overflowed = false;
        }

        public int count() {
            return count;
        }

        public boolean overflowed() {
            return overflowed;
        }

        public boolean add(int runTopY, int runBottomY, int color, byte runFlags) {
            return add(runTopY, runBottomY, color, runFlags,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        public boolean add(int runTopY, int runBottomY, int color, byte runFlags,
                int runFluidColor, int runFluidAlpha, int runFluidY,
                int runFluidLight, int runFluidDepth, int runFluidFlags,
                int runEmissiveColor, int runEmissiveAlpha,
                int runEmissiveY, int runEmissiveLight) {
            if (color == 0) return false;
            short safeTop = clampShort(runTopY);
            short safeBottom = clampShort(runBottomY);
            if (count > 0 && bottomY[count - 1] == safeBottom) return false;
            if (count >= MAX_RUNS) {
                overflowed = true;
                return false;
            }
            topY[count] = safeTop;
            bottomY[count] = safeBottom;
            colors[count] = color;
            flags[count] = runFlags;
            fluidColors[count] = runFluidColor;
            fluidAlpha[count] = (byte) clampUnsignedByte(runFluidAlpha);
            fluidY[count] = clampShort(runFluidY);
            fluidLight[count] = (byte) clampUnsignedByte(runFluidLight);
            fluidDepth[count] = (byte) clampUnsignedByte(runFluidDepth);
            fluidFlags[count] = (byte) clampUnsignedByte(runFluidFlags);
            emissiveColors[count] = runEmissiveColor;
            emissiveAlpha[count] = (byte) clampUnsignedByte(runEmissiveAlpha);
            emissiveY[count] = clampShort(runEmissiveY);
            emissiveLight[count] = (byte) clampUnsignedByte(runEmissiveLight);
            count++;
            return true;
        }

        public CaveColumnData build(int scannedMinimumY, int scannedMaximumY,
                boolean reachedMinimumY) {
            boolean complete = reachedMinimumY && !overflowed;
            return count == 0
                    ? CaveColumnData.emptyScanned(scannedMinimumY, scannedMaximumY, complete)
                    : new CaveColumnData(topY, bottomY, colors, flags,
                            fluidColors, fluidAlpha, fluidY, fluidLight,
                            fluidDepth, fluidFlags, emissiveColors, emissiveAlpha,
                            emissiveY, emissiveLight, count,
                            scannedMinimumY, scannedMaximumY, complete);
        }
    }

    private static int clampUnsignedByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static short clampShort(int value) {
        return (short) Math.max(Short.MIN_VALUE + 1,
                Math.min(Short.MAX_VALUE, value));
    }
}
