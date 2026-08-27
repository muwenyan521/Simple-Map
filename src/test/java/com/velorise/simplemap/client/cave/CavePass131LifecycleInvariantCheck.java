package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapRequestLane;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS131 source durability, presentation ownership and no-hole fallback. */
public final class CavePass131LifecycleInvariantCheck {
    private CavePass131LifecycleInvariantCheck() { }

    public static void main(String[] args) throws Exception {
        Path client = Path.of("src/main/java/com/velorise/simplemap/client");
        Path cave = client.resolve("cave");
        String cache = Files.readString(cave.resolve("DecodedWorldRegionCache.java"));
        String importer = Files.readString(
                cave.resolve("CaveNativeRegionImportService.java"));
        String projection = Files.readString(
                cave.resolve("CaveRegionProjectionService.java"));
        String unified = Files.readString(
                cave.resolve("UnifiedCaveTextureManager.java"));
        String facade = Files.readString(client.resolve("CaveTextureManager.java"));
        String telemetry = Files.readString(
                client.resolve("MapPipelineTelemetry.java"));

        require(cache.contains("void reclassify(MapRequestLane replacement)")
                        && importer.contains("durableCaveInFlight")
                        && importer.contains("lease.reclassify(MapRequestLane.PREFETCH)")
                        && importer.contains("CAVE_SOURCE_ARCHIVE_COMMITTED_OFFSCREEN"),
                "admitted Cave sources do not survive viewport exit as bounded prefetch");
        require(projection.contains("activePresentationGenerations")
                        && projection.contains("presentationGeneration")
                        && projection.contains("request.foregroundGeneration")
                        && unified.contains("loadingPresentationGenerations")
                        && unified.contains("displayedPresentationGenerations")
                        && unified.contains("previousPresentationGenerations")
                        && unified.contains("presentationGenerationOwned"),
                "loading/displayed generation ownership is not explicit end-to-end");
        require(facade.contains("minimapFallbackAvailable")
                        && facade.contains("fallbackSafe")
                        && facade.contains("CAVE_MINIMAP_WRITER_EXPANSION_BLOCKED")
                        && facade.contains("policy=no_hole_before_full"),
                "minimap writer can still expand without current-or-fallback core coverage");
        require(unified.contains("CAVE_PAGE_HOLE_REASON")
                        && unified.contains("archive_resident_mask")
                        && unified.contains("cave_projection_present")
                        && unified.contains("cave_presentation_resident")
                        && telemetry.contains("recordCavePageHole"),
                "Cave holes are not classified across source/archive/projection/presentation");

        CavePresentationGeneration ticket = new CavePresentationGeneration(
                42L, 7L, "minecraft:overworld", CaveView.LAYERED,
                64, 75, MapRequestLane.MINIMAP);
        require(ticket.matches("minecraft:overworld", CaveView.LAYERED,
                        64, 75, MapRequestLane.MINIMAP),
                "presentation ticket rejects its own immutable identity");
        require(!ticket.matches("minecraft:overworld", CaveView.LAYERED,
                        64, 58, MapRequestLane.MINIMAP),
                "presentation ticket does not fence a superseded exact Top-Y");

        System.out.println("CAVE_PASS131_LIFECYCLE_INVARIANTS_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
