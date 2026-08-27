package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapWorkScheduler;

import java.util.EnumMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded continuation adapter over the global map CPU control plane.
 *
 * <p>CompletableFuture callbacks cannot simply be dropped when the global map
 * scheduler is saturated, but recursively scheduling one retry timer per callback
 * creates a denial/retry storm. PASS139 keeps one retained FIFO per adapter and a
 * single delayed drain. Heavy decoded-source fanout is also globally permit-bound
 * so multiple cave readers cannot occupy every map CPU worker at once.</p>
 */
final class PriorityDecodeExecutor {
    static final int FOREGROUND = 0;
    static final int PREFETCH = 1;

    private static final int MAX_DRAIN_ADMISSIONS = 8;
    private static final int MIN_RETRY_DELAY_MS = 4;
    private static final int MAX_RETRY_DELAY_MS = 32;
    private static final EnumMap<MapWorkScheduler.WorkType, Semaphore>
            TYPE_PERMITS = createTypePermits();
    /**
     * Decode and fanout share the same map CPU pool. Separate per-type caps could
     * still occupy seven of eight workers at once, leaving exact/region work one
     * thread. Keep at most four source-pipeline CPU tasks active in aggregate.
     */
    private static final Semaphore SOURCE_TOTAL_PERMITS =
            new Semaphore(4);

    private final MapWorkScheduler.WorkType workType;
    private final int cost;
    /*
     * PASS141: callback retention is priority-aware before work reaches the global
     * scheduler. The old single FIFO let BACKGROUND/native-archive continuations sit
     * in front of newly visible MINIMAP/FULLSCREEN page work; lane priority only
     * mattered after dequeue, which is too late for a source dependency queue.
     */
    private final ConcurrentLinkedQueue<PendingTask> minimapPending =
            new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PendingTask> fullscreenPending =
            new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PendingTask> backgroundPending =
            new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PendingTask> prefetchPending =
            new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drainOwned = new AtomicBoolean();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final AtomicInteger retryDelayMs =
            new AtomicInteger(MIN_RETRY_DELAY_MS);

    PriorityDecodeExecutor(int ignoredThreads) {
        this(MapWorkScheduler.WorkType.SOURCE_DECODE, 12);
    }

    PriorityDecodeExecutor(MapWorkScheduler.WorkType workType, int cost) {
        this.workType = workType == null
                ? MapWorkScheduler.WorkType.SOURCE_DECODE : workType;
        this.cost = Math.max(1, cost);
    }

    Executor dynamic(PrioritySupplier supplier) {
        PrioritySupplier effectiveSupplier = supplier == null
                ? () -> PREFETCH : supplier;
        return command -> {
            if (command == null) return;
            int queuePriority = Math.max(0, Math.min(3,
                    effectiveSupplier.priority()));
            PendingTask task = new PendingTask(command, effectiveSupplier,
                    queuePriority);
            queueForPriority(queuePriority).offer(task);
            pendingCount.incrementAndGet();
            requestDrain(0L);
        };
    }

    int queuedTasks() {
        /*
         * PASS140: source admission must observe this adapter's retained decode/
         * fanout backlog, not every unrelated exact/branch task in the global CPU
         * scheduler. The global scheduler still enforces total CPU capacity when
         * drain() calls tryCpu(); folding its queue into this number created a
         * dependency inversion where missing Cave source stopped being read because
         * presentation refinement was already queued.
         */
        return pendingCount.get();
    }

    private void requestDrain(long delayMs) {
        if (!drainOwned.compareAndSet(false, true)) return;
        MapWorkScheduler.scheduleControl(delayMs, this::drain);
    }

    private void drain() {
        int admitted = 0;
        while (admitted < MAX_DRAIN_ADMISSIONS) {
            PendingTask next = peekNext();
            if (next == null) {
                releaseDrainOwnership();
                return;
            }

            Semaphore permits = TYPE_PERMITS.get(workType);
            if (permits != null && !permits.tryAcquire()) {
                scheduleRetry();
                return;
            }
            boolean sourceWork = workType == MapWorkScheduler.WorkType.SOURCE_DECODE
                    || workType == MapWorkScheduler.WorkType.SOURCE_PROJECTION;
            boolean sourcePermit = !sourceWork || SOURCE_TOTAL_PERMITS.tryAcquire();
            if (!sourcePermit) {
                if (permits != null) permits.release();
                scheduleRetry();
                return;
            }

            int executorPriority = Math.max(FOREGROUND,
                    next.supplier().priority());
            MapRequestLane lane = MapWorkScheduler.laneForExecutorPriority(
                    executorPriority);
            Runnable wrapped = () -> {
                try {
                    next.command().run();
                } finally {
                    if (sourceWork) SOURCE_TOTAL_PERMITS.release();
                    if (permits != null) permits.release();
                    requestDrain(0L);
                }
            };

            boolean accepted = MapWorkScheduler.tryCpu(lane, workType,
                    lane.priorityBase(), cost, () -> true, wrapped);
            if (!accepted) {
                if (sourceWork) SOURCE_TOTAL_PERMITS.release();
                if (permits != null) permits.release();
                scheduleRetry();
                return;
            }

            PendingTask removed = queueForPriority(
                    next.queuePriority()).poll();
            if (removed != null) pendingCount.decrementAndGet();
            retryDelayMs.set(MIN_RETRY_DELAY_MS);
            admitted++;
        }

        MapWorkScheduler.scheduleControl(0L, this::drain);
    }


    private void scheduleRetry() {
        int delay = retryDelayMs.getAndUpdate(current ->
                Math.min(MAX_RETRY_DELAY_MS,
                        Math.max(MIN_RETRY_DELAY_MS, current << 1)));
        MapWorkScheduler.scheduleControl(delay, this::drain);
    }

    private void releaseDrainOwnership() {
        drainOwned.set(false);
        if (pendingCount.get() > 0) requestDrain(0L);
    }

    private ConcurrentLinkedQueue<PendingTask> queueForPriority(int priority) {
        return switch (Math.max(0, Math.min(3, priority))) {
            case 0 -> minimapPending;
            case 1 -> fullscreenPending;
            case 2 -> backgroundPending;
            default -> prefetchPending;
        };
    }

    private PendingTask peekNext() {
        PendingTask task = minimapPending.peek();
        if (task != null) return task;
        task = fullscreenPending.peek();
        if (task != null) return task;
        task = backgroundPending.peek();
        return task != null ? task : prefetchPending.peek();
    }

    private static EnumMap<MapWorkScheduler.WorkType, Semaphore>
            createTypePermits() {
        EnumMap<MapWorkScheduler.WorkType, Semaphore> result =
                new EnumMap<>(MapWorkScheduler.WorkType.class);
        /*
         * Vertical archive fanout is the heaviest source CPU stage. The current
         * log contains a 1.37 s SOURCE_PROJECTION outlier; allowing every global
         * map worker to perform this stage at once creates whole-system stalls.
         * Xaero deliberately advances a small writer frontier instead.
         */
        result.put(MapWorkScheduler.WorkType.SOURCE_PROJECTION,
                new Semaphore(3));
        result.put(MapWorkScheduler.WorkType.SOURCE_DECODE,
                new Semaphore(4));
        return result;
    }

    private record PendingTask(Runnable command, PrioritySupplier supplier,
            int queuePriority) {
    }

    @FunctionalInterface
    interface PrioritySupplier {
        int priority();
    }
}
