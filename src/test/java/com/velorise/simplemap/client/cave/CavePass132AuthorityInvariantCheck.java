package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS132 transient-absence, live arbitration and proof-bearing emptiness. */
public final class CavePass132AuthorityInvariantCheck {
    private CavePass132AuthorityInvariantCheck() { }

    public static void main(String[] args) throws Exception {
        Path cave = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String importer = Files.readString(
                cave.resolve("CaveNativeRegionImportService.java"));
        String reader = Files.readString(cave.resolve("CaveWorldSaveReader.java"));
        String repository = Files.readString(cave.resolve("CaveTileRepository.java"));
        String pipeline = Files.readString(cave.resolve("CavePipeline.java"));
        String manager = Files.readString(
                cave.resolve("UnifiedCaveTextureManager.java"));
        String cache = Files.readString(cave.resolve("CaveRegionImageCache.java"));
        String absentImport = importer.substring(importer.indexOf("case ABSENT ->"),
                importer.indexOf("case FAILED, DEFERRED ->"));

        require(absentImport.contains("caveArchiveReady = false")
                        && importer.contains("sourceRetryAfterMs")
                        && importer.contains("repairTransientDiskAbsence")
                        && importer.contains("CAVE_DISK_ABSENT_TRANSIENT")
                        && !importer.contains("boolean complete = absent.get(index)"),
                "disk absence can still become resolved Cave archive authority");
        require(!reader.contains("archiveOrAbsentCoverage")
                        && reader.contains(
                                "transient source absence stays unresolved and retries")
                        && reader.contains(
                                "if (absent > 0) return DISK_ABSENT_RETRY_MS"),
                "world-save assembly still treats generated-index absence as coverage");
        require(repository.contains("liveDenseExact")
                        && repository.contains(
                                "dense.source() == DenseCaveTile.Source.LIVE")
                        && repository.contains(
                                "currentLiveDisplayProjectionRevisionLocked")
                        && repository.contains("liveDisplayRevision == 0L")
                        && repository.contains("CaveEmptyProof emptyProof")
                        && repository.contains("strongEmptyCoverage")
                        && repository.contains(
                                "dense.source() != DenseCaveTile.Source.DISK")
                        && !repository.contains("absentDisplayTiles")
                        && !repository.contains("boolean[] knownAbsent"),
                "LIVE arbitration or transient-absence revocation regressed");
        require(manager.contains(
                                "authoritative && effectiveEmptyProof.strong()")
                        && manager.contains("CAVE_EMPTY_WITHOUT_PROOF")
                        && cache.contains("private static final int VERSION = 9")
                        && cache.contains("pageEmptyProofs"),
                "empty publication/cache replay is not proof-bearing CIMG v9");
        require(pipeline.contains("CAVE_LIVE_REVOKED_DISK_ABSENCE")
                        && pipeline.contains("CaveTileScheduler.Lane.FOREGROUND")
                        && pipeline.contains("budget * 4L / 5L"),
                "bounded hot canonical archive repair queue is missing");

        for (CaveEmptyProof proof : CaveEmptyProof.values()) {
            require(CaveEmptyProof.fromPersistedCode(proof.persistedCode()) == proof,
                    "empty proof persistence round-trip changed: " + proof);
        }
        require(!CaveEmptyProof.NONE.strong()
                        && CaveEmptyProof.COMPLETE_SAVED_SOURCE_EMPTY.strong()
                        && CaveEmptyProof.COMPLETE_LIVE_SOURCE_EMPTY.strong(),
                "empty proof strength ordering changed");

        System.out.println("CAVE_PASS132_AUTHORITY_INVARIANTS_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
