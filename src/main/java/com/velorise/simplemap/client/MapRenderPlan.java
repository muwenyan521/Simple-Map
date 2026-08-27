package com.velorise.simplemap.client;

import com.velorise.simplemap.client.gpu.MapGpuInstancePlan;
import com.velorise.simplemap.client.renderer.MapGpuRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import com.velorise.simplemap.client.gpu.TileKey;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Immutable cache-only draw plan for one quantized viewport.
 *
 * <p>Hierarchy traversal and atlas lookup happen only when the viewport key or
 * global residency topology changes. V16.9 also compacts resolved quads into
 * primitive raw-vertex batches, so replay uses one texture bind and one buffer
 * submission per atlas/phase instead of one {@code GuiGraphics.blit()} per
 * exact page or branch node.</p>
 */
final class MapRenderPlan {
    /** Stable 512x512 compatibility texture retained below every finer LOD. */
    static final int PHASE_LEGACY_UNDERLAY = 2;
    /** Region-centric M4 coverage always stays below factor-2 refinement. */
    static final int PHASE_REGION_COARSE = 4;
    static final int PHASE_BRANCH_BASE = 16;
    /** Exact fallback drawn immediately below the density-correct L1 branch. */
    static final int PHASE_L1_EXACT_UNDERLAY = 27;
    static final int PHASE_EXACT = 96;
    static final int PHASE_GLOW = 128;

    private final List<Batch> baseBatches;
    private final List<Batch> glowBatches;
    private final MapGpuInstancePlan instancePlan;
    private final int[] basePhases;
    private final int[] glowPhases;
    private final long[] pendingRegions;
    private final MapDrawResult result;
    private final long topologyRevision;
    private final long builtAtNanos;
    private final int quadCount;
    private final int batchCount;
    /**
     * True when every exact leaf in the viewport is represented by a logical
     * page-table key, including leaves that were non-resident while the plan was
     * built. Such a plan is independent of atlas residency churn: publication and
     * eviction are resolved at replay time without rebuilding world geometry.
     */
    private final boolean logicalExactCoverage;

    private MapRenderPlan(List<Batch> baseBatches, List<Batch> glowBatches,
            MapGpuInstancePlan instancePlan,
            int[] basePhases, int[] glowPhases,
            long[] pendingRegions, MapDrawResult result,
            long topologyRevision, long builtAtNanos,
            int quadCount, int batchCount, boolean logicalExactCoverage) {
        this.baseBatches = baseBatches;
        this.glowBatches = glowBatches;
        this.instancePlan = instancePlan;
        this.basePhases = basePhases;
        this.glowPhases = glowPhases;
        this.pendingRegions = pendingRegions;
        this.result = result;
        this.topologyRevision = topologyRevision;
        this.builtAtNanos = builtAtNanos;
        this.quadCount = quadCount;
        this.batchCount = batchCount;
        this.logicalExactCoverage = logicalExactCoverage;
    }

    void drawBase(GuiGraphics graphics) {
        drawOrdered(graphics, baseBatches, basePhases);
    }

    void drawGlow(GuiGraphics graphics) {
        drawOrdered(graphics, glowBatches, glowPhases);
    }

    /** Replay inside a state scope already owned by MapRenderer. */
    void drawBasePrepared(Matrix4f matrix) {
        drawOrderedPrepared(baseBatches, basePhases, matrix);
    }

    /** Replay inside a state scope already owned by MapRenderer. */
    void drawGlowPrepared(Matrix4f matrix) {
        drawOrderedPrepared(glowBatches, glowPhases, matrix);
    }

    private void drawOrdered(GuiGraphics graphics, List<Batch> batches,
            int[] phases) {
        if (graphics == null || phases.length == 0) return;
        /*
         * One immutable plan used to flush GuiGraphics, query GL depth state and
         * toggle the shader twice for every phase (page-table instances + raw atlas
         * batches). Large cave plans therefore generated thousands of driver sync
         * points while GPU utilisation remained low. Scope state once and preserve
         * the same coarse-to-fine phase ordering inside it.
         */
        graphics.flush();
        boolean depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        RenderSystem.disableDepthTest();
        try {
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            drawOrderedPrepared(batches, phases, graphics.pose().last().pose());
        } finally {
            if (depthWasEnabled) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
        }
    }

    private void drawOrderedPrepared(List<Batch> batches,
            int[] phases, Matrix4f matrix) {
        if (matrix == null || phases.length == 0) return;
        for (int phase : phases) {
            MapGpuRenderer.drawPhasePrepared(instancePlan, phase, matrix);
            MapAtlasBatchRenderer.drawPhasePrepared(batches, phase, matrix);
        }
    }

    long[] pendingRegions() {
        return pendingRegions;
    }

    MapDrawResult result() {
        return result;
    }

    int quadCount() {
        return quadCount;
    }

    int batchCount() {
        return batchCount;
    }

    boolean logicalExactCoverage() {
        return logicalExactCoverage;
    }

    long builtAtNanos() {
        return builtAtNanos;
    }

    boolean topologyValid(long revision) {
        return topologyRevision == revision;
    }

    boolean valid(long revision, long nowNanos, boolean hasPending) {
        // Residency content revisions now invalidate the owner CachedPlan. Do not
        // rebuild an unchanged cave hierarchy every 200–500 ms merely because a
        // source is pending; branch/leaf publication will advance contentRevision
        // when a renderable atlas slot actually changes.
        return topologyValid(revision);
    }

    static int branchPhase(int level) {
        int clamped = Math.max(1, Math.min(MapLodPolicy.MAX_BRANCH_LEVEL, level));
        // Keep one free phase between adjacent branch levels so L1 can place
        // an exact fallback underneath the branch without colliding with L2.
        return PHASE_BRANCH_BASE
                + (MapLodPolicy.MAX_BRANCH_LEVEL - clamped) * 2;
    }

    static final class Builder {
        private static final int MAX_QUADS = 32_768;
        private static final int FLOATS_PER_VERTEX = 4;
        private static final int VERTICES_PER_QUAD = 4;
        private static final int FLOATS_PER_QUAD = FLOATS_PER_VERTEX * VERTICES_PER_QUAD;
        private static final Comparator<Quad> QUAD_ORDER = Comparator
                .comparingInt((Quad quad) -> quad.phase)
                .thenComparing(Quad::texture);

        private final List<Quad> quads = new ArrayList<>(256);
        private final MapGpuInstancePlan.Builder instances =
                new MapGpuInstancePlan.Builder();
        private final long[] pending = new long[256];
        /** Primitive open-addressed dedup; avoids O(N^2) pending-region scans. */
        private final long[] pendingSet = new long[512];
        private final boolean[] pendingSetUsed = new boolean[512];
        /** One-plan region availability cache; avoids repeated synchronized/file-cache probes. */
        private final long[] sourceRegionKeys = new long[256];
        private final byte[] sourceRegionStates = new byte[256];
        private final boolean[] sourceRegionUsed = new boolean[256];
        private int pendingCount;
        private int instanceCount;
        private int exactPages;
        private int branchNodes;
        private int legacyFallbacks;
        private boolean logicalExactCoverage;

        boolean add(ResourceLocation texture, int phase,
                int x, int y, int width, int height,
                float u, float v, int uWidth, int vHeight,
                int textureWidth, int textureHeight) {
            if (texture == null || width <= 0 || height <= 0
                    || uWidth <= 0 || vHeight <= 0
                    || textureWidth <= 0 || textureHeight <= 0
                    || quads.size() >= MAX_QUADS) return false;
            float inverseWidth = 1.0f / textureWidth;
            float inverseHeight = 1.0f / textureHeight;
            float u0 = u * inverseWidth;
            float v0 = v * inverseHeight;
            float u1 = (u + uWidth) * inverseWidth;
            float v1 = (v + vHeight) * inverseHeight;
            quads.add(new Quad(texture, phase, x, y, width, height,
                    u0, v0, u1, v1));
            return true;
        }

        boolean addTile(TileKey key, int phase,
                int x, int y, int width, int height) {
            return addTile(key, phase, x, y, width, height,
                    0.0f, 0.0f, 1.0f, 1.0f, 0);
        }

        boolean addTile(TileKey key, int phase,
                int x, int y, int width, int height,
                float localU0, float localV0, float localU1, float localV1,
                int requiredCoverageMask) {
            if (key == null || width <= 0 || height <= 0
                    || quads.size() + instanceCount >= MAX_QUADS) return false;
            boolean added = instances.add(key, phase, x, y, width, height,
                    localU0, localV0, localU1, localV1,
                    requiredCoverageMask);
            if (added) instanceCount++;
            return added;
        }

        int entryCount() {
            return quads.size() + instanceCount;
        }

        void exact() {
            exactPages++;
        }

        void branch() {
            branchNodes++;
        }

        void legacy() {
            legacyFallbacks++;
        }

        void logicalExactCoverage() {
            logicalExactCoverage = true;
        }


        boolean sourceRegionAvailable(MapManager manager, int regionX, int regionZ) {
            if (manager == null) return false;
            long packed = ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
            int slot = (int) mixPendingKey(packed) & (sourceRegionKeys.length - 1);
            int probes = 0;
            while (sourceRegionUsed[slot] && probes++ < sourceRegionKeys.length) {
                if (sourceRegionKeys[slot] == packed) {
                    return sourceRegionStates[slot] == 2;
                }
                slot = (slot + 1) & (sourceRegionKeys.length - 1);
            }
            boolean available = manager.hasRegionFile(regionX, regionZ)
                    || manager.isRegionLoadedInCache(regionX, regionZ);
            if (probes < sourceRegionKeys.length) {
                sourceRegionUsed[slot] = true;
                sourceRegionKeys[slot] = packed;
                sourceRegionStates[slot] = (byte) (available ? 2 : 1);
            }
            return available;
        }

        void pending(int regionX, int regionZ) {
            long packed = ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
            int slot = (int) mixPendingKey(packed) & (pendingSet.length - 1);
            while (pendingSetUsed[slot]) {
                if (pendingSet[slot] == packed) return;
                slot = (slot + 1) & (pendingSet.length - 1);
            }
            if (pendingCount >= pending.length) return;
            pendingSetUsed[slot] = true;
            pendingSet[slot] = packed;
            pending[pendingCount++] = packed;
        }

        private static long mixPendingKey(long value) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdl;
            value ^= value >>> 33;
            value *= 0xc4ceb9fe1a85ec53l;
            value ^= value >>> 33;
            return value;
        }

        MapRenderPlan build(long topologyRevision) {
            // Preserve coarse-to-fine phases and group equal atlas textures inside
            // each phase. The resulting primitive batches are immutable and contain
            // normalized UVs, so replay performs no atlas arithmetic or quad object
            // allocation on the render path.
            quads.sort(QUAD_ORDER);

            List<Batch> base = new ArrayList<>();
            List<Batch> glow = new ArrayList<>();
            int cursor = 0;
            while (cursor < quads.size()) {
                Quad first = quads.get(cursor);
                int end = cursor + 1;
                while (end < quads.size()) {
                    Quad candidate = quads.get(end);
                    if (candidate.phase != first.phase
                            || !candidate.texture.equals(first.texture)) break;
                    end++;
                }
                int groupQuads = end - cursor;
                float[] vertices = new float[groupQuads * FLOATS_PER_QUAD];
                int output = 0;
                for (int index = cursor; index < end; index++) {
                    output = writeQuad(vertices, output, quads.get(index));
                }
                Batch batch = new Batch(first.texture, first.phase,
                        vertices, groupQuads);
                if (first.phase >= PHASE_GLOW) glow.add(batch);
                else base.add(batch);
                cursor = end;
            }

            long[] pendingArray = Arrays.copyOf(pending, pendingCount);
            MapGpuInstancePlan instancePlan = instances.build();
            List<Batch> immutableBase = List.copyOf(base);
            List<Batch> immutableGlow = List.copyOf(glow);
            int[] basePhases = collectPhases(immutableBase, instancePlan, false);
            int[] glowPhases = collectPhases(immutableGlow, instancePlan, true);
            return new MapRenderPlan(immutableBase, immutableGlow,
                    instancePlan, basePhases, glowPhases, pendingArray,
                    new MapDrawResult(exactPages, branchNodes, legacyFallbacks),
                    topologyRevision, System.nanoTime(),
                    quads.size() + instancePlan.size(),
                    base.size() + glow.size() + (instancePlan.size() == 0 ? 0 : 1),
                    logicalExactCoverage);
        }

        private static int[] collectPhases(List<Batch> batches,
                MapGpuInstancePlan instances, boolean glow) {
            int[] phases = new int[batches.size() + instances.size()];
            int count = 0;
            for (Batch batch : batches) {
                boolean batchGlow = batch.phase() >= PHASE_GLOW;
                if (batchGlow == glow) phases[count++] = batch.phase();
            }
            for (int index = 0; index < instances.size(); index++) {
                int phase = instances.phase(index);
                boolean instanceGlow = phase >= PHASE_GLOW;
                if (instanceGlow == glow) phases[count++] = phase;
            }
            if (count == 0) return new int[0];
            Arrays.sort(phases, 0, count);
            int unique = 1;
            for (int index = 1; index < count; index++) {
                if (phases[index] != phases[unique - 1]) {
                    phases[unique++] = phases[index];
                }
            }
            return Arrays.copyOf(phases, unique);
        }

        private static int writeQuad(float[] target, int offset, Quad quad) {
            float x0 = quad.x;
            float y0 = quad.y;
            float x1 = quad.x + quad.width;
            float y1 = quad.y + quad.height;

            offset = writeVertex(target, offset, x0, y0, quad.u0, quad.v0);
            offset = writeVertex(target, offset, x0, y1, quad.u0, quad.v1);
            offset = writeVertex(target, offset, x1, y1, quad.u1, quad.v1);
            return writeVertex(target, offset, x1, y0, quad.u1, quad.v0);
        }

        private static int writeVertex(float[] target, int offset,
                float x, float y, float u, float v) {
            target[offset++] = x;
            target[offset++] = y;
            target[offset++] = u;
            target[offset++] = v;
            return offset;
        }
    }

    /** One immutable atlas/phase submission. */
    record Batch(ResourceLocation texture, int phase,
            float[] vertices, int quadCount) {
        Batch {
            if (texture == null) throw new IllegalArgumentException("texture");
            if (vertices == null || vertices.length != quadCount * 16) {
                throw new IllegalArgumentException("Invalid map batch vertex data");
            }
        }
    }

    private record Quad(ResourceLocation texture, int phase,
            int x, int y, int width, int height,
            float u0, float v0, float u1, float v1) {
    }
}
