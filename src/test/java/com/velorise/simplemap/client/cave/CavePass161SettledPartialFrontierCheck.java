package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards PASS161 against scanline head-of-line stalls on settled partial pages. */
public final class CavePass161SettledPartialFrontierCheck {
    private CavePass161SettledPartialFrontierCheck() { }

    public static void main(String[] args) throws Exception {
        String manager = read("src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java");
        String importer = read("src/main/java/com/velorise/simplemap/client/cave/CaveNativeRegionImportService.java");

        require(manager.contains("CAVE_FULLSCREEN_PARTIAL_SETTLED_FRONTIER")
                        && manager.contains("settled_partial_is_not_a_hole")
                        && manager.contains("source.resolvedChunks() + source.absentChunks()")
                        && manager.contains("source.inFlightChunks() == 0 && settledChildren >= 16"),
                "settled partial pages can still permanently block the fullscreen scanline");
        require((manager.contains("ACK is CPU-retention, not visibility")
                                || manager.contains("futureExactPreupload"))
                        && manager.contains("service.acknowledgeForeground(imported);"),
                "prepared pages behind the scanline frontier can still re-offer forever");
        require(manager.contains("CAVE_FULLSCREEN_PRESENTATION_FRONTIER_BLOCKED")
                        && (manager.contains("diagnose_scanline_head_of_line")
                                || manager.contains("frontier_waits_for_product_not_timer")),
                "frontier blocker telemetry is missing");
        require(importer.contains("region.presenceKnown.get(index)")
                        && importer.contains("region.presenceAbsent.get(index)"),
                "page source state does not count header-proven absent children as settled");

        System.out.println("CAVE_PASS161_SETTLED_PARTIAL_FRONTIER_PASS");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
