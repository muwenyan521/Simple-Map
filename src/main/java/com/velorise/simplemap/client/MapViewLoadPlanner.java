package com.velorise.simplemap.client;

/**
 * Separate visible-demand policies for fullscreen maps and the minimap.
 *
 * <p>Fullscreen follows a stable viewport traversal split into small update
 * slices. The page coordinate is only a queue/storage key: each selected page is
 * published incrementally as complete 16x16 chunks arrive. Pages are ordered
 * centre-out from the current viewport, matching the
 * nearest-view priority used by mature map renderers: after a zoom transition the
 * area the player is actually inspecting becomes sharp before distant edges.
 * Cursor movement is not part of the load key or candidate priority.</p>
 *
 * <p>The minimap is intentionally different. It requests the Minecraft-loaded
 * working set around the camera/player in centre-out order. Coarser world-map
 * branches may cover the rest of a far-zoom cave viewport, while these exact
 * leaves keep the player's immediate surroundings current.</p>
 */
public final class MapViewLoadPlanner {
    public static final int FULLSCREEN_SLICE_SIZE = 100;
    public static final int FULLSCREEN_SHORTLIST_SIZE = 10;
    public static final int MINIMAP_HALO_PAGES = 1;
    /**
     * Maximum exact working radius needed by Minecraft's supported client render
     * distances. Xaero's standalone 9x9 chunk writer is not the policy used when
     * its world-map integration is active: that path renders the actual minimap
     * bounds from the shared region cache. The old radius of two pages confused
     * those two paths and permanently limited Simple Map to a 320x320-block island.
     */
    public static final int MINIMAP_MAX_RADIUS_PAGES = 10;

    /** Cave gameplay writer: Xaero-equivalent centre +/-2 64x64 map pages. */
    public static final int CAVE_MINIMAP_MAX_RADIUS_PAGES = 2;
    private MapViewLoadPlanner() {
    }

    /** Persistent traversal state. Focus is deliberately not part of the key. */
    public static final class State {
        private String dimension = "";
        private int minX;
        private int maxX;
        private int minZ;
        private int maxZ;
        private int sliceIndex;
        private int sliceCount;
        private boolean configured;
        private boolean retainedOverlap;
        private long completedCycles;
        private long[] pagePlan = new long[0];

        public boolean configure(String dimension, int minX, int maxX,
                int minZ, int maxZ) {
            String safeDimension = dimension == null ? "" : dimension;
            int safeMinX = Math.min(minX, maxX);
            int safeMaxX = Math.max(minX, maxX);
            int safeMinZ = Math.min(minZ, maxZ);
            int safeMaxZ = Math.max(minZ, maxZ);
            if (configured && this.dimension.equals(safeDimension)
                    && this.minX == safeMinX && this.maxX == safeMaxX
                    && this.minZ == safeMinZ && this.maxZ == safeMaxZ) return false;

            int previousMinX = this.minX;
            int previousMaxX = this.maxX;
            int previousMinZ = this.minZ;
            int previousMaxZ = this.maxZ;
            boolean sameDimension = configured && this.dimension.equals(safeDimension);
            retainedOverlap = sameDimension && rectanglesOverlap(
                    previousMinX, previousMaxX, previousMinZ, previousMaxZ,
                    safeMinX, safeMaxX, safeMinZ, safeMaxZ);

            this.dimension = safeDimension;
            this.minX = safeMinX;
            this.maxX = safeMaxX;
            this.minZ = safeMinZ;
            this.maxZ = safeMaxZ;
            this.pagePlan = buildPlan(safeMinX, safeMaxX, safeMinZ, safeMaxZ);
            this.sliceCount = Math.max(1, (pagePlan.length
                    + FULLSCREEN_SLICE_SIZE - 1) / FULLSCREEN_SLICE_SIZE);
            this.sliceIndex = 0;
            this.completedCycles = 0L;
            this.configured = true;
            return true;
        }

        /**
         * Fills the current fullscreen update slice without advancing it.
         * The immutable plan is centre-out, so zooming never restarts at a remote
         * top-left edge and strands the inspected page behind hundreds of leaves.
         */
        public int fillCurrentFullscreenSlice(long[] output) {
            if (!configured || output == null || output.length == 0) return 0;
            int start = sliceIndex * FULLSCREEN_SLICE_SIZE;
            int count = Math.min(output.length,
                    Math.max(0, Math.min(pagePlan.length,
                            start + FULLSCREEN_SLICE_SIZE) - start));
            if (count > 0) System.arraycopy(pagePlan, start, output, 0, count);
            return count;
        }

        /** Compatibility view for dependency-light tools; production uses longs. */
        public int fillCurrentFullscreenSlice(Page[] output) {
            if (!configured || output == null || output.length == 0) return 0;
            int start = sliceIndex * FULLSCREEN_SLICE_SIZE;
            int end = Math.min(pagePlan.length, start + FULLSCREEN_SLICE_SIZE);
            int count = 0;
            for (int index = start; index < end && count < output.length; index++) {
                long packed = pagePlan[index];
                output[count++] = new Page(packedX(packed), packedZ(packed), index);
            }
            return count;
        }

        /** Advances only after the current slice has no missing or in-flight page. */
        public void advanceFullscreenSlice() {
            if (!configured) return;
            sliceIndex++;
            if (sliceIndex >= sliceCount) {
                sliceIndex = 0;
                completedCycles++;
            }
        }

        /** Compatibility helper for tests/tools that intentionally consume a slice. */
        public int fillNextFullscreenSlice(long[] output) {
            int count = fillCurrentFullscreenSlice(output);
            advanceFullscreenSlice();
            return count;
        }

        public int fillNextFullscreenSlice(Page[] output) {
            int count = fillCurrentFullscreenSlice(output);
            advanceFullscreenSlice();
            return count;
        }

        public int currentSliceStartOrdinal() {
            return sliceIndex * FULLSCREEN_SLICE_SIZE;
        }

        public boolean retainedOverlap() {
            return retainedOverlap;
        }

        public long completedCycles() {
            return completedCycles;
        }

        public int currentSliceIndex() {
            return sliceIndex;
        }

        public int sliceCount() {
            return sliceCount;
        }

        public void clear() {
            dimension = "";
            minX = maxX = minZ = maxZ = 0;
            sliceIndex = 0;
            sliceCount = 0;
            completedCycles = 0L;
            configured = false;
            retainedOverlap = false;
            pagePlan = new long[0];
        }

        private static long[] buildPlan(int minX, int maxX,
                int minZ, int maxZ) {
            int width = Math.max(0, maxX - minX + 1);
            int height = Math.max(0, maxZ - minZ + 1);
            int total = width * height;
            if (total == 0) return new long[0];

            /*
             * Xaero shortlists the closest viewed regions rather than walking a
             * screen edge. Generate the same deterministic centre-out authority
             * without sorting or temporary distance arrays. Retained overlap is
             * still preserved by the caller for source ownership; satisfied centre
             * pages are skipped cheaply and newly exposed nearby pages are then
             * admitted before distant edges.
             */
            long[] result = new long[total];
            int centerX = minX + (maxX - minX) / 2;
            int centerZ = minZ + (maxZ - minZ) / 2;
            int maximumRadius = Math.max(
                    Math.max(centerX - minX, maxX - centerX),
                    Math.max(centerZ - minZ, maxZ - centerZ));
            int ordinal = 0;
            for (int radius = 0; radius <= maximumRadius; radius++) {
                int left = centerX - radius;
                int right = centerX + radius;
                int top = centerZ - radius;
                int bottom = centerZ + radius;
                if (top >= minZ && top <= maxZ) {
                    for (int x = Math.max(minX, left);
                            x <= Math.min(maxX, right); x++) {
                        result[ordinal] = pack(x, top);
                        ordinal++;
                    }
                }
                if (radius == 0) continue;
                for (int z = Math.max(minZ, top + 1);
                        z <= Math.min(maxZ, bottom - 1); z++) {
                    if (left >= minX && left <= maxX) {
                        result[ordinal] = pack(left, z);
                        ordinal++;
                    }
                    if (right != left && right >= minX && right <= maxX) {
                        result[ordinal] = pack(right, z);
                        ordinal++;
                    }
                }
                if (bottom >= minZ && bottom <= maxZ) {
                    for (int x = Math.max(minX, left);
                            x <= Math.min(maxX, right); x++) {
                        result[ordinal] = pack(x, bottom);
                        ordinal++;
                    }
                }
            }
            return result;
        }

        private static boolean rectanglesOverlap(int firstMinX, int firstMaxX,
                int firstMinZ, int firstMaxZ, int secondMinX, int secondMaxX,
                int secondMinZ, int secondMaxZ) {
            return firstMinX <= secondMaxX && firstMaxX >= secondMinX
                    && firstMinZ <= secondMaxZ && firstMaxZ >= secondMinZ;
        }
    }

    /** Covers the loaded chunk radius, page-alignment slack and one repair halo. */
    public static int minimapWorkingRadiusPages(int renderDistanceChunks) {
        int loadedRadiusChunks = Math.max(2, renderDistanceChunks + 3);
        int alignedPages = (loadedRadiusChunks + 3) / 4;
        return Math.max(2, Math.min(MINIMAP_MAX_RADIUS_PAGES,
                alignedPages + MINIMAP_HALO_PAGES));
    }

    /**
     * Fills a bounded minimap exact-leaf halo in deterministic centre-out order.
     * The visible rectangle is expanded by one page, then capped to the caller's
     * loaded-world working radius so extreme zoom cannot flood the exact pipeline.
     */
    public static int fillMinimapHalo(int visibleMinX, int visibleMaxX,
            int visibleMinZ, int visibleMaxZ, int centerX, int centerZ,
            long[] output) {
        return fillMinimapHalo(visibleMinX, visibleMaxX,
                visibleMinZ, visibleMaxZ, centerX, centerZ,
                MINIMAP_MAX_RADIUS_PAGES, output);
    }

    public static int fillMinimapHalo(int visibleMinX, int visibleMaxX,
            int visibleMinZ, int visibleMaxZ, int centerX, int centerZ,
            int maximumRadiusPages, long[] output) {
        if (output == null || output.length == 0) return 0;
        int safeRadius = Math.max(1,
                Math.min(MINIMAP_MAX_RADIUS_PAGES, maximumRadiusPages));
        int minX = Math.max(Math.min(visibleMinX, visibleMaxX) - MINIMAP_HALO_PAGES,
                centerX - safeRadius);
        int maxX = Math.min(Math.max(visibleMinX, visibleMaxX) + MINIMAP_HALO_PAGES,
                centerX + safeRadius);
        int minZ = Math.max(Math.min(visibleMinZ, visibleMaxZ) - MINIMAP_HALO_PAGES,
                centerZ - safeRadius);
        int maxZ = Math.min(Math.max(visibleMinZ, visibleMaxZ) + MINIMAP_HALO_PAGES,
                centerZ + safeRadius);
        int maximumRadius = Math.max(
                Math.max(Math.abs(centerX - minX), Math.abs(maxX - centerX)),
                Math.max(Math.abs(centerZ - minZ), Math.abs(maxZ - centerZ)));
        int count = 0;
        for (int radius = 0; radius <= maximumRadius && count < output.length;
                radius++) {
            int left = centerX - radius;
            int right = centerX + radius;
            int top = centerZ - radius;
            int bottom = centerZ + radius;
            if (top >= minZ && top <= maxZ) {
                for (int x = Math.max(minX, left);
                        x <= Math.min(maxX, right) && count < output.length; x++) {
                    output[count++] = pack(x, top);
                }
            }
            if (radius == 0) continue;
            for (int z = Math.max(minZ, top + 1);
                    z <= Math.min(maxZ, bottom - 1) && count < output.length; z++) {
                if (left >= minX && left <= maxX) output[count++] = pack(left, z);
                if (count >= output.length) break;
                if (right != left && right >= minX && right <= maxX) {
                    output[count++] = pack(right, z);
                }
            }
            if (bottom >= minZ && bottom <= maxZ) {
                for (int x = Math.max(minX, left);
                        x <= Math.min(maxX, right) && count < output.length; x++) {
                    output[count++] = pack(x, bottom);
                }
            }
        }
        return count;
    }

    /** Compatibility overload; production uses the allocation-free long buffer. */
    public static int fillMinimapHalo(int visibleMinX, int visibleMaxX,
            int visibleMinZ, int visibleMaxZ, int centerX, int centerZ,
            Page[] output) {
        if (output == null || output.length == 0) return 0;
        long[] packed = new long[output.length];
        int count = fillMinimapHalo(visibleMinX, visibleMaxX,
                visibleMinZ, visibleMaxZ, centerX, centerZ, packed);
        for (int index = 0; index < count; index++) {
            output[index] = new Page(packedX(packed[index]), packedZ(packed[index]), index);
        }
        return count;
    }

    static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    static int packedX(long packed) {
        return (int) (packed >> 32);
    }

    static int packedZ(long packed) {
        return (int) packed;
    }

    public record Page(int x, int z, int ordinal) {
        public int distanceSquaredTo(int focusX, int focusZ) {
            int dx = x - focusX;
            int dz = z - focusZ;
            return dx * dx + dz * dz;
        }
    }
}
