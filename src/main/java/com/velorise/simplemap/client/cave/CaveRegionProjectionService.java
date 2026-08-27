package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapDebugRecorder;
import com.velorise.simplemap.client.MapPerformanceGovernor;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapWorkScheduler;
import net.minecraft.world.level.Level;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;

/**
 * Region-transaction projection authority for local-world cave imports.
 *
 * <p>The Anvil reader owns source decode and vertical archive creation. Once a
 * 64x64 page plus its one-chunk styling halo is resolved, this service performs
 * the final projection/style pass exactly once, stores the clear result in a
 * 512x512 region transaction, persists that transaction as CIMG and hands the
 * same immutable pixels to exact GPU publication.</p>
 *
 * <p>This removes the old cold path where the world reader committed source,
 * the exact manager independently resolved/styled it again, GPU publication
 * happened, and only then a second subsystem reconstructed the region image.</p>
 */
final class CaveRegionProjectionService {
    private static final CaveRegionProjectionService INSTANCE =
            new CaveRegionProjectionService();
    private static final int MAX_ACTIVE_BUILDS = 8;
    private static final int MAX_ACTIVE_REGION_BUILDS = 8;
    /**
     * Xaero's writer bounds visible work by both tile count and a hard time slice.
     * Keep fullscreen throughput high, but prevent gameplay/minimap Cave from
     * occupying all global CPU workers with eight simultaneous 24-page region jobs.
     */
    private static final int MAX_ACTIVE_MINIMAP_REGION_BUILDS = 1;
    private static final int MAX_ACTIVE_BACKGROUND_REGION_BUILDS = 2;
    private static final int MINIMAP_REGION_PAGE_SLICE = 4;
    private static final int FULLSCREEN_REGION_PAGE_SLICE = 24;
    private static final int BACKGROUND_REGION_PAGE_SLICE = 8;
    private static final int MAX_PROJECTED_PAGES = 4096;
    private static final int MAX_REGION_TRANSACTIONS = 64;
    private static final long INCOMPLETE_RETRY_MS = 40L;
    /*
     * CIMG snapshots are 512x512 ARGB transactions (about 1 MiB before file
     * framing). A 120 ms quiet period rewrote partial regions many times during
     * one viewport fill. Coalesce them into one bounded background write instead.
     */
    private static final long REGION_WRITE_DEBOUNCE_MS = 1_200L;
    /** A continuously changing region still receives a periodic durable checkpoint. */
    private static final long REGION_WRITE_MAX_DIRTY_MS = 5_000L;
    /**
     * A CIMG write always emits the full 512x512 / 1 MiB body even when only one
     * 64x64 child is known. PASS154 wrote 253 region transactions in ~5 minutes,
     * 85 of them with four pages or fewer. Keep sparse edge state in CAV8/CVD and
     * only persist the packaged image after it is useful as a viewport bootstrap.
     */
    private static final int REGION_WRITE_MIN_PACKAGED_PAGES = 8;
    /** Coalesce at least one eighth-region of new/revised page progress per write. */
    private static final int REGION_WRITE_MIN_PROGRESS = 8;
    private static final int MAX_PENDING_REGION_WRITES = 1;
    /** Keep completed visible region transactions alive across repeated viewport pulses. */
    private static final long FOREGROUND_REQUEST_LEASE_MS = 2_000L;
    /**
     * A foreground handoff is an offer, not ownership transfer. The texture manager
     * may poll while its planner is rebasing/mode-switching and reject that offer.
     * Re-offer an unacknowledged immutable page on the next foreground lease pulse
     * instead of permanently suppressing the same source revision.
     */
    private static final long FOREGROUND_REOFFER_MS = 250L;
    /** Small coherent frontier slice, matching Xaero's bounded region updates. */
    private static final int FOREGROUND_RELEASE_SLICE = 64;
    /**
     * PASS165: a retained foreground region is a live projection subscription, not
     * a one-shot build. Source chunks can finish after a partial ProjectedPage was
     * cached. Reconcile a bounded number of active regions every maintenance pulse
     * so source-revision drift automatically re-arms projection without waiting for
     * a new viewport/minimap subscription.
     */
    private static final int FOREGROUND_REVISION_RECONCILE_BUDGET = 32;
    private static final long FOREGROUND_REVISION_RECONCILE_INTERVAL_MS = 24L;

    private final CaveTileRepository repository = CaveTileRepository.getInstance();
    private final CaveRegionImageCache regionImageCache =
            CaveRegionImageCache.getInstance();
    private final PriorityQueue<Request> queue = new PriorityQueue<>();
    private final Map<PageKey, Request> requests = new LinkedHashMap<>();
    private final PriorityQueue<RegionBuildRequest> regionQueue = new PriorityQueue<>();
    private final Map<RegionBuildKey, RegionBuildRequest> regionRequests =
            new LinkedHashMap<>();
    private final LinkedHashMap<PageKey, ProjectedPage> pages =
            new LinkedHashMap<>(256, 0.75f, true);
    private final LinkedHashMap<RegionKey, RegionTransaction> regions =
            new LinkedHashMap<>(16, 0.75f, true);
    /**
     * Ready pages are globally viewport-ranked rather than FIFO. Native regions
     * complete on different worker threads; FIFO allowed an old far region to occupy
     * every branch/exact admission while the current screen remained black.
     */
    private final PriorityQueue<ProjectedPage> readyPages =
            new PriorityQueue<>(Comparator
                    .comparingInt((ProjectedPage page) -> page.lane().rank())
                    .reversed()
                    .thenComparing(Comparator
                            .comparingInt(ProjectedPage::priority).reversed())
                    .thenComparingLong(ProjectedPage::sequence));
    private final Set<RegionKey> pendingWrites = new HashSet<>();
    /** Current loading-generation authority per foreground presentation lane. */
    private final EnumMap<MapRequestLane, CavePresentationGeneration>
            activePresentationGenerations = new EnumMap<>(MapRequestLane.class);

    private long epoch = 1L;
    private long sequence;
    private int activeBuilds;
    private int activeRegionBuilds;

    private CaveRegionProjectionService() {
    }

    static CaveRegionProjectionService getInstance() {
        return INSTANCE;
    }

    synchronized void reset() {
        epoch++;
        queue.clear();
        requests.clear();
        regionQueue.clear();
        regionRequests.clear();
        pages.clear();
        regions.clear();
        readyPages.clear();
        pendingWrites.clear();
        activePresentationGenerations.clear();
        activeBuilds = 0;
        activeRegionBuilds = 0;
    }

    /**
     * Transfers foreground projection ownership to one cave view.
     *
     * <p>Completed pixels stay in the immutable page cache, but queued/in-flight
     * work from the previous view is detached immediately. PASS81 kept hundreds of
     * Layered pages ahead of a newly opened Full viewport, so the Full screen saw
     * only spinners while old Top-Y work consumed CPU, branch and GPU budgets.</p>
     */
    synchronized void activateView(String dimension, CaveView view) {
        if (dimension == null || dimension.isBlank()) return;
        queue.removeIf(request -> dimension.equals(request.key.dimension())
                && (view == null || request.key.view() != view));
        requests.entrySet().removeIf(entry ->
                dimension.equals(entry.getKey().dimension())
                        && (view == null || entry.getKey().view() != view));
        regionQueue.removeIf(request ->
                dimension.equals(request.key.dimension())
                        && (view == null || request.key.view() != view));
        regionRequests.entrySet().removeIf(entry ->
                dimension.equals(entry.getKey().dimension())
                        && (view == null || entry.getKey().view() != view));
        readyPages.removeIf(page -> dimension.equals(page.dimension())
                && (view == null || page.view() != view));
        activePresentationGenerations.entrySet().removeIf(entry -> {
            CavePresentationGeneration generation = entry.getValue();
            return generation == null || dimension.equals(generation.dimension())
                    && (view == null || generation.view() != view);
        });
        /*
         * Old-view CIMG consolidation is optional cache work. Do not let it keep
         * allocating and writing 1 MiB transactions after the user has selected a
         * different projection.
         */
        regions.entrySet().removeIf(entry ->
                dimension.equals(entry.getKey().dimension())
                        && (view == null || entry.getKey().view() != view)
                        && !pendingWrites.contains(entry.getKey()));
        pumpLocked();
    }

    /**
     * Retires queued and in-memory products for obsolete Top-Y projections while
     * preserving the immutable vertical archive and persistent CIMG files.
     *
     * <p>PASS90 pruned the exact atlas pages but left the region projection queues,
     * ready-page queue and region transactions from every previous Layered level
     * alive. Those products continued to occupy CPU-ready/publication capacity, so
     * the first level worked and later levels accumulated hundreds of stranded
     * completions. Xaero keeps one active tile-build interpretation per map view;
     * apply the same ownership boundary here.</p>
     */
    synchronized void activateProjection(String dimension, CaveView view,
            int projectionTopY) {
        if (dimension == null || dimension.isBlank() || view == null) return;
        int canonicalTopY = canonicalTopY(view, projectionTopY);
        int normalizedLayer = DenseCaveTile.normalizeLayer(view, canonicalTopY);

        queue.removeIf(request -> sameDimensionView(request.key, dimension, view)
                && !matchesProjection(request.key, dimension, view,
                        normalizedLayer, canonicalTopY));
        requests.entrySet().removeIf(entry ->
                sameDimensionView(entry.getKey(), dimension, view)
                        && !matchesProjection(entry.getKey(), dimension, view,
                                normalizedLayer, canonicalTopY));
        regionQueue.removeIf(request ->
                sameDimensionView(request.key, dimension, view)
                        && !matchesProjection(request.key, dimension, view,
                                normalizedLayer, canonicalTopY));
        regionRequests.entrySet().removeIf(entry ->
                sameDimensionView(entry.getKey(), dimension, view)
                        && !matchesProjection(entry.getKey(), dimension, view,
                                normalizedLayer, canonicalTopY));
        readyPages.removeIf(page -> dimension.equals(page.dimension())
                && page.view() == view
                && (page.normalizedLayer() != normalizedLayer
                        || page.projectionTopY() != canonicalTopY));

        // Completed immutable pages are an LRU cache, not active ownership.
        // Keep old bands here: returning to a recently visited Top-Y can hand the
        // same source-revision pixels back immediately instead of re-projecting.
        // Queue/request/ready ownership above is still retired, and trimLocked()
        // bounds retained products to MAX_PROJECTED_PAGES.
        regions.entrySet().removeIf(entry ->
                sameDimensionView(entry.getKey(), dimension, view)
                        && !matchesProjection(entry.getKey(), dimension, view,
                                normalizedLayer, canonicalTopY)
                        && !pendingWrites.contains(entry.getKey()));
        pumpLocked();
    }

    private static boolean sameDimensionView(PageKey key, String dimension,
            CaveView view) {
        return key != null && dimension.equals(key.dimension())
                && key.view() == view;
    }

    private static boolean sameDimensionView(RegionBuildKey key,
            String dimension, CaveView view) {
        return key != null && dimension.equals(key.dimension())
                && key.view() == view;
    }

    private static boolean sameDimensionView(RegionKey key, String dimension,
            CaveView view) {
        return key != null && dimension.equals(key.dimension())
                && key.view() == view;
    }

    /**
     * Re-ranks native-region projection ownership around the current fullscreen
     * viewport. Region workers complete asynchronously; without this handoff, pages
     * from an old pan remain in the ready/build queues and can occupy every exact and
     * branch admission while the new screen stays black.
     */
    synchronized void activateViewport(String dimension, CaveView view,
            int projectionTopY, int minPageX, int maxPageX,
            int minPageZ, int maxPageZ, int focusPageX, int focusPageZ,
            MapRequestLane foregroundLane,
            CavePresentationGeneration presentationGeneration) {
        if (dimension == null || dimension.isBlank() || view == null) return;
        MapRequestLane effectiveForegroundLane = foregroundLane == null
                ? MapRequestLane.MINIMAP : foregroundLane;
        int canonicalTopY = canonicalTopY(view, projectionTopY);
        int normalizedLayer = DenseCaveTile.normalizeLayer(view, canonicalTopY);
        if (presentationGeneration != null
                && presentationGeneration.matches(dimension, view,
                        normalizedLayer, canonicalTopY,
                        effectiveForegroundLane)) {
            activePresentationGenerations.put(
                    effectiveForegroundLane, presentationGeneration);
        } else {
            activePresentationGenerations.remove(effectiveForegroundLane);
        }

        /*
         * PASS129: foreground lane ownership is one presentation projection at a
         * time. Switching LAYERED -> FULL (or exact Top-Y within the same lane)
         * previously left the old native-region writer lease alive because the
         * pruning below only inspected keys matching the NEW projection. The old
         * immutable pages then arrived hundreds at a time and were rejected by the
         * consumer. Retire only publication/work ownership here; already projected
         * pages remain in the bounded cache and an in-flight slice may finish into
         * that cache, matching Xaero's written-tile vs loading-window separation.
         */
        int supersededForegroundPages = 0;
        int supersededReadyPruned = 0;
        var supersededIterator = regionRequests.entrySet().iterator();
        while (supersededIterator.hasNext()) {
            Map.Entry<RegionBuildKey, RegionBuildRequest> entry =
                    supersededIterator.next();
            RegionBuildKey oldKey = entry.getKey();
            RegionBuildRequest oldRequest = entry.getValue();
            if (!dimension.equals(oldKey.dimension())
                    || oldRequest.foregroundLane != effectiveForegroundLane
                    || oldRequest.foregroundMask == 0L
                    || matchesProjection(oldKey, dimension, view,
                            normalizedLayer, canonicalTopY)) {
                continue;
            }
            long removed = oldRequest.foregroundMask;
            supersededForegroundPages += Long.bitCount(removed);
            oldRequest.setForegroundMask(0L);
            supersededReadyPruned += pruneReadyForegroundLocked(oldKey, removed);
            oldRequest.presentationRetired = true;
            if (!oldRequest.inFlight) {
                regionQueue.remove(oldRequest);
                supersededIterator.remove();
            }
        }
        if (supersededForegroundPages > 0 || supersededReadyPruned > 0) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent(
                    "CAVE_REGION_SUPERSEDED_PROJECTION_RETIRED", 100L)) {
                recorder.event("CAVE_REGION_SUPERSEDED_PROJECTION_RETIRED",
                        "dimension=" + dimension
                                + " new_view=" + view
                                + " new_top_y=" + canonicalTopY
                                + " lane=" + effectiveForegroundLane
                                + " released_pages=" + supersededForegroundPages
                                + " queued_offers_pruned=" + supersededReadyPruned);
            }
        }

        queue.removeIf(request -> matchesProjection(request.key, dimension, view,
                normalizedLayer, canonicalTopY)
                && outside(request.key.globalPageX(), request.key.globalPageZ(),
                        minPageX, maxPageX, minPageZ, maxPageZ));
        requests.entrySet().removeIf(entry -> {
            Request request = entry.getValue();
            return matchesProjection(entry.getKey(), dimension, view,
                    normalizedLayer, canonicalTopY)
                    && outside(entry.getKey().globalPageX(),
                            entry.getKey().globalPageZ(), minPageX, maxPageX,
                            minPageZ, maxPageZ);
        });

        var regionIterator = regionRequests.entrySet().iterator();
        while (regionIterator.hasNext()) {
            Map.Entry<RegionBuildKey, RegionBuildRequest> entry =
                    regionIterator.next();
            RegionBuildKey key = entry.getKey();
            RegionBuildRequest request = entry.getValue();
            if (!matchesProjection(key, dimension, view,
                    normalizedLayer, canonicalTopY)) {
                continue;
            }
            long viewportMask = viewportMask(key.regionX(), key.regionZ(),
                    minPageX, maxPageX, minPageZ, maxPageZ);
            if (viewportMask == 0L) {
                if (!request.inFlight) regionQueue.remove(request);
                regionIterator.remove();
                continue;
            }
            if (!request.inFlight) regionQueue.remove(request);
            /*
             * Presentation work outside the current screen is disposable. Raw
             * vertical archives remain cached, so retaining old background page
             * masks only lets a previous pan consume projection/branch/disk budgets.
             */
            request.pageMask = viewportMask;
            request.completedMask &= viewportMask;
            request.setForegroundMask(viewportMask);
            /* Publication lane is current viewport ownership, not immutable pixel
             * identity. Update it on MINIMAP <-> FULLSCREEN transitions so queued
             * region offers are relabelled instead of arriving at a dead planner. */
            request.foregroundLane = effectiveForegroundLane;
            request.setForegroundGeneration(activePresentationGeneration(
                    effectiveForegroundLane, key));
            request.focusPageX = focusPageX;
            request.focusPageZ = focusPageZ;
            request.priority = viewportScanlinePriority(key.regionX(), key.regionZ(),
                    minPageX, maxPageX, minPageZ, maxPageZ);
            request.sequence = sequence++;
            request.lastRequestedMs = System.currentTimeMillis();
            request.reconcileSatisfiedPages(repository, pages);
            releaseForegroundBatchLocked(request);
            if (!request.inFlight && request.pendingMask() != 0L) {
                regionQueue.offer(request);
            }
        }

        readyPages.removeIf(page -> page.lane() != MapRequestLane.BACKGROUND
                && page.lane() != MapRequestLane.PREFETCH
                && dimension.equals(page.dimension()) && page.view() == view
                && page.normalizedLayer() == normalizedLayer
                && page.projectionTopY() == canonicalTopY
                && outside(page.globalPageX(), page.globalPageZ(),
                        minPageX, maxPageX, minPageZ, maxPageZ));
        /*
         * CIMG is a derived presentation cache. A dirty transaction that no longer
         * intersects the current viewport must not continue writing 1 MiB snapshots
         * after a pan. The immutable vertical archive remains the recoverable source.
         */
        regions.entrySet().removeIf(entry ->
                matchesProjection(entry.getKey(), dimension, view,
                        normalizedLayer, canonicalTopY)
                        && viewportMask(entry.getKey().regionX(),
                                entry.getKey().regionZ(), minPageX, maxPageX,
                                minPageZ, maxPageZ) == 0L
                        && !pendingWrites.contains(entry.getKey()));
        pumpLocked();
    }

    private static boolean matchesProjection(PageKey key, String dimension,
            CaveView view, int normalizedLayer, int projectionTopY) {
        return key != null && dimension.equals(key.dimension())
                && key.view() == view && key.normalizedLayer() == normalizedLayer
                && key.projectionTopY() == projectionTopY;
    }

    private static boolean matchesProjection(RegionBuildKey key, String dimension,
            CaveView view, int normalizedLayer, int projectionTopY) {
        return key != null && dimension.equals(key.dimension())
                && key.view() == view && key.normalizedLayer() == normalizedLayer
                && key.projectionTopY() == projectionTopY;
    }

    private CavePresentationGeneration activePresentationGeneration(
            MapRequestLane lane, PageKey key) {
        if (key == null) return null;
        return activePresentationGeneration(lane, key.dimension(), key.view(),
                key.normalizedLayer(), key.projectionTopY());
    }

    private CavePresentationGeneration activePresentationGeneration(
            MapRequestLane lane, RegionBuildKey key) {
        if (key == null) return null;
        return activePresentationGeneration(lane, key.dimension(), key.view(),
                key.normalizedLayer(), key.projectionTopY());
    }

    private CavePresentationGeneration activePresentationGeneration(
            MapRequestLane lane, ProjectedPage page) {
        if (page == null) return null;
        return activePresentationGeneration(lane, page.dimension(), page.view(),
                page.normalizedLayer(), page.projectionTopY());
    }

    private CavePresentationGeneration activePresentationGeneration(
            MapRequestLane lane, String dimension, CaveView view,
            int normalizedLayer, int projectionTopY) {
        if (lane == null || lane == MapRequestLane.BACKGROUND
                || lane == MapRequestLane.PREFETCH) return null;
        CavePresentationGeneration generation =
                activePresentationGenerations.get(lane);
        return generation != null && generation.matches(dimension, view,
                normalizedLayer, projectionTopY, lane) ? generation : null;
    }

    private static boolean matchesProjection(RegionKey key, String dimension,
            CaveView view, int normalizedLayer, int projectionTopY) {
        return key != null && dimension.equals(key.dimension())
                && key.view() == view && key.normalizedLayer() == normalizedLayer
                && key.projectionTopY() == projectionTopY;
    }

    private static boolean outside(int pageX, int pageZ,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ) {
        return pageX < minPageX || pageX > maxPageX
                || pageZ < minPageZ || pageZ > maxPageZ;
    }

    private static int viewportScanlinePriority(int regionX, int regionZ,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ) {
        int firstPageX = regionX * CaveLoadHierarchy.PAGES_PER_REGION;
        int firstPageZ = regionZ * CaveLoadHierarchy.PAGES_PER_REGION;
        int visibleX = Math.max(minPageX, firstPageX);
        int visibleZ = Math.max(minPageZ, firstPageZ);
        int width = Math.max(1, maxPageX - minPageX + 1);
        long ordinal = (long) Math.max(0, visibleZ - minPageZ) * width
                + Math.max(0, visibleX - minPageX);
        return MapRequestLane.FULLSCREEN.priorityBase() + 920_000
                - (int) Math.min(880_000L, ordinal * 512L);
    }

    private static long viewportMask(int regionX, int regionZ,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ) {
        long mask = 0L;
        int firstPageX = regionX * CaveLoadHierarchy.PAGES_PER_REGION;
        int firstPageZ = regionZ * CaveLoadHierarchy.PAGES_PER_REGION;
        for (int localZ = 0; localZ < CaveLoadHierarchy.PAGES_PER_REGION; localZ++) {
            int pageZ = firstPageZ + localZ;
            if (pageZ < minPageZ || pageZ > maxPageZ) continue;
            for (int localX = 0; localX < CaveLoadHierarchy.PAGES_PER_REGION; localX++) {
                int pageX = firstPageX + localX;
                if (pageX < minPageX || pageX > maxPageX) continue;
                mask |= 1L << (localZ * CaveLoadHierarchy.PAGES_PER_REGION + localX);
            }
        }
        return mask;
    }

    /**
     * Requests one final clear page from already-imported source authority.
     * Duplicate page requests share one CPU transaction and promote its lane.
     */
    void request(Level level, String dimension, CaveView view, int projectionTopY,
            int globalPageX, int globalPageZ, MapRequestLane lane, int priority,
            long repositoryGeneration) {
        if (level == null || dimension == null || dimension.isBlank() || view == null
                || !repository.isGenerationCurrent(repositoryGeneration)) return;
        int canonicalTopY = canonicalTopY(view, projectionTopY);
        int normalizedLayer = DenseCaveTile.normalizeLayer(view, canonicalTopY);
        long sourceRevision = repository.getPageRevision(
                view, canonicalTopY, globalPageX, globalPageZ);
        if (sourceRevision == 0L) return;
        PageKey key = new PageKey(dimension, view, normalizedLayer,
                canonicalTopY, globalPageX, globalPageZ,
                CaveProjectionStyle.signature());
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        synchronized (this) {
            CavePresentationGeneration presentationGeneration =
                    activePresentationGeneration(effectiveLane, key);
            RegionBuildRequest regionOwner = regionRequestForPageLocked(key);
            if (regionOwner != null) {
                /*
                 * PASS165: region ownership must not turn a stale partial product
                 * into a permanent no-op. If source revision advanced after the
                 * region cached this page, re-arm that exact bit immediately. This
                 * is the direct visible-page escape hatch; the maintenance sweep
                 * below provides the same repair even without another request().
                 */
                ProjectedPage retained = pages.get(key);
                if (retained == null || retained.sourceRevision() != sourceRevision) {
                    int localX = Math.floorMod(globalPageX,
                            CaveLoadHierarchy.PAGES_PER_REGION);
                    int localZ = Math.floorMod(globalPageZ,
                            CaveLoadHierarchy.PAGES_PER_REGION);
                    int ordinal = localZ * CaveLoadHierarchy.PAGES_PER_REGION
                            + localX;
                    long bit = 1L << ordinal;
                    regionOwner.pageMask |= bit;
                    regionOwner.completedMask &= ~bit;
                    regionOwner.offeredForegroundMask &= ~bit;
                    regionOwner.acknowledgedForegroundMask &= ~bit;
                    regionOwner.offeredSourceRevisions[ordinal] = 0L;
                    regionOwner.offeredLaneRanks[ordinal] = 0;
                    regionOwner.lastOfferedMs[ordinal] = 0L;
                    regionOwner.acknowledgedSourceRevisions[ordinal] = 0L;
                    regionOwner.acknowledgedLaneRanks[ordinal] = 0;
                    regionOwner.retryAfterMs = 0L;
                    regionOwner.sequence = sequence++;
                    if (!regionOwner.inFlight) {
                        regionQueue.remove(regionOwner);
                        regionQueue.offer(regionOwner);
                    }
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    String eventKey = "CAVE_REGION_OWNED_STALE_PAGE_REARMED:"
                            + dimension + ':' + view + ':' + canonicalTopY + ':'
                            + globalPageX + ':' + globalPageZ;
                    if (recorder.shouldEmitEvent(eventKey, 250L)) {
                        recorder.event("CAVE_REGION_OWNED_STALE_PAGE_REARMED",
                                "page=" + globalPageX + ',' + globalPageZ
                                        + " view=" + view
                                        + " top_y=" + canonicalTopY
                                        + " cached_revision="
                                        + (retained == null ? 0L
                                                : retained.sourceRevision())
                                        + " current_revision=" + sourceRevision
                                        + " action=rearm_region_projection"
                                        + " pass=PASS165");
                    }
                } else {
                    promoteCachedRegionPagesLocked(regionOwner, 1L
                            << (Math.floorMod(globalPageZ,
                                    CaveLoadHierarchy.PAGES_PER_REGION)
                            * CaveLoadHierarchy.PAGES_PER_REGION
                            + Math.floorMod(globalPageX,
                                    CaveLoadHierarchy.PAGES_PER_REGION)),
                            effectiveLane);
                    releaseForegroundBatchLocked(regionOwner);
                }
                pumpLocked();
                return;
            }
            ProjectedPage cached = pages.get(key);
            if (cached != null && cached.sourceRevision() == sourceRevision) {
                recordProjectionProductReuse(key, effectiveLane, true);
                readyPages.offer(cached.asDirectOffer(
                        effectiveLane, presentationGeneration));
                return;
            }
            Request existing = requests.get(key);
            if (existing != null) {
                if (!existing.inFlight) queue.remove(existing);
                /*
                 * Source fingerprints are scheduling hints, not immutable job IDs.
                 * Coalesce repeated archive commits into the existing page request
                 * and let buildPage select one stable snapshot instead of replacing
                 * the request/future for every arriving chunk.
                 */
                existing.sourceRevision = sourceRevision;
                if (effectiveLane.strongerThan(existing.lane)) {
                    existing.lane = effectiveLane;
                }
                existing.priority = effectiveLane == MapRequestLane.FULLSCREEN
                        ? priority : Math.max(existing.priority, priority);
                existing.sequence = sequence++;
                existing.retryAfterMs = 0L;
                existing.addSubscriber(effectiveLane, presentationGeneration);
                recordProjectionProductReuse(key, effectiveLane, true);
                if (!existing.inFlight) queue.offer(existing);
                pumpLocked();
                return;
            }
            Request request = new Request(key, level, sourceRevision,
                    repositoryGeneration, effectiveLane, priority, sequence++,
                    presentationGeneration);
            recordProjectionProductBuildStarted(key, effectiveLane);
            requests.put(key, request);
            queue.offer(request);
            pumpLocked();
        }
    }


    /**
     * Merges native-region page demand into one bounded CPU transaction. A region
     * build processes a small page slice per scheduler task, stages every finished
     * page into the same CIMG transaction and requeues itself until the requested
     * page mask is complete. This removes sixty-four independent page futures from
     * the cold Full/Layered import path while preserving centre-first publication.
     */
    void requestRegion(Level level, String dimension, CaveView view,
            int projectionTopY, int regionX, int regionZ, long workMask,
            long foregroundMask, int focusPageX, int focusPageZ,
            MapRequestLane lane, int priority, long repositoryGeneration) {
        if (level == null || dimension == null || dimension.isBlank() || view == null
                || (workMask == 0L && foregroundMask == 0L)
                || !repository.isGenerationCurrent(repositoryGeneration)) return;
        int canonicalTopY = canonicalTopY(view, projectionTopY);
        int normalizedLayer = DenseCaveTile.normalizeLayer(view, canonicalTopY);
        RegionBuildKey key = new RegionBuildKey(dimension, view, normalizedLayer,
                canonicalTopY, CaveProjectionStyle.signature(), regionX, regionZ);
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        synchronized (this) {
            long now = System.currentTimeMillis();
            /*
             * PASS169: a source completion from the previous Cave Top-Y can arrive
             * after the fullscreen presentation generation has already moved on.
             * PASS167/168 allowed that late completion to recreate a foreground
             * RegionBuildRequest with a null/old generation, so the producer spent
             * seconds re-offering immutable pages that the texture manager correctly
             * rejected as superseded. The 18:09 trace shows exactly this: after
             * switching -8 -> 17, the old -8 projection emitted 300+ foreground
             * waves and 134 rejected handoffs while the new layer barely progressed.
             *
             * A foreground exact projection is useful only for the active lane
             * generation. Raw/CAV8 source remains retained independently, so drop
             * stale exact projection requests at the producer boundary instead of
             * constructing work that has no consumer.
             */
            if (effectiveLane == MapRequestLane.MINIMAP
                    || effectiveLane == MapRequestLane.FULLSCREEN) {
                CavePresentationGeneration active =
                        activePresentationGenerations.get(effectiveLane);
                if (active != null && !active.matches(dimension, view,
                        normalizedLayer, canonicalTopY, effectiveLane)) {
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    String staleKey = "CAVE_SUPERSEDED_FOREGROUND_REGION_REQUEST_DROPPED:"
                            + effectiveLane + ':' + dimension + ':' + view + ':'
                            + canonicalTopY + ':' + regionX + ':' + regionZ;
                    if (recorder.shouldEmitEvent(staleKey, 500L)) {
                        recorder.event(
                                "CAVE_SUPERSEDED_FOREGROUND_REGION_REQUEST_DROPPED",
                                "region=" + regionX + ',' + regionZ
                                        + " view=" + view
                                        + " top_y=" + canonicalTopY
                                        + " lane=" + effectiveLane
                                        + " active_top_y=" + active.projectionTopY()
                                        + " action=drop_exact_projection_keep_source_cache"
                                        + " policy=single_active_foreground_generation"
                                        + " pass=PASS169");
                    }
                    return;
                }
            }
            RegionBuildRequest request = regionRequests.get(key);
            if (request == null) {
                request = new RegionBuildRequest(key, level, repositoryGeneration,
                        effectiveLane, priority, sequence++);
                regionRequests.put(key, request);
            } else if (!request.inFlight) {
                regionQueue.remove(request);
            }
            boolean foregroundRequest = effectiveLane == MapRequestLane.MINIMAP
                    || effectiveLane == MapRequestLane.FULLSCREEN;
            boolean retainedFullscreenPresentation =
                    effectiveLane == MapRequestLane.FULLSCREEN;
            /*
             * PASS162: FULLSCREEN source windows are bounded, but presentation is
             * viewport-lived. PASS161 replaced pageMask/foregroundMask with every
             * newly ready 32-page writer slice. That silently revoked CPU/GPU
             * ownership from earlier source-settled pages before the strict scanline
             * reached them. The result was deterministic: a few top rows appeared,
             * then frontier=65/209 with source_resolved=16 and no runnable owner.
             *
             * Xaero's writer cursor moves independently from its loaded/loading map
             * product. Mirror that split: FULLSCREEN accumulates projection leases
             * inside the active viewport; activateViewport() remains the authority
             * that clips them when the actual viewport/projection changes. MINIMAP
             * keeps replacement semantics so its 5x5 working set stays bounded.
             */
            if (retainedFullscreenPresentation) {
                request.presentationRetired = false;
                request.pageMask |= workMask;
            } else if (foregroundRequest) {
                request.presentationRetired = false;
                request.pageMask = (request.pageMask & foregroundMask) | workMask;
            } else {
                request.pageMask |= workMask;
            }
            request.lastRequestedMs = now;
            if (foregroundRequest) {
                long previousForegroundMask = request.foregroundMask;
                long targetForegroundMask = retainedFullscreenPresentation
                        ? previousForegroundMask | foregroundMask
                        : foregroundMask;
                long removedForegroundMask = previousForegroundMask
                        & ~targetForegroundMask;
                request.setForegroundMask(targetForegroundMask);
                if (!retainedFullscreenPresentation
                        && removedForegroundMask != 0L) {
                    int pruned = pruneReadyForegroundLocked(
                            request.key, removedForegroundMask);
                    if (pruned > 0) {
                        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                        String eventKey = "CAVE_REGION_FOREGROUND_READY_PRUNED:"
                                + request.key;
                        if (recorder.shouldEmitEvent(eventKey, 250L)) {
                            recorder.event("CAVE_REGION_FOREGROUND_READY_PRUNED",
                                    "region=" + regionX + ',' + regionZ
                                            + " view=" + view
                                            + " top_y=" + canonicalTopY
                                            + " pages=" + pruned
                                            + " policy=current_writer_window");
                        }
                    }
                }
                if (retainedFullscreenPresentation
                        && targetForegroundMask != foregroundMask) {
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    String eventKey = "CAVE_FULLSCREEN_PRESENTATION_MASK_RETAINED:"
                            + request.key;
                    if (recorder.shouldEmitEvent(eventKey, 500L)) {
                        recorder.event("CAVE_FULLSCREEN_PRESENTATION_MASK_RETAINED",
                                "region=" + regionX + ',' + regionZ
                                        + " view=" + view
                                        + " top_y=" + canonicalTopY
                                        + " retained_pages="
                                        + Long.bitCount(targetForegroundMask)
                                        + " incoming_pages="
                                        + Long.bitCount(foregroundMask)
                                        + " policy=viewport_lifetime_not_source_window"
                                        + " pass=PASS162");
                    }
                }
                /*
                 * Foreground handoff ownership follows the current native-import
                 * lease, not the historically strongest lane. Keeping MINIMAP here
                 * after ownership moved to FULLSCREEN can label every re-offer for a
                 * planner that no longer exists and recreate the same lost-handoff
                 * hole PASS113 is designed to eliminate. Build scheduling may retain
                 * its stronger lane independently below.
                 */
                request.foregroundLane = effectiveLane;
                request.setForegroundGeneration(activePresentationGeneration(
                        effectiveLane, key));
                promoteCachedRegionPagesLocked(
                        request, targetForegroundMask, effectiveLane);
            }
            request.focusPageX = focusPageX;
            request.focusPageZ = focusPageZ;
            if (effectiveLane.strongerThan(request.lane)) request.lane = effectiveLane;
            request.priority = effectiveLane == MapRequestLane.FULLSCREEN
                    ? priority : Math.max(request.priority, priority);
            request.sequence = sequence++;
            request.reconcileSatisfiedPages(repository, pages);
            releaseForegroundBatchLocked(request);
            if (request.pendingMask() != 0L && !request.inFlight) {
                regionQueue.offer(request);
            } else if (request.pendingMask() == 0L
                    && !request.keepAlive(now)) {
                regionRequests.remove(key, request);
            }
            pumpLocked();
        }
    }

    /**
     * Drops queued foreground offers that have left the writer window before the
     * texture manager sees them. The immutable projected page remains in the LRU and
     * can be promoted again later. Xaero similarly keeps written tiles but only
     * publishes children belonging to the current loading window.
     */
    private int pruneReadyForegroundLocked(RegionBuildKey key, long removedMask) {
        if (key == null || removedMask == 0L || readyPages.isEmpty()) return 0;
        int before = readyPages.size();
        readyPages.removeIf(page -> {
            if (page == null || page.lane() == MapRequestLane.BACKGROUND
                    || page.lane() == MapRequestLane.PREFETCH
                    || !page.dimension().equals(key.dimension())
                    || page.view() != key.view()
                    || page.normalizedLayer() != key.normalizedLayer()
                    || page.projectionTopY() != key.projectionTopY()) {
                return false;
            }
            if (Math.floorDiv(page.globalPageX(), CaveLoadHierarchy.PAGES_PER_REGION)
                            != key.regionX()
                    || Math.floorDiv(page.globalPageZ(), CaveLoadHierarchy.PAGES_PER_REGION)
                            != key.regionZ()) {
                return false;
            }
            int localX = Math.floorMod(page.globalPageX(),
                    CaveLoadHierarchy.PAGES_PER_REGION);
            int localZ = Math.floorMod(page.globalPageZ(),
                    CaveLoadHierarchy.PAGES_PER_REGION);
            long bit = 1L << (localZ * CaveLoadHierarchy.PAGES_PER_REGION + localX);
            return (removedMask & bit) != 0L;
        });
        return Math.max(0, before - readyPages.size());
    }

    synchronized ProjectedPage pollReady() {
        int stalePruned = 0;
        long now = System.currentTimeMillis();
        ProjectedPage page;
        while ((page = readyPages.poll()) != null) {
            long current = repository.getPageRevision(page.view(),
                    page.projectionTopY(), page.globalPageX(),
                    page.globalPageZ());
            if (current == 0L || current != page.sourceRevision()) {
                stalePruned++;
                continue;
            }

            if (page.lane() == MapRequestLane.BACKGROUND
                    || page.lane() == MapRequestLane.PREFETCH) {
                if (stalePruned > 0) recordReadyQueuePrune(stalePruned);
                return page;
            }

            /*
             * A direct request can legitimately publish a retained page without a
             * native-region writer lease. Only offers released by the 8x8 native
             * region writer are fenced by its current writer window; the offer
             * provenance travels with scheduling metadata, not pixel identity.
             */
            if (!page.regionOffer()) {
                if (stalePruned > 0) recordReadyQueuePrune(stalePruned);
                ProjectedPage offered = page.withPresentationGeneration(
                        activePresentationGeneration(page.lane(), page));
                recordHandoffOffer(offered);
                return offered;
            }

            RegionBuildKey regionKey = new RegionBuildKey(page.dimension(),
                    page.view(), page.normalizedLayer(), page.projectionTopY(),
                    CaveProjectionStyle.signature(),
                    Math.floorDiv(page.globalPageX(),
                            CaveLoadHierarchy.PAGES_PER_REGION),
                    Math.floorDiv(page.globalPageZ(),
                            CaveLoadHierarchy.PAGES_PER_REGION));
            RegionBuildRequest request = regionRequests.get(regionKey);
            if (request == null || !request.keepAlive(now)) {
                stalePruned++;
                continue;
            }
            int localX = Math.floorMod(page.globalPageX(),
                    CaveLoadHierarchy.PAGES_PER_REGION);
            int localZ = Math.floorMod(page.globalPageZ(),
                    CaveLoadHierarchy.PAGES_PER_REGION);
            int ordinal = localZ * CaveLoadHierarchy.PAGES_PER_REGION + localX;
            long bit = 1L << ordinal;
            if ((request.foregroundMask & bit) == 0L) {
                stalePruned++;
                continue;
            }

            // The queued page may predate a MINIMAP <-> FULLSCREEN ownership
            // handoff. Publication lane is current writer-window metadata, not
            // immutable pixel identity, so relabel it here instead of forcing the
            // consumer to reject a valid retained page.
            MapRequestLane currentLane = request.foregroundLane == null
                    ? page.lane() : request.foregroundLane;
            CavePresentationGeneration currentGeneration =
                    activePresentationGeneration(currentLane, page);
            if (currentGeneration != null) {
                request.setForegroundGeneration(currentGeneration);
            }
            if (stalePruned > 0) recordReadyQueuePrune(stalePruned);
            ProjectedPage offered = page.asRegionOffer(
                    currentLane, currentGeneration);
            recordHandoffOffer(offered);
            return offered;
        }
        if (stalePruned > 0) recordReadyQueuePrune(stalePruned);
        return null;
    }

    private void recordReadyQueuePrune(int count) {
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (recorder.shouldEmitEvent(
                "CAVE_REGION_FOREGROUND_QUEUE_STALE_PRUNED", 250L)) {
            recorder.event("CAVE_REGION_FOREGROUND_QUEUE_STALE_PRUNED",
                    "pages=" + count + " policy=producer_writer_window");
        }
    }

    private static void recordHandoffOffer(ProjectedPage page) {
        if (page == null || page.lane() == MapRequestLane.BACKGROUND
                || page.lane() == MapRequestLane.PREFETCH) return;
        com.velorise.simplemap.client.MapPipelineTelemetry.getInstance()
                .recordCaveHandoffOffered();
    }

    /**
     * Acknowledges that the foreground consumer retained a projected page in its
     * presentation pipeline. Merely polling readyPages is deliberately not an ACK:
     * a mode switch / viewport rebase can reject a perfectly valid cached page after
     * it has left this queue. Xaero keeps MapTile/MapTileChunk source resident and
     * can rebuild publication from it; this ACK/re-offer contract gives our retained
     * projected-page cache the same non-one-shot behavior.
     */
    synchronized void acknowledgeForeground(ProjectedPage page) {
        if (page == null || page.lane() == MapRequestLane.BACKGROUND
                || page.lane() == MapRequestLane.PREFETCH) return;
        RegionBuildKey regionKey = new RegionBuildKey(page.dimension(), page.view(),
                page.normalizedLayer(), page.projectionTopY(),
                CaveProjectionStyle.signature(),
                Math.floorDiv(page.globalPageX(),
                        CaveLoadHierarchy.PAGES_PER_REGION),
                Math.floorDiv(page.globalPageZ(),
                        CaveLoadHierarchy.PAGES_PER_REGION));
        RegionBuildRequest request = regionRequests.get(regionKey);
        if (request == null) return;
        /* Pixel/source identity is independent from the presentation subscriber.
         * A page polled by generation N may enter retained staging after generation
         * N+1 attaches to the same content product. Exact source and writer-window
         * checks below are the authority fence; the old ticket must not waste the
         * completed product. */
        int localX = Math.floorMod(page.globalPageX(),
                CaveLoadHierarchy.PAGES_PER_REGION);
        int localZ = Math.floorMod(page.globalPageZ(),
                CaveLoadHierarchy.PAGES_PER_REGION);
        int ordinal = localZ * CaveLoadHierarchy.PAGES_PER_REGION + localX;
        long bit = 1L << ordinal;
        if ((request.foregroundMask & bit) == 0L) return;
        long current = repository.getPageRevision(page.view(),
                page.projectionTopY(), page.globalPageX(), page.globalPageZ());
        if (current == 0L || current != page.sourceRevision()) return;
        ProjectedPage retained = readyForegroundPageLocked(request, ordinal);
        if (retained == null || retained.sourceRevision() != page.sourceRevision()) {
            return;
        }
        int rank = page.lane().rank();
        request.acknowledgedForegroundMask |= bit;
        request.acknowledgedSourceRevisions[ordinal] = page.sourceRevision();
        if (Byte.toUnsignedInt(request.acknowledgedLaneRanks[ordinal]) < rank) {
            request.acknowledgedLaneRanks[ordinal] = (byte) rank;
        }
    }

    /**
     * Revokes one stale foreground offer without discarding its retained projected
     * pixels. A rejected offer means the producer's old viewport lease is no longer
     * presentation authority; repeatedly re-offering it every 250 ms only creates a
     * producer/consumer ping-pong. The next real viewport/source lease can set the
     * bit again and immediately promote the retained page.
     */
    synchronized void rejectForeground(ProjectedPage page) {
        if (page == null || page.lane() == MapRequestLane.BACKGROUND
                || page.lane() == MapRequestLane.PREFETCH) return;
        RegionBuildKey regionKey = new RegionBuildKey(page.dimension(), page.view(),
                page.normalizedLayer(), page.projectionTopY(),
                CaveProjectionStyle.signature(),
                Math.floorDiv(page.globalPageX(),
                        CaveLoadHierarchy.PAGES_PER_REGION),
                Math.floorDiv(page.globalPageZ(),
                        CaveLoadHierarchy.PAGES_PER_REGION));
        RegionBuildRequest request = regionRequests.get(regionKey);
        if (request == null) return;
        /* A delayed rejection from a superseded loading generation must never
         * revoke the current generation's foreground bit for the same page. */
        if (page.presentationGeneration() != null
                && !page.presentationGeneration().equals(
                        request.foregroundGeneration)) return;
        int localX = Math.floorMod(page.globalPageX(),
                CaveLoadHierarchy.PAGES_PER_REGION);
        int localZ = Math.floorMod(page.globalPageZ(),
                CaveLoadHierarchy.PAGES_PER_REGION);
        int ordinal = localZ * CaveLoadHierarchy.PAGES_PER_REGION + localX;
        long bit = 1L << ordinal;
        request.foregroundMask &= ~bit;
        request.offeredForegroundMask &= ~bit;
        request.acknowledgedForegroundMask &= ~bit;
        request.offeredSourceRevisions[ordinal] = 0L;
        request.offeredLaneRanks[ordinal] = 0;
        request.lastOfferedMs[ordinal] = 0L;
        request.acknowledgedSourceRevisions[ordinal] = 0L;
        request.acknowledgedLaneRanks[ordinal] = 0;
        // Purge duplicate offers for the same immutable revision that may already
        // have been inserted by a prior lease pulse. The page itself remains in
        // pages/region transaction and is therefore instantly reusable.
        readyPages.removeIf(candidate -> candidate.sourceRevision()
                        == page.sourceRevision()
                && candidate.globalPageX() == page.globalPageX()
                && candidate.globalPageZ() == page.globalPageZ()
                && candidate.projectionTopY() == page.projectionTopY()
                && candidate.view() == page.view()
                && candidate.dimension().equals(page.dimension()));
    }

    /**
     * Retires the foreground publication lease for a native region that has left
     * the current writer window. Projected pages remain in the bounded LRU and any
     * already-running region slice may finish into that retained cache, but it can
     * no longer re-offer stale pages to the exact/branch presentation pipeline.
     * This mirrors Xaero moving its loading window without deleting written tiles.
     */
    synchronized int retireForegroundRegion(String dimension, CaveView view,
            int projectionTopY, int regionX, int regionZ) {
        if (dimension == null || dimension.isBlank() || view == null) return 0;
        int canonicalTopY = canonicalTopY(view, projectionTopY);
        RegionBuildKey key = new RegionBuildKey(dimension, view,
                DenseCaveTile.normalizeLayer(view, canonicalTopY), canonicalTopY,
                CaveProjectionStyle.signature(), regionX, regionZ);
        RegionBuildRequest request = regionRequests.get(key);
        if (request == null || request.foregroundMask == 0L) return 0;
        long removed = request.foregroundMask;
        request.setForegroundMask(0L);
        int pruned = pruneReadyForegroundLocked(key, removed);
        if (!request.inFlight && request.pendingMask() == 0L) {
            regionQueue.remove(request);
            regionRequests.remove(key, request);
        }
        int released = Long.bitCount(removed);
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        String eventKey = "CAVE_REGION_FOREGROUND_REGION_RETIRED:" + key;
        if (recorder.shouldEmitEvent(eventKey, 250L)) {
            recorder.event("CAVE_REGION_FOREGROUND_REGION_RETIRED",
                    "region=" + regionX + ',' + regionZ
                            + " view=" + view
                            + " top_y=" + canonicalTopY
                            + " released_pages=" + released
                            + " queued_offers_pruned=" + pruned
                            + " policy=writer_window_exit");
        }
        return Math.max(released, pruned);
    }

    /**
     * True while the local-world region transaction owns final CPU projection for
     * this exact page. The GPU manager uses this as a single-writer fence and does
     * not launch a second resolve/style future for the same source revision.
     */
    synchronized boolean owns(CaveView view, int projectionTopY,
            int globalPageX, int globalPageZ, String dimension) {
        int canonicalTopY = canonicalTopY(view, projectionTopY);
        PageKey key = new PageKey(dimension, view,
                DenseCaveTile.normalizeLayer(view, canonicalTopY), canonicalTopY,
                globalPageX, globalPageZ, CaveProjectionStyle.signature());
        long current = repository.getPageRevision(
                view, canonicalTopY, globalPageX, globalPageZ);
        if (current == 0L) return false;
        Request request = requests.get(key);
        if (request != null
                && repository.isGenerationCurrent(request.repositoryGeneration)) {
            return true;
        }
        RegionBuildKey regionKey = new RegionBuildKey(dimension, view,
                key.normalizedLayer(), canonicalTopY, key.styleSignature(),
                Math.floorDiv(globalPageX, CaveLoadHierarchy.PAGES_PER_REGION),
                Math.floorDiv(globalPageZ, CaveLoadHierarchy.PAGES_PER_REGION));
        RegionBuildRequest regionRequest = regionRequests.get(regionKey);
        if (regionRequest != null && regionRequest.owns(globalPageX, globalPageZ)) {
            return true;
        }
        // A retained ProjectedPage is reusable data, not active writer ownership.
        // requestRegion() promotes retained pages when the viewport asks for them.
        // Treating the cache entry itself as ownership can suppress the direct exact
        // path forever after the native writer lease has expired.
        return false;
    }


    /**
     * Pure liveness probe for the fullscreen reveal watchdog. This deliberately
     * reports runnable projection work, not retained ownership: a completed stale
     * region bit used to return "owned" forever even though no CPU task could ever
     * advance it.
     */
    synchronized boolean hasPendingProjectionWork(CaveView view,
            int projectionTopY, int globalPageX, int globalPageZ,
            String dimension) {
        if (view == null || dimension == null || dimension.isBlank()) return false;
        int canonicalTopY = canonicalTopY(view, projectionTopY);
        int normalizedLayer = DenseCaveTile.normalizeLayer(view, canonicalTopY);
        PageKey key = new PageKey(dimension, view, normalizedLayer,
                canonicalTopY, globalPageX, globalPageZ,
                CaveProjectionStyle.signature());
        Request direct = requests.get(key);
        if (direct != null) return true;
        RegionBuildKey regionKey = new RegionBuildKey(dimension, view,
                normalizedLayer, canonicalTopY, CaveProjectionStyle.signature(),
                Math.floorDiv(globalPageX, CaveLoadHierarchy.PAGES_PER_REGION),
                Math.floorDiv(globalPageZ, CaveLoadHierarchy.PAGES_PER_REGION));
        RegionBuildRequest region = regionRequests.get(regionKey);
        if (region == null || region.presentationRetired) return false;
        int localX = Math.floorMod(globalPageX,
                CaveLoadHierarchy.PAGES_PER_REGION);
        int localZ = Math.floorMod(globalPageZ,
                CaveLoadHierarchy.PAGES_PER_REGION);
        long bit = 1L << (localZ * CaveLoadHierarchy.PAGES_PER_REGION + localX);
        return (region.pendingMask() & bit) != 0L
                || region.inFlight && (region.pageMask & bit) != 0L;
    }

    synchronized ProjectedPage find(CaveView view, int projectionTopY,
            int globalPageX, int globalPageZ, String dimension) {
        int canonicalTopY = canonicalTopY(view, projectionTopY);
        PageKey key = new PageKey(dimension, view,
                DenseCaveTile.normalizeLayer(view, canonicalTopY), canonicalTopY,
                globalPageX, globalPageZ, CaveProjectionStyle.signature());
        ProjectedPage page = pages.get(key);
        if (page == null) return null;
        long current = repository.getPageRevision(
                view, canonicalTopY, globalPageX, globalPageZ);
        return current != 0L && current == page.sourceRevision() ? page : null;
    }

    synchronized void maintain() {
        long now = System.currentTimeMillis();
        /*
         * PASS165: source and projection have independent completion times. A
         * partial page can be valid at revision N, then become stale when the last
         * source child commits revision N+1 after the writer window has moved on.
         * Previously completedMask stayed set forever, leaving no runnable CPU work
         * while the fullscreen frontier waited on that stale page. Minimap appeared
         * to "fix" it only because opening/closing a subscriber called
         * reconcileSatisfiedPages() again. Keep the active foreground product
         * self-progressing instead.
         */
        reconcileForegroundRevisionDriftLocked(now);
        pruneCompletedRegionRequestsLocked(now);
        pumpLocked();
        scheduleRegionWriteLocked(now);
        trimLocked();
    }

    private void reconcileForegroundRevisionDriftLocked(long now) {
        int inspected = 0;
        int invalidatedPages = 0;
        for (RegionBuildRequest request : regionRequests.values()) {
            if (inspected >= FOREGROUND_REVISION_RECONCILE_BUDGET) break;
            if (request == null || request.presentationRetired
                    || request.foregroundMask == 0L
                    || now - request.lastRevisionReconcileMs
                            < FOREGROUND_REVISION_RECONCILE_INTERVAL_MS) {
                continue;
            }
            request.lastRevisionReconcileMs = now;
            inspected++;

            long completedBefore = request.completedMask;
            request.reconcileSatisfiedPages(repository, pages);
            long invalidated = completedBefore & ~request.completedMask
                    & request.foregroundMask;
            if (invalidated != 0L) {
                invalidatedPages += Long.bitCount(invalidated);
                request.pageMask |= invalidated;
                request.retryAfterMs = 0L;
                request.sequence = sequence++;
                if (!request.inFlight) {
                    regionQueue.remove(request);
                    regionQueue.offer(request);
                }
            }
            /* Valid retained pages may have been waiting only for a subscriber
             * handoff. Re-offer them from the same product without rebuilding. */
            releaseForegroundBatchLocked(request);
        }
        if (invalidatedPages > 0) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent(
                    "CAVE_FOREGROUND_PROJECTION_REVISION_REARMED", 100L)) {
                recorder.event("CAVE_FOREGROUND_PROJECTION_REVISION_REARMED",
                        "pages=" + invalidatedPages
                                + " action=reproject_current_source_revision"
                                + " policy=foreground_product_self_progresses"
                                + " pass=PASS165");
            }
        }
    }

    synchronized DebugSnapshot debugSnapshot() {
        int dirty = 0;
        for (RegionTransaction region : regions.values()) {
            if (region.dirty) dirty++;
        }
        return new DebugSnapshot(requests.size(), activeBuilds,
                regionRequests.size(), activeRegionBuilds,
                readyPages.size(), pages.size(), regions.size(), dirty,
                pendingWrites.size());
    }

    private void pumpLocked() {
        pumpRegionBuildsLocked();
        int maximum = MapPerformanceGovernor.getInstance().underPressure()
                ? 2 : MAX_ACTIVE_BUILDS;
        long now = System.currentTimeMillis();
        int examined = 0;
        int scanLimit = Math.max(16, queue.size());
        while (activeBuilds < maximum && !queue.isEmpty()
                && examined++ < scanLimit) {
            Request request = queue.poll();
            if (request == null || requests.get(request.key) != request
                    || request.inFlight) continue;
            if (request.retryAfterMs > now) {
                queue.offer(request);
                continue;
            }
            if (!isCurrent(request)) {
                requests.remove(request.key, request);
                continue;
            }
            request.inFlight = true;
            activeBuilds++;
            long submittedEpoch = epoch;
            CompletableFuture<ProjectedPage> future =
                    MapWorkScheduler.tryCpuFuture(request.lane,
                            MapWorkScheduler.WorkType.REGION_PROJECTION,
                            request.priority, 14,
                            () -> submittedEpoch == epoch && isCurrent(request),
                            () -> build(request));
            if (future == null) {
                request.inFlight = false;
                activeBuilds--;
                request.retryAfterMs = now + 16L;
                queue.offer(request);
                break;
            }
            future.whenComplete((page, failure) -> complete(
                    request, submittedEpoch, page, failure));
        }
        scheduleRegionWriteLocked(now);
    }

    private ProjectedPage build(Request request) {
        return buildPage(request.level, request.key, request.sourceRevision,
                request.lane, request.priority, request.sequence, false);
    }

    private ProjectedPage buildPage(Level level, PageKey key,
            long expectedSourceRevision, MapRequestLane lane, int pagePriority,
            long pageSequence, boolean regionOffer) {
        /*
         * PASS142: a 64x64 page is the GPU packing unit, not the minimum CPU/source
         * publication unit. The repository masks incomplete data to whole 16x16
         * Minecraft children via coherentChunkRows(), and the texture manager stages
         * those children into a retained page. Requiring 16/16 here turned every slow
         * source child into a black 64x64 island and made PASS141's direct exact
         * projection path ineffective in the default native importer.
         */
        /*
         * The archive is populated chunk-by-chunk. Bind the projection to the
         * newest coherent page snapshot, not to the fingerprint observed when the
         * scheduler admitted the task. Two bounded attempts absorb the normal last
         * archive commit without turning source churn into an unbounded worker loop.
         */
        for (int attempt = 0; attempt < 2; attempt++) {
            CaveTileRepository.ResolvedPage resolved = repository.resolvePage(
                    key.view(), key.projectionTopY(), level,
                    key.globalPageX(), key.globalPageZ());
            if (resolved == null || resolved.knownColumnCount() <= 0) {
                return null;
            }
            long[] knownRows = coherentChunkRows(resolved.knownRows());
            int knownColumns = countKnownColumns(knownRows);
            if (knownColumns <= 0) return null;
            boolean complete = knownColumns == CaveTextureAtlas.PAGE_SIZE
                    * CaveTextureAtlas.PAGE_SIZE;
            long sourceBeforeStyle = repository.getPageRevision(
                    key.view(), key.projectionTopY(),
                    key.globalPageX(), key.globalPageZ());
            if (sourceBeforeStyle == 0L
                    || resolved.revision() != sourceBeforeStyle) {
                continue;
            }
            int[] styled = CavePageStyler.style(
                    resolved.pixels(), resolved.heights(), resolved.topHeights(),
                    resolved.flags(), resolved.light(), resolved.overlayCounts(),
                    resolved.overlayColors(), resolved.overlayAlpha(),
                    resolved.overlayY(), resolved.overlayLight(),
                    resolved.overlayFlags(), key.view(), key.projectionTopY());
            long sourceAfterStyle = repository.getPageRevision(
                    key.view(), key.projectionTopY(),
                    key.globalPageX(), key.globalPageZ());
            if (sourceAfterStyle == 0L
                    || sourceAfterStyle != sourceBeforeStyle) {
                continue;
            }
            if (!complete) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String eventKey = "CAVE_REGION_PARTIAL_CHILD_PUBLICATION:"
                        + key.view() + ':' + key.projectionTopY() + ':'
                        + key.globalPageX() + ':' + key.globalPageZ();
                if (recorder.shouldEmitEvent(eventKey, 500L)) {
                    recorder.event("CAVE_REGION_PARTIAL_CHILD_PUBLICATION",
                            "page=" + key.globalPageX() + ',' + key.globalPageZ()
                                    + " view=" + key.view()
                                    + " top_y=" + key.projectionTopY()
                                    + " known_columns=" + knownColumns
                                    + " complete_children=" + (knownColumns / 256));
                }
            }
            return new ProjectedPage(key.dimension(), key.view(),
                    key.normalizedLayer(), key.projectionTopY(),
                    key.globalPageX(), key.globalPageZ(), sourceAfterStyle,
                    styled, knownRows, knownColumns, complete,
                    resolved.emptyProof(),
                    lane, pagePriority, pageSequence, regionOffer, null);
        }
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        String eventKey = "CAVE_PAGE_SOURCE_COALESCE_RETRY:"
                + key.globalPageX() + ':' + key.globalPageZ() + ':' + key.view();
        if (recorder.shouldEmitEvent(eventKey, 250L)) {
            recorder.event("CAVE_PAGE_SOURCE_COALESCE_RETRY",
                    "page=" + key.globalPageX() + ',' + key.globalPageZ()
                            + " view=" + key.view()
                            + " scheduled_source=" + expectedSourceRevision);
        }
        return null;
    }

    /**
     * Converts arbitrary column readiness into Xaero-style child readiness. A child
     * contributes only when all 16x16 columns of that Minecraft chunk are known.
     * This lets generated chunks appear independently while preventing sparse columns
     * from being published as random islands.
     */
    private static long[] coherentChunkRows(long[] sourceRows) {
        long[] result = new long[CaveTextureAtlas.PAGE_SIZE];
        if (sourceRows == null || sourceRows.length < CaveTextureAtlas.PAGE_SIZE) {
            return result;
        }
        for (int childZ = 0; childZ < 4; childZ++) {
            int rowStart = childZ << 4;
            for (int childX = 0; childX < 4; childX++) {
                int bitStart = childX << 4;
                long childMask = 0xFFFFL << bitStart;
                boolean childComplete = true;
                for (int row = rowStart; row < rowStart + 16; row++) {
                    if ((sourceRows[row] & childMask) != childMask) {
                        childComplete = false;
                        break;
                    }
                }
                if (!childComplete) continue;
                for (int row = rowStart; row < rowStart + 16; row++) {
                    result[row] |= childMask;
                }
            }
        }
        return result;
    }

    private static int countKnownColumns(long[] knownRows) {
        int count = 0;
        if (knownRows == null) return 0;
        for (long row : knownRows) count += Long.bitCount(row);
        return count;
    }

    private void pumpRegionBuildsLocked() {
        boolean pressure = MapPerformanceGovernor.getInstance().underPressure();
        int maximum = pressure ? 1 : MAX_ACTIVE_REGION_BUILDS;
        long now = System.currentTimeMillis();
        int examined = 0;
        int scanLimit = Math.max(8, regionQueue.size());
        while (activeRegionBuilds < maximum && !regionQueue.isEmpty()
                && examined++ < scanLimit) {
            RegionBuildRequest request = regionQueue.poll();
            if (request == null || regionRequests.get(request.key) != request
                    || request.inFlight) continue;
            if (request.retryAfterMs > now) {
                regionQueue.offer(request);
                continue;
            }
            if (!pressure && activeRegionBuildsForLaneLocked(request.lane)
                    >= maxActiveRegionBuilds(request.lane)) {
                regionQueue.offer(request);
                continue;
            }
            request.reconcileSatisfiedPages(repository, pages);
            if (!repository.isGenerationCurrent(request.repositoryGeneration)) {
                regionRequests.remove(request.key, request);
                continue;
            }
            if (request.pendingMask() == 0L) {
                if (!request.keepAlive(now)) {
                    regionRequests.remove(request.key, request);
                }
                continue;
            }
            int sliceLimit = pressure ? Math.min(8, regionPageSlice(request.lane))
                    : regionPageSlice(request.lane);
            request.inFlight = true;
            activeRegionBuilds++;
            long submittedEpoch = epoch;
            CompletableFuture<RegionBuildSlice> future =
                    MapWorkScheduler.tryCpuFuture(request.lane,
                            MapWorkScheduler.WorkType.REGION_PROJECTION,
                            request.priority, sliceLimit,
                            () -> submittedEpoch == epoch
                                    && repository.isGenerationCurrent(
                                            request.repositoryGeneration)
                                    && regionRequests.get(request.key) == request,
                            () -> buildRegionSlice(request, sliceLimit));
            if (future == null) {
                request.inFlight = false;
                activeRegionBuilds--;
                request.retryAfterMs = now + 16L;
                regionQueue.offer(request);
                break;
            }
            future.whenComplete((slice, failure) ->
                    completeRegionSlice(request, submittedEpoch, slice, failure));
        }
    }

    private int activeRegionBuildsForLaneLocked(MapRequestLane lane) {
        int active = 0;
        MapRequestLane effective = lane == null ? MapRequestLane.FULLSCREEN : lane;
        for (RegionBuildRequest request : regionRequests.values()) {
            if (!request.inFlight) continue;
            MapRequestLane requestLane = request.lane == null
                    ? MapRequestLane.FULLSCREEN : request.lane;
            if (requestLane == effective) active++;
        }
        return active;
    }

    private static int maxActiveRegionBuilds(MapRequestLane lane) {
        if (lane == MapRequestLane.MINIMAP) return MAX_ACTIVE_MINIMAP_REGION_BUILDS;
        if (lane == MapRequestLane.BACKGROUND) return MAX_ACTIVE_BACKGROUND_REGION_BUILDS;
        return MAX_ACTIVE_REGION_BUILDS;
    }

    private static int regionPageSlice(MapRequestLane lane) {
        if (lane == MapRequestLane.MINIMAP) return MINIMAP_REGION_PAGE_SLICE;
        if (lane == MapRequestLane.BACKGROUND) return BACKGROUND_REGION_PAGE_SLICE;
        return FULLSCREEN_REGION_PAGE_SLICE;
    }

    private RegionBuildSlice buildRegionSlice(RegionBuildRequest request,
            int sliceLimit) {
        long pending = request.pendingMask();
        if (pending == 0L) return new RegionBuildSlice(List.of(), 0L, 0L);
        int boundedSlice = Math.max(1, sliceLimit);
        List<Integer> ordinals = request.orderedPendingOrdinals();
        List<ProjectedPage> built = new java.util.ArrayList<>(boundedSlice);
        long attempted = 0L;
        long incomplete = 0L;
        int count = 0;
        for (int ordinal : ordinals) {
            if (count++ >= boundedSlice) break;
            long bit = 1L << ordinal;
            attempted |= bit;
            int pageX = request.key.regionX() * CaveLoadHierarchy.PAGES_PER_REGION
                    + ordinal % CaveLoadHierarchy.PAGES_PER_REGION;
            int pageZ = request.key.regionZ() * CaveLoadHierarchy.PAGES_PER_REGION
                    + ordinal / CaveLoadHierarchy.PAGES_PER_REGION;
            PageKey pageKey = new PageKey(request.key.dimension(),
                    request.key.view(), request.key.normalizedLayer(),
                    request.key.projectionTopY(), pageX, pageZ,
                    request.key.styleSignature());
            long sourceRevision = repository.getPageRevision(
                    pageKey.view(), pageKey.projectionTopY(), pageX, pageZ);
            MapRequestLane pageLane = request.pageLane(ordinal);
            ProjectedPage page = sourceRevision == 0L ? null
                    : buildPage(request.level, pageKey, sourceRevision,
                            pageLane, request.pagePriority(ordinal),
                            request.sequence + ordinal, true);
            if (page == null) incomplete |= bit;
            else built.add(page);
        }
        return new RegionBuildSlice(List.copyOf(built), attempted, incomplete);
    }

    private void completeRegionSlice(RegionBuildRequest request,
            long submittedEpoch, RegionBuildSlice slice, Throwable failure) {
        synchronized (this) {
            activeRegionBuilds = Math.max(0, activeRegionBuilds - 1);
            request.inFlight = false;
            if (submittedEpoch != epoch) {
                pumpLocked();
                return;
            }
            if (failure != null || !repository.isGenerationCurrent(
                    request.repositoryGeneration)) {
                regionRequests.remove(request.key, request);
                pumpLocked();
                return;
            }
            if (regionRequests.get(request.key) != request) {
                retainCompletedProductsLocked(slice);
                trimLocked();
                pumpLocked();
                return;
            }
            if (slice != null) {
                long staleMask = 0L;
                for (ProjectedPage page : slice.pages()) {
                    int ordinal = Math.floorMod(page.globalPageZ(),
                            CaveLoadHierarchy.PAGES_PER_REGION)
                            * CaveLoadHierarchy.PAGES_PER_REGION
                            + Math.floorMod(page.globalPageX(),
                                    CaveLoadHierarchy.PAGES_PER_REGION);
                    long currentSource = repository.getPageRevision(
                            page.view(), page.projectionTopY(),
                            page.globalPageX(), page.globalPageZ());
                    if (currentSource == 0L
                            || currentSource != page.sourceRevision()) {
                        staleMask |= 1L << ordinal;
                        request.completedMask &= ~(1L << ordinal);
                        continue;
                    }
                    PageKey pageKey = new PageKey(page.dimension(), page.view(),
                            page.normalizedLayer(), page.projectionTopY(),
                            page.globalPageX(), page.globalPageZ(),
                            CaveProjectionStyle.signature());
                    pages.put(pageKey, page);
                    /* Stage only a source-coherent child. This prevents an old
                     * archive fingerprint from entering CIMG while the request is
                     * being coalesced to the final page source. */
                    if (page.complete()) stageRegionLocked(page);
                    request.completedMask |= 1L << ordinal;
                }
                if ((slice.incompleteMask() | staleMask) != 0L) {
                    request.retryAfterMs = System.currentTimeMillis()
                            + INCOMPLETE_RETRY_MS;
                } else {
                    request.retryAfterMs = 0L;
                }
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (!slice.pages().isEmpty() && recorder.shouldEmitEvent(
                        "CAVE_NATIVE_REGION_PROJECTION_SLICE:" + request.key, 100L)) {
                    recorder.event("CAVE_NATIVE_REGION_PROJECTION_SLICE",
                            "region=" + request.key.regionX() + ','
                                    + request.key.regionZ()
                                    + " view=" + request.key.view()
                                    + " top_y=" + request.key.projectionTopY()
                                    + " pages=" + slice.pages().size()
                                    + " remaining="
                                    + Long.bitCount(request.pendingMask()));
                }
            }
            request.reconcileSatisfiedPages(repository, pages);
            if (request.presentationRetired) {
                // The current CPU slice was already running when another Cave
                // projection became the loading window. Retain any valid pages it
                // finished, but do not continue the obsolete region transaction as
                // BACKGROUND work and do not publish it into the new presentation.
                request.setForegroundMask(0L);
                regionQueue.remove(request);
                regionRequests.remove(request.key, request);
                trimLocked();
                pumpLocked();
                return;
            }
            releaseForegroundBatchLocked(request);
            request.refreshLaneForPending();
            if (request.pendingMask() == 0L) {
                if (!request.keepAlive(System.currentTimeMillis())) {
                    regionRequests.remove(request.key, request);
                }
            } else {
                request.sequence = sequence++;
                regionQueue.offer(request);
            }
            trimLocked();
            pumpLocked();
        }
    }

    private void complete(Request request, long submittedEpoch,
            ProjectedPage page, Throwable failure) {
        synchronized (this) {
            activeBuilds = Math.max(0, activeBuilds - 1);
            request.inFlight = false;
            if (submittedEpoch != epoch) {
                pumpLocked();
                return;
            }
            if (failure != null
                    || !repository.isGenerationCurrent(
                            request.repositoryGeneration)) {
                requests.remove(request.key, request);
                pumpLocked();
                return;
            }
            if (requests.get(request.key) != request) {
                retainCompletedProductLocked(request.key, page);
                trimLocked();
                pumpLocked();
                return;
            }
            long currentSource = repository.getPageRevision(
                    request.key.view(), request.key.projectionTopY(),
                    request.key.globalPageX(), request.key.globalPageZ());
            if (currentSource == 0L) {
                requests.remove(request.key, request);
                pumpLocked();
                return;
            }
            if (page == null || page.sourceRevision() != currentSource) {
                request.sourceRevision = currentSource;
                request.retryAfterMs = System.currentTimeMillis()
                        + INCOMPLETE_RETRY_MS;
                request.sequence = sequence++;
                queue.offer(request);
                pumpLocked();
                return;
            }
            requests.remove(request.key, request);
            pages.put(request.key, page);
            boolean offered = false;
            for (Map.Entry<MapRequestLane, CavePresentationGeneration> subscriber
                    : request.subscribers.entrySet()) {
                MapRequestLane subscriberLane = subscriber.getKey();
                CavePresentationGeneration currentGeneration =
                        activePresentationGeneration(subscriberLane, request.key);
                readyPages.offer(page.asDirectOffer(subscriberLane,
                        currentGeneration == null
                                ? subscriber.getValue() : currentGeneration));
                offered = true;
            }
            if (!offered && request.backgroundConsumer) {
                readyPages.offer(page.asDirectOffer(request.lane, null));
            }
            if (page.complete()) stageRegionLocked(page);
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent(
                    "CAVE_REGION_PAGE_PROJECTED:" + request.key, 250L)) {
                recorder.event("CAVE_REGION_PAGE_PROJECTED",
                        "page=" + request.key.globalPageX() + ','
                                + request.key.globalPageZ()
                                + " view=" + request.key.view()
                                + " top_y=" + request.key.projectionTopY()
                                + " source_revision=" + page.sourceRevision()
                                + " lane=" + request.lane);
            }
            trimLocked();
            pumpLocked();
        }
    }

    private boolean isCurrent(Request request) {
        return request != null
                && repository.isGenerationCurrent(request.repositoryGeneration)
                && repository.getPageRevision(
                        request.key.view(), request.key.projectionTopY(),
                        request.key.globalPageX(), request.key.globalPageZ()) != 0L;
    }

    /** Retains valid content completed after every presentation subscriber left. */
    private void retainCompletedProductLocked(PageKey key, ProjectedPage page) {
        if (key == null || page == null) return;
        long current = repository.getPageRevision(key.view(), key.projectionTopY(),
                key.globalPageX(), key.globalPageZ());
        if (current == 0L || current != page.sourceRevision()) return;
        pages.put(key, page.withPresentationGeneration(null));
        recordProjectionProductReadyWithoutSubscriber(key);
    }

    private void retainCompletedProductsLocked(RegionBuildSlice slice) {
        if (slice == null || slice.pages() == null) return;
        for (ProjectedPage page : slice.pages()) {
            if (page == null) continue;
            PageKey key = new PageKey(page.dimension(), page.view(),
                    page.normalizedLayer(), page.projectionTopY(),
                    page.globalPageX(), page.globalPageZ(),
                    CaveProjectionStyle.signature());
            retainCompletedProductLocked(key, page);
        }
    }

    private static void recordProjectionProductBuildStarted(PageKey key,
            MapRequestLane lane) {
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (recorder.shouldEmitEvent("CAVE_PROJECTION_PRODUCT_BUILD_STARTED", 250L)) {
            recorder.event("CAVE_PROJECTION_PRODUCT_BUILD_STARTED",
                    "page=" + key.globalPageX() + ',' + key.globalPageZ()
                            + " view=" + key.view() + " top_y="
                            + key.projectionTopY() + " lane=" + lane);
        }
    }

    private static void recordProjectionProductReuse(PageKey key,
            MapRequestLane lane, boolean subscriberAttached) {
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (recorder.shouldEmitEvent("CAVE_PROJECTION_PRODUCT_REUSED", 250L)) {
            recorder.event("CAVE_PROJECTION_PRODUCT_REUSED",
                    "page=" + key.globalPageX() + ',' + key.globalPageZ()
                            + " view=" + key.view() + " top_y="
                            + key.projectionTopY() + " lane=" + lane
                            + " subscriber_attached=" + subscriberAttached);
        }
    }

    private static void recordProjectionProductReadyWithoutSubscriber(PageKey key) {
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (recorder.shouldEmitEvent(
                "CAVE_PROJECTION_PRODUCT_READY_NO_CURRENT_SUBSCRIBER", 250L)) {
            recorder.event("CAVE_PROJECTION_PRODUCT_READY_NO_CURRENT_SUBSCRIBER",
                    "page=" + key.globalPageX() + ',' + key.globalPageZ()
                            + " view=" + key.view() + " top_y="
                            + key.projectionTopY() + " action=retain_content");
        }
    }

    private void stageRegionLocked(ProjectedPage page) {
        int regionX = Math.floorDiv(page.globalPageX(),
                CaveRegionImageCache.PAGES_PER_EDGE);
        int regionZ = Math.floorDiv(page.globalPageZ(),
                CaveRegionImageCache.PAGES_PER_EDGE);
        RegionKey key = new RegionKey(page.dimension(), page.view(),
                page.normalizedLayer(), page.projectionTopY(),
                CaveProjectionStyle.signature(), regionX, regionZ);
        RegionTransaction region = regions.computeIfAbsent(
                key, RegionTransaction::new);
        region.stage(page);
    }

    private void scheduleRegionWriteLocked(long now) {
        if (pendingWrites.size() >= MAX_PENDING_REGION_WRITES) return;
        RegionTransaction selected = null;
        for (RegionTransaction candidate : regions.values()) {
            if (!candidate.dirty || pendingWrites.contains(candidate.key)) continue;
            boolean quiet = now - candidate.lastChangedMs
                    >= REGION_WRITE_DEBOUNCE_MS;
            boolean maxDirtyAge = candidate.dirtySinceMs != 0L
                    && now - candidate.dirtySinceMs >= REGION_WRITE_MAX_DIRTY_MS;
            boolean writeIntervalElapsed = candidate.lastWriteAttemptMs == 0L
                    || now - candidate.lastWriteAttemptMs
                            >= REGION_WRITE_DEBOUNCE_MS;
            int packagedPages = Long.bitCount(candidate.pageMask);
            int newPages = Long.bitCount(candidate.pageMask
                    & ~candidate.lastWrittenPageMask);
            long revisedPages = Math.max(0L,
                    candidate.version - candidate.lastWrittenVersion);
            boolean usefulPackage = packagedPages
                    >= REGION_WRITE_MIN_PACKAGED_PAGES;
            boolean substantialProgress = newPages >= REGION_WRITE_MIN_PROGRESS
                    || revisedPages >= REGION_WRITE_MIN_PROGRESS
                    || candidate.lastWrittenVersion == 0L;
            /*
             * A complete 64-page region is not a reason to bypass debounce. More
             * importantly, do not write a full 1 MiB CIMG for one or two children.
             * CAV8/CVD are the sparse durable products; CIMG is the packaged visual
             * bootstrap and should be written in meaningful coherent checkpoints.
             */
            if (!usefulPackage || (!quiet && !maxDirtyAge)
                    || !writeIntervalElapsed
                    || (!substantialProgress && !maxDirtyAge)) continue;
            selected = candidate;
            break;
        }
        if (selected == null) return;
        RegionTransaction target = selected;
        long writeVersion = target.version;
        long submittedEpoch = epoch;
        CaveRegionImageCache.RegionImage snapshot = target.snapshot(now);
        if (snapshot == null || !pendingWrites.add(target.key)) return;
        long snapshotPageMask = snapshot.pageMask();
        int snapshotPages = Long.bitCount(snapshotPageMask);
        int snapshotNewPages = Long.bitCount(snapshotPageMask
                & ~target.lastWrittenPageMask);
        target.lastWriteAttemptMs = now;
        CompletableFuture<Boolean> future = MapWorkScheduler.tryIoFuture(
                MapRequestLane.BACKGROUND, MapWorkScheduler.WorkType.DISK_WRITE,
                MapRequestLane.BACKGROUND.priorityBase(), 128,
                () -> submittedEpoch == epoch,
                () -> regionImageCache.save(snapshot));
        if (future == null) {
            pendingWrites.remove(target.key);
            return;
        }
        future.whenComplete((saved, failure) -> {
            synchronized (CaveRegionProjectionService.this) {
                pendingWrites.remove(target.key);
                if (submittedEpoch != epoch) return;
                RegionTransaction current = regions.get(target.key);
                if (current != null && failure == null
                        && Boolean.TRUE.equals(saved)) {
                    current.lastWrittenPageMask = snapshotPageMask;
                    current.lastWrittenVersion = Math.max(
                            current.lastWrittenVersion, writeVersion);
                    if (current.version == writeVersion) {
                        current.dirty = false;
                        current.dirtySinceMs = 0L;
                    }
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    if (recorder.shouldEmitEvent(
                            "CAVE_REGION_TRANSACTION_SAVED:" + target.key, 250L)) {
                        recorder.event("CAVE_REGION_TRANSACTION_SAVED",
                                "region=" + target.key.regionX() + ','
                                        + target.key.regionZ()
                                        + " view=" + target.key.view()
                                        + " top_y=" + target.key.projectionTopY()
                                        + " pages=" + snapshotPages
                                        + " new_pages=" + snapshotNewPages
                                        + " policy=coalesced_packaged_cimg"
                                        + " pass=PASS155");
                    }
                }
                scheduleRegionWriteLocked(System.currentTimeMillis());
                trimLocked();
            }
        });
    }

    private void releaseForegroundBatchLocked(RegionBuildRequest request) {
        if (request == null || request.foregroundMask == 0L) return;

        /*
         * PASS169: foreground offers are a subscription to the currently visible
         * generation, not a durable property of a cached region product. In-flight
         * slices from a retired layer may finish, but they must never re-arm/re-offer
         * themselves after activateViewport() switched Top-Y. Fence publication at
         * the producer as well as at the consumer so stale work cannot create a
         * reject/re-offer loop.
         */
        if (request.foregroundLane == MapRequestLane.MINIMAP
                || request.foregroundLane == MapRequestLane.FULLSCREEN) {
            CavePresentationGeneration active =
                    activePresentationGenerations.get(request.foregroundLane);
            boolean projectionMismatch = active != null
                    && !active.matches(request.key.dimension(), request.key.view(),
                            request.key.normalizedLayer(),
                            request.key.projectionTopY(), request.foregroundLane);
            if (request.presentationRetired || projectionMismatch) {
                int stalePages = Long.bitCount(request.foregroundMask);
                request.setForegroundMask(0L);
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String staleKey = "CAVE_STALE_FOREGROUND_REOFFER_SUPPRESSED:"
                        + request.key + ':' + request.foregroundLane;
                if (stalePages > 0 && recorder.shouldEmitEvent(staleKey, 500L)) {
                    recorder.event("CAVE_STALE_FOREGROUND_REOFFER_SUPPRESSED",
                            "region=" + request.key.regionX() + ','
                                    + request.key.regionZ()
                                    + " view=" + request.key.view()
                                    + " top_y=" + request.key.projectionTopY()
                                    + " lane=" + request.foregroundLane
                                    + " pages=" + stalePages
                                    + " action=retire_offer_without_touching_retained_pixels"
                                    + " policy=single_active_foreground_generation"
                                    + " pass=PASS169");
                }
                return;
            }
            if (active != null
                    && !java.util.Objects.equals(
                            request.foregroundGeneration, active)) {
                request.setForegroundGeneration(active);
            }
        }

        java.util.List<Integer> ordinals =
                request.orderedOrdinals(request.foregroundMask);
        java.util.List<ProjectedPage> batch =
                new java.util.ArrayList<>(FOREGROUND_RELEASE_SLICE);
        java.util.List<Integer> offeredOrdinals =
                new java.util.ArrayList<>(FOREGROUND_RELEASE_SLICE);
        int requestedRank = request.foregroundLane.rank();
        long now = System.currentTimeMillis();
        for (int ordinal : ordinals) {
            long bit = 1L << ordinal;
            /*
             * PASS110 / Xaero page-local commit: a native 512x512 region is an I/O
             * grouping, not a visual transaction. One unresolved 64x64 child must
             * not strand every later child in this region.
             *
             * PASS113: queue insertion is only an OFFER. The consumer can poll this
             * page during a planner handoff and reject it. Suppress permanently only
             * after consumer ACK for this exact source revision/lane; otherwise use
             * a short anti-duplicate backoff and re-offer from retained page cache.
             */
            if ((request.completedMask & bit) == 0L) continue;
            ProjectedPage page = readyForegroundPageLocked(request, ordinal);
            if (page == null) continue;
            long current = page.sourceRevision();
            if (request.acknowledgedSourceRevisions[ordinal] == current
                    && Byte.toUnsignedInt(request.acknowledgedLaneRanks[ordinal])
                            >= requestedRank) {
                continue;
            }
            boolean recentlyOffered = request.offeredSourceRevisions[ordinal]
                    == current
                    && Byte.toUnsignedInt(request.offeredLaneRanks[ordinal])
                            >= requestedRank
                    && now - request.lastOfferedMs[ordinal] < FOREGROUND_REOFFER_MS;
            if (recentlyOffered) continue;
            batch.add(page.asRegionOffer(
                    request.foregroundLane, request.foregroundGeneration));
            offeredOrdinals.add(ordinal);
            if (batch.size() >= FOREGROUND_RELEASE_SLICE) break;
        }

        if (batch.isEmpty()) return;
        for (ProjectedPage page : batch) readyPages.offer(page);
        for (int index = 0; index < offeredOrdinals.size(); index++) {
            int ordinal = offeredOrdinals.get(index);
            ProjectedPage page = batch.get(index);
            request.offeredForegroundMask |= 1L << ordinal;
            request.offeredSourceRevisions[ordinal] = page.sourceRevision();
            request.offeredLaneRanks[ordinal] =
                    (byte) request.foregroundLane.rank();
            request.lastOfferedMs[ordinal] = now;
        }
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        String frontierEventKey = "CAVE_REGION_FOREGROUND_FRONTIER_READY:"
                + request.key;
        if (recorder.shouldEmitEvent(frontierEventKey, 100L)) {
            recorder.event("CAVE_REGION_FOREGROUND_FRONTIER_READY",
                    "region=" + request.key.regionX() + ','
                            + request.key.regionZ()
                            + " view=" + request.key.view()
                            + " top_y=" + request.key.projectionTopY()
                            + " pages=" + batch.size()
                            + " offered="
                            + Long.bitCount(request.offeredForegroundMask)
                            + '/' + Long.bitCount(request.foregroundMask)
                            + " acked="
                            + Long.bitCount(request.acknowledgedForegroundMask)
                            + '/' + Long.bitCount(request.foregroundMask)
                            + " order=page_local_priority_ack");
        }
    }

    private ProjectedPage readyForegroundPageLocked(RegionBuildRequest request,
            int ordinal) {
        int pageX = request.key.regionX()
                * CaveLoadHierarchy.PAGES_PER_REGION
                + ordinal % CaveLoadHierarchy.PAGES_PER_REGION;
        int pageZ = request.key.regionZ()
                * CaveLoadHierarchy.PAGES_PER_REGION
                + ordinal / CaveLoadHierarchy.PAGES_PER_REGION;
        PageKey pageKey = new PageKey(request.key.dimension(),
                request.key.view(), request.key.normalizedLayer(),
                request.key.projectionTopY(), pageX, pageZ,
                request.key.styleSignature());
        ProjectedPage page = pages.get(pageKey);
        if (page == null) return null;
        long current = repository.getPageRevision(request.key.view(),
                request.key.projectionTopY(), pageX, pageZ);
        return current != 0L && current == page.sourceRevision() ? page : null;
    }

    private void promoteCachedRegionPagesLocked(RegionBuildRequest request,
            long requestedMask, MapRequestLane lane) {
        long mask = requestedMask;
        while (mask != 0L) {
            int ordinal = Long.numberOfTrailingZeros(mask);
            mask &= mask - 1L;
            int pageX = request.key.regionX()
                    * CaveLoadHierarchy.PAGES_PER_REGION
                    + ordinal % CaveLoadHierarchy.PAGES_PER_REGION;
            int pageZ = request.key.regionZ()
                    * CaveLoadHierarchy.PAGES_PER_REGION
                    + ordinal / CaveLoadHierarchy.PAGES_PER_REGION;
            PageKey pageKey = new PageKey(request.key.dimension(),
                    request.key.view(), request.key.normalizedLayer(),
                    request.key.projectionTopY(), pageX, pageZ,
                    request.key.styleSignature());
            ProjectedPage cached = pages.get(pageKey);
            if (cached == null) continue;
            long current = repository.getPageRevision(request.key.view(),
                    request.key.projectionTopY(), pageX, pageZ);
            if (current == 0L || current != cached.sourceRevision()) continue;
            request.completedMask |= 1L << ordinal;
        }
    }

    private RegionBuildRequest regionRequestForPageLocked(PageKey pageKey) {
        RegionBuildKey regionKey = new RegionBuildKey(pageKey.dimension(),
                pageKey.view(), pageKey.normalizedLayer(),
                pageKey.projectionTopY(), pageKey.styleSignature(),
                Math.floorDiv(pageKey.globalPageX(),
                        CaveLoadHierarchy.PAGES_PER_REGION),
                Math.floorDiv(pageKey.globalPageZ(),
                        CaveLoadHierarchy.PAGES_PER_REGION));
        RegionBuildRequest request = regionRequests.get(regionKey);
        return request != null
                && request.owns(pageKey.globalPageX(), pageKey.globalPageZ())
                        ? request : null;
    }

    private boolean regionOwnsPageLocked(PageKey pageKey) {
        return regionRequestForPageLocked(pageKey) != null;
    }

    private static int canonicalTopY(CaveView view, int projectionTopY) {
        return view == CaveView.FULL ? Integer.MIN_VALUE : projectionTopY;
    }

    private void pruneCompletedRegionRequestsLocked(long now) {
        var iterator = regionRequests.entrySet().iterator();
        while (iterator.hasNext()) {
            RegionBuildRequest request = iterator.next().getValue();
            if (request.inFlight || request.pendingMask() != 0L) continue;
            if (!request.keepAlive(now)) iterator.remove();
        }
    }

    private void trimLocked() {
        while (pages.size() > MAX_PROJECTED_PAGES) {
            var iterator = pages.entrySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
        if (regions.size() <= MAX_REGION_TRANSACTIONS) return;
        var iterator = regions.entrySet().iterator();
        while (regions.size() > MAX_REGION_TRANSACTIONS && iterator.hasNext()) {
            Map.Entry<RegionKey, RegionTransaction> entry = iterator.next();
            if (entry.getValue().dirty || pendingWrites.contains(entry.getKey())) {
                continue;
            }
            iterator.remove();
        }
    }

    record ProjectedPage(String dimension, CaveView view, int normalizedLayer,
            int projectionTopY, int globalPageX, int globalPageZ,
            long sourceRevision, int[] pixels, long[] knownRows,
            int knownColumnCount, boolean complete, CaveEmptyProof emptyProof,
            MapRequestLane lane,
            int priority, long sequence, boolean regionOffer,
            CavePresentationGeneration presentationGeneration) {
        ProjectedPage {
            /*
             * buildPage() hands ownership of freshly allocated styled/known arrays
             * to this immutable internal object. Keep those arrays by reference so
             * lane promotion does not allocate/copy another ~16 KiB page payload.
             * Public record accessors below still return defensive copies; internal
             * consumers use the read-only unsafe accessors. Xaero likewise retains
             * written tile payloads and changes writer/presentation metadata without
             * cloning the tile for every priority transition.
             */
            lane = lane == null ? MapRequestLane.FULLSCREEN : lane;
            emptyProof = emptyProof == null ? CaveEmptyProof.NONE : emptyProof;
        }

        @Override
        public int[] pixels() {
            return Arrays.copyOf(pixels, pixels.length);
        }

        @Override
        public long[] knownRows() {
            return Arrays.copyOf(knownRows, knownRows.length);
        }

        /** Package-private immutable handoff; constructor already owns a copy. */
        int[] pixelsUnsafe() {
            return pixels;
        }

        /** Package-private immutable handoff; constructor already owns a copy. */
        long[] knownRowsUnsafe() {
            return knownRows;
        }

        ProjectedPage withLane(MapRequestLane replacement) {
            if (replacement == null || !replacement.strongerThan(lane)) return this;
            return new ProjectedPage(dimension, view, normalizedLayer,
                    projectionTopY, globalPageX, globalPageZ, sourceRevision,
                    pixels, knownRows, knownColumnCount, complete,
                    emptyProof,
                    replacement, priority, sequence, regionOffer,
                    presentationGeneration);
        }

        ProjectedPage withPresentationGeneration(
                CavePresentationGeneration replacement) {
            if (java.util.Objects.equals(
                    presentationGeneration, replacement)) return this;
            return new ProjectedPage(dimension, view, normalizedLayer,
                    projectionTopY, globalPageX, globalPageZ, sourceRevision,
                    pixels, knownRows, knownColumnCount, complete,
                    emptyProof,
                    lane, priority, sequence, regionOffer, replacement);
        }

        ProjectedPage asDirectOffer(MapRequestLane replacement,
                CavePresentationGeneration generation) {
            MapRequestLane effective = replacement == null ? lane : replacement;
            if (!regionOffer && effective == lane
                    && java.util.Objects.equals(
                            presentationGeneration, generation)) return this;
            return new ProjectedPage(dimension, view, normalizedLayer,
                    projectionTopY, globalPageX, globalPageZ, sourceRevision,
                    pixels, knownRows, knownColumnCount, complete,
                    emptyProof,
                    effective, priority, sequence, false, generation);
        }

        ProjectedPage asRegionOffer(MapRequestLane replacement,
                CavePresentationGeneration generation) {
            MapRequestLane effective = replacement == null ? lane : replacement;
            if (regionOffer && effective == lane
                    && java.util.Objects.equals(
                            presentationGeneration, generation)) return this;
            return new ProjectedPage(dimension, view, normalizedLayer,
                    projectionTopY, globalPageX, globalPageZ, sourceRevision,
                    pixels, knownRows, knownColumnCount, complete,
                    emptyProof,
                    effective, priority, sequence, true, generation);
        }
    }

    record DebugSnapshot(int pendingRequests, int activeBuilds,
            int pendingRegionBuilds, int activeRegionBuilds, int readyPages,
            int cachedPages, int regionTransactions, int dirtyRegions,
            int pendingWrites) {
    }

    private record PageKey(String dimension, CaveView view, int normalizedLayer,
            int projectionTopY, int globalPageX, int globalPageZ,
            int styleSignature) {
    }

    private record RegionKey(String dimension, CaveView view, int normalizedLayer,
            int projectionTopY, int styleSignature, int regionX, int regionZ) {
        CaveRegionImageCache.Key cacheKey() {
            return new CaveRegionImageCache.Key(dimension, view, normalizedLayer,
                    projectionTopY, styleSignature, regionX, regionZ);
        }
    }

    private record RegionBuildKey(String dimension, CaveView view,
            int normalizedLayer, int projectionTopY, int styleSignature,
            int regionX, int regionZ) {
    }

    private record RegionBuildSlice(List<ProjectedPage> pages,
            long attemptedMask, long incompleteMask) {
    }

    private static final class RegionBuildRequest
            implements Comparable<RegionBuildRequest> {
        private final RegionBuildKey key;
        private final Level level;
        private final long repositoryGeneration;
        private MapRequestLane lane;
        private int priority;
        private long sequence;
        private long pageMask;
        private long completedMask;
        private long foregroundMask;
        private long offeredForegroundMask;
        private long acknowledgedForegroundMask;
        private final long[] offeredSourceRevisions = new long[64];
        private final byte[] offeredLaneRanks = new byte[64];
        private final long[] lastOfferedMs = new long[64];
        private final long[] acknowledgedSourceRevisions = new long[64];
        private final byte[] acknowledgedLaneRanks = new byte[64];
        private MapRequestLane foregroundLane = MapRequestLane.FULLSCREEN;
        /** Stable loading generation; independent from the short foreground mask. */
        private CavePresentationGeneration foregroundGeneration;
        private int focusPageX;
        private int focusPageZ;
        private long retryAfterMs;
        private long lastRequestedMs;
        private boolean inFlight;
        /** Last bounded source-revision reconciliation for this retained product. */
        private long lastRevisionReconcileMs;
        /** Foreground projection was replaced while this region slice was running. */
        private boolean presentationRetired;

        private RegionBuildRequest(RegionBuildKey key, Level level,
                long repositoryGeneration, MapRequestLane lane, int priority,
                long sequence) {
            this.key = key;
            this.level = level;
            this.repositoryGeneration = repositoryGeneration;
            this.lane = lane;
            this.priority = priority;
            this.sequence = sequence;
            this.lastRequestedMs = System.currentTimeMillis();
        }

        private long pendingMask() {
            return pageMask & ~completedMask;
        }

        private boolean owns(int globalPageX, int globalPageZ) {
            if (Math.floorDiv(globalPageX, CaveLoadHierarchy.PAGES_PER_REGION)
                    != key.regionX()
                    || Math.floorDiv(globalPageZ,
                            CaveLoadHierarchy.PAGES_PER_REGION) != key.regionZ()) {
                return false;
            }
            int localX = Math.floorMod(globalPageX,
                    CaveLoadHierarchy.PAGES_PER_REGION);
            int localZ = Math.floorMod(globalPageZ,
                    CaveLoadHierarchy.PAGES_PER_REGION);
            long bit = 1L << (localZ
                    * CaveLoadHierarchy.PAGES_PER_REGION + localX);
            /*
             * Keep the single-writer fence for every page in the active foreground
             * lease, including completed pages waiting for publication. PASS82
             * released ownership as soon as pendingMask cleared, so the direct exact
             * builder duplicated the same Full pages and generated 741 empty swaps.
             */
            long owned = pendingMask();
            if (keepAlive(System.currentTimeMillis())) owned |= foregroundMask;
            return (owned & bit) != 0L;
        }

        private void reconcileSatisfiedPages(CaveTileRepository repository,
                Map<PageKey, ProjectedPage> pages) {
            long mask = pageMask;
            while (mask != 0L) {
                int ordinal = Long.numberOfTrailingZeros(mask);
                mask &= mask - 1L;
                long bit = 1L << ordinal;
                int pageX = key.regionX() * CaveLoadHierarchy.PAGES_PER_REGION
                        + ordinal % CaveLoadHierarchy.PAGES_PER_REGION;
                int pageZ = key.regionZ() * CaveLoadHierarchy.PAGES_PER_REGION
                        + ordinal / CaveLoadHierarchy.PAGES_PER_REGION;
                PageKey pageKey = new PageKey(key.dimension(), key.view(),
                        key.normalizedLayer(), key.projectionTopY(),
                        pageX, pageZ, key.styleSignature());
                ProjectedPage page = pages.get(pageKey);
                long current = repository.getPageRevision(key.view(),
                        key.projectionTopY(), pageX, pageZ);
                boolean valid = page != null && current != 0L
                        && current == page.sourceRevision();
                if (valid) {
                    completedMask |= bit;
                } else {
                    completedMask &= ~bit;
                    offeredForegroundMask &= ~bit;
                    acknowledgedForegroundMask &= ~bit;
                    offeredSourceRevisions[ordinal] = 0L;
                    offeredLaneRanks[ordinal] = 0;
                    lastOfferedMs[ordinal] = 0L;
                    acknowledgedSourceRevisions[ordinal] = 0L;
                    acknowledgedLaneRanks[ordinal] = 0;
                }
            }
        }

        private void setForegroundMask(long mask) {
            long removed = foregroundMask & ~mask;
            while (removed != 0L) {
                int ordinal = Long.numberOfTrailingZeros(removed);
                removed &= removed - 1L;
                offeredSourceRevisions[ordinal] = 0L;
                offeredLaneRanks[ordinal] = 0;
                lastOfferedMs[ordinal] = 0L;
                acknowledgedSourceRevisions[ordinal] = 0L;
                acknowledgedLaneRanks[ordinal] = 0;
            }
            foregroundMask = mask;
            offeredForegroundMask &= mask;
            acknowledgedForegroundMask &= mask;
        }

        private void setForegroundGeneration(
                CavePresentationGeneration generation) {
            if (java.util.Objects.equals(foregroundGeneration, generation)) return;
            foregroundGeneration = generation;
            /* ACKs belong to one loading transaction, even when immutable pixels
             * and region/page identity are reused by the next generation. */
            offeredForegroundMask = 0L;
            acknowledgedForegroundMask = 0L;
            java.util.Arrays.fill(offeredSourceRevisions, 0L);
            java.util.Arrays.fill(offeredLaneRanks, (byte) 0);
            java.util.Arrays.fill(lastOfferedMs, 0L);
            java.util.Arrays.fill(acknowledgedSourceRevisions, 0L);
            java.util.Arrays.fill(acknowledgedLaneRanks, (byte) 0);
        }

        private boolean keepAlive(long now) {
            return foregroundMask != 0L
                    && now - lastRequestedMs <= FOREGROUND_REQUEST_LEASE_MS;
        }

        private List<Integer> orderedPendingOrdinals() {
            long pending = pendingMask();
            long pendingForeground = pending & foregroundMask;
            if (pendingForeground != 0L) pending = pendingForeground;
            return orderedOrdinals(pending);
        }

        private List<Integer> orderedOrdinals(long mask) {
            List<Integer> result = new java.util.ArrayList<>(
                    Long.bitCount(mask));
            while (mask != 0L) {
                int ordinal = Long.numberOfTrailingZeros(mask);
                mask &= mask - 1L;
                result.add(ordinal);
            }
            /*
             * A native region is 8x8 pages. Worker completion order must follow the
             * current viewport focus, not the region's top-left ordinal. Xaero
             * reduces the cave writer to a small player-centred window during mode
             * transitions; centre-first ordering gives the same visible-first
             * behavior without changing total work. Scanline ordinal is a stable
             * tie-breaker.
             */
            result.sort(java.util.Comparator
                    .comparingInt(this::focusDistanceSquared)
                    .thenComparingInt(Integer::intValue));
            return result;
        }

        private int focusDistanceSquared(int ordinal) {
            int localX = ordinal % CaveLoadHierarchy.PAGES_PER_REGION;
            int localZ = ordinal / CaveLoadHierarchy.PAGES_PER_REGION;
            int pageX = key.regionX() * CaveLoadHierarchy.PAGES_PER_REGION + localX;
            int pageZ = key.regionZ() * CaveLoadHierarchy.PAGES_PER_REGION + localZ;
            int dx = pageX - focusPageX;
            int dz = pageZ - focusPageZ;
            return dx * dx + dz * dz;
        }

        private int pagePriority(int ordinal) {
            return priority - focusDistanceSquared(ordinal) * 4_096 - ordinal;
        }

        private MapRequestLane pageLane(int ordinal) {
            return (foregroundMask & (1L << ordinal)) != 0L
                    ? foregroundLane : MapRequestLane.BACKGROUND;
        }

        private void refreshLaneForPending() {
            lane = (pendingMask() & foregroundMask) != 0L
                    ? foregroundLane : MapRequestLane.BACKGROUND;
        }

        @Override
        public int compareTo(RegionBuildRequest other) {
            int byLane = Integer.compare(other.lane.rank(), lane.rank());
            if (byLane != 0) return byLane;
            int byPriority = Integer.compare(other.priority, priority);
            return byPriority != 0 ? byPriority
                    : Long.compare(sequence, other.sequence);
        }
    }

    private static final class Request implements Comparable<Request> {
        private final PageKey key;
        private final Level level;
        private long sourceRevision;
        private final long repositoryGeneration;
        private MapRequestLane lane;
        private int priority;
        private long sequence;
        private long retryAfterMs;
        private boolean inFlight;
        /** Presentation consumers attached to one content-keyed CPU product. */
        private final EnumMap<MapRequestLane, CavePresentationGeneration> subscribers =
                new EnumMap<>(MapRequestLane.class);
        private boolean backgroundConsumer;

        private Request(PageKey key, Level level, long sourceRevision,
                long repositoryGeneration, MapRequestLane lane, int priority,
                long sequence,
                CavePresentationGeneration presentationGeneration) {
            this.key = key;
            this.level = level;
            this.sourceRevision = sourceRevision;
            this.repositoryGeneration = repositoryGeneration;
            this.lane = lane;
            this.priority = priority;
            this.sequence = sequence;
            addSubscriber(lane, presentationGeneration);
        }

        private void addSubscriber(MapRequestLane lane,
                CavePresentationGeneration presentationGeneration) {
            MapRequestLane effective = lane == null
                    ? MapRequestLane.FULLSCREEN : lane;
            if (effective == MapRequestLane.BACKGROUND
                    || effective == MapRequestLane.PREFETCH) {
                backgroundConsumer = true;
                return;
            }
            subscribers.put(effective, presentationGeneration);
        }

        @Override
        public int compareTo(Request other) {
            int byLane = Integer.compare(other.lane.rank(), lane.rank());
            if (byLane != 0) return byLane;
            int byPriority = Integer.compare(other.priority, priority);
            return byPriority != 0 ? byPriority
                    : Long.compare(sequence, other.sequence);
        }
    }

    private static final class RegionTransaction {
        private final RegionKey key;
        private final int[] pixels =
                new int[CaveRegionImageCache.PIXEL_COUNT];
        private final long[] pageSourceStamps =
                new long[CaveRegionImageCache.PAGE_COUNT];
        private final byte[] pageEmptyProofs =
                new byte[CaveRegionImageCache.PAGE_COUNT];
        private long pageMask;
        private long version;
        private long lastChangedMs;
        private long dirtySinceMs;
        private long lastWriteAttemptMs;
        private long lastWrittenPageMask;
        private long lastWrittenVersion;
        private boolean dirty;

        private RegionTransaction(RegionKey key) {
            this.key = key;
        }

        private boolean stage(ProjectedPage page) {
            boolean hasContent = false;
            for (int pixel : page.pixels) {
                if (pixel != 0) {
                    hasContent = true;
                    break;
                }
            }
            if (!hasContent && !page.emptyProof().strong()) return false;
            int localPageX = Math.floorMod(page.globalPageX(),
                    CaveRegionImageCache.PAGES_PER_EDGE);
            int localPageZ = Math.floorMod(page.globalPageZ(),
                    CaveRegionImageCache.PAGES_PER_EDGE);
            int ordinal = localPageZ * CaveRegionImageCache.PAGES_PER_EDGE
                    + localPageX;
            long bit = 1L << ordinal;
            if ((pageMask & bit) != 0L
                    && pageSourceStamps[ordinal] == page.sourceRevision()) {
                return false;
            }
            int destinationX = localPageX * CaveTextureAtlas.PAGE_SIZE;
            int destinationZ = localPageZ * CaveTextureAtlas.PAGE_SIZE;
            int[] source = page.pixels;
            for (int row = 0; row < CaveTextureAtlas.PAGE_SIZE; row++) {
                System.arraycopy(source, row * CaveTextureAtlas.PAGE_SIZE,
                        pixels, (destinationZ + row)
                                * CaveRegionImageCache.REGION_PIXELS
                                + destinationX,
                        CaveTextureAtlas.PAGE_SIZE);
            }
            pageSourceStamps[ordinal] = page.sourceRevision();
            pageEmptyProofs[ordinal] = (byte) page.emptyProof().persistedCode();
            pageMask |= bit;
            version++;
            long now = System.currentTimeMillis();
            lastChangedMs = now;
            if (!dirty) dirtySinceMs = now;
            dirty = true;
            return true;
        }

        private CaveRegionImageCache.RegionImage snapshot(long now) {
            if (pageMask == 0L) return null;
            return new CaveRegionImageCache.RegionImage(key.cacheKey(), pageMask,
                    Arrays.copyOf(pageSourceStamps, pageSourceStamps.length),
                    Arrays.copyOf(pageEmptyProofs, pageEmptyProofs.length),
                    Arrays.copyOf(pixels, pixels.length), now);
        }
    }
}
