package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapVisualClassifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.BitSet;
import java.util.Optional;

/**
 * Converts one current-format Anvil chunk NBT payload into the primitive cave tile
 * archive without constructing or ticketing a gameplay chunk.
 */
final class CaveWorldSaveChunkDecoder {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final ThreadLocal<CaveColumnData.Builder> BUILDERS =
            ThreadLocal.withInitial(CaveColumnData.Builder::new);

    private final CaveStateClassifier classifier = CaveStateClassifier.getInstance();
    private final MapVisualClassifier visualClassifier = MapVisualClassifier.getInstance();
    private final CaveColorResolver colors = CaveColorResolver.getInstance();

    CaveChunkTile.Snapshot decode(CompoundTag source, int chunkX, int chunkZ,
            int minimumY, int maximumY) {
        if (source == null || maximumY <= minimumY) return null;
        CompoundTag root = source.contains("Level", Tag.TAG_COMPOUND)
                ? source.getCompound("Level") : source;
        ListTag sectionTags = root.getList("sections", Tag.TAG_COMPOUND);
        if (sectionTags.isEmpty()) return null;

        DecodedChunk chunk = DecodedChunk.decode(sectionTags, minimumY, maximumY);
        CaveColumnData[] columns = new CaveColumnData[CaveChunkTile.COLUMN_COUNT];
        BitSet scanned = new BitSet(CaveChunkTile.COLUMN_COUNT);
        BitSet fullHeight = new BitSet(CaveChunkTile.COLUMN_COUNT);

        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int index = CaveChunkTile.index(localX, localZ);
                CaveColumnData column = scanColumn(chunk, localX, localZ,
                        minimumY, maximumY);
                columns[index] = column;
                scanned.set(index);
                if (column.fullHeightComplete()) fullHeight.set(index);
            }
        }
        return new CaveChunkTile.Snapshot(chunkX, chunkZ, 1L,
                scanned, fullHeight, columns);
    }

    private CaveColumnData scanColumn(DecodedChunk chunk, int localX, int localZ,
            int minimumY, int maximumY) {
        int startY = findUndergroundStart(chunk, localX, localZ,
                minimumY, maximumY - 1);
        if (startY <= minimumY) {
            return CaveColumnData.emptyScanned(minimumY, startY, true);
        }

        CaveColumnData.Builder builder = BUILDERS.get();
        builder.reset();
        boolean inOpenRun = false;
        int runTopY = startY;
        int waterDepth = 0;
        boolean runHadWater = false;
        boolean runHadOtherFluid = false;
        boolean runFluidEmissive = false;
        int runFluidColor = 0;
        int runFluidAlpha = 0;
        int runFluidY = 0;
        int runFluidDepth = 0;
        int runEmissiveColor = 0;
        int runEmissiveAlpha = 0;
        int runEmissiveY = 0;

        int y = startY;
        while (y >= minimumY) {
            BlockState state = chunk.stateAt(localX, y, localZ);
            byte kind = classifier.classify(state);

            if (kind == CaveStateClassifier.WATER) {
                if (!inOpenRun) {
                    inOpenRun = true;
                    runTopY = y;
                    waterDepth = 0;
                    runHadWater = false;
                    runHadOtherFluid = false;
                    runFluidEmissive = false;
                    runFluidColor = 0;
                    runFluidAlpha = 0;
                    runFluidY = 0;
                    runFluidDepth = 0;
                    runEmissiveColor = 0;
                    runEmissiveAlpha = 0;
                    runEmissiveY = 0;
                }
                runHadWater = true;
                waterDepth++;
                int fluidColor = colors.resolveDenseOfflineFluid(state, null);
                if (fluidColor != 0 && runFluidColor == 0) {
                    runFluidColor = fluidColor;
                    runFluidAlpha = visualClassifier.fluidOverlayOpacity(state);
                    runFluidY = y;
                }
                runFluidDepth++;
                y--;
                continue;
            }

            if (kind == CaveStateClassifier.OTHER_FLUID) {
                if (!inOpenRun) {
                    inOpenRun = true;
                    runTopY = y;
                    waterDepth = 0;
                    runHadWater = false;
                    runHadOtherFluid = false;
                    runFluidEmissive = false;
                    runFluidColor = 0;
                    runFluidAlpha = 0;
                    runFluidY = 0;
                    runFluidDepth = 0;
                    runEmissiveColor = 0;
                    runEmissiveAlpha = 0;
                    runEmissiveY = 0;
                }
                runHadOtherFluid = true;
                int fluidColor = colors.resolveDenseOfflineFluid(state, null);
                if (fluidColor != 0 && runFluidColor == 0) {
                    runFluidColor = fluidColor;
                    runFluidAlpha = visualClassifier.fluidOverlayOpacity(state);
                    runFluidY = y;
                }
                runFluidDepth++;
                if (state.getLightEmission() > 0
                        || visualClassifier.info(state).emissive()) {
                    runFluidEmissive = true;
                }
                y--;
                continue;
            }

            if (kind == CaveStateClassifier.AIR) {
                if (!inOpenRun) {
                    inOpenRun = true;
                    runTopY = y;
                    waterDepth = 0;
                    runHadWater = false;
                    runHadOtherFluid = false;
                    runFluidEmissive = false;
                    runFluidColor = 0;
                    runFluidAlpha = 0;
                    runFluidY = 0;
                    runFluidDepth = 0;
                    runEmissiveColor = 0;
                    runEmissiveAlpha = 0;
                    runEmissiveY = 0;
                }
                y--;
                continue;
            }

            CaveStateClassifier.StateInfo info = classifier.info(state);
            MapVisualClassifier.VisualInfo visual = visualClassifier.info(state);
            if (inOpenRun && CaveProjectionSemantics.isOpenDecoration(
                    state, visual, info.collisionEmpty())) {
                if (visual.emissive() && runEmissiveColor == 0) {
                    runEmissiveColor = colors.resolveDenseOffline(state, null, 0);
                    runEmissiveAlpha = visual.overlayOpacity();
                    runEmissiveY = y;
                }
                y--;
                continue;
            }

            if (inOpenRun) {
                int color = colors.resolveDenseOffline(state, null,
                        0);
                byte flags = runHadWater ? CaveColumnData.FLAG_WATER : 0;
                if (runHadOtherFluid) flags |= CaveColumnData.FLAG_FLUID;
                if (state.getLightEmission() > 0) {
                    flags |= CaveColumnData.FLAG_EMISSIVE;
                }
                builder.add(runTopY, y, color, flags,
                        runFluidColor, runFluidAlpha, runFluidY, 0,
                        runFluidDepth, runFluidEmissive
                                ? CaveColumnData.FLUID_FLAG_EMISSIVE : 0,
                        runEmissiveColor, runEmissiveAlpha,
                        runEmissiveY, 0);
                inOpenRun = false;
                waterDepth = 0;
                runHadWater = false;
                runHadOtherFluid = false;
                runFluidEmissive = false;
                runFluidColor = 0;
                runFluidAlpha = 0;
                runFluidY = 0;
                runFluidDepth = 0;
                runEmissiveColor = 0;
                runEmissiveAlpha = 0;
                runEmissiveY = 0;
            }
            y--;
        }
        return builder.build(minimumY, startY, true);
    }

    private int findUndergroundStart(DecodedChunk chunk, int localX, int localZ,
            int minimumY, int topY) {
        for (int y = topY; y >= minimumY; y--) {
            BlockState state = chunk.stateAt(localX, y, localZ);
            CaveStateClassifier.StateInfo info = classifier.info(state);
            MapVisualClassifier.VisualInfo visual = visualClassifier.info(state);
            if (CaveProjectionSemantics.isTerrainEntry(
                    state, visual, info.collisionEmpty())) return y - 1;
        }
        return minimumY;
    }

    private static final class DecodedChunk {
        private final int minimumSectionY;
        private final Section[] sections;

        private DecodedChunk(int minimumSectionY, Section[] sections) {
            this.minimumSectionY = minimumSectionY;
            this.sections = sections;
        }

        static DecodedChunk decode(ListTag sectionTags, int minimumY, int maximumY) {
            int minimumSectionY = Math.floorDiv(minimumY, 16);
            int maximumSectionY = Math.floorDiv(maximumY - 1, 16);
            Section[] sections = new Section[maximumSectionY - minimumSectionY + 1];
            for (int i = 0; i < sectionTags.size(); i++) {
                CompoundTag sectionTag = sectionTags.getCompound(i);
                int sectionY = sectionTag.getByte("Y");
                int target = sectionY - minimumSectionY;
                if (target < 0 || target >= sections.length) continue;
                sections[target] = Section.decode(sectionTag.getCompound("block_states"));
            }
            return new DecodedChunk(minimumSectionY, sections);
        }

        BlockState stateAt(int localX, int y, int localZ) {
            int sectionIndex = Math.floorDiv(y, 16) - minimumSectionY;
            if (sectionIndex < 0 || sectionIndex >= sections.length) return AIR;
            Section section = sections[sectionIndex];
            return section == null ? AIR : section.stateAt(localX, y & 15, localZ);
        }
    }

    private static final class Section {
        private final BlockState[] palette;
        private final long[] data;
        private final int bits;
        private final int valuesPerLong;
        private final long mask;

        private Section(BlockState[] palette, long[] data, int bits) {
            this.palette = palette;
            this.data = data;
            this.bits = bits;
            this.valuesPerLong = bits == 0 ? 0 : 64 / bits;
            this.mask = bits == 0 ? 0L : (1L << bits) - 1L;
        }

        static Section decode(CompoundTag blockStates) {
            if (blockStates == null || blockStates.isEmpty()) {
                return new Section(new BlockState[] { AIR }, new long[0], 0);
            }
            ListTag paletteTag = blockStates.getList("palette", Tag.TAG_COMPOUND);
            if (paletteTag.isEmpty()) {
                return new Section(new BlockState[] { AIR }, new long[0], 0);
            }
            BlockState[] palette = new BlockState[paletteTag.size()];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = decodeState(paletteTag.getCompound(i));
            }
            long[] data = blockStates.contains("data", Tag.TAG_LONG_ARRAY)
                    ? blockStates.getLongArray("data") : new long[0];
            if (palette.length == 1 || data.length == 0) {
                return new Section(palette, new long[0], 0);
            }
            int bits = detectBits(palette.length, data.length);
            return new Section(palette, data, bits);
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

        private static int detectBits(int paletteSize, int longCount) {
            int minimum = Math.max(4, ceilLog2(paletteSize));
            for (int bits = minimum; bits <= 32; bits++) {
                int valuesPerLong = 64 / bits;
                if (valuesPerLong == 0) break;
                int expected = (4096 + valuesPerLong - 1) / valuesPerLong;
                if (expected == longCount) return bits;
            }
            return Math.max(minimum, Math.min(32, (longCount * 64) / 4096));
        }
    }

    private static BlockState decodeState(CompoundTag stateTag) {
        try {
            ResourceLocation id = ResourceLocation.parse(stateTag.getString("Name"));
            Block block = BuiltInRegistries.BLOCK.get(id);
            if (block == null) return AIR;
            BlockState state = block.defaultBlockState();
            if (!stateTag.contains("Properties", Tag.TAG_COMPOUND)) return state;
            CompoundTag properties = stateTag.getCompound("Properties");
            for (String name : properties.getAllKeys()) {
                Property<?> property = block.getStateDefinition().getProperty(name);
                if (property == null) continue;
                state = setProperty(state, property, properties.getString(name));
            }
            return state;
        } catch (Throwable ignored) {
            return AIR;
        }
    }

    private static <T extends Comparable<T>> BlockState setProperty(
            BlockState state, Property<T> property, String text) {
        Optional<T> value = property.getValue(text);
        return value.map(candidate -> state.setValue(property, candidate)).orElse(state);
    }

    private static int ceilLog2(int value) {
        if (value <= 1) return 0;
        return 32 - Integer.numberOfLeadingZeros(value - 1);
    }
}
