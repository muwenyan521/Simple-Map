package com.velorise.simplemap.client.gpu;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/** Dependency-free checks for compact logical page-table draw plans. */
public final class MapGpuInstancePlanCheck {
    private MapGpuInstancePlanCheck() { }

    public static void main(String[] args) {
        MapGpuInstancePlan.Builder builder = new MapGpuInstancePlan.Builder();
        TileKey first96 = key(1, 10, 20);
        TileKey phase4 = key(2, 11, 21);
        TileKey second96 = key(3, 12, 22);
        TileKey phase128 = key(4, 13, 23);

        require(builder.add(first96, 96, 10, 20, 64, 64), "first add failed");
        require(builder.add(phase4, 4, -64, 0, 64, 64), "coarse add failed");
        require(builder.add(second96, 96, 74, 20, 64, 64), "stable add failed");
        require(builder.add(phase128, 128, 0, 64, 64, 64), "glow add failed");
        require(!builder.add(null, 96, 0, 0, 64, 64), "null key was accepted");
        require(!builder.add(first96, 96, 0, 0, 0, 64), "zero width was accepted");

        MapGpuInstancePlan plan = builder.build();
        require(plan.size() == 4, "wrong plan size");
        require(plan.phase(0) == 4 && plan.phase(1) == 96
                        && plan.phase(2) == 96 && plan.phase(3) == 128,
                "phase ordering is not coarse-to-fine");
        require(plan.key(1).equals(first96) && plan.key(2).equals(second96),
                "equal-phase insertion order was not retained");
        require(plan.x(0) == -64.0f && plan.y(0) == 0.0f
                        && plan.width(0) == 64.0f && plan.height(0) == 64.0f,
                "rectangle data moved independently from its key");
        require(plan.hasPhase(96) && !plan.hasPhase(97), "phase lookup failed");
        require(plan.firstIndexAtOrAfter(96) == 1, "phase lower bound failed");
        require(plan.firstIndexAfter(96) == 3, "phase upper bound failed");
        require(plan.firstIndexAtOrAfter(129) == 4, "end lower bound failed");

        MapGpuInstancePlan.Builder masked = new MapGpuInstancePlan.Builder();
        require(masked.add(first96, 96, 0, 0, 16, 16,
                        0.25f, 0.5f, 0.5f, 0.75f, 1 << 9),
                "masked subtile add failed");
        MapGpuInstancePlan maskedPlan = masked.build();
        require(maskedPlan.localU0(0) == 0.25f
                        && maskedPlan.localV0(0) == 0.5f
                        && maskedPlan.localU1(0) == 0.5f
                        && maskedPlan.localV1(0) == 0.75f
                        && maskedPlan.requiredCoverageMask(0) == (1 << 9),
                "subtile UV/coverage metadata was not retained");

        randomizedStableOrdering();

        Set<String> fields = Arrays.stream(MapGpuInstancePlan.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName).collect(Collectors.toSet());
        require(fields.equals(Set.of("keys", "phases", "rects",
                        "localUvs", "requiredCoverageMasks", "size")),
                "unexpected logical-plan state: " + fields);

        System.out.println("MAP_GPU_INSTANCE_PLAN_PASS");
    }


    private static void randomizedStableOrdering() {
        Random random = new Random(0x5A17C0DEL);
        int[] phasePool = { 4, 16, 27, 28, 96, 128 };
        for (int round = 0; round < 5_000; round++) {
            int size = random.nextInt(513);
            MapGpuInstancePlan.Builder builder = new MapGpuInstancePlan.Builder();
            TileKey[] keys = new TileKey[size];
            int[] phases = new int[size];
            for (int index = 0; index < size; index++) {
                phases[index] = phasePool[random.nextInt(phasePool.length)];
                keys[index] = new TileKey(11L, random.nextInt(3), random.nextInt(4),
                        index, -index, random.nextInt(6) + 1);
                require(builder.add(keys[index], phases[index], index, -index, 64, 64),
                        "random add failed");
            }
            MapGpuInstancePlan plan = builder.build();
            require(plan.size() == size, "random plan size changed");
            int previousPhase = Integer.MIN_VALUE;
            int[] lastOriginalIndex = new int[129];
            Arrays.fill(lastOriginalIndex, -1);
            for (int output = 0; output < size; output++) {
                int phase = plan.phase(output);
                require(phase >= previousPhase, "random phase order regressed");
                previousPhase = phase;
                int original = plan.key(output).tileX();
                require(phases[original] == phase, "key/phase association changed");
                require(original > lastOriginalIndex[phase],
                        "equal-phase ordering became unstable");
                lastOriginalIndex[phase] = original;
                require(plan.x(output) == original && plan.y(output) == -original,
                        "random rectangle association changed");
            }
            for (int phase : phasePool) {
                int lower = plan.firstIndexAtOrAfter(phase);
                int upper = plan.firstIndexAfter(phase);
                require(lower <= upper, "random phase range inverted");
                for (int index = lower; index < upper; index++) {
                    require(plan.phase(index) == phase, "random phase range leaked");
                }
            }
        }
    }

    private static TileKey key(int variant, int x, int z) {
        return new TileKey(7L, 1, 0, x, z, variant);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
