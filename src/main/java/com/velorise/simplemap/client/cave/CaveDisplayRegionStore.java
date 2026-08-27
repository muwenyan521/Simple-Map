package com.velorise.simplemap.client.cave;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.velorise.simplemap.client.MapConfig;

/** Append-only random-access store for dense display tiles (.cvd). */
final class CaveDisplayRegionStore {
    private static final int REGION_MAGIC = 0x43564431; // CVD1
    /*
     * v9 stores exact Layered Top-Y in the append record identity. The payload
     * already retained it, but the v8 index collapsed every exact slice in one
     * 16-block band to a single mutable slot.
     */
    private static final int REGION_VERSION = CaveCacheSchema.DISPLAY_REGION_VERSION;
    private static final int RECORD_MAGIC = 0x4454494C; // DTIL
    private static final int TILE_MAGIC = 0x44435431; // DCT1
    private static final int TILE_VERSION = CaveCacheSchema.DISPLAY_TILE_VERSION;
    private static final int HEADER_BYTES = Integer.BYTES * 2;
    private static final int RECORD_HEADER_BYTES = Integer.BYTES * 8;
    private static final int MAX_PAYLOAD = 1 << 20;
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    private CaveDisplayRegionStore() {
    }

    static Map<DenseCaveTileKey, RecordPointer> rebuildIndex(File directory) {
        Map<DenseCaveTileKey, RecordPointer> result = new HashMap<>();
        if (directory == null || !directory.isDirectory()) return result;
        String prefix = colourNamespacePrefix();
        File[] files = directory.listFiles((dir, name) -> name != null
                && name.matches(java.util.regex.Pattern.quote(prefix)
                        + "r\\.-?\\d+\\.-?\\d+\\.cvd"));
        if (files == null) return result;
        for (File file : files) scan(file, result);
        return result;
    }

    static Map<DenseCaveTileKey, RecordPointer> append(File directory,
            List<DenseCaveTile> tiles) throws IOException {
        Map<DenseCaveTileKey, RecordPointer> result = new HashMap<>();
        if (directory == null || tiles == null || tiles.isEmpty()) return result;
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory);
        }
        Map<Long, List<DenseCaveTile>> grouped = new LinkedHashMap<>();
        for (DenseCaveTile tile : tiles) {
            if (tile == null) continue;
            int regionX = tile.chunkX() >> 5;
            int regionZ = tile.chunkZ() >> 5;
            long key = (((long) regionX) << 32) ^ (regionZ & 0xFFFFFFFFL);
            grouped.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(tile);
        }
        for (Map.Entry<Long, List<DenseCaveTile>> entry : grouped.entrySet()) {
            int regionX = (int) (entry.getKey() >> 32);
            int regionZ = (int) (long) entry.getKey();
            File file = file(directory, regionX, regionZ);
            synchronized (lock(file)) {
                prepare(file);
                try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
                    output.seek(output.length());
                    for (DenseCaveTile tile : entry.getValue()) {
                        byte[] payload = encode(tile);
                        CRC32 crc = new CRC32();
                        crc.update(payload);
                        output.writeInt(RECORD_MAGIC);
                        output.writeInt(tile.chunkX());
                        output.writeInt(tile.chunkZ());
                        output.writeInt(tile.view().ordinal());
                        output.writeInt(tile.layerY());
                        output.writeInt(tile.projectionTopY());
                        output.writeInt(payload.length);
                        output.writeInt((int) crc.getValue());
                        long offset = output.getFilePointer();
                        output.write(payload);
                        DenseCaveTileKey key = DenseCaveTileKey.of(tile);
                        result.put(key, new RecordPointer(regionX, regionZ, key,
                                offset, payload.length, (int) crc.getValue()));
                    }
                }
            }
        }
        return result;
    }

    static DenseCaveTile read(File directory, RecordPointer pointer) throws IOException {
        if (directory == null || pointer == null) return null;
        File file = file(directory, pointer.regionX(), pointer.regionZ());
        if (!file.isFile()) return null;
        byte[] payload;
        synchronized (lock(file)) {
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                if (pointer.offset() < HEADER_BYTES || pointer.length() <= 0
                        || pointer.length() > MAX_PAYLOAD
                        || pointer.offset() + pointer.length() > input.length()) return null;
                payload = new byte[pointer.length()];
                input.seek(pointer.offset());
                input.readFully(payload);
            }
        }
        CRC32 crc = new CRC32();
        crc.update(payload);
        if ((int) crc.getValue() != pointer.checksum()) return null;
        DenseCaveTile tile = decode(payload);
        return tile != null && DenseCaveTileKey.of(tile).equals(pointer.key()) ? tile : null;
    }

    /**
     * Reads a display-cache batch from one region with a single file handle.
     *
     * <p>The fullscreen map normally asks for dozens or hundreds of neighbouring
     * 16x16 projections at once. Opening the same .cvd file and constructing a
     * GZIP stream in a separate scheduler task for every projection made cache
     * replay slower than rebuilding from the Minecraft save. Region archives in
     * mature map renderers are consumed as one ordered stream; sorting the latest
     * append-only records by offset gives this store the same IO shape while
     * retaining its random-access format.</p>
     */
    static Map<DenseCaveTileKey, DenseCaveTile> readMany(File directory,
            List<RecordPointer> requested) throws IOException {
        Map<DenseCaveTileKey, DenseCaveTile> result = new LinkedHashMap<>();
        if (directory == null || requested == null || requested.isEmpty()) return result;

        List<RecordPointer> pointers = new ArrayList<>(requested.size());
        int regionX = requested.get(0).regionX();
        int regionZ = requested.get(0).regionZ();
        for (RecordPointer pointer : requested) {
            if (pointer != null && pointer.regionX() == regionX
                    && pointer.regionZ() == regionZ) pointers.add(pointer);
        }
        if (pointers.isEmpty()) return result;
        pointers.sort(Comparator.comparingLong(RecordPointer::offset));

        File file = file(directory, regionX, regionZ);
        if (!file.isFile()) return result;
        synchronized (lock(file)) {
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                long fileLength = input.length();
                for (RecordPointer pointer : pointers) {
                    if (pointer.offset() < HEADER_BYTES || pointer.length() <= 0
                            || pointer.length() > MAX_PAYLOAD
                            || pointer.offset() + pointer.length() > fileLength) continue;
                    byte[] payload = new byte[pointer.length()];
                    input.seek(pointer.offset());
                    input.readFully(payload);
                    CRC32 crc = new CRC32();
                    crc.update(payload);
                    if ((int) crc.getValue() != pointer.checksum()) continue;
                    DenseCaveTile tile = decode(payload);
                    if (tile != null && DenseCaveTileKey.of(tile).equals(pointer.key())) {
                        result.put(pointer.key(), tile);
                    }
                }
            }
        }
        return result;
    }

    private static void scan(File file, Map<DenseCaveTileKey, RecordPointer> output) {
        int[] region = regionCoordinates(file);
        if (region == null) return;
        synchronized (lock(file)) {
            try (RandomAccessFile input = new RandomAccessFile(file, "rw")) {
                if (input.length() < HEADER_BYTES || input.readInt() != REGION_MAGIC
                        || input.readInt() != REGION_VERSION) return;
                long lastGood = HEADER_BYTES;
                while (input.getFilePointer() < input.length()) {
                    if (input.length() - input.getFilePointer() < RECORD_HEADER_BYTES) break;
                    int magic = input.readInt();
                    int chunkX = input.readInt();
                    int chunkZ = input.readInt();
                    int viewOrdinal = input.readInt();
                    int layerY = input.readInt();
                    int projectionTopY = input.readInt();
                    int length = input.readInt();
                    int checksum = input.readInt();
                    if (magic != RECORD_MAGIC || length <= 0 || length > MAX_PAYLOAD
                            || (chunkX >> 5) != region[0] || (chunkZ >> 5) != region[1]
                            || viewOrdinal < 0 || viewOrdinal >= CaveView.values().length
                            || input.getFilePointer() + length > input.length()) break;
                    CaveView view = CaveView.values()[viewOrdinal];
                    DenseCaveTileKey key = new DenseCaveTileKey(
                            chunkX, chunkZ, view, layerY, projectionTopY);
                    long offset = input.getFilePointer();
                    output.put(key, new RecordPointer(region[0], region[1], key,
                            offset, length, checksum));
                    input.seek(offset + length);
                    lastGood = input.getFilePointer();
                }
                if (lastGood < input.length()) input.setLength(lastGood);
            } catch (IOException ignored) {
            }
        }
    }

    private static byte[] encode(DenseCaveTile tile) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(bytes)))) {
            output.writeInt(TILE_MAGIC);
            output.writeInt(TILE_VERSION);
            output.writeInt(tile.chunkX());
            output.writeInt(tile.chunkZ());
            output.writeInt(tile.view().ordinal());
            output.writeInt(tile.layerY());
            output.writeInt(tile.projectionTopY());
            output.writeLong(tile.revision());
            int[] colors = tile.colorsUnsafe();
            short[] floors = tile.floorUnsafe();
            short[] tops = tile.topUnsafe();
            byte[] flags = tile.flagsUnsafe();
            byte[] light = tile.lightUnsafe();
            byte[] overlayCounts = tile.overlayCountsUnsafe();
            int[] overlayColors = tile.overlayColorsUnsafe();
            byte[] overlayAlpha = tile.overlayAlphaUnsafe();
            short[] overlayY = tile.overlayYUnsafe();
            byte[] overlayLight = tile.overlayLightUnsafe();
            byte[] overlayFlags = tile.overlayFlagsUnsafe();
            for (int i = 0; i < DenseCaveTile.COLUMN_COUNT; i++) {
                output.writeInt(colors[i]);
                output.writeShort(floors[i]);
                output.writeShort(tops[i]);
                output.writeByte(flags[i]);
                output.writeByte(light[i]);
                int count = Math.min(DenseCaveTile.MAX_OVERLAYS,
                        Byte.toUnsignedInt(overlayCounts[i]));
                output.writeByte(count);
                int first = i * DenseCaveTile.MAX_OVERLAYS;
                for (int layer = 0; layer < count; layer++) {
                    int entry = first + layer;
                    output.writeInt(overlayColors[entry]);
                    output.writeByte(overlayAlpha[entry]);
                    output.writeShort(overlayY[entry]);
                    output.writeByte(overlayLight[entry]);
                    output.writeByte(overlayFlags[entry]);
                }
            }
        }
        return bytes.toByteArray();
    }

    private static DenseCaveTile decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new ByteArrayInputStream(payload))))) {
            if (input.readInt() != TILE_MAGIC) return null;
            if (input.readInt() != TILE_VERSION) return null;
            int chunkX = input.readInt();
            int chunkZ = input.readInt();
            int viewOrdinal = input.readInt();
            int layerY = input.readInt();
            int projectionTopY = input.readInt();
            long revision = input.readLong();
            if (viewOrdinal < 0 || viewOrdinal >= CaveView.values().length) return null;
            int[] colors = new int[DenseCaveTile.COLUMN_COUNT];
            short[] floors = new short[DenseCaveTile.COLUMN_COUNT];
            short[] tops = new short[DenseCaveTile.COLUMN_COUNT];
            byte[] flags = new byte[DenseCaveTile.COLUMN_COUNT];
            byte[] light = new byte[DenseCaveTile.COLUMN_COUNT];
            byte[] overlayCounts = new byte[DenseCaveTile.COLUMN_COUNT];
            int[] overlayColors = new int[DenseCaveTile.OVERLAY_ENTRY_COUNT];
            byte[] overlayAlpha = new byte[DenseCaveTile.OVERLAY_ENTRY_COUNT];
            short[] overlayY = new short[DenseCaveTile.OVERLAY_ENTRY_COUNT];
            byte[] overlayLight = new byte[DenseCaveTile.OVERLAY_ENTRY_COUNT];
            byte[] overlayFlags = new byte[DenseCaveTile.OVERLAY_ENTRY_COUNT];
            java.util.Arrays.fill(floors, com.velorise.simplemap.client.FullCaveMapManager.NO_SURFACE);
            java.util.Arrays.fill(tops, com.velorise.simplemap.client.FullCaveMapManager.NO_SURFACE);
            java.util.Arrays.fill(overlayY, com.velorise.simplemap.client.FullCaveMapManager.NO_SURFACE);
            for (int i = 0; i < DenseCaveTile.COLUMN_COUNT; i++) {
                colors[i] = input.readInt();
                floors[i] = input.readShort();
                tops[i] = input.readShort();
                flags[i] = input.readByte();
                light[i] = input.readByte();
                int count = input.readUnsignedByte();
                if (count > DenseCaveTile.MAX_OVERLAYS) return null;
                overlayCounts[i] = (byte) count;
                int first = i * DenseCaveTile.MAX_OVERLAYS;
                for (int layer = 0; layer < count; layer++) {
                    int entry = first + layer;
                    overlayColors[entry] = input.readInt();
                    overlayAlpha[entry] = input.readByte();
                    overlayY[entry] = input.readShort();
                    overlayLight[entry] = input.readByte();
                    overlayFlags[entry] = input.readByte();
                }
            }
            return DenseCaveTile.fromStored(chunkX, chunkZ,
                    CaveView.values()[viewOrdinal], layerY, projectionTopY, revision,
                    colors, floors, tops, flags, light, overlayCounts,
                    overlayColors, overlayAlpha, overlayY,
                    overlayLight, overlayFlags);
        }
    }

    private static void prepare(File file) throws IOException {
        if (file.isFile()) {
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                if (input.length() >= HEADER_BYTES && input.readInt() == REGION_MAGIC
                        && input.readInt() == REGION_VERSION) return;
            }
            java.nio.file.Files.deleteIfExists(file.toPath());
        }
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.writeInt(REGION_MAGIC);
            output.writeInt(REGION_VERSION);
        }
    }

    private static File file(File directory, int regionX, int regionZ) {
        return new File(directory, colourNamespacePrefix() + "r."
                + regionX + "." + regionZ + ".cvd");
    }

    /** Persistent cave material colour depends on colour mode and visual schema. */
    private static String colourNamespacePrefix() {
        return "c" + CaveCacheSchema.EPOCH + "-m"
                + Math.max(0, MapConfig.blockColourMode) + "-";
    }

    private static int[] regionCoordinates(File file) {
        String name = file.getName();
        String prefix = colourNamespacePrefix();
        if (!name.startsWith(prefix)) return null;
        String[] parts = name.substring(prefix.length()).split("\\.");
        if (parts.length != 4) return null;
        try {
            return new int[] { Integer.parseInt(parts[1]), Integer.parseInt(parts[2]) };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Object lock(File file) {
        return LOCKS.computeIfAbsent(file.getAbsolutePath(), ignored -> new Object());
    }

    record RecordPointer(int regionX, int regionZ, DenseCaveTileKey key,
            long offset, int length, int checksum) {
    }
}
