package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapDebugRecorder;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapWorkScheduler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Request-local generated-chunk presence for native Anvil regions.
 *
 * <p>The old index enumerated every {@code .mca} file and synchronously read every
 * 4 KiB location table whenever a visible request crossed its refresh interval. A minimap request now performs only cache lookups and bounded scheduler
 * admission. The IO worker stats and, when necessary, reads the one exact region
 * file requested by a native import.</p>
 */
final class AnvilPagePresenceIndex {
    private static final AnvilPagePresenceIndex INSTANCE =
            new AnvilPagePresenceIndex();
    private static final int MAX_CACHE = 256;
    private static final long REFRESH_INTERVAL_MS = 5_000L;
    private static final long FAILURE_RETRY_MS = 250L;
    private static final long ADMISSION_RETRY_MS = 10L;
    private static final int HEADER_BYTES = 4_096;
    private static final long ABSENT_FILE_STAMP = Long.MIN_VALUE;

    private final Map<RegionPresenceKey, PresenceEntry> cache =
            new LinkedHashMap<>(64, 0.75f, true);
    private final Map<RegionPresenceKey, Long> inFlight = new HashMap<>();
    private final ThreadLocal<ByteBuffer> headerBuffer =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(HEADER_BYTES)
                    .order(ByteOrder.BIG_ENDIAN));

    private long epoch = 1L;
    private long generation = 1L;
    private long regionRequests;
    private long cacheHits;
    private long headerReads;
    private long bytesRead;
    private long refreshNanos;
    private long refreshMaxNanos;

    private AnvilPagePresenceIndex() {
    }

    static AnvilPagePresenceIndex getInstance() {
        return INSTANCE;
    }

    synchronized void reset() {
        epoch++;
        cache.clear();
        inFlight.clear();
        generation++;
        regionRequests = 0L;
        cacheHits = 0L;
        headerReads = 0L;
        bytesRead = 0L;
        refreshNanos = 0L;
        refreshMaxNanos = 0L;
    }

    /** Cache-only hot-path lookup. This method never stats, opens or reads a file. */
    RegionSnapshot getCached(ServerLevel level, int regionX, int regionZ) {
        PresenceTarget target = target(level, regionX, regionZ);
        if (target == null) return null;
        synchronized (this) {
            PresenceEntry entry = cache.get(target.key());
            if (entry == null || entry.snapshot == null) return null;
            cacheHits++;
            return entry.snapshot;
        }
    }

    /**
     * Admits one exact 4 KiB header refresh to the shared IO domain. Scheduler
     * saturation retains the request as stale and lets the next viewport pulse
     * retry; work is never executed inline on the caller/render thread.
     */
    void requestAsync(ServerLevel level, int regionX, int regionZ,
            MapRequestLane lane) {
        PresenceTarget target = target(level, regionX, regionZ);
        if (target == null) return;
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        long requestEpoch;
        RegionSnapshot previous;
        long now = System.currentTimeMillis();
        synchronized (this) {
            PresenceEntry entry = cache.computeIfAbsent(target.key(),
                    ignored -> new PresenceEntry());
            if (now < entry.nextRefreshMs || inFlight.containsKey(target.key())) return;
            requestEpoch = epoch;
            previous = entry.snapshot;
            inFlight.put(target.key(), requestEpoch);
            entry.nextRefreshMs = Long.MAX_VALUE;
        }

        CompletableFuture<LoadResult> future = MapWorkScheduler.tryIoFuture(
                effectiveLane, MapWorkScheduler.WorkType.DISK_READ,
                effectiveLane.priorityBase(), 4,
                () -> isEpochCurrent(requestEpoch),
                () -> loadOneRegionHeader(target, previous));
        if (future == null) {
            synchronized (this) {
                inFlight.remove(target.key(), requestEpoch);
                PresenceEntry entry = cache.get(target.key());
                if (entry != null) entry.nextRefreshMs = now + ADMISSION_RETRY_MS;
                trimCacheLocked();
            }
            return;
        }

        synchronized (this) {
            regionRequests++;
        }
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (recorder.shouldEmitEvent("ANVIL_PRESENCE_REGION_REQUEST", 250L)) {
            recorder.event("ANVIL_PRESENCE_REGION_REQUEST",
                    "region=" + regionX + ',' + regionZ
                            + " lane=" + effectiveLane
                            + " mode=REGION thread=" + Thread.currentThread().getName());
        }
        future.whenComplete((result, failure) -> finishRequest(
                target, effectiveLane, requestEpoch, result, failure));
    }

    synchronized DebugSnapshot debugSnapshot() {
        return new DebugSnapshot(cache.size(), inFlight.size(), regionRequests,
                cacheHits, headerReads, bytesRead, refreshNanos, refreshMaxNanos);
    }

    private synchronized boolean isEpochCurrent(long expected) {
        return epoch == expected;
    }

    private void finishRequest(PresenceTarget target, MapRequestLane lane,
            long requestEpoch, LoadResult result, Throwable failure) {
        RegionSnapshot published = null;
        boolean changed = false;
        long nextRefresh = System.currentTimeMillis() + FAILURE_RETRY_MS;
        synchronized (this) {
            inFlight.remove(target.key(), requestEpoch);
            if (epoch != requestEpoch) return;
            PresenceEntry entry = cache.computeIfAbsent(target.key(),
                    ignored -> new PresenceEntry());
            if (failure == null && result != null && result.success()) {
                nextRefresh = System.currentTimeMillis() + REFRESH_INTERVAL_MS;
                headerReads += result.headerRead() ? 1L : 0L;
                bytesRead += result.bytesRead();
                refreshNanos += result.elapsedNanos();
                refreshMaxNanos = Math.max(refreshMaxNanos, result.elapsedNanos());
                RegionSnapshot current = entry.snapshot;
                boolean bitsChanged = current == null
                        || !Arrays.equals(current.chunkBitsUnsafe(), result.chunkBits());
                if (bitsChanged) {
                    published = new RegionSnapshot(target.key().regionX(),
                            target.key().regionZ(), ++generation,
                            result.fileStamp(), result.chunkBits(), result.chunks(), true);
                    entry.snapshot = published;
                    changed = true;
                } else if (current.fileStamp() != result.fileStamp()) {
                    /*
                     * PASS166: integrated-server region timestamps can change while
                     * the generated-chunk location table is identical. Timestamp-only
                     * churn is not a new presence generation; update the stat stamp so
                     * the next probe can skip the 4 KiB header without waking every
                     * page subscriber.
                     */
                    published = new RegionSnapshot(target.key().regionX(),
                            target.key().regionZ(), current.generation(),
                            result.fileStamp(), current.chunkBitsUnsafe(),
                            current.chunks(), true);
                    entry.snapshot = published;
                } else {
                    published = current;
                }
            }
            entry.nextRefreshMs = nextRefresh;
            trimCacheLocked();
        }

        if (result != null && (result.headerRead() || changed)) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            recorder.event("ANVIL_PRESENCE_REFRESH",
                    "mode=REGION region=" + target.key().regionX() + ','
                            + target.key().regionZ()
                            + " region_files_scanned=0 headers_read="
                            + (result.headerRead() ? 1 : 0)
                            + " bytes_read=" + result.bytesRead()
                            + " elapsed_us=" + result.elapsedNanos() / 1_000L
                            + " changed=" + changed + " lane=" + lane
                            + " thread=" + result.threadName());
            if (published != null && changed) {
                recorder.event("ANVIL_PRESENCE_REGION_READY",
                        "region=" + published.regionX() + ',' + published.regionZ()
                                + " generation=" + published.generation()
                                + " chunks=" + published.chunks()
                                + " changed=true lane=" + lane
                                + " thread=" + result.threadName());
            }
        }
    }

    private LoadResult loadOneRegionHeader(PresenceTarget target,
            RegionSnapshot previous) {
        long started = System.nanoTime();
        String threadName = Thread.currentThread().getName();
        Path path = target.regionDirectory().resolve("r."
                + target.key().regionX() + '.' + target.key().regionZ() + ".mca");
        try {
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(path, BasicFileAttributes.class);
            } catch (NoSuchFileException missing) {
                long[] absent = new long[16];
                return new LoadResult(true, ABSENT_FILE_STAMP, absent,
                        0, false, 0, System.nanoTime() - started, threadName);
            }
            if (!attributes.isRegularFile()) {
                long[] absent = new long[16];
                return new LoadResult(true, ABSENT_FILE_STAMP, absent,
                        0, false, 0, System.nanoTime() - started, threadName);
            }
            long stamp = attributes.lastModifiedTime().toMillis()
                    ^ Long.rotateLeft(attributes.size(), 17);
            if (previous != null && previous.fileStamp() == stamp) {
                return new LoadResult(true, stamp, previous.chunkBitsUnsafe(),
                        previous.chunks(), false, 0,
                        System.nanoTime() - started, threadName);
            }

            ByteBuffer header = headerBuffer.get();
            header.clear();
            int readBytes = 0;
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                while (header.hasRemaining()) {
                    int read = channel.read(header);
                    if (read < 0) break;
                    if (read == 0) continue;
                    readBytes += read;
                }
            }
            header.flip();
            long[] bits = new long[16];
            int entries = Math.min(1_024, header.remaining() / Integer.BYTES);
            int found = 0;
            for (int index = 0; index < entries; index++) {
                if (header.getInt() == 0) continue;
                bits[index >>> 6] |= 1L << (index & 63);
                found++;
            }
            return new LoadResult(true, stamp, bits, found, true,
                    readBytes, System.nanoTime() - started, threadName);
        } catch (IOException failure) {
            return new LoadResult(false, 0L, new long[16], 0, false, 0,
                    System.nanoTime() - started, threadName);
        }
    }

    private synchronized void trimCacheLocked() {
        if (cache.size() <= MAX_CACHE) return;
        Iterator<Map.Entry<RegionPresenceKey, PresenceEntry>> iterator =
                cache.entrySet().iterator();
        while (cache.size() > MAX_CACHE && iterator.hasNext()) {
            Map.Entry<RegionPresenceKey, PresenceEntry> entry = iterator.next();
            if (inFlight.containsKey(entry.getKey())) continue;
            iterator.remove();
        }
    }

    private static PresenceTarget target(ServerLevel level, int regionX, int regionZ) {
        if (level == null) return null;
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        Path dimensionRoot = DimensionType.getStorageFolder(level.dimension(), worldRoot);
        Path regionDirectory = dimensionRoot.resolve("region").toAbsolutePath().normalize();
        String identity = level.dimension().location() + "|" + regionDirectory;
        return new PresenceTarget(new RegionPresenceKey(identity, regionX, regionZ),
                regionDirectory);
    }

    record RegionSnapshot(int regionX, int regionZ, long generation,
            long fileStamp, long[] chunkBits, int chunks, boolean ready) {
        RegionSnapshot {
            chunkBits = chunkBits == null ? new long[16]
                    : Arrays.copyOf(chunkBits, 16);
        }

        @Override
        public long[] chunkBits() {
            return Arrays.copyOf(chunkBits, chunkBits.length);
        }

        long[] copyBits() {
            return Arrays.copyOf(chunkBits, chunkBits.length);
        }

        long[] chunkBitsUnsafe() {
            return chunkBits;
        }

        boolean hasChunk(int chunkX, int chunkZ) {
            if (!ready || Math.floorDiv(chunkX, 32) != regionX
                    || Math.floorDiv(chunkZ, 32) != regionZ) return false;
            int local = Math.floorMod(chunkZ, 32) * 32
                    + Math.floorMod(chunkX, 32);
            return (chunkBits[local >>> 6] & (1L << (local & 63))) != 0L;
        }
    }

    record DebugSnapshot(int cachedRegions, int inFlightRegions,
            long regionRequests, long cacheHits, long headerReads,
            long bytesRead, long refreshNanos, long refreshMaxNanos) {
    }

    private record RegionPresenceKey(String identity, int regionX, int regionZ) {
    }

    private record PresenceTarget(RegionPresenceKey key, Path regionDirectory) {
    }

    private record LoadResult(boolean success, long fileStamp, long[] chunkBits,
            int chunks, boolean headerRead, int bytesRead, long elapsedNanos,
            String threadName) {
    }

    private static final class PresenceEntry {
        private RegionSnapshot snapshot;
        private long nextRefreshMs;
    }
}
