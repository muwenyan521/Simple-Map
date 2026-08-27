package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapRequestLane;

import java.util.Set;

/** Dependency-free ordering invariants for fixed-region cave streaming. */
public final class CaveLoadHierarchyCheck {
    private CaveLoadHierarchyCheck() { }

    public static void main(String[] args) {
        int[] sourceWindow = CaveLoadHierarchy.buildCenterOutCellOrder(6);
        require(sourceWindow.length == 36
                        && sourceWindow[0] == 14 && sourceWindow[1] == 15
                        && sourceWindow[2] == 20 && sourceWindow[3] == 21,
                "haloed source window no longer starts with its compact 2x2 core");
        verifyRectangle(-4, 5, -2, 6, 1, 2);
        verifyRectangle(1, 5, 0, 3, 3, 1);
        verifyRectangle(-8, -1, -8, -1, -3, -5);
        verifyRectangle(0, 0, 0, 0, 0, 0);
        verifyFarZoomPolicy();
        verifyAnvilPresenceFilter();
        verifyRegionReadPlan();
        exhaustiveSmallRectangles();
        System.out.println("Simple Map Cave viewport scanline hierarchy checks passed");
    }

    private static void verifyAnvilPresenceFilter() {
        long[] plan = CaveLoadHierarchy.buildVisiblePagePlan(
                -2, 10, -1, 9, 3, 3, true);
        Set<Long> present = Set.of(
                CaveLoadHierarchy.pack(-1, -1),
                CaveLoadHierarchy.pack(2, -1),
                CaveLoadHierarchy.pack(0, 0),
                CaveLoadHierarchy.pack(8, 8));
        long[] filtered = CaveLoadHierarchy.retainPresentPages(plan, present);
        CaveLoadHierarchy.OrdinalIndex ordinals =
                CaveLoadHierarchy.buildOrdinalIndex(plan);
        require(filtered.length == present.size(),
                "Anvil presence filter retained empty viewport pages");
        int previousOrdinal = -1;
        for (long page : filtered) {
            require(present.contains(page),
                    "Anvil presence filter manufactured a page");
            int ordinal = ordinals.getOrDefault(page, -1);
            require(ordinal > previousOrdinal,
                    "Anvil presence filter broke region-grid priority");
            previousOrdinal = ordinal;
        }
    }

    private static void verifyRegionReadPlan() {
        long[] pages = CaveLoadHierarchy.buildVisiblePagePlan(
                -9, 11, -7, 12, 0, 0, true);
        long[] regions = CaveLoadHierarchy.buildRegionPlanFromPagePlan(pages);
        java.util.LinkedHashSet<Long> expected = new java.util.LinkedHashSet<>();
        for (long page : pages) {
            expected.add(CaveLoadHierarchy.pack(
                    Math.floorDiv(CaveLoadHierarchy.x(page),
                            CaveLoadHierarchy.PAGES_PER_REGION),
                    Math.floorDiv(CaveLoadHierarchy.z(page),
                            CaveLoadHierarchy.PAGES_PER_REGION)));
        }
        require(regions.length == expected.size(),
                "region prefetch plan duplicated a 512x512 cache file");
        int cursor = 0;
        for (long region : expected) {
            require(regions[cursor++] == region,
                    "region prefetch order no longer follows first visible page");
        }
    }

    private static void verifyFarZoomPolicy() {
        require(CaveScreenSpacePolicy.branchOnly(0.2956f,
                        MapRequestLane.FULLSCREEN),
                "logged 0.296x cave viewport still admits an exact-page flood");
        require(!CaveScreenSpacePolicy.restrictLiveProjectionToFocusPage(0.2488f,
                        MapRequestLane.FULLSCREEN),
                "far zoom still collapses fullscreen source coverage to the focus page");
        require(!CaveScreenSpacePolicy.restrictLiveProjectionToFocusPage(0.10f,
                        MapRequestLane.MINIMAP),
                "minimap again clips live projection to one 4x4-chunk page");
        require(!CaveScreenSpacePolicy.branchOnly(0.35f,
                        MapRequestLane.FULLSCREEN),
                "near zoom lost exact cave refinement");
        require(CaveScreenSpacePolicy.branchOnly(0.10f,
                        MapRequestLane.MINIMAP),
                "far-zoom minimap no longer reuses the shared cave LOD cache");
    }

    private static void exhaustiveSmallRectangles() {
        for (int width = 1; width <= 9; width++) {
            for (int height = 1; height <= 9; height++) {
                int minX = -4;
                int maxX = minX + width - 1;
                int minZ = 3;
                int maxZ = minZ + height - 1;
                for (int centerX = minX - 1; centerX <= maxX + 1; centerX++) {
                    for (int centerZ = minZ - 1; centerZ <= maxZ + 1; centerZ++) {
                        verifyRectangle(minX, maxX, minZ, maxZ,
                                centerX, centerZ);
                    }
                }
            }
        }
    }

    private static void verifyRectangle(int minX, int maxX,
            int minZ, int maxZ, int centerX, int centerZ) {
        long[] plan = CaveLoadHierarchy.buildVisiblePagePlan(
                minX, maxX, minZ, maxZ, centerX, centerZ, true);
        int expected = (maxX - minX + 1) * (maxZ - minZ + 1);
        require(plan.length == expected, "visible plan size changed");
        CaveLoadHierarchy.OrdinalIndex ordinals =
                CaveLoadHierarchy.buildOrdinalIndex(plan);

        for (int ordinal = 0; ordinal < plan.length; ordinal++) {
            long page = plan[ordinal];
            int x = CaveLoadHierarchy.x(page);
            int z = CaveLoadHierarchy.z(page);
            require(CaveLoadHierarchy.scanlineOrdinal(
                    minX, maxX, minZ, maxZ, x, z) == ordinal,
                    "fullscreen viewport scanline ordinal mismatch");
            require(ordinals.getOrDefault(page, -1) == ordinal,
                    "wavefront ordinal index mismatch");
        }

        long[] minimap = CaveLoadHierarchy.buildVisiblePagePlan(
                minX, maxX, minZ, maxZ, centerX, centerZ, false);
        for (int ordinal = 0; ordinal < minimap.length; ordinal++) {
            int x = CaveLoadHierarchy.x(minimap[ordinal]);
            int z = CaveLoadHierarchy.z(minimap[ordinal]);
            require(CaveLoadHierarchy.centerOutOrdinal(
                    minX, maxX, minZ, maxZ,
                    centerX, centerZ, x, z) == ordinal,
                    "minimap centre-out ordinal mismatch");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
