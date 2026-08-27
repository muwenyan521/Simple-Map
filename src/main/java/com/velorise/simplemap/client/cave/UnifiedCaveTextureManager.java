package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.cave.archive.CaveArchiveV2Service;
import com.velorise.simplemap.client.cave.projection.CaveProjectionServiceV2;

import com.velorise.simplemap.client.MapCancellationToken;
import com.velorise.simplemap.client.MapDebugRecorder;
import com.velorise.simplemap.client.MapConfig;
import com.velorise.simplemap.client.MapActivityGate;
import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapLodPolicy;
import com.velorise.simplemap.client.MapGpuBudgetController;
import com.velorise.simplemap.client.MapResidencyManager;
import com.velorise.simplemap.client.MapPageLayout;
import com.velorise.simplemap.client.MapPerformanceGovernor;
import com.velorise.simplemap.client.MapPipelineStage;
import com.velorise.simplemap.client.MapPipelineTelemetry;
import com.velorise.simplemap.client.ExactPageState;
import com.velorise.simplemap.client.ExactPageStateTracker;
import com.velorise.simplemap.client.GeneratedChunkIndex;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapViewLoadPlanner;
import com.velorise.simplemap.client.MapViewportDemandPolicy;
import com.velorise.simplemap.client.MapWorkScheduler;
import com.velorise.simplemap.client.gpu.MapGpuPageTableService;
import com.velorise.simplemap.client.gpu.TileKey;
import com.velorise.simplemap.client.pipeline.RevisionStamp;
import com.velorise.simplemap.client.session.MapSessionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unified page-only GPU pipeline for Layered and Full Cave.
 *
 * Foreground publication never waits for a 512x512 region consolidation. Every
 * 64x64 page has an explicit source/build/upload revision and stale jobs retry.
 */
public final class UnifiedCaveTextureManager {
    private static final int MAX_PAGES = Math.max(CaveTextureAtlas.SLOT_COUNT,
            Math.min(1536, CaveTextureAtlas.SLOT_COUNT + 256));
    private static final int MAX_REQUESTS = 4096;
    private static final long INCOMPLETE_RETRY_MS = 80L;
    /** Incomplete zero-source probes stay retryable, but not at exact-build cadence. */
    private static final long PARTIAL_NO_SOURCE_RETRY_MS = 180L;
    private static final long FAILED_RETRY_MS = 250L;
    /**
     * A 64x64 cave page is fed by sixteen central chunks plus a one-chunk halo.
     * World-save/live capture commits those leaves independently, so the summed
     * page revision may advance dozens of times while one coherent page is being
     * assembled. Once a usable fallback exists, wait for a short quiet window
     * instead of rebuilding after every individual leaf commit. The hard burst
     * limit guarantees that a permanently noisy page still receives progressive
     * updates.
     */
    private static final long SOURCE_QUIET_MS = 70L;
    private static final long SOURCE_BURST_MAX_MS = 400L;
    /** Two 16x16 leaves worth of newly authoritative pixels justify publishing a
     * stale-in-flight result immediately; smaller deltas are coalesced. */
    private static final int SOURCE_PROGRESS_MIN_COLUMNS = 512;
    /** Fullscreen CPU build-ahead is bounded independently of ordered GPU commit. */
    private static final int FULLSCREEN_BUILD_AHEAD_PAGES = 96;
    /** A ready local-priority run is revealed as one compact visual burst. */
    private static final int FULLSCREEN_PUBLICATION_BURST = 32;
    /** PASS156: reveal a small contiguous scanline prefix per upload transaction. */
    private static final int FULLSCREEN_REVEAL_BURST = 4;
    /**
     * The presentation writer repairs its current coordinate, never skips it. PASS165's 120 ms
     * watchdog turned every transient source/projection mismatch into a permanent
     * visible hole and paid that timer serially for dozens of pages after each
     * Layered Top-Y change. Retry/re-arm the exact frontier instead.
     */
    private static final long FULLSCREEN_FRONTIER_REPAIR_MS = 250L;
    /** CPU-complete region pages are staged into the branch hierarchy ahead of exact GPU admission. */
    private static final int MAX_REGION_EXACT_BACKLOG = 4096;
    /**
     * A CPU-ready page that lost the atlas race is retried from the immutable
     * completed payload. Rebuilding the projection would multiply IO/CPU work while
     * the atlas is saturated.
     */
    private static final long ATLAS_PUBLICATION_RETRY_MS = 40L;
    /**
     * Pages outside every live viewport may surrender GPU residency without first
     * waiting for branch replacement. Their coherent CPU LODs remain attached and
     * can be restored cheaply when revisited.
     */
    private static final long OFFSCREEN_EVICTION_GRACE_MS = 120L;
    /** Keep two exact-page rings owned while the fullscreen camera pans. */
    private static final int FULLSCREEN_STICKY_HALO_PAGES = 0;
    private static final int FULLSCREEN_RECENTER_THRESHOLD_PAGES = 2;
    private static final long ACTIVE_PLANNER_GRACE_MS = 1_000L;
    /** Adaptive visible restyle sweep after palette/profile changes. */
    private static final long STYLE_REFRESH_WINDOW_MS = 2_500L;
    /** Quiet period before consolidating exact pages into one 512x512 CIMG. */
    private static final long REGION_IMAGE_SAVE_DEBOUNCE_MS = 450L;
    /**
     * A persisted CIMG may be rendered before its exact source fingerprint has been
     * rehydrated. It is visual continuity only, never source satisfaction. Keep a
     * sentinel that can never equal repository revisions (which are non-negative).
     */
    private static final long UNVERIFIED_REGION_IMAGE_SOURCE_REVISION = -2L;
    private static final long REGION_IMAGE_MISS_RETRY_MS = 5_000L;
    /** Bound decoded 1 MiB CIMG payloads independently from the IO scheduler. */
    private static final int MAX_PENDING_REGION_IMAGE_READS = 12;
    private static final int MAX_COMPLETED_REGION_IMAGES = 12;
    /** Partial exact pages are useful near the player, but at smaller screen sizes
     * they visually tear against a stable branch backdrop. */
    private static final float PARTIAL_EXACT_MIN_SCREEN_PIXELS = 16.0f;
    /** Layered continuity is committed at Minecraft chunk granularity. */
    private static final int PROJECTION_TILE_SIZE = 16;
    private static final int PROJECTION_TILES_PER_PAGE =
            CaveTextureAtlas.PAGE_SIZE / PROJECTION_TILE_SIZE;
    private static final int PROJECTION_TILE_COUNT =
            PROJECTION_TILES_PER_PAGE * PROJECTION_TILES_PER_PAGE;
    private static final long PROJECTION_TILE_ROW_MASK = 0xFFFFL;
    private static final MapRequestLane[] REQUEST_LANES = MapRequestLane.values();
    private static final UnifiedCaveTextureManager INSTANCE = new UnifiedCaveTextureManager();

    private final CaveTileRepository repository = CaveTileRepository.getInstance();
    private final CaveTelemetry telemetry = CaveTelemetry.getInstance();
    private final MapPipelineTelemetry pipelineTelemetry = MapPipelineTelemetry.getInstance();
    private final CaveTextureAtlas atlas = new CaveTextureAtlas();
    private final CaveRegionImageCache regionImageCache =
            CaveRegionImageCache.getInstance();
    private final ConcurrentLinkedQueue<RegionCacheInstall> completedRegionImages =
            new ConcurrentLinkedQueue<>();
    /** Render-thread scratch for choosing the earliest READY CIMG page globally. */
    private final ArrayList<RegionCacheInstall> regionImageInstallScratch =
            new ArrayList<>(MAX_COMPLETED_REGION_IMAGES);
    private final Set<CaveRegionImageCache.Key> pendingRegionImageReads =
            ConcurrentHashMap.newKeySet();
    /** Keys remain owned from decode completion until every cached page is consumed. */
    private final Set<CaveRegionImageCache.Key> queuedRegionImageInstalls =
            ConcurrentHashMap.newKeySet();
    private final Set<CaveRegionImageCache.Key> pendingRegionImageWrites =
            ConcurrentHashMap.newKeySet();
    private final Map<CaveRegionImageCache.Key, Long> regionImageMissUntil =
            new ConcurrentHashMap<>();
    /**
     * A decoded CIMG snapshot is consumed at most once per file timestamp. PASS73
     * reread the same stale 1 MiB region image every 250 ms and skipped its pages
     * again, producing thousands of no-progress IO/decode cycles.
     */
    private final Map<CaveRegionImageCache.Key, Long>
            consumedRegionImageTimestamps = new ConcurrentHashMap<>();
    /** Recursive L1-L5 branch tree updated incrementally from leaf-page patches. */
    private final CaveLodTree lodTree = new CaveLodTree();
    /** Render-thread scratch reused across every page publication. */
    private final int[][] uploadScratchLods = allocateLodBuffers();
    /** Candidate coverage staged into CaveLodTree before exact GPU admission. */
    private final long[] branchKnownRowsScratch =
            new long[CaveTextureAtlas.PAGE_SIZE];
    private final Map<PageKey, PageInfo> pages = new LinkedHashMap<>(128, 0.75f, true);
    /**
     * O(1) resident-subtree index. The old implementation scanned every resident
     * page for every missing branch node, turning far-zoom cave rendering into
     * O(visibleNodes × residentPages) work.
     */
    private final java.util.concurrent.ConcurrentHashMap<ResidentNodeKey, Integer> residentNodeCounts =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<PageKey, PageRequest> requests = new LinkedHashMap<>(128, 0.75f, true);
    private final Map<PageKey, Long> revisions = new HashMap<>();
    /** Exact Top-Y retained for the active and immediately previous Layered bands. */
    private final Map<ProjectionBandKey, Integer> activeLayerProjections = new HashMap<>();
    /** Current/previous Layered band per dimension, used as a two-generation CPU working set. */
    private final Map<String, Integer> activeLayerBandByDimension = new HashMap<>();
    private final Map<String, Integer> previousLayerBandByDimension = new HashMap<>();
    private final List<PageInfo> deferredCloses = new ArrayList<>();
    /** Strict publication ordering: minimap completions can never sit behind a
     * large FIFO burst of fullscreen builds. */
    private final PriorityBlockingQueue<CompletedBuild> completedBuilds =
            new PriorityBlockingQueue<>();
    /**
     * Xaero derives/keeps parent textures independently from leaf residency. Region
     * pages therefore enter the cave LOD tree immediately, while exact atlas
     * publication drains from this bounded render-thread backlog.
     */
    private final LinkedHashMap<PageKey, CaveRegionProjectionService.ProjectedPage>
            regionExactBacklog = new LinkedHashMap<>(256, 0.75f, true);
    private final Map<RegionBranchKey, Long> stagedRegionBranchRevisions =
            new HashMap<>();
    /** Render-thread scratch used to skip temporarily ineligible completions
     * without letting one lane monopolise the publication queue. */
    private final ArrayList<CompletedBuild> completedPollScratch =
            new ArrayList<>(MAX_PAGES);
    private final List<PageRequest> candidateBuffer = new ArrayList<>(128);
    private final AtomicLong completedSequence = new AtomicLong();
    /** Exact-cave render revision independent from unrelated surface/legacy uploads. */
    private final AtomicLong exactTopologyRevision = new AtomicLong();
    private final EnumMap<MapRequestLane, VisiblePlanner> visiblePlans =
            new EnumMap<>(MapRequestLane.class);
    /** Stable writer ownership, deliberately longer-lived than PageRequest pulses. */
    private final EnumMap<MapRequestLane, CavePresentationGeneration>
            loadingPresentationGenerations = new EnumMap<>(MapRequestLane.class);
    private final EnumMap<MapRequestLane, CavePresentationGeneration>
            displayedPresentationGenerations = new EnumMap<>(MapRequestLane.class);
    private final EnumMap<MapRequestLane, CavePresentationGeneration>
            previousPresentationGenerations = new EnumMap<>(MapRequestLane.class);
    /** Render-thread-owned dirty-region debounce table. */
    private final Map<CaveRegionImageCache.Key, Long> dirtyRegionImages =
            new LinkedHashMap<>();

    private int renderBatchDepth;
    /** Monotonic renderer epoch; current/previous-frame leaves are never victims. */
    private long renderEpoch;
    /** Monotonic first-residency order used to distinguish old cached leaves from
     * newly revealed leaves in the active fullscreen scanline generation. */
    private long gpuPublicationSequence;
    private long lastUploadMs;
    /** Actual render-call cadence used to cap texture work to a frame share. */
    private long lastUploadFrameNanos;
    private volatile long styleRefreshUntilMs;
    private long observedAtlasGeneration = Long.MIN_VALUE;
    /** Global cadence aligns Layered fullscreen page commits into one viewport batch. */
    private long nextFullscreenLayeredPublicationMs;
    private int observedFullscreenLayeredProjectionTopY = Integer.MIN_VALUE;
    private boolean fullscreenLayeredPublicationWindowOpen = true;
    private volatile long regionImageEpoch = 1L;
    /** Working-set authority used to evict historical mode residency first. */
    private volatile CaveView preferredView;
    private volatile String preferredDimension = "";
    private long presentationGenerationSequence;
    private long writerViewportSequence;

    private UnifiedCaveTextureManager() {
        for (MapRequestLane lane : REQUEST_LANES) {
            visiblePlans.put(lane, new VisiblePlanner());
        }
    }

    public static UnifiedCaveTextureManager getInstance() {
        return INSTANCE;
    }

    /** Called once after the GPU page-table swap for safe atlas-slot reuse. */
    public void onPageTableFrameBoundary() {
        atlas.onPageTableFrameBoundary();
        lodTree.onPageTableFrameBoundary();
    }

    /**
     * Transfers visible ownership to the selected cave view. Inactive exact and
     * projection CPU products are parked for reuse, while their GPU exact/branch
     * residency is released so FULL and LAYERED do not compete for presentation
     * atlas space.
     */
    public void onModeChanged(CaveView nextView) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> onModeChanged(nextView));
            return;
        }
        int parkedInactiveProducts = 0;
        String activatedDimension = dimension();
        synchronized (pages) {
            preferredDimension = activatedDimension;
            preferredView = nextView;
            for (VisiblePlanner planner : visiblePlans.values()) planner.clear();
            loadingPresentationGenerations.entrySet().removeIf(entry -> {
                CavePresentationGeneration generation = entry.getValue();
                return generation == null
                        || activatedDimension.equals(generation.dimension())
                                && generation.view() != nextView;
            });
            regionExactBacklog.entrySet().removeIf(entry ->
                    activatedDimension.equals(entry.getKey().dimension())
                            && entry.getKey().view() != nextView);
            /*
             * Keep coherent branch source stamps for parked views. The LOD tree
             * already retains their CPU pixels while releasing GPU residency;
             * deleting the stamp here made a FULL <-> LAYERED return derive the
             * identical hierarchy again even when its source revision was unchanged.
             * Actual page eviction below removes the bounded stamp with PageInfo.
             */
            completedBuilds.removeIf(completed ->
                    activatedDimension.equals(
                            completed.info().key.dimension())
                            && completed.info().key.view() != nextView);
            completedPollScratch.removeIf(completed ->
                    activatedDimension.equals(
                            completed.info().key.dimension())
                            && completed.info().key.view() != nextView);

            /*
             * Xaero separates the currently loaded cave presentation from retained
             * written tiles. PASS123 instead closed every exact CPU product when
             * switching FULL <-> LAYERED, so returning to a view rebuilt the same
             * projection/style/LOD payload from the immutable archive. Park inactive
             * products: revoke work ownership and GPU residency, but keep coherent
             * frontLods/revision state for a cheap restore on the next visit.
             */
            for (PageInfo info : pages.values()) {
                if (!activatedDimension.equals(info.key.dimension())) continue;
                if (nextView != null && info.key.view() == nextView) continue;
                if (info.pending != null) detachPendingLocked(info, true);
                PageRequest request = requests.get(info.key);
                if (request != null) removeRequestOwnershipLocked(info.key, request);
                info.releaseAtlasSlot();
                info.nextRetryMs = 0L;
                parkedInactiveProducts++;
            }
            long now = System.currentTimeMillis();
            removeExpiredRequestsLocked(now);
            nextFullscreenLayeredPublicationMs = 0L;
            observedFullscreenLayeredProjectionTopY = Integer.MIN_VALUE;
            fullscreenLayeredPublicationWindowOpen = true;
        }
        CaveRegionProjectionService.getInstance().activateView(
                activatedDimension, nextView);
        int retiredProjectionEntries = nextView == CaveView.FULL
                ? CaveProjectionServiceV2.getInstance().activateFull() : 0;

        // Inactive branch GPU residency is presentation state; its bounded CPU
        // hierarchy is reusable written work. Park GPU/queues so the selected view
        // owns the atlas while retained pixels can restore without re-derivation.
        int parkedBranchNodes = lodTree.parkInactiveViews(
                activatedDimension, nextView);
        exactTopologyRevision.incrementAndGet();
        trimPages();
        if (parkedInactiveProducts > 0 || parkedBranchNodes > 0) {
            MapDebugRecorder.getInstance().event("CAVE_INACTIVE_VIEW_PRODUCTS_PARKED",
                    "view=" + nextView + " parked=" + parkedInactiveProducts
                            + " parked_branch_nodes=" + parkedBranchNodes
                            + " retired_projection_entries="
                            + retiredProjectionEntries);
        }
    }



    /**
     * Revision used by cave render-plan caching. It changes only for cave exact or
     * cave branch residency, so Surface/legacy publication cannot invalidate a
     * stable Cave Map plan every tick.
     */
    public long contentRevision() {
        return exactTopologyRevision.get() + lodTree.contentRevision();
    }

    /** Branch-only far zoom must not rebuild for unrelated exact-page uploads. */
    public long branchContentRevision() {
        return lodTree.contentRevision();
    }

    public int requestCount() {
        synchronized (pages) {
            return requests.size();
        }
    }

    /** Stops a hidden viewport lane from retaining priority or publication work. */
    public void suspendLane(MapRequestLane lane) {
        if (lane == null) return;
        synchronized (pages) {
            long now = System.currentTimeMillis();
            for (PageRequest request : requests.values()) request.clearLane(lane);
            // Planner ownership is part of pending-build lifetime in PASS129. Clear
            // the hidden lane before pruning requests so a stale viewport cannot
            // accidentally keep its own work alive.
            VisiblePlanner planner = visiblePlans.get(lane);
            if (planner != null) planner.clear();
            removeExpiredRequestsLocked(now);
            // Future-aware scheduling now gives cancellation a terminal callback.
            // Once this lane is hidden, detach only builds that no other live lane
            // still owns. Retaining them until completion caused hundreds of stale
            // results after repeated Surface/Layered/Full transitions and let the
            // diagnostic BUILDING state grow while every worker queue was empty.
            for (PageInfo info : pages.values()) {
                if (info.pending == null || info.pendingLane != lane) continue;
                if (isPendingStillWantedLocked(info, now)) continue;
                detachPendingLocked(info, true);
            }
        }
    }

    public int pendingBuildCount() {
        return (int) (pendingBuildCounts() >>> 32);
    }

    private int pendingBuildCount(MapRequestLane lane) {
        synchronized (pages) {
            int count = 0;
            // Every attached build is owned by either a live PageRequest or a
            // current visible planner window. The request map itself remains bounded
            // to the lane shortlists, while the page
            // cache can contain thousands of historical entries at far zoom.
            for (PageRequest request : requests.values()) {
                PageInfo info = pages.get(request.key);
                if (info != null && info.pending != null
                        && info.pendingLane == lane) count++;
            }
            return count;
        }
    }

    /** Packs total pending builds in the high word and minimap builds in the low. */
    private long pendingBuildCounts() {
        synchronized (pages) {
            int total = 0;
            int minimap = 0;
            for (PageRequest request : requests.values()) {
                PageInfo info = pages.get(request.key);
                if (info == null || info.pending == null) continue;
                total++;
                if (info.pendingLane == MapRequestLane.MINIMAP) minimap++;
            }
            return ((long) total << 32) | (minimap & 0xFFFF_FFFFL);
        }
    }

    public DebugSnapshot debugSnapshot() {
        synchronized (pages) {
            int pending = 0;
            int initialized = 0;
            int partial = 0;
            int knownEmpty = 0;
            int resident = 0;
            for (PageInfo info : pages.values()) {
                if (info.pending != null) pending++;
                if (info.initialized) initialized++;
                if (info.initialized && info.knownColumns > 0
                        && info.knownColumns < CaveTextureAtlas.PAGE_SIZE
                                * CaveTextureAtlas.PAGE_SIZE) partial++;
                if (info.knownEmpty) knownEmpty++;
                if (info.initialized && info.atlasSlot >= 0) resident++;
            }
            VisiblePlanner fullscreen = visiblePlans.get(MapRequestLane.FULLSCREEN);
            return new DebugSnapshot(pages.size(), requests.size(), pending,
                    completedBuilds.size(), initialized, partial, knownEmpty, resident,
                    fullscreen == null ? 0 : fullscreen.updateSliceIndex,
                    fullscreen == null ? 0 : fullscreen.pagePlan.length);
        }
    }

    private boolean hasActiveRequest(MapRequestLane lane, long now) {
        synchronized (pages) {
            for (PageRequest request : requests.values()) {
                if (request.effectiveLane(now) == lane) return true;
            }
            return false;
        }
    }

    private static boolean plannerActive(VisiblePlanner planner, long now) {
        return planner != null && planner.pagePlan.length > 0
                && now - planner.lastDemandMs <= ACTIVE_PLANNER_GRACE_MS;
    }

    /**
     * Cave minimap ownership must follow the current small writer window, not ten
     * seconds of player travel. Xaero continually rewrites a compact near-player
     * set; retaining old MINIMAP leaves for the shared generic lane TTL kept
     * hundreds of exact requests alive and competing for branch/GPU residency.
     */
    private static long requestLeaseMs(MapRequestLane lane) {
        return lane == MapRequestLane.MINIMAP ? 2_000L : lane.requestTtlMs();
    }

    public void requestVisiblePages(CaveView view, int layerY,
            double minX, double maxX, double minZ, double maxZ, float scale) {
        requestVisiblePages(view, layerY, minX, maxX, minZ, maxZ, scale,
                MapRequestLane.FULLSCREEN);
    }

    public void requestVisiblePages(CaveView view, int layerY,
            double minX, double maxX, double minZ, double maxZ, float scale,
            MapRequestLane lane) {
        requestVisiblePages(view, layerY, minX, maxX, minZ, maxZ, scale,
                (minX + maxX) * 0.5, (minZ + maxZ) * 0.5, lane);
    }

    public void requestVisiblePages(CaveView view, int layerY,
            double minX, double maxX, double minZ, double maxZ, float scale,
            double focusX, double focusZ, MapRequestLane lane) {
        if (MapActivityGate.getInstance().blocksForegroundStreaming()) return;
        layerY = projectionTopY(view, layerY);
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        String currentDimension = dimension();
        boolean ownsActiveProjection =
                CaveProjectionOwnershipPolicy.ownsActiveProjection(effectiveLane);
        if (ownsActiveProjection
                && (preferredView != view
                        || !currentDimension.equals(preferredDimension))) {
            onModeChanged(view);
        }
        VisiblePlanner planner = visiblePlans.get(effectiveLane);

        MapViewportDemandPolicy.Bounds admittedViewport =
                MapViewportDemandPolicy.trimEdgeSlivers(
                        minX, maxX, minZ, maxZ, effectiveLane);
        double visibleMinX = admittedViewport.minX();
        double visibleMaxX = admittedViewport.maxX();
        double visibleMinZ = admittedViewport.minZ();
        double visibleMaxZ = admittedViewport.maxZ();
        int minPageX = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(visibleMinX));
        int maxPageX = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(Math.nextDown(visibleMaxX)));
        int minPageZ = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(visibleMinZ));
        int maxPageZ = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(Math.nextDown(visibleMaxZ)));
        int rawMinPageX = minPageX;
        int rawMaxPageX = maxPageX;
        int rawMinPageZ = minPageZ;
        int rawMaxPageZ = maxPageZ;
        int normalizedLayerY = normalizedLayer(view, layerY);
        long now = System.currentTimeMillis();
        CavePresentationGeneration presentationGeneration =
                ensurePresentationGeneration(effectiveLane, currentDimension,
                        view, normalizedLayerY, layerY);
        planner.lastDemandMs = now;
        int rawFocusPageX = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(focusX));
        int rawFocusPageZ = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(focusZ));
        if (effectiveLane == MapRequestLane.FULLSCREEN) {
            if (planner.containsVisibleViewport(currentDimension, view,
                    normalizedLayerY, layerY,
                    rawMinPageX, rawMaxPageX, rawMinPageZ, rawMaxPageZ)) {
                // Keep the existing halo window while the real screen remains
                // inside it. Previously the halo shifted by one page whenever the
                // camera crossed a 64-block boundary, rebuilding a plan and handing
                // off hundreds of source/build owners despite almost total overlap.
                minPageX = planner.minPageX;
                maxPageX = planner.maxPageX;
                minPageZ = planner.minPageZ;
                maxPageZ = planner.maxPageZ;
            } else {
                minPageX -= FULLSCREEN_STICKY_HALO_PAGES;
                maxPageX += FULLSCREEN_STICKY_HALO_PAGES;
                minPageZ -= FULLSCREEN_STICKY_HALO_PAGES;
                maxPageZ += FULLSCREEN_STICKY_HALO_PAGES;
            }
        }
        if (effectiveLane == MapRequestLane.MINIMAP) {
            /*
             * PASS156 / Xaero parity: cave gameplay is a bounded writer, not a
             * second fullscreen importer. Xaero clamps cave MinimapWriter to the
             * centre +/-2 map chunks (each map chunk is 4x4 Minecraft chunks, the
             * same 64x64 footprint as one Simple Map page). Keep at most 5x5 exact
             * pages around the player; shared retained branches can cover farther
             * zoom without making gameplay decode/project the whole screen.
             */
            minPageX = Math.max(minPageX,
                    rawFocusPageX - MapViewLoadPlanner.CAVE_MINIMAP_MAX_RADIUS_PAGES);
            maxPageX = Math.min(maxPageX,
                    rawFocusPageX + MapViewLoadPlanner.CAVE_MINIMAP_MAX_RADIUS_PAGES);
            minPageZ = Math.max(minPageZ,
                    rawFocusPageZ - MapViewLoadPlanner.CAVE_MINIMAP_MAX_RADIUS_PAGES);
            maxPageZ = Math.min(maxPageZ,
                    rawFocusPageZ + MapViewLoadPlanner.CAVE_MINIMAP_MAX_RADIUS_PAGES);
            if (minPageX > maxPageX) minPageX = maxPageX = rawFocusPageX;
            if (minPageZ > maxPageZ) minPageZ = maxPageZ = rawFocusPageZ;
        }
        int centerPageX = clamp(rawFocusPageX, minPageX, maxPageX);
        int centerPageZ = clamp(rawFocusPageZ, minPageZ, maxPageZ);
        if (view == CaveView.LAYERED && ownsActiveProjection) {
            activateLayerProjection(currentDimension, normalizedLayerY, layerY);
        }
        boolean focusChanged = centerPageX != planner.focusPageX
                || centerPageZ != planner.focusPageZ;
        boolean branchOnlyPlan = CaveScreenSpacePolicy.branchOnly(
                scale, effectiveLane);
        int preferredBranchLevel = Math.max(1, MapLodPolicy.branchLevel(
                scale, CaveLodTree.RENDER_MAX_LEVEL));
        if (effectiveLane == MapRequestLane.MINIMAP) {
            lodTree.prioritizeMinimap(currentDimension, view,
                    normalizedLayerY, preferredBranchLevel);
        }
        boolean branchPolicyChanged = planner.branchOnly != branchOnlyPlan;
        boolean branchLevelChanged = planner.preferredBranchLevel
                != preferredBranchLevel;
        boolean changed = !currentDimension.equals(planner.dimension)
                || view != planner.view
                || normalizedLayerY != planner.layerY
                || layerY != planner.projectionTopY
                || minPageX != planner.minPageX || maxPageX != planner.maxPageX
                || minPageZ != planner.minPageZ || maxPageZ != planner.maxPageZ
                || planner.fullscreen != (effectiveLane == MapRequestLane.FULLSCREEN)
                || branchPolicyChanged || branchLevelChanged
                || (effectiveLane != MapRequestLane.FULLSCREEN && focusChanged);
        boolean recentered = !changed && effectiveLane == MapRequestLane.FULLSCREEN
                && planner.shouldRecenter(centerPageX, centerPageZ,
                        FULLSCREEN_RECENTER_THRESHOLD_PAGES);
        if (!changed && !recentered && now - planner.lastEnumerationMs
                < CaveScreenSpacePolicy.exactEnumerationRetryMs(scale, effectiveLane)) return;
        planner.lastEnumerationMs = now;

        if (changed) {
            boolean continuousFullscreenPan = effectiveLane == MapRequestLane.FULLSCREEN
                    && !branchPolicyChanged
                    && planner.sameProjectionOverlap(currentDimension, view,
                            normalizedLayerY, layerY,
                            minPageX, maxPageX, minPageZ, maxPageZ);
            planner.reset(currentDimension, view, normalizedLayerY, layerY,
                    minPageX, maxPageX, minPageZ, maxPageZ,
                    centerPageX, centerPageZ, effectiveLane);
            if (effectiveLane == MapRequestLane.FULLSCREEN) {
                planner.baselineGpuPublicationSequence = gpuPublicationSequence;
                lodTree.prioritizeViewport(currentDimension, view,
                        normalizedLayerY, minPageX, maxPageX,
                        minPageZ, maxPageZ, preferredBranchLevel);
            }
            if (continuousFullscreenPan) {
                handoffFullscreenViewport(planner);
            } else {
                retireLaneOutside(effectiveLane, currentDimension, view,
                        normalizedLayerY, minPageX, maxPageX, minPageZ, maxPageZ);
            }
        } else if (recentered) {
            planner.recenter(centerPageX, centerPageZ, effectiveLane);
            planner.baselineGpuPublicationSequence = gpuPublicationSequence;
            lodTree.prioritizeViewport(currentDimension, view,
                    normalizedLayerY, minPageX, maxPageX,
                    minPageZ, maxPageZ, preferredBranchLevel);
            handoffFullscreenViewport(planner);
        } else {
            planner.focusPageX = centerPageX;
            planner.focusPageZ = centerPageZ;
        }
        planner.scale = scale;
        planner.branchOnly = branchOnlyPlan;
        planner.preferredBranchLevel = preferredBranchLevel;
        if (changed || recentered) {
            /*
             * PASS128: native-region writer ownership follows both minimap and
             * fullscreen viewport changes. PASS127 only fenced FULLSCREEN, so after
             * closing/panning the map hundreds of stale FULLSCREEN offers survived
             * until the consumer rejected them against the MINIMAP planner.
             */
            CaveRegionProjectionService.getInstance().activateViewport(
                    currentDimension, view, layerY,
                    planner.minPageX, planner.maxPageX,
                    planner.minPageZ, planner.maxPageZ,
                    planner.focusPageX, planner.focusPageZ, effectiveLane,
                    presentationGeneration);
        }
        if (effectiveLane == MapRequestLane.FULLSCREEN && (changed || recentered)) {
            synchronized (pages) {
                releaseObsoleteFullscreenResidencyLocked(planner, now);
                if (planner.branchOnly) {
                    retireBranchOnlyExactWorkLocked(planner, now);
                }
                regionExactBacklog.entrySet().removeIf(entry ->
                        !planner.matches(entry.getKey())
                                || entry.getValue().projectionTopY()
                                        != planner.projectionTopY);
                completedBuilds.removeIf(completed ->
                        completed.lane() == MapRequestLane.FULLSCREEN
                                && !planner.matches(completed.info().key)
                                && !completed.info().initialized);
                // Preserve already-complete overlap immediately; only genuinely new
                // gaps participate in the visible scanline wave.
                refreshFullscreenPublicationFrontier(planner,
                        Integer.MAX_VALUE, "planner-reset-resident-seed");
            }
        }

        // Xaero fills its region-read pipeline before leaf refinement starts. Do the
        // same for CIMG: enumerate unique 512x512 regions independently from the
        // exact-page cursor so a 2,000-2,500 block viewport can keep all disk readers
        // busy instead of discovering the next cache file only after 64 page admits.
        admitVisibleRegionImageReads(planner, effectiveLane, now);

        boolean pressuredAdmission = MapPerformanceGovernor.getInstance().underPressure();
        int admissionBudget = branchOnlyPlan
                ? CaveScreenSpacePolicy.sourceAdmissionBudget(
                        scale, effectiveLane, pressuredAdmission)
                : CaveScreenSpacePolicy.exactAdmissionBudget(
                        scale, effectiveLane, pressuredAdmission);
        if (admissionBudget <= 0) return;

        if (effectiveLane == MapRequestLane.FULLSCREEN) {
            // Fullscreen keeps a bounded fixed-region build-ahead window. Request
            // ownership is created only inside that window; the cursor parks at the
            // first future ordinal until the contiguous publication prefix advances.
            if (planner.pagePlan.length == 0) return;
            if (planner.pageCursor >= planner.pagePlan.length) {
                if (now < planner.nextRestartMs) return;
                planner.pageCursor = 0;
            }
            int admitted = 0;
            int considered = 0;
            int considerationBudget = Math.max(16, admissionBudget * 8);
            while (planner.pageCursor < planner.pagePlan.length
                    && admitted < admissionBudget
                    && considered < considerationBudget) {
                int ordinal = planner.pageCursor;
                /*
                 * PASS110 / Xaero local publication:
                 * The writer cursor must advance even when one earlier coordinate is
                 * unresolved. PASS109 parked the entire fullscreen viewport at a
                 * global contiguous-prefix barrier (typically ordinal 127/128). CPU
                 * completions behind that one missing source then accumulated until
                 * MapScreen closed, at which point MINIMAP could publish them
                 * immediately. Xaero bounds live work with a small writer queue but
                 * never makes tile B depend on tile A being present. The existing
                 * FULLSCREEN shortlist and pending-build caps (640) provide the
                 * bounded ownership; let this persistent cursor skip/advance.
                 */
                long packedPage = planner.pagePlan[planner.pageCursor++];
                considered++;
                int pageX = CaveLoadHierarchy.x(packedPage);
                int pageZ = CaveLoadHierarchy.z(packedPage);
                if (CaveWorldSaveReader.getInstance().isFullscreenPageKnownAbsent(
                        currentDimension, view, normalizedLayerY, layerY,
                        pageX, pageZ)) {
                    continue;
                }
                PageKey candidateKey = key(view, layerY, pageX, pageZ);
                if (isPageDisplayReady(candidateKey)) continue;
                if (!branchOnlyPlan
                        && isPageActive(candidateKey, effectiveLane, now)) continue;
                int priority = effectiveLane.priorityBase() + 220_000
                        - Math.min(180_000, ordinal * 250);
                boolean sourceReady = repository.isPageProjectionReady(
                        view, layerY, pageX, pageZ);
                if (!sourceReady) {
                    requestRegionImageCache(candidateKey, layerY, effectiveLane,
                            priority, now);
                    /*
                     * PASS157: branch-only rendering never consumes exact CVD leaves.
                     * PASS156 still scanned a 6x6 chunk cache window for up to 48 leaf
                     * coordinates per pulse while the renderer emitted only coarse
                     * quads. CIMG/branch IO is already admitted independently above;
                     * missing coarse coverage is filled by the bounded native source
                     * writer. Do not allocate HashSets and hydrate exact display tiles
                     * merely because the camera is zoomed out.
                     */
                    if (!branchOnlyPlan) {
                        repository.requestDisplayPageLoad(view, layerY, pageX, pageZ,
                                effectiveLane);
                    }
                }
                if (!branchOnlyPlan) {
                    requestPage(view, layerY, pageX, pageZ,
                            priority, effectiveLane, now, ordinal);
                    pipelineTelemetry.recordPageAdmission(effectiveLane);
                } else {
                    // Wide fullscreen views consume the branch hierarchy. The native
                    // region transaction already owns source projection and feeds the
                    // branch tree directly, so creating hundreds of exact PageInfo and
                    // tracker entries here only raises heap pressure without adding a
                    // visible leaf. CIMG/source replay still runs above.
                    synchronized (pages) {
                        PageRequest obsolete = requests.get(candidateKey);
                        if (obsolete != null) obsolete.clearLane(effectiveLane);
                    }
                }
                admitted++;
            }
            planner.updateSliceIndex = planner.pageCursor
                    / MapViewLoadPlanner.FULLSCREEN_SLICE_SIZE;
            if (planner.pageCursor >= planner.pagePlan.length) {
                planner.requestCompletedCycles++;
                planner.nextRestartMs = now
                        + CaveScreenSpacePolicy.completedPlanPauseMs(
                                scale, effectiveLane);
            }
            return;
        }

        // Minimap/background retain centre-out ordering and may admit a few exact
        // leaves because the visible footprint is intentionally small.
        if (planner.pageCursor >= planner.pagePlan.length) {
            if (now < planner.nextRestartMs) return;
            planner.pageCursor = 0;
        }
        int admitted = 0;
        while (planner.pageCursor < planner.pagePlan.length
                && admitted < admissionBudget) {
            int ordinal = planner.pageCursor;
            long packedPage = planner.pagePlan[planner.pageCursor++];
            int pageX = CaveLoadHierarchy.x(packedPage);
            int pageZ = CaveLoadHierarchy.z(packedPage);
            PageKey candidateKey = key(view, layerY, pageX, pageZ);
            if (isPageSatisfied(candidateKey)) continue;
            int distancePenalty = Math.min(800_000,
                    squaredDistance(pageX, pageZ, centerPageX, centerPageZ) * 2_000);
            int priority = effectiveLane.priorityBase() + 180_000
                    - distancePenalty - ordinal * 250;
            requestRegionImageCache(candidateKey, layerY, effectiveLane,
                    priority, now);
            repository.requestDisplayPageLoad(view, layerY, pageX, pageZ,
                    effectiveLane);
            requestPage(view, layerY, pageX, pageZ, priority,
                    effectiveLane, now);
            pipelineTelemetry.recordPageAdmission(effectiveLane);
            admitted++;
        }
        if (planner.pageCursor >= planner.pagePlan.length) {
            planner.nextRestartMs = now
                    + CaveScreenSpacePolicy.completedPlanPauseMs(scale, effectiveLane);
        }
    }

    private void admitVisibleRegionImageReads(VisiblePlanner planner,
            MapRequestLane lane, long now) {
        if (planner == null || planner.regionPlan.length == 0) return;
        boolean pressured = MapPerformanceGovernor.getInstance().underPressure();
        boolean headroom = MapPerformanceGovernor.getInstance().hasStreamingHeadroom();
        int budget = lane == MapRequestLane.FULLSCREEN
                ? (pressured ? 1 : headroom ? 8 : 4)
                : (pressured ? 1 : 2);
        if (planner.regionCursor >= planner.regionPlan.length) {
            if (now < planner.nextRegionRestartMs) return;
            planner.regionCursor = 0;
        }
        int admitted = 0;
        int considered = 0;
        while (planner.regionCursor < planner.regionPlan.length
                && admitted < budget && considered < budget * 3) {
            int ordinal = planner.regionCursor++;
            considered++;
            long packedRegion = planner.regionPlan[ordinal];
            int regionX = CaveLoadHierarchy.x(packedRegion);
            int regionZ = CaveLoadHierarchy.z(packedRegion);
            PageKey representative = new PageKey(planner.dimension, planner.view,
                    planner.layerY, regionX * CaveLoadHierarchy.PAGES_PER_REGION,
                    regionZ * CaveLoadHierarchy.PAGES_PER_REGION);
            int priority = lane.priorityBase() + 520_000
                    - Math.min(260_000, ordinal * 2_000);
            if (requestRegionImageCache(representative,
                    planner.projectionTopY, lane, priority, now)) admitted++;
        }
        if (planner.regionCursor >= planner.regionPlan.length) {
            planner.nextRegionRestartMs = now + 250L;
        }
    }

    private boolean requestRegionImageCache(PageKey pageKey, int projectionTopY,
            MapRequestLane lane, int priority, long now) {
        CaveRegionImageCache.Key cacheKey = regionImageKey(pageKey, projectionTopY);
        Long blockedUntil = regionImageMissUntil.get(cacheKey);
        if (blockedUntil != null && blockedUntil > now) return false;
        if (queuedRegionImageInstalls.contains(cacheKey)) return false;
        if (pendingRegionImageReads.size() >= MAX_PENDING_REGION_IMAGE_READS
                || !pendingRegionImageReads.add(cacheKey)) return false;

        long requestEpoch = regionImageEpoch;
        long cacheGeneration = regionImageCache.generation();
        CompletableFuture<CaveRegionImageCache.RegionImage> future =
                MapWorkScheduler.tryIoFuture(lane, MapWorkScheduler.WorkType.DISK_READ,
                        priority + 300_000, 64,
                        () -> requestEpoch == regionImageEpoch
                                && cacheGeneration == regionImageCache.generation(),
                        () -> regionImageCache.load(cacheKey));
        if (future == null) {
            pendingRegionImageReads.remove(cacheKey);
            return false;
        }
        future.whenComplete((image, failure) -> {
            pendingRegionImageReads.remove(cacheKey);
            if (requestEpoch != regionImageEpoch
                    || cacheGeneration != regionImageCache.generation()) return;
            if (failure != null || image == null) {
                regionImageMissUntil.put(cacheKey,
                        System.currentTimeMillis() + REGION_IMAGE_MISS_RETRY_MS);
                return;
            }
            Long consumedTimestamp = consumedRegionImageTimestamps.get(cacheKey);
            if (consumedTimestamp != null
                    && consumedTimestamp.longValue() == image.sourceTimestampMs()) {
                return;
            }
            regionImageMissUntil.remove(cacheKey);
            long validPageMask = validRegionImagePageMask(image);
            int stalePages = Long.bitCount(image.pageMask() & ~validPageMask);
            if (validPageMask == 0L) {
                /*
                 * Consume this exact file generation once. A later CIMG rewrite has a
                 * new source timestamp and remains loadable. PASS82 instead pushed all
                 * 64 pages through the render thread and logged ~3,000 stale skips.
                 */
                consumedRegionImageTimestamps.put(
                        cacheKey, image.sourceTimestampMs());
                regionImageMissUntil.put(cacheKey,
                        System.currentTimeMillis() + REGION_IMAGE_MISS_RETRY_MS);
                if (stalePages > 0) {
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    if (recorder.shouldEmitEvent(
                            "CAVE_REGION_IMAGE_STALE_DISCARDED:" + cacheKey, 500L)) {
                        recorder.event("CAVE_REGION_IMAGE_STALE_DISCARDED",
                                "region=" + cacheKey.regionX() + ','
                                        + cacheKey.regionZ()
                                        + " view=" + cacheKey.view()
                                        + " stale_pages=" + stalePages
                                        + " valid_pages=0");
                    }
                }
                return;
            }
            // A decoded image owns roughly 1 MiB of int pixels. Keep the handoff
            // bounded even if the render thread is temporarily frame-starved.
            if (completedRegionImages.size() >= MAX_COMPLETED_REGION_IMAGES
                    || !queuedRegionImageInstalls.add(cacheKey)) {
                regionImageMissUntil.put(cacheKey,
                        System.currentTimeMillis() + 100L);
                return;
            }
            if (stalePages > 0) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent(
                        "CAVE_REGION_IMAGE_PARTIAL_STALE:" + cacheKey, 500L)) {
                    recorder.event("CAVE_REGION_IMAGE_PARTIAL_STALE",
                            "region=" + cacheKey.regionX() + ','
                                    + cacheKey.regionZ()
                                    + " view=" + cacheKey.view()
                                    + " stale_pages=" + stalePages
                                    + " valid_pages="
                                    + Long.bitCount(validPageMask));
                }
            }
            completedRegionImages.offer(new RegionCacheInstall(
                    image, lane == null ? MapRequestLane.FULLSCREEN : lane,
                    requestEpoch, validPageMask));
        });
        return true;
    }

    private long validRegionImagePageMask(
            CaveRegionImageCache.RegionImage image) {
        CaveRegionImageCache.Key key = image.key();
        long valid = 0L;
        long mask = image.pageMask();
        while (mask != 0L) {
            int ordinal = Long.numberOfTrailingZeros(mask);
            mask &= mask - 1L;
            int localPageX = ordinal & 7;
            int localPageZ = ordinal >>> 3;
            int globalPageX = key.regionX() * 8 + localPageX;
            int globalPageZ = key.regionZ() * 8 + localPageZ;
            long cached = image.pageSourceStamp(localPageX, localPageZ);
            long current = repository.getPageRevision(
                    key.view(), key.projectionTopY(), globalPageX, globalPageZ);
            // PASS147 / Xaero cache-first parity: a persisted CIMG is a visual
            // fallback and must not require replaying CVD/Anvil merely to prove the
            // same source stamp before it can be shown. If current source authority
            // is not resident yet (revision 0), replay the cached page optimistically
            // and keep exact demand alive. A known non-zero mismatch is genuinely
            // stale and remains rejected.
            if (cached != 0L && (current == 0L || cached == current)) {
                valid |= 1L << ordinal;
            }
        }
        return valid;
    }

    private int installCompletedRegionImages(int pageBudget, long deadline,
            long now) {
        int installed = 0;
        while (installed < pageBudget && System.nanoTime() < deadline) {
            /*
             * PASS156: asynchronous disk completion order is not presentation order.
             * CIMG remains the fastest product, but it must enter the same strict
             * top-left scanline commit frontier as fresh exact/branch work. A later
             * cached page therefore stays ready off-screen until every earlier page
             * has either published or proven empty.
             */
            regionImageInstallScratch.clear();
            RegionCacheInstall selected = null;
            RegionCacheInstall.EligiblePage selectedPage = null;
            int scanLimit = Math.min(MAX_COMPLETED_REGION_IMAGES,
                    Math.max(1, completedRegionImages.size()));
            for (int scan = 0; scan < scanLimit; scan++) {
                RegionCacheInstall pending = completedRegionImages.poll();
                if (pending == null) break;
                CaveRegionImageCache.Key cacheKey = pending.image.key();
                if (pending.epoch != regionImageEpoch
                        || !cacheKey.dimension().equals(dimension())
                        || cacheKey.styleSignature()
                                != CaveProjectionStyle.signature()) {
                    queuedRegionImageInstalls.remove(cacheKey);
                    continue;
                }

                VisiblePlanner planner = visiblePlans.get(pending.lane);
                RegionCacheInstall.EligiblePage candidate =
                        pending.peekEligiblePage(planner);
                if (candidate == null) {
                    if (pending.hasRemainingPages()) {
                        regionImageInstallScratch.add(pending);
                    } else {
                        queuedRegionImageInstalls.remove(cacheKey);
                    }
                    continue;
                }
                regionImageInstallScratch.add(pending);
                if (selectedPage == null
                        || candidate.planOrdinal()
                                < selectedPage.planOrdinal()) {
                    selected = pending;
                    selectedPage = candidate;
                }
            }

            if (selected == null || selectedPage == null) {
                for (RegionCacheInstall pending : regionImageInstallScratch) {
                    completedRegionImages.offer(pending);
                }
                break;
            }

            for (RegionCacheInstall pending : regionImageInstallScratch) {
                if (pending != selected) completedRegionImages.offer(pending);
            }
            selected.consume(selectedPage.localOrdinal());
            installCachedRegionPage(selected.image,
                    selectedPage.localOrdinal(), selected.lane);
            installed++;
            if (selected.lane == MapRequestLane.FULLSCREEN) {
                VisiblePlanner revealPlanner = visiblePlans.get(
                        MapRequestLane.FULLSCREEN);
                refreshFullscreenPublicationFrontier(revealPlanner, 1,
                        "cimg-prefix-commit");
            }
            CaveRegionImageCache.Key selectedKey = selected.image.key();
            if (selected.hasRemainingPages()) {
                completedRegionImages.offer(selected);
            } else {
                queuedRegionImageInstalls.remove(selectedKey);
                consumedRegionImageTimestamps.put(selectedKey,
                        selected.image.sourceTimestampMs());
            }
        }
        if (installed > 0) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent(
                    "CAVE_PACKAGED_CACHE_PUBLICATION_WAVE", 250L)) {
                recorder.event("CAVE_PACKAGED_CACHE_PUBLICATION_WAVE",
                        "pages=" + installed
                                + " queued_regions=" + completedRegionImages.size()
                                + " policy=async_cache_prepare_ordered_reveal"
                                + " pass=PASS170");
            }
        }
        return installed;
    }

    private void installCachedRegionPage(
            CaveRegionImageCache.RegionImage image, int ordinal,
            MapRequestLane lane) {
        CaveRegionImageCache.Key cacheKey = image.key();
        int localPageX = ordinal & 7;
        int localPageZ = ordinal >>> 3;
        int globalPageX = cacheKey.regionX() * 8 + localPageX;
        int globalPageZ = cacheKey.regionZ() * 8 + localPageZ;
        PageKey pageKey = new PageKey(cacheKey.dimension(), cacheKey.view(),
                cacheKey.normalizedLayer(), globalPageX, globalPageZ);
        long now = System.currentTimeMillis();
        long cachedSourceRevision = image.pageSourceStamp(localPageX, localPageZ);
        long currentSourceRevision = repository.getPageRevision(
                cacheKey.view(), cacheKey.projectionTopY(),
                globalPageX, globalPageZ);
        boolean verifiedSource = currentSourceRevision != 0L
                && cachedSourceRevision == currentSourceRevision;
        if (cachedSourceRevision == 0L
                || currentSourceRevision != 0L && !verifiedSource) {
            // Source advanced after the region-level validation. Drop this one race;
            // the next CIMG timestamp or exact source publication will replace it.
            return;
        }
        synchronized (pages) {
            PageInfo info = pages.computeIfAbsent(pageKey, PageInfo::new);
            int fullColumns = CaveTextureAtlas.PAGE_SIZE * CaveTextureAtlas.PAGE_SIZE;
            // Do not replace a complete source-backed exact page. A partial exact
            // page is different: preserve visual continuity by showing the complete
            // cached CIMG underneath while its requested replacement continues.
            if (!info.regionImageFallback
                    && info.frontLods != null
                    && info.knownColumns >= fullColumns
                    && info.isProjectionAuthoritative(cacheKey.projectionTopY())
                    && (currentSourceRevision == 0L
                            || info.uploadedSourceRevision == currentSourceRevision)) {
                /*
                 * CPU ownership is not visual ownership. releaseAtlasSlot() keeps
                 * frontLods specifically so a later viewport can re-upload cheaply.
                 * PASS152 returned here merely because the retained pixels were
                 * authoritative, even when initialized=false/atlasSlot=-1. That
                 * permanently discarded every CIMG replay for the exact black page.
                 */
                if (info.knownEmpty || info.initialized && info.atlasSlot >= 0) {
                    return;
                }
                if (restoreCavePageResidency(info, lane)) {
                    recordVisualPublicationFenceRestore(info, lane,
                            "region_image_cpu_residency_restore");
                    return;
                }
            }
            long revision = revisions.computeIfAbsent(pageKey, ignored -> 1L);
            info.installRegionImage(cacheKey.projectionTopY(), image.pixels(),
                    localPageX, localPageZ,
                    image.pageEmptyProof(localPageX, localPageZ));
            info.uploadedRevision = revision;
            info.uploadedSourceRevision = verifiedSource
                    ? currentSourceRevision
                    : UNVERIFIED_REGION_IMAGE_SOURCE_REVISION;
            info.noSourceRevision = Long.MIN_VALUE;
            info.lastPublicationMs = now;
            if (verifiedSource) info.markSourceSettled(currentSourceRevision);
            updateBranch(info);
            if (!verifiedSource) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String eventKey = "CAVE_REGION_IMAGE_OPTIMISTIC_REPLAY:"
                        + cacheKey + ':' + ordinal;
                if (recorder.shouldEmitEvent(eventKey, 250L)) {
                    recorder.event("CAVE_REGION_IMAGE_OPTIMISTIC_REPLAY",
                            "region=" + cacheKey.regionX() + ',' + cacheKey.regionZ()
                                    + " page=" + globalPageX + ',' + globalPageZ
                                    + " view=" + cacheKey.view()
                                    + " top_y=" + cacheKey.projectionTopY()
                                    + " cached_source=" + cachedSourceRevision
                                    + " current_source=unresolved"
                                    + " policy=render_cache_then_verify");
                }
            }
            if (info.knownEmpty) {
                info.releaseAtlasSlot();
                ExactPageStateTracker.getInstance().transition(
                        stateKey(pageKey), ExactPageState.KNOWN_EMPTY, lane, revision);
                return;
            }
            restoreCavePageResidency(info, lane);
        }
    }

    private void markRegionImageDirty(PageInfo info, int projectionTopY, long now) {
        if (info == null || info.frontLods == null || info.regionImageFallback
                || !info.isProjectionAuthoritative(projectionTopY)) return;
        synchronized (pages) {
            dirtyRegionImages.put(regionImageKey(info.key, projectionTopY), now);
        }
    }

    private void scheduleRegionImageSave(long now, long deadline) {
        if (System.nanoTime() >= deadline || !pendingRegionImageWrites.isEmpty()) return;
        CaveRegionImageCache.Key selected = null;
        synchronized (pages) {
            for (Map.Entry<CaveRegionImageCache.Key, Long> entry
                    : dirtyRegionImages.entrySet()) {
                if (now - entry.getValue() < REGION_IMAGE_SAVE_DEBOUNCE_MS
                        || pendingRegionImageWrites.contains(entry.getKey())) continue;
                selected = entry.getKey();
                break;
            }
        }
        if (selected == null || System.nanoTime() >= deadline) return;
        CaveRegionImageCache.RegionImage snapshot = snapshotRegionImage(selected, now);
        if (snapshot == null) {
            synchronized (pages) {
                dirtyRegionImages.remove(selected);
            }
            return;
        }

        long writeEpoch = regionImageEpoch;
        long cacheGeneration = regionImageCache.generation();
        if (!pendingRegionImageWrites.add(selected)) return;
        CaveRegionImageCache.Key writeKey = selected;
        CompletableFuture<Boolean> future = MapWorkScheduler.tryIoFuture(
                MapRequestLane.BACKGROUND, MapWorkScheduler.WorkType.DISK_WRITE,
                MapRequestLane.BACKGROUND.priorityBase(), 128,
                () -> writeEpoch == regionImageEpoch
                        && cacheGeneration == regionImageCache.generation(),
                () -> regionImageCache.save(snapshot));
        if (future == null) {
            pendingRegionImageWrites.remove(writeKey);
            return;
        }
        synchronized (pages) {
            dirtyRegionImages.remove(writeKey);
        }
        future.whenComplete((saved, failure) -> {
            pendingRegionImageWrites.remove(writeKey);
            if (writeEpoch != regionImageEpoch
                    || cacheGeneration != regionImageCache.generation()) return;
            if (failure != null || !Boolean.TRUE.equals(saved)) {
                synchronized (pages) {
                    dirtyRegionImages.putIfAbsent(writeKey,
                            System.currentTimeMillis());
                }
            } else {
                regionImageMissUntil.remove(writeKey);
                consumedRegionImageTimestamps.remove(writeKey);
            }
        });
    }

    private CaveRegionImageCache.RegionImage snapshotRegionImage(
            CaveRegionImageCache.Key cacheKey, long now) {
        int[] regionPixels = new int[CaveRegionImageCache.PIXEL_COUNT];
        long[] pageSourceStamps = new long[CaveRegionImageCache.PAGE_COUNT];
        byte[] pageEmptyProofs = new byte[CaveRegionImageCache.PAGE_COUNT];
        long pageMask = 0L;
        synchronized (pages) {
            int firstPageX = cacheKey.regionX() * 8;
            int firstPageZ = cacheKey.regionZ() * 8;
            for (int localPageZ = 0; localPageZ < 8; localPageZ++) {
                for (int localPageX = 0; localPageX < 8; localPageX++) {
                    PageKey pageKey = new PageKey(cacheKey.dimension(),
                            cacheKey.view(), cacheKey.normalizedLayer(),
                            firstPageX + localPageX, firstPageZ + localPageZ);
                    PageInfo info = pages.get(pageKey);
                    if (info == null || info.frontLods == null
                            || !info.isProjectionAuthoritative(
                                    cacheKey.projectionTopY())) continue;
                    long requestedRevision = revisions.getOrDefault(pageKey, 1L);
                    long sourceRevision = repository.getPageRevision(
                            pageKey.view(), cacheKey.projectionTopY(),
                            pageKey.globalPageX(), pageKey.globalPageZ());
                    if (info.uploadedRevision != requestedRevision
                            || info.uploadedSourceRevision != sourceRevision) continue;
                    if (info.knownEmpty && !info.emptyProof.strong()) continue;
                    int ordinal = localPageZ * 8 + localPageX;
                    pageMask |= 1L << ordinal;
                    pageSourceStamps[ordinal] = sourceRevision;
                    pageEmptyProofs[ordinal] = (byte) info.emptyProof.persistedCode();
                    if (info.knownEmpty) continue;
                    int destinationX = localPageX * CaveTextureAtlas.PAGE_SIZE;
                    int destinationZ = localPageZ * CaveTextureAtlas.PAGE_SIZE;
                    for (int row = 0; row < CaveTextureAtlas.PAGE_SIZE; row++) {
                        System.arraycopy(info.frontLods[0],
                                row * CaveTextureAtlas.PAGE_SIZE, regionPixels,
                                (destinationZ + row)
                                        * CaveRegionImageCache.REGION_PIXELS
                                        + destinationX,
                                CaveTextureAtlas.PAGE_SIZE);
                    }
                }
            }
        }
        return pageMask == 0L ? null : new CaveRegionImageCache.RegionImage(
                cacheKey, pageMask, pageSourceStamps, pageEmptyProofs,
                regionPixels, now);
    }

    private static CaveRegionImageCache.Key regionImageKey(PageKey pageKey,
            int projectionTopY) {
        return new CaveRegionImageCache.Key(pageKey.dimension(), pageKey.view(),
                pageKey.layerY(), projectionTopY, CaveProjectionStyle.signature(),
                Math.floorDiv(pageKey.globalPageX(), 8),
                Math.floorDiv(pageKey.globalPageZ(), 8));
    }

    private static int squaredDistance(int pageX, int pageZ,
            int focusPageX, int focusPageZ) {
        int dx = pageX - focusPageX;
        int dz = pageZ - focusPageZ;
        return dx * dx + dz * dz;
    }

    /** Rebase every overlapping transaction. Source-waiting, queued, in-flight and
     * CPU-ready pages all represent reusable work; restricting retention to
     * {@code info.pending != null} repeatedly revoked pages before they acquired a
     * build slot and was the primary cause of black cave viewports while panning. */
    /**
     * Makes the active exact viewport the atlas working set. Historical pages,
     * inactive Top-Y pages and sticky-halo pages are CPU-cache entries, not GPU
     * foreground authority. Releasing them before admission prevents the fixed
     * exact atlas from stalling a viewport that itself fits in the selected profile.
     */
    private void releaseObsoleteFullscreenResidencyLocked(VisiblePlanner planner,
            long now) {
        if (planner == null || planner.pagePlan.length == 0) return;
        int released = 0;
        for (PageInfo info : pages.values()) {
            if (info.atlasSlot < 0 || !info.initialized) continue;
            boolean coordinateInViewport = info.key.dimension().equals(planner.dimension)
                    && info.key.view() == planner.view
                    && info.key.globalPageX() >= planner.minPageX
                    && info.key.globalPageX() <= planner.maxPageX
                    && info.key.globalPageZ() >= planner.minPageZ
                    && info.key.globalPageZ() <= planner.maxPageZ;
            boolean currentCoordinate = planner.matches(info.key);
            boolean currentProjection = currentCoordinate
                    && (planner.view == CaveView.FULL
                            || info.isProjectionAuthoritative(
                                    planner.projectionTopY));
            if (currentProjection) continue;

            /* PASS170 / Xaero loadedCaving: do not destroy the drawable product
             * while the replacement Top-Y is still loading. Same-band retargets use
             * PageInfo's tile-level last-good projection; cross-band retargets keep
             * exactly one previous band resident as the fullscreen underlay. The
             * previous CPU working-set was already retained by PASS159, but this
             * earlier atlas sweep still released its GPU slots, making that fallback
             * impossible and exposing black holes. */
            int previousBand = planner.view == CaveView.LAYERED
                    ? previousLayerBandByDimension.getOrDefault(
                            planner.dimension, Integer.MIN_VALUE)
                    : Integer.MIN_VALUE;
            boolean sameBandLastGood = currentCoordinate
                    && planner.view == CaveView.LAYERED
                    && info.canRenderLastGoodWithinBand(
                            planner.projectionTopY);
            boolean previousBandUnderlay = coordinateInViewport
                    && planner.view == CaveView.LAYERED
                    && previousBand != Integer.MIN_VALUE
                    && info.key.layerY() == previousBand;
            if (sameBandLastGood || previousBandUnderlay) continue;

            // Older history remains cache-backed and may release atlas residency.
            info.releaseAtlasSlot();
            released++;
        }
        if (released > 0) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent("CAVE_VIEWPORT_ATLAS_REBASE", 100L)) {
                recorder.event("CAVE_VIEWPORT_ATLAS_REBASE",
                        "released=" + released + " pages="
                                + planner.pagePlan.length
                                + " order=viewport_scanline_sweep_top_left");
            }
        }
    }

    /**
     * A density transition to branch-only must also retire the exact foreground
     * machinery left by the previous close-zoom plan. Keeping its requests,
     * completions and atlas slots alive made a wide viewport look player-centred
     * even after source planning had switched to the branch hierarchy. Persisted
     * CIMG data and immutable archive tiles remain untouched.
     */
    private void retireBranchOnlyExactWorkLocked(VisiblePlanner planner, long now) {
        if (planner == null || !planner.branchOnly) return;
        int retiredRequests = 0;
        int detachedBuilds = 0;
        int releasedSlots = 0;
        java.util.HashSet<PageKey> retiredKeys = new java.util.HashSet<>();
        for (PageRequest request : requests.values()) {
            if (!planner.matches(request.key)
                    || request.projectionTopY != planner.projectionTopY) continue;
            if (request.isLaneActive(MapRequestLane.FULLSCREEN, now)) {
                request.clearLane(MapRequestLane.FULLSCREEN);
                retiredRequests++;
            }
            if (request.isExpired(now)) retiredKeys.add(request.key);
        }
        for (PageKey retiredKey : retiredKeys) {
            PageRequest removed = requests.remove(retiredKey);
            if (removed == null) continue;
            ExactPageStateTracker.getInstance().removeIfState(
                    stateKey(retiredKey), ExactPageState.REQUESTED);
            PageInfo info = pages.get(retiredKey);
            if (info != null && info.pending != null) detachPendingLocked(info, true);
            if (!pages.containsKey(retiredKey)) revisions.remove(retiredKey);
        }

        for (PageInfo info : pages.values()) {
            if (!planner.matches(info.key)) continue;
            PageRequest remaining = requests.get(info.key);
            boolean ownedByMinimap = remaining != null
                    && remaining.isLaneActive(MapRequestLane.MINIMAP, now)
                    && remaining.projectionTopY == planner.projectionTopY;
            boolean replacementPublished = info.knownEmpty
                    || hasReplacementCoverage(info);
            if (!ownedByMinimap && replacementPublished && info.pending != null
                    && info.pendingLane == MapRequestLane.FULLSCREEN) {
                detachPendingLocked(info, true);
                detachedBuilds++;
            }
            /*
             * Xaero keeps the previous loaded texture until the replacement texture
             * is actually drawable. Do the same: branch-only is a density policy,
             * not permission to punch a hole while the L1 update is merely queued.
             */
            if (!ownedByMinimap && replacementPublished && info.atlasSlot >= 0) {
                info.releaseAtlasSlot();
                releasedSlots++;
            }
        }
        int backlogBefore = regionExactBacklog.size();
        regionExactBacklog.entrySet().removeIf(entry ->
                planner.matches(entry.getKey())
                        && entry.getValue().projectionTopY()
                                == planner.projectionTopY);
        int retiredBacklog = backlogBefore - regionExactBacklog.size();
        completedBuilds.removeIf(completed ->
                completed.lane() == MapRequestLane.FULLSCREEN
                        && planner.matches(completed.info().key)
                        && hasReplacementCoverage(completed.info()));
        completedPollScratch.removeIf(completed ->
                completed.lane() == MapRequestLane.FULLSCREEN
                        && planner.matches(completed.info().key)
                        && hasReplacementCoverage(completed.info()));

        if (retiredRequests > 0 || detachedBuilds > 0
                || releasedSlots > 0 || retiredBacklog > 0) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent("CAVE_BRANCH_ONLY_EXACT_RETIRE", 100L)) {
                recorder.event("CAVE_BRANCH_ONLY_EXACT_RETIRE",
                        "requests=" + retiredRequests
                                + " detached=" + detachedBuilds
                                + " slots=" + releasedSlots
                                + " backlog=" + retiredBacklog
                                + " pages=" + planner.pagePlan.length);
            }
        }
    }

    private void handoffFullscreenViewport(VisiblePlanner planner) {
        synchronized (pages) {
            long now = System.currentTimeMillis();
            int retained = 0;
            int retired = 0;
            int detached = 0;
            for (PageRequest request : requests.values()) {
                int ordinal = planner.ordinalOf(
                        request.key.globalPageX(), request.key.globalPageZ());
                boolean overlapping = ordinal >= 0
                        && request.projectionTopY == planner.projectionTopY;
                if (overlapping) {
                    int priority = MapRequestLane.FULLSCREEN.priorityBase() + 220_000
                            - Math.min(180_000, ordinal * 250);
                    request.rebase(MapRequestLane.FULLSCREEN,
                            priority, now, ordinal);
                    retained++;
                } else {
                    request.clearLane(MapRequestLane.FULLSCREEN);
                    retired++;
                }
            }
            removeExpiredRequestsLocked(now);

            // pendingLane records where a task started, not who owns it now. Check
            // current request ownership for every pending page so a historical lane
            // cannot retain an exact-build slot after viewport handoff.
            for (PageInfo info : pages.values()) {
                if (info.pending == null || isPendingStillWantedLocked(info, now)) continue;
                detachPendingLocked(info, true);
                detached++;
            }

            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent("CAVE_VIEWPORT_HANDOFF", 100L)) {
                recorder.event("CAVE_VIEWPORT_HANDOFF",
                        "retained=" + retained + " retired=" + retired
                                + " detached=" + detached
                                + " pages=" + planner.pagePlan.length);
            }
        }
    }

    private void retireLaneOutside(MapRequestLane lane, String dimension,
            CaveView view, int normalizedLayerY, int minPageX, int maxPageX,
            int minPageZ, int maxPageZ) {
        synchronized (pages) {
            long now = System.currentTimeMillis();
            for (PageRequest request : requests.values()) {
                PageKey key = request.key;
                boolean retained = lane != MapRequestLane.FULLSCREEN
                        && key.dimension().equals(dimension)
                        && key.view() == view && key.layerY() == normalizedLayerY
                        && key.globalPageX() >= minPageX
                        && key.globalPageX() <= maxPageX
                        && key.globalPageZ() >= minPageZ
                        && key.globalPageZ() <= maxPageZ;
                // Fullscreen pan/zoom starts a fresh region-ranked generation.
                // Old viewport lane ownership must not keep obsolete distance/age
                // priorities alive after the inspected position changes.
                if (!retained) request.clearLane(lane);
            }
            removeExpiredRequestsLocked(now);
            // A viewport-scoped build that no longer has any live lane owner must
            // not retain one of the six exact-build slots. Its source/style result
            // is reusable only after being requested again; cancellation here is
            // cheaper than completing and discarding it after several mode/pan
            // transitions.
            for (PageInfo info : pages.values()) {
                if (info.pending == null || info.pendingLane != lane) continue;
                if (isPendingStillWantedLocked(info, now)) continue;
                detachPendingLocked(info, true);
            }
        }
    }

    private void activateLayerProjection(String dimension, int normalizedLayerY,
            int projectionTopY) {
        projectionTopY = CaveLayerBand.projectionTopY(CaveView.LAYERED, projectionTopY);
        final int targetProjectionTopY = projectionTopY;
        ProjectionBandKey band = new ProjectionBandKey(
                dimension, CaveView.LAYERED, normalizedLayerY);
        int previousProjection;
        int previousBandY = Integer.MIN_VALUE;
        boolean bandChanged;
        LayerWorkingSetRetention retention;
        int detachedRetargetBuilds = 0;
        int retiredRetargetCompletions = 0;
        int retiredRegionBacklog = 0;
        int retiredBranchStamps = 0;
        int reusedRetainedPages = 0;
        synchronized (pages) {
            Integer oldActiveBand = activeLayerBandByDimension.get(dimension);
            bandChanged = oldActiveBand == null
                    || oldActiveBand.intValue() != normalizedLayerY;
            if (bandChanged) {
                if (oldActiveBand != null) {
                    previousLayerBandByDimension.put(dimension, oldActiveBand);
                    previousBandY = oldActiveBand;
                } else {
                    previousLayerBandByDimension.remove(dimension);
                }
                activeLayerBandByDimension.put(dimension, normalizedLayerY);
            } else {
                previousBandY = previousLayerBandByDimension.getOrDefault(
                        dimension, Integer.MIN_VALUE);
            }

            Integer previous = activeLayerProjections.put(band, projectionTopY);
            if (!bandChanged && previous != null && previous == projectionTopY) return;
            previousProjection = previous == null ? Integer.MIN_VALUE : previous;

            /*
             * Keep every recently used Layered band as bounded CPU work and park
             * only inactive presentation ownership. PASS125 still retained only an
             * active/previous interpretation in several lower layers and eagerly
             * deleted same-band exact projections. Xaero keeps MapLayers/written
             * regions and lets bounded caches age them out instead.
             */
            retention = retainLayerWorkingSetLocked(
                    dimension, normalizedLayerY, previousBandY);

            /*
             * Retarget only pages whose exact Top-Y really differs. A page parked
             * from a recent visit can already hold an authoritative frontLods image
             * for this projection; destroying it here would defeat retained work.
             */
            java.util.HashSet<PageKey> retargetedKeys = new java.util.HashSet<>();
            for (PageInfo info : pages.values()) {
                if (!info.key.dimension().equals(dimension)
                        || info.key.view() != CaveView.LAYERED
                        || info.key.layerY() != normalizedLayerY) continue;
                if (info.canRenderProjection(projectionTopY)) {
                    info.nextRetryMs = 0L;
                    reusedRetainedPages++;
                    continue;
                }
                retargetedKeys.add(info.key);
                if (info.pending != null
                        && info.pendingProjectionTopY != projectionTopY) {
                    detachPendingLocked(info, true);
                    detachedRetargetBuilds++;
                }
                PageRequest request = requests.get(info.key);
                if (request != null && request.projectionTopY != projectionTopY) {
                    removeRequestOwnershipLocked(info.key, request);
                }
                info.beginProjectionTransition(projectionTopY);
                /*
                 * PASS126: keep the currently published same-band page as the
                 * loaded/last-good presentation while the new exact Top-Y is
                 * assembled. Xaero separates loadingCaving from loadedCaving and
                 * does not blank already-written tiles just because caveStart
                 * changed. Exact authority below still requires projectionTopY, so
                 * this retained image is only a visual underlay, never completion.
                 */
                info.nextRetryMs = 0L;
            }
            if (!retargetedKeys.isEmpty()) {
                int completedBefore = completedBuilds.size();
                completedBuilds.removeIf(build -> retargetedKeys.contains(
                        build.info().key));
                completedPollScratch.removeIf(build -> retargetedKeys.contains(
                        build.info().key));
                retiredRetargetCompletions = completedBefore
                        - completedBuilds.size();
                int backlogBefore = regionExactBacklog.size();
                regionExactBacklog.entrySet().removeIf(entry ->
                        retargetedKeys.contains(entry.getKey())
                                && entry.getValue().projectionTopY()
                                        != targetProjectionTopY);
                retiredRegionBacklog = backlogBefore - regionExactBacklog.size();
                int branchBefore = stagedRegionBranchRevisions.size();
                stagedRegionBranchRevisions.keySet().removeIf(branchKey ->
                        retargetedKeys.contains(branchKey.pageKey())
                                && branchKey.projectionTopY()
                                        != targetProjectionTopY);
                retiredBranchStamps = branchBefore
                        - stagedRegionBranchRevisions.size();
            }
            /*
             * Keep the last exact Top-Y for every retained 16-block band. Xaero's
             * LayeredRegionManager does not collapse to active+previous layers;
             * bounded region/texture caches decide what eventually ages out. The
             * PageInfo LRU below provides the same bound for SimpleMap.
             */
            exactTopologyRevision.incrementAndGet();
        }

        CaveRegionProjectionService.getInstance().activateProjection(
                dimension, CaveView.LAYERED, projectionTopY);
        int retiredProjectionEntries = CaveProjectionServiceV2.getInstance()
                .activateLayeredTopY(projectionTopY);
        /*
         * Branch pixels are also retained work. When leaving a band, park only its
         * GPU residency/queues and keep CPU branch pixels for a cheap restore. When
         * exact Top-Y changes inside the active band, keep the old branch as the
         * last-good underlay until fresh exact pages incrementally replace it.
         */
        if (bandChanged && previousBandY != Integer.MIN_VALUE
                && previousBandY != normalizedLayerY) {
            /*
             * PASS159: do not park the immediately previous branch layer during the
             * loading handoff. The renderer may use it as the same last-good
             * underlay as exact pages until the new band reaches stable coverage.
             */
        }
        trimPages();
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (recorder.shouldEmitEvent("CAVE_LAYER_PROJECTION_RETARGET:" + band, 50L)) {
            recorder.event("CAVE_LAYER_PROJECTION_RETARGET",
                    "band=" + band + " previous_top_y=" + previousProjection
                            + " current_top_y=" + projectionTopY
                            + " previous_band=" + previousBandY
                            + " parked_pages=" + retention.parked()
                            + " retired_pages=" + retention.retired()
                            + " reused_retained_pages=" + reusedRetainedPages
                            + " retired_projection_entries="
                            + retiredProjectionEntries
                            + " detached_retarget_builds="
                            + detachedRetargetBuilds
                            + " retired_retarget_completions="
                            + retiredRetargetCompletions
                            + " retired_region_backlog="
                            + retiredRegionBacklog
                            + " retired_branch_stamps="
                            + retiredBranchStamps
                            + " branch_policy=last_good_retained");
        }
    }

    private record LayerWorkingSetRetention(int parked, int retired) { }

    /**
     * Parks all inactive Layered bands without deleting settled CPU products.
     * In-flight/request ownership is revoked because it represents old presentation
     * demand; MAX_PAGES/trimPages remains the memory and eviction boundary.
     */
    private LayerWorkingSetRetention retainLayerWorkingSetLocked(String dimension,
            int activeNormalizedLayerY, int previousNormalizedLayerY) {
        java.util.HashSet<PageKey> inactiveKeys = new java.util.HashSet<>();
        java.util.HashSet<PageKey> retiredKeys = new java.util.HashSet<>();
        java.util.ArrayList<PageInfo> retiredInfos = new java.util.ArrayList<>();
        int parked = 0;
        int retired = 0;
        var iterator = pages.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<PageKey, PageInfo> entry = iterator.next();
            PageKey key = entry.getKey();
            if (!key.dimension().equals(dimension)
                    || key.view() != CaveView.LAYERED
                    || key.layerY() == activeNormalizedLayerY) continue;
            PageInfo info = entry.getValue();
            boolean keepPrevious = previousNormalizedLayerY != Integer.MIN_VALUE
                    && key.layerY() == previousNormalizedLayerY;
            if (info.pending != null) detachPendingLocked(info, true);
            PageRequest request = requests.get(key);
            if (request != null) removeRequestOwnershipLocked(key, request);
            info.nextRetryMs = 0L;

            if (keepPrevious) {
                /*
                 * PASS159 / Xaero loadedCaving: one previous band is the visual
                 * underlay while a newly requested Layered band is still loading.
                 * Keep its atlas residency as well as CPU pixels. Releasing the slot
                 * here made fullscreen cross-band changes immediately black even
                 * though a complete last-good layer existed one frame earlier.
                 * MAX_PAGES/atlas pressure remains the hard eviction boundary.
                 */
                inactiveKeys.add(key);
                parked++;
                continue;
            }

            /*
             * PASS147: CIMG/CVD are the durable history. Keeping every visited Top-Y
             * band as live PageInfo/frontLods duplicated that history in heap and made
             * every manager scan grow for the whole MapScreen session (the validation
             * run parked >1,200 pages after a few slider moves). Retain active + one
             * previous band only; older bands are restored cache-first if revisited.
             */
            iterator.remove();
            revisions.remove(key);
            inactiveKeys.add(key);
            retiredKeys.add(key);
            retiredInfos.add(info);
            ExactPageStateTracker.getInstance().remove(stateKey(key));
            retired++;
        }

        if (!inactiveKeys.isEmpty()) {
            regionExactBacklog.keySet().removeIf(inactiveKeys::contains);
            completedBuilds.removeIf(build -> inactiveKeys.contains(build.info().key));
            completedPollScratch.removeIf(
                    build -> inactiveKeys.contains(build.info().key));
            stagedRegionBranchRevisions.keySet().removeIf(
                    branchKey -> inactiveKeys.contains(branchKey.pageKey()));
        }
        if (!retiredKeys.isEmpty()) {
            activeLayerProjections.keySet().removeIf(band ->
                    band.dimension().equals(dimension)
                            && band.view() == CaveView.LAYERED
                            && band.layerY() != activeNormalizedLayerY
                            && band.layerY() != previousNormalizedLayerY);
        }
        for (PageInfo info : retiredInfos) {
            if (renderBatchDepth > 0) deferredCloses.add(info);
            else info.close();
        }

        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if ((parked > 0 || retired > 0)
                && recorder.shouldEmitEvent("CAVE_LAYER_WORKING_SET_RETAINED", 50L)) {
            recorder.event("CAVE_LAYER_WORKING_SET_RETAINED",
                    "dimension=" + dimension + " active_band="
                            + activeNormalizedLayerY + " previous_band="
                            + previousNormalizedLayerY + " parked=" + parked
                            + " retired=" + retired + " retained_pages=" + pages.size()
                            + " policy=active_plus_previous_cache_backed");
        }
        return new LayerWorkingSetRetention(parked, retired);
    }

    public void requestRegion(CaveView view, int layerY, int regionX, int regionZ) {
        layerY = projectionTopY(view, layerY);
        int firstPageX = regionX << 3;
        int firstPageZ = regionZ << 3;
        long now = System.currentTimeMillis();
        for (int pageX = 0; pageX < 8; pageX++) {
            for (int pageZ = 0; pageZ < 8; pageZ++) {
                int dx = pageX - 3;
                int dz = pageZ - 3;
                requestPage(view, layerY, firstPageX + pageX, firstPageZ + pageZ,
                        MapRequestLane.BACKGROUND.priorityBase()
                                - (dx * dx + dz * dz) * 100,
                        MapRequestLane.BACKGROUND, now);
            }
        }
        repository.requestDisplayPageRangeLoad(view, layerY,
                firstPageX, firstPageX + 7, firstPageZ, firstPageZ + 7);
    }

    public void markRegionDirty(CaveView view, int layerY, int regionX, int regionZ) {
        layerY = projectionTopY(view, layerY);
        int firstPageX = regionX << 3;
        int firstPageZ = regionZ << 3;
        synchronized (pages) {
            for (PageKey key : new ArrayList<>(revisions.keySet())) {
                if (key.view() == view && key.layerY() == normalizedLayer(view, layerY)
                        && key.globalPageX() >= firstPageX && key.globalPageX() < firstPageX + 8
                        && key.globalPageZ() >= firstPageZ && key.globalPageZ() < firstPageZ + 8) {
                    revisions.merge(key, 1L, Long::sum);
                }
            }
        }
    }

    public CaveAtlasRegion peekPageRegion(CaveView view, int layerY,
            int regionX, int regionZ, int pageX, int pageZ, float scale) {
        layerY = projectionTopY(view, layerY);
        PageKey key = key(view, layerY,
                (regionX << 3) + pageX, (regionZ << 3) + pageZ);
        synchronized (pages) {
            PageInfo info = pages.get(key);
            if (info == null) return null;
            if (view == CaveView.LAYERED
                    && !info.canRenderProjection(layerY)
                    && !info.canRenderLastGoodWithinBand(layerY)) return null;
            info.lastVisibleRenderEpoch = renderEpoch;
            info.lastVisibleMs = System.currentTimeMillis();
            if (!info.initialized || info.atlasSlot < 0) return null;
            int fullColumns = CaveTextureAtlas.PAGE_SIZE * CaveTextureAtlas.PAGE_SIZE;
            if (info.knownColumns < fullColumns
                    && CaveScreenSpacePolicy.exactPagePixels(scale)
                            < PARTIAL_EXACT_MIN_SCREEN_PIXELS) {
                return null;
            }
            MapResidencyManager.getInstance().touch(residencyKey(key));
            publishPageTable(info, false);
            return atlas.region(info.atlasSlot, scale);
        }
    }

    /** Logical exact-page identity selected for the same atlas LOD as scale. */
    public TileKey pageTileKey(CaveView view, int layerY,
            int globalPageX, int globalPageZ, float scale) {
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp == null) return null;
        int normalized = normalizedLayer(view, layerY);
        int lod = CaveTextureAtlas.lodForScale(scale);
        return caveTileKey(stamp.sessionId(), view, normalized,
                globalPageX, globalPageZ, lod);
    }

    /** Compatibility lookup for the first 512x512 branch level. */
    public CaveAtlasRegion peekBranchRegion(CaveView view, int layerY,
            int regionX, int regionZ) {
        return peekBranchRegion(view, layerY, 1, regionX, regionZ);
    }

    /** Returns a partial or complete recursive LOD node. */
    public CaveAtlasRegion peekBranchRegion(CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
        layerY = projectionTopY(view, layerY);
        // Render-plan traversal reads only the last GPU-published branch snapshot.
        // Disk loading, tree derivation and dirty-queue mutation are bridged back to
        // the owner thread by CaveLodTree, so zooming cannot block on the pages lock.
        return lodTree.peekPublished(dimension(), view,
                normalizedLayer(view, layerY), level, nodeX, nodeZ);
    }

    public boolean hasBranchData(CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
        layerY = projectionTopY(view, layerY);
        return lodTree.hasDataSnapshot(dimension(), view,
                normalizedLayer(view, layerY), level, nodeX, nodeZ);
    }

    /**
     * PASS157 source-window fence. Far-zoom world-save reconstruction must not
     * decode leaves already covered by a GPU-published branch/CIMG product.
     */
    public boolean hasPublishedBranchCoverage(CaveView view, int layerY,
            int globalPageX, int globalPageZ) {
        layerY = projectionTopY(view, layerY);
        return lodTree.hasPublishedCoverage(dimension(), view,
                normalizedLayer(view, layerY), globalPageX, globalPageZ);
    }

    /** Compatibility view for older call sites; new rendering should use atlas regions. */
    public ResourceLocation peekPage(CaveView view, int layerY,
            int regionX, int regionZ, int pageX, int pageZ) {
        CaveAtlasRegion region = peekPageRegion(view, layerY,
                regionX, regionZ, pageX, pageZ, 1.0f);
        return region == null ? null : region.texture();
    }

    public boolean hasAnyPage(CaveView view, int layerY, int regionX, int regionZ) {
        layerY = projectionTopY(view, layerY);
        int firstPageX = regionX << 3;
        int firstPageZ = regionZ << 3;
        int normalized = normalizedLayer(view, layerY);
        String dimension = dimension();
        synchronized (pages) {
            for (PageInfo info : pages.values()) {
                PageKey key = info.key;
                if (info.initialized && key.dimension().equals(dimension)
                        && key.view() == view && key.layerY() == normalized
                        && key.globalPageX() >= firstPageX && key.globalPageX() < firstPageX + 8
                        && key.globalPageZ() >= firstPageZ && key.globalPageZ() < firstPageZ + 8) return true;
            }
        }
        return false;
    }



    /**
     * Pure tick-side readiness probe used for atomic Layered cave handoff. A page
     * counts only when the requested projection owns every 16x16 tile (or the
     * source transaction proved the page empty). Partial pages remain useful after
     * handoff, but cannot be the signal that replaces the previous visible layer.
     */
    public boolean isPageProjectionResolved(CaveView view, int layerY,
            int globalPageX, int globalPageZ) {
        layerY = projectionTopY(view, layerY);
        PageKey key = key(view, layerY, globalPageX, globalPageZ);
        synchronized (pages) {
            PageInfo info = pages.get(key);
            if (info == null) return false;
            if (info.knownEmpty) return true;
            if (!info.initialized || info.atlasSlot < 0 || info.knownColumns <= 0) {
                return false;
            }
            return view == CaveView.FULL
                    || info.isProjectionAuthoritative(layerY);
        }
    }

    /** Pure readiness probe for transition gates; does not touch LRU/render epochs. */
    public boolean isPageVisualAvailable(CaveView view, int layerY,
            int globalPageX, int globalPageZ) {
        layerY = projectionTopY(view, layerY);
        PageKey key = key(view, layerY, globalPageX, globalPageZ);
        synchronized (pages) {
            PageInfo info = pages.get(key);
            if (info == null || !info.initialized || info.atlasSlot < 0
                    || info.knownColumns <= 0) return false;
            return view == CaveView.FULL || info.canRenderProjection(layerY)
                    || info.canRenderLastGoodWithinBand(layerY);
        }
    }

    /**
     * Samples one visible Layered minimap hole and records all four completeness
     * boundaries: raw MCA evidence, vertical archive, projected pixels and exact
     * presentation residency.
     */
    public void recordCavePageHole(CaveView view, int layerY,
            int globalPageX, int globalPageZ, boolean fallbackAvailable) {
        layerY = projectionTopY(view, layerY);
        String currentDimension = dimension();
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        /* Hole classification crosses source/archive/projection locks. Sample one
         * visible hole globally per 100 ms instead of inspecting every missing
         * page in one render-plan rebuild. */
        if (!recorder.shouldEmitEvent("CAVE_PAGE_HOLE_REASON_SAMPLE", 100L)) {
            return;
        }
        PageKey pageKey = key(view, layerY, globalPageX, globalPageZ);
        boolean presentationResident = false;
        boolean cpuProjection = false;
        boolean gpuDenied = false;
        boolean knownEmpty = false;
        int atlasSlot = -1;
        int knownColumns = 0;
        int visibleTileMask = 0;
        int publishedProjectionTopY = Integer.MIN_VALUE;
        long uploadedSourceRevision = Long.MIN_VALUE;
        synchronized (pages) {
            PageInfo info = pages.get(pageKey);
            if (info != null) {
                presentationResident = info.initialized && info.atlasSlot >= 0
                        && info.knownColumns > 0;
                cpuProjection = info.frontLods != null && info.knownColumns > 0
                        || info.pending != null && info.pending.isDone();
                gpuDenied = info.gpuReservationFailures > 0;
                knownEmpty = info.knownEmpty;
                atlasSlot = info.atlasSlot;
                knownColumns = info.knownColumns;
                visibleTileMask = info.visibleTileMask;
                publishedProjectionTopY = info.publishedProjectionTopY;
                uploadedSourceRevision = info.uploadedSourceRevision;
            }
        }
        CaveRegionProjectionService.ProjectedPage projected =
                CaveRegionProjectionService.getInstance().find(
                        view, layerY, globalPageX, globalPageZ, currentDimension);
        boolean projectionPresent = cpuProjection || projected != null;
        CaveArchiveV2Service archive = CaveArchiveV2Service.getInstance();
        int residentArchiveMask = archive.residentAnyMask(
                globalPageX, globalPageZ);
        int indexedArchiveMask = archive.indexedAnyMask(
                globalPageX, globalPageZ);
        CaveNativeRegionImportService.PageSourceState source =
                CaveNativeRegionImportService.getInstance().pageSourceState(
                        currentDimension, globalPageX, globalPageZ);

        boolean mcaPresent = false;
        boolean mcaKnownAbsent = true;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.level != null) {
            GeneratedChunkIndex generated = GeneratedChunkIndex.getInstance();
            int firstChunkX = globalPageX << 2;
            int firstChunkZ = globalPageZ << 2;
            for (int chunkZ = 0; chunkZ < 4; chunkZ++) {
                for (int chunkX = 0; chunkX < 4; chunkX++) {
                    GeneratedChunkIndex.State state = generated.state(
                            minecraft.level, firstChunkX + chunkX,
                            firstChunkZ + chunkZ);
                    if (state == GeneratedChunkIndex.State.LIVE
                            || state == GeneratedChunkIndex.State.SAVED_PRESENT) {
                        mcaPresent = true;
                    }
                    if (state != GeneratedChunkIndex.State.KNOWN_ABSENT) {
                        mcaKnownAbsent = false;
                    }
                }
            }
        } else {
            mcaKnownAbsent = false;
        }

        CavePageHoleReason reason;
        if (fallbackAvailable) {
            reason = CavePageHoleReason.CURRENT_MISSING_PREVIOUS_AVAILABLE;
        } else if (gpuDenied) {
            reason = CavePageHoleReason.GPU_BUDGET_DENIED;
        } else if (projectionPresent) {
            reason = CavePageHoleReason.EXACT_GPU_NOT_READY;
        } else if (source.inFlightChunks() > 0) {
            reason = CavePageHoleReason.SOURCE_DECODE_IN_FLIGHT;
        } else if (indexedArchiveMask != 0 && residentArchiveMask == 0) {
            reason = CavePageHoleReason.ARCHIVE_INDEXED_NOT_RESIDENT;
        } else if (residentArchiveMask != 0 || source.archivedChunks() > 0) {
            reason = CavePageHoleReason.PROJECTION_NOT_READY;
        } else if (mcaPresent) {
            reason = CavePageHoleReason.MCA_PRESENT_NOT_DECODED;
        } else if (mcaKnownAbsent) {
            reason = CavePageHoleReason.NO_MCA_RECORD;
        } else if (indexedArchiveMask == 0 && source.resolvedChunks() == 0) {
            reason = CavePageHoleReason.ARCHIVE_MISSING;
        } else {
            reason = CavePageHoleReason.CURRENT_AND_PREVIOUS_MISSING;
        }

        pipelineTelemetry.recordCavePageHole(reason);
        recorder.event("CAVE_PAGE_HOLE_REASON",
                "page=" + globalPageX + ',' + globalPageZ
                        + " view=" + view
                        + " top_y=" + layerY
                        + " reason=" + reason
                        + " mca_present=" + mcaPresent
                        + " cave_archive_present="
                        + (residentArchiveMask != 0
                                || indexedArchiveMask != 0)
                        + " archive_resident_mask=0x"
                        + Integer.toHexString(residentArchiveMask)
                        + " archive_indexed_mask=0x"
                        + Integer.toHexString(indexedArchiveMask)
                        + " source_in_flight=" + source.inFlightChunks()
                        + " source_archived=" + source.archivedChunks()
                        + " cave_projection_present=" + projectionPresent
                        + " cave_presentation_resident="
                        + presentationResident
                        + " known_empty=" + knownEmpty
                        + " atlas_slot=" + atlasSlot
                        + " known_columns=" + knownColumns
                        + " visible_tile_mask=0x"
                        + Integer.toHexString(visibleTileMask)
                        + " published_top_y=" + publishedProjectionTopY
                        + " uploaded_source_revision=" + uploadedSourceRevision
                        + " previous_available=" + fallbackAvailable);
    }

    /**
     * Returns true when at least one exact GPU page is resident below the given
     * recursive LOD node. A complete ancestor is allowed to cover cold children,
     * but it must not hide a newer exact leaf that is already available.
     */
    public boolean hasResidentPageInNode(CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
        layerY = projectionTopY(view, layerY);
        int normalized = normalizedLayer(view, layerY);
        String currentDimension = dimension();
        if (level > 0) {
            return residentNodeCounts.getOrDefault(new ResidentNodeKey(
                    currentDimension, view, normalized, level, nodeX, nodeZ), 0) > 0;
        }
        synchronized (pages) {
            PageInfo info = pages.get(new PageKey(currentDimension, view, normalized,
                    nodeX, nodeZ));
            return info != null && info.initialized && info.atlasSlot >= 0;
        }
    }

    /**
     * PASS170: preparation is asynchronous, visibility/GPU front-product mutation is
     * not. Exact pages may be projected and retained on CPU ahead, but one row-major
     * presentation cursor decides when they enter the atlas and become drawable.
     * This mirrors Xaero's persistent writer/product lifecycle without serialising
     * source/projection work.
     */
    public boolean allowFullscreenExact(CaveView view, int layerY,
            int globalPageX, int globalPageZ) {
        layerY = projectionTopY(view, layerY);
        VisiblePlanner planner = visiblePlans.get(MapRequestLane.FULLSCREEN);
        if (planner == null || planner.pagePlan.length == 0) return true;
        PageKey key = key(view, layerY, globalPageX, globalPageZ);
        synchronized (pages) {
            if (!planner.matches(key)) return true;
            if (planner.projectionTopY != layerY) return false;
            int ordinal = planner.ordinalOf(globalPageX, globalPageZ);
            if (ordinal < 0) return false;
            /* PASS170: source/cache/projection preparation may finish in any order,
             * but a single fullscreen presentation writer owns GPU mutation and
             * visibility. The cursor is therefore a reveal fence for exact pages as
             * well as branches. Later pages may be CPU-ready behind this fence without
             * overwriting the currently loaded Cave product. */
            if (ordinal >= planner.publicationCursor) return false;
            PageInfo info = pages.get(key);
            if (info == null || !info.initialized || info.atlasSlot < 0) return false;
            return hasCoherentVisiblePageLocked(info);
        }
    }

    private void indexResidentPage(PageKey key, int delta) {
        if (key == null || delta == 0) return;
        for (int level = 1; level <= CaveLodTree.RENDER_MAX_LEVEL; level++) {
            int span = 1 << level;
            int nodeX = Math.floorDiv(key.globalPageX(), span);
            int nodeZ = Math.floorDiv(key.globalPageZ(), span);
            ResidentNodeKey node = new ResidentNodeKey(key.dimension(), key.view(),
                    key.layerY(), level, nodeX, nodeZ);
            residentNodeCounts.compute(node, (ignored, previous) -> {
                int updated = (previous == null ? 0 : previous) + delta;
                return updated <= 0 ? null : updated;
            });
        }
    }

    public void beginRenderBatch() {
        synchronized (pages) {
            if (renderBatchDepth == 0) renderEpoch++;
            renderBatchDepth++;
        }
    }

    public void endRenderBatch() {
        List<PageInfo> close = null;
        synchronized (pages) {
            if (renderBatchDepth > 0) renderBatchDepth--;
            if (renderBatchDepth == 0 && !deferredCloses.isEmpty()) {
                close = new ArrayList<>(deferredCloses);
                deferredCloses.clear();
            }
        }
        if (close != null) close.forEach(PageInfo::close);
    }

    public void upload(boolean force) {
        if (MapActivityGate.getInstance().blocksForegroundStreaming()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> upload(force));
            return;
        }
        synchronizeAtlasStorage();
        CaveRegionProjectionService.getInstance().maintain();
        long frameStartedNanos = System.nanoTime();
        long measuredFrameNanos = lastUploadFrameNanos == 0L
                ? 16_666_667L
                : Math.max(4_000_000L, Math.min(50_000_000L,
                        frameStartedNanos - lastUploadFrameNanos));
        lastUploadFrameNanos = frameStartedNanos;
        long now = System.currentTimeMillis();
        if (!force && now - lastUploadMs
                < (MapConfig.fastFullscreenLoading ? 5L : 12L)) return;
        lastUploadMs = now;
        pruneRequests(now);

        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        boolean pressured = governor.underPressure();
        boolean idleHeadroom = governor.hasStreamingHeadroom();
        boolean styleRefresh = now < styleRefreshUntilMs && !pressured;
        VisiblePlanner fullscreenPlanner = visiblePlans.get(MapRequestLane.FULLSCREEN);
        /* Viewport ownership outlives an individual PageRequest. Satisfied exact
         * requests are intentionally cleared, but the open map still owns later
         * source refreshes and GPU publication. Deriving activity only from the
         * request map demoted those refreshes to BACKGROUND until completion, which
         * produced thousands of PASS69 lane promotions and weak GPU budgets. */
        boolean fullscreenActive = plannerActive(fullscreenPlanner, now);
        boolean minimapActive = plannerActive(
                visiblePlans.get(MapRequestLane.MINIMAP), now);
        boolean branchFirst = fullscreenActive
                && CaveScreenSpacePolicy.branchFirst(
                        fullscreenPlanner.scale, MapRequestLane.FULLSCREEN);

        // Force bypasses cadence, never the frame deadline. The governor remains
        // authoritative, while the measured render cadence additionally caps cave
        // work to Xaero-like 5/12 fullscreen or 1/5 gameplay frame shares.
        int governorBudget = governor.texturePageBudget(true);
        int publishBudget = styleRefresh
                ? Math.min(idleHeadroom ? 24 : 14,
                        governorBudget + (idleHeadroom ? 12 : 6))
                : pressured ? 2 : Math.min(idleHeadroom ? 24 : 14,
                        governorBudget + (idleHeadroom ? 12 : 5));
        long governorUploadBudget = styleRefresh
                ? Math.min(1_500_000L, governor.textureUploadBudgetNanos(true))
                : governor.textureUploadBudgetNanos(force
                        || fullscreenActive || minimapActive);
        long measuredFrameShare = fullscreenActive
                ? measuredFrameNanos * 7L / 12L
                : measuredFrameNanos / 5L;
        long minimumUsefulSlice = fullscreenActive
                ? (pressured ? 1_500_000L : 4_000_000L) : 250_000L;
        long adaptiveUploadSlice = Math.min(governorUploadBudget,
                Math.max(250_000L, measuredFrameShare));
        /*
         * PASS88 let the global adaptive governor shrink an open fullscreen cave
         * transaction below one useful scanline burst (often < 1 ms). CPU-ready rows
         * then waited dozens of frames and generated thousands of reservation denials.
         * Keep the normal governor for gameplay/minimap, but guarantee a bounded
         * focused slice while MapScreen owns the viewport.
         */
        long uploadBudgetNanos = fullscreenActive
                ? Math.min(measuredFrameNanos * 7L / 12L,
                        Math.max(minimumUsefulSlice, adaptiveUploadSlice))
                : adaptiveUploadSlice;
        long deadline = frameStartedNanos + uploadBudgetNanos;
        int regionCachePageBudget = branchFirst
                ? (pressured ? 8 : (idleHeadroom ? 48 : 24))
                : pressured ? 2 : (idleHeadroom ? 16 : 8);
        int importedPageBudget = branchFirst
                ? (pressured ? 12 : (idleHeadroom ? 64 : 40))
                : pressured ? 2 : (idleHeadroom ? 24 : 12);
        /*
         * A queued branch is not a visible branch. Service already-ready hierarchy
         * work before importing another region wave so retained coverage cannot wait
         * behind tens of milliseconds of producer work every frame. The previous
         * order said "branch first" in comments but actually drained region pages
         * before calling lodTree.publish().
         */
        int preDrainBranchBudget = fullscreenActive
                ? (branchFirst ? (pressured ? 4 : (idleHeadroom ? 16 : 8)) : 1)
                : minimapActive ? 1 : 0;
        boolean branchBudgetExhausted = false;
        if (preDrainBranchBudget > 0 && System.nanoTime() < deadline) {
            branchBudgetExhausted = publishBranches(preDrainBranchBudget, deadline);
        }
        /*
         * PASS155 / Xaero cache-first publication order:
         *
         * CIMG is already the final 512x512 presentation product. Do not let newly
         * reconstructed region pages consume the render-thread slice before this
         * packaged cache is installed. PASS154 admitted CIMG reads first but then
         * drained 40-60 fresh region pages before installing them, so a warm cache
         * could still wait seconds behind cold reconstruction. Render cache first,
         * then refine from the current world source.
         */
        if (System.nanoTime() < deadline) {
            installCompletedRegionImages(regionCachePageBudget, deadline, now);
        }
        if (System.nanoTime() < deadline) {
            drainRegionProjectedPages(importedPageBudget, deadline, now);
        }
        /*
         * Xaero's writer has one bounded tile/time slice. Gameplay/minimap must not
         * consume a second independent region-import slice in the same render frame.
         * Only an open fullscreen map with measured idle headroom may exploit a
         * second drain, and it remains clamped to the same outer deadline.
         */
        if (fullscreenActive && idleHeadroom && System.nanoTime() < deadline) {
            drainRegionProjectedPages(Math.max(1, importedPageBudget / 2),
                    deadline, now);
        }

        // One completed exact transaction may create/refresh a coarse branch, but
        // far-zoom publication spends the remaining frame on branch coverage before
        // starting another expensive leaf. Close zoom publishes at most one compact
        // scanline burst of exact leaves so the visual reveal remains coherent.
        int exactPublishBudget = branchFirst
                ? (fullscreenActive ? (pressured ? 1 : (idleHeadroom ? 6 : 3)) : 0)
                : (fullscreenActive
                        ? Math.min(FULLSCREEN_PUBLICATION_BURST, publishBudget)
                        : publishBudget);
        boolean layeredFullscreenActive = fullscreenActive
                && fullscreenPlanner.view == CaveView.LAYERED;
        boolean layeredProjectionChanged = layeredFullscreenActive
                && observedFullscreenLayeredProjectionTopY
                        != fullscreenPlanner.projectionTopY;
        if (layeredProjectionChanged) {
            observedFullscreenLayeredProjectionTopY = fullscreenPlanner.projectionTopY;
            nextFullscreenLayeredPublicationMs = 0L;
        }
        fullscreenLayeredPublicationWindowOpen =
                CaveViewportPublicationPolicy.windowOpen(layeredFullscreenActive,
                        layeredProjectionChanged,
                        nextFullscreenLayeredPublicationMs, now);
        /*
         * Far zoom consumes branch textures as its primary representation. Publish
         * the already-derived visible branch first, while the per-frame GPU
         * ledger is still empty, so its bootstrap admission cannot be consumed by
         * an exact page. Xaero follows the same visible-branch-first shape: a small
         * nearest region slice advances every pass and leaves are refinement work.
         */
        boolean minimapBranchBootstrap = !branchFirst && minimapActive
                && !fullscreenActive;
        int bootstrapBranchBudget = branchFirst
                ? (pressured ? 4 : (idleHeadroom ? 16 : 8))
                : (fullscreenActive || minimapBranchBootstrap) ? 1 : 0;
        if (bootstrapBranchBudget > 0 && !branchBudgetExhausted
                && System.nanoTime() < deadline) {
            branchBudgetExhausted = publishBranches(bootstrapBranchBudget, deadline);
        }
        publishCompleted(exactPublishBudget, deadline, now);
        if (layeredFullscreenActive && fullscreenLayeredPublicationWindowOpen) {
            nextFullscreenLayeredPublicationMs =
                    CaveViewportPublicationPolicy.nextWindow(now);
        }
        // The flag is meaningful only inside this upload transaction. Other callers
        // must never inherit a closed fullscreen window.
        fullscreenLayeredPublicationWindowOpen = true;
        if (branchFirst && !branchBudgetExhausted
                && System.nanoTime() < deadline) {
            // Give a just-published exact page one chance to advance its branch in
            // the same frame, unless the shared BRANCH ledger already denied this
            // transaction. Re-probing an exhausted frame ledger only burns CPU.
            branchBudgetExhausted = publishBranches(
                    pressured ? 2 : (idleHeadroom ? 8 : 4), deadline);
        }
        if (System.nanoTime() < deadline) {
            int scheduleBudget = branchFirst
                    ? (minimapActive ? (pressured ? 2 : 6) : 0)
                    : (styleRefresh ? (idleHeadroom ? 12 : 8)
                            : force ? Math.min(idleHeadroom ? 4 : 2, publishBudget)
                                    : Math.max(1, publishBudget
                                            + (idleHeadroom ? 2 : 0)));
            scheduleBuilds(scheduleBudget, deadline, now);
        }
        if (!branchFirst && !minimapBranchBootstrap && !branchBudgetExhausted
                && System.nanoTime() < deadline) {
            branchBudgetExhausted = publishBranches(pressured ? 1
                    : Math.min(idleHeadroom ? 4 : 2, publishBudget), deadline);
        }
        VisiblePlanner revealPlanner = visiblePlans.get(MapRequestLane.FULLSCREEN);
        if (fullscreenActive && revealPlanner != null) {
            refreshFullscreenPublicationFrontier(revealPlanner,
                    FULLSCREEN_REVEAL_BURST, "upload-commit");
        }
        if (!pressured && System.nanoTime() < deadline) {
            scheduleRegionImageSave(now, deadline);
        }
    }

    private CavePresentationGeneration ensurePresentationGeneration(
            MapRequestLane lane, String dimension, CaveView view,
            int normalizedLayer, int projectionTopY) {
        if (lane == null || lane == MapRequestLane.BACKGROUND
                || lane == MapRequestLane.PREFETCH) return null;
        synchronized (pages) {
            CavePresentationGeneration current =
                    loadingPresentationGenerations.get(lane);
            if (current != null && current.matches(dimension, view,
                    normalizedLayer, projectionTopY, lane)) return current;
            CavePresentationGeneration next = new CavePresentationGeneration(
                    ++presentationGenerationSequence, ++writerViewportSequence,
                    dimension, view, normalizedLayer, projectionTopY, lane);
            loadingPresentationGenerations.put(lane, next);
            MapDebugRecorder.getInstance().event(
                    "CAVE_PRESENTATION_LOADING_GENERATION_STARTED",
                    "generation=" + next.id()
                            + " writer_viewport=" + next.writerViewportId()
                            + " previous_generation="
                            + (current == null ? 0L : current.id())
                            + " dimension=" + dimension
                            + " view=" + view
                            + " band=" + normalizedLayer
                            + " top_y=" + projectionTopY
                            + " lane=" + lane);
            return next;
        }
    }

    private boolean presentationGenerationOwned(
            CavePresentationGeneration generation) {
        if (generation == null) return false;
        synchronized (pages) {
            return generation.equals(
                    loadingPresentationGenerations.get(generation.lane()));
        }
    }

    private void markPresentationDisplayed(
            CavePresentationGeneration generation) {
        if (generation == null) return;
        synchronized (pages) {
            if (!generation.equals(
                    loadingPresentationGenerations.get(generation.lane()))) return;
            CavePresentationGeneration displayed =
                    displayedPresentationGenerations.get(generation.lane());
            if (generation.equals(displayed)) return;
            if (displayed != null) {
                previousPresentationGenerations.put(
                        generation.lane(), displayed);
            }
            displayedPresentationGenerations.put(generation.lane(), generation);
            MapDebugRecorder.getInstance().event(
                    "CAVE_PRESENTATION_GENERATION_DISPLAYED",
                    "generation=" + generation.id()
                            + " previous_generation="
                            + (displayed == null ? 0L : displayed.id())
                            + " lane=" + generation.lane()
                            + " view=" + generation.view()
                            + " top_y=" + generation.projectionTopY());
        }
    }

    /**
     * Foreground region pages may finish after a mode change or pan. A page carrying
     * the current loading-generation ticket remains valid even when the short
     * PageRequest and displayed planner pulse have already retired.
     */
    private boolean foregroundImportStillOwned(
            CaveRegionProjectionService.ProjectedPage imported, PageKey key,
            long now) {
        if (imported == null || imported.lane() == MapRequestLane.BACKGROUND
                || imported.lane() == MapRequestLane.PREFETCH) {
            return true;
        }
        if (imported.presentationGeneration() != null) {
            return presentationGenerationOwned(imported.presentationGeneration())
                    || isProjectionStillOwned(
                            key, imported.projectionTopY(), now);
        }
        /*
         * A completed PageRequest is deliberately retired once exact work is
         * satisfied, but the visible planner continues to own later immutable
         * region refreshes for as long as that page remains on screen. PASS122
         * required the short request lease AND then separately observed planner
         * ownership. The 19:51 log therefore rejected 9,602 foreground imports;
         * 5,230 FULL/FULLSCREEN pages had request=false while the live viewport
         * still owned them. Admit when either presentation authority is current.
         */
        return isProjectionStillOwned(key, imported.projectionTopY(), now);
    }

    /**
     * Drains immutable projected pages prepared by CaveRegionProjectionService.
     * Publication, atlas allocation and frame budgeting remain single-owner here;
     * only source resolve/style work is bypassed.
     */
    private int drainRegionProjectedPages(int budget, long deadline, long now) {
        CaveRegionProjectionService service =
                CaveRegionProjectionService.getInstance();
        long stageStarted = System.nanoTime();
        if (budget <= 0 || stageStarted >= deadline) return 0;
        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        boolean fullscreenActive = plannerActive(
                visiblePlans.get(MapRequestLane.FULLSCREEN), now);
        /*
         * PASS166: publication gets first claim on the frame slice. PASS165 could
         * spend ~0.9 ms polling dozens of future region completions before it even
         * looked at the exact scanline frontier. If that consumed the outer budget,
         * a CPU-ready frontier sat untouched while its watchdog timer accumulated.
         */
        boolean frontierBuffered = fullscreenActive
                && bufferRetainedFullscreenFrontier(service, now);
        /*
         * PASS168: a ready frontier is a priority hint, never permission to stop
         * draining producer completions. PASS166 set this budget to zero whenever
         * the frontier was buffered, leaving dozens of CPU-ready pages un-ACKed and
         * forcing the region producer to re-offer them every frame. Keep a bounded
         * drain active so prepared products flow continuously into the exact backlog.
         */
        int stageBudget = Math.min(48,
                Math.max(8, Math.max(1, budget) * (frontierBuffered ? 6 : 4)));
        long localSlice = governor.underPressure()
                ? 200_000L : fullscreenActive ? 600_000L : 450_000L;
        // Leave most of the shared render budget for ordered exact publication.
        long stageDeadline = Math.min(deadline, stageStarted + localSlice);
        int staged = 0;
        while (staged < stageBudget && System.nanoTime() < stageDeadline) {
            CaveRegionProjectionService.ProjectedPage imported = service.pollReady();
            if (imported == null) break;
            if (!matchesCurrentDimension(imported.dimension())) {
                service.rejectForeground(imported);
                continue;
            }
            PageKey key = key(imported.view(), imported.projectionTopY(),
                    imported.globalPageX(), imported.globalPageZ());
            boolean weakLane = imported.lane() == MapRequestLane.BACKGROUND
                    || imported.lane() == MapRequestLane.PREFETCH;
            boolean requestOwned = weakLane
                    || isProjectionStillRequested(key, imported.projectionTopY());
            boolean plannerOwned = weakLane
                    || isProjectionViewportOwned(key, imported.projectionTopY(), now);
            boolean generationOwned = presentationGenerationOwned(
                    imported.presentationGeneration());
            boolean projectionOwned = foregroundImportStillOwned(imported, key, now);
            if (!projectionOwned) {
                /*
                 * PASS114: a consumer rejection revokes this stale foreground
                 * lease instead of re-offering it forever. The immutable projection
                 * remains retained in CaveRegionProjectionService; a subsequent
                 * real viewport lease can promote it again without rebuilding.
                 */
                service.rejectForeground(imported);
                if (imported.presentationGeneration() != null) {
                    pipelineTelemetry.recordCaveHandoffRejectedSuperseded();
                } else {
                    pipelineTelemetry.recordCaveHandoffRejectedPlannerMismatch();
                }
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String rejectKey = "CAVE_REGION_FOREGROUND_HANDOFF_REJECTED:"
                        + imported.dimension() + ':' + imported.view() + ':'
                        + imported.projectionTopY() + ':'
                        + imported.globalPageX() + ':' + imported.globalPageZ();
                if (recorder.shouldEmitEvent(rejectKey, 250L)) {
                    recorder.event("CAVE_REGION_FOREGROUND_HANDOFF_REJECTED",
                            "page=" + imported.globalPageX() + ','
                                    + imported.globalPageZ()
                                    + " view=" + imported.view()
                                    + " top_y=" + imported.projectionTopY()
                                    + " lane=" + imported.lane()
                                    + " request_owned=" + requestOwned
                                    + " planner_owned=" + plannerOwned
                                    + " generation_owned=" + generationOwned
                                    + " generation="
                                    + (imported.presentationGeneration() == null
                                            ? 0L
                                            : imported.presentationGeneration().id())
                                    + " projection_owned=" + projectionOwned
                                    + " action=reject_superseded_generation");
                }
                continue;
            }
            if (!weakLane) {
                pipelineTelemetry.recordCaveHandoffAccepted();
                if (generationOwned && !requestOwned && !plannerOwned) {
                    pipelineTelemetry.recordCaveHandoffRetainedLoadingGeneration();
                }
            }
            long currentSource = repository.getPageRevision(
                    imported.view(), imported.projectionTopY(),
                    imported.globalPageX(), imported.globalPageZ());
            if (currentSource == 0L) {
                service.rejectForeground(imported);
                continue;
            }

            /*
             * Do not mutate the visible LOD tree in worker-completion order. PASS104
             * staged the branch here, before the exact publication wavefront saw the
             * page, so a far-zoom map could reveal later async regions as random
             * islands even though exact atlas publication claimed scanline ordering.
             * Buffer the immutable page first. The backlog pass below is the single
             * publication owner for both branch and exact representations.
             */
            /*
             * PASS169: polling a coherent foreground product transfers ownership to
             * this retained backlog immediately. ACK here, not only after GPU
             * admission. Otherwise a page that is already sitting in
             * regionExactBacklog remains "unacknowledged" at the producer and gets
             * re-offered every FOREGROUND_REOFFER_MS. The latest PASS167 trace had
             * 727 FRONTIER_READY waves for only ~209 visible pages. ACK is explicitly
             * CPU-product retention, not visibility; the single fullscreen writer
             * may commit it later under the normal frame budget. Historical guard:
             * ACK is CPU-retention, not visibility.
             */
            if (!weakLane) {
                service.acknowledgeForeground(imported);
            }
            regionExactBacklog.put(key, imported);
            if (imported.lane() == MapRequestLane.FULLSCREEN) {
                VisiblePlanner stagedPlanner = visiblePlans.get(MapRequestLane.FULLSCREEN);
                if (plannerActive(stagedPlanner, now) && stagedPlanner.matches(key)) {
                    int stagedOrdinal = stagedPlanner.ordinalOf(
                            key.globalPageX(), key.globalPageZ());
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    if (recorder.shouldEmitEvent(
                            "CAVE_FULLSCREEN_PAGE_CPU_STAGED", 250L)) {
                        recorder.event("CAVE_FULLSCREEN_PAGE_CPU_STAGED",
                                "page=" + key.globalPageX() + ',' + key.globalPageZ()
                                        + " ordinal=" + stagedOrdinal
                                        + " cursor=" + stagedPlanner.publicationCursor
                                        + " backlog=" + regionExactBacklog.size()
                                        + " policy=async_prepare_single_gpu_writer"
                                        + " pass=PASS170");
                    }
                }
            }
            while (regionExactBacklog.size() > MAX_REGION_EXACT_BACKLOG) {
                var backlogIterator = regionExactBacklog.entrySet().iterator();
                if (!backlogIterator.hasNext()) break;
                backlogIterator.next();
                backlogIterator.remove();
            }
            staged++;
        }

        int admitted = 0;
        /*
         * PASS164: completion order is not presentation order. The old access-order
         * LinkedHashMap was walked from its eldest entry every frame. Once dozens of
         * later fullscreen pages were ACKed ahead of the scanline, the one un-ACKed
         * frontier page could sit behind them until the render slice expired. The
         * 10:33 trace shows region 0,-8 repeatedly reporting offered=42/42,
         * acked=41/42 while the fullscreen cursor remained 62/209; closing MapScreen
         * immediately reclassified the same retained CPU products to MINIMAP and
         * published them. Select the exact fullscreen frontier by coordinate first,
         * then service unrelated/minimap backlog. This keeps strict visual ordering
         * without serialising source/projection work.
         */
        while (admitted < Math.max(1, budget)
                && System.nanoTime() < deadline) {
            PageKey key = selectRegionExactBacklogKey(service, now);
            if (key == null) break;
            CaveRegionProjectionService.ProjectedPage imported =
                    regionExactBacklog.get(key);
            if (imported == null) continue;
            long currentSource = repository.getPageRevision(
                    imported.view(), imported.projectionTopY(),
                    imported.globalPageX(), imported.globalPageZ());
            boolean foregroundOwned = foregroundImportStillOwned(
                    imported, key, now);
            if (currentSource == 0L || currentSource != imported.sourceRevision()
                    || !matchesCurrentDimension(imported.dimension())
                    || !foregroundOwned) {
                regionExactBacklog.remove(key, imported);
                continue;
            }
            VisiblePlanner fullscreenPlanner =
                    visiblePlans.get(MapRequestLane.FULLSCREEN);
            boolean currentFullscreen = imported.lane() == MapRequestLane.FULLSCREEN
                    && plannerActive(fullscreenPlanner, now)
                    && fullscreenPlanner.matches(key)
                    && fullscreenPlanner.projectionTopY == imported.projectionTopY();
            if (currentFullscreen) {
                int ordinal = fullscreenPlanner.ordinalOf(
                        key.globalPageX(), key.globalPageZ());
                if (ordinal < 0) {
                    regionExactBacklog.remove(key, imported);
                    continue;
                }
                if (ordinal != fullscreenPlanner.publicationCursor) {
                    /* PASS170: the producer was ACKed when this immutable CPU
                     * product entered regionExactBacklog. Keep later ordinals staged
                     * on CPU; only one row-major presentation writer may mutate the
                     * visible atlas. This also keeps same-band previous-layer pixels
                     * intact until their replacement is actually committed. */
                    break;
                }
            }

            RegionBranchKey branchKey = new RegionBranchKey(key,
                    imported.projectionTopY());
            long branchStamp = imported.sourceRevision()
                    ^ Long.rotateLeft((long) CaveProjectionStyle.signature(), 17);
            Long previousBranchStamp = stagedRegionBranchRevisions.get(branchKey);
            /*
             * Xaero keeps the currently loaded hierarchy underneath incremental
             * writer updates. A partial 64x64 region page is useful for exact
             * presentation, but feeding every child wave into the coarse tree
             * repeatedly derives L1/L2/L3 from a moving source and creates long
             * branch queues. Only a coherent complete page becomes branch
             * authority; partial pages still continue through the exact path below.
             */
            if (imported.complete()
                    && (previousBranchStamp == null
                            || previousBranchStamp != branchStamp)) {
                lodTree.updatePage(key.dimension(), imported.view(), key.layerY(),
                        imported.globalPageX(), imported.globalPageZ(),
                        imported.pixelsUnsafe(), imported.knownRowsUnsafe(),
                        imported.knownColumnCount(), true,
                        imported.sourceRevision(), imported.lane());
                stagedRegionBranchRevisions.put(branchKey, branchStamp);
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent(
                        "CAVE_REGION_BRANCH_AUTHORITY_STAGED", 100L)) {
                    recorder.event("CAVE_REGION_BRANCH_AUTHORITY_STAGED",
                            "page=" + imported.globalPageX() + ','
                                    + imported.globalPageZ()
                                    + " view=" + imported.view()
                                    + " top_y=" + imported.projectionTopY()
                                    + " lane=" + imported.lane()
                                    + " order=coherent_complete_page");
                }
            }

            /*
             * PASS169 ACK already happened when this coherent CPU product entered
             * regionExactBacklog. GPU admission below is intentionally independent
             * from producer re-offer state.
             */

            boolean branchOnlyForeground = currentFullscreen
                    && CaveScreenSpacePolicy.branchOnly(
                            fullscreenPlanner.scale, MapRequestLane.FULLSCREEN);
            boolean publishedBranchCoverage = branchOnlyForeground
                    && imported.complete()
                    && lodTree.coversPage(key.dimension(), imported.view(), key.layerY(),
                            imported.globalPageX(), imported.globalPageZ(),
                            imported.sourceRevision());
            if (branchOnlyForeground && publishedBranchCoverage) {
                synchronized (pages) {
                    PageRequest exactRequest = requests.get(key);
                    if (exactRequest != null) {
                        exactRequest.clearLane(MapRequestLane.FULLSCREEN);
                        if (exactRequest.isExpired(now)) {
                            removeRequestOwnershipLocked(key, exactRequest);
                        }
                    }
                }
                regionExactBacklog.remove(key, imported);
                admitted++;
                continue;
            }
            if (branchOnlyForeground) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String fenceKey = "CAVE_VISUAL_PUBLICATION_FENCE_FALLBACK:"
                        + key.dimension() + ':' + key.view() + ':' + key.layerY()
                        + ':' + key.globalPageX() + ':' + key.globalPageZ();
                if (recorder.shouldEmitEvent(fenceKey, 500L)) {
                    recorder.event("CAVE_VISUAL_PUBLICATION_FENCE_FALLBACK",
                            "page=" + imported.globalPageX() + ','
                                    + imported.globalPageZ()
                                    + " view=" + imported.view()
                                    + " top_y=" + imported.projectionTopY()
                                    + " source_revision=" + imported.sourceRevision()
                                    + " action=publish_exact_underlay"
                                    + " policy=projected_not_visible_until_exact_or_branch_gpu"
                                    + " pass=PASS153");
                }
            }

            synchronized (pages) {
                PageInfo info = pages.computeIfAbsent(key, PageInfo::new);
                if (info.isProjectionAuthoritative(imported.projectionTopY())
                        && info.uploadedSourceRevision == imported.sourceRevision()
                        && info.frontLods != null) {
                    /*
                     * This was the persistent-hole bug visible in PASS152.
                     * releaseAtlasSlot() intentionally retains frontLods, source
                     * revision and projection identity. The old condition treated
                     * that CPU cache as if the atlas/page-table were still resident,
                     * cleared the request, removed this immutable projected page, and
                     * then did the same thing again on every retry. Result:
                     * source=16/16 + projection=true + GPU=false forever.
                     */
                    if (info.knownEmpty
                            || info.initialized && info.atlasSlot >= 0) {
                        clearSatisfiedRequest(key, now);
                        regionExactBacklog.remove(key, imported);
                        continue;
                    }
                    if (info.knownColumns > 0
                            && info.canRenderProjection(imported.projectionTopY())
                            && restoreCavePageResidency(info, imported.lane())) {
                        recordVisualPublicationFenceRestore(info, imported.lane(),
                                "region_projection_cpu_residency_restore");
                        markPresentationDisplayed(imported.presentationGeneration());
                        clearSatisfiedRequest(key, now);
                        regionExactBacklog.remove(key, imported);
                        admitted++;
                        continue;
                    }
                    /*
                     * If the direct re-upload cannot reserve GPU budget this frame,
                     * DO NOT clear demand and DO NOT remove the retained projected
                     * page. Leave it in regionExactBacklog for the next frame. This
                     * makes CPU-ready -> GPU-ready a real publication fence instead
                     * of a lossy ownership handoff.
                     */
                    if (info.knownColumns > 0
                            && info.canRenderProjection(imported.projectionTopY())) {
                        break;
                    }
                }
                if (info.pending != null && info.pending.isDone()
                        && !info.pending.isCompletedExceptionally()
                        && !info.pending.isCancelled()) {
                    BuildResult pendingResult = info.pending.getNow(null);
                    if (pendingResult != null && pendingResult.regionImported()
                            && pendingResult.sourceRevision()
                                    == imported.sourceRevision()
                            && pendingResult.projectionTopY()
                                    == imported.projectionTopY()) {
                        regionExactBacklog.remove(key, imported);
                        continue;
                    }
                }
                if (info.pending != null) {
                    // Detach through the queue-aware helper. A superseded future
                    // may already have a CompletedBuild ownership record; leaving
                    // that record behind only makes publication discard it later.
                    detachPendingLocked(info, true);
                }
                long revision = revisions.computeIfAbsent(key, ignored -> 1L);
                BuildResult result = new BuildResult(revision,
                        imported.sourceRevision(), imported.projectionTopY(),
                        imported.pixelsUnsafe(), imported.knownRowsUnsafe(),
                        imported.knownColumnCount(), imported.complete(),
                        imported.emptyProof(), false, true);
                CompletableFuture<BuildResult> future =
                        CompletableFuture.completedFuture(result);
                info.pending = future;
                info.pendingToken = null;
                info.pendingLane = imported.lane();
                info.pendingProjectionTopY = imported.projectionTopY();
                info.pendingCompletionRecorded = true;
                completedBuilds.offer(new CompletedBuild(info, future,
                        imported.lane(), fullscreenOrdinalFor(key),
                        completedSequence.getAndIncrement(),
                        imported.presentationGeneration()));
                ExactPageStateTracker.getInstance().transition(
                        stateKey(key), ExactPageState.CPU_READY,
                        imported.lane(), revision);
            }
            regionExactBacklog.remove(key, imported);
            admitted++;
        }
        return admitted;
    }

    /**
     * Pull the exact current fullscreen frontier from the retained projection cache
     * before polling arbitrary async completions. This is intentionally a direct
     * coordinate lookup: completion order must not consume the publication slice.
     */
    private boolean bufferRetainedFullscreenFrontier(
            CaveRegionProjectionService service, long now) {
        VisiblePlanner planner = visiblePlans.get(MapRequestLane.FULLSCREEN);
        if (!plannerActive(planner, now) || !planner.fullscreen
                || planner.publicationCursor >= planner.pagePlan.length) {
            return false;
        }
        long packed = planner.pagePlan[planner.publicationCursor];
        int pageX = CaveLoadHierarchy.x(packed);
        int pageZ = CaveLoadHierarchy.z(packed);
        PageKey frontierKey = new PageKey(planner.dimension, planner.view,
                planner.layerY, pageX, pageZ);
        if (regionExactBacklog.containsKey(frontierKey)) return true;

        /* PASS170: retained final CPU pixels outrank a stale/duplicate PageInfo
         * future. PASS169 checked info.pending first, so a detached or obsolete
         * pending future could hide a perfectly current ProjectedPage forever; the
         * repair loop then kept reusing the same producer product without ingesting
         * it. Pull by coordinate first, exactly like a writer reading its loaded
         * MapTileChunk product. */
        CaveRegionProjectionService.ProjectedPage retained = service.find(
                planner.view, planner.projectionTopY, pageX, pageZ,
                planner.dimension);
        if (retained == null) {
            synchronized (pages) {
                PageInfo info = pages.get(frontierKey);
                if (info != null && info.pending != null
                        && info.pendingProjectionTopY == planner.projectionTopY
                        && !info.pending.isDone()) {
                    return true;
                }
            }
            return false;
        }
        CavePresentationGeneration generation;
        synchronized (pages) {
            generation = loadingPresentationGenerations.get(
                    MapRequestLane.FULLSCREEN);
        }
        regionExactBacklog.put(frontierKey,
                retained.asDirectOffer(MapRequestLane.FULLSCREEN, generation));
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        String eventKey = "CAVE_FULLSCREEN_FRONTIER_BACKLOG_DIRECT_HIT:"
                + planner.dimension + ':' + planner.view + ':'
                + planner.projectionTopY + ':' + pageX + ':' + pageZ;
        if (recorder.shouldEmitEvent(eventKey, 250L)) {
            recorder.event("CAVE_FULLSCREEN_FRONTIER_BACKLOG_DIRECT_HIT",
                    "cursor=" + planner.publicationCursor + '/'
                            + planner.pagePlan.length
                            + " page=" + pageX + ',' + pageZ
                            + " backlog=" + regionExactBacklog.size()
                            + " action=publish_frontier_before_future"
                            + " policy=coordinate_selected_retained_product"
                            + " pass=PASS170");
        }
        return true;
    }

    /**
     * Chooses retained exact work for one presentation writer, not completion order.
     * The current row-major frontier is the only fullscreen page allowed to mutate
     * the exact atlas. Later pages stay in the acknowledged CPU backlog.
     */
    private PageKey selectRegionExactBacklogKey(
            CaveRegionProjectionService service, long now) {
        VisiblePlanner planner = visiblePlans.get(MapRequestLane.FULLSCREEN);
        if (plannerActive(planner, now) && planner.fullscreen
                && planner.publicationCursor < planner.pagePlan.length) {
            bufferRetainedFullscreenFrontier(service, now);
            long packed = planner.pagePlan[planner.publicationCursor];
            PageKey frontierKey = new PageKey(planner.dimension, planner.view,
                    planner.layerY, CaveLoadHierarchy.x(packed),
                    CaveLoadHierarchy.z(packed));
            if (regionExactBacklog.containsKey(frontierKey)) {
                return frontierKey;
            }
        }

        PageKey fallback = null;
        for (Map.Entry<PageKey, CaveRegionProjectionService.ProjectedPage> entry
                : regionExactBacklog.entrySet()) {
            PageKey key = entry.getKey();
            CaveRegionProjectionService.ProjectedPage imported = entry.getValue();
            if (plannerActive(planner, now)
                    && imported.lane() == MapRequestLane.FULLSCREEN
                    && planner.matches(key)
                    && planner.projectionTopY == imported.projectionTopY()) {
                // Current fullscreen pages are already retained/ACKed; wait for the
                // writer cursor instead of publishing them by completion order.
                continue;
            }
            if (fallback == null) fallback = key;
        }
        return fallback;
    }

    private int fullscreenOrdinalFor(PageKey key) {
        VisiblePlanner planner = visiblePlans.get(MapRequestLane.FULLSCREEN);
        if (planner == null || key == null || !planner.matches(key)) {
            return Integer.MAX_VALUE;
        }
        int ordinal = planner.ordinalOf(
                key.globalPageX(), key.globalPageZ());
        return ordinal < 0 ? Integer.MAX_VALUE : ordinal;
    }

    private void synchronizeAtlasStorage() {
        atlas.ensureInitialized();
        synchronized (pages) {
            lodTree.synchronizeStorage();
        }
        long generation = atlas.storageGeneration();
        boolean pageReload = observedAtlasGeneration != Long.MIN_VALUE
                && generation != observedAtlasGeneration;
        observedAtlasGeneration = generation;
        if (!pageReload) return;
        MapResidencyManager.getInstance().markTopologyChanged();
        exactTopologyRevision.incrementAndGet();
        synchronized (pages) {
            residentNodeCounts.clear();
            // A resource reload recreates empty GL storage. Keep CPU page buffers
            // and slot ownership, but force every visible page through a full upload.
            for (PageInfo info : pages.values()) {
                info.initialized = false;
                info.uploadedRevision = 0L;
                info.uploadedSourceRevision = Long.MIN_VALUE;
                info.nextRetryMs = 0L;
            }
        }
    }

    public void invalidateStyle() {
        LodBranchDiskCache.getInstance().invalidateCurrentDimension();
        styleRefreshUntilMs = System.currentTimeMillis() + STYLE_REFRESH_WINDOW_MS;
        lastUploadMs = 0L;
        lastUploadFrameNanos = 0L;
        synchronized (pages) {
            regionImageEpoch++;
            completedRegionImages.clear();
            pendingRegionImageReads.clear();
            queuedRegionImageInstalls.clear();
            pendingRegionImageWrites.clear();
            regionImageMissUntil.clear();
            consumedRegionImageTimestamps.clear();
            dirtyRegionImages.clear();
            regionExactBacklog.clear();
            stagedRegionBranchRevisions.clear();
            for (PageKey key : pages.keySet()) revisions.merge(key, 1L, Long::sum);
            // Re-enumerate the visible plans immediately. Existing atlas pages stay
            // visible until replacements publish, so this is a revision sweep rather
            // than a destructive cache clear.
            for (VisiblePlanner planner : visiblePlans.values()) {
                planner.pageCursor = 0;
                planner.nextRestartMs = 0L;
                planner.lastEnumerationMs = Long.MIN_VALUE;
            }
        }
    }

    public void clear() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(this::clear);
            return;
        }
        List<PageInfo> close;
        synchronized (pages) {
            close = new ArrayList<>(pages.values());
            close.addAll(deferredCloses);
            pages.clear();
            residentNodeCounts.clear();
            requests.clear();
            revisions.clear();
            activeLayerProjections.clear();
            activeLayerBandByDimension.clear();
            previousLayerBandByDimension.clear();
            deferredCloses.clear();
            completedBuilds.clear();
            completedPollScratch.clear();
            completedRegionImages.clear();
            pendingRegionImageReads.clear();
            queuedRegionImageInstalls.clear();
            pendingRegionImageWrites.clear();
            regionImageMissUntil.clear();
            consumedRegionImageTimestamps.clear();
            dirtyRegionImages.clear();
            regionExactBacklog.clear();
            stagedRegionBranchRevisions.clear();
            loadingPresentationGenerations.clear();
            displayedPresentationGenerations.clear();
            previousPresentationGenerations.clear();
            regionImageEpoch++;
            gpuPublicationSequence = 0L;
            exactTopologyRevision.incrementAndGet();
            for (VisiblePlanner planner : visiblePlans.values()) planner.clear();
        }
        close.forEach(PageInfo::close);
        synchronized (pages) {
            lodTree.clear();
        }
        atlas.resetSlots();
        ExactPageStateTracker.getInstance().clearPrefix("cave:");
    }

    /**
     * Cancels dimension-owned work while retaining immutable CPU/GPU pages and LOD
     * branches under their dimension-qualified keys. The atlas is shared and its
     * normal residency policy may evict inactive dimensions when space is needed.
     * Returning to a previously viewed dimension can therefore draw the last-good
     * cave map immediately instead of rebuilding every page from disk.
     */
    public void suspendForDimensionSwitch() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(this::suspendForDimensionSwitch);
            return;
        }
        synchronized (pages) {
            for (PageInfo info : pages.values()) {
                if (info.pending != null) detachPendingLocked(info, false);
            }
            for (PageKey requestKey : requests.keySet()) {
                ExactPageStateTracker.getInstance().removeIfState(
                        stateKey(requestKey), ExactPageState.REQUESTED);
            }
            requests.clear();
            completedBuilds.clear();
            completedPollScratch.clear();
            loadingPresentationGenerations.clear();
            displayedPresentationGenerations.clear();
            previousPresentationGenerations.clear();
            for (VisiblePlanner planner : visiblePlans.values()) planner.clear();
            gpuPublicationSequence = 0L;
            exactTopologyRevision.incrementAndGet();
        }
    }

    private boolean isPageSatisfied(PageKey key) {
        synchronized (pages) {
            return isPageSatisfiedLocked(key);
        }
    }

    /** A cold fullscreen page becomes visible only as one coherent 64x64 unit.
     * Other coordinates remain independent, so page atomicity cannot become a
     * viewport-wide publication barrier. */
    private boolean isPageDisplayReady(PageKey key) {
        synchronized (pages) {
            return isPageDisplayReadyLocked(key);
        }
    }

    private boolean isPageDisplayReadyLocked(PageKey key) {
        if (isPageSatisfiedLocked(key)) return true;
        PageInfo info = pages.get(key);
        if (info == null) return false;

        if (info.knownEmpty && matchesAuthoritativeProjectionLocked(info)) return true;
        return info.initialized && info.atlasSlot >= 0
                && hasCoherentVisiblePageLocked(info)
                && matchesActiveProjectionLocked(info);
    }

    /** Coverage authority is stricter than projection identity for Full Cave. */
    private boolean hasCoherentVisiblePageLocked(PageInfo info) {
        if (info == null) return false;
        if (info.key.view() == CaveView.FULL) {
            return info.isProjectionAuthoritative(Integer.MIN_VALUE);
        }
        ProjectionBandKey band = new ProjectionBandKey(info.key.dimension(),
                info.key.view(), info.key.layerY());
        int active = activeLayerProjections.getOrDefault(
                band, info.publishedProjectionTopY);
        return info.isProjectionAuthoritative(active);
    }

    private boolean isPageActive(PageKey key, MapRequestLane lane, long now) {
        synchronized (pages) {
            return isPageActiveLocked(key, lane, now);
        }
    }

    private boolean isPageActiveLocked(PageKey key, MapRequestLane lane, long now) {
        PageRequest request = requests.get(key);
        if (request != null && request.isLaneActive(lane, now)) return true;
        PageInfo info = pages.get(key);
        return info != null && info.pending != null && info.pendingLane == lane;
    }

    private boolean isPageSatisfiedLocked(PageKey key) {
        PageInfo info = pages.get(key);
        if (info == null || info.pending != null) return false;
        if (!matchesAuthoritativeProjectionLocked(info)) return false;
        long requestedRevision = revisions.getOrDefault(key, 1L);
        int projectionTopY = key.view() == CaveView.FULL
                ? Integer.MIN_VALUE : activeLayerProjections.getOrDefault(
                        new ProjectionBandKey(key.dimension(), key.view(), key.layerY()),
                        key.layerY());
        long sourceRevision = repository.getPageRevision(
                key.view(), projectionTopY,
                key.globalPageX(), key.globalPageZ());
        int fullColumns = CaveTextureAtlas.PAGE_SIZE * CaveTextureAtlas.PAGE_SIZE;
        return info.uploadedRevision == requestedRevision
                && info.uploadedSourceRevision == sourceRevision
                && (info.knownEmpty
                        || (info.initialized && info.atlasSlot >= 0
                                && info.knownColumns >= fullColumns));
    }

    private void clearSatisfiedRequest(PageKey key, long now) {
        synchronized (pages) {
            PageRequest request = requests.get(key);
            if (request == null) return;
            request.clearAll();
            if (request.isExpired(now)) {
                removeRequestOwnershipLocked(key, request);
            }
        }
    }

    private void requestPage(CaveView view, int layerY,
            int globalPageX, int globalPageZ, int priority,
            MapRequestLane lane, long now) {
        requestPage(view, layerY, globalPageX, globalPageZ,
                priority, lane, now, Integer.MAX_VALUE);
    }

    private void requestPage(CaveView view, int layerY,
            int globalPageX, int globalPageZ, int priority,
            MapRequestLane lane, long now, int fullscreenOrdinal) {
        layerY = projectionTopY(view, layerY);
        PageKey key = key(view, layerY, globalPageX, globalPageZ);
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        synchronized (pages) {
            revisions.putIfAbsent(key, 1L);
            if (isPageSatisfiedLocked(key)) {
                PageRequest satisfied = requests.get(key);
                if (satisfied != null) {
                    satisfied.clearAll();
                    if (satisfied.isExpired(now)) {
                        removeRequestOwnershipLocked(key, satisfied);
                    }
                }
                ExactPageStateTracker.getInstance().removeIfState(
                        stateKey(key), ExactPageState.REQUESTED);
                return;
            }
            PageRequest existing = requests.get(key);
            if (existing == null) {
                existing = new PageRequest(key, layerY);
                requests.put(key, existing);
            } else if (existing.projectionTopY != layerY) {
                existing.retarget(layerY);
                revisions.merge(key, 1L, Long::sum);
                PageInfo info = pages.get(key);
                if (info != null) {
                    // Legacy caches may still contain a pre-Pass-30 projection for
                    // this band. Keep that complete page visible while the canonical
                    // replacement is staged, then swap the whole 64x64 page once.
                    info.beginProjectionTransition(layerY);
                    info.nextRetryMs = 0L;
                }
            }
            existing.observe(effectiveLane, priority, now, fullscreenOrdinal);
            trimLaneShortlist(effectiveLane, now);
            ExactPageStateTracker.getInstance().transition(
                    stateKey(key), ExactPageState.REQUESTED,
                    effectiveLane, revisions.getOrDefault(key, 1L));
            PageInfo pendingInfo = pages.get(key);
            if (pendingInfo != null && pendingInfo.pending != null
                    && effectiveLane.strongerThan(pendingInfo.pendingLane)) {
                /* A completed exact payload is independent from the request lane.
                 * PASS68 detached it merely to change publication priority, then
                 * rebuilt the same page while the immutable completion was already
                 * waiting for the render thread. Promote completed work in place;
                 * only a genuinely unfinished lower-priority task is resubmitted. */
                if (pendingInfo.pending.isDone()) {
                    promotePendingLaneLocked(pendingInfo, effectiveLane, now,
                            "foreground-request");
                } else {
                    detachPendingLocked(pendingInfo, true);
                }
            }
            while (requests.size() > MAX_REQUESTS) {
                PageKey retired = weakestRequestKey(now);
                if (retired == null) break;
                requests.remove(retired);
                ExactPageStateTracker.getInstance().removeIfState(
                        stateKey(retired), ExactPageState.REQUESTED);
                if (!pages.containsKey(retired)) revisions.remove(retired);
            }
        }
    }

    private PageKey weakestRequestKey(long now) {
        PageKey selected = null;
        int selectedPriority = Integer.MAX_VALUE;
        long selectedSeen = Long.MAX_VALUE;
        for (Map.Entry<PageKey, PageRequest> entry : requests.entrySet()) {
            PageRequest request = entry.getValue();
            int priority = request.effectivePriority(now);
            long seen = request.latestSeenMs();
            if (selected == null || priority < selectedPriority
                    || (priority == selectedPriority && seen < selectedSeen)) {
                selected = entry.getKey();
                selectedPriority = priority;
                selectedSeen = seen;
            }
        }
        return selected;
    }

    private void trimLaneShortlist(MapRequestLane lane, long now) {
        int maximum = switch (lane) {
            case MINIMAP -> 32;
            case FULLSCREEN -> 640;
            case BACKGROUND -> 8;
            case PREFETCH -> 4;
        };
        for (PageRequest request : requests.values()) {
            if (isPageSatisfiedLocked(request.key)) request.clearAll();
        }
        int active = 0;
        for (PageRequest request : requests.values()) {
            if (request.isLaneActive(lane, now)) active++;
        }
        while (active > maximum) {
            PageRequest weakest = null;
            for (PageRequest request : requests.values()) {
                if (!request.isLaneActive(lane, now)) continue;
                if (weakest == null
                        || request.priorityForLane(lane) < weakest.priorityForLane(lane)
                        || (request.priorityForLane(lane) == weakest.priorityForLane(lane)
                                && request.seenForLane(lane) < weakest.seenForLane(lane))) {
                    weakest = request;
                }
            }
            if (weakest == null) break;
            weakest.clearLane(lane);
            if (weakest.isExpired(now)) {
                ExactPageStateTracker.getInstance().removeIfState(
                        stateKey(weakest.key), ExactPageState.REQUESTED);
            }
            active--;
        }
        var expiredIterator = requests.entrySet().iterator();
        while (expiredIterator.hasNext()) {
            Map.Entry<PageKey, PageRequest> entry = expiredIterator.next();
            if (!entry.getValue().isExpired(now)) continue;
            ExactPageStateTracker.getInstance().removeIfState(
                    stateKey(entry.getKey()), ExactPageState.REQUESTED);
            expiredIterator.remove();
        }
    }

    private int publishCompleted(int budget, long deadline, long now) {
        int published = 0;
        while (published < budget && System.nanoTime() < deadline) {
            CompletedBuild completed = pollHighestPriorityCompleted(now);
            if (completed == null) break;
            PageInfo info = completed.info();
            CompletableFuture<BuildResult> pending = completed.future();
            if (info.pending != pending) {
                pipelineTelemetry.recordTaskCompletedButDiscarded();
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent("CAVE_COMPLETION_REPLACED", 250L)) {
                    recorder.event("CAVE_COMPLETION_REPLACED",
                            "page=" + info.key + " lane=" + completed.lane()
                                    + " ordinal=" + completed.fullscreenOrdinal());
                }
                continue;
            }

            BuildResult result;
            try {
                result = pending.join();
            } catch (Throwable throwable) {
                Throwable cause = throwable;
                while (cause.getCause() != null) cause = cause.getCause();
                info.pending = null;
                info.pendingToken = null;
                info.pendingLane = null;
                info.pendingProjectionTopY = Integer.MIN_VALUE;
                info.pendingCompletionRecorded = false;
                info.nextRetryMs = cause instanceof CancellationException
                        ? now + 16L : now + FAILED_RETRY_MS;
                pipelineTelemetry.recordExactBuildDiscarded();
                ExactPageStateTracker.getInstance().transition(
                        stateKey(info.key), cause instanceof CancellationException
                                ? ExactPageState.STALE_GENERATION
                                : ExactPageState.FAILED_RETRYABLE,
                        completed.lane(), revisions.getOrDefault(info.key, 1L));
                if (!(cause instanceof CancellationException)) {
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    if (recorder.shouldEmitEvent("CAVE_BUILD_FAILED:" + info.key, 500L)) {
                        recorder.event("CAVE_BUILD_FAILED",
                                "page=" + info.key + " lane=" + completed.lane()
                                        + " failure=" + cause.getClass().getSimpleName()
                                        + ':' + String.valueOf(cause.getMessage()));
                    }
                }
                continue;
            }
            MapRequestLane completedLane = completed.lane();
            boolean loadingGenerationOwned = presentationGenerationOwned(
                    completed.presentationGeneration());
            if (result.projectionTopY() != info.pendingProjectionTopY
                    || !loadingGenerationOwned
                            && !isProjectionStillOwned(
                                    info.key, result.projectionTopY(), now)) {
                int rejectedPendingTopY = info.pendingProjectionTopY;
                info.pending = null;
                info.pendingToken = null;
                info.pendingLane = null;
                info.pendingProjectionTopY = Integer.MIN_VALUE;
                info.pendingCompletionRecorded = false;
                info.nextRetryMs = now;
                pipelineTelemetry.recordExactBuildDiscarded();
                pipelineTelemetry.recordTaskCompletedButDiscarded();
                ExactPageStateTracker.getInstance().transition(
                        stateKey(info.key), ExactPageState.STALE_GENERATION,
                        completedLane, revisions.getOrDefault(info.key, 1L));
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent(
                        "CAVE_COMPLETION_NO_LONGER_OWNED", 250L)) {
                    recorder.event("CAVE_COMPLETION_NO_LONGER_OWNED",
                            "page=" + info.key
                                    + " result_top_y=" + result.projectionTopY()
                                    + " pending_top_y=" + rejectedPendingTopY
                                    + " lane=" + completedLane
                                    + " request_owned="
                                    + isProjectionStillRequested(info.key,
                                            result.projectionTopY())
                                    + " viewport_owned="
                                    + isProjectionViewportOwned(info.key,
                                            result.projectionTopY(), now)
                                    + " generation_owned="
                                    + loadingGenerationOwned
                                    + " generation="
                                    + (completed.presentationGeneration() == null
                                            ? 0L
                                            : completed.presentationGeneration().id()));
                }
                continue;
            }
            if (!info.pendingCompletionRecorded) {
                pipelineTelemetry.recordExactBuildCompleted();
                info.pendingCompletionRecorded = true;
            }
            long current;
            synchronized (pages) {
                current = revisions.getOrDefault(info.key, 0L);
            }
            long currentSource = repository.getPageRevision(
                    info.key.view(), result.projectionTopY(),
                    info.key.globalPageX(), info.key.globalPageZ());
            /*
             * Source revisions are content fingerprints, not monotonic counters.
             * Ordering them with '<' or '>' made stale acceptance depend on the
             * numerical shape of a hash. A completed page is valid only for the
             * exact projection-scoped source fingerprint it was built from.
             */
            boolean sourceChanged = result.sourceRevision() != currentSource;
            if (result.expectedRevision() != current
                    || (!result.superseded() && sourceChanged)
                    || !info.key.dimension().equals(dimension())) {
                info.pending = null;
                info.pendingToken = null;
                info.pendingLane = null;
                info.pendingProjectionTopY = Integer.MIN_VALUE;
                info.pendingCompletionRecorded = false;
                info.nextRetryMs = now;
                pipelineTelemetry.recordExactBuildDiscarded();
                pipelineTelemetry.recordTaskCompletedButDiscarded();
                ExactPageStateTracker.getInstance().transition(
                        stateKey(info.key), ExactPageState.STALE_GENERATION,
                        completedLane, current);
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent("CAVE_RESULT_STALE:" + info.key, 250L)) {
                    recorder.event("CAVE_RESULT_STALE",
                            "page=" + info.key + " lane=" + completedLane
                                    + " expected=" + result.expectedRevision()
                                    + " current=" + current + " source="
                                    + result.sourceRevision() + " current_source="
                                    + currentSource + " current_dimension=" + dimension());
                }
                continue;
            }
            info.observeSourceRevision(currentSource, now);
            if (result.superseded()) {
                info.restartSourceSettleWindow(currentSource, now);
                info.pending = null;
                info.pendingToken = null;
                info.pendingLane = null;
                info.pendingProjectionTopY = Integer.MIN_VALUE;
                info.pendingCompletionRecorded = false;
                info.nextRetryMs = Math.max(now + 16L,
                        info.sourceSettleDeadlineMs());
                ExactPageStateTracker.getInstance().transition(
                        stateKey(info.key), info.knownColumns > 0
                                ? ExactPageState.CPU_PARTIAL
                                : ExactPageState.REQUESTED,
                        completedLane, current);
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent("CAVE_BUILD_SOURCE_COALESCED:" + info.key,
                        500L)) {
                    recorder.event("CAVE_BUILD_SOURCE_COALESCED",
                            "page=" + info.key + " lane=" + completedLane
                                    + " current_source=" + currentSource
                                    + " known_columns=" + info.knownColumns);
                }
                continue;
            }
            if (result.knownColumns() == 0 && !result.complete()) {
                info.pending = null;
                info.pendingToken = null;
                info.pendingLane = null;
                info.pendingProjectionTopY = Integer.MIN_VALUE;
                info.pendingCompletionRecorded = false;
                boolean authoritativeNoSource =
                        repository.hasCompleteProjectionSourcePage(
                                info.key.view(), result.projectionTopY(),
                                info.key.globalPageX(), info.key.globalPageZ());
                info.noSourceRevision = authoritativeNoSource
                        ? result.sourceRevision() : Long.MIN_VALUE;
                // An incomplete source window is not negative authority. A live,
                // archive or decoded child can become available without changing
                // the exact presentation revision that produced this empty probe.
                // Keep foreground retry alive until all 16 children are archived
                // or explicitly proven absent.
                // CIMG is a visual fallback, not source authority. One empty
                // authoritative probe is enough: bind the fallback to the current
                // repository revision so it remains stable until real CVD/Anvil/live
                // data advances that revision. This avoids rebuilding an empty
                // remote-multiplayer page forever.
                if (info.regionImageFallback) {
                    if (authoritativeNoSource && result.sourceRevision() != 0L) {
                        info.uploadedRevision = current;
                        info.uploadedSourceRevision = result.sourceRevision();
                        info.markSourceSettled(result.sourceRevision());
                        info.nextRetryMs = 0L;
                        if (info.initialized && info.atlasSlot >= 0) {
                            clearSatisfiedRequest(info.key, now);
                        }
                    } else {
                        // Optimistic CIMG is still useful pixels, but an unresolved
                        // source revision is not permission to stop verification.
                        info.uploadedSourceRevision =
                                UNVERIFIED_REGION_IMAGE_SOURCE_REVISION;
                        info.nextRetryMs = now + PARTIAL_NO_SOURCE_RETRY_MS;
                    }
                    ExactPageStateTracker.getInstance().transition(
                            stateKey(info.key), info.initialized
                                    ? ExactPageState.GPU_READY
                                    : ExactPageState.CPU_READY,
                            completedLane, current);
                    continue;
                }
                // Nothing in this build is authoritative yet. Keep the previous
                // atlas page untouched and retry when source capture advances.
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent("CAVE_PAGE_NO_SOURCE:" + info.key, 500L)) {
                    recorder.event("CAVE_PAGE_NO_SOURCE",
                            "page=" + info.key + " lane=" + completedLane
                                    + " complete=" + result.complete()
                                    + " authoritative=" + authoritativeNoSource
                                    + " source_revision=" + result.sourceRevision());
                }
                info.nextRetryMs = Math.max(now + (authoritativeNoSource
                                ? INCOMPLETE_RETRY_MS : PARTIAL_NO_SOURCE_RETRY_MS),
                        info.sourceSettleDeadlineMs());
                ExactPageStateTracker.getInstance().transition(
                        stateKey(info.key), ExactPageState.ABSENT,
                        completedLane, current);
                continue;
            }
            boolean firstGpuPublication = !info.initialized;
            boolean wasRegionImageFallback = info.regionImageFallback;
            ApplyOutcome outcome = apply(info, result.projectionTopY(), result.pixels(),
                    result.knownRows(), result.complete(), result.emptyProof(),
                    result.sourceRevision(), now);
            if (outcome.retryablePublication()) {
                if (outcome.deferral() == PublicationDeferral.GPU_BUDGET) {
                    deferCaveGpuRetry(info, completedLane, now);
                } else if (outcome.deferral() == PublicationDeferral.COALESCE) {
                    info.nextPublicationAttemptMs = now + CaveTilePublicationPolicy.RETRY_MS;
                } else {
                    info.nextPublicationAttemptMs = now + ATLAS_PUBLICATION_RETRY_MS;
                }
                ExactPageStateTracker.getInstance().transition(
                        stateKey(info.key), ExactPageState.CPU_READY,
                        completedLane, current);
                completedBuilds.offer(completed);
                if (outcome.deferral() == PublicationDeferral.COALESCE) {
                    // This page is waiting for more 16x16 tiles. Keep scanning the
                    // completed queue so one sparse page cannot waste the whole
                    // viewport publication window.
                    continue;
                }
                boolean weakLane = completedLane == MapRequestLane.BACKGROUND
                        || completedLane == MapRequestLane.PREFETCH;
                if (weakLane) {
                    /* BACKGROUND/PREFETCH cannot consume the protected foreground
                     * reserve when the adaptive frame budget is below that reserve.
                     * Breaking here made one permanently denied weak completion a
                     * global head-of-line barrier for CPU-ready fullscreen pages. */
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    if (recorder.shouldEmitEvent(
                            "CAVE_WEAK_COMPLETION_BYPASSED", 500L)) {
                        recorder.event("CAVE_WEAK_COMPLETION_BYPASSED",
                                "page=" + info.key + " lane=" + completedLane
                                        + " deferral=" + outcome.deferral());
                    }
                    continue;
                }
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String event = outcome.deferral() == PublicationDeferral.ATLAS_SLOT
                        ? "CAVE_ATLAS_SLOT_DEFERRED"
                        : "CAVE_GPU_BUDGET_DEFERRED";
                if (recorder.shouldEmitEvent(event, 500L)) {
                    recorder.event(event,
                            "page=" + info.key + " lane=" + completedLane
                                    + " atlas_capacity=" + CaveTextureAtlas.SLOT_COUNT
                                    + " retained_pages=" + pages.size());
                }
                break;
            }
            info.pending = null;
            info.pendingToken = null;
            info.pendingCompletionRecorded = false;
            info.pendingProjectionTopY = Integer.MIN_VALUE;
            info.pendingLane = null;
            resetCaveGpuRetry(info, completedLane);
            if (!outcome.applied()) {
                markIncomplete(info, now);
                continue;
            }
            info.uploadedRevision = current;
            boolean exactReplacementComplete = result.complete();
            if (wasRegionImageFallback && !exactReplacementComplete) {
                // Keep the full cached page as visual authority underneath the fresh
                // child tiles. Do not let one partial exact wave turn an optimistic
                // CIMG into a falsely source-satisfied page.
                info.uploadedSourceRevision =
                        UNVERIFIED_REGION_IMAGE_SOURCE_REVISION;
                info.regionImageFallback = true;
            } else {
                info.uploadedSourceRevision = result.sourceRevision();
                info.regionImageFallback = false;
            }
            if (!result.regionImported() && !info.regionImageFallback) {
                markRegionImageDirty(info, result.projectionTopY(), now);
            }
            info.noSourceRevision = Long.MIN_VALUE;
            info.lastPublicationMs = now;
            markPresentationDisplayed(completed.presentationGeneration());
            if (result.complete()) {
                info.markSourceSettled(currentSource);
                info.nextRetryMs = 0L;
                clearSatisfiedRequest(info.key, now);
            } else {
                info.nextRetryMs = now + INCOMPLETE_RETRY_MS;
            }
            if (firstGpuPublication && outcome.hasContent()) {
                MapDebugRecorder.getInstance().event(
                        result.regionImported()
                                ? "CAVE_REGION_PAGE_GPU_READY"
                                : "CAVE_PAGE_GPU_READY",
                        "page=" + info.key + " lane=" + completedLane
                                + " known_columns=" + result.knownColumns()
                                + " complete=" + result.complete()
                                + " region_imported=" + result.regionImported()
                                + " expected_revision=" + result.expectedRevision()
                                + " source_revision=" + result.sourceRevision());
            }
            published++;

            /* PASS170 / Xaero writer loop: one committed fullscreen page advances
             * the writer immediately, then pulls the next already-prepared CPU page
             * in the same render-thread time slice. Without this step the strict GPU
             * writer would publish only one 64x64 page per frame even when the whole
             * next row was ready, turning ordering into an artificial throughput cap. */
            if (completedLane == MapRequestLane.FULLSCREEN) {
                VisiblePlanner writer = visiblePlans.get(MapRequestLane.FULLSCREEN);
                if (plannerActive(writer, now) && writer.matches(info.key)
                        && writer.projectionTopY == result.projectionTopY()) {
                    refreshFullscreenPublicationFrontier(writer, 1,
                            "single-writer-commit");
                    if (published < budget && System.nanoTime() < deadline) {
                        drainRegionProjectedPages(1, deadline, now);
                    }
                }
            }
        }
        trimPages();
        return published;
    }


    /**
     * Publication has its own latency ordering. CPU priority alone is not enough:
     * a completed fullscreen burst must not sit in front of a ready minimap page.
     */
    private CompletedBuild pollHighestPriorityCompleted(long now) {
        VisiblePlanner fullscreen = visiblePlans.get(MapRequestLane.FULLSCREEN);
        completedPollScratch.clear();
        CompletedBuild selected = null;
        /*
         * Never cap this scan at the build-ahead runway. A few early wavefront
         * completions can be temporarily delayed by the publication cursor or a
         * short GPU retry. With hundreds of later CPU-ready pages in the same
         * priority queue, inspecting only 72 entries created a permanent
         * head-of-line block: fullscreen appeared frozen, then the newly submitted
         * MINIMAP entries jumped to the front after MapScreen closed.
         *
         * The retained cave working set is bounded (MAX_PAGES), so one complete
         * priority-ordered scan per selected publication remains deterministic and
         * cheap compared with rebuilding or decoding another page.
         */
        int scanBudget = Math.min(completedBuilds.size(), MAX_PAGES);
        int deferredCount = 0;
        for (int scanned = 0; scanned < scanBudget; scanned++) {
            CompletedBuild candidate = completedBuilds.poll();
            if (candidate == null) break;
            if (!isCompletionStillAttached(candidate)) {
                pipelineTelemetry.recordTaskCompletedButDiscarded();
                continue;
            }
            candidate = reclassifyCompletedLane(candidate, now);
            if (candidate.info().nextPublicationAttemptMs <= now
                    && caveGpuLaneEligible(candidate.lane(), now)
                    && isCompletionPublicationEligible(candidate, fullscreen)) {
                selected = candidate;
                break;
            }
            completedPollScratch.add(candidate);
            deferredCount++;
        }
        for (CompletedBuild deferred : completedPollScratch) {
            completedBuilds.offer(deferred);
        }
        completedPollScratch.clear();
        if (selected != null && deferredCount >= FULLSCREEN_BUILD_AHEAD_PAGES) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent("CAVE_COMPLETION_QUEUE_BYPASS", 250L)) {
                recorder.event("CAVE_COMPLETION_QUEUE_BYPASS",
                        "deferred=" + deferredCount
                                + " remaining=" + completedBuilds.size()
                                + " selected_lane=" + selected.lane()
                                + " selected_page=" + selected.info().key);
            }
        }
        return selected;
    }

    private boolean isCompletionStillAttached(CompletedBuild completed) {
        if (completed == null) return false;
        synchronized (pages) {
            return completed.info().pending == completed.future();
        }
    }


    /**
     * Reclassifies an immutable CPU-ready completion from the lane that originally
     * scheduled its build to the strongest viewport that owns it now. Build output
     * does not depend on lane; rebuilding it to change GPU/publication priority is
     * pure duplicate work and can leave a BACKGROUND payload permanently unable to
     * consume the protected foreground GPU reserve.
     */
    private CompletedBuild reclassifyCompletedLane(CompletedBuild completed,
            long now) {
        if (completed == null) return null;
        MapRequestLane effective = completed.lane() == null
                ? MapRequestLane.BACKGROUND : completed.lane();
        int effectiveOrdinal = completed.fullscreenOrdinal();
        PageInfo info = completed.info();
        synchronized (pages) {
            if (info.pending != completed.future()) return completed;
            if (info.pendingLane != null
                    && info.pendingLane.strongerThan(effective)) {
                effective = info.pendingLane;
            }
            PageRequest request = requests.get(info.key);
            if (request != null && !request.isExpired(now)
                    && request.projectionTopY == info.pendingProjectionTopY) {
                MapRequestLane requestedLane = request.effectiveLane(now);
                if (requestedLane != null && requestedLane.strongerThan(effective)) {
                    effective = requestedLane;
                }
            }
            for (MapRequestLane viewportLane : REQUEST_LANES) {
                if (viewportLane != MapRequestLane.MINIMAP
                        && viewportLane != MapRequestLane.FULLSCREEN) continue;
                VisiblePlanner planner = visiblePlans.get(viewportLane);
                if (planner == null || now - planner.lastDemandMs
                        > ACTIVE_PLANNER_GRACE_MS) continue;
                if (planner.projectionTopY != info.pendingProjectionTopY
                        || !planner.matches(info.key)) continue;
                if (viewportLane.strongerThan(effective)) effective = viewportLane;
                if (viewportLane == MapRequestLane.FULLSCREEN) {
                    int ordinal = planner.ordinalOf(info.key.globalPageX(),
                            info.key.globalPageZ());
                    if (ordinal >= 0) effectiveOrdinal = ordinal;
                }
            }
            if (effective.strongerThan(info.pendingLane)) {
                promotePendingLaneLocked(info, effective, now,
                        "completed-publication");
            }
        }
        if (effective == completed.lane()
                && effectiveOrdinal == completed.fullscreenOrdinal()) return completed;
        return new CompletedBuild(info, completed.future(), effective,
                effectiveOrdinal, completed.sequence(),
                completed.presentationGeneration());
    }

    private void promotePendingLaneLocked(PageInfo info, MapRequestLane lane,
            long now, String reason) {
        if (info == null || lane == null || !lane.strongerThan(info.pendingLane)) return;
        MapRequestLane previous = info.pendingLane;
        info.pendingLane = lane;
        info.nextPublicationAttemptMs = 0L;
        info.gpuReservationFailures = 0;
        ExactPageStateTracker.getInstance().transition(
                stateKey(info.key), ExactPageState.CPU_READY,
                lane, revisions.getOrDefault(info.key, 1L));
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (recorder.shouldEmitEvent("CAVE_COMPLETED_LANE_PROMOTED:" + info.key,
                100L)) {
            recorder.event("CAVE_COMPLETED_LANE_PROMOTED",
                    "page=" + info.key + " from=" + previous + " to=" + lane
                            + " reason=" + reason + " now=" + now);
        }
    }

    /* PASS156: foreground work may prepare out of order, but fullscreen Cave
     * publication is committed through one deterministic scanline frontier. Retained
     * branch/exact data remains visible until the next prefix coordinate is ready. */

    private boolean plannerHasBranchCoverage(VisiblePlanner planner) {
        if (planner == null) return false;
        int minX = Math.max(planner.minPageX, planner.focusPageX - 1);
        int maxX = Math.min(planner.maxPageX, planner.focusPageX + 1);
        int minZ = Math.max(planner.minPageZ, planner.focusPageZ - 1);
        int maxZ = Math.min(planner.maxPageZ, planner.focusPageZ + 1);
        for (int pageZ = minZ; pageZ <= maxZ; pageZ++) {
            for (int pageX = minX; pageX <= maxX; pageX++) {
                long currentSource = repository.getPageRevision(planner.view,
                        planner.projectionTopY, pageX, pageZ);
                if (currentSource == 0L) continue;
                if (lodTree.coversPage(planner.dimension, planner.view,
                        planner.layerY, pageX, pageZ, currentSource)
                        || lodTree.hasPublishedCoverage(planner.dimension,
                                planner.view, planner.layerY, pageX, pageZ)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Advances the ordered fullscreen scanline reveal. Expensive work may finish
     * ahead, but an unresolved coordinate remains the frontier and is explicitly
     * repaired/re-offered instead of being skipped into a visible hole.
     *
     * Legacy PASS156 guard markers: strict_scanline_no_holes,
     * strict_scanline_prefix_ready_only.
     * PASS166 cumulative guard: repair_never_skip_visible_page.
     */
    private int refreshFullscreenPublicationFrontier(VisiblePlanner planner,
            int maximumAdvance, String reason) {
        if (planner == null || !planner.fullscreen || planner.pagePlan.length == 0) {
            return 0;
        }
        int limit = maximumAdvance <= 0 ? Integer.MAX_VALUE : maximumAdvance;
        int start = planner.publicationCursor;
        int advanced = 0;
        while (planner.publicationCursor < planner.pagePlan.length
                && advanced < limit) {
            long packed = planner.pagePlan[planner.publicationCursor];
            int pageX = CaveLoadHierarchy.x(packed);
            int pageZ = CaveLoadHierarchy.z(packed);
            if (fullscreenPublicationPageResolved(planner, pageX, pageZ)) {
                planner.clearFrontierBlock();
                planner.publicationCursor++;
                advanced++;
                continue;
            }

            long now = System.currentTimeMillis();
            if (planner.frontierBlockedPage != packed) {
                planner.frontierBlockedPage = packed;
                planner.frontierBlockedSinceMs = now;
                break;
            }

            long blockedMs = Math.max(0L, now - planner.frontierBlockedSinceMs);
            CaveNativeRegionImportService.PageSourceState source =
                    CaveNativeRegionImportService.getInstance().pageSourceState(
                            planner.dimension, pageX, pageZ);
            CaveRegionProjectionService projectionService =
                    CaveRegionProjectionService.getInstance();

            /* PASS170: the presentation writer PULLS the next retained product by
             * coordinate before it asks the producer to do anything. This removes
             * ACK/re-offer/completion-order from the liveness path. If the product
             * already exists, buffer it directly and let the normal GPU publication
             * stage consume it. */
            boolean retainedBuffered = bufferRetainedFullscreenFrontier(
                    projectionService, now);
            boolean projectionPending = projectionService.hasPendingProjectionWork(
                    planner.view, planner.projectionTopY, pageX, pageZ,
                    planner.dimension);

            boolean repairRequested = retainedBuffered;
            if (!retainedBuffered
                    && blockedMs >= FULLSCREEN_FRONTIER_REPAIR_MS
                    && source.inFlightChunks() == 0 && !projectionPending
                    && now - planner.frontierLastRepairMs
                            >= FULLSCREEN_FRONTIER_REPAIR_MS * 2L) {
                var level = Minecraft.getInstance().level;
                if (level != null) {
                    planner.frontierLastRepairMs = now;
                    projectionService.request(level, planner.dimension, planner.view,
                            planner.projectionTopY, pageX, pageZ,
                            MapRequestLane.FULLSCREEN, planner.publicationCursor,
                            repository.generation());
                    repairRequested = projectionService.hasPendingProjectionWork(
                            planner.view, planner.projectionTopY, pageX, pageZ,
                            planner.dimension)
                            || projectionService.find(planner.view,
                                    planner.projectionTopY, pageX, pageZ,
                                    planner.dimension) != null;
                }
            }

            if (repairRequested) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String repairKey = "CAVE_FULLSCREEN_FRONTIER_REPAIR_REARMED:"
                        + planner.dimension + ':' + planner.view + ':'
                        + planner.projectionTopY + ':' + pageX + ':' + pageZ;
                if (recorder.shouldEmitEvent(repairKey, 250L)) {
                    recorder.event("CAVE_FULLSCREEN_FRONTIER_REPAIR_REARMED",
                            "cursor=" + planner.publicationCursor + '/'
                                    + planner.pagePlan.length
                                    + " page=" + pageX + ',' + pageZ
                                    + " view=" + planner.view
                                    + " top_y=" + planner.projectionTopY
                                    + " blocked_ms=" + blockedMs
                                    + " source_resolved=" + source.resolvedChunks()
                                    + " source_absent=" + source.absentChunks()
                                    + " action=pull_retained_or_rearm_exact_frontier"
                                    + " policy=pull_product_then_bounded_repair"
                                    + " pass=PASS170");
                }
            }
            break;
        }

        if (advanced > 0) {
            /* Advancing the presentation cursor is a render-plan content change.
             * Publish a topology revision so the cached FBO/plan reveals the newly
             * committed row-major prefix immediately. */
            exactTopologyRevision.incrementAndGet();
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent(
                    "CAVE_FULLSCREEN_PRESENTATION_FRONTIER_ADVANCED", 100L)) {
                recorder.event("CAVE_FULLSCREEN_PRESENTATION_FRONTIER_ADVANCED",
                        "from=" + start
                                + " to=" + planner.publicationCursor
                                + " total=" + planner.pagePlan.length
                                + " advanced=" + advanced
                                + " reason=" + reason
                                + " policy=single_writer_row_major_commit"
                                + " pass=PASS170");
            }
        } else if (planner.publicationCursor < planner.pagePlan.length) {
            long packed = planner.pagePlan[planner.publicationCursor];
            int blockedX = CaveLoadHierarchy.x(packed);
            int blockedZ = CaveLoadHierarchy.z(packed);
            CaveNativeRegionImportService.PageSourceState source =
                    CaveNativeRegionImportService.getInstance().pageSourceState(
                            planner.dimension, blockedX, blockedZ);
            int knownColumns = 0;
            boolean resident = false;
            boolean knownEmpty = false;
            synchronized (pages) {
                PageInfo info = pages.get(new PageKey(planner.dimension,
                        planner.view, planner.layerY, blockedX, blockedZ));
                if (info != null) {
                    knownColumns = info.knownColumns;
                    resident = info.initialized && info.atlasSlot >= 0;
                    knownEmpty = info.knownEmpty;
                }
            }
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            String blockerKey = "CAVE_FULLSCREEN_PRESENTATION_FRONTIER_BLOCKED:"
                    + planner.dimension + ':' + planner.view + ':'
                    + planner.projectionTopY + ':' + blockedX + ':' + blockedZ;
            if (recorder.shouldEmitEvent(blockerKey, 500L)) {
                recorder.event("CAVE_FULLSCREEN_PRESENTATION_FRONTIER_BLOCKED",
                        "cursor=" + planner.publicationCursor + '/'
                                + planner.pagePlan.length
                                + " page=" + blockedX + ',' + blockedZ
                                + " view=" + planner.view
                                + " top_y=" + planner.projectionTopY
                                + " resident=" + resident
                                + " known_columns=" + knownColumns
                                + " known_empty=" + knownEmpty
                                + " source_inflight=" + source.inFlightChunks()
                                + " source_resolved=" + source.resolvedChunks()
                                + " source_absent=" + source.absentChunks()
                                + " reason=" + reason
                                + " policy=frontier_waits_for_product_not_timer"
                                + " pass=PASS170");
            }
        }
        return advanced;
    }

    private boolean fullscreenPublicationPageResolved(VisiblePlanner planner,
            int pageX, int pageZ) {
        if (pageX < planner.minPageX || pageX > planner.maxPageX
                || pageZ < planner.minPageZ || pageZ > planner.maxPageZ) return true;
        CaveWorldSaveReader reader = CaveWorldSaveReader.getInstance();
        if (reader.isFullscreenPageKnownAbsent(planner.dimension,
                planner.view, planner.layerY, planner.projectionTopY,
                pageX, pageZ)) return true;
        /* At branch-only zoom the visible product is the retained LOD branch, not an
         * exact atlas page. A branch coordinate closes the publication prefix only
         * after the branch has actually been published, never merely because its CPU
         * derivation finished. This keeps far-zoom reveal ordered without deadlocking
         * the frontier on exact pages that are intentionally not requested. */
        if (planner.branchOnly
                && lodTree.hasPublishedCoverage(planner.dimension, planner.view,
                        planner.layerY, pageX, pageZ)) {
            return true;
        }
        synchronized (pages) {
            PageKey key = new PageKey(planner.dimension, planner.view,
                    planner.layerY, pageX, pageZ);
            PageInfo info = pages.get(key);
            if (info == null) return false;
            if (info.knownEmpty
                    && (planner.view == CaveView.FULL
                            || info.publishedProjectionTopY
                                    == planner.projectionTopY)) return true;
            int fullColumns = CaveTextureAtlas.PAGE_SIZE
                    * CaveTextureAtlas.PAGE_SIZE;
            boolean projectionMatches = planner.view == CaveView.FULL
                    || info.publishedProjectionTopY == planner.projectionTopY;
            if (info.initialized && info.atlasSlot >= 0
                    && info.knownColumns >= fullColumns
                    && projectionMatches) {
                return true;
            }

            /*
             * PASS161 / Xaero writer parity: a generated-page transaction is
             * presentation-settled when every one of its 4x4 source chunks is either
             * resolved or authoritatively absent. PASS156 required 4096 known
             * columns, so a page containing e.g. 15 generated chunks + 1 absent
             * chunk could be uploaded as a coherent 3840-column partial product but
             * could NEVER close the global scanline frontier. One such coordinate
             * stopped all later pages permanently (22-59 and 23-23 logs). Xaero's
             * writer advances past unavailable chunks; it does not head-of-line
             * block the rest of the map. Preserve the strict page order, but let a
             * settled partial page close its coordinate after its known children are
             * resident. Future source arrival may refine that page normally.
             */
            if (info.initialized && info.atlasSlot >= 0
                    && info.knownColumns > 0 && projectionMatches) {
                CaveNativeRegionImportService.PageSourceState source =
                        CaveNativeRegionImportService.getInstance().pageSourceState(
                                planner.dimension, pageX, pageZ);
                int settledChildren = Math.min(16,
                        source.resolvedChunks() + source.absentChunks());
                if (source.inFlightChunks() == 0 && settledChildren >= 16) {
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    String eventKey =
                            "CAVE_FULLSCREEN_PARTIAL_SETTLED_FRONTIER:"
                                    + planner.dimension + ':' + planner.view + ':'
                                    + planner.projectionTopY + ':' + pageX + ':' + pageZ;
                    if (recorder.shouldEmitEvent(eventKey, 500L)) {
                        recorder.event("CAVE_FULLSCREEN_PARTIAL_SETTLED_FRONTIER",
                                "page=" + pageX + ',' + pageZ
                                        + " view=" + planner.view
                                        + " top_y=" + planner.projectionTopY
                                        + " known_columns=" + info.knownColumns
                                        + " resolved_children=" + source.resolvedChunks()
                                        + " absent_children=" + source.absentChunks()
                                        + " action=advance_scanline"
                                        + " policy=settled_partial_is_not_a_hole"
                                        + " pass=PASS161");
                    }
                    return true;
                }
            }

            /* PASS166: an unproven empty stays a repair target. Never advance the
             * visible scanline merely because a timer expired; that policy was the
             * direct source of persistent black holes after repeated layer changes. */
            return false;
        }
    }

    /**
     * Minimap/background completions retain lane ordering. Fullscreen completions may
     * prepare independently, but visible mutation is bounded by the PASS156 scanline
     * publication frontier.
     */
    private boolean isCompletionPublicationEligible(CompletedBuild completed,
            VisiblePlanner planner) {
        if (completed == null) return false;
        if (completed.lane() != MapRequestLane.FULLSCREEN) return true;
        if (completed.info().key.view() == CaveView.LAYERED
                && !fullscreenLayeredPublicationWindowOpen) {
            return false;
        }
        if (planner == null || planner.pagePlan.length == 0
                || !planner.matches(completed.info().key)) {
            // Do not allocate a first atlas slot for an obsolete viewport; an
            // already resident page may still receive a cheap last-good refresh.
            return completed.info().initialized;
        }
        int ordinal = planner.ordinalOf(completed.info().key.globalPageX(),
                completed.info().key.globalPageZ());
        if (ordinal < 0) return false;
        /* PASS170: source/projection may run ahead, but the atlas is the visible
         * product and has exactly one fullscreen writer. GPU publication is therefore
         * permitted only for the current row-major ordinal. */
        return ordinal <= planner.publicationCursor;
    }


    private boolean isBuildAheadEligible(PageRequest request, long now) {
        MapRequestLane lane = buildOwnership(request, now).lane();
        if (lane != MapRequestLane.FULLSCREEN) return true;
        VisiblePlanner planner = visiblePlans.get(MapRequestLane.FULLSCREEN);
        if (planner == null || planner.pagePlan.length == 0) return true;
        if (!planner.matches(request.key)) return false;
        int ordinal = planner.ordinalOf(request.key.globalPageX(),
                request.key.globalPageZ());
        if (ordinal < 0) return false;
        /*
         * PASS168: for an exact fullscreen viewport, source/window admission already
         * bounds work. Do not add a second global prefix gate at CPU projection. Far
         * zoom branch-only traversal remains bounded around the cursor.
         */
        if (!planner.branchOnly) return true;
        return ordinal <= planner.publicationCursor
                + Math.min(FULLSCREEN_BUILD_AHEAD_PAGES, 32);
    }

    /**
     * Resolves who owns a page before CPU admission, not after the future has
     * completed. A satisfied foreground request may have been cleared while the
     * fullscreen planner still owns the coordinate; later source refreshes must
     * enter the scheduler and GPU ledger as foreground immediately.
     */
    private BuildOwnership buildOwnership(PageRequest request, long now) {
        if (request == null) {
            return new BuildOwnership(null, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }
        MapRequestLane lane = request.effectiveLane(now);
        int priority = request.effectivePriority(now);
        int ordinal = lane == MapRequestLane.FULLSCREEN
                ? request.ordinalForLane(lane) : Integer.MAX_VALUE;
        for (MapRequestLane viewportLane : REQUEST_LANES) {
            if (viewportLane != MapRequestLane.MINIMAP
                    && viewportLane != MapRequestLane.FULLSCREEN) continue;
            VisiblePlanner planner = visiblePlans.get(viewportLane);
            if (!plannerActive(planner, now)
                    || planner.projectionTopY != request.projectionTopY
                    || !planner.matches(request.key)) continue;
            int viewportOrdinal = viewportLane == MapRequestLane.FULLSCREEN
                    ? planner.ordinalOf(request.key.globalPageX(),
                            request.key.globalPageZ())
                    : Integer.MAX_VALUE;
            if (viewportLane == MapRequestLane.FULLSCREEN
                    && viewportOrdinal < 0) continue;
            int viewportPriority = viewportLane.priorityBase() + 220_000;
            if (viewportLane == MapRequestLane.FULLSCREEN) {
                viewportPriority -= Math.min(180_000, viewportOrdinal * 250);
            } else {
                viewportPriority -= Math.min(120_000,
                        squaredDistance(request.key.globalPageX(),
                                request.key.globalPageZ(),
                                planner.focusPageX, planner.focusPageZ) * 2_000);
            }
            if (viewportLane.strongerThan(lane)) {
                lane = viewportLane;
                ordinal = viewportOrdinal;
            } else if (viewportLane == lane
                    && viewportLane == MapRequestLane.FULLSCREEN) {
                ordinal = Math.min(ordinal, viewportOrdinal);
            }
            priority = Math.max(priority, viewportPriority);
        }
        return new BuildOwnership(lane, priority, ordinal);
    }



    private void scheduleBuilds(int budget, long deadline, long now) {
        long pendingCounts = pendingBuildCounts();
        int pending = (int) (pendingCounts >>> 32);
        boolean minimapWaiting = hasActiveRequest(MapRequestLane.MINIMAP, now);
        boolean minimapRunning = (int) pendingCounts > 0;
        // Reserve one overflow slot for minimap demand without shrinking the
        // fullscreen nearest-first build-ahead runway.
        int pendingCap = minimapWaiting && !minimapRunning
                ? FULLSCREEN_BUILD_AHEAD_PAGES + 1
                : FULLSCREEN_BUILD_AHEAD_PAGES;
        budget = Math.min(budget, Math.max(0, pendingCap - pending));
        if (budget <= 0) return;
        synchronized (pages) {
            candidateBuffer.clear();
            candidateBuffer.addAll(requests.values());
        }

        int scheduled = 0;
        while (scheduled < budget && System.nanoTime() < deadline) {
            int bestIndex = -1;
            for (int index = 0; index < candidateBuffer.size(); index++) {
                PageRequest candidate = candidateBuffer.get(index);
                if (candidate == null) continue;
                if (candidate.isExpired(now)) continue;
                if (!isBuildAheadEligible(candidate, now)) continue;
                if (bestIndex < 0 || higherPriority(
                        candidate, candidateBuffer.get(bestIndex), now)) bestIndex = index;
            }
            if (bestIndex < 0) break;

            PageRequest request = candidateBuffer.get(bestIndex);
            candidateBuffer.set(bestIndex, null);
            PageKey key = request.key;
            BuildOwnership ownership = buildOwnership(request, now);
            MapRequestLane originalLane = request.effectiveLane(now);
            MapRequestLane requestLane = ownership.lane();
            if (requestLane == null || !key.dimension().equals(dimension())) continue;
            int requestOrdinal = ownership.fullscreenOrdinal();
            int requestPriority = ownership.priority();
            if (requestLane.strongerThan(originalLane)) {
                request.observe(requestLane, requestPriority, now, requestOrdinal);
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent(
                        "CAVE_BUILD_LANE_PROMOTED:" + key, 250L)) {
                    recorder.event("CAVE_BUILD_LANE_PROMOTED",
                            "page=" + key + " from=" + originalLane
                                    + " to=" + requestLane
                                    + " ordinal=" + requestOrdinal);
                }
            }

            int projectionTopY = request.projectionTopY;
            long sourceRevision = repository.getPageRevision(
                    key.view(), projectionTopY,
                    key.globalPageX(), key.globalPageZ());
            boolean regionAuthorityOwns = CaveRegionProjectionService.getInstance()
                    .owns(key.view(), projectionTopY, key.globalPageX(),
                            key.globalPageZ(), key.dimension())
                    || CaveNativeRegionImportService.getInstance().ownsPage(
                            key.dimension(), key.view(), projectionTopY,
                            key.globalPageX(), key.globalPageZ());
            PageInfo info;
            long revision;
            synchronized (pages) {
                info = pages.computeIfAbsent(key, PageInfo::new);
                revision = revisions.getOrDefault(key, 1L);
                if (info.pending != null) continue;
                info.beginProjectionTransition(projectionTopY);
                info.observeSourceRevision(sourceRevision, now);
                if (now < info.nextRetryMs) continue;
                /*
                 * Exact presentation is allowed to progress from a stable partial
                 * source. PASS100 made isPageProjectionReady() (all 16 central
                 * chunks) a hard admission gate, so one late/unexplored child kept
                 * the entire 64x64 page black. Xaero writes MinimapChunk tiles
                 * independently and keeps the loaded parent underneath. We retain
                 * the same rule here: no source means wait, partial stable source
                 * may refine existing branch/root coverage.
                 */
                boolean projectionHasSource = sourceRevision != 0L;
                boolean residentProjectionSource =
                        repository.hasAnyProjectionSourcePage(
                                key.view(), projectionTopY,
                                key.globalPageX(), key.globalPageZ());
                if (!residentProjectionSource) {
                    // PASS151: cache discovery must not depend on the old global
                    // 66k-tile startup index. Probe the addressed exact cache and
                    // SMR2 native region before declaring a page source-less or
                    // allowing the world-save importer to rebuild it from Anvil.
                    repository.requestDisplayPageLoad(key.view(), projectionTopY,
                            key.globalPageX(), key.globalPageZ(), requestLane);
                    boolean archiveLoad = repository.requestPersistedArchivePageLoad(
                            key.view(), projectionTopY, key.globalPageX(),
                            key.globalPageZ(), requestLane);
                    boolean displayLoad = repository.hasPendingDisplayPageLoad(
                            key.view(), projectionTopY,
                            key.globalPageX(), key.globalPageZ());
                    if (archiveLoad || displayLoad) {
                        info.nextRetryMs = now + 24L;
                        ExactPageStateTracker.getInstance().transition(
                                stateKey(key), ExactPageState.REQUESTED,
                                requestLane, revision);
                        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                        if (recorder.shouldEmitEvent(
                                "CAVE_INDEXED_SOURCE_WAITING_RESIDENCY:" + key, 500L)) {
                            recorder.event("CAVE_INDEXED_SOURCE_WAITING_RESIDENCY",
                                    "page=" + key + " lane=" + requestLane
                                            + " source_revision=" + sourceRevision
                                            + " archive_load=" + archiveLoad
                                            + " display_load=" + displayLoad
                                            + " policy=lazy_region_before_no_source"
                                            + " pass=PASS152");
                        }
                        continue;
                    }
                }
                if (projectionHasSource && !residentProjectionSource) {
                    info.nextRetryMs = now + PARTIAL_NO_SOURCE_RETRY_MS;
                    ExactPageStateTracker.getInstance().transition(
                            stateKey(key), ExactPageState.REQUESTED,
                            requestLane, revision);
                    continue;
                }
                boolean changingProjection = key.view() == CaveView.LAYERED
                        && info.publishedProjectionTopY != projectionTopY;
                if (!projectionHasSource && (!info.initialized || changingProjection)) {
                    info.nextRetryMs = now + 12L;
                    ExactPageStateTracker.getInstance().transition(
                            stateKey(key), ExactPageState.REQUESTED,
                            requestLane, revision);
                    continue;
                }
                // No-source is a repository state, not a timer. Rebuilding an
                // unchanged empty snapshot only burns CPU and keeps the request
                // lane busy; a repository revision advance re-enables it.
                if (!info.initialized && info.knownColumns == 0
                        && info.noSourceRevision == sourceRevision
                        && repository.hasCompleteProjectionSourcePage(
                                key.view(), projectionTopY,
                                key.globalPageX(), key.globalPageZ())) continue;
                // A usable exact/CPU fallback already exists. Coalesce the burst of
                // per-leaf source revisions into one coherent refresh instead of
                // resolving and styling the same 64x64 page after every chunk commit.
                if (projectionHasSource
                        && info.uploadedSourceRevision != sourceRevision
                        && !info.isSourceSettled(now)) {
                    info.nextRetryMs = Math.max(info.nextRetryMs,
                            info.sourceSettleDeadlineMs());
                    continue;
                }
                if (info.uploadedRevision == revision
                        && info.uploadedSourceRevision == sourceRevision) {
                    if (info.knownEmpty) {
                        PageRequest satisfied = requests.get(key);
                        if (satisfied != null) {
                            satisfied.clearAll();
                            if (satisfied.isExpired(now)) {
                                removeRequestOwnershipLocked(key, satisfied);
                            }
                        }
                        continue;
                    }
                    if (info.initialized && info.atlasSlot >= 0) {
                        if (info.isProjectionAuthoritative(projectionTopY)) {
                            PageRequest satisfied = requests.get(key);
                            if (satisfied != null) {
                                satisfied.clearAll();
                                if (satisfied.isExpired(now)) {
                                    removeRequestOwnershipLocked(key, satisfied);
                                }
                            }
                        } else {
                            // The current source revision has been staged or only
                            // some 16x16 replacements are visible. Keep the old-band
                            // fallback, but continue until every tile represents the
                            // requested exact Top-Y.
                            info.nextRetryMs = now + INCOMPLETE_RETRY_MS;
                        }
                        continue;
                    }
                    if (info.frontLods != null && info.knownColumns > 0
                            && info.canRenderProjection(projectionTopY)) {
                        if (restoreCavePageResidency(info, requestLane)) {
                            if (info.isProjectionAuthoritative(projectionTopY)) {
                                PageRequest satisfied = requests.get(key);
                                if (satisfied != null) {
                                    satisfied.clearAll();
                                    if (satisfied.isExpired(now)) {
                                        removeRequestOwnershipLocked(key, satisfied);
                                    }
                                }
                            } else {
                                info.nextRetryMs = now + INCOMPLETE_RETRY_MS;
                            }
                        } else {
                            info.nextRetryMs = now + 16L;
                        }
                        continue;
                    }
                }

                if (regionAuthorityOwns && projectionHasSource) {
                    /*
                     * Single-writer fence. The native region transaction is able to
                     * publish stable partial child waves as well as complete pages,
                     * so launching the generic 64x64 resolver beside it duplicates
                     * projection/style work and commonly finishes against an older
                     * source fingerprint (CAVE_RESULT_STALE). Xaero has one active
                     * writer for a MapTile working set; retained products are reused
                     * rather than recomputed by a second concurrent writer. Direct
                     * exact remains the fallback when there is no active region lease.
                     */
                    info.nextRetryMs = now + 12L;
                    ExactPageStateTracker.getInstance().transition(
                            stateKey(key), ExactPageState.REQUESTED,
                            requestLane, revision);
                    continue;
                }

                var level = Minecraft.getInstance().level;
                ExactPageStateTracker.getInstance().transition(
                        stateKey(key), ExactPageState.CPU_READY,
                        requestLane, revision);
                long repositoryGeneration = repository.generation();
                long scheduledSourceRevision = sourceRevision;
                /*
                 * The worker cancellation token guards source/dimension generation,
                 * not the short PageRequest lease. A visible Xaero-style writer
                 * window can legitimately outlive the 2 s minimap request object
                 * while queues are pressured. Viewport/mode retirement explicitly
                 * detaches and cancels this future; publication performs the final
                 * request-or-planner ownership check on the render thread. Keeping
                 * VisiblePlanner out of the worker predicate also avoids racing its
                 * render-thread-only mutable bounds.
                 */
                MapCancellationToken token = new MapCancellationToken(() ->
                        key.dimension().equals(dimension())
                                && repository.isGenerationCurrent(repositoryGeneration));
                long queuedNanos = System.nanoTime();
                CompletableFuture<BuildResult> future = MapWorkScheduler.tryCpuFuture(
                        requestLane, MapWorkScheduler.WorkType.EXACT_BUILD,
                        requestPriority, 8, token, () -> {
                    long buildStart = System.nanoTime();
                    pipelineTelemetry.recordStageNanos(MapPipelineStage.EXACT_QUEUE,
                            Math.max(0L, buildStart - queuedNanos));
                    try {
                        /*
                         * One worker owns one immutable source revision. PASS101
                         * retried the whole resolve/style transaction once inside
                         * the same task when live chunks changed underneath it. In a
                         * streaming viewport that means allocating two ResolvedPage
                         * payloads/style buffers just to discard the first generation.
                         * Xaero lets its retained writer advance and schedules the
                         * newest tile on the next slice instead. Do the same here:
                         * any source-revision change terminates this attempt and lets
                         * the outer quiet-window/request scheduler coalesce the next
                         * generation.
                         */
                        long latestSource = scheduledSourceRevision;
                        token.checkpoint("cave-page-resolve-start");
                        long sourceBeforeResolve = repository.getPageRevision(
                                key.view(), projectionTopY,
                                key.globalPageX(), key.globalPageZ());
                        latestSource = sourceBeforeResolve;
                        if (sourceBeforeResolve == 0L) {
                            return BuildResult.superseded(revision, latestSource,
                                    projectionTopY);
                        }
                        telemetry.recordPageBuild();
                        CaveTileRepository.ResolvedPage resolved =
                                repository.resolvePage(
                                        key.view(), projectionTopY, level,
                                        key.globalPageX(), key.globalPageZ());
                        token.checkpoint("cave-page-resolve-finished");
                        long sourceAfterResolve = repository.getPageRevision(
                                key.view(), projectionTopY,
                                key.globalPageX(), key.globalPageZ());
                        latestSource = sourceAfterResolve;
                        if (resolved == null
                                || sourceBeforeResolve != sourceAfterResolve
                                || resolved.revision() != sourceAfterResolve) {
                            return BuildResult.superseded(revision, latestSource,
                                    projectionTopY);
                        }
                        int[] styled = CavePageStyler.style(
                                resolved.pixels(), resolved.heights(),
                                resolved.topHeights(), resolved.flags(),
                                resolved.light(), resolved.overlayCounts(),
                                resolved.overlayColors(), resolved.overlayAlpha(),
                                resolved.overlayY(), resolved.overlayLight(),
                                resolved.overlayFlags(), key.view(), projectionTopY);
                        token.checkpoint("cave-page-style-finished");
                        long sourceAfterStyle = repository.getPageRevision(
                                key.view(), projectionTopY,
                                key.globalPageX(), key.globalPageZ());
                        latestSource = sourceAfterStyle;
                        if (sourceAfterStyle != sourceAfterResolve) {
                            return BuildResult.superseded(revision, latestSource,
                                    projectionTopY);
                        }
                        return new BuildResult(revision, sourceAfterStyle,
                                projectionTopY, styled, resolved.knownRows(),
                                resolved.knownColumnCount(), resolved.complete(),
                                resolved.emptyProof(), false, false);
                    } finally {
                        pipelineTelemetry.recordStageNanos(MapPipelineStage.EXACT_BUILD,
                                System.nanoTime() - buildStart);
                    }
                });
                if (future == null) {
                    // Admission failure is not an in-flight build. The previous
                    // CompletableFuture/Executor adapter could place the command in
                    // an invisible delayed retry queue while PageInfo retained one
                    // of the six exact-build slots indefinitely. A cold viewport
                    // then had demand but could not schedule any current pages.
                    info.nextRetryMs = now + 16L;
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    if (recorder.shouldEmitEvent("CAVE_BUILD_ADMISSION_DEFERRED", 500L)) {
                        recorder.event("CAVE_BUILD_ADMISSION_DEFERRED",
                                "page=" + key + " lane=" + requestLane
                                        + " pending=" + pendingBuildCount()
                                        + " requests=" + requestCount());
                    }
                    continue;
                }
                pipelineTelemetry.recordExactBuildQueued();
                ExactPageStateTracker.getInstance().transition(
                        stateKey(key), ExactPageState.BUILDING,
                        requestLane, revision);
                info.pending = future;
                info.pendingToken = token;
                info.pendingLane = requestLane;
                info.pendingProjectionTopY = projectionTopY;
                info.pendingCompletionRecorded = false;
                future.whenComplete((result, throwable) -> {
                    synchronized (pages) {
                        // Stronger-lane retargeting or page retirement may have
                        // already detached this future. Do not enqueue a terminal
                        // completion that can only be discarded later. The detach
                        // path normally terminalizes the tracker; cover the narrow
                        // race where completion observes a cleared owner first.
                        if (info.pending != future) {
                            if (info.pending == null) {
                                PageRequest live = requests.get(info.key);
                                long completedAt = System.currentTimeMillis();
                                boolean requested = live != null
                                        && !live.isExpired(completedAt)
                                        && live.projectionTopY == projectionTopY;
                                boolean viewportOwned = isProjectionViewportOwned(
                                        info.key, projectionTopY, completedAt);
                                ExactPageStateTracker.getInstance().transition(
                                        stateKey(info.key), requested || viewportOwned
                                                ? ExactPageState.REQUESTED
                                                : ExactPageState.STALE_GENERATION,
                                        requested ? live.effectiveLane(completedAt)
                                                : requestLane,
                                        revisions.getOrDefault(info.key, revision));
                            }
                            return;
                        }
                    }
                    completedBuilds.offer(new CompletedBuild(
                            info, future, requestLane, requestOrdinal,
                            completedSequence.getAndIncrement(), null));
                });
            }
            scheduled++;
        }
    }



    private boolean higherPriority(PageRequest candidate,
            PageRequest current, long now) {
        BuildOwnership candidateOwnership = buildOwnership(candidate, now);
        BuildOwnership currentOwnership = buildOwnership(current, now);
        int candidatePriority = candidateOwnership.priority();
        int currentPriority = currentOwnership.priority();
        if (candidatePriority != currentPriority) {
            return candidatePriority > currentPriority;
        }
        MapRequestLane candidateLane = candidateOwnership.lane();
        MapRequestLane currentLane = currentOwnership.lane();
        if (candidateLane != currentLane) {
            return candidateLane != null
                    && (currentLane == null || candidateLane.strongerThan(currentLane));
        }
        if (candidateLane == MapRequestLane.FULLSCREEN) {
            int candidateOrdinal = candidateOwnership.fullscreenOrdinal();
            int currentOrdinal = currentOwnership.fullscreenOrdinal();
            if (candidateOrdinal != currentOrdinal) {
                return candidateOrdinal < currentOrdinal;
            }
        }
        return candidate.latestSeenMs() > current.latestSeenMs();
    }

    /**
     * Only minimap demand may use the bounded first-page timeout. Cold fullscreen
     * pages remain atomic at their own fixed coordinates, preventing isolated Anvil
     * callbacks from appearing as 16x16 dots.
     */
    private boolean isLeadingPublicationPage(PageKey key, MapRequestLane lane) {
        if (key == null || lane == null) return false;
        VisiblePlanner planner = visiblePlans.get(lane);
        if (planner == null || planner.pagePlan.length == 0 || !planner.matches(key)) {
            return false;
        }
        // Only minimap uses a deadline-based first-page exception. Cold fullscreen
        // pages stay atomic, but no page is designated as a viewport-wide gate.
        return lane == MapRequestLane.MINIMAP
                && key.globalPageX() == planner.focusPageX
                && key.globalPageZ() == planner.focusPageZ;
    }

    private ApplyOutcome apply(PageInfo info, int projectionTopY, int[] pixels,
            long[] incomingKnownRows, boolean complete, CaveEmptyProof emptyProof,
            long sourceRevision,
            long now) {
        int pageSize = CaveTextureAtlas.PAGE_SIZE;
        int pixelCount = pageSize * pageSize;
        boolean wasCompleteBeforeApply = info.knownColumns >= pixelCount;
        if (pixels == null || pixels.length < pixelCount
                || incomingKnownRows == null || incomingKnownRows.length < pageSize) {
            return ApplyOutcome.NOT_APPLIED;
        }

        info.beginProjectionTransition(projectionTopY);
        int[] mergedBase = uploadScratchLods[0];
        // A far-zoom page may wait several frames for an atlas/GPU reservation.
        // Do not allocate its four retained mip arrays until publication can
        // actually proceed. Existing resident pages still merge from last-good
        // pixels; first publications begin from a clean reusable scratch page.
        if (info.frontLods != null) {
            System.arraycopy(info.frontLods[0], 0, mergedBase, 0, pixelCount);
        } else {
            java.util.Arrays.fill(mergedBase, 0);
        }

        int addedKnownColumns = info.stageProjection(
                projectionTopY, pixels, incomingKnownRows, complete);
        boolean replacingDifferentProjection =
                info.hasVisibleProjectionDifferentFrom(projectionTopY);
        if (replacingDifferentProjection
                && !info.stagingProjectionComplete(projectionTopY)) {
            // Never expose a checkerboard of two vertical projections. Keep the
            // last coherent page as the authority until its replacement is complete.
            return ApplyOutcome.NOT_APPLIED;
        }
        int readyTileMask = replacingDifferentProjection
                ? (1 << PROJECTION_TILE_COUNT) - 1
                : info.readyStagedTileMask(projectionTopY, mergedBase);
        if (!info.initialized && !replacingDifferentProjection) {
            readyTileMask = CaveTilePublicationPolicy.largestConnectedMask(
                    readyTileMask);
        }
        if (readyTileMask == 0) {
            info.stagedReadySinceMs = 0L;
            return ApplyOutcome.NOT_APPLIED;
        }
        if (info.stagedReadySinceMs == 0L) info.stagedReadySinceMs = now;
        info.copyStagedTiles(projectionTopY, readyTileMask, mergedBase);
        boolean authoritative = info.wouldBeProjectionAuthoritative(
                projectionTopY, readyTileMask);
        CaveEmptyProof effectiveEmptyProof = emptyProof == null
                ? CaveEmptyProof.NONE : emptyProof;
        boolean hasContent = false;
        for (int index = 0; index < pixelCount; index++) {
            if (mergedBase[index] != 0) {
                hasContent = true;
                break;
            }
        }
        if (!hasContent && !(authoritative && effectiveEmptyProof.strong())) {
            if (info.unprovenEmptySinceMs == 0L) info.unprovenEmptySinceMs = now;
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent(
                    "CAVE_EMPTY_WITHOUT_PROOF:" + info.key, 500L)) {
                recorder.event("CAVE_EMPTY_WITHOUT_PROOF",
                        "page=" + info.key + " top_y=" + projectionTopY
                                + " authoritative=" + authoritative
                                + " proof=" + effectiveEmptyProof
                                + " action=retain_last_good");
            }
            return ApplyOutcome.NOT_APPLIED;
        }
        info.unprovenEmptySinceMs = 0L;

        // Root/branch authority is a CPU cache and must not wait for exact tile
        // coalescing, an atlas slot or a frame GPU reservation. Only atomically ready
        // 16x16 tiles are offered, so Layered Top-Y transitions remain coherent.
        long[] candidateKnownRows = branchKnownRowsScratch;
        int candidateKnownColumns = info.candidateProjectionKnownRows(
                projectionTopY, readyTileMask, candidateKnownRows);
        // Xaero derives branch textures only from a complete, version-matched
        // child texture. Partial Full pages created the visible coarse/murky first
        // pass and were then replaced by exact pages a second time.
        boolean branchSemanticallyReady = authoritative
                && (!info.regionImageFallback || complete);
        if (branchSemanticallyReady && candidateKnownColumns > 0
                && info.markBranchCandidate(sourceRevision, projectionTopY,
                        readyTileMask, candidateKnownColumns, authoritative)) {
            updateBranchCandidate(info, mergedBase, candidateKnownRows,
                    candidateKnownColumns, authoritative, sourceRevision);
        }

        int readyTileCount = Integer.bitCount(readyTileMask);
        boolean leadingPage = isLeadingPublicationPage(info.key, info.pendingLane);
        if (!CaveTilePublicationPolicy.shouldPublish(info.pendingLane,
                info.initialized, replacingDifferentProjection, leadingPage,
                readyTileCount, info.stagedReadySinceMs, now)) {
            return ApplyOutcome.COALESCED;
        }

        if (hasContent) {
            boolean hadAtlasSlot = info.atlasSlot >= 0;
            if (!ensureAtlasSlot(info)) return ApplyOutcome.ATLAS_DEFERRED;
            MapRequestLane uploadLane = info.pendingLane == null
                    ? MapRequestLane.BACKGROUND : info.pendingLane;
            if (!MapGpuBudgetController.getInstance().tryReserve(
                    MapGpuBudgetController.UploadKind.CAVE_EXACT,
                    uploadLane, uploadLane == MapRequestLane.MINIMAP
                            || uploadLane == MapRequestLane.FULLSCREEN)) {
                if (!hadAtlasSlot && !info.initialized && info.atlasSlot >= 0) {
                    atlas.releaseSlot(info.atlasSlot);
                    info.atlasSlot = -1;
                }
                return ApplyOutcome.GPU_DEFERRED;
            }
        }

        // Visible authority changes only after a content page owns both atlas
        // residency and render-frame upload budget. A denial leaves the staged
        // projection immutable and ready to retry without tile swaps or LOD work.
        info.commitStagedTiles(projectionTopY, readyTileMask, mergedBase);
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        String event = replacingDifferentProjection
                ? "CAVE_LAYER_PAGE_SWAP"
                : info.key.view() == CaveView.FULL
                        ? "CAVE_FULL_TILE_SWAP" : "CAVE_LAYER_TILE_SWAP";
        if (recorder.shouldEmitEvent(event + ':' + info.key, 250L)) {
            recorder.event(event,
                    "page=" + info.key + " top_y=" + projectionTopY
                            + " tiles=" + Integer.bitCount(readyTileMask)
                            + " tile_mask=" + Integer.toHexString(readyTileMask));
        }

        for (int lod = 1; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            downsample(uploadScratchLods[lod - 1], CaveTextureAtlas.lodSize(lod - 1),
                    uploadScratchLods[lod], CaveTextureAtlas.lodSize(lod));
        }
        telemetry.recordLodBuild();

        // Reservation succeeded; retained CPU mips now have a consumer and are
        // worth materializing. Deferred pages avoid this allocation entirely.
        info.ensureBuffers();
        if (!hasContent) {
            info.emptyProof = effectiveEmptyProof;
            info.knownEmpty = authoritative && info.emptyProof.strong();
            for (int lod = 0; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
                System.arraycopy(uploadScratchLods[lod], 0, info.frontLods[lod], 0,
                        uploadScratchLods[lod].length);
            }
            if (authoritative) info.publishedProjectionTopY = projectionTopY;
            info.stagedReadySinceMs = 0L;
            info.releaseAtlasSlot();
            ExactPageStateTracker.getInstance().transition(
                    stateKey(info.key), authoritative
                            ? ExactPageState.KNOWN_EMPTY
                            : ExactPageState.CPU_PARTIAL,
                    info.pendingLane, revisions.getOrDefault(info.key, 1L));
            if (authoritative && info.knownEmpty) {
                MapDebugRecorder emptyRecorder = MapDebugRecorder.getInstance();
                String emptyEventKey = "CAVE_STRONG_EMPTY_APPLIED:" + info.key;
                if (emptyRecorder.shouldEmitEvent(emptyEventKey, 500L)) {
                    emptyRecorder.event("CAVE_STRONG_EMPTY_APPLIED",
                            "page=" + info.key + " top_y=" + projectionTopY
                                    + " proof=" + info.emptyProof
                                    + " source_revision=" + sourceRevision
                                    + " cave_cache_epoch=" + CaveCacheSchema.EPOCH
                                    + " pass=PASS154");
                }
            }
            return ApplyOutcome.APPLIED_EMPTY;
        }
        info.emptyProof = CaveEmptyProof.NONE;
        info.knownEmpty = false;
        ExactPageStateTracker.getInstance().transition(
                stateKey(info.key), ExactPageState.UPLOAD_QUEUED,
                info.pendingLane, revisions.getOrDefault(info.key, 1L));
        boolean uploaded = false;
        long exactUploadStart = System.nanoTime();
        for (int lod = 0; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            int size = CaveTextureAtlas.lodSize(lod);
            info.dirtyPlan.compute(info.initialized ? info.frontLods[lod] : null,
                    uploadScratchLods[lod], size);
            for (int rect = 0; rect < info.dirtyPlan.count(); rect++) {
                CaveTextureAtlas.DirtyRect dirty = info.dirtyPlan.rect(rect);
                atlas.upload(info.atlasSlot, lod, uploadScratchLods[lod], dirty);
                telemetry.recordDirtyRectangleUpload();
                uploaded = true;
            }
        }

        long exactUploadNanos = System.nanoTime() - exactUploadStart;
        pipelineTelemetry.recordStageNanos(MapPipelineStage.EXACT_UPLOAD,
                exactUploadNanos);
        MapGpuBudgetController.getInstance().record(
                MapGpuBudgetController.UploadKind.CAVE_EXACT,
                exactUploadNanos);
        for (int lod = 0; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            System.arraycopy(uploadScratchLods[lod], 0, info.frontLods[lod], 0,
                    uploadScratchLods[lod].length);
        }
        if (authoritative) info.publishedProjectionTopY = projectionTopY;
        info.stagedReadySinceMs = 0L;
        boolean firstGpuPublication = !info.initialized;
        boolean completenessChanged = wasCompleteBeforeApply
                != (info.knownColumns >= pixelCount);
        if (uploaded) {
            telemetry.recordPageUpload();
            MapResidencyManager.getInstance().markPixelsChanged(
                    MapResidencyManager.Kind.CAVE_EXACT);
        }
        info.initialized = true;
        publishPageTable(info, true);
        // Atlas pixel changes are visible without rebuilding world geometry. Only
        // residency or coverage transitions alter which quads the hierarchy emits.
        if (firstGpuPublication || completenessChanged) {
            exactTopologyRevision.incrementAndGet();
        }
        if (firstGpuPublication && info.firstGpuPublicationSequence == 0L) {
            info.firstGpuPublicationSequence = ++gpuPublicationSequence;
        }
        String residentKey = residencyKey(info.key);
        MapResidencyManager.getInstance().register(
                residentKey, MapResidencyManager.Kind.CAVE_EXACT,
                4L * (64L * 64L + 32L * 32L + 16L * 16L + 8L * 8L),
                () -> evictExactPageForBudget(info.key));
        MapResidencyManager.getInstance().enforceBudget(
                residentKey, info.pendingLane);
        if (firstGpuPublication) indexResidentPage(info.key, 1);
        ExactPageStateTracker.getInstance().transition(
                stateKey(info.key), ExactPageState.GPU_READY,
                info.pendingLane, revisions.getOrDefault(info.key, 1L));
        if (firstGpuPublication) pipelineTelemetry.recordExactGpuReady();
        return ApplyOutcome.APPLIED_CONTENT;
    }

    private static void copyKnownPixels(int[] source, long[] knownRows,
            int[] destination, int pageSize) {
        for (int y = 0; y < pageSize; y++) {
            long mask = knownRows[y];
            while (mask != 0L) {
                int x = Long.numberOfTrailingZeros(mask);
                destination[y * pageSize + x] = source[y * pageSize + x];
                mask &= mask - 1L;
            }
        }
    }

    private static long caveGpuRetryDelayMs(MapRequestLane lane, int failures) {
        MapRequestLane effective = normalizedGpuLane(lane);
        boolean pressure = MapPerformanceGovernor.getInstance().underPressure();
        long base = switch (effective) {
            case MINIMAP -> pressure ? 8L : 4L;
            case FULLSCREEN -> pressure ? 16L : 8L;
            default -> pressure ? 40L : 24L;
        };
        long cap = switch (effective) {
            case MINIMAP -> 96L;
            case FULLSCREEN -> 160L;
            default -> 500L;
        };
        int shift = Math.min(4, Math.max(0, failures - 1));
        return Math.min(cap, base << shift);
    }

    private static MapRequestLane normalizedGpuLane(MapRequestLane lane) {
        return lane == null ? MapRequestLane.BACKGROUND : lane;
    }

    private boolean caveGpuLaneEligible(MapRequestLane lane, long nowMs) {
        return true;
    }

    private void deferCaveGpuRetry(PageInfo info, MapRequestLane lane, long nowMs) {
        info.gpuReservationFailures = Math.min(8, info.gpuReservationFailures + 1);
        info.nextPublicationAttemptMs = nowMs
                + caveGpuRetryDelayMs(lane, info.gpuReservationFailures);
    }

    private void resetCaveGpuRetry(PageInfo info, MapRequestLane lane) {
        if (info != null) {
            info.gpuReservationFailures = 0;
            info.nextPublicationAttemptMs = 0L;
        }
    }

    private boolean restoreCavePageResidency(PageInfo info,
            MapRequestLane lane) {
        if (info == null || info.frontLods == null || info.knownColumns <= 0
                || info.knownEmpty) return false;
        long now = System.currentTimeMillis();
        MapRequestLane uploadLane = normalizedGpuLane(lane);
        if (info.nextPublicationAttemptMs > now
                || !caveGpuLaneEligible(uploadLane, now)) return false;
        boolean hadAtlasSlot = info.atlasSlot >= 0;
        if (!ensureAtlasSlot(info)) return false;
        if (!MapGpuBudgetController.getInstance().tryReserve(
                MapGpuBudgetController.UploadKind.CAVE_EXACT,
                uploadLane, uploadLane == MapRequestLane.MINIMAP
                        || uploadLane == MapRequestLane.FULLSCREEN)) {
            if (!hadAtlasSlot && !info.initialized && info.atlasSlot >= 0) {
                atlas.releaseSlot(info.atlasSlot);
                info.atlasSlot = -1;
            }
            deferCaveGpuRetry(info, uploadLane, now);
            return false;
        }
        resetCaveGpuRetry(info, uploadLane);
        long uploadStarted = System.nanoTime();
        for (int lod = 0; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            int size = CaveTextureAtlas.lodSize(lod);
            atlas.upload(info.atlasSlot, lod, info.frontLods[lod],
                    new CaveTextureAtlas.DirtyRect(0, 0, size - 1, size - 1));
        }
        long uploadNanos = System.nanoTime() - uploadStarted;
        pipelineTelemetry.recordStageNanos(MapPipelineStage.EXACT_UPLOAD, uploadNanos);
        MapGpuBudgetController.getInstance().record(
                MapGpuBudgetController.UploadKind.CAVE_EXACT, uploadNanos);
        info.initialized = true;
        publishPageTable(info, true);
        if (info.firstGpuPublicationSequence == 0L) {
            info.firstGpuPublicationSequence = ++gpuPublicationSequence;
        }
        info.nextRetryMs = 0L;
        indexResidentPage(info.key, 1);
        String residentKey = residencyKey(info.key);
        MapResidencyManager.getInstance().register(
                residentKey, MapResidencyManager.Kind.CAVE_EXACT,
                4L * (64L * 64L + 32L * 32L + 16L * 16L + 8L * 8L),
                () -> evictExactPageForBudget(info.key));
        MapResidencyManager.getInstance().enforceBudget(residentKey, lane);
        exactTopologyRevision.incrementAndGet();
        telemetry.recordPageUpload();
        MapResidencyManager.getInstance().markPixelsChanged(
                MapResidencyManager.Kind.CAVE_EXACT);
        ExactPageStateTracker.getInstance().transition(
                stateKey(info.key), ExactPageState.GPU_READY,
                lane, info.uploadedRevision);
        return true;
    }

    private void recordVisualPublicationFenceRestore(PageInfo info,
            MapRequestLane lane, String source) {
        if (info == null) return;
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        String eventKey = "CAVE_VISUAL_PUBLICATION_FENCE_RESTORED:"
                + info.key.dimension() + ':' + info.key.view() + ':'
                + info.key.layerY() + ':' + info.key.globalPageX() + ':'
                + info.key.globalPageZ();
        if (!recorder.shouldEmitEvent(eventKey, 250L)) return;
        recorder.event("CAVE_VISUAL_PUBLICATION_FENCE_RESTORED",
                "page=" + info.key.globalPageX() + ',' + info.key.globalPageZ()
                        + " view=" + info.key.view()
                        + " layer=" + info.key.layerY()
                        + " lane=" + lane
                        + " source=" + source
                        + " source_revision=" + info.uploadedSourceRevision
                        + " policy=cpu_projection_is_not_gpu_residency"
                        + " pass=PASS153");
    }

    private void publishPageTable(PageInfo info, boolean force) {
        if (info == null || !info.initialized || info.atlasSlot < 0) return;
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp == null) return;
        long storageGeneration = atlas.storageGeneration();
        long revision = Math.max(1L, revisions.getOrDefault(
                info.key, info.uploadedRevision));
        if (!force && info.pageTableSessionId == stamp.sessionId()
                && info.pageTableStorageGeneration == storageGeneration
                && info.pageTableSlot == info.atlasSlot
                && info.pageTableRevision == revision) return;

        MapGpuPageTableService pageTable = MapGpuPageTableService.getInstance();
        for (int lod = 0; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            CaveAtlasRegion region = atlas.regionForLod(info.atlasSlot, lod);
            if (region == null) continue;
            TileKey tileKey = caveTileKey(stamp.sessionId(), info.key.view(),
                    info.key.layerY(), info.key.globalPageX(),
                    info.key.globalPageZ(), lod);
            pageTable.stage(tileKey, region.texture(), info.atlasSlot,
                    storageGeneration, revision, 0,
                    region.sourceX(), region.sourceY(),
                    region.sourceSize(), region.atlasSize());
        }
        info.pageTableSessionId = stamp.sessionId();
        info.pageTableStorageGeneration = storageGeneration;
        info.pageTableSlot = info.atlasSlot;
        info.pageTableRevision = revision;
    }

    private void removePageTable(PageInfo info) {
        if (info == null || info.pageTableSessionId <= 0L) return;
        MapGpuPageTableService pageTable = MapGpuPageTableService.getInstance();
        for (int lod = 0; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            pageTable.remove(caveTileKey(info.pageTableSessionId,
                    info.key.view(), info.key.layerY(),
                    info.key.globalPageX(), info.key.globalPageZ(), lod));
        }
        info.pageTableSessionId = 0L;
        info.pageTableStorageGeneration = Long.MIN_VALUE;
        info.pageTableSlot = -1;
        info.pageTableRevision = Long.MIN_VALUE;
    }

    /**
     * Branch derivation priority is current viewport ownership, never the lane that
     * happened to create the last exact-page build. PASS100 still defaulted a page
     * with no pending build to FULLSCREEN, which continuously fed historical/offscreen
     * leaves into CaveLodTree's foreground queue. Xaero retains region data but derives
     * processing priority from the currently loaded/viewed window.
     *
     * Caller holds {@code pages}.
     */
    private MapRequestLane currentBranchLaneLocked(PageInfo page, long now) {
        if (page == null) return MapRequestLane.BACKGROUND;
        MapRequestLane effective = MapRequestLane.BACKGROUND;
        PageRequest request = requests.get(page.key);
        if (request != null && !request.isExpired(now)) {
            MapRequestLane requested = request.effectiveLane(now);
            if (requested != null && requested.strongerThan(effective)) {
                effective = requested;
            }
        }
        for (MapRequestLane viewportLane : REQUEST_LANES) {
            if (viewportLane != MapRequestLane.MINIMAP
                    && viewportLane != MapRequestLane.FULLSCREEN) continue;
            VisiblePlanner planner = visiblePlans.get(viewportLane);
            if (planner == null || now - planner.lastDemandMs
                    > ACTIVE_PLANNER_GRACE_MS || !planner.matches(page.key)) continue;
            if (viewportLane.strongerThan(effective)) effective = viewportLane;
        }
        return effective;
    }

    private static TileKey caveTileKey(long sessionId, CaveView view,
            int normalizedLayer, int globalPageX, int globalPageZ, int lod) {
        int variant = view == CaveView.FULL
                ? TileKey.VARIANT_CAVE_FULL : TileKey.VARIANT_CAVE_LAYERED;
        int projectionId = view == CaveView.FULL ? 0 : normalizedLayer;
        return new TileKey(sessionId, projectionId, lod,
                globalPageX, globalPageZ, variant);
    }

    private void updateBranchCandidate(PageInfo page, int[] pixels,
            long[] knownRows, int knownColumns, boolean complete,
            long sourceRevision) {
        if (page == null || pixels == null || knownRows == null
                || knownColumns <= 0) return;
        synchronized (pages) {
            MapRequestLane lane = currentBranchLaneLocked(page,
                    System.currentTimeMillis());
            lodTree.updatePage(page.key.dimension(), page.key.view(), page.key.layerY(),
                    page.key.globalPageX(), page.key.globalPageZ(),
                    pixels, knownRows, knownColumns, complete, sourceRevision, lane);
        }
    }

    private void updateBranch(PageInfo page) {
        // The LOD tree carries per-pixel known/complete coverage and merges each
        // dirty leaf into the existing branch. Publishing resolved leaves now is
        // therefore both stable and essential: one deferred chunk must not keep a
        // whole 4x4-chunk page (and every zoomed-out ancestor) black.
        int fullColumns = CaveTextureAtlas.PAGE_SIZE * CaveTextureAtlas.PAGE_SIZE;
        if (page.frontLods == null || page.knownColumns <= 0) return;
        synchronized (pages) {
            long sourceRevision = page.uploadedSourceRevision != Long.MIN_VALUE
                    ? page.uploadedSourceRevision
                    : revisions.getOrDefault(page.key,
                            Math.max(1L, page.uploadedRevision));
            MapRequestLane lane = currentBranchLaneLocked(page,
                    System.currentTimeMillis());
            lodTree.updatePage(page.key.dimension(), page.key.view(), page.key.layerY(),
                    page.key.globalPageX(), page.key.globalPageZ(),
                    page.frontLods[0], page.knownRows,
                    page.knownColumns, page.knownColumns >= fullColumns,
                    sourceRevision, lane);
        }
    }

    private boolean hasReplacementCoverage(PageInfo page) {
        if (page == null || page.knownEmpty) return true;
        long revision = page.uploadedSourceRevision;
        if (revision == Long.MIN_VALUE
                || revision == UNVERIFIED_REGION_IMAGE_SOURCE_REVISION) {
            return false;
        }
        return lodTree.coversPage(page.key.dimension(), page.key.view(),
                page.key.layerY(), page.key.globalPageX(), page.key.globalPageZ(),
                revision);
    }

    private boolean publishBranches(int budget, long deadline) {
        synchronized (pages) {
            lodTree.publish(budget, deadline);
            return lodTree.lastPublishGpuDenied();
        }
    }

    private static void commitCoverage(PageInfo info,
            long[] incomingKnownRows, boolean complete,
            int addedKnownColumns, int pixelCount) {
        if (complete) {
            java.util.Arrays.fill(info.knownRows, -1L);
            info.knownColumns = pixelCount;
            return;
        }
        for (int y = 0; y < info.knownRows.length; y++) {
            info.knownRows[y] |= incomingKnownRows[y];
        }
        info.knownColumns = Math.min(pixelCount,
                info.knownColumns + addedKnownColumns);
    }

    private static int countAddedKnownColumns(long[] existingRows,
            long[] incomingRows) {
        if (incomingRows == null) return 0;
        int rowCount = Math.min(existingRows.length, incomingRows.length);
        int added = 0;
        for (int row = 0; row < rowCount; row++) {
            added += Long.bitCount(incomingRows[row] & ~existingRows[row]);
        }
        return added;
    }

    private boolean isRecentlyRenderVisible(PageInfo info) {
        return info != null && info.lastVisibleRenderEpoch > 0L
                && renderEpoch - info.lastVisibleRenderEpoch <= 1L;
    }

    private static void markIncomplete(PageInfo info, long now) {
        // Preserve the last-good CPU/GPU revision. A partial page is visible and
        // reusable; only a newer repository revision should trigger another build.
        info.nextRetryMs = now + INCOMPLETE_RETRY_MS;
    }


    private boolean evictExactPageForBudget(PageKey key) {
        if (key == null || renderBatchDepth > 0) return false;
        PageInfo retired;
        long now = System.currentTimeMillis();
        synchronized (pages) {
            retired = pages.get(key);
            if (retired == null || retired.pending != null
                    || retired.atlasSlot < 0 || !retired.initialized
                    || isRecentlyRenderVisible(retired)) {
                return false;
            }
            boolean safelyOffscreen = isOffscreenEvictionCandidateLocked(retired, now);
            if (!safelyOffscreen && !hasReplacementCoverage(retired)) return false;
            // Preserve CPU data and request/revision state. Only GPU residency is
            // retired so a later visible request can re-upload without rereading
            // or reprojecting the world source.
            retired.releaseAtlasSlot();
        }
        telemetry.recordAtlasEviction();
        return true;
    }

    /**
     * Acquires an exact atlas slot without allocating candidate collections.
     *
     * <p>Offscreen coherent pages are the first eviction class and do not require a
     * branch replacement: they cannot create a visible hole and their CPU copy is
     * retained for a cheap restore. A visible page is considered only when a branch
     * already covers it. This prevents a full exact-page atlas from deadlocking a
     * newly visible close-zoom page merely because branch publication is behind.</p>
     */
    private boolean ensureAtlasSlot(PageInfo info) {
        if (info.atlasSlot >= 0) return true;
        int slot = atlas.acquireSlot();
        if (slot >= 0) {
            info.atlasSlot = slot;
            return true;
        }
        if (atlas.hasQuarantinedSlots()) return false;

        PageInfo victim;
        long now = System.currentTimeMillis();
        synchronized (pages) {
            if (renderBatchDepth > 0) return false;
            victim = selectAtlasVictimLocked(info, now, true, true);
            if (victim == null) {
                // Local atlas liveness is more important than a stale global pin.
                // The page is outside all active viewports and retains its CPU copy.
                victim = selectAtlasVictimLocked(info, now, true, false);
            }
            if (victim == null) {
                victim = selectAtlasVictimLocked(info, now, false, true);
            }
        }
        if (victim == null) return false;

        boolean offscreen;
        synchronized (pages) {
            offscreen = isOffscreenEvictionCandidateLocked(victim, now);
            victim.releaseAtlasSlot();
        }
        telemetry.recordAtlasEviction();
        if (offscreen) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent("CAVE_ATLAS_OFFSCREEN_EVICTED", 250L)) {
                recorder.event("CAVE_ATLAS_OFFSCREEN_EVICTED",
                        "victim=" + victim.key + " requester=" + info.key);
            }
        }
        // victim.releaseAtlasSlot() is fenced when the victim was published.
        // The newly free capacity is intentionally visible next frame.
        slot = atlas.acquireSlot();
        if (slot < 0) return false;
        info.atlasSlot = slot;
        return true;
    }

    private PageInfo selectAtlasVictimLocked(PageInfo protectedInfo, long now,
            boolean requireOffscreen, boolean respectPin) {
        PageInfo best = null;
        long bestScore = Long.MAX_VALUE;
        MapResidencyManager residency = MapResidencyManager.getInstance();
        for (PageInfo candidate : pages.values()) {
            if (candidate == protectedInfo || candidate.pending != null
                    || candidate.atlasSlot < 0 || !candidate.initialized
                    || isRecentlyRenderVisible(candidate)
                    || hasActiveRequestLocked(candidate.key, now)) {
                continue;
            }
            boolean offscreen = isOffscreenEvictionCandidateLocked(candidate, now);
            if (requireOffscreen) {
                if (!offscreen) continue;
            } else if (!hasReplacementCoverage(candidate)) {
                continue;
            }
            String key = residencyKey(candidate.key);
            if (respectPin && residency.isPinned(key)) continue;
            long score = residency.evictionScore(key);
            if (preferredView != null
                    && preferredDimension.equals(candidate.key.dimension())
                    && candidate.key.view() != preferredView) {
                score -= 1_000_000_000L;
            }
            if (best == null || score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private boolean hasActiveRequestLocked(PageKey key, long now) {
        PageRequest request = requests.get(key);
        return request != null && request.effectiveLane(now) != null;
    }

    private boolean isOffscreenEvictionCandidateLocked(PageInfo info, long now) {
        if (info == null || now - info.lastVisibleMs <= OFFSCREEN_EVICTION_GRACE_MS) {
            return false;
        }
        for (VisiblePlanner planner : visiblePlans.values()) {
            if (planner == null || planner.pagePlan.length == 0
                    || now - planner.lastEnumerationMs > ACTIVE_PLANNER_GRACE_MS) {
                continue;
            }
            if (planner.matches(info.key)) return false;
        }
        return true;
    }

    private static void downsample(int[] source, int sourceSize,
            int[] target, int targetSize) {
        for (int z = 0; z < targetSize; z++) {
            int sourceZ = z << 1;
            for (int x = 0; x < targetSize; x++) {
                int sourceX = x << 1;
                int p0 = source[sourceZ * sourceSize + sourceX];
                int p1 = source[sourceZ * sourceSize + sourceX + 1];
                int p2 = source[(sourceZ + 1) * sourceSize + sourceX];
                int p3 = source[(sourceZ + 1) * sourceSize + sourceX + 1];
                target[z * targetSize + x] = averageAbgr(p0, p1, p2, p3);
            }
        }
    }

    private static int averageAbgr(int p0, int p1, int p2, int p3) {
        int[] values = { p0, p1, p2, p3 };
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        int colored = 0;
        int alpha = 0;
        for (int value : values) {
            int childAlpha = (value >>> 24) & 0xFF;
            if (childAlpha == 0 || value == 0) continue;
            red += value & 0xFF;
            green += (value >>> 8) & 0xFF;
            blue += (value >>> 16) & 0xFF;
            alpha = Math.max(alpha, childAlpha);
            colored++;
        }
        if (colored == 0) return 0;
        // Xaero derives one 64x64 branch by linearly reducing all four children.
        // Keep every visible child in the result instead of selecting one texel and
        // discarding the other three cave lines. Alpha remains opaque enough for a
        // one-pixel tunnel to survive subsequent hierarchy levels.
        int r = (int) (red / colored);
        int g = (int) (green / colored);
        int b = (int) (blue / colored);
        int coverageLift = 196 + colored * 15;
        r = Math.min(255, r * coverageLift / 256);
        g = Math.min(255, g * coverageLift / 256);
        b = Math.min(255, b * coverageLift / 256);
        return (Math.max(alpha, 224) << 24) | (b << 16) | (g << 8) | r;
    }

    /**
     * Removes one live request and its REQUESTED diagnostic ownership together.
     * A PageRequest can be cleared/retargeted long before the PageInfo itself is
     * evicted, so tying tracker cleanup only to page eviction leaves historical
     * REQUESTED states alive for the whole session.
     */
    private boolean removeRequestOwnershipLocked(PageKey key, PageRequest expected) {
        PageRequest current = requests.get(key);
        if (current == null || (expected != null && current != expected)) return false;
        requests.remove(key);
        ExactPageStateTracker.getInstance().removeIfState(
                stateKey(key), ExactPageState.REQUESTED);
        if (!pages.containsKey(key)) revisions.remove(key);
        return true;
    }

    /**
     * Removes expired request ownership and its diagnostic state as one invariant.
     * PASS101 had several direct Map.removeIf() paths for mode/lane/viewport
     * handoff. Those removed the real PageRequest but left REQUESTED entries in
     * ExactPageStateTracker indefinitely (the PASS101 log ended with 1085 requested
     * states older than five seconds while only 166 live cave requests existed).
     */
    private int removeExpiredRequestsLocked(long now) {
        int removed = 0;
        var iterator = requests.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<PageKey, PageRequest> entry = iterator.next();
            PageRequest request = entry.getValue();
            request.expireLaneObservations(now);
            if (!request.isExpired(now)) continue;
            PageKey key = entry.getKey();
            iterator.remove();
            removed++;
            ExactPageStateTracker.getInstance().removeIfState(
                    stateKey(key), ExactPageState.REQUESTED);
            PageInfo info = pages.get(key);
            if (info != null && info.pending != null) {
                boolean viewportOwned = isProjectionViewportOwned(key,
                        info.pendingProjectionTopY, now);
                if (!viewportOwned) {
                    detachPendingLocked(info, true);
                } else {
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    if (recorder.shouldEmitEvent(
                            "CAVE_REQUEST_EXPIRED_VIEWPORT_RETAINED", 250L)) {
                        recorder.event("CAVE_REQUEST_EXPIRED_VIEWPORT_RETAINED",
                                "page=" + key
                                        + " top_y=" + info.pendingProjectionTopY
                                        + " pending_lane=" + info.pendingLane
                                        + " policy=planner_owns_inflight");
                    }
                }
            }
            if (!pages.containsKey(key)) revisions.remove(key);
        }
        return removed;
    }

    private void pruneRequests(long now) {
        synchronized (pages) {
            removeExpiredRequestsLocked(now);
        }
    }

    private boolean isProjectionStillOwned(PageKey key, int projectionTopY,
            long now) {
        return isProjectionStillRequested(key, projectionTopY)
                || isProjectionViewportOwned(key, projectionTopY, now);
    }

    private boolean isProjectionViewportOwned(PageKey key, int projectionTopY,
            long now) {
        for (MapRequestLane lane : new MapRequestLane[] {
                MapRequestLane.FULLSCREEN, MapRequestLane.MINIMAP }) {
            VisiblePlanner planner = visiblePlans.get(lane);
            if (plannerActive(planner, now) && planner.matches(key)
                    && planner.projectionTopY == projectionTopY) {
                return true;
            }
        }
        return false;
    }

    private boolean isProjectionStillRequested(PageKey key, int projectionTopY) {
        synchronized (pages) {
            PageRequest request = requests.get(key);
            long now = System.currentTimeMillis();
            return request != null && !request.isExpired(now)
                    && request.projectionTopY == projectionTopY;
        }
    }

    private boolean isPendingStillWantedLocked(PageInfo info, long now) {
        if (info == null || info.pending == null) return false;
        PageRequest request = requests.get(info.key);
        if (request != null && !request.isExpired(now)
                && request.projectionTopY == info.pendingProjectionTopY) {
            return true;
        }
        // Xaero's loaded writer window remains ownership even after a transient
        // request object has been satisfied/expired. A visible page must not lose an
        // in-flight exact build merely because its 2 s minimap lease elapsed while
        // CPU/GPU queues were under pressure.
        return isProjectionViewportOwned(info.key,
                info.pendingProjectionTopY, now);
    }

    private void detachPendingLocked(PageInfo info, boolean countCancellation) {
        if (info == null || info.pending == null) return;
        CompletableFuture<BuildResult> detached = info.pending;
        MapRequestLane detachedLane = info.pendingLane;
        int detachedProjectionTopY = info.pendingProjectionTopY;
        long revision = revisions.getOrDefault(info.key, 1L);
        if (info.pendingToken != null) info.pendingToken.cancel();
        boolean cancelled = detached.cancel(false);
        if (countCancellation && cancelled) {
            pipelineTelemetry.recordTaskCancelledBeforeRun();
        }
        // A completion may already have reached the publication queue just before
        // the viewport/lane was retired. Remove that ownership record now so it
        // cannot consume a later publication scan only to be discarded there.
        completedBuilds.removeIf(completed -> completed.info() == info
                && completed.future() == detached);
        info.pending = null;
        info.pendingToken = null;
        info.pendingLane = null;
        info.pendingProjectionTopY = Integer.MIN_VALUE;
        info.pendingCompletionRecorded = false;
        info.nextRetryMs = 0L;

        long now = System.currentTimeMillis();
        PageRequest request = requests.get(info.key);
        boolean requested = request != null && !request.isExpired(now)
                && request.projectionTopY == detachedProjectionTopY;
        MapRequestLane terminalLane = requested
                ? request.effectiveLane(now) : detachedLane;
        ExactPageStateTracker.getInstance().transition(
                stateKey(info.key), requested
                        ? ExactPageState.REQUESTED
                        : ExactPageState.STALE_GENERATION,
                terminalLane, revision);
    }

    private boolean matchesActiveProjectionLocked(PageInfo info) {
        if (info == null || info.key.view() == CaveView.FULL) return true;
        ProjectionBandKey band = new ProjectionBandKey(info.key.dimension(),
                info.key.view(), info.key.layerY());
        int active = activeLayerProjections.getOrDefault(
                band, info.publishedProjectionTopY);
        return info.canRenderProjection(active);
    }

    private boolean matchesAuthoritativeProjectionLocked(PageInfo info) {
        if (info == null || info.key.view() == CaveView.FULL) return true;
        ProjectionBandKey band = new ProjectionBandKey(info.key.dimension(),
                info.key.view(), info.key.layerY());
        int active = activeLayerProjections.getOrDefault(
                band, info.publishedProjectionTopY);
        return info.isProjectionAuthoritative(active);
    }

    private void trimPages() {
        synchronized (pages) {
            if (pages.size() <= MAX_PAGES) return;
        }
        List<PageInfo> retired = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (pages) {
            while (pages.size() > MAX_PAGES) {
                var iterator = pages.entrySet().iterator();
                PageInfo selected = null;
                while (iterator.hasNext()) {
                    PageInfo candidate = iterator.next().getValue();
                    if (candidate.pending != null
                            || hasActiveRequestLocked(candidate.key, now)
                            || isRecentlyRenderVisible(candidate)) {
                        continue;
                    }
                    if (!candidate.initialized
                            || isOffscreenEvictionCandidateLocked(candidate, now)
                            || hasReplacementCoverage(candidate)) {
                        selected = candidate;
                        iterator.remove();
                        if (!requests.containsKey(candidate.key)) {
                            revisions.remove(candidate.key);
                        }
                        break;
                    }
                }
                if (selected == null) break;
                retired.add(selected);
            }
        }
        for (PageInfo info : retired) {
            synchronized (pages) {
                stagedRegionBranchRevisions.keySet().removeIf(
                        branchKey -> branchKey.pageKey().equals(info.key));
                if (renderBatchDepth > 0) deferredCloses.add(info);
                else info.close();
            }
            ExactPageStateTracker.getInstance().remove(stateKey(info.key));
        }
    }

    private static PageKey key(CaveView view, int layerY, int globalPageX, int globalPageZ) {
        return new PageKey(dimension(), view, normalizedLayer(view, layerY),
                globalPageX, globalPageZ);
    }


    private static String residencyKey(PageKey key) {
        return key == null ? "cave:unknown"
                : "cave:" + key.dimension() + ':' + key.view() + ':'
                        + key.layerY() + ':' + key.globalPageX() + ':'
                        + key.globalPageZ();
    }

    private static String stateKey(PageKey key) {
        return "cave:" + key.dimension() + ':' + key.view() + ':' + key.layerY()
                + ':' + key.globalPageX() + ':' + key.globalPageZ();
    }

    private static int normalizedLayer(CaveView view, int layerY) {
        return DenseCaveTile.normalizeLayer(view, projectionTopY(view, layerY));
    }

    private static int projectionTopY(CaveView view, int layerY) {
        return CaveLayerBand.projectionTopY(view, layerY);
    }

    private static String dimension() {
        return MapManager.getInstance().getDimensionCacheKey();
    }

    private static boolean matchesCurrentDimension(String candidate) {
        if (candidate == null || candidate.isBlank()) return false;
        MapManager manager = MapManager.getInstance();
        return candidate.equals(manager.getDimensionCacheKey())
                || candidate.equals(manager.getCurrentDimensionResourceId());
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class RegionCacheInstall {
        private final CaveRegionImageCache.RegionImage image;
        private final MapRequestLane lane;
        private final long epoch;
        private long remainingPageMask;

        private RegionCacheInstall(CaveRegionImageCache.RegionImage image,
                MapRequestLane lane, long epoch, long validPageMask) {
            this.image = image;
            this.lane = lane;
            this.epoch = epoch;
            this.remainingPageMask = validPageMask;
        }

        private EligiblePage peekEligiblePage(VisiblePlanner planner) {
            if (remainingPageMask == 0L) return null;
            if (planner == null || !planner.matches(image.key())) {
                // The viewport was handed off before this 1 MiB image reached the
                // render thread. Do not populate off-screen exact atlas slots.
                remainingPageMask = 0L;
                return null;
            }
            int bestPage = -1;
            int bestPlanOrdinal = Integer.MAX_VALUE;
            long candidates = remainingPageMask;
            while (candidates != 0L) {
                int localOrdinal = Long.numberOfTrailingZeros(candidates);
                candidates &= candidates - 1L;
                int localX = localOrdinal & 7;
                int localZ = localOrdinal >>> 3;
                int pageX = image.key().regionX() * CaveLoadHierarchy.PAGES_PER_REGION
                        + localX;
                int pageZ = image.key().regionZ() * CaveLoadHierarchy.PAGES_PER_REGION
                        + localZ;
                int planOrdinal = planner.ordinalOf(pageX, pageZ);
                if (planOrdinal < 0) {
                    remainingPageMask &= ~(1L << localOrdinal);
                    continue;
                }
                if (planner.fullscreen
                        && planOrdinal > planner.publicationCursor) {
                    // Finished CIMG pixels stay staged until the same single
                    // fullscreen presentation writer reaches this page.
                    continue;
                }
                if (planOrdinal < bestPlanOrdinal) {
                    bestPlanOrdinal = planOrdinal;
                    bestPage = localOrdinal;
                }
            }
            return bestPage < 0 ? null
                    : new EligiblePage(bestPage, bestPlanOrdinal);
        }

        private void consume(int localOrdinal) {
            if (localOrdinal >= 0 && localOrdinal < CaveRegionImageCache.PAGE_COUNT) {
                remainingPageMask &= ~(1L << localOrdinal);
            }
        }

        private record EligiblePage(int localOrdinal, int planOrdinal) { }

        private boolean containsPreparedPage(VisiblePlanner planner,
                int pageX, int pageZ, CaveTileRepository repository) {
            if (planner == null || repository == null
                    || !planner.matches(image.key())) return false;
            int firstPageX = image.key().regionX()
                    * CaveLoadHierarchy.PAGES_PER_REGION;
            int firstPageZ = image.key().regionZ()
                    * CaveLoadHierarchy.PAGES_PER_REGION;
            int localX = pageX - firstPageX;
            int localZ = pageZ - firstPageZ;
            if (localX < 0 || localX >= CaveLoadHierarchy.PAGES_PER_REGION
                    || localZ < 0 || localZ >= CaveLoadHierarchy.PAGES_PER_REGION) {
                return false;
            }
            int localOrdinal = localZ * CaveLoadHierarchy.PAGES_PER_REGION + localX;
            if ((remainingPageMask & (1L << localOrdinal)) == 0L) return false;
            long cached = image.pageSourceStamp(localX, localZ);
            long current = repository.getPageRevision(image.key().view(),
                    image.key().projectionTopY(), pageX, pageZ);
            return cached != 0L && (current == 0L || cached == current);
        }

        private boolean hasRemainingPages() {
            return remainingPageMask != 0L;
        }
    }

    private record RegionBranchKey(PageKey pageKey, int projectionTopY) {
    }

    private record PageKey(String dimension, CaveView view, int layerY,
            int globalPageX, int globalPageZ) {
    }

    private record ProjectionBandKey(String dimension, CaveView view, int layerY) {
    }

    private record ResidentNodeKey(String dimension, CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
    }

    private static final class VisiblePlanner {
        private String dimension = "";
        private CaveView view;
        private int layerY = Integer.MIN_VALUE;
        private int projectionTopY = Integer.MIN_VALUE;
        private int minPageX = Integer.MIN_VALUE;
        private int maxPageX = Integer.MIN_VALUE;
        private int minPageZ = Integer.MIN_VALUE;
        private int maxPageZ = Integer.MIN_VALUE;
        private int focusPageX = Integer.MIN_VALUE;
        private int focusPageZ = Integer.MIN_VALUE;
        private long lastEnumerationMs;
        /** Last live viewport demand, independent from enumeration cadence. */
        private long lastDemandMs;
        private long[] pagePlan = new long[0];
        private CaveLoadHierarchy.OrdinalIndex pageOrdinals = CaveLoadHierarchy.buildOrdinalIndex(new long[0]);
        /** Unique 512x512 cache regions ordered by first visible page. */
        private long[] regionPlan = new long[0];
        private int pageCursor;
        /** Contiguous visible scanline prefix allowed to render exact leaves. */
        private int publicationCursor;
        /** Current coordinate waiting at the reveal fence; never a source owner. */
        private long frontierBlockedPage = Long.MIN_VALUE;
        private long frontierBlockedSinceMs;
        /** Last producer repair pulse for the current presentation coordinate. */
        private long frontierLastRepairMs;
        private int regionCursor;
        private long nextRegionRestartMs;
        /** GPU publication sequence captured when this viewport generation began. */
        private long baselineGpuPublicationSequence;
        private int updateSliceIndex;
        private long requestCompletedCycles;
        private float scale = 1.0f;
        private long nextRestartMs;
        private boolean fullscreen;
        /** Current presentation policy; source coverage is never focus-clamped. */
        private boolean branchOnly;
        /** Density-correct hierarchy level currently requested by the viewport. */
        private int preferredBranchLevel = 1;

        private boolean shouldRecenter(int centerPageX, int centerPageZ,
                int thresholdPages) {
            if (focusPageX == Integer.MIN_VALUE) return false;
            return Math.max(Math.abs(centerPageX - focusPageX),
                    Math.abs(centerPageZ - focusPageZ)) >= Math.max(1, thresholdPages);
        }

        private void recenter(int centerPageX, int centerPageZ,
                MapRequestLane lane) {
            fullscreen = lane == MapRequestLane.FULLSCREEN;
            focusPageX = centerPageX;
            focusPageZ = centerPageZ;
            pagePlan = CaveLoadHierarchy.buildVisiblePagePlan(
                    minPageX, maxPageX, minPageZ, maxPageZ,
                    centerPageX, centerPageZ, fullscreen,
                    false, minPageX, maxPageX, minPageZ, maxPageZ);
            pageOrdinals = CaveLoadHierarchy.buildOrdinalIndex(pagePlan);
            regionPlan = CaveLoadHierarchy.buildRegionPlanFromPagePlan(pagePlan);
            pageCursor = 0;
            publicationCursor = 0;
            clearFrontierBlock();
            regionCursor = 0;
            nextRegionRestartMs = 0L;
            updateSliceIndex = 0;
            requestCompletedCycles = 0L;
            nextRestartMs = 0L;
        }

        private boolean sameProjectionOverlap(String dimension, CaveView view,
                int layerY, int projectionTopY,
                int minPageX, int maxPageX, int minPageZ, int maxPageZ) {
            return this.dimension.equals(dimension) && this.view == view
                    && this.layerY == layerY
                    && this.projectionTopY == projectionTopY
                    && rectanglesOverlap(this.minPageX, this.maxPageX,
                            this.minPageZ, this.maxPageZ,
                            minPageX, maxPageX, minPageZ, maxPageZ);
        }

        private boolean containsVisibleViewport(String dimension, CaveView view,
                int layerY, int projectionTopY,
                int visibleMinPageX, int visibleMaxPageX,
                int visibleMinPageZ, int visibleMaxPageZ) {
            return this.dimension.equals(dimension) && this.view == view
                    && this.layerY == layerY
                    && this.projectionTopY == projectionTopY
                    && visibleMinPageX >= minPageX
                    && visibleMaxPageX <= maxPageX
                    && visibleMinPageZ >= minPageZ
                    && visibleMaxPageZ <= maxPageZ;
        }

        private void reset(String dimension, CaveView view, int layerY,
                int projectionTopY, int minPageX, int maxPageX,
                int minPageZ, int maxPageZ, int centerPageX, int centerPageZ,
                MapRequestLane lane) {
            boolean continuousPan = lane == MapRequestLane.FULLSCREEN
                    && this.dimension.equals(dimension) && this.view == view
                    && this.layerY == layerY
                    && this.projectionTopY == projectionTopY
                    && rectanglesOverlap(this.minPageX, this.maxPageX,
                            this.minPageZ, this.maxPageZ,
                            minPageX, maxPageX, minPageZ, maxPageZ);
            int previousMinPageX = this.minPageX;
            int previousMaxPageX = this.maxPageX;
            int previousMinPageZ = this.minPageZ;
            int previousMaxPageZ = this.maxPageZ;
            int previousFocusPageX = this.focusPageX;
            int previousFocusPageZ = this.focusPageZ;
            this.dimension = dimension;
            this.view = view;
            this.layerY = layerY;
            this.projectionTopY = projectionTopY;
            this.minPageX = minPageX;
            this.maxPageX = maxPageX;
            this.minPageZ = minPageZ;
            this.maxPageZ = maxPageZ;
            this.fullscreen = lane == MapRequestLane.FULLSCREEN;
            int plannedCenterX = centerPageX;
            int plannedCenterZ = centerPageZ;
            if (continuousPan
                    && previousFocusPageX >= minPageX
                    && previousFocusPageX <= maxPageX
                    && previousFocusPageZ >= minPageZ
                    && previousFocusPageZ <= maxPageZ) {
                /* Keep the wavefront anchor stable while a guarded viewport shifts.
                 * New edge pages then enter at the outer rings instead of reshuffling
                 * the entire exact queue on every 64-block boundary. */
                plannedCenterX = previousFocusPageX;
                plannedCenterZ = previousFocusPageZ;
            }
            this.focusPageX = plannedCenterX;
            this.focusPageZ = plannedCenterZ;
            pagePlan = CaveLoadHierarchy.buildVisiblePagePlan(
                    minPageX, maxPageX, minPageZ, maxPageZ,
                    plannedCenterX, plannedCenterZ, fullscreen,
                    continuousPan, previousMinPageX, previousMaxPageX,
                    previousMinPageZ, previousMaxPageZ);
            pageOrdinals = CaveLoadHierarchy.buildOrdinalIndex(pagePlan);
            regionPlan = CaveLoadHierarchy.buildRegionPlanFromPagePlan(pagePlan);
            pageCursor = 0;
            publicationCursor = 0;
            clearFrontierBlock();
            regionCursor = 0;
            nextRegionRestartMs = 0L;
            updateSliceIndex = 0;
            requestCompletedCycles = 0L;
            nextRestartMs = 0L;
        }

        private void clearFrontierBlock() {
            frontierBlockedPage = Long.MIN_VALUE;
            frontierBlockedSinceMs = 0L;
            frontierLastRepairMs = 0L;
        }

        private boolean matches(PageKey key) {
            return key != null && dimension.equals(key.dimension())
                    && view == key.view() && layerY == key.layerY()
                    && key.globalPageX() >= minPageX
                    && key.globalPageX() <= maxPageX
                    && key.globalPageZ() >= minPageZ
                    && key.globalPageZ() <= maxPageZ;
        }

        private boolean matches(CaveRegionImageCache.Key key) {
            if (key == null || !dimension.equals(key.dimension())
                    || view != key.view() || layerY != key.normalizedLayer()
                    || projectionTopY != key.projectionTopY()) return false;
            int firstPageX = key.regionX()
                    * CaveRegionImageCache.PAGES_PER_EDGE;
            int firstPageZ = key.regionZ()
                    * CaveRegionImageCache.PAGES_PER_EDGE;
            int lastPageX = firstPageX
                    + CaveRegionImageCache.PAGES_PER_EDGE - 1;
            int lastPageZ = firstPageZ
                    + CaveRegionImageCache.PAGES_PER_EDGE - 1;
            return lastPageX >= minPageX && firstPageX <= maxPageX
                    && lastPageZ >= minPageZ && firstPageZ <= maxPageZ;
        }

        private int ordinalOf(int pageX, int pageZ) {
            return pageOrdinals.getOrDefault(
                    CaveLoadHierarchy.pack(pageX, pageZ), -1);
        }

        private static boolean rectanglesOverlap(int firstMinX, int firstMaxX,
                int firstMinZ, int firstMaxZ, int secondMinX, int secondMaxX,
                int secondMinZ, int secondMaxZ) {
            return firstMinX <= secondMaxX && firstMaxX >= secondMinX
                    && firstMinZ <= secondMaxZ && firstMaxZ >= secondMinZ;
        }

        private void clear() {
            dimension = "";
            view = null;
            layerY = Integer.MIN_VALUE;
            projectionTopY = Integer.MIN_VALUE;
            minPageX = maxPageX = minPageZ = maxPageZ = Integer.MIN_VALUE;
            focusPageX = focusPageZ = Integer.MIN_VALUE;
            lastEnumerationMs = 0L;
            lastDemandMs = 0L;
            pagePlan = new long[0];
            pageOrdinals = CaveLoadHierarchy.buildOrdinalIndex(new long[0]);
            regionPlan = new long[0];
            pageCursor = 0;
            publicationCursor = 0;
            clearFrontierBlock();
            regionCursor = 0;
            nextRegionRestartMs = 0L;
            baselineGpuPublicationSequence = 0L;
            updateSliceIndex = 0;
            requestCompletedCycles = 0L;
            scale = 1.0f;
            nextRestartMs = 0L;
            fullscreen = false;
            branchOnly = false;
        }
    }

    private static final class PageRequest {
        private final PageKey key;
        private final long[] lastSeenByLane = new long[REQUEST_LANES.length];
        private final long[] firstSeenByLane = new long[REQUEST_LANES.length];
        private final int[] priorityByLane = new int[REQUEST_LANES.length];
        private final int[] ordinalByLane = new int[REQUEST_LANES.length];
        private int projectionTopY;

        private PageRequest(PageKey key, int projectionTopY) {
            this.key = key;
            this.projectionTopY = projectionTopY;
            java.util.Arrays.fill(priorityByLane, Integer.MIN_VALUE);
            java.util.Arrays.fill(ordinalByLane, Integer.MAX_VALUE);
        }

        private void retarget(int projectionTopY) {
            this.projectionTopY = projectionTopY;
            java.util.Arrays.fill(lastSeenByLane, 0L);
            java.util.Arrays.fill(firstSeenByLane, 0L);
            java.util.Arrays.fill(priorityByLane, Integer.MIN_VALUE);
            java.util.Arrays.fill(ordinalByLane, Integer.MAX_VALUE);
        }

        private void observe(MapRequestLane lane, int priority, long now,
                int fullscreenOrdinal) {
            int index = lane.ordinal();
            lastSeenByLane[index] = now;
            if (firstSeenByLane[index] == 0L) firstSeenByLane[index] = now;
            priorityByLane[index] = Math.max(priorityByLane[index], priority);
            if (lane == MapRequestLane.FULLSCREEN) {
                ordinalByLane[index] = Math.min(ordinalByLane[index], fullscreenOrdinal);
            }
        }

        private void rebase(MapRequestLane lane, int priority, long now,
                int fullscreenOrdinal) {
            int index = lane.ordinal();
            lastSeenByLane[index] = now;
            firstSeenByLane[index] = now;
            priorityByLane[index] = priority;
            ordinalByLane[index] = lane == MapRequestLane.FULLSCREEN
                    ? fullscreenOrdinal : Integer.MAX_VALUE;
        }

        private boolean isLaneActive(MapRequestLane lane, long now) {
            long seen = lastSeenByLane[lane.ordinal()];
            return seen != 0L && now - seen <= requestLeaseMs(lane);
        }

        private int priorityForLane(MapRequestLane lane) {
            return priorityByLane[lane.ordinal()];
        }

        private long seenForLane(MapRequestLane lane) {
            return lastSeenByLane[lane.ordinal()];
        }

        private void clearLane(MapRequestLane lane) {
            int index = lane.ordinal();
            lastSeenByLane[index] = 0L;
            firstSeenByLane[index] = 0L;
            priorityByLane[index] = Integer.MIN_VALUE;
            ordinalByLane[index] = Integer.MAX_VALUE;
        }

        private void clearAll() {
            java.util.Arrays.fill(lastSeenByLane, 0L);
            java.util.Arrays.fill(firstSeenByLane, 0L);
            java.util.Arrays.fill(priorityByLane, Integer.MIN_VALUE);
            java.util.Arrays.fill(ordinalByLane, Integer.MAX_VALUE);
        }

        private void expireLaneObservations(long now) {
            for (MapRequestLane lane : REQUEST_LANES) {
                int index = lane.ordinal();
                long seen = lastSeenByLane[index];
                if (seen != 0L && now - seen <= requestLeaseMs(lane)) continue;
                lastSeenByLane[index] = 0L;
                firstSeenByLane[index] = 0L;
                priorityByLane[index] = Integer.MIN_VALUE;
                ordinalByLane[index] = Integer.MAX_VALUE;
            }
        }

        private int ordinalForLane(MapRequestLane lane) {
            return ordinalByLane[lane.ordinal()];
        }

        private MapRequestLane effectiveLane(long now) {
            MapRequestLane best = null;
            for (MapRequestLane lane : REQUEST_LANES) {
                long seen = lastSeenByLane[lane.ordinal()];
                if (seen == 0L || now - seen > requestLeaseMs(lane)) continue;
                if (lane.strongerThan(best)) best = lane;
            }
            return best;
        }

        private int effectivePriority(long now) {
            int best = Integer.MIN_VALUE;
            for (MapRequestLane lane : REQUEST_LANES) {
                int index = lane.ordinal();
                long seen = lastSeenByLane[index];
                if (seen == 0L || now - seen > requestLeaseMs(lane)) continue;
                long first = firstSeenByLane[index] == 0L
                        ? seen : firstSeenByLane[index];
                long ageMs = Math.max(0L, now - first);
                // Xaero re-evaluates a small nearest set every frame. Stable
                // fixed-region work needs equivalent fairness: an eligible page gains
                // priority while waiting, but never crosses a stronger lane.
                // Fullscreen already has one immutable wavefront ordinal. Age
                // promotion previously let old lower rows overtake the top row in a
                // few milliseconds, producing the scattered PASS57 reveal. Keep
                // fairness for non-visual lanes only.
                int ageBonus = lane == MapRequestLane.FULLSCREEN
                        ? 0 : (int) Math.min(420_000L, ageMs * 210L);
                best = Math.max(best, priorityByLane[index] + ageBonus);
            }
            return best;
        }

        private long latestSeenMs() {
            long latest = 0L;
            for (long seen : lastSeenByLane) latest = Math.max(latest, seen);
            return latest;
        }

        private boolean isExpired(long now) {
            return effectiveLane(now) == null;
        }
    }

    private final class PageInfo {
        private final PageKey key;
        private final DirtyPlan dirtyPlan = new DirtyPlan();
        private int atlasSlot = -1;
        /** Last logical page-table publication; independent from atlas residency. */
        private long pageTableSessionId;
        private long pageTableStorageGeneration = Long.MIN_VALUE;
        private int pageTableSlot = -1;
        private long pageTableRevision = Long.MIN_VALUE;
        private int[][] frontLods;
        private long firstGpuPublicationSequence;
        private CompletableFuture<BuildResult> pending;
        private MapCancellationToken pendingToken;
        private MapRequestLane pendingLane;
        private int pendingProjectionTopY = Integer.MIN_VALUE;
        private boolean pendingCompletionRecorded;
        /** Exact Layered Top-Y represented by the whole visible page when uniform. */
        private int publishedProjectionTopY = Integer.MIN_VALUE;
        /** Exact Top-Y currently requested inside this retained 16-block band. */
        private int activeProjectionTopY = Integer.MIN_VALUE;
        /** Per 16x16 visible tile identity; mixed values are valid during transition. */
        private final int[] visibleTileProjectionTopY = new int[PROJECTION_TILE_COUNT];
        /** Bit set only after the corresponding 16x16 tile is atomically committed. */
        private int visibleTileMask;
        /** CPU-only replacement assembled for activeProjectionTopY. */
        private int stagingProjectionTopY = Integer.MIN_VALUE;
        private int[] stagingPixels;
        private final long[] stagingKnownRows = new long[CaveTextureAtlas.PAGE_SIZE];
        private int stagingKnownColumns;
        private long uploadedRevision;
        private long uploadedSourceRevision = Long.MIN_VALUE;
        private long noSourceRevision = Long.MIN_VALUE;
        private long observedSourceRevision = Long.MIN_VALUE;
        private long sourceRevisionChangedMs;
        private long sourceBurstStartedMs;
        private long lastPublicationMs;
        private long nextRetryMs;
        /** Earliest retry for an immutable CPU-ready completion awaiting an atlas slot. */
        private long nextPublicationAttemptMs;
        private long stagedReadySinceMs;
        private int gpuReservationFailures;
        private long lastVisibleRenderEpoch;
        private long lastVisibleMs;
        private boolean initialized;
        /** True only while frontLods originate from a pre-rendered CIMG. */
        private boolean regionImageFallback;
        private boolean knownEmpty;
        private CaveEmptyProof emptyProof = CaveEmptyProof.NONE;
        /** First time an all-zero product was rejected for lacking strong proof. */
        private long unprovenEmptySinceMs;
        /** Authoritative coverage accumulated across partial page builds. */
        private final long[] knownRows = new long[CaveTextureAtlas.PAGE_SIZE];
        private int knownColumns;
        /** Last CPU-ready state already offered to the independent branch tree. */
        private long branchCandidateSourceRevision = Long.MIN_VALUE;
        private int branchCandidateProjectionTopY = Integer.MIN_VALUE;
        private int branchCandidateTileMask;
        private int branchCandidateKnownColumns;
        private boolean branchCandidateAuthoritative;

        private PageInfo(PageKey key) {
            this.key = key;
            java.util.Arrays.fill(visibleTileProjectionTopY, Integer.MIN_VALUE);
        }

        private boolean matchesProjection(int projectionTopY) {
            return canRenderProjection(projectionTopY);
        }

        /**
         * A Layered cache key is the 16-block band. The exact Top-Y is transition
         * state inside that band. Retargeting must preserve the visible page and
         * prepare atomic 16x16 replacements rather than clearing the atlas.
         */
        private void beginProjectionTransition(int projectionTopY) {
            if (key.view() == CaveView.FULL) {
                activeProjectionTopY = Integer.MIN_VALUE;
                publishedProjectionTopY = Integer.MIN_VALUE;
                return;
            }
            if (activeProjectionTopY == projectionTopY) return;

            activeProjectionTopY = projectionTopY;
            resetProjectionStaging(projectionTopY);
            uploadedSourceRevision = Long.MIN_VALUE;
            noSourceRevision = Long.MIN_VALUE;
            observedSourceRevision = Long.MIN_VALUE;
            sourceRevisionChangedMs = 0L;
            sourceBurstStartedMs = 0L;
            lastPublicationMs = 0L;
            nextRetryMs = 0L;
            stagedReadySinceMs = 0L;
            knownEmpty = false;
            emptyProof = CaveEmptyProof.NONE;
            unprovenEmptySinceMs = 0L;
        }

        private void discardVisibleProjectionForRetarget() {
            releaseAtlasSlot();
            visibleTileMask = 0;
            java.util.Arrays.fill(visibleTileProjectionTopY, Integer.MIN_VALUE);
            java.util.Arrays.fill(knownRows, 0L);
            knownColumns = 0;
            publishedProjectionTopY = Integer.MIN_VALUE;
            knownEmpty = false;
            emptyProof = CaveEmptyProof.NONE;
            unprovenEmptySinceMs = 0L;
            regionImageFallback = false;
            branchCandidateSourceRevision = Long.MIN_VALUE;
            branchCandidateProjectionTopY = Integer.MIN_VALUE;
            branchCandidateTileMask = 0;
            branchCandidateKnownColumns = 0;
            branchCandidateAuthoritative = false;
        }

        private boolean canRenderProjection(int projectionTopY) {
            if (key.view() == CaveView.FULL) return true;
            if (activeProjectionTopY != projectionTopY || frontLods == null) return false;
            int fullMask = (1 << PROJECTION_TILE_COUNT) - 1;
            if (visibleTileMask != fullMask) return false;
            boolean exact = true;
            for (int tile = 0; tile < PROJECTION_TILE_COUNT; tile++) {
                if (visibleTileProjectionTopY[tile] != projectionTopY) {
                    exact = false;
                    break;
                }
            }
            return exact;
        }

        /**
         * Visual-only fallback during an exact Top-Y transition inside the same
         * 16-block cave layer. This is the SimpleMap equivalent of Xaero keeping
         * loadedCaving visible while loadingCaving is rewritten. It never satisfies
         * a request: completion/authority still require canRenderProjection().
         */
        private boolean canRenderLastGoodWithinBand(int projectionTopY) {
            if (key.view() != CaveView.LAYERED || frontLods == null
                    || !initialized || atlasSlot < 0 || visibleTileMask == 0) {
                return false;
            }
            int targetBand = DenseCaveTile.normalizeLayer(
                    CaveView.LAYERED, projectionTopY);
            for (int tile = 0; tile < PROJECTION_TILE_COUNT; tile++) {
                if ((visibleTileMask & (1 << tile)) == 0) continue;
                int visibleTopY = visibleTileProjectionTopY[tile];
                if (visibleTopY == Integer.MIN_VALUE
                        || DenseCaveTile.normalizeLayer(
                                CaveView.LAYERED, visibleTopY) != targetBand) {
                    return false;
                }
            }
            return true;
        }

        private boolean isProjectionAuthoritative(int projectionTopY) {
            int fullMask = (1 << PROJECTION_TILE_COUNT) - 1;
            if (visibleTileMask != fullMask) return false;
            if (key.view() == CaveView.FULL) return true;
            if (activeProjectionTopY != projectionTopY) return false;
            for (int tile = 0; tile < PROJECTION_TILE_COUNT; tile++) {
                if (visibleTileProjectionTopY[tile] != projectionTopY) return false;
            }
            return true;
        }

        private boolean hasVisibleProjectionDifferentFrom(int projectionTopY) {
            if (!initialized || visibleTileMask == 0) return false;
            for (int tile = 0; tile < PROJECTION_TILE_COUNT; tile++) {
                if ((visibleTileMask & (1 << tile)) == 0) continue;
                if (visibleTileProjectionTopY[tile] != projectionTopY) return true;
            }
            return false;
        }

        private boolean stagingProjectionComplete(int projectionTopY) {
            if (stagingProjectionTopY != projectionTopY) return false;
            for (long row : stagingKnownRows) {
                if (row != -1L) return false;
            }
            return true;
        }

        private int currentProjectionKnownColumns(int projectionTopY) {
            int count = 0;
            for (int row = 0; row < CaveTextureAtlas.PAGE_SIZE; row++) {
                count += Long.bitCount(projectionKnownRow(projectionTopY, row));
            }
            return count;
        }

        private int countAddedProjectionColumns(int projectionTopY,
                long[] incomingRows) {
            if (incomingRows == null) return 0;
            int added = 0;
            int rows = Math.min(CaveTextureAtlas.PAGE_SIZE, incomingRows.length);
            for (int row = 0; row < rows; row++) {
                added += Long.bitCount(incomingRows[row]
                        & ~projectionKnownRow(projectionTopY, row));
            }
            return added;
        }

        private int countNewReadyProjectionTiles(int projectionTopY,
                long[] incomingRows, boolean complete) {
            long[] candidateRows = stagingProjectionTopY == projectionTopY
                    ? stagingKnownRows : null;
            int ready = 0;
            for (int tileZ = 0; tileZ < PROJECTION_TILES_PER_PAGE; tileZ++) {
                for (int tileX = 0; tileX < PROJECTION_TILES_PER_PAGE; tileX++) {
                    int tile = tileZ * PROJECTION_TILES_PER_PAGE + tileX;
                    if ((visibleTileMask & (1 << tile)) != 0
                            && visibleTileProjectionTopY[tile] == projectionTopY) continue;
                    boolean tileReady = true;
                    long mask = PROJECTION_TILE_ROW_MASK
                            << (tileX * PROJECTION_TILE_SIZE);
                    int firstRow = tileZ * PROJECTION_TILE_SIZE;
                    for (int localZ = 0; localZ < PROJECTION_TILE_SIZE; localZ++) {
                        int row = firstRow + localZ;
                        long known = complete ? -1L : 0L;
                        if (candidateRows != null) known |= candidateRows[row];
                        if (incomingRows != null && row < incomingRows.length) {
                            known |= incomingRows[row];
                        }
                        if ((known & mask) != mask) {
                            tileReady = false;
                            break;
                        }
                    }
                    if (tileReady) ready++;
                }
            }
            return ready;
        }

        private long projectionKnownRow(int projectionTopY, int row) {
            long known = stagingProjectionTopY == projectionTopY
                    ? stagingKnownRows[row] : 0L;
            int tileZ = row / PROJECTION_TILE_SIZE;
            for (int tileX = 0; tileX < PROJECTION_TILES_PER_PAGE; tileX++) {
                int tile = tileZ * PROJECTION_TILES_PER_PAGE + tileX;
                if ((visibleTileMask & (1 << tile)) != 0
                        && visibleTileProjectionTopY[tile] == projectionTopY) {
                    known |= PROJECTION_TILE_ROW_MASK
                            << (tileX * PROJECTION_TILE_SIZE);
                }
            }
            return known;
        }

        private int stageProjection(int projectionTopY, int[] pixels,
                long[] incomingRows, boolean complete) {
            if (stagingProjectionTopY != projectionTopY) {
                resetProjectionStaging(projectionTopY);
            }
            if (stagingPixels == null) {
                stagingPixels = new int[CaveTextureAtlas.PAGE_SIZE
                        * CaveTextureAtlas.PAGE_SIZE];
            }
            int before = stagingKnownColumns;
            if (complete) {
                System.arraycopy(pixels, 0, stagingPixels, 0, stagingPixels.length);
                java.util.Arrays.fill(stagingKnownRows, -1L);
                stagingKnownColumns = stagingPixels.length;
                return Math.max(0, stagingKnownColumns - before);
            }
            for (int row = 0; row < CaveTextureAtlas.PAGE_SIZE; row++) {
                long mask = incomingRows[row];
                stagingKnownColumns += Long.bitCount(mask & ~stagingKnownRows[row]);
                long copy = mask;
                while (copy != 0L) {
                    int x = Long.numberOfTrailingZeros(copy);
                    stagingPixels[row * CaveTextureAtlas.PAGE_SIZE + x] =
                            pixels[row * CaveTextureAtlas.PAGE_SIZE + x];
                    copy &= copy - 1L;
                }
                stagingKnownRows[row] |= mask;
            }
            return Math.max(0, stagingKnownColumns - before);
        }

        private int readyStagedTileMask(int projectionTopY,
                int[] visiblePixels) {
            if (stagingProjectionTopY != projectionTopY
                    || visiblePixels == null) return 0;
            int maskResult = 0;
            for (int tileZ = 0; tileZ < PROJECTION_TILES_PER_PAGE; tileZ++) {
                for (int tileX = 0; tileX < PROJECTION_TILES_PER_PAGE; tileX++) {
                    long mask = PROJECTION_TILE_ROW_MASK
                            << (tileX * PROJECTION_TILE_SIZE);
                    int firstRow = tileZ * PROJECTION_TILE_SIZE;
                    boolean ready = true;
                    for (int localZ = 0; localZ < PROJECTION_TILE_SIZE; localZ++) {
                        if ((stagingKnownRows[firstRow + localZ] & mask) != mask) {
                            ready = false;
                            break;
                        }
                    }
                    if (ready) {
                        int tile = tileZ * PROJECTION_TILES_PER_PAGE + tileX;
                        boolean projectionChanged =
                                (visibleTileMask & (1 << tile)) == 0
                                        || visibleTileProjectionTopY[tile] != projectionTopY;
                        boolean pixelsChanged = projectionChanged;
                        if (!pixelsChanged) {
                            int firstX = tileX * PROJECTION_TILE_SIZE;
                            for (int localZ = 0; localZ < PROJECTION_TILE_SIZE
                                    && !pixelsChanged; localZ++) {
                                int row = firstRow + localZ;
                                int offset = row * CaveTextureAtlas.PAGE_SIZE + firstX;
                                for (int localX = 0;
                                        localX < PROJECTION_TILE_SIZE; localX++) {
                                    if (visiblePixels[offset + localX]
                                            != stagingPixels[offset + localX]) {
                                        pixelsChanged = true;
                                        break;
                                    }
                                }
                            }
                        }
                        if (pixelsChanged) maskResult |= 1 << tile;
                    }
                }
            }
            return maskResult;
        }

        private void copyStagedTiles(int projectionTopY, int tileMask,
                int[] destination) {
            if (stagingProjectionTopY != projectionTopY || stagingPixels == null) return;
            for (int tile = 0; tile < PROJECTION_TILE_COUNT; tile++) {
                if ((tileMask & (1 << tile)) == 0) continue;
                int tileX = tile % PROJECTION_TILES_PER_PAGE;
                int tileZ = tile / PROJECTION_TILES_PER_PAGE;
                int firstX = tileX * PROJECTION_TILE_SIZE;
                int firstZ = tileZ * PROJECTION_TILE_SIZE;
                for (int localZ = 0; localZ < PROJECTION_TILE_SIZE; localZ++) {
                    int offset = (firstZ + localZ) * CaveTextureAtlas.PAGE_SIZE
                            + firstX;
                    System.arraycopy(stagingPixels, offset, destination, offset,
                            PROJECTION_TILE_SIZE);
                }
            }
        }

        private boolean wouldBeProjectionAuthoritative(int projectionTopY,
                int tileMask) {
            int projectedMask = visibleTileMask | tileMask;
            if (projectedMask != (1 << PROJECTION_TILE_COUNT) - 1) return false;
            for (int tile = 0; tile < PROJECTION_TILE_COUNT; tile++) {
                if ((tileMask & (1 << tile)) != 0) continue;
                if (visibleTileProjectionTopY[tile] != projectionTopY) return false;
            }
            return true;
        }

        private void commitStagedTiles(int projectionTopY, int tileMask,
                int[] destination) {
            if (stagingProjectionTopY != projectionTopY || stagingPixels == null) return;
            for (int tile = 0; tile < PROJECTION_TILE_COUNT; tile++) {
                if ((tileMask & (1 << tile)) == 0) continue;
                int tileX = tile % PROJECTION_TILES_PER_PAGE;
                int tileZ = tile / PROJECTION_TILES_PER_PAGE;
                int firstX = tileX * PROJECTION_TILE_SIZE;
                int firstZ = tileZ * PROJECTION_TILE_SIZE;
                long rowMask = PROJECTION_TILE_ROW_MASK << firstX;
                for (int localZ = 0; localZ < PROJECTION_TILE_SIZE; localZ++) {
                    int row = firstZ + localZ;
                    int offset = row * CaveTextureAtlas.PAGE_SIZE + firstX;
                    System.arraycopy(stagingPixels, offset, destination, offset,
                            PROJECTION_TILE_SIZE);
                    knownRows[row] |= rowMask;
                }
                visibleTileProjectionTopY[tile] = projectionTopY;
                visibleTileMask |= 1 << tile;
            }
            knownColumns = countKnownColumns(knownRows);
            if (isProjectionAuthoritative(projectionTopY)) {
                publishedProjectionTopY = projectionTopY;
            }
        }

        private int candidateProjectionKnownRows(int projectionTopY,
                int stagedTileMask, long[] destination) {
            java.util.Arrays.fill(destination, 0L);
            int candidateTileMask = stagedTileMask;
            for (int tile = 0; tile < PROJECTION_TILE_COUNT; tile++) {
                if ((visibleTileMask & (1 << tile)) != 0
                        && visibleTileProjectionTopY[tile] == projectionTopY) {
                    candidateTileMask |= 1 << tile;
                }
            }
            for (int tile = 0; tile < PROJECTION_TILE_COUNT; tile++) {
                if ((candidateTileMask & (1 << tile)) == 0) continue;
                int tileX = tile % PROJECTION_TILES_PER_PAGE;
                int tileZ = tile / PROJECTION_TILES_PER_PAGE;
                long rowMask = PROJECTION_TILE_ROW_MASK
                        << (tileX * PROJECTION_TILE_SIZE);
                int firstRow = tileZ * PROJECTION_TILE_SIZE;
                for (int localZ = 0; localZ < PROJECTION_TILE_SIZE; localZ++) {
                    destination[firstRow + localZ] |= rowMask;
                }
            }
            return countKnownColumns(destination);
        }

        private boolean markBranchCandidate(long sourceRevision, int projectionTopY,
                int tileMask, int candidateKnownColumns, boolean authoritative) {
            long normalizedRevision = CaveCacheSchema.canonicalContentRevision(sourceRevision);
            if (branchCandidateSourceRevision == normalizedRevision
                    && branchCandidateProjectionTopY == projectionTopY
                    && branchCandidateTileMask == tileMask
                    && branchCandidateKnownColumns == candidateKnownColumns
                    && branchCandidateAuthoritative == authoritative) {
                return false;
            }
            branchCandidateSourceRevision = normalizedRevision;
            branchCandidateProjectionTopY = projectionTopY;
            branchCandidateTileMask = tileMask;
            branchCandidateKnownColumns = candidateKnownColumns;
            branchCandidateAuthoritative = authoritative;
            return true;
        }

        private void resetProjectionStaging(int projectionTopY) {
            stagingProjectionTopY = projectionTopY;
            java.util.Arrays.fill(stagingKnownRows, 0L);
            stagingKnownColumns = 0;
            stagedReadySinceMs = 0L;
            if (stagingPixels != null) java.util.Arrays.fill(stagingPixels, 0);
        }

        private int countKnownColumns(long[] rows) {
            int count = 0;
            for (long row : rows) count += Long.bitCount(row);
            return count;
        }

        private void observeSourceRevision(long sourceRevision, long now) {
            if (observedSourceRevision == sourceRevision) return;
            if (sourceRevisionChangedMs == 0L || sourceBurstStartedMs == 0L
                    || now - sourceBurstStartedMs >= SOURCE_BURST_MAX_MS
                    || now - sourceRevisionChangedMs > SOURCE_QUIET_MS * 2L) {
                sourceBurstStartedMs = now;
            }
            observedSourceRevision = sourceRevision;
            sourceRevisionChangedMs = now;
        }

        private void restartSourceSettleWindow(long sourceRevision, long now) {
            observedSourceRevision = sourceRevision;
            sourceRevisionChangedMs = now;
            sourceBurstStartedMs = now;
        }

        private long sourceSettleDeadlineMs() {
            if (sourceRevisionChangedMs == 0L) return 0L;
            long quietDeadline = sourceRevisionChangedMs + SOURCE_QUIET_MS;
            long burstDeadline = sourceBurstStartedMs == 0L
                    ? quietDeadline : sourceBurstStartedMs + SOURCE_BURST_MAX_MS;
            return Math.min(quietDeadline, burstDeadline);
        }

        private boolean isSourceSettled(long now) {
            return now >= sourceSettleDeadlineMs();
        }

        private void markSourceSettled(long sourceRevision) {
            observedSourceRevision = sourceRevision;
            sourceRevisionChangedMs = 0L;
            sourceBurstStartedMs = 0L;
        }

        private void installRegionImage(int projectionTopY, int[] regionPixels,
                int localPageX, int localPageZ,
                CaveEmptyProof cachedEmptyProof) {
            beginProjectionTransition(projectionTopY);
            ensureBuffers();
            int sourceX = localPageX * CaveTextureAtlas.PAGE_SIZE;
            int sourceZ = localPageZ * CaveTextureAtlas.PAGE_SIZE;
            boolean hasContent = false;
            for (int row = 0; row < CaveTextureAtlas.PAGE_SIZE; row++) {
                int sourceOffset = (sourceZ + row)
                        * CaveRegionImageCache.REGION_PIXELS + sourceX;
                int destinationOffset = row * CaveTextureAtlas.PAGE_SIZE;
                System.arraycopy(regionPixels, sourceOffset, frontLods[0],
                        destinationOffset, CaveTextureAtlas.PAGE_SIZE);
                if (!hasContent) {
                    for (int column = 0; column < CaveTextureAtlas.PAGE_SIZE; column++) {
                        if (frontLods[0][destinationOffset + column] != 0) {
                            hasContent = true;
                            break;
                        }
                    }
                }
            }
            for (int lod = 1; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
                downsample(frontLods[lod - 1], CaveTextureAtlas.lodSize(lod - 1),
                        frontLods[lod], CaveTextureAtlas.lodSize(lod));
            }
            java.util.Arrays.fill(knownRows, -1L);
            knownColumns = CaveTextureAtlas.PAGE_SIZE * CaveTextureAtlas.PAGE_SIZE;
            visibleTileMask = (1 << PROJECTION_TILE_COUNT) - 1;
            java.util.Arrays.fill(visibleTileProjectionTopY, projectionTopY);
            publishedProjectionTopY = key.view() == CaveView.FULL
                    ? Integer.MIN_VALUE : projectionTopY;
            activeProjectionTopY = key.view() == CaveView.FULL
                    ? Integer.MIN_VALUE : projectionTopY;
            resetProjectionStaging(projectionTopY);
            regionImageFallback = true;
            emptyProof = cachedEmptyProof == null
                    ? CaveEmptyProof.NONE : cachedEmptyProof;
            knownEmpty = !hasContent && emptyProof.strong();
            if (!hasContent && !knownEmpty) {
                java.util.Arrays.fill(knownRows, 0L);
                knownColumns = 0;
                visibleTileMask = 0;
                regionImageFallback = false;
            }
            nextRetryMs = 0L;
            nextPublicationAttemptMs = 0L;
        }

        private void ensureBuffers() {
            if (frontLods != null) return;
            frontLods = allocateLodBuffers();
        }

        private void releaseAtlasSlot() {
            boolean wasResident = initialized && atlasSlot >= 0;
            if (wasResident) indexResidentPage(key, -1);
            removePageTable(this);
            if (atlasSlot >= 0) {
                if (wasResident) atlas.releasePublishedSlot(atlasSlot);
                else atlas.releaseSlot(atlasSlot);
            }
            if (wasResident) {
                MapResidencyManager.getInstance().remove(residencyKey(key));
                exactTopologyRevision.incrementAndGet();
            }
            atlasSlot = -1;
            initialized = false;
            if (wasResident) {
                ExactPageStateTracker.getInstance().transition(
                        stateKey(key), ExactPageState.GPU_EVICTED,
                        pendingLane, revisions.getOrDefault(key, uploadedRevision));
            }
        }

        private void close() {
            if (pendingToken != null) pendingToken.cancel();
            if (pending != null && pending.cancel(false)) {
                pipelineTelemetry.recordTaskCancelledBeforeRun();
            }
            pending = null;
            pendingToken = null;
            pendingLane = null;
            pendingProjectionTopY = Integer.MIN_VALUE;
            pendingCompletionRecorded = false;
            nextPublicationAttemptMs = 0L;
            stagedReadySinceMs = 0L;
            gpuReservationFailures = 0;
            releaseAtlasSlot();
            frontLods = null;
            stagingPixels = null;
            java.util.Arrays.fill(stagingKnownRows, 0L);
            java.util.Arrays.fill(visibleTileProjectionTopY, Integer.MIN_VALUE);
            visibleTileMask = 0;
            stagingKnownColumns = 0;
            stagingProjectionTopY = Integer.MIN_VALUE;
            activeProjectionTopY = Integer.MIN_VALUE;
            regionImageFallback = false;
        }
    }

    private static int[][] allocateLodBuffers() {
        int[][] buffers = new int[CaveTextureAtlas.LOD_COUNT][];
        for (int lod = 0; lod < buffers.length; lod++) {
            int size = CaveTextureAtlas.lodSize(lod);
            buffers[lod] = new int[size * size];
        }
        return buffers;
    }

    /**
     * One bounding dirty rectangle per LOD. Exact Cave pages are at most 64x64, so
     * one slightly larger transfer is cheaper and more stable than up to eight GL
     * subuploads plus gutter repairs.
     */
    private static final class DirtyPlan {
        private int minX;
        private int minY;
        private int maxX;
        private int maxY;
        private int count;

        private void compute(int[] previous, int[] current, int size) {
            count = 0;
            if (previous == null) {
                setBounding(0, 0, size - 1, size - 1);
                return;
            }

            int boundMinX = size;
            int boundMinY = size;
            int boundMaxX = -1;
            int boundMaxY = -1;
            for (int y = 0; y < size; y++) {
                int row = y * size;
                for (int x = 0; x < size; x++) {
                    if (previous[row + x] == current[row + x]) continue;
                    boundMinX = Math.min(boundMinX, x);
                    boundMaxX = Math.max(boundMaxX, x);
                    boundMinY = Math.min(boundMinY, y);
                    boundMaxY = Math.max(boundMaxY, y);
                }
            }
            if (boundMaxX >= boundMinX && boundMaxY >= boundMinY) {
                setBounding(boundMinX, boundMinY, boundMaxX, boundMaxY);
            }
        }

        private void setBounding(int x0, int y0, int x1, int y1) {
            minX = x0;
            minY = y0;
            maxX = x1;
            maxY = y1;
            count = x1 >= x0 && y1 >= y0 ? 1 : 0;
        }

        private int count() {
            return count;
        }

        private CaveTextureAtlas.DirtyRect rect(int index) {
            if (index != 0 || count == 0) {
                throw new IndexOutOfBoundsException(index);
            }
            return new CaveTextureAtlas.DirtyRect(minX, minY, maxX, maxY);
        }
    }

    public record DebugSnapshot(int pages, int requests, int pendingBuilds,
            int completedBuilds, int initializedPages, int partialPages,
            int knownEmptyPages, int residentPages, int fullscreenSlice,
            int fullscreenPlanPages) {
        public static DebugSnapshot empty() {
            return new DebugSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private record BuildOwnership(MapRequestLane lane, int priority,
            int fullscreenOrdinal) {
    }

    private record CompletedBuild(PageInfo info,
            CompletableFuture<BuildResult> future,
            MapRequestLane lane,
            int fullscreenOrdinal,
            long sequence,
            CavePresentationGeneration presentationGeneration)
            implements Comparable<CompletedBuild> {
        @Override
        public int compareTo(CompletedBuild other) {
            int thisRank = lane == null ? 0 : lane.rank();
            int otherRank = other.lane == null ? 0 : other.lane.rank();
            int byLane = Integer.compare(otherRank, thisRank);
            if (byLane != 0) return byLane;
            if (lane == MapRequestLane.FULLSCREEN
                    && other.lane == MapRequestLane.FULLSCREEN) {
                int byOrdinal = Integer.compare(fullscreenOrdinal,
                        other.fullscreenOrdinal);
                if (byOrdinal != 0) return byOrdinal;
            }
            return Long.compare(sequence, other.sequence);
        }
    }

    private enum PublicationDeferral {
        NONE,
        ATLAS_SLOT,
        GPU_BUDGET,
        COALESCE
    }

    private record ApplyOutcome(boolean applied, boolean hasContent,
            PublicationDeferral deferral) {
        private static final ApplyOutcome NOT_APPLIED =
                new ApplyOutcome(false, false, PublicationDeferral.NONE);
        private static final ApplyOutcome ATLAS_DEFERRED =
                new ApplyOutcome(false, true, PublicationDeferral.ATLAS_SLOT);
        private static final ApplyOutcome GPU_DEFERRED =
                new ApplyOutcome(false, true, PublicationDeferral.GPU_BUDGET);
        private static final ApplyOutcome COALESCED =
                new ApplyOutcome(false, true, PublicationDeferral.COALESCE);
        private static final ApplyOutcome APPLIED_EMPTY =
                new ApplyOutcome(true, false, PublicationDeferral.NONE);
        private static final ApplyOutcome APPLIED_CONTENT =
                new ApplyOutcome(true, true, PublicationDeferral.NONE);

        private boolean retryablePublication() {
            return deferral != PublicationDeferral.NONE;
        }
    }

    private record BuildResult(long expectedRevision, long sourceRevision,
            int projectionTopY, int[] pixels, long[] knownRows,
            int knownColumns, boolean complete, CaveEmptyProof emptyProof,
            boolean superseded,
            boolean regionImported) {
        private BuildResult {
            emptyProof = emptyProof == null ? CaveEmptyProof.NONE : emptyProof;
        }
        private static BuildResult superseded(long expectedRevision,
                long sourceRevision, int projectionTopY) {
            return new BuildResult(expectedRevision, sourceRevision,
                    projectionTopY, null, null, 0, false,
                    CaveEmptyProof.NONE, true, false);
        }
    }
}
