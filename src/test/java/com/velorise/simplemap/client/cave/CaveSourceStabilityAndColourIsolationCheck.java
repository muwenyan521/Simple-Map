package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

public final class CaveSourceStabilityAndColourIsolationCheck {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client");
        String reader = Files.readString(root.resolve("cave/CaveWorldSaveReader.java"));
        String repository = Files.readString(root.resolve("cave/CaveTileRepository.java"));
        String tile = Files.readString(root.resolve("cave/DenseCaveTile.java"));
        String store = Files.readString(root.resolve("cave/CaveDisplayRegionStore.java"));
        String archiveStore = Files.readString(root.resolve("cave/CaveRegionStore.java"));
        String archiveProjector = Files.readString(root.resolve("cave/CaveArchiveProjector.java"));
        String projectionV2 = Files.readString(
                root.resolve("cave/projection/CaveProjectionServiceV2.java"));
        String lodTree = Files.readString(root.resolve("cave/CaveLodTree.java"));
        String persistence = Files.readString(
                root.resolve("persistence/v2/MapPersistenceV2Service.java"));
        String manager = Files.readString(root.resolve("cave/UnifiedCaveTextureManager.java"));
        String projectionStyle = Files.readString(
                root.resolve("cave/CaveProjectionStyle.java"));
        String colors = Files.readString(root.resolve("cave/CaveColorResolver.java"));
        require(reader.contains("resolvedPageStamps"), "missing source-complete stamp");
        require(repository.contains("getDisplayPageResolutionStamp"),
                "missing display-only source fingerprint");
        require(repository.contains("sameProjectionContent(tile)"), "missing payload dedupe");
        require(repository.contains("touchDisplayTileLocked"),
                "raw/display page revisions are still coupled");
        int rawTouch = repository.indexOf("private void touchLocked");
        int displayTouch = repository.indexOf("private void touchDisplayTileLocked");
        require(rawTouch >= 0 && displayTouch > rawTouch
                        && !repository.substring(rawTouch, displayTouch)
                                .contains("pageRevisions.addTo"),
                "raw CVR ingestion still invalidates exact display pages");
        require(tile.contains("boolean sameProjectionContent"), "missing tile comparator");
        require(store.contains("colourNamespacePrefix"), "CVD not colour-isolated");
        require(archiveStore.contains("colourNamespacePrefix"),
                "CVR archive not colour-isolated");
        require(archiveStore.contains("c5-m"), "CVR schema was not advanced");
        require(!archiveProjector.contains("FLAG_PRELIT_LEGACY"),
                "new archive replay still bypasses raw cave shading");
        require(!projectionV2.contains("denseFlags |= DenseCaveTile.FLAG_PRELIT_LEGACY"),
                "SMR2 archive replay still marks raw colours as pre-lit");
        require(!repository.contains("column.flags(run)\n                                        | DenseCaveTile.FLAG_PRELIT_LEGACY"),
                "CVR compatibility replay still marks raw colours as pre-lit");
        require(lodTree.contains("cave_v12_"),
                "old cave branch pixels can still replay after region-transaction migration");
        require(persistence.contains("caveArchiveStyleSignature"),
                "SMR2 cave archive is not colour-schema isolated");
        require(projectionStyle.contains("MapConfig.blockColourMode")
                        && manager.contains("CaveProjectionStyle.signature()"),
                "CIMG and exact publication do not share one colour/style namespace");
        require(!colors.contains("rgb = enrich("), "private cave enrichment remains");
    }
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
