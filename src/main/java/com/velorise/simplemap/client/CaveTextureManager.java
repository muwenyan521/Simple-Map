package com.velorise.simplemap.client;

import com.velorise.simplemap.client.cave.CaveAtlasRegion;
import com.velorise.simplemap.client.cave.CaveView;
import com.velorise.simplemap.client.cave.DenseCaveTile;
import com.velorise.simplemap.client.cave.UnifiedCaveTextureManager;
import com.velorise.simplemap.client.gpu.TileKey;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.atomic.AtomicLong;

/** Compatibility facade for the unified page-only cave texture pipeline. */
public final class CaveTextureManager {
    private static final CaveTextureManager INSTANCE = new CaveTextureManager();
    private final UnifiedCaveTextureManager unified = UnifiedCaveTextureManager.getInstance();
    private final AtomicLong handoffRevision = new AtomicLong();
    private volatile int activeLayerY = Integer.MIN_VALUE;
    private volatile int previousLayerY = Integer.MIN_VALUE;
    private volatile boolean layerHandoffPending;
    private volatile long layerHandoffStartedMs;
    private volatile int layerHandoffReadyPasses;

    /*
     * Xaero-style minimap layer lifecycle. activeLayerY is the detector/requested
     * projection, while minimapWriterLayerY is the stable layer that is allowed to
     * consume CPU projection work. One previous writer layer is retained as a
     * per-page visual fallback while the new writer replaces it centre-out.
     */
    private volatile int minimapWriterLayerY = Integer.MIN_VALUE;
    private volatile int minimapPreviousLayerY = Integer.MIN_VALUE;
    private volatile long minimapWriterCommittedMs;
    /** 0 = full viewport, 1 = centre-first, 2 = near window. */
    private volatile int minimapTransitionPhase;

    private static final int HANDOFF_STABLE_PASSES = 2;
    private static final int HANDOFF_SAMPLE_CAP = 256;
    private static final float HANDOFF_READY_RATIO = 0.92f;
    private static final long MINIMAP_CENTER_PHASE_MAX_MS = 250L;
    private static final long MINIMAP_NEAR_PHASE_MAX_MS = 700L;
    private static final double MINIMAP_CENTER_RADIUS_BLOCKS = MapPageLayout.PAGE_SIZE;
    private static final double MINIMAP_NEAR_RADIUS_BLOCKS = MapPageLayout.PAGE_SIZE * 2.0;
    private static final int[][] MINIMAP_CORE_OFFSETS = {
            {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    private CaveTextureManager() {
    }

    public static CaveTextureManager getInstance() {
        return INSTANCE;
    }

    public void markRegionTextureDirty(int layerY, int rx, int rz) {
        unified.markRegionDirty(CaveView.LAYERED, layerY, rx, rz);
    }

    public void requestVisiblePages(int layerY, double minX, double maxX,
            double minZ, double maxZ) {
        requestVisiblePages(layerY, minX, maxX, minZ, maxZ, 1.0f);
    }

    public void requestVisiblePages(int layerY, double minX, double maxX,
            double minZ, double maxZ, float scale) {
        requestVisiblePages(layerY, minX, maxX, minZ, maxZ, scale,
                MapRequestLane.FULLSCREEN);
    }

    public void requestVisiblePages(int layerY, double minX, double maxX,
            double minZ, double maxZ, float scale, MapRequestLane lane) {
        requestVisiblePages(layerY, minX, maxX, minZ, maxZ, scale,
                (minX + maxX) * 0.5, (minZ + maxZ) * 0.5, lane);
    }

    public void requestVisiblePages(int layerY, double minX, double maxX,
            double minZ, double maxZ, float scale,
            double focusX, double focusZ, MapRequestLane lane) {
        if (activeLayerY != layerY) onLayerActivated(layerY);
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        int requestedLayerY = layerY;
        double requestMinX = minX;
        double requestMaxX = maxX;
        double requestMinZ = minZ;
        double requestMaxZ = maxZ;
        if (effectiveLane == MapRequestLane.MINIMAP) {
            requestedLayerY = selectMinimapWriterLayer(layerY, focusX, focusZ);
            int phase = advanceMinimapTransition(requestedLayerY, focusX, focusZ);
            if (phase == 1) {
                requestMinX = Math.max(minX, focusX - MINIMAP_CENTER_RADIUS_BLOCKS);
                requestMaxX = Math.min(maxX, focusX + MINIMAP_CENTER_RADIUS_BLOCKS);
                requestMinZ = Math.max(minZ, focusZ - MINIMAP_CENTER_RADIUS_BLOCKS);
                requestMaxZ = Math.min(maxZ, focusZ + MINIMAP_CENTER_RADIUS_BLOCKS);
            } else if (phase == 2) {
                requestMinX = Math.max(minX, focusX - MINIMAP_NEAR_RADIUS_BLOCKS);
                requestMaxX = Math.min(maxX, focusX + MINIMAP_NEAR_RADIUS_BLOCKS);
                requestMinZ = Math.max(minZ, focusZ - MINIMAP_NEAR_RADIUS_BLOCKS);
                requestMaxZ = Math.min(maxZ, focusZ + MINIMAP_NEAR_RADIUS_BLOCKS);
            }
        }
        unified.requestVisiblePages(CaveView.LAYERED, requestedLayerY,
                requestMinX, requestMaxX, requestMinZ, requestMaxZ,
                scale, focusX, focusZ, effectiveLane);
        if (effectiveLane != MapRequestLane.MINIMAP) {
            observeLayerHandoff(layerY, minX, maxX, minZ, maxZ,
                    focusX, focusZ, effectiveLane);
        }
    }

    public void requestVisibleRegion(int layerY, int rx, int rz) {
        if (activeLayerY != layerY) onLayerActivated(layerY);
        unified.requestRegion(CaveView.LAYERED, layerY, rx, rz);
    }

    public synchronized void onLayerActivated(int layerY) {
        if (activeLayerY == layerY) return;
        int oldLayer = activeLayerY;
        boolean retainedBand = sameBand(oldLayer, layerY);
        /*
         * PASS159 / Xaero loadingCaving vs loadedCaving:
         * crossing a 16-block band is still a layer transition. Keep the last
         * displayed layer as a visual underlay until the new layer has enough exact
         * coverage instead of blanking the fullscreen map immediately.
         */
        previousLayerY = oldLayer == Integer.MIN_VALUE
                ? Integer.MIN_VALUE : oldLayer;
        activeLayerY = layerY;
        layerHandoffPending = previousLayerY != Integer.MIN_VALUE;
        layerHandoffStartedMs = layerHandoffPending
                ? System.currentTimeMillis() : 0L;
        layerHandoffReadyPasses = 0;
        // Exact Top-Y changes inside one 16-block band reuse the same retained
        // geometry/cache universe. Unified publication revisions will refresh the
        // pixels; do not invalidate the minimap plan merely because Y moved by one.
        if (!retainedBand) handoffRevision.incrementAndGet();
    }

    public long contentRevision() {
        return unified.contentRevision() + handoffRevision.get();
    }

    public long branchContentRevision() {
        return unified.branchContentRevision() + handoffRevision.get();
    }

    public void beginRenderBatch() {
        unified.beginRenderBatch();
    }

    public void endRenderBatch() {
        unified.endRenderBatch();
    }

    public CaveAtlasRegion peekPageRegion(int layerY, int rx, int rz,
            int pageX, int pageZ, float scale) {
        return peekPageRegion(layerY, rx, rz, pageX, pageZ, scale,
                MapRequestLane.FULLSCREEN);
    }

    public CaveAtlasRegion peekPageRegion(int layerY, int rx, int rz,
            int pageX, int pageZ, float scale, MapRequestLane lane) {
        if (activeLayerY != layerY) return null;
        return unified.peekPageRegion(CaveView.LAYERED,
                layerY, rx, rz, pageX, pageZ, scale);
    }

    /** One retained previous-layer page used only as a minimap visual underlay. */
    public PageSelection peekFallbackPage(int layerY, int rx, int rz,
            int pageX, int pageZ, float scale, MapRequestLane lane) {
        if ((lane != MapRequestLane.MINIMAP && lane != MapRequestLane.FULLSCREEN)
                || activeLayerY != layerY) return null;
        int globalPageX = (rx << 3) + pageX;
        int globalPageZ = (rz << 3) + pageZ;
        int first = fallbackLayer(layerY, true, lane);
        PageSelection selection = fallbackPageSelection(first, layerY, rx, rz,
                pageX, pageZ, globalPageX, globalPageZ, scale);
        if (selection == null) {
            int second = fallbackLayer(layerY, false, lane);
            selection = fallbackPageSelection(second, layerY, rx, rz,
                    pageX, pageZ, globalPageX, globalPageZ, scale);
        }
        unified.recordCavePageHole(CaveView.LAYERED, layerY,
                globalPageX, globalPageZ, selection != null);
        return selection;
    }

    public TileKey pageTileKey(int layerY, int globalPageX,
            int globalPageZ, float scale) {
        return pageTileKey(layerY, globalPageX, globalPageZ, scale,
                MapRequestLane.FULLSCREEN);
    }

    public TileKey pageTileKey(int layerY, int globalPageX,
            int globalPageZ, float scale, MapRequestLane lane) {
        if (activeLayerY != layerY) return null;
        return unified.pageTileKey(CaveView.LAYERED, layerY,
                globalPageX, globalPageZ, scale);
    }

    public CaveAtlasRegion peekBranchRegion(int layerY, int rx, int rz) {
        return peekBranchRegion(layerY, 1, rx, rz);
    }

    public CaveAtlasRegion peekBranchRegion(int layerY, int level,
            int nodeX, int nodeZ) {
        return peekBranchRegion(layerY, level, nodeX, nodeZ,
                MapRequestLane.FULLSCREEN);
    }

    public CaveAtlasRegion peekBranchRegion(int layerY, int level,
            int nodeX, int nodeZ, MapRequestLane lane) {
        if (activeLayerY != layerY) return null;
        CaveAtlasRegion current = unified.peekBranchRegion(CaveView.LAYERED,
                layerY, level, nodeX, nodeZ);
        if (current != null
                || lane != MapRequestLane.MINIMAP
                        && lane != MapRequestLane.FULLSCREEN) return current;
        int first = fallbackLayer(layerY, true, lane);
        if (validFallback(first, layerY)) {
            CaveAtlasRegion fallback = unified.peekBranchRegion(CaveView.LAYERED,
                    first, level, nodeX, nodeZ);
            if (fallback != null) return fallback;
        }
        int second = fallbackLayer(layerY, false, lane);
        if (validFallback(second, layerY)) {
            return unified.peekBranchRegion(CaveView.LAYERED,
                    second, level, nodeX, nodeZ);
        }
        return null;
    }

    public boolean hasBranchData(int layerY, int level, int nodeX, int nodeZ) {
        return hasBranchData(layerY, level, nodeX, nodeZ,
                MapRequestLane.FULLSCREEN);
    }

    public boolean hasBranchData(int layerY, int level, int nodeX, int nodeZ,
            MapRequestLane lane) {
        if (activeLayerY != layerY) return false;
        if (unified.hasBranchData(CaveView.LAYERED, layerY,
                level, nodeX, nodeZ)) return true;
        if (lane != MapRequestLane.MINIMAP
                && lane != MapRequestLane.FULLSCREEN) return false;
        int first = fallbackLayer(layerY, true, lane);
        if (validFallback(first, layerY)
                && unified.hasBranchData(CaveView.LAYERED, first,
                        level, nodeX, nodeZ)) return true;
        int second = fallbackLayer(layerY, false, lane);
        return validFallback(second, layerY)
                && unified.hasBranchData(CaveView.LAYERED, second,
                        level, nodeX, nodeZ);
    }

    public boolean hasResidentPageInNode(int layerY, int level,
            int nodeX, int nodeZ) {
        return hasResidentPageInNode(layerY, level, nodeX, nodeZ,
                MapRequestLane.FULLSCREEN);
    }

    public boolean hasResidentPageInNode(int layerY, int level,
            int nodeX, int nodeZ, MapRequestLane lane) {
        if (activeLayerY != layerY) return false;
        if (unified.hasResidentPageInNode(CaveView.LAYERED,
                layerY, level, nodeX, nodeZ)) return true;
        if (lane != MapRequestLane.MINIMAP
                && lane != MapRequestLane.FULLSCREEN) return false;
        int first = fallbackLayer(layerY, true, lane);
        if (validFallback(first, layerY)
                && unified.hasResidentPageInNode(CaveView.LAYERED, first,
                        level, nodeX, nodeZ)) return true;
        int second = fallbackLayer(layerY, false, lane);
        return validFallback(second, layerY)
                && unified.hasResidentPageInNode(CaveView.LAYERED, second,
                        level, nodeX, nodeZ);
    }

    public boolean allowFullscreenExact(int layerY,
            int globalPageX, int globalPageZ) {
        return activeLayerY == layerY
                && unified.allowFullscreenExact(CaveView.LAYERED, layerY,
                        globalPageX, globalPageZ);
    }

    public ResourceLocation peekPageTexture(int layerY, int rx, int rz,
            int pageX, int pageZ) {
        CaveAtlasRegion region = peekPageRegion(layerY, rx, rz, pageX, pageZ, 1.0f);
        return region == null ? null : region.texture();
    }

    public boolean hasAnyPageTexture(int layerY, int rx, int rz) {
        return activeLayerY == layerY
                && unified.hasAnyPage(CaveView.LAYERED, layerY, rx, rz);
    }

    /** Region textures were removed; exact 64x64 pages are the foreground unit. */
    public ResourceLocation getRegionTexture(int layerY, int rx, int rz) {
        requestVisibleRegion(layerY, rx, rz);
        return null;
    }

    public ResourceLocation peekRegionTexture(int layerY, int rx, int rz) {
        return null;
    }

    public void uploadDirtyTextures() {
        uploadDirtyTextures(false);
    }

    public void uploadDirtyTextures(boolean force) {
        if (MapActivityGate.getInstance().blocksForegroundStreaming()) return;
        unified.upload(force);
    }

    public void invalidateStyle() {
        unified.invalidateStyle();
    }

    public void clearCache() {
        unified.clear();
        resetViewState();
    }

    /** Resets only the selected-layer handoff state. Dimension-qualified atlas
     * pages remain resident inside the unified cache. */
    public void suspendForDimensionSwitch() {
        resetViewState();
    }

    private synchronized void resetViewState() {
        activeLayerY = Integer.MIN_VALUE;
        previousLayerY = Integer.MIN_VALUE;
        layerHandoffPending = false;
        layerHandoffStartedMs = 0L;
        layerHandoffReadyPasses = 0;
        minimapWriterLayerY = Integer.MIN_VALUE;
        minimapPreviousLayerY = Integer.MIN_VALUE;
        minimapWriterCommittedMs = 0L;
        minimapTransitionPhase = 0;
        handoffRevision.incrementAndGet();
    }

    /** Returns the projection Top-Y that is currently allowed to consume minimap writer work. */
    public int projectionLayerForLane(int requestedLayerY, MapRequestLane lane) {
        if (lane == MapRequestLane.MINIMAP
                && minimapWriterLayerY != Integer.MIN_VALUE) {
            return minimapWriterLayerY;
        }
        return requestedLayerY;
    }

    private synchronized int selectMinimapWriterLayer(int desiredLayerY,
            double focusX, double focusZ) {
        long now = System.currentTimeMillis();
        if (minimapWriterLayerY == Integer.MIN_VALUE) {
            commitMinimapWriterLayer(desiredLayerY, now, false);
            return minimapWriterLayerY;
        }
        if (desiredLayerY == minimapWriterLayerY) {
            return minimapWriterLayerY;
        }

        /*
         * PASS156: the selected cave Top-Y is one shared map state. PASS155 delayed
         * the MINIMAP writer target while moving, so fullscreen could already be
         * requesting/rendering Top-Y N while the minimap was still spending source
         * work on Top-Y N-1. Keep only the visual fallback delayed: switch the writer
         * target immediately, retain the previous writer layer underneath, and let
         * the existing centre/near transition replace it coherently.
         */
        int focusPageX = Math.floorDiv((int) Math.floor(focusX),
                MapPageLayout.PAGE_SIZE);
        int focusPageZ = Math.floorDiv((int) Math.floor(focusZ),
                MapPageLayout.PAGE_SIZE);
        boolean alreadyRenderable = unified.isPageProjectionResolved(
                CaveView.LAYERED, desiredLayerY, focusPageX, focusPageZ);
        commitMinimapWriterLayer(desiredLayerY, now, alreadyRenderable);
        MapDebugRecorder.getInstance().event("CAVE_MINIMAP_TARGET_SYNCHRONIZED",
                "top_y=" + desiredLayerY
                        + " cached_center=" + alreadyRenderable
                        + " policy=shared_target_previous_visual_fallback"
                        + " pass=PASS156");
        return minimapWriterLayerY;
    }

    private void commitMinimapWriterLayer(int layerY, long now, boolean cached) {
        int previous = minimapWriterLayerY;
        if (previous == layerY) return;
        minimapPreviousLayerY = previous;
        minimapWriterLayerY = layerY;
        minimapWriterCommittedMs = now;
        minimapTransitionPhase = previous == Integer.MIN_VALUE || cached ? 0 : 1;
        handoffRevision.incrementAndGet();
        MapDebugRecorder.getInstance().event("CAVE_MINIMAP_WRITER_LAYER_COMMIT",
                "previous_top_y=" + previous
                        + " writer_top_y=" + layerY
                        + " cached=" + cached
                        + " phase=" + minimapTransitionPhase);
    }

    private synchronized int advanceMinimapTransition(int writerLayerY,
            double focusX, double focusZ) {
        if (minimapTransitionPhase == 0 || writerLayerY == Integer.MIN_VALUE) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int centerPageX = Math.floorDiv((int) Math.floor(focusX),
                MapPageLayout.PAGE_SIZE);
        int centerPageZ = Math.floorDiv((int) Math.floor(focusZ),
                MapPageLayout.PAGE_SIZE);
        long elapsed = Math.max(0L, now - minimapWriterCommittedMs);
        if (minimapTransitionPhase == 1) {
            boolean centerReady = unified.isPageProjectionResolved(CaveView.LAYERED,
                    writerLayerY, centerPageX, centerPageZ);
            boolean centerFallback = minimapFallbackAvailable(
                    writerLayerY, centerPageX, centerPageZ);
            if (centerReady || centerFallback
                    && elapsed >= MINIMAP_CENTER_PHASE_MAX_MS) {
                minimapTransitionPhase = 2;
                MapDebugRecorder.getInstance().event(
                        "CAVE_MINIMAP_WRITER_WINDOW_EXPANDED",
                        "writer_top_y=" + writerLayerY + " phase=near"
                                + " center_ready=" + centerReady
                                + " fallback_safe=" + centerFallback
                                + " elapsed_ms=" + elapsed);
            }
        }
        if (minimapTransitionPhase == 2) {
            int ready = 0;
            boolean fallbackSafe = true;
            for (int[] offset : MINIMAP_CORE_OFFSETS) {
                int pageX = centerPageX + offset[0];
                int pageZ = centerPageZ + offset[1];
                boolean pageReady = unified.isPageProjectionResolved(
                        CaveView.LAYERED, writerLayerY, pageX, pageZ);
                if (pageReady) ready++;
                else if (!minimapFallbackAvailable(writerLayerY, pageX, pageZ)) {
                    fallbackSafe = false;
                }
            }
            if (fallbackSafe
                    && (ready >= 3 || elapsed >= MINIMAP_NEAR_PHASE_MAX_MS)) {
                minimapTransitionPhase = 0;
                MapDebugRecorder.getInstance().event(
                        "CAVE_MINIMAP_WRITER_WINDOW_EXPANDED",
                        "writer_top_y=" + writerLayerY + " phase=full"
                                + " ready_core=" + ready + "/5"
                                + " fallback_safe=true"
                                + " elapsed_ms=" + elapsed);
            } else if (!fallbackSafe
                    && elapsed >= MINIMAP_NEAR_PHASE_MAX_MS) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String eventKey = "CAVE_MINIMAP_WRITER_EXPANSION_BLOCKED:"
                        + writerLayerY;
                if (recorder.shouldEmitEvent(eventKey, 500L)) {
                    recorder.event("CAVE_MINIMAP_WRITER_EXPANSION_BLOCKED",
                            "writer_top_y=" + writerLayerY
                                    + " ready_core=" + ready + "/5"
                                    + " fallback_safe=false"
                                    + " policy=no_hole_before_full");
                }
            }
        }
        return minimapTransitionPhase;
    }

    private int fallbackLayer(int requestedLayerY, boolean first,
            MapRequestLane lane) {
        if (lane == MapRequestLane.FULLSCREEN) {
            return first && validFallback(previousLayerY, requestedLayerY)
                    ? previousLayerY : Integer.MIN_VALUE;
        }
        return minimapFallbackLayer(requestedLayerY, first);
    }

    private int minimapFallbackLayer(int requestedLayerY, boolean first) {
        int writer = minimapWriterLayerY;
        int previous = minimapPreviousLayerY;
        if (first) return writer != requestedLayerY ? writer : previous;
        if (writer == requestedLayerY || previous == writer) return Integer.MIN_VALUE;
        return previous;
    }

    private static boolean validFallback(int candidate, int requestedLayerY) {
        return candidate != Integer.MIN_VALUE && candidate != requestedLayerY;
    }

    private boolean minimapFallbackAvailable(int requestedLayerY,
            int globalPageX, int globalPageZ) {
        int first = minimapFallbackLayer(requestedLayerY, true);
        if (validFallback(first, requestedLayerY)
                && unified.isPageVisualAvailable(CaveView.LAYERED, first,
                        globalPageX, globalPageZ)) return true;
        int second = minimapFallbackLayer(requestedLayerY, false);
        return validFallback(second, requestedLayerY)
                && unified.isPageVisualAvailable(CaveView.LAYERED, second,
                        globalPageX, globalPageZ);
    }

    private PageSelection fallbackPageSelection(int candidate, int requestedLayerY,
            int rx, int rz, int pageX, int pageZ,
            int globalPageX, int globalPageZ, float scale) {
        if (!validFallback(candidate, requestedLayerY)) return null;
        CaveAtlasRegion region = unified.peekPageRegion(CaveView.LAYERED,
                candidate, rx, rz, pageX, pageZ, scale);
        if (region == null) return null;
        TileKey key = unified.pageTileKey(CaveView.LAYERED, candidate,
                globalPageX, globalPageZ, scale);
        return new PageSelection(region, key);
    }

    public record PageSelection(CaveAtlasRegion region, TileKey tileKey) {
    }

    private synchronized void observeLayerHandoff(int layerY,
            double minX, double maxX, double minZ, double maxZ,
            double focusX, double focusZ, MapRequestLane lane) {
        if (!layerHandoffPending || !sameBand(activeLayerY, layerY)) return;
        int minimumPageX = Math.floorDiv((int) Math.floor(Math.min(minX, maxX)),
                MapPageLayout.PAGE_SIZE);
        int maximumPageX = Math.floorDiv((int) Math.floor(Math.nextDown(
                Math.max(minX, maxX))), MapPageLayout.PAGE_SIZE);
        int minimumPageZ = Math.floorDiv((int) Math.floor(Math.min(minZ, maxZ)),
                MapPageLayout.PAGE_SIZE);
        int maximumPageZ = Math.floorDiv((int) Math.floor(Math.nextDown(
                Math.max(minZ, maxZ))), MapPageLayout.PAGE_SIZE);
        int centerPageX = Math.max(minimumPageX, Math.min(maximumPageX,
                Math.floorDiv((int) Math.floor(focusX), MapPageLayout.PAGE_SIZE)));
        int centerPageZ = Math.max(minimumPageZ, Math.min(maximumPageZ,
                Math.floorDiv((int) Math.floor(focusZ), MapPageLayout.PAGE_SIZE)));

        int width = Math.max(1, maximumPageX - minimumPageX + 1);
        int height = Math.max(1, maximumPageZ - minimumPageZ + 1);
        int total = width * height;
        int stride = lane == MapRequestLane.MINIMAP ? 1
                : Math.max(1, (int) Math.ceil(Math.sqrt(
                        total / (double) HANDOFF_SAMPLE_CAP)));
        int resolved = 0;
        int target = 0;
        for (int pageZ = minimumPageZ; pageZ <= maximumPageZ; pageZ += stride) {
            for (int pageX = minimumPageX; pageX <= maximumPageX; pageX += stride) {
                target++;
                if (unified.isPageProjectionResolved(CaveView.LAYERED,
                        layerY, pageX, pageZ)) resolved++;
            }
        }
        boolean centerResolved = unified.isPageProjectionResolved(
                CaveView.LAYERED, layerY, centerPageX, centerPageZ);
        int required = lane == MapRequestLane.MINIMAP ? target
                : Math.max(1, (int) Math.ceil(target * HANDOFF_READY_RATIO));
        if (centerResolved && resolved >= required) layerHandoffReadyPasses++;
        else layerHandoffReadyPasses = 0;

        if (layerHandoffReadyPasses >= HANDOFF_STABLE_PASSES) {
            int loadedLayer = activeLayerY;
            int unloadedLayer = previousLayerY;
            // Xaero keeps the previous exact caveStart inside the same retained
            // 16-block band until the replacement is ready. UnifiedCaveTextureManager
            // performs an atomic whole-page swap, so this underlay cannot create a
            // mixed 16x16 checkerboard and disappears naturally page by page.
            previousLayerY = Integer.MIN_VALUE;
            layerHandoffPending = false;
            layerHandoffStartedMs = 0L;
            layerHandoffReadyPasses = 0;
            handoffRevision.incrementAndGet();
            MapDebugRecorder.getInstance().event("CAVE_LAYER_HANDOFF",
                    "previous_top_y=" + unloadedLayer
                            + " current_top_y=" + loadedLayer
                            + " center_page=" + centerPageX + ',' + centerPageZ
                            + " ready_samples=" + resolved + '/' + target
                            + " ratio=" + (target == 0 ? 1.0
                                    : (double) resolved / target));
        }
    }

    private static boolean sameBand(int firstTopY, int secondTopY) {
        if (firstTopY == Integer.MIN_VALUE || secondTopY == Integer.MIN_VALUE) {
            return firstTopY == secondTopY;
        }
        return DenseCaveTile.normalizeLayer(CaveView.LAYERED, firstTopY)
                == DenseCaveTile.normalizeLayer(CaveView.LAYERED, secondTopY);
    }
}
