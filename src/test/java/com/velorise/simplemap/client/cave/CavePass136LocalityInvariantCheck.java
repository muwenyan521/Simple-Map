package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS136-A/B per-region presence IO and chunk-local exact-variant trim. */
public final class CavePass136LocalityInvariantCheck {
    private CavePass136LocalityInvariantCheck() { }

    public static void main(String[] args) throws Exception {
        System.setProperty("simplemap.disableDebugTelemetry", "true");
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String presence = Files.readString(root.resolve("AnvilPagePresenceIndex.java"));
        String importer = Files.readString(
                root.resolve("CaveNativeRegionImportService.java"));
        String pipeline = Files.readString(
                root.resolve("WorldSaveProjectionPipeline.java"));
        String reader = Files.readString(root.resolve("CaveWorldSaveReader.java"));
        String repository = Files.readString(root.resolve("CaveTileRepository.java"));

        require(!presence.contains("DirectoryStream")
                        && !presence.contains("newDirectoryStream")
                        && presence.contains("MAX_CACHE = 256")
                        && presence.contains("getCached(ServerLevel level")
                        && presence.contains("requestAsync(ServerLevel level")
                        && presence.contains("MapWorkScheduler.tryIoFuture")
                        && presence.contains("ThreadLocal<ByteBuffer>")
                        && presence.contains("HEADER_BYTES = 4_096")
                        && presence.contains("region_files_scanned=0")
                        && presence.contains("ANVIL_PRESENCE_REGION_READY"),
                "presence lookup can still enumerate/read the whole world on a hot path");
        require(!pipeline.contains("presenceIndex.snapshot")
                        && !reader.contains("anvilPresence.snapshot")
                        && !reader.contains("applySourceFilter")
                        && reader.contains("nativeRegionImporter.isPageKnownAbsent")
                        && importer.contains("refreshPresence(presenceIndex")
                        && importer.contains("isPageKnownAbsent")
                        && importer.contains("sourcePresent.get(index)")
                        && importer.contains("presenceKnown")
                        && importer.contains("presenceAbsent")
                        && importer.contains(
                                "previous.chunkBits[word] ^ currentBits[word]")
                        && importer.contains("ANVIL_PRESENCE_DELTA_APPLIED")
                        && !importer.contains(
                                "CAVE_TRANSIENT_ABSENT_WOKEN_BY_PRESENCE"),
                "native import does not apply one region-local XOR presence delta");
        require(repository.contains("trimExactBandVariantsForChunkLocked")
                        && !repository.contains(
                                "private void trimExactBandVariantsLocked")
                        && repository.contains(
                                "Set<DenseCaveTileKey> chunkKeys = displayKeysByChunk.get")
                        && repository.contains("DenseVariantTrimStats")
                        && repository.contains("CAVE_DENSE_VARIANT_TRIM_SUMMARY"),
                "dense exact-variant retention still scans global display authority");

        verifyRegionBitmap();
        verifyChunkLocalVariantTrim();
        System.out.println("CAVE_PASS136_LOCALITY_INVARIANT_PASS");
    }

    private static void verifyRegionBitmap() {
        long[] bits = new long[16];
        int local = 31 * 32 + 2;
        bits[local >>> 6] |= 1L << (local & 63);
        AnvilPagePresenceIndex.RegionSnapshot snapshot =
                new AnvilPagePresenceIndex.RegionSnapshot(
                        -4, 7, 9L, 13L, bits, 1, true);
        require(snapshot.hasChunk(-4 * 32 + 2, 7 * 32 + 31),
                "region snapshot lost its exact present chunk");
        require(!snapshot.hasChunk(-4 * 32 + 3, 7 * 32 + 31)
                        && !snapshot.hasChunk(-4 * 32 + 2, 8 * 32),
                "region snapshot leaked presence across a cell or region boundary");
        bits[local >>> 6] = 0L;
        require(snapshot.hasChunk(-4 * 32 + 2, 7 * 32 + 31),
                "published region bitmap is not immutable");
    }

    private static void verifyChunkLocalVariantTrim() {
        CaveTileRepository repository = CaveTileRepository.getInstance();
        repository.clearRuntime(false);
        long generation = repository.generation();

        for (int ordinal = 0; ordinal < 128; ordinal++) {
            require(repository.commitDisplayTile(tile(
                            700_000 + ordinal, -710_000 - ordinal,
                            -28, 1_000L + ordinal), generation),
                    "repository rejected unrelated locality fixture");
        }
        CaveTileRepository.DenseVariantTrimStats before =
                repository.denseVariantTrimStats();
        int targetX = 880_003;
        int targetZ = -880_019;
        for (int topY = -28; topY <= -24; topY++) {
            require(repository.commitDisplayTile(
                            tile(targetX, targetZ, topY, 2_000L + topY),
                            generation),
                    "repository rejected exact-variant locality fixture");
        }
        CaveTileRepository.DenseVariantTrimStats after =
                repository.denseVariantTrimStats();
        long callDelta = after.calls() - before.calls();
        long keyDelta = after.keysScanned() - before.keysScanned();
        require(callDelta == 5L && keyDelta <= 15L,
                "exact trim scanned outside its target chunk: calls=" + callDelta
                        + " keys=" + keyDelta);
        require(repository.getExactDisplayTile(CaveView.LAYERED, -28,
                        targetX, targetZ) == null
                        && repository.getExactDisplayTile(CaveView.LAYERED, -24,
                                targetX, targetZ) != null,
                "chunk-local trim did not retain the newest four same-band variants");
        repository.clearRuntime(false);
    }

    private static DenseCaveTile tile(int chunkX, int chunkZ, int topY,
            long revision) {
        DenseCaveTile.Builder builder = new DenseCaveTile.Builder();
        builder.beginColumn();
        builder.set(0, 0, 0xFF223344 | (topY & 0xFF), topY - 4, topY,
                (byte) 0, 0);
        return builder.build(chunkX, chunkZ, CaveView.LAYERED,
                CaveLayerBand.key(CaveView.LAYERED, topY), topY,
                revision, DenseCaveTile.Source.LIVE);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
