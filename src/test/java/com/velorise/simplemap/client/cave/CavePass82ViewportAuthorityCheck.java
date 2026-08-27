package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS82 guard for viewport ownership, Full authority and bounded CIMG writes. */
public final class CavePass82ViewportAuthorityCheck {
    private CavePass82ViewportAuthorityCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String style = Files.readString(root.resolve("CaveProjectionStyle.java"));
        String projection = Files.readString(
                root.resolve("CaveRegionProjectionService.java"));
        String manager = Files.readString(
                root.resolve("UnifiedCaveTextureManager.java"));

        require(style.contains("STYLE_SIGNATURE_VERSION = 20"),
                "stale PASS80/PASS81 Full CIMG files are not invalidated");
        require(projection.contains("void activateViewport(")
                        && projection.contains("request.pageMask = viewportMask")
                        && projection.contains("request.completedMask &= viewportMask")
                        && projection.contains("PriorityQueue<ProjectedPage> readyPages")
                        && projection.contains("ProjectedPage::priority")
                                                && projection.contains("regions.entrySet().removeIf")
                        && projection.contains("viewportMask(entry.getKey().regionX()"),
                "native-region foreground ownership is not current-viewport ranked");
        require(projection.contains("key.view() == CaveView.FULL")
                        && projection.contains("!resolved.archiveAuthoritative()")
                        && projection.contains("CAVE_FULL_ARCHIVE_AUTHORITY_WAIT"),
                "Full projection can still publish Dense fallback as exact authority");

        require(manager.contains("foregroundImportStillOwned(imported, key, now)")
                        && manager.contains("order=viewport_scanline_sweep_top_left")
                        && manager.contains("lastPageX >= minPageX")
                        && manager.contains("lastPageZ >= minPageZ"),
                "stale imports or one missing exact page can still own the viewport");

        require(projection.contains("REGION_WRITE_MAX_DIRTY_MS = 5_000L")
                        && projection.contains("lastWriteAttemptMs")
                        && projection.contains("dirtySinceMs")
                        && projection.contains("(!quiet && !maxDirtyAge)"),
                "CIMG write coalescing lacks quiet/max-age backpressure");
        require(!projection.contains("candidate.pageMask != -1L"),
                "complete 64-page CIMG transactions still bypass debounce");

        System.out.println("CAVE_PASS82_VIEWPORT_AUTHORITY_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
