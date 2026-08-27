package com.velorise.simplemap.client;

import com.velorise.simplemap.client.cave.CavePipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/**
 * Render-frame foreground writer for the live player corridor.
 *
 * <p>The client tick scheduler remains the authority for mutation repair,
 * persistence, saved-world IO and broad viewport work. This writer owns only
 * already-loaded chunks close to the player. It converts elapsed render time into
 * a small capped credit, then resumes the same Surface/Cave cursors over multiple
 * frames. That is the important part of Xaero's writer design: progress follows
 * wall-clock time instead of being limited to twenty coarse pulses per second.</p>
 */
public final class MapForegroundWriter {
    private static final MapForegroundWriter INSTANCE = new MapForegroundWriter();
    private static final long MAX_ELAPSED_NANOS = 50_000_000L;
    private static final long MIN_SPEND_NANOS = 180_000L;
    private static final long PRESSURED_FRAME_CAP_NANOS = 1_150_000L;
    private static final long HEALTHY_FRAME_CAP_NANOS = 2_400_000L;
    private static final long MAX_CREDIT_NANOS = 5_000_000L;

    private Level observedLevel;
    private long lastFrameNanos;
    private long creditNanos;

    private MapForegroundWriter() { }

    public static MapForegroundWriter getInstance() {
        return INSTANCE;
    }

    public void reset() {
        observedLevel = null;
        lastFrameNanos = 0L;
        creditNanos = 0L;
    }

    /** Called once for each physical HUD frame, before retained-map rendering. */
    public void onFrame(Minecraft minecraft, boolean mapUnlocked) {
        long now = System.nanoTime();
        boolean supportedScreen = minecraft != null
                && (minecraft.screen == null || minecraft.screen instanceof MapScreen);
        if (minecraft == null || minecraft.level == null || minecraft.player == null
                || !mapUnlocked || !supportedScreen) {
            lastFrameNanos = now;
            creditNanos = 0L;
            observedLevel = minecraft == null ? null : minecraft.level;
            return;
        }
        if (observedLevel != minecraft.level) {
            observedLevel = minecraft.level;
            lastFrameNanos = now;
            creditNanos = 0L;
            return;
        }
        long previous = lastFrameNanos;
        lastFrameNanos = now;
        if (previous == 0L) return;
        if (MapActivityGate.getInstance().blocksForegroundStreaming()) {
            creditNanos = 0L;
            return;
        }
        /*
         * PASS148: while fullscreen Cave owns MapScreen, the visible source is the
         * one-shot Anvil snapshot transaction. Advancing the live cave writer every
         * render frame mutates the same repository fingerprints underneath exact
         * builds and was responsible for hundreds of CAVE_RESULT_STALE discards in
         * the 14:07 trace. Freeze this player-corridor writer until MapScreen closes;
         * normal gameplay/minimap resumes it immediately afterwards.
         */
        if (minecraft.screen instanceof MapScreen && CaveMode.isActive(minecraft)) {
            creditNanos = 0L;
            return;
        }

        long elapsed = Math.max(1_000_000L,
                Math.min(MAX_ELAPSED_NANOS, now - previous));
        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        boolean pressured = governor.underPressure();
        boolean moving = MapActivityGate.getInstance().blocksMapWork();

        // A fixed duty ratio makes throughput independent of FPS. At 120 FPS this
        // accrues many small slices; at 20 FPS it accrues a larger but capped slice.
        // Healthy sessions in the PASS53 trace held 119-128 FPS while the live
        // Surface frontier still fell behind. Spend a little more of that measured
        // headroom on coherent near-player chunks; pressure retains the old cap.
        double duty = pressured ? 0.040D : (moving ? 0.090D : 0.065D);
        long gained = Math.max(40_000L, (long) (elapsed * duty));
        creditNanos = Math.min(MAX_CREDIT_NANOS, creditNanos + gained);
        long frameCap = pressured ? PRESSURED_FRAME_CAP_NANOS
                : HEALTHY_FRAME_CAP_NANOS;
        long budget = Math.min(frameCap, creditNanos);
        if (budget < MIN_SPEND_NANOS) return;

        long started = System.nanoTime();
        if (CaveMode.isActive(minecraft)) {
            // Cave projection is the selected visual, but Surface history must keep
            // following the route for an instant OFF switch and the world map.
            long caveBudget = Math.min(650_000L, budget * 2L / 5L);
            CavePipeline.getInstance().scanForegroundFrame(minecraft, caveBudget);
            long remainingDeadline = started + budget;
            long remaining = Math.max(0L, remainingDeadline - System.nanoTime());
            if (remaining >= 80_000L) {
                ChunkScanner.getInstance().scanSurfaceForegroundFrame(
                        minecraft, remaining);
            }
        } else {
            ChunkScanner.getInstance().scanSurfaceForegroundFrame(
                    minecraft, budget);
        }
        long spent = Math.max(0L, System.nanoTime() - started);
        creditNanos = Math.max(0L, creditNanos - Math.min(creditNanos, spent));
        // Overspending means the current column was unusually expensive. Do not
        // carry debt into the next frame; the ordinary governor will also pressure.
        if (spent > frameCap * 2L) creditNanos = 0L;
    }
}
