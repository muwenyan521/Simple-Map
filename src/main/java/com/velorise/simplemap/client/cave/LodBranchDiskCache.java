package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapWorkScheduler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Persistent CPU branch cache for surface and cave LOD nodes. */
final class LodBranchDiskCache {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final LodBranchDiskCache INSTANCE = new LodBranchDiskCache();
    private static final int MAGIC = 0x534C4F44; // SLOD
    private static final int VERSION = CaveCacheSchema.LOD_BRANCH_VERSION;
    private static final int INDEX_MAGIC = 0x534C4F49; // SLOI
    private static final int INDEX_VERSION = 2;
    private static final int PIXELS = 64 * 64;
    private static final int MAX_FILE_BYTES = 96 * 1024;
    private static final long DISK_TARGET_BYTES = 512L << 20;
    private static final long WRITE_DEBOUNCE_MS = 600L;
    private static final long INDEX_WRITE_DEBOUNCE_MS = 1_200L;

    private final Map<String, PendingWrite> pendingWrites = new ConcurrentHashMap<>();
    private final Set<String> scheduledWrites = ConcurrentHashMap.newKeySet();
    private final AtomicLong epoch = new AtomicLong(1L);
    private final Map<String, CompletableFuture<Snapshot>> pendingReads = new ConcurrentHashMap<>();
    private final Set<String> knownMissing = ConcurrentHashMap.newKeySet();
    private final AtomicLong completedWrites = new AtomicLong();
    private final Map<String, Long> styleGenerations = new ConcurrentHashMap<>();
    private final Map<Key, Metadata> metadata = new ConcurrentHashMap<>();
    private final AtomicBoolean metadataLoadScheduled = new AtomicBoolean();
    private final AtomicBoolean metadataWriteScheduled = new AtomicBoolean();
    private final AtomicBoolean metadataRebuildScheduled = new AtomicBoolean();
    private final Object metadataRootLock = new Object();
    private volatile String metadataRootPath = "";
    private volatile boolean metadataLoaded;
    private volatile boolean metadataAuthoritative;
    private final AtomicBoolean trimScheduled = new AtomicBoolean();
    private volatile long lastInvalidationNanos;
    private volatile String lastInvalidationRoot = "";

    private LodBranchDiskCache() {
    }

    static LodBranchDiskCache getInstance() {
        return INSTANCE;
    }

    /**
     * Returns false only when the metadata index is loaded and proves the node is
     * absent. While the index is still loading, callers conservatively keep the
     * old file-probe behaviour.
     */
    boolean mayContain(Key key) {
        ensureMetadataIndexAsync();
        return !metadataLoaded || !metadataAuthoritative
                || metadata.containsKey(key);
    }

    Metadata metadata(Key key) {
        ensureMetadataIndexAsync();
        return metadata.get(key);
    }

    CompletableFuture<Snapshot> loadAsync(Key key) {
        ensureMetadataIndexAsync();
        if (metadataLoaded && metadataAuthoritative
                && !metadata.containsKey(key)) {
            return CompletableFuture.completedFuture(null);
        }
        File file = fileFor(key);
        if (file == null) return CompletableFuture.completedFuture(null);
        String path = file.getAbsolutePath();
        if (knownMissing.contains(path)) return CompletableFuture.completedFuture(null);
        if (!file.isFile()) {
            knownMissing.add(path);
            metadata.remove(key);
            scheduleMetadataWrite();
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Snapshot> existing = pendingReads.get(path);
        if (existing != null) return existing;
        CompletableFuture<Snapshot> result = new CompletableFuture<>();
        existing = pendingReads.putIfAbsent(path, result);
        if (existing != null) return existing;

        long requestEpoch = epoch.get();
        CompletableFuture<Snapshot> admitted = MapWorkScheduler.tryIoFuture(
                MapRequestLane.FULLSCREEN, MapWorkScheduler.WorkType.DISK_READ,
                MapRequestLane.FULLSCREEN.priorityBase(), 6,
                () -> requestEpoch == epoch.get(), () -> {
                    try {
                        long size = Files.size(file.toPath());
                        if (size <= 0 || size > MAX_FILE_BYTES) {
                            metadata.remove(key);
                            scheduleMetadataWrite();
                            return null;
                        }
                        try (DataInputStream input = new DataInputStream(
                                new BufferedInputStream(new GZIPInputStream(
                                        new FileInputStream(file))))) {
                            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                                metadata.remove(key);
                                scheduleMetadataWrite();
                                return null;
                            }
                            if (!input.readUTF().equals(key.kind())
                                    || input.readInt() != key.level()
                                    || input.readInt() != key.nodeX()
                                    || input.readInt() != key.nodeZ()) {
                                metadata.remove(key);
                                scheduleMetadataWrite();
                                return null;
                            }
                            long knownMask = input.readLong();
                            long completeMask = input.readLong();
                            long revision = input.readLong();
                            int[] pixels = new int[PIXELS];
                            long[] knownRows = new long[64];
                            long[] completeRows = new long[64];
                            for (int i = 0; i < PIXELS; i++) pixels[i] = input.readInt();
                            for (int i = 0; i < 64; i++) knownRows[i] = input.readLong();
                            for (int i = 0; i < 64; i++) completeRows[i] = input.readLong();
                            if (requestEpoch != epoch.get()) return null;
                            file.setLastModified(System.currentTimeMillis());
                            metadata.merge(key,
                                    new Metadata(knownMask, completeMask, revision),
                                    Metadata::newer);
                            scheduleMetadataWrite();
                            return new Snapshot(pixels, knownRows, completeRows,
                                    knownMask, completeMask, revision);
                        }
                    } catch (Throwable ignoredFailure) {
                        knownMissing.add(path);
                        metadata.remove(key);
                        scheduleMetadataWrite();
                        return null;
                    }
                });
        if (admitted == null) {
            pendingReads.remove(path, result);
            result.complete(null);
            return result;
        }
        admitted.whenComplete((value, throwable) -> {
            pendingReads.remove(path, result);
            if (throwable != null) result.complete(null);
            else result.complete(value);
        });
        return result;
    }

    void saveAsync(Key key, Snapshot snapshot) {
        if (snapshot == null || snapshot.knownMask() == 0L) return;
        ensureMetadataIndexAsync();
        metadata.merge(key,
                new Metadata(snapshot.knownMask(), snapshot.completeMask(), snapshot.revision()),
                Metadata::newer);
        scheduleMetadataWrite();
        File file = fileFor(key);
        if (file == null) return;
        String path = file.getAbsolutePath();
        knownMissing.remove(path);
        pendingWrites.put(path, new PendingWrite(snapshot.deepCopy(), epoch.get()));
        scheduleWrite(key, file, path);
    }

    synchronized void invalidateCurrentDimension() {
        File base = baseDirectory();
        String basePath = base == null ? "" : base.getAbsolutePath();
        long now = System.nanoTime();
        if (basePath.equals(lastInvalidationRoot)
                && now - lastInvalidationNanos < TimeUnit.MILLISECONDS.toNanos(150L)) {
            return; // Cave + Full Cave style invalidators can target the same store.
        }
        lastInvalidationRoot = basePath;
        lastInvalidationNanos = now;
        epoch.incrementAndGet();
        pendingWrites.clear();
        pendingReads.clear();
        knownMissing.clear();
        resetMetadataRoot();
        if (base == null) return;

        long nextGeneration = generationFor(base) + 1L;
        styleGenerations.put(basePath, nextGeneration);
        writeActiveGeneration(base, nextGeneration);
        File active = new File(base, "g" + nextGeneration);
        MapWorkScheduler.tryIo(MapRequestLane.BACKGROUND,
                MapWorkScheduler.WorkType.CACHE_MAINTENANCE, 0, 16,
                () -> true, () -> deleteOldGenerations(base, active));
    }

    private void ensureMetadataIndexAsync() {
        File root = rootDirectory();
        if (root == null) return;
        String path = root.getAbsolutePath();
        synchronized (metadataRootLock) {
            if (!path.equals(metadataRootPath)) {
                metadataRootPath = path;
                metadata.clear();
                metadataLoaded = false;
                metadataAuthoritative = false;
                metadataLoadScheduled.set(false);
                metadataWriteScheduled.set(false);
                metadataRebuildScheduled.set(false);
            }
        }
        if (metadataLoaded || !metadataLoadScheduled.compareAndSet(false, true)) return;
        File index = new File(root, "index.bin");
        boolean accepted = MapWorkScheduler.tryIo(MapRequestLane.FULLSCREEN,
                MapWorkScheduler.WorkType.DISK_READ,
                MapRequestLane.FULLSCREEN.priorityBase(), 4,
                () -> true, () -> {
                    MetadataIndex loaded = readMetadataIndex(index);
                    synchronized (metadataRootLock) {
                        if (path.equals(metadataRootPath)) {
                            for (Map.Entry<Key, Metadata> entry : loaded.entries().entrySet()) {
                                metadata.merge(entry.getKey(), entry.getValue(), Metadata::newer);
                            }
                            metadataLoaded = true;
                            metadataAuthoritative = loaded.authoritative();
                        }
                        metadataLoadScheduled.set(false);
                    }
                    if (!loaded.authoritative()) {
                        scheduleMetadataRebuild(root, path);
                    }
                });
        if (!accepted) metadataLoadScheduled.set(false);
    }

    private void resetMetadataRoot() {
        synchronized (metadataRootLock) {
            metadataRootPath = "";
            metadata.clear();
            metadataLoaded = false;
            metadataAuthoritative = false;
            metadataLoadScheduled.set(false);
            metadataWriteScheduled.set(false);
            metadataRebuildScheduled.set(false);
        }
    }

    private void scheduleMetadataRebuild(File root, String path) {
        if (root == null || !metadataRebuildScheduled.compareAndSet(false, true)) return;
        boolean accepted = MapWorkScheduler.tryIo(MapRequestLane.BACKGROUND,
                MapWorkScheduler.WorkType.CACHE_MAINTENANCE,
                0, 20, () -> true, () -> {
                    try {
                        Map<Key, Metadata> rebuilt = rebuildMetadata(root);
                        synchronized (metadataRootLock) {
                            if (!path.equals(metadataRootPath)) return;
                            for (Map.Entry<Key, Metadata> entry : rebuilt.entrySet()) {
                                metadata.merge(entry.getKey(), entry.getValue(), Metadata::newer);
                            }
                            metadataLoaded = true;
                            metadataAuthoritative = true;
                        }
                        scheduleMetadataWrite();
                    } finally {
                        metadataRebuildScheduled.set(false);
                    }
                });
        if (!accepted) metadataRebuildScheduled.set(false);
    }

    private static Map<Key, Metadata> rebuildMetadata(File root) {
        Map<Key, Metadata> result = new HashMap<>();
        List<File> files = new ArrayList<>();
        collectCacheFiles(root, files);
        for (File file : files) {
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                    new GZIPInputStream(new FileInputStream(file))))) {
                if (input.readInt() != MAGIC || input.readInt() != VERSION) continue;
                Key key = new Key(input.readUTF(), input.readInt(),
                        input.readInt(), input.readInt());
                Metadata value = new Metadata(input.readLong(), input.readLong(),
                        input.readLong());
                if (value.knownMask() != 0L) {
                    result.merge(key, value, Metadata::newer);
                }
            } catch (IOException ignored) {
            }
        }
        return result;
    }

    private void scheduleMetadataWrite() {
        if (!metadataWriteScheduled.compareAndSet(false, true)) return;
        MapWorkScheduler.scheduleIo(INDEX_WRITE_DEBOUNCE_MS, TimeUnit.MILLISECONDS,
                MapRequestLane.BACKGROUND, MapWorkScheduler.WorkType.DISK_WRITE,
                0, 4, () -> true, this::writeMetadataIndex);
    }

    private void writeMetadataIndex() {
        try {
            File root = rootDirectory();
            if (root == null) return;
            String path = root.getAbsolutePath();
            if (!path.equals(metadataRootPath)) return;
            if (!root.isDirectory() && !root.mkdirs()) return;
            File index = new File(root, "index.bin");
            File temporary = new File(root, "index.bin.tmp");
            Map<Key, Metadata> snapshot = new HashMap<>(metadata);
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(temporary)))) {
                output.writeInt(INDEX_MAGIC);
                output.writeInt(INDEX_VERSION);
                output.writeBoolean(metadataAuthoritative);
                output.writeInt(snapshot.size());
                for (Map.Entry<Key, Metadata> entry : snapshot.entrySet()) {
                    Key key = entry.getKey();
                    Metadata value = entry.getValue();
                    output.writeUTF(key.kind());
                    output.writeInt(key.level());
                    output.writeInt(key.nodeX());
                    output.writeInt(key.nodeZ());
                    output.writeLong(value.knownMask());
                    output.writeLong(value.completeMask());
                    output.writeLong(value.revision());
                }
            }
            try {
                Files.move(temporary.toPath(), index.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), index.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            // The index is authoritative only for generations that started with
            // an index. A migrated V16.4 directory may contain old branch files
            // not yet discovered, so do not claim absence until the next restart
            // reads the newly written complete-as-known index.
        } catch (IOException ignored) {
        } finally {
            metadataWriteScheduled.set(false);
        }
    }

    private static MetadataIndex readMetadataIndex(File index) {
        Map<Key, Metadata> result = new HashMap<>();
        if (index == null || !index.isFile()) return new MetadataIndex(result, false);
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(new FileInputStream(index)))) {
            if (input.readInt() != INDEX_MAGIC || input.readInt() != INDEX_VERSION) {
                return new MetadataIndex(result, false);
            }
            boolean authoritative = input.readBoolean();
            int count = input.readInt();
            if (count < 0 || count > 1_000_000) {
                return new MetadataIndex(result, false);
            }
            for (int i = 0; i < count; i++) {
                Key key = new Key(input.readUTF(), input.readInt(),
                        input.readInt(), input.readInt());
                Metadata value = new Metadata(input.readLong(), input.readLong(),
                        input.readLong());
                if (value.knownMask() != 0L) result.put(key, value);
            }
            return new MetadataIndex(result, authoritative);
        } catch (IOException ignored) {
            result.clear();
            return new MetadataIndex(result, false);
        }
    }

    private void scheduleWrite(Key key, File file, String path) {
        if (!scheduledWrites.add(path)) return;
        MapWorkScheduler.scheduleIo(WRITE_DEBOUNCE_MS, TimeUnit.MILLISECONDS,
                MapRequestLane.BACKGROUND, MapWorkScheduler.WorkType.DISK_WRITE,
                0, 8, () -> true, () -> drainWrite(key, file, path));
    }

    private void drainWrite(Key key, File file, String path) {
        PendingWrite pending = pendingWrites.remove(path);
        if (pending == null || pending.epoch() != epoch.get()) {
            finishWriteSchedule(key, file, path);
            return;
        }
        Snapshot snapshot = pending.snapshot();
        File parent = file.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            finishWriteSchedule(key, file, path);
            return;
        }
        File temporary = new File(parent, file.getName() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(new FileOutputStream(temporary))))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeUTF(key.kind());
            output.writeInt(key.level());
            output.writeInt(key.nodeX());
            output.writeInt(key.nodeZ());
            output.writeLong(snapshot.knownMask());
            output.writeLong(snapshot.completeMask());
            output.writeLong(snapshot.revision());
            for (int value : snapshot.pixels()) output.writeInt(value);
            for (long value : snapshot.knownRows()) output.writeLong(value);
            for (long value : snapshot.completeRows()) output.writeLong(value);
        } catch (IOException failure) {
            temporary.delete();
            finishWriteSchedule(key, file, path);
            return;
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
        } catch (IOException failure) {
            temporary.delete();
        }
        completedWrites.incrementAndGet();
        scheduleTrim();
        finishWriteSchedule(key, file, path);
    }

    private void finishWriteSchedule(Key key, File file, String path) {
        scheduledWrites.remove(path);
        if (pendingWrites.containsKey(path)) scheduleWrite(key, file, path);
    }

    private void scheduleTrim() {
        long writes = completedWrites.get();
        if ((writes & 63L) != 0L || !trimScheduled.compareAndSet(false, true)) return;
        File root = rootDirectory();
        if (root == null) {
            trimScheduled.set(false);
            return;
        }
        MapWorkScheduler.scheduleIo(2L, TimeUnit.SECONDS,
                MapRequestLane.BACKGROUND,
                MapWorkScheduler.WorkType.CACHE_MAINTENANCE,
                0, 24, () -> true, () -> {
                    try {
                        trimDiskCache(root);
                    } finally {
                        trimScheduled.set(false);
                    }
                });
    }

    private static void trimDiskCache(File root) {
        if (root == null || !root.isDirectory()) return;
        List<File> files = new ArrayList<>();
        collectCacheFiles(root, files);
        long total = 0L;
        for (File file : files) total += Math.max(0L, file.length());
        if (total <= DISK_TARGET_BYTES) return;
        files.sort(Comparator.comparingLong(File::lastModified));
        long target = DISK_TARGET_BYTES * 9L / 10L;
        for (File file : files) {
            if (total <= target) break;
            long size = Math.max(0L, file.length());
            try {
                if (Files.deleteIfExists(file.toPath())) total -= size;
            } catch (IOException ignored) {
            }
        }
    }

    private static void collectCacheFiles(File file, List<File> output) {
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collectCacheFiles(child, output);
            else if (child.getName().endsWith(".lod.gz")) output.add(child);
        }
    }

    private File fileFor(Key key) {
        File root = rootDirectory();
        if (root == null) return null;
        int regionX = Math.floorDiv(key.nodeX(), 64);
        int regionZ = Math.floorDiv(key.nodeZ(), 64);
        File directory = new File(new File(new File(root, safe(key.kind())),
                "l" + key.level()), "r." + regionX + '.' + regionZ);
        return new File(directory, "n." + key.nodeX() + '.' + key.nodeZ() + ".lod.gz");
    }

    private File rootDirectory() {
        File base = baseDirectory();
        if (base == null) return null;
        return new File(base, "g" + generationFor(base));
    }

    private static File baseDirectory() {
        File dimension = MapManager.getInstance().getCurrentDimensionDir();
        return dimension == null ? null : new File(dimension, ".lod_v2");
    }

    private long generationFor(File base) {
        String path = base.getAbsolutePath();
        return styleGenerations.computeIfAbsent(path, ignored -> readActiveGeneration(base));
    }

    private static long readActiveGeneration(File base) {
        File marker = new File(base, "active.gen");
        if (!marker.isFile()) return 1L;
        try {
            String value = Files.readString(marker.toPath()).trim();
            return Math.max(1L, Long.parseLong(value));
        } catch (Throwable ignored) {
            return 1L;
        }
    }

    private static void writeActiveGeneration(File base, long generation) {
        if (!base.isDirectory() && !base.mkdirs()) return;
        File marker = new File(base, "active.gen");
        File temporary = new File(base, "active.gen.tmp");
        try {
            Files.writeString(temporary.toPath(), Long.toString(generation));
            try {
                Files.move(temporary.toPath(), marker.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), marker.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            temporary.delete();
        }
    }

    private static void deleteOldGenerations(File base, File active) {
        File[] children = base.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory() || child.equals(active)
                    || !child.getName().startsWith("g")) continue;
            deleteRecursively(child);
        }
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException ignored) {
        }
    }

    private record MetadataIndex(Map<Key, Metadata> entries,
            boolean authoritative) {
    }

    record Metadata(long knownMask, long completeMask, long revision) {
        private static Metadata newer(Metadata left, Metadata right) {
            if (left == null) return right;
            if (right == null) return left;
            return right.revision() >= left.revision() ? right : left;
        }
    }

    record Key(String kind, int level, int nodeX, int nodeZ) {
    }

    private record PendingWrite(Snapshot snapshot, long epoch) {
    }

    record Snapshot(int[] pixels, long[] knownRows, long[] completeRows,
            long knownMask, long completeMask, long revision) {
        Snapshot {
            if (pixels == null || pixels.length != PIXELS
                    || knownRows == null || knownRows.length != 64
                    || completeRows == null || completeRows.length != 64) {
                throw new IllegalArgumentException("Invalid LOD branch snapshot");
            }
        }

        Snapshot deepCopy() {
            return new Snapshot(Arrays.copyOf(pixels, pixels.length),
                    Arrays.copyOf(knownRows, knownRows.length),
                    Arrays.copyOf(completeRows, completeRows.length),
                    knownMask, completeMask, revision);
        }
    }
}
