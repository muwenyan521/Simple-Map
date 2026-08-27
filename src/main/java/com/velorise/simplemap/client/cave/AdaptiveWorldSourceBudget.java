package com.velorise.simplemap.client.cave;

/**
 * Pure policy for the decoded world-source cache.
 *
 * <p>Kept independent from Minecraft classes so heap-pressure behavior can be
 * unit tested without launching the game. The policy reserves most heap for the
 * game/modpack and contracts both residency and decode concurrency before the
 * JVM approaches a GC spiral.</p>
 */
final class AdaptiveWorldSourceBudget {
    static final long MIB = 1L << 20;
    static final long MIN_TARGET = 32L * MIB;
    static final long MAX_TARGET = 256L * MIB;

    private AdaptiveWorldSourceBudget() {
    }

    static Snapshot evaluate(long maximumHeap, long committedHeap, long freeCommitted,
            int processors, int pendingForeground, int pendingBackground) {
        long max = Math.max(64L * MIB, maximumHeap);
        long committed = Math.max(0L, Math.min(max, committedHeap));
        long free = Math.max(0L, Math.min(committed, freeCommitted));
        long used = Math.max(0L, committed - free);
        long headroom = Math.max(0L, max - used);
        double pressure = Math.min(1.0D, used / (double) max);

        long target = Math.max(64L * MIB, Math.min(MAX_TARGET, max / 16L));
        if (pressure >= 0.92D) target /= 8L;
        else if (pressure >= 0.86D) target /= 4L;
        else if (pressure >= 0.78D) target /= 2L;
        target = Math.min(target, Math.max(MIN_TARGET, headroom / 2L));
        target = clamp(target, MIN_TARGET, MAX_TARGET);

        int cpu = Math.max(1, processors);
        /*
         * PASS139: decoded-source admission is a disk/CPU pipeline, not a "more is
         * always faster" queue. PASS138 allowed up to 288 simultaneous chunkMap
         * reads (exactly eight 6x6 Cave source pages). The latest run then recorded
         * ANVIL_READ avg ~=52 ms, max >3.2 s and hundreds of completions in one
         * sample while frame time spiked. Keep enough overlap for NVMe latency, but
         * stop flooding Minecraft's RegionFileStorage/IO queue.
         */
        /*
         * PASS141: a visible 64x64 Cave page is exactly sixteen Minecraft chunks.
         * The PASS139/PASS140 heap-pressure floor could contract async source IO to
         * six chunks, making one page transaction mathematically impossible while a
         * handful of durable native-archive reads were active. Xaero's world-save
         * reader starts all sixteen NBT futures for one 4x4 MapTileChunk together.
         * Keep at least one complete visible page plus a bounded durable overlap;
         * CPU decode/fanout remains separately limited by PriorityDecodeExecutor.
         */
        int baseInFlight = Math.max(28, Math.min(32, cpu * 2));
        if (pressure >= 0.92D) baseInFlight = 28;
        else if (pressure >= 0.86D) baseInFlight = 30;
        else if (pressure >= 0.78D) baseInFlight = Math.max(30, baseInFlight);

        int demand = Math.max(0, pendingForeground)
                + Math.max(0, pendingBackground) / 4;
        int maximumInFlight = Math.min(32,
                baseInFlight + Math.min(4, demand / 16));
        int maximumPrefetch = Math.max(1,
                Math.min(4, maximumInFlight / (pressure >= 0.78D ? 10 : 8)));
        return new Snapshot(target, maximumInFlight, maximumPrefetch, pressure, headroom);
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    record Snapshot(long targetBytes, int maximumInFlight, int maximumPrefetch,
            double pressure, long headroomBytes) {
    }
}
