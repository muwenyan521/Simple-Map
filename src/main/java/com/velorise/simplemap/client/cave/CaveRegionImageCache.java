package com.velorise.simplemap.client.cave;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

/**
 * Persistent pre-rendered cave-region cache.
 *
 * <p>One CIMG contains up to sixty-four 64x64 exact pages arranged as a
 * 512x512 Minecraft region image. The page mask permits incremental snapshots;
 * cache replay can publish already-rendered pages immediately while missing or
 * stale pages continue through the authoritative CVD/Anvil pipeline.</p>
 */
public final class CaveRegionImageCache {
    private static final System.Logger LOGGER = System.getLogger(
            CaveRegionImageCache.class.getName());
    private static final CaveRegionImageCache INSTANCE =
            new CaveRegionImageCache();

    private static final int MAGIC = 0x43494D47; // CIMG
    private static final int VERSION = CaveCacheSchema.REGION_IMAGE_VERSION;
    public static final int REGION_PIXELS = 512;
    public static final int PAGE_PIXELS = 64;
    public static final int PAGES_PER_EDGE = REGION_PIXELS / PAGE_PIXELS;
    public static final int PIXEL_COUNT = REGION_PIXELS * REGION_PIXELS;
    public static final int PAGE_COUNT = PAGES_PER_EDGE * PAGES_PER_EDGE;
    private static final int BODY_BYTES = PIXEL_COUNT * Integer.BYTES;
    private static final int MAX_BODY_BYTES = BODY_BYTES;
    private static final ThreadLocal<byte[]> BODY_BUFFER =
            ThreadLocal.withInitial(() -> new byte[BODY_BYTES]);

    private final AtomicLong generation = new AtomicLong(1L);
    private volatile File baseDirectory;

    private CaveRegionImageCache() {
    }

    public static CaveRegionImageCache getInstance() {
        return INSTANCE;
    }

    public synchronized void setBaseDirectory(File v4TileDirectory) {
        File next = v4TileDirectory == null
                ? null : new File(v4TileDirectory, "cave_img");
        if (next != null && !next.exists() && !next.mkdirs()) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Could not create cave image cache directory {0}", next);
        }
        baseDirectory = next;
        generation.incrementAndGet();
    }

    public long generation() {
        return generation.get();
    }

    public boolean exists(Key key) {
        File file = file(key);
        return file != null && file.isFile();
    }

    public RegionImage load(Key key) {
        File file = file(key);
        if (file == null || !file.isFile()) return null;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new FileInputStream(file), 128 * 1024))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return null;
            int dimensionHash = input.readInt();
            int regionX = input.readInt();
            int regionZ = input.readInt();
            int viewOrdinal = input.readInt();
            int normalizedLayer = input.readInt();
            int projectionTopY = input.readInt();
            int styleSignature = input.readInt();
            int width = input.readInt();
            int height = input.readInt();
            int pageSize = input.readInt();
            long pageMask = input.readLong();
            long[] pageSourceStamps = new long[PAGE_COUNT];
            for (int page = 0; page < PAGE_COUNT; page++) {
                pageSourceStamps[page] = input.readLong();
            }
            byte[] pageEmptyProofs = input.readNBytes(PAGE_COUNT);
            if (pageEmptyProofs.length != PAGE_COUNT) return null;
            int bodyBytes = input.readInt();
            int expectedCrc = input.readInt();
            if (dimensionHash != key.dimension().hashCode()
                    || regionX != key.regionX() || regionZ != key.regionZ()
                    || viewOrdinal != key.view().ordinal()
                    || normalizedLayer != key.normalizedLayer()
                    || projectionTopY != key.projectionTopY()
                    || styleSignature != key.styleSignature()
                    || width != REGION_PIXELS || height != REGION_PIXELS
                    || pageSize != PAGE_PIXELS || bodyBytes != BODY_BYTES
                    || bodyBytes <= 0 || bodyBytes > MAX_BODY_BYTES) return null;

            byte[] body = input.readNBytes(bodyBytes);
            if (body.length != bodyBytes) return null;
            CRC32 crc = new CRC32();
            crc.update(pageEmptyProofs);
            crc.update(body);
            if ((int) crc.getValue() != expectedCrc) return null;
            int[] pixels = new int[PIXEL_COUNT];
            ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
            for (int index = 0; index < pixels.length; index++) {
                pixels[index] = buffer.getInt();
            }
            return new RegionImage(key, pageMask, pageSourceStamps,
                    pageEmptyProofs, pixels,
                    Math.max(0L, file.lastModified()));
        } catch (EOFException ignored) {
            return null;
        } catch (IOException | RuntimeException failure) {
            LOGGER.log(System.Logger.Level.DEBUG,
                    "Could not read cave image cache " + file, failure);
            return null;
        }
    }

    public boolean save(RegionImage image) {
        if (image == null) return false;
        Key key = image.key();
        File file = file(key);
        if (file == null) return false;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false;

        byte[] body = BODY_BUFFER.get();
        ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
        buffer.clear();
        for (int pixel : image.pixels()) buffer.putInt(pixel);
        CRC32 crc = new CRC32();
        crc.update(image.pageEmptyProofs());
        crc.update(body);
        File temporary = new File(parent, file.getName() + ".tmp."
                + Long.toUnsignedString(Thread.currentThread().getId()));
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(temporary),
                        128 * 1024))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(key.dimension().hashCode());
            output.writeInt(key.regionX());
            output.writeInt(key.regionZ());
            output.writeInt(key.view().ordinal());
            output.writeInt(key.normalizedLayer());
            output.writeInt(key.projectionTopY());
            output.writeInt(key.styleSignature());
            output.writeInt(REGION_PIXELS);
            output.writeInt(REGION_PIXELS);
            output.writeInt(PAGE_PIXELS);
            output.writeLong(image.pageMask());
            for (long sourceStamp : image.pageSourceStamps()) {
                output.writeLong(sourceStamp);
            }
            output.write(image.pageEmptyProofs());
            output.writeInt(body.length);
            output.writeInt((int) crc.getValue());
            output.write(body);
        } catch (IOException failure) {
            temporary.delete();
            LOGGER.log(System.Logger.Level.DEBUG,
                    "Could not write cave image cache " + file, failure);
            return false;
        }

        try {
            try {
                Files.move(temporary.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException failure) {
            temporary.delete();
            LOGGER.log(System.Logger.Level.DEBUG,
                    "Could not publish cave image cache " + file, failure);
            return false;
        }
    }

    private File file(Key key) {
        File root = baseDirectory;
        if (root == null || key == null) return null;
        String dimension = sanitize(key.dimension());
        String view = key.view().name().toLowerCase(Locale.ROOT);
        File directory = new File(root, dimension + File.separator + view
                + File.separator + "layer_" + key.normalizedLayer()
                + File.separator + "top_" + key.projectionTopY()
                + File.separator + "style_"
                + Integer.toUnsignedString(key.styleSignature(), 16));
        return new File(directory,
                "r." + key.regionX() + "." + key.regionZ() + ".cimg");
    }

    private static String sanitize(String value) {
        String source = value == null || value.isBlank() ? "unknown" : value;
        String safe = source.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe + '_' + Integer.toUnsignedString(source.hashCode(), 16);
    }

    public record Key(String dimension, CaveView view, int normalizedLayer,
            int projectionTopY, int styleSignature, int regionX, int regionZ) {
        public Key {
            if (dimension == null || dimension.isBlank()) dimension = "unknown";
            if (view == null) throw new IllegalArgumentException("view is required");
        }
    }

    public record RegionImage(Key key, long pageMask, long[] pageSourceStamps,
            byte[] pageEmptyProofs, int[] pixels, long sourceTimestampMs) {
        public RegionImage {
            if (key == null) throw new IllegalArgumentException("key is required");
            if (pageSourceStamps == null || pageSourceStamps.length != PAGE_COUNT) {
                throw new IllegalArgumentException(
                        "CIMG requires exactly " + PAGE_COUNT + " page source stamps");
            }
            pageSourceStamps = java.util.Arrays.copyOf(
                    pageSourceStamps, pageSourceStamps.length);
            if (pageEmptyProofs == null || pageEmptyProofs.length != PAGE_COUNT) {
                throw new IllegalArgumentException(
                        "CIMG requires exactly " + PAGE_COUNT + " empty proofs");
            }
            pageEmptyProofs = java.util.Arrays.copyOf(
                    pageEmptyProofs, pageEmptyProofs.length);
            if (pixels == null || pixels.length != PIXEL_COUNT) {
                throw new IllegalArgumentException(
                        "CIMG requires exactly " + PIXEL_COUNT + " pixels");
            }
        }

        @Override
        public long[] pageSourceStamps() {
            return java.util.Arrays.copyOf(
                    pageSourceStamps, pageSourceStamps.length);
        }

        @Override
        public byte[] pageEmptyProofs() {
            return java.util.Arrays.copyOf(pageEmptyProofs, pageEmptyProofs.length);
        }

        public boolean hasPage(int localPageX, int localPageZ) {
            if (localPageX < 0 || localPageX >= PAGES_PER_EDGE
                    || localPageZ < 0 || localPageZ >= PAGES_PER_EDGE) return false;
            int ordinal = localPageZ * PAGES_PER_EDGE + localPageX;
            return (pageMask & (1L << ordinal)) != 0L;
        }

        public long pageSourceStamp(int localPageX, int localPageZ) {
            if (!hasPage(localPageX, localPageZ)) return 0L;
            int ordinal = localPageZ * PAGES_PER_EDGE + localPageX;
            return pageSourceStamps[ordinal];
        }

        public CaveEmptyProof pageEmptyProof(int localPageX, int localPageZ) {
            if (!hasPage(localPageX, localPageZ)) return CaveEmptyProof.NONE;
            int ordinal = localPageZ * PAGES_PER_EDGE + localPageX;
            return CaveEmptyProof.fromPersistedCode(
                    Byte.toUnsignedInt(pageEmptyProofs[ordinal]));
        }
    }
}
