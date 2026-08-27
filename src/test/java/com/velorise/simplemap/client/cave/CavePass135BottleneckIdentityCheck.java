package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Guards PASS134 exact-product identity and local invalidation bottleneck fixes. */
public final class CavePass135BottleneckIdentityCheck {
    private CavePass135BottleneckIdentityCheck() { }

    public static void main(String[] args) throws Exception {
        System.setProperty("simplemap.disableDebugTelemetry", "true");
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String key = Files.readString(root.resolve("DenseCaveTileKey.java"));
        String repository = Files.readString(root.resolve("CaveTileRepository.java"));
        String store = Files.readString(root.resolve("CaveDisplayRegionStore.java"));
        String projection = Files.readString(
                root.resolve("CaveRegionProjectionService.java"));
        String manager = Files.readString(
                root.resolve("UnifiedCaveTextureManager.java"));
        String importer = Files.readString(
                root.resolve("CaveNativeRegionImportService.java"));
        String pipeline = Files.readString(root.resolve("CavePipeline.java"));

        require(key.contains("int bandY, int projectionTopY")
                        && key.contains("static DenseCaveTileKey of(")
                        && repository.contains("getExactDisplayTile")
                        && repository.contains("getLastGoodBandTile")
                        && !repository.contains("CAVE_LAYER_BAND_ALIAS_BYPASSED"),
                "Layered dense authority is still collapsed to one band slot");
        require(store.contains("private static final int REGION_VERSION = 9;")
                        && store.contains("private static final int TILE_VERSION = 9;")
                        && store.contains("output.writeInt(tile.projectionTopY())")
                        && store.contains("return \"c5-m\""),
                "persistent dense index does not retain exact Top-Y identity");

        require(projection.contains("EnumMap<MapRequestLane, CavePresentationGeneration> subscribers")
                        && projection.contains("retainCompletedProductLocked")
                        && projection.contains("CAVE_PROJECTION_PRODUCT_REUSED")
                        && projection.contains("CAVE_PROJECTION_PRODUCT_READY_NO_CURRENT_SUBSCRIBER")
                        && manager.contains("presentationGenerationOwned(imported.presentationGeneration())")
                        && manager.contains("|| isProjectionStillOwned("),
                "projection products are still owned solely by a late UI generation");

        require(importer.contains("AFFECTED_PAGE_MASKS")
                        && importer.contains("buildAffectedPageMasks()")
                        && importer.contains("changedPageMask & demand.pageMask")
                        && importer.contains("demand.foregroundSubmittedMask &= ~affected")
                        && !importer.contains(
                                "Arrays.fill(demand.submittedSourceRevisions"),
                "one presence wake still resets every page in the native region");
        require(repository.contains("ArchivePublicationTicket")
                        && repository.contains("queuedArchivePublications")
                        && repository.contains("activeArchivePublications")
                        && repository.contains("MapWorkScheduler.tryCpuFuture")
                        && repository.contains("CompactCaveTile.fromLegacy(ticket.snapshot())")
                        && repository.contains("thread=cpu_worker")
                        && pipeline.contains("repository.pumpArchivePublications(1)"),
                "compact archive materialization can still escape the client budget");

        verifyExactVariantsCoexist();
        System.out.println("CAVE_PASS135_BOTTLENECK_IDENTITY_PASS");
    }

    private static void verifyExactVariantsCoexist() throws Exception {
        int chunkX = 1_000_003;
        int chunkZ = -1_000_019;
        DenseCaveTileKey lower = DenseCaveTileKey.of(
                chunkX, chunkZ, CaveView.LAYERED, -28);
        DenseCaveTileKey upper = DenseCaveTileKey.of(
                chunkX, chunkZ, CaveView.LAYERED, -27);
        require(lower.bandY() == upper.bandY() && !lower.equals(upper),
                "same-band exact Top-Y keys still alias");

        CaveTileRepository repository = CaveTileRepository.getInstance();
        long generation = repository.generation();
        DenseCaveTile first = tile(chunkX, chunkZ, -28, 0xFF223344, 11L);
        DenseCaveTile second = tile(chunkX, chunkZ, -27, 0xFF556677, 12L);
        require(repository.commitDisplayTile(first, generation)
                        && repository.commitDisplayTile(second, generation),
                "repository rejected independent exact variants");
        require(repository.getExactDisplayTile(CaveView.LAYERED, -28,
                        chunkX, chunkZ) == first
                        && repository.getExactDisplayTile(CaveView.LAYERED, -27,
                                chunkX, chunkZ) == second,
                "committing a neighbouring slice replaced the previous exact tile");

        Path directory = Files.createTempDirectory("simplemap-cvd-exact-");
        try {
            Map<DenseCaveTileKey, CaveDisplayRegionStore.RecordPointer> appended =
                    CaveDisplayRegionStore.append(directory.toFile(),
                            List.of(first, second));
            Map<DenseCaveTileKey, CaveDisplayRegionStore.RecordPointer> rebuilt =
                    CaveDisplayRegionStore.rebuildIndex(directory.toFile());
            require(appended.size() == 2 && rebuilt.size() == 2,
                    "persistent dense index collapsed exact variants");
            require(CaveDisplayRegionStore.read(directory.toFile(),
                            rebuilt.get(lower)).projectionTopY() == -28
                            && CaveDisplayRegionStore.read(directory.toFile(),
                                    rebuilt.get(upper)).projectionTopY() == -27,
                    "persistent dense replay lost exact Top-Y");
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                });
            }
        }
        for (int topY = -26; topY <= -24; topY++) {
            require(repository.commitDisplayTile(
                            tile(chunkX, chunkZ, topY,
                                    0xFF000000 | (topY & 0x00FFFFFF),
                                    100L + topY),
                            generation),
                    "repository rejected a bounded exact variant");
        }
        require(repository.getExactDisplayTile(CaveView.LAYERED, -28,
                        chunkX, chunkZ) == null
                        && repository.getExactDisplayTile(CaveView.LAYERED, -24,
                                chunkX, chunkZ) != null,
                "same-band exact variant retention is not bounded to four");
        repository.invalidateDisplayTile(chunkX, chunkZ);
    }

    private static DenseCaveTile tile(int chunkX, int chunkZ, int topY,
            int color, long revision) {
        DenseCaveTile.Builder builder = new DenseCaveTile.Builder();
        builder.beginColumn();
        builder.set(0, 0, color, topY - 8, topY,
                (byte) 0, 0);
        return builder.build(chunkX, chunkZ, CaveView.LAYERED,
                CaveLayerBand.key(CaveView.LAYERED, topY), topY,
                revision, DenseCaveTile.Source.LIVE);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
