package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PASS79 guard for versioned native-region resubmission, bounded foreground
 * release and emissive feature preservation in the vertical cave archive.
 */
public final class CaveArchiveFrontierEmissiveCheck {
    private CaveArchiveFrontierEmissiveCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String importer = Files.readString(root.resolve(
                "CaveNativeRegionImportService.java"));
        String projection = Files.readString(root.resolve(
                "CaveRegionProjectionService.java"));
        String manager = Files.readString(root.resolve(
                "UnifiedCaveTextureManager.java"));
        String decoded = Files.readString(root.resolve(
                "DecodedWorldChunkSource.java"));
        String lod = Files.readString(root.resolve("CaveLodTree.java"));
        String cimg = Files.readString(root.resolve("CaveRegionImageCache.java"));
        String archiveStore = Files.readString(root.resolve("CaveRegionStore.java"));
        String style = Files.readString(root.resolve("CaveProjectionStyle.java"));

        require(importer.contains("long[] submittedSourceRevisions")
                        && importer.contains(
                                "long[] foregroundSubmittedSourceRevisions")
                        && importer.contains(
                                "foregroundSubmittedSourceRevisions[ordinal]")
                        && importer.contains("foregroundProjectionMask")
                        && importer.contains(
                                "CAVE_NATIVE_REGION_INCREMENTAL_SUBMIT")
                        && importer.contains("lastProjectionLeaseRefreshMs")
                        && !importer.contains(
                                "CAVE_NATIVE_REGION_SOURCE_REVISION_RESUBMITTED")
                        && !importer.contains("private long submittedMask;"),
                "native-region projection is not versioned per child page");

        require(projection.contains("FOREGROUND_RELEASE_SLICE = 64")
                        && projection.contains(
                                "CAVE_REGION_FOREGROUND_FRONTIER_READY")
                        && projection.contains("order=page_local_priority_ack")
                        && projection.contains("if ((request.completedMask & bit) == 0L)")
                        && projection.contains("int[] pixelsUnsafe()")
                        && projection.contains("long[] knownRowsUnsafe()"),
                "foreground region release lacks deterministic scanline publication");

        require(manager.contains(
                                "long stageDeadline = Math.min(deadline, stageStarted + localSlice)")
                        && manager.contains("fullscreenActive ? 900_000L : 450_000L")
                        && manager.contains("imported.pixelsUnsafe()")
                        && manager.contains("imported.knownRowsUnsafe()")
                        && manager.contains(
                                "CAVE_REGION_BRANCH_AUTHORITY_STAGED"),
                "region CPU authority remains coupled to the tiny GPU deadline");

        require(decoded.contains("boolean runHadEmissive = false;")
                        && decoded.contains("int runEmissiveColor = 0;")
                        && decoded.contains("runFluidAlpha")
                        && decoded.contains("FLUID_FLAG_EMISSIVE")
                        && !decoded.contains("blendArchiveEmissive")
                        && decoded.contains("openVisual.emissive()")
                        && decoded.contains("runFluidEmissive = true")
                        && decoded.contains("flags |= CaveColumnData.FLAG_EMISSIVE"),
                "vertical cave archive still discards emissive cave features");

        require(lod.contains("cave_v12_")
                        && cimg.contains("private static final int VERSION = 9;")
                        && archiveStore.contains(
                                "private static final int REGION_VERSION = 6;")
                        && archiveStore.contains(
                                "private static final int SNAPSHOT_VERSION = 9;")
                        && style.contains("STYLE_SIGNATURE_VERSION = 20")
                        && style.contains(
                                "return 31 * hash + STYLE_SIGNATURE_VERSION;"),
                "cave source/presentation cache namespaces were not isolated");

        verifyEmissiveStyling();
        verifyBlockLightStyling();
        System.out.println("CAVE_ARCHIVE_FRONTIER_EMISSIVE_PASS");
    }

    private static void verifyEmissiveStyling() {
        int[] source = new int[64 * 64];
        short[] heights = new short[64 * 64];
        byte[] flags = new byte[64 * 64];
        byte[] lights = new byte[64 * 64];
        java.util.Arrays.fill(heights, (short) -46);
        int center = 32 * 64 + 32;
        source[center] = 0xFF182840;
        source[center + 1] = 0xFF303030;
        int[] ordinary = CavePageStyler.style(source, heights, null, flags,
                lights, CaveView.LAYERED, -32);
        flags[center] = DenseCaveTile.FLAG_EMISSIVE;
        int[] glowing = CavePageStyler.style(source, heights, null, flags,
                lights, CaveView.LAYERED, -32);
        require(rgbSum(glowing[center]) > rgbSum(ordinary[center])
                        && glowing[center + 1] != ordinary[center + 1],
                "emissive cave material is not brightened or haloed");
    }

    private static void verifyBlockLightStyling() {
        int[] source = new int[64 * 64];
        short[] heights = new short[64 * 64];
        byte[] flags = new byte[64 * 64];
        byte[] darkLights = new byte[64 * 64];
        byte[] brightLights = new byte[64 * 64];
        java.util.Arrays.fill(heights, (short) -46);
        int center = 32 * 64 + 32;
        source[center] = 0xFF203050;
        source[center + 1] = 0xFF303030;
        brightLights[center] = 15;
        int[] dark = CavePageStyler.style(source, heights, null, flags,
                darkLights, CaveView.LAYERED, -32);
        int[] lit = CavePageStyler.style(source, heights, null, flags,
                brightLights, CaveView.LAYERED, -32);
        require(rgbSum(lit[center]) > rgbSum(dark[center])
                        && lit[center + 1] != dark[center + 1],
                "stored block light is not driving cave brightness and halo");

        int[] compatibility = CavePageStyler.style(source, heights, null, flags,
                null, CaveView.LAYERED, -32);
        int[] neighbourOnlySource = source.clone();
        neighbourOnlySource[center] = 0;
        int[] neighbourOnly = CavePageStyler.style(neighbourOnlySource, heights,
                null, flags, null, CaveView.LAYERED, -32);
        require(compatibility[center + 1] == neighbourOnly[center + 1],
                "missing legacy light arrays incorrectly create a halo");
    }

    private static int rgbSum(int abgr) {
        return (abgr & 0xFF) + ((abgr >>> 8) & 0xFF)
                + ((abgr >>> 16) & 0xFF);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
