package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-free guard for PASS149 direct page resolve + ungated snapshot barrier. */
public final class CavePass149DirectPageResolveCheck {
    private CavePass149DirectPageResolveCheck() { }

    public static void main(String[] args) throws Exception {
        String worldSave = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/"
                        + "WorldSaveProjectionPipeline.java"));
        String repository = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/"
                        + "CaveTileRepository.java"));

        require(worldSave.contains("FULLSCREEN_CAVE_STICKY_HALO_PAGES = 0"),
                "fullscreen Cave still expands the cold source plan beyond the visible pages");
        require(worldSave.contains("xaero_snapshot_barrier_direct_server_submit")
                        && worldSave.contains("level.getServer()")
                        && worldSave.contains("level.getChunkSource().save(false)"),
                "fullscreen Cave snapshot still depends on the map IO scheduler");
        require(!worldSave.contains("MapWorkScheduler.tryIoFuture"),
                "snapshot barrier can still be denied by the map IO admission queue");

        require(repository.contains("direct_compact_column_no_36_tile_projection")
                        && repository.contains("trustedDenseExact")
                        && repository.contains("compactColumnKnown")
                        && repository.contains("compactProjectionColor")
                        && repository.contains("compactProjectionFlags")
                        && repository.contains("compactProjectionLight"),
                "page resolve no longer performs direct compact-column composition");
        require(!repository.contains("archiveV2Tiles"),
                "resolver still allocates a 36-entry projected archive-tile window");
        require(!repository.contains("CaveProjectionServiceV2"),
                "resolver still materializes whole CaveProjectionTile payloads for page borders");

        System.out.println("CAVE_PASS149_DIRECT_PAGE_RESOLVE_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
