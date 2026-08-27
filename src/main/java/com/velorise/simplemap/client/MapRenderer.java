package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import com.velorise.simplemap.client.cave.CaveAtlasRegion;
import com.velorise.simplemap.client.cave.CaveScreenSpacePolicy;
import com.velorise.simplemap.client.cave.CaveView;
import com.velorise.simplemap.client.cave.DenseCaveTile;
import com.velorise.simplemap.client.cave.SurfaceLodTree;
import com.velorise.simplemap.client.gpu.TileKey;
import com.velorise.simplemap.client.gpu.MapGpuInstancePlan;
import com.velorise.simplemap.client.renderer.LodSelector;
import com.velorise.simplemap.client.pipeline.RevisionStamp;
import com.velorise.simplemap.client.session.MapSessionManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class MapRenderer {
    private static final MapRenderer INSTANCE = new MapRenderer();
    private static final ResourceLocation RED_X_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    "minecraft", "textures/map/decorations/red_x.png");
    private static final float NIGHT_MIN_BRIGHTNESS = 0.22f;
    /** Xaero derives map lighting from DimensionType ambient light instead of a
     * hardcoded universal night floor. Keep the same 0.375 baseline. */
    private static final float XAERO_AMBIENT_BASE = 0.375f;
    /** Far Cave plans must stay bounded even when most selected-level branches are
     * absent and fallback traversal would otherwise expand thousands of nodes. */
    private static final int CAVE_BRANCH_PLAN_MAX_QUADS = 1_024;
    private static final int CAVE_BRANCH_PLAN_MAX_VISITS = 4_096;
    /** Coalesce many exact/branch publications into one immutable plan rebuild.
     * The existing plan remains a valid world-space snapshot while the newest
     * atlas state waits for the next refresh window. */
    private static final long FULLSCREEN_PLAN_REFRESH_NANOS = 125_000_000L;
    private static final long SURFACE_HIERARCHY_PLAN_REFRESH_NANOS = 250_000_000L;
    private static final long SURFACE_PRESSURE_PLAN_REFRESH_NANOS = 400_000_000L;
    private static final long FULLSCREEN_BRANCH_PLAN_REFRESH_NANOS = 300_000_000L;
    /**
     * Surface/cave content revisions can advance many times in one visual frame as
     * worker completions are published. Rebuilding an immutable minimap plan for
     * every revision boxed/sorted tile instances and recreated vertex arrays much
     * faster than the player could perceive. Coalesce those publications while an
     * unchanged plan remains a valid visual snapshot.
     */
    private static final long MINIMAP_PLAN_REFRESH_NANOS = 75_000_000L;
    private static final long MINIMAP_EMPTY_PLAN_REFRESH_NANOS = 33_000_000L;
    private static final long FULLSCREEN_FALLBACK_MAX_NANOS = 1_500_000_000L;
    private static final long FULLSCREEN_EMPTY_FALLBACK_MAX_NANOS = 3_000_000_000L;

    private int lastSurfaceHierarchyLevel = -1;
    private int lastCaveHierarchyLevel = -1;
    private int lastMinimapCaveHierarchyLevel = -1;
    private CachedPlan fullscreenPlan;
    private CachedPlan fullscreenFallbackPlan;
    private long fullscreenFallbackExpiresNanos;
    /**
     * Last non-empty authority plan per mode/layer. Xaero keeps root textures alive
     * while a new leaf set warms; this small LRU supplies the same atomic visual
     * handoff across Surface, Layered Cave and Full Cave transitions.
     */
    private final RenderStats sharedRenderStats = new RenderStats();
    private final net.minecraft.world.item.ItemStack fallbackWaypointStack =
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COMPASS);
    private final Map<String, net.minecraft.world.item.ItemStack> waypointItemStacks =
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, net.minecraft.world.item.ItemStack> eldest) {
                    return size() > 64;
                }
            };
    private final Map<StablePlanKey, CachedPlan> stableFullscreenPlans =
            new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<StablePlanKey, CachedPlan> eldest) {
                    return size() > 8;
                }
            };
    private CachedPlan minimapPlan;
    private CachedPlan minimapStagingPlan;
    private int regionFallbackVisitsRemaining;
    private long minimapStagingSinceNanos;
    private long lastMinimapPlanBuildNanos;
    /** Render-thread scratch for at most 24 deduplicated loading cells. */
    private final long[] loadingCellScratch = new long[24];
    private static final int[] SPINNER_X = { 0, 1, 1, 1, 0, -1, -1, -1 };
    private static final int[] SPINNER_Y = { -1, -1, 0, 1, 1, 1, 0, -1 };


    public static MapRenderer getInstance() {
        return INSTANCE;
    }

    private MapRenderer() {
    }

    /**
     * Renders the map in the specified viewport on the screen.
     *
     * @param guiGraphics The GUI graphics instance
     * @param viewportX   Left edge of the viewport
     * @param viewportY   Top edge of the viewport
     * @param width       Width of the viewport
     * @param height      Height of the viewport
     * @param centerX     World X coordinate to center the map on
     * @param centerZ     World Z coordinate to center the map on
     * @param scale       Zoom scale factor (pixels per block)
     * @param drawPlayer  Whether to render the player marker at the player's actual
     *                    position
     */
    public void drawMap(GuiGraphics guiGraphics, int viewportX, int viewportY, int width, int height,
            double centerX, double centerZ, float scale, boolean drawPlayer, boolean rotateWithPlayer,
            boolean isMinimap, double mouseWorldX, double mouseWorldZ, float partialTick) {
        drawMap(guiGraphics, viewportX, viewportY, width, height, centerX, centerZ, scale,
                drawPlayer, rotateWithPlayer, isMinimap, mouseWorldX, mouseWorldZ,
                partialTick, false);
    }

    /**
     * The trailing interaction flag is retained for source compatibility only.
     * Visible scan, IO and publication are tick-side and continue during panning.
     */
    public void drawMap(GuiGraphics guiGraphics, int viewportX, int viewportY, int width, int height,
            double centerX, double centerZ, float scale, boolean drawPlayer, boolean rotateWithPlayer,
            boolean isMinimap, double mouseWorldX, double mouseWorldZ, float partialTick,
            boolean cachedOnly) {
        float policyScale = MapRenderScalePolicy.physicalPixelsPerBlock(scale,
                Minecraft.getInstance().getWindow().getGuiScale());
        drawMapInternal(guiGraphics, viewportX, viewportY, width, height, centerX, centerZ, scale,
                drawPlayer, rotateWithPlayer, isMinimap, mouseWorldX, mouseWorldZ, partialTick,
                cachedOnly, true, 1.0f, policyScale, scale, true, true);
    }

    /**
     * Off-screen minimap path. The framebuffer already clips to its own extent, so
     * GuiGraphics' window-relative scissor must stay disabled here. Live pin
     * navigation is intentionally excluded and drawn after composition, preventing
     * exact player motion from invalidating the retained atlas target.
     */
    MapDrawResult drawMapOffscreen(GuiGraphics guiGraphics, int width, int height,
            double centerX, double centerZ, float renderPixelsPerBlock,
            float policyPixelsPerBlock, boolean drawPlayer,
            boolean rotateWithPlayer, float partialTick, float fixedOverlayScale) {
        return drawMapInternal(guiGraphics, 0, 0, width, height, centerX, centerZ,
                renderPixelsPerBlock, drawPlayer, rotateWithPlayer, true, 0.0, 0.0,
                partialTick, false, false, fixedOverlayScale, policyPixelsPerBlock,
                policyPixelsPerBlock, true, false);
    }

    /**
     * Fullscreen surface composition entry used by the pixel-aligned framebuffer.
     * It retains fullscreen scheduling/LOD semantics while disabling only the
     * window-relative scissor because the framebuffer clips to its own extent.
     */
    MapDrawResult drawFullscreenMapOffscreen(GuiGraphics guiGraphics, int width, int height,
            double centerX, double centerZ, float renderPixelsPerBlock,
            float policyPixelsPerBlock, float cavePolicyPixelsPerBlock,
            float partialTick) {
        return drawMapInternal(guiGraphics, 0, 0, width, height, centerX, centerZ,
                renderPixelsPerBlock, false, false, false, 0.0, 0.0,
                partialTick, false, false, 1.0f, policyPixelsPerBlock,
                cavePolicyPixelsPerBlock, false, false);
    }

    /**
     * Draws dynamic fullscreen markers after the retained terrain quad.
     *
     * <p>Waypoints, hover state, pin navigation and the live player marker are
     * intentionally excluded from the expensive terrain framebuffer. Mouse and
     * player motion therefore remain a small GUI overlay rather than invalidating
     * and replaying all atlas batches.</p>
     */
    void drawFullscreenOverlays(GuiGraphics guiGraphics, int viewportX, int viewportY,
            int width, int height, double centerX, double centerZ, float scale,
            boolean drawPlayer, double mouseWorldX, double mouseWorldZ,
            float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || width <= 0 || height <= 0
                || scale <= 0.0f || !Float.isFinite(scale)) return;

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        guiGraphics.enableScissor(viewportX, viewportY,
                viewportX + width, viewportY + height);
        try {
            poseStack.translate(viewportX + width / 2.0,
                    viewportY + height / 2.0, 0.0);
            poseStack.scale(scale, scale, 1.0f);
            poseStack.translate(-centerX, -centerZ, 0.0);

            double halfW = (width / 2.0) / scale;
            double halfH = (height / 2.0) / scale;
            drawMapOverlays(guiGraphics, mc, poseStack,
                    centerX - halfW, centerX + halfW,
                    centerZ - halfH, centerZ + halfH,
                    scale, false, mouseWorldX, mouseWorldZ,
                    partialTick, 1.0f, true, drawPlayer);
        } finally {
            guiGraphics.disableScissor();
            poseStack.popPose();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private MapDrawResult drawMapInternal(GuiGraphics guiGraphics, int viewportX, int viewportY,
            int width, int height, double centerX, double centerZ, float scale,
            boolean drawPlayer, boolean rotateWithPlayer, boolean isMinimap,
            double mouseWorldX, double mouseWorldZ, float partialTick,
            boolean cachedOnly, boolean manageScissor, float fixedOverlayScale,
            float policyScale, float cavePolicyScale, boolean drawOverlayContent,
            boolean drawPinContent) {

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return MapDrawResult.EMPTY;

        RenderStats renderStats = sharedRenderStats;
        renderStats.reset();
        MapResidencyManager.beginRender(isMinimap
                ? MapRequestLane.MINIMAP : MapRequestLane.FULLSCREEN);
        try {

        boolean caveMode = CaveMode.isActive(mc);
        // Surface raster policy follows physical framebuffer density. Cave hierarchy
        // follows the user's logical map zoom, matching Xaero's texture-level choice
        // and the tick-side CaveScreenSpacePolicy mailbox.
        float activePolicyScale = caveMode ? cavePolicyScale : policyScale;
        boolean fullCaveView = caveMode && CaveMode.isFullView(mc);
        int caveLayerY = caveMode ? CaveMode.getLayerY(mc) : Integer.MIN_VALUE;
        // Renderer is strictly cache-only. Scans, IO, CPU builds and GPU
        // publication are scheduled from MapViewportCoordinator on client tick.
        if (caveMode && !fullCaveView) {
            CaveMapManager.getInstance().setActiveLayer(caveLayerY);
        }

        // Reset shader color to prevent other GUI elements from tinting the map
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();

        // 2. Enable scissor test only for direct window rendering. Off-screen
        // targets use the framebuffer bounds; window-relative GUI scissor coordinates
        // would otherwise clip the 512x512 target incorrectly.
        if (manageScissor) {
            guiGraphics.enableScissor(viewportX, viewportY, viewportX + width, viewportY + height);
        }

        // 3. Set up the camera transformation:
        // - Translate to the center of the viewport
        poseStack.translate(viewportX + width / 2.0, viewportY + height / 2.0, 0);

        // - Rotate the map coordinate space if requested (so player yaw points UP)
        if (rotateWithPlayer) {
            float playerYaw = net.minecraft.util.Mth.rotLerp(partialTick, mc.player.yRotO, mc.player.getYRot());
            poseStack.mulPose(Axis.ZP.rotationDegrees(-playerYaw - 180.0f));
        }

        // - Scale according to zoom level
        poseStack.scale(scale, scale, 1.0f);
        // - Translate by the negative center coordinates in world space
        poseStack.translate(-centerX, -centerZ, 0);

        // 4. Calculate bounds of visible world coordinates in the viewport
        // Expand bounds by sqrt(2) (1.415x) when rotated to prevent region clipping at
        // corners
        double searchFactor = rotateWithPlayer ? 1.415 : 1.0;
        double halfW = ((width / 2.0) / scale) * searchFactor;
        double halfH = ((height / 2.0) / scale) * searchFactor;
        double minX = centerX - halfW;
        double maxX = centerX + halfW;
        double minZ = centerZ - halfH;
        double maxZ = centerZ + halfH;

        // 5. Determine which 512x512 regions are visible
        // One-block epsilon prevents a region at the exact viewport edge from
        // alternating in/out because of floating-point zoom rounding.
        int minRegionX = (int) Math.floor(minX - 1.0) >> 9;
        int maxRegionX = (int) Math.floor(maxX + 1.0) >> 9;
        int minRegionZ = (int) Math.floor(minZ - 1.0) >> 9;
        int maxRegionZ = (int) Math.floor(maxZ + 1.0) >> 9;
        int minVisiblePageX = Math.floorDiv((int) Math.floor(minX - 1.0),
                MapPageLayout.PAGE_SIZE);
        int maxVisiblePageX = Math.floorDiv((int) Math.floor(maxX + 1.0),
                MapPageLayout.PAGE_SIZE);
        int minVisiblePageZ = Math.floorDiv((int) Math.floor(minZ - 1.0),
                MapPageLayout.PAGE_SIZE);
        int maxVisiblePageZ = Math.floorDiv((int) Math.floor(maxZ + 1.0),
                MapPageLayout.PAGE_SIZE);
        // Surface keeps its exact minimap path. Cave minimap instead shares the
        // density-correct LOD hierarchy with fullscreen, just as Xaero's minimap
        // reads the world-map cache rather than maintaining an unrelated picture.
        int hierarchyLevel;
        if (isMinimap && !caveMode) {
            hierarchyLevel = 0;
        } else {
            /*
             * Surface quality follows texel density first, matching Xaero's useful
             * L0/L1/L2/L3 selection. The old viewport-node budget could force a
             * 0.29x view from density-correct L1 to L2/L3 and made the map look soft.
             * Work volume is controlled by sliced demand/admission, not by silently
             * lowering the visible texture density.
             */
            boolean surfaceCoarseRequired = !caveMode
                    && MapRegionLodPolicy.directProjectionEnabled(
                            activePolicyScale, minVisiblePageX, maxVisiblePageX,
                            minVisiblePageZ, maxVisiblePageZ);
            int candidateLevel = caveMode
                    // Xaero keeps its fullscreen texture hierarchy at L0-L3 and
                    // chooses solely from screen density. A node-count override
                    // made the same nominal zoom look much softer in Simple Map.
                    ? MapLodPolicy.branchLevel(activePolicyScale, 3)
                    : LodSelector.surfaceLevel(activePolicyScale);
            if (surfaceCoarseRequired) {
                // Density alone selects exact L0 at 0.50x and above, but a wide
                // fullscreen viewport can contain far more exact leaves than the
                // finite atlas can retain. Force one hierarchy layer so the M4
                // 512x512 region projection becomes a stable underlay while exact
                // pages refine above it instead of leaving black holes.
                candidateLevel = Math.max(1, candidateLevel);
            }
            if (caveMode) {
                int previousLevel = isMinimap
                        ? lastMinimapCaveHierarchyLevel : lastCaveHierarchyLevel;
                hierarchyLevel = MapLodPolicy.stabilizeCaveBranchLevel(
                        candidateLevel, previousLevel, activePolicyScale);
                if (isMinimap) lastMinimapCaveHierarchyLevel = hierarchyLevel;
                else lastCaveHierarchyLevel = hierarchyLevel;
            } else {
                hierarchyLevel = MapLodPolicy.stabilizeBranchLevel(
                        candidateLevel, lastSurfaceHierarchyLevel, activePolicyScale);
                // The generic density hysteresis may intentionally keep L0 around
                // the 0.50x boundary. It must not suppress the only stable underlay
                // when the viewport exceeds atlas capacity (or scale is already in
                // the coarse range), otherwise 0.48x can remain exact-only forever.
                if (surfaceCoarseRequired) hierarchyLevel = Math.max(1, hierarchyLevel);
                lastSurfaceHierarchyLevel = hierarchyLevel;
            }
        }
        MapRequestLane renderLane = isMinimap
                ? MapRequestLane.MINIMAP : MapRequestLane.FULLSCREEN;
        boolean caveBranchOnly = caveMode
                && CaveScreenSpacePolicy.branchOnly(activePolicyScale, renderLane);
        /*
         * Both visible-map pipelines are anchored to their viewport. The cursor is
         * reserved for coordinate inspection, waypoint interaction and tooltips;
         * it never changes source, exact or branch loading order.
         */
        double attentionX = centerX;
        double attentionZ = centerZ;
        int attentionPageX = Math.floorDiv((int) Math.floor(attentionX),
                MapPageLayout.PAGE_SIZE);
        int attentionPageZ = Math.floorDiv((int) Math.floor(attentionZ),
                MapPageLayout.PAGE_SIZE);

        // Publish viewport to the tick-side coordinator only for direct window
        // rendering. Retained FBO owners refresh the complete guarded demand before
        // invoking this off-screen atlas replay. Publishing here as well alternated
        // guarded and unguarded page rectangles on every redraw, producing two
        // planning generations and two batch submissions per retained frame.
        if (ViewportDemandPublicationPolicy.rendererOwnsDemand(manageScissor)) {
            if (isMinimap) {
                MapViewportCoordinator.getInstance().submitMinimap(
                        minX, maxX, minZ, maxZ, activePolicyScale);
            } else {
                // Surface and cave fullscreen share the same viewport mailbox.
                // SurfaceDemandController owns far-zoom trimming downstream.
                MapViewportCoordinator.getInstance().submitFullscreen(
                        minX, maxX, minZ, maxZ, activePolicyScale,
                        centerX, centerZ, false);
            }
        }

        // Visible data requests are handled by MapViewportCoordinator. Keeping
        // this path free of enqueue loops is what makes dragging/zooming stable.

        // 6. Build or reuse an immutable cache-only render plan. Recursive
        // hierarchy traversal is repeated only when the quantized viewport or
        // global atlas topology changes.
        float mapBrightness = getMapBrightness(mc, partialTick);
        float caveBrightness = getCaveBrightness(mc, partialTick);
        MapTextureManager surfaceTextures = MapTextureManager.getInstance();
        MapOverviewTextureManager overviewTextures = MapOverviewTextureManager.getInstance();
        if (!caveMode) {
            // Xaero keeps the retained root cache hot for the minimap writer too.
            // PASS98 only published the M4 visible window from fullscreen, so the
            // minimap had no stable parent authority when exact pages were cold.
            overviewTextures.setPreferredSurfaceView(activePolicyScale, hierarchyLevel,
                    minVisiblePageX, maxVisiblePageX,
                    minVisiblePageZ, maxVisiblePageZ,
                    attentionPageX, attentionPageZ, renderLane);
        }
        FullCaveTextureManager fullCaveTextures = FullCaveTextureManager.getInstance();
        CaveTextureManager caveTextures = CaveTextureManager.getInstance();
        surfaceTextures.beginRenderBatch();
        overviewTextures.beginRenderBatch();
        fullCaveTextures.beginRenderBatch();
        caveTextures.beginRenderBatch();
        try {
            MapResidencyManager residency = MapResidencyManager.getInstance();
            long topologyRevision = residency.topologyRevision();
            // Cave plans should react to cave exact/branch publication only. The old
            // global residency revision rebuilt a large cave hierarchy whenever an
            // unrelated Surface, minimap halo or legacy texture changed.
            long contentRevision = caveMode
                    ? (fullCaveView ? fullCaveTextures.contentRevision()
                            : caveTextures.contentRevision())
                    : residency.surfaceContentRevision();
            int renderScaleClass = renderScaleClass(caveMode, activePolicyScale);
            /*
             * A branch-only fullscreen plan draws complete top-level branch cells;
             * sub-cell camera movement changes only clipping, not plan contents.
             * Key those plans by the actual covered branch-cell bounds rather than
             * the centre page, which previously forced a rebuild every 64 blocks.
             * Exact-leaf plans, including the minimap, retain visible-page bounds
             * because their logical key set genuinely changes when an edge page
             * enters or leaves the viewport.
             */
            int planMinCellX = minVisiblePageX;
            int planMaxCellX = maxVisiblePageX;
            int planMinCellZ = minVisiblePageZ;
            int planMaxCellZ = maxVisiblePageZ;
            if (caveBranchOnly && hierarchyLevel > 0) {
                int pageSpan = MapLodPolicy.pageSpanForBranch(hierarchyLevel);
                planMinCellX = Math.floorDiv(minVisiblePageX, pageSpan);
                planMaxCellX = Math.floorDiv(maxVisiblePageX, pageSpan);
                planMinCellZ = Math.floorDiv(minVisiblePageZ, pageSpan);
                planMaxCellZ = Math.floorDiv(maxVisiblePageZ, pageSpan);
            }
            int keyAttentionPageX = isMinimap ? attentionPageX : 0;
            int keyAttentionPageZ = isMinimap ? attentionPageZ : 0;
            int keyCaveLayerY = caveMode && !fullCaveView
                    ? (isMinimap
                            ? DenseCaveTile.normalizeLayer(CaveView.LAYERED, caveLayerY)
                            : caveLayerY)
                    : Integer.MIN_VALUE;
            RevisionStamp activeStamp = MapSessionManager.getInstance().activeStamp();
            long planSessionId = activeStamp != null ? activeStamp.sessionId() : 0L;
            PlanKey planKey = new PlanKey(
                    MapManager.getInstance().getDimensionCacheKey(), planSessionId,
                    caveMode, fullCaveView, keyCaveLayerY,
                    minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                    planMinCellX, planMaxCellX, planMinCellZ, planMaxCellZ,
                    hierarchyLevel, caveBranchOnly,
                    renderScaleClass, keyAttentionPageX, keyAttentionPageZ,
                    MapConfig.minimapNightMode);
            long nowNanos = System.nanoTime();
            CachedPlan cachedPlan;
            CachedPlan fallbackPlan = null;
            if (isMinimap) {
                cachedPlan = selectMinimapPlan(planKey, caveMode, fullCaveView,
                        caveLayerY, hierarchyLevel, caveBranchOnly, activePolicyScale,
                        minX, maxX, minZ, maxZ,
                        minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                        minVisiblePageX, maxVisiblePageX,
                        minVisiblePageZ, maxVisiblePageZ,
                        attentionPageX, attentionPageZ,
                        surfaceTextures, overviewTextures,
                        fullCaveTextures, caveTextures,
                        topologyRevision, contentRevision, nowNanos);
            } else {
                fallbackPlan = fullscreenFallbackPlan;
                if (fallbackPlan != null
                        && nowNanos >= fullscreenFallbackExpiresNanos) {
                    fallbackPlan = null;
                    fullscreenFallbackPlan = null;
                    fullscreenFallbackExpiresNanos = 0L;
                }
                if (fallbackPlan != null
                        && (!compatibleFullscreenFallback(fallbackPlan.key(), planKey)
                                || !fallbackPlan.plan().topologyValid(topologyRevision))) {
                    fallbackPlan = null;
                    fullscreenFallbackPlan = null;
                    fullscreenFallbackExpiresNanos = 0L;
                }
                CachedPlan previousPlan = fullscreenPlan;
                if (fallbackPlan == null) {
                    fallbackPlan = stableFullscreenPlan(
                            StablePlanKey.of(planKey), planKey, topologyRevision);
                    if (fallbackPlan != null) {
                        fullscreenFallbackPlan = fallbackPlan;
                        fullscreenFallbackExpiresNanos = nowNanos
                                + FULLSCREEN_FALLBACK_MAX_NANOS;
                    }
                }
                cachedPlan = previousPlan;
                boolean cachedHasPending = cachedPlan != null
                        && cachedPlan.plan().pendingRegions().length > 0;
                boolean keyChanged = cachedPlan == null
                        || !cachedPlan.key().equals(planKey);
                boolean topologyChanged = cachedPlan != null
                        && !cachedPlan.plan().valid(topologyRevision, nowNanos,
                                cachedHasPending);
                boolean contentChanged = cachedPlan != null
                        && cachedPlan.contentRevision() != contentRevision;
                long contentRefreshNanos = fullscreenPlanRefreshNanos(
                        caveMode, caveBranchOnly, hierarchyLevel);
                boolean contentRefreshDue = contentChanged
                        && !cachedPlan.plan().logicalExactCoverage()
                        && (cachedPlan.plan().quadCount() == 0
                                || nowNanos - cachedPlan.plan().builtAtNanos()
                                        >= contentRefreshNanos);
                if (cachedPlan == null || keyChanged || topologyChanged
                        || contentRefreshDue) {
                    long planBuildStartedNanos = System.nanoTime();
                    boolean logicalFullscreenExact = hierarchyLevel == 0
                            && !caveBranchOnly;
                    MapRenderPlan plan = buildRenderPlan(caveMode, fullCaveView,
                            caveLayerY, hierarchyLevel, caveBranchOnly, activePolicyScale,
                            true, true, logicalFullscreenExact,
                            minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                            minVisiblePageX, maxVisiblePageX,
                            minVisiblePageZ, maxVisiblePageZ,
                            attentionPageX, attentionPageZ,
                            surfaceTextures, overviewTextures,
                            fullCaveTextures, caveTextures,
                            MapRequestLane.FULLSCREEN, topologyRevision);
                    long planBuildNanos = System.nanoTime() - planBuildStartedNanos;
                    MapPipelineTelemetry.getInstance().recordRenderPlanBuild(
                            plan.quadCount(), plan.batchCount());
                    recordSlowRenderPlan(planBuildNanos, caveMode, caveBranchOnly,
                            activePolicyScale, hierarchyLevel, plan,
                            minVisiblePageX, maxVisiblePageX,
                            minVisiblePageZ, maxVisiblePageZ);
                    if (fallbackPlan == null && previousPlan != null
                            && compatibleFullscreenFallback(previousPlan.key(), planKey)
                            && previousPlan.plan().topologyValid(topologyRevision)
                            && previousPlan.plan().quadCount() > 0
                            && (plan.quadCount() == 0
                                    || plan.pendingRegions().length > 0)) {
                        // Keep the previous viewport/LOD as a visual underlay while
                        // the target hierarchy warms. World-space vertices remain
                        // correct under the new pose, so overlap survives pan/zoom
                        // instead of flashing to black.
                        fallbackPlan = previousPlan;
                        fullscreenFallbackPlan = previousPlan;
                        fullscreenFallbackExpiresNanos = nowNanos
                                + (plan.quadCount() == 0
                                        ? FULLSCREEN_EMPTY_FALLBACK_MAX_NANOS
                                        : FULLSCREEN_FALLBACK_MAX_NANOS);
                    }
                    cachedPlan = new CachedPlan(planKey, plan, contentRevision);
                    fullscreenPlan = cachedPlan;
                    if (plan.quadCount() > 0) {
                        rememberStableFullscreenPlan(cachedPlan);
                    }
                    if (fullscreenFallbackPlan != null
                            && cachedPlan.plan().quadCount() > 0
                            && cachedPlan.plan().pendingRegions().length == 0) {
                        fallbackPlan = null;
                        fullscreenFallbackPlan = null;
                        fullscreenFallbackExpiresNanos = 0L;
                    }
                } else {
                    MapPipelineTelemetry.getInstance().recordRenderPlanReuse();
                }
            }

            /*
             * Replay base and glow under one render-state scope. Previously each
             * immutable plan call flushed GuiGraphics and queried/restored depth,
             * while the glow pass repeated the blend query. Those synchronous GL
             * reads serialized the render thread despite low GPU utilisation.
             */
            boolean blendWasEnabledForMap = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean depthWasEnabledForMap = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            try {
                if (caveMode) {
                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                    guiGraphics.fill(minRegionX * 512, minRegionZ * 512,
                            (maxRegionX + 1) * 512, (maxRegionZ + 1) * 512,
                            0xFF080A0C);
                }
                // Flush the buffered cave backdrop/earlier GUI exactly once before
                // direct BufferUploader replay starts.
                guiGraphics.flush();
                RenderSystem.setShader(GameRenderer::getPositionTexShader);
                Matrix4f planMatrix = guiGraphics.pose().last().pose();
                float baseBrightness = caveMode ? caveBrightness : mapBrightness;
                RenderSystem.setShaderColor(baseBrightness, baseBrightness,
                        baseBrightness, 1.0F);
                if (fallbackPlan != null) {
                    fallbackPlan.plan().drawBasePrepared(planMatrix);
                }
                cachedPlan.plan().drawBasePrepared(planMatrix);

                if (!caveMode && MapConfig.minimapNightMode != 0
                        && hierarchyLevel == 0) {
                    float glowStrength = MapConfig.minimapNightMode == 2
                            ? 1.0f
                            : Math.max(0.0f, Math.min(1.0f,
                                    (1.0f - mapBrightness)
                                            / (1.0f - NIGHT_MIN_BRIGHTNESS)));
                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F,
                            glowStrength);
                    if (fallbackPlan != null) {
                        fallbackPlan.plan().drawGlowPrepared(planMatrix);
                    }
                    cachedPlan.plan().drawGlowPrepared(planMatrix);
                }
            } finally {
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                if (depthWasEnabledForMap) RenderSystem.enableDepthTest();
                else RenderSystem.disableDepthTest();
                if (blendWasEnabledForMap) {
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                } else {
                    RenderSystem.disableBlend();
                }
            }
            if (!isMinimap) {
                drawLoadingIndicators(guiGraphics,
                        cachedPlan.plan().pendingRegions(), activePolicyScale);
            }
            if (fallbackPlan != null) renderStats.accept(fallbackPlan.plan().result());
            renderStats.accept(cachedPlan.plan().result());
        } finally {
            guiGraphics.flush();
            caveTextures.endRenderBatch();
            fullCaveTextures.endRenderBatch();
            overviewTextures.endRenderBatch();
            surfaceTextures.endRenderBatch();
        }

        if (drawOverlayContent) {
            drawMapOverlays(guiGraphics, mc, poseStack,
                    minX, maxX, minZ, maxZ, scale, isMinimap,
                    mouseWorldX, mouseWorldZ, partialTick,
                    fixedOverlayScale, drawPinContent, drawPlayer);
        }

        // 8. Disable scissor and pop pose
        if (manageScissor) guiGraphics.disableScissor();
        poseStack.popPose();
        MapDrawResult result = renderStats.snapshot();
        String renderProjection = isMinimap
                ? (caveMode ? (fullCaveView ? "MINIMAP_CAVE_FULL"
                        : "MINIMAP_CAVE_LAYERED") : "MINIMAP_SURFACE")
                : (!caveMode ? "SURFACE"
                        : (fullCaveView ? "CAVE_FULL" : "CAVE_LAYERED"));
        MapPipelineTelemetry telemetry = MapPipelineTelemetry.getInstance();
        telemetry.recordRenderContext(renderProjection, hierarchyLevel);
        telemetry.recordRenderResult(result);
        return result;
        } finally {
            MapResidencyManager.endRender();
        }
    }

    private void drawMapOverlays(GuiGraphics guiGraphics, Minecraft mc,
            PoseStack poseStack, double minX, double maxX,
            double minZ, double maxZ, float scale, boolean isMinimap,
            double mouseWorldX, double mouseWorldZ, float partialTick,
            float fixedOverlayScale, boolean drawPinContent,
            boolean drawPlayer) {
        // 6.5. Draw waypoints for the current dimension if enabled
        if (MapConfig.waypointsVisible) {
            java.util.List<WaypointManager.Waypoint> waypoints = WaypointManager.getInstance()
                    .getWaypointsForDimension(
                            MapManager.getInstance().getCurrentDimensionResourceId());
            for (WaypointManager.Waypoint wp : waypoints) {
                boolean isHovered = false;
                if (!isMinimap) {
                    double hoverRadius = waypointHitRadiusWorld(wp, scale);
                    isHovered = Math.abs(mouseWorldX - wp.x) <= hoverRadius
                            && Math.abs(mouseWorldZ - wp.z) <= hoverRadius;
                }
                drawWaypointMarker(guiGraphics, wp, scale, isMinimap, isHovered);
            }
        }

        // 6.6. Draw the live player -> pin route. The full world-space segment is
        // solved before viewport clipping, and dot phase is anchored from the fixed
        // destination backwards. Pan/zoom/offscreen clipping therefore never
        // chooses new guide positions, while player movement still updates the
        // navigation path as expected.
        if (drawPinContent && MapConfig.pinActive) {
            double routePlayerX = net.minecraft.util.Mth.lerp(partialTick,
                    mc.player.xo, mc.player.getX());
            double routePlayerZ = net.minecraft.util.Mth.lerp(partialTick,
                    mc.player.zo, mc.player.getZ());
            PinNavigation.Route route = PinNavigation.currentRoute(
                    routePlayerX, routePlayerZ);
            if (route != null) {
                double minWorldSpacing = 6.0 / Math.max(0.001f, scale);
                PinNavigation.DotRange dots = PinNavigation.visibleDots(route,
                        minX, maxX, minZ, maxZ, minWorldSpacing,
                        PinNavigation.MAX_VISIBLE_ROUTE_DOTS);
                if (!dots.isEmpty()) {
                    int pointerColor = getActualPointerColor(
                            MapConfig.playerPointerColor);
                    int lineColor = isMinimap
                            ? ((pointerColor & 0x00FFFFFF) | 0xCC000000)
                            : pointerColor;
                    for (int index = dots.firstIndex();
                            index <= dots.lastIndex(); index += dots.stride()) {
                        int bx = (int) Math.floor(
                                PinNavigation.dotWorldX(route, index));
                        int bz = (int) Math.floor(
                                PinNavigation.dotWorldZ(route, index));
                        guiGraphics.fill(bx, bz, bx + 1, bz + 1, lineColor);
                    }
                }
            }
        }

        // 6.5. Render pin marker if active and within visible bounds
        if (drawPinContent && MapConfig.pinActive) {
            if (MapConfig.pinWorldX >= minX && MapConfig.pinWorldX <= maxX &&
                    MapConfig.pinWorldZ >= minZ && MapConfig.pinWorldZ <= maxZ) {

                poseStack.pushPose();
                poseStack.translate(MapConfig.pinWorldX, MapConfig.pinWorldZ, 5);

                float baseFactor = isMinimap ? 1.0f : 2.0f;
                float pinScaleFactor = (baseFactor / scale) * MapConfig.pinScale * fixedOverlayScale;
                poseStack.scale(pinScaleFactor, pinScaleFactor, 1.0f);

                guiGraphics.blit(RED_X_TEXTURE, -4, -4, 8, 8,
                        0.0f, 0.0f, 8, 8, 8, 8);

                /*
                 * Keep the distance label inside the exact same world-to-screen
                 * transform as the pin icon. Rendering it later from MapScreen used a
                 * second projection that drifted during pixel-aligned framebuffer
                 * composition, opening animation and zoom interpolation.
                 */
                if (!isMinimap) {
                    double playerX = net.minecraft.util.Mth.lerp(partialTick,
                            mc.player.xo, mc.player.getX());
                    double playerZ = net.minecraft.util.Mth.lerp(partialTick,
                            mc.player.zo, mc.player.getZ());
                    double distanceX = MapConfig.pinWorldX - playerX;
                    double distanceZ = MapConfig.pinWorldZ - playerZ;
                    int distanceBlocks = (int) Math.floor(Math.sqrt(
                            distanceX * distanceX + distanceZ * distanceZ));
                    String distanceText = distanceBlocks + " blocks";
                    int textWidth = mc.font.width(distanceText);
                    int labelY = 6;
                    poseStack.translate(0.0F, 0.0F, 1.0F);
                    guiGraphics.fill(-textWidth / 2 - 2, labelY - 1,
                            textWidth / 2 + 2, labelY + 9, 0xAA000000);
                    guiGraphics.drawString(mc.font, distanceText,
                            -textWidth / 2, labelY, 0xFFFFFFFF, false);
                }

                poseStack.popPose();
            }
        }

        // 7. Render player marker if enabled
        if (drawPlayer) {
            double playerX = net.minecraft.util.Mth.lerp(partialTick, mc.player.xo, mc.player.getX());
            double playerZ = net.minecraft.util.Mth.lerp(partialTick, mc.player.zo, mc.player.getZ());
            float playerYaw = net.minecraft.util.Mth.rotLerp(partialTick, mc.player.yRotO, mc.player.getYRot())
                    + 180.0f;

            drawPlayerMarker(guiGraphics, playerX, playerZ, playerYaw, scale, fixedOverlayScale);
        }

    }

    private CachedPlan selectMinimapPlan(PlanKey planKey,
            boolean caveMode, boolean fullCaveView, int caveLayerY,
            int hierarchyLevel, boolean caveBranchOnly, float scale,
            double minX, double maxX, double minZ, double maxZ,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            int minVisiblePageX, int maxVisiblePageX,
            int minVisiblePageZ, int maxVisiblePageZ,
            int attentionPageX, int attentionPageZ,
            MapTextureManager surfaceTextures,
            MapOverviewTextureManager overviewTextures,
            FullCaveTextureManager fullCaveTextures,
            CaveTextureManager caveTextures,
            long topologyRevision, long contentRevision, long nowNanos) {
        CachedPlan current = minimapPlan;
        /*
         * A logical-coverage minimap plan contains one world-space instance for
         * every visible exact page, including pages that were absent at build time.
         * Residency is resolved from the front page table during replay, so atlas
         * publication/eviction does not alter geometry and must not rebuild/sort the
         * plan. PlanKey includes the map session, authority, viewport and style.
         */
        if (current != null && current.key().equals(planKey)
                && current.plan().logicalExactCoverage()) {
            minimapStagingPlan = null;
            minimapStagingSinceNanos = 0L;
            MapPipelineTelemetry.getInstance().recordRenderPlanReuse();
            return current;
        }
        int expectedPages = visiblePageCount(minX, maxX, minZ, maxZ);
        int requiredPages = Math.max(1,
                Math.min(expectedPages, (int) Math.ceil(expectedPages * 0.70)));
        boolean currentSafe = current != null
                && current.plan().topologyValid(topologyRevision)
                && sameMinimapAuthority(current.key(), planKey);
        boolean currentHasBranchCoverage = current != null
                && current.plan().result().branchNodesDrawn() > 0;
        boolean currentWindowComplete = currentSafe
                && current.key().equals(planKey)
                && current.contentRevision() == contentRevision
                && (current.plan().logicalExactCoverage()
                        || (caveBranchOnly && currentHasBranchCoverage)
                        || current.plan().result().exactPagesDrawn() >= expectedPages);
        if (currentWindowComplete && minimapStagingPlan == null) {
            // Pixel uploads inside an existing slot do not advance the coverage
            // revision, so the UV plan remains valid. New residents and evictions do
            // advance it and must rebuild the plan; otherwise an evicted exact tile
            // could remain a permanent skipped hole after the page-table entry is
            // removed.
            MapPipelineTelemetry.getInstance().recordRenderPlanReuse();
            return current;
        }
        boolean sameStagingWindow = minimapStagingPlan != null
                && minimapStagingPlan.key().equals(planKey)
                && (minimapStagingPlan.plan().logicalExactCoverage()
                        || minimapStagingPlan.plan().topologyValid(topologyRevision));
        boolean stagingMatches = sameStagingWindow
                && (minimapStagingPlan.plan().logicalExactCoverage()
                        || minimapStagingPlan.contentRevision() == contentRevision);
        if (!stagingMatches) {
            boolean currentWindowMatches = current != null
                    && current.key().equals(planKey)
                    && current.plan().topologyValid(topologyRevision);
            boolean newWindowNeedsCandidate = !currentWindowMatches
                    && !sameStagingWindow;
            long refreshInterval = current == null
                    || current.plan().quadCount() == 0
                            ? MINIMAP_EMPTY_PLAN_REFRESH_NANOS
                            : MapPerformanceGovernor.getInstance().underPressure()
                                    ? 125_000_000L
                                    : MINIMAP_PLAN_REFRESH_NANOS;
            boolean refreshDue = nowNanos - lastMinimapPlanBuildNanos
                    >= refreshInterval;
            boolean buildCandidate = newWindowNeedsCandidate || refreshDue
                    || (!currentSafe && !sameStagingWindow);
            if (!buildCandidate) {
                if (minimapStagingPlan == null) {
                    MapPipelineTelemetry.getInstance().recordRenderPlanReuse();
                    return current;
                }
                // Keep evaluating the existing staging plan below; do not rebuild
                // it merely because another page published during this window.
            } else {
                MapRenderPlan candidate = buildRenderPlan(caveMode, fullCaveView,
                        caveLayerY, hierarchyLevel, caveBranchOnly, scale,
                        false, true, true,
                        minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                        minVisiblePageX, maxVisiblePageX,
                        minVisiblePageZ, maxVisiblePageZ,
                        attentionPageX, attentionPageZ,
                        surfaceTextures, overviewTextures,
                        fullCaveTextures, caveTextures,
                        MapRequestLane.MINIMAP, topologyRevision);
                minimapStagingPlan = new CachedPlan(planKey, candidate, contentRevision);
                lastMinimapPlanBuildNanos = nowNanos;
                if (!sameStagingWindow) minimapStagingSinceNanos = nowNanos;
                MapPipelineTelemetry.getInstance().recordRenderPlanBuild(
                        candidate.quadCount(), candidate.batchCount());
            }
        }

        CachedPlan staging = minimapStagingPlan;
        int stagedExact = staging.plan().result().exactPagesDrawn();
        boolean stagedAnyCoverage = staging.plan().result().drewAnyMapContent();
        boolean authorityChanged = current == null
                || !sameMinimapAuthority(current.key(), planKey);
        boolean ready = staging.plan().logicalExactCoverage()
                || (caveBranchOnly && stagedAnyCoverage)
                || stagedExact >= requiredPages
                || (!currentSafe && stagedExact > 0)
                || (stagedExact > 0
                        && nowNanos - minimapStagingSinceNanos >= 350_000_000L);
        if (authorityChanged || !currentSafe || ready) {
            minimapPlan = staging;
            minimapStagingPlan = null;
            minimapStagingSinceNanos = 0L;
            return minimapPlan;
        }
        MapPipelineTelemetry.getInstance().recordRenderPlanReuse();
        return current;
    }

    /**
     * True when every visible Surface region that has a known source also has its
     * retained M4 level-0 root on the GPU. This is the same handoff invariant as
     * Xaero's loadedBlocks/loadingBlocks pair: a finer/exact refresh is not allowed
     * to become the visible minimap authority while a known parent region is still
     * missing. Unknown/unexplored regions are intentionally not treated as holes.
     */
    boolean surfaceMinimapRetainedCoverageReady(double centerX, double centerZ,
            float scale, int targetSize) {
        if (!Float.isFinite(scale) || scale <= 0.0f || targetSize <= 0) return false;
        double half = targetSize * 0.5 / scale;
        int minRegionX = (int) Math.floor(centerX - half - 1.0) >> 9;
        int maxRegionX = (int) Math.floor(centerX + half + 1.0) >> 9;
        int minRegionZ = (int) Math.floor(centerZ - half - 1.0) >> 9;
        int maxRegionZ = (int) Math.floor(centerZ + half + 1.0) >> 9;
        MapManager manager = MapManager.getInstance();
        MapOverviewTextureManager overview = MapOverviewTextureManager.getInstance();
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                boolean knownSource = manager.hasRegionFile(regionX, regionZ)
                        || manager.isRegionLoadedInCache(regionX, regionZ);
                if (!knownSource) continue;
                CaveAtlasRegion root = overview.peekRegionSurfaceBranch(
                        0, regionX, regionZ);
                if (root == null || root.knownMask() == 0L) return false;
            }
        }
        return true;
    }

    /**
     * Retained-root readiness for Cave minimap handoff. We deliberately do not
     * require every exact 64x64 page: at far zoom Xaero also lets branch textures
     * remain the visible authority. Instead, any L1 branch that the CPU hierarchy
     * already knows must have a published GPU snapshot before a new retained FBO is
     * allowed to replace the previous one. Nodes with no known cave data are not
     * mistaken for holes.
     */
    boolean caveMinimapRetainedCoverageReady(boolean fullCaveView, int layerY,
            double centerX, double centerZ, float scale, int targetSize) {
        if (!Float.isFinite(scale) || scale <= 0.0f || targetSize <= 0) return false;
        double half = targetSize * 0.5 / scale;
        int minPageX = Math.floorDiv((int) Math.floor(centerX - half - 1.0),
                MapPageLayout.PAGE_SIZE);
        int maxPageX = Math.floorDiv((int) Math.floor(centerX + half + 1.0),
                MapPageLayout.PAGE_SIZE);
        int minPageZ = Math.floorDiv((int) Math.floor(centerZ - half - 1.0),
                MapPageLayout.PAGE_SIZE);
        int maxPageZ = Math.floorDiv((int) Math.floor(centerZ + half + 1.0),
                MapPageLayout.PAGE_SIZE);
        int span = MapLodPolicy.pageSpanForBranch(1);
        int minNodeX = Math.floorDiv(minPageX, span);
        int maxNodeX = Math.floorDiv(maxPageX, span);
        int minNodeZ = Math.floorDiv(minPageZ, span);
        int maxNodeZ = Math.floorDiv(maxPageZ, span);
        FullCaveTextureManager full = fullCaveView
                ? FullCaveTextureManager.getInstance() : null;
        CaveTextureManager layered = fullCaveView
                ? null : CaveTextureManager.getInstance();
        for (int nodeZ = minNodeZ; nodeZ <= maxNodeZ; nodeZ++) {
            for (int nodeX = minNodeX; nodeX <= maxNodeX; nodeX++) {
                boolean known = fullCaveView
                        ? full.hasBranchData(1, nodeX, nodeZ)
                        : layered.hasBranchData(layerY, 1, nodeX, nodeZ,
                                MapRequestLane.MINIMAP);
                if (!known) continue;
                CaveAtlasRegion root = fullCaveView
                        ? full.peekBranchRegion(1, nodeX, nodeZ)
                        : layered.peekBranchRegion(layerY, 1, nodeX, nodeZ,
                                MapRequestLane.MINIMAP);
                if (root == null || root.knownMask() == 0L) return false;
            }
        }
        return true;
    }

    private static boolean sameMinimapAuthority(PlanKey left, PlanKey right) {
        if (left == null || right == null) return false;
        return left.dimension().equals(right.dimension())
                && left.sessionId() == right.sessionId()
                && left.caveMode() == right.caveMode()
                && left.fullCaveView() == right.fullCaveView()
                && (!left.caveMode() || left.fullCaveView()
                        || left.caveLayerY() == right.caveLayerY());
    }

    private static int visiblePageCount(double minX, double maxX,
            double minZ, double maxZ) {
        int minPageX = Math.floorDiv((int) Math.floor(Math.min(minX, maxX) - 1.0),
                MapPageLayout.PAGE_SIZE);
        int maxPageX = Math.floorDiv((int) Math.floor(Math.max(minX, maxX) + 1.0),
                MapPageLayout.PAGE_SIZE);
        int minPageZ = Math.floorDiv((int) Math.floor(Math.min(minZ, maxZ) - 1.0),
                MapPageLayout.PAGE_SIZE);
        int maxPageZ = Math.floorDiv((int) Math.floor(Math.max(minZ, maxZ) + 1.0),
                MapPageLayout.PAGE_SIZE);
        long total = (long) (maxPageX - minPageX + 1)
                * (maxPageZ - minPageZ + 1);
        return (int) Math.max(1L, Math.min(256L, total));
    }

    private static int renderScaleClass(boolean caveMode, float scale) {
        if (!caveMode) return MapRegionLodPolicy.targetLevel(scale);
        int mip = MapLodPolicy.leafMipLevel(scale, 3);
        int partialExactClass = CaveScreenSpacePolicy.exactPagePixels(scale) >= 16.0f
                ? 1 : 0;
        return (mip << 1) | partialExactClass;
    }

    private static long fullscreenPlanRefreshNanos(boolean caveMode,
            boolean caveBranchOnly, int hierarchyLevel) {
        if (caveBranchOnly) {
            return MapPerformanceGovernor.getInstance().underPressure()
                    ? 500_000_000L : FULLSCREEN_BRANCH_PLAN_REFRESH_NANOS;
        }
        if (caveMode) return 200_000_000L;
        // Surface hierarchy plan construction is the dominant render-thread stall in
        // PASS94 (65/68 slow plans; up to ~199 ms). Coalesce streaming revisions at
        // branch/region density instead of rebuilding the same immutable geometry at
        // exact-leaf cadence. The retained front FBO remains visible between refreshes.
        if (hierarchyLevel > 0) {
            return MapPerformanceGovernor.getInstance().underPressure()
                    ? SURFACE_PRESSURE_PLAN_REFRESH_NANOS
                    : SURFACE_HIERARCHY_PLAN_REFRESH_NANOS;
        }
        if (MapPerformanceGovernor.getInstance().underPressure()) {
            return 250_000_000L;
        }
        return FULLSCREEN_PLAN_REFRESH_NANOS;
    }

    /**
     * Missing far-zoom branches used to recurse through the full 4,096-node cap
     * every time the camera crossed one page. At four screen pixels per exact page,
     * those deep sparse descendants cannot contribute enough visible information to
     * justify a multi-millisecond traversal. Keep a larger budget as density rises.
     */
    private static int caveBranchVisitBudget(float scale) {
        float exactPagePixels = CaveScreenSpacePolicy.exactPagePixels(scale);
        if (exactPagePixels <= 4.5f) return 128;
        if (exactPagePixels <= 8.0f) return 256;
        if (exactPagePixels < 16.0f) return 768;
        return CAVE_BRANCH_PLAN_MAX_VISITS;
    }

    private static void recordSlowRenderPlan(long buildNanos,
            boolean caveMode, boolean caveBranchOnly, float scale,
            int hierarchyLevel, MapRenderPlan plan,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ) {
        if (buildNanos < 4_000_000L) return;
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (!recorder.shouldEmitEvent("RENDER_PLAN_SLOW", 250L)) return;
        long pageCount = (long) (maxPageX - minPageX + 1)
                * (maxPageZ - minPageZ + 1);
        recorder.event("RENDER_PLAN_SLOW",
                "build_us=" + (buildNanos / 1_000L)
                        + " cave=" + caveMode
                        + " branch_only=" + caveBranchOnly
                        + " scale=" + scale
                        + " hierarchy=" + hierarchyLevel
                        + " visible_pages=" + pageCount
                        + " quads=" + plan.quadCount()
                        + " batches=" + plan.batchCount());
    }

    private MapRenderPlan buildRenderPlan(boolean caveMode, boolean fullCaveView,
            int caveLayerY, int hierarchyLevel, boolean caveBranchOnly, float scale,
            boolean collectPending, boolean centerOutTraversal,
            boolean logicalExactCoverage,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            int minVisiblePageX, int maxVisiblePageX,
            int minVisiblePageZ, int maxVisiblePageZ,
            int attentionPageX, int attentionPageZ,
            MapTextureManager surfaceTextures,
            MapOverviewTextureManager overviewTextures,
            FullCaveTextureManager fullCaveTextures,
            CaveTextureManager caveTextures,
            MapRequestLane renderLane, long topologyRevision) {
        MapRenderPlan.Builder builder = new MapRenderPlan.Builder();
        boolean pageTableCoverage = logicalExactCoverage
                && hierarchyLevel == 0
                && MapSessionManager.getInstance().activeStamp() != null;
        if (pageTableCoverage) builder.logicalExactCoverage();
        if (caveMode) {
            LongKeySet drawnRegions = new LongKeySet();
            CaveHierarchySource source;
            if (fullCaveView) {
                source = new CaveHierarchySource() {
                    @Override
                    public CaveAtlasRegion branch(int level, int nodeX, int nodeZ) {
                        return fullCaveTextures.peekBranchRegion(level, nodeX, nodeZ);
                    }

                    @Override
                    public boolean hasBranchData(int level, int nodeX, int nodeZ) {
                        return fullCaveTextures.hasBranchData(level, nodeX, nodeZ);
                    }

                    @Override
                    public boolean hasResidentPageInNode(int level, int nodeX, int nodeZ) {
                        return fullCaveTextures.hasResidentPageInNode(level, nodeX, nodeZ);
                    }

                    @Override
                    public boolean allowExact(int globalPageX, int globalPageZ) {
                        /* PASS170: center-out traversal is a search/order heuristic,
                         * not permission to bypass the fullscreen presentation
                         * writer. PASS168/169 used `centerOutTraversal || ...`, so the
                         * renderer ignored UnifiedCaveTextureManager's publication
                         * cursor and displayed whichever async page completed first. */
                        return (renderLane != MapRequestLane.FULLSCREEN
                                && centerOutTraversal)
                                || fullCaveTextures.allowFullscreenExact(
                                        globalPageX, globalPageZ);
                    }

                    @Override
                    public CaveAtlasRegion page(int rx, int rz, int px, int pz, float drawScale) {
                        return fullCaveTextures.peekPageRegion(rx, rz, px, pz, drawScale);
                    }

                    @Override
                    public TileKey pageKey(int globalPageX, int globalPageZ,
                            float drawScale) {
                        return fullCaveTextures.pageTileKey(
                                globalPageX, globalPageZ, drawScale);
                    }
                };
            } else {
                source = new CaveHierarchySource() {
                    @Override
                    public CaveAtlasRegion branch(int level, int nodeX, int nodeZ) {
                        return caveTextures.peekBranchRegion(caveLayerY, level,
                                nodeX, nodeZ, renderLane);
                    }

                    @Override
                    public boolean hasBranchData(int level, int nodeX, int nodeZ) {
                        return caveTextures.hasBranchData(caveLayerY, level,
                                nodeX, nodeZ, renderLane);
                    }

                    @Override
                    public boolean hasResidentPageInNode(int level, int nodeX, int nodeZ) {
                        return caveTextures.hasResidentPageInNode(caveLayerY, level,
                                nodeX, nodeZ, renderLane);
                    }

                    @Override
                    public boolean allowExact(int globalPageX, int globalPageZ) {
                        return (renderLane != MapRequestLane.FULLSCREEN
                                && centerOutTraversal)
                                || caveTextures.allowFullscreenExact(caveLayerY,
                                        globalPageX, globalPageZ);
                    }

                    @Override
                    public CaveAtlasRegion page(int rx, int rz, int px, int pz, float drawScale) {
                        return caveTextures.peekPageRegion(caveLayerY, rx, rz, px, pz,
                                drawScale, renderLane);
                    }

                    @Override
                    public TileKey pageKey(int globalPageX, int globalPageZ,
                            float drawScale) {
                        return caveTextures.pageTileKey(caveLayerY,
                                globalPageX, globalPageZ, drawScale, renderLane);
                    }

                    @Override
                    public CaveLeafSelection fallbackPage(int rx, int rz, int px, int pz,
                            float drawScale) {
                        CaveTextureManager.PageSelection fallback =
                                caveTextures.peekFallbackPage(caveLayerY, rx, rz, px, pz,
                                        drawScale, renderLane);
                        return fallback == null ? null
                                : new CaveLeafSelection(fallback.region(), fallback.tileKey());
                    }
                };
            }
            collectCaveHierarchy(builder, source,
                    minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                    minVisiblePageX, maxVisiblePageX,
                    minVisiblePageZ, maxVisiblePageZ,
                    hierarchyLevel, scale, caveBranchOnly,
                    attentionPageX, attentionPageZ, centerOutTraversal,
                    pageTableCoverage, drawnRegions);
            if (collectPending && fullCaveView) {
                FullCaveMapManager manager = FullCaveMapManager.getInstance();
                for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                    for (int rx = minRegionX; rx <= maxRegionX; rx++) {
                        if (!drawnRegions.contains(packRegion(rx, rz))
                                && (manager.hasRegionFile(rx, rz)
                                        || manager.isRegionLoaded(rx, rz))) {
                            builder.pending(rx, rz);
                        }
                    }
                }
            } else if (collectPending) {
                CaveMapManager manager = CaveMapManager.getInstance();
                VerticalCaveArchiveManager archive = VerticalCaveArchiveManager.getInstance();
                for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                    for (int rx = minRegionX; rx <= maxRegionX; rx++) {
                        if (!drawnRegions.contains(packRegion(rx, rz))
                                && (manager.hasRegionFile(rx, rz)
                                        || manager.isRegionLoaded(rx, rz)
                                        || archive.hasRegionData(rx, rz))) {
                            builder.pending(rx, rz);
                        }
                    }
                }
            }
        } else {
            MapManager manager = MapManager.getInstance();
            boolean regionOnly = MapRegionLodPolicy.regionAuthorityOnly(scale);
            /*
             * Xaero never lets a finer texture become the sole visual authority.
             * World Map crops a loaded root texture into a child that is still cold,
             * while Minimap keeps its completed loadedBlocks grid visible until the
             * loadingBlocks grid completes. Surface follows both invariants here:
             * M4/root coverage remains underneath exact refinement in fullscreen and
             * the minimap plan also carries logical M4 roots so an exact-page burst
             * can never expose the framebuffer clear colour. The legacy 512x512
             * compatibility path is deliberately not the authority; M4 is the
             * retained representation actually published by RegionSurfaceLodService.
             */
            boolean minimapLogicalSurface = !collectPending
                    && centerOutTraversal && logicalExactCoverage;
            if (minimapLogicalSurface) {
                /*
                 * Xaero MinimapWriter keeps a loaded grid active while a loading
                 * grid is refreshed. Give the retained FBO the equivalent stable
                 * parent geometry: one logical M4 level-0 tile for every visible
                 * 512x512 region. The TileKey may be non-resident now; replay will
                 * resolve it as soon as RegionSurfaceLodService publishes it, so a
                 * cached minimap plan does not need to rebuild merely to discover a
                 * newly-ready root. Exact 64x64 pages refine this root at PHASE_EXACT.
                 */
                collectMinimapRegionRootCoverage(builder,
                        minRegionX, maxRegionX, minRegionZ, maxRegionZ);
            } else if (collectPending || !centerOutTraversal) {
                collectRegionSurfaceCoverage(builder, overviewTextures,
                        minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                        scale, attentionPageX, attentionPageZ, regionOnly);
            }
            if (hierarchyLevel > 0) {
                if (!regionOnly) {
                    collectSurfaceHierarchy(builder, overviewTextures, surfaceTextures,
                            minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                            minVisiblePageX, maxVisiblePageX,
                            minVisiblePageZ, maxVisiblePageZ,
                            hierarchyLevel, scale, attentionPageX, attentionPageZ,
                            manager, collectPending, centerOutTraversal);
                }
            } else {
                if (centerOutTraversal) {
                    int centerPageX = clamp(attentionPageX,
                            minVisiblePageX, maxVisiblePageX);
                    int centerPageZ = clamp(attentionPageZ,
                            minVisiblePageZ, maxVisiblePageZ);
                    int maximumRadius = gridRadius(minVisiblePageX, maxVisiblePageX,
                            minVisiblePageZ, maxVisiblePageZ,
                            centerPageX, centerPageZ);
                    for (int radius = 0; radius <= maximumRadius; radius++) {
                        int left = centerPageX - radius;
                        int right = centerPageX + radius;
                        int top = centerPageZ - radius;
                        int bottom = centerPageZ + radius;
                        if (top >= minVisiblePageZ && top <= maxVisiblePageZ) {
                            for (int pageX = Math.max(minVisiblePageX, left);
                                    pageX <= Math.min(maxVisiblePageX, right); pageX++) {
                                collectSurfaceLeaf(builder, surfaceTextures,
                                        pageX, top, manager, collectPending,
                                        MapRenderPlan.PHASE_EXACT, pageTableCoverage);
                            }
                        }
                        if (radius == 0) continue;
                        for (int pageZ = Math.max(minVisiblePageZ, top + 1);
                                pageZ <= Math.min(maxVisiblePageZ, bottom - 1); pageZ++) {
                            if (left >= minVisiblePageX && left <= maxVisiblePageX) {
                                collectSurfaceLeaf(builder, surfaceTextures,
                                        left, pageZ, manager, collectPending,
                                        MapRenderPlan.PHASE_EXACT, pageTableCoverage);
                            }
                            if (right != left && right >= minVisiblePageX
                                    && right <= maxVisiblePageX) {
                                collectSurfaceLeaf(builder, surfaceTextures,
                                        right, pageZ, manager, collectPending,
                                        MapRenderPlan.PHASE_EXACT, pageTableCoverage);
                            }
                        }
                        if (bottom >= minVisiblePageZ && bottom <= maxVisiblePageZ) {
                            for (int pageX = Math.max(minVisiblePageX, left);
                                    pageX <= Math.min(maxVisiblePageX, right); pageX++) {
                                collectSurfaceLeaf(builder, surfaceTextures,
                                        pageX, bottom, manager, collectPending,
                                        MapRenderPlan.PHASE_EXACT, pageTableCoverage);
                            }
                        }
                    }
                } else {
                    for (int pageZ = minVisiblePageZ;
                            pageZ <= maxVisiblePageZ; pageZ++) {
                        for (int pageX = minVisiblePageX;
                                pageX <= maxVisiblePageX; pageX++) {
                            collectSurfaceLeaf(builder, surfaceTextures,
                                    pageX, pageZ, manager, collectPending,
                                    MapRenderPlan.PHASE_EXACT, pageTableCoverage);
                        }
                    }
                }
                if (MapConfig.minimapNightMode != 0) {
                    for (int pageZ = minVisiblePageZ;
                            pageZ <= maxVisiblePageZ; pageZ++) {
                        for (int pageX = minVisiblePageX;
                                pageX <= maxVisiblePageX; pageX++) {
                            collectSurfaceGlowLeaf(builder, surfaceTextures,
                                    pageX, pageZ, pageTableCoverage);
                        }
                    }
                }
            }
        }
        return builder.build(topologyRevision);
    }

    private void collectCaveHierarchy(MapRenderPlan.Builder builder,
            CaveHierarchySource source,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            int minVisiblePageX, int maxVisiblePageX,
            int minVisiblePageZ, int maxVisiblePageZ,
            int level, float scale, boolean branchOnly,
            int focusPageX, int focusPageZ, boolean centerOutTraversal,
            boolean logicalExactCoverage, LongKeySet drawnRegions) {
        int minPageX = minVisiblePageX;
        int maxPageX = maxVisiblePageX;
        int minPageZ = minVisiblePageZ;
        int maxPageZ = maxVisiblePageZ;
        if (branchOnly && level > 0) {
            /*
             * Build complete selected-level cells. The GUI scissor clips their
             * offscreen portions, while the immutable plan remains valid until an
             * actual selected-cell edge enters/leaves the viewport. Without this
             * alignment, reusing a quantized plan could omit a lower-level fallback
             * that became visible inside the same selected cell during a small pan.
             */
            int pageSpan = MapLodPolicy.pageSpanForBranch(level);
            minPageX = Math.floorDiv(minPageX, pageSpan) * pageSpan;
            maxPageX = (Math.floorDiv(maxPageX, pageSpan) + 1) * pageSpan - 1;
            minPageZ = Math.floorDiv(minPageZ, pageSpan) * pageSpan;
            maxPageZ = (Math.floorDiv(maxPageZ, pageSpan) + 1) * pageSpan - 1;
        }
        CaveTraversalBudget traversalBudget = branchOnly
                ? new CaveTraversalBudget(caveBranchVisitBudget(scale),
                        CAVE_BRANCH_PLAN_MAX_QUADS)
                : CaveTraversalBudget.unbounded();
        if (level <= 0) {
            if (branchOnly) return;
            /*
             * Xaero does not turn an already-known parent into black while exact
             * cave leaves are rebuilding. PASS98 intentionally left L0 unresolved
             * pages black to avoid a brief coarse->sharp transition; that traded a
             * cosmetic refinement for persistent holes and made the retained FBO
             * learn those holes as its next underlay. Keep the already-published L1
             * branch below exact L0 instead. Exact pages still win at PHASE_EXACT;
             * the branch is only last-good coverage for cold/unknown children.
             */
            collectCaveLevelZeroUnderlay(builder, source,
                    minPageX, maxPageX, minPageZ, maxPageZ,
                    scale, focusPageX, focusPageZ, drawnRegions);
            if (!centerOutTraversal) {
                for (int pageZ = minPageZ; pageZ <= maxPageZ; pageZ++) {
                    for (int pageX = minPageX; pageX <= maxPageX; pageX++) {
                        collectCaveLeaf(builder, source, pageX, pageZ, scale,
                                drawnRegions, MapRenderPlan.PHASE_EXACT,
                                logicalExactCoverage);
                    }
                }
                return;
            }
            int centerX = clamp(focusPageX, minPageX, maxPageX);
            int centerZ = clamp(focusPageZ, minPageZ, maxPageZ);
            int maximumRadius = gridRadius(minPageX, maxPageX, minPageZ, maxPageZ,
                    centerX, centerZ);
            for (int radius = 0; radius <= maximumRadius; radius++) {
                int left = centerX - radius;
                int right = centerX + radius;
                int top = centerZ - radius;
                int bottom = centerZ + radius;
                if (top >= minPageZ && top <= maxPageZ) {
                    for (int pageX = Math.max(minPageX, left);
                            pageX <= Math.min(maxPageX, right); pageX++) {
                        collectCaveLeaf(builder, source, pageX, top, scale,
                                drawnRegions, MapRenderPlan.PHASE_EXACT,
                                logicalExactCoverage);
                    }
                }
                if (radius == 0) continue;
                for (int pageZ = Math.max(minPageZ, top + 1);
                        pageZ <= Math.min(maxPageZ, bottom - 1); pageZ++) {
                    if (left >= minPageX && left <= maxPageX) {
                        collectCaveLeaf(builder, source, left, pageZ, scale,
                                drawnRegions, MapRenderPlan.PHASE_EXACT,
                                logicalExactCoverage);
                    }
                    if (right != left && right >= minPageX && right <= maxPageX) {
                        collectCaveLeaf(builder, source, right, pageZ, scale,
                                drawnRegions, MapRenderPlan.PHASE_EXACT,
                                logicalExactCoverage);
                    }
                }
                if (bottom >= minPageZ && bottom <= maxPageZ) {
                    for (int pageX = Math.max(minPageX, left);
                            pageX <= Math.min(maxPageX, right); pageX++) {
                        collectCaveLeaf(builder, source, pageX, bottom, scale,
                                drawnRegions, MapRenderPlan.PHASE_EXACT,
                                logicalExactCoverage);
                    }
                }
            }
            return;
        }
        int pageSpan = MapLodPolicy.pageSpanForBranch(level);
        int minNodeX = Math.floorDiv(minPageX, pageSpan);
        int maxNodeX = Math.floorDiv(maxPageX, pageSpan);
        int minNodeZ = Math.floorDiv(minPageZ, pageSpan);
        int maxNodeZ = Math.floorDiv(maxPageZ, pageSpan);
        if (!centerOutTraversal) {
            for (int nodeZ = minNodeZ; nodeZ <= maxNodeZ; nodeZ++) {
                for (int nodeX = minNodeX; nodeX <= maxNodeX; nodeX++) {
                    if (traversalBudget.exhausted()) return;
                    collectCaveNode(builder, source, level, nodeX, nodeZ, scale,
                            branchOnly, minPageX, maxPageX, minPageZ, maxPageZ,
                            drawnRegions, traversalBudget);
                }
            }
            return;
        }
        int centerX = clamp(Math.floorDiv(focusPageX, pageSpan), minNodeX, maxNodeX);
        int centerZ = clamp(Math.floorDiv(focusPageZ, pageSpan), minNodeZ, maxNodeZ);
        int maximumRadius = gridRadius(minNodeX, maxNodeX, minNodeZ, maxNodeZ,
                centerX, centerZ);
        for (int radius = 0; radius <= maximumRadius; radius++) {
            int left = centerX - radius;
            int right = centerX + radius;
            int top = centerZ - radius;
            int bottom = centerZ + radius;
            if (top >= minNodeZ && top <= maxNodeZ) {
                for (int nodeX = Math.max(minNodeX, left);
                        nodeX <= Math.min(maxNodeX, right); nodeX++) {
                    if (traversalBudget.exhausted()) return;
                    collectCaveNode(builder, source, level, nodeX, top, scale,
                            branchOnly, minPageX, maxPageX, minPageZ, maxPageZ,
                            drawnRegions, traversalBudget);
                }
            }
            if (radius == 0) continue;
            for (int nodeZ = Math.max(minNodeZ, top + 1);
                    nodeZ <= Math.min(maxNodeZ, bottom - 1); nodeZ++) {
                if (left >= minNodeX && left <= maxNodeX) {
                    if (traversalBudget.exhausted()) return;
                    collectCaveNode(builder, source, level, left, nodeZ, scale,
                            branchOnly, minPageX, maxPageX, minPageZ, maxPageZ,
                            drawnRegions, traversalBudget);
                }
                if (right != left && right >= minNodeX && right <= maxNodeX) {
                    if (traversalBudget.exhausted()) return;
                    collectCaveNode(builder, source, level, right, nodeZ, scale,
                            branchOnly, minPageX, maxPageX, minPageZ, maxPageZ,
                            drawnRegions, traversalBudget);
                }
            }
            if (bottom >= minNodeZ && bottom <= maxNodeZ) {
                for (int nodeX = Math.max(minNodeX, left);
                        nodeX <= Math.min(maxNodeX, right); nodeX++) {
                    if (traversalBudget.exhausted()) return;
                    collectCaveNode(builder, source, level, nodeX, bottom, scale,
                            branchOnly, minPageX, maxPageX, minPageZ, maxPageZ,
                            drawnRegions, traversalBudget);
                }
            }
        }
    }

    private void collectCaveLevelZeroUnderlay(MapRenderPlan.Builder builder,
            CaveHierarchySource source,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ,
            float scale, int focusPageX, int focusPageZ,
            LongKeySet drawnRegions) {
        int underlayLevel = 1;
        int pageSpan = MapLodPolicy.pageSpanForBranch(underlayLevel);
        int minNodeX = Math.floorDiv(minPageX, pageSpan);
        int maxNodeX = Math.floorDiv(maxPageX, pageSpan);
        int minNodeZ = Math.floorDiv(minPageZ, pageSpan);
        int maxNodeZ = Math.floorDiv(maxPageZ, pageSpan);
        int centerX = clamp(Math.floorDiv(focusPageX, pageSpan),
                minNodeX, maxNodeX);
        int centerZ = clamp(Math.floorDiv(focusPageZ, pageSpan),
                minNodeZ, maxNodeZ);
        int maximumRadius = gridRadius(minNodeX, maxNodeX,
                minNodeZ, maxNodeZ, centerX, centerZ);
        CaveTraversalBudget budget = new CaveTraversalBudget(
                Math.min(1_024, caveBranchVisitBudget(scale)),
                CAVE_BRANCH_PLAN_MAX_QUADS);
        for (int radius = 0; radius <= maximumRadius; radius++) {
            int left = centerX - radius;
            int right = centerX + radius;
            int top = centerZ - radius;
            int bottom = centerZ + radius;
            if (top >= minNodeZ && top <= maxNodeZ) {
                for (int nodeX = Math.max(minNodeX, left);
                        nodeX <= Math.min(maxNodeX, right); nodeX++) {
                    if (budget.exhausted()) return;
                    collectCaveNode(builder, source, underlayLevel,
                            nodeX, top, scale, true,
                            minPageX, maxPageX, minPageZ, maxPageZ,
                            drawnRegions, budget);
                }
            }
            if (radius == 0) continue;
            for (int nodeZ = Math.max(minNodeZ, top + 1);
                    nodeZ <= Math.min(maxNodeZ, bottom - 1); nodeZ++) {
                if (left >= minNodeX && left <= maxNodeX) {
                    if (budget.exhausted()) return;
                    collectCaveNode(builder, source, underlayLevel,
                            left, nodeZ, scale, true,
                            minPageX, maxPageX, minPageZ, maxPageZ,
                            drawnRegions, budget);
                }
                if (right != left && right >= minNodeX && right <= maxNodeX) {
                    if (budget.exhausted()) return;
                    collectCaveNode(builder, source, underlayLevel,
                            right, nodeZ, scale, true,
                            minPageX, maxPageX, minPageZ, maxPageZ,
                            drawnRegions, budget);
                }
            }
            if (bottom >= minNodeZ && bottom <= maxNodeZ) {
                for (int nodeX = Math.max(minNodeX, left);
                        nodeX <= Math.min(maxNodeX, right); nodeX++) {
                    if (budget.exhausted()) return;
                    collectCaveNode(builder, source, underlayLevel,
                            nodeX, bottom, scale, true,
                            minPageX, maxPageX, minPageZ, maxPageZ,
                            drawnRegions, budget);
                }
            }
        }
    }

    private void collectCaveNode(MapRenderPlan.Builder builder,
            CaveHierarchySource source, int level, int nodeX, int nodeZ,
            float scale, boolean branchOnly,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ,
            LongKeySet drawnRegions, CaveTraversalBudget traversalBudget) {
        if (!traversalBudget.visit()) return;
        int pageSpan = MapLodPolicy.pageSpanForBranch(level);
        int firstPageX = nodeX * pageSpan;
        int firstPageZ = nodeZ * pageSpan;
        int lastPageX = firstPageX + pageSpan - 1;
        int lastPageZ = firstPageZ + pageSpan - 1;
        if (lastPageX < minPageX || firstPageX > maxPageX
                || lastPageZ < minPageZ || firstPageZ > maxPageZ) return;

        CaveAtlasRegion branch = source.branch(level, nodeX, nodeZ);
        CaveAtlasRegion ancestor = null;
        if (branch == null) {
            ancestor = findCaveAncestor(source, level, nodeX, nodeZ);
            if (ancestor != null && traversalBudget.emitQuad()) {
                addAncestorQuad(builder, ancestor, level, nodeX, nodeZ);
                markDrawnPageRange(drawnRegions,
                        Math.max(firstPageX, minPageX), Math.min(lastPageX, maxPageX),
                        Math.max(firstPageZ, minPageZ), Math.min(lastPageZ, maxPageZ));
            }
        }
        if (branchOnly) {
            /*
             * Branch-only controls preferred density, not visibility. At L1 a
             * resident exact page is a temporary underlay while its coherent 2x2
             * branch is deriving/loading. This also applies to a partial branch:
             * transparent/unknown child quadrants must not erase exact data that is
             * already GPU-ready. Phase 27 sorts exact below the L1 branch (phase 28).
             */
            if (level == 1) {
                for (int childX = 0; childX < 2
                        && !traversalBudget.exhausted(); childX++) {
                    for (int childZ = 0; childZ < 2
                            && !traversalBudget.exhausted(); childZ++) {
                        int childIndex = childZ * 2 + childX;
                        if (branch != null && branch.childComplete(childIndex)) {
                            continue;
                        }
                        int pageX = firstPageX + childX;
                        int pageZ = firstPageZ + childZ;
                        if (pageX < minPageX || pageX > maxPageX
                                || pageZ < minPageZ || pageZ > maxPageZ) continue;
                        if (!traversalBudget.emitQuad()) return;
                        if (!collectCaveLeaf(builder, source, pageX, pageZ, scale,
                                drawnRegions,
                                MapRenderPlan.PHASE_L1_EXACT_UNDERLAY)) {
                            traversalBudget.refundQuad();
                        }
                    }
                }
                if (branch != null && traversalBudget.emitQuad()) {
                    addNodeQuad(builder, branch, nodeX, nodeZ, level);
                    markDrawnPageRange(drawnRegions,
                            Math.max(firstPageX, minPageX),
                            Math.min(lastPageX, maxPageX),
                            Math.max(firstPageZ, minPageZ),
                            Math.min(lastPageZ, maxPageZ));
                }
                // An ancestor was already added above at the correct phase. Exact
                // underlay fills only its transparent gaps; no recursive probe is
                // needed after either coherent representation exists.
                return;
            }
            // A coarser ancestor already covers this higher-level node. Descending
            // after drawing it causes overlap and exponential traversal.
            if (ancestor != null) return;
            if (branch != null) {
                if (traversalBudget.emitQuad()) {
                    addNodeQuad(builder, branch, nodeX, nodeZ, level);
                    markDrawnPageRange(drawnRegions,
                            Math.max(firstPageX, minPageX),
                            Math.min(lastPageX, maxPageX),
                            Math.max(firstPageZ, minPageZ),
                            Math.min(lastPageZ, maxPageZ));
                }
                return;
            }
            // At higher levels descend only when an exact/child branch subtree is
            // resident. Disk metadata alone is not a reason to hide useful leaves.
            if (!source.hasResidentPageInNode(level, nodeX, nodeZ)) return;
            for (int childX = 0; childX < 2 && !traversalBudget.exhausted(); childX++) {
                for (int childZ = 0; childZ < 2
                        && !traversalBudget.exhausted(); childZ++) {
                    collectCaveNode(builder, source, level - 1,
                            nodeX * 2 + childX, nodeZ * 2 + childZ, scale, true,
                            minPageX, maxPageX, minPageZ, maxPageZ, drawnRegions,
                            traversalBudget);
                }
            }
            return;
        }
        if (branch == null && ancestor == null
                && !source.hasResidentPageInNode(level, nodeX, nodeZ)) return;
        if (branch != null && traversalBudget.emitQuad()) {
            addNodeQuad(builder, branch, nodeX, nodeZ, level);
        }
        if (level == 1) {
            for (int childX = 0; childX < 2; childX++) {
                for (int childZ = 0; childZ < 2; childZ++) {
                    int childIndex = childZ * 2 + childX;
                    int pageX = firstPageX + childX;
                    int pageZ = firstPageZ + childZ;
                    if (pageX < minPageX || pageX > maxPageX
                            || pageZ < minPageZ || pageZ > maxPageZ) continue;
                    if (collectCaveLeaf(builder, source, pageX, pageZ, scale,
                            drawnRegions)) continue;
                    if (branch != null && branch.childComplete(childIndex)) {
                        markPageDrawn(drawnRegions, pageX, pageZ);
                    }
                }
            }
            return;
        }
        for (int childX = 0; childX < 2; childX++) {
            for (int childZ = 0; childZ < 2; childZ++) {
                int childIndex = childZ * 2 + childX;
                int childNodeX = nodeX * 2 + childX;
                int childNodeZ = nodeZ * 2 + childZ;
                if (branch != null && branch.childComplete(childIndex)
                        && !source.hasResidentPageInNode(level - 1,
                                childNodeX, childNodeZ)) continue;
                collectCaveNode(builder, source, level - 1,
                        childNodeX, childNodeZ, scale, false,
                        minPageX, maxPageX, minPageZ, maxPageZ, drawnRegions,
                        traversalBudget);
            }
        }
    }

    private boolean collectCaveLeaf(MapRenderPlan.Builder builder,
            CaveHierarchySource source, int globalPageX, int globalPageZ,
            float scale, LongKeySet drawnRegions) {
        return collectCaveLeaf(builder, source, globalPageX, globalPageZ,
                scale, drawnRegions, MapRenderPlan.PHASE_EXACT, false);
    }

    private boolean collectCaveLeaf(MapRenderPlan.Builder builder,
            CaveHierarchySource source, int globalPageX, int globalPageZ,
            float scale, LongKeySet drawnRegions, int phase) {
        return collectCaveLeaf(builder, source, globalPageX, globalPageZ,
                scale, drawnRegions, phase, false);
    }

    private boolean collectCaveLeaf(MapRenderPlan.Builder builder,
            CaveHierarchySource source, int globalPageX, int globalPageZ,
            float scale, LongKeySet drawnRegions, int phase,
            boolean logicalExactCoverage) {
        if (!source.allowExact(globalPageX, globalPageZ)) return false;
        int rx = Math.floorDiv(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int rz = Math.floorDiv(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        int px = Math.floorMod(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int pz = Math.floorMod(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        CaveAtlasRegion page = source.page(rx, rz, px, pz, scale);
        TileKey pageKey = source.pageKey(globalPageX, globalPageZ, scale);
        if (page == null) {
            if (logicalExactCoverage && pageKey != null) {
                CaveLeafSelection fallback = source.fallbackPage(
                        rx, rz, px, pz, scale);
                if (fallback != null && fallback.region() != null) {
                    addCavePageQuad(builder, fallback.key(), fallback.region(),
                            rx, rz, px, pz, MapRenderPlan.PHASE_L1_EXACT_UNDERLAY);
                    drawnRegions.add(packRegion(rx, rz));
                }
                // Current-layer logical exact stays above the retained old layer.
                // Page-table replay replaces the underlay as soon as the new leaf
                // becomes resident, without another immutable plan rebuild.
                addLogicalCavePageQuad(builder, pageKey, globalPageX,
                        globalPageZ, phase);
            }
            return false;
        }
        addCavePageQuad(builder, pageKey, page, rx, rz, px, pz, phase);
        drawnRegions.add(packRegion(rx, rz));
        return true;
    }

    private static final int REGION_ONLY_PLAN_QUAD_CAP = 1_024;
    private static final int REGION_ONLY_FALLBACK_VISIT_CAP = 4_096;

    private static final int LEGACY_SURFACE_UNDERLAY_CAP = 2_048;

    private void collectLegacySurfaceCoverage(MapRenderPlan.Builder builder,
            MapTextureManager surfaceTextures, MapManager manager,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            int focusPageX, int focusPageZ) {
        if (surfaceTextures == null || manager == null) return;
        int focusRegionX = Math.floorDiv(focusPageX,
                MapPageLayout.PAGES_PER_REGION);
        int focusRegionZ = Math.floorDiv(focusPageZ,
                MapPageLayout.PAGES_PER_REGION);
        int maximumRadius = gridRadius(minRegionX, maxRegionX,
                minRegionZ, maxRegionZ, focusRegionX, focusRegionZ);
        int emitted = 0;
        for (int radius = 0; radius <= maximumRadius
                && emitted < LEGACY_SURFACE_UNDERLAY_CAP; radius++) {
            int left = focusRegionX - radius;
            int right = focusRegionX + radius;
            int top = focusRegionZ - radius;
            int bottom = focusRegionZ + radius;
            for (int regionZ = Math.max(minRegionZ, top);
                    regionZ <= Math.min(maxRegionZ, bottom); regionZ++) {
                for (int regionX = Math.max(minRegionX, left);
                        regionX <= Math.min(maxRegionX, right); regionX++) {
                    if (Math.max(Math.abs(regionX - focusRegionX),
                            Math.abs(regionZ - focusRegionZ)) != radius) continue;
                    if (!manager.hasRegionFile(regionX, regionZ)
                            && !manager.isRegionLoadedInCache(regionX, regionZ)) {
                        continue;
                    }
                    ResourceLocation texture = surfaceTextures.peekRegionTexture(
                            regionX, regionZ);
                    if (texture == null) {
                        // Cached-only replay remains allocation-free after the first
                        // request. getRegionTexture() only admits the background
                        // compatibility build when the retained source is available.
                        texture = surfaceTextures.getRegionTexture(regionX, regionZ);
                    }
                    if (texture == null) continue;
                    if (builder.add(texture,
                            MapRenderPlan.PHASE_LEGACY_UNDERLAY,
                            regionX * MapPageLayout.REGION_SIZE,
                            regionZ * MapPageLayout.REGION_SIZE,
                            MapPageLayout.REGION_SIZE,
                            MapPageLayout.REGION_SIZE,
                            0.0f, 0.0f,
                            MapPageLayout.REGION_SIZE,
                            MapPageLayout.REGION_SIZE,
                            MapPageLayout.REGION_SIZE,
                            MapPageLayout.REGION_SIZE)) {
                        builder.legacy();
                        emitted++;
                    }
                }
            }
        }
    }

    /**
     * Logical level-0 M4 underlay for the minimap. Unlike the old exact-only HUD
     * plan, these keys survive atlas publication churn and become drawable through
     * the front page table without rebuilding the immutable viewport geometry.
     */
    private void collectMinimapRegionRootCoverage(MapRenderPlan.Builder builder,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ) {
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp == null) return;
        int worldSize = MapRegionLodPolicy.worldSize(0);
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                if (builder.addTile(new TileKey(stamp.sessionId(),
                                RegionSurfaceLodService.PROJECTION_SURFACE,
                                0, regionX, regionZ,
                                TileKey.VARIANT_SURFACE_BRANCH),
                        MapRenderPlan.PHASE_REGION_COARSE,
                        regionX * worldSize, regionZ * worldSize,
                        worldSize, worldSize)) {
                    builder.branch();
                }
            }
        }
    }

    private void collectRegionSurfaceCoverage(MapRenderPlan.Builder builder,
            MapOverviewTextureManager overviewTextures,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            float scale, int focusPageX, int focusPageZ, boolean regionOnly) {
        int level = MapRegionLodPolicy.targetLevel(scale);
        int span = MapRegionLodPolicy.regionSpan(level);
        int minNodeX = Math.floorDiv(minRegionX, span);
        int maxNodeX = Math.floorDiv(maxRegionX, span);
        int minNodeZ = Math.floorDiv(minRegionZ, span);
        int maxNodeZ = Math.floorDiv(maxRegionZ, span);
        /*
         * Missing target-level M4 nodes may fall back to already-resident finer M4
         * descendants while their coarser parent warms. This preserves coverage
         * across zoom transitions instead of making a loaded level-0 root vanish
         * merely because level 1/2 was selected. Bound both emitted quads and failed
         * hierarchy lookups so very wide views remain finite.
         */
        regionFallbackVisitsRemaining = REGION_ONLY_FALLBACK_VISIT_CAP;
        for (int nodeZ = minNodeZ; nodeZ <= maxNodeZ; nodeZ++) {
            for (int nodeX = minNodeX; nodeX <= maxNodeX; nodeX++) {
                if (builder.entryCount() >= REGION_ONLY_PLAN_QUAD_CAP) return;
                if (collectRegionSurfaceNode(builder, overviewTextures, level,
                        nodeX, nodeZ, minRegionX, maxRegionX,
                        minRegionZ, maxRegionZ, true)) continue;
            }
        }
    }

    private boolean collectRegionSurfaceNode(MapRenderPlan.Builder builder,
            MapOverviewTextureManager overviewTextures, int level,
            int nodeX, int nodeZ, int minRegionX, int maxRegionX,
            int minRegionZ, int maxRegionZ, boolean allowDescendants) {
        if (regionFallbackVisitsRemaining-- <= 0) return false;
        int span = MapRegionLodPolicy.regionSpan(level);
        int firstRegionX = nodeX * span;
        int firstRegionZ = nodeZ * span;
        int lastRegionX = firstRegionX + span - 1;
        int lastRegionZ = firstRegionZ + span - 1;
        if (lastRegionX < minRegionX || firstRegionX > maxRegionX
                || lastRegionZ < minRegionZ || firstRegionZ > maxRegionZ) {
            return false;
        }
        CaveAtlasRegion branch = overviewTextures
                .peekRegionSurfaceBranch(level, nodeX, nodeZ);
        if (branch != null) {
            addRegionLodQuad(builder, branch, nodeX, nodeZ);
            return true;
        }
        CaveAtlasRegion ancestor = findRegionSurfaceAncestor(
                overviewTextures, level, nodeX, nodeZ);
        if (ancestor != null) {
            addRegionLodAncestorQuad(builder, ancestor, level, nodeX, nodeZ);
            return true;
        }
        if (!allowDescendants || level <= 0) return false;
        boolean drew = false;
        int childLevel = level - 1;
        for (int childZ = 0; childZ < 2; childZ++) {
            for (int childX = 0; childX < 2; childX++) {
                if (builder.entryCount() >= REGION_ONLY_PLAN_QUAD_CAP) return drew;
                drew |= collectRegionSurfaceNode(builder, overviewTextures,
                        childLevel, nodeX * 2 + childX, nodeZ * 2 + childZ,
                        minRegionX, maxRegionX, minRegionZ, maxRegionZ, true);
            }
        }
        return drew;
    }

    private CaveAtlasRegion findRegionSurfaceAncestor(
            MapOverviewTextureManager overviewTextures, int targetLevel,
            int targetNodeX, int targetNodeZ) {
        int divisor = 1;
        for (int level = targetLevel + 1; level <= 3; level++) {
            divisor *= 2;
            CaveAtlasRegion ancestor = overviewTextures.peekRegionSurfaceBranch(
                    level, Math.floorDiv(targetNodeX, divisor),
                    Math.floorDiv(targetNodeZ, divisor));
            if (ancestor != null) return ancestor;
        }
        return null;
    }

    private void collectSurfaceHierarchy(MapRenderPlan.Builder builder,
            MapOverviewTextureManager overviewTextures,
            MapTextureManager surfaceTextures,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            int minVisiblePageX, int maxVisiblePageX,
            int minVisiblePageZ, int maxVisiblePageZ,
            int level, float scale, int focusPageX, int focusPageZ,
            MapManager manager, boolean collectPending,
            boolean centerOutTraversal) {
        int minPageX = minVisiblePageX;
        int maxPageX = maxVisiblePageX;
        int minPageZ = minVisiblePageZ;
        int maxPageZ = maxVisiblePageZ;
        int pageSpan = MapLodPolicy.pageSpanForBranch(level);
        int minNodeX = Math.floorDiv(minPageX, pageSpan);
        int maxNodeX = Math.floorDiv(maxPageX, pageSpan);
        int minNodeZ = Math.floorDiv(minPageZ, pageSpan);
        int maxNodeZ = Math.floorDiv(maxPageZ, pageSpan);
        if (!centerOutTraversal) {
            for (int nodeX = minNodeX; nodeX <= maxNodeX; nodeX++) {
                for (int nodeZ = minNodeZ; nodeZ <= maxNodeZ; nodeZ++) {
                    collectSurfaceNode(builder, overviewTextures, surfaceTextures,
                            level, nodeX, nodeZ, minPageX, maxPageX,
                            minPageZ, maxPageZ, scale, manager, collectPending, false);
                }
            }
            return;
        }
        int centerX = clamp(Math.floorDiv(focusPageX, pageSpan), minNodeX, maxNodeX);
        int centerZ = clamp(Math.floorDiv(focusPageZ, pageSpan), minNodeZ, maxNodeZ);
        int maximumRadius = gridRadius(minNodeX, maxNodeX, minNodeZ, maxNodeZ,
                centerX, centerZ);
        for (int radius = 0; radius <= maximumRadius; radius++) {
            int left = centerX - radius;
            int right = centerX + radius;
            int top = centerZ - radius;
            int bottom = centerZ + radius;
            if (top >= minNodeZ && top <= maxNodeZ) {
                for (int nodeX = Math.max(minNodeX, left);
                        nodeX <= Math.min(maxNodeX, right); nodeX++) {
                    collectSurfaceNode(builder, overviewTextures, surfaceTextures,
                            level, nodeX, top, minPageX, maxPageX,
                            minPageZ, maxPageZ, scale, manager, collectPending, false);
                }
            }
            if (radius == 0) continue;
            for (int nodeZ = Math.max(minNodeZ, top + 1);
                    nodeZ <= Math.min(maxNodeZ, bottom - 1); nodeZ++) {
                if (left >= minNodeX && left <= maxNodeX) {
                    collectSurfaceNode(builder, overviewTextures, surfaceTextures,
                            level, left, nodeZ, minPageX, maxPageX,
                            minPageZ, maxPageZ, scale, manager, collectPending, false);
                }
                if (right != left && right >= minNodeX && right <= maxNodeX) {
                    collectSurfaceNode(builder, overviewTextures, surfaceTextures,
                            level, right, nodeZ, minPageX, maxPageX,
                            minPageZ, maxPageZ, scale, manager, collectPending, false);
                }
            }
            if (bottom >= minNodeZ && bottom <= maxNodeZ) {
                for (int nodeX = Math.max(minNodeX, left);
                        nodeX <= Math.min(maxNodeX, right); nodeX++) {
                    collectSurfaceNode(builder, overviewTextures, surfaceTextures,
                            level, nodeX, bottom, minPageX, maxPageX,
                            minPageZ, maxPageZ, scale, manager, collectPending, false);
                }
            }
        }
    }

    private void collectSurfaceNode(MapRenderPlan.Builder builder,
            MapOverviewTextureManager overviewTextures,
            MapTextureManager surfaceTextures,
            int level, int nodeX, int nodeZ,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ,
            float scale, MapManager manager, boolean collectPending,
            boolean inheritedCoverage) {
        int pageSpan = MapLodPolicy.pageSpanForBranch(level);
        int firstPageX = nodeX * pageSpan;
        int firstPageZ = nodeZ * pageSpan;
        int lastPageX = firstPageX + pageSpan - 1;
        int lastPageZ = firstPageZ + pageSpan - 1;
        if (lastPageX < minPageX || firstPageX > maxPageX
                || lastPageZ < minPageZ || firstPageZ > maxPageZ) return;

        boolean exactResident = surfaceTextures.hasResidentPageInNode(
                level, nodeX, nodeZ);
        boolean sourceAvailable = surfaceNodeHasSource(builder, manager,
                Math.max(firstPageX, minPageX), Math.min(lastPageX, maxPageX),
                Math.max(firstPageZ, minPageZ), Math.min(lastPageZ, maxPageZ));
        if (!exactResident && !sourceAvailable) return;
        CaveAtlasRegion branch = overviewTextures.peekSurfaceBranch(level, nodeX, nodeZ);
        boolean nodeCovered = inheritedCoverage;
        if (branch != null) {
            addNodeQuad(builder, branch, nodeX, nodeZ, level);
            nodeCovered = true;
        } else if (!nodeCovered) {
            // Never expose a black hole while the requested LOD/exact leaf is
            // pending. A coarser GPU-resident ancestor remains as an underlay and
            // is cropped to this node until finer data is published.
            CaveAtlasRegion ancestor = findSurfaceAncestor(
                    overviewTextures, level, nodeX, nodeZ);
            if (ancestor != null) {
                addAncestorQuad(builder, ancestor, level, nodeX, nodeZ);
                nodeCovered = true;
            }
        }

        // Source exists but neither an exact descendant nor a branch is resident
        // yet. Record pending once per overlapping region.
        // Descending to every leaf only repeats cache/file lookups and turned a
        // sparse 2,405-page far view into a 20 ms render-thread plan build.
        if (branch == null && !exactResident) {
            if (collectPending) recordPendingSurfaceRegions(builder, manager,
                    Math.max(firstPageX, minPageX), Math.min(lastPageX, maxPageX),
                    Math.max(firstPageZ, minPageZ), Math.min(lastPageZ, maxPageZ));
            return;
        }

        if (level == 1) {
            for (int childX = 0; childX < 2; childX++) {
                for (int childZ = 0; childZ < 2; childZ++) {
                    int childIndex = childZ * 2 + childX;
                    int pageX = firstPageX + childX;
                    int pageZ = firstPageZ + childZ;
                    if (pageX < minPageX || pageX > maxPageX
                            || pageZ < minPageZ || pageZ > maxPageZ) continue;
                    /*
                     * At far zoom the density-correct L1 texture is the primary
                     * representation, not merely an underlay beneath a minified
                     * nearest-filtered L0 page. Keep exact leaves only as a
                     * progressive fallback for quadrants that the branch has not
                     * captured yet. Once the L1 child is complete, drawing exact on
                     * top would reintroduce the aliasing/softness this path exists
                     * to remove.
                     */
                    boolean branchPrimary = branch != null
                            && MapSurfaceRenderPolicy.useBranchInsteadOfExact(
                                    scale, level, branch.childKnown(childIndex));
                    if (branchPrimary && branch.childComplete(childIndex)) continue;
                    /*
                     * A partial L1 texture is transparent outside its known texels.
                     * Keep the exact leaf underneath it instead of drawing exact on
                     * top. The branch therefore owns every known pixel while exact
                     * fills only the still-unknown part, avoiding both black holes
                     * and the old nearest-filtered L0 override.
                     */
                    int phase = branchPrimary
                            ? MapRenderPlan.PHASE_L1_EXACT_UNDERLAY
                            : MapRenderPlan.PHASE_EXACT;
                    collectSurfaceLeaf(builder, surfaceTextures, pageX, pageZ,
                            manager, collectPending, phase, false);
                }
            }
            return;
        }
        for (int childX = 0; childX < 2; childX++) {
            for (int childZ = 0; childZ < 2; childZ++) {
                int childIndex = childZ * 2 + childX;
                int childNodeX = nodeX * 2 + childX;
                int childNodeZ = nodeZ * 2 + childZ;
                if (branch != null && branch.childComplete(childIndex)
                        && !surfaceTextures.hasResidentPageInNode(level - 1,
                                childNodeX, childNodeZ)) continue;
                collectSurfaceNode(builder, overviewTextures, surfaceTextures,
                        level - 1, childNodeX, childNodeZ,
                        minPageX, maxPageX, minPageZ, maxPageZ,
                        scale, manager, collectPending, nodeCovered);
            }
        }
    }

    private boolean surfaceNodeHasSource(MapRenderPlan.Builder builder,
            MapManager manager, int firstPageX, int lastPageX,
            int firstPageZ, int lastPageZ) {
        int firstRegionX = Math.floorDiv(firstPageX,
                MapPageLayout.PAGES_PER_REGION);
        int lastRegionX = Math.floorDiv(lastPageX,
                MapPageLayout.PAGES_PER_REGION);
        int firstRegionZ = Math.floorDiv(firstPageZ,
                MapPageLayout.PAGES_PER_REGION);
        int lastRegionZ = Math.floorDiv(lastPageZ,
                MapPageLayout.PAGES_PER_REGION);
        for (int regionZ = firstRegionZ; regionZ <= lastRegionZ; regionZ++) {
            for (int regionX = firstRegionX; regionX <= lastRegionX; regionX++) {
                if (builder.sourceRegionAvailable(manager, regionX, regionZ)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void recordPendingSurfaceRegions(MapRenderPlan.Builder builder,
            MapManager manager, int firstPageX, int lastPageX,
            int firstPageZ, int lastPageZ) {
        int firstRegionX = Math.floorDiv(firstPageX,
                MapPageLayout.PAGES_PER_REGION);
        int lastRegionX = Math.floorDiv(lastPageX,
                MapPageLayout.PAGES_PER_REGION);
        int firstRegionZ = Math.floorDiv(firstPageZ,
                MapPageLayout.PAGES_PER_REGION);
        int lastRegionZ = Math.floorDiv(lastPageZ,
                MapPageLayout.PAGES_PER_REGION);
        for (int regionZ = firstRegionZ; regionZ <= lastRegionZ; regionZ++) {
            for (int regionX = firstRegionX; regionX <= lastRegionX; regionX++) {
                if (builder.sourceRegionAvailable(manager, regionX, regionZ)) {
                    builder.pending(regionX, regionZ);
                }
            }
        }
    }

    private boolean collectSurfaceLeaf(MapRenderPlan.Builder builder,
            MapTextureManager surfaceTextures, int globalPageX, int globalPageZ,
            MapManager manager, boolean collectPending) {
        return collectSurfaceLeaf(builder, surfaceTextures, globalPageX, globalPageZ,
                manager, collectPending, MapRenderPlan.PHASE_EXACT, false);
    }

    private boolean collectSurfaceLeaf(MapRenderPlan.Builder builder,
            MapTextureManager surfaceTextures, int globalPageX, int globalPageZ,
            MapManager manager, boolean collectPending, int phase,
            boolean logicalExactCoverage) {
        int rx = Math.floorDiv(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int rz = Math.floorDiv(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        int px = Math.floorMod(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int pz = Math.floorMod(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        CaveAtlasRegion page = surfaceTextures.peekPageRegion(rx, rz, px, pz);
        if (page != null) {
            addPageQuad(builder, page, rx, rz, px, pz, phase);
            return true;
        }
        if (logicalExactCoverage) {
            addLogicalSurfacePageQuad(builder, globalPageX, globalPageZ, phase);
        }
        if (collectPending && builder.sourceRegionAvailable(manager, rx, rz)) {
            builder.pending(rx, rz);
        }
        return false;
    }

    private boolean collectSurfaceRegionPages(MapRenderPlan.Builder builder,
            MapTextureManager surfaceTextures, int rx, int rz) {
        boolean drew = false;
        for (int pz = 0; pz < MapPageLayout.PAGES_PER_REGION; pz++) {
            for (int px = 0; px < MapPageLayout.PAGES_PER_REGION; px++) {
                CaveAtlasRegion page = surfaceTextures.peekPageRegion(rx, rz, px, pz);
                if (page == null) continue;
                addPageQuad(builder, page, rx, rz, px, pz,
                        MapRenderPlan.PHASE_EXACT);
                drew = true;
            }
        }
        return drew;
    }

    private void collectSurfaceGlowLeaf(MapRenderPlan.Builder builder,
            MapTextureManager surfaceTextures, int globalPageX, int globalPageZ) {
        collectSurfaceGlowLeaf(builder, surfaceTextures, globalPageX, globalPageZ, false);
    }

    private void collectSurfaceGlowLeaf(MapRenderPlan.Builder builder,
            MapTextureManager surfaceTextures, int globalPageX, int globalPageZ,
            boolean logicalExactCoverage) {
        int rx = Math.floorDiv(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int rz = Math.floorDiv(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        int px = Math.floorMod(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int pz = Math.floorMod(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        CaveAtlasRegion page = surfaceTextures.peekGlowPageRegion(rx, rz, px, pz);
        if (page != null) {
            addPageQuad(builder, page, rx, rz, px, pz, MapRenderPlan.PHASE_GLOW);
        } else if (logicalExactCoverage) {
            addLogicalSurfacePageQuad(builder, globalPageX, globalPageZ,
                    MapRenderPlan.PHASE_GLOW);
        }
    }

    private void collectSurfaceGlowPages(MapRenderPlan.Builder builder,
            MapTextureManager surfaceTextures, int rx, int rz) {
        for (int pz = 0; pz < MapPageLayout.PAGES_PER_REGION; pz++) {
            for (int px = 0; px < MapPageLayout.PAGES_PER_REGION; px++) {
                CaveAtlasRegion page = surfaceTextures.peekGlowPageRegion(rx, rz, px, pz);
                if (page != null) addPageQuad(builder, page, rx, rz, px, pz,
                        MapRenderPlan.PHASE_GLOW);
            }
        }
    }

    private void addLogicalCavePageQuad(MapRenderPlan.Builder builder, TileKey key,
            int globalPageX, int globalPageZ, int phase) {
        if (key == null) return;
        builder.addTile(key, phase,
                globalPageX * MapPageLayout.PAGE_SIZE,
                globalPageZ * MapPageLayout.PAGE_SIZE,
                MapPageLayout.PAGE_SIZE, MapPageLayout.PAGE_SIZE);
    }

    private void addLogicalSurfacePageQuad(MapRenderPlan.Builder builder,
            int globalPageX, int globalPageZ, int phase) {
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp == null) return;
        int variant = phase >= MapRenderPlan.PHASE_GLOW
                ? TileKey.VARIANT_SURFACE_GLOW
                : TileKey.VARIANT_SURFACE_EXACT;
        TileKey key = new TileKey(stamp.sessionId(), 0, 0,
                globalPageX, globalPageZ, variant);
        addLogicalSurfaceSubtiles(builder, key, globalPageX, globalPageZ, phase);
    }

    /**
     * Keep one retained logical page in the immutable render plan. The front page
     * table already carries the 4x4 complete-subtile mask; replay resolves that
     * mask and emits one quad for a complete page or only the known 16x16 cells for
     * a partial page. PASS97 expanded every page into sixteen plan instances, so a
     * 345-page viewport built/sorted 5,520 entries on the Render thread.
     */
    private void addLogicalSurfaceSubtiles(MapRenderPlan.Builder builder,
            TileKey key, int globalPageX, int globalPageZ, int phase) {
        builder.addTile(key, phase,
                globalPageX * MapPageLayout.PAGE_SIZE,
                globalPageZ * MapPageLayout.PAGE_SIZE,
                MapPageLayout.PAGE_SIZE, MapPageLayout.PAGE_SIZE,
                0.0f, 0.0f, 1.0f, 1.0f,
                MapGpuInstancePlan.DYNAMIC_SURFACE_SUBTILES);
    }

    /** Cave exact leaves use the same logical page-table indirection as Surface.
     * Atlas slot reuse and storage recreation therefore no longer invalidate cached
     * world geometry or risk sampling another cave page's slot. */
    private void addCavePageQuad(MapRenderPlan.Builder builder, TileKey key,
            CaveAtlasRegion region, int regionX, int regionZ,
            int pageX, int pageZ, int phase) {
        boolean added;
        if (key != null) {
            added = builder.addTile(key, phase,
                    regionX * 512 + pageX * 64,
                    regionZ * 512 + pageZ * 64, 64, 64);
        } else {
            added = builder.add(region.texture(), phase,
                    regionX * 512 + pageX * 64,
                    regionZ * 512 + pageZ * 64,
                    64, 64, region.sourceX(), region.sourceY(),
                    region.sourceSize(), region.sourceSize(),
                    region.atlasSize(), region.atlasSize());
        }
        if (added) builder.exact();
    }

    private void addPageQuad(MapRenderPlan.Builder builder, CaveAtlasRegion region,
            int regionX, int regionZ, int pageX, int pageZ, int phase) {
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        int globalPageX = regionX * MapPageLayout.PAGES_PER_REGION + pageX;
        int globalPageZ = regionZ * MapPageLayout.PAGES_PER_REGION + pageZ;
        int variant = phase >= MapRenderPlan.PHASE_GLOW
                ? TileKey.VARIANT_SURFACE_GLOW
                : TileKey.VARIANT_SURFACE_EXACT;
        boolean added = false;
        if (stamp != null) {
            int before = builder.entryCount();
            addLogicalSurfaceSubtiles(builder,
                    new TileKey(stamp.sessionId(), 0, 0,
                            globalPageX, globalPageZ, variant),
                    globalPageX, globalPageZ, phase);
            added = builder.entryCount() > before;
        } else {
            int mask = exactSubtileMask(region);
            float sourceStep = region.sourceSize()
                    / (float) MapPageLayout.SUBTILES_PER_PAGE;
            int pageWorldX = regionX * 512 + pageX * MapPageLayout.PAGE_SIZE;
            int pageWorldZ = regionZ * 512 + pageZ * MapPageLayout.PAGE_SIZE;
            for (int subtileZ = 0; subtileZ < MapPageLayout.SUBTILES_PER_PAGE;
                    subtileZ++) {
                for (int subtileX = 0; subtileX < MapPageLayout.SUBTILES_PER_PAGE;
                        subtileX++) {
                    int subtile = MapPageLayout.subtileIndex(subtileX, subtileZ);
                    if ((mask & (1 << subtile)) == 0) continue;
                    added |= builder.add(region.texture(), phase,
                            pageWorldX + subtileX * MapPageLayout.SUBTILE_SIZE,
                            pageWorldZ + subtileZ * MapPageLayout.SUBTILE_SIZE,
                            MapPageLayout.SUBTILE_SIZE, MapPageLayout.SUBTILE_SIZE,
                            region.sourceX() + subtileX * sourceStep,
                            region.sourceY() + subtileZ * sourceStep,
                            Math.round(sourceStep), Math.round(sourceStep),
                            region.atlasSize(), region.atlasSize());
                }
            }
        }
        if (added && (phase == MapRenderPlan.PHASE_EXACT
                || phase == MapRenderPlan.PHASE_L1_EXACT_UNDERLAY)) builder.exact();
    }

    private static int exactSubtileMask(CaveAtlasRegion region) {
        if (region == null) return 0;
        long complete = region.completeMask();
        return complete == -1L ? MapPageLayout.FULL_SUBTILE_MASK
                : (int) complete & MapPageLayout.FULL_SUBTILE_MASK;
    }

    private void addNodeQuad(MapRenderPlan.Builder builder, CaveAtlasRegion region,
            int nodeX, int nodeZ, int level) {
        int worldSize = region.worldSize();
        if (builder.add(region.texture(), MapRenderPlan.branchPhase(level),
                nodeX * worldSize, nodeZ * worldSize,
                worldSize, worldSize, region.sourceX(), region.sourceY(),
                region.sourceSize(), region.sourceSize(),
                region.atlasSize(), region.atlasSize())) {
            builder.branch();
        }
    }

    private void addRegionLodQuad(MapRenderPlan.Builder builder,
            CaveAtlasRegion region, int nodeX, int nodeZ) {
        int worldSize = region.worldSize();
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        boolean added;
        if (stamp != null) {
            added = builder.addTile(new TileKey(stamp.sessionId(),
                            RegionSurfaceLodService.PROJECTION_SURFACE,
                            region.level(), nodeX, nodeZ,
                            TileKey.VARIANT_SURFACE_BRANCH),
                    MapRenderPlan.PHASE_REGION_COARSE,
                    nodeX * worldSize, nodeZ * worldSize,
                    worldSize, worldSize);
        } else {
            added = builder.add(region.texture(),
                    MapRenderPlan.PHASE_REGION_COARSE,
                    nodeX * worldSize, nodeZ * worldSize,
                    worldSize, worldSize, region.sourceX(), region.sourceY(),
                    region.sourceSize(), region.sourceSize(),
                    region.atlasSize(), region.atlasSize());
        }
        if (added) builder.branch();
    }

    private void addRegionLodAncestorQuad(MapRenderPlan.Builder builder,
            CaveAtlasRegion ancestor, int targetLevel,
            int nodeX, int nodeZ) {
        int difference = ancestor.level() - targetLevel;
        if (difference <= 0) {
            addRegionLodQuad(builder, ancestor, nodeX, nodeZ);
            return;
        }
        int subdivision = 1 << difference;
        int sourceSize = Math.max(1, ancestor.sourceSize() / subdivision);
        int localX = Math.floorMod(nodeX, subdivision);
        int localZ = Math.floorMod(nodeZ, subdivision);
        int worldSize = MapRegionLodPolicy.worldSize(targetLevel);
        if (builder.add(ancestor.texture(), MapRenderPlan.PHASE_REGION_COARSE,
                nodeX * worldSize, nodeZ * worldSize,
                worldSize, worldSize,
                ancestor.sourceX() + localX * sourceSize,
                ancestor.sourceY() + localZ * sourceSize,
                sourceSize, sourceSize,
                ancestor.atlasSize(), ancestor.atlasSize())) {
            builder.branch();
        }
    }

    private void addAncestorQuad(MapRenderPlan.Builder builder,
            CaveAtlasRegion ancestor, int targetLevel, int nodeX, int nodeZ) {
        int difference = ancestor.level() - targetLevel;
        if (difference <= 0) {
            addNodeQuad(builder, ancestor, nodeX, nodeZ, targetLevel);
            return;
        }
        int subdivision = 1 << difference;
        int sourceSize = Math.max(1, ancestor.sourceSize() / subdivision);
        int localX = Math.floorMod(nodeX, subdivision);
        int localZ = Math.floorMod(nodeZ, subdivision);
        int worldSize = MapLodPolicy.worldSizeForBranch(targetLevel);
        if (builder.add(ancestor.texture(), MapRenderPlan.branchPhase(targetLevel),
                nodeX * worldSize, nodeZ * worldSize, worldSize, worldSize,
                ancestor.sourceX() + localX * sourceSize,
                ancestor.sourceY() + localZ * sourceSize,
                sourceSize, sourceSize, ancestor.atlasSize(), ancestor.atlasSize())) {
            builder.branch();
        }
    }

    private void drawRegion(GuiGraphics guiGraphics, ResourceLocation texture, int regionX, int regionZ) {
        if (texture == null) return;
        RenderSystem.setShaderTexture(0, texture);
        guiGraphics.blit(texture, regionX * 512, regionZ * 512, 512, 512,
                0f, 0f, 512, 512, 512, 512);
    }

    private void drawPage(GuiGraphics guiGraphics, ResourceLocation texture, int regionX, int regionZ, int pageX, int pageZ) {
        if (texture == null) return;
        RenderSystem.setShaderTexture(0, texture);
        int x = regionX * 512 + pageX * 64;
        int z = regionZ * 512 + pageZ * 64;
        guiGraphics.blit(texture, x, z, 64, 64, 0f, 0f, 64, 64, 64, 64);
    }


    private void drawAtlasPage(GuiGraphics guiGraphics, CaveAtlasRegion region,
            int regionX, int regionZ, int pageX, int pageZ) {
        if (region == null) return;
        RenderSystem.setShaderTexture(0, region.texture());
        int pageWorldX = regionX * 512 + pageX * MapPageLayout.PAGE_SIZE;
        int pageWorldZ = regionZ * 512 + pageZ * MapPageLayout.PAGE_SIZE;
        int mask = exactSubtileMask(region);
        float sourceStep = region.sourceSize()
                / (float) MapPageLayout.SUBTILES_PER_PAGE;
        for (int subtileZ = 0; subtileZ < MapPageLayout.SUBTILES_PER_PAGE;
                subtileZ++) {
            for (int subtileX = 0; subtileX < MapPageLayout.SUBTILES_PER_PAGE;
                    subtileX++) {
                int subtile = MapPageLayout.subtileIndex(subtileX, subtileZ);
                if ((mask & (1 << subtile)) == 0) continue;
                guiGraphics.blit(region.texture(),
                        pageWorldX + subtileX * MapPageLayout.SUBTILE_SIZE,
                        pageWorldZ + subtileZ * MapPageLayout.SUBTILE_SIZE,
                        MapPageLayout.SUBTILE_SIZE, MapPageLayout.SUBTILE_SIZE,
                        region.sourceX() + subtileX * sourceStep,
                        region.sourceY() + subtileZ * sourceStep,
                        Math.round(sourceStep), Math.round(sourceStep),
                        region.atlasSize(), region.atlasSize());
            }
        }
    }

    private void drawAtlasRegion(GuiGraphics guiGraphics, CaveAtlasRegion region,
            int regionX, int regionZ) {
        if (region == null) return;
        RenderSystem.setShaderTexture(0, region.texture());
        guiGraphics.blit(region.texture(), regionX * 512, regionZ * 512, 512, 512,
                region.sourceX(), region.sourceY(),
                region.sourceSize(), region.sourceSize(),
                region.atlasSize(), region.atlasSize());
    }

    private void drawCaveHierarchy(GuiGraphics guiGraphics,
            CaveHierarchySource source,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            int level, float scale, boolean branchOnly,
            int focusPageX, int focusPageZ,
            LongKeySet drawnRegions, RenderStats stats) {
        int minPageX = minRegionX * MapPageLayout.PAGES_PER_REGION;
        int maxPageX = (maxRegionX + 1) * MapPageLayout.PAGES_PER_REGION - 1;
        int minPageZ = minRegionZ * MapPageLayout.PAGES_PER_REGION;
        int maxPageZ = (maxRegionZ + 1) * MapPageLayout.PAGES_PER_REGION - 1;

        if (level <= 0) {
            if (branchOnly) return;
            int clampedFocusPageX = clamp(focusPageX, minPageX, maxPageX);
            int clampedFocusPageZ = clamp(focusPageZ, minPageZ, maxPageZ);
            int maximumRadius = gridRadius(minPageX, maxPageX, minPageZ, maxPageZ,
                    clampedFocusPageX, clampedFocusPageZ);
            for (int radius = 0; radius <= maximumRadius; radius++) {
                for (int pageZ = clampedFocusPageZ - radius;
                        pageZ <= clampedFocusPageZ + radius; pageZ++) {
                    for (int pageX = clampedFocusPageX - radius;
                            pageX <= clampedFocusPageX + radius; pageX++) {
                        if (!onRing(pageX, pageZ, clampedFocusPageX,
                                clampedFocusPageZ, radius)) continue;
                        if (pageX < minPageX || pageX > maxPageX
                                || pageZ < minPageZ || pageZ > maxPageZ) continue;
                        drawCaveLeafPage(guiGraphics, source, pageX, pageZ, scale,
                                drawnRegions, stats);
                    }
                }
            }
            return;
        }

        int pageSpan = MapLodPolicy.pageSpanForBranch(level);
        int minNodeX = Math.floorDiv(minPageX, pageSpan);
        int maxNodeX = Math.floorDiv(maxPageX, pageSpan);
        int minNodeZ = Math.floorDiv(minPageZ, pageSpan);
        int maxNodeZ = Math.floorDiv(maxPageZ, pageSpan);
        int focusNodeX = clamp(Math.floorDiv(focusPageX, pageSpan), minNodeX, maxNodeX);
        int focusNodeZ = clamp(Math.floorDiv(focusPageZ, pageSpan), minNodeZ, maxNodeZ);
        int maximumRadius = gridRadius(minNodeX, maxNodeX, minNodeZ, maxNodeZ,
                focusNodeX, focusNodeZ);
        for (int radius = 0; radius <= maximumRadius; radius++) {
            for (int nodeZ = focusNodeZ - radius; nodeZ <= focusNodeZ + radius; nodeZ++) {
                for (int nodeX = focusNodeX - radius; nodeX <= focusNodeX + radius; nodeX++) {
                    if (!onRing(nodeX, nodeZ, focusNodeX, focusNodeZ, radius)) continue;
                    if (nodeX < minNodeX || nodeX > maxNodeX
                            || nodeZ < minNodeZ || nodeZ > maxNodeZ) continue;
                    drawCaveNode(guiGraphics, source, level, nodeX, nodeZ, scale,
                            branchOnly, minPageX, maxPageX, minPageZ, maxPageZ,
                            drawnRegions, stats);
                }
            }
        }
    }

    private void drawCaveNode(GuiGraphics guiGraphics, CaveHierarchySource source,
            int level, int nodeX, int nodeZ, float scale, boolean branchOnly,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ,
            LongKeySet drawnRegions, RenderStats stats) {
        int pageSpan = MapLodPolicy.pageSpanForBranch(level);
        int firstPageX = nodeX * pageSpan;
        int firstPageZ = nodeZ * pageSpan;
        int lastPageX = firstPageX + pageSpan - 1;
        int lastPageZ = firstPageZ + pageSpan - 1;
        if (lastPageX < minPageX || firstPageX > maxPageX
                || lastPageZ < minPageZ || firstPageZ > maxPageZ) return;

        CaveAtlasRegion branch = source.branch(level, nodeX, nodeZ);
        CaveAtlasRegion ancestorFallback = branch == null
                ? findCaveAncestor(source, level, nodeX, nodeZ) : null;
        if (branchOnly) {
            if (level == 1) {
                // Legacy/direct replay follows the same ordering as the immutable
                // plan: exact incomplete children first, then branch/ancestor on top.
                for (int childX = 0; childX < 2; childX++) {
                    for (int childZ = 0; childZ < 2; childZ++) {
                        int childIndex = childZ * 2 + childX;
                        if (branch != null && branch.childComplete(childIndex)) {
                            continue;
                        }
                        int pageX = firstPageX + childX;
                        int pageZ = firstPageZ + childZ;
                        if (pageX < minPageX || pageX > maxPageX
                                || pageZ < minPageZ || pageZ > maxPageZ) continue;
                        drawCaveLeafPage(guiGraphics, source, pageX, pageZ, scale,
                                drawnRegions, stats);
                    }
                }
                if (branch != null) {
                    drawAtlasNode(guiGraphics, branch, nodeX, nodeZ);
                    stats.branchNodes++;
                    markDrawnPageRange(drawnRegions,
                            Math.max(firstPageX, minPageX),
                            Math.min(lastPageX, maxPageX),
                            Math.max(firstPageZ, minPageZ),
                            Math.min(lastPageZ, maxPageZ));
                } else if (ancestorFallback != null) {
                    drawCaveAncestorSubRect(guiGraphics, ancestorFallback,
                            level, nodeX, nodeZ);
                    stats.branchNodes++;
                    markDrawnPageRange(drawnRegions,
                            Math.max(firstPageX, minPageX),
                            Math.min(lastPageX, maxPageX),
                            Math.max(firstPageZ, minPageZ),
                            Math.min(lastPageZ, maxPageZ));
                }
                return;
            }
            if (branch != null) {
                drawAtlasNode(guiGraphics, branch, nodeX, nodeZ);
                stats.branchNodes++;
                markDrawnPageRange(drawnRegions,
                        Math.max(firstPageX, minPageX), Math.min(lastPageX, maxPageX),
                        Math.max(firstPageZ, minPageZ), Math.min(lastPageZ, maxPageZ));
                return;
            }
            if (ancestorFallback != null) {
                drawCaveAncestorSubRect(guiGraphics, ancestorFallback,
                        level, nodeX, nodeZ);
                stats.branchNodes++;
                markDrawnPageRange(drawnRegions,
                        Math.max(firstPageX, minPageX), Math.min(lastPageX, maxPageX),
                        Math.max(firstPageZ, minPageZ), Math.min(lastPageZ, maxPageZ));
                return;
            }
            if (!source.hasResidentPageInNode(level, nodeX, nodeZ)) return;
            int childPageSpan = MapLodPolicy.pageSpanForBranch(level - 1);
            for (int childX = 0; childX < 2; childX++) {
                for (int childZ = 0; childZ < 2; childZ++) {
                    int childNodeX = nodeX * 2 + childX;
                    int childNodeZ = nodeZ * 2 + childZ;
                    int childFirstPageX = childNodeX * childPageSpan;
                    int childFirstPageZ = childNodeZ * childPageSpan;
                    if (childFirstPageX > maxPageX
                            || childFirstPageX + childPageSpan - 1 < minPageX
                            || childFirstPageZ > maxPageZ
                            || childFirstPageZ + childPageSpan - 1 < minPageZ) continue;
                    drawCaveNode(guiGraphics, source, level - 1,
                            childNodeX, childNodeZ, scale, true,
                            minPageX, maxPageX, minPageZ, maxPageZ,
                            drawnRegions, stats);
                }
            }
            return;
        }

        if (ancestorFallback != null) {
            drawCaveAncestorSubRect(guiGraphics, ancestorFallback,
                    level, nodeX, nodeZ);
            stats.branchNodes++;
            markDrawnPageRange(drawnRegions,
                    Math.max(firstPageX, minPageX), Math.min(lastPageX, maxPageX),
                    Math.max(firstPageZ, minPageZ), Math.min(lastPageZ, maxPageZ));
        }

        // A missing branch is not allowed to hide resident exact pages at close and
        // medium zoom. Query the exact-page authority once and stop only when neither
        // branch nor resident child data exists.
        if (branch == null && ancestorFallback == null
                && !source.hasResidentPageInNode(level, nodeX, nodeZ)) {
            return;
        }
        if (branch != null) {
            drawAtlasNode(guiGraphics, branch, nodeX, nodeZ);
            stats.branchNodes++;
        }

        if (level == 1) {
            for (int childX = 0; childX < 2; childX++) {
                for (int childZ = 0; childZ < 2; childZ++) {
                    int childIndex = childZ * 2 + childX;
                    int pageX = firstPageX + childX;
                    int pageZ = firstPageZ + childZ;
                    if (pageX < minPageX || pageX > maxPageX
                            || pageZ < minPageZ || pageZ > maxPageZ) continue;
                    boolean exactDrawn = drawCaveLeafPage(guiGraphics, source,
                            pageX, pageZ, scale, drawnRegions, stats);
                    if (exactDrawn) continue;
                    if (branch != null && branch.childComplete(childIndex)) {
                        markPageDrawn(drawnRegions, pageX, pageZ);
                        continue;
                    }
                }
            }
            return;
        }

        int childPageSpan = MapLodPolicy.pageSpanForBranch(level - 1);
        for (int childX = 0; childX < 2; childX++) {
            for (int childZ = 0; childZ < 2; childZ++) {
                int childIndex = childZ * 2 + childX;
                int childNodeX = nodeX * 2 + childX;
                int childNodeZ = nodeZ * 2 + childZ;
                int childFirstPageX = childNodeX * childPageSpan;
                int childFirstPageZ = childNodeZ * childPageSpan;
                if (branch != null && branch.childComplete(childIndex)
                        && !source.hasResidentPageInNode(
                                level - 1, childNodeX, childNodeZ)) {
                    markDrawnPageRange(drawnRegions,
                            Math.max(childFirstPageX, minPageX),
                            Math.min(childFirstPageX + childPageSpan - 1, maxPageX),
                            Math.max(childFirstPageZ, minPageZ),
                            Math.min(childFirstPageZ + childPageSpan - 1, maxPageZ));
                    continue;
                }
                drawCaveNode(guiGraphics, source, level - 1,
                        childNodeX, childNodeZ, scale, false,
                        minPageX, maxPageX, minPageZ, maxPageZ, drawnRegions, stats);
            }
        }
    }

    private boolean drawCaveLeafPage(GuiGraphics guiGraphics, CaveHierarchySource source,
            int globalPageX, int globalPageZ, float scale, LongKeySet drawnRegions,
            RenderStats stats) {
        int rx = Math.floorDiv(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int rz = Math.floorDiv(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        int px = Math.floorMod(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int pz = Math.floorMod(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        CaveAtlasRegion page = source.page(rx, rz, px, pz, scale);
        if (page == null) return false;
        drawAtlasPage(guiGraphics, page, rx, rz, px, pz);
        drawnRegions.add(packRegion(rx, rz));
        stats.exactPages++;
        return true;
    }

    private CaveAtlasRegion findSurfaceAncestor(
            MapOverviewTextureManager source, int targetLevel,
            int targetNodeX, int targetNodeZ) {
        for (int ancestorLevel = targetLevel + 1;
                ancestorLevel <= SurfaceLodTree.MAX_LEVEL; ancestorLevel++) {
            int shift = ancestorLevel - targetLevel;
            int ancestorX = Math.floorDiv(targetNodeX, 1 << shift);
            int ancestorZ = Math.floorDiv(targetNodeZ, 1 << shift);
            CaveAtlasRegion ancestor = source.peekSurfaceBranch(
                    ancestorLevel, ancestorX, ancestorZ);
            if (ancestor != null) return ancestor;
        }
        return null;
    }

    private static final class CaveTraversalBudget {
        private int remainingVisits;
        private int remainingQuads;

        private CaveTraversalBudget(int remainingVisits, int remainingQuads) {
            this.remainingVisits = remainingVisits;
            this.remainingQuads = remainingQuads;
        }

        private static CaveTraversalBudget unbounded() {
            return new CaveTraversalBudget(Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        private boolean visit() {
            if (remainingVisits <= 0) return false;
            remainingVisits--;
            return true;
        }

        private boolean emitQuad() {
            if (remainingQuads <= 0) return false;
            remainingQuads--;
            return true;
        }

        private void refundQuad() {
            if (remainingQuads < Integer.MAX_VALUE) remainingQuads++;
        }

        private boolean exhausted() {
            return remainingVisits <= 0 || remainingQuads <= 0;
        }
    }

    private CaveAtlasRegion findCaveAncestor(CaveHierarchySource source,
            int level, int nodeX, int nodeZ) {
        for (int ancestorLevel = level + 1;
                ancestorLevel <= MapLodPolicy.MAX_BRANCH_LEVEL; ancestorLevel++) {
            int scale = 1 << (ancestorLevel - level);
            CaveAtlasRegion ancestor = source.branch(ancestorLevel,
                    Math.floorDiv(nodeX, scale), Math.floorDiv(nodeZ, scale));
            if (ancestor != null) return ancestor;
        }
        return null;
    }

    /** Draws the descendant node's sub-rectangle from a coarser resident ancestor. */
    private void drawCaveAncestorSubRect(GuiGraphics guiGraphics,
            CaveAtlasRegion ancestor, int targetLevel, int nodeX, int nodeZ) {
        int levelDifference = ancestor.level() - targetLevel;
        if (levelDifference <= 0) {
            drawAtlasNode(guiGraphics, ancestor, nodeX, nodeZ);
            return;
        }
        int subdivision = 1 << levelDifference;
        int sourceSize = Math.max(1, ancestor.sourceSize() / subdivision);
        int localX = Math.floorMod(nodeX, subdivision);
        int localZ = Math.floorMod(nodeZ, subdivision);
        float sourceX = ancestor.sourceX() + localX * sourceSize;
        float sourceY = ancestor.sourceY() + localZ * sourceSize;
        int worldSize = MapLodPolicy.worldSizeForBranch(targetLevel);
        RenderSystem.setShaderTexture(0, ancestor.texture());
        guiGraphics.blit(ancestor.texture(), nodeX * worldSize, nodeZ * worldSize,
                worldSize, worldSize, sourceX, sourceY, sourceSize, sourceSize,
                ancestor.atlasSize(), ancestor.atlasSize());
    }

    private void drawAtlasNode(GuiGraphics guiGraphics, CaveAtlasRegion region,
            int nodeX, int nodeZ) {
        RenderSystem.setShaderTexture(0, region.texture());
        int worldSize = region.worldSize();
        guiGraphics.blit(region.texture(), nodeX * worldSize, nodeZ * worldSize,
                worldSize, worldSize,
                region.sourceX(), region.sourceY(),
                region.sourceSize(), region.sourceSize(),
                region.atlasSize(), region.atlasSize());
    }

    private static void markPageDrawn(LongKeySet output, int pageX, int pageZ) {
        output.add(packRegion(
                Math.floorDiv(pageX, MapPageLayout.PAGES_PER_REGION),
                Math.floorDiv(pageZ, MapPageLayout.PAGES_PER_REGION)));
    }

    private static void markDrawnPageRange(LongKeySet output,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ) {
        if (maxPageX < minPageX || maxPageZ < minPageZ) return;
        int minRegionX = Math.floorDiv(minPageX, MapPageLayout.PAGES_PER_REGION);
        int maxRegionX = Math.floorDiv(maxPageX, MapPageLayout.PAGES_PER_REGION);
        int minRegionZ = Math.floorDiv(minPageZ, MapPageLayout.PAGES_PER_REGION);
        int maxRegionZ = Math.floorDiv(maxPageZ, MapPageLayout.PAGES_PER_REGION);
        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                output.add(packRegion(rx, rz));
            }
        }
    }

    private interface CaveHierarchySource {
        CaveAtlasRegion branch(int level, int nodeX, int nodeZ);

        boolean hasBranchData(int level, int nodeX, int nodeZ);

        boolean hasResidentPageInNode(int level, int nodeX, int nodeZ);

        boolean allowExact(int globalPageX, int globalPageZ);

        CaveAtlasRegion page(int regionX, int regionZ,
                int pageX, int pageZ, float scale);

        TileKey pageKey(int globalPageX, int globalPageZ, float scale);

        default CaveLeafSelection fallbackPage(int regionX, int regionZ,
                int pageX, int pageZ, float scale) {
            return null;
        }
    }

    private record CaveLeafSelection(CaveAtlasRegion region, TileKey key) {
    }

    private void drawSurfaceHierarchy(GuiGraphics guiGraphics,
            MapOverviewTextureManager overviewTextures,
            MapTextureManager surfaceTextures,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            int level, float scale, int focusPageX, int focusPageZ,
            Set<Long> pendingRegions, MapManager manager, RenderStats stats) {
        if (level <= 0) return;
        int minPageX = minRegionX * MapPageLayout.PAGES_PER_REGION;
        int maxPageX = (maxRegionX + 1) * MapPageLayout.PAGES_PER_REGION - 1;
        int minPageZ = minRegionZ * MapPageLayout.PAGES_PER_REGION;
        int maxPageZ = (maxRegionZ + 1) * MapPageLayout.PAGES_PER_REGION - 1;
        int pageSpan = MapLodPolicy.pageSpanForBranch(level);
        int minNodeX = Math.floorDiv(minPageX, pageSpan);
        int maxNodeX = Math.floorDiv(maxPageX, pageSpan);
        int minNodeZ = Math.floorDiv(minPageZ, pageSpan);
        int maxNodeZ = Math.floorDiv(maxPageZ, pageSpan);
        int focusNodeX = clamp(Math.floorDiv(focusPageX, pageSpan), minNodeX, maxNodeX);
        int focusNodeZ = clamp(Math.floorDiv(focusPageZ, pageSpan), minNodeZ, maxNodeZ);
        int maximumRadius = gridRadius(minNodeX, maxNodeX, minNodeZ, maxNodeZ,
                focusNodeX, focusNodeZ);
        for (int radius = 0; radius <= maximumRadius; radius++) {
            for (int nodeZ = focusNodeZ - radius; nodeZ <= focusNodeZ + radius; nodeZ++) {
                for (int nodeX = focusNodeX - radius; nodeX <= focusNodeX + radius; nodeX++) {
                    if (!onRing(nodeX, nodeZ, focusNodeX, focusNodeZ, radius)) continue;
                    if (nodeX < minNodeX || nodeX > maxNodeX
                            || nodeZ < minNodeZ || nodeZ > maxNodeZ) continue;
                    drawSurfaceNode(guiGraphics, overviewTextures, surfaceTextures,
                            level, nodeX, nodeZ, scale,
                            minPageX, maxPageX, minPageZ, maxPageZ,
                            pendingRegions, manager, stats);
                }
            }
        }
    }

    private void drawSurfaceNode(GuiGraphics guiGraphics,
            MapOverviewTextureManager overviewTextures,
            MapTextureManager surfaceTextures,
            int level, int nodeX, int nodeZ, float scale,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ,
            Set<Long> pendingRegions, MapManager manager, RenderStats stats) {
        int pageSpan = MapLodPolicy.pageSpanForBranch(level);
        int firstPageX = nodeX * pageSpan;
        int firstPageZ = nodeZ * pageSpan;
        int lastPageX = firstPageX + pageSpan - 1;
        int lastPageZ = firstPageZ + pageSpan - 1;
        if (lastPageX < minPageX || firstPageX > maxPageX
                || lastPageZ < minPageZ || firstPageZ > maxPageZ) return;

        CaveAtlasRegion branch = overviewTextures.peekSurfaceBranch(level, nodeX, nodeZ);
        boolean l1BranchOverlay = level == 1 && branch != null && scale < 0.50f;
        // Direct compatibility rendering has no phase sorter. At far L1 zoom, draw
        // exact fallback first and the partially transparent branch afterwards so
        // known L1 texels are authoritative while unknown texels keep exact data.
        if (branch != null && !l1BranchOverlay) {
            drawAtlasNode(guiGraphics, branch, nodeX, nodeZ);
            stats.branchNodes++;
        }

        if (level == 1) {
            for (int childX = 0; childX < 2; childX++) {
                for (int childZ = 0; childZ < 2; childZ++) {
                    int childIndex = childZ * 2 + childX;
                    int pageX = firstPageX + childX;
                    int pageZ = firstPageZ + childZ;
                    if (pageX < minPageX || pageX > maxPageX
                            || pageZ < minPageZ || pageZ > maxPageZ) continue;
                    boolean branchPrimary = branch != null
                            && MapSurfaceRenderPolicy.useBranchInsteadOfExact(
                                    scale, level, branch.childKnown(childIndex));
                    if (branchPrimary && branch.childComplete(childIndex)) continue;
                    drawSurfaceLeafPage(guiGraphics, surfaceTextures, pageX, pageZ,
                            pendingRegions, manager, stats);
                }
            }
            if (l1BranchOverlay) {
                drawAtlasNode(guiGraphics, branch, nodeX, nodeZ);
                stats.branchNodes++;
            }
            return;
        }

        for (int childX = 0; childX < 2; childX++) {
            for (int childZ = 0; childZ < 2; childZ++) {
                int childIndex = childZ * 2 + childX;
                int childNodeX = nodeX * 2 + childX;
                int childNodeZ = nodeZ * 2 + childZ;
                if (branch != null && branch.childComplete(childIndex)
                        && !surfaceTextures.hasResidentPageInNode(
                                level - 1, childNodeX, childNodeZ)) continue;
                drawSurfaceNode(guiGraphics, overviewTextures, surfaceTextures,
                        level - 1, childNodeX, childNodeZ, scale,
                        minPageX, maxPageX, minPageZ, maxPageZ,
                        pendingRegions, manager, stats);
            }
        }
    }

    private boolean drawSurfaceLeafPage(GuiGraphics guiGraphics,
            MapTextureManager surfaceTextures, int globalPageX, int globalPageZ,
            Set<Long> pendingRegions, MapManager manager, RenderStats stats) {
        int rx = Math.floorDiv(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int rz = Math.floorDiv(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        int px = Math.floorMod(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int pz = Math.floorMod(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        CaveAtlasRegion page = surfaceTextures.peekPageRegion(rx, rz, px, pz);
        if (page != null) {
            drawAtlasPage(guiGraphics, page, rx, rz, px, pz);
            stats.exactPages++;
            return true;
        }
        // Legacy 512x512 region textures are no longer a foreground authority.
        // Missing exact leaves remain covered by stable LOD branches or a loading
        // indicator until the exact page is published.
        if (manager.hasRegionFile(rx, rz) || manager.isRegionLoadedInCache(rx, rz)) {
            collectPendingRegion(pendingRegions, rx, rz);
        }
        return false;
    }

    private boolean drawSurfaceRegionWithPages(GuiGraphics guiGraphics,
            MapTextureManager surfaceTextures, int rx, int rz, float scale,
            RenderStats stats) {
        boolean drewAny = false;
        for (int pz = 0; pz < MapPageLayout.PAGES_PER_REGION; pz++) {
            for (int px = 0; px < MapPageLayout.PAGES_PER_REGION; px++) {
                CaveAtlasRegion pageTex = surfaceTextures.peekPageRegion(rx, rz, px, pz);
                if (pageTex == null) continue;
                drawAtlasPage(guiGraphics, pageTex, rx, rz, px, pz);
                drewAny = true;
                stats.exactPages++;
            }
        }
        return drewAny;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(Math.min(minimum, maximum),
                Math.min(Math.max(minimum, maximum), value));
    }

    private static int gridRadius(int minX, int maxX, int minZ, int maxZ,
            int focusX, int focusZ) {
        return Math.max(
                Math.max(Math.abs(focusX - minX), Math.abs(maxX - focusX)),
                Math.max(Math.abs(focusZ - minZ), Math.abs(maxZ - focusZ)));
    }

    private static boolean onRing(int x, int z, int focusX, int focusZ, int radius) {
        return Math.max(Math.abs(x - focusX), Math.abs(z - focusZ)) == radius;
    }

    private static long packRegion(int regionX, int regionZ) {
        return ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
    }

    private static void collectPendingRegion(Set<Long> pending, int regionX, int regionZ) {
        if (pending == null || pending.size() >= 256) return;
        pending.add(packRegion(regionX, regionZ));
    }

    /**
     * Loading granularity follows zoom: close views use 128/256-block cells,
     * normal views use one region, and far views group 2x2 or 4x4 regions.
     */
    private void drawLoadingIndicators(GuiGraphics guiGraphics,
            long[] pendingRegions, float mapScale) {
        if (pendingRegions == null || pendingRegions.length == 0
                || mapScale <= 0.0f) return;
        int cellBlocks = loadingCellBlocks(mapScale);
        int cellCount = 0;
        for (int pendingIndex = 0; pendingIndex < pendingRegions.length
                && pendingIndex < 256 && cellCount < loadingCellScratch.length;
                pendingIndex++) {
            long packed = pendingRegions[pendingIndex];
            int regionX = (int) (packed >> 32) * 512;
            int regionZ = (int) packed * 512;
            if (cellBlocks < 512) {
                for (int z = regionZ; z < regionZ + 512
                        && cellCount < loadingCellScratch.length; z += cellBlocks) {
                    for (int x = regionX; x < regionX + 512
                            && cellCount < loadingCellScratch.length; x += cellBlocks) {
                        cellCount = appendUniqueLoadingCell(
                                packCell(x, z), cellCount);
                    }
                }
            } else {
                int cellX = Math.floorDiv(regionX, cellBlocks) * cellBlocks;
                int cellZ = Math.floorDiv(regionZ, cellBlocks) * cellBlocks;
                cellCount = appendUniqueLoadingCell(
                        packCell(cellX, cellZ), cellCount);
            }
        }

        float screenCell = Math.max(1.0f, cellBlocks * mapScale);
        int radius = Math.max(4, Math.min(9, Math.round(screenCell * 0.08f)));
        int spinnerPhase = (int) ((System.currentTimeMillis() / 90L) & 7L);
        for (int index = 0; index < cellCount; index++) {
            long packed = loadingCellScratch[index];
            int cellX = (int) (packed >> 32);
            int cellZ = (int) packed;
            drawLoadingSpinner(guiGraphics,
                    cellX + cellBlocks * 0.5,
                    cellZ + cellBlocks * 0.5,
                    mapScale, radius, spinnerPhase);
        }
    }

    private int appendUniqueLoadingCell(long packed, int count) {
        for (int index = 0; index < count; index++) {
            if (loadingCellScratch[index] == packed) return count;
        }
        if (count < loadingCellScratch.length) {
            loadingCellScratch[count++] = packed;
        }
        return count;
    }

    private static int loadingCellBlocks(float scale) {
        if (scale >= 1.0f) return 128;
        if (scale >= 0.45f) return 256;
        if (scale >= 0.18f) return 512;
        if (scale >= 0.10f) return 1024;
        return 2048;
    }

    private static long packCell(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private void drawLoadingSpinner(GuiGraphics guiGraphics,
            double worldX, double worldZ, float mapScale, int radius,
            int phase) {
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(worldX, worldZ, 20.0);
        float inverseScale = 1.0f / mapScale;
        pose.scale(inverseScale, inverseScale, 1.0f);
        int diagonal = Math.max(3, Math.round(radius * 0.70f));
        for (int i = 0; i < 8; i++) {
            int distance = (i - phase + 8) & 7;
            int alpha = distance == 0 ? 0xFF : distance <= 2 ? 0xA0 : 0x48;
            int color = (alpha << 24) | 0x00D8D8D8;
            int distanceFromCenter = (i & 1) == 0 ? radius : diagonal;
            int x = SPINNER_X[i] * distanceFromCenter;
            int y = SPINNER_Y[i] * distanceFromCenter;
            guiGraphics.fill(x - 1, y - 1, x + 1, y + 1, color);
        }
        pose.popPose();
    }

    private void drawSurfaceGlowRegionWithPages(GuiGraphics guiGraphics,
            MapTextureManager surfaceTextures, int rx, int rz) {
        for (int pz = 0; pz < MapPageLayout.PAGES_PER_REGION; pz++) {
            for (int px = 0; px < MapPageLayout.PAGES_PER_REGION; px++) {
                CaveAtlasRegion pageGlow = surfaceTextures.peekGlowPageRegion(rx, rz, px, pz);
                if (pageGlow != null) drawAtlasPage(guiGraphics, pageGlow, rx, rz, px, pz);
            }
        }
    }


    /**
     * Quantized visual brightness used by retained minimap composition. The atlas
     * geometry can remain unchanged while day/night brightness changes, so the FBO
     * cache needs a low-frequency visual generation that does not force a redraw
     * for every interpolation tick.
     */
    int minimapBrightnessBucket(Minecraft mc, float partialTick,
            boolean caveMode) {
        float brightness = caveMode
                ? getCaveBrightness(mc, partialTick)
                : getMapBrightness(mc, partialTick);
        /*
         * Retained FBOs bake the map-light multiplier, unlike Xaero's final shader
         * pass. Quantizing to 24 stable buckets avoids rebuilding the entire FBO for
         * tiny time-of-day interpolation changes while remaining visually smooth.
         */
        return Math.max(0, Math.min(24, Math.round(brightness * 24.0f)));
    }

    private float getMapBrightness(Minecraft mc, float partialTick) {
        if (MapConfig.minimapNightMode == 0) return 1.0f;
        if (MapConfig.minimapNightMode == 2) return NIGHT_MIN_BRIGHTNESS;

        MapManager manager = MapManager.getInstance();
        DimensionMapProfile profile = manager.getCurrentDimensionProfile();
        if (profile == null && mc != null && mc.level != null) {
            profile = DimensionMapProfile.fromLevel(mc.level,
                    mc.level.dimension().location().toString());
        }
        if (profile != null && profile.forceBrightLightmap()) return 1.0f;

        float ambient = xaeroAmbientBrightness(profile);
        boolean hasSkyLight = profile != null && profile.hasSkyLight();
        if (!hasSkyLight) {
            // Dark/ceiling dimensions have no meaningful Overworld day/night fade.
            // Keep a stable dimension-owned ambient instead of sampling unrelated
            // live sky state or forcing every dimension to the same 0.22 floor.
            return ambient;
        }

        // Xaero resolves lighting from the target MapDimension. For a remotely
        // browsed dimension there is no matching ClientLevel sky state; retain a
        // stable daylight presentation rather than borrowing the player's world.
        if (!manager.isViewingLiveDimension() || mc == null || mc.level == null) {
            return 1.0f;
        }

        float skyBrightness = Math.max(0.0f, Math.min(1.0f,
                mc.level.getSkyDarken(partialTick)));
        float normalizedSun = Math.max(0.0f, Math.min(1.0f,
                (skyBrightness - 0.2f) / 0.8f));
        return ambient + (1.0f - ambient) * normalizedSun;
    }

    private float getCaveBrightness(Minecraft mc, float partialTick) {
        if (MapConfig.minimapNightMode == 0) return 1.0f;
        if (MapConfig.minimapNightMode == 2) return 0.62f;
        MapManager manager = MapManager.getInstance();
        DimensionMapProfile profile = manager.getCurrentDimensionProfile();
        if (profile == null && mc != null && mc.level != null) {
            profile = DimensionMapProfile.fromLevel(mc.level,
                    mc.level.dimension().location().toString());
        }
        if (profile != null && profile.forceBrightLightmap()) return 1.0f;
        /*
         * Xaero keeps a cave layer at DimensionType ambient brightness instead of
         * making it pulse with surface day/night. This also prevents cached Cave
         * pages from appearing to change exposure when AUTO crosses a roof boundary.
         */
        return Math.max(0.45f, xaeroAmbientBrightness(profile));
    }

    private static float xaeroAmbientBrightness(DimensionMapProfile profile) {
        float dimensionAmbient = profile == null ? 0.0f : profile.ambientLight();
        return Math.max(0.0f, Math.min(1.0f,
                XAERO_AMBIENT_BASE + dimensionAmbient));
    }

    /**
     * Draws a waypoint marker at the specified world coordinates.
     */
    private void drawWaypointMarker(GuiGraphics guiGraphics, WaypointManager.Waypoint wp, float mapScale,
            boolean isMinimap, boolean isHovered) {
        Minecraft mc = Minecraft.getInstance();
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();

        // Translate to the waypoint's position in world space
        poseStack.translate(wp.x, wp.z, 5);

        // Item icons are screen overlays anchored in world space. Compensating the
        // camera zoom keeps a 0.48x fullscreen icon visible instead of shrinking it
        // to less than one pixel; wp.scale remains an immediately visible choice.
        float safeMapScale = Math.max(0.0001f, Math.abs(mapScale));
        // The global setting is historically 1..10 with 5 as its visual 1.0x.
        float markerScale = (0.20f * MapConfig.waypointScale * wp.scale)
                / safeMapScale;
        poseStack.scale(markerScale, markerScale, 1.0f);

        // Resolve old preset icons and new item icons through one shared path so
        // the map and Waypoint Manager always display the same symbol.
        String itemID = WaypointManager.resolveIconItemId(wp);

        net.minecraft.world.item.ItemStack iconStack = waypointItemStacks.get(itemID);
        if (iconStack == null) {
            try {
                net.minecraft.world.item.Item item =
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                                ResourceLocation.parse(itemID));
                iconStack = item != null && item != net.minecraft.world.item.Items.AIR
                        ? new net.minecraft.world.item.ItemStack(item)
                        : fallbackWaypointStack;
            } catch (RuntimeException invalidIdentifier) {
                iconStack = fallbackWaypointStack;
            }
            waypointItemStacks.put(itemID, iconStack);
        }
        // Draw item centered (ItemStack is 16x16px, so we offset by -8).
        guiGraphics.renderFakeItem(iconStack, -8, -8);

        // Draw name text above the icon ONLY if it is not the minimap and it is hovered
        if (!isMinimap && isHovered) {
            poseStack.scale(0.8f, 0.8f, 1.0f);
            int textWidth = mc.font.width(wp.name);
            int textX = -textWidth / 2;
            int textY = -16;

            guiGraphics.fill(textX - 2, textY - 2, textX + textWidth + 2, textY + 9, 0x88000000);
            guiGraphics.drawString(mc.font, wp.name, textX, textY, 0xFFFFFF, false);
        }

        poseStack.popPose();
    }

    static double waypointHitRadiusWorld(WaypointManager.Waypoint waypoint,
            float mapScale) {
        float iconScale = waypoint == null ? 1.0f : waypoint.scale;
        double radiusPixels = Math.max(6.0,
                8.0 * 0.20 * MapConfig.waypointScale * iconScale + 2.0);
        return radiusPixels / Math.max(0.0001, Math.abs(mapScale));
    }

    /**
     * Draws the player marker at the specified world coordinates.
     */
    private void drawPlayerMarker(GuiGraphics guiGraphics, double worldX, double worldZ, float yaw,
            float mapScale, float fixedOverlayScale) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;

        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();

        // Translate to the player's position in world space
        poseStack.translate(worldX, worldZ, 10);

        // Compensate map scale and apply playerMarkerScale config
        float markerScale = (1.0f / mapScale) * MapConfig.playerMarkerScale * fixedOverlayScale;
        poseStack.scale(markerScale, markerScale, 1.0f);

        // Rotate the entire marker to face player direction
        poseStack.mulPose(Axis.ZP.rotationDegrees(yaw));

        int pointerColor = getActualPointerColor(MapConfig.playerPointerColor);

        if (MapConfig.playerMarkerMode == 1) {
            // Mode 1: ARROW_ONLY (Only show triangle/chevron pointer pointing UP/forward)
            drawDirectionalPointer(poseStack, pointerColor, 1.0f);
        } else {
            // Mode 0: DEFAULT (Player skin head only, no pointer)
            // 1. Draw black background border for player head
            guiGraphics.fill(-5, -5, 5, 5, 0xFF121212);

            // 2. Draw Player Head Skin (Face + Hat Layer)
            net.minecraft.client.resources.PlayerSkin skin = mc.player.getSkin();
            ResourceLocation skinLocation = skin.texture();

            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

            // Base Face layer
            guiGraphics.blit(skinLocation, -4, -4, 8, 8, 8.0f, 8.0f, 8, 8, 64, 64);
            // Outer Hat/Hair layer
            guiGraphics.blit(skinLocation, -4, -4, 8, 8, 40.0f, 8.0f, 8, 8, 64, 64);
        }

        poseStack.popPose();
    }

    /**
     * Draws the minimap player marker as a final HUD overlay. It must not live in
     * the retained map FBO: a cold map page or failed composition would otherwise
     * make the player arrow disappear together with map content.
     */
    public void drawMinimapPlayerOverlay(GuiGraphics guiGraphics, float centerX,
            float centerY, boolean rotateWithPlayer, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        boolean depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        try {
            poseStack.translate(centerX, centerY, 400.0f);
            poseStack.scale(MapConfig.playerMarkerScale,
                    MapConfig.playerMarkerScale, 1.0f);
            if (!rotateWithPlayer) {
                float yaw = net.minecraft.util.Mth.rotLerp(
                        partialTick, mc.player.yRotO, mc.player.getYRot()) + 180.0f;
                poseStack.mulPose(Axis.ZP.rotationDegrees(yaw));
            }

            int pointerColor = getActualPointerColor(MapConfig.playerPointerColor);
            if (MapConfig.playerMarkerMode == 1) {
                drawDirectionalPointer(poseStack, pointerColor, 1.0f);
            } else {
                guiGraphics.fill(-5, -5, 5, 5, 0xFF121212);
                net.minecraft.client.resources.PlayerSkin skin = mc.player.getSkin();
                ResourceLocation skinLocation = skin.texture();
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                guiGraphics.blit(skinLocation, -4, -4, 8, 8,
                        8.0f, 8.0f, 8, 8, 64, 64);
                guiGraphics.blit(skinLocation, -4, -4, 8, 8,
                        40.0f, 8.0f, 8, 8, 64, 64);
            }
            guiGraphics.flush();
        } finally {
            poseStack.popPose();
            if (depthWasEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST);
            else GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
    }

    public int getActualPointerColor(int configColor) {
        if (configColor == 0xFF000001) {
            float hue = (System.currentTimeMillis() % 4000) / 4000.0f; // cycle every 4 seconds
            int r = 0, g = 0, b = 0;
            float h = hue * 6;
            float f = h - (int) h;
            float q = 1.0f - f;
            float t = f;
            switch ((int) h) {
                case 0:
                    r = 255;
                    g = (int) (t * 255);
                    b = 0;
                    break;
                case 1:
                    r = (int) (q * 255);
                    g = 255;
                    b = 0;
                    break;
                case 2:
                    r = 0;
                    g = 255;
                    b = (int) (t * 255);
                    break;
                case 3:
                    r = 0;
                    g = (int) (q * 255);
                    b = 255;
                    break;
                case 4:
                    r = (int) (t * 255);
                    g = 0;
                    b = 255;
                    break;
                case 5:
                    r = 255;
                    g = 0;
                    b = (int) (q * 255);
                    break;
            }
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return configColor;
    }

    private void drawDirectionalPointer(PoseStack poseStack, int color, float sizeScale) {
        org.joml.Matrix4f matrix = poseStack.last().pose();

        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = ((color >> 24) & 0xFF) / 255.0f;

        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem
                .setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);

        com.mojang.blaze3d.vertex.Tesselator tesselator = com.mojang.blaze3d.vertex.Tesselator.getInstance();
        com.mojang.blaze3d.vertex.BufferBuilder buffer = tesselator.begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);

        // Chevron coordinates:
        float tipY = -6.0f * sizeScale;
        float blX = -4.5f * sizeScale;
        float blY = 4.5f * sizeScale;
        float indY = 2.0f * sizeScale;
        float brX = 4.5f * sizeScale;
        float brY = 4.5f * sizeScale;

        // Outline coordinates:
        float oTipY = -7.5f * sizeScale;
        float oBlX = -5.5f * sizeScale;
        float oBlY = 5.5f * sizeScale;
        float oIndY = 3.0f * sizeScale;
        float oBrX = 5.5f * sizeScale;
        float oBrY = 5.5f * sizeScale;

        // 1. Draw Outline (2 triangles for chevron)
        // Left half: tip -> bl -> ind
        buffer.addVertex(matrix, 0.0f, oTipY, 0.0f).setColor(0.07f, 0.07f, 0.07f, 1.0f);
        buffer.addVertex(matrix, oBlX, oBlY, 0.0f).setColor(0.07f, 0.07f, 0.07f, 1.0f);
        buffer.addVertex(matrix, 0.0f, oIndY, 0.0f).setColor(0.07f, 0.07f, 0.07f, 1.0f);
        // Right half: tip -> ind -> br
        buffer.addVertex(matrix, 0.0f, oTipY, 0.0f).setColor(0.07f, 0.07f, 0.07f, 1.0f);
        buffer.addVertex(matrix, 0.0f, oIndY, 0.0f).setColor(0.07f, 0.07f, 0.07f, 1.0f);
        buffer.addVertex(matrix, oBrX, oBrY, 0.0f).setColor(0.07f, 0.07f, 0.07f, 1.0f);

        // 2. Draw Inner Fill (2 triangles for chevron)
        // Left half: tip -> bl -> ind
        buffer.addVertex(matrix, 0.0f, tipY, 0.1f).setColor(r, g, b, a);
        buffer.addVertex(matrix, blX, blY, 0.1f).setColor(r, g, b, a);
        buffer.addVertex(matrix, 0.0f, indY, 0.1f).setColor(r, g, b, a);
        // Right half: tip -> ind -> br
        buffer.addVertex(matrix, 0.0f, tipY, 0.1f).setColor(r, g, b, a);
        buffer.addVertex(matrix, 0.0f, indY, 0.1f).setColor(r, g, b, a);
        buffer.addVertex(matrix, brX, brY, 0.1f).setColor(r, g, b, a);

        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    private static final class RenderStats {
        private int exactPages;
        private int branchNodes;
        private int legacyFallbacks;

        private void reset() {
            exactPages = 0;
            branchNodes = 0;
            legacyFallbacks = 0;
        }

        private void accept(MapDrawResult result) {
            if (result == null) return;
            exactPages += result.exactPagesDrawn();
            branchNodes += result.branchNodesDrawn();
            legacyFallbacks += result.legacyFallbacksDrawn();
        }

        private MapDrawResult snapshot() {
            return new MapDrawResult(exactPages, branchNodes, legacyFallbacks);
        }
    }

    private CachedPlan stableFullscreenPlan(StablePlanKey stableKey,
            PlanKey target, long topologyRevision) {
        CachedPlan candidate = stableFullscreenPlans.get(stableKey);
        if (candidate == null) return null;
        if (!candidate.plan().topologyValid(topologyRevision)) {
            stableFullscreenPlans.remove(stableKey);
            return null;
        }
        return compatibleFullscreenFallback(candidate.key(), target)
                && candidate.plan().quadCount() > 0 ? candidate : null;
    }

    private void rememberStableFullscreenPlan(CachedPlan plan) {
        if (plan == null || plan.plan().quadCount() <= 0) return;
        stableFullscreenPlans.put(StablePlanKey.of(plan.key()), plan);
    }

    private static boolean compatibleFullscreenFallback(
            PlanKey previous, PlanKey target) {
        if (previous == null || target == null
                || !previous.dimension().equals(target.dimension())
                || previous.sessionId() != target.sessionId()
                || previous.caveMode() != target.caveMode()
                || previous.fullCaveView() != target.fullCaveView()
                || previous.caveLayerY() != target.caveLayerY()) return false;
        int overlapMinX = Math.max(previous.minRegionX(), target.minRegionX());
        int overlapMaxX = Math.min(previous.maxRegionX(), target.maxRegionX());
        int overlapMinZ = Math.max(previous.minRegionZ(), target.minRegionZ());
        int overlapMaxZ = Math.min(previous.maxRegionZ(), target.maxRegionZ());
        if (overlapMaxX < overlapMinX || overlapMaxZ < overlapMinZ) return false;
        int targetCenterX = Math.floorDiv(target.minRegionX() + target.maxRegionX(), 2);
        int targetCenterZ = Math.floorDiv(target.minRegionZ() + target.maxRegionZ(), 2);
        boolean coversTargetCenter = targetCenterX >= previous.minRegionX()
                && targetCenterX <= previous.maxRegionX()
                && targetCenterZ >= previous.minRegionZ()
                && targetCenterZ <= previous.maxRegionZ();
        long overlapArea = (long) (overlapMaxX - overlapMinX + 1)
                * (overlapMaxZ - overlapMinZ + 1);
        long targetArea = (long) (target.maxRegionX() - target.minRegionX() + 1)
                * (target.maxRegionZ() - target.minRegionZ() + 1);
        return coversTargetCenter || overlapArea * 4L >= targetArea;
    }

    private record PlanKey(String dimension, long sessionId, boolean caveMode,
            boolean fullCaveView, int caveLayerY,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            int minPlanCellX, int maxPlanCellX,
            int minPlanCellZ, int maxPlanCellZ,
            int hierarchyLevel, boolean caveBranchOnly, int renderScaleClass,
            int attentionPageX, int attentionPageZ, int nightMode) {
    }

    private record StablePlanKey(String dimension, long sessionId, boolean caveMode,
            boolean fullCaveView, int caveLayerY) {
        private static StablePlanKey of(PlanKey key) {
            int layer = key.caveMode() && !key.fullCaveView()
                    ? key.caveLayerY() : Integer.MIN_VALUE;
            return new StablePlanKey(key.dimension(), key.sessionId(), key.caveMode(),
                    key.fullCaveView(), layer);
        }
    }

    /** Allocation-free region membership set used only while building one plan. */
    private static final class LongKeySet {
        private long[] keys = new long[32];
        private byte[] states = new byte[32];
        private int size;

        private boolean add(long key) {
            if ((size + 1) * 10 >= keys.length * 7) resize(keys.length << 1);
            int mask = keys.length - 1;
            int index = mix(key) & mask;
            while (states[index] != 0) {
                if (keys[index] == key) return false;
                index = (index + 1) & mask;
            }
            states[index] = 1;
            keys[index] = key;
            size++;
            return true;
        }

        private boolean contains(long key) {
            int mask = keys.length - 1;
            int index = mix(key) & mask;
            while (states[index] != 0) {
                if (keys[index] == key) return true;
                index = (index + 1) & mask;
            }
            return false;
        }

        private void resize(int capacity) {
            long[] oldKeys = keys;
            byte[] oldStates = states;
            keys = new long[capacity];
            states = new byte[capacity];
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldStates[i] != 0) add(oldKeys[i]);
            }
        }

        private static int mix(long value) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdL;
            value ^= value >>> 33;
            value *= 0xc4ceb9fe1a85ec53L;
            value ^= value >>> 33;
            return (int) value;
        }
    }

    private record CachedPlan(PlanKey key, MapRenderPlan plan,
            long contentRevision) {
    }

}
