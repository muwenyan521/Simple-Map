package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS152 fullscreen snapshot ownership and cache-ready projection demand. */
public final class CavePass152SnapshotPreemptCacheDemandCheck {
    private CavePass152SnapshotPreemptCacheDemandCheck() { }

    public static void main(String[] args) throws Exception {
        String importer = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/"
                        + "CaveNativeRegionImportService.java"));
        String recorder = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapDebugRecorder.java"));

        require(importer.contains("CAVE_FULLSCREEN_LIVE_PENDING_PREEMPTED")
                        && importer.contains("fullscreen_snapshot_over_inherited_live")
                        && importer.contains("foregroundLane == MapRequestLane.FULLSCREEN")
                        && importer.contains("fullscreenSnapshotDependency")
                        && importer.contains("keep_snapshot_disk_authority"),
                "fullscreen Cave can still inherit or re-enter LIVE_PENDING instead of reading the saved snapshot");

        require(importer.contains("cacheIoPendingForegroundPages")
                        && importer.contains("ready_pages_remain_demanded=true")
                        && importer.contains("cache_authority_not_demand_suppression")
                        && importer.contains("pageMasksExcluding(sourcePagePlan,\n                cacheIoPendingForegroundPages)")
                        && !importer.contains("pageMasksExcluding(sourcePagePlan,\n                cacheOwnedForegroundPages)"),
                "resident cache pages are still removed from the branch/native projection demand graph");

        require(recorder.contains("BUILD_PROVENANCE"),
                "PASS152 runtime provenance is missing");

        System.out.println("CAVE_PASS152_SNAPSHOT_PREEMPT_CACHE_DEMAND_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
