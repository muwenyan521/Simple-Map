package com.velorise.simplemap.client;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * Adaptive time-budget controller. Visible map work continues while dragging;
 * only measured frame pressure reduces its slice. Background work still yields
 * when Minecraft is busy streaming chunks.
 */
public final class MapPerformanceGovernor {
    private static final MapPerformanceGovernor INSTANCE = new MapPerformanceGovernor();
    private static final double DEFAULT_TARGET_FRAME_NANOS = 8_000_000.0; // 125 FPS
    private static final double MIN_TARGET_FRAME_NANOS = 4_166_667.0; // 240 FPS
    private static final double MAX_TARGET_FRAME_NANOS = 33_333_333.0;
    private static final long DUPLICATE_FRAME_EVENT_NANOS = 1_500_000L;
    private static final long MAX_MUTATION_CREDIT_NANOS = 550_000L;
    private static final long MAX_MUTATION_CREDIT_GAIN_PER_FRAME_NANOS = 80_000L;
    private static final long MAX_MUTATION_BURST_PER_TICK_NANOS = 250_000L;
    /** JVM heap pressure is part of map pressure. The latest Cave capture reached
     * 94% of a 4 GiB heap while frame-time-only pressure was still false, allowing
     * background producers to keep allocating until a GC pause. */
    private static final double HEAP_PRESSURE_ENTER = 0.82D;

    private volatile boolean fullscreenOpen;
    private volatile boolean interacting;
    private volatile long lastFrameNanos;
    private volatile long lastFrameEventNanos;
    private volatile double smoothedFrameNanos = DEFAULT_TARGET_FRAME_NANOS;
    private volatile double configuredTargetFrameNanos = DEFAULT_TARGET_FRAME_NANOS;
    private volatile long lastTargetRefreshNanos;
    private volatile int pressureFrames;
    private volatile double heapPressure;
    private volatile double focusWorldX;
    private volatile double focusWorldZ;
    /**
     * Short-lived client-thread credit earned only by genuinely cheap frames.
     * The mutation queue may spend a bounded amount of this credit on a later
     * client tick. Credit is deliberately capped and decays immediately under
     * pressure, so idle headroom becomes useful progress without creating one
     * multi-millisecond repair spike.
     */
    private final AtomicLong mutationCreditNanos = new AtomicLong();

    private MapPerformanceGovernor() {
    }

    public static MapPerformanceGovernor getInstance() {
        return INSTANCE;
    }

    public void onFrame() {
        long now = System.nanoTime();
        // RenderGuiEvent and ScreenEvent can both fire for one visual frame.
        // Treat near-adjacent callbacks as the same frame instead of reporting a
        // fictitious sub-millisecond frame and resetting upload pressure twice.
        if (lastFrameEventNanos != 0L
                && now - lastFrameEventNanos < DUPLICATE_FRAME_EVENT_NANOS) return;
        lastFrameEventNanos = now;
        refreshConfiguredTarget(now);
        long previous = lastFrameNanos;
        lastFrameNanos = now;
        if (previous == 0L) return;
        long elapsed = Math.min(250_000_000L, Math.max(1_000_000L, now - previous));
        smoothedFrameNanos = smoothedFrameNanos * 0.92 + elapsed * 0.08;
        Runtime runtime = Runtime.getRuntime();
        long maximumHeap = runtime.maxMemory();
        if (maximumHeap > 0L) {
            long usedHeap = runtime.totalMemory() - runtime.freeMemory();
            heapPressure = Math.max(0.0D, Math.min(1.0D,
                    usedHeap / (double) maximumHeap));
        }
        double target = targetFrameNanos();
        if (elapsed > target * 1.75D) pressureFrames = Math.min(120, pressureFrames + 4);
        else if (elapsed > target * 1.35D) pressureFrames = Math.min(120, pressureFrames + 1);
        else pressureFrames = Math.max(0, pressureFrames - 1);
        updateMutationCredit(elapsed, target);
    }

    public void setFullscreenState(boolean open, boolean interacting) {
        this.fullscreenOpen = open;
        this.interacting = interacting;
    }

    public void setFocus(double worldX, double worldZ) {
        focusWorldX = worldX;
        focusWorldZ = worldZ;
    }

    public double focusDistanceSquared(double worldX, double worldZ) {
        double dx = worldX - focusWorldX;
        double dz = worldZ - focusWorldZ;
        return dx * dx + dz * dz;
    }

    public boolean isFullscreenOpen() {
        return fullscreenOpen;
    }

    public boolean isInteracting() {
        return interacting;
    }

    public boolean underPressure() {
        double target = targetFrameNanos();
        return pressureFrames > 8 || smoothedFrameNanos > target * 1.40D
                || heapPressure >= HEAP_PRESSURE_ENTER
                || MapWorkScheduler.cpuTotalCost() > 1_240L
                || MapWorkScheduler.ioTotalCost() > 680L;
    }

    /**
     * True only when the shared workers are effectively idle and the current frame
     * is healthy. Runtime captures showed cave/surface work waiting for cadence and
     * frontier gates while both scheduler queues stayed at zero. Callers widen
     * admission with this signal but still obey the render-thread time deadline.
     */
    public boolean hasStreamingHeadroom() {
        if (underPressure()) return false;
        return MapWorkScheduler.cpuQueuedCount() <= 1
                && MapWorkScheduler.ioQueuedCount() <= 1
                && MapWorkScheduler.cpuTotalCost() < 280L
                && MapWorkScheduler.ioTotalCost() < 160L;
    }

    public boolean hasForegroundUploadHeadroom() {
        return fullscreenOpen && !underPressure()
                && MapWorkScheduler.cpuQueuedCount() <= 2
                && MapWorkScheduler.ioQueuedCount() <= 1;
    }

    private double targetFrameNanos() {
        /*
         * The previous governor optimized for 60 FPS and slowly learned a persistent
         * 25-35 ms map slowdown as the new healthy baseline. That made pressure go
         * false at 30-40 FPS. Use the player's real cap when it is a finite value,
         * otherwise hold an explicit 125 FPS objective. A measured slow frame is
         * evidence to reduce map work, never permission to move the target upward.
         * The option lookup is cached because underPressure/headroom are called by
         * several subsystems in one frame.
         */
        return configuredTargetFrameNanos;
    }

    private void refreshConfiguredTarget(long nowNanos) {
        if (lastTargetRefreshNanos != 0L
                && nowNanos - lastTargetRefreshNanos < 500_000_000L) return;
        lastTargetRefreshNanos = nowNanos;
        configuredTargetFrameNanos = configuredHealthyFrameNanos();
    }

    private static double configuredHealthyFrameNanos() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            int limit = minecraft == null ? 125
                    : minecraft.options.framerateLimit().get();
            // Minecraft commonly represents "unlimited" above the useful display
            // range. Finite 30-240 FPS caps are respected exactly.
            if (limit >= 30 && limit <= 240) {
                return Math.max(MIN_TARGET_FRAME_NANOS,
                        Math.min(MAX_TARGET_FRAME_NANOS,
                                1_000_000_000.0D / limit));
            }
        } catch (RuntimeException ignored) {
            // Options can be unavailable during very early bootstrap/resource reload.
        }
        return DEFAULT_TARGET_FRAME_NANOS;
    }

    private double headroomFactor() {
        double target = targetFrameNanos();
        double factor = target / Math.max(target, smoothedFrameNanos);
        if (pressureFrames > 20) factor *= 0.55;
        else if (pressureFrames > 8) factor *= 0.75;
        long cpuCost = MapWorkScheduler.cpuTotalCost();
        if (cpuCost > 620L) factor *= 0.50;
        else if (cpuCost > 420L) factor *= 0.72;
        if (MapWorkScheduler.ioTotalCost() > 340L) factor *= 0.78;
        factor *= integratedServerHeadroomFactor();
        return Math.max(0.20, Math.min(1.0, factor));
    }

    /**
     * Singleplayer generation and map observation share one process and one GC.
     * When the integrated server is already missing its 50 ms deadline, advancing
     * broad map work at the normal rate only stretches both frame and tick pacing.
     */
    private static double integratedServerMspt() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            MinecraftServer server = minecraft == null ? null
                    : minecraft.getSingleplayerServer();
            return server == null ? 0.0D
                    : server.getAverageTickTimeNanos() / 1_000_000.0D;
        } catch (RuntimeException ignored) {
            return 0.0D;
        }
    }

    private static double integratedServerHeadroomFactor() {
        double mspt = integratedServerMspt();
        if (mspt >= 40.0D) return 0.25D;
        if (mspt >= 30.0D) return 0.40D;
        if (mspt >= 22.0D) return 0.60D;
        if (mspt >= 16.0D) return 0.80D;
        return 1.0D;
    }

    public long gameplayScanBudgetNanos(boolean cave) {
        MapActivityGate activity = MapActivityGate.getInstance();
        if (activity.blocksMapWork()) {
            return movementStreamingBudgetNanos(cave);
        }
        // Cave projection is substantially more expensive per useful page than
        // surface scanning. Keep a larger but still adaptive slice so the minimap
        // does not wait seconds for the first 16x16 tiles.
        // Cave exact/branch projection and GPU publication already consume their
        // own bounded budgets. A 2 ms client-thread source slice on top of those
        // stages caused a visible hitch every AUTO transition. Xaero advances its
        // persistent writer under a strict elapsed-time deadline; keep this source
        // contribution sub-millisecond and let retained work continue next frame.
        long base = cave ? 850_000L : 300_000L;
        return Math.max(80_000L, (long) (base * headroomFactor()));
    }

    /**
     * Xaero-style travel writer slice: make bounded forward progress only while the
     * current frame and integrated server have real headroom. When vanilla starts
     * generating/meshing chunks this returns zero immediately, retaining the last
     * map image rather than contributing to the hitch.
     */
    public long movementStreamingBudgetNanos(boolean cave) {
        MapActivityGate activity = MapActivityGate.getInstance();
        if (!activity.blocksMapWork() || activity.blocksForegroundStreaming()) return 0L;
        double serverMspt = integratedServerMspt();
        boolean active = activity.isActivelyMoving();
        /*
         * Xaero does not make map writing binary while the player travels. It
         * advances a persistent cursor under a hard elapsed-time deadline. Keep the
         * same invariant here: even when vanilla generation is busy, one very small
         * coherent pulse is retained so the route cannot become a chain of holes.
         * The pulse never expands mutation/saved-world/background lanes.
         */
        if (serverMspt >= 40.0D) {
            return cave ? 250_000L : 180_000L;
        }
        double serverFactor = serverMspt >= 30.0D ? 0.45D
                : serverMspt >= 22.0D ? 0.70D : 1.0D;
        long base;
        if (underPressure()) {
            // Hard per-tick ceilings: enough to finish useful chunks, too small to
            // recreate the former multi-millisecond packet/mutation spikes.
            base = cave
                    ? (active ? 900_000L : 1_250_000L)
                    : (active ? 650_000L : 900_000L);
        } else {
            base = cave
                    ? (active ? 1_600_000L : 2_200_000L)
                    : (active ? 1_100_000L : 1_500_000L);
        }
        double factor = Math.max(0.55D, headroomFactor()) * serverFactor;
        long minimum = cave ? 250_000L : 180_000L;
        return Math.max(minimum, (long) (base * factor));
    }

    public long verticalArchiveBudgetNanos() {
        if (underPressure()) return 0L;
        return Math.max(80_000L, (long) (250_000L * headroomFactor()));
    }

    public long fullscreenScanBudgetNanos(float scale, boolean fastLoading) {
        if (!fullscreenOpen) return 0L;
        if (MapActivityGate.getInstance().blocksMapWork()) {
            return movementStreamingBudgetNanos(false);
        }
        long base;
        // Live Level access cannot leave the client thread. Keep it to a short,
        // resumable slice and let the saved-world decoder fill the broad viewport
        // asynchronously. A 5 ms scanner slice alone consumed most of the 8 ms
        // frame budget needed for 125 FPS.
        if (scale < 0.20f) base = fastLoading ? 500_000L : 250_000L;
        else if (scale < 0.55f) base = fastLoading ? 800_000L : 450_000L;
        else base = fastLoading ? 1_100_000L : 650_000L;
        return Math.max(150_000L, (long) (base * headroomFactor()));
    }

    public long fullscreenCaveBudgetNanos(float scale, boolean fastLoading) {
        if (!fullscreenOpen) return 0L;
        if (MapActivityGate.getInstance().blocksMapWork()) {
            return movementStreamingBudgetNanos(true);
        }
        long base;
        if (scale < 0.20f) base = fastLoading ? 350_000L : 180_000L;
        else if (scale < 0.55f) base = fastLoading ? 550_000L : 300_000L;
        else base = fastLoading ? 800_000L : 450_000L;
        return Math.max(120_000L, (long) (base * headroomFactor()));
    }

    public long textureUploadBudgetNanos(boolean focus) {
        MapActivityGate activity = MapActivityGate.getInstance();
        if (activity.blocksMapWork()) {
            if (activity.blocksForegroundStreaming()) return 0L;
            double serverMspt = integratedServerMspt();
            if (serverMspt >= 40.0D) return focus ? 320_000L : 120_000L;
            double serverFactor = serverMspt >= 30.0D ? 0.50D
                    : serverMspt >= 22.0D ? 0.72D : 1.0D;
            if (underPressure()) {
                // One visible page at a low cadence; enough to publish travel
                // progress continuously without draining a burst in one frame.
                long base = focus ? 850_000L : 250_000L;
                return Math.max(90_000L, (long) (base * serverFactor));
            }
            long base = focus ? 1_200_000L : 420_000L;
            return Math.max(90_000L,
                    (long) (base * headroomFactor() * serverFactor));
        }
        double target = targetFrameNanos();
        double current = Math.max(target, smoothedFrameNanos);
        double measuredSlack = Math.max(0.0D, target - Math.min(target, current * 0.82D));
        long base = focus ? 2_200_000L : 450_000L;
        long adaptive = (long) (base * headroomFactor() + measuredSlack * 0.12D);
        long maximum = focus
                ? (long) Math.max(1_200_000L, target * 0.30D)
                : (long) Math.max(250_000L, target * 0.06D);
        long minimum = focus ? 220_000L : 120_000L;
        return Math.max(minimum, Math.min(maximum, adaptive));
    }

    public long targetFrameBudgetNanos() {
        return (long) targetFrameNanos();
    }

    public double smoothedFrameNanos() {
        return smoothedFrameNanos;
    }

    public int texturePageBudget(boolean focus) {
        if (MapActivityGate.getInstance().blocksMapWork()) return 1;
        int base = focus ? 6 : 2;
        return Math.max(1, (int) Math.round(base * headroomFactor()));
    }

    /**
     * Hard client-thread deadline for packet-driven map repair. A column count is
     * not a reliable frame budget: a water/cave column can be orders of magnitude
     * more expensive than an already-cached flat column. Keep the count cap as a
     * safety net, but make elapsed time the primary pre-emption signal.
     */
    public long mutationRepairBudgetNanos() {
        return mutationRepairBudgetNanos(false);
    }

    private long mutationRepairBudgetNanos(boolean backlogWaiting) {
        boolean pressured = underPressure();
        long base = pressured
                ? 160_000L
                : (fullscreenOpen ? 400_000L : 280_000L);
        long budget = Math.max(90_000L, (long) (base * headroomFactor()));
        if (!pressured && backlogWaiting) {
            long burst = consumeMutationCredit(MAX_MUTATION_BURST_PER_TICK_NANOS);
            budget = Math.min(550_000L, budget + burst);
        }
        return budget;
    }


    /**
     * One immutable admission profile consumed by the unified observation scheduler.
     * Existing subsystem-specific budget methods remain the low-level time slices;
     * this profile decides which lanes may run and how much mutation/viewport work
     * may be admitted during the current frame-pressure regime.
     */
    public ObservationProfile observationProfile(Minecraft minecraft) {
        MapActivityGate activity = MapActivityGate.getInstance();
        if (activity.blocksForegroundStreaming()) {
            return new ObservationProfile(0, 0, 0L, 0.0,
                    Long.MAX_VALUE, Long.MAX_VALUE,
                    false, false, false, false, false);
        }
        boolean pressured = underPressure();
        if (activity.blocksMapWork()) {
            /*
             * Travel keeps one small loaded-chunk writer and one-page publication
             * lane alive. Saved-world decode, mutation ingress, warmup and background
             * archive remain disabled. Under actual frame/server pressure even this
             * foreground lane pauses, matching Xaero's elapsed-time writer behaviour.
             */
            long writerBudget = movementStreamingBudgetNanos(false);
            if (writerBudget <= 0L) {
                return new ObservationProfile(0, 0, 0L, 0.25,
                        250_000_000L, 200_000_000L,
                        false, false, false, false, false);
            }
            boolean active = activity.isActivelyMoving();
            if (pressured) {
                return new ObservationProfile(0, 0, 0L,
                        active ? 0.30 : 0.45,
                        active ? 120_000_000L : 90_000_000L,
                        active ? 65_000_000L : 50_000_000L,
                        true, false, false, true, false);
            }
            boolean idleWorkers = hasStreamingHeadroom();
            return new ObservationProfile(0, 0, 0L,
                    active ? 0.55 : 0.75,
                    active ? 50_000_000L : 35_000_000L,
                    active ? 25_000_000L : 20_000_000L,
                    true, idleWorkers && fullscreenOpen,
                    false, true, false);
        }
        boolean movingFast = minecraft != null && minecraft.player != null
                && minecraft.player.getDeltaMovement().horizontalDistanceSqr() >= 0.18;
        boolean mutationBacklog = MapMutationBus.getInstance()
                .hasBacklog(
                        MovementMutationPolicy.BACKLOG_COLUMN_THRESHOLD,
                        MovementMutationPolicy.BACKLOG_CHUNK_THRESHOLD);
        long mutationBudget = mutationRepairBudgetNanos(mutationBacklog);
        if (pressured) {
            if (fullscreenOpen) {
                return new ObservationProfile(20, 2, mutationBudget, 0.85,
                        120_000_000L, 140_000_000L,
                        true, true, false, true, false);
            }
            return new ObservationProfile(12, 1, mutationBudget, 0.65,
                    80_000_000L, 140_000_000L,
                    true, true, false, true, false);
        }
        int mutationColumnBudget = mutationBacklog ? 64 : 24;
        int mutationChunkBudget = mutationBacklog ? 4 : 2;
        if (fullscreenOpen) {
            return new ObservationProfile(Math.max(24, mutationColumnBudget),
                    Math.max(2, mutationChunkBudget), mutationBudget, 1.0,
                    80_000_000L, 50_000_000L,
                    true, true, !movingFast, true, !movingFast);
        }
        return new ObservationProfile(mutationColumnBudget, mutationChunkBudget,
                mutationBudget,
                movingFast ? 0.80 : 1.0,
                40_000_000L, 50_000_000L,
                true, true, !movingFast, true, !movingFast);
    }

    private void updateMutationCredit(long elapsedNanos, double targetNanos) {
        if (pressureFrames > 4 || elapsedNanos > targetNanos * 1.08D) {
            halveMutationCredit();
            return;
        }
        if (elapsedNanos >= targetNanos * 0.90D) return;
        long slack = Math.max(0L, (long) targetNanos - elapsedNanos);
        long gain = Math.min(MAX_MUTATION_CREDIT_GAIN_PER_FRAME_NANOS,
                Math.max(20_000L, slack >>> 3));
        addMutationCredit(gain);
    }

    private void halveMutationCredit() {
        while (true) {
            long current = mutationCreditNanos.get();
            long next = current >>> 1;
            if (current == next
                    || mutationCreditNanos.compareAndSet(current, next)) return;
        }
    }

    private void addMutationCredit(long gain) {
        while (true) {
            long current = mutationCreditNanos.get();
            long next = Math.min(MAX_MUTATION_CREDIT_NANOS, current + gain);
            if (current == next
                    || mutationCreditNanos.compareAndSet(current, next)) return;
        }
    }

    private long consumeMutationCredit(long maximum) {
        while (true) {
            long current = mutationCreditNanos.get();
            if (current <= 0L) return 0L;
            long consumed = Math.min(current, maximum);
            if (mutationCreditNanos.compareAndSet(current, current - consumed)) {
                return consumed;
            }
        }
    }

    public record ObservationProfile(int mutationColumnBudget,
            int mutationChunkBudget, long mutationBudgetNanos,
            double liveRadiusFactor,
            long fullscreenIntervalNanos, long minimapIntervalNanos,
            boolean allowVisibleScan, boolean allowSavedVisible,
            boolean allowLayerWarmup, boolean allowPublication,
            boolean allowArchiveBackground) {
    }

    public boolean allowBackgroundWork(Minecraft minecraft) {
        if (MapActivityGate.getInstance().blocksMapWork()) return false;
        if (underPressure()) return false;
        if (minecraft == null || minecraft.player == null) return false;
        double speedSq = minecraft.player.getDeltaMovement().horizontalDistanceSqr();
        return speedSq < 0.18;
    }
}
