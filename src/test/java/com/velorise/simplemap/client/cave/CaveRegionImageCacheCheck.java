package com.velorise.simplemap.client.cave;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;

/** Dependency-free CIMG round-trip and corruption checks. */
public final class CaveRegionImageCacheCheck {
    private CaveRegionImageCacheCheck() { }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("simplemap-cimg-check").toFile();
        try {
            CaveRegionImageCache cache = CaveRegionImageCache.getInstance();
            cache.setBaseDirectory(root);
            CaveRegionImageCache.Key key = new CaveRegionImageCache.Key(
                    "minecraft:overworld", CaveView.LAYERED,
                    48, 55, 0x1234ABCD, -2, 3);
            int[] pixels = new int[CaveRegionImageCache.PIXEL_COUNT];
            for (int index = 0; index < pixels.length; index += 257) {
                pixels[index] = 0xFF000000 | index;
            }
            long mask = (1L << 0) | (1L << 17) | (1L << 63);
            long[] sourceStamps = new long[CaveRegionImageCache.PAGE_COUNT];
            sourceStamps[0] = 101L;
            sourceStamps[17] = 202L;
            sourceStamps[63] = 303L;
            byte[] emptyProofs = new byte[CaveRegionImageCache.PAGE_COUNT];
            emptyProofs[17] = (byte) CaveEmptyProof
                    .COMPLETE_SAVED_SOURCE_EMPTY.persistedCode();
            require(cache.save(new CaveRegionImageCache.RegionImage(
                            key, mask, sourceStamps, emptyProofs, pixels, 0L)),
                    "CIMG save failed");
            CaveRegionImageCache.RegionImage loaded = cache.load(key);
            require(loaded != null, "CIMG load failed");
            require(loaded.pageMask() == mask, "CIMG page mask changed");
            require(loaded.pageSourceStamp(0, 0) == 101L
                            && loaded.pageSourceStamp(1, 2) == 202L
                            && loaded.pageSourceStamp(7, 7) == 303L
                            && loaded.pageSourceStamp(2, 2) == 0L,
                    "CIMG page source stamps changed");
            require(loaded.pageEmptyProof(1, 2)
                            == CaveEmptyProof.COMPLETE_SAVED_SOURCE_EMPTY
                            && loaded.pageEmptyProof(0, 0) == CaveEmptyProof.NONE,
                    "CIMG empty proof changed");
            require(Arrays.equals(loaded.pixels(), pixels),
                    "CIMG pixels changed during round trip");
            require(loaded.hasPage(0, 0) && loaded.hasPage(1, 2)
                            && loaded.hasPage(7, 7) && !loaded.hasPage(2, 2),
                    "CIMG page addressing changed");

            File imageFile = Files.walk(root.toPath())
                    .filter(path -> path.getFileName().toString().endsWith(".cimg"))
                    .findFirst().orElseThrow().toFile();
            byte[] corrupted = Files.readAllBytes(imageFile.toPath());
            ByteBuffer.wrap(corrupted).putInt(Integer.BYTES, 8);
            Files.write(imageFile.toPath(), corrupted);
            require(cache.load(key) == null,
                    "CIMG v8 must be invalidated after proof-bearing v9 migration");
            ByteBuffer.wrap(corrupted).putInt(Integer.BYTES, 9);
            corrupted[corrupted.length - 1] ^= 0x5A;
            Files.write(imageFile.toPath(), corrupted);
            require(cache.load(key) == null,
                    "CIMG CRC must reject corrupted pixel payloads");
            System.out.println("Simple Map CIMG cache checks passed");
        } finally {
            delete(root);
        }
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
