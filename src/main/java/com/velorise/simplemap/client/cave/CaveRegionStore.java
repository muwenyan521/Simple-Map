package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapConfig;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Append-only random-access cave region container.
 *
 * One .cvr file stores the latest and historical records for the 32x32 chunk tiles
 * inside one Minecraft region. The in-memory index points directly at the newest
 * complete record for each tile, avoiding thousands of small .cvt files on Windows.
 * A truncated final record is discarded safely during the next index rebuild.
 */
final class CaveRegionStore {
    private static final int REGION_MAGIC = 0x43565231; // CVR1
    /*
     * v5 invalidates raw archives whose live Full-Cave entry scan still required
     * a multi-solid roof instead of Xaero-style first real terrain entry.
     */
    private static final int REGION_VERSION = CaveCacheSchema.RAW_REGION_VERSION;
    private static final int RECORD_MAGIC = 0x54494C45; // TILE
    private static final int REGION_HEADER_BYTES = Integer.BYTES * 2;
    private static final int RECORD_HEADER_BYTES = Integer.BYTES * 5;
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    private static final int SNAPSHOT_MAGIC = 0x43565434; // CVT4
    private static final int SNAPSHOT_VERSION = CaveCacheSchema.RAW_SNAPSHOT_VERSION;

    /** Compaction is deliberately conservative because it is maintenance IO. */
    private static final long COMPACT_MIN_FILE_BYTES = Math.max(1L << 20,
            Long.getLong("simplemap.caveCompactMinBytes", 8L << 20));
    private static final long COMPACT_MIN_RECLAIM_BYTES = Math.max(512L << 10,
            Long.getLong("simplemap.caveCompactMinReclaimBytes", 4L << 20));
    private static final int COMPACT_MAX_LIVE_PERCENT = Math.max(20, Math.min(95,
            Integer.getInteger("simplemap.caveCompactMaxLivePercent", 70)));
    private static final int COMPACT_MIN_RECORD_MULTIPLIER = Math.max(2,
            Integer.getInteger("simplemap.caveCompactRecordMultiplier", 3));

    /** Serializes appends/reads/compaction to one region while unrelated regions run in parallel. */
    private static final Map<String, Object> REGION_LOCKS = new ConcurrentHashMap<>();

    private CaveRegionStore() {
    }

    static Map<Long, RecordPointer> rebuildIndex(File directory) {
        Map<Long, RecordPointer> result = new HashMap<>();
        if (directory == null || !directory.isDirectory()) return result;
        String prefix = colourNamespacePrefix();
        File[] files = directory.listFiles((dir, name) -> name != null
                && name.startsWith(prefix)
                && name.substring(prefix.length())
                        .matches("r\\.-?\\d+\\.-?\\d+\\.cvr"));
        if (files == null) return result;
        for (File file : files) scanRegionFile(file, result);
        return result;
    }

    private static void scanRegionFile(File file, Map<Long, RecordPointer> result) {
        int[] coordinates = parseRegionCoordinates(file);
        if (coordinates == null) return;
        int regionX = coordinates[0];
        int regionZ = coordinates[1];

        Object lock = regionLock(file);
        synchronized (lock) {
            try (RandomAccessFile input = new RandomAccessFile(file, "rw")) {
                RegionScan scan = scanLatestLocked(input, regionX, regionZ);
                if (!scan.validHeader()) return;
                result.putAll(scan.latest());
                if (scan.trimTail() && scan.lastGoodOffset() >= REGION_HEADER_BYTES
                        && scan.lastGoodOffset() < input.length()) {
                    input.setLength(scan.lastGoodOffset());
                }
            } catch (IOException ignored) {
                // A damaged region remains isolated. Live world scans can rebuild it.
            }
        }
    }

    static RecordPointer appendSnapshot(File directory, CaveChunkTile.Snapshot snapshot)
            throws IOException {
        Map<Long, RecordPointer> result = appendSnapshots(directory, List.of(snapshot));
        return result.get(CaveTileRepository.pack(snapshot.chunkX(), snapshot.chunkZ()));
    }

    /**
     * Appends many snapshots with one open/close cycle per region. This is used by
     * dimension flushes and normal save batching to avoid repeated NTFS metadata work.
     */
    static Map<Long, RecordPointer> appendSnapshots(File directory,
            List<CaveChunkTile.Snapshot> snapshots) throws IOException {
        Map<Long, RecordPointer> result = new HashMap<>();
        if (directory == null || snapshots == null || snapshots.isEmpty()) return result;
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory);
        }

        Map<Long, List<EncodedSnapshot>> grouped = new LinkedHashMap<>();
        for (CaveChunkTile.Snapshot snapshot : snapshots) {
            if (snapshot == null) continue;
            byte[] payload = encodeSnapshot(snapshot);
            CRC32 crc = new CRC32();
            crc.update(payload);
            int regionX = snapshot.chunkX() >> 5;
            int regionZ = snapshot.chunkZ() >> 5;
            long regionKey = CaveTileRepository.pack(regionX, regionZ);
            grouped.computeIfAbsent(regionKey, ignored -> new ArrayList<>())
                    .add(new EncodedSnapshot(snapshot, payload, (int) crc.getValue()));
        }

        for (Map.Entry<Long, List<EncodedSnapshot>> entry : grouped.entrySet()) {
            int regionX = (int) (entry.getKey() >> 32);
            int regionZ = (int) (long) entry.getKey();
            File target = regionFile(directory, regionX, regionZ);
            Object lock = regionLock(target);
            synchronized (lock) {
                prepareRegionFile(target);
                try (RandomAccessFile output = new RandomAccessFile(target, "rw")) {
                    output.seek(output.length());
                    for (EncodedSnapshot encoded : entry.getValue()) {
                        CaveChunkTile.Snapshot snapshot = encoded.snapshot();
                        output.writeInt(RECORD_MAGIC);
                        output.writeInt(snapshot.chunkX());
                        output.writeInt(snapshot.chunkZ());
                        output.writeInt(encoded.payload().length);
                        output.writeInt(encoded.checksum());
                        long payloadOffset = output.getFilePointer();
                        output.write(encoded.payload());
                        RecordPointer pointer = new RecordPointer(regionX, regionZ,
                                snapshot.chunkX(), snapshot.chunkZ(), payloadOffset,
                                encoded.payload().length, encoded.checksum());
                        result.put(CaveTileRepository.pack(snapshot.chunkX(), snapshot.chunkZ()),
                                pointer);
                        deleteLegacySnapshot(directory, snapshot.chunkX(), snapshot.chunkZ());
                        CaveTelemetry.getInstance().recordTileSave();
                    }
                }
            }
        }
        return result;
    }

    static CaveChunkTile.Snapshot readSnapshot(File directory, RecordPointer pointer)
            throws IOException {
        if (directory == null || pointer == null) return null;
        File file = regionFile(directory, pointer.regionX(), pointer.regionZ());
        if (!file.isFile()) return null;

        byte[] payload;
        Object lock = regionLock(file);
        synchronized (lock) {
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                payload = readPayloadLocked(input, pointer);
                if (payload == null) {
                    // A compaction can relocate records between scheduling and IO.
                    // Resolve the newest pointer by chunk and retry instead of treating
                    // the tile as corrupt and permanently removing it from the index.
                    RegionScan scan = scanLatestLocked(input, pointer.regionX(), pointer.regionZ());
                    RecordPointer latest = scan.latest().get(
                            CaveTileRepository.pack(pointer.chunkX(), pointer.chunkZ()));
                    payload = readPayloadLocked(input, latest);
                }
            }
        }
        if (payload == null) return null;
        CaveChunkTile.Snapshot snapshot = decodeSnapshot(payload);
        if (snapshot == null || snapshot.chunkX() != pointer.chunkX()
                || snapshot.chunkZ() != pointer.chunkZ()) return null;
        return snapshot;
    }

    /**
     * Rewrites one append-log region with only its newest valid tile records.
     * Returns null when no compaction is currently justified.
     */
    static CompactionResult compactRegionIfNeeded(File directory, int regionX, int regionZ)
            throws IOException {
        if (directory == null) return null;
        File target = regionFile(directory, regionX, regionZ);
        if (!target.isFile()) return null;
        Object lock = regionLock(target);

        synchronized (lock) {
            RegionScan scan;
            try (RandomAccessFile input = new RandomAccessFile(target, "rw")) {
                scan = scanLatestLocked(input, regionX, regionZ);
                if (!scan.validHeader()) return null;
                if (scan.trimTail() && scan.lastGoodOffset() >= REGION_HEADER_BYTES
                        && scan.lastGoodOffset() < input.length()) {
                    input.setLength(scan.lastGoodOffset());
                    scan = scanLatestLocked(input, regionX, regionZ);
                }
            }

            long oldBytes = target.length();
            long liveBytes = REGION_HEADER_BYTES;
            for (RecordPointer pointer : scan.latest().values()) {
                liveBytes += RECORD_HEADER_BYTES + pointer.payloadLength();
            }
            long reclaimable = Math.max(0L, oldBytes - liveBytes);
            int latestCount = scan.latest().size();
            boolean excessiveRecords = latestCount > 0
                    && scan.recordCount() >= latestCount * COMPACT_MIN_RECORD_MULTIPLIER;
            boolean poorLiveRatio = oldBytes > 0
                    && liveBytes * 100L <= oldBytes * COMPACT_MAX_LIVE_PERCENT;
            if (oldBytes < COMPACT_MIN_FILE_BYTES
                    || reclaimable < COMPACT_MIN_RECLAIM_BYTES
                    || (!excessiveRecords && !poorLiveRatio)) {
                return null;
            }

            File temporary = new File(target.getParentFile(), target.getName() + ".compact.tmp");
            Files.deleteIfExists(temporary.toPath());
            Map<Long, RecordPointer> compacted = new HashMap<>();
            List<RecordPointer> ordered = new ArrayList<>(scan.latest().values());
            ordered.sort(Comparator.comparingInt(RecordPointer::chunkZ)
                    .thenComparingInt(RecordPointer::chunkX));

            int copied = 0;
            try (RandomAccessFile input = new RandomAccessFile(target, "r");
                    RandomAccessFile output = new RandomAccessFile(temporary, "rw")) {
                output.setLength(0L);
                output.writeInt(REGION_MAGIC);
                output.writeInt(REGION_VERSION);
                for (RecordPointer pointer : ordered) {
                    byte[] payload = readPayloadLocked(input, pointer);
                    if (payload == null) continue;
                    CaveChunkTile.Snapshot snapshot = decodeSnapshot(payload);
                    if (snapshot == null || snapshot.chunkX() != pointer.chunkX()
                            || snapshot.chunkZ() != pointer.chunkZ()) continue;

                    output.writeInt(RECORD_MAGIC);
                    output.writeInt(pointer.chunkX());
                    output.writeInt(pointer.chunkZ());
                    output.writeInt(payload.length);
                    output.writeInt(pointer.checksum());
                    long payloadOffset = output.getFilePointer();
                    output.write(payload);
                    compacted.put(CaveTileRepository.pack(pointer.chunkX(), pointer.chunkZ()),
                            new RecordPointer(regionX, regionZ, pointer.chunkX(),
                                    pointer.chunkZ(), payloadOffset, payload.length,
                                    pointer.checksum()));
                    copied++;
                }
                output.getFD().sync();
            } catch (IOException failure) {
                Files.deleteIfExists(temporary.toPath());
                throw failure;
            }

            try {
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }

            long newBytes = target.length();
            return new CompactionResult(regionX, regionZ, Map.copyOf(compacted),
                    oldBytes, newBytes, Math.max(0, scan.recordCount() - copied));
        }
    }

    static CaveChunkTile.Snapshot readLegacySnapshot(File directory, int chunkX, int chunkZ)
            throws IOException {
        if (directory == null) return null;
        File file = new File(directory, legacyFileName(chunkX, chunkZ));
        if (!file.isFile()) return null;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(file))))) {
            return readSnapshotPayload(input);
        }
    }

    static void deleteLegacySnapshot(File directory, int chunkX, int chunkZ) {
        if (directory == null) return;
        File legacy = new File(directory, legacyFileName(chunkX, chunkZ));
        try {
            Files.deleteIfExists(legacy.toPath());
        } catch (IOException ignored) {
        }
    }

    static String legacyFileName(int chunkX, int chunkZ) {
        return "c." + chunkX + "." + chunkZ + ".cvt";
    }

    private static RegionScan scanLatestLocked(RandomAccessFile input,
            int regionX, int regionZ) throws IOException {
        Map<Long, RecordPointer> latest = new HashMap<>();
        input.seek(0L);
        if (input.length() < REGION_HEADER_BYTES
                || input.readInt() != REGION_MAGIC
                || input.readInt() != REGION_VERSION) {
            return new RegionScan(false, latest, 0, 0L, true);
        }

        long lastGoodOffset = REGION_HEADER_BYTES;
        int recordCount = 0;
        boolean trimTail = false;
        while (input.getFilePointer() < input.length()) {
            long remaining = input.length() - input.getFilePointer();
            if (remaining < RECORD_HEADER_BYTES) {
                trimTail = true;
                break;
            }
            int recordMagic = input.readInt();
            int chunkX = input.readInt();
            int chunkZ = input.readInt();
            int payloadLength = input.readInt();
            int checksum = input.readInt();
            if (recordMagic != RECORD_MAGIC || payloadLength <= 0
                    || payloadLength > MAX_PAYLOAD_BYTES
                    || (chunkX >> 5) != regionX || (chunkZ >> 5) != regionZ) {
                trimTail = true;
                break;
            }
            long payloadOffset = input.getFilePointer();
            long nextOffset = payloadOffset + payloadLength;
            if (nextOffset > input.length()) {
                trimTail = true;
                break;
            }
            latest.put(CaveTileRepository.pack(chunkX, chunkZ),
                    new RecordPointer(regionX, regionZ, chunkX, chunkZ,
                            payloadOffset, payloadLength, checksum));
            input.seek(nextOffset);
            lastGoodOffset = nextOffset;
            recordCount++;
        }
        return new RegionScan(true, latest, recordCount, lastGoodOffset, trimTail);
    }

    private static byte[] readPayloadLocked(RandomAccessFile input, RecordPointer pointer)
            throws IOException {
        if (pointer == null || pointer.payloadLength() <= 0
                || pointer.payloadLength() > MAX_PAYLOAD_BYTES
                || pointer.payloadOffset() < REGION_HEADER_BYTES
                || pointer.payloadOffset() + pointer.payloadLength() > input.length()) {
            return null;
        }
        byte[] payload = new byte[pointer.payloadLength()];
        input.seek(pointer.payloadOffset());
        input.readFully(payload);
        CRC32 crc = new CRC32();
        crc.update(payload);
        return (int) crc.getValue() == pointer.checksum() ? payload : null;
    }

    private static int[] parseRegionCoordinates(File file) {
        if (file == null) return null;
        String name = file.getName();
        String prefix = colourNamespacePrefix();
        if (!name.startsWith(prefix)) return null;
        String[] parts = name.substring(prefix.length()).split("\\.");
        if (parts.length != 4 || !"r".equals(parts[0])
                || !"cvr".equals(parts[3])) return null;
        try {
            return new int[] { Integer.parseInt(parts[1]), Integer.parseInt(parts[2]) };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void prepareRegionFile(File target) throws IOException {
        if (!target.exists()) {
            try (RandomAccessFile output = new RandomAccessFile(target, "rw")) {
                output.writeInt(REGION_MAGIC);
                output.writeInt(REGION_VERSION);
            }
            return;
        }
        boolean valid;
        try (RandomAccessFile input = new RandomAccessFile(target, "r")) {
            valid = input.length() >= REGION_HEADER_BYTES
                    && input.readInt() == REGION_MAGIC
                    && input.readInt() == REGION_VERSION;
        }
        if (valid) return;

        File corrupt = new File(target.getParentFile(), target.getName()
                + ".corrupt." + System.currentTimeMillis());
        try {
            Files.move(target.toPath(), corrupt.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveFailure) {
            Files.deleteIfExists(target.toPath());
        }
        try (RandomAccessFile output = new RandomAccessFile(target, "rw")) {
            output.writeInt(REGION_MAGIC);
            output.writeInt(REGION_VERSION);
        }
    }

    private static byte[] encodeSnapshot(CaveChunkTile.Snapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 * 1024);
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(bytes)))) {
            output.writeInt(SNAPSHOT_MAGIC);
            output.writeInt(SNAPSHOT_VERSION);
            output.writeInt(snapshot.chunkX());
            output.writeInt(snapshot.chunkZ());
            output.writeLong(snapshot.revision());
            long[] scannedWords = snapshot.scanned().toLongArray();
            for (int i = 0; i < 4; i++) {
                output.writeLong(i < scannedWords.length ? scannedWords[i] : 0L);
            }
            long[] fullHeightWords = snapshot.fullHeight().toLongArray();
            for (int i = 0; i < 4; i++) {
                output.writeLong(i < fullHeightWords.length ? fullHeightWords[i] : 0L);
            }
            for (int index = 0; index < CaveChunkTile.COLUMN_COUNT; index++) {
                if (!snapshot.scanned().get(index)) continue;
                CaveColumnData column = snapshot.columns()[index];
                int count = column == null ? 0 : column.count();
                output.writeByte(count);
                output.writeShort(column == null
                        ? Short.MIN_VALUE : column.scannedMinimumY());
                output.writeShort(column == null
                        ? Short.MIN_VALUE : column.scannedMaximumY());
                for (int run = 0; run < count; run++) {
                    output.writeShort(column.topY(run));
                    output.writeShort(column.bottomY(run));
                    output.writeInt(column.color(run));
                    output.writeByte(column.flags(run));
                    output.writeInt(column.fluidColor(run));
                    output.writeByte(column.fluidAlpha(run));
                    output.writeShort(column.fluidY(run));
                    output.writeByte(column.fluidLight(run));
                    output.writeByte(column.fluidDepth(run));
                    output.writeByte(column.fluidFlags(run));
                    output.writeInt(column.emissiveColor(run));
                    output.writeByte(column.emissiveAlpha(run));
                    output.writeShort(column.emissiveY(run));
                    output.writeByte(column.emissiveLight(run));
                }
            }
        }
        return bytes.toByteArray();
    }

    private static CaveChunkTile.Snapshot decodeSnapshot(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new ByteArrayInputStream(payload))))) {
            return readSnapshotPayload(input);
        }
    }

    private static CaveChunkTile.Snapshot readSnapshotPayload(DataInputStream input)
            throws IOException {
        try {
            if (input.readInt() != SNAPSHOT_MAGIC
                    || input.readInt() != SNAPSHOT_VERSION) return null;
            int storedX = input.readInt();
            int storedZ = input.readInt();
            long revision = input.readLong();
            long[] words = new long[4];
            for (int i = 0; i < words.length; i++) words[i] = input.readLong();
            BitSet scanned = BitSet.valueOf(words);
            long[] fullHeightWords = new long[4];
            for (int i = 0; i < fullHeightWords.length; i++) {
                fullHeightWords[i] = input.readLong();
            }
            BitSet fullHeight = BitSet.valueOf(fullHeightWords);
            CaveColumnData[] columns = new CaveColumnData[CaveChunkTile.COLUMN_COUNT];
            for (int index = 0; index < CaveChunkTile.COLUMN_COUNT; index++) {
                if (!scanned.get(index)) continue;
                int count = input.readUnsignedByte();
                short scannedMinimumY = input.readShort();
                short scannedMaximumY = input.readShort();
                short[] tops = new short[count];
                short[] bottoms = new short[count];
                int[] colors = new int[count];
                byte[] flags = new byte[count];
                int[] fluidColors = new int[count];
                byte[] fluidAlpha = new byte[count];
                short[] fluidY = new short[count];
                byte[] fluidLight = new byte[count];
                byte[] fluidDepth = new byte[count];
                byte[] fluidFlags = new byte[count];
                int[] emissiveColors = new int[count];
                byte[] emissiveAlpha = new byte[count];
                short[] emissiveY = new short[count];
                byte[] emissiveLight = new byte[count];
                for (int run = 0; run < count; run++) {
                    tops[run] = input.readShort();
                    bottoms[run] = input.readShort();
                    colors[run] = input.readInt();
                    flags[run] = input.readByte();
                    fluidColors[run] = input.readInt();
                    fluidAlpha[run] = input.readByte();
                    fluidY[run] = input.readShort();
                    fluidLight[run] = input.readByte();
                    fluidDepth[run] = input.readByte();
                    fluidFlags[run] = input.readByte();
                    emissiveColors[run] = input.readInt();
                    emissiveAlpha[run] = input.readByte();
                    emissiveY[run] = input.readShort();
                    emissiveLight[run] = input.readByte();
                }
                boolean completeHeight = fullHeight.get(index);
                columns[index] = count == 0
                        ? CaveColumnData.emptyScanned(
                                scannedMinimumY, scannedMaximumY, completeHeight)
                        : new CaveColumnData(tops, bottoms, colors, flags,
                                fluidColors, fluidAlpha, fluidY, fluidLight,
                                fluidDepth, fluidFlags, emissiveColors,
                                emissiveAlpha, emissiveY, emissiveLight, count,
                                scannedMinimumY, scannedMaximumY, completeHeight);
            }
            return new CaveChunkTile.Snapshot(storedX, storedZ,
                    Math.max(1L, revision), scanned, fullHeight, columns);
        } catch (EOFException truncated) {
            return null;
        }
    }

    private static File regionFile(File directory, int regionX, int regionZ) {
        return new File(directory, colourNamespacePrefix()
                + "r." + regionX + "." + regionZ + ".cvr");
    }

    /**
     * CVR stores resolved material colours, so it is not safe to share between
     * Accurate and Vanilla colour modes. The Cave epoch also invalidates archives written
     * before Surface-equivalent Accurate input and raw archive shading.
     */
    private static String colourNamespacePrefix() {
        return "c" + CaveCacheSchema.EPOCH + "-m"
                + Math.max(0, MapConfig.blockColourMode) + "-";
    }

    private static Object regionLock(File file) {
        return REGION_LOCKS.computeIfAbsent(file.getAbsolutePath(), ignored -> new Object());
    }

    private record EncodedSnapshot(CaveChunkTile.Snapshot snapshot,
            byte[] payload, int checksum) {
    }

    private record RegionScan(boolean validHeader, Map<Long, RecordPointer> latest,
            int recordCount, long lastGoodOffset, boolean trimTail) {
    }

    record RecordPointer(int regionX, int regionZ, int chunkX, int chunkZ,
            long payloadOffset, int payloadLength, int checksum) {
    }

    record CompactionResult(int regionX, int regionZ,
            Map<Long, RecordPointer> records, long oldBytes,
            long newBytes, int removedRecords) {
        long reclaimedBytes() {
            return Math.max(0L, oldBytes - newBytes);
        }
    }
}
