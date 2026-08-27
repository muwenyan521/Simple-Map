package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS76 guard for native-region source ownership and bounded region projection. */
public final class CaveNativeRegionBatchImportCheck {
    private CaveNativeRegionBatchImportCheck() { }

    public static void main(String[] args) throws Exception {
        require(CaveNativeRegionImportService.REGION_CHUNKS == 32,
                "native Anvil region must remain 32x32 chunks");
        require(CaveNativeRegionImportService.REGION_PAGES == 8,
                "native region must contain 8x8 64-block pages");
        require(CaveNativeRegionImportService.SOURCE_EDGE == 34,
                "region source transaction must include a one-chunk halo");
        long[] chunkMask = new long[16];
        int local = 7 * 32 + 5;
        chunkMask[local >>> 6] |= 1L << (local & 63);
        AnvilPagePresenceIndex.RegionSnapshot snapshot =
                new AnvilPagePresenceIndex.RegionSnapshot(
                        3, -2, 1L, 11L, chunkMask, 1, true);
        require(snapshot.hasChunk(3 * 32 + 5, -2 * 32 + 7),
                "generated chunk bitmap lost a present header entry");
        require(!snapshot.hasChunk(3 * 32 + 6, -2 * 32 + 7),
                "generated chunk bitmap cannot distinguish absent chunks");

        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String reader = Files.readString(root.resolve("CaveWorldSaveReader.java"));
        String importer = Files.readString(
                root.resolve("CaveNativeRegionImportService.java"));
        String projection = Files.readString(
                root.resolve("CaveRegionProjectionService.java"));
        String presence = Files.readString(
                root.resolve("AnvilPagePresenceIndex.java"));

        require(reader.contains("nativeRegionImporter.requestViewport")
                        && reader.contains("cancelLegacyFullscreenPageTasksLocked")
                        && reader.contains("one native 32x32-chunk transaction"),
                "fullscreen still admits independent page-source transactions");
        require(importer.contains("SOURCE_EDGE = REGION_CHUNKS + SOURCE_HALO * 2")
                        && importer.contains("sourceCache.requestReservedLease")
                        && importer.contains("prepareProjectionBundle")
                        && importer.contains("surfaceRequiredSources")
                        && importer.contains("caveRequiredSources")
                        && importer.contains("generatedPageMask()")
                        && importer.contains("retainProjection(requestedView, requestedTopY)")
                        && importer.contains("requiredSourcesSettledForPass()")
                        && importer.contains("CAVE_NATIVE_REGION_SOURCE_SETTLED")
                        && importer.contains("cancelUnneededSourcesLocked()")
                        && importer.contains("archive.hasFullProjectionChunk")
                        && importer.contains("archive.hasCompleteChunk")
                        && importer.contains("refreshPresence(presenceIndex")
                        && importer.contains("applyPresenceDelta(snapshot"),
                "native region import does not own/cancel source or reuse retained archive authority");
        require(projection.contains("REGION_PAGE_SLICE = 24")
                        && projection.contains("regionQueue")
                        && projection.contains("foregroundMask")
                        && projection.contains("orderedPendingOrdinals")
                        && projection.contains("CAVE_NATIVE_REGION_PROJECTION_SLICE")
                        && projection.contains("releaseForegroundBatchLocked")
                        && projection.contains("stageRegionLocked(page)"),
                "region projection is not sliced or background pages can flood GPU publication");
        require(presence.contains("long[] bits = new long[16]")
                        && presence.contains("RegionSnapshot")
                        && presence.contains("boolean hasChunk(int chunkX, int chunkZ)"),
                "Anvil header presence is not reused to resolve absent chunks without NBT reads");
        require(importer.contains("CAVE_NATIVE_REGION_VISIBLE_SOURCE_READY")
                        && importer.contains("region.lane = MapRequestLane.BACKGROUND"),
                "region source work does not demote after visible dependencies are ready");

        System.out.println("CAVE_NATIVE_REGION_BATCH_IMPORT_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
