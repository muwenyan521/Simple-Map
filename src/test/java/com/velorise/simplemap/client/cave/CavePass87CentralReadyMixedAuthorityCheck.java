package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.cave.archive.CaveArchiveV2Service;
import com.velorise.simplemap.client.cave.archive.CompactCaveTile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** PASS87 compatibility guard updated for PASS132 archive-only authority. */
public final class CavePass87CentralReadyMixedAuthorityCheck {
    private CavePass87CentralReadyMixedAuthorityCheck() { }

    public static void main(String[] args) throws Exception {
        verifyArchitecture();
        verifyMixedAuthorityRuntime();
        System.out.println("CAVE_PASS87_CENTRAL_READY_MIXED_AUTHORITY_PASS");
    }

    private static void verifyArchitecture() throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String importer = Files.readString(
                root.resolve("CaveNativeRegionImportService.java"));
        String repository = Files.readString(root.resolve("CaveTileRepository.java"));
        String archive = Files.readString(
                root.resolve("archive/CaveArchiveV2Service.java"));
        String style = Files.readString(root.resolve("CaveProjectionStyle.java"));

        require(importer.contains("centralReadyPageMask()")
                        && importer.contains("haloReadyPageMask()")
                        && importer.contains("foregroundReadyMask")
                        && importer.contains(
                                "foregroundProjectionMask, foregroundReadyMask")
                        && importer.contains("CAVE_PROJECTION_CENTRAL_READY")
                        && importer.contains("CAVE_FULL_MIXED_AUTHORITY_READY"),
                "visible pages still wait for halo or submit unready foreground work");
        require(repository.contains("projectionAuthorityRevisionLocked")
                        && repository.contains("hasProjectionAuthorityPage")
                        && repository.contains("indexedProjectionMask")
                        && repository.contains("archiveMask != 0xFFFF"),
                "partial archive pages can still use weak absence as authority");
        require(archive.contains("hasIndexedCompleteChunk")
                        && archive.contains("hasIndexedFullProjectionChunk"),
                "archive does not expose chunk-level retained authority");
        require(style.contains("STYLE_SIGNATURE_VERSION = 20"),
                "old incomplete cave region images remain cache-compatible");
    }

    private static void verifyMixedAuthorityRuntime() {
        CaveTileRepository repository = CaveTileRepository.getInstance();
        CaveArchiveV2Service archive = CaveArchiveV2Service.getInstance();
        repository.clearRuntime(false);
        archive.clear();
        long generation = repository.generation();
        int firstChunkX = 80;
        int firstChunkZ = -48;
        int pageX = firstChunkX >> 2;
        int pageZ = firstChunkZ >> 2;

        require(!repository.hasProjectionAuthorityPage(CaveView.FULL,
                        Integer.MIN_VALUE, pageX, pageZ),
                "missing Full absence entries were mistaken for known-empty chunks");
        require(archive.ingest(completeTile(firstChunkX, firstChunkZ, 71L)),
                "test archive tile was not ingested");
        require(!repository.commitDisplayPage(List.of(), CaveView.FULL,
                        Integer.MIN_VALUE, firstChunkX, firstChunkZ,
                        generation),
                "weak absence transaction unexpectedly changed repository state");
        require(!repository.hasProjectionAuthorityPage(CaveView.FULL,
                        Integer.MIN_VALUE, pageX, pageZ),
                "one archived plus fifteen absent chunks formed Full authority");
        long firstRevision = repository.getPageRevision(CaveView.FULL,
                Integer.MIN_VALUE, pageX, pageZ);
        require(firstRevision != 0L, "resident archive has no source revision");
        repository.commitDisplayPage(List.of(), CaveView.FULL,
                Integer.MIN_VALUE, firstChunkX, firstChunkZ,
                generation);
        long secondRevision = repository.getPageRevision(CaveView.FULL,
                Integer.MIN_VALUE, pageX, pageZ);
        require(firstRevision == secondRevision,
                "idempotent mixed presence transaction changed source revision");
    }

    private static CompactCaveTile completeTile(int chunkX, int chunkZ,
            long revision) {
        int[] offsets = new int[257];
        short[] top = new short[256];
        short[] floor = new short[256];
        int[] colors = new int[256];
        short[] biomes = new short[256];
        byte[] block = new byte[256];
        byte[] sky = new byte[256];
        byte[] fluid = new byte[256];
        byte[] flags = new byte[256];
        byte[] statuses = new byte[256];
        for (int column = 0; column < 256; column++) {
            offsets[column] = column;
            top[column] = 20;
            floor[column] = 12;
            colors[column] = 0xFF646464;
            flags[column] = CompactCaveTile.FLAG_LEGACY_COLOR;
            statuses[column] =
                    (byte) CompactCaveTile.ColumnStatus.COMPLETE.ordinal();
        }
        offsets[256] = 256;
        return new CompactCaveTile(chunkX, chunkZ, revision, offsets,
                top, floor, colors, biomes, block, sky, fluid, flags, statuses);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
