package com.velorise.simplemap.client;

import com.velorise.simplemap.client.cave.CavePipeline;
import com.velorise.simplemap.client.cave.CaveColumnData;
import com.velorise.simplemap.client.cave.CaveDimensionProfile;
import com.velorise.simplemap.client.cave.CaveStateClassifier;
import com.velorise.simplemap.client.cave.CaveView;
import com.velorise.simplemap.client.cave.DenseCaveTile;
import com.velorise.simplemap.client.pipeline.RevisionStamp;
import com.velorise.simplemap.client.session.MapSessionManager;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class ChunkScanner {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int IMMEDIATE_RADIUS = 8;
    private static final int FULL_CAVE_IMMEDIATE_RADIUS = 6;
    private static final int REGION_CHUNK_SIZE = 32;
    private static final int UNKNOWN_SURFACE_SCAN_ATTEMPTS = 12;
    // Do not restart an in-progress chunk every time the player walks a few blocks.
    private static final int NORMAL_REANCHOR_DISTANCE = 24;
    /** Surface catch-up keeps its chunk cursor across ordinary movement. */
    private static final int SURFACE_REANCHOR_CHUNKS = 4;
    /** Xaero-style transaction size: scan a bounded part, publish once per slice. */
    private static final int SURFACE_CHUNK_SLICE = 256;
    /** A loaded FULL chunk is one visual transaction, matching Xaero's writer. */
    private static final int SURFACE_QUEUE_STALE_RADIUS_CHUNKS = 8;
    /**
     * Packet-loaded chunks are primitive keys only. 1,024 cannot even hold a
     * 33x33 render-distance-16 square (1,089 chunks), so it deterministically
     * dropped valid client chunks before the writer saw them. 8,192 covers a full
     * render-distance-32 view plus movement slack without materialising chunk data.
     */
    private static final int LOADED_SURFACE_QUEUE_LIMIT = 8_192;
    /**
     * Spatial reprioritisation is a viewport event, not a per-transaction operation.
     * Re-evaluate at most ten times per second while the player remains in one chunk.
     */
    private static final long SURFACE_QUEUE_REPRIORITIZE_NANOS = 100_000_000L;
    /** Surface remains authoritative even while a cave projection is selected. */
    private static final long SURFACE_DURING_CAVE_BUDGET_NANOS = 900_000L;
    private static final int BASE_VERTICAL_COST = 24;
    // Increase per-tick nano budgets to allow broader scanning for larger explored areas
    private static final long SURFACE_SCAN_BUDGET_NANOS = 5_000_000L;
    private static final long CAVE_SCAN_BUDGET_NANOS = 7_000_000L;
    private static final long MAP_SCREEN_SCAN_BUDGET_NANOS = 18_000_000L;
    private static final long MAP_SCREEN_BALANCED_BUDGET_NANOS = 9_000_000L;
    private static final int MAP_SCREEN_MAX_VISITED_PIXELS = 32_768;
    private static final int IMMEDIATE_COLUMNS_PER_TICK = 64;
    /** One-shot main-thread budget used only after committing a different Top-Y. */
    /** Keep aggressively filling the selected layer for a few follow-up ticks. */
    /** Quiet background budget that captures vertical cave surfaces during normal exploration. */
    private static final long VERTICAL_ARCHIVE_BACKGROUND_BUDGET_NANOS = 2_000_000L;
    /** Full-map focus mode may spend more time completing reusable distant cave data. */
    private static final long VERTICAL_ARCHIVE_MAP_FAST_BUDGET_NANOS = 7_000_000L;
    private static final long VERTICAL_ARCHIVE_MAP_BALANCED_BUDGET_NANOS = 4_000_000L;
    private static final int VERTICAL_ARCHIVE_MAX_COLUMN_CHECKS = 4096;
    /** Avoid restarting the vertical pass whenever the player merely crosses one chunk border. */
    private static final int VERTICAL_ARCHIVE_REANCHOR_CHUNKS = 3;
    private static final long CAVE_PIXEL_NOT_FOUND = Long.MIN_VALUE;
    /** Impossible Minecraft Surface height encoding used only as a scanner sentinel. */
    private static final long SURFACE_DATA_UNAVAILABLE = Long.MIN_VALUE + 1L;

    private static final ChunkScanner INSTANCE = new ChunkScanner();

    public static ChunkScanner getInstance() {
        return INSTANCE;
    }

    private final CaveStateClassifier caveStateClassifier = CaveStateClassifier.getInstance();
    private final MapVisualClassifier visualClassifier = MapVisualClassifier.getInstance();
    private final MapBlockEntityVisualResolver blockEntityVisuals =
            MapBlockEntityVisualResolver.getInstance();
    /**
     * Column scanning is a very hot client-side path. Reuse mutable positions per
     * calling thread instead of allocating several BlockPos instances for every
     * map pixel. A ThreadLocal also keeps direct UI refreshes safe if a future
     * source reader invokes the scanner away from the client thread.
     */
    private final ThreadLocal<ScanScratch> scanScratch =
            ThreadLocal.withInitial(ScanScratch::new);
    /** Registry locations are stable; retain their serialized palette IDs once. */
    private final Map<ResourceLocation, String> biomeIdStrings = new HashMap<>();
    private final Random random = new Random();
    /** NORMAL completes loaded chunks from nearest to farthest. */
    private final Map<String, NormalScanState> normalScans = new HashMap<>();
    private final Map<Integer, int[]> chunkOrders = new HashMap<>();
    /** Each view keeps an adaptive reveal radius while the player is moving. */
    private final Map<String, MovementState> movementStates = new HashMap<>();
    private final Map<String, Integer> immediateCursors = new HashMap<>();
    private final Map<String, Integer> urgentChunkCursors = new HashMap<>();
    /**
     * Xaero-style packet ingress: network handlers append only a primitive chunk
     * key. The client tick later captures the chunk as one deadline-checked 256-column
     * visual transaction. No 256-column scan or mutation object is created in the packet path.
     */
    private final LongArrayFIFOQueue loadedSurfaceChunks = new LongArrayFIFOQueue();
    private final LongOpenHashSet loadedSurfaceChunkSet = new LongOpenHashSet();
    private final Long2IntOpenHashMap loadedSurfaceChunkCursors =
            new Long2IntOpenHashMap();
    /**
     * Double-buffered live chunk builds. Xaero writes into a loading tile and swaps
     * it only after the tile is coherent; never expose 32/64/128 freshly sampled
     * columns through the already-complete retained Surface authority.
     */
    private final Long2ObjectOpenHashMap<SurfaceChunkStage> surfaceChunkStages =
            new Long2ObjectOpenHashMap<>();
    /** Packet-complete chunks must replace stale/placeholder Surface cache data. */
    private final LongOpenHashSet forcedSurfaceChunkRefresh = new LongOpenHashSet();
    /**
     * Xaero-style reconciliation cursor. Packet ingress is the primary writer;
     * this cursor only walks a bounded number of loaded candidates per pulse so a
     * render-distance halo is never re-probed in one monolithic sweep.
     */
    private static final int FOREGROUND_SEED_CANDIDATES_PER_PULSE = 24;
    private static final long FOREGROUND_SEED_RECONCILE_NANOS = 1_000_000_000L;
    private static final int LIVE_SURFACE_VERIFIED_LIMIT = 8_192;
    private static final int SURFACE_STAGE_LIMIT = 2_048;
    private static final long SURFACE_WRITER_MIN_PULSE_NANOS = 5_000_000L;
    private static final boolean[] ALL_SURFACE_COLUMNS_VALID = fullSurfaceValidity();
    private int foregroundSeedChunkX = Integer.MIN_VALUE;
    private int foregroundSeedChunkZ = Integer.MIN_VALUE;
    private int foregroundSeedRadiusBlocks = -1;
    private int foregroundSeedCursor;
    private long foregroundSeedNanos;
    /** Chunks whose retained Surface has been verified from this live client session. */
    private final LongOpenHashSet liveSurfacePublishedChunks = new LongOpenHashSet();
    private int surfaceQueuePriorityChunkX = Integer.MIN_VALUE;
    private int surfaceQueuePriorityChunkZ = Integer.MIN_VALUE;
    private long surfaceQueuePriorityNanos;
    /** One live Surface writer slice per client frame/pulse, regardless of callers. */
    private long surfaceWriterLastPulseNanos;
    /** One-shot destination refresh armed by MapActivityGate.teleportEpoch(). */
    private boolean teleportRecoverySeedPending;
    private final Map<String, ViewportScanState> viewportScans = new HashMap<>();
    private final Map<String, NormalScanState> verticalArchiveScans = new HashMap<>();
    /** Recently edited columns are rebuilt before the normal outward archive pass. */
    private final ArrayDeque<Long> verticalArchivePriority = new ArrayDeque<>();
    private final Set<Long> verticalArchivePrioritySet = new HashSet<>();
    private volatile long forcedRescanUntilNanos;
    private long observedCaveModeRevision = Long.MIN_VALUE;
    private CaveView observedCaveView;
    private int observedCaveBandY = Integer.MIN_VALUE;
    private int observedCaveProjectionTopY = Integer.MIN_VALUE;

    private ChunkScanner() {
    }

    /** Reset scan state so a fresh world starts with a clean slate */
    public void reset() {
        normalScans.clear();
        movementStates.clear();
        immediateCursors.clear();
        urgentChunkCursors.clear();
        loadedSurfaceChunks.clear();
        loadedSurfaceChunkSet.clear();
        loadedSurfaceChunkCursors.clear();
        surfaceChunkStages.clear();
        forcedSurfaceChunkRefresh.clear();
        foregroundSeedChunkX = Integer.MIN_VALUE;
        foregroundSeedChunkZ = Integer.MIN_VALUE;
        foregroundSeedRadiusBlocks = -1;
        foregroundSeedCursor = 0;
        foregroundSeedNanos = 0L;
        liveSurfacePublishedChunks.clear();
        surfaceQueuePriorityChunkX = Integer.MIN_VALUE;
        surfaceQueuePriorityChunkZ = Integer.MIN_VALUE;
        surfaceQueuePriorityNanos = 0L;
        surfaceWriterLastPulseNanos = 0L;
        teleportRecoverySeedPending = false;
        viewportScans.clear();
        verticalArchiveScans.clear();
        verticalArchivePriority.clear();
        verticalArchivePrioritySet.clear();
        // Existing cache pixels may have been produced by an older scan rule.
        // Give every newly entered world a short repair pass over loaded chunks so
        // stale black holes are replaced without requiring manual cache deletion.
        forcedRescanUntilNanos = System.nanoTime() + 3_000_000_000L;
        observedCaveModeRevision = Long.MIN_VALUE;
        observedCaveView = null;
        observedCaveBandY = Integer.MIN_VALUE;
        observedCaveProjectionTopY = Integer.MIN_VALUE;
        CavePipeline.getInstance().resetScanState();
    }

    /**
     * Cheap packet ingress used by the chunk-data mixin. The complete chunk is not
     * scanned here; it is resumed later by the foreground writer. Capping the FIFO
     * drops old chunks behind a fast traveller instead of creating an unbounded
     * catch-up backlog.
     */
    public void enqueueLoadedSurfaceChunk(int chunkX, int chunkZ) {
        enqueueLoadedSurfaceChunk(chunkX, chunkZ, true);
    }

    /**
     * Reasserts a currently loaded FULL chunk as the Surface authority without
     * restarting an in-flight writer transaction. Disk reconstruction can discover
     * that live data exists after the packet writer has already advanced; treating
     * that discovery as a new packet used to reset the cursor and publish a disk
     * snapshot first, creating disk->live revision churn at chunk cadence.
     */
    public void enqueueLiveSurfaceAuthorityChunk(int chunkX, int chunkZ) {
        enqueueLoadedSurfaceChunk(chunkX, chunkZ, false);
    }

    /**
     * Foreground retained-source recovery hook. A page request may discover that a
     * Minecraft FULL chunk is live while the retained Surface DB no longer owns its
     * 16x16 body (for example after source eviction/rebase or a packet/seed cursor
     * race). Queue exactly that live chunk again; do not wait for another network
     * packet or for the player to move back across the seed cursor.
     */
    public void nudgeRetainedSurfacePage(int regionX, int regionZ,
            int localPageX, int localPageZ, int missingSubtileMask) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> nudgeRetainedSurfacePage(regionX, regionZ,
                    localPageX, localPageZ, missingSubtileMask));
            return;
        }
        if (minecraft.level == null || missingSubtileMask == 0) return;
        int firstChunkX = regionX * REGION_CHUNK_SIZE + localPageX * 4;
        int firstChunkZ = regionZ * REGION_CHUNK_SIZE + localPageZ * 4;
        for (int localZ = 0; localZ < 4; localZ++) {
            for (int localX = 0; localX < 4; localX++) {
                int bit = 1 << (localZ * 4 + localX);
                if ((missingSubtileMask & bit) == 0) continue;
                int chunkX = firstChunkX + localX;
                int chunkZ = firstChunkZ + localZ;
                if (readySurfaceChunk(minecraft.level, chunkX, chunkZ) == null) {
                    continue;
                }
                long key = packChunk(chunkX, chunkZ);
                if (loadedSurfaceChunkSet.contains(key)) continue;
                forcedSurfaceChunkRefresh.add(key);
                loadedSurfaceChunkCursors.remove(key);
                surfaceChunkStages.remove(key);
                liveSurfacePublishedChunks.remove(key);
                if (loadedSurfaceChunks.size() >= LOADED_SURFACE_QUEUE_LIMIT) {
                    long dropped = loadedSurfaceChunks.dequeueLong();
                    loadedSurfaceChunkSet.remove(dropped);
                    loadedSurfaceChunkCursors.remove(dropped);
                    surfaceChunkStages.remove(dropped);
                    forcedSurfaceChunkRefresh.remove(dropped);
                }
                if (loadedSurfaceChunkSet.add(key)) loadedSurfaceChunks.enqueue(key);
            }
        }
    }

    /**
     * Starts a discontinuous Surface authority handoff. Old packet/seed cursors are
     * tied to the previous player location and are not useful after a teleport. The
     * first destination seed forces every already-loaded FULL chunk through one
     * coherent 256-column transaction; later packet arrivals remain authoritative.
     */
    public void beginTeleportRecovery() {
        loadedSurfaceChunks.clear();
        loadedSurfaceChunkSet.clear();
        loadedSurfaceChunkCursors.clear();
        surfaceChunkStages.clear();
        forcedSurfaceChunkRefresh.clear();
        foregroundSeedChunkX = Integer.MIN_VALUE;
        foregroundSeedChunkZ = Integer.MIN_VALUE;
        foregroundSeedRadiusBlocks = -1;
        foregroundSeedCursor = 0;
        foregroundSeedNanos = 0L;
        liveSurfacePublishedChunks.clear();
        surfaceQueuePriorityChunkX = Integer.MIN_VALUE;
        surfaceQueuePriorityChunkZ = Integer.MIN_VALUE;
        surfaceQueuePriorityNanos = 0L;
        surfaceWriterLastPulseNanos = 0L;
        teleportRecoverySeedPending = true;
    }

    /** Force once without restarting a cursor that has already begun this recovery. */
    private void enqueueTeleportRecoveryChunk(int chunkX, int chunkZ) {
        long key = packChunk(chunkX, chunkZ);
        if (loadedSurfaceChunkSet.contains(key)) return;
        forcedSurfaceChunkRefresh.add(key);
        loadedSurfaceChunkCursors.remove(key);
        // Recovery traversal is centre-out. Once the bounded FIFO is full, keep
        // those nearest chunks instead of evicting its head to admit farther rings.
        // Normal packet ingress below may still drop old edge-first packet work.
        if (loadedSurfaceChunks.size() >= LOADED_SURFACE_QUEUE_LIMIT) {
            forcedSurfaceChunkRefresh.remove(key);
            return;
        }
        if (!loadedSurfaceChunkSet.add(key)) return;
        loadedSurfaceChunks.enqueue(key);
    }

    private void enqueueLoadedSurfaceChunk(int chunkX, int chunkZ,
            boolean packetAuthoritative) {
        long key = packChunk(chunkX, chunkZ);
        if (packetAuthoritative) {
            // A packet arriving at TAIL is newer than any seed scan or persisted
            // completion bit. Restart this one bounded transaction from column 0.
            forcedSurfaceChunkRefresh.add(key);
            loadedSurfaceChunkCursors.remove(key);
            surfaceChunkStages.remove(key);
        }
        if (!loadedSurfaceChunkSet.add(key)) return;
        while (loadedSurfaceChunks.size() >= LOADED_SURFACE_QUEUE_LIMIT) {
            long dropped = loadedSurfaceChunks.dequeueLong();
            loadedSurfaceChunkSet.remove(dropped);
            loadedSurfaceChunkCursors.remove(dropped);
            surfaceChunkStages.remove(dropped);
            forcedSurfaceChunkRefresh.remove(dropped);
        }
        loadedSurfaceChunks.enqueue(key);
    }

    private static boolean[] fullSurfaceValidity() {
        boolean[] valid = new boolean[256];
        java.util.Arrays.fill(valid, true);
        return valid;
    }

    private static long packChunk(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static int unpackChunkX(long key) { return (int) (key >> 32); }
    private static int unpackChunkZ(long key) { return (int) key; }

    /**
     * Returns the authoritative client body for one Surface chunk.
     *
     * <p>PASS105-109 made all eight neighbours a publication prerequisite. That is
     * safe for slope/edge context but it also makes the outer render-distance rim
     * impossible to publish on a cold map: the neighbour outside the client square
     * does not exist. PASS110 follows the loaded/loading principle more precisely:
     * a FULL centre chunk is a coherent 16x16 body transaction and may be published;
     * cross-chunk tint/relief dependencies are repaired when neighbouring retained
     * chunks arrive. The exact page remains atomic, so no partially scanned body is
     * ever exposed.</p>
     */
    private static LevelChunk readySurfaceChunk(Level level, int chunkX, int chunkZ) {
        if (level == null) return null;
        var center = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        return center instanceof LevelChunk loaded
                && !(loaded instanceof EmptyLevelChunk) ? loaded : null;
    }

    private static boolean isSurfaceChunkReady(Level level, int chunkX, int chunkZ) {
        return readySurfaceChunk(level, chunkX, chunkZ) != null;
    }

    /** Completion bits written for a placeholder must never suppress live repair. */
    private static boolean hasUsableSurfaceCompletion(Level level,
            int chunkX, int chunkZ) {
        MapManager manager = MapManager.getInstance();
        if (!manager.isChunkSurfaceComplete(chunkX, chunkZ)) return false;
        // Empty End chunks are legitimate void. Open terrain and hard-ceiling
        // dimensions always contain a visible surface in a generated FULL chunk.
        return level != null && level.dimension() == Level.END
                || manager.knownSurfaceColumnCount(chunkX, chunkZ) > 0;
    }

    /**
     * Multiple observation layers call the scanner in one client tick. Xaero has
     * one writer cursor/time slice; SimpleMap previously let LIVE_CRITICAL, loaded
     * halo and minimap viewport each drain the same Surface source independently.
     */
    private boolean claimSurfaceWriterPulse() {
        long now = System.nanoTime();
        if (surfaceWriterLastPulseNanos != 0L
                && now - surfaceWriterLastPulseNanos < SURFACE_WRITER_MIN_PULSE_NANOS) {
            return false;
        }
        surfaceWriterLastPulseNanos = now;
        return true;
    }

    /**
     * Reconciles the loaded halo with a persistent centre-out cursor. Xaero keeps a
     * writer cursor (chunk + tile position) and advances only a bounded amount each
     * frame; it does not rescan the complete render-distance square every 250 ms.
     *
     * <p>The centre-only FULL lookup here is intentional. The expensive 3x3
     * dependency test is performed only when a queued candidate reaches the writer.
     * A frontier chunk that is waiting for neighbours therefore remains queued
     * instead of being forgotten and later resurrected from stale persisted pixels.</p>
     */
    private void seedForegroundSurfaceNeighborhood(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) return;
        int centerChunkX = minecraft.player.getBlockX() >> 4;
        int centerChunkZ = minecraft.player.getBlockZ() >> 4;
        int effectiveRenderDistance = Math.max(2,
                minecraft.options.getEffectiveRenderDistance());
        // PASS110: reconcile the complete square of client-loaded FULL chunks.
        // Edge-dependent styling is repaired separately when neighbours publish;
        // never reserve a permanent one-chunk black rim around the render distance.
        int stableSurfaceRadiusChunks = Math.max(1, effectiveRenderDistance);
        int renderRadiusBlocks = stableSurfaceRadiusChunks * 16;
        long now = System.nanoTime();
        boolean teleportSeed = teleportRecoverySeedPending;
        int chunkRadius = (renderRadiusBlocks + 15) >> 4;
        boolean uninitialized = foregroundSeedChunkX == Integer.MIN_VALUE
                || foregroundSeedChunkZ == Integer.MIN_VALUE;
        boolean radiusChanged = renderRadiusBlocks != foregroundSeedRadiusBlocks;
        boolean anchorOutsideCurrentHalo = !uninitialized
                && (Math.abs(centerChunkX - foregroundSeedChunkX) > chunkRadius
                        || Math.abs(centerChunkZ - foregroundSeedChunkZ) > chunkRadius);
        /*
         * PASS108 / Xaero invariant: progress through one rectangular loading grid
         * with a persistent cursor. Resetting a centre-distance order every time the
         * player crossed a chunk produced the visible stepped circular arcs and could
         * starve the square corners forever during continuous travel. Packet ingress
         * is authoritative for newly arriving chunks, so this reconciliation sweep
         * only reanchors for a discontinuity, a radius change, or after it finishes.
         */
        boolean reanchor = teleportSeed || uninitialized || radiusChanged
                || anchorOutsideCurrentHalo;
        int[] order = getChunkOrder(renderRadiusBlocks);
        if (reanchor) {
            foregroundSeedChunkX = centerChunkX;
            foregroundSeedChunkZ = centerChunkZ;
            foregroundSeedRadiusBlocks = renderRadiusBlocks;
            foregroundSeedCursor = 0;
            foregroundSeedNanos = 0L;
        } else if (foregroundSeedCursor >= order.length) {
            if (now - foregroundSeedNanos < FOREGROUND_SEED_RECONCILE_NANOS) return;
            // A completed Xaero-style rectangular pass may now follow the player.
            foregroundSeedChunkX = centerChunkX;
            foregroundSeedChunkZ = centerChunkZ;
            foregroundSeedRadiusBlocks = renderRadiusBlocks;
            foregroundSeedCursor = 0;
            foregroundSeedNanos = 0L;
        }

        int inspected = 0;
        while (foregroundSeedCursor < order.length
                && inspected < FOREGROUND_SEED_CANDIDATES_PER_PULSE) {
            int packed = order[foregroundSeedCursor++];
            inspected++;
            int chunkX = foregroundSeedChunkX + (short) (packed >>> 16);
            int chunkZ = foregroundSeedChunkZ + (short) packed;
            long key = packChunk(chunkX, chunkZ);
            var center = minecraft.level.getChunk(
                    chunkX, chunkZ, ChunkStatus.FULL, false);
            boolean live = center instanceof LevelChunk liveChunk
                    && !(liveChunk instanceof EmptyLevelChunk);
            if (live) {
                if (!liveSurfacePublishedChunks.contains(key)) {
                    if (teleportSeed) enqueueTeleportRecoveryChunk(chunkX, chunkZ);
                    else enqueueSeedLiveRepairChunk(chunkX, chunkZ);
                }
                continue;
            }
            // Persisted Surface is fallback authority only while the chunk is not
            // live. Never let an old cache snapshot beat a currently loaded chunk.
            if (hasUsableSurfaceCompletion(minecraft.level, chunkX, chunkZ)) {
                publishCompletedSurfaceChunk(chunkX, chunkZ, MapRequestLane.MINIMAP);
            }
        }
        if (foregroundSeedCursor >= order.length) {
            foregroundSeedNanos = now;
            if (teleportSeed) teleportRecoverySeedPending = false;
        }
    }

    /** Seed reconciliation must not restart a transaction already in progress. */
    private void enqueueSeedLiveRepairChunk(int chunkX, int chunkZ) {
        long key = packChunk(chunkX, chunkZ);
        if (liveSurfacePublishedChunks.contains(key)
                || loadedSurfaceChunkSet.contains(key)
                || loadedSurfaceChunks.size() >= LOADED_SURFACE_QUEUE_LIMIT) return;
        forcedSurfaceChunkRefresh.add(key);
        loadedSurfaceChunkCursors.remove(key);
        surfaceChunkStages.remove(key);
        if (!loadedSurfaceChunkSet.add(key)) {
            forcedSurfaceChunkRefresh.remove(key);
            return;
        }
        loadedSurfaceChunks.enqueue(key);
    }

    /**
     * Scans a time-bounded amount of work per tick. Loaded chunks are completed
     * from the player outward; the legacy random-dot reveal path is no longer used.
     */
    public void scanAroundPlayerUniform(Minecraft mc, int maxRadius) {
        scanAroundPlayerUniform(mc, maxRadius, false);
    }

    /**
     * Completes the real client-loaded Surface halo independently from the current
     * map viewport or Cave projection. The minimap is expected to represent every
     * FULL chunk inside Minecraft's render distance even while MapScreen is open.
     *
     * <p>This method never touches Anvil files and never changes Cave mode. It
     * drains the same packet/seed cursors used by normal gameplay, so repeated
     * calls resume unfinished 16x16 transactions instead of restarting them.</p>
     */
    public void maintainLoadedSurfaceHalo(Minecraft mc, int radiusBlocks,
            long budgetNanos) {
        if (MapActivityGate.getInstance().blocksForegroundStreaming()) return;
        if (mc == null || mc.level == null || mc.player == null
                || !MapManager.getInstance().acceptsLiveLevel(mc.level)
                || budgetNanos <= 0L) return;
        if (!claimSurfaceWriterPulse()) return;
        seedForegroundSurfaceNeighborhood(mc);
        long started = System.nanoTime();
        long deadline = started + budgetNanos;
        long urgentDeadline = started + Math.max(120_000L, budgetNanos / 4L);
        scanUrgentLoadedChunks(mc, 0, false, false, urgentDeadline);
        scanQueuedSurfaceChunks(mc, deadline);
        // Packet ingress + the persistent seed cursor own loaded-halo discovery.
        // scanAroundPlayerUniform() remains the legacy fallback lane; running a
        // second nearest-radius traversal here duplicated the hottest world reads.
    }


    /**
     * @param suppressSurfaceFallback true while packet mutation already owns a
     *        substantial authoritative chunk backlog.
     */
    public void scanAroundPlayerUniform(Minecraft mc, int maxRadius,
            boolean suppressSurfaceFallback) {
        if (MapActivityGate.getInstance().blocksForegroundStreaming()) return;
        if (mc.level == null || mc.player == null
                || !MapManager.getInstance().acceptsLiveLevel(mc.level))
            return;

        boolean caveActive = CaveMode.isActive(mc);
        boolean cavePreparing = !caveActive && CaveMode.shouldPrepare(mc);
        synchronizeCaveModeRevision();
        if (caveActive && mc.screen instanceof MapScreen) {
            // PASS148: MapScreen Cave uses the stable saved-world snapshot path.
            // The ordinary player-local live writer resumes after the screen closes;
            // running it here would mutate source fingerprints beneath fullscreen
            // exact builds and recreate the stale-result storm.
            return;
        }
        if (caveActive) {
            int layerY = CaveMode.getLayerY(mc);
            if (!CaveMode.isFullView(mc)) CaveMapManager.getInstance().setActiveLayer(layerY);
            CavePipeline.getInstance().scanAroundPlayer(mc, maxRadius);
            // Display mode must not decide what the durable Surface archive learns.
            // Packet-driven chunks and this small rolling lane continue completing
            // 16x16 surface tiles while Layered/Full Cave is selected.
            if (!suppressSurfaceFallback && claimSurfaceWriterPulse()) {
                seedForegroundSurfaceNeighborhood(mc);
                long surfaceStarted = System.nanoTime();
                long surfaceBudget = surfaceDuringCaveBudget(mc);
                long surfaceDeadline = surfaceStarted + surfaceBudget;
                long surfaceUrgentDeadline = surfaceStarted
                        + foregroundUrgentSlice(surfaceBudget);
                // The player chunk and the chunk ahead of movement are useful on
                // screen immediately. Reserve the remaining slice for the sticky
                // FIFO so Surface history continues while Cave is selected.
                scanUrgentLoadedChunks(mc, 0, false, false, surfaceUrgentDeadline);
                scanQueuedSurfaceChunks(mc, surfaceDeadline);
                // The packet FIFO plus the bounded seed cursor already discover
                // every live Surface chunk. A second nearest-radius traversal did
                // the same 3x3 FULL probes again while Cave was active.
                if (shouldRescanExplored() && System.nanoTime() < surfaceDeadline) {
                    scanNearestChunks(mc, Math.max(16, maxRadius), Integer.MAX_VALUE,
                            0, false, false, surfaceDeadline);
                }
            }
            return;
        }

        // During the Xaero-style AUTO latch, prepare a compact Cave source window
        // but keep Surface as the only visible projection. This makes the eventual
        // switch cheap instead of starting the whole Cave pipeline on the same frame
        // in which the HUD changes projection.
        if (cavePreparing) {
            CavePipeline.getInstance().prewarmAroundPlayer(mc);
        }

        // The mutation writer will complete these chunks transactionally. Running
        // the fallback scanner as well only restarts the same source reads.
        if (suppressSurfaceFallback || !claimSurfaceWriterPulse()) return;

        int centerBlockX = (int) Math.floor(mc.player.getX());
        int centerBlockZ = (int) Math.floor(mc.player.getZ());
        int effectiveRadius = getAdaptiveRadius(mc, maxRadius, viewKey(mc, false, false, 0));
        int radiusSq = effectiveRadius * effectiveRadius;
        seedForegroundSurfaceNeighborhood(mc);
        long scanStarted = System.nanoTime();
        long totalBudget = scanBudget(mc, false);
        long deadline = scanStarted + totalBudget;
        long urgentDeadline = scanStarted + foregroundUrgentSlice(totalBudget);

        // 1. Scan immediate small 13x13 circular region (radius = 6) around the player
        // instantly to capture direct player interactions (building/mining)
        // Direct block/chunk packets are the steady-state dirty authority. The
        // immediate 13x13 reread is retained only for explicit migration/refresh;
        // otherwise it repeatedly consumes the same near-player columns and delays
        // completion of newly loaded chunks ahead of movement.
        if (shouldRescanExplored()) {
            scanImmediate(mc, centerBlockX, centerBlockZ, IMMEDIATE_RADIUS,
                    0, false, false, deadline);
        }
        scanUrgentLoadedChunks(mc, 0, false, false, urgentDeadline);
        // Then finish packet-loaded chunks transactionally. Reserving part of the
        // deadline for the FIFO prevents the current/ahead chunk from monopolising
        // every pulse and leaving one-page gaps behind a fast player.
        // This preserves travel
        // history without letting old render-distance edge packets win over the
        // current player neighbourhood.
        scanQueuedSurfaceChunks(mc, deadline);

        // Packet ingress is the primary live writer and the persistent seed cursor
        // is the reconciliation fallback. Re-running a second centre-out Surface
        // cursor every observation tick was duplicate world/heightmap work and is
        // retained only for an explicit Always Rescan / manual refresh window.
        int samplesTarget = resolveSampleTarget(false);
        if (shouldRescanExplored() && System.nanoTime() < deadline) {
            scanNearestChunks(mc, effectiveRadius, samplesTarget,
                    0, false, false, deadline);
        }

        /*
         * PASS128: do not probe random columns for map lighting. Surface chunk
         * transactions already capture the block-light field, while
         * MapMutationBus/light packet handling schedules deterministic light-only
         * refreshes for actual changes. Random probes made stationary terrain flip
         * dirty pages at arbitrary positions and produced patchy, unstable glow.
         * Xaero likewise keeps written lighting state and updates it from writer
         * events instead of sampling unrelated columns every observation tick.
         */
        if (MapPerformanceGovernor.getInstance().allowBackgroundWork(mc)) {
            CavePipeline.getInstance().scanBackgroundAroundPlayer(mc, effectiveRadius);
        }
    }

    /**
     * Extra render-frame Surface pulse used by {@link MapForegroundWriter}.
     *
     * <p>This deliberately excludes random light refresh, saved-world reads and
     * broad nearest-chunk discovery. It only resumes the player corridor and the
     * bounded packet frontier, so calling it more often than the 20 Hz client tick
     * increases useful travel throughput without multiplying unrelated work.</p>
     */
    public void scanSurfaceForegroundFrame(Minecraft minecraft,
            long budgetNanos) {
        if (budgetNanos <= 0L || minecraft == null || minecraft.level == null
                || minecraft.player == null
                || !MapManager.getInstance().acceptsLiveLevel(minecraft.level)
                || MapActivityGate.getInstance().blocksForegroundStreaming()) {
            return;
        }
        seedForegroundSurfaceNeighborhood(minecraft);
        long started = System.nanoTime();
        long deadline = started + budgetNanos;
        long urgentDeadline = started + foregroundUrgentSlice(budgetNanos);
        scanUrgentLoadedChunks(minecraft, 0, false, false, urgentDeadline);
        if (System.nanoTime() < deadline) {
            scanQueuedSurfaceChunks(minecraft, deadline);
        }
    }

    private static long foregroundUrgentSlice(long totalBudget) {
        if (totalBudget <= 0L) return 0L;
        // 58% urgent / 42% sticky completion. The minimum still guarantees one
        // deadline-checked row on very pressured frames.
        return Math.min(totalBudget, Math.max(140_000L,
                totalBudget * 58L / 100L));
    }

    private long surfaceDuringCaveBudget(Minecraft minecraft) {
        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        long governed = governor.gameplayScanBudgetNanos(false);
        if (governed <= 0L) return 0L;
        if (minecraft != null && minecraft.screen instanceof MapScreen) {
            return Math.min(500_000L, governed);
        }
        return Math.min(SURFACE_DURING_CAVE_BUDGET_NANOS, governed);
    }

    private void synchronizeCaveModeRevision() {
        long revision = CaveMode.getRevision();
        if (observedCaveModeRevision == revision) return;
        observedCaveModeRevision = revision;

        Minecraft minecraft = Minecraft.getInstance();
        boolean active = minecraft.level != null && CaveMode.isActive(minecraft);
        CaveView nextView = active && CaveMode.isFullView(minecraft)
                ? CaveView.FULL : (active ? CaveView.LAYERED : null);
        int nextTopY = active ? CaveMode.getLayerY(minecraft) : Integer.MIN_VALUE;
        int nextBand = nextView == null ? Integer.MIN_VALUE
                : DenseCaveTile.normalizeLayer(nextView, nextTopY);
        boolean retainedLayerTransition = nextView == CaveView.LAYERED
                && observedCaveView == CaveView.LAYERED;

        if (retainedLayerTransition) {
            // Same-band changes patch the retained hierarchy. Cross-band changes use
            // the previous band as a page fallback and warm the new band near-player
            // first; neither path performs a global clear.
            CavePipeline.getInstance().retargetLayer(minecraft, nextTopY);
        } else if (active) {
            // Type switches are cache selections, not destructive refreshes.
            // CaveDisplayScheduler.activateMode() cancels only incompatible
            // unpublished tasks; retained Surface/Layered/FULL data stays warm.
            CavePipeline.getInstance().primeCurrentView(minecraft);
        }
        observedCaveView = nextView;
        observedCaveBandY = nextBand;
        observedCaveProjectionTopY = nextTopY;
    }

    private void scanColumnIfLoaded(Minecraft mc, int blockX, int blockZ) {
        if (mc.level.hasChunk(blockX >> 4, blockZ >> 4)) {
            GeneratedChunkIndex.getInstance().markLive(
                    mc.level, blockX >> 4, blockZ >> 4);
            MapBlockData data = buildBlockData(mc.level, blockX, blockZ);
            if (data != null) {
                // Don't overwrite existing data with empty scan
                long existing = MapManager.getInstance().getPackedBlockData(blockX, blockZ);
                if (MapBlockData.isEmpty(existing) || !data.isEmpty()) {
                    MapManager.getInstance().setBlockData(blockX, blockZ, data,
                            resolveSurfaceTint(mc.level, blockX, blockZ, data));
                }
            }
            int surfaceY = data != null && !data.isEmpty() ? data.topY
                    : getHighestY(mc.level, blockX, blockZ);
            updateSurfaceLight(mc.level, blockX, surfaceY, blockZ);
        }
    }

    private void scanCaveAroundPlayerUniform(Minecraft mc, int maxRadius) {
        int layerY = CaveMode.getLayerY(mc);
        boolean fullView = CaveMode.isFullView(mc);
        if (!fullView) CaveMapManager.getInstance().setActiveLayer(layerY);
        int effectiveRadius = getAdaptiveRadius(mc, maxRadius, viewKey(mc, true, fullView, layerY));
        int centerBlockX = (int) Math.floor(mc.player.getX());
        int centerBlockZ = (int) Math.floor(mc.player.getZ());
        int innerRadius = fullView ? FULL_CAVE_IMMEDIATE_RADIUS : IMMEDIATE_RADIUS;
        long deadline = System.nanoTime() + scanBudget(mc, true);
        scanImmediate(mc, centerBlockX, centerBlockZ, innerRadius,
                layerY, true, fullView, deadline);
        scanUrgentLoadedChunks(mc, layerY, true, fullView, deadline);

        // Downward projections can inspect many blocks per pixel. Keep their total
        // block-state work close to a predictable surface-scan budget.
        int samplesTarget = Math.max(256, resolveSampleTarget(true));
        int projectionMinimum = fullView
                ? mc.level.getMinBuildHeight()
                : CaveMode.getScanMinimum(mc.level, layerY);
        int projectionMaximum = fullView
                ? mc.level.getMaxBuildHeight() - 1
                : CaveMode.getScanMaximum(mc.level, layerY);
        int projectionDepth = projectionMaximum - projectionMinimum + 1;
        long scaledTarget = (long) samplesTarget * BASE_VERTICAL_COST
                / Math.max(1, projectionDepth);
        samplesTarget = (int) Math.min(Integer.MAX_VALUE, Math.max(24L, scaledTarget));
        // Always scan nearest chunks for cave view as well. Random probing removed.
        scanNearestChunks(mc, effectiveRadius, samplesTarget, layerY, true, fullView, deadline);
        // The selected cave scan already archives most touched columns. This pass
        // fills nearby untouched columns so a future distant Top-Y view is complete.
        scanVerticalArchiveAroundPlayer(mc, effectiveRadius,
                System.nanoTime() + verticalArchiveBudget(mc));
    }

    private long verticalArchiveBudget(Minecraft mc) {
        if (mc != null && mc.screen instanceof MapScreen) {
            return MapConfig.fastFullscreenLoading
                    ? VERTICAL_ARCHIVE_MAP_FAST_BUDGET_NANOS
                    : VERTICAL_ARCHIVE_MAP_BALANCED_BUDGET_NANOS;
        }
        return VERTICAL_ARCHIVE_BACKGROUND_BUDGET_NANOS;
    }

    private void scanVerticalArchiveAroundPlayer(Minecraft mc, int radius, long deadline) {
        if (mc == null || mc.level == null || mc.player == null) return;
        scanPriorityVerticalArchiveColumns(mc, deadline);
        if (System.nanoTime() >= deadline) return;
        String key = mc.level.dimension().location() + ":vertical-archive";
        NormalScanState state = verticalArchiveScans.computeIfAbsent(key, ignored -> new NormalScanState());
        int playerChunkX = ((int) Math.floor(mc.player.getX())) >> 4;
        int playerChunkZ = ((int) Math.floor(mc.player.getZ())) >> 4;
        if (state.radius < 0
                || Math.abs(state.anchorChunkX - playerChunkX) > VERTICAL_ARCHIVE_REANCHOR_CHUNKS
                || Math.abs(state.anchorChunkZ - playerChunkZ) > VERTICAL_ARCHIVE_REANCHOR_CHUNKS) {
            state.anchorChunkX = playerChunkX;
            state.anchorChunkZ = playerChunkZ;
            state.chunkIndex = 0;
            state.pixelIndex = 0;
        }
        state.radius = radius;
        int[] order = getChunkOrder(radius);
        int checks = 0;
        while (System.nanoTime() < deadline && checks < VERTICAL_ARCHIVE_MAX_COLUMN_CHECKS
                && order.length > 0) {
            if (state.chunkIndex >= order.length) {
                state.chunkIndex = 0;
                state.pixelIndex = 0;
                state.pass++;
            }
            int packed = order[state.chunkIndex];
            int chunkX = state.anchorChunkX + (short) (packed >>> 16);
            int chunkZ = state.anchorChunkZ + (short) packed;
            if (!isSurfaceChunkReady(mc.level, chunkX, chunkZ)) {
                state.chunkIndex++;
                state.pixelIndex = 0;
                continue;
            }
            int pixel = state.pixelIndex;
            int blockX = (chunkX << 4) + (pixel & 15);
            int blockZ = (chunkZ << 4) + (pixel >>> 4);
            // Do not skip a whole on-disk tile while its async load is still in
            // flight. Keep this cursor and resume next tick once the tile is ready.
            if (!captureVerticalArchiveColumn(mc.level, blockX, blockZ)) break;
            state.pixelIndex++;
            checks++;
            if (state.pixelIndex >= 256) {
                state.pixelIndex = 0;
                state.chunkIndex++;
            }
        }
    }

    private void queueVerticalArchiveRefresh(int blockX, int blockZ) {
        VerticalCaveArchiveManager.getInstance().invalidateColumn(blockX, blockZ);
        long packed = ((long) blockX << 32) ^ (blockZ & 0xFFFFFFFFL);
        if (!verticalArchivePrioritySet.add(packed)) return;
        // A large structure edit should not retain an unbounded list. Dropped
        // entries are still rediscovered by the normal outward archive pass.
        while (verticalArchivePriority.size() >= 4096) {
            Long removed = verticalArchivePriority.pollFirst();
            if (removed != null) verticalArchivePrioritySet.remove(removed);
        }
        verticalArchivePriority.addLast(packed);
    }

    private void scanPriorityVerticalArchiveColumns(Minecraft mc, long deadline) {
        int processed = 0;
        while (processed < 32 && System.nanoTime() < deadline) {
            Long packed = verticalArchivePriority.peekFirst();
            if (packed == null) return;
            int blockX = (int) (packed >> 32);
            int blockZ = (int) (long) packed;
            if (!mc.level.hasChunk(blockX >> 4, blockZ >> 4)) {
                verticalArchivePriority.pollFirst();
                verticalArchivePrioritySet.remove(packed);
                continue;
            }
            // Keep the entry queued while an existing tile is still loading.
            if (!captureVerticalArchiveColumn(mc.level, blockX, blockZ, true)) return;
            verticalArchivePriority.pollFirst();
            verticalArchivePrioritySet.remove(packed);
            processed++;
        }
    }

    /** Captures visible underground floors in one loaded X/Z column. */
    private boolean captureVerticalArchiveColumn(Level level, int blockX, int blockZ) {
        return captureVerticalArchiveColumn(level, blockX, blockZ, false);
    }

    private boolean captureVerticalArchiveColumn(Level level, int blockX, int blockZ, boolean force) {
        VerticalCaveArchiveManager archive = VerticalCaveArchiveManager.getInstance();
        if (!archive.isColumnReady(blockX, blockZ)) return false;
        if (!force && archive.isColumnScanned(blockX, blockZ)) return true;

        int minimumY = level.getMinBuildHeight();
        int maximumY = level.getMaxBuildHeight() - 1;
        int dimensionMiddle = (minimumY + maximumY) / 2;
        int undergroundStartY = findUndergroundSearchStart(level, blockX, blockZ);
        ScanScratch scratch = scanScratch.get();
        BlockPos.MutableBlockPos openPos = scratch.position(0, blockX, undergroundStartY, blockZ);
        BlockPos.MutableBlockPos colorPos = scratch.position(1, blockX, undergroundStartY, blockZ);
        BlockPos.MutableBlockPos runPos = scratch.position(2, blockX, undergroundStartY, blockZ);
        CaveColumnData.Builder builder = scratch.caveColumnBuilder;
        builder.reset();

        boolean reachedMinimumY = true;
        for (int openY = undergroundStartY; openY > minimumY; openY--) {
            long pixel = getCaveSurface(level, openPos, colorPos, openY,
                    dimensionMiddle, true, Integer.MAX_VALUE);
            if (pixel == CAVE_PIXEL_NOT_FOUND || cavePixelColor(pixel) == 0
                    || cavePixelY(pixel) == FullCaveMapManager.NO_SURFACE) continue;

            // Archive directly into one reusable primitive writer. The previous
            // path allocated an ArrayList, one Candidate object per cavity, a
            // Candidate[] and a second builder for every X/Z column. Runs are
            // already discovered highest-first and the scanner deliberately stops
            // at 96, below CaveColumnData.MAX_RUNS, so collection reduction could
            // never contribute useful output here.
            int floorY = cavePixelY(pixel);
            int runTopY = findCavityRunTop(level, runPos, openY, floorY,
                    undergroundStartY);
            builder.add(runTopY, floorY, cavePixelColor(pixel), (byte) 0);

            openPos.setY(openY);
            var fluid = level.getBlockState(openPos).getFluidState();
            if (!fluid.isEmpty()) {
                while (openY - 1 > minimumY) {
                    openPos.setY(openY - 1);
                    var nextFluid = level.getBlockState(openPos).getFluidState();
                    if (nextFluid.isEmpty() || nextFluid.getType() != fluid.getType()) break;
                    openY--;
                }
            } else {
                openY = Math.min(openY, floorY);
            }
            if (builder.count() >= 96) {
                reachedMinimumY = false;
                break;
            }
        }

        return archive.recordColumnData(blockX, blockZ,
                builder.build(minimumY, undergroundStartY, reachedMinimumY));
    }

    private int findCavityRunTop(Level level, BlockPos.MutableBlockPos probe,
            int openY, int floorY, int maximumY) {
        int startY = Math.max(openY, floorY);
        int topY = startY;
        for (int y = startY; y <= maximumY; y++) {
            probe.setY(y);
            if (!isCavityOpenState(level, probe, level.getBlockState(probe))) break;
            topY = y;
        }
        return Math.max(floorY, topY);
    }

    /**
     * Cycles through the live area instead of re-reading the whole 13x13 disc every
     * client tick. Direct edits still converge quickly, while deep cave columns can
     * no longer monopolize a frame.
     */
    private void scanImmediate(Minecraft mc, int centerX, int centerZ, int radius,
            int layerY, boolean cave, boolean fullView, long deadline) {
        String key = viewKey(mc, cave, fullView, layerY) + ":immediate";
        int diameter = radius * 2 + 1;
        int area = diameter * diameter;
        int cursor = immediateCursors.getOrDefault(key, 0);
        int visited = 0;
        int processed = 0;
        int immediateLimit = cave && shouldRescanExplored() ? area : IMMEDIATE_COLUMNS_PER_TICK;
        while (visited < area && processed < immediateLimit
                && System.nanoTime() < deadline) {
            int index = cursor++ % area;
            int dx = index % diameter - radius;
            int dz = index / diameter - radius;
            visited++;
            if (dx * dx + dz * dz > radius * radius) continue;
            int blockX = centerX + dx;
            int blockZ = centerZ + dz;
            if (cave && dx * dx + dz * dz > 1 && !shouldRescanExplored()
                    && isExplored(blockX, blockZ, cave, fullView)) continue;
            scanPixel(mc, blockX, blockZ, layerY, cave, fullView);
            processed++;
        }
        immediateCursors.put(key, cursor % area);
    }



    private void scanQueuedSurfaceChunks(Minecraft mc, long deadline) {
        int playerChunkX = mc.player == null ? 0 : mc.player.getBlockX() >> 4;
        int playerChunkZ = mc.player == null ? 0 : mc.player.getBlockZ() >> 4;
        int readinessDeferrals = 0;
        int maximumReadinessDeferrals = Math.min(16, loadedSurfaceChunks.size());
        while (!loadedSurfaceChunks.isEmpty() && System.nanoTime() < deadline) {
            selectNearestSurfaceQueueHead(playerChunkX, playerChunkZ);
            long key = loadedSurfaceChunks.firstLong();
            // An urgent pass can complete a queued chunk before FIFO ownership
            // reaches it. Keep the primitive queue append-only and discard that
            // stale entry in O(1) here instead of rescanning the chunk.
            if (!loadedSurfaceChunkSet.contains(key)) {
                loadedSurfaceChunks.dequeueLong();
                loadedSurfaceChunkCursors.remove(key);
                surfaceChunkStages.remove(key);
                forcedSurfaceChunkRefresh.remove(key);
                continue;
            }
            int chunkX = unpackChunkX(key);
            int chunkZ = unpackChunkZ(key);
            if (mc.level == null || !isSurfaceChunkReady(mc.level, chunkX, chunkZ)) {
                /* The centre chunk itself is not FULL yet. Rotate interior packet
                 * work while it arrives; stale work outside the current client square
                 * can still be retired. Neighbour absence alone no longer blocks a
                 * valid FULL centre body. */
                int renderDistance = Math.max(2,
                        mc.options.getEffectiveRenderDistance());
                int chunkDistance = Math.max(Math.abs(chunkX - playerChunkX),
                        Math.abs(chunkZ - playerChunkZ));
                if (chunkDistance >= renderDistance) {
                    loadedSurfaceChunks.dequeueLong();
                    loadedSurfaceChunkSet.remove(key);
                    loadedSurfaceChunkCursors.remove(key);
                    surfaceChunkStages.remove(key);
                    forcedSurfaceChunkRefresh.remove(key);
                    continue;
                }
                // Interior neighbours can still be arriving asynchronously. Keep
                // their authority and rotate a bounded number of candidates.
                loadedSurfaceChunks.dequeueLong();
                loadedSurfaceChunks.enqueue(key);
                readinessDeferrals++;
                if (readinessDeferrals >= maximumReadinessDeferrals) break;
                continue;
            }
            readinessDeferrals = 0;
            int cursor = loadedSurfaceChunkCursors.get(key);
            // Packet order begins at the render-distance edge. Drop untouched work
            // that has already fallen behind the traveller, but always finish a
            // transaction once one row has been sampled so visible chunks remain
            // atomic rather than becoming horizontal stripes.
            int queueRadius = Math.max(SURFACE_QUEUE_STALE_RADIUS_CHUNKS,
                    mc.options.getEffectiveRenderDistance());
            if (cursor == 0
                    && (Math.abs(chunkX - playerChunkX) > queueRadius
                            || Math.abs(chunkZ - playerChunkZ) > queueRadius)) {
                loadedSurfaceChunks.dequeueLong();
                loadedSurfaceChunkSet.remove(key);
                loadedSurfaceChunkCursors.remove(key);
                surfaceChunkStages.remove(key);
                forcedSurfaceChunkRefresh.remove(key);
                continue;
            }
            if (!forcedSurfaceChunkRefresh.contains(key)
                    && !shouldRescanExplored()
                    && hasUsableSurfaceCompletion(mc.level, chunkX, chunkZ)) {
                publishCompletedSurfaceChunk(chunkX, chunkZ, MapRequestLane.MINIMAP);
                loadedSurfaceChunks.dequeueLong();
                loadedSurfaceChunkSet.remove(key);
                loadedSurfaceChunkCursors.remove(key);
                surfaceChunkStages.remove(key);
                forcedSurfaceChunkRefresh.remove(key);
                continue;
            }
            int next = scanSurfaceChunkSlice(mc.level, chunkX, chunkZ, cursor,
                    SURFACE_CHUNK_SLICE, deadline, MapRequestLane.MINIMAP);
            if (next >= 256) {
                loadedSurfaceChunks.dequeueLong();
                loadedSurfaceChunkSet.remove(key);
                loadedSurfaceChunkCursors.remove(key);
                forcedSurfaceChunkRefresh.remove(key);
            } else {
                loadedSurfaceChunkCursors.put(key, next);
                // Preserve FIFO ownership until this transaction is complete. This
                // avoids 25/50/75%-complete pages spread across hundreds of chunks.
                if (next == cursor) return;
            }
        }
    }

    /**
     * Packet order is not spatial order, but spatial ordering must not become an
     * O(queue) operation before every 16x16 transaction. Xaero advances persistent
     * writer cursors and only changes the loaded/loading window when the viewport
     * changes. Re-score this bounded FIFO only when the player crosses a chunk or a
     * short cadence expires; urgent player/ahead chunks already bypass this queue.
     */
    private void selectNearestSurfaceQueueHead(int playerChunkX,
            int playerChunkZ) {
        int inspect = loadedSurfaceChunks.size();
        if (inspect <= 1) return;
        long currentHead = loadedSurfaceChunks.firstLong();
        if (loadedSurfaceChunkSet.contains(currentHead)
                && loadedSurfaceChunkCursors.get(currentHead) > 0) {
            return;
        }
        long now = System.nanoTime();
        if (surfaceQueuePriorityChunkX == playerChunkX
                && surfaceQueuePriorityChunkZ == playerChunkZ
                && now - surfaceQueuePriorityNanos
                        < SURFACE_QUEUE_REPRIORITIZE_NANOS) {
            return;
        }
        surfaceQueuePriorityChunkX = playerChunkX;
        surfaceQueuePriorityChunkZ = playerChunkZ;
        surfaceQueuePriorityNanos = now;
        long selected = 0L;
        long selectedScore = Long.MAX_VALUE;
        int retained = 0;
        for (int index = 0; index < inspect; index++) {
            long key = loadedSurfaceChunks.dequeueLong();
            if (!loadedSurfaceChunkSet.contains(key)) {
                loadedSurfaceChunkCursors.remove(key);
                surfaceChunkStages.remove(key);
                continue;
            }
            loadedSurfaceChunks.enqueue(key);
            retained++;
            int chunkX = unpackChunkX(key);
            int chunkZ = unpackChunkZ(key);
            long dx = chunkX - (long) playerChunkX;
            long dz = chunkZ - (long) playerChunkZ;
            /*
             * Square/Chebyshev priority, never Euclidean priority. The old dx*dx
             * + dz*dz score literally turned packet publication into circular
             * wavefronts around the player. Minecraft/Xaero coverage is managed as
             * a rectangular chunk grid; urgent current/ahead chunks already bypass
             * this queue, so the FIFO fallback should preserve square shells.
             */
            long ring = Math.max(Math.abs(dx), Math.abs(dz));
            long tie = (Math.abs(dz) << 16) + Math.abs(dx);
            long score = ring * 1_000_000L + tie;
            int cursor = loadedSurfaceChunkCursors.get(key);
            if (cursor > 0) score -= 1_000_000L + cursor * 1_000L;
            if (score < selectedScore) {
                selectedScore = score;
                selected = key;
            }
        }
        if (retained == 0 || !loadedSurfaceChunkSet.contains(selected)) return;
        int rotations = loadedSurfaceChunks.size();
        while (rotations-- > 0 && loadedSurfaceChunks.firstLong() != selected) {
            loadedSurfaceChunks.enqueue(loadedSurfaceChunks.dequeueLong());
        }
    }

    /** Completes the player chunk first, then two chunks in the movement direction. */
    private void scanUrgentLoadedChunks(Minecraft mc, int layerY, boolean cave,
            boolean fullView, long deadline) {
        int playerChunkX = ((int) Math.floor(mc.player.getX())) >> 4;
        int playerChunkZ = ((int) Math.floor(mc.player.getZ())) >> 4;
        double velocityX = mc.player.getDeltaMovement().x;
        double velocityZ = mc.player.getDeltaMovement().z;
        int directionX = (int) Math.signum(velocityX);
        int directionZ = (int) Math.signum(velocityZ);
        // Prefer the dominant axis. A diagonal sign/sign target is often a corner
        // chunk that the player never enters and leaves two orthogonal gaps.
        if (Math.abs(velocityX) > Math.abs(velocityZ) * 1.5D) directionZ = 0;
        else if (Math.abs(velocityZ) > Math.abs(velocityX) * 1.5D) directionX = 0;
        boolean currentComplete = scanUrgentChunk(mc, playerChunkX, playerChunkZ,
                layerY, cave, fullView, deadline);
        if (!cave && currentComplete && System.nanoTime() < deadline
                && (directionX != 0 || directionZ != 0)) {
            boolean firstAheadComplete = scanUrgentChunk(mc,
                    playerChunkX + directionX, playerChunkZ + directionZ,
                    layerY, false, false, deadline);
            if (firstAheadComplete && System.nanoTime() < deadline) {
                scanUrgentChunk(mc, playerChunkX + directionX * 2,
                        playerChunkZ + directionZ * 2,
                        layerY, false, false, deadline);
            }
        }
    }

    private boolean scanUrgentChunk(Minecraft mc, int chunkX, int chunkZ, int layerY,
            boolean cave, boolean fullView, long deadline) {
        if (!isSurfaceChunkReady(mc.level, chunkX, chunkZ)) return false;
        String key = viewKey(mc, cave, fullView, layerY) + ":urgent:" + chunkX + ',' + chunkZ;
        if (!cave) {
            long chunkKey = packChunk(chunkX, chunkZ);
            // Surface has one cursor authority shared by packet FIFO, player/ahead
            // priority and later nearest-chunk traversal. The previous independent
            // urgent cursor repeatedly rescanned rows 0..63 before FIFO resumed,
            // roughly halving useful travel throughput while preserving the same
            // CPU cost.
            int cursor = loadedSurfaceChunkCursors.get(chunkKey);
            if (!forcedSurfaceChunkRefresh.contains(chunkKey)
                    && !shouldRescanExplored()
                    && hasUsableSurfaceCompletion(mc.level, chunkX, chunkZ)) {
                publishCompletedSurfaceChunk(chunkX, chunkZ, MapRequestLane.MINIMAP);
                loadedSurfaceChunkCursors.remove(chunkKey);
                surfaceChunkStages.remove(chunkKey);
                loadedSurfaceChunkSet.remove(chunkKey);
                forcedSurfaceChunkRefresh.remove(chunkKey);
                return true;
            }
            int next = scanSurfaceChunkSlice(mc.level, chunkX, chunkZ,
                    cursor, 256 - cursor, deadline);
            if (next >= 256) {
                loadedSurfaceChunkCursors.remove(chunkKey);
                surfaceChunkStages.remove(chunkKey);
                loadedSurfaceChunkSet.remove(chunkKey);
                forcedSurfaceChunkRefresh.remove(chunkKey);
                return true;
            } else if (next > cursor) {
                loadedSurfaceChunkCursors.put(chunkKey, next);
            }
            return false;
        }
        int cursor = urgentChunkCursors.getOrDefault(key, 0);
        int maximum = shouldRescanExplored() ? 256 : 96;
        int processed = 0;
        int visited = 0;
        while (visited < 256 && processed < maximum && System.nanoTime() < deadline) {
            int pixel = cursor++ & 255;
            visited++;
            int blockX = (chunkX << 4) + (pixel & 15);
            int blockZ = (chunkZ << 4) + (pixel >>> 4);
            if (!shouldRescanExplored() && isExplored(blockX, blockZ, true, fullView)) continue;
            scanPixel(mc, blockX, blockZ, layerY, true, fullView);
            processed++;
        }
        urgentChunkCursors.put(key, cursor & 255);
        if (urgentChunkCursors.size() > 256) urgentChunkCursors.clear();
        return false;
    }

    /**
     * Cave-only visible viewport request. This deliberately bypasses the Surface
     * writer pulse in {@link #scanVisibleArea}: the Cave pipeline must still run its
     * world-save/Anvil request while Cave owns the screen, but hidden Surface work
     * must not be resurrected just to reach that request path.
     *
     * <p>PASS147 accidentally skipped {@code scanVisibleArea()} whenever Cave was
     * active in {@code MapViewportCoordinator}. That also skipped
     * {@code CavePipeline.scanVisibleArea()}, which is the only normal caller that
     * drives {@code CaveWorldSaveReader -> WorldSaveProjectionPipeline}. The result
     * was exactly the 14:07 trace: no fullscreen snapshot event, no coherent Cave
     * source batch, and visible pages filling only from the much slower live archive
     * scanner. Keep the saved-world reader alive without bringing Surface churn back.</p>
     */
    public void scanVisibleCaveArea(Minecraft mc, double minX, double maxX,
            double minZ, double maxZ, float scale, double focusX, double focusZ,
            MapRequestLane lane) {
        if (MapActivityGate.getInstance().blocksForegroundStreaming()) return;
        if (mc == null || mc.level == null || mc.player == null
                || !MapManager.getInstance().acceptsLiveLevel(mc.level)
                || !CaveMode.isActive(mc)) return;
        synchronizeCaveModeRevision();
        int layerY = CaveMode.getLayerY(mc);
        if (!CaveMode.isFullView(mc)) {
            CaveMapManager.getInstance().setActiveLayer(layerY);
        }
        CavePipeline.getInstance().scanVisibleArea(mc, minX, maxX, minZ, maxZ,
                scale, focusX, focusZ, lane);
    }

    /**
     * Called by the full-screen map. It progressively fills every client-loaded
     * chunk intersecting the viewport, rather than only a circle around the player.
     */
    public void scanVisibleArea(Minecraft mc, double minX, double maxX,
            double minZ, double maxZ) {
        scanVisibleArea(mc, minX, maxX, minZ, maxZ, 1.0f,
                (minX + maxX) * 0.5, (minZ + maxZ) * 0.5,
                MapRequestLane.FULLSCREEN);
    }

    public void scanVisibleArea(Minecraft mc, double minX, double maxX,
            double minZ, double maxZ, float scale) {
        scanVisibleArea(mc, minX, maxX, minZ, maxZ, scale,
                (minX + maxX) * 0.5, (minZ + maxZ) * 0.5,
                MapRequestLane.FULLSCREEN);
    }

    public void scanVisibleArea(Minecraft mc, double minX, double maxX,
            double minZ, double maxZ, float scale, MapRequestLane lane) {
        scanVisibleArea(mc, minX, maxX, minZ, maxZ, scale,
                (minX + maxX) * 0.5, (minZ + maxZ) * 0.5, lane);
    }

    public void scanVisibleArea(Minecraft mc, double minX, double maxX,
            double minZ, double maxZ, float scale,
            double focusX, double focusZ, MapRequestLane lane) {
        if (MapActivityGate.getInstance().blocksForegroundStreaming()) return;
        if (mc == null || mc.level == null || mc.player == null
                || !MapManager.getInstance().acceptsLiveLevel(mc.level)) return;
        boolean cave = CaveMode.isActive(mc);
        synchronizeCaveModeRevision();
        boolean fullView = cave && CaveMode.isFullView(mc);
        int layerY = cave ? CaveMode.getLayerY(mc) : 0;
        if (cave && !fullView) CaveMapManager.getInstance().setActiveLayer(layerY);
        if (cave) {
            CavePipeline.getInstance().scanVisibleArea(mc, minX, maxX, minZ, maxZ,
                    scale, focusX, focusZ, lane);
            // MapScreen suppresses the normal LIVE_CRITICAL lane. Keep a tiny
            // player-local Surface writer alive here as well, otherwise leaving a
            // Cave view open makes the Surface archive fall permanently behind the
            // same already-generated Minecraft chunks.
            if (claimSurfaceWriterPulse()) {
                long surfaceStarted = System.nanoTime();
                long surfaceBudget = surfaceDuringCaveBudget(mc);
                long surfaceDeadline = surfaceStarted + surfaceBudget;
                seedForegroundSurfaceNeighborhood(mc);
                scanUrgentLoadedChunks(mc, 0, false, false,
                        surfaceStarted + foregroundUrgentSlice(surfaceBudget));
                scanQueuedSurfaceChunks(mc, surfaceDeadline);
            }
            return;
        }

        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        // Surface viewport consumers never become another terrain writer. The
        // retained repository + page demand decide what to draw; all live Minecraft
        // reads flow through the single packet/seed writer above. This mirrors
        // Xaero's MinimapWriter/MapWriter cursor ownership and removes a second
        // pixel cursor that used to race the packet FIFO at frontier chunks.
        if (claimSurfaceWriterPulse()) {
            seedForegroundSurfaceNeighborhood(mc);
            long started = System.nanoTime();
            long budget = effectiveLane == MapRequestLane.FULLSCREEN
                    ? Math.min(MAP_SCREEN_BALANCED_BUDGET_NANOS,
                            MapPerformanceGovernor.getInstance()
                                    .fullscreenScanBudgetNanos(scale,
                                            MapConfig.fastFullscreenLoading))
                    : scanBudget(mc, false);
            if (budget > 0L) {
                long deadline = started + budget;
                scanUrgentLoadedChunks(mc, 0, false, false,
                        started + foregroundUrgentSlice(budget));
                scanQueuedSurfaceChunks(mc, deadline);
            }
        }
    }

    /**
     * Activates a selected Top-Y cache without destroying its warm image. Existing
     * exact/archive data streams through the normal viewport queue; only a genuinely
     * uncached player region receives a tiny one-chunk prime.
     */
    public void requestImmediateCaveLayerRefresh(Minecraft mc) {
        if (MapActivityGate.getInstance().blocksMapWork()) return;
        if (mc == null || mc.level == null || mc.player == null) return;
        if (!mc.isSameThread()) {
            mc.execute(() -> requestImmediateCaveLayerRefresh(mc));
            return;
        }
        if (!CaveMode.isActive(mc)) {
            requestRefresh(mc);
            return;
        }
        if (!CaveMode.isFullView(mc)) {
            CaveMapManager.getInstance().setActiveLayer(CaveMode.getLayerY(mc));
        }
        observedCaveModeRevision = CaveMode.getRevision();
        int topY = CaveMode.getLayerY(mc);
        CaveView view = CaveMode.isFullView(mc) ? CaveView.FULL : CaveView.LAYERED;
        int bandY = DenseCaveTile.normalizeLayer(view, topY);
        boolean retainedLayerTransition = view == CaveView.LAYERED
                && observedCaveView == CaveView.LAYERED;
        if (retainedLayerTransition) {
            CavePipeline.getInstance().retargetLayer(mc, topY);
        } else {
            // Preserve the warm cache from the previous Cave type. The selected
            // mode is primed centre-out by the bounded foreground writer instead
            // of synchronously clearing queues and rebuilding the whole viewport.
            CavePipeline.getInstance().primeCurrentView(mc);
        }
        observedCaveView = view;
        observedCaveBandY = bandY;
        observedCaveProjectionTopY = topY;
        MapViewportCoordinator.getInstance().onLayerChanged();
    }

    /** Refresh now reuses the normal chunk pipeline instead of a separate circle scan. */
    public void requestRefresh(Minecraft mc) {
        if (MapActivityGate.getInstance().blocksMapWork()) return;
        if (!MapManager.getInstance().isViewingLiveDimension()) return;
        forcedRescanUntilNanos = System.nanoTime() + 2_000_000_000L;
        normalScans.clear();
        viewportScans.clear();
        urgentChunkCursors.clear();
        surfaceChunkStages.clear();
        liveSurfacePublishedChunks.clear();
        foregroundSeedCursor = 0;
        foregroundSeedNanos = 0L;
        if (mc != null && mc.level != null && mc.player != null) {
            int renderDistance = mc.options.renderDistance().get();
            int radius = Math.max(16, renderDistance * 16);
            if (CaveMode.isActive(mc)) CavePipeline.getInstance().requestRefresh(mc, radius);
            else scanAroundPlayerUniform(mc, radius);
        }
    }

    private void scanNearestChunks(Minecraft mc, int radius, int samplesTarget,
            int layerY, boolean cave, boolean fullView, long deadline) {
        int playerX = (int) Math.floor(mc.player.getX());
        int playerZ = (int) Math.floor(mc.player.getZ());
        String key = viewKey(mc, cave, fullView, layerY);
        NormalScanState state = normalScans.computeIfAbsent(key, ignored -> new NormalScanState());
        long deltaX = (long) playerX - state.anchorX;
        long deltaZ = (long) playerZ - state.anchorZ;
        int playerChunkX = playerX >> 4;
        int playerChunkZ = playerZ >> 4;
        boolean reanchor = state.radius < 0;
        if (!reanchor && cave) {
            reanchor = deltaX * deltaX + deltaZ * deltaZ
                    > NORMAL_REANCHOR_DISTANCE * NORMAL_REANCHOR_DISTANCE;
        } else if (!reanchor) {
            reanchor = Math.abs(playerChunkX - state.anchorChunkX)
                    > SURFACE_REANCHOR_CHUNKS
                    || Math.abs(playerChunkZ - state.anchorChunkZ)
                    > SURFACE_REANCHOR_CHUNKS;
        }
        if (reanchor && !cave && state.pixelIndex != 0) {
            // Finish the current 16x16 transaction before moving the traversal
            // anchor. Abandoning a 64/128/192-column tile on every fast reanchor
            // was another reason Surface lagged behind generated chunks.
            reanchor = false;
        }
        if (reanchor) {
            state.anchorX = playerX;
            state.anchorZ = playerZ;
            state.anchorChunkX = playerChunkX;
            state.anchorChunkZ = playerChunkZ;
            state.chunkIndex = 0;
            state.pixelIndex = 0;
        }
        // Radius can contract while sprinting and expand again while idle without
        // restarting the nearest-chunk cursor on every small animation step.
        state.radius = radius;

        int[] order = getChunkOrder(radius);
        int processed = 0;
        int inspected = 0;
        int inspectionLimit = samplesTarget == Integer.MAX_VALUE
                ? Integer.MAX_VALUE : Math.max(samplesTarget * 16, 512);
        while (processed < samplesTarget && inspected < inspectionLimit && order.length > 0
                && System.nanoTime() < deadline) {
            if (state.chunkIndex >= order.length) {
                state.chunkIndex = 0;
                state.pixelIndex = 0;
                state.pass++;
            }
            int packed = order[state.chunkIndex];
            int chunkDx = (short) (packed >>> 16);
            int chunkDz = (short) packed;
            int chunkX = state.anchorChunkX + chunkDx;
            int chunkZ = state.anchorChunkZ + chunkDz;
            if ((!cave && !isSurfaceChunkReady(mc.level, chunkX, chunkZ))
                    || (cave && !mc.level.hasChunk(chunkX, chunkZ))) {
                state.chunkIndex++;
                state.pixelIndex = 0;
                inspected += 16;
                continue;
            }

            if (!cave) {
                preloadKnownRegionForChunk(chunkX << 4, chunkZ << 4,
                        false, false);
                long chunkKey = packChunk(chunkX, chunkZ);
                if (loadedSurfaceChunkSet.contains(chunkKey)
                        || loadedSurfaceChunkCursors.containsKey(chunkKey)) {
                    // Packet/urgent travel ownership already carries the persistent
                    // cursor for this chunk. A second nearest-scan cursor would
                    // duplicate rows and publish no additional map coverage.
                    state.chunkIndex++;
                    state.pixelIndex = 0;
                    inspected += 16;
                    continue;
                }
                if (state.pixelIndex == 0 && !shouldRescanExplored()
                        && hasUsableSurfaceCompletion(mc.level, chunkX, chunkZ)) {
                    publishCompletedSurfaceChunk(chunkX, chunkZ,
                            MapRequestLane.MINIMAP);
                    state.chunkIndex++;
                    inspected += 256;
                    continue;
                }
                int requested = samplesTarget == Integer.MAX_VALUE
                        ? SURFACE_CHUNK_SLICE
                        : Math.min(SURFACE_CHUNK_SLICE,
                                Math.max(1, samplesTarget - processed));
                int previous = state.pixelIndex;
                int next = scanSurfaceChunkSlice(mc.level, chunkX, chunkZ,
                        previous, requested, deadline);
                int advanced = Math.max(0, next - previous);
                if (advanced == 0) {
                    state.chunkIndex++;
                    state.pixelIndex = 0;
                    inspected += 16;
                    continue;
                }
                state.pixelIndex = next;
                if (state.pixelIndex >= 256) {
                    state.pixelIndex = 0;
                    state.chunkIndex++;
                }
                processed += advanced;
                inspected += advanced;
                continue;
            }

            int pixel = state.pixelIndex++;
            if (state.pixelIndex >= 256) {
                state.pixelIndex = 0;
                state.chunkIndex++;
            }
            inspected++;
            int blockX = (chunkX << 4) + (pixel & 15);
            int blockZ = (chunkZ << 4) + (pixel >>> 4);
            preloadKnownRegionForChunk(blockX, blockZ, true, fullView);
            if (isExplored(blockX, blockZ, true, fullView)
                    && !shouldRescanExplored()) continue;
            scanPixel(mc, blockX, blockZ, layerY, true, fullView);
            processed++;
        }
    }

    private boolean isExplored(int blockX, int blockZ, boolean cave, boolean fullView) {
        if (!cave) {
            long data = MapManager.getInstance().getPackedBlockData(blockX, blockZ);
            if (MapBlockData.isEmpty(data)) return false;
            // Version-1 water pixels had no floor height and stored the water block
            // itself. Surface v1/v2 pixels also lack an exact BlockColors result.
            // Treat both as incomplete so loaded old maps migrate automatically.
            if (MapBlockData.isFluid(data) && !MapBlockData.isGlowing(data)
                    && MapBlockData.floorY(data) == MapBlockData.topY(data)) return false;
            return MapManager.getInstance().getSurfaceTint(blockX, blockZ)
                    != SurfaceTintData.UNKNOWN;
        }
        int rx = blockX >> 9;
        int rz = blockZ >> 9;
        if (fullView) {
            FullCaveMapManager.FullRegion region = FullCaveMapManager.getInstance().getRegion(rx, rz, false);
            return region != null && region.isLoaded()
                    && region.getColor(blockX & 511, blockZ & 511) != 0;
        }
        CaveMapManager manager = CaveMapManager.getInstance();
        // While fullscreen, an already uploaded exact-layer texture is authoritative.
        // Do not reload/rescan it simply because the active CPU layer cache was swapped.
        if (Minecraft.getInstance().screen instanceof MapScreen
                && manager.hasRegionFile(rx, rz)
                && CaveTextureManager.getInstance().peekRegionTexture(
                        manager.getActiveLayerY(), rx, rz) != null) {
            return true;
        }
        CaveRegion region = manager.getRegion(rx, rz, false);
        if (region == null || !region.isLoaded()) return false;
        int px = blockX & 511;
        int pz = blockZ & 511;
        // Exact/cache pixels are already valid selected-Y results and must not be
        // scanned again merely because the user switched back to this layer. Empty
        // live pixels are also complete observations, so they are not retried every
        // frame and cannot form a repeated black chunk around the player.
        return region.hasExactSnapshot()
                || region.isLivePixel(px, pz)
                || region.getColor(px, pz) != 0;
    }

    private void scanPixel(Minecraft mc, int blockX, int blockZ,
            int layerY, boolean cave, boolean fullView) {
        if (cave) scanCavePixelIfLoaded(mc.level, blockX, layerY, blockZ, fullView);
        else scanColumnIfLoaded(mc, blockX, blockZ);
    }

    private int getAdaptiveRadius(Minecraft mc, int configuredRadius, String key) {
        // The previous speed-based contraction was the main reason flying into a
        // loaded chunk produced an empty minimap edge. CPU time is already bounded
        // by deadlines, so keep the full configured chunk radius at every speed.
        movementStates.computeIfAbsent(key, ignored -> new MovementState()).factor = 1.0f;
        return Math.max(16, configuredRadius);
    }

    private long scanBudget(Minecraft mc, boolean cave) {
        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        if (mc.screen instanceof MapScreen) {
            long governed = governor.fullscreenScanBudgetNanos(
                    1.0f, MapConfig.fastFullscreenLoading);
            return governed <= 0L ? 0L
                    : Math.min(MAP_SCREEN_SCAN_BUDGET_NANOS, governed);
        }
        long governed = governor.gameplayScanBudgetNanos(cave);
        if (governed <= 0L) return 0L;
        long base = cave ? CAVE_SCAN_BUDGET_NANOS : SURFACE_SCAN_BUDGET_NANOS;
        // The governor budget is the aggregate client-thread deadline. Multiplying
        // it by four silently turned a nominal 0.7-1.5 ms slice into 2.8-6 ms and
        // made a 125 FPS (8 ms) target impossible before rendering even began.
        return Math.min(base, Math.max(80_000L, governed));
    }

    private int resolveSampleTarget(boolean cave) {
        int configured = Math.max(1_000, MapConfig.scanPointsPerTick);
        // 100k is the new AUTO/MAX setting. The nano deadline, not a random-dot
        // counter, becomes the real safety limit.
        if (configured >= 100_000) return Integer.MAX_VALUE;
        return cave ? Math.max(256, configured / 2) : configured;
    }

    private boolean shouldRescanExplored() {
        return MapConfig.alwaysRescanExplored || System.nanoTime() < forcedRescanUntilNanos;
    }

    private String viewKey(Minecraft mc, boolean cave, boolean fullView, int layerY) {
        String dimension = mc.level.dimension().location().toString();
        return dimension + ":" + (cave ? (fullView ? "full" : "layer:" + layerY) : "surface");
    }

    private static final class NormalScanState {
        private int anchorX;
        private int anchorZ;
        private int anchorChunkX;
        private int anchorChunkZ;
        private int radius = -1;
        private int chunkIndex;
        private int pixelIndex;
        private long pass;
    }

    private static final class MovementState {
        private float factor = 1.0f;
    }

    private static final class ViewportScanState {
        private int minChunkX;
        private int maxChunkX;
        private int minChunkZ;
        private int maxChunkZ;
        private int focusChunkX;
        private int focusChunkZ;
        private boolean stableFullscreen;
        private int chunkCursor;
        private int pixelCursor;
        private int[] chunkOrder = new int[0];

        private boolean matchesShape(int minX, int maxX, int minZ, int maxZ,
                boolean stable) {
            return stableFullscreen == stable
                    && maxChunkX - minChunkX == maxX - minX
                    && maxChunkZ - minChunkZ == maxZ - minZ;
        }

        private void updateBounds(int minX, int maxX, int minZ, int maxZ,
                int focusX, int focusZ) {
            minChunkX = minX;
            maxChunkX = maxX;
            minChunkZ = minZ;
            maxChunkZ = maxZ;
            focusChunkX = focusX;
            focusChunkZ = focusZ;
        }

        private boolean sameBounds(int minX, int maxX, int minZ, int maxZ) {
            return minChunkX == minX && maxChunkX == maxX
                    && minChunkZ == minZ && maxChunkZ == maxZ;
        }

        private void shiftBoundsDeltaFirst(int minX, int maxX,
                int minZ, int maxZ, int focusX, int focusZ) {
            int oldMinX = minChunkX;
            int oldMaxX = maxChunkX;
            int oldMinZ = minChunkZ;
            int oldMaxZ = maxChunkZ;
            int oldWidth = Math.max(1, oldMaxX - oldMinX + 1);
            int[] oldOrder = chunkOrder;
            updateBounds(minX, maxX, minZ, maxZ, focusX, focusZ);
            chunkCursor = 0;
            pixelCursor = 0;

            int width = Math.max(1, maxX - minX + 1);
            int height = Math.max(1, maxZ - minZ + 1);
            int count = width * height;
            int[] reordered = new int[count];
            int cursor = 0;

            // Newly exposed chunks are the only urgent work. Fill them directly
            // into a primitive array: the previous implementation boxed every
            // chunk index into two lists and sorted them on each chunk boundary,
            // producing a GC spike precisely while the world was loading.
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (x >= oldMinX && x <= oldMaxX
                            && z >= oldMinZ && z <= oldMaxZ) continue;
                    reordered[cursor++] = (z - minZ) * width + (x - minX);
                }
            }

            // Preserve the previous centre/ring order for the overlap, remapped to
            // the new bounds. No sort and no temporary object graph are required.
            for (int oldIndex : oldOrder) {
                int x = oldMinX + oldIndex % oldWidth;
                int z = oldMinZ + oldIndex / oldWidth;
                if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
                reordered[cursor++] = (z - minZ) * width + (x - minX);
            }
            // A large discontinuous move has no overlap; the first pass already
            // fills everything. This guard also makes recovery robust if a legacy
            // state ever carried an incomplete order.
            if (cursor < count) {
                boolean[] present = new boolean[count];
                for (int i = 0; i < cursor; i++) present[reordered[i]] = true;
                for (int index = 0; index < count; index++) {
                    if (!present[index]) reordered[cursor++] = index;
                }
            }
            chunkOrder = reordered;
        }

        private void reset(int minX, int maxX, int minZ, int maxZ,
                int focusX, int focusZ, boolean stable) {
            minChunkX = minX;
            maxChunkX = maxX;
            minChunkZ = minZ;
            maxChunkZ = maxZ;
            focusChunkX = focusX;
            focusChunkZ = focusZ;
            stableFullscreen = stable;
            chunkCursor = 0;
            pixelCursor = 0;
            int width = Math.max(1, maxX - minX + 1);
            int height = Math.max(1, maxZ - minZ + 1);
            int count = width * height;
            chunkOrder = new int[count];
            int centerX = stable ? (minX + maxX) >> 1 : focusX;
            int centerZ = stable ? (minZ + maxZ) >> 1 : focusZ;
            int cursor = 0;
            int maximumRing = Math.max(
                    Math.max(Math.abs(centerX - minX), Math.abs(maxX - centerX)),
                    Math.max(Math.abs(centerZ - minZ), Math.abs(maxZ - centerZ)));
            for (int ring = 0; ring <= maximumRing; ring++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    for (int dx = -ring; dx <= ring; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        int x = centerX + dx;
                        int z = centerZ + dz;
                        if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
                        chunkOrder[cursor++] = (z - minZ) * width + (x - minX);
                    }
                }
            }
        }
    }

    private int[] getChunkOrder(int radius) {
        int chunkRadius = (radius + 15) >> 4;
        return chunkOrders.computeIfAbsent(chunkRadius, requested -> {
            int side = requested * 2 + 1;
            int[] result = new int[side * side];
            int cursor = 0;
            /*
             * Deterministic rectangular/serpentine traversal, matching Xaero's
             * persistent updateChunkX/updateChunkZ grid semantics. Do not distance
             * sort this list: Euclidean ordering creates circular wavefronts while
             * Minecraft's loaded view and the map's coverage contract are square.
             */
            for (int dz = -requested; dz <= requested; dz++) {
                if (((dz + requested) & 1) == 0) {
                    for (int dx = -requested; dx <= requested; dx++) {
                        result[cursor++] = ((dx & 0xFFFF) << 16) | (dz & 0xFFFF);
                    }
                } else {
                    for (int dx = requested; dx >= -requested; dx--) {
                        result[cursor++] = ((dx & 0xFFFF) << 16) | (dz & 0xFFFF);
                    }
                }
            }
            return result;
        });
    }

    private boolean isKnownSurfaceRegion(int blockX, int blockZ) {
        int rx = blockX >> 9;
        int rz = blockZ >> 9;
        return MapManager.getInstance().hasRegionFile(rx, rz)
                || MapManager.getInstance().isRegionLoadedInCache(rx, rz);
    }

    private void preloadKnownRegionForChunk(int blockX, int blockZ, boolean cave, boolean fullView) {
        int rx = blockX >> 9;
        int rz = blockZ >> 9;
        // Load only the exact region currently being scanned. The previous 3x3
        // preload multiplied every request by nine and caused I/O/eviction thrash.
        if (!cave) {
            MapManager manager = MapManager.getInstance();
            if (manager.hasRegionFile(rx, rz) && !manager.isRegionLoadedInCache(rx, rz)) {
                MapProcessor.getInstance().enqueueSurfaceLoad(rx, rz, 10_000);
            }
        } else if (fullView) {
            FullCaveMapManager manager = FullCaveMapManager.getInstance();
            if (manager.hasRegionFile(rx, rz) && !manager.isRegionLoaded(rx, rz)) {
                MapProcessor.getInstance().enqueueFullCaveLoad(rx, rz, 10_000);
            }
        } else {
            CaveMapManager manager = CaveMapManager.getInstance();
            boolean warmExactTexture = Minecraft.getInstance().screen instanceof MapScreen
                    && manager.hasRegionFile(rx, rz)
                    && CaveTextureManager.getInstance().peekRegionTexture(
                            manager.getActiveLayerY(), rx, rz) != null;
            boolean hasSource = manager.hasRegionFile(rx, rz)
                    || VerticalCaveArchiveManager.getInstance().hasRegionData(rx, rz);
            if (!warmExactTexture && hasSource && !manager.isRegionLoaded(rx, rz)) {
                MapProcessor.getInstance().enqueueCaveLoad(manager.getActiveLayerY(), rx, rz, 10_000);
            }
        }
    }

    private void scanCavePixelIfLoaded(Level level, int blockX, int layerY, int blockZ, boolean fullView) {
        if (!level.hasChunk(blockX >> 4, blockZ >> 4)) return;

        int scanMaximum = fullView
                ? level.getMaxBuildHeight() - 1
                : CaveMode.getScanMaximum(level, layerY);

        // Loaded Minecraft chunks are always the authoritative source. The archive
        // exists for unloaded/distant reconstruction; reusing it here made current
        // cave scans inherit incomplete or stale floor snapshots and defeated the
        // center-out live scan. A bounded layer costs at most 32 Y checks, so scanning
        // the live column is both accurate and cheap enough for the existing budget.
        long pixel = getCavePixel(level, blockX, layerY, blockZ, fullView);

        int surfaceY = cavePixelY(pixel);
        int rawColor = cavePixelColor(pixel);
        int displayedColor = surfaceY == FullCaveMapManager.NO_SURFACE
                ? rawColor
                : applyAbgrShade(rawColor, calculateCaveTerrainShade(
                        level, blockX, blockZ, layerY, fullView, surfaceY));
        if (fullView && surfaceY != FullCaveMapManager.NO_SURFACE) {
            FullCaveMapManager.getInstance().mergeCandidate(
                    blockX, blockZ, displayedColor, surfaceY, scanMaximum);
        }
        if (!fullView) CaveMapManager.getInstance().setColor(blockX, blockZ, displayedColor);
    }

    private long getCavePixel(Level level, int blockX, int layerY, int blockZ, boolean fullView) {
        if (fullView) return getFullCavePixel(level, blockX, blockZ);
        int scanMinimum = CaveMode.getScanMinimum(level, layerY);
        int scanMaximum = CaveMode.getScanMaximum(level, layerY);
        int surfaceCutoffY = getReliableCaveSurfaceCutoff(level, blockX, blockZ);
        ScanScratch scratch = scanScratch.get();
        BlockPos.MutableBlockPos openPos = scratch.position(0, blockX, scanMaximum, blockZ);
        BlockPos.MutableBlockPos colorPos = scratch.position(1, blockX, scanMaximum, blockZ);

        // FULL projects to world bottom. LAYERED searches its 32-block band,
        // then follows only a cavity that is already open at the band's lower
        // boundary until that same cavity reaches its floor. It must not continue
        // through solid rock into unrelated lower caves.
        for (int openY = scanMaximum; openY >= scanMinimum; openY--) {
            long pixel = getCaveSurface(level, openPos, colorPos, openY,
                    layerY, fullView, surfaceCutoffY);
            if (pixel != CAVE_PIXEL_NOT_FOUND) return pixel;
        }

        if (!fullView) {
            long crossing = resolveCavityCrossingBandFloor(
                    level, openPos, colorPos, scanMinimum, layerY, surfaceCutoffY);
            if (crossing != CAVE_PIXEL_NOT_FOUND) return crossing;
        }

        return packCavePixel(0, FullCaveMapManager.NO_SURFACE);
    }

    /**
     * FULL cave begins only after the column has entered a real underground mass.
     * This mirrors Xaero's enter-ground state: surface vegetation and tree trunks
     * are skipped before the first enclosed air/fluid run is allowed to become a
     * cave floor.
     */
    private long getFullCavePixel(Level level, int blockX, int blockZ) {
        int minimumY = level.getMinBuildHeight();
        int startY = findUndergroundSearchStart(level, blockX, blockZ);
        if (startY <= minimumY) return packCavePixel(0, FullCaveMapManager.NO_SURFACE);

        int dimensionMiddle = (minimumY + level.getMaxBuildHeight() - 1) / 2;
        ScanScratch scratch = scanScratch.get();
        BlockPos.MutableBlockPos openPos = scratch.position(0, blockX, startY, blockZ);
        BlockPos.MutableBlockPos colorPos = scratch.position(1, blockX, startY, blockZ);
        for (int openY = startY; openY >= minimumY; openY--) {
            long pixel = getCaveSurface(level, openPos, colorPos, openY,
                    dimensionMiddle, true, Integer.MAX_VALUE);
            if (pixel != CAVE_PIXEL_NOT_FOUND) return pixel;
        }
        return packCavePixel(0, FullCaveMapManager.NO_SURFACE);
    }

    private int findUndergroundSearchStart(Level level, int blockX, int blockZ) {
        int minimumY = level.getMinBuildHeight();
        int maximumY = level.getMaxBuildHeight() - 1;
        int topY = isCaveLikeDimension(level)
                ? maximumY
                : Math.max(minimumY, Math.min(maximumY,
                        level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ)));
        BlockPos.MutableBlockPos probe = scanScratch.get().position(2,
                blockX, topY, blockZ);
        for (int y = topY; y >= minimumY; y--) {
            probe.setY(y);
            BlockState state = level.getBlockState(probe);
            if (isUndergroundTerrainEntry(level, probe, state)) return y - 1;
        }
        return minimumY;
    }

    /**
     * Same enter-ground predicate used by the decoded/archive cave pipeline and by
     * Xaero's Full-Cave loadPixel state machine. A three-consecutive-solid heuristic
     * skipped valid caves behind one- or two-block roofs and made live chunks disagree
     * with the Anvil/archive version of the same X/Z column.
     */
    private boolean isUndergroundTerrainEntry(Level level, BlockPos pos,
            BlockState state) {
        if (state == null || state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        MapVisualClassifier.VisualInfo visual = visualClassifier.info(state);
        if (state.ignitedByLava() || state.canBeReplaced()
                || state.getPistonPushReaction() == PushReaction.DESTROY) {
            return false;
        }
        if (visual.role() != MapVisualClassifier.Role.OPAQUE_BASE) return false;
        return state.blocksMotion()
                || !caveStateClassifier.isCollisionEmpty(level, pos, state);
    }

    private long getCaveSurface(Level level, BlockPos.MutableBlockPos openPos,
            BlockPos.MutableBlockPos colorPos, int openY, int bandCenterY,
            boolean fullView, int surfaceCutoffY) {
        // OCEAN_FLOOR is not guaranteed to be present in a client chunk. On affected
        // worlds it resolves to the dimension minimum, which made this condition
        // reject every underground Y and left cave textures completely black. The
        // caller now computes one reliable WORLD_SURFACE-based land/seabed cutoff
        // for the column and reuses it throughout the vertical scan.
        if (surfaceCutoffY != Integer.MAX_VALUE && openY >= surfaceCutoffY - 1) {
            return CAVE_PIXEL_NOT_FOUND;
        }

        openPos.setY(openY);
        BlockState openState = level.getBlockState(openPos);
        BlockState colorState;
        boolean emissiveFeature = isOpenEmissiveFeature(level, openPos, openState);
        boolean visibleFlower = MapConfig.displayFlowers
                && visualClassifier.info(openState).flower();
        boolean waterCoveredFloor = false;
        int waterDepth = 0;
        int waterTintY = openY;
        // Fluids are followed only through their own contiguous column. Their
        // basin floor may lie below the selected band without exposing unrelated
        // lower caves, so water resolution may safely continue to world bottom.
        int minimumY = level.getMinBuildHeight();

        if (emissiveFeature || visibleFlower
                || (!openState.getFluidState().isEmpty()
                        && !openState.getFluidState().is(net.minecraft.tags.FluidTags.WATER))) {
            colorPos.setY(openY);
            colorState = openState;
        } else if (openState.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
            // Water is transparent map geometry, not an opaque cave ceiling. Walk
            // through the contiguous water column and color the actual basin/cave
            // floor, then blend the water tint over it.
            WaterFloor floor = resolveWaterFloor(level, colorPos, openY, minimumY);
            if (floor == null) {
                // The water continues below this layer or forms a waterfall. Keep a
                // visible water pixel rather than converting the column into a hole.
                colorPos.setY(openY);
                colorState = openState;
            } else {
                colorPos.setY(floor.y());
                colorState = floor.state();
                waterDepth = floor.depth();
                waterCoveredFloor = true;
            }
        } else {
            boolean openSpace = openState.isAir() || caveStateClassifier.isCollisionEmpty(level, openPos, openState);
            if (!openSpace || openY <= level.getMinBuildHeight()) return CAVE_PIXEL_NOT_FOUND;
            colorPos.setY(openY - 1);
            colorState = level.getBlockState(colorPos);

            // The first block below cave air is commonly the surface of an
            // underground lake. Resolve through that water as well; otherwise the
            // scanner colors the water block itself and hides the connected floor.
            if (colorState.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                waterTintY = openY - 1;
                WaterFloor floor = resolveWaterFloor(level, colorPos, waterTintY, minimumY);
                if (floor == null) {
                    colorPos.setY(waterTintY);
                    colorState = level.getBlockState(colorPos);
                } else {
                    colorPos.setY(floor.y());
                    colorState = floor.state();
                    waterDepth = floor.depth();
                    waterCoveredFloor = true;
                }
            } else {
                boolean floorOpen = colorState.isAir()
                        || (caveStateClassifier.isCollisionEmpty(level, colorPos, colorState)
                                && colorState.getFluidState().isEmpty());
                if (floorOpen) return CAVE_PIXEL_NOT_FOUND;
            }
        }

        int baseColor = emissiveFeature
                ? getEmissiveFeatureColor(level, colorPos, colorState)
                : getCaveBlockColor(level, colorPos, colorState);
        if (baseColor == 0) return CAVE_PIXEL_NOT_FOUND;
        if (waterCoveredFloor) {
            BlockPos.MutableBlockPos waterPos = scanScratch.get().position(3,
                    openPos.getX(), waterTintY, openPos.getZ());
            baseColor = applyCaveWaterOverlay(level, waterPos, baseColor, waterDepth);
        }
        int blockLight = Math.max(level.getBrightness(LightLayer.BLOCK, openPos), colorState.getLightEmission());
        int shadeOffset;
        if (fullView) {
            int dimensionMiddle = (level.getMinBuildHeight() + level.getMaxBuildHeight() - 1) / 2;
            shadeOffset = Math.round((colorPos.getY() - dimensionMiddle) / 8.0f);
        } else {
            shadeOffset = Math.round((colorPos.getY() - bandCenterY) / 8.0f);
        }
        return packCavePixel(applyCaveLighting(baseColor, blockLight, shadeOffset),
                colorPos.getY());
    }

    /**
     * If the selected 32-block layer cuts through the middle of a tall cavern,
     * continue only through that already-open vertical run to its first floor.
     * This restores connected large caves without turning LAYERED into FULL:
     * solid rock terminates the search immediately.
     */
    private long resolveCavityCrossingBandFloor(Level level,
            BlockPos.MutableBlockPos openPos, BlockPos.MutableBlockPos colorPos,
            int bandMinimumY, int bandCenterY, int surfaceCutoffY) {
        if (surfaceCutoffY != Integer.MAX_VALUE
                && bandMinimumY >= surfaceCutoffY - 1) return CAVE_PIXEL_NOT_FOUND;

        int waterTopY = Integer.MIN_VALUE;
        int waterDepth = 0;
        boolean crossedOpenSpace = false;

        for (int y = bandMinimumY; y > level.getMinBuildHeight(); y--) {
            openPos.setY(y);
            BlockState state = level.getBlockState(openPos);
            boolean water = state.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
            boolean waterloggedSolid = water
                    && !state.is(Blocks.WATER)
                    && !caveStateClassifier.isCollisionEmpty(level, openPos, state);

            if (water && !waterloggedSolid) {
                crossedOpenSpace = true;
                if (waterTopY == Integer.MIN_VALUE) waterTopY = y;
                waterDepth++;
                continue;
            }

            if (!state.getFluidState().isEmpty()) {
                // Lava or another visible fluid belongs to this cavity and should
                // be drawn at the first encountered fluid surface.
                int fluidColor = getCaveBlockColor(level, openPos, state);
                if (fluidColor == 0) return CAVE_PIXEL_NOT_FOUND;
                int light = Math.max(level.getBrightness(LightLayer.BLOCK, openPos),
                        state.getLightEmission());
                int offset = Math.round((y - bandCenterY) / 8.0f);
                return packCavePixel(applyCaveLighting(fluidColor, light, offset), y);
            }

            if (isCavityOpenState(level, openPos, state)) {
                crossedOpenSpace = true;
                continue;
            }

            if (!crossedOpenSpace) return CAVE_PIXEL_NOT_FOUND;

            colorPos.setY(y);
            int baseColor = getCaveBlockColor(level, colorPos, state);
            if (baseColor == 0) return CAVE_PIXEL_NOT_FOUND;
            if (waterTopY != Integer.MIN_VALUE) {
                baseColor = applyCaveWaterOverlay(level,
                        scanScratch.get().position(3,
                                openPos.getX(), waterTopY, openPos.getZ()),
                        baseColor, waterDepth);
            }

            openPos.setY(Math.min(level.getMaxBuildHeight() - 1, y + 1));
            int blockLight = Math.max(level.getBrightness(LightLayer.BLOCK, openPos),
                    state.getLightEmission());
            int shadeOffset = Math.round((y - bandCenterY) / 8.0f);
            return packCavePixel(applyCaveLighting(baseColor, blockLight, shadeOffset), y);
        }
        return CAVE_PIXEL_NOT_FOUND;
    }

    private boolean isCavityOpenState(Level level, BlockPos pos, BlockState state) {
        if (state.isAir()) return true;
        if (!state.getFluidState().isEmpty()) {
            return state.is(Blocks.WATER) || state.is(Blocks.LAVA)
                    || caveStateClassifier.isCollisionEmpty(level, pos, state);
        }
        return caveStateClassifier.isCollisionEmpty(level, pos, state);
    }

    private WaterFloor resolveWaterFloor(Level level, BlockPos.MutableBlockPos probe,
            int waterY, int minimumY) {
        WaterFloor result = scanScratch.get().waterFloor;
        int depth = 0;
        for (int y = waterY; y >= minimumY; y--) {
            probe.setY(y);
            BlockState candidate = level.getBlockState(probe);
            boolean candidateWater = candidate.getFluidState()
                    .is(net.minecraft.tags.FluidTags.WATER);
            boolean waterloggedSolid = candidateWater
                    && !candidate.is(Blocks.WATER)
                    && !caveStateClassifier.isCollisionEmpty(level, probe, candidate);
            if (waterloggedSolid) {
                return result.set(candidate, y, Math.max(1, depth + 1));
            }
            if (candidateWater) {
                depth++;
                continue;
            }
            // A falling stream entering open air has no covered floor at this
            // height. The outer downward scan will resolve the lower space itself.
            if (candidate.isAir()) return null;
            boolean passThrough = caveStateClassifier.isCollisionEmpty(level, probe, candidate)
                    && candidate.getFluidState().isEmpty();
            if (passThrough) continue;
            return result.set(candidate, y, Math.max(1, depth));
        }
        return null;
    }

    private static final class WaterFloor {
        private BlockState state;
        private int y;
        private int depth;

        private WaterFloor set(BlockState state, int y, int depth) {
            this.state = state;
            this.y = y;
            this.depth = depth;
            return this;
        }

        private BlockState state() {
            return state;
        }

        private int y() {
            return y;
        }

        private int depth() {
            return depth;
        }
    }

    private static long packCavePixel(int color, int surfaceY) {
        return ((long) color << 32) | (surfaceY & 0xFFFFFFFFL);
    }

    private static int cavePixelColor(long pixel) {
        return (int) (pixel >> 32);
    }

    private static int cavePixelY(long pixel) {
        return (int) pixel;
    }

    /** Cave relief follows the actual visible floor selected by the Top-Y band,
     * rather than the overworld heightmap. This makes LAYERED and FULL honour the
     * same OFF / 2D / 3D setting as the surface map. */
    private float calculateCaveTerrainShade(Level level, int x, int z, int layerY,
            boolean fullView, int centerY) {
        if (MapConfig.terrainSlopes <= 0) return 1.0f;
        int north = getCaveNeighbourY(level, x, z - 1, layerY, fullView, centerY);
        if (MapConfig.terrainSlopes == 1) {
            return Math.max(0.86f, Math.min(1.14f,
                    1.0f + (centerY - north) * 0.030f));
        }
        int south = getCaveNeighbourY(level, x, z + 1, layerY, fullView, centerY);
        int west = getCaveNeighbourY(level, x - 1, z, layerY, fullView, centerY);
        int east = getCaveNeighbourY(level, x + 1, z, layerY, fullView, centerY);
        float gradientX = Math.max(-8.0f, Math.min(8.0f, (west - east) * 0.5f));
        float gradientZ = Math.max(-8.0f, Math.min(8.0f, (north - south) * 0.5f));
        float directional = gradientX * 0.045f + gradientZ * 0.062f;
        int rim = Math.max(Math.max(north, south), Math.max(west, east));
        float pit = Math.min(0.30f, Math.max(0, rim - centerY) * 0.050f);
        float edge = Math.min(0.16f,
                (Math.abs(west - east) + Math.abs(north - south)) * 0.012f);
        return Math.max(0.62f, Math.min(1.24f, 1.0f + directional - pit - edge));
    }

    private int getCaveNeighbourY(Level level, int x, int z, int layerY,
            boolean fullView, int fallbackY) {
        VerticalCaveArchiveManager archive = VerticalCaveArchiveManager.getInstance();
        if (archive.isColumnReady(x, z) && archive.isColumnScanned(x, z)) {
            int maximum = fullView
                    ? level.getMaxBuildHeight() - 1
                    : CaveMode.getScanMaximum(level, layerY);
            int minimum = fullView ? level.getMinBuildHeight() : CaveMode.getScanMinimum(level, layerY);
            VerticalCaveArchiveManager.Candidate candidate = archive.getCandidate(x, z, maximum, minimum);
            return candidate == null ? fallbackY : candidate.bottomY();
        }
        if (!level.hasChunk(x >> 4, z >> 4)) return fallbackY;
        if (fullView) {
            int cached = FullCaveMapManager.getInstance().getSurfaceY(x, z);
            return cached == FullCaveMapManager.NO_SURFACE ? fallbackY : cached;
        }
        int minimum = CaveMode.getScanMinimum(level, layerY);
        int maximum = CaveMode.getScanMaximum(level, layerY);
        ScanScratch scratch = scanScratch.get();
        BlockPos.MutableBlockPos openPos = scratch.position(0, x, maximum, z);
        BlockPos.MutableBlockPos floorPos = scratch.position(1, x, maximum, z);

        for (int openY = maximum; openY >= minimum; openY--) {
            openPos.setY(openY);
            BlockState open = level.getBlockState(openPos);
            if (isOpenEmissiveFeature(level, openPos, open)
                    || (MapConfig.displayFlowers && visualClassifier.info(open).flower())) {
                return openY;
            }
            if (open.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                WaterFloor waterFloor = resolveWaterFloor(level, floorPos, openY, minimum);
                if (waterFloor != null) return waterFloor.y();
                continue;
            }
            if (!open.getFluidState().isEmpty()) return openY;
            boolean openSpace = open.isAir() || caveStateClassifier.isCollisionEmpty(level, openPos, open);
            if (!openSpace || openY <= level.getMinBuildHeight()) continue;
            floorPos.setY(openY - 1);
            BlockState floor = level.getBlockState(floorPos);
            if (floor.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                WaterFloor waterFloor = resolveWaterFloor(level, floorPos, openY - 1, minimum);
                if (waterFloor != null) return waterFloor.y();
                continue;
            }
            boolean floorOpen = floor.isAir()
                    || (caveStateClassifier.isCollisionEmpty(level, floorPos, floor)
                            && floor.getFluidState().isEmpty());
            if (!floorOpen) return openY - 1;
        }
        return fallbackY;
    }

    private int applyAbgrShade(int abgr, float shade) {
        int alpha = (abgr >>> 24) & 0xFF;
        int red = Math.max(0, Math.min(255, Math.round((abgr & 0xFF) * shade)));
        int green = Math.max(0, Math.min(255, Math.round(((abgr >>> 8) & 0xFF) * shade)));
        int blue = Math.max(0, Math.min(255, Math.round(((abgr >>> 16) & 0xFF) * shade)));
        return (alpha << 24) | (blue << 16) | (green << 8) | red;
    }

    private int getCaveBlockColor(Level level, BlockPos pos, BlockState state) {
        String blockId = visualClassifier.info(state).blockId();
        Integer override = MapConfig.blockColorOverrides.get(blockId);
        if (override != null) return argbToAbgr(override);
        if (state.getLightEmission() > 0) return getEmissiveFeatureColor(level, pos, state);

        MapColor mapColor = state.getMapColor(level, pos);
        int rgb;
        if (mapColor == MapColor.NONE) {
            // Many modded, glass-like and decorative solid blocks deliberately use
            // MapColor.NONE even though their model has a valid texture. Falling back
            // to the texture sampler prevents those floors from becoming cave holes.
            int sampled = MapTextureManager.getInstance()
                    .resolveBlockColor(blockId, MapConfig.blockColourMode);
            rgb = sampled == 0 || sampled == 0xFFFFFFFF
                    ? 0x7F8588 : sampled & 0x00FFFFFF;
        } else {
            rgb = resolveBlockRgb(level, pos, state, mapColor);
        }
        MapVisualClassifier.VisualInfo visual = visualClassifier.info(state);
        if (MapConfig.blockColourMode == 0) {
            rgb = makeColorRich(rgb, visual.leaves(), visual.fixedTextureColor(),
                    visual.grass(), visual.wood() || mapColor == MapColor.WOOD);
        }
        int red = (rgb >>> 16) & 0xFF;
        int green = (rgb >>> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return 0xFF000000 | (blue << 16) | (green << 8) | red;
    }

    private int applyCaveWaterOverlay(Level level, BlockPos waterPos,
            int floorAbgr, int depth) {
        BlockState waterState = level.getBlockState(waterPos);
        int waterRgb = Minecraft.getInstance().getBlockColors()
                .getColor(waterState, level, waterPos, 0);
        if (waterRgb == -1) waterRgb = MapColor.WATER.col;

        float amount = Math.min(0.82f, 0.34f + Math.max(1, depth) * 0.055f);
        float attenuation = Math.max(0.72f,
                (float) Math.pow(0.982f, Math.max(0, depth - 2)));
        int floorRed = floorAbgr & 0xFF;
        int floorGreen = (floorAbgr >>> 8) & 0xFF;
        int floorBlue = (floorAbgr >>> 16) & 0xFF;
        int waterRed = (waterRgb >>> 16) & 0xFF;
        int waterGreen = (waterRgb >>> 8) & 0xFF;
        int waterBlue = waterRgb & 0xFF;

        int red = Math.round((floorRed + (waterRed - floorRed) * amount) * attenuation);
        int green = Math.round((floorGreen + (waterGreen - floorGreen) * amount) * attenuation);
        int blue = Math.round((floorBlue + (waterBlue - floorBlue) * amount) * attenuation);
        return 0xFF000000 | (Math.min(255, blue) << 16)
                | (Math.min(255, green) << 8) | Math.min(255, red);
    }

    private boolean isOpenEmissiveFeature(Level level, BlockPos pos, BlockState state) {
        if (state.getLightEmission() <= 0 || !state.getFluidState().isEmpty()
                || isMapInvisibleDecoration(state)) return false;
        return state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || caveStateClassifier.isCollisionEmpty(level, pos, state);
    }

    /** Gives flames and non-solid light sources a visible map core, not only a halo. */
    private int getEmissiveFeatureColor(Level level, BlockPos pos, BlockState state) {
        String blockId = visualClassifier.info(state).blockId();
        Integer override = MapConfig.blockColorOverrides.get(blockId);
        if (override != null) return argbToAbgr(override);
        int rgb = MapTextureManager.getInstance().resolveBlockColor(blockId, MapConfig.blockColourMode);
        if (rgb != 0 && rgb != 0xFFFFFFFF) rgb &= 0x00FFFFFF;
        else rgb = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
        if (rgb == -1) {
            MapColor mapColor = state.getMapColor(level, pos);
            rgb = mapColor == MapColor.NONE ? 0xFFB13B : mapColor.col;
        }

        float boost = 1.0f + 0.25f * state.getLightEmission() / 15.0f;
        int red = Math.min(255, Math.round(((rgb >>> 16) & 0xFF) * boost));
        int green = Math.min(255, Math.round(((rgb >>> 8) & 0xFF) * boost));
        int blue = Math.min(255, Math.round((rgb & 0xFF) * boost));
        return 0xFF000000 | (blue << 16) | (green << 8) | red;
    }

    private int applyCaveLighting(int abgr, int light, int verticalOffset) {
        float normalized = Math.max(0.0f, Math.min(1.0f, light / 15.0f));
        float heightShade = Math.max(0.82f, Math.min(1.18f, 1.0f + verticalOffset * 0.018f));
        // Cave maps represent geometry, not the player's current gamma/light
        // exposure. Keep a readable ambient floor so one scanned region cannot be
        // pitch black while an adjacent cached region is bright.
        float brightness = (0.60f + 0.40f * (float) Math.pow(normalized, 0.90f)) * heightShade;
        brightness = Math.max(0.58f, Math.min(1.0f, brightness));
        int red = Math.round((abgr & 0xFF) * brightness);
        int green = Math.round(((abgr >>> 8) & 0xFF) * brightness);
        int blue = Math.round(((abgr >>> 16) & 0xFF) * brightness);

        if (light > 6) {
            float warmth = Math.min(0.45f, ((light - 6) / 9.0f) * 0.45f);
            red = Math.round(red + (255 - red) * warmth);
            green = Math.round(green + (185 - green) * warmth);
            blue = Math.round(blue + (80 - blue) * warmth);
        }
        return 0xFF000000 | (blue << 16) | (green << 8) | red;
    }

    private void scanLightIfLoaded(Minecraft mc, int blockX, int blockZ) {
        if (mc.level.hasChunk(blockX >> 4, blockZ >> 4)) {
            int surfaceY = getHighestY(mc.level, blockX, blockZ);
            updateSurfaceLight(mc.level, blockX, surfaceY, blockZ);
        }
    }

    private void updateSurfaceLight(Level level, int blockX, int surfaceY, int blockZ) {
        MapLightManager.getInstance().setLight(blockX, blockZ,
                sampleSurfaceLight(level, blockX, surfaceY, blockZ));
    }

    private int sampleSurfaceLight(Level level, int blockX, int surfaceY, int blockZ) {
        BlockPos.MutableBlockPos lightPos = scanScratch.get().position(3,
                blockX, surfaceY, blockZ);
        int light = level.getBrightness(LightLayer.BLOCK, lightPos);
        lightPos.setY(surfaceY + 1);
        return Math.max(light, level.getBrightness(LightLayer.BLOCK, lightPos));
    }

    /**
     * Re-scans a single column (x, z) in the world synchronously.
     * Now stores raw MapBlockData for re-colorizable map.
     */
    public void scanBlockColumn(Level level, BlockPos pos) {
        scanBlockColumn(level, pos.getX(), pos.getZ(), true);
    }

    /** Surface half of an event-driven mutation. Cave work is queued separately. */
    public void scanSurfaceColumn(Level level, int blockX, int blockZ) {
        scanBlockColumn(level, blockX, blockZ, false);
    }

    private void publishCompletedSurfaceChunk(int chunkX, int chunkZ,
            MapRequestLane lane) {
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp != null) {
            SurfaceRegionSourceDatabase.getInstance().publishCompletedChunk(
                    stamp, chunkX, chunkZ, lane);
        }
    }

    /**
     * Scans a contiguous row-major chunk slice and commits it once. This mirrors
     * Xaero's chunk writer semantics without copying its code: packets mark chunk
     * work, the writer completes a bounded chunk transaction, and retained source
     * becomes visible only after a complete 16x16 payload exists.
     *
     * @return the next row-major column cursor
     */
    public int scanSurfaceChunkSlice(Level level, int chunkX, int chunkZ,
            int startColumn, int maximumColumns) {
        return scanSurfaceChunkSlice(level, chunkX, chunkZ, startColumn,
                maximumColumns, Long.MAX_VALUE, MapRequestLane.MINIMAP);
    }

    /** Deadline-aware form used by live and mutation schedulers. */
    public int scanSurfaceChunkSlice(Level level, int chunkX, int chunkZ,
            int startColumn, int maximumColumns, long deadlineNanos) {
        return scanSurfaceChunkSlice(level, chunkX, chunkZ, startColumn,
                maximumColumns, deadlineNanos, MapRequestLane.MINIMAP);
    }

    /** Deadline-aware form that preserves the requesting viewport lane. */
    private int scanSurfaceChunkSlice(Level level, int chunkX, int chunkZ,
            int startColumn, int maximumColumns, long deadlineNanos,
            MapRequestLane publicationLane) {
        MapManager manager = MapManager.getInstance();
        if (level == null || !level.isClientSide() || maximumColumns <= 0
                || !manager.acceptsLiveLevel(level)) {
            return Math.max(0, startColumn);
        }
        int requestedStart = Math.max(0, Math.min(256, startColumn));
        if (requestedStart >= 256) return requestedStart;
        LevelChunk loadedChunk = readySurfaceChunk(level, chunkX, chunkZ);
        if (loadedChunk == null) return requestedStart;
        GeneratedChunkIndex.getInstance().markLive(level, chunkX, chunkZ);
        ScanScratch scratch = scanScratch.get();
        int blockStartX = chunkX << 4;
        int blockStartZ = chunkZ << 4;
        MapManager.Region targetRegion = manager.getRegion(
                blockStartX >> 9, blockStartZ >> 9, true);
        if (targetRegion == null || !targetRegion.isLoaded()) return requestedStart;
        scratch.beginSurfaceSlice(level, targetRegion);

        long chunkKey = packChunk(chunkX, chunkZ);
        SurfaceChunkStage stage = surfaceChunkStages.get(chunkKey);
        if (stage == null) {
            // A caller can retain an old numeric cursor after its stage was evicted
            // or invalidated by a newer packet. Restart privately from column zero;
            // the old authoritative map tile remains visible until this replacement
            // reaches 256 columns and swaps as one transaction.
            if (surfaceChunkStages.size() >= SURFACE_STAGE_LIMIT) {
                surfaceChunkStages.clear();
            }
            stage = new SurfaceChunkStage();
            surfaceChunkStages.put(chunkKey, stage);
        }
        int start = stage.cursor;
        if (start >= 256) return 256;
        int requestedEnd = Math.min(256, start + maximumColumns);
        stage.promote(publicationLane);

        int count = 0;
        for (int column = start; column < requestedEnd; column++) {
            // One 16-column row is the smallest useful travel pulse. The build is
            // private, so yielding cannot expose stripes or half-refreshed trees.
            if (count > 0 && (count & 15) == 0
                    && System.nanoTime() >= deadlineNanos) break;
            int blockX = blockStartX + (column & 15);
            int blockZ = blockStartZ + (column >>> 4);
            long packed = buildPackedBlockData(level, loadedChunk, targetRegion,
                    blockX, blockZ, true, scratch.surfaceSample);
            if (packed == SURFACE_DATA_UNAVAILABLE) break;
            stage.packed[column] = packed;
            stage.tints[column] = scratch.surfaceSample.tint;
            stage.lights[column] = scratch.surfaceSample.light;
            count++;
        }
        if (count <= 0) return start;
        stage.cursor = start + count;
        if (stage.cursor < 256) return stage.cursor;

        // Recheck ownership only at the atomic swap boundary. No partial Surface
        // column has touched MapManager/retained source before this point.
        if (!manager.acceptsLiveLevel(level)) {
            surfaceChunkStages.remove(chunkKey);
            return 0;
        }
        manager.commitSurfaceChunkSlice(chunkX, chunkZ, 0,
                stage.packed, stage.tints, ALL_SURFACE_COLUMNS_VALID, 0, 256);
        MapLightManager.getInstance().setChunkLightSlice(chunkX, chunkZ, 0,
                stage.lights, 0, 256, false);
        MapManager.SurfaceChunkCommit completed = manager
                .finishSurfaceChunkTransaction(chunkX, chunkZ);
        MapRequestLane completedLane = stage.publicationLane == null
                ? MapRequestLane.MINIMAP : stage.publicationLane;
        surfaceChunkStages.remove(chunkKey);
        if (completed.chunkComplete()) {
            if (liveSurfacePublishedChunks.size() >= LIVE_SURFACE_VERIFIED_LIMIT) {
                // This set is only a short-lived reconciliation hint, never
                // durable authority. Bound it so long exploration cannot turn
                // writer bookkeeping into another historical working set.
                liveSurfacePublishedChunks.clear();
            }
            liveSurfacePublishedChunks.add(chunkKey);
            publishCompletedSurfaceChunk(chunkX, chunkZ, completedLane);
        }
        return 256;
    }

    /** Light-only packet path: preserve geometry and update the surface light cache. */
    public void scanSurfaceLightColumn(Level level, int blockX, int blockZ) {
        if (level == null || !level.isClientSide()) return;
        long data = MapManager.getInstance().getPackedBlockData(blockX, blockZ);
        int surfaceY = !MapBlockData.isEmpty(data)
                ? MapBlockData.topY(data) : getHighestY(level, blockX, blockZ);
        updateSurfaceLight(level, blockX, surfaceY, blockZ);
    }

    /**
     * Incremental light-packet path. Samples a bounded row-major chunk slice and
     * publishes it to the light cache as one transaction, preserving the mutation
     * deadline while avoiding per-column cache locks and dirty notifications.
     *
     * @return the next row-major column cursor
     */
    public int scanSurfaceLightChunkSlice(Level level, int chunkX, int chunkZ,
            int startColumn, int maximumColumns) {
        if (level == null || !level.isClientSide() || maximumColumns <= 0
                || !MapManager.getInstance().acceptsLiveLevel(level)) {
            return Math.max(0, startColumn);
        }
        int start = Math.max(0, Math.min(256, startColumn));
        int end = Math.min(256, start + maximumColumns);
        if (start >= end || !level.hasChunk(chunkX, chunkZ)) return start;
        ScanScratch scratch = scanScratch.get();
        int blockStartX = chunkX << 4;
        int blockStartZ = chunkZ << 4;
        for (int column = start; column < end; column++) {
            int blockX = blockStartX + (column & 15);
            int blockZ = blockStartZ + (column >>> 4);
            long data = MapManager.getInstance().getPackedBlockData(blockX, blockZ);
            int surfaceY = !MapBlockData.isEmpty(data)
                    ? MapBlockData.topY(data) : getHighestY(level, blockX, blockZ);
            scratch.lightLevels[column - start] = (byte) sampleSurfaceLight(
                    level, blockX, surfaceY, blockZ);
        }
        MapLightManager.getInstance().setChunkLightSlice(chunkX, chunkZ, start,
                scratch.lightLevels, 0, end - start);
        return end;
    }

    private void scanBlockColumn(Level level, int blockX, int blockZ, boolean invalidateCave) {
        if (!level.isClientSide()
                || !MapManager.getInstance().acceptsLiveLevel(level)) return;
        try {
            GeneratedChunkIndex.getInstance().markLive(level, blockX >> 4, blockZ >> 4);
            MapBlockData data = buildBlockData(level, blockX, blockZ);
            if (data != null) {
                long existing = MapManager.getInstance().getPackedBlockData(blockX, blockZ);
                if (MapBlockData.isEmpty(existing) || !data.isEmpty()) {
                    MapManager.getInstance().setBlockData(blockX, blockZ, data,
                            resolveSurfaceTint(level, blockX, blockZ, data));
                }
            }
            int surfaceY = data != null && !data.isEmpty() ? data.topY
                    : getHighestY(level, blockX, blockZ);
            updateSurfaceLight(level, blockX, surfaceY, blockZ);
            if (invalidateCave) {
                // Legacy/direct callers still produce the same centralized cave
                // mutation. Packet-driven callers use scanSurfaceColumn() and queue
                // the cave half exactly once through MapMutationBus.
                CavePipeline.getInstance().onColumnMutation(
                        blockX, blockZ, MapMutationBus.BLOCK_STATE);
            }
        } catch (Exception e) {
            LOGGER.error("Error scanning block column at {}, {}", blockX, blockZ, e);
        }
    }

    /** Re-scans one map pixel using whichever surface/cave view is currently active. */
    public void scanDisplayedColumn(Minecraft mc, int blockX, int blockZ) {
        if (mc == null || mc.level == null || mc.player == null) return;
        if (CaveMode.isActive(mc)) {
            if (!CaveMode.isFullView(mc)) {
                CaveMapManager.getInstance().setActiveLayer(CaveMode.getLayerY(mc));
            }
            CavePipeline.getInstance().scanColumnNow(mc.level, blockX, blockZ);
        } else {
            scanBlockColumn(mc.level, blockX, blockZ, true);
        }
    }

    /** Captures the exact tint returned by Minecraft/modded BlockColors at this pixel. */
    private int resolveSurfaceTint(Level level, int blockX, int blockZ,
            MapBlockData data) {
        return data == null ? SurfaceTintData.UNKNOWN
                : resolveSurfaceTint(level, blockX, blockZ, data.pack());
    }

    /** Allocation-free tint path for chunk transactions. */
    private int resolveSurfaceTint(Level level, int blockX, int blockZ,
            long packedData) {
        if (level == null || packedData == SURFACE_DATA_UNAVAILABLE
                || MapBlockData.isEmpty(packedData)) return SurfaceTintData.UNKNOWN;
        if (MapBlockData.isFluid(packedData)
                && !MapBlockData.isGlowing(packedData)) return SurfaceTintData.NONE;
        BlockPos.MutableBlockPos pos = scanScratch.get().position(2,
                blockX, MapBlockData.topY(packedData), blockZ);
        BlockState state = level.getBlockState(pos);
        state = blockEntityVisuals.resolveLive(level, pos, state);
        MapVisualClassifier.VisualInfo visual = visualClassifier.info(state);
        if (visual.fixedTextureColor()) return SurfaceTintData.NONE;
        // Biome-driven tint is reconstructed from stored biome ids during region
        // composition. Capturing the live provider value here would freeze the
        // current chunk-loading neighbourhood into a permanent 16x16 colour seam.
        if (visual.tintPolicy() != BlockTintPolicy.NONE) {
            return SurfaceTintData.NONE;
        }
        String blockId = visual.blockId();
        if (BrokenBlockTintCache.getInstance().isBroken(blockId)) {
            return SurfaceTintData.NONE;
        }
        try {
            int tint = Minecraft.getInstance().getBlockColors().getColor(
                    state, level, pos, 0);
            return SurfaceTintData.fromProviderResult(tint);
        } catch (Throwable throwable) {
            BrokenBlockTintCache.getInstance().markBroken(blockId);
            return SurfaceTintData.NONE;
        }
    }

    /**
     * Prepared-state tint path used by the 16x16 writer. The previous chunk path
     * fetched the top BlockState, resolved block-entity camouflage and classified
     * the same block a second time after {@link #buildPackedBlockData}; doing that
     * for every column was a measurable part of the Surface catch-up deficit.
     */
    private int resolveSurfaceTint(Level level, BlockPos.MutableBlockPos pos,
            BlockState visibleState, MapVisualClassifier.VisualInfo visual,
            boolean fluid, boolean glowing) {
        if (level == null || visibleState == null || visual == null) {
            return SurfaceTintData.UNKNOWN;
        }
        if (fluid && !glowing) return SurfaceTintData.NONE;
        if (visual.fixedTextureColor()) return SurfaceTintData.NONE;
        // Biome-driven tint is reconstructed from stored biome ids during region
        // composition. Capturing the live provider value here would freeze the
        // current chunk-loading neighbourhood into a permanent 16x16 colour seam.
        if (visual.tintPolicy() != BlockTintPolicy.NONE) {
            return SurfaceTintData.NONE;
        }
        String blockId = visual.blockId();
        if (BrokenBlockTintCache.getInstance().isBroken(blockId)) {
            return SurfaceTintData.NONE;
        }
        try {
            int tint = Minecraft.getInstance().getBlockColors().getColor(
                    visibleState, level, pos, 0);
            return SurfaceTintData.fromProviderResult(tint);
        } catch (Throwable throwable) {
            BrokenBlockTintCache.getInstance().markBroken(blockId);
            return SurfaceTintData.NONE;
        }
    }

    /**
     * Builds a MapBlockData from the current world state at (blockX, blockZ).
     * Returns null if chunk is not loaded, or a data with EMPTY_Y if no surface
     * block was found.
     */
    private MapBlockData buildBlockData(Level level, int blockX, int blockZ) {
        long packed = buildPackedBlockData(level, null,
                blockX, blockZ, false);
        return packed == SURFACE_DATA_UNAVAILABLE
                ? null : MapBlockData.unpack(packed);
    }

    private long buildPackedBlockData(Level level, LevelChunk loadedChunk,
            int blockX, int blockZ, boolean chunkKnownLoaded) {
        return buildPackedBlockData(level, loadedChunk, null,
                blockX, blockZ, chunkKnownLoaded, null);
    }

    /**
     * Combined Surface column capture. When {@code output} is supplied this method
     * also computes tint and light from the already-resolved state, eliminating a
     * second block-state lookup, a second block-entity resolution and one duplicate
     * block-light sample per column.
     */
    private long buildPackedBlockData(Level level, LevelChunk loadedChunk,
            MapManager.Region preparedRegion, int blockX, int blockZ,
            boolean chunkKnownLoaded, SurfaceColumnSample output) {
        if (output != null) output.reset();
        if (!chunkKnownLoaded && !level.hasChunk(blockX >> 4, blockZ >> 4)) {
            return SURFACE_DATA_UNAVAILABLE;
        }
        ScanScratch scratch = scanScratch.get();
        SurfaceTopSample top = resolveSurfaceTop(level, loadedChunk,
                blockX, blockZ, scratch);
        int surfaceY = top.surfaceY;
        BlockPos.MutableBlockPos pos = scratch.position(0,
                blockX, surfaceY, blockZ);
        BlockState actualVisibleState = top.actualState;
        BlockState visibleState = top.visibleState;
        MapVisualClassifier.VisualInfo visual = top.visual;
        boolean fluid = actualVisibleState != null
                && !actualVisibleState.getFluidState().isEmpty();
        if (!top.visible || actualVisibleState == null
                || visibleState == null || visual == null) {
            if (output != null) {
                output.packed = MapBlockData.EMPTY_PACKED;
                output.tint = SurfaceTintData.UNKNOWN;
                output.light = (byte) sampleSurfaceLight(
                        level, blockX, surfaceY, blockZ);
            }
            return MapBlockData.EMPTY_PACKED;
        }

        int floorY = surfaceY;
        BlockState paletteState = visibleState;
        boolean water = actualVisibleState.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
        boolean lava = actualVisibleState.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
        if (water) {
            /*
             * Match the decoded/world-save path before measuring water depth.
             * WORLD_SURFACE is normally correct, but a partially updated client
             * heightmap or a waterlogged plant can make the first visible state
             * sit below the real liquid surface. Using that submerged Y as topY
             * turns kelp/seagrass columns into false shallow-water stripes.
             */
            int connectedSurfaceY = findConnectedLiveWaterSurfaceY(
                    level, loadedChunk, blockX, blockZ, surfaceY, scratch);
            if (connectedSurfaceY != surfaceY) {
                surfaceY = connectedSurfaceY;
                pos.setY(surfaceY);
                actualVisibleState = loadedChunk == null
                        ? level.getBlockState(pos) : loadedChunk.getBlockState(pos);
                visibleState = blockEntityVisuals.resolveLive(
                        level, pos, actualVisibleState);
                visual = visualClassifier.info(visibleState);
            }
            // Store the physical floor below water, including waterlogged slabs,
            // stairs and other solid basin blocks. The previous implementation
            // skipped every waterlogged state because it checked FluidState first;
            // small fountains could therefore retain the water block as their floor
            // palette and render as a dark/empty-looking hole.
            for (int y = surfaceY; y >= level.getMinBuildHeight(); y--) {
                pos.setY(y);
                BlockState candidate = loadedChunk == null
                        ? level.getBlockState(pos) : loadedChunk.getBlockState(pos);
                BlockState candidateVisual = blockEntityVisuals.resolveLive(level, pos, candidate);
                boolean candidateWater = candidate.getFluidState()
                        .is(net.minecraft.tags.FluidTags.WATER);
                boolean waterloggedSolid = candidateWater
                        && !candidate.is(Blocks.WATER)
                        && !caveStateClassifier.isCollisionEmpty(level, pos, candidate);
                if (waterloggedSolid) {
                    floorY = y;
                    paletteState = candidateVisual;
                    break;
                }
                if (candidateWater || candidate.isAir()) continue;

                // Texture sampling can still color solid modded/glass blocks whose
                // vanilla MapColor is NONE. Only pass through genuinely non-solid
                // decoration instead of discarding the whole water column.
                boolean passThrough = caveStateClassifier.isCollisionEmpty(level, pos, candidate)
                        && candidate.getFluidState().isEmpty();
                if (passThrough) continue;
                floorY = y;
                paletteState = candidateVisual;
                break;
            }
        }

        MapVisualClassifier.VisualInfo paletteVisual = paletteState == visibleState
                ? visual : visualClassifier.info(paletteState);
        String blockId = paletteVisual.blockId();
        pos.setY(surfaceY);
        String biomeId = resolveSurfaceBiomeId(level, pos, scratch);

        MapManager.Region region = preparedRegion;
        if (region == null) {
            int rx = blockX >> 9;
            int rz = blockZ >> 9;
            region = MapManager.getInstance().getRegion(rx, rz, true);
        }
        if (region == null || !region.isLoaded()) return SURFACE_DATA_UNAVAILABLE;
        long paletteIndices = scratch.surfacePaletteIndices(
                region, biomeId, blockId);
        int biomeIdx = (int) (paletteIndices >>> 32);
        int blockIdx = (int) paletteIndices;

        boolean leaves = !fluid && visual.leaves();
        boolean flower = !fluid && visual.flower();
        boolean glowing = lava || (!water && visual.emissive());
        BlockPos.MutableBlockPos lightPos = scratch.position(3,
                blockX, surfaceY, blockZ);
        int surfaceLight = level.getBrightness(LightLayer.BLOCK, lightPos);
        lightPos.setY(surfaceY + 1);
        int blockLight = level.getBrightness(LightLayer.BLOCK, lightPos);

        int flags = Math.max(0, Math.min(15, blockLight));
        if (glowing) flags |= 0x10;
        if (fluid) flags |= 0x20;
        if (flower) flags |= 0x40;
        if (leaves) flags |= 0x80;
        long packed = MapBlockData.packRaw((short) surfaceY, (short) blockIdx,
                (byte) biomeIdx, (byte) flags, (short) floorY);
        if (output != null) {
            pos.setY(surfaceY);
            output.packed = packed;
            output.tint = resolveSurfaceTint(level, pos, visibleState,
                    visual, fluid, glowing);
            output.light = (byte) Math.max(surfaceLight, blockLight);
        }
        return packed;
    }

    /**
     * Resolves the visible top state once for the chunk writer. The old path first
     * searched for the Y coordinate and then fetched/resolved/classified the same
     * winning state again. Keeping the winning state in scratch removes that second
     * block lookup from nearly every Surface pixel.
     */
    private SurfaceTopSample resolveSurfaceTop(Level level,
            LevelChunk loadedChunk, int blockX, int blockZ,
            ScanScratch scratch) {
        SurfaceTopSample output = scratch.surfaceTop;
        output.reset();
        int minimumY = level.getMinBuildHeight();
        BlockPos.MutableBlockPos probe = scratch.position(1,
                blockX, minimumY, blockZ);

        if (isCaveLikeDimension(level)) {
            boolean foundAir = false;
            for (int y = level.getMaxBuildHeight() - 1; y >= minimumY; y--) {
                probe.setY(y);
                BlockState state = loadedChunk == null
                        ? level.getBlockState(probe)
                        : loadedChunk.getBlockState(probe);
                if (!foundAir) {
                    if (state.isAir()) foundAir = true;
                    continue;
                }
                if (isVisibleMapSurface(level, probe, state)) {
                    captureSurfaceTop(output, level, probe, y, state, true);
                    return output;
                }
            }
        } else {
            int highestY = loadedChunk == null
                    ? level.getHeight(Heightmap.Types.WORLD_SURFACE,
                            blockX, blockZ)
                    : loadedChunk.getHeight(Heightmap.Types.WORLD_SURFACE,
                            blockX & 15, blockZ & 15);
            for (int y = highestY; y >= minimumY; y--) {
                probe.setY(y);
                BlockState state = loadedChunk == null
                        ? level.getBlockState(probe)
                        : loadedChunk.getBlockState(probe);
                if (isVisibleMapSurface(level, probe, state)) {
                    captureSurfaceTop(output, level, probe, y, state, true);
                    return output;
                }
            }
        }

        // Preserve the legacy minimum-height fallback. buildPackedBlockData will
        // turn a non-visible fallback into EMPTY_PACKED exactly as before.
        probe.setY(minimumY);
        BlockState fallback = loadedChunk == null
                ? level.getBlockState(probe)
                : loadedChunk.getBlockState(probe);
        captureSurfaceTop(output, level, probe, minimumY, fallback,
                isVisibleMapSurface(level, probe, fallback));
        return output;
    }

    /** Returns the highest block in the connected live water column. */
    private int findConnectedLiveWaterSurfaceY(Level level, LevelChunk loadedChunk,
            int blockX, int blockZ, int startY, ScanScratch scratch) {
        int minimumY = level.getMinBuildHeight();
        int maximumY = level.getMaxBuildHeight() - 1;
        int surface = Math.max(minimumY, Math.min(maximumY, startY));
        BlockPos.MutableBlockPos probe = scratch.position(2, blockX, surface, blockZ);
        for (int y = surface + 1; y <= maximumY; y++) {
            probe.setY(y);
            BlockState state = loadedChunk == null
                    ? level.getBlockState(probe) : loadedChunk.getBlockState(probe);
            if (!state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) break;
            surface = y;
        }
        return surface;
    }

    private void captureSurfaceTop(SurfaceTopSample output, Level level,
            BlockPos.MutableBlockPos position, int y, BlockState state,
            boolean visible) {
        output.surfaceY = y;
        output.actualState = state;
        output.visibleState = blockEntityVisuals.resolveLive(
                level, position, state);
        output.visual = output.visibleState == null ? null
                : visualClassifier.info(output.visibleState);
        output.visible = visible && output.visibleState != null
                && isVisibleMapSurface(level, position, output.visibleState);
    }

    /**
     * Biomes are sampled on Minecraft's quart grid. A 16x16 chunk therefore tends
     * to reuse the same few biome holders dozens of times. Keep a tiny direct-mapped
     * cache in the scanner scratch instead of constructing Optional/registry paths
     * for every map pixel.
     */
    private String resolveSurfaceBiomeId(Level level,
            BlockPos.MutableBlockPos position, ScanScratch scratch) {
        scratch.beginSurfaceLevel(level);
        int quartX = position.getX() >> 2;
        int quartY = position.getY() >> 2;
        int quartZ = position.getZ() >> 2;
        long key = ((long) (quartX & 0x03FF_FFFF) << 38)
                | ((long) (quartZ & 0x03FF_FFFF) << 12)
                | (quartY & 0xFFFL);
        String cached = scratch.surfaceBiome(key);
        if (cached != null) return cached;
        String biomeId = "minecraft:plains";
        try {
            Holder<Biome> biomeHolder = level.getBiome(position);
            java.util.Optional<net.minecraft.resources.ResourceKey<Biome>> keyOpt =
                    biomeHolder.unwrapKey();
            if (keyOpt.isPresent()) {
                ResourceLocation location = keyOpt.get().location();
                biomeId = biomeIdStrings.get(location);
                if (biomeId == null) {
                    biomeId = location.toString();
                    biomeIdStrings.put(location, biomeId);
                }
            }
        } catch (Exception ignored) {
        }
        scratch.putSurfaceBiome(key, biomeId);
        return biomeId;
    }

    /**
     * Returns the first-air Y above the reliable land/seabed surface for cave scans.
     *
     * <p>The client does not consistently receive {@code OCEAN_FLOOR} heightmaps.
     * Querying that type can therefore return the dimension minimum and suppress
     * every cave pixel. {@code WORLD_SURFACE} is client-available; walking down only
     * through air and water reconstructs the land/seabed cutoff once per X/Z column.</p>
     */
    private static boolean isCaveLikeDimension(Level level) {
        return CaveDimensionProfile.shouldScanFromWorldTop(level);
    }

    private int getReliableCaveSurfaceCutoff(Level level, int blockX, int blockZ) {
        if (isCaveLikeDimension(level)) return Integer.MAX_VALUE;

        int minimumY = level.getMinBuildHeight();
        int maximumY = level.getMaxBuildHeight() - 1;
        int worldSurfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
        int startY = Math.max(minimumY, Math.min(maximumY, worldSurfaceY));
        BlockPos.MutableBlockPos probe = scanScratch.get().position(2,
                blockX, startY, blockZ);

        for (int y = startY; y >= minimumY; y--) {
            probe.setY(y);
            BlockState state = level.getBlockState(probe);
            if (state.isAir()) continue;
            if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) continue;
            boolean passThrough = caveStateClassifier.isCollisionEmpty(level, probe, state)
                    && state.getFluidState().isEmpty();
            if (passThrough) continue;
            return Math.min(level.getMaxBuildHeight(), y + 1);
        }
        // No trustworthy surface was found. Disable only the surface exclusion; do
        // not treat the dimension minimum as a real ocean floor.
        return Integer.MAX_VALUE;
    }

    private int getHighestY(Level level, int blockX, int blockZ) {
        return getHighestY(level, null, blockX, blockZ);
    }

    private int getHighestY(Level level, LevelChunk loadedChunk,
            int blockX, int blockZ) {
        boolean isCaveLike = isCaveLikeDimension(level);
        int minBuildHeight = level.getMinBuildHeight();
        BlockPos.MutableBlockPos pos = scanScratch.get().position(1,
                blockX, 0, blockZ);

        if (isCaveLike) {
            int startY = level.getMaxBuildHeight() - 1;
            boolean foundAir = false;
            for (int y = startY; y >= minBuildHeight; y--) {
                pos.setY(y);
                BlockState state = loadedChunk == null
                        ? level.getBlockState(pos) : loadedChunk.getBlockState(pos);
                if (!foundAir) {
                    if (state.isAir()) {
                        foundAir = true;
                    }
                } else {
                    if (isVisibleMapSurface(level, pos, state)) {
                        return y;
                    }
                }
            }
            return minBuildHeight;
        } else {
            int highestY = loadedChunk == null
                    ? level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ)
                    : loadedChunk.getHeight(Heightmap.Types.WORLD_SURFACE,
                            blockX & 15, blockZ & 15);
            for (int y = highestY; y >= minBuildHeight; y--) {
                pos.setY(y);
                BlockState state = loadedChunk == null
                        ? level.getBlockState(pos) : loadedChunk.getBlockState(pos);
                if (isVisibleMapSurface(level, pos, state)) {
                    return y;
                }
            }
            return minBuildHeight;
        }
    }

    private int getColumnColor(Level level, int blockX, int blockZ, int currentY) {
        BlockPos.MutableBlockPos pos = scanScratch.get().position(0,
                blockX, currentY, blockZ);
        BlockState targetState = level.getBlockState(pos);

        // Repair stale cached columns produced before torches became map-invisible.
        // Resolve the actual surface below instead of returning a black pixel.
        if (isMapInvisibleDecoration(targetState)) {
            BlockPos.MutableBlockPos probe = scanScratch.get().position(1,
                    blockX, currentY, blockZ);
            for (int y = currentY - 1; y >= level.getMinBuildHeight(); y--) {
                probe.setY(y);
                BlockState candidate = level.getBlockState(probe);
                if (isVisibleMapSurface(level, probe, candidate)) {
                    return getColumnColor(level, blockX, blockZ, y);
                }
            }
            return 0;
        }

        String blockId = visualClassifier.info(targetState).blockId();
        Integer overrideColor = MapConfig.blockColorOverrides.get(blockId);
        if (overrideColor != null) {
            return argbToAbgr(overrideColor);
        }

        // Emissive blocks use the same sampled texture resolver as ordinary Accurate
        // colors. Glow is added separately, so lava no longer receives a yellow RGB
        // replacement before rendering.
        if (targetState.getLightEmission() > 0) {
            return getEmissiveFeatureColor(level, pos, targetState);
        }

        // Vanilla-accurate water depth shading with transitional dithering (caro)
        // Transitions between shallow (1-2), medium (5-6) and deep (10+) using
        // checkerboard dither (3-4, 7-9)
        if (targetState.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
            int depth = 0;
            BlockPos.MutableBlockPos depthPos = scanScratch.get().position(1,
                    blockX, currentY, blockZ);
            while (depth < 30) {
                int checkY = currentY - depth - 1;
                if (checkY < level.getMinBuildHeight())
                    break;
                depthPos.setY(checkY);
                if (!level.getBlockState(depthPos).getFluidState().is(net.minecraft.tags.FluidTags.WATER))
                    break;
                depth++;
            }

            // Vanilla water MapColor base (col index 12 in MapColor = 0x3F76E4)
            int waterBase = MapConfig.blockColourMode == 1
                    ? MapColor.WATER.col
                    : Minecraft.getInstance().getBlockColors().getColor(targetState, level, pos, 0);
            if (waterBase == -1) waterBase = MapColor.WATER.col;
            int wr = (waterBase >> 16) & 0xFF;
            int wg = (waterBase >> 8) & 0xFF;
            int wb = waterBase & 0xFF;

            // Define three base shades for water depth (shallow, medium, deep)
            float shade;
            if (depth <= 3) {
                shade = 1.0f;  // Shallow water (0-3 blocks deep)
            } else if (depth <= 8) {
                shade = 0.85f; // Medium water (4-8 blocks deep)
            } else {
                shade = 0.70f; // Deep water (9+ blocks deep)
            }

            int r = Math.round(wr * shade);
            int g = Math.round(wg * shade);
            int b = Math.round(wb * shade);

            return 0xFF000000 | (b << 16) | (g << 8) | r; // ABGR (NativeImage format)
        }

        boolean isLeaves = targetState.is(net.minecraft.tags.BlockTags.LEAVES);
        boolean isCherry = targetState.is(Blocks.CHERRY_LEAVES);
        boolean isGrass = targetState.is(Blocks.GRASS_BLOCK);
        MapColor mapColor = targetState.getMapColor(level, pos);
        boolean isWood = (mapColor == MapColor.WOOD) || targetState.is(net.minecraft.tags.BlockTags.PLANKS) || targetState.is(net.minecraft.tags.BlockTags.LOGS);
        int rgb = resolveBlockRgb(level, pos, targetState, mapColor);
        if (rgb == 0) return 0;
        if (MapConfig.blockColourMode == 0) {
            rgb = makeColorRich(rgb, isLeaves, isCherry, isGrass, isWood);
        }

        // Convert MapColor RGB to ABGR for NativeImage
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;

        float shade = calculateTerrainShade(level, blockX, blockZ, currentY);

        // Procedural micro-texture noise for organic feel (stable and fast coordinate hash)
        long hash = ((long) blockX * 312251L) ^ ((long) blockZ * 4390321L);
        hash = (hash ^ (hash >>> 16)) * 0x85ebca6bL;
        hash = (hash ^ (hash >>> 13)) * 0xc2b2ae35L;
        float noise = (float) (hash & 0xFFFF) / 65535.0f;
        float noiseVal = noise * 2.0f - 1.0f; // -1.0 to 1.0

        float variation = 0.0f;
        if (isLeaves) {
            variation = 0.07f * noiseVal; // Speckled leaves foliage noise (+/- 7%)
        } else if (targetState.is(Blocks.GRASS_BLOCK) || targetState.is(Blocks.DIRT)
                || targetState.is(Blocks.SAND) || targetState.is(Blocks.GRAVEL)) {
            variation = 0.025f * noiseVal; // Subtle ground noise (+/- 2.5%)
        }

        shade *= (1.0f + variation);

        red = Math.max(0, Math.min(255, (int) (red * shade)));
        green = Math.max(0, Math.min(255, (int) (green * shade)));
        blue = Math.max(0, Math.min(255, (int) (blue * shade)));

        // NativeImage uses ABGR: (0xFF << 24) | (blue << 16) | (green << 8) | red
        return 0xFF000000 | (blue << 16) | (green << 8) | red;
    }

    /**
     * ACCURATE uses Minecraft's registered BlockColors provider (including biome
     * tint and modded providers). VANILLA intentionally uses the small MapColor
     * palette used by vanilla maps.
     */
    private int resolveBlockRgb(Level level, BlockPos pos, BlockState state, MapColor fallback) {
        String blockId = visualClassifier.info(state).blockId();
        MapTextureManager textureManager = MapTextureManager.getInstance();

        if (MapConfig.blockColourMode == 1 && fallback != MapColor.NONE) {
            return fallback.col;
        }

        int sampled = textureManager.resolveBlockColor(blockId, 0);
        if (sampled == 0 || sampled == 0xFFFFFFFF) {
            if (state.is(Blocks.CHERRY_LEAVES)) return 0xE0A1B8;
            if (fallback == MapColor.NONE) return 0;
            sampled = 0xFF000000 | fallback.col;
        }

        /* Cherry leaves use their own pink texture and must not receive the generic
         * foliage biome tint. Other leaves still use the registered tint provider. */
        if (visualClassifier.info(state).fixedTextureColor()) return sampled & 0x00FFFFFF;

        BlockTintPolicy policy = visualClassifier.tintPolicy(state);
        if (policy == BlockTintPolicy.NONE) return sampled & 0x00FFFFFF;

        int tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
        if (tint == -1) return sampled & 0x00FFFFFF;
        float strength = policy == BlockTintPolicy.GRASS ? 0.90f : 0.95f;
        return SurfaceColorizer.applyBiomeTint(
                sampled, 0xFF000000 | (tint & 0x00FFFFFF), strength) & 0x00FFFFFF;
    }

    /** OFF is flat, 2D keeps a restrained north-lit relief, and 3D evaluates a
     * four-direction height gradient for a stronger embossed terrain reading. */
    private float calculateTerrainShade(Level level, int x, int z, int centerY) {
        if (MapConfig.terrainSlopes <= 0) return 1.0f;
        int north = getTerrainHeightForSlope(level, x, z - 1, centerY);
        if (MapConfig.terrainSlopes == 1) {
            int delta = centerY - north;
            return Math.max(0.88f, Math.min(1.12f, 1.0f + delta * 0.025f));
        }

        int south = getTerrainHeightForSlope(level, x, z + 1, centerY);
        int west = getTerrainHeightForSlope(level, x - 1, z, centerY);
        int east = getTerrainHeightForSlope(level, x + 1, z, centerY);
        float gradientX = Math.max(-8.0f, Math.min(8.0f, (west - east) * 0.5f));
        float gradientZ = Math.max(-8.0f, Math.min(8.0f, (north - south) * 0.5f));

        // Simulated light from north-west. A small edge term darkens steep breaks,
        // producing clearer relief without pretending to render real 3D geometry.
        float directional = gradientX * 0.032f + gradientZ * 0.045f;
        float edge = Math.min(0.10f,
                (Math.abs(west - east) + Math.abs(north - south)) * 0.008f);
        float localPeak = centerY > north && centerY > south && centerY > west && centerY > east
                ? 0.035f : 0.0f;
        return Math.max(0.70f, Math.min(1.30f, 1.0f + directional - edge + localPeak));
    }

    /**
     * Slope sampling must stay cheap: calling the full column resolver four more
     * times for every map pixel makes 3D relief several times slower. Open-sky
     * dimensions can use the chunk heightmap directly. Ceiling dimensions only
     * inspect a small band around the already resolved centre surface, which is
     * enough to describe local relief without walking the whole Nether column.
     */
    private int getTerrainHeightForSlope(Level level, int x, int z, int referenceY) {
        BlockPos.MutableBlockPos pos = scanScratch.get().position(2, x, 0, z);
        if (!isCaveLikeDimension(level)) {
            int top = Math.min(level.getMaxBuildHeight() - 1,
                    level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
            int bottom = Math.max(level.getMinBuildHeight(), top - 8);
            for (int y = top; y >= bottom; y--) {
                pos.setY(y);
                BlockState state = level.getBlockState(pos);
                if (isVisibleMapSurface(level, pos, state)) return y;
            }
            return referenceY;
        }

        int top = Math.min(level.getMaxBuildHeight() - 1, referenceY + 8);
        int bottom = Math.max(level.getMinBuildHeight(), referenceY - 16);
        for (int y = top; y >= bottom; y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            if (isVisibleMapSurface(level, pos, state)) return y;
        }
        return referenceY;
    }

    /**
     * Xaero-style surface visibility. Thin light sources are skipped so the block
     * below remains visible. Leaves are explicitly accepted even when a particular
     * state reports MapColor.NONE; Accurate mode can still sample their texture.
     */
    private boolean isVisibleMapSurface(Level level, BlockPos pos, BlockState state) {
        return visualClassifier.isVisibleSurface(level, pos, state, MapConfig.displayFlowers);
    }

    private boolean isMapInvisibleDecoration(BlockState state) {
        return visualClassifier.isInvisibleDecoration(state);
    }

    private int argbToAbgr(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        return (alpha << 24) | (blue << 16) | (green << 8) | red;
    }

    /**
     * Explicit Refresh Map action: synchronously re-scans every loaded column in
     * the circular area, independently of the selected progressive reveal order.
     */
    public void scanAroundPlayer(Minecraft mc, int radius) {
        if (mc.level == null || mc.player == null)
            return;

        if (CaveMode.isActive(mc)) {
            if (!CaveMode.isFullView(mc)) {
                CaveMapManager.getInstance().setActiveLayer(CaveMode.getLayerY(mc));
            }
            CavePipeline.getInstance().requestRefresh(mc, radius);
            return;
        }

        double px = mc.player.getX();
        double pz = mc.player.getZ();

        int centerBlockX = (int) Math.floor(px);
        int centerBlockZ = (int) Math.floor(pz);
        int radiusSq = radius * radius;

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz <= radiusSq) {
                    scanColumnIfLoaded(mc, centerBlockX + dx, centerBlockZ + dz);
                }
            }
        }
    }

    public int makeColorRich(int rgb, boolean isLeaves, boolean isCherry,
            boolean isGrass, boolean isWood) {
        int red = (rgb >>> 16) & 0xFF;
        int green = (rgb >>> 8) & 0xFF;
        int blue = rgb & 0xFF;
        float luma = red * 0.2126f + green * 0.7152f + blue * 0.0722f;
        float saturation = isCherry ? 1.05f : (isLeaves || isGrass ? 1.10f : 1.06f);
        float brightness = isLeaves ? 0.88f : (isWood ? 0.92f : 0.97f);
        red = Math.max(0, Math.min(255, Math.round((luma + (red - luma) * saturation) * brightness)));
        green = Math.max(0, Math.min(255, Math.round((luma + (green - luma) * saturation) * brightness)));
        blue = Math.max(0, Math.min(255, Math.round((luma + (blue - luma) * saturation) * brightness)));
        return (red << 16) | (green << 8) | blue;
    }

    private static final class ScanScratch {
        private static final int SURFACE_CACHE_SIZE = 64;
        private final BlockPos.MutableBlockPos[] positions = {
                new BlockPos.MutableBlockPos(),
                new BlockPos.MutableBlockPos(),
                new BlockPos.MutableBlockPos(),
                new BlockPos.MutableBlockPos()
        };
        private final WaterFloor waterFloor = new WaterFloor();
        private final SurfaceColumnSample surfaceSample = new SurfaceColumnSample();
        private final SurfaceTopSample surfaceTop = new SurfaceTopSample();
        private final CaveColumnData.Builder caveColumnBuilder =
                new CaveColumnData.Builder();
        private final byte[] lightLevels = new byte[256];
        private final long[] surfacePacked = new long[256];
        private final int[] surfaceTints = new int[256];
        private final boolean[] surfaceValid = new boolean[256];
        private final long[] biomeKeys = new long[SURFACE_CACHE_SIZE];
        private final String[] biomeValues = new String[SURFACE_CACHE_SIZE];
        private final String[] paletteBiomeIds = new String[SURFACE_CACHE_SIZE];
        private final String[] paletteBlockIds = new String[SURFACE_CACHE_SIZE];
        private final long[] paletteValues = new long[SURFACE_CACHE_SIZE];
        private MapManager.Region paletteRegion;
        private Level surfaceLevel;

        private BlockPos.MutableBlockPos position(int slot, int x, int y, int z) {
            return positions[slot].set(x, y, z);
        }

        private void beginSurfaceLevel(Level level) {
            if (surfaceLevel == level) return;
            surfaceLevel = level;
            paletteRegion = null;
            java.util.Arrays.fill(biomeValues, null);
            java.util.Arrays.fill(paletteBiomeIds, null);
            java.util.Arrays.fill(paletteBlockIds, null);
        }

        private void beginSurfaceSlice(Level level, MapManager.Region region) {
            beginSurfaceLevel(level);
            if (paletteRegion == region) return;
            paletteRegion = region;
            java.util.Arrays.fill(paletteBiomeIds, null);
            java.util.Arrays.fill(paletteBlockIds, null);
        }

        private String surfaceBiome(long key) {
            int slot = (int) (key ^ (key >>> 32)) & (SURFACE_CACHE_SIZE - 1);
            String value = biomeValues[slot];
            return value != null && biomeKeys[slot] == key ? value : null;
        }

        private void putSurfaceBiome(long key, String value) {
            int slot = (int) (key ^ (key >>> 32)) & (SURFACE_CACHE_SIZE - 1);
            biomeKeys[slot] = key;
            biomeValues[slot] = value;
        }

        private long surfacePaletteIndices(MapManager.Region region,
                String biomeId, String blockId) {
            if (paletteRegion != region) {
                paletteRegion = region;
                java.util.Arrays.fill(paletteBiomeIds, null);
                java.util.Arrays.fill(paletteBlockIds, null);
            }
            int hash = 31 * biomeId.hashCode() + blockId.hashCode();
            int slot = hash & (SURFACE_CACHE_SIZE - 1);
            if (biomeId.equals(paletteBiomeIds[slot])
                    && blockId.equals(paletteBlockIds[slot])) {
                return paletteValues[slot];
            }
            long value = region.getOrAddSurfacePaletteIndices(biomeId, blockId);
            paletteBiomeIds[slot] = biomeId;
            paletteBlockIds[slot] = blockId;
            paletteValues[slot] = value;
            return value;
        }
    }

    private static final class SurfaceChunkStage {
        private final long[] packed = new long[256];
        private final int[] tints = new int[256];
        private final byte[] lights = new byte[256];
        private int cursor;
        private MapRequestLane publicationLane = MapRequestLane.MINIMAP;

        private void promote(MapRequestLane lane) {
            if (lane != null && lane.strongerThan(publicationLane)) {
                publicationLane = lane;
            }
        }
    }

    private static final class SurfaceTopSample {
        private int surfaceY;
        private BlockState actualState;
        private BlockState visibleState;
        private MapVisualClassifier.VisualInfo visual;
        private boolean visible;

        private void reset() {
            surfaceY = 0;
            actualState = null;
            visibleState = null;
            visual = null;
            visible = false;
        }
    }

    private static final class SurfaceColumnSample {
        private long packed = SURFACE_DATA_UNAVAILABLE;
        private int tint = SurfaceTintData.UNKNOWN;
        private byte light;

        private void reset() {
            packed = SURFACE_DATA_UNAVAILABLE;
            tint = SurfaceTintData.UNKNOWN;
            light = 0;
        }
    }
}
