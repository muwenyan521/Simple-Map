package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.cave.archive.CaveArchiveV2Service;
import com.velorise.simplemap.client.MapBlockEntityVisualResolver;
import com.velorise.simplemap.client.MapCancellationToken;
import com.velorise.simplemap.client.MapVisualClassifier;
import com.velorise.simplemap.client.SurfaceTintData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.biome.Biome;

import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable decoded world chunk shared by surface and cave projections.
 *
 * <p>The object owns section palettes, biomes, heightmaps, light arrays and
 * block-entity visual metadata. Full Cave, Layered Cave and offline surface
 * reconstruction all consume this same decoded source instead of independently
 * reading or DataFixing the Anvil chunk.</p>
 */
final class DecodedWorldChunkSource implements CaveDisplayProjector.ChunkSource {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** Palettes repeat the same ids across thousands of sections. */
    private static final ConcurrentHashMap<String, Block> BLOCK_ID_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ResourceLocation> RESOURCE_ID_CACHE =
            new ConcurrentHashMap<>();
    /*
     * Offline Surface reconstruction used to call ResourceLocation.toString() for
     * block and biome ids in every one of 256 columns per decoded chunk. A large
     * fullscreen reconstruction therefore produced millions of short-lived Strings.
     * Registry objects are stable for a world session and the number of distinct
     * ids is tiny relative to the number of columns, so retain canonical id Strings.
     */
    private static final ConcurrentHashMap<Block, String> SURFACE_BLOCK_ID_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Biome, String> SURFACE_BIOME_ID_CACHE =
            new ConcurrentHashMap<>();
    private static final String SURFACE_AIR_ID = "minecraft:air";
    private static final String SURFACE_PLAINS_ID = "minecraft:plains";

    private final int chunkX;
    private final int chunkZ;
    private final int minimumY;
    private final int maximumY;
    private final int minimumSectionY;
    private final Section[] sections;
    private final int[] surfaceHeights;
    private final Registry<Biome> biomeRegistry;
    private final Map<Integer, CompoundTag> blockEntities;
    private final Map<Integer, BlockState> blockEntityVisualStates =
            new ConcurrentHashMap<>();
    /* A decoded source can be reused by Full and Layered projection workers.
     * Keep mutable positions thread-local so concurrent read-only projections do not
     * race on one BlockPos instance. */
    private final ThreadLocal<BlockPos.MutableBlockPos> positions =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private final CaveColorResolver colors = CaveColorResolver.getInstance();
    private final MapVisualClassifier visuals = MapVisualClassifier.getInstance();
    private final MapBlockEntityVisualResolver blockEntityVisuals =
            MapBlockEntityVisualResolver.getInstance();
    /**
     * Small projection memo for Full Cave and adjacent Layered Cave Y values.
     * Decoded sections are immutable, so repeated viewport requests can reuse the
     * same dense tile instead of rescanning the full vertical column range.
     */
    private static final int MAX_CAVE_PROJECTION_CACHE = 6;
    private final LinkedHashMap<Long, DenseCaveTile> caveProjectionCache =
            new LinkedHashMap<>(4, 0.75f, true);
    /**
     * Style-independent vertical cavity runs. Built once from the decoded Anvil
     * payload before any visible cave projection, then reused for Full and every
     * exact Layered Top-Y.
     */
    private static final int ARCHIVE_COLUMNS_PER_SLICE = 32;
    private static final int ARCHIVE_VERTICAL_STEPS_PER_SLICE = 64;
    private static final long ARCHIVE_SLICE_BUDGET_NANOS = 2_000_000L;

    private volatile CaveChunkTile.Snapshot verticalArchive;
    /*
     * PASS139: decoded .mca Cave archive construction is retained and sliced.
     * The previous ensureVerticalArchive() scanned all 256 columns in one CPU
     * continuation; the latest log captured a 1.37 s source-fanout outlier.
     */
    private CaveColumnData[] verticalArchiveColumns;
    private BitSet verticalArchiveScanned;
    private BitSet verticalArchiveFullHeight;
    private int verticalArchiveNextColumn;
    private ArchiveColumnCursor verticalArchiveColumnCursor;
    /** Stable content identity derived from the decoded Anvil payload. */
    private final long sourceRevision;
    /**
     * Surface must never treat a proto-generation Anvil snapshot as final terrain.
     * Newly generated chunks can be visible to the client before the server has
     * flushed the final FULL chunk to the region file. Those earlier statuses can
     * contain the base surface while FEATURES (trees/vegetation) are still absent.
     */
    private final boolean authoritativeSurfaceSource;
    private final CaveStateClassifier caveGeometry = CaveStateClassifier.getInstance();

    private DecodedWorldChunkSource(int chunkX, int chunkZ, int minimumY, int maximumY,
            int minimumSectionY, Section[] sections, int[] surfaceHeights,
            Registry<Biome> biomeRegistry, Map<Integer, CompoundTag> blockEntities,
            long sourceRevision, boolean authoritativeSurfaceSource) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minimumY = minimumY;
        this.maximumY = maximumY;
        this.minimumSectionY = minimumSectionY;
        this.sections = sections;
        this.surfaceHeights = surfaceHeights;
        this.biomeRegistry = biomeRegistry;
        this.blockEntities = blockEntities == null ? Map.of() : Map.copyOf(blockEntities);
        this.sourceRevision = sourceRevision == 0L ? 1L : sourceRevision;
        this.authoritativeSurfaceSource = authoritativeSurfaceSource;
    }

    static DecodedWorldChunkSource decode(CompoundTag source, int chunkX, int chunkZ,
            int minimumY, int maximumY, Registry<Biome> biomeRegistry) {
        return decode(source, chunkX, chunkZ, minimumY, maximumY,
                biomeRegistry, new MapCancellationToken(null));
    }

    static DecodedWorldChunkSource decode(CompoundTag source, int chunkX, int chunkZ,
            int minimumY, int maximumY, Registry<Biome> biomeRegistry,
            MapCancellationToken token) {
        if (source == null || maximumY <= minimumY) return null;
        MapCancellationToken effectiveToken = token == null
                ? new MapCancellationToken(null) : token;
        effectiveToken.checkpoint("chunk-palette-decode-start");
        CompoundTag root = source.contains("Level", Tag.TAG_COMPOUND)
                ? source.getCompound("Level") : source;
        ListTag sectionTags = root.getList("sections", Tag.TAG_COMPOUND);
        if (sectionTags.isEmpty()) return null;

        int minimumSectionY = Math.floorDiv(minimumY, 16);
        int maximumSectionY = Math.floorDiv(maximumY - 1, 16);
        Section[] sections = new Section[maximumSectionY - minimumSectionY + 1];
        for (int i = 0; i < sectionTags.size(); i++) {
            if ((i & 3) == 0) effectiveToken.checkpoint("chunk-section-" + i);
            CompoundTag sectionTag = sectionTags.getCompound(i);
            int sectionY = sectionTag.getByte("Y");
            int target = sectionY - minimumSectionY;
            if (target < 0 || target >= sections.length) continue;
            sections[target] = Section.decode(sectionTag, biomeRegistry);
        }

        effectiveToken.checkpoint("chunk-sections-finished");
        int[] heights = decodeHeightmap(root, minimumY, maximumY);
        boolean needsSurfaceScan = heights == null;
        if (heights == null) heights = new int[DenseCaveTile.COLUMN_COUNT];
        effectiveToken.checkpoint("chunk-block-entities-start");
        Map<Integer, CompoundTag> blockEntities = decodeBlockEntities(
                root, chunkX, chunkZ, minimumY, effectiveToken);
        long sourceRevision = stableSourceRevision(source, root, sectionTags,
                chunkX, chunkZ, minimumY, maximumY);
        String chunkStatus = root.getString("Status");
        boolean authoritativeSurfaceSource = "full".equals(chunkStatus)
                || "minecraft:full".equals(chunkStatus);
        DecodedWorldChunkSource decoded = new DecodedWorldChunkSource(chunkX, chunkZ,
                minimumY, maximumY, minimumSectionY, sections, heights,
                biomeRegistry, blockEntities, sourceRevision,
                authoritativeSurfaceSource);
        if (needsSurfaceScan) decoded.fillSurfaceHeightsByScan(effectiveToken);
        effectiveToken.checkpoint("chunk-source-ready");
        return decoded;
    }

    private static long stableSourceRevision(CompoundTag source, CompoundTag root,
            ListTag sectionTags, int chunkX, int chunkZ,
            int minimumY, int maximumY) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, chunkX);
        hash = mix(hash, chunkZ);
        hash = mix(hash, minimumY);
        hash = mix(hash, maximumY);
        int dataVersion = source.contains("DataVersion", Tag.TAG_ANY_NUMERIC)
                ? source.getInt("DataVersion")
                : root.contains("DataVersion", Tag.TAG_ANY_NUMERIC)
                        ? root.getInt("DataVersion") : -1;
        hash = mix(hash, dataVersion);
        hash = mix(hash, root.getLong("LastUpdate"));
        hash = mix(hash, root.getLong("InhabitedTime"));
        hash = mix(hash, root.getString("Status").hashCode());
        hash = mix(hash, sectionTags.size());
        for (int index = 0; index < sectionTags.size(); index++) {
            CompoundTag section = sectionTags.getCompound(index);
            hash = mix(hash, section.getByte("Y"));
            CompoundTag blocks = section.getCompound("block_states");
            ListTag palette = blocks.getList("palette", Tag.TAG_COMPOUND);
            hash = mix(hash, palette.size());
            for (int entry = 0; entry < palette.size(); entry++) {
                hash = mix(hash, palette.getCompound(entry).hashCode());
            }
            long[] blockData = blocks.getLongArray("data");
            hash = mix(hash, blockData.length);
            for (long value : blockData) hash = mix(hash, value);
            CompoundTag biomes = section.getCompound("biomes");
            ListTag biomePalette = biomes.getList("palette", Tag.TAG_STRING);
            hash = mix(hash, biomePalette.hashCode());
            long[] biomeData = biomes.getLongArray("data");
            for (long value : biomeData) hash = mix(hash, value);
            hash = mix(hash, java.util.Arrays.hashCode(
                    section.getByteArray("BlockLight")));
            hash = mix(hash, java.util.Arrays.hashCode(
                    section.getByteArray("SkyLight")));
        }
        hash = mix(hash, root.getCompound("Heightmaps").hashCode());
        hash = mix(hash, root.getList("block_entities", Tag.TAG_COMPOUND).hashCode());
        hash = mix(hash, root.getList("TileEntities", Tag.TAG_COMPOUND).hashCode());
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        return hash == 0L ? 1L : hash;
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    @Override
    public int chunkX() {
        return chunkX;
    }

    @Override
    public int chunkZ() {
        return chunkZ;
    }

    @Override
    public int minimumY() {
        return minimumY;
    }

    @Override
    public int maximumY() {
        return maximumY;
    }

    @Override
    public int surfaceY(int localX, int localZ) {
        return surfaceHeights[DenseCaveTile.index(localX, localZ)];
    }

    @Override
    public BlockState stateAt(int localX, int y, int localZ) {
        int sectionIndex = Math.floorDiv(y, 16) - minimumSectionY;
        if (sectionIndex < 0 || sectionIndex >= sections.length) return AIR;
        Section section = sections[sectionIndex];
        return section == null ? AIR : section.stateAt(localX, y & 15, localZ);
    }

    @Override
    public byte sectionKind(int y) {
        int sectionIndex = Math.floorDiv(y, 16) - minimumSectionY;
        if (sectionIndex < 0 || sectionIndex >= sections.length) {
            return CaveTileScanContext.ALL_AIR;
        }
        Section section = sections[sectionIndex];
        return section == null ? CaveTileScanContext.ALL_AIR
                : section.sectionKind();
    }

    @Override
    public byte sectionKind(int localX, int y, int localZ) {
        int sectionIndex = Math.floorDiv(y, 16) - minimumSectionY;
        if (sectionIndex < 0 || sectionIndex >= sections.length) {
            return CaveTileScanContext.ALL_AIR;
        }
        Section section = sections[sectionIndex];
        return section == null ? CaveTileScanContext.ALL_AIR
                : section.columnKind(localX, localZ);
    }

    @Override
    public int sectionBottom(int y) {
        return Math.max(minimumY, Math.floorDiv(y, 16) * 16);
    }

    @Override
    public BlockState visualStateAt(int localX, int y, int localZ) {
        BlockState actual = stateAt(localX, y, localZ);
        return visualStateAt(localX, y, localZ, actual);
    }

    @Override
    public BlockState visualStateAt(int localX, int y, int localZ,
            BlockState actual) {
        // The overwhelming majority of chunks have no block entities. Avoid
        // boxing one Integer key for every scanned cave block in that common case.
        if (blockEntities.isEmpty()) return actual;
        int key = blockEntityKey(localX, y, localZ, minimumY);
        CompoundTag tag = blockEntities.get(key);
        if (tag == null) return actual;
        return blockEntityVisualStates.computeIfAbsent(key, ignored ->
                blockEntityVisuals.resolveOffline(actual, tag));
    }

    @Override
    public BlockGetter blockGetter() {
        return EmptyBlockGetter.INSTANCE;
    }

    @Override
    public BlockPos position(int localX, int y, int localZ) {
        return positions.get().set((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
    }

    @Override
    public int lightAt(int localX, int y, int localZ) {
        int sectionIndex = Math.floorDiv(y, 16) - minimumSectionY;
        if (sectionIndex < 0 || sectionIndex >= sections.length) return 0;
        Section section = sections[sectionIndex];
        return section == null ? 0 : section.blockLight(localX, y & 15, localZ);
    }

    @Override
    public int skyLightAt(int localX, int y, int localZ) {
        int sectionIndex = Math.floorDiv(y, 16) - minimumSectionY;
        if (sectionIndex < 0 || sectionIndex >= sections.length) return 0;
        Section section = sections[sectionIndex];
        return section == null ? 0 : section.skyLight(localX, y & 15, localZ);
    }

    private Biome biomeAt(int localX, int y, int localZ) {
        int sectionIndex = Math.floorDiv(y, 16) - minimumSectionY;
        if (sectionIndex < 0 || sectionIndex >= sections.length) return null;
        Section section = sections[sectionIndex];
        return section == null ? null : section.biomeAt(localX, y & 15, localZ);
    }

    @Override
    public int resolveBlockColor(BlockState state, int localX, int y, int localZ) {
        return colors.resolveDenseOffline(state, biomeAt(localX, y, localZ), 0);
    }

    @Override
    public int resolveFluidColor(BlockState state, int localX, int y, int localZ) {
        BlockState fluidState = state.getFluidState().isEmpty()
                ? state : state.getFluidState().createLegacyBlock();
        return colors.resolveDenseOfflineFluid(
                fluidState, biomeAt(localX, y, localZ));
    }

    synchronized DenseCaveTile projectCave(CaveDisplayProjector projector,
            CaveView view, int layerY, DenseCaveTile.Source tileSource,
            MapCancellationToken token) {
        CaveView effectiveView = view == null ? CaveView.FULL : view;
        int projectionY = effectiveView == CaveView.FULL
                ? Integer.MIN_VALUE : layerY;
        long key = ((long) effectiveView.ordinal() << 32)
                ^ (projectionY & 0xFFFFFFFFL);
        DenseCaveTile cached = caveProjectionCache.get(key);
        if (cached != null) return cached;

        /*
         * One decoded Anvil chunk now has one source transaction. PASS74 first
         * produced a direct display tile, then built the vertical archive later and
         * invalidated that first image. Build the reusable archive before any public
         * projection so Full, Layered and CIMG consume identical source authority.
         */
        CaveChunkTile.Snapshot archive = ensureVerticalArchive(token);
        if (archive == null) {
            /*
             * Archive construction is intentionally resumable. Do not bypass the
             * retained source transaction with a second full-column direct scan;
             * the caller will retry this already-decoded source after the next
             * bounded archive slice.
             */
            return null;
        }
        DenseCaveTile projected = CaveArchiveProjector.project(archive,
                effectiveView, layerY, tileSource);
        if (projected == null) {
            projected = projector.project(this, effectiveView, layerY,
                    sourceRevision, tileSource, token);
        }
        caveProjectionCache.put(key, projected);
        while (caveProjectionCache.size() > MAX_CAVE_PROJECTION_CACHE) {
            var iterator = caveProjectionCache.entrySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
        return projected;
    }

    /**
     * PASS141 visible exact-page fast path.
     *
     * <p>Xaero's world-save reader decodes the sixteen chunks of one 4x4 map tile
     * and immediately writes the cave tile for the requested cave start. It does not
     * require a style-independent all-height archive to finish before the current
     * layer can become visible. Simple Map previously made every visible WORLD_SAVE
     * tile wait for all 256 vertical archive columns, then repeatedly re-leased the
     * same decoded chunk for retained archive slices. That produced minutes-long
     * black holes even though the NBT was already decoded.</p>
     *
     * <p>This method publishes the exact requested Cave representation directly from
     * the decoded immutable chunk. The durable vertical archive remains an
     * independent background product owned by the native importer. Both products use
     * the same source revision and CaveDisplayProjector semantics, so archive
     * completion never invalidates this already-useful exact tile merely because the
     * durable representation finished later.</p>
     */
    synchronized DenseCaveTile projectCaveImmediate(CaveDisplayProjector projector,
            CaveView view, int layerY, DenseCaveTile.Source tileSource,
            MapCancellationToken token) {
        CaveView effectiveView = view == null ? CaveView.FULL : view;
        int projectionY = effectiveView == CaveView.FULL
                ? Integer.MIN_VALUE : layerY;
        long key = ((long) effectiveView.ordinal() << 32)
                ^ (projectionY & 0xFFFFFFFFL);
        DenseCaveTile cached = caveProjectionCache.get(key);
        if (cached != null) return cached;

        MapCancellationToken effectiveToken = token == null
                ? new MapCancellationToken(null) : token;
        DenseCaveTile projected = projector.project(this, effectiveView, layerY,
                sourceRevision, tileSource, effectiveToken);
        caveProjectionCache.put(key, projected);
        while (caveProjectionCache.size() > MAX_CAVE_PROJECTION_CACHE) {
            var iterator = caveProjectionCache.entrySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
        return projected;
    }

    /**
     * Materializes the reusable source products for one decoded Anvil chunk.
     *
     * <p>This is deliberately a source-stage operation, not a presentation-stage
     * reader. The NBT payload and palettes have already been decoded once. Surface
     * receives its 256 immutable columns while Layered and Full receive the same
     * vertical archive. Later view changes only project these retained products; no
     * second RegionFileStorage read or palette decode is started.</p>
     */
    synchronized ProjectionBundle prepareProjectionBundle(boolean surfaceRequested,
            boolean caveRequested, boolean showFlowers,
            MapCancellationToken token) {
        SurfaceProjection[] surface = surfaceRequested
                && authoritativeSurfaceSource
                ? projectSurface(showFlowers) : null;
        CaveChunkTile.Snapshot archive = caveRequested
                ? ensureVerticalArchive(token) : null;
        return new ProjectionBundle(sourceRevision, surface, archive);
    }

    synchronized CaveChunkTile.Snapshot ensureVerticalArchive(
            MapCancellationToken token) {
        CaveChunkTile.Snapshot archive = verticalArchive;
        if (archive != null) {
            /*
             * DecodedWorldRegionCache can outlive the compact-archive resident LRU.
             * Re-ingestion is content-idempotent: an already-resident tile is
             * ignored, while an evicted tile is restored without a second disk
             * append. Full projection must never see indexed-complete source with
             * no resident CompactCaveTile.
             */
            CaveTileRepository repository = CaveTileRepository.getInstance();
            long expectedGeneration = repository.generation();
            if (!repository.isGenerationCurrent(expectedGeneration)) return null;
            CaveArchiveV2Service archiveService =
                    CaveArchiveV2Service.getInstance();
            if (!archiveService.isResident(archive.chunkX(), archive.chunkZ())
                    || archiveService.requiresCurrentSourceVerification(
                            archive.chunkX(), archive.chunkZ())) {
                repository.ingestDecodedArchive(archive, expectedGeneration);
            }
            return archive;
        }

        MapCancellationToken effectiveToken = token == null
                ? new MapCancellationToken(null) : token;
        if (verticalArchiveColumns == null) {
            verticalArchiveColumns =
                    new CaveColumnData[CaveChunkTile.COLUMN_COUNT];
            verticalArchiveScanned =
                    new BitSet(CaveChunkTile.COLUMN_COUNT);
            verticalArchiveFullHeight =
                    new BitSet(CaveChunkTile.COLUMN_COUNT);
            verticalArchiveNextColumn = 0;
            verticalArchiveColumnCursor = null;
        }

        long deadline = System.nanoTime() + ARCHIVE_SLICE_BUDGET_NANOS;
        int completedColumns = 0;
        while (verticalArchiveNextColumn < CaveChunkTile.COLUMN_COUNT
                && completedColumns < ARCHIVE_COLUMNS_PER_SLICE
                && System.nanoTime() < deadline) {
            effectiveToken.checkpoint("vertical-cave-archive-slice");

            int index = verticalArchiveNextColumn;
            int localX = index & 15;
            int localZ = index >>> 4;
            if (verticalArchiveColumnCursor == null) {
                verticalArchiveColumnCursor = beginArchiveColumn(
                        localX, localZ);
            }

            boolean completed = scanArchiveColumnSlice(
                    verticalArchiveColumnCursor, effectiveToken, deadline,
                    ARCHIVE_VERTICAL_STEPS_PER_SLICE);
            if (!completed) {
                break;
            }

            CaveColumnData column = verticalArchiveColumnCursor.completedData;
            verticalArchiveColumns[index] = column;
            verticalArchiveScanned.set(index);
            if (column != null && column.fullHeightComplete()) {
                verticalArchiveFullHeight.set(index);
            }
            verticalArchiveColumnCursor = null;
            verticalArchiveNextColumn++;
            completedColumns++;
        }

        if (verticalArchiveNextColumn < CaveChunkTile.COLUMN_COUNT) {
            return null;
        }

        archive = new CaveChunkTile.Snapshot(
                chunkX, chunkZ, sourceRevision,
                verticalArchiveScanned,
                verticalArchiveFullHeight,
                verticalArchiveColumns);
        effectiveToken.checkpoint("vertical-archive-built");
        CaveTileRepository repository = CaveTileRepository.getInstance();
        long expectedGeneration = repository.generation();
        if (!repository.isGenerationCurrent(expectedGeneration)) return null;
        verticalArchive = archive;
        verticalArchiveColumns = null;
        verticalArchiveScanned = null;
        verticalArchiveFullHeight = null;
        verticalArchiveNextColumn = 0;
        verticalArchiveColumnCursor = null;
        repository.mergeWorldSaveTile(archive, expectedGeneration);
        repository.ingestDecodedArchive(archive, expectedGeneration);
        return archive;
    }

    /** Starts one decoded-Anvil archive column without traversing it synchronously. */
    private ArchiveColumnCursor beginArchiveColumn(int localX, int localZ) {
        int top = Math.max(minimumY,
                Math.min(maximumY - 1, surfaceY(localX, localZ) + 1));
        return new ArchiveColumnCursor(localX, localZ, minimumY, top);
    }

    /**
     * Advances one decoded archive column in bounded vertical steps. The old
     * PASS139 outer slice yielded only between columns, so one pathological
     * modded/deep column could still consume tens or hundreds of milliseconds.
     * This mirrors the live CaveColumnScanCursor invariant: no full vertical
     * traversal is an indivisible source-fanout operation.
     */
    private boolean scanArchiveColumnSlice(ArchiveColumnCursor cursor,
            MapCancellationToken token, long deadlineNanos,
            int maximumSteps) {
        if (cursor.completedData != null) return true;
        int steps = 0;
        int stepLimit = Math.max(1, maximumSteps);
        while (steps < stepLimit && System.nanoTime() < deadlineNanos) {
            token.checkpoint("vertical-cave-column-slice");

            if (cursor.findingTerrainEntry) {
                if (cursor.y < cursor.minimumY) {
                    cursor.completedData = CaveColumnData.emptyScanned(
                            cursor.minimumY, cursor.minimumY, true);
                    return true;
                }
                int y = cursor.y;
                byte sectionKind = sectionKind(cursor.localX, y, cursor.localZ);
                int sectionBottom = sectionBottom(y);
                steps++;
                if (sectionKind == CaveTileScanContext.ALL_AIR) {
                    cursor.y = sectionBottom - 1;
                    continue;
                }
                if (sectionKind == CaveTileScanContext.ALL_SOLID_FAST) {
                    cursor.beginBody(y - 1);
                } else {
                    BlockState actual = stateAt(cursor.localX, y, cursor.localZ);
                    BlockState visualState = visualStateAt(
                            cursor.localX, y, cursor.localZ, actual);
                    CaveStateClassifier.StateInfo info = caveGeometry.info(actual);
                    MapVisualClassifier.VisualInfo visual = visuals.info(visualState);
                    if (CaveProjectionSemantics.isTerrainEntry(
                            actual, visual, info.collisionEmpty())) {
                        cursor.beginBody(y - 1);
                    } else {
                        cursor.y--;
                    }
                }
                if (!cursor.findingTerrainEntry
                        && cursor.startY <= cursor.minimumY) {
                    cursor.completedData = CaveColumnData.emptyScanned(
                            cursor.minimumY, cursor.startY, true);
                    return true;
                }
                continue;
            }

            if (cursor.y < cursor.minimumY) {
                cursor.completedData = cursor.builder.build(
                        cursor.minimumY, cursor.startY, true);
                return true;
            }

            int y = cursor.y;
            byte sectionKind = sectionKind(cursor.localX, y, cursor.localZ);
            int sectionBottom = sectionBottom(y);
            steps++;
            if (sectionKind == CaveTileScanContext.ALL_AIR) {
                if (!cursor.inOpenRun) cursor.beginRun(y);
                cursor.y = sectionBottom - 1;
                continue;
            }
            if (sectionKind == CaveTileScanContext.ALL_SOLID_FAST) {
                if (cursor.inOpenRun) {
                    BlockState floor = visualStateAt(
                            cursor.localX, y, cursor.localZ);
                    int color = resolveArchiveFloorColor(
                            floor, cursor.localX, y, cursor.localZ, 0);
                    byte flags = cursor.runHadWater
                            ? CaveColumnData.FLAG_WATER : 0;
                    if (cursor.runHadOtherFluid) flags |= CaveColumnData.FLAG_FLUID;
                    boolean floorEmissive = floor.getLightEmission() > 0
                            || visuals.info(floor).emissive();
                    if (floorEmissive || cursor.runHadEmissive) {
                        flags |= CaveColumnData.FLAG_EMISSIVE;
                    }
                    cursor.builder.add(cursor.runTopY, y, color, flags,
                            cursor.runFluidColor, cursor.runFluidAlpha,
                            cursor.runFluidY, cursor.runFluidLight,
                            cursor.runFluidDepth, cursor.runFluidEmissive
                                    ? CaveColumnData.FLUID_FLAG_EMISSIVE : 0,
                            cursor.runEmissiveColor, cursor.runEmissiveAlpha,
                            cursor.runEmissiveY, cursor.runEmissiveLight);
                    cursor.resetRun();
                }
                cursor.y = sectionBottom - 1;
                continue;
            }

            BlockState actual = stateAt(cursor.localX, y, cursor.localZ);
            BlockState visual = visualStateAt(
                    cursor.localX, y, cursor.localZ, actual);
            byte kind = caveGeometry.classify(actual);
            if (kind == CaveStateClassifier.WATER) {
                if (!cursor.inOpenRun) cursor.beginRun(y);
                cursor.runHadWater = true;
                int fluidColor = resolveFluidColor(
                        visual, cursor.localX, y, cursor.localZ);
                if (fluidColor != 0 && cursor.runFluidColor == 0) {
                    cursor.runFluidColor = fluidColor;
                    cursor.runFluidAlpha = visuals.fluidOverlayOpacity(visual);
                    cursor.runFluidY = y;
                    cursor.runFluidLight = Math.max(
                            visual.getLightEmission(),
                            lightAt(cursor.localX, y, cursor.localZ));
                }
                cursor.runFluidDepth++;
                cursor.y--;
                continue;
            }
            if (kind == CaveStateClassifier.OTHER_FLUID) {
                if (!cursor.inOpenRun) cursor.beginRun(y);
                cursor.runHadOtherFluid = true;
                int fluidColor = resolveFluidColor(
                        visual, cursor.localX, y, cursor.localZ);
                if (fluidColor != 0 && cursor.runFluidColor == 0) {
                    cursor.runFluidColor = fluidColor;
                    cursor.runFluidAlpha = visuals.fluidOverlayOpacity(visual);
                    cursor.runFluidY = y;
                    cursor.runFluidLight = Math.max(
                            visual.getLightEmission(),
                            lightAt(cursor.localX, y, cursor.localZ));
                }
                cursor.runFluidDepth++;
                if (visual.getLightEmission() > 0
                        || visuals.info(visual).emissive()) {
                    cursor.runHadEmissive = true;
                    cursor.runFluidEmissive = true;
                }
                cursor.y--;
                continue;
            }
            if (actual.isAir()) {
                if (!cursor.inOpenRun) cursor.beginRun(y);
                cursor.y--;
                continue;
            }

            CaveStateClassifier.StateInfo info = caveGeometry.info(actual);
            MapVisualClassifier.VisualInfo openVisual = visuals.info(visual);
            if (cursor.inOpenRun && CaveProjectionSemantics.isOpenDecoration(
                    actual, openVisual, info.collisionEmpty())) {
                if (visual.getLightEmission() > 0 || openVisual.emissive()) {
                    cursor.runHadEmissive = true;
                    int emissiveColor = resolveBlockColor(
                            visual, cursor.localX, y, cursor.localZ);
                    if (emissiveColor != 0 && cursor.runEmissiveColor == 0) {
                        cursor.runEmissiveColor = emissiveColor;
                        cursor.runEmissiveAlpha = openVisual.overlayOpacity();
                        cursor.runEmissiveY = y;
                        cursor.runEmissiveLight = Math.max(
                                visual.getLightEmission(),
                                lightAt(cursor.localX, y, cursor.localZ));
                    }
                }
                cursor.y--;
                continue;
            }
            if (cursor.inOpenRun) {
                int color = resolveArchiveFloorColor(
                        visual, cursor.localX, y, cursor.localZ, 0);
                byte flags = cursor.runHadWater
                        ? CaveColumnData.FLAG_WATER : 0;
                if (cursor.runHadOtherFluid) flags |= CaveColumnData.FLAG_FLUID;
                boolean floorEmissive = visual.getLightEmission() > 0
                        || openVisual.emissive();
                if (floorEmissive || cursor.runHadEmissive) {
                    flags |= CaveColumnData.FLAG_EMISSIVE;
                }
                cursor.builder.add(cursor.runTopY, y, color, flags,
                        cursor.runFluidColor, cursor.runFluidAlpha,
                        cursor.runFluidY, cursor.runFluidLight,
                        cursor.runFluidDepth, cursor.runFluidEmissive
                                ? CaveColumnData.FLUID_FLAG_EMISSIVE : 0,
                        cursor.runEmissiveColor, cursor.runEmissiveAlpha,
                        cursor.runEmissiveY, cursor.runEmissiveLight);
                cursor.resetRun();
            }
            cursor.y--;
        }
        return false;
    }

    private static final class ArchiveColumnCursor {
        private final int localX;
        private final int localZ;
        private final int minimumY;
        private final CaveColumnData.Builder builder =
                new CaveColumnData.Builder();

        private boolean findingTerrainEntry = true;
        private int startY;
        private int y;
        private boolean inOpenRun;
        private int runTopY;
        private boolean runHadWater;
        private boolean runHadOtherFluid;
        private boolean runHadEmissive;
        private boolean runFluidEmissive;
        private int runFluidColor;
        private int runFluidAlpha;
        private int runFluidY;
        private int runFluidLight;
        private int runFluidDepth;
        private int runEmissiveColor;
        private int runEmissiveAlpha;
        private int runEmissiveY;
        private int runEmissiveLight;
        private CaveColumnData completedData;

        private ArchiveColumnCursor(int localX, int localZ,
                int minimumY, int topY) {
            this.localX = localX;
            this.localZ = localZ;
            this.minimumY = minimumY;
            this.startY = topY;
            this.y = topY;
            builder.reset();
        }

        private void beginBody(int firstBodyY) {
            startY = firstBodyY;
            y = firstBodyY;
            findingTerrainEntry = false;
        }

        private void beginRun(int topY) {
            inOpenRun = true;
            runTopY = topY;
            runHadWater = false;
            runHadOtherFluid = false;
            runHadEmissive = false;
            runFluidEmissive = false;
            runFluidColor = 0;
            runFluidAlpha = 0;
            runFluidY = 0;
            runFluidLight = 0;
            runFluidDepth = 0;
            runEmissiveColor = 0;
            runEmissiveAlpha = 0;
            runEmissiveY = 0;
            runEmissiveLight = 0;
        }

        private void resetRun() {
            inOpenRun = false;
            runHadWater = false;
            runHadOtherFluid = false;
            runHadEmissive = false;
            runFluidEmissive = false;
            runFluidColor = 0;
            runFluidAlpha = 0;
            runFluidY = 0;
            runFluidLight = 0;
            runFluidDepth = 0;
            runEmissiveColor = 0;
            runEmissiveAlpha = 0;
            runEmissiveY = 0;
            runEmissiveLight = 0;
        }
    }

    private int resolveArchiveFloorColor(BlockState state, int localX, int y,
            int localZ, int waterDepth) {
        return colors.resolveDenseOffline(state, biomeAt(localX, y, localZ),
                Math.max(0, waterDepth));
    }

    private int findArchiveUndergroundStart(int localX, int localZ) {
        int top = Math.max(minimumY,
                Math.min(maximumY - 1, surfaceY(localX, localZ) + 1));
        for (int y = top; y >= minimumY; y--) {
            byte sectionKind = sectionKind(localX, y, localZ);
            if (sectionKind == CaveTileScanContext.ALL_AIR) {
                y = sectionBottom(y);
                continue;
            }
            if (sectionKind == CaveTileScanContext.ALL_SOLID_FAST) return y - 1;
            BlockState actual = stateAt(localX, y, localZ);
            BlockState visualState = visualStateAt(localX, y, localZ, actual);
            CaveStateClassifier.StateInfo info = caveGeometry.info(actual);
            MapVisualClassifier.VisualInfo visual = visuals.info(visualState);
            if (CaveProjectionSemantics.isTerrainEntry(
                    actual, visual, info.collisionEmpty())) return y - 1;
        }
        return minimumY;
    }

    boolean hasAuthoritativeSurfaceSource() {
        return authoritativeSurfaceSource;
    }

    SurfaceProjection[] projectSurface(boolean showFlowers) {
        /*
         * The projection is handed directly to SurfaceWorldSaveReconstructor, which
         * owns it until the client-thread commit. Retaining a second reference on
         * every DecodedWorldChunkSource kept 256 projection objects alive per cached
         * Anvil chunk after publication. Rebuild-on-repeat is cheaper than pinning
         * that payload, and the importer marks a source cell projected after one
         * successful handoff so normal operation does not repeat this work.
         */
        SurfaceProjection[] built = new SurfaceProjection[DenseCaveTile.COLUMN_COUNT];
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                built[DenseCaveTile.index(localX, localZ)] =
                        projectSurfaceColumn(localX, localZ, showFlowers);
            }
        }
        return built;
    }

    SurfaceProjection projectSurfaceColumn(int localX, int localZ, boolean showFlowers) {
        int top = Math.max(minimumY, Math.min(maximumY - 1, surfaceY(localX, localZ)));
        BlockState actual = AIR;
        BlockState visual = AIR;
        int visibleY = minimumY;
        BlockPos pos = position(localX, top, localZ);
        for (int y = top; y >= minimumY; y--) {
            actual = stateAt(localX, y, localZ);
            visual = visualStateAt(localX, y, localZ);
            pos = position(localX, y, localZ);
            if (visuals.isVisibleSurface(EmptyBlockGetter.INSTANCE, pos, visual, showFlowers)) {
                visibleY = y;
                break;
            }
        }
        if (actual.isAir() && visual.isAir()) return SurfaceProjection.emptyProjection();

        boolean fluid = !actual.getFluidState().isEmpty();
        boolean water = actual.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
        boolean lava = actual.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
        if (water) {
            /*
             * WORLD_SURFACE normally starts above the liquid surface, but modded or
             * partially regenerated chunks can point at a waterlogged plant lower in
             * the column. Using that Y as the fluid top makes kelp/seagrass columns
             * look artificially shallow and exposes them as long warm-ocean dashes.
             * Recover the top of the connected water column before measuring depth.
             */
            visibleY = findConnectedWaterSurfaceY(localX, localZ, visibleY);
            actual = stateAt(localX, visibleY, localZ);
            visual = visualStateAt(localX, visibleY, localZ, actual);
            pos = position(localX, visibleY, localZ);
        }
        int floorY = visibleY;
        BlockState paletteState = visual;
        if (water) {
            for (int y = visibleY; y >= minimumY; y--) {
                BlockState candidate = stateAt(localX, y, localZ);
                BlockState candidateVisual = visualStateAt(localX, y, localZ);
                boolean candidateWater = candidate.getFluidState()
                        .is(net.minecraft.tags.FluidTags.WATER);
                boolean waterloggedSolid = candidateWater
                        && !candidate.is(Blocks.WATER)
                        && !CaveStateClassifier.getInstance().info(candidate).collisionEmpty();
                if (waterloggedSolid) {
                    floorY = y;
                    paletteState = candidateVisual;
                    break;
                }
                if (candidateWater || candidate.isAir()) continue;
                if (CaveStateClassifier.getInstance().info(candidate).collisionEmpty()
                        && candidate.getFluidState().isEmpty()) continue;
                floorY = y;
                paletteState = candidateVisual;
                break;
            }
        }

        MapVisualClassifier.VisualInfo visualInfo = visuals.info(visual);
        Block surfaceBlock = paletteState.getBlock();
        String blockId = SURFACE_BLOCK_ID_CACHE.computeIfAbsent(surfaceBlock, block -> {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            return id == null ? SURFACE_AIR_ID : id.toString();
        });
        Biome biome = biomeAt(localX, visibleY, localZ);
        String biomeId = biome == null || biomeRegistry == null
                ? SURFACE_PLAINS_ID
                : SURFACE_BIOME_ID_CACHE.computeIfAbsent(biome, value -> {
                    ResourceLocation id = biomeRegistry.getKey(value);
                    return id == null ? SURFACE_PLAINS_ID : id.toString();
                });
        int light = Math.max(lightAt(localX, visibleY, localZ),
                lightAt(localX, Math.min(maximumY - 1, visibleY + 1), localZ));
        return new SurfaceProjection(false, visibleY, floorY, blockId,
                biomeId,
                light, lava || (!water && visualInfo.emissive()), fluid,
                !fluid && visualInfo.flower(), !fluid && visualInfo.leaves(),
                SurfaceTintData.UNKNOWN);
    }

    /** Returns the highest block in the connected water column. */
    private int findConnectedWaterSurfaceY(int localX, int localZ, int startY) {
        int surface = Math.max(minimumY, Math.min(maximumY - 1, startY));
        for (int y = surface + 1; y < maximumY; y++) {
            BlockState state = stateAt(localX, y, localZ);
            if (!state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) break;
            surface = y;
        }
        return surface;
    }

    long estimatedBytes() {
        // Reserve for surface metadata plus a small cave projection memo.
        long bytes = 64_000L + 256L * Integer.BYTES + 256L;
        for (Section section : sections) if (section != null) bytes += section.estimatedBytes();
        for (CompoundTag tag : blockEntities.values()) bytes += 128L + Math.min(16_384, tag.toString().length() * 2L);
        return Math.max(16_384L, bytes);
    }

    private static Map<Integer, CompoundTag> decodeBlockEntities(CompoundTag root,
            int chunkX, int chunkZ, int minimumY, MapCancellationToken token) {
        ListTag tags = root.getList("block_entities", Tag.TAG_COMPOUND);
        if (tags.isEmpty()) tags = root.getList("TileEntities", Tag.TAG_COMPOUND);
        if (tags.isEmpty()) return Map.of();
        Map<Integer, CompoundTag> result = new HashMap<>();
        for (int i = 0; i < tags.size(); i++) {
            if ((i & 15) == 0) token.checkpoint("chunk-block-entity-" + i);
            CompoundTag tag = tags.getCompound(i);
            if (!tag.contains("x", Tag.TAG_ANY_NUMERIC)
                    || !tag.contains("y", Tag.TAG_ANY_NUMERIC)
                    || !tag.contains("z", Tag.TAG_ANY_NUMERIC)) continue;
            int localX = Math.floorMod(tag.getInt("x"), 16);
            int localZ = Math.floorMod(tag.getInt("z"), 16);
            int y = tag.getInt("y");
            result.put(blockEntityKey(localX, y, localZ, minimumY), tag.copy());
        }
        return result;
    }

    private static int blockEntityKey(int localX, int y, int localZ, int minimumY) {
        return ((y - minimumY) << 8) | ((localZ & 15) << 4) | (localX & 15);
    }

    record ProjectionBundle(long sourceRevision,
            SurfaceProjection[] surfaceColumns,
            CaveChunkTile.Snapshot verticalArchive) {
    }

    record SurfaceProjection(boolean empty, int topY, int floorY, String blockId,
            String biomeId, int blockLight, boolean glowing, boolean fluid,
            boolean flower, boolean leaves, int tint) {
        private static final SurfaceProjection EMPTY = new SurfaceProjection(
                true, Short.MIN_VALUE, Short.MIN_VALUE, SURFACE_AIR_ID,
                SURFACE_PLAINS_ID, 0, false, false, false, false,
                SurfaceTintData.UNKNOWN);

        static SurfaceProjection emptyProjection() {
            return EMPTY;
        }
    }

    private void fillSurfaceHeightsByScan(MapCancellationToken token) {
        for (int localZ = 0; localZ < 16; localZ++) {
            token.checkpoint("surface-height-row-" + localZ);
            for (int localX = 0; localX < 16; localX++) {
                int height = minimumY;
                for (int y = maximumY - 1; y >= minimumY; y--) {
                    if (!stateAt(localX, y, localZ).isAir()) {
                        height = y;
                        break;
                    }
                }
                surfaceHeights[DenseCaveTile.index(localX, localZ)] = height;
            }
        }
    }

    private static int[] decodeHeightmap(CompoundTag root,
            int minimumY, int maximumY) {
        int[] result = new int[DenseCaveTile.COLUMN_COUNT];
        CompoundTag maps = root.getCompound("Heightmaps");
        long[] packed = maps.getLongArray("WORLD_SURFACE");
        if (packed.length == 0) return null;
        int bits = packed.length / 4;
        if (bits <= 0 || bits > 16) return null;
        int baseY = root.contains("yPos", Tag.TAG_ANY_NUMERIC)
                ? root.getInt("yPos") * 16 : minimumY;
        /* Heightmaps use Minecraft's SimpleBitStorage layout: entries never span
         * two longs. Treating it as one continuous bit stream shifts most columns
         * whenever 64 is not divisible by the entry width and produces incorrect
         * Full Cave start heights. */
        int valuesPerLong = 64 / bits;
        if (valuesPerLong <= 0
                || (result.length + valuesPerLong - 1) / valuesPerLong != packed.length) {
            return null;
        }
        long mask = (1L << bits) - 1L;
        for (int index = 0; index < result.length; index++) {
            int cell = index / valuesPerLong;
            int shift = (index - cell * valuesPerLong) * bits;
            int height = baseY + (int) ((packed[cell] >>> shift) & mask);
            result[index] = Math.max(minimumY,
                    Math.min(maximumY - 1, height));
        }
        return result;
    }

    private static final class Section {
        private final BlockState[] palette;
        private final long[] data;
        private final int bits;
        private final int valuesPerLong;
        private final long mask;
        private final byte[] blockLight;
        private final byte[] skyLight;
        private final Biome[] biomePalette;
        private final long[] biomeData;
        private final int biomeBits;
        private final int biomeValuesPerLong;
        private final long biomeMask;
        /** One conservative cave classification for each X/Z column in this section. */
        private final byte[] caveColumnKinds;
        private final byte caveSectionKind;

        private Section(BlockState[] palette, long[] data, int bits,
                byte[] blockLight, byte[] skyLight,
                Biome[] biomePalette, long[] biomeData, int biomeBits) {
            this.palette = palette;
            this.data = data;
            this.bits = bits;
            this.valuesPerLong = bits == 0 ? 0 : 64 / bits;
            this.mask = bits == 0 ? 0L : (1L << bits) - 1L;
            this.blockLight = validLight(blockLight);
            this.skyLight = validLight(skyLight);
            this.biomePalette = biomePalette;
            this.biomeData = biomeData;
            this.biomeBits = biomeBits;
            this.biomeValuesPerLong = biomeBits == 0 ? 0 : 64 / biomeBits;
            this.biomeMask = biomeBits == 0 ? 0L : (1L << biomeBits) - 1L;
            this.caveColumnKinds = classifyCaveColumns();
            byte summary = caveColumnKinds.length == 0
                    ? CaveTileScanContext.MIXED : caveColumnKinds[0];
            for (int i = 1; i < caveColumnKinds.length; i++) {
                if (caveColumnKinds[i] != summary) {
                    summary = CaveTileScanContext.MIXED;
                    break;
                }
            }
            this.caveSectionKind = summary;
        }

        static Section decode(CompoundTag sectionTag, Registry<Biome> biomeRegistry) {
            CompoundTag blockStates = sectionTag == null
                    ? new CompoundTag() : sectionTag.getCompound("block_states");
            BlockState[] palette;
            long[] data;
            int bits;
            if (blockStates == null || blockStates.isEmpty()) {
                palette = new BlockState[] { AIR };
                data = new long[0];
                bits = 0;
            } else {
                ListTag paletteTag = blockStates.getList("palette", Tag.TAG_COMPOUND);
                if (paletteTag.isEmpty()) {
                    palette = new BlockState[] { AIR };
                    data = new long[0];
                    bits = 0;
                } else {
                    palette = new BlockState[paletteTag.size()];
                    for (int i = 0; i < palette.length; i++) {
                        palette[i] = decodeState(paletteTag.getCompound(i));
                    }
                    data = blockStates.contains("data", Tag.TAG_LONG_ARRAY)
                            ? blockStates.getLongArray("data") : new long[0];
                    bits = palette.length == 1 || data.length == 0
                            ? 0 : detectBits(palette.length, data.length, 4096, 4);
                }
            }

            Biome[] biomePalette = new Biome[0];
            long[] biomeData = new long[0];
            int biomeBits = 0;
            CompoundTag biomes = sectionTag == null
                    ? new CompoundTag() : sectionTag.getCompound("biomes");
            ListTag biomePaletteTag = biomes.getList("palette", Tag.TAG_STRING);
            if (!biomePaletteTag.isEmpty()) {
                biomePalette = new Biome[biomePaletteTag.size()];
                for (int i = 0; i < biomePalette.length; i++) {
                    try {
                        ResourceLocation biomeId = cachedResourceId(
                                biomePaletteTag.getString(i));
                        biomePalette[i] = biomeRegistry == null || biomeId == null
                                ? null : biomeRegistry.get(biomeId);
                    } catch (Throwable ignored) {
                        biomePalette[i] = null;
                    }
                }
                biomeData = biomes.contains("data", Tag.TAG_LONG_ARRAY)
                        ? biomes.getLongArray("data") : new long[0];
                if (biomePalette.length > 1 && biomeData.length > 0) {
                    biomeBits = detectBits(biomePalette.length,
                            biomeData.length, 64, 1);
                }
            }

            byte[] blockLight = sectionTag != null
                    && sectionTag.contains("BlockLight", Tag.TAG_BYTE_ARRAY)
                    ? sectionTag.getByteArray("BlockLight") : null;
            byte[] skyLight = sectionTag != null
                    && sectionTag.contains("SkyLight", Tag.TAG_BYTE_ARRAY)
                    ? sectionTag.getByteArray("SkyLight") : null;
            return new Section(palette, data, bits, blockLight, skyLight,
                    biomePalette, biomeData, biomeBits);
        }

        byte sectionKind() {
            return caveSectionKind;
        }

        byte columnKind(int localX, int localZ) {
            return caveColumnKinds[(localZ & 15) * 16 + (localX & 15)];
        }

        private byte[] classifyCaveColumns() {
            byte[] result = new byte[256];
            if (palette.length == 0) {
                java.util.Arrays.fill(result, CaveTileScanContext.ALL_AIR);
                return result;
            }
            if (bits == 0 || data.length == 0) {
                BlockState state = palette[0];
                byte uniform = state.isAir() ? CaveTileScanContext.ALL_AIR
                        : CaveStateClassifier.getInstance().classify(state)
                                == CaveStateClassifier.SOLID_FAST
                                ? CaveTileScanContext.ALL_SOLID_FAST
                                : CaveTileScanContext.MIXED;
                java.util.Arrays.fill(result, uniform);
                return result;
            }
            CaveStateClassifier classifier = CaveStateClassifier.getInstance();
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    boolean allAir = true;
                    boolean allSolidFast = true;
                    for (int localY = 0; localY < 16; localY++) {
                        BlockState state = stateAt(localX, localY, localZ);
                        if (!state.isAir()) allAir = false;
                        if (classifier.classify(state)
                                != CaveStateClassifier.SOLID_FAST) {
                            allSolidFast = false;
                        }
                        if (!allAir && !allSolidFast) break;
                    }
                    result[localZ * 16 + localX] = allAir
                            ? CaveTileScanContext.ALL_AIR
                            : allSolidFast ? CaveTileScanContext.ALL_SOLID_FAST
                                    : CaveTileScanContext.MIXED;
                }
            }
            return result;
        }

    long estimatedBytes() {
            return 96L + (long) palette.length * 16L
                    + (long) data.length * Long.BYTES
                    + (blockLight == null ? 0L : blockLight.length)
                    + (skyLight == null ? 0L : skyLight.length)
                    + (long) biomePalette.length * 8L
                    + (long) biomeData.length * Long.BYTES
                    + caveColumnKinds.length;
        }

        BlockState stateAt(int localX, int localY, int localZ) {
            if (palette.length == 0) return AIR;
            if (bits == 0 || data.length == 0) return palette[0];
            int index = (localY << 8) | (localZ << 4) | localX;
            int cell = index / valuesPerLong;
            if (cell < 0 || cell >= data.length) return AIR;
            int shift = (index - cell * valuesPerLong) * bits;
            int paletteIndex = (int) ((data[cell] >>> shift) & mask);
            return paletteIndex >= 0 && paletteIndex < palette.length
                    ? palette[paletteIndex] : AIR;
        }

        int blockLight(int localX, int localY, int localZ) {
            return nibble(blockLight, (localY << 8) | (localZ << 4) | localX);
        }

        int skyLight(int localX, int localY, int localZ) {
            return nibble(skyLight, (localY << 8) | (localZ << 4) | localX);
        }

        Biome biomeAt(int localX, int localY, int localZ) {
            if (biomePalette.length == 0) return null;
            if (biomeBits == 0 || biomeData.length == 0) return biomePalette[0];
            int index = ((localY >> 2) << 4) | ((localZ >> 2) << 2) | (localX >> 2);
            int cell = index / biomeValuesPerLong;
            if (cell < 0 || cell >= biomeData.length) return biomePalette[0];
            int shift = (index - cell * biomeValuesPerLong) * biomeBits;
            int paletteIndex = (int) ((biomeData[cell] >>> shift) & biomeMask);
            return paletteIndex >= 0 && paletteIndex < biomePalette.length
                    ? biomePalette[paletteIndex] : biomePalette[0];
        }

        private static int detectBits(int paletteSize, int longCount,
                int valueCount, int minimumBits) {
            int paletteBits = Math.max(minimumBits,
                    ceilLog2(Math.max(1, paletteSize)));
            for (int candidate = paletteBits; candidate <= 32; candidate++) {
                int perLong = 64 / candidate;
                if (perLong > 0
                        && (valueCount + perLong - 1) / perLong == longCount) {
                    return candidate;
                }
            }
            return paletteBits;
        }

        private static byte[] validLight(byte[] data) {
            return data != null && data.length == 2048 ? data : null;
        }

        private static int nibble(byte[] data, int index) {
            if (data == null) return 0;
            int packed = data[index >> 1] & 0xFF;
            return (index & 1) == 0 ? packed & 0xF : (packed >>> 4) & 0xF;
        }
    }

    private static BlockState decodeState(CompoundTag stateTag) {
        String serializedId = stateTag.getString("Name");
        Block block = BLOCK_ID_CACHE.computeIfAbsent(serializedId, name -> {
            ResourceLocation id = cachedResourceId(name);
            Block resolved = id == null ? Blocks.AIR : BuiltInRegistries.BLOCK.get(id);
            return resolved == null ? Blocks.AIR : resolved;
        });
        BlockState state = block.defaultBlockState();
        CompoundTag properties = stateTag.getCompound("Properties");
        for (String name : properties.getAllKeys()) {
            Property<?> property = block.getStateDefinition().getProperty(name);
            if (property == null) continue;
            state = applyProperty(state, property, properties.getString(name));
        }
        return state;
    }

    private static ResourceLocation cachedResourceId(String serialized) {
        if (serialized == null || serialized.isBlank()) return null;
        try {
            return RESOURCE_ID_CACHE.computeIfAbsent(serialized, ResourceLocation::parse);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static <T extends Comparable<T>> BlockState applyProperty(
            BlockState state, Property<T> property, String serialized) {
        Optional<T> value = property.getValue(serialized);
        return value.map(candidate -> state.setValue(property, candidate)).orElse(state);
    }

    private static int ceilLog2(int value) {
        return value <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(value - 1);
    }
}
