package com.velorise.simplemap.client.cave;

import java.util.BitSet;

/**
 * Transactional 16x16 cave unit matching one Minecraft chunk.
 *
 * The scanner may pause between columns, but rendering and persistence observe a
 * monotonically versioned tile. Newly loaded disk data only fills columns that have
 * not already been replaced by live world scans.
 *
 * A completed tile can also be revalidated without removing its currently visible
 * data. Revalidation keeps the old authoritative column until the replacement scan
 * commits, avoiding black flashes after nearby block changes.
 */
public final class CaveChunkTile {
    /**
     * Live authority is a small idempotent lifecycle, separate from persisted
     * column coverage. A disk tile may already be complete before the live client
     * becomes its authority, and a repeated live repair must not erase it.
     */
    public enum LiveAuthorityState {
        NONE,
        LIVE_PENDING,
        LIVE_SCANNING,
        LIVE_COMPLETE
    }

    public enum TileState {
        EMPTY,
        LOADING,
        SCANNING,
        PARTIAL,
        REVALIDATING,
        COMPLETE_TRUNCATED,
        COMPLETE
    }

    public static final int SIZE = 16;
    public static final int COLUMN_COUNT = SIZE * SIZE;

    private final int chunkX;
    private final int chunkZ;
    private final CaveColumnData[] columns = new CaveColumnData[COLUMN_COUNT];
    private final BitSet scanned = new BitSet(COLUMN_COUNT);
    private final BitSet fullHeight = new BitSet(COLUMN_COUNT);
    private final BitSet pending = new BitSet(COLUMN_COUNT);
    private final BitSet recheck = new BitSet(COLUMN_COUNT);

    /**
     * Columns touched or explicitly invalidated by the live world. Async disk data
     * may fill untouched columns, but it must never resurrect a stale live column.
     */
    private final BitSet liveOwned = new BitSet(COLUMN_COUNT);

    private long revision = 1L;
    /** Changes only when CompactCaveTile-visible content changes. */
    private long archiveRevision = 1L;
    private long publishedArchiveRevision;
    private int lastPublishedScannedCount;
    private long savedRevision;
    private int cursor;
    private LiveAuthorityState liveAuthorityState = LiveAuthorityState.NONE;

    public CaveChunkTile(int chunkX, int chunkZ, boolean newLiveTile) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        if (newLiveTile) pending.set(0, COLUMN_COUNT);
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized long archiveRevision() { return archiveRevision; }

    public synchronized boolean archivePublicationCurrent(long value) {
        return value > 0L && publishedArchiveRevision == value;
    }

    public synchronized void markArchivePublished(long value) {
        if (value > publishedArchiveRevision) {
            publishedArchiveRevision = value;
            lastPublishedScannedCount = scanned.cardinality();
        }
    }

    /**
     * PASS167: publish the projection-visible compact archive only when the chunk is
     * a complete product. The mutable CaveChunkTile already retains 64/128/192-column
     * scan progress, so materialising CompactCaveTile objects at those intermediate
     * counts adds allocation and, worse, mutates page fingerprints while exact Cave
     * products are in flight. Xaero keeps writer progress private and exposes the
     * retained product after a bounded writer pass; follow the same ownership rule.
     */
    public synchronized boolean shouldPublishArchiveMilestone() {
        if (archivePublicationCurrent(archiveRevision)
                || scanned.isEmpty()) return false;
        return scanned.cardinality() == COLUMN_COUNT
                && pending.isEmpty() && recheck.isEmpty();
    }

    public synchronized int archivePublicationMilestone() {
        return scanned.cardinality() == COLUMN_COUNT
                && pending.isEmpty() && recheck.isEmpty()
                ? COLUMN_COUNT : 0;
    }

    public synchronized long savedRevision() {
        return savedRevision;
    }

    public synchronized void markSaved(long value) {
        if (value > savedRevision) savedRevision = value;
    }

    public synchronized boolean isDirtyForSave() {
        return revision > savedRevision && scanned.cardinality() > 0;
    }

    /** All 256 X/Z columns have produced a stable scan result. */
    public synchronized boolean isComplete() {
        return scanned.cardinality() == COLUMN_COUNT && pending.isEmpty();
    }

    /** All columns reached the world minimum without overflowing the run archive. */
    public synchronized boolean hasFullHeightCoverage() {
        return isComplete() && fullHeight.cardinality() == COLUMN_COUNT;
    }

    public synchronized int fullHeightColumnCount() {
        return fullHeight.cardinality();
    }

    /** Returns true when either a missing column or a background recheck remains. */
    public synchronized boolean needsScanWork() {
        return !pending.isEmpty() || !recheck.isEmpty();
    }

    public synchronized LiveAuthorityState liveAuthorityState() {
        return liveAuthorityState;
    }

    /** Claims live authority without invalidating any already scanned column. */
    public synchronized boolean ensureLiveAuthority() {
        LiveAuthorityState previous = liveAuthorityState;
        if (isComplete() && recheck.isEmpty()) {
            liveAuthorityState = LiveAuthorityState.LIVE_COMPLETE;
        } else if (liveAuthorityState == LiveAuthorityState.NONE) {
            liveAuthorityState = LiveAuthorityState.LIVE_PENDING;
        }
        return previous != liveAuthorityState;
    }

    public synchronized void markLiveScanning() {
        if (liveAuthorityState != LiveAuthorityState.LIVE_COMPLETE
                || needsScanWork()) {
            liveAuthorityState = LiveAuthorityState.LIVE_SCANNING;
        }
    }

    public synchronized void markLiveUnavailable() {
        liveAuthorityState = LiveAuthorityState.NONE;
    }

    public synchronized int recheckColumnCount() {
        return recheck.cardinality();
    }

    /** Explicit transaction state used by the page resolver and scheduler. */
    public synchronized TileState state() {
        int scannedCount = scanned.cardinality();
        if (scannedCount == COLUMN_COUNT && pending.isEmpty()) {
            if (!recheck.isEmpty()) return TileState.REVALIDATING;
            return fullHeight.cardinality() == COLUMN_COUNT
                    ? TileState.COMPLETE : TileState.COMPLETE_TRUNCATED;
        }
        if (scannedCount == 0) return pending.isEmpty() ? TileState.EMPTY : TileState.SCANNING;
        return pending.isEmpty() ? TileState.PARTIAL : TileState.SCANNING;
    }

    /** Layered projection can publish once every column has a stable result. */
    public synchronized boolean isLayeredAuthoritative() {
        return isComplete();
    }

    /** Full projection additionally records whether the complete vertical range exists. */
    public synchronized boolean isFullAuthoritative() {
        return hasFullHeightCoverage();
    }

    public synchronized int scannedColumnCount() {
        return scanned.cardinality();
    }

    public synchronized boolean hasAnyScannedColumn() {
        return !scanned.isEmpty();
    }

    public synchronized boolean isColumnScanned(int localX, int localZ) {
        return scanned.get(index(localX, localZ));
    }

    public synchronized CaveColumnData getColumn(int localX, int localZ) {
        int index = index(localX, localZ);
        return scanned.get(index) ? columns[index] : null;
    }

    public synchronized CaveColumnData getColumnByIndex(int index) {
        return scanned.get(index) ? columns[index] : null;
    }

    /**
     * Copies a rectangular set of immutable column references while holding this
     * tile's monitor once. Page projection can therefore read a 66x66 bordered
     * window without taking thousands of per-column locks or allocating snapshots.
     */
    public synchronized void copyColumnsTo(CaveColumnData[] destination,
            int destinationOffset, int destinationStride,
            int sourceX, int sourceZ, int width, int height) {
        if (destination == null || width <= 0 || height <= 0) return;
        int safeSourceX = Math.max(0, Math.min(SIZE, sourceX));
        int safeSourceZ = Math.max(0, Math.min(SIZE, sourceZ));
        int safeWidth = Math.max(0, Math.min(width, SIZE - safeSourceX));
        int safeHeight = Math.max(0, Math.min(height, SIZE - safeSourceZ));
        for (int z = 0; z < safeHeight; z++) {
            int sourceRow = (safeSourceZ + z) * SIZE + safeSourceX;
            int targetRow = destinationOffset + z * destinationStride;
            for (int x = 0; x < safeWidth; x++) {
                int sourceIndex = sourceRow + x;
                destination[targetRow + x] = scanned.get(sourceIndex)
                        ? columns[sourceIndex] : null;
            }
        }
    }

    /**
     * Returns the next missing column first, then a non-destructive revalidation
     * column. The selected bit is not cleared until commitColumn succeeds.
     */
    public synchronized int nextPendingColumn() {
        int index = pending.nextSetBit(cursor);
        if (index < 0 && cursor > 0) index = pending.nextSetBit(0);
        if (index < 0) {
            index = recheck.nextSetBit(cursor);
            if (index < 0 && cursor > 0) index = recheck.nextSetBit(0);
        }
        if (index >= 0) cursor = (index + 1) & 255;
        return index;
    }

    public synchronized boolean commitColumn(int index, CaveColumnData value) {
        CaveColumnData safe = value == null ? CaveColumnData.empty() : value;
        CaveColumnData previous = columns[index];
        boolean wasScanned = scanned.get(index);
        boolean changed = previous == null || !previous.contentEquals(safe) || !wasScanned;
        boolean archiveChanged = previous == null
                || !previous.archiveContentEquals(safe) || !wasScanned;
        columns[index] = safe;
        scanned.set(index);
        pending.clear(index);
        recheck.clear(index);
        if (safe.fullHeightComplete()) fullHeight.set(index);
        else fullHeight.clear(index);
        liveOwned.set(index);
        if (pending.isEmpty() && recheck.isEmpty()
                && scanned.cardinality() == COLUMN_COUNT) {
            liveAuthorityState = LiveAuthorityState.LIVE_COMPLETE;
        }
        if (changed) revision++;
        if (archiveChanged) archiveRevision++;
        return changed;
    }

    /**
     * Requests a replacement scan while retaining the old visible column. This is
     * used for periodic nearby-world revalidation and fluid/block updates.
     */
    public synchronized boolean requestRecheckColumn(int localX, int localZ) {
        int index = index(localX, localZ);
        if (!scanned.get(index)) {
            boolean changed = !pending.get(index);
            pending.set(index);
            liveOwned.set(index);
            liveAuthorityState = LiveAuthorityState.LIVE_PENDING;
            return changed;
        }
        if (recheck.get(index)) return false;
        recheck.set(index);
        liveOwned.set(index);
        liveAuthorityState = LiveAuthorityState.LIVE_PENDING;
        return true;
    }

    public synchronized void invalidateAll() {
        scanned.clear();
        fullHeight.clear();
        pending.set(0, COLUMN_COUNT);
        recheck.clear();
        liveOwned.set(0, COLUMN_COUNT);
        liveAuthorityState = LiveAuthorityState.LIVE_PENDING;
        lastPublishedScannedCount = 0;
        revision++;
        archiveRevision++;
    }

    public synchronized boolean invalidateColumn(int localX, int localZ) {
        int index = index(localX, localZ);
        boolean changed = !pending.get(index) || scanned.get(index);
        scanned.clear(index);
        fullHeight.clear(index);
        pending.set(index);
        recheck.clear(index);
        liveOwned.set(index);
        liveAuthorityState = LiveAuthorityState.LIVE_PENDING;
        if (changed) {
            revision++;
            archiveRevision++;
        }
        return changed;
    }

    /**
     * Merges a tile reconstructed from the Minecraft world save. Unlike the .cvr
     * disk merge, imported columns remain dirty so they are persisted into the
     * compact Simple Map cache. Live-owned columns always win.
     */
    public synchronized boolean mergeWorldSave(Snapshot snapshot) {
        boolean changed = false;
        for (int i = 0; i < COLUMN_COUNT; i++) {
            if (scanned.get(i) || liveOwned.get(i) || !snapshot.scanned().get(i)) continue;
            columns[i] = snapshot.columns()[i];
            scanned.set(i);
            if (snapshot.fullHeight().get(i)) fullHeight.set(i);
            else fullHeight.clear(i);
            pending.clear(i);
            recheck.clear(i);
            changed = true;
        }
        if (changed) {
            revision++;
            archiveRevision++;
        }
        return changed;
    }

    /** Merge an async disk tile without replacing columns already scanned live. */
    public synchronized boolean mergeMissing(Snapshot snapshot) {
        boolean hadLiveChanges = !liveOwned.isEmpty();
        boolean changed = false;
        for (int i = 0; i < COLUMN_COUNT; i++) {
            if (scanned.get(i) || liveOwned.get(i) || !snapshot.scanned().get(i)) continue;
            columns[i] = snapshot.columns()[i];
            scanned.set(i);
            if (snapshot.fullHeight().get(i)) fullHeight.set(i);
            pending.clear(i);
            changed = true;
        }
        revision = Math.max(revision, snapshot.revision());
        savedRevision = Math.max(savedRevision, snapshot.revision());
        if (hadLiveChanges) revision = Math.max(revision, savedRevision + 1L);
        if (changed) archiveRevision++;
        return changed;
    }

    public synchronized Snapshot snapshot() {
        CaveColumnData[] copy = new CaveColumnData[COLUMN_COUNT];
        System.arraycopy(columns, 0, copy, 0, COLUMN_COUNT);
        return new Snapshot(chunkX, chunkZ, revision,
                (BitSet) scanned.clone(), (BitSet) fullHeight.clone(), copy);
    }

    public static CaveChunkTile fromSnapshot(Snapshot snapshot) {
        CaveChunkTile tile = new CaveChunkTile(snapshot.chunkX(), snapshot.chunkZ(), false);
        synchronized (tile) {
            System.arraycopy(snapshot.columns(), 0, tile.columns, 0, COLUMN_COUNT);
            tile.scanned.or(snapshot.scanned());
            tile.fullHeight.or(snapshot.fullHeight());
            tile.pending.set(0, COLUMN_COUNT);
            tile.pending.andNot(tile.scanned);
            tile.revision = Math.max(1L, snapshot.revision());
            tile.archiveRevision = tile.revision;
            tile.publishedArchiveRevision = tile.archiveRevision;
            tile.lastPublishedScannedCount = tile.scanned.cardinality();
            tile.savedRevision = tile.revision;
        }
        return tile;
    }

    public static int index(int localX, int localZ) {
        return (localZ & 15) * SIZE + (localX & 15);
    }

    public record Snapshot(int chunkX, int chunkZ, long revision,
            BitSet scanned, BitSet fullHeight, CaveColumnData[] columns) {
    }
}
