package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.cave.archive.CompactCaveTile;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS134 live authority routing and canonical lava composition. */
public final class CavePass134PostAuthorityPerformanceCheck {
    private CavePass134PostAuthorityPerformanceCheck() { }

    public static void main(String[] args) throws Exception {
        Path cave = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String pipeline = Files.readString(cave.resolve("CavePipeline.java"));
        String importer = Files.readString(
                cave.resolve("CaveNativeRegionImportService.java"));
        String tile = Files.readString(cave.resolve("CaveChunkTile.java"));
        String repository = Files.readString(cave.resolve("CaveTileRepository.java"));
        String liveScanner = Files.readString(cave.resolve("CaveTileScanner.java"));
        String decoded = Files.readString(
                cave.resolve("DecodedWorldChunkSource.java"));
        String semantics = Files.readString(
                cave.resolve("CaveProjectionSemantics.java"));
        String style = Files.readString(cave.resolve("CaveProjectionStyle.java"));
        String store = Files.readString(cave.resolve("CaveRegionStore.java"));
        String displayStore = Files.readString(
                cave.resolve("CaveDisplayRegionStore.java"));
        String lod = Files.readString(cave.resolve("CaveLodTree.java"));
        String persistence = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/persistence/v2/"
                        + "MapPersistenceV2Service.java"));

        int repairStart = pipeline.indexOf("void repairTransientDiskAbsence");
        int repairEnd = pipeline.indexOf("public CaveTelemetry.Snapshot", repairStart);
        require(repairStart >= 0 && repairEnd > repairStart,
                "missing live disk-absence repair callback");
        String repair = pipeline.substring(repairStart, repairEnd);
        require(repair.contains("liveRepairInbox.offer")
                        && !repair.contains("minecraft.execute")
                        && !repair.contains("invalidateTile")
                        && !repair.contains("markDisplayRangeStaleAllLayers"),
                "callback still escapes budget or invalidates content/display state");
        require(pipeline.contains("Math.min(80_000L, governed / 8L)")
                        && pipeline.contains("drainLiveRepairInbox")
                        && pipeline.contains("MAX_KEYS = 16_384")
                        && pipeline.contains("LongOpenHashSet")
                        && pipeline.contains("resumeDiskAfterLiveUnavailable"),
                "live repair inbox is not hard-budgeted, bounded and deduplicated");

        require(importer.contains("Long2ObjectOpenHashMap<ArrayList<RegionCellRef>>")
                        && importer.contains("getOrCreateRegion")
                        && importer.contains("indexRegion(region)")
                        && importer.contains("unindexRegion(region)")
                        && importer.contains("BitSet liveSourcePending")
                        && importer.contains("sourceRetryAfterMs[index] = Long.MAX_VALUE")
                        && !importer.contains("wakeTransientDiskAbsenceByLive"),
                "native importer still scans every region or wakes disk under LIVE");
        require(tile.contains("LIVE_PENDING") && tile.contains("LIVE_COMPLETE")
                        && repository.contains("ensureLiveAuthority")
                        && repository.contains("LiveAuthorityClaim"),
                "live authority lifecycle is missing or not idempotent");

        require(liveScanner.contains("runFluidAlpha")
                        && decoded.contains("runFluidAlpha")
                        && !decoded.contains("blendArchiveEmissive")
                        && !liveScanner.contains("blendOverlay(")
                        && !decoded.contains("blendOverlay(")
                        && repository.contains("copySemanticOverlay")
                        && repository.contains("semanticOverlayCount"),
                "live/decoded archive writers still compose lava differently");
        require(style.contains("STYLE_SIGNATURE_VERSION = 20")
                        && store.contains("private static final int REGION_VERSION = 6;")
                        && displayStore.contains(
                                "private static final int REGION_VERSION = 9;")
                        && lod.contains("cave_v12_")
                        && persistence.contains("0x4341563700000000L"),
                "old baked cave material cache namespaces remain valid");

        verifyLiveLifecycle();
        verifyFluidComposition();
        System.out.println("CAVE_PASS134_POST_AUTHORITY_PERFORMANCE_PASS");
    }

    private static void verifyLiveLifecycle() {
        CaveChunkTile tile = new CaveChunkTile(3, -7, true);
        require(tile.ensureLiveAuthority(), "new live claim did not enter pending");
        require(tile.liveAuthorityState()
                        == CaveChunkTile.LiveAuthorityState.LIVE_PENDING,
                "new live claim has wrong lifecycle state");
        CaveColumnData empty = CaveColumnData.emptyScanned(-64, 80, true);
        for (int index = 0; index < CaveChunkTile.COLUMN_COUNT; index++) {
            tile.commitColumn(index, empty);
        }
        require(tile.liveAuthorityState()
                        == CaveChunkTile.LiveAuthorityState.LIVE_COMPLETE,
                "complete canonical scan did not close live lifecycle");
        require(!tile.ensureLiveAuthority() && tile.scannedColumnCount() == 256,
                "repeated live claim was not idempotent");
    }

    private static void verifyFluidComposition() {
        int floor = 0xFF4A4A4A;
        int lava = 0xFF18A8FF;
        CaveColumnData.Builder builder = new CaveColumnData.Builder();
        builder.add(40, 20, floor,
                (byte) (CaveColumnData.FLAG_FLUID
                        | CaveColumnData.FLAG_EMISSIVE),
                lava, 214, 32, 15, 4,
                CaveColumnData.FLUID_FLAG_EMISSIVE,
                0, 0, 0, 0);
        CaveColumnData column = builder.build(-64, 80, true);
        require(column.color(0) == floor && column.fluidColor(0) == lava
                        && Byte.toUnsignedInt(column.fluidAlpha(0)) == 214,
                "canonical column baked lava into the floor material");
        CaveChunkTile tile = new CaveChunkTile(0, 0, true);
        tile.commitColumn(0, column);
        CompactCaveTile compact = CompactCaveTile.fromLegacy(tile.snapshot());
        require(compact.materialId(0) == floor && compact.fluidColor(0) == lava
                        && Byte.toUnsignedInt(compact.fluidAlpha(0)) == 214
                        && (compact.fluidFlags(0)
                                & CaveColumnData.FLUID_FLAG_EMISSIVE) != 0,
                "compact archive lost semantic lava payload");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
