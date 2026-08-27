package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS59 guard for atomic source pages, archive replay, minimap priority and HiFi LOD. */
public final class CaveAtomicPageHiFiLodCheck {
    private CaveAtomicPageHiFiLodCheck() { }

    public static void main(String[] args) throws Exception {
        Path cave = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String cache = Files.readString(cave.resolve("DecodedWorldRegionCache.java"));
        String reader = Files.readString(cave.resolve("CaveWorldSaveReader.java"));
        String repository = Files.readString(cave.resolve("CaveTileRepository.java"));
        String scheduler = Files.readString(cave.resolve("CaveDisplayScheduler.java"));
        String policy = Files.readString(cave.resolve("CaveScreenSpacePolicy.java"));
        String manager = Files.readString(cave.resolve("UnifiedCaveTextureManager.java"));
        String branch = Files.readString(cave.resolve("CaveBranchAtlas.java"));
        String archive = Files.readString(cave.resolve("archive/CaveArchiveV2Service.java"));
        String decoded = Files.readString(cave.resolve("DecodedWorldChunkSource.java"));
        String persistence = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/persistence/v2/MapPersistenceV2Service.java"));
        String regionLod = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/lod/RegionLodDeriver.java"));
        String overview = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapOverviewTextureManager.java"));
        String surfaceManager = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapTextureManager.java"));

        require(cache.contains("reservedForegroundDecodes")
                        && cache.contains("PageReservation")
                        && cache.contains("consumeLocked"),
                "foreground page decode slots are not atomically reserved");
        require(reader.contains("reserveForegroundDecodes(requiredDecodes)")
                        && reader.contains("requestReservedLease")
                        && !reader.contains("if (lease.isImmediatelyDeferred()) break"),
                "one page can still fragment into one-chunk retry passes");
        require(repository.contains("touchDisplayPageLocked")
                        && repository.contains("isPageProjectionReady")
                        && repository.contains("ingestDecodedArchive"),
                "page revisions or durable archive publication regressed");
        require(decoded.contains("repository.ingestDecodedArchive")
                        && persistence.contains("loadCaveArchives")
                        && persistence.contains("decodeCave"),
                "decoded Anvil archives are not durable across client restarts");
        require(archive.contains("indexedFingerprint == contentFingerprint")
                        && archive.contains("compact.contentFingerprint()")
                        && !archive.contains("indexedRevision >= compact.revision()"),
                "archive fingerprints are still treated as monotonic counters");
        require(scheduler.contains("&& !evictWeakViewportTask(priority)")
                        && policy.contains("MINIMAP) normal = pressured ? 2 : 4")
                        && policy.contains("MINIMAP) normal = pressured ? 2 : 6")
                        && surfaceManager.contains("? 8 : 24")
                        && !surfaceManager.contains("Math.min(4, 8 - active)"),
                "player-centred minimap demand can still be starved by fullscreen work");
        require(!manager.contains("FULLSCREEN_WAVEFRONT_GRACE_MS")
                        && manager.contains("averageAbgr(p0, p1, p2, p3)"),
                "fast wavefront sweep or coverage-preserving cave LOD is missing");
        require(branch.contains("new CaveAtlasTexture(atlasSize, true,")
                        && regionLod.contains("packMeanColor")
                        && overview.contains("preserveThinFeatures")
                        && overview.contains("salient"),
                "far zoom still selects one texel instead of aggregating visible detail");
        System.out.println("CAVE_ATOMIC_PAGE_HIFI_LOD_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
