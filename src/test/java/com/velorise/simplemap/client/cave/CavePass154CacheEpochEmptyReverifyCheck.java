package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS154 against persistent false-empty Cave authority. */
public final class CavePass154CacheEpochEmptyReverifyCheck {
    private CavePass154CacheEpochEmptyReverifyCheck() { }

    public static void main(String[] args) throws Exception {
        String schema = read("src/main/java/com/velorise/simplemap/client/cave/CaveCacheSchema.java");
        String archive = read("src/main/java/com/velorise/simplemap/client/cave/archive/CaveArchiveV2Service.java");
        String compact = read("src/main/java/com/velorise/simplemap/client/cave/archive/CompactCaveTile.java");
        String dense = read("src/main/java/com/velorise/simplemap/client/cave/DenseCaveTile.java");
        String repository = read("src/main/java/com/velorise/simplemap/client/cave/CaveTileRepository.java");
        String decoded = read("src/main/java/com/velorise/simplemap/client/cave/DecodedWorldChunkSource.java");
        String persistence = read("src/main/java/com/velorise/simplemap/client/persistence/v2/MapPersistenceV2Service.java");
        String displayStore = read("src/main/java/com/velorise/simplemap/client/cave/CaveDisplayRegionStore.java");
        String rawStore = read("src/main/java/com/velorise/simplemap/client/cave/CaveRegionStore.java");
        String imageCache = read("src/main/java/com/velorise/simplemap/client/cave/CaveRegionImageCache.java");
        String lodCache = read("src/main/java/com/velorise/simplemap/client/cave/LodBranchDiskCache.java");
        String lodTree = read("src/main/java/com/velorise/simplemap/client/cave/CaveLodTree.java");
        String manager = read("src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java");
        String recorder = read("src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java");

        require(schema.contains("EPOCH = 8")
                        && schema.contains("0x4341563800000000L")
                        && schema.contains("canonicalContentRevision")
                        && schema.contains("revision == 0L ? 1L : revision"),
                "Cave caches still have no single semantic epoch / signed fingerprint canonicalization");
        require(persistence.contains("CaveCacheSchema.ARCHIVE_STYLE_BASE")
                        && !persistence.contains("0x4341563700000000L"),
                "SMR2 Cave archive can still accept CAV7 semantic cache records");
        require(rawStore.contains("CaveCacheSchema.RAW_REGION_VERSION")
                        && rawStore.contains("CaveCacheSchema.RAW_SNAPSHOT_VERSION")
                        && rawStore.contains("CaveCacheSchema.EPOCH"),
                "raw Cave cache is not isolated by the PASS154 epoch");
        require(displayStore.contains("CaveCacheSchema.DISPLAY_REGION_VERSION")
                        && displayStore.contains("CaveCacheSchema.DISPLAY_TILE_VERSION")
                        && displayStore.contains("CaveCacheSchema.EPOCH"),
                "exact Cave display cache is not isolated by the PASS154 epoch");
        require(imageCache.contains("CaveCacheSchema.REGION_IMAGE_VERSION"),
                "CIMG can still replay pre-PASS154 empty proofs");
        require(lodCache.contains("CaveCacheSchema.LOD_BRANCH_VERSION")
                        && lodTree.contains("CaveCacheSchema.CAVE_LOD_KIND_VERSION")
                        && lodTree.contains("\"_e\" + CaveCacheSchema.EPOCH"),
                "Cave LOD branch namespace can still reuse old semantic cache nodes");

        require(dense.contains("CaveCacheSchema.canonicalContentRevision(revision)")
                        && compact.contains("CaveCacheSchema.canonicalContentRevision(revision)")
                        && manager.contains("CaveCacheSchema.canonicalContentRevision(sourceRevision)")
                        && !dense.contains("Math.max(1L, revision)")
                        && !compact.contains("Math.max(1L, revision)"),
                "signed WORLD_SAVE content hashes are still collapsed to revision 1");

        require(archive.contains("persistedUnverifiedEmptyChunks")
                        && archive.contains("currentSessionVerifiedFingerprints")
                        && archive.contains("persisted_zero_run_is_not_strong_empty_authority")
                        && archive.contains("ingestCompactWithoutTrim(partition, compact, false)")
                        && archive.contains("ingestCompact(activePartition, compact, true)")
                        && archive.contains("CAVE_PERSISTED_ARCHIVE_STALE_AGAINST_CURRENT")
                        && archive.contains("action=ignore_persisted_replay")
                        && archive.contains("isProjectionTrusted(partition, key)"),
                "persisted zero-run chunks can still become strong-empty authority without current source verification");
        require(repository.contains("archiveService.isStrongEmptyProofTrusted")
                        && repository.contains("archiveService.hasFullProjectionChunk(chunkX, chunkZ)")
                        && repository.contains("archiveService.hasCompleteChunk(chunkX, chunkZ)"),
                "page completion/empty proof still trusts raw persisted CompactCaveTile coverage directly");
        require(decoded.contains("requiresCurrentSourceVerification")
                        && decoded.contains("repository.ingestDecodedArchive"),
                "decoded current WORLD_SAVE source can skip re-verifying a resident persisted empty tile");

        require(manager.contains("CAVE_VISUAL_PUBLICATION_FENCE_RESTORED")
                        && manager.contains("projected_not_visible_until_exact_or_branch_gpu")
                        && manager.contains("CAVE_STRONG_EMPTY_APPLIED")
                        && manager.contains("cave_cache_epoch="),
                "PASS153 GPU publication fence regressed or strong-empty publication is not observable");
        require(recorder.contains("BUILD_PROVENANCE")
                        && recorder.contains("cave_cache_epoch="),
                "PASS154 runtime/cache provenance is missing");

        System.out.println("CAVE_PASS154_CACHE_EPOCH_EMPTY_REVERIFY_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
