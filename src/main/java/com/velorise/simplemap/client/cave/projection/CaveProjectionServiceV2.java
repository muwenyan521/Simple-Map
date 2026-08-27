package com.velorise.simplemap.client.cave.projection;

import com.velorise.simplemap.client.cave.archive.CaveArchiveV2Service;
import com.velorise.simplemap.client.cave.archive.CompactCaveTile;
import com.velorise.simplemap.client.cave.DenseCaveTile;
import com.velorise.simplemap.client.FullCaveMapManager;

import java.util.HashMap;
import java.util.Map;

/**
 * M8 archive-only Layered and Full Cave projection service.
 * Top-Y scrubbing uses nearest cached bands; exact refinement is requested only
 * after the caller's debounce window.
 */
public final class CaveProjectionServiceV2 {
    public record Summary(int bandEntries, int coarseEntries,
            long layeredHits, long layeredMisses, long fullBuilds,
            long staleRejected) { }

    private static final CaveProjectionServiceV2 INSTANCE =
            new CaveProjectionServiceV2();
    private static final int BAND_HEIGHT = 32;

    /**
     * Keep Full and Layered projection products in independent retained LRU sets.
     * PASS123 used one shared 16,384-entry cache, then destructively rebased it on
     * every FULL <-> LAYERED switch. That made a mode switch throw away thousands
     * of already-projected 16x16 chunks and immediately rebuild them from the same
     * immutable archive. Xaero keeps previously written map tiles and only rewrites
     * tiles whose cave interpretation/content changed. Split the same total entry
     * budget in two so one view cannot evict the other merely by becoming active.
     */
    private final CaveBandCache layeredCache = new CaveBandCache(8192);
    private final CaveBandCache fullCache = new CaveBandCache(8192);
    /**
     * One retained build owner per hash stripe. Multiple cave workers frequently
     * miss the same projection key during a viewport fan-out; PASS98 let every
     * worker allocate six 256-entry arrays and only deduplicated after the work.
     * Xaero gives a retained texture/chunk one dirty-buffer owner. These stripes
     * provide the same single-builder rule without serializing unrelated chunks.
     */
    private final Object[] projectionBuildLocks = new Object[64];
    private record SummaryKey(String dimension, long chunkKey) { }

    private static final int MAX_COARSE_SUMMARIES = 4096;
    private final java.util.LinkedHashMap<SummaryKey, CaveCoarseSummary> fullSummaries =
            new java.util.LinkedHashMap<>(128, 0.75f, true);
    private long cacheEpoch = 1L;
    private long layeredHits;
    private long layeredMisses;
    private long fullBuilds;
    private long staleRejected;
    /** Active exact cave-start is tracked independently for every dimension. */
    private final Map<String, Integer> activeLayeredTopYByDimension = new HashMap<>();
    private String activeDimension = "simplemap:unknown";

    private CaveProjectionServiceV2() {
        for (int index = 0; index < projectionBuildLocks.length; index++) {
            projectionBuildLocks[index] = new Object();
        }
    }

    public static CaveProjectionServiceV2 getInstance() { return INSTANCE; }

    /**
     * Selects the projection namespace before archive/page work for a map
     * dimension. Navigation does not clear retained products; dimension is part of
     * every cache key, mirroring Xaero's per-MapDimension LayeredRegionManager.
     */
    public synchronized void activateDimension(String dimension) {
        String next = normalizeDimension(dimension);
        if (next.equals(activeDimension)) return;
        activeDimension = next;
        // Fence workers that captured the previous dimension before this handoff.
        cacheEpoch++;
    }

    /**
     * Updates the exact cave-start without deleting already projected products.
     * Xaero keeps MapRegions inside a MapLayer and marks old caveStart products
     * lazily outdated instead of purging the layer at the moment the player moves.
     * Exact Top-Y is already part of our cache key, so the bounded LRU can retain
     * several recent exact starts safely and reuse them on vertical backtracking.
     */
    public synchronized int activateLayeredTopY(int topY) {
        Integer previous = activeLayeredTopYByDimension.put(activeDimension, topY);
        if (previous != null && previous == topY) return 0;
        int normalizedBand = Math.floorDiv(topY, 16) * 16;
        return layeredCache.retainExactProjectionForBand(activeDimension,
                normalizedBand, topY);
    }

    /**
     * Full Cave activation no longer destroys retained Layered projections. The
     * dedicated Full LRU is already bounded, so activation is ownership-only.
     */
    public synchronized int activateFull() {
        return 0;
    }

    public CaveProjectionTile layered(int chunkX, int chunkZ, int topY,
            long styleRevision) {
        return layered(CaveArchiveV2Service.getInstance().get(chunkX, chunkZ),
                topY, styleRevision);
    }

    public CaveProjectionTile layered(CompactCaveTile tile, int topY,
            long styleRevision) {
        if (tile == null) return null;
        int chunkX = tile.chunkX();
        int chunkZ = tile.chunkZ();
        int bandTop = topY;
        String dimension;
        synchronized (this) { dimension = activeDimension; }
        CaveBandCache.Key key = new CaveBandCache.Key(dimension, chunkX, chunkZ,
                bandTop, tile.revision(), styleRevision);
        long epoch;
        synchronized (this) {
            CaveProjectionTile cached = layeredCache.get(key);
            if (cached != null) {
                layeredHits++;
                return cached;
            }
            epoch = cacheEpoch;
        }

        synchronized (projectionLock(key)) {
            synchronized (this) {
                CaveProjectionTile cached = layeredCache.get(key);
                if (cached != null) {
                    layeredHits++;
                    return cached;
                }
                epoch = cacheEpoch;
            }
            CaveProjectionTile projected = projectLayered(tile, bandTop);
            synchronized (this) {
                if (epoch != cacheEpoch) return projected;
                CaveProjectionTile raced = layeredCache.get(key);
                if (raced != null) {
                    layeredHits++;
                    return raced;
                }
                layeredMisses++;
                layeredCache.put(key, projected);
                return projected;
            }
        }
    }



    /**
     * Exact Full-Cave projection from the full-height compact archive. Unlike the
     * coarse summary, this produces the same 16x16 display payload consumed by an
     * exact page build. A FULL viewport can therefore reuse the archive populated by
     * persistence/live scanning instead of decoding the same Anvil chunks again.
     */
    public CaveProjectionTile full(int chunkX, int chunkZ, long styleRevision) {
        return full(CaveArchiveV2Service.getInstance().get(chunkX, chunkZ),
                styleRevision);
    }

    public CaveProjectionTile full(CompactCaveTile tile, long styleRevision) {
        if (tile == null) return null;
        int chunkX = tile.chunkX();
        int chunkZ = tile.chunkZ();
        String dimension;
        synchronized (this) { dimension = activeDimension; }
        CaveBandCache.Key key = new CaveBandCache.Key(dimension, chunkX, chunkZ,
                Integer.MIN_VALUE, tile.revision(), styleRevision);
        long epoch;
        synchronized (this) {
            CaveProjectionTile cached = fullCache.get(key);
            if (cached != null) return cached;
            epoch = cacheEpoch;
        }

        synchronized (projectionLock(key)) {
            synchronized (this) {
                CaveProjectionTile cached = fullCache.get(key);
                if (cached != null) return cached;
                epoch = cacheEpoch;
            }
            CaveProjectionTile projected = projectFull(tile);
            synchronized (this) {
                if (epoch != cacheEpoch) return projected;
                CaveProjectionTile raced = fullCache.get(key);
                if (raced != null) return raced;
                fullBuilds++;
                fullCache.put(key, projected);
                return projected;
            }
        }
    }


    public synchronized CaveCoarseSummary fullSummary(int chunkX, int chunkZ) {
        CompactCaveTile tile = CaveArchiveV2Service.getInstance().get(chunkX, chunkZ);
        if (tile == null) return null;
        SummaryKey key = new SummaryKey(activeDimension,
                CaveArchiveV2Service.pack(chunkX, chunkZ));
        CaveCoarseSummary cached = fullSummaries.get(key);
        if (cached != null && cached.archiveRevision() == tile.revision()) {
            return cached;
        }
        CaveCoarseSummary summary = summarize(tile);
        fullSummaries.put(key, summary);
        trimCoarseSummaries();
        fullBuilds++;
        return summary;
    }

    public synchronized Summary summary() {
        return new Summary(layeredCache.size() + fullCache.size(),
                fullSummaries.size(), layeredHits, layeredMisses,
                fullBuilds, staleRejected);
    }

    public synchronized void clear() {
        cacheEpoch++;
        layeredCache.clear();
        fullCache.clear();
        fullSummaries.clear();
        activeLayeredTopYByDimension.clear();
        activeDimension = "simplemap:unknown";
    }


    private void trimCoarseSummaries() {
        var iterator = fullSummaries.entrySet().iterator();
        while (fullSummaries.size() > MAX_COARSE_SUMMARIES && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private Object projectionLock(CaveBandCache.Key key) {
        int hash = key.hashCode();
        hash ^= hash >>> 16;
        return projectionBuildLocks[hash & (projectionBuildLocks.length - 1)];
    }

    private static CaveProjectionTile projectFull(CompactCaveTile tile) {
        int[] pixels = new int[256];
        short[] floors = new short[256];
        short[] tops = new short[256];
        byte[] flags = new byte[256];
        byte[] lights = new byte[256];
        byte[] completeness = new byte[256];
        java.util.Arrays.fill(floors, FullCaveMapManager.NO_SURFACE);
        java.util.Arrays.fill(tops, FullCaveMapManager.NO_SURFACE);

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int column = (z << 4) | x;
                CompactCaveTile.ColumnStatus status = tile.status(column);
                completeness[column] = (byte) status.ordinal();
                int run = tile.firstVisibleFullRun(column);
                if (run >= 0) {
                    fillSelectedRun(tile, run, column, pixels, floors, tops,
                            flags, lights);
                }
            }
        }
        return new CaveProjectionTile(tile.chunkX(), tile.chunkZ(),
                Integer.MIN_VALUE, tile.revision(), pixels, floors, tops, flags,
                lights, completeness);
    }

    private static CaveProjectionTile projectLayered(CompactCaveTile tile,
            int bandTop) {
        int bandBottom = bandTop - BAND_HEIGHT + 1;
        int[] pixels = new int[256];
        short[] floors = new short[256];
        short[] tops = new short[256];
        byte[] flags = new byte[256];
        byte[] lights = new byte[256];
        byte[] completeness = new byte[256];
        java.util.Arrays.fill(floors, FullCaveMapManager.NO_SURFACE);
        java.util.Arrays.fill(tops, FullCaveMapManager.NO_SURFACE);

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int column = (z << 4) | x;
                CompactCaveTile.ColumnStatus status = tile.status(column);
                completeness[column] = (byte) status.ordinal();
                int run = tile.firstVisibleLayeredRun(
                        column, bandTop, bandBottom);
                if (run >= 0) {
                    fillSelectedRun(tile, run, column, pixels, floors, tops,
                            flags, lights);
                }
            }
        }
        return new CaveProjectionTile(tile.chunkX(), tile.chunkZ(), bandTop,
                tile.revision(), pixels, floors, tops, flags, lights,
                completeness);
    }

    private static void fillSelectedRun(CompactCaveTile tile, int run,
            int column, int[] pixels, short[] floors, short[] tops,
            byte[] flags, byte[] lights) {
        pixels[column] = resolveColor(tile, run);
        floors[column] = tile.floorY(run);
        tops[column] = tile.topY(run);
        int compactFlags = tile.flags(run) & 0xFF;
        byte denseFlags = 0;
        boolean semanticFluid = tile.fluidColor(run) != 0;
        boolean semanticEmissive = tile.emissiveColor(run) != 0;
        if (!semanticFluid
                && (compactFlags & CompactCaveTile.FLAG_WATER) != 0) {
            denseFlags |= DenseCaveTile.FLAG_WATER;
        }
        if (!semanticFluid
                && (compactFlags & CompactCaveTile.FLAG_FLUID) != 0) {
            denseFlags |= DenseCaveTile.FLAG_FLUID;
        }
        if (!semanticFluid && !semanticEmissive
                && (compactFlags & CompactCaveTile.FLAG_EMISSIVE) != 0) {
            denseFlags |= DenseCaveTile.FLAG_EMISSIVE;
        }
        flags[column] = denseFlags;
        lights[column] = (byte) Math.max(
                Byte.toUnsignedInt(tile.blockLight(run)),
                Byte.toUnsignedInt(tile.skyLight(run)));
    }

    private static CaveCoarseSummary summarize(CompactCaveTile tile) {
        int known = 0;
        int complete = 0;
        int water = 0;
        int emissive = 0;
        int minDepth = Integer.MAX_VALUE;
        int maxDepth = Integer.MIN_VALUE;
        Map<Integer, Integer> materialFrequency = new HashMap<>();
        for (int column = 0; column < 256; column++) {
            CompactCaveTile.ColumnStatus status = tile.status(column);
            if (status != CompactCaveTile.ColumnStatus.UNKNOWN
                    && status != CompactCaveTile.ColumnStatus.CORRUPT) known++;
            if (status == CompactCaveTile.ColumnStatus.COMPLETE) complete++;
            if (tile.runStart(column) >= tile.runEnd(column)) continue;
            int run = tile.runStart(column);
            int flags = tile.flags(run) & 0xFF;
            if ((flags & CompactCaveTile.FLAG_WATER) != 0) water++;
            if ((flags & CompactCaveTile.FLAG_EMISSIVE) != 0) emissive++;
            int depth = tile.floorY(run);
            minDepth = Math.min(minDepth, depth);
            maxDepth = Math.max(maxDepth, depth);
            materialFrequency.merge(tile.materialId(run), 1, Integer::sum);
        }
        int dominant = 0;
        int dominantCount = -1;
        for (Map.Entry<Integer, Integer> entry : materialFrequency.entrySet()) {
            if (entry.getValue() > dominantCount) {
                dominant = entry.getKey();
                dominantCount = entry.getValue();
            }
        }
        if (minDepth == Integer.MAX_VALUE) minDepth = 0;
        if (maxDepth == Integer.MIN_VALUE) maxDepth = 0;
        return new CaveCoarseSummary(tile.chunkX(), tile.chunkZ(), tile.revision(),
                known / 256.0f, dominant, water / 256.0f,
                emissive / 256.0f, minDepth, maxDepth, complete / 256.0f);
    }

    private static String normalizeDimension(String dimension) {
        if (dimension == null || dimension.isBlank()) return "simplemap:unknown";
        return dimension.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static int resolveColor(CompactCaveTile tile, int run) {
        int material = tile.materialId(run);
        if ((tile.flags(run) & CompactCaveTile.FLAG_LEGACY_COLOR) != 0) {
            // CaveColumnData stores the same ABGR colour consumed by NativeImage and
            // DenseCaveTile. The former conversion swapped red/blue a second time.
            return material == 0 ? 0 : material | 0xFF000000;
        }
        int hash = material * 0x9E3779B9;
        int red = 48 + ((hash >>> 16) & 0x7F);
        int green = 48 + ((hash >>> 8) & 0x7F);
        int blue = 48 + (hash & 0x7F);
        return 0xFF000000 | (blue << 16) | (green << 8) | red;
    }

}
