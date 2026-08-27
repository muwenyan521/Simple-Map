package com.velorise.simplemap.client.cave.archive;

import com.velorise.simplemap.client.cave.CaveChunkTile;
import com.velorise.simplemap.client.cave.CaveCacheSchema;
import com.velorise.simplemap.client.cave.CaveColumnData;

import java.util.Arrays;
import java.util.BitSet;

/**
 * M7 style-independent structure-of-arrays cave archive for one 16x16 chunk.
 *
 * <p>During migration legacy scan colors are preserved in materialIds with the
 * LEGACY_COLOR flag. New scanners can commit registry material/biome identities
 * without changing the projection API.</p>
 */
public final class CompactCaveTile {
    public enum ColumnStatus {
        UNKNOWN, PARTIAL, COMPLETE, COMPLETE_TRUNCATED, CORRUPT
    }

    public static final byte FLAG_WATER = 1;
    public static final byte FLAG_FLUID = 1 << 1;
    public static final byte FLAG_EMISSIVE = 1 << 2;
    /** Material field contains an inline raw ABGR colour, not a registry id. */
    public static final byte FLAG_LEGACY_COLOR = 1 << 6;
    public static final int COLUMNS = 256;

    private final int chunkX;
    private final int chunkZ;
    private final long revision;
    private final int[] columnOffsets;
    private final short[] runTopY;
    private final short[] runFloorY;
    private final int[] materialIds;
    private final short[] biomeIds;
    private final byte[] blockLight;
    private final byte[] skyLight;
    private final byte[] fluidDepth;
    private final int[] fluidColors;
    private final byte[] fluidAlpha;
    private final short[] fluidY;
    private final byte[] fluidLight;
    private final byte[] fluidFlags;
    private final int[] emissiveColors;
    private final byte[] emissiveAlpha;
    private final short[] emissiveY;
    private final byte[] emissiveLight;
    private final byte[] flags;
    private final byte[] statuses;
    private final boolean completeCoverage;
    /**
     * Full Cave only needs a final discovered run set for each column. Legacy/live
     * scans marked COMPLETE_TRUNCATED already contain that set even though they are
     * not safe for an arbitrary future Layered Top-Y query.
     */
    private final boolean fullProjectionCoverage;
    /** Stable geometry/material identity independent from scanner revision counters. */
    private final long contentFingerprint;

    public CompactCaveTile(int chunkX, int chunkZ, long revision,
            int[] columnOffsets, short[] runTopY, short[] runFloorY,
            int[] materialIds, short[] biomeIds, byte[] blockLight,
            byte[] skyLight, byte[] fluidDepth, byte[] flags,
            byte[] statuses) {
        this(chunkX, chunkZ, revision, columnOffsets, runTopY, runFloorY,
                materialIds, biomeIds, blockLight, skyLight, fluidDepth, flags,
                new int[runTopY == null ? 0 : runTopY.length],
                new byte[runTopY == null ? 0 : runTopY.length],
                new short[runTopY == null ? 0 : runTopY.length],
                new byte[runTopY == null ? 0 : runTopY.length],
                new byte[runTopY == null ? 0 : runTopY.length],
                new int[runTopY == null ? 0 : runTopY.length],
                new byte[runTopY == null ? 0 : runTopY.length],
                new short[runTopY == null ? 0 : runTopY.length],
                new byte[runTopY == null ? 0 : runTopY.length], statuses);
    }

    public CompactCaveTile(int chunkX, int chunkZ, long revision,
            int[] columnOffsets, short[] runTopY, short[] runFloorY,
            int[] materialIds, short[] biomeIds, byte[] blockLight,
            byte[] skyLight, byte[] fluidDepth, byte[] flags,
            int[] fluidColors, byte[] fluidAlpha, short[] fluidY,
            byte[] fluidLight, byte[] fluidFlags,
            int[] emissiveColors, byte[] emissiveAlpha, short[] emissiveY,
            byte[] emissiveLight, byte[] statuses) {
        if (columnOffsets == null || columnOffsets.length != COLUMNS + 1) {
            throw new IllegalArgumentException("columnOffsets");
        }
        int runs = columnOffsets[COLUMNS];
        if (runs < 0 || runTopY == null || runTopY.length != runs
                || runFloorY == null || runFloorY.length != runs
                || materialIds == null || materialIds.length != runs
                || biomeIds == null || biomeIds.length != runs
                || blockLight == null || blockLight.length != runs
                || skyLight == null || skyLight.length != runs
                || fluidDepth == null || fluidDepth.length != runs
                || flags == null || flags.length != runs
                || fluidColors == null || fluidColors.length != runs
                || fluidAlpha == null || fluidAlpha.length != runs
                || fluidY == null || fluidY.length != runs
                || fluidLight == null || fluidLight.length != runs
                || fluidFlags == null || fluidFlags.length != runs
                || emissiveColors == null || emissiveColors.length != runs
                || emissiveAlpha == null || emissiveAlpha.length != runs
                || emissiveY == null || emissiveY.length != runs
                || emissiveLight == null || emissiveLight.length != runs
                || statuses == null || statuses.length != COLUMNS) {
            throw new IllegalArgumentException("compact cave arrays");
        }
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.revision = CaveCacheSchema.canonicalContentRevision(revision);
        this.columnOffsets = Arrays.copyOf(columnOffsets, columnOffsets.length);
        this.runTopY = Arrays.copyOf(runTopY, runs);
        this.runFloorY = Arrays.copyOf(runFloorY, runs);
        this.materialIds = Arrays.copyOf(materialIds, runs);
        this.biomeIds = Arrays.copyOf(biomeIds, runs);
        this.blockLight = Arrays.copyOf(blockLight, runs);
        this.skyLight = Arrays.copyOf(skyLight, runs);
        this.fluidDepth = Arrays.copyOf(fluidDepth, runs);
        this.flags = Arrays.copyOf(flags, runs);
        this.fluidColors = Arrays.copyOf(fluidColors, runs);
        this.fluidAlpha = Arrays.copyOf(fluidAlpha, runs);
        this.fluidY = Arrays.copyOf(fluidY, runs);
        this.fluidLight = Arrays.copyOf(fluidLight, runs);
        this.fluidFlags = Arrays.copyOf(fluidFlags, runs);
        this.emissiveColors = Arrays.copyOf(emissiveColors, runs);
        this.emissiveAlpha = Arrays.copyOf(emissiveAlpha, runs);
        this.emissiveY = Arrays.copyOf(emissiveY, runs);
        this.emissiveLight = Arrays.copyOf(emissiveLight, runs);
        this.statuses = Arrays.copyOf(statuses, COLUMNS);
        boolean complete = true;
        boolean fullProjectionComplete = true;
        int completeOrdinal = ColumnStatus.COMPLETE.ordinal();
        int truncatedOrdinal = ColumnStatus.COMPLETE_TRUNCATED.ordinal();
        for (byte status : this.statuses) {
            int value = status & 0xFF;
            if (value != completeOrdinal) complete = false;
            if (value != completeOrdinal && value != truncatedOrdinal) {
                fullProjectionComplete = false;
            }
        }
        this.completeCoverage = complete;
        this.fullProjectionCoverage = fullProjectionComplete;
        this.contentFingerprint = computeContentFingerprint();
    }

    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public long revision() { return revision; }
    public long contentFingerprint() { return contentFingerprint; }
    public int runCount() { return runTopY.length; }
    public int runStart(int column) { return columnOffsets[column]; }
    public int runEnd(int column) { return columnOffsets[column + 1]; }
    public short topY(int run) { return runTopY[run]; }
    public short floorY(int run) { return runFloorY[run]; }
    public int materialId(int run) { return materialIds[run]; }
    public short biomeId(int run) { return biomeIds[run]; }
    public byte blockLight(int run) { return blockLight[run]; }
    public byte skyLight(int run) { return skyLight[run]; }
    public byte fluidDepth(int run) { return fluidDepth[run]; }
    public byte flags(int run) { return flags[run]; }
    public int fluidColor(int run) { return fluidColors[run]; }
    public byte fluidAlpha(int run) { return fluidAlpha[run]; }
    public short fluidY(int run) { return fluidY[run]; }
    public byte fluidLight(int run) { return fluidLight[run]; }
    public byte fluidFlags(int run) { return fluidFlags[run]; }
    public int emissiveColor(int run) { return emissiveColors[run]; }
    public byte emissiveAlpha(int run) { return emissiveAlpha[run]; }
    public short emissiveY(int run) { return emissiveY[run]; }
    public byte emissiveLight(int run) { return emissiveLight[run]; }

    /**
     * Selects one connected-looking Full Cave representative for a column.
     *
     * <p>The archive stores every discovered vertical cavity. Selecting runStart()
     * unconditionally collapses a full projection into unrelated ceiling cracks.
     * This score prefers usable rooms/tunnels and then strongly follows the floor
     * selected by an adjacent column. {@code preferredY} is the dominant floor band
     * of the whole 16x16 tile and prevents the first column from choosing an extreme
     * outlier.</p>
     */
    public int selectFullRun(int column, int preferredY, int neighbourY) {
        int first = runStart(column);
        int end = runEnd(column);
        int selected = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int run = first; run < end; run++) {
            int top = topY(run);
            int floor = floorY(run);
            int height = Math.max(1, top - floor);
            int center = (top + floor) >> 1;
            int score = Math.min(96, height) * 24;
            if (height >= 2) score += 120;
            if (height >= 4) score += 180;
            if (height >= 8) score += 220;
            int runFlags = flags(run) & 0xFF;
            if ((runFlags & FLAG_EMISSIVE) != 0) score += 80;
            if ((runFlags & FLAG_WATER) != 0) score += 28;
            if ((runFlags & FLAG_FLUID) != 0) score += 42;
            if (preferredY != Integer.MIN_VALUE) {
                score -= Math.min(480, Math.abs(center - preferredY) * 3);
            }
            if (neighbourY != Integer.MIN_VALUE) {
                score -= Math.min(1600, Math.abs(floor - neighbourY) * 24);
                if (Math.abs(floor - neighbourY) <= 3) score += 260;
            }
            if (score > bestScore) {
                bestScore = score;
                selected = run;
            }
        }
        return selected;
    }

    /** Selects a cavity intersecting the exact Layered Top-Y window. */
    public int selectLayeredRun(int column, int maximumY, int minimumY,
            int neighbourY) {
        int first = runStart(column);
        int end = runEnd(column);
        int selected = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int run = first; run < end; run++) {
            int top = topY(run);
            int floor = floorY(run);
            if (top < minimumY || floor > maximumY) continue;
            int overlapTop = Math.min(top, maximumY);
            int overlapBottom = Math.max(floor, minimumY);
            int overlap = Math.max(1, overlapTop - overlapBottom + 1);
            int height = Math.max(1, top - floor);
            int score = overlap * 32 + Math.min(64, height) * 8;
            if (floor >= minimumY && floor <= maximumY) score += 320;
            score -= Math.abs(maximumY - top) * 2;
            int runFlags = flags(run) & 0xFF;
            if ((runFlags & FLAG_EMISSIVE) != 0) score += 24;
            if ((runFlags & FLAG_WATER) != 0) score += 12;
            if ((runFlags & FLAG_FLUID) != 0) score += 16;
            if (neighbourY != Integer.MIN_VALUE) {
                score -= Math.min(900, Math.abs(floor - neighbourY) * 20);
                if (Math.abs(floor - neighbourY) <= 2) score += 180;
            }
            if (score > bestScore) {
                bestScore = score;
                selected = run;
            }
        }
        return selected;
    }

    /**
     * Dominant vertical floor band used to seed a deterministic Full Cave raster.
     * Eight-block buckets are broad enough to join sloped tunnels while rejecting
     * isolated near-surface cracks and deep one-column pockets.
     */
    public int dominantFullFloorY() {
        int minimumBand = Integer.MAX_VALUE;
        int maximumBand = Integer.MIN_VALUE;
        for (int run = 0; run < runCount(); run++) {
            int band = Math.floorDiv(floorY(run), 8);
            minimumBand = Math.min(minimumBand, band);
            maximumBand = Math.max(maximumBand, band);
        }
        if (minimumBand == Integer.MAX_VALUE) return Integer.MIN_VALUE;
        int[] weights = new int[maximumBand - minimumBand + 1];
        for (int run = 0; run < runCount(); run++) {
            int height = Math.max(1, topY(run) - floorY(run));
            int weight = 1 + Math.min(64, height) * 3;
            if (height >= 3) weight += 24;
            if (height >= 7) weight += 32;
            weights[Math.floorDiv(floorY(run), 8) - minimumBand] += weight;
        }
        int best = 0;
        for (int index = 1; index < weights.length; index++) {
            if (weights[index] > weights[best]) best = index;
        }
        return (minimumBand + best) * 8 + 4;
    }

    /**
     * Canonical Full-Cave projection: runs are stored highest-first, therefore
     * the first archived cavity is the same top-down cavity reached by the live
     * Xaero-style column scan. Selection is deliberately column-local; no tile-wide
     * preferred Y or neighbour continuity may change the vertical authority.
     */
    public int firstVisibleFullRun(int column) {
        int first = runStart(column);
        return first < runEnd(column) ? first : -1;
    }

    /**
     * Canonical Layered projection: descending from Top-Y, select the first cavity
     * whose floor lies inside the requested band. This mirrors the live column scan
     * and avoids chunk-local continuity scores selecting a different vertical run.
     */
    public int firstVisibleLayeredRun(int column, int maximumY, int minimumY) {
        for (int run = runStart(column), end = runEnd(column); run < end; run++) {
            int floor = floorY(run);
            if (floor > maximumY) continue;
            if (floor < minimumY) break;
            if (topY(run) >= minimumY) return run;
        }
        return -1;
    }

    public boolean completeCoverage() { return completeCoverage; }
    public boolean fullProjectionCoverage() { return fullProjectionCoverage; }
    public ColumnStatus status(int column) {
        int ordinal = statuses[column] & 0xFF;
        return ordinal >= ColumnStatus.values().length
                ? ColumnStatus.CORRUPT : ColumnStatus.values()[ordinal];
    }

    public long estimatedBytes() {
        return (long) columnOffsets.length * Integer.BYTES
                + (long) runTopY.length * (Short.BYTES * 5L
                        + Integer.BYTES * 3L + 10L)
                + statuses.length;
    }

    private long computeContentFingerprint() {
        long hash = 0xCBF29CE484222325L;
        hash = mix(hash, chunkX);
        hash = mix(hash, chunkZ);
        for (int value : columnOffsets) hash = mix(hash, value);
        for (short value : runTopY) hash = mix(hash, value);
        for (short value : runFloorY) hash = mix(hash, value);
        for (int value : materialIds) hash = mix(hash, value);
        for (short value : biomeIds) hash = mix(hash, value);
        for (byte value : blockLight) hash = mix(hash, value);
        for (byte value : skyLight) hash = mix(hash, value);
        for (byte value : fluidDepth) hash = mix(hash, value);
        for (byte value : flags) hash = mix(hash, value);
        for (int value : fluidColors) hash = mix(hash, value);
        for (byte value : fluidAlpha) hash = mix(hash, value);
        for (short value : fluidY) hash = mix(hash, value);
        for (byte value : fluidLight) hash = mix(hash, value);
        for (byte value : fluidFlags) hash = mix(hash, value);
        for (int value : emissiveColors) hash = mix(hash, value);
        for (byte value : emissiveAlpha) hash = mix(hash, value);
        for (short value : emissiveY) hash = mix(hash, value);
        for (byte value : emissiveLight) hash = mix(hash, value);
        for (byte value : statuses) hash = mix(hash, value);
        hash ^= completeCoverage
                ? 0x6C8E9CF570932BD5L : 0xA5A5A5A55A5A5A5AL;
        hash ^= hash >>> 30;
        hash *= 0xBF58476D1CE4E5B9L;
        hash ^= hash >>> 27;
        hash *= 0x94D049BB133111EBL;
        hash ^= hash >>> 31;
        return hash == 0L ? 1L : hash;
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        hash *= 0x100000001B3L;
        return hash;
    }

    public static CompactCaveTile fromLegacy(CaveChunkTile.Snapshot snapshot) {
        if (snapshot == null) return null;
        int[] offsets = new int[COLUMNS + 1];
        int totalRuns = 0;
        for (int column = 0; column < COLUMNS; column++) {
            offsets[column] = totalRuns;
            CaveColumnData data = snapshot.columns()[column];
            if (snapshot.scanned().get(column) && data != null) totalRuns += data.count();
        }
        offsets[COLUMNS] = totalRuns;
        short[] top = new short[totalRuns];
        short[] floor = new short[totalRuns];
        int[] material = new int[totalRuns];
        short[] biome = new short[totalRuns];
        byte[] block = new byte[totalRuns];
        byte[] sky = new byte[totalRuns];
        byte[] fluidDepth = new byte[totalRuns];
        byte[] flags = new byte[totalRuns];
        int[] fluidColors = new int[totalRuns];
        byte[] fluidAlpha = new byte[totalRuns];
        short[] fluidY = new short[totalRuns];
        byte[] fluidLight = new byte[totalRuns];
        byte[] fluidFlags = new byte[totalRuns];
        int[] emissiveColors = new int[totalRuns];
        byte[] emissiveAlpha = new byte[totalRuns];
        short[] emissiveY = new short[totalRuns];
        byte[] emissiveLight = new byte[totalRuns];
        byte[] statuses = new byte[COLUMNS];
        int cursor = 0;
        BitSet scanned = snapshot.scanned();
        BitSet fullHeight = snapshot.fullHeight();
        for (int column = 0; column < COLUMNS; column++) {
            CaveColumnData data = snapshot.columns()[column];
            if (!scanned.get(column)) {
                statuses[column] = (byte) ColumnStatus.UNKNOWN.ordinal();
                continue;
            }
            boolean complete = fullHeight.get(column);
            statuses[column] = (byte) (complete
                    ? ColumnStatus.COMPLETE.ordinal()
                    : ColumnStatus.COMPLETE_TRUNCATED.ordinal());
            if (data == null) continue;
            for (int run = 0; run < data.count(); run++) {
                top[cursor] = data.topY(run);
                floor[cursor] = data.bottomY(run);
                material[cursor] = data.color(run);
                byte legacyFlags = FLAG_LEGACY_COLOR;
                byte old = data.flags(run);
                if ((old & CaveColumnData.FLAG_WATER) != 0) legacyFlags |= FLAG_WATER;
                if ((old & CaveColumnData.FLAG_FLUID) != 0) legacyFlags |= FLAG_FLUID;
                if ((old & CaveColumnData.FLAG_EMISSIVE) != 0) legacyFlags |= FLAG_EMISSIVE;
                flags[cursor] = legacyFlags;
                fluidColors[cursor] = data.fluidColor(run);
                fluidAlpha[cursor] = data.fluidAlpha(run);
                fluidY[cursor] = data.fluidY(run);
                fluidLight[cursor] = data.fluidLight(run);
                fluidDepth[cursor] = data.fluidDepth(run);
                fluidFlags[cursor] = data.fluidFlags(run);
                emissiveColors[cursor] = data.emissiveColor(run);
                emissiveAlpha[cursor] = data.emissiveAlpha(run);
                emissiveY[cursor] = data.emissiveY(run);
                emissiveLight[cursor] = data.emissiveLight(run);
                cursor++;
            }
        }
        return new CompactCaveTile(snapshot.chunkX(), snapshot.chunkZ(),
                snapshot.revision(), offsets, top, floor, material, biome,
                block, sky, fluidDepth, flags, fluidColors, fluidAlpha,
                fluidY, fluidLight, fluidFlags, emissiveColors,
                emissiveAlpha, emissiveY, emissiveLight, statuses);
    }
}
