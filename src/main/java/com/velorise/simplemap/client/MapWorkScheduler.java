package com.velorise.simplemap.client;

import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Global control plane for expensive map work.
 *
 * <p>Older revisions let every subsystem own a private executor. That allowed
 * decode, projection, exact styling, overview generation and cache maintenance to
 * oversubscribe the CPU independently. V16.2 keeps one priority CPU domain, one
 * priority IO domain and one tiny delay timer. Tasks still retain subsystem-local
 * state, but admission and execution order are decided globally.</p>
 */
public final class MapWorkScheduler {
    private static final int PROCESSORS = Math.max(2,
            Runtime.getRuntime().availableProcessors());
    // Scale beyond the old four-worker ceiling, but never consume the whole CPU.
    // Minecraft render, integrated-server and chunk-meshing threads keep at least
    // two logical processors and map CPU concurrency is capped at eight.
    private static final int CPU_THREADS = Math.max(2,
            Math.min(8, Math.max(2, PROCESSORS - 2)));
    private static final int IO_THREADS = Math.max(2,
            Math.min(4, Math.max(2, PROCESSORS / 4)));
    private static final MapRequestLane[] REQUEST_LANES = MapRequestLane.values();

    /** Cost units are deliberately coarse; they represent retained snapshots and
     * expected CPU/IO occupancy, not milliseconds. */
    private static final long CPU_SOFT_COST = 1_280L;
    private static final long CPU_HARD_COST = 1_920L;
    private static final long IO_SOFT_COST = 640L;
    private static final long IO_HARD_COST = 1_024L;

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicLong CPU_QUEUED_COST = new AtomicLong();
    private static final AtomicLong IO_QUEUED_COST = new AtomicLong();
    /** Running work remains part of pressure/admission accounting until completion. */
    private static final AtomicLong CPU_ACTIVE_COST = new AtomicLong();
    private static final AtomicLong IO_ACTIVE_COST = new AtomicLong();
    private static final EnumMap<WorkType, AtomicLong> RUNTIME_EWMA_NANOS =
            new EnumMap<>(WorkType.class);
    /** At least one CPU worker remains available for minimap/fullscreen work. */
    private static final Semaphore WEAK_CPU_PERMITS =
            new Semaphore(Math.max(1, CPU_THREADS - 1));
    /** Keep one IO worker available for visible reads. Background cave saves,
     * compaction and cache maintenance share the remaining capacity. */
    private static final Semaphore WEAK_IO_PERMITS =
            new Semaphore(Math.max(1, IO_THREADS - 1));
    private static final EnumMap<MapRequestLane, AtomicLong> VIEWPORT_EPOCHS =
            new EnumMap<>(MapRequestLane.class);
    private static final EnumMap<MapRequestLane, AtomicLong> COMPLETED_BY_LANE =
            new EnumMap<>(MapRequestLane.class);
    private static final EnumMap<MapRequestLane, AtomicLong> DENIED_BY_LANE =
            new EnumMap<>(MapRequestLane.class);

    private static final ThreadPoolExecutor CPU = createPool(
            CPU_THREADS, "SimpleMap-CPU");
    private static final ThreadPoolExecutor IO = createPool(
            IO_THREADS, "SimpleMap-IO");
    private static final ScheduledThreadPoolExecutor DELAYER =
            new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "SimpleMap-Delay");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY,
                        Thread.NORM_PRIORITY - 2));
                return thread;
            });

    static {
        for (MapRequestLane lane : REQUEST_LANES) {
            VIEWPORT_EPOCHS.put(lane, new AtomicLong(1L));
            COMPLETED_BY_LANE.put(lane, new AtomicLong());
            DENIED_BY_LANE.put(lane, new AtomicLong());
        }
        for (WorkType type : WorkType.values()) {
            RUNTIME_EWMA_NANOS.put(type, new AtomicLong(500_000L));
        }
        CPU.allowCoreThreadTimeOut(false);
        IO.allowCoreThreadTimeOut(false);
        DELAYER.setRemoveOnCancelPolicy(true);
        DELAYER.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    private MapWorkScheduler() {
    }

    private static ThreadPoolExecutor createPool(int threads, String name) {
        return new ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS,
                new FairTaskQueue(), runnable -> {
                    Thread thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    thread.setPriority(Math.max(Thread.MIN_PRIORITY,
                            Thread.NORM_PRIORITY - 1));
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Work type controls global ordering after the viewport lane. */
    public enum WorkType {
        MINIMAP_EXACT(90, true),
        REGION_PROJECTION(88, true),
        /*
         * Source decode/fanout is an upstream dependency for exact/branch work.
         * PASS138 could keep all eight CPU workers busy refining already-ready
         * branches while visible Anvil source waited behind them. The current log
         * shows SOURCE_QUEUE max >1 s and source fanout bursts coincident with
         * stalls. Promote bounded source work above refinement; PASS139 separately
         * caps source concurrency so this cannot monopolize the worker pool.
         */
        SOURCE_DECODE(87, false),
        SOURCE_PROJECTION(86, false),
        BRANCH_DERIVE(84, true),
        EXACT_BUILD(80, true),
        DISK_READ(40, false),
        LEGACY_BUILD(25, true),
        DISK_WRITE(15, false),
        CACHE_MAINTENANCE(5, false);

        private final int rank;
        private final boolean viewportScoped;

        WorkType(int rank, boolean viewportScoped) {
            this.rank = rank;
            this.viewportScoped = viewportScoped;
        }

        int rank() {
            return rank;
        }

        boolean viewportScoped() {
            return viewportScoped;
        }
    }

    public static long bumpViewport(MapRequestLane lane) {
        MapRequestLane effective = lane == null ? MapRequestLane.FULLSCREEN : lane;
        long epoch = VIEWPORT_EPOCHS.get(effective).incrementAndGet();
        /*
         * A priority queue does not remove invalid tasks by itself. Repeated pan,
         * zoom and layer changes could therefore retain stale pixel snapshots until
         * workers eventually reached them. That is bounded by cost, so it is not a
         * permanent leak, but it behaves like one and can delay centre-page work.
         * Purge stale viewport tasks immediately when the epoch changes.
         */
        purgeStaleViewportTasks(CPU, effective, epoch);
        purgeStaleViewportTasks(IO, effective, epoch);
        return epoch;
    }

    private static void purgeStaleViewportTasks(ThreadPoolExecutor pool,
            MapRequestLane lane, long currentEpoch) {
        // PriorityBlockingQueue's weakly-consistent iterator is sufficient here:
        // tasks added after the epoch bump already capture the new epoch, while any
        // stale task missed by a concurrent iterator still self-cancels before run.
        // Avoid materialising an Object[] on every page-boundary/layer handoff.
        var iterator = pool.getQueue().iterator();
        while (iterator.hasNext()) {
            Runnable queued = iterator.next();
            if (!(queued instanceof PrioritizedTask task)
                    || !task.isStaleViewportTask(lane, currentEpoch)) continue;
            iterator.remove();
            task.cancelStaleBeforeRun();
        }
    }

    public static long viewportEpoch(MapRequestLane lane) {
        MapRequestLane effective = lane == null ? MapRequestLane.FULLSCREEN : lane;
        return VIEWPORT_EPOCHS.get(effective).get();
    }

    public static boolean isViewportCurrent(MapRequestLane lane, long epoch) {
        return viewportEpoch(lane) == epoch;
    }

    public static MapRequestLane laneForExecutorPriority(int priority) {
        for (MapRequestLane lane : REQUEST_LANES) {
            if (lane.executorPriority() == priority) return lane;
        }
        if (priority <= MapRequestLane.MINIMAP.executorPriority()) {
            return MapRequestLane.MINIMAP;
        }
        if (priority >= MapRequestLane.PREFETCH.executorPriority()) {
            return MapRequestLane.PREFETCH;
        }
        return MapRequestLane.FULLSCREEN;
    }

    public static boolean tryCpu(MapRequestLane lane, WorkType type,
            int priority, int cost, BooleanSupplier valid, Runnable runnable) {
        return submit(CPU, CPU_QUEUED_COST, CPU_ACTIVE_COST,
                CPU_SOFT_COST, CPU_HARD_COST,
                lane, type, priority, cost, valid, runnable, false, true);
    }

    public static boolean tryIo(MapRequestLane lane, WorkType type,
            int priority, int cost, BooleanSupplier valid, Runnable runnable) {
        return submit(IO, IO_QUEUED_COST, IO_ACTIVE_COST,
                IO_SOFT_COST, IO_HARD_COST,
                lane, type, priority, cost, valid, runnable, false, false);
    }


    /**
     * Submit a CompletableFuture-style IO operation without exposing executor
     * rejection to the caller. A null result means the bounded IO domain is
     * currently saturated; the subsystem must retain its dirty/requested state
     * and retry later. Never run the supplier inline on the render thread.
     */
    public static <T> CompletableFuture<T> tryIoFuture(MapRequestLane lane,
            WorkType type, int priority, int cost, BooleanSupplier valid,
            Supplier<T> supplier) {
        if (supplier == null) return null;
        CompletableFuture<T> future = new CompletableFuture<>();
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        long submittedViewportEpoch = type != null && type.viewportScoped()
                ? viewportEpoch(effectiveLane) : Long.MIN_VALUE;
        BooleanSupplier suppliedValid = valid == null ? () -> true : valid;
        BooleanSupplier effectiveValid = () -> suppliedValid.getAsBoolean()
                && (submittedViewportEpoch == Long.MIN_VALUE
                || isViewportCurrent(effectiveLane, submittedViewportEpoch));
        boolean accepted = submit(IO, IO_QUEUED_COST, IO_ACTIVE_COST,
                IO_SOFT_COST, IO_HARD_COST,
                lane, type, priority, cost, () -> true, () -> {
                    try {
                        if (!effectiveValid.getAsBoolean()) {
                            future.cancel(false);
                            return;
                        }
                        future.complete(supplier.get());
                    } catch (Throwable failure) {
                        future.completeExceptionally(failure);
                    }
                }, true, false);
        return accepted ? future : null;
    }

    /** Same non-throwing admission contract for CPU work. */
    public static <T> CompletableFuture<T> tryCpuFuture(MapRequestLane lane,
            WorkType type, int priority, int cost, BooleanSupplier valid,
            Supplier<T> supplier) {
        if (supplier == null) return null;
        CompletableFuture<T> future = new CompletableFuture<>();
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        long submittedViewportEpoch = type != null && type.viewportScoped()
                ? viewportEpoch(effectiveLane) : Long.MIN_VALUE;
        BooleanSupplier suppliedValid = valid == null ? () -> true : valid;
        BooleanSupplier effectiveValid = () -> suppliedValid.getAsBoolean()
                && (submittedViewportEpoch == Long.MIN_VALUE
                || isViewportCurrent(effectiveLane, submittedViewportEpoch));
        boolean accepted = submit(CPU, CPU_QUEUED_COST, CPU_ACTIVE_COST,
                CPU_SOFT_COST, CPU_HARD_COST,
                lane, type, priority, cost, () -> true, () -> {
                    try {
                        if (!effectiveValid.getAsBoolean()) {
                            future.cancel(false);
                            return;
                        }
                        future.complete(supplier.get());
                    } catch (Throwable failure) {
                        future.completeExceptionally(failure);
                    }
                }, true, true);
        return accepted ? future : null;
    }

    /** Compatibility adapter for CompletableFuture APIs. Prefer tryCpuFuture. */
    @Deprecated
    public static Executor cpuExecutor(MapRequestLane lane, WorkType type,
            int priority, int cost, BooleanSupplier valid) {
        return command -> {
            BooleanSupplier effectiveValid = valid == null ? () -> true : valid;
            if (tryCpu(lane, type, priority, cost, effectiveValid, command)) return;
            scheduleCpuAttempt(10L, lane, type, priority, cost,
                    effectiveValid, command, 0);
        };
    }

    /**
     * Compatibility adapter for APIs that accept only Executor. IO saturation is
     * never propagated to Minecraft's render/client tick. The command is moved to
     * the bounded retry path instead. New code should prefer tryIoFuture/tryIo so
     * it can explicitly retain dirty state when admission is denied.
     */
    @Deprecated
    public static Executor ioExecutor(MapRequestLane lane, WorkType type,
            int priority, int cost, BooleanSupplier valid) {
        return command -> {
            BooleanSupplier effectiveValid = valid == null ? () -> true : valid;
            if (tryIo(lane, type, priority, cost, effectiveValid, command)) return;
            scheduleIoAttempt(10L, TimeUnit.MILLISECONDS, lane, type,
                    priority, cost, effectiveValid, command, 0);
        };
    }

    private static void scheduleCpuAttempt(long delayMs,
            MapRequestLane lane, WorkType type, int priority, int cost,
            BooleanSupplier valid, Runnable runnable, int attempt) {
        DELAYER.schedule(() -> {
            BooleanSupplier effectiveValid = valid == null ? () -> true : valid;
            if (!effectiveValid.getAsBoolean()) {
                // Executor compatibility tasks normally use an always-valid token.
                // Do not execute stale projection work merely to satisfy a future.
                return;
            }
            if (tryCpu(lane, type, priority, cost, effectiveValid, runnable)) return;
            if (attempt < 64) {
                long retryDelayMs = Math.min(250L,
                        5L << Math.min(6, attempt));
                scheduleCpuAttempt(retryDelayMs, lane, type, priority, cost,
                        effectiveValid, runnable, attempt + 1);
            }
        }, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
    }

    /**
     * Schedules one tiny control-plane continuation without creating a private
     * subsystem timer. This is intentionally not work admission: the continuation
     * must still call tryCpu/tryIo and retain its own pending state when saturated.
     */
    public static void scheduleControl(long delayMs, Runnable runnable) {
        if (runnable == null) return;
        DELAYER.schedule(runnable, Math.max(0L, delayMs),
                TimeUnit.MILLISECONDS);
    }

    public static void scheduleIo(long delay, TimeUnit unit,
            MapRequestLane lane, WorkType type, int priority, int cost,
            BooleanSupplier valid, Runnable runnable) {
        scheduleIoAttempt(Math.max(0L, delay),
                unit == null ? TimeUnit.MILLISECONDS : unit,
                lane, type, priority, cost, valid, runnable, 0);
    }

    private static void scheduleIoAttempt(long delay, TimeUnit unit,
            MapRequestLane lane, WorkType type, int priority, int cost,
            BooleanSupplier valid, Runnable runnable, int attempt) {
        DELAYER.schedule(() -> {
            BooleanSupplier effectiveValid = valid == null ? () -> true : valid;
            if (!effectiveValid.getAsBoolean()) return;
            if (tryIo(lane, type, priority, cost, effectiveValid, runnable)) return;

            // Debounced saves and compaction jobs often mark themselves as
            // scheduled before reaching this control plane. Silently dropping a
            // rejected delayed submission can therefore leave that subsystem
            // permanently stuck in the scheduled state. Retry with bounded
            // exponential backoff instead; foreground IO will naturally outrank
            // these weak maintenance retries in the global priority queue.
            long retryDelayMs = Math.min(500L, 10L << Math.min(6, attempt));
            // Pull-driven IO requests represent retained dirty/source state. Do
            // not silently abandon them after an arbitrary retry count; validity
            // or successful admission is the terminal condition.
            scheduleIoAttempt(retryDelayMs, TimeUnit.MILLISECONDS,
                    lane, type, priority, cost, effectiveValid, runnable,
                    Math.min(64, attempt + 1));
        }, delay, unit);
    }

    /** Allocation-free pressure probes for hot admission/governor paths. */
    public static long cpuTotalCost() {
        return CPU_QUEUED_COST.get() + CPU_ACTIVE_COST.get();
    }

    public static long ioTotalCost() {
        return IO_QUEUED_COST.get() + IO_ACTIVE_COST.get();
    }

    public static int cpuActiveCount() {
        return CPU.getActiveCount();
    }

    public static int cpuQueuedCount() {
        return CPU.getQueue().size();
    }

    public static int ioQueuedCount() {
        return IO.getQueue().size();
    }

    public static long ioQueuedCost() {
        return IO_QUEUED_COST.get();
    }

    public static Snapshot snapshot() {
        int laneCount = REQUEST_LANES.length;
        int[] cpuQueuedByLane = new int[laneCount];
        int[] ioQueuedByLane = new int[laneCount];
        long[] completedByLane = new long[laneCount];
        long[] deniedByLane = new long[laneCount];
        FairTaskQueue cpuQueue = (FairTaskQueue) CPU.getQueue();
        FairTaskQueue ioQueue = (FairTaskQueue) IO.getQueue();
        for (MapRequestLane lane : REQUEST_LANES) {
            int index = lane.ordinal();
            cpuQueuedByLane[index] = cpuQueue.queued(lane);
            ioQueuedByLane[index] = ioQueue.queued(lane);
            completedByLane[index] = COMPLETED_BY_LANE.get(lane).get();
            deniedByLane[index] = DENIED_BY_LANE.get(lane).get();
        }
        return new Snapshot(CPU.getActiveCount(), CPU.getQueue().size(),
                CPU_QUEUED_COST.get(), CPU_ACTIVE_COST.get(),
                IO.getActiveCount(), IO.getQueue().size(),
                IO_QUEUED_COST.get(), IO_ACTIVE_COST.get(),
                cpuQueuedByLane, ioQueuedByLane, completedByLane, deniedByLane);
    }

    /** Cheap preflight used before allocating a large immutable source snapshot. */
    public static boolean canAdmitCpu(MapRequestLane lane, int requestedCost) {
        MapRequestLane effective = lane == null ? MapRequestLane.FULLSCREEN : lane;
        long total = CPU_QUEUED_COST.get() + CPU_ACTIVE_COST.get()
                + Math.max(1, requestedCost);
        boolean weak = effective == MapRequestLane.BACKGROUND
                || effective == MapRequestLane.PREFETCH;
        return total <= CPU_HARD_COST && (!weak || total <= CPU_SOFT_COST);
    }

    public static boolean canAdmitIo(MapRequestLane lane, int requestedCost) {
        MapRequestLane effective = lane == null ? MapRequestLane.FULLSCREEN : lane;
        long total = IO_QUEUED_COST.get() + IO_ACTIVE_COST.get()
                + Math.max(1, requestedCost);
        boolean weak = effective == MapRequestLane.BACKGROUND
                || effective == MapRequestLane.PREFETCH;
        return total <= IO_HARD_COST && (!weak || total <= IO_SOFT_COST);
    }

    public static long predictedRuntimeNanos(WorkType type) {
        WorkType effective = type == null ? WorkType.CACHE_MAINTENANCE : type;
        return RUNTIME_EWMA_NANOS.get(effective).get();
    }

    private static boolean submit(ThreadPoolExecutor pool, AtomicLong queuedCost,
            AtomicLong activeCost, long softCost, long hardCost,
            MapRequestLane lane, WorkType type,
            int priority, int requestedCost, BooleanSupplier valid, Runnable runnable,
            boolean mustRun, boolean cpuDomain) {
        if (runnable == null) return false;
        MapRequestLane effectiveLane = lane == null ? MapRequestLane.FULLSCREEN : lane;
        WorkType effectiveType = type == null ? WorkType.CACHE_MAINTENANCE : type;
        BooleanSupplier effectiveValid = valid == null ? () -> true : valid;
        if (!effectiveValid.getAsBoolean()) return false;

        int cost = Math.max(1, requestedCost);
        MapMemoryLeaseManager.Category memoryCategory = memoryCategory(effectiveType);
        long estimatedBytes = estimateRetainedBytes(effectiveType, cost);
        MapMemoryLeaseManager.Lease memoryLease = MapMemoryLeaseManager.tryAcquire(
                memoryCategory, estimatedBytes, effectiveLane);
        if (memoryLease == null) {
            DENIED_BY_LANE.get(effectiveLane).incrementAndGet();
            return false;
        }

        long after = queuedCost.addAndGet(cost) + activeCost.get();
        boolean weakLane = effectiveLane == MapRequestLane.BACKGROUND
                || effectiveLane == MapRequestLane.PREFETCH;
        if (after > hardCost || (weakLane && after > softCost)) {
            queuedCost.addAndGet(-cost);
            memoryLease.close();
            DENIED_BY_LANE.get(effectiveLane).incrementAndGet();
            return false;
        }

        long viewportEpoch = effectiveType.viewportScoped()
                ? viewportEpoch(effectiveLane) : Long.MIN_VALUE;
        PrioritizedTask task = new PrioritizedTask(runnable, effectiveValid,
                effectiveLane, effectiveType, priority,
                SEQUENCE.getAndIncrement(), cost, queuedCost, activeCost,
                viewportEpoch, mustRun, cpuDomain, pool, memoryLease);
        try {
            pool.execute(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            task.cancelBeforeRun();
            DENIED_BY_LANE.get(effectiveLane).incrementAndGet();
            return false;
        }
    }

    private static MapMemoryLeaseManager.Category memoryCategory(WorkType type) {
        return switch (type) {
            case SOURCE_DECODE, DISK_READ ->
                    MapMemoryLeaseManager.Category.PENDING_SOURCE;
            case MINIMAP_EXACT, REGION_PROJECTION, EXACT_BUILD, SOURCE_PROJECTION, LEGACY_BUILD ->
                    MapMemoryLeaseManager.Category.PENDING_PROJECTION;
            case BRANCH_DERIVE -> MapMemoryLeaseManager.Category.PENDING_LOD;
            case DISK_WRITE, CACHE_MAINTENANCE ->
                    MapMemoryLeaseManager.Category.IO_BUFFER;
        };
    }

    private static long estimateRetainedBytes(WorkType type, int cost) {
        long unit = switch (type) {
            case MINIMAP_EXACT, REGION_PROJECTION, EXACT_BUILD, SOURCE_PROJECTION -> 96L << 10;
            case SOURCE_DECODE, DISK_READ -> 80L << 10;
            case BRANCH_DERIVE -> 64L << 10;
            case LEGACY_BUILD -> 48L << 10;
            case DISK_WRITE, CACHE_MAINTENANCE -> 64L << 10;
        };
        return Math.max(64L << 10, Math.multiplyExact((long) cost, unit));
    }

    public record Snapshot(int cpuActive, int cpuQueued, long cpuQueuedCost,
            long cpuActiveCost, int ioActive, int ioQueued, long ioQueuedCost,
            long ioActiveCost, int[] cpuQueuedByLane, int[] ioQueuedByLane,
            long[] completedByLane, long[] deniedByLane) {
        public Snapshot {
            cpuQueuedByLane = cpuQueuedByLane.clone();
            ioQueuedByLane = ioQueuedByLane.clone();
            completedByLane = completedByLane.clone();
            deniedByLane = deniedByLane.clone();
        }

        @Override public int[] cpuQueuedByLane() { return cpuQueuedByLane.clone(); }
        @Override public int[] ioQueuedByLane() { return ioQueuedByLane.clone(); }
        @Override public long[] completedByLane() { return completedByLane.clone(); }
        @Override public long[] deniedByLane() { return deniedByLane.clone(); }

        public int cpuQueued(MapRequestLane lane) {
            return cpuQueuedByLane[(lane == null
                    ? MapRequestLane.FULLSCREEN : lane).ordinal()];
        }

        public int ioQueued(MapRequestLane lane) {
            return ioQueuedByLane[(lane == null
                    ? MapRequestLane.FULLSCREEN : lane).ordinal()];
        }

        public long completed(MapRequestLane lane) {
            return completedByLane[(lane == null
                    ? MapRequestLane.FULLSCREEN : lane).ordinal()];
        }

        public long denied(MapRequestLane lane) {
            return deniedByLane[(lane == null
                    ? MapRequestLane.FULLSCREEN : lane).ordinal()];
        }

        public long cpuTotalCost() {
            return cpuQueuedCost + cpuActiveCost;
        }

        public long ioTotalCost() {
            return ioQueuedCost + ioActiveCost;
        }
    }

    private static void recordRuntime(WorkType type, long nanos) {
        if (type == null || nanos <= 0L) return;
        long sample = Math.max(20_000L, Math.min(250_000_000L, nanos));
        AtomicLong ewma = RUNTIME_EWMA_NANOS.get(type);
        long previous;
        long next;
        do {
            previous = ewma.get();
            next = previous + ((sample - previous) >> 3);
        } while (!ewma.compareAndSet(previous, Math.max(20_000L, next)));
    }

    /**
     * Weighted deficit-round-robin queue. Strict priority is preserved inside a
     * lane, while continuously arriving minimap work can no longer prevent
     * fullscreen/background/prefetch work from ever reaching a worker.
     */
    private static final class FairTaskQueue extends AbstractQueue<Runnable>
            implements BlockingQueue<Runnable> {
        private static final MapRequestLane[] LANES = MapRequestLane.values();
        private static final long MAX_DEFICIT = 4_096L;

        private final EnumMap<MapRequestLane, PriorityQueue<PrioritizedTask>> queues =
                new EnumMap<>(MapRequestLane.class);
        private final long[] deficits = new long[LANES.length];
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notEmpty = lock.newCondition();
        private int cursor;
        private int size;

        private FairTaskQueue() {
            for (MapRequestLane lane : LANES) {
                queues.put(lane, new PriorityQueue<>());
            }
        }

        @Override
        public boolean offer(Runnable runnable) {
            if (!(runnable instanceof PrioritizedTask task)) {
                throw new IllegalArgumentException(
                        "Map scheduler queue accepts PrioritizedTask only");
            }
            lock.lock();
            try {
                queues.get(task.lane).offer(task);
                size++;
                notEmpty.signal();
                return true;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void put(Runnable runnable) {
            offer(runnable);
        }

        @Override
        public boolean offer(Runnable runnable, long timeout, TimeUnit unit) {
            return offer(runnable);
        }

        @Override
        public Runnable poll() {
            lock.lock();
            try {
                return pollFairLocked();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Runnable take() throws InterruptedException {
            lock.lockInterruptibly();
            try {
                while (size == 0) notEmpty.await();
                return pollFairLocked();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Runnable poll(long timeout, TimeUnit unit)
                throws InterruptedException {
            long nanos = unit.toNanos(timeout);
            lock.lockInterruptibly();
            try {
                while (size == 0) {
                    if (nanos <= 0L) return null;
                    nanos = notEmpty.awaitNanos(nanos);
                }
                return pollFairLocked();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Runnable peek() {
            lock.lock();
            try {
                if (size == 0) return null;
                PrioritizedTask best = null;
                for (MapRequestLane lane : LANES) {
                    PrioritizedTask candidate = queues.get(lane).peek();
                    if (candidate != null
                            && (best == null || candidate.compareTo(best) < 0)) {
                        best = candidate;
                    }
                }
                return best;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public int size() {
            lock.lock();
            try {
                return size;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public int remainingCapacity() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean remove(Object value) {
            lock.lock();
            try {
                if (!(value instanceof PrioritizedTask task)) return false;
                boolean removed = queues.get(task.lane).remove(task);
                if (removed) size--;
                return removed;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean contains(Object value) {
            lock.lock();
            try {
                if (!(value instanceof PrioritizedTask task)) return false;
                return queues.get(task.lane).contains(task);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void clear() {
            List<PrioritizedTask> cancelled = new ArrayList<>();
            lock.lock();
            try {
                for (PriorityQueue<PrioritizedTask> queue : queues.values()) {
                    cancelled.addAll(queue);
                    queue.clear();
                }
                java.util.Arrays.fill(deficits, 0L);
                size = 0;
            } finally {
                lock.unlock();
            }
            // ThreadPoolExecutor treats queue.clear() as removal, not task
            // cancellation. Release durable scheduler and memory accounting here.
            for (PrioritizedTask task : cancelled) task.cancelBeforeRun();
        }

        @Override
        public Iterator<Runnable> iterator() {
            return snapshotLocked().iterator();
        }

        @Override
        public Object[] toArray() {
            return snapshotLocked().toArray();
        }

        @Override
        public <T> T[] toArray(T[] target) {
            return snapshotLocked().toArray(target);
        }

        @Override
        public int drainTo(Collection<? super Runnable> destination) {
            return drainTo(destination, Integer.MAX_VALUE);
        }

        @Override
        public int drainTo(Collection<? super Runnable> destination, int maxElements) {
            if (destination == null || destination == this) {
                throw new IllegalArgumentException("Invalid drain destination");
            }
            lock.lock();
            try {
                int drained = 0;
                while (drained < maxElements && size > 0) {
                    Runnable task = pollFairLocked();
                    if (task == null) break;
                    destination.add(task);
                    drained++;
                }
                return drained;
            } finally {
                lock.unlock();
            }
        }

        int queued(MapRequestLane lane) {
            lock.lock();
            try {
                PriorityQueue<PrioritizedTask> queue = queues.get(lane);
                return queue == null ? 0 : queue.size();
            } finally {
                lock.unlock();
            }
        }

        private PrioritizedTask pollFairLocked() {
            if (size == 0) return null;
            int attempts = 0;
            int maximumAttempts = LANES.length * 256;
            while (attempts++ < maximumAttempts) {
                MapRequestLane lane = LANES[cursor];
                int laneIndex = cursor;
                PriorityQueue<PrioritizedTask> queue = queues.get(lane);
                PrioritizedTask task = queue.peek();
                if (task == null) {
                    deficits[laneIndex] = 0L;
                    cursor = (cursor + 1) % LANES.length;
                    continue;
                }

                long charge = Math.max(1L, task.cost);
                if (deficits[laneIndex] < charge) {
                    deficits[laneIndex] = Math.min(MAX_DEFICIT,
                            deficits[laneIndex] + quantum(lane));
                }
                if (charge > deficits[laneIndex]) {
                    cursor = (cursor + 1) % LANES.length;
                    continue;
                }

                queue.poll();
                size--;
                deficits[laneIndex] -= charge;
                PrioritizedTask next = queue.peek();
                if (next == null
                        || Math.max(1L, next.cost) > deficits[laneIndex]) {
                    cursor = (cursor + 1) % LANES.length;
                }
                return task;
            }

            // A task with an unexpectedly high declared cost must still make
            // progress. Choose the oldest head and reset only its lane deficit.
            PrioritizedTask oldest = null;
            MapRequestLane oldestLane = null;
            for (MapRequestLane lane : LANES) {
                PrioritizedTask candidate = queues.get(lane).peek();
                if (candidate != null
                        && (oldest == null || candidate.sequence < oldest.sequence)) {
                    oldest = candidate;
                    oldestLane = lane;
                }
            }
            if (oldest == null) return null;
            queues.get(oldestLane).poll();
            size--;
            deficits[oldestLane.ordinal()] = 0L;
            return oldest;
        }

        private List<Runnable> snapshotLocked() {
            lock.lock();
            try {
                List<Runnable> snapshot = new ArrayList<>(size);
                for (MapRequestLane lane : LANES) {
                    snapshot.addAll(queues.get(lane));
                }
                return snapshot;
            } finally {
                lock.unlock();
            }
        }

        private static int quantum(MapRequestLane lane) {
            return switch (lane) {
                case MINIMAP -> 128;
                case FULLSCREEN -> 96;
                case BACKGROUND -> 32;
                case PREFETCH -> 16;
            };
        }
    }

    private static final class PrioritizedTask
            implements Runnable, Comparable<PrioritizedTask> {
        private final Runnable command;
        private final BooleanSupplier valid;
        private final MapRequestLane lane;
        private final WorkType type;
        private final int priority;
        private final long sequence;
        private final int cost;
        private final AtomicLong queuedCost;
        private final AtomicLong activeCost;
        private final AtomicBoolean queuedCostHeld = new AtomicBoolean(true);
        private final long viewportEpoch;
        private final boolean mustRun;
        private final boolean cpuDomain;
        private final ThreadPoolExecutor owner;
        private final MapMemoryLeaseManager.Lease memoryLease;
        private final AtomicBoolean memoryLeaseHeld = new AtomicBoolean(true);

        private PrioritizedTask(Runnable command, BooleanSupplier valid,
                MapRequestLane lane, WorkType type, int priority, long sequence,
                int cost, AtomicLong queuedCost, AtomicLong activeCost,
                long viewportEpoch, boolean mustRun, boolean cpuDomain,
                ThreadPoolExecutor owner, MapMemoryLeaseManager.Lease memoryLease) {
            this.command = command;
            this.valid = valid;
            this.lane = lane;
            this.type = type;
            this.priority = priority;
            this.sequence = sequence;
            this.cost = cost;
            this.queuedCost = queuedCost;
            this.activeCost = activeCost;
            this.viewportEpoch = viewportEpoch;
            this.mustRun = mustRun;
            this.cpuDomain = cpuDomain;
            this.owner = owner;
            this.memoryLease = memoryLease;
        }

        @Override
        public void run() {
            boolean weakPermit = false;
            boolean weakLane = lane == MapRequestLane.BACKGROUND
                    || lane == MapRequestLane.PREFETCH;
            Semaphore permitDomain = cpuDomain ? WEAK_CPU_PERMITS : WEAK_IO_PERMITS;
            if (weakLane) {
                weakPermit = permitDomain.tryAcquire();
                if (!weakPermit) {
                    DELAYER.schedule(() -> {
                        try {
                            owner.execute(this);
                        } catch (RejectedExecutionException rejected) {
                            cancelBeforeRun();
                        }
                    }, 2L, TimeUnit.MILLISECONDS);
                    return;
                }
            }
            releaseQueuedCost();
            activeCost.addAndGet(cost);
            long startedNanos = System.nanoTime();
            try {
                if (!mustRun) {
                    try {
                        if (!valid.getAsBoolean()) return;
                    } catch (Throwable invalid) {
                        return;
                    }
                    if (type.viewportScoped()
                            && !MapWorkScheduler.isViewportCurrent(lane, viewportEpoch)) return;
                }
                // CompletableFuture executors must invoke their command so the future
                // reaches a terminal state. Their subsystem token remains the final
                // cancellation authority after queue admission.
                command.run();
            } catch (Throwable ignoredFailure) {
                // Subsystems own logging/result propagation. The shared worker must
                // remain alive even if a maintenance task fails unexpectedly.
            } finally {
                activeCost.addAndGet(-cost);
                COMPLETED_BY_LANE.get(lane).incrementAndGet();
                recordRuntime(type, System.nanoTime() - startedNanos);
                releaseMemoryLease();
                if (weakPermit) permitDomain.release();
            }
        }

        private boolean isStaleViewportTask(MapRequestLane targetLane,
                long currentEpoch) {
            return type.viewportScoped() && lane == targetLane
                    && viewportEpoch != currentEpoch;
        }

        private void releaseQueuedCost() {
            if (queuedCostHeld.compareAndSet(true, false)) {
                queuedCost.addAndGet(-cost);
            }
        }

        private void releaseMemoryLease() {
            if (memoryLeaseHeld.compareAndSet(true, false) && memoryLease != null) {
                memoryLease.close();
            }
        }

        private void cancelBeforeRun() {
            releaseQueuedCost();
            releaseMemoryLease();
        }

        /**
         * A purged CompletableFuture task still needs a terminal signal so its
         * subsystem releases retained source views and pending-batch ownership.
         * The future wrapper includes the submitted viewport epoch in its validity
         * check, therefore invoking this tiny command cannot run the stale supplier.
         */
        private void cancelStaleBeforeRun() {
            releaseQueuedCost();
            releaseMemoryLease();
            if (!mustRun) return;
            try {
                command.run();
            } catch (Throwable ignoredFailure) {
                // Future wrappers own result propagation.
            }
        }

        @Override
        public int compareTo(PrioritizedTask other) {
            int byLane = Integer.compare(other.lane.rank(), lane.rank());
            if (byLane != 0) return byLane;
            int byType = Integer.compare(other.type.rank(), type.rank());
            if (byType != 0) return byType;
            int byPriority = Integer.compare(other.priority, priority);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }
}
