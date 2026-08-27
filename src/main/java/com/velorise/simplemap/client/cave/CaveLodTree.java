package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapDebugRecorder;
import com.velorise.simplemap.client.MapGpuBudgetController;
import com.velorise.simplemap.client.MapPerformanceGovernor;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapResidencyManager;

import com.velorise.simplemap.client.MapPipelineStage;
import com.velorise.simplemap.client.MapPipelineTelemetry;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Page-rooted recursive cave hierarchy.
 *
 * <p>Exact cave pages remain 64x64 block textures. Branch level 1 groups 2x2
 * pages and covers 128x128 blocks, then each higher level doubles the world
 * span while retaining a 64x64 GPU texture. Unknown coverage is never inferred
 * from transparent colour; it is carried separately by the page known-row mask.</p>
 */
final class CaveLodTree {
    /** On-disk compatibility ceiling for older branch snapshots. */
    static final int MAX_LEVEL = 7;
    /** MapRenderer never requests Cave hierarchy above L3. Do not derive invisible L4-L7. */
    static final int RENDER_MAX_LEVEL = 3;
    /**
     * Offscreen branch state is a cache, not a durable job queue. Exact/archive
     * source is authoritative and can restage a branch when it becomes visible.
     * Keeping thousands of nonresident dirty nodes pinned CPU pixels indefinitely
     * was the main cause of 30+ second branch queue latency in PASS100.
     */
    private static final int MAX_OFFSCREEN_DIRTY_QUEUE = 256;
    private static final long FULL_ROW = -1L;
    /** Xaero admits only a couple of branch-cache requests per render pass. */
    private static final int MAX_DISK_LOADS_PER_WINDOW = 2;
    private static final long DISK_LOAD_WINDOW_NANOS = 16_000_000L;
    /**
     * Xaero does not rebuild its parent hierarchy for every individual child write.
     * Keep exact cave leaves progressive, but let an already-established branch
     * absorb a short burst of page revisions before reducing/uploading it again.
     */
    private static final long MINIMAP_BRANCH_QUIET_NANOS = 90_000_000L;
    private static final long MINIMAP_BRANCH_MAX_HOLD_NANOS = 180_000_000L;
    private static final long FULLSCREEN_BRANCH_QUIET_NANOS = 35_000_000L;
    private static final long FULLSCREEN_BRANCH_MAX_HOLD_NANOS = 100_000_000L;
    private static final long BACKGROUND_BRANCH_QUIET_NANOS = 150_000_000L;
    private static final long BACKGROUND_BRANCH_MAX_HOLD_NANOS = 350_000_000L;

    private final CaveBranchAtlas[] atlases = new CaveBranchAtlas[MAX_LEVEL + 1];
    private final long[] observedStorageGeneration = new long[MAX_LEVEL + 1];
    private final Map<NodeKey, Node> nodes = new LinkedHashMap<>(128, 0.75f, true);
    /*
     * Xaero-style retained dirty lanes. Viewport changes must not rebuild/sort a
     * multi-thousand-node queue on the client/render thread. Visible nodes move
     * between two deques in O(n) without allocation; publication always drains
     * foreground first while the durable dirtySet remains the single ownership bit.
     */
    private final ArrayDeque<NodeKey> foregroundDirtyQueue = new ArrayDeque<>();
    private final ArrayDeque<NodeKey> backgroundDirtyQueue = new ArrayDeque<>();
    private final Set<NodeKey> dirtySet = new HashSet<>();
    private String visibleDimension = "";
    private CaveView visibleView;
    private int visibleLayerY = Integer.MIN_VALUE;
    private int visibleMinPageX = 1;
    private int visibleMaxPageX = 0;
    private int visibleMinPageZ = 1;
    private int visibleMaxPageZ = 0;
    private final Set<NodeKey> loadingFromDisk = new HashSet<>();
    private final ConcurrentLinkedQueue<LoadedNode> loadedFromDisk =
            new ConcurrentLinkedQueue<>();
    private final LinkedHashMap<PageUpdateKey, PageUpdate> pendingPageUpdates =
            new LinkedHashMap<>(64, 0.75f, true);
    /**
     * Foreground branch inputs must not wait behind a historical BACKGROUND
     * backlog. One deduplicated key occurrence is enough: coalescing may replace
     * the immutable snapshot in pendingPageUpdates while this queue continues to
     * point at the newest value for that key.
     */
    private final ArrayDeque<PageUpdateKey> foregroundPageOrder =
            new ArrayDeque<>();
    private final Set<PageUpdateKey> foregroundPageKeys = new HashSet<>();
    /**
     * Dirty branch roots waiting for bottom-up reduction. Exact pages are admitted
     * individually, but parent reduction is Xaero-style dirty aggregation: siblings
     * are coalesced first and each parent is reduced once per drain wave instead of
     * recursively once for every arriving page.
     */
    private final LinkedHashSet<NodeKey> foregroundPropagation = new LinkedHashSet<>();
    private final LinkedHashSet<NodeKey> backgroundPropagation = new LinkedHashSet<>();
    /** Density-correct fullscreen branch level gets first publication opportunity. */
    private int preferredVisibleLevel = 1;
    private String minimapDimension = "";
    private CaveView minimapView;
    private int minimapLayerY = Integer.MIN_VALUE;
    private int preferredMinimapLevel = 1;
    private final MapPipelineTelemetry pipelineTelemetry = MapPipelineTelemetry.getInstance();
    private final LodBranchDiskCache diskCache = LodBranchDiskCache.getInstance();
    private long loadGeneration = 1L;
    private long diskLoadWindowStartedNanos;
    private int diskLoadsInWindow;
    /** Cave-only render revision; surface/background atlas traffic must not rebuild cave plans. */
    private final AtomicLong contentRevision = new AtomicLong();
    /** True when the most recent publish slice hit the shared BRANCH GPU ledger. */
    private boolean lastPublishGpuDenied;
    /*
     * Render traversal must be cache-only. The mutable LOD tree can drain disk
     * results, derive parents, reorder its access-order map and enqueue uploads;
     * doing any of that from MapRenderer caused 0.3-1.0 second zoom stalls.
     * These concurrent snapshots expose only the last GPU-published branch and
     * lightweight CPU-known metadata. Misses are handed back to the owner thread
     * through deduplicated queues instead of mutating the tree on the render path.
     */
    private final ConcurrentHashMap<NodeKey, CaveAtlasRegion> publishedRegions =
            new ConcurrentHashMap<>();
    private final Set<NodeKey> knownRenderNodes = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<NodeKey> renderRequests =
            new ConcurrentLinkedQueue<>();
    private final Set<NodeKey> queuedRenderRequests = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<NodeKey> renderTouches =
            new ConcurrentLinkedQueue<>();
    private final Set<NodeKey> queuedRenderTouches = ConcurrentHashMap.newKeySet();

    long contentRevision() {
        return contentRevision.get();
    }

    /** Lock-free, side-effect-free branch lookup used by render-plan building. */
    CaveAtlasRegion peekPublished(String dimension, CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
        if (level < 1 || level > MAX_LEVEL) return null;
        NodeKey key = new NodeKey(dimension, view, layerY, level, nodeX, nodeZ);
        CaveAtlasRegion region = publishedRegions.get(key);
        if (region != null) {
            queueRenderTouch(key);
            return region;
        }
        queueRenderRequest(key);
        return null;
    }

    /**
     * CPU-known branch metadata snapshot. This intentionally does not consult the
     * disk cache or drain completed disk reads on the render thread.
     */
    boolean hasDataSnapshot(String dimension, CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
        if (level < 1 || level > MAX_LEVEL) return false;
        NodeKey key = new NodeKey(dimension, view, layerY, level, nodeX, nodeZ);
        boolean known = knownRenderNodes.contains(key) || publishedRegions.containsKey(key);
        if (!known) queueRenderRequest(key);
        return known;
    }

    private void queueRenderRequest(NodeKey key) {
        if (key != null && queuedRenderRequests.add(key)) renderRequests.offer(key);
    }

    private void queueRenderTouch(NodeKey key) {
        if (key != null && queuedRenderTouches.add(key)) renderTouches.offer(key);
    }

    /** Owner-thread bridge for render cache misses and residency touches. */
    private void drainRenderSignals(int budget) {
        int remaining = Math.max(8, budget);
        while (remaining-- > 0) {
            NodeKey key = renderRequests.poll();
            if (key == null) break;
            queuedRenderRequests.remove(key);
            Node node = nodes.get(key);
            if (node == null) {
                requestDiskLoad(key);
                continue;
            }
            if (node.knownMask == 0L) continue;
            knownRenderNodes.add(key);
            if (node.initialized && node.atlasSlot >= 0
                    && node.uploadedKnownMask != 0L) {
                CaveAtlasRegion region = atlases[key.level()].region(node.atlasSlot,
                        node.uploadedKnownMask, node.uploadedCompleteMask);
                if (region != null) publishedRegions.put(key, region);
                if (node.isDirty()) {
                    enqueue(key);
                    enqueuePropagation(key);
                }
            } else {
                node.markFullDirty();
                enqueue(key);
                enqueuePropagation(key);
            }
        }
        remaining = Math.max(8, budget);
        while (remaining-- > 0) {
            NodeKey key = renderTouches.poll();
            if (key == null) break;
            queuedRenderTouches.remove(key);
            if (publishedRegions.containsKey(key)) {
                MapResidencyManager.getInstance().touch(residencyKey(key));
            }
        }
    }

    CaveLodTree() {
        java.util.Arrays.fill(observedStorageGeneration, Long.MIN_VALUE);
        for (int level = 1; level <= MAX_LEVEL; level++) {
            atlases[level] = new CaveBranchAtlas(level);
        }
    }

    void onPageTableFrameBoundary() {
        for (int level = 1; level <= MAX_LEVEL; level++) {
            atlases[level].onPageTableFrameBoundary();
        }
    }

    void synchronizeStorage() {
        drainDiskLoads();
        boolean reallocated = false;
        for (int level = 1; level <= MAX_LEVEL; level++) {
            CaveBranchAtlas atlas = atlases[level];
            // Detail atlases are deliberately larger. Allocate only levels that
            // have actually received a node/upload instead of reserving L1-L7 as
            // soon as Cave mode opens.
            if (!atlas.initialized()) continue;
            long generation = atlas.storageGeneration();
            if (observedStorageGeneration[level] != Long.MIN_VALUE
                    && observedStorageGeneration[level] != generation) reallocated = true;
            observedStorageGeneration[level] = generation;
        }
        if (!reallocated) return;
        MapResidencyManager.getInstance().markTopologyChanged();
        contentRevision.incrementAndGet();
        publishedRegions.clear();
        for (Node node : nodes.values()) {
            node.initialized = false;
            node.markFullDirty();
            enqueue(node.key);
        }
    }

    CaveAtlasRegion peek(String dimension, CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
        drainDiskLoads();
        if (level < 1 || level > MAX_LEVEL) return null;
        NodeKey key = new NodeKey(dimension, view, layerY, level, nodeX, nodeZ);
        Node node = nodes.get(key);
        if (node == null) {
            requestDiskLoad(key);
            return null;
        }
        if (node.initialized && node.atlasSlot >= 0
                && node.uploadedKnownMask != 0L) {
            MapResidencyManager.getInstance().touch(residencyKey(key));
            return atlases[level].region(node.atlasSlot,
                    node.uploadedKnownMask, node.uploadedCompleteMask);
        }
        if (node.knownMask == 0L) return null;
        node.markFullDirty();
        enqueue(node.key);
        return null;
    }

    boolean hasData(String dimension, CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
        drainDiskLoads();
        if (level < 1 || level > MAX_LEVEL) return false;
        NodeKey key = new NodeKey(dimension, view, layerY, level, nodeX, nodeZ);
        Node node = nodes.get(key);
        if (node == null) requestDiskLoad(key);
        if (node != null && node.knownMask != 0L) return true;
        if (view == CaveView.LAYERED) return false;
        LodBranchDiskCache.Metadata metadata = diskCache.metadata(diskKey(key));
        return metadata != null && metadata.knownMask() != 0L;
    }

    /**
     * Releases GPU/publication ownership for one layer while retaining its bounded
     * CPU branch pixels and child revisions. Xaero keeps written map regions across
     * cave-layer switches and reloads presentation from that retained state.
     */
    int parkLayer(String dimension, CaveView view, int layerY) {
        int parked = 0;
        for (Map.Entry<NodeKey, Node> entry : new java.util.ArrayList<>(nodes.entrySet())) {
            NodeKey key = entry.getKey();
            if (!key.dimension().equals(dimension)
                    || key.view() != view || key.layerY() != layerY) continue;
            Node node = entry.getValue();
            if (node.atlasSlot >= 0 || node.initialized) parked++;
            node.close();
            dirtySet.remove(key);
        }
        foregroundDirtyQueue.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        backgroundDirtyQueue.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        publishedRegions.keySet().removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        knownRenderNodes.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        queuedRenderRequests.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        renderRequests.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        queuedRenderTouches.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        renderTouches.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        loadingFromDisk.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        loadedFromDisk.removeIf(loaded -> loaded.key.dimension().equals(dimension)
                && loaded.key.view() == view && loaded.key.layerY() == layerY);
        synchronized (pendingPageUpdates) {
            pendingPageUpdates.entrySet().removeIf(entry -> {
                PageUpdateKey key = entry.getKey();
                return key.dimension().equals(dimension)
                        && key.view() == view && key.layerY() == layerY;
            });
            foregroundPageOrder.removeIf(key -> key.dimension().equals(dimension)
                    && key.view() == view && key.layerY() == layerY);
            foregroundPageKeys.removeIf(key -> key.dimension().equals(dimension)
                    && key.view() == view && key.layerY() == layerY);
        }
        foregroundPropagation.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        backgroundPropagation.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        if (parked > 0) contentRevision.incrementAndGet();
        return parked;
    }

    /**
     * Parks branch GPU state belonging to inactive FULL/LAYERED views without
     * deleting the CPU hierarchy. Bounded per-level LRU trimming remains the memory
     * limit, so returning to a view restores branches instead of re-deriving them.
     */
    int parkInactiveViews(String dimension, CaveView activeView) {
        int parked = 0;
        for (Map.Entry<NodeKey, Node> entry : new java.util.ArrayList<>(nodes.entrySet())) {
            NodeKey key = entry.getKey();
            if (!inactiveView(key.dimension(), key.view(), dimension, activeView)) continue;
            Node node = entry.getValue();
            if (node.atlasSlot >= 0 || node.initialized) parked++;
            node.close();
            dirtySet.remove(key);
        }
        foregroundDirtyQueue.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        backgroundDirtyQueue.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        publishedRegions.keySet().removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        knownRenderNodes.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        queuedRenderRequests.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        renderRequests.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        queuedRenderTouches.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        renderTouches.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        loadingFromDisk.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        loadedFromDisk.removeIf(loaded -> inactiveView(
                loaded.key.dimension(), loaded.key.view(), dimension, activeView));
        synchronized (pendingPageUpdates) {
            pendingPageUpdates.entrySet().removeIf(entry -> {
                PageUpdateKey key = entry.getKey();
                return inactiveView(key.dimension(), key.view(), dimension, activeView);
            });
            foregroundPageOrder.removeIf(key -> inactiveView(
                    key.dimension(), key.view(), dimension, activeView));
            foregroundPageKeys.removeIf(key -> inactiveView(
                    key.dimension(), key.view(), dimension, activeView));
        }
        foregroundPropagation.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        backgroundPropagation.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        if (parked > 0) contentRevision.incrementAndGet();
        return parked;
    }

    /**
     * Drops one in-memory Layered branch hierarchy when exact Top-Y changes inside
     * its retained 16-block band. The branch disk format is band-keyed and cannot
     * prove which exact Top-Y produced a snapshot, so Layered branches are rebuilt
     * from current exact pages instead of reloading a semantically stale underlay.
     */
    void invalidateLayer(String dimension, CaveView view, int layerY) {
        boolean changed = false;
        Iterator<Map.Entry<NodeKey, Node>> iterator = nodes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<NodeKey, Node> entry = iterator.next();
            NodeKey key = entry.getKey();
            if (!key.dimension().equals(dimension)
                    || key.view() != view || key.layerY() != layerY) continue;
            entry.getValue().close();
            iterator.remove();
            dirtySet.remove(key);
            changed = true;
        }
        foregroundDirtyQueue.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        backgroundDirtyQueue.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        publishedRegions.keySet().removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        knownRenderNodes.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        queuedRenderRequests.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        renderRequests.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        queuedRenderTouches.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        renderTouches.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        loadingFromDisk.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        loadedFromDisk.removeIf(loaded -> loaded.key.dimension().equals(dimension)
                && loaded.key.view() == view && loaded.key.layerY() == layerY);
        synchronized (pendingPageUpdates) {
            pendingPageUpdates.entrySet().removeIf(entry -> {
                PageUpdateKey key = entry.getKey();
                return key.dimension().equals(dimension)
                        && key.view() == view && key.layerY() == layerY;
            });
            foregroundPageOrder.removeIf(key ->
                    key.dimension().equals(dimension)
                            && key.view() == view && key.layerY() == layerY);
            foregroundPageKeys.removeIf(key ->
                    key.dimension().equals(dimension)
                            && key.view() == view && key.layerY() == layerY);
        }
        foregroundPropagation.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        backgroundPropagation.removeIf(key -> key.dimension().equals(dimension)
                && key.view() == view && key.layerY() == layerY);
        if (changed) contentRevision.incrementAndGet();
    }

    /**
     * Releases GPU/queue state for cave views that are not currently presented.
     * Derived exact/projection CPU products live outside this tree and remain
     * retained. This mirrors Xaero's loaded-vs-written distinction: inactive map
     * data is reusable, but it must not occupy the active branch atlas.
     *
     * @return number of resident/in-memory branch nodes retired
     */
    int invalidateInactiveViews(String dimension, CaveView activeView) {
        if (dimension == null || dimension.isBlank()) return 0;
        int retired = 0;
        Iterator<Map.Entry<NodeKey, Node>> iterator = nodes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<NodeKey, Node> entry = iterator.next();
            NodeKey key = entry.getKey();
            if (!inactiveView(key.dimension(), key.view(), dimension, activeView)) {
                continue;
            }
            entry.getValue().close();
            iterator.remove();
            dirtySet.remove(key);
            retired++;
        }
        foregroundDirtyQueue.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        backgroundDirtyQueue.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        publishedRegions.keySet().removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        knownRenderNodes.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        queuedRenderRequests.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        renderRequests.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        queuedRenderTouches.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        renderTouches.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        loadingFromDisk.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        loadedFromDisk.removeIf(loaded -> inactiveView(
                loaded.key.dimension(), loaded.key.view(), dimension, activeView));
        synchronized (pendingPageUpdates) {
            pendingPageUpdates.entrySet().removeIf(entry -> {
                PageUpdateKey key = entry.getKey();
                return inactiveView(key.dimension(), key.view(),
                        dimension, activeView);
            });
            foregroundPageOrder.removeIf(key -> inactiveView(
                    key.dimension(), key.view(), dimension, activeView));
            foregroundPageKeys.removeIf(key -> inactiveView(
                    key.dimension(), key.view(), dimension, activeView));
        }
        foregroundPropagation.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        backgroundPropagation.removeIf(key -> inactiveView(
                key.dimension(), key.view(), dimension, activeView));
        if (retired > 0) contentRevision.incrementAndGet();
        return retired;
    }

    private static boolean inactiveView(String candidateDimension,
            CaveView candidateView, String dimension, CaveView activeView) {
        return dimension.equals(candidateDimension)
                && (activeView == null || candidateView != activeView);
    }

    /**
     * Exact-to-branch publication fence. An exact cave page is safe to retire only
     * after its level-1 quadrant is resident on the GPU and was derived from at
     * least the exact page revision being retired.
     */
    boolean coversPage(String dimension, CaveView view, int layerY,
            int globalPageX, int globalPageZ, long sourceRevision) {
        drainDiskLoads();
        int nodeX = Math.floorDiv(globalPageX, 2);
        int nodeZ = Math.floorDiv(globalPageZ, 2);
        NodeKey key = new NodeKey(dimension, view, layerY, 1, nodeX, nodeZ);
        Node node = nodes.get(key);
        if (node == null || !node.initialized || node.atlasSlot < 0) return false;
        int childIndex = Math.floorMod(globalPageZ, 2) * 2
                + Math.floorMod(globalPageX, 2);
        boolean covered = (node.uploadedCompleteMask & (1L << childIndex)) != 0L
                && node.uploadedChildRevisions[childIndex]
                        == canonicalContentRevision(sourceRevision);
        if (covered) MapResidencyManager.getInstance().touch(residencyKey(key));
        return covered;
    }

    /**
     * Returns true when any resident parent texture contains a complete quadrant
     * covering this exact page. Xaero renders the nearest loaded root/parent
     * sub-rectangle whenever a child texture is missing; this is the equivalent
     * publication fence used before opening later exact coordinates.
     */
    boolean hasPublishedCoverage(String dimension, CaveView view, int layerY,
            int globalPageX, int globalPageZ) {
        drainDiskLoads();
        for (int level = 1; level <= MAX_LEVEL; level++) {
            int span = 1 << level;
            int nodeX = Math.floorDiv(globalPageX, span);
            int nodeZ = Math.floorDiv(globalPageZ, span);
            NodeKey key = new NodeKey(dimension, view, layerY,
                    level, nodeX, nodeZ);
            Node node = nodes.get(key);
            if (node == null || !node.initialized || node.atlasSlot < 0) continue;
            int childSpan = span >> 1;
            int localPageX = Math.floorMod(globalPageX, span);
            int localPageZ = Math.floorMod(globalPageZ, span);
            int childX = localPageX >= childSpan ? 1 : 0;
            int childZ = localPageZ >= childSpan ? 1 : 0;
            int childIndex = childZ * 2 + childX;
            if ((node.uploadedCompleteMask & (1L << childIndex)) != 0L) {
                MapResidencyManager.getInstance().touch(residencyKey(key));
                return true;
            }
        }
        return false;
    }

    void updatePage(String dimension, CaveView view, int layerY,
            int globalPageX, int globalPageZ, int[] pagePixels64,
            long[] knownRows, int knownColumns, boolean complete) {
        updatePage(dimension, view, layerY, globalPageX, globalPageZ,
                pagePixels64, knownRows, knownColumns, complete,
                System.nanoTime(), MapRequestLane.BACKGROUND);
    }

    void updatePage(String dimension, CaveView view, int layerY,
            int globalPageX, int globalPageZ, int[] pagePixels64,
            long[] knownRows, int knownColumns, boolean complete,
            long sourceRevision, MapRequestLane lane) {
        if (pagePixels64 == null || pagePixels64.length < 64 * 64
                || knownRows == null || knownRows.length < 64 || knownColumns <= 0) return;
        PageUpdateKey key = new PageUpdateKey(dimension, view, layerY,
                globalPageX, globalPageZ);
        /*
         * Source revisions are signed 64-bit content fingerprints, not monotonic
         * counters. Math.max(1, revision) collapsed every negative fingerprint to
         * the same value (1), so about half of real cave products could be mistaken
         * for an already queued/published branch after a Top-Y or FULL transition.
         * Preserve all non-zero bits; only zero is reserved for "no revision".
         */
        long effectiveRevision = canonicalContentRevision(sourceRevision);
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.BACKGROUND : lane;
        /*
         * Xaero keeps offscreen region source/cache data retained but does not turn
         * every historical leaf change into an eager hierarchy job. If no live
         * minimap/fullscreen viewport owns this page, the exact/archive source is
         * enough; a later visible projection will restage the newest page. Dropping
         * here also happens before the 64x64 defensive copies.
         */
        if (!isForegroundLane(effectiveLane) && !pageInVisibleViewport(key)) {
            pipelineTelemetry.recordBranchUpdateDropped();
            return;
        }
        long queuedNanos = System.nanoTime();
        boolean queued = false;
        boolean duplicateDropped = false;
        boolean coalesced = false;
        synchronized (pendingPageUpdates) {
            PageUpdate previous = pendingPageUpdates.get(key);
            PageUpdate retained = previous;
            if (previous != null && previous.sourceRevision() == effectiveRevision
                    && previous.knownColumns() >= knownColumns
                    && (previous.complete() || !complete)) {
                if (effectiveLane.strongerThan(previous.lane())) {
                    // Priority-only promotion: reuse the immutable queued snapshot.
                    // Do not allocate another 64x64 pixel + row-mask copy, and do not
                    // restart the quiet window because the content did not change.
                    retained = new PageUpdate(key,
                            previous.pagePixels64(), previous.knownRows(),
                            previous.knownColumns(), previous.complete(),
                            previous.sourceRevision(), effectiveLane,
                            previous.firstQueuedNanos(), previous.lastQueuedNanos(),
                            previous.immediate());
                    pendingPageUpdates.put(key, retained);
                } else {
                    duplicateDropped = true;
                }
            } else {
                boolean established = branchAlreadyHasPage(key);
                long firstQueuedNanos = previous == null
                        ? queuedNanos : previous.firstQueuedNanos();
                boolean immediate = previous != null
                        ? previous.immediate() : !established;
                // Copy only after semantic coalescing. A newer revision replaces the
                // queued immutable snapshot in-place; the queue owns one key, not one
                // job per child publication. Cold branch seeds remain immediately
                // eligible without falsifying queue-latency telemetry.
                retained = new PageUpdate(key,
                        java.util.Arrays.copyOf(pagePixels64, 64 * 64),
                        java.util.Arrays.copyOf(knownRows, 64), knownColumns, complete,
                        effectiveRevision, effectiveLane,
                        firstQueuedNanos, queuedNanos, immediate);
                pendingPageUpdates.put(key, retained);
                queued = previous == null;
                coalesced = previous != null;
            }
            if (retained != null && isForegroundLane(retained.lane())) {
                enqueueForegroundPageLocked(retained.key());
            }
        }
        if (queued) pipelineTelemetry.recordBranchUpdateQueued();
        else if (duplicateDropped) pipelineTelemetry.recordBranchUpdateDropped();
        if (coalesced) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent("CAVE_BRANCH_PAGE_COALESCED", 250L)) {
                recorder.event("CAVE_BRANCH_PAGE_COALESCED",
                        "page=" + globalPageX + ',' + globalPageZ
                                + " view=" + view + " layer=" + layerY
                                + " lane=" + effectiveLane);
            }
        }
    }

    private static long canonicalContentRevision(long revision) {
        return revision == 0L ? 1L : revision;
    }

    /**
     * Moves current fullscreen branch inputs ahead of valid offscreen cache work.
     * Source updates are never discarded; pan only changes the bounded derive order.
     * This is the branch equivalent of Xaero selecting a small visible candidate
     * slice instead of restarting every region transaction.
     */
    /** Records only the hierarchy depth actually consumed by the minimap.
     * L1 remains MINIMAP foreground at normal zoom; higher ancestors stay
     * reconstructable background unless a zoom level really renders them. */
    void prioritizeMinimap(String dimension, CaveView view, int layerY,
            int preferredLevel) {
        minimapDimension = dimension == null ? "" : dimension;
        minimapView = view;
        minimapLayerY = layerY;
        preferredMinimapLevel = Math.max(1,
                Math.min(RENDER_MAX_LEVEL, preferredLevel));
    }

    void prioritizeViewport(String dimension, CaveView view, int layerY,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ,
            int preferredLevel) {
        preferredVisibleLevel = Math.max(1,
                Math.min(RENDER_MAX_LEVEL, preferredLevel));
        visibleDimension = dimension == null ? "" : dimension;
        visibleView = view;
        visibleLayerY = layerY;
        visibleMinPageX = minPageX;
        visibleMaxPageX = maxPageX;
        visibleMinPageZ = minPageZ;
        visibleMaxPageZ = maxPageZ;

        int visibleCount = 0;
        int deferredCount = 0;
        /*
         * pendingPageUpdates already has foregroundPageOrder. Merely promote the
         * immutable value/lane and enqueue its key there. PASS98 copied every value
         * into two ArrayLists and sorted them on each recenter even though the
         * foreground deque subsequently provided the real priority.
         */
        synchronized (pendingPageUpdates) {
            Iterator<Map.Entry<PageUpdateKey, PageUpdate>> pendingIterator =
                    pendingPageUpdates.entrySet().iterator();
            while (pendingIterator.hasNext()) {
                Map.Entry<PageUpdateKey, PageUpdate> entry = pendingIterator.next();
                PageUpdate update = entry.getValue();
                PageUpdateKey key = update.key();
                boolean current = pageInVisibleViewport(key);
                if (current) {
                    visibleCount++;
                    /*
                     * PASS158: visibility outranks the static lane rank. MINIMAP is
                     * intentionally strong during gameplay, but once MapScreen owns
                     * the screen its queued branch snapshot is merely shared cache
                     * input. Claim the current fullscreen rectangle explicitly so a
                     * hidden minimap backlog cannot sit ahead of visible branches.
                     */
                    if (update.lane() != MapRequestLane.FULLSCREEN) {
                        update = new PageUpdate(update.key(),
                                update.pagePixels64(), update.knownRows(),
                                update.knownColumns(), update.complete(),
                                update.sourceRevision(), MapRequestLane.FULLSCREEN,
                                update.firstQueuedNanos(), update.lastQueuedNanos(),
                                update.immediate());
                        entry.setValue(update);
                    }
                    enqueueForegroundPageLocked(key);
                    continue;
                }
                /*
                 * Offscreen FULLSCREEN ownership expires immediately on rebase. More
                 * importantly, do not keep the demoted immutable 64x64 snapshot as a
                 * historical job. Source/archive state is retained elsewhere and the
                 * visible planner will restage its newest snapshot if the camera
                 * returns. This is the lazy region hierarchy used by Xaero rather
                 * than an ever-growing global dirty transaction queue.
                 */
                if (update.lane() == MapRequestLane.FULLSCREEN
                        || update.lane() == MapRequestLane.MINIMAP) {
                    update = new PageUpdate(update.key(),
                            update.pagePixels64(), update.knownRows(),
                            update.knownColumns(), update.complete(),
                            update.sourceRevision(), MapRequestLane.BACKGROUND,
                            update.firstQueuedNanos(), update.lastQueuedNanos(),
                            update.immediate());
                }
                if (!isForegroundLane(update.lane())) {
                    pendingIterator.remove();
                    foregroundPageKeys.remove(key);
                    deferredCount++;
                    continue;
                }
                entry.setValue(update);
                deferredCount++;
            }
        }

        int visibleDirty = 0;
        int deferredDirty = 0;
        /* Move only ownership references; no sort, no queue rebuild, no arrays. */
        Iterator<NodeKey> foregroundIterator = foregroundDirtyQueue.iterator();
        while (foregroundIterator.hasNext()) {
            NodeKey key = foregroundIterator.next();
            Node node = nodes.get(key);
            if (nodeInVisibleViewport(key)) {
                visibleDirty++;
                if (node != null) node.requestLane = MapRequestLane.FULLSCREEN;
                continue;
            }
            foregroundIterator.remove();
            if (node != null && (node.requestLane == MapRequestLane.FULLSCREEN
                    || node.requestLane == MapRequestLane.MINIMAP)) {
                node.requestLane = MapRequestLane.BACKGROUND;
            }
            backgroundDirtyQueue.addLast(key);
            deferredDirty++;
        }
        Iterator<NodeKey> backgroundIterator = backgroundDirtyQueue.iterator();
        while (backgroundIterator.hasNext()) {
            NodeKey key = backgroundIterator.next();
            Node node = nodes.get(key);
            if (!nodeInVisibleViewport(key)) {
                if (node != null && (node.requestLane == MapRequestLane.FULLSCREEN
                        || node.requestLane == MapRequestLane.MINIMAP)) {
                    node.requestLane = MapRequestLane.BACKGROUND;
                }
                deferredDirty++;
                continue;
            }
            backgroundIterator.remove();
            if (node != null) node.requestLane = MapRequestLane.FULLSCREEN;
            if (key.level() == preferredVisibleLevel) {
                foregroundDirtyQueue.addFirst(key);
            } else {
                foregroundDirtyQueue.addLast(key);
            }
            visibleDirty++;
        }

        /*
         * A nonresident offscreen dirty branch has no visible GPU value and its exact
         * source is durable. Bound this cache queue instead of allowing dirty state to
         * pin tens of MiB of 64x64 CPU nodes and seconds/minutes of queue age. Keep
         * resident nodes intact so last-good coverage is never destroyed.
         */
        int prune = Math.max(0, backgroundDirtyQueue.size()
                - MAX_OFFSCREEN_DIRTY_QUEUE);
        if (prune > 0) {
            Iterator<NodeKey> pruneIterator = backgroundDirtyQueue.iterator();
            while (prune > 0 && pruneIterator.hasNext()) {
                NodeKey key = pruneIterator.next();
                Node node = nodes.get(key);
                if (node == null) {
                    pruneIterator.remove();
                    dirtySet.remove(key);
                    prune--;
                    continue;
                }
                if (nodeInVisibleViewport(key) || node.atlasSlot >= 0) continue;
                pruneIterator.remove();
                dirtySet.remove(key);
                foregroundPropagation.remove(key);
                backgroundPropagation.remove(key);
                knownRenderNodes.remove(key);
                publishedRegions.remove(key);
                nodes.remove(key);
                prune--;
            }
        }

        /* Retained propagation follows the same foreground/background ownership.
         * This prevents a historical offscreen L1 wave from delaying the L2/L3
         * parent that the current fullscreen is actually rendering. */
        Iterator<NodeKey> foregroundPropagationIterator =
                foregroundPropagation.iterator();
        while (foregroundPropagationIterator.hasNext()) {
            NodeKey key = foregroundPropagationIterator.next();
            Node node = nodes.get(key);
            boolean keepForeground = node != null
                    && nodeInVisibleViewport(key);
            if (keepForeground) node.requestLane = MapRequestLane.FULLSCREEN;
            if (keepForeground) continue;
            foregroundPropagationIterator.remove();
            backgroundPropagation.add(key);
        }
        Iterator<NodeKey> backgroundPropagationIterator =
                backgroundPropagation.iterator();
        while (backgroundPropagationIterator.hasNext()) {
            NodeKey key = backgroundPropagationIterator.next();
            if (!nodeInVisibleViewport(key)) continue;
            backgroundPropagationIterator.remove();
            foregroundPropagation.add(key);
        }

        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (recorder.shouldEmitEvent("CAVE_BRANCH_VIEWPORT_REBASED", 100L)) {
            recorder.event("CAVE_BRANCH_VIEWPORT_REBASED",
                    "visible_updates=" + visibleCount
                            + " deferred_updates=" + deferredCount
                            + " visible_dirty=" + visibleDirty
                            + " deferred_dirty=" + deferredDirty);
        }
    }

    private boolean pageInVisibleViewport(PageUpdateKey key) {
        return key != null
                && key.dimension().equals(visibleDimension)
                && key.view() == visibleView && key.layerY() == visibleLayerY
                && key.globalPageX() >= visibleMinPageX
                && key.globalPageX() <= visibleMaxPageX
                && key.globalPageZ() >= visibleMinPageZ
                && key.globalPageZ() <= visibleMaxPageZ;
    }

    private boolean nodeInVisibleViewport(NodeKey key) {
        if (key == null || visibleView == null
                || !key.dimension().equals(visibleDimension)
                || key.view() != visibleView || key.layerY() != visibleLayerY) {
            return false;
        }
        int spanPages = 1 << key.level();
        int nodeMinPageX = key.nodeX() * spanPages;
        int nodeMaxPageX = nodeMinPageX + spanPages - 1;
        int nodeMinPageZ = key.nodeZ() * spanPages;
        int nodeMaxPageZ = nodeMinPageZ + spanPages - 1;
        return nodeMinPageX <= visibleMaxPageX && nodeMaxPageX >= visibleMinPageX
                && nodeMinPageZ <= visibleMaxPageZ && nodeMaxPageZ >= visibleMinPageZ;
    }

    private void promoteNodeLane(NodeKey key) {
        Node node = nodes.get(key);
        if (node != null
                && MapRequestLane.FULLSCREEN.strongerThan(node.requestLane)) {
            node.requestLane = MapRequestLane.FULLSCREEN;
        }
    }

    private void enqueuePropagation(NodeKey key) {
        if (key == null || foregroundPropagation.contains(key)
                || backgroundPropagation.contains(key)) return;
        Node node = nodes.get(key);
        boolean foreground = node != null
                && (node.requestLane == MapRequestLane.MINIMAP
                        || (isForegroundLane(node.requestLane)
                                && nodeInVisibleViewport(key)));
        (foreground ? foregroundPropagation : backgroundPropagation).add(key);
    }

    private boolean removePropagation(NodeKey key) {
        return foregroundPropagation.remove(key)
                || backgroundPropagation.remove(key);
    }

    private NodeKey pollPropagation() {
        LinkedHashSet<NodeKey> source = !foregroundPropagation.isEmpty()
                ? foregroundPropagation : backgroundPropagation;
        if (source.isEmpty()) return null;
        Iterator<NodeKey> iterator = source.iterator();
        NodeKey key = iterator.next();
        iterator.remove();
        return key;
    }

    private boolean hasPendingPropagation() {
        return !foregroundPropagation.isEmpty() || !backgroundPropagation.isEmpty();
    }

    private static double pageDistanceSquared(int pageX, int pageZ,
            double centerPageX, double centerPageZ) {
        double dx = pageX + 0.5D - centerPageX;
        double dz = pageZ + 0.5D - centerPageZ;
        return dx * dx + dz * dz;
    }

    private static double nodeDistanceSquared(NodeKey key,
            double centerPageX, double centerPageZ) {
        int spanPages = 1 << key.level();
        double nodeCenterX = key.nodeX() * (double) spanPages + spanPages * 0.5D;
        double nodeCenterZ = key.nodeZ() * (double) spanPages + spanPages * 0.5D;
        double dx = nodeCenterX - centerPageX;
        double dz = nodeCenterZ - centerPageZ;
        return dx * dx + dz * dz;
    }

    private void applyPageUpdate(PageUpdate update) {
        drainDiskLoads();
        PageUpdateKey key = update.key();
        int[] pagePixels64 = update.pagePixels64();
        long[] knownRows = update.knownRows();
        int globalPageX = key.globalPageX();
        int globalPageZ = key.globalPageZ();

        int nodeX = Math.floorDiv(globalPageX, 2);
        int nodeZ = Math.floorDiv(globalPageZ, 2);
        Node node = node(key.dimension(), key.view(), key.layerY(), 1, nodeX, nodeZ);
        if (update.lane() != null && update.lane().strongerThan(node.requestLane)) {
            node.requestLane = update.lane();
        }
        int localPageX = Math.floorMod(globalPageX, 2);
        int localPageZ = Math.floorMod(globalPageZ, 2);
        int destinationBaseX = localPageX * 32;
        int destinationBaseY = localPageZ * 32;
        int changedMinX = 64;
        int changedMinY = 64;
        int changedMaxX = -1;
        int changedMaxY = -1;

        long previousKnownMask = node.knownMask;
        long previousCompleteMask = node.completeMask;
        for (int y = 0; y < 32; y++) {
            int sourceY = y << 1;
            int targetRow = (destinationBaseY + y) * 64;
            for (int x = 0; x < 32; x++) {
                int sourceX = x << 1;
                int known = knownCount(knownRows, sourceX, sourceY);
                int targetX = destinationBaseX + x;
                int targetY = destinationBaseY + y;
                int targetIndex = targetRow + targetX;
                boolean nowKnown = known > 0;
                int reduced = nowKnown
                        ? reduceCave(pagePixels64, knownRows, sourceX, sourceY) : 0;
                boolean wasKnown = (node.knownRows[targetY] & (1L << targetX)) != 0L;
                boolean wasComplete = (node.completeRows[targetY] & (1L << targetX)) != 0L;
                boolean nowComplete = nowKnown && known == 4;
                if (wasKnown == nowKnown && node.pixels[targetIndex] == reduced
                        && wasComplete == nowComplete) continue;
                node.pixels[targetIndex] = reduced;
                setBit(node.knownRows, targetX, targetY, nowKnown);
                setBit(node.completeRows, targetX, targetY, nowComplete);
                changedMinX = Math.min(changedMinX, targetX);
                changedMinY = Math.min(changedMinY, targetY);
                changedMaxX = Math.max(changedMaxX, targetX);
                changedMaxY = Math.max(changedMaxY, targetY);
            }
        }

        int childIndex = localPageZ * 2 + localPageX;
        // Source revisions are content fingerprints, not monotonic counters.
        // The child slot represents exactly the newest admitted page transaction.
        node.childRevisions[childIndex] = update.sourceRevision();
        refreshQuadrantCoverage(node, localPageX, localPageZ);
        boolean coverageChanged = previousKnownMask != node.knownMask
                || previousCompleteMask != node.completeMask;
        if (changedMaxX < changedMinX && !coverageChanged) return;
        if (changedMaxX < changedMinX) {
            changedMinX = destinationBaseX;
            changedMinY = destinationBaseY;
            changedMaxX = destinationBaseX + 31;
            changedMaxY = destinationBaseY + 31;
        }
        node.revision++;
        node.markDirty(changedMinX, changedMinY, changedMaxX, changedMaxY);
        enqueue(node.key);
        // Do not recursively reduce L1->L2->L3 for every exact page. Queue the
        // changed root; drainPendingPropagation() coalesces sibling updates first.
        enqueuePropagation(node.key);
        trimLevel(1);
    }

    private void enqueueForegroundPageLocked(PageUpdateKey key) {
        if (key != null && foregroundPageKeys.add(key)) {
            foregroundPageOrder.addLast(key);
        }
    }

    private PageUpdate pollPendingPageUpdateLocked(long nowNanos) {
        int foregroundCandidates = foregroundPageOrder.size();
        while (foregroundCandidates-- > 0 && !foregroundPageOrder.isEmpty()) {
            PageUpdateKey key = foregroundPageOrder.removeFirst();
            foregroundPageKeys.remove(key);
            PageUpdate current = pendingPageUpdates.get(key);
            if (current == null) continue;
            if (!branchUpdateReady(current, nowNanos)) {
                enqueueForegroundPageLocked(key);
                continue;
            }
            pendingPageUpdates.remove(key);
            return current;
        }
        Iterator<Map.Entry<PageUpdateKey, PageUpdate>> iterator =
                pendingPageUpdates.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<PageUpdateKey, PageUpdate> entry = iterator.next();
            PageUpdate fallback = entry.getValue();
            if (!branchUpdateReady(fallback, nowNanos)) continue;
            iterator.remove();
            foregroundPageKeys.remove(entry.getKey());
            foregroundPageOrder.remove(entry.getKey());
            return fallback;
        }
        return null;
    }

    private static boolean isForegroundLane(MapRequestLane lane) {
        return lane == MapRequestLane.MINIMAP
                || lane == MapRequestLane.FULLSCREEN;
    }

    private static long branchQuietNanos(MapRequestLane lane) {
        if (lane == MapRequestLane.MINIMAP) return MINIMAP_BRANCH_QUIET_NANOS;
        if (lane == MapRequestLane.FULLSCREEN) return FULLSCREEN_BRANCH_QUIET_NANOS;
        return BACKGROUND_BRANCH_QUIET_NANOS;
    }

    private static long branchMaxHoldNanos(MapRequestLane lane) {
        if (lane == MapRequestLane.MINIMAP) return MINIMAP_BRANCH_MAX_HOLD_NANOS;
        if (lane == MapRequestLane.FULLSCREEN) return FULLSCREEN_BRANCH_MAX_HOLD_NANOS;
        return BACKGROUND_BRANCH_MAX_HOLD_NANOS;
    }

    private static boolean branchUpdateReady(PageUpdate update, long nowNanos) {
        if (update == null) return false;
        if (update.immediate()) return true;
        return nowNanos - update.lastQueuedNanos() >= branchQuietNanos(update.lane())
                || nowNanos - update.firstQueuedNanos()
                        >= branchMaxHoldNanos(update.lane());
    }

    private boolean branchAlreadyHasPage(PageUpdateKey key) {
        int nodeX = Math.floorDiv(key.globalPageX(), 2);
        int nodeZ = Math.floorDiv(key.globalPageZ(), 2);
        Node node = nodes.get(new NodeKey(key.dimension(), key.view(), key.layerY(),
                1, nodeX, nodeZ));
        if (node == null) return false;
        int childX = Math.floorMod(key.globalPageX(), 2);
        int childZ = Math.floorMod(key.globalPageZ(), 2);
        int childIndex = childZ * 2 + childX;
        return node.childRevisions[childIndex] != 0L
                || node.uploadedChildRevisions[childIndex] != 0L;
    }

    private int drainPendingPageUpdates(int budget, long deadlineNanos) {
        int derived = 0;
        while (derived < budget && System.nanoTime() < deadlineNanos) {
            PageUpdate update;
            long nowNanos = System.nanoTime();
            synchronized (pendingPageUpdates) {
                update = pollPendingPageUpdateLocked(nowNanos);
                if (update == null) break;
            }
            long started = System.nanoTime();
            pipelineTelemetry.recordStageNanos(MapPipelineStage.BRANCH_QUEUE,
                    Math.max(0L, started - update.firstQueuedNanos()));
            applyPageUpdate(update);
            pipelineTelemetry.recordStageNanos(MapPipelineStage.BRANCH_DERIVE,
                    System.nanoTime() - started);
            derived++;
        }
        return derived;
    }

    /**
     * Bottom-up dirty aggregation. Nodes added while reducing a child are appended
     * to the same ordered set, so all already-queued siblings run before their common
     * parent. This mirrors Xaero's changed/tile-buffer handoff and prevents branch
     * work amplification during wide cave fills.
     */
    private boolean drainPendingPropagation(int budget) {
        int remaining = Math.max(1, budget);
        while (remaining-- > 0 && hasPendingPropagation()) {
            NodeKey key = pollPropagation();
            if (key == null) break;
            Node node = nodes.get(key);
            if (node == null || !node.isDirty()) continue;
            long started = System.nanoTime();
            propagate(node);
            pipelineTelemetry.recordStageNanos(MapPipelineStage.BRANCH_DERIVE,
                    System.nanoTime() - started);
        }
        return !hasPendingPropagation();
    }

    /**
     * A node may publish as soon as its own dirty rectangle has been consumed by
     * its parent. Unrelated dirty roots are not a transaction barrier. This is the
     * retained-texture rule used by Xaero: each dirty texture/branch advances
     * independently while parents catch up from child version changes.
     */
    private void propagateBeforeUpload(Node node) {
        if (node == null || !node.isDirty()) return;
        if (!removePropagation(node.key)) return;
        long started = System.nanoTime();
        propagate(node);
        pipelineTelemetry.recordStageNanos(MapPipelineStage.BRANCH_DERIVE,
                System.nanoTime() - started);
    }

    private static long branchGpuRetryDelayNanos() {
        // A denial describes only the current render-frame ledger. Exponential
        // 24-160 ms backoff made a cheap 0.08 ms upload wait for many future frames.
        return MapPerformanceGovernor.getInstance().underPressure()
                ? 32_000_000L : 16_000_000L;
    }

    int publish(int budget, long deadlineNanos) {
        lastPublishGpuDenied = false;
        drainRenderSignals(Math.max(32, budget * 16));
        drainDiskLoads();
        int derivedPages = drainPendingPageUpdates(
                Math.max(8, Math.min(128, budget * 12)), deadlineNanos);
        // Parent derivation is CPU-only and extremely cheap compared with upload.
        // Coalesce a bounded wave first, but do not require the *entire* hierarchy
        // to become clean before any ready branch can reach the GPU. Continuous
        // cave source arrival otherwise turns pendingPropagation into a global
        // barrier and already-ready visible nodes wait seconds behind unrelated
        // work.
        int propagationBudget = Math.max(24, derivedPages * 3 + 16);
        drainPendingPropagation(propagationBudget);
        int published = 0;
        int considered = 0;
        int scanLimit = Math.max(8, budget * 4);
        while (published < budget && considered++ < scanLimit
                && System.nanoTime() < deadlineNanos) {
            NodeKey key = pollDirtyNode();
            if (key == null) break;
            dirtySet.remove(key);
            Node node = nodes.get(key);
            if (node == null || !node.isDirty() || node.knownMask == 0L) continue;
            long now = System.nanoTime();
            if (node.nextUploadAttemptNanos > now) {
                enqueue(key);
                continue;
            }
            /*
             * If this exact node did not fit in the opportunistic propagation
             * slice, consume its dirty rectangle into the parent now. Clearing the
             * child after upload is then safe without waiting for unrelated roots.
             */
            propagateBeforeUpload(node);
            if (!ensureSlot(node)) {
                enqueue(key);
                continue;
            }
            /*
             * Lane is dynamic viewport ownership. A node that was fullscreen ten
             * seconds ago must not remain foreground after the camera moves.
             * Preserve explicit MINIMAP ownership; otherwise derive priority from
             * the current fullscreen rectangle.
             */
            MapRequestLane uploadLane = node.requestLane == MapRequestLane.MINIMAP
                    ? MapRequestLane.MINIMAP
                    : nodeInVisibleViewport(node.key)
                            ? MapRequestLane.FULLSCREEN
                            : MapRequestLane.BACKGROUND;
            node.requestLane = uploadLane;
            boolean foreground = uploadLane == MapRequestLane.MINIMAP
                    || uploadLane == MapRequestLane.FULLSCREEN;
            if (!MapGpuBudgetController.getInstance().tryReserve(
                    MapGpuBudgetController.UploadKind.BRANCH,
                    uploadLane, foreground)) {
                lastPublishGpuDenied = true;
                node.uploadReservationFailures = Math.min(8,
                        node.uploadReservationFailures + 1);
                node.nextUploadAttemptNanos = now + branchGpuRetryDelayNanos();
                enqueue(key);
                /*
                 * PASS99 split dirty work into foreground/background queues. Once
                 * pollDirtyNode() reaches a background node, the foreground deque
                 * is already exhausted for this slice. Continuing after a denial
                 * can only hammer the same exhausted frame ledger and was the
                 * direct source of ~19k branch reservation denials in the PASS99.1
                 * log. Xaero stops the upload slice when its uploader budget is
                 * exhausted and leaves dirty textures retained for the next pass.
                 */
                break;
            }
            node.uploadReservationFailures = 0;
            node.nextUploadAttemptNanos = 0L;
            long uploadStart = System.nanoTime();
            atlases[node.key.level()].upload(node.atlasSlot, node.pixels, node.dirtyRect());
            long uploadNanos = System.nanoTime() - uploadStart;
            pipelineTelemetry.recordStageNanos(MapPipelineStage.BRANCH_UPLOAD,
                    uploadNanos);
            MapGpuBudgetController.getInstance().record(
                    MapGpuBudgetController.UploadKind.BRANCH, uploadNanos);
            node.clearDirty();
            node.initialized = true;
            String residentKey = residencyKey(node.key);
            MapResidencyManager.getInstance().register(
                    residentKey, MapResidencyManager.Kind.CAVE_BRANCH,
                    66L * 66L * Integer.BYTES,
                    () -> evictResidentForBudget(node.key));
            MapResidencyManager.getInstance().enforceBudget(
                    residentKey, MapRequestLane.FULLSCREEN);
            node.uploadedRevision = node.revision;
            node.uploadedKnownMask = node.knownMask;
            node.uploadedCompleteMask = node.completeMask;
            System.arraycopy(node.childRevisions, 0,
                    node.uploadedChildRevisions, 0, node.childRevisions.length);
            knownRenderNodes.add(node.key);
            CaveAtlasRegion publishedRegion = atlases[node.key.level()].region(
                    node.atlasSlot, node.uploadedKnownMask, node.uploadedCompleteMask);
            CaveAtlasRegion previousRegion = publishedRegion == null
                    ? publishedRegions.remove(node.key)
                    : publishedRegions.put(node.key, publishedRegion);
            // A branch upload can be a pure pixel refresh at the same atlas slot.
            // Rebuild the render hierarchy only when residency or coverage masks
            // change; texture content itself is already visible to the cached plan.
            if (!sameRenderTopology(previousRegion, publishedRegion)) {
                contentRevision.incrementAndGet();
            }
            saveNode(node);
            published++;
        }
        return published;
    }

    boolean lastPublishGpuDenied() {
        return lastPublishGpuDenied;
    }

    private static boolean sameRenderTopology(CaveAtlasRegion left,
            CaveAtlasRegion right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return left.texture().equals(right.texture())
                && Float.compare(left.sourceX(), right.sourceX()) == 0
                && Float.compare(left.sourceY(), right.sourceY()) == 0
                && left.sourceSize() == right.sourceSize()
                && left.atlasSize() == right.atlasSize()
                && left.level() == right.level()
                && left.worldSize() == right.worldSize()
                && left.knownMask() == right.knownMask()
                && left.completeMask() == right.completeMask();
    }

    void clear() {
        if (!nodes.isEmpty()) contentRevision.incrementAndGet();
        for (Node node : nodes.values()) node.close();
        nodes.clear();
        publishedRegions.clear();
        knownRenderNodes.clear();
        renderRequests.clear();
        queuedRenderRequests.clear();
        renderTouches.clear();
        queuedRenderTouches.clear();
        foregroundDirtyQueue.clear();
        backgroundDirtyQueue.clear();
        dirtySet.clear();
        visibleDimension = "";
        visibleView = null;
        visibleLayerY = Integer.MIN_VALUE;
        minimapDimension = "";
        minimapView = null;
        minimapLayerY = Integer.MIN_VALUE;
        preferredMinimapLevel = 1;
        visibleMinPageX = 1;
        visibleMaxPageX = 0;
        visibleMinPageZ = 1;
        visibleMaxPageZ = 0;
        loadGeneration++;
        loadingFromDisk.clear();
        loadedFromDisk.clear();
        diskLoadWindowStartedNanos = 0L;
        diskLoadsInWindow = 0;
        synchronized (pendingPageUpdates) {
            pendingPageUpdates.clear();
            foregroundPageOrder.clear();
            foregroundPageKeys.clear();
        }
        foregroundPropagation.clear();
        backgroundPropagation.clear();
        for (int level = 1; level <= MAX_LEVEL; level++) atlases[level].resetSlots();
    }

    private MapRequestLane propagatedLane(Node child, Node parent,
            int parentLevel) {
        MapRequestLane lane = child.requestLane;
        if (lane != MapRequestLane.MINIMAP) return lane;
        boolean sameMinimapProjection = child.key.dimension().equals(minimapDimension)
                && child.key.view() == minimapView
                && child.key.layerY() == minimapLayerY;
        if (sameMinimapProjection && parentLevel <= preferredMinimapLevel) {
            return MapRequestLane.MINIMAP;
        }
        // A fullscreen viewport may independently need this ancestor. Otherwise
        // do not let minimap leaf churn promote every L2..Ln node to foreground.
        return nodeInVisibleViewport(parent.key)
                ? MapRequestLane.FULLSCREEN : MapRequestLane.BACKGROUND;
    }

    private void propagate(Node child) {
        if (child.key.level() >= RENDER_MAX_LEVEL || !child.isDirty()) return;
        int parentLevel = child.key.level() + 1;
        int parentX = Math.floorDiv(child.key.nodeX(), 2);
        int parentZ = Math.floorDiv(child.key.nodeZ(), 2);
        Node parent = node(child.key.dimension(), child.key.view(), child.key.layerY(),
                parentLevel, parentX, parentZ);
        MapRequestLane propagatedLane = propagatedLane(child, parent,
                parentLevel);
        if (parent.requestLane == MapRequestLane.MINIMAP
                && propagatedLane != MapRequestLane.MINIMAP) {
            parent.requestLane = propagatedLane;
        } else if (propagatedLane.strongerThan(parent.requestLane)) {
            parent.requestLane = propagatedLane;
        }

        int quadrantX = Math.floorMod(child.key.nodeX(), 2);
        int quadrantZ = Math.floorMod(child.key.nodeZ(), 2);
        int destinationBaseX = quadrantX * 32;
        int destinationBaseY = quadrantZ * 32;
        int minX = Math.max(0, child.dirtyMinX >> 1);
        int minY = Math.max(0, child.dirtyMinY >> 1);
        int maxX = Math.min(31, child.dirtyMaxX >> 1);
        int maxY = Math.min(31, child.dirtyMaxY >> 1);
        int changedMinX = 64;
        int changedMinY = 64;
        int changedMaxX = -1;
        int changedMaxY = -1;
        long previousKnownMask = parent.knownMask;
        long previousCompleteMask = parent.completeMask;
        for (int y = minY; y <= maxY; y++) {
            int sourceY = y << 1;
            int targetRow = (destinationBaseY + y) * 64;
            for (int x = minX; x <= maxX; x++) {
                int sourceX = x << 1;
                int known = knownCount(child.knownRows, sourceX, sourceY);
                int targetX = destinationBaseX + x;
                int targetY = destinationBaseY + y;
                int targetIndex = targetRow + targetX;
                boolean nowKnown = known > 0;
                int reduced = nowKnown
                        ? reduceCave(child.pixels, child.knownRows, sourceX, sourceY) : 0;
                boolean wasKnown = (parent.knownRows[targetY] & (1L << targetX)) != 0L;
                boolean wasComplete = (parent.completeRows[targetY] & (1L << targetX)) != 0L;
                boolean nowComplete = nowKnown
                        && knownCount(child.completeRows, sourceX, sourceY) == 4;
                if (wasKnown == nowKnown && parent.pixels[targetIndex] == reduced
                        && wasComplete == nowComplete) continue;
                parent.pixels[targetIndex] = reduced;
                setBit(parent.knownRows, targetX, targetY, nowKnown);
                setBit(parent.completeRows, targetX, targetY, nowComplete);
                changedMinX = Math.min(changedMinX, targetX);
                changedMinY = Math.min(changedMinY, targetY);
                changedMaxX = Math.max(changedMaxX, targetX);
                changedMaxY = Math.max(changedMaxY, targetY);
            }
        }

        int childIndex = quadrantZ * 2 + quadrantX;
        parent.childRevisions[childIndex] = child.revision;
        refreshQuadrantCoverage(parent, quadrantX, quadrantZ);
        boolean coverageChanged = previousKnownMask != parent.knownMask
                || previousCompleteMask != parent.completeMask;
        if (changedMaxX < changedMinX && !coverageChanged) return;
        if (changedMaxX < changedMinX) {
            changedMinX = destinationBaseX + minX;
            changedMinY = destinationBaseY + minY;
            changedMaxX = destinationBaseX + maxX;
            changedMaxY = destinationBaseY + maxY;
        }
        parent.revision++;
        parent.markDirty(changedMinX, changedMinY, changedMaxX, changedMaxY);
        enqueue(parent.key);
        trimLevel(parentLevel);
        if (parent.key.level() < RENDER_MAX_LEVEL) {
            enqueuePropagation(parent.key);
        }
    }

    private static int knownCount(long[] rows, int x, int y) {
        int count = 0;
        if ((rows[y] & (1L << x)) != 0L) count++;
        if ((rows[y] & (1L << (x + 1))) != 0L) count++;
        if ((rows[y + 1] & (1L << x)) != 0L) count++;
        if ((rows[y + 1] & (1L << (x + 1))) != 0L) count++;
        return count;
    }

    private static int reduceCave(int[] pixels, long[] rows, int x, int y) {
        int best = 0;
        int bestScore = -1;
        // Four scalar samples; never allocate int[4]/boolean[4] per output texel.
        for (int child = 0; child < 4; child++) {
            int sourceX = x + (child & 1);
            int sourceY = y + (child >>> 1);
            if ((rows[sourceY] & (1L << sourceX)) == 0L) continue;
            int value = pixels[sourceY * 64 + sourceX];
            int alpha = (value >>> 24) & 0xFF;
            if (value == 0 || alpha == 0) continue;
            int r = value & 0xFF;
            int g = (value >>> 8) & 0xFF;
            int b = (value >>> 16) & 0xFF;
            int luminance = r * 3 + g * 6 + b;
            int score = alpha * 2048 + luminance;
            if (score > bestScore) {
                bestScore = score;
                best = value;
            }
        }
        // Thin cave passages disappear when every recursive level blends them
        // with surrounding darkness/stone. Keep the most visible real child.
        return best;
    }

    private static void setBit(long[] rows, int x, int y, boolean value) {
        long bit = 1L << x;
        if (value) rows[y] |= bit;
        else rows[y] &= ~bit;
    }

    private static void refreshQuadrantCoverage(Node node, int quadrantX, int quadrantZ) {
        int startX = quadrantX * 32;
        int startY = quadrantZ * 32;
        boolean anyKnown = false;
        boolean allComplete = true;
        long rangeMask = 0xFFFFFFFFL << startX;
        for (int y = startY; y < startY + 32; y++) {
            anyKnown |= (node.knownRows[y] & rangeMask) != 0L;
            if ((node.completeRows[y] & rangeMask) != rangeMask) allComplete = false;
        }
        int childIndex = quadrantZ * 2 + quadrantX;
        if (anyKnown) node.knownMask |= 1L << childIndex;
        else node.knownMask &= ~(1L << childIndex);
        if (allComplete) node.completeMask |= 1L << childIndex;
        else node.completeMask &= ~(1L << childIndex);
    }

    private static boolean allRowsComplete(long[] rows) {
        for (int y = 0; y < 64; y++) if (rows[y] != FULL_ROW) return false;
        return true;
    }

    private Node node(String dimension, CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
        NodeKey key = new NodeKey(dimension, view, layerY, level, nodeX, nodeZ);
        Node node = nodes.computeIfAbsent(key, Node::new);
        requestDiskLoad(key);
        return node;
    }

    private void requestDiskLoad(NodeKey key) {
        if (key.view() == CaveView.LAYERED) return;
        if (nodes.containsKey(key) && nodes.get(key).knownMask != 0L) return;
        if (!diskCache.mayContain(diskKey(key))) return;
        if (loadingFromDisk.contains(key)) return;
        if (!consumeDiskLoadSlot()) return;
        if (!loadingFromDisk.add(key)) return;
        long generation = loadGeneration;
        diskCache.loadAsync(diskKey(key)).whenComplete((snapshot, throwable) ->
                loadedFromDisk.add(new LoadedNode(key, snapshot, generation)));
    }

    private boolean consumeDiskLoadSlot() {
        long now = System.nanoTime();
        if (diskLoadWindowStartedNanos == 0L
                || now - diskLoadWindowStartedNanos >= DISK_LOAD_WINDOW_NANOS) {
            diskLoadWindowStartedNanos = now;
            diskLoadsInWindow = 0;
        }
        if (diskLoadsInWindow >= MAX_DISK_LOADS_PER_WINDOW) return false;
        diskLoadsInWindow++;
        return true;
    }

    private void drainDiskLoads() {
        LoadedNode loaded;
        while ((loaded = loadedFromDisk.poll()) != null) {
            loadingFromDisk.remove(loaded.key);
            if (loaded.generation != loadGeneration) continue;
            if (loaded.key.view() == CaveView.LAYERED) continue;
            LodBranchDiskCache.Snapshot snapshot = loaded.snapshot;
            if (snapshot == null || snapshot.knownMask() == 0L) continue;
            Node node = nodes.computeIfAbsent(loaded.key, Node::new);
            for (int y = 0; y < 64; y++) {
                long incomingKnown = snapshot.knownRows()[y];
                long missing = incomingKnown & ~node.knownRows[y];
                while (missing != 0L) {
                    int x = Long.numberOfTrailingZeros(missing);
                    int index = y * 64 + x;
                    node.pixels[index] = snapshot.pixels()[index];
                    missing &= missing - 1L;
                }
                node.knownRows[y] |= incomingKnown;
                node.completeRows[y] |= snapshot.completeRows()[y];
            }
            node.knownMask |= snapshot.knownMask();
            node.completeMask |= snapshot.completeMask();
            node.revision = Math.max(node.revision, snapshot.revision());
            node.markFullDirty();
            enqueue(node.key);
        }
    }

    private void saveNode(Node node) {
        if (node.key.view() == CaveView.LAYERED) return;
        diskCache.saveAsync(diskKey(node.key), new LodBranchDiskCache.Snapshot(
                node.pixels, node.knownRows, node.completeRows,
                node.knownMask, node.completeMask, node.revision));
    }

    private static String residencyKey(NodeKey key) {
        return key == null ? "cave_branch:unknown" : "cave_branch:" + key;
    }

    private static LodBranchDiskCache.Key diskKey(NodeKey key) {
        String kind = "cave_v" + CaveCacheSchema.CAVE_LOD_KIND_VERSION
                + "_e" + CaveCacheSchema.EPOCH + '_' + key.dimension + '_'
                + key.view.name().toLowerCase() + '_' + key.layerY;
        return new LodBranchDiskCache.Key(kind,
                key.level, key.nodeX, key.nodeZ);
    }


    private boolean evictResidentForBudget(NodeKey key) {
        if (key == null) return false;
        Node node = nodes.get(key);
        if (node == null || node.atlasSlot < 0 || node.isDirty()
                || !hasPublishedParentCoverage(node)) return false;
        atlases[key.level()].releaseSlot(node.atlasSlot);
        MapResidencyManager.getInstance().remove(residencyKey(key));
        node.atlasSlot = -1;
        node.initialized = false;
        publishedRegions.remove(key);
        contentRevision.incrementAndGet();
        MapResidencyManager.getInstance().markTopologyChanged();
        return true;
    }

    /** Keeps one continuous cave LOD underlay while exact/branch residency churns. */
    private boolean hasPublishedParentCoverage(Node node) {
        if (node == null || node.key.level() >= MAX_LEVEL) return false;
        int parentLevel = node.key.level() + 1;
        int parentX = Math.floorDiv(node.key.nodeX(), 2);
        int parentZ = Math.floorDiv(node.key.nodeZ(), 2);
        Node parent = nodes.get(new NodeKey(node.key.dimension(), node.key.view(),
                node.key.layerY(), parentLevel, parentX, parentZ));
        if (parent == null || !parent.initialized || parent.atlasSlot < 0) return false;
        int childIndex = Math.floorMod(node.key.nodeZ(), 2) * 2
                + Math.floorMod(node.key.nodeX(), 2);
        return (parent.uploadedKnownMask & (1L << childIndex)) != 0L
                && parent.uploadedChildRevisions[childIndex] >= node.uploadedRevision;
    }

    private boolean ensureSlot(Node node) {
        if (node.atlasSlot >= 0) return true;
        CaveBranchAtlas atlas = atlases[node.key.level()];
        int slot = atlas.acquireSlot();
        if (observedStorageGeneration[node.key.level()] == Long.MIN_VALUE) {
            observedStorageGeneration[node.key.level()] = atlas.storageGeneration();
        }
        if (slot < 0) {
            if (atlas.hasQuarantinedSlots()) return false;
            if (!retireOldestResident(node.key.level(), node.key)) return false;
            // One fenced retirement is enough for this level this frame.
            return false;
        }
        node.atlasSlot = slot;
        node.markFullDirty();
        return true;
    }

    private void trimLevel(int level) {
        int atlasSlots = CaveBranchAtlas.slotCountForLevel(level);
        // Keep at most two atlas generations of CPU branch pixels warm. The old
        // fixed 2048/1024 limits could retain well over one hundred MiB across
        // surface and cave trees even after GPU entries were evicted.
        int maximumCpuNodes = Math.max(96, atlasSlots * 2);
        while (countLevel(level) > maximumCpuNodes) {
            if (removeOldestNonResident(level)) continue;
            if (!removeOldestDiscardableDirty(level)) break;
        }
    }

    private int countLevel(int level) {
        int count = 0;
        for (NodeKey key : nodes.keySet()) if (key.level() == level) count++;
        return count;
    }

    /** Releases only GPU residency; CPU pixels and coverage remain reusable. */
    private boolean retireOldestResident(int level, NodeKey protectedKey) {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        java.util.Map<String, Node> byKey = new java.util.HashMap<>();
        // nodes is an access-order LinkedHashMap. Parent-coverage lookups call
        // nodes.get(), which reorder the map. Iterate a stable snapshot so LRU
        // observation cannot invalidate the active iterator.
        java.util.List<Map.Entry<NodeKey, Node>> snapshot =
                new java.util.ArrayList<>(nodes.entrySet());
        for (Map.Entry<NodeKey, Node> entry : snapshot) {
            if (entry.getKey().level() != level
                    || entry.getKey().equals(protectedKey)) continue;
            Node node = entry.getValue();
            if (node.atlasSlot < 0 || node.isDirty()
                    || !hasPublishedParentCoverage(node)) continue;
            String residency = residencyKey(entry.getKey());
            candidates.add(residency);
            byKey.put(residency, node);
        }
        String victimKey = MapResidencyManager.getInstance().chooseVictim(
                candidates, residencyKey(protectedKey));
        Node retired = byKey.get(victimKey);
        if (retired == null) return false;
        atlases[level].releaseSlot(retired.atlasSlot);
        MapResidencyManager.getInstance().remove(victimKey);
        retired.atlasSlot = -1;
        retired.initialized = false;
        publishedRegions.remove(retired.key);
        contentRevision.incrementAndGet();
        MapResidencyManager.getInstance().markTopologyChanged();
        return true;
    }

    private boolean removeOldestNonResident(int level) {
        Iterator<Map.Entry<NodeKey, Node>> iterator = nodes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<NodeKey, Node> entry = iterator.next();
            if (entry.getKey().level() != level) continue;
            Node node = entry.getValue();
            if (node.atlasSlot >= 0 || node.isDirty()) continue;
            iterator.remove();
            dirtySet.remove(node.key);
            publishedRegions.remove(node.key);
            knownRenderNodes.remove(node.key);
            return true;
        }
        return false;
    }

    /**
     * Dirty CPU hierarchy is reconstructable cache state. When the cache exceeds its
     * bounded level budget, an offscreen nonresident dirty node may be discarded and
     * rebuilt from exact/archive source on the next visible request. This prevents a
     * never-uploaded background branch from becoming an accidental memory root.
     */
    private boolean removeOldestDiscardableDirty(int level) {
        Iterator<Map.Entry<NodeKey, Node>> iterator = nodes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<NodeKey, Node> entry = iterator.next();
            Node node = entry.getValue();
            if (entry.getKey().level() != level || node.atlasSlot >= 0
                    || node.requestLane == MapRequestLane.MINIMAP
                    || nodeInVisibleViewport(entry.getKey())) continue;
            iterator.remove();
            foregroundDirtyQueue.remove(entry.getKey());
            backgroundDirtyQueue.remove(entry.getKey());
            dirtySet.remove(entry.getKey());
            foregroundPropagation.remove(entry.getKey());
            backgroundPropagation.remove(entry.getKey());
            publishedRegions.remove(entry.getKey());
            knownRenderNodes.remove(entry.getKey());
            return true;
        }
        return false;
    }

    private void enqueue(NodeKey key) {
        Node node = nodes.get(key);
        if (node != null && node.knownMask != 0L) knownRenderNodes.add(key);
        if (!dirtySet.add(key)) return;
        /*
         * MINIMAP is a separate foreground viewport. PASS99 accidentally required
         * every foreground node to intersect the fullscreen rectangle, placing
         * minimap cave work behind historical background branches whenever the
         * fullscreen map was panned away.
         */
        boolean minimap = node != null
                && node.requestLane == MapRequestLane.MINIMAP;
        boolean fullscreen = node != null
                && node.requestLane == MapRequestLane.FULLSCREEN
                && nodeInVisibleViewport(key);
        if (minimap) {
            foregroundDirtyQueue.addFirst(key);
        } else if (fullscreen) {
            if (key.level() == preferredVisibleLevel) {
                foregroundDirtyQueue.addFirst(key);
            } else {
                foregroundDirtyQueue.addLast(key);
            }
        } else {
            backgroundDirtyQueue.addLast(key);
        }
    }

    private NodeKey pollDirtyNode() {
        NodeKey key = foregroundDirtyQueue.pollFirst();
        return key != null ? key : backgroundDirtyQueue.pollFirst();
    }

    private int dirtyQueueSize() {
        return foregroundDirtyQueue.size() + backgroundDirtyQueue.size();
    }

    private record PageUpdateKey(String dimension, CaveView view, int layerY,
            int globalPageX, int globalPageZ) {
    }

    private record PageUpdate(PageUpdateKey key, int[] pagePixels64, long[] knownRows,
            int knownColumns, boolean complete, long sourceRevision,
            MapRequestLane lane, long firstQueuedNanos, long lastQueuedNanos,
            boolean immediate) {
    }

    private record NodeKey(String dimension, CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
    }

    private record LoadedNode(NodeKey key, LodBranchDiskCache.Snapshot snapshot,
            long generation) {
    }

    private final class Node {
        private final NodeKey key;
        private final int[] pixels = new int[64 * 64];
        private final long[] knownRows = new long[64];
        private final long[] completeRows = new long[64];
        private long knownMask;
        private long completeMask;
        /** GPU-published masks remain authoritative while newer CPU state is dirty. */
        private long uploadedKnownMask;
        private long uploadedCompleteMask;
        private long revision = 1L;
        private long uploadedRevision;
        private final long[] childRevisions = new long[4];
        private final long[] uploadedChildRevisions = new long[4];
        private int atlasSlot = -1;
        private int dirtyMinX = 64;
        private int dirtyMinY = 64;
        private int dirtyMaxX = -1;
        private int dirtyMaxY = -1;
        private long nextUploadAttemptNanos;
        private int uploadReservationFailures;
        private MapRequestLane requestLane = MapRequestLane.BACKGROUND;
        private boolean initialized;

        private Node(NodeKey key) {
            this.key = key;
        }

        private boolean isComplete() {
            return completeMask == 0xFL;
        }

        private void markDirty(int minX, int minY, int maxX, int maxY) {
            dirtyMinX = Math.min(dirtyMinX, minX);
            dirtyMinY = Math.min(dirtyMinY, minY);
            dirtyMaxX = Math.max(dirtyMaxX, maxX);
            dirtyMaxY = Math.max(dirtyMaxY, maxY);
        }

        private void markFullDirty() {
            markDirty(0, 0, 63, 63);
        }

        private boolean isDirty() {
            return dirtyMaxX >= dirtyMinX && dirtyMaxY >= dirtyMinY;
        }

        private CaveTextureAtlas.DirtyRect dirtyRect() {
            return new CaveTextureAtlas.DirtyRect(
                    dirtyMinX, dirtyMinY, dirtyMaxX, dirtyMaxY);
        }

        private void clearDirty() {
            dirtyMinX = dirtyMinY = 64;
            dirtyMaxX = dirtyMaxY = -1;
        }

        private void close() {
            if (atlasSlot >= 0) atlases[key.level()].releaseSlot(atlasSlot);
            MapResidencyManager.getInstance().remove(residencyKey(key));
            atlasSlot = -1;
            initialized = false;
        }
    }
}
