package com.velorise.simplemap.client.cave.archive;

import com.velorise.simplemap.client.MapDebugRecorder;
import com.velorise.simplemap.client.cave.CaveChunkTile;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compact cave archive partitioned by canonical dimension id.
 *
 * <p>Xaero owns map layers from a {@code MapDimension}; a chunk coordinate is
 * therefore never a world-global identity. SimpleMap used to keep this service
 * session-scoped but dimension-blind, so Overworld/Nether/End/custom dimensions
 * at the same chunk coordinates could share fingerprints, completeness and
 * resident compact tiles across a dimension handoff. PASS125 makes dimension an
 * explicit archive ownership boundary while retaining lightweight indexed
 * identity for recently visited dimensions.</p>
 */
public final class CaveArchiveV2Service {
    public record Summary(int tiles, long bytes, long ingested,
            long replaced, long staleIgnored) { }

    private static final CaveArchiveV2Service INSTANCE =
            new CaveArchiveV2Service();
    private static final int MAX_RESIDENT_TILES = 32768;
    private static final long MIB = 1024L * 1024L;
    private static final long MIN_RESIDENT_BYTES = 64L * MIB;
    private static final long MAX_RESIDENT_BYTES =
            calculateResidentByteLimit();
    /** Metadata-only inactive partitions are cheap; bound pathological modpacks. */
    private static final int MAX_DIMENSION_PARTITIONS = 8;
    private static final String UNKNOWN_DIMENSION = "simplemap:unknown";

    private static final class Partition {
        final LinkedHashMap<Long, CompactCaveTile> tiles =
                new LinkedHashMap<>(256, 0.75f, true);
        final Map<Long, Long> pageFingerprints = new HashMap<>();
        final Map<Long, Long> indexedContributions = new HashMap<>();
        final Map<Long, Long> indexedFingerprints = new HashMap<>();
        final Set<Long> indexedCompleteChunks = new HashSet<>();
        final Set<Long> indexedFullProjectionChunks = new HashSet<>();
        /** Persisted complete zero-run tiles are visual hints, not strong empty proof. */
        final Set<Long> persistedUnverifiedEmptyChunks = new HashSet<>();
        /** Content fingerprints verified against LIVE/WORLD_SAVE in this session. */
        final Map<Long, Long> currentSessionVerifiedFingerprints = new HashMap<>();
        long bytes;
        long ingested;
        long replaced;
        long staleIgnored;

        void dropResidents() {
            tiles.clear();
            bytes = 0L;
        }

        void clear() {
            dropResidents();
            pageFingerprints.clear();
            indexedContributions.clear();
            indexedFingerprints.clear();
            indexedCompleteChunks.clear();
            indexedFullProjectionChunks.clear();
            persistedUnverifiedEmptyChunks.clear();
            currentSessionVerifiedFingerprints.clear();
            ingested = 0L;
            replaced = 0L;
            staleIgnored = 0L;
        }
    }

    /** Access ordered so very old custom dimensions can be forgotten safely. */
    private final LinkedHashMap<String, Partition> partitions =
            new LinkedHashMap<>(8, 0.75f, true);
    private String activeDimension = UNKNOWN_DIMENSION;
    private Partition activePartition;

    private static long calculateResidentByteLimit() {
        /*
         * PASS139: a fixed 192 MiB old-generation archive is too aggressive on a
         * 4 GiB client heap. The supplied run reached ~3.1/4.0 GiB used while the
         * archive alone held ~178 MiB, increasing full-GC scan pressure exactly
         * when cold Cave streaming was allocating heavily. Keep a useful retained
         * LRU, but scale it with heap and leave the persistent archive as the
         * durable source of truth.
         */
        long heap = Math.max(512L * MIB,
                Runtime.getRuntime().maxMemory());
        return Math.max(MIN_RESIDENT_BYTES,
                Math.min(160L * MIB, heap / 32L));
    }

    private CaveArchiveV2Service() {
        activePartition = new Partition();
        partitions.put(activeDimension, activePartition);
    }

    public static CaveArchiveV2Service getInstance() { return INSTANCE; }

    /**
     * Selects the archive namespace before persistence replay/live ingestion for a
     * map dimension. Inactive resident tiles are released so dimensions do not each
     * reserve a 192 MiB working set, while indexed fingerprints/completeness remain
     * available for idempotent replay when the user returns.
     */
    public synchronized void activateDimension(String dimension) {
        String next = normalizeDimension(dimension);
        if (next.equals(activeDimension)) return;

        Partition previous = activePartition;
        if (previous != null) previous.dropResidents();

        activeDimension = next;
        activePartition = partitions.computeIfAbsent(next, ignored -> new Partition());
        trimPartitions();
    }

    public synchronized String activeDimension() {
        return activeDimension;
    }

    public synchronized boolean ingest(CaveChunkTile.Snapshot snapshot) {
        CompactCaveTile compact = CompactCaveTile.fromLegacy(snapshot);
        if (compact == null) return false;
        return ingestCompact(activePartition, compact, true);
    }

    public synchronized CompactCaveTile get(int chunkX, int chunkZ) {
        return activePartition.tiles.get(pack(chunkX, chunkZ));
    }

    /** Copies a rectangular resident window under one monitor hold. */
    public synchronized void fillWindow(int firstChunkX, int firstChunkZ,
            int edge, CompactCaveTile[] target) {
        if (edge <= 0 || target == null || target.length < edge * edge) {
            throw new IllegalArgumentException("archive window");
        }
        Partition partition = activePartition;
        int index = 0;
        for (int dz = 0; dz < edge; dz++) {
            for (int dx = 0; dx < edge; dx++) {
                target[index++] = partition.tiles.get(pack(firstChunkX + dx,
                        firstChunkZ + dz));
            }
        }
    }

    public synchronized boolean isResident(int chunkX, int chunkZ) {
        return activePartition.tiles.get(pack(chunkX, chunkZ)) != null;
    }

    public synchronized boolean hasCompleteChunk(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        CompactCaveTile tile = activePartition.tiles.get(key);
        return tile != null && tile.completeCoverage()
                && isProjectionTrusted(activePartition, key);
    }

    public synchronized boolean hasFullProjectionChunk(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        CompactCaveTile tile = activePartition.tiles.get(key);
        return tile != null && tile.fullProjectionCoverage()
                && isProjectionTrusted(activePartition, key);
    }

    /**
     * True when a resident persisted all-empty tile still needs one authoritative
     * LIVE/WORLD_SAVE read in this session before it may close a visible hole.
     */
    public synchronized boolean requiresCurrentSourceVerification(int chunkX, int chunkZ) {
        return activePartition.persistedUnverifiedEmptyChunks.contains(
                pack(chunkX, chunkZ));
    }

    /** Strong-empty proof may only use source verified under the current Cave epoch. */
    public synchronized boolean isStrongEmptyProofTrusted(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        return activePartition.tiles.containsKey(key)
                && isProjectionTrusted(activePartition, key);
    }

    /** Used by persistence replay without rebuilding a legacy snapshot. */
    public synchronized boolean ingest(CompactCaveTile compact) {
        if (compact == null) return false;
        return ingestCompact(activePartition, compact, true);
    }

    /**
     * Publishes one persisted native-region snapshot under a single archive lock.
     * Readers therefore observe either the old resident set or the complete refill,
     * never hundreds of intermediate page fingerprints while one SMR2 file is being
     * replayed. This mirrors Xaero's region-cache handoff.
     */
    public synchronized int ingestBatch(List<CompactCaveTile> compactTiles) {
        if (compactTiles == null || compactTiles.isEmpty()) return 0;
        Partition partition = activePartition;
        int accepted = 0;
        for (CompactCaveTile compact : compactTiles) {
            if (compact == null) continue;
            ingestCompactWithoutTrim(partition, compact, false);
            accepted++;
        }
        trim(partition);
        return accepted;
    }

    /** 16-bit central-page resident coverage, ordered localX * 4 + localZ. */
    public synchronized int residentProjectionMask(int globalPageX,
            int globalPageZ, boolean fullProjection) {
        Partition partition = activePartition;
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        int mask = 0;
        for (int localZ = 0; localZ < 4; localZ++) {
            for (int localX = 0; localX < 4; localX++) {
                CompactCaveTile tile = partition.tiles.get(pack(
                        firstChunkX + localX, firstChunkZ + localZ));
                long key = pack(firstChunkX + localX, firstChunkZ + localZ);
                boolean covered = tile != null && (fullProjection
                        ? tile.fullProjectionCoverage()
                        : tile.completeCoverage())
                        && isProjectionTrusted(partition, key);
                if (covered) mask |= 1 << (localX * 4 + localZ);
            }
        }
        return mask;
    }

    /** 16-bit central-page residency regardless of projection completeness. */
    public synchronized int residentAnyMask(int globalPageX, int globalPageZ) {
        Partition partition = activePartition;
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        int mask = 0;
        for (int localZ = 0; localZ < 4; localZ++) {
            for (int localX = 0; localX < 4; localX++) {
                if (partition.tiles.containsKey(pack(firstChunkX + localX,
                        firstChunkZ + localZ))) {
                    mask |= 1 << (localX * 4 + localZ);
                }
            }
        }
        return mask;
    }

    /** 16-bit persistent identity coverage, independent from resident LRU state. */
    public synchronized int indexedAnyMask(int globalPageX, int globalPageZ) {
        Partition partition = activePartition;
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        int mask = 0;
        for (int localZ = 0; localZ < 4; localZ++) {
            for (int localX = 0; localX < 4; localX++) {
                if (partition.indexedFingerprints.containsKey(pack(
                        firstChunkX + localX, firstChunkZ + localZ))) {
                    mask |= 1 << (localX * 4 + localZ);
                }
            }
        }
        return mask;
    }

    /** Installs persistent source identity without forcing tile residency. */
    public synchronized boolean index(CompactCaveTile compact) {
        if (compact == null) return false;
        Partition partition = activePartition;
        long key = pack(compact.chunkX(), compact.chunkZ());
        long contentFingerprint = compact.contentFingerprint();
        long previousFingerprint = partition.indexedFingerprints.getOrDefault(
                key, Long.MIN_VALUE);
        updateVerificationState(partition, key, compact, false);
        if (previousFingerprint == contentFingerprint) return false;

        long previousContribution = partition.indexedContributions.getOrDefault(key, 0L);
        long currentContribution = tileContribution(compact);
        updatePageFingerprint(partition, compact.chunkX(), compact.chunkZ(),
                previousContribution, currentContribution);
        partition.indexedContributions.put(key, currentContribution);
        partition.indexedFingerprints.put(key, contentFingerprint);
        setCoverage(partition, key, compact);
        return true;
    }

    /** 16-bit central-page indexed coverage that survives resident LRU eviction. */
    public synchronized int indexedProjectionMask(int globalPageX,
            int globalPageZ, boolean fullProjection) {
        Partition partition = activePartition;
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        Set<Long> coverage = fullProjection
                ? partition.indexedFullProjectionChunks
                : partition.indexedCompleteChunks;
        int mask = 0;
        for (int localZ = 0; localZ < 4; localZ++) {
            for (int localX = 0; localX < 4; localX++) {
                long key = pack(firstChunkX + localX, firstChunkZ + localZ);
                if (coverage.contains(key) && isProjectionTrusted(partition, key)) {
                    mask |= 1 << (localX * 4 + localZ);
                }
            }
        }
        return mask;
    }

    public synchronized boolean hasCompletePage(int globalPageX, int globalPageZ) {
        return residentProjectionMask(globalPageX, globalPageZ, false) == 0xFFFF;
    }

    public synchronized boolean hasFullProjectionPage(int globalPageX,
            int globalPageZ) {
        return residentProjectionMask(globalPageX, globalPageZ, true) == 0xFFFF;
    }

    public synchronized boolean hasIndexedCompleteChunk(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        return activePartition.indexedCompleteChunks.contains(key)
                && isProjectionTrusted(activePartition, key);
    }

    public synchronized boolean hasIndexedFullProjectionChunk(int chunkX,
            int chunkZ) {
        long key = pack(chunkX, chunkZ);
        return activePartition.indexedFullProjectionChunks.contains(key)
                && isProjectionTrusted(activePartition, key);
    }

    public synchronized boolean hasIndexedCompletePage(int globalPageX,
            int globalPageZ) {
        return indexedProjectionMask(globalPageX, globalPageZ, false) == 0xFFFF;
    }

    public synchronized boolean hasIndexedFullProjectionPage(int globalPageX,
            int globalPageZ) {
        return indexedProjectionMask(globalPageX, globalPageZ, true) == 0xFFFF;
    }

    private boolean ingestCompact(Partition partition, CompactCaveTile compact,
            boolean verifiedCurrentSource) {
        boolean changed = ingestCompactWithoutTrim(partition, compact,
                verifiedCurrentSource);
        trim(partition);
        return changed;
    }

    private boolean ingestCompactWithoutTrim(Partition partition,
            CompactCaveTile compact, boolean verifiedCurrentSource) {
        long key = pack(compact.chunkX(), compact.chunkZ());
        long contentFingerprint = compact.contentFingerprint();
        if (!verifiedCurrentSource) {
            Long verifiedFingerprint =
                    partition.currentSessionVerifiedFingerprints.get(key);
            if (verifiedFingerprint != null
                    && verifiedFingerprint.longValue() != contentFingerprint) {
                partition.staleIgnored++;
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String eventKey = "CAVE_PERSISTED_ARCHIVE_STALE_AGAINST_CURRENT:"
                        + compact.chunkX() + ':' + compact.chunkZ();
                if (recorder.shouldEmitEvent(eventKey, 2_000L)) {
                    recorder.event("CAVE_PERSISTED_ARCHIVE_STALE_AGAINST_CURRENT",
                            "chunk=" + compact.chunkX() + ',' + compact.chunkZ()
                                    + " persisted_fingerprint="
                                    + Long.toUnsignedString(contentFingerprint, 16)
                                    + " current_fingerprint="
                                    + Long.toUnsignedString(verifiedFingerprint, 16)
                                    + " action=ignore_persisted_replay"
                                    + " pass=PASS154");
                }
                return false;
            }
        }
        updateVerificationState(partition, key, compact, verifiedCurrentSource);
        long indexedFingerprint = partition.indexedFingerprints.getOrDefault(
                key, Long.MIN_VALUE);
        if (indexedFingerprint == contentFingerprint) {
            partition.staleIgnored++;
            CompactCaveTile resident = partition.tiles.get(key);
            if (resident == null) {
                partition.tiles.put(key, compact);
                partition.bytes += compact.estimatedBytes();
            }
            return false;
        }

        CompactCaveTile residentPrevious = partition.tiles.get(key);
        if (residentPrevious != null) {
            partition.bytes -= residentPrevious.estimatedBytes();
            partition.replaced++;
        } else if (indexedFingerprint != Long.MIN_VALUE) {
            partition.replaced++;
        }

        long previousContribution = partition.indexedContributions.getOrDefault(key, 0L);
        long currentContribution = tileContribution(compact);
        updatePageFingerprint(partition, compact.chunkX(), compact.chunkZ(),
                previousContribution, currentContribution);
        partition.indexedContributions.put(key, currentContribution);
        partition.indexedFingerprints.put(key, contentFingerprint);
        setCoverage(partition, key, compact);

        partition.tiles.put(key, compact);
        partition.bytes += compact.estimatedBytes();
        partition.ingested++;
        return true;
    }

    private static boolean isProjectionTrusted(Partition partition, long key) {
        return !partition.persistedUnverifiedEmptyChunks.contains(key);
    }

    private static boolean persistedEmptyCandidate(CompactCaveTile compact) {
        return compact != null && compact.runCount() == 0
                && compact.fullProjectionCoverage();
    }

    /**
     * Persistence is a cache, not source authority. A zero-run tile replayed from
     * SMR2 cannot prove that an entire 16x16 chunk is truly cave-empty until the
     * current world source confirms the same content fingerprint once this session.
     * This specifically prevents historical false-empty cache entries from becoming
     * permanent 64x64 black holes across reload, Top-Y changes and Full Cave.
     */
    private static void updateVerificationState(Partition partition, long key,
            CompactCaveTile compact, boolean verifiedCurrentSource) {
        long fingerprint = compact.contentFingerprint();
        boolean wasUnverified = partition.persistedUnverifiedEmptyChunks.contains(key);
        if (verifiedCurrentSource) {
            partition.currentSessionVerifiedFingerprints.put(key, fingerprint);
            partition.persistedUnverifiedEmptyChunks.remove(key);
            if (wasUnverified) {
                MapDebugRecorder.getInstance().event(
                        compact.runCount() == 0
                                ? "CAVE_PERSISTED_EMPTY_REVERIFIED"
                                : "CAVE_PERSISTED_EMPTY_CORRECTED",
                        "chunk=" + compact.chunkX() + ',' + compact.chunkZ()
                                + " runs=" + compact.runCount()
                                + " fingerprint=" + Long.toUnsignedString(fingerprint, 16)
                                + " policy=current_world_source_closes_persisted_empty_proof"
                                + " pass=PASS154");
            }
            return;
        }

        Long verifiedFingerprint = partition.currentSessionVerifiedFingerprints.get(key);
        boolean alreadyVerified = verifiedFingerprint != null
                && verifiedFingerprint.longValue() == fingerprint;
        if (persistedEmptyCandidate(compact) && !alreadyVerified) {
            if (partition.persistedUnverifiedEmptyChunks.add(key)) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String eventKey = "CAVE_PERSISTED_EMPTY_REVERIFY_REQUIRED:"
                        + compact.chunkX() + ':' + compact.chunkZ();
                if (recorder.shouldEmitEvent(eventKey, 2_000L)) {
                    recorder.event("CAVE_PERSISTED_EMPTY_REVERIFY_REQUIRED",
                            "chunk=" + compact.chunkX() + ',' + compact.chunkZ()
                                    + " fingerprint="
                                    + Long.toUnsignedString(fingerprint, 16)
                                    + " policy=persisted_zero_run_is_not_strong_empty_authority"
                                    + " pass=PASS154");
                }
            }
        } else {
            partition.persistedUnverifiedEmptyChunks.remove(key);
        }
    }

    public synchronized Summary summary() {
        Partition partition = activePartition;
        return new Summary(partition.tiles.size(), partition.bytes,
                partition.ingested, partition.replaced, partition.staleIgnored);
    }

    /** Source revision consumed by exact-page, CIMG and branch cache validation. */
    public synchronized long pageRevision(int globalPageX, int globalPageZ) {
        return activePartition.pageFingerprints.getOrDefault(
                pack(globalPageX, globalPageZ), 0L);
    }

    /** True world/session reset. Dimension switching uses activateDimension(). */
    public synchronized void clear() {
        for (Partition partition : partitions.values()) partition.clear();
        partitions.clear();
        activeDimension = UNKNOWN_DIMENSION;
        activePartition = new Partition();
        partitions.put(activeDimension, activePartition);
    }

    private static void setCoverage(Partition partition, long key,
            CompactCaveTile compact) {
        if (compact.completeCoverage()) partition.indexedCompleteChunks.add(key);
        else partition.indexedCompleteChunks.remove(key);
        if (compact.fullProjectionCoverage()) {
            partition.indexedFullProjectionChunks.add(key);
        } else {
            partition.indexedFullProjectionChunks.remove(key);
        }
    }

    private static void updatePageFingerprint(Partition partition, int chunkX,
            int chunkZ, long previousContribution, long currentContribution) {
        int pageX = Math.floorDiv(chunkX, 4);
        int pageZ = Math.floorDiv(chunkZ, 4);
        long pageKey = pack(pageX, pageZ);
        long fingerprint = partition.pageFingerprints.getOrDefault(pageKey, 0L);
        if (previousContribution != 0L) fingerprint ^= previousContribution;
        if (currentContribution != 0L) fingerprint ^= currentContribution;
        if (fingerprint == 0L) partition.pageFingerprints.remove(pageKey);
        else partition.pageFingerprints.put(pageKey, fingerprint);
    }

    private static long tileContribution(CompactCaveTile tile) {
        long value = tile.contentFingerprint()
                ^ Long.rotateLeft(pack(tile.chunkX(), tile.chunkZ()), 17)
                ^ ((long) tile.runCount() << 32)
                ^ (tile.completeCoverage()
                        ? 0x6C8E9CF570932BD5L : 0xA5A5A5A55A5A5A5AL);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return value == 0L ? 1L : value;
    }

    private static void trim(Partition partition) {
        var iterator = partition.tiles.entrySet().iterator();
        while ((partition.tiles.size() > MAX_RESIDENT_TILES
                || partition.bytes > MAX_RESIDENT_BYTES) && iterator.hasNext()) {
            CompactCaveTile evicted = iterator.next().getValue();
            partition.bytes -= evicted.estimatedBytes();
            iterator.remove();
        }
    }

    private void trimPartitions() {
        var iterator = partitions.entrySet().iterator();
        while (partitions.size() > MAX_DIMENSION_PARTITIONS && iterator.hasNext()) {
            Map.Entry<String, Partition> entry = iterator.next();
            if (entry.getKey().equals(activeDimension)) continue;
            entry.getValue().clear();
            iterator.remove();
        }
    }

    private static String normalizeDimension(String dimension) {
        if (dimension == null || dimension.isBlank()) return UNKNOWN_DIMENSION;
        return dimension.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}
