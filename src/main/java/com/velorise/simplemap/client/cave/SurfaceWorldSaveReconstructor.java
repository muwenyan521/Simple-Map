package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapBlockData;
import com.velorise.simplemap.client.ChunkScanner;
import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapDebugRecorder;
import com.velorise.simplemap.client.MapPipelineStage;
import com.velorise.simplemap.client.MapPipelineTelemetry;
import com.velorise.simplemap.client.MapPerformanceGovernor;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapWorkScheduler;
import com.velorise.simplemap.client.SurfaceTintData;
import com.velorise.simplemap.client.SurfaceRegionSourceDatabase;
import com.velorise.simplemap.client.pipeline.RevisionStamp;
import com.velorise.simplemap.client.session.MapSessionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Surface projection consumer for the shared decoded world-region cache.
 *
 * <p>This closes the old split where surface used only .smdat/live columns while
 * cave decoded Anvil sections separately. A generated chunk decoded for either
 * view can now populate missing surface columns and later serve every cave band.</p>
 */
final class SurfaceWorldSaveReconstructor {
    private static final SurfaceWorldSaveReconstructor INSTANCE =
            new SurfaceWorldSaveReconstructor();
    /*
     * One pending key owns one already-decoded 16x16 surface projection. The old
     * 192/64 split discarded completed MCA work under normal fullscreen bursts;
     * the pending set itself is the memory bound, so a second tiny ready cap only
     * created permanent holes in the reconstructed surface footprint.
     */
    private static final int MAX_PENDING = 768;
    private static final long REGION_LOAD_RETRY_MS = 20L;
    private static final long PRESSURED_APPLY_BUDGET_NANOS = 500_000L;
    private static final long HEALTHY_APPLY_BUDGET_NANOS = 2_500_000L;

    private final Set<Key> pending = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<ReadyProjection> readyApplications =
            new ConcurrentLinkedQueue<>();
    private final AtomicInteger readyApplicationCount = new AtomicInteger();
    /*
     * PASS139: source projection continuations share the same bounded retained
     * adapter as Cave source fanout. This avoids one delayed scheduler retry chain
     * per completed Anvil chunk when the global CPU domain is saturated.
     */
    private final PriorityDecodeExecutor projectionWorkers =
            new PriorityDecodeExecutor(
                    MapWorkScheduler.WorkType.SOURCE_PROJECTION, 10);
    /* Client-thread-only commit scratch. commitSurfaceChunkSlice() copies values
     * synchronously under the Region lock, so one reusable 256-column transaction
     * avoids three fresh primitive arrays for every reconstructed Anvil chunk. */
    private final long[] applyPackedScratch = new long[256];
    private final int[] applyTintScratch = new int[256];
    private final boolean[] applyValidScratch = new boolean[256];

    private SurfaceWorldSaveReconstructor() {
    }

    static SurfaceWorldSaveReconstructor getInstance() {
        return INSTANCE;
    }

    void reset() {
        pending.clear();
        readyApplications.clear();
        readyApplicationCount.set(0);
    }

    void request(ServerLevel level, int chunkX, int chunkZ,
            DecodedWorldRegionCache.SourceLease sourceLease) {
        if (sourceLease == null) return;
        if (level == null || pending.size() >= MAX_PENDING) {
            sourceLease.close();
            return;
        }
        if (MapManager.getInstance().isChunkSurfaceComplete(chunkX, chunkZ)) {
            sourceLease.close();
            return;
        }
        CompletableFuture<DecodedWorldRegionCache.Result> sourceFuture = sourceLease.future();
        if (sourceFuture.isDone()) {
            try {
                DecodedWorldRegionCache.Result immediate = sourceFuture.getNow(null);
                if (immediate == null
                        || immediate.state() != DecodedWorldRegionCache.State.PRESENT
                        || immediate.source() == null) {
                    sourceLease.close();
                    return;
                }
            } catch (RuntimeException failure) {
                sourceLease.close();
                return;
            }
        }
        Key key = new Key(level.dimension().location().toString(), chunkX, chunkZ);
        if (!pending.add(key)) {
            sourceLease.close();
            return;
        }
        long generation = MapManager.getInstance().getGeneration();
        MapRequestLane lane = sourceLease.lane();
        sourceFuture.thenAcceptAsync(result -> {
            if (result == null || result.state() != DecodedWorldRegionCache.State.PRESENT
                    || result.source() == null) {
                pending.remove(key);
                sourceLease.close();
                return;
            }
            if (!result.source().hasAuthoritativeSurfaceSource()) {
                // A proto-generation .mca snapshot can contain the base ground
                // before FEATURES has placed trees/vegetation. Never publish that
                // snapshot as a complete Surface chunk; a later FULL save or the
                // live ChunkScanner will provide the authoritative transaction.
                pending.remove(key);
                sourceLease.close();
                return;
            }
            long projectionStart = System.nanoTime();
            DecodedWorldChunkSource.SurfaceProjection[] columns = result.source()
                    .projectSurface(com.velorise.simplemap.client.MapConfig.displayFlowers);
            MapPipelineTelemetry.getInstance().recordStageNanos(
                    MapPipelineStage.SURFACE_PROJECTION,
                    System.nanoTime() - projectionStart);
            sourceLease.close();
            readyApplicationCount.incrementAndGet();
            readyApplications.offer(new ReadyProjection(
                    key, generation, columns, lane, 0L));
        }, projectionWorkers.dynamic(sourceLease.lane()::executorPriority))
                .exceptionally(throwable -> {
            pending.remove(key);
            sourceLease.close();
            return null;
        });
    }

    /**
     * Fan-out entry used by the unified native-region source transaction. The NBT
     * chunk has already been read and palette-decoded once; this method derives the
     * Surface presentation directly from that immutable source without opening a
     * second lease, future chain or page reader.
     */
    boolean acceptDecoded(ServerLevel level, int chunkX, int chunkZ,
            DecodedWorldChunkSource source, MapRequestLane lane) {
        if (source == null || !source.hasAuthoritativeSurfaceSource()) return false;
        long projectionStart = System.nanoTime();
        DecodedWorldChunkSource.SurfaceProjection[] columns = source
                .projectSurface(com.velorise.simplemap.client.MapConfig.displayFlowers);
        MapPipelineTelemetry.getInstance().recordStageNanos(
                MapPipelineStage.SURFACE_PROJECTION,
                System.nanoTime() - projectionStart);
        return acceptProjection(level, chunkX, chunkZ, columns, lane);
    }

    /**
     * Publishes Surface columns already produced by the unified source stage. This
     * avoids calling the Surface projector again after the same source transaction
     * has prepared the vertical Cave archive.
     */
    boolean acceptProjection(ServerLevel level, int chunkX, int chunkZ,
            DecodedWorldChunkSource.SurfaceProjection[] columns,
            MapRequestLane lane) {
        if (level == null || columns == null || columns.length == 0
                || pending.size() >= MAX_PENDING) return false;
        MapManager manager = MapManager.getInstance();
        if (manager.isChunkSurfaceComplete(chunkX, chunkZ)) return true;
        Key key = new Key(level.dimension().location().toString(), chunkX, chunkZ);
        if (!pending.add(key)) return true;
        long generation = manager.getGeneration();
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        readyApplicationCount.incrementAndGet();
        readyApplications.offer(new ReadyProjection(
                key, generation, columns, effectiveLane, 0L));
        return true;
    }

    /**
     * Applies completed disk projections on the client thread with an actual time
     * slice. CompletableFuture callbacks used to enqueue every completed chunk via
     * Minecraft.execute(), so a fast Anvil burst could commit dozens of palettes
     * in one frame even though background worker queues looked empty in the log.
     */
    void drainReadyApplications() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !minecraft.isSameThread()) return;
        boolean pressured = MapPerformanceGovernor.getInstance().underPressure();
        long deadline = System.nanoTime() + (pressured
                ? PRESSURED_APPLY_BUDGET_NANOS : HEALTHY_APPLY_BUDGET_NANOS);
        int maximum = pressured ? 2 : 12;
        int applied = 0;
        int examined = 0;
        int scanLimit = Math.max(1, readyApplicationCount.get());
        long now = System.currentTimeMillis();
        while (applied < maximum && examined++ < scanLimit) {
            ReadyProjection ready = readyApplications.poll();
            if (ready == null) break;
            readyApplicationCount.updateAndGet(value -> Math.max(0, value - 1));
            if (ready.retryAfterMs() > now) {
                requeue(ready);
                continue;
            }
            ApplyResult result = apply(ready.key(), ready.generation(),
                    ready.columns(), ready.lane());
            if (result == ApplyResult.RETRY) {
                requeue(ready.withRetry(now + REGION_LOAD_RETRY_MS));
            } else {
                pending.remove(ready.key());
                if (result == ApplyResult.APPLIED) applied++;
            }
            if (System.nanoTime() >= deadline) break;
        }
    }

    private void requeue(ReadyProjection ready) {
        readyApplicationCount.incrementAndGet();
        readyApplications.offer(ready);
    }

    private ApplyResult apply(Key key, long generation,
            DecodedWorldChunkSource.SurfaceProjection[] columns,
            MapRequestLane lane) {
        MapManager manager = MapManager.getInstance();
        if (!manager.isGenerationCurrent(generation)
                || !manager.getCurrentDimensionResourceId().equals(key.dimension)
                || manager.isChunkSurfaceComplete(key.chunkX, key.chunkZ)) {
            return ApplyResult.TERMINAL;
        }
        /*
         * A loaded FULL LevelChunk is the single Surface authority. The old
         * provisional path published the decoded disk snapshot first and then
         * called packet-authoritative enqueue, which reset an already-running live
         * cursor. That created two coherent-but-different revisions for one chunk
         * and repeatedly dirtied exact/LOD pages at the 16-block boundary. Xaero
         * likewise advances one loaded map buffer instead of letting a saved-world
         * snapshot race the live writer. Keep the disk projection retained as source
         * cache, but do not publish it while a live body exists.
         */
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && manager.acceptsLiveLevel(minecraft.level)) {
            var liveAccess = minecraft.level.getChunk(
                    key.chunkX, key.chunkZ, ChunkStatus.FULL, false);
            if (liveAccess instanceof LevelChunk live
                    && !(live instanceof EmptyLevelChunk)) {
                ChunkScanner.getInstance().enqueueLiveSurfaceAuthorityChunk(
                        key.chunkX, key.chunkZ);
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String eventKey = "SURFACE_MCA_LIVE_AUTHORITY_HANDOFF:"
                        + key.chunkX + ':' + key.chunkZ;
                if (recorder.shouldEmitEvent(eventKey, 500L)) {
                    recorder.event("SURFACE_MCA_LIVE_AUTHORITY_HANDOFF",
                            "chunk=" + key.chunkX + ',' + key.chunkZ
                                    + " action=drop_disk_keep_live_writer");
                }
                return ApplyResult.TERMINAL;
            }
        }

        int firstX = key.chunkX << 4;
        int firstZ = key.chunkZ << 4;
        MapManager.Region region = manager.getRegion(firstX >> 9, firstZ >> 9, true);
        if (region == null || !region.isLoaded()) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            String eventKey = "SURFACE_MCA_APPLY_WAIT:" + key.chunkX + ':' + key.chunkZ;
            if (recorder.shouldEmitEvent(eventKey, 500L)) {
                recorder.event("SURFACE_MCA_APPLY_WAIT",
                        "chunk=" + key.chunkX + ',' + key.chunkZ
                                + " reason=map_region_loading");
            }
            return ApplyResult.RETRY;
        }
        long[] packed = applyPackedScratch;
        int[] tints = applyTintScratch;
        boolean[] valid = applyValidScratch;
        java.util.Arrays.fill(packed, MapBlockData.EMPTY_PACKED);
        java.util.Arrays.fill(tints, SurfaceTintData.UNKNOWN);
        java.util.Arrays.fill(valid, false);
        int validColumns = 0;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int column = (localZ << 4) | localX;
                DecodedWorldChunkSource.SurfaceProjection projection =
                        columns[column];
                if (projection == null) continue;
                valid[column] = true;
                validColumns++;
                if (projection.empty()) continue;
                int blockIndex = region.getOrAddBlockIndex(projection.blockId());
                int biomeIndex = region.getOrAddBiomeIndex(projection.biomeId());
                MapBlockData data = MapBlockData.create(
                        projection.topY(), projection.floorY(), blockIndex, biomeIndex,
                        projection.blockLight(), projection.glowing(), projection.fluid(),
                        projection.flower(), projection.leaves());
                packed[column] = data.pack();
                tints[column] = projection.tint();
            }
        }
        if (validColumns == 0) return ApplyResult.TERMINAL;
        manager.commitSurfaceChunkSlice(key.chunkX, key.chunkZ, 0,
                packed, tints, valid, 0, 256);
        if (validColumns == 256) {
            MapManager.SurfaceChunkCommit completed = manager
                    .finishSurfaceChunkTransaction(key.chunkX, key.chunkZ);
            if (completed.chunkComplete()) {
                // Publish directly into the retained source database. Merely waking
                // the page forced a later capture probe to copy the same chunk and
                // allowed the minimap to remain empty despite completed disk work.
                RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
                if (stamp != null && stamp.isCurrent()) {
                    SurfaceRegionSourceDatabase.getInstance().publishCompletedChunk(
                            stamp, key.chunkX, key.chunkZ,
                            lane == null ? MapRequestLane.MINIMAP : lane);
                }
                com.velorise.simplemap.client.MapTextureManager.getInstance()
                        .wakeRegionCaptureForChunk(key.chunkX, key.chunkZ);
            }
        }
        return ApplyResult.APPLIED;
    }

    int pendingCount() {
        return pending.size();
    }

    boolean isPending(ServerLevel level, int chunkX, int chunkZ) {
        return level != null && pending.contains(new Key(
                level.dimension().location().toString(), chunkX, chunkZ));
    }

    private record Key(String dimension, int chunkX, int chunkZ) {
    }

    private enum ApplyResult {
        APPLIED,
        RETRY,
        TERMINAL
    }

    private record ReadyProjection(Key key, long generation,
            DecodedWorldChunkSource.SurfaceProjection[] columns,
            MapRequestLane lane, long retryAfterMs) {
        private ReadyProjection withRetry(long nextRetryMs) {
            return new ReadyProjection(key, generation, columns, lane, nextRetryMs);
        }
    }
}
