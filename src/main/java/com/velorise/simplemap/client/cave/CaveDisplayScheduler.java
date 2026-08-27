package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapDebugRecorder;
import com.velorise.simplemap.client.MapRequestLane;
import net.minecraft.world.level.Level;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Client-thread scheduler for transactional dense cave tiles.
 *
 * <p>Initial loads remain 256-column atomic transactions. Known LIVE tiles can be
 * patched by a coalesced set of dirty columns; the old tile remains renderable until
 * the patch snapshot is validated and committed. A patch never seeds from DISK or
 * WORLD_SAVE authority, because doing so would falsely promote unchanged columns to
 * LIVE.</p>
 */
final class CaveDisplayScheduler {
    /*
     * A live tile costs 256 vertical column projections. A 512-entry backlog was
     * over 130k columns and took many seconds to drain after opening/switching a
     * dimension, while making every viewport handoff touch a huge cold queue. Eight
     * 64x64 pages are enough build-ahead; the rolling page frontier revisits work
     * that could not be admitted.
     */
    private static final int MAX_TASKS = 256;
    /**
     * Primitive packet-arrival frontier. It deliberately owns only the newest
     * travel history: one chunk is completed coherently before the next becomes
     * foreground demand, while the bounded FIFO drops cold history behind a very
     * fast player instead of creating an unbounded post-travel backlog.
     */
    private static final int MAX_LOADED_CHUNK_FRONTIER = 8192;
    private static final int MAX_HEAD_RETRY_PULSES = 160;
    private static final int LOADED_CHUNKS_ADMITTED_PER_PULSE = 48;
    private static final int LOADED_CHUNKS_INSPECTED_PER_PULSE = 1024;
    private static final int COLUMN_BURST = 64;
    /** Deadline checks inside one vertical column, not merely between columns. */
    private static final int COLUMN_VERTICAL_BURST = 16;
    private static final long VIEWPORT_REFRESH_NANOS = 150_000_000L;

    private final CaveTileRepository repository;
    private final CaveChunkReadinessTracker readiness;
    private final CaveDisplayProjector projector = new CaveDisplayProjector();
    /*
     * Queue entries are immutable scheduling snapshots. Viewport handoff used to
     * remove and reinsert every retained Task directly in PriorityQueue. Since
     * PriorityQueue.remove(Object) is linear, a 512-task cave viewport turned one
     * small pan into O(n^2) client-thread work. Versioned entries make reprioritizing
     * and cancellation O(log n): stale heap entries are discarded lazily.
     */
    private final PriorityQueue<ReadyEntry> queue = new PriorityQueue<>();
    private final PriorityQueue<DeferredEntry> deferred = new PriorityQueue<>();
    private final Map<TaskKey, Task> queued = new HashMap<>();
    private final LongArrayFIFOQueue loadedChunkFrontier = new LongArrayFIFOQueue();
    private final LongOpenHashSet loadedChunkSet = new LongOpenHashSet();
    private final Long2IntOpenHashMap loadedChunkAttempts = new Long2IntOpenHashMap();
    private long sequence;
    private CaveView activeView;
    /** Retained 16-block identity. */
    private int activeLayerY = Integer.MIN_VALUE;
    /** Exact Top-Y currently requested inside the active band. */
    private int activeProjectionTopY = Integer.MIN_VALUE;
    private final EnumMap<MapRequestLane, ViewportState> viewports =
            new EnumMap<>(MapRequestLane.class);

    CaveDisplayScheduler(CaveTileRepository repository,
            CaveChunkReadinessTracker readiness) {
        this.repository = repository;
        this.readiness = readiness;
        for (MapRequestLane lane : MapRequestLane.values()) {
            viewports.put(lane, new ViewportState());
        }
    }

    void reset() {
        queue.clear();
        queued.clear();
        deferred.clear();
        loadedChunkFrontier.clear();
        loadedChunkSet.clear();
        loadedChunkAttempts.clear();
        activeView = null;
        activeLayerY = Integer.MIN_VALUE;
        activeProjectionTopY = Integer.MIN_VALUE;
        for (ViewportState state : viewports.values()) state.clear();
    }

    int queuedTaskCount() {
        return queued.size();
    }

    /** Cancels only unpublished work after a teleport/world packet transition. */
    void cancelInFlight() {
        for (Task task : queued.values()) task.cancelled = true;
        queue.clear();
        queued.clear();
        deferred.clear();
        loadedChunkFrontier.clear();
        loadedChunkSet.clear();
        loadedChunkAttempts.clear();
        for (ViewportState state : viewports.values()) state.clear();
    }

    /** Packet path: append one primitive key and perform no projection work. */
    void enqueueLoadedChunk(int chunkX, int chunkZ) {
        long key = packChunk(chunkX, chunkZ);
        if (!loadedChunkSet.add(key)) return;
        while (loadedChunkFrontier.size() >= MAX_LOADED_CHUNK_FRONTIER) {
            long dropped = loadedChunkFrontier.dequeueLong();
            loadedChunkSet.remove(dropped);
            loadedChunkAttempts.remove(dropped);
        }
        loadedChunkFrontier.enqueue(key);
    }

    /**
     * Promotes a tiny fair active set from the loaded-chunk frontier.
     *
     * <p>The former single sticky head preserved atomic tiles but one light/section
     * readiness wait could block every later chunk on the route. Four centre-biased
     * transactions are enough to hide that latency while still preventing hundreds
     * of quarter-built tiles. Keys rotate after admission and disappear only after
     * the selected projection is transactionally published.</p>
     */
    void admitLoadedChunk(Level level, CaveView view, int layerY,
            double centerChunkX, double centerChunkZ, int basePriority) {
        activateMode(view, layerY);
        int admitted = 0;
        int inspected = 0;
        int available = loadedChunkFrontier.size();
        int admissionBudget = CaveModeTransitionPolicy.loadedFrontierBudget(
                LOADED_CHUNKS_ADMITTED_PER_PULSE);
        while (!loadedChunkFrontier.isEmpty()
                && admitted < admissionBudget
                && inspected < Math.min(available,
                        LOADED_CHUNKS_INSPECTED_PER_PULSE)) {
            long key = loadedChunkFrontier.dequeueLong();
            inspected++;
            if (!loadedChunkSet.contains(key)) continue;
            int chunkX = unpackChunkX(key);
            int chunkZ = unpackChunkZ(key);
            if (!level.hasChunk(chunkX, chunkZ)
                    || repository.hasFreshDisplayTileSource(view, layerY,
                            chunkX, chunkZ, DenseCaveTile.Source.LIVE)) {
                loadedChunkSet.remove(key);
                loadedChunkAttempts.remove(key);
                continue;
            }
            double dx = chunkX - centerChunkX;
            double dz = chunkZ - centerChunkZ;
            int distancePenalty = (int) Math.min(120_000.0D,
                    (dx * dx + dz * dz) * 500.0D);
            enqueue(chunkX, chunkZ, view, layerY,
                    basePriority + 400_000 - distancePenalty,
                    null, -1, false);
            admitted++;
            int attempts = loadedChunkAttempts.addTo(key, 1) + 1;
            if (attempts >= MAX_HEAD_RETRY_PULSES) {
                loadedChunkAttempts.put(key, 0);
            }
            loadedChunkFrontier.enqueue(key);
        }
    }

    /**
     * Source-only AUTO warmup. Unlike enqueueViewport this does not create a
     * MINIMAP presentation lease, so preparing Cave behind a still-visible Surface
     * frame cannot generate foreground handoff/revoke churn. Completed tiles remain
     * in CaveTileRepository and the real minimap viewport can publish them after the
     * display latch opens.
     */
    void enqueueWarmupWindow(Level level, CaveView view, int layerY,
            int centerChunkX, int centerChunkZ, int radius, int basePriority) {
        if (level == null || radius < 0) return;
        activateMode(view, layerY);
        int ordinal = 0;
        for (int ring = 0; ring <= radius; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;
                    int priority = basePriority - ordinal++ * 2_000;
                    if (!level.hasChunk(chunkX, chunkZ)
                            || repository.hasFreshDisplayTileSource(view, layerY,
                                    chunkX, chunkZ, DenseCaveTile.Source.LIVE)) {
                        continue;
                    }
                    // null lane = finite source transaction only. It does not
                    // register a VisiblePlanner/foreground rectangle and disappears
                    // after commit. The ring traversal is allocation-free.
                    enqueue(chunkX, chunkZ, view, layerY, priority, null, -1, false);
                }
            }
        }
    }

    int loadedChunkFrontierSize() {
        return loadedChunkFrontier.size();
    }

    private static long packChunk(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static int unpackChunkX(long key) { return (int) (key >> 32); }
    private static int unpackChunkZ(long key) { return (int) key; }

    void cancelChunk(int chunkX, int chunkZ) {
        var iterator = queued.entrySet().iterator();
        while (iterator.hasNext()) {
            Task task = iterator.next().getValue();
            if (task.key.chunkX() != chunkX || task.key.chunkZ() != chunkZ) continue;
            task.cancelled = true;
            iterator.remove();
        }
    }

    void enqueueViewport(Level level, CaveView view, int layerY,
            int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
            double centerChunkX, double centerChunkZ, int basePriority,
            MapRequestLane lane) {
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        int normalizedLayer = DenseCaveTile.normalizeLayer(view, layerY);
        activateMode(view, layerY);
        ViewportState state = viewports.get(effectiveLane);
        long now = System.nanoTime();
        boolean changed = !state.matchesShape(view, normalizedLayer, layerY,
                minChunkX, maxChunkX, minChunkZ, maxChunkZ);
        if (!changed && now - state.lastEnqueueNanos < VIEWPORT_REFRESH_NANOS) return;
        state.update(view, normalizedLayer, layerY,
                minChunkX, maxChunkX, minChunkZ, maxChunkZ,
                centerChunkX, centerChunkZ, effectiveLane, now, changed);

        if (changed) {
            int retained = 0;
            int cancelled = 0;
            var iterator = queued.entrySet().iterator();
            while (iterator.hasNext()) {
                Task task = iterator.next().getValue();
                if (!task.viewportDemand || task.persistentDemand) continue;
                if (state.contains(task)) {
                    task.priority = state.viewportPriority(task, basePriority,
                            effectiveLane);
                    task.sequence = sequence++;
                    if (!task.deferredState) offerReady(task);
                    retained++;
                    continue;
                }
                if (wantedByOtherViewport(task, now, effectiveLane)) continue;
                cancelQueuedTask(task);
                iterator.remove();
                cancelled++;
            }
            if (effectiveLane == MapRequestLane.FULLSCREEN) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent("CAVE_LIVE_VIEWPORT_HANDOFF", 100L)) {
                    recorder.event("CAVE_LIVE_VIEWPORT_HANDOFF",
                            "retained=" + retained + " cancelled=" + cancelled
                                    + " queued=" + queued.size());
                }
            }
            compactQueuesIfNeeded();
        }

        if (effectiveLane == MapRequestLane.FULLSCREEN) {
            /*
             * Advance a bounded rolling 64x64 page frontier. Never wait for one
             * incomplete live page: old tiles remain visible and the missed page
             * is revisited on the next cycle. This prevents a single unavailable
             * chunk from freezing the full-map viewport.
             */
            int totalPages = state.pagePlan.length;
            if (totalPages <= 0) return;
            if (state.fullscreenPageCursor >= totalPages) {
                state.fullscreenPageCursor = 0;
                state.completedCycles++;
            }
            com.velorise.simplemap.client.MapPerformanceGovernor governor =
                    com.velorise.simplemap.client.MapPerformanceGovernor.getInstance();
            int pageBudget = governor.underPressure() ? 2
                    : governor.hasStreamingHeadroom() ? 8 : 4;
            for (int page = 0; page < pageBudget
                    && state.fullscreenPageCursor < totalPages; page++) {
                int ordinal = state.fullscreenPageCursor++;
                long packedPage = state.pagePlan[ordinal];
                int pageX = CaveLoadHierarchy.x(packedPage);
                int pageZ = CaveLoadHierarchy.z(packedPage);
                int firstChunkX = pageX * 4;
                int firstChunkZ = pageZ * 4;
                for (int localZ = 0; localZ < 4; localZ++) {
                    for (int localX = 0; localX < 4; localX++) {
                        int chunkX = firstChunkX + localX;
                        int chunkZ = firstChunkZ + localZ;
                        if (chunkX < minChunkX || chunkX > maxChunkX
                                || chunkZ < minChunkZ || chunkZ > maxChunkZ
                                || !level.hasChunk(chunkX, chunkZ)) continue;
                        if (repository.hasFreshDisplayTileSource(view, layerY,
                                chunkX, chunkZ, DenseCaveTile.Source.LIVE)) continue;
                        int localOrdinal = localZ * 4 + localX;
                        enqueue(chunkX, chunkZ, view, layerY,
                                basePriority + 220_000 - ordinal * 250
                                        - localOrdinal * 1_000,
                                effectiveLane, -1, false);
                    }
                }
            }
            return;
        }

        /*
         * HUD/minimap demand used to rewalk the complete render-distance rectangle
         * on every 150 ms refresh. A 27x27 window means 729 hasChunk calls, TaskKey
         * allocations and HashMap probes before one cave column is projected. Keep
         * a stable centre-out primitive plan and admit only a small rolling slice.
         * Fresh tiles are skipped, and the cursor wraps so deferred/unavailable
         * chunks are revisited without one large client-thread burst.
         */
        int totalChunks = state.chunkPlan.length;
        if (totalChunks <= 0) return;
        if (state.chunkCursor >= totalChunks) {
            state.chunkCursor = 0;
            state.completedCycles++;
        }
        int chunkBudget = com.velorise.simplemap.client.MapPerformanceGovernor
                .getInstance().underPressure() ? 48 : 128;
        chunkBudget = CaveModeTransitionPolicy.viewportChunkBudget(chunkBudget);
        int considered = 0;
        while (considered < chunkBudget && state.chunkCursor < totalChunks) {
            int ordinal = state.chunkCursor++;
            considered++;
            long packedChunk = state.chunkPlan[ordinal];
            int chunkX = CaveLoadHierarchy.x(packedChunk);
            int chunkZ = CaveLoadHierarchy.z(packedChunk);
            if (!level.hasChunk(chunkX, chunkZ)
                    || repository.hasFreshDisplayTileSource(view, layerY,
                            chunkX, chunkZ, DenseCaveTile.Source.LIVE)) continue;
            enqueue(chunkX, chunkZ, view, layerY,
                    state.viewportPriority(chunkX, chunkZ, basePriority,
                            effectiveLane),
                    effectiveLane, -1, false);
        }
    }

    /** Normal viewport demand: build a full tile only when no patch already owns it. */
    void enqueue(int chunkX, int chunkZ, CaveView view, int layerY, int priority) {
        enqueue(chunkX, chunkZ, view, layerY, priority, null, -1, false);
    }

    /** Authoritative chunk/light/manual refresh: any pending patch must become full. */
    void enqueueReplacement(int chunkX, int chunkZ, CaveView view, int layerY,
            int priority) {
        enqueue(chunkX, chunkZ, view, layerY, priority, null, -1, true);
    }

    void enqueuePatch(int chunkX, int chunkZ, CaveView view, int layerY,
            int localX, int localZ, int priority) {
        enqueue(chunkX, chunkZ, view, layerY, priority, null,
                DenseCaveTile.index(localX, localZ), false);
    }

    private void enqueue(int chunkX, int chunkZ, CaveView view, int layerY,
            int priority, MapRequestLane viewportLane, int patchColumn,
            boolean forceFullReplacement) {
        int normalizedLayer = DenseCaveTile.normalizeLayer(view, layerY);
        boolean fullProjection = patchColumn < 0;
        if (fullProjection && repository.hasFreshDisplayTileSource(view,
                layerY, chunkX, chunkZ, DenseCaveTile.Source.LIVE)) return;

        TaskKey key = new TaskKey(chunkX, chunkZ, view, normalizedLayer);
        Task existing = queued.get(key);
        if (existing != null) {
            boolean retargeted = existing.projectionTopY != layerY;
            boolean priorityChanged = priority > existing.priority;
            if (retargeted) {
                // Inside one 16-block band, a transaction that already consumed
                // client-thread scan time is allowed to finish as fallback. Queue
                // the newest exact Top-Y as a follow-up instead of throwing the
                // partial work away. An untouched task can be retargeted in place.
                if (existing.hasStarted()) existing.requestFollowUp(layerY);
                else existing.retarget(layerY);
                existing.retryAfterTick = 0L;
            }
            existing.priority = Math.max(existing.priority, priority);
            if (fullProjection) {
                // Viewport demand must not destroy an incremental dirty-column patch.
                // An exact Top-Y retarget or authoritative replacement is full-tile.
                if (retargeted || forceFullReplacement) existing.upgradeToFullProjection();
            } else if (!retargeted) {
                existing.addPatchColumn(patchColumn);
            }
            if (viewportLane != null) {
                existing.viewportDemand = true;
            } else {
                existing.persistentDemand = true;
            }
            if (retargeted) {
                existing.sequence = sequence++;
                existing.retryAfterTick = 0L;
                offerReady(existing);
            } else if (!existing.deferredState && priorityChanged) {
                existing.sequence = sequence++;
                offerReady(existing);
            }
            return;
        }
        if (queued.size() >= MAX_TASKS
                && !evictWeakViewportTask(priority)) return;
        Task task = new Task(key, layerY, priority, sequence++, repository.generation(),
                viewportLane != null, fullProjection);
        if (!fullProjection) task.addPatchColumn(patchColumn);
        queued.put(key, task);
        offerReady(task);
    }

    /**
     * Mutation/player hot-set work must not be rejected merely because a distant
     * fullscreen frontier filled the queue first. Evict only an idle, non-persistent
     * viewport task with lower priority; started transactions are never torn down.
     */
    private boolean evictWeakViewportTask(int incomingPriority) {
        Task victim = null;
        for (Task candidate : queued.values()) {
            if (!candidate.viewportDemand || candidate.persistentDemand
                    || candidate.hasStarted() || candidate.cancelled) continue;
            if (candidate.priority >= incomingPriority) continue;
            if (victim == null || candidate.priority < victim.priority
                    || (candidate.priority == victim.priority
                            && candidate.sequence < victim.sequence)) {
                victim = candidate;
            }
        }
        if (victim == null) return false;
        victim.cancelled = true;
        queued.remove(victim.key, victim);
        return true;
    }

    int process(Level level, long deadlineNanos) {
        int columns = 0;
        long gameTick = level.getGameTime();
        while (System.nanoTime() < deadlineNanos) {
            Task task = pollReady(gameTick);
            if (task == null) break;
            boolean viewportRelevant = task.persistentDemand
                    || !task.viewportDemand
                    || wantedByAnyViewport(task, System.nanoTime());
            if (!repository.isGenerationCurrent(task.repositoryGeneration)
                    || !viewportRelevant) {
                continue;
            }
            if (task.fullProjection
                    && repository.hasFreshDisplayTileSource(task.key.view(),
                            task.projectionTopY, task.key.chunkX(), task.key.chunkZ(),
                            DenseCaveTile.Source.LIVE)) {
                continue;
            }

            if (task.snapshot == null) {
                task.snapshot = readiness.acquire(level,
                        task.key.chunkX(), task.key.chunkZ());
                if (task.snapshot == null) {
                    defer(task, gameTick, false);
                    continue;
                }
                task.source = new LiveCaveChunkSource(task.snapshot);
                if (task.fullProjection) {
                    task.builder = new DenseCaveTile.Builder();
                } else {
                    DenseCaveTile seed = repository.getLoadedDisplayTile(
                            task.key.view(), task.projectionTopY,
                            task.key.chunkX(), task.key.chunkZ());
                    if (seed == null || seed.source() != DenseCaveTile.Source.LIVE
                            || (task.key.view() != CaveView.FULL
                                    && seed.projectionTopY() != task.projectionTopY)) {
                        task.promoteToFullUsingCurrentSnapshot();
                        task.builder = new DenseCaveTile.Builder();
                    } else {
                        task.seedRevision = seed.revision();
                        task.builder = new DenseCaveTile.Builder(seed);
                    }
                }
            } else if (!readiness.stillValid(level, task.snapshot)) {
                defer(task, gameTick, true);
                continue;
            }

            int burst = 0;
            while (task.hasRemainingWork() && burst < COLUMN_BURST
                    && System.nanoTime() < deadlineNanos) {
                if (task.activeColumn < 0) {
                    task.activeColumn = task.takeNextColumn();
                    if (task.activeColumn < 0) break;
                    task.columnCursor = projector.beginColumn(task.source,
                            task.key.view(), task.projectionTopY,
                            task.activeColumn & 15, task.activeColumn >>> 4,
                            task.builder);
                }
                boolean completedColumn = projector.projectColumnSlice(task.source,
                        task.activeColumn & 15, task.activeColumn >>> 4,
                        task.builder, task.columnCursor, deadlineNanos,
                        COLUMN_VERTICAL_BURST);
                if (!completedColumn) break;
                task.activeColumn = -1;
                task.columnCursor = null;
                columns++;
                burst++;
            }

            if (!task.hasRemainingWork()) {
                if (!readiness.stillValid(level, task.snapshot)) {
                    defer(task, gameTick, true);
                    continue;
                }
                if (!task.fullProjection && !repository.isCurrentDisplayTileRevision(
                        task.key.view(), task.projectionTopY, task.key.chunkX(),
                        task.key.chunkZ(), task.seedRevision, DenseCaveTile.Source.LIVE)) {
                    // A newer tile committed while this patch was being assembled.
                    // Restart from that tile instead of overwriting unrelated columns.
                    defer(task, gameTick, true);
                    continue;
                }
                DenseCaveTile tile = task.builder.buildOwned(task.key.chunkX(),
                        task.key.chunkZ(), task.key.view(), task.key.layerY(),
                        task.projectionTopY, System.nanoTime(), DenseCaveTile.Source.LIVE);
                // Content completed for a superseded presentation is still a valid
                // exact product. Retain it in the bounded band LRU; the active exact
                // key remains untouched and can consume the follow-up transaction.
                repository.commitDisplayTile(tile, task.repositoryGeneration);
                if (task.hasFollowUp()) {
                    task.activateFollowUp();
                    task.priority += 2_000;
                    requeue(task);
                }
            } else {
                task.priority += 2_000;
                requeue(task);
            }
        }
        return columns;
    }

    private Task pollReady(long gameTick) {
        promoteDeferred(gameTick);
        return pollValid();
    }

    private void promoteDeferred(long gameTick) {
        while (true) {
            DeferredEntry entry = deferred.peek();
            if (entry == null || entry.retryAfterTick > gameTick) return;
            deferred.poll();
            Task task = entry.task;
            if (!entry.current() || task.cancelled
                    || queued.get(task.key) != task) continue;
            offerReady(task);
        }
    }

    private void defer(Task task, long gameTick, boolean restartProjection) {
        if (restartProjection) task.restartProjection();
        task.retryAfterTick = gameTick + CaveChunkReadinessTracker.RETRY_DELAY_TICKS;
        task.priority = Math.max(Integer.MIN_VALUE + 10_000, task.priority - 4_000);
        task.sequence = sequence++;
        queued.put(task.key, task);
        offerDeferred(task);
    }

    private void requeue(Task task) {
        task.sequence = sequence++;
        queued.put(task.key, task);
        offerReady(task);
    }

    private void offerReady(Task task) {
        task.deferredState = false;
        int version = ++task.scheduleVersion;
        queue.offer(new ReadyEntry(task, task.priority, task.sequence, version));
    }

    private void offerDeferred(Task task) {
        task.deferredState = true;
        int version = ++task.scheduleVersion;
        deferred.offer(new DeferredEntry(task, task.retryAfterTick,
                task.sequence, version));
    }

    /** Bounds stale lazy entries after a fast pan/zoom burst. */
    private void compactQueuesIfNeeded() {
        int live = queued.size();
        int maximumEntries = Math.max(64, live * 3 + 32);
        if (queue.size() > maximumEntries) {
            queue.clear();
            for (Task task : queued.values()) {
                if (!task.cancelled && !task.deferredState) {
                    queue.offer(new ReadyEntry(task, task.priority,
                            task.sequence, task.scheduleVersion));
                }
            }
        }
        if (deferred.size() > maximumEntries) {
            deferred.clear();
            for (Task task : queued.values()) {
                if (!task.cancelled && task.deferredState) {
                    deferred.offer(new DeferredEntry(task, task.retryAfterTick,
                            task.sequence, task.scheduleVersion));
                }
            }
        }
    }

    private void activateMode(CaveView view, int layerY) {
        int normalized = DenseCaveTile.normalizeLayer(view, layerY);
        if (view == activeView && normalized == activeLayerY) {
            // Xaero keeps one cache layer for the whole 16-block band. Changing
            // the exact Top-Y inside that band retargets unpublished transactions,
            // but it must not clear/cancel the already visible layer working set.
            activeProjectionTopY = layerY;
            return;
        }
        activeView = view;
        activeLayerY = normalized;
        activeProjectionTopY = layerY;
        for (ViewportState state : viewports.values()) state.clear();

        // Crossing a band, mode or dimension changes semantic cache identity.
        // Only then cancel work that cannot contribute to the newly selected layer.
        var iterator = queued.entrySet().iterator();
        while (iterator.hasNext()) {
            Task task = iterator.next().getValue();
            if (task.key.view() == view && task.key.layerY() == normalized) continue;
            cancelQueuedTask(task);
            iterator.remove();
        }
    }

    private void cancelQueuedTask(Task task) {
        if (task == null) return;
        task.cancelled = true;
    }

    private boolean wantedByOtherViewport(Task task, long nowNanos,
            MapRequestLane excludedLane) {
        for (Map.Entry<MapRequestLane, ViewportState> entry : viewports.entrySet()) {
            MapRequestLane lane = entry.getKey();
            if (lane == excludedLane) continue;
            ViewportState state = entry.getValue();
            if (state.isFresh(nowNanos, lane) && state.contains(task)) return true;
        }
        return false;
    }

    private boolean wantedByAnyViewport(Task task, long nowNanos) {
        for (Map.Entry<MapRequestLane, ViewportState> entry : viewports.entrySet()) {
            MapRequestLane lane = entry.getKey();
            ViewportState state = entry.getValue();
            if (state.isFresh(nowNanos, lane) && state.contains(task)) return true;
        }
        return false;
    }

    private static final class ViewportState {
        private CaveView view;
        private int layerY = Integer.MIN_VALUE;
        private int projectionTopY = Integer.MIN_VALUE;
        private int minChunkX = Integer.MIN_VALUE;
        private int maxChunkX = Integer.MIN_VALUE;
        private int minChunkZ = Integer.MIN_VALUE;
        private int maxChunkZ = Integer.MIN_VALUE;
        private long lastEnqueueNanos;
        private long[] pagePlan = new long[0];
        private CaveLoadHierarchy.OrdinalIndex pageOrdinals = CaveLoadHierarchy.buildOrdinalIndex(new long[0]);
        private int fullscreenPageCursor;
        private long[] chunkPlan = new long[0];
        private int chunkCursor;
        private double centerChunkX;
        private double centerChunkZ;
        private long completedCycles;

        private boolean matchesShape(CaveView view, int layerY, int projectionTopY,
                int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
            return this.view == view && this.layerY == layerY
                    && this.projectionTopY == projectionTopY
                    && this.minChunkX == minChunkX && this.maxChunkX == maxChunkX
                    && this.minChunkZ == minChunkZ && this.maxChunkZ == maxChunkZ;
        }

        private void update(CaveView view, int layerY, int projectionTopY,
                int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
                double centerChunkX, double centerChunkZ,
                MapRequestLane lane, long nowNanos, boolean changed) {
            if (changed) {
                if (lane == MapRequestLane.FULLSCREEN) {
                    int previousMinPageX = Math.floorDiv(this.minChunkX, 4);
                    int previousMaxPageX = Math.floorDiv(this.maxChunkX, 4);
                    int previousMinPageZ = Math.floorDiv(this.minChunkZ, 4);
                    int previousMaxPageZ = Math.floorDiv(this.maxChunkZ, 4);
                    int minPageX = Math.floorDiv(minChunkX, 4);
                    int maxPageX = Math.floorDiv(maxChunkX, 4);
                    int minPageZ = Math.floorDiv(minChunkZ, 4);
                    int maxPageZ = Math.floorDiv(maxChunkZ, 4);
                    boolean continuousPan = this.view == view && this.layerY == layerY
                            && this.projectionTopY == projectionTopY
                            && rectanglesOverlap(previousMinPageX, previousMaxPageX,
                                    previousMinPageZ, previousMaxPageZ,
                                    minPageX, maxPageX, minPageZ, maxPageZ);
                    int centerPageX = clamp((int) Math.floor(centerChunkX / 4.0),
                            minPageX, maxPageX);
                    int centerPageZ = clamp((int) Math.floor(centerChunkZ / 4.0),
                            minPageZ, maxPageZ);
                    pagePlan = CaveLoadHierarchy.buildVisiblePagePlan(
                            minPageX, maxPageX, minPageZ, maxPageZ,
                            centerPageX, centerPageZ, true, continuousPan,
                            previousMinPageX, previousMaxPageX,
                            previousMinPageZ, previousMaxPageZ);
                    pageOrdinals = CaveLoadHierarchy.buildOrdinalIndex(pagePlan);
                    fullscreenPageCursor = 0;
                    chunkPlan = new long[0];
                    chunkCursor = 0;
                } else {
                    chunkPlan = CaveLoadHierarchy.buildRegionPlan(
                            minChunkX, maxChunkX, minChunkZ, maxChunkZ,
                            centerChunkX, centerChunkZ);
                    chunkCursor = 0;
                    pagePlan = new long[0];
                    pageOrdinals = CaveLoadHierarchy.buildOrdinalIndex(new long[0]);
                    fullscreenPageCursor = 0;
                }
                completedCycles = 0L;
            }
            this.view = view;
            this.layerY = layerY;
            this.projectionTopY = projectionTopY;
            this.minChunkX = minChunkX;
            this.maxChunkX = maxChunkX;
            this.minChunkZ = minChunkZ;
            this.maxChunkZ = maxChunkZ;
            this.centerChunkX = centerChunkX;
            this.centerChunkZ = centerChunkZ;
            this.lastEnqueueNanos = nowNanos;
        }

        private static int clamp(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }

        private static boolean rectanglesOverlap(int firstMinX, int firstMaxX,
                int firstMinZ, int firstMaxZ, int secondMinX, int secondMaxX,
                int secondMinZ, int secondMaxZ) {
            return firstMinX <= secondMaxX && firstMaxX >= secondMinX
                    && firstMinZ <= secondMaxZ && firstMaxZ >= secondMinZ;
        }

        private int viewportPriority(Task task, int basePriority,
                MapRequestLane lane) {
            return viewportPriority(task.key.chunkX(), task.key.chunkZ(),
                    basePriority, lane);
        }

        private int viewportPriority(int chunkX, int chunkZ, int basePriority,
                MapRequestLane lane) {
            if (lane != MapRequestLane.FULLSCREEN) {
                double dx = chunkX - centerChunkX;
                double dz = chunkZ - centerChunkZ;
                int distance = (int) Math.min(900_000.0,
                        (dx * dx + dz * dz) * 1_000.0);
                return basePriority - distance;
            }
            return fullscreenPriority(chunkX, chunkZ, basePriority);
        }

        private int fullscreenPriority(int chunkX, int chunkZ, int basePriority) {
            int pageX = Math.floorDiv(chunkX, 4);
            int pageZ = Math.floorDiv(chunkZ, 4);
            int ordinal = pageOrdinals.getOrDefault(
                    CaveLoadHierarchy.pack(pageX, pageZ), -1);
            if (ordinal < 0) ordinal = 0;

            // A page is only revealed transactionally, but completing its four-by-four
            // chunk set centre-out reduces the time until the visible page becomes
            // authoritative. Retained tasks keep the same ordering after a viewport
            // handoff instead of reverting to the old top-left row-major priority.
            int localX = Math.floorMod(chunkX, 4);
            int localZ = Math.floorMod(chunkZ, 4);
            double localDx = localX - 1.5D;
            double localDz = localZ - 1.5D;
            int localDistancePenalty = (int) Math.round(
                    (localDx * localDx + localDz * localDz) * 1_000.0D);
            return basePriority + 220_000 - ordinal * 250
                    - localDistancePenalty;
        }

        private boolean contains(Task task) {
            return task.key.view() == view && task.key.layerY() == layerY
                    && task.isRelevantToProjection(projectionTopY)
                    && task.key.chunkX() >= minChunkX - 1
                    && task.key.chunkX() <= maxChunkX + 1
                    && task.key.chunkZ() >= minChunkZ - 1
                    && task.key.chunkZ() <= maxChunkZ + 1;
        }

        private boolean isFresh(long nowNanos, MapRequestLane lane) {
            return lastEnqueueNanos != 0L
                    && nowNanos - lastEnqueueNanos
                            <= lane.requestTtlMs() * 1_000_000L;
        }

        private void clear() {
            view = null;
            layerY = Integer.MIN_VALUE;
            projectionTopY = Integer.MIN_VALUE;
            minChunkX = maxChunkX = minChunkZ = maxChunkZ = Integer.MIN_VALUE;
            lastEnqueueNanos = 0L;
            pagePlan = new long[0];
            pageOrdinals = CaveLoadHierarchy.buildOrdinalIndex(new long[0]);
            fullscreenPageCursor = 0;
            chunkPlan = new long[0];
            chunkCursor = 0;
            centerChunkX = 0.0;
            centerChunkZ = 0.0;
            completedCycles = 0L;
        }
    }

    private Task pollValid() {
        while (true) {
            ReadyEntry entry = queue.poll();
            if (entry == null) return null;
            Task task = entry.task;
            if (!entry.current() || task.cancelled || task.deferredState) continue;
            if (!queued.remove(task.key, task)) continue;
            return task;
        }
    }

    private record ReadyEntry(Task task, int priority, long sequence,
            int version) implements Comparable<ReadyEntry> {
        private boolean current() {
            return task.scheduleVersion == version;
        }

        @Override
        public int compareTo(ReadyEntry other) {
            int byPriority = Integer.compare(other.priority, priority);
            return byPriority != 0 ? byPriority
                    : Long.compare(sequence, other.sequence);
        }
    }

    private record DeferredEntry(Task task, long retryAfterTick, long sequence,
            int version) implements Comparable<DeferredEntry> {
        private boolean current() {
            return task.scheduleVersion == version && task.deferredState;
        }

        @Override
        public int compareTo(DeferredEntry other) {
            int byRetry = Long.compare(retryAfterTick, other.retryAfterTick);
            return byRetry != 0 ? byRetry : Long.compare(sequence, other.sequence);
        }
    }

    private record TaskKey(int chunkX, int chunkZ, CaveView view, int layerY) {
    }

    private static final class Task {
        private final TaskKey key;
        private final long repositoryGeneration;
        private int projectionTopY;
        private int followUpProjectionTopY = Integer.MIN_VALUE;
        private final long[] requestedColumns = new long[4];
        private final long[] completedColumns = new long[4];
        private boolean viewportDemand;
        private boolean persistentDemand;
        private DenseCaveTile.Builder builder;
        private int priority;
        private long sequence;
        private int nextFullColumn;
        private int patchCursor;
        private int processedColumns;
        private long retryAfterTick;
        private long seedRevision;
        private CaveChunkReadinessTracker.Snapshot snapshot;
        private LiveCaveChunkSource source;
        private boolean fullProjection;
        private boolean cancelled;
        private boolean deferredState;
        private int scheduleVersion;
        private int activeColumn = -1;
        private CaveDisplayProjector.ColumnCursor columnCursor;

        private Task(TaskKey key, int projectionTopY, int priority, long sequence,
                long repositoryGeneration, boolean viewportDemand,
                boolean fullProjection) {
            this.key = key;
            this.projectionTopY = projectionTopY;
            this.priority = priority;
            this.sequence = sequence;
            this.repositoryGeneration = repositoryGeneration;
            this.viewportDemand = viewportDemand;
            this.persistentDemand = !viewportDemand;
            this.fullProjection = fullProjection;
        }

        private boolean hasStarted() {
            return processedColumns > 0;
        }

        private void retarget(int projectionTopY) {
            this.projectionTopY = projectionTopY;
            this.followUpProjectionTopY = Integer.MIN_VALUE;
            this.fullProjection = true;
            Arrays.fill(requestedColumns, 0L);
            Arrays.fill(completedColumns, 0L);
            restartProjection();
        }

        private void requestFollowUp(int projectionTopY) {
            if (projectionTopY != this.projectionTopY) {
                this.followUpProjectionTopY = projectionTopY;
            }
        }

        private boolean hasFollowUp() {
            return followUpProjectionTopY != Integer.MIN_VALUE
                    && followUpProjectionTopY != projectionTopY;
        }

        private boolean isRelevantToProjection(int projectionTopY) {
            return this.projectionTopY == projectionTopY
                    || this.followUpProjectionTopY == projectionTopY;
        }

        private void activateFollowUp() {
            int nextProjection = followUpProjectionTopY;
            followUpProjectionTopY = Integer.MIN_VALUE;
            retarget(nextProjection);
        }

        private void addPatchColumn(int column) {
            if (fullProjection) return;
            int normalized = column & 255;
            int word = normalized >>> 6;
            long bit = 1L << (normalized & 63);
            requestedColumns[word] |= bit;
            completedColumns[word] &= ~bit;
            patchCursor = Math.min(patchCursor, normalized);
        }

        private void upgradeToFullProjection() {
            if (fullProjection) return;
            fullProjection = true;
            Arrays.fill(requestedColumns, 0L);
            Arrays.fill(completedColumns, 0L);
            restartProjection();
        }

        private void promoteToFullUsingCurrentSnapshot() {
            fullProjection = true;
            Arrays.fill(requestedColumns, 0L);
            Arrays.fill(completedColumns, 0L);
            nextFullColumn = 0;
            patchCursor = 0;
            processedColumns = 0;
            seedRevision = 0L;
        }

        private boolean hasRemainingColumns() {
            if (fullProjection) return nextFullColumn < DenseCaveTile.COLUMN_COUNT;
            for (int i = 0; i < requestedColumns.length; i++) {
                if ((requestedColumns[i] & ~completedColumns[i]) != 0L) return true;
            }
            return false;
        }

        private boolean hasRemainingWork() {
            return activeColumn >= 0 || hasRemainingColumns();
        }

        private int takeNextColumn() {
            if (fullProjection) {
                if (nextFullColumn >= DenseCaveTile.COLUMN_COUNT) return -1;
                processedColumns++;
                return nextFullColumn++;
            }
            for (int offset = 0; offset < DenseCaveTile.COLUMN_COUNT; offset++) {
                int column = (patchCursor + offset) & 255;
                int word = column >>> 6;
                long bit = 1L << (column & 63);
                if ((requestedColumns[word] & bit) == 0L
                        || (completedColumns[word] & bit) != 0L) continue;
                completedColumns[word] |= bit;
                patchCursor = (column + 1) & 255;
                processedColumns++;
                return column;
            }
            return -1;
        }

        private void restartProjection() {
            builder = null;
            source = null;
            snapshot = null;
            seedRevision = 0L;
            nextFullColumn = 0;
            patchCursor = 0;
            processedColumns = 0;
            activeColumn = -1;
            columnCursor = null;
            Arrays.fill(completedColumns, 0L);
        }

    }
}
