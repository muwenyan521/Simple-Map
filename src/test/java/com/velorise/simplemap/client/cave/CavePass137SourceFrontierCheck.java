package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;

/** Guards PASS137/PASS138 retained demand and bounded/fair source scheduling. */
public final class CavePass137SourceFrontierCheck {
    private CavePass137SourceFrontierCheck() { }

    public static void main(String[] args) throws Exception {
        String importer = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/"
                        + "CaveNativeRegionImportService.java"));
        require(importer.contains("class RegionSourceFrontier")
                        && importer.contains("reusedRefreshes")
                        && importer.contains("needsUpdate(")
                        && importer.contains("requiredSinceMs")
                        && importer.contains("SOURCE_FRONTIER_REBUILT")
                        && !importer.contains("sourceCursor = 0")
                        && !importer.contains("sourceOrderVisibleMask"),
                "source frontier can still reset on every viewport observation");
        require(importer.contains("scoreSource(int index, long nowMs)")
                        && importer.contains("SourcePriorityScratch")
                        && importer.contains("SOURCE_SELECTION_LOOKAHEAD")
                        && importer.contains("VISIBLE_SOURCE_FAIRNESS_MS")
                        && importer.contains("sourceFrontier.advanceCursor(cursor + inspected)")
                        && importer.contains("SOURCE_ADMISSION_SUMMARY")
                        && importer.contains("VISIBLE_CAVE_SOURCE_WAIT_SUMMARY")
                        && importer.contains("central children before")
                        && importer.contains("if (localX > 0 && localX < 5"),
                "source scheduling is not bounded/fair/central-first");
        require(!hasGlobalCavePreclear(importer),
                "requestViewport still clears every cave demand before re-applying it");

        verifyIdempotentFrontier();
        verifyFocusCentralOrder();
        System.out.println("CAVE_PASS137_SOURCE_FRONTIER_PASS");
    }

    private static void verifyIdempotentFrontier() {
        int regionX = 3;
        int regionZ = -2;
        int focusX = regionX * CaveNativeRegionImportService.REGION_PAGES + 2;
        int focusZ = regionZ * CaveNativeRegionImportService.REGION_PAGES + 5;
        long pageMask = 1L << (5 * 8 + 2);
        BitSet required = requiredFor(regionX, regionZ, pageMask, focusX, focusZ);
        CaveNativeRegionImportService.RegionSourceFrontier frontier =
                new CaveNativeRegionImportService.RegionSourceFrontier();

        CaveNativeRegionImportService.FrontierUpdate first = frontier.updateDemand(
                required, 0L, pageMask, pageMask, focusX, focusZ,
                regionX, regionZ, 1_000L);
        require(first.changed() && first.generation() == 1L,
                "initial structural demand did not create a frontier");
        frontier.advanceCursor(11);
        int cursor = frontier.cursor();
        long generation = frontier.debug().generation();
        long waitAge = frontier.waitAgeMs(frontier.ordered()[0], 1_100L);

        CaveNativeRegionImportService.FrontierUpdate repeated =
                frontier.updateDemand(null, 0L, pageMask, pageMask,
                        focusX, focusZ, regionX, regionZ, 1_100L);
        require(!repeated.changed() && frontier.cursor() == cursor
                        && frontier.debug().generation() == generation
                        && frontier.debug().reusedRefreshes() == 1L,
                "identical demand rebuilt or rewound the retained frontier");

        int movedFocusX = focusX + 1;
        CaveNativeRegionImportService.FrontierUpdate focusChanged =
                frontier.updateDemand(frontier.requiredCopy(), 0L, pageMask,
                        pageMask, movedFocusX, focusZ,
                        regionX, regionZ, 1_200L);
        require(focusChanged.changed() && focusChanged.focusChanged()
                        && frontier.cursor() == cursor
                        && frontier.waitAgeMs(frontier.ordered()[0], 1_200L)
                                >= waitAge,
                "focus rerank discarded existing cursor/wait-age authority");
    }

    private static void verifyFocusCentralOrder() {
        int regionX = -4;
        int regionZ = 7;
        int focusLocalX = 6;
        int focusLocalZ = 1;
        int farLocalX = 0;
        int farLocalZ = 7;
        long pageMask = 1L << (focusLocalZ * 8 + focusLocalX)
                | 1L << (farLocalZ * 8 + farLocalX);
        int focusX = regionX * 8 + focusLocalX;
        int focusZ = regionZ * 8 + focusLocalZ;
        int[] order = CaveNativeRegionImportService.buildSourceOrder(
                regionX, regionZ, pageMask, focusX, focusZ);
        require(order.length > 32, "source plan lost required styling halo");
        for (int ordinal = 0; ordinal < 16; ordinal++) {
            require(isCentralForPage(order[ordinal], focusLocalX, focusLocalZ),
                    "focus page did not own the first sixteen central cells");
        }
        for (int ordinal = 16; ordinal < 32; ordinal++) {
            require(isCentralForPage(order[ordinal], farLocalX, farLocalZ),
                    "a styling halo ran before another visible central child");
        }
    }


    private static boolean hasGlobalCavePreclear(String importer) {
        int request = importer.indexOf("synchronized void requestViewport");
        int masks = importer.indexOf("Map<Long, Long> sourceMasks", request);
        if (request < 0 || masks < 0) return true;
        String preamble = importer.substring(request, masks);
        return preamble.contains("existing.clearCaveDemand()");
    }

    private static BitSet requiredFor(int regionX, int regionZ, long pageMask,
            int focusX, int focusZ) {
        BitSet required = new BitSet(CaveNativeRegionImportService.SOURCE_COUNT);
        for (int index : CaveNativeRegionImportService.buildSourceOrder(
                regionX, regionZ, pageMask, focusX, focusZ)) {
            required.set(index);
        }
        return required;
    }

    private static boolean isCentralForPage(int sourceIndex,
            int localPageX, int localPageZ) {
        int sourceX = sourceIndex % CaveNativeRegionImportService.SOURCE_EDGE;
        int sourceZ = sourceIndex / CaveNativeRegionImportService.SOURCE_EDGE;
        int minimumX = localPageX * 4 + CaveNativeRegionImportService.SOURCE_HALO;
        int minimumZ = localPageZ * 4 + CaveNativeRegionImportService.SOURCE_HALO;
        return sourceX >= minimumX && sourceX < minimumX + 4
                && sourceZ >= minimumZ && sourceZ < minimumZ + 4;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
