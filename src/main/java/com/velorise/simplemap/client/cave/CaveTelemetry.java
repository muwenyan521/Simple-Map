package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapPipelineStage;
import com.velorise.simplemap.client.MapPipelineTelemetry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/** Lightweight cave-pipeline telemetry, enabled with -Dsimplemap.caveTelemetry=true. */
public final class CaveTelemetry {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final CaveTelemetry INSTANCE = new CaveTelemetry();
    private static final boolean LOG_ENABLED = Boolean.getBoolean("simplemap.caveTelemetry");
    private static final boolean COLLECT_ENABLED = LOG_ENABLED
            || Boolean.getBoolean("simplemap.caveMetrics");
    private static final long LOG_INTERVAL_MS = 5_000L;

    private final AtomicLong scannedColumns = new AtomicLong();
    private final AtomicLong revalidatedColumns = new AtomicLong();
    private final AtomicLong changedRevalidations = new AtomicLong();
    private final AtomicLong scanNanos = new AtomicLong();
    private final AtomicLong archiveScanSlices = new AtomicLong();
    private final AtomicLong archiveScanSliceNanos = new AtomicLong();
    private final AtomicLong archiveScanSliceMaxNanos = new AtomicLong();
    private final AtomicLong archiveScanVerticalSteps = new AtomicLong();
    private final AtomicLong archiveScanPauses = new AtomicLong();
    private final AtomicLong archiveInternalColumnCommits = new AtomicLong();
    private final AtomicLong archiveCompactPublications = new AtomicLong();
    private final AtomicLong archivePageFingerprintChanges = new AtomicLong();
    private final AtomicLong blockStateReads = new AtomicLong();
    private final AtomicLong airSectionSkips = new AtomicLong();
    private final AtomicLong solidSectionSkips = new AtomicLong();
    private final AtomicLong stateCacheHits = new AtomicLong();
    private final AtomicLong stateCacheMisses = new AtomicLong();
    private final AtomicLong stateDynamicFallbacks = new AtomicLong();
    private final AtomicLong graphNanos = new AtomicLong();
    private final AtomicLong resolvedPages = new AtomicLong();
    private final AtomicLong resolvedPageCacheHits = new AtomicLong();
    private final AtomicLong resolvedPageCacheMisses = new AtomicLong();
    private final AtomicLong tileGraphNanos = new AtomicLong();
    private final AtomicLong tileGraphCacheHits = new AtomicLong();
    private final AtomicLong tileGraphCacheMisses = new AtomicLong();
    private final AtomicLong pageBuilds = new AtomicLong();
    private final AtomicLong pageUploads = new AtomicLong();
    private final AtomicLong lodBuilds = new AtomicLong();
    private final AtomicLong dirtyRectangleUploads = new AtomicLong();
    private final AtomicLong gpuPboUploads = new AtomicLong();
    private final AtomicLong gpuDirectUploads = new AtomicLong();
    private final AtomicLong gpuUploadedPixels = new AtomicLong();
    private final AtomicLong atlasEvictions = new AtomicLong();
    private final AtomicLong tileLoads = new AtomicLong();
    private final AtomicLong tileSaves = new AtomicLong();
    private final AtomicLong regionCompactions = new AtomicLong();
    private final AtomicLong compactionBytesReclaimed = new AtomicLong();

    private volatile int schedulerQueue;
    private volatile int pageRequests;
    private volatile int pageBuildQueue;
    private volatile int loadedTiles;
    private volatile long lastLogMs;

    private CaveTelemetry() {
    }

    public static CaveTelemetry getInstance() {
        return INSTANCE;
    }

    public void recordColumnScan(long nanos, boolean revalidation, boolean changed) {
        if (!COLLECT_ENABLED) return;
        scannedColumns.incrementAndGet();
        scanNanos.addAndGet(Math.max(0L, nanos));
        if (revalidation) {
            revalidatedColumns.incrementAndGet();
            if (changed) changedRevalidations.incrementAndGet();
        }
    }

    public void recordBlockStateRead() { if (COLLECT_ENABLED) blockStateReads.incrementAndGet(); }

    public void recordArchiveScanSlice(long nanos, int steps, boolean paused) {
        long safeNanos = Math.max(0L, nanos);
        // The main debug recorder always captures stage avg/max/count, independent
        // from the optional verbose cave telemetry system property.
        MapPipelineTelemetry.getInstance().recordStageNanos(
                MapPipelineStage.CAVE_ARCHIVE_SCAN, safeNanos);
        if (!COLLECT_ENABLED) return;
        archiveScanSlices.incrementAndGet();
        archiveScanSliceNanos.addAndGet(safeNanos);
        archiveScanSliceMaxNanos.accumulateAndGet(safeNanos, Math::max);
        archiveScanVerticalSteps.addAndGet(Math.max(0, steps));
        if (paused) archiveScanPauses.incrementAndGet();
    }

    public void recordInternalArchiveColumnCommit() {
        if (COLLECT_ENABLED) archiveInternalColumnCommits.incrementAndGet();
    }

    public void recordArchiveCompactPublication(boolean pageFingerprintChanged) {
        if (!COLLECT_ENABLED) return;
        archiveCompactPublications.incrementAndGet();
        if (pageFingerprintChanged) archivePageFingerprintChanges.incrementAndGet();
    }
    public void recordAirSectionSkip() { if (COLLECT_ENABLED) airSectionSkips.incrementAndGet(); }
    public void recordSolidSectionSkip() { if (COLLECT_ENABLED) solidSectionSkips.incrementAndGet(); }
    public void recordStateCacheHits(long count) { if (COLLECT_ENABLED) stateCacheHits.addAndGet(Math.max(0L, count)); }
    public void recordStateCacheMiss() { if (COLLECT_ENABLED) stateCacheMisses.incrementAndGet(); }
    public void recordStateDynamicFallback() { if (COLLECT_ENABLED) stateDynamicFallbacks.incrementAndGet(); }

    public void recordGraphResolve(long nanos) {
        if (!COLLECT_ENABLED) return;
        graphNanos.addAndGet(Math.max(0L, nanos));
        resolvedPages.incrementAndGet();
    }

    public void recordResolvedPageCacheHit() { if (COLLECT_ENABLED) resolvedPageCacheHits.incrementAndGet(); }
    public void recordResolvedPageCacheMiss() { if (COLLECT_ENABLED) resolvedPageCacheMisses.incrementAndGet(); }
    public void recordTileGraphBuild(long nanos) { if (COLLECT_ENABLED) tileGraphNanos.addAndGet(Math.max(0L, nanos)); }
    public void recordTileGraphCacheHit() { if (COLLECT_ENABLED) tileGraphCacheHits.incrementAndGet(); }
    public void recordTileGraphCacheMiss() { if (COLLECT_ENABLED) tileGraphCacheMisses.incrementAndGet(); }
    public void recordPageBuild() { if (COLLECT_ENABLED) pageBuilds.incrementAndGet(); }
    public void recordPageUpload() { if (COLLECT_ENABLED) pageUploads.incrementAndGet(); }
    public void recordLodBuild() { if (COLLECT_ENABLED) lodBuilds.incrementAndGet(); }
    public void recordDirtyRectangleUpload() { if (COLLECT_ENABLED) dirtyRectangleUploads.incrementAndGet(); }

    public void recordGpuSubUpload(boolean pbo, int pixels) {
        if (!COLLECT_ENABLED) return;
        if (pbo) gpuPboUploads.incrementAndGet();
        else gpuDirectUploads.incrementAndGet();
        gpuUploadedPixels.addAndGet(Math.max(0, pixels));
    }

    public void recordAtlasEviction() { if (COLLECT_ENABLED) atlasEvictions.incrementAndGet(); }
    public void recordTileLoad() { if (COLLECT_ENABLED) tileLoads.incrementAndGet(); }
    public void recordTileSave() { if (COLLECT_ENABLED) tileSaves.incrementAndGet(); }

    public void recordRegionCompaction(long reclaimedBytes) {
        if (!COLLECT_ENABLED) return;
        regionCompactions.incrementAndGet();
        compactionBytesReclaimed.addAndGet(Math.max(0L, reclaimedBytes));
    }

    public void updateQueues(int schedulerQueue, int pageRequests,
            int pageBuildQueue, int loadedTiles) {
        this.schedulerQueue = Math.max(0, schedulerQueue);
        this.pageRequests = Math.max(0, pageRequests);
        this.pageBuildQueue = Math.max(0, pageBuildQueue);
        this.loadedTiles = Math.max(0, loadedTiles);
    }

    public Snapshot snapshot() {
        return new Snapshot(scannedColumns.get(), revalidatedColumns.get(),
                changedRevalidations.get(), scanNanos.get(),
                archiveScanSlices.get(), archiveScanSliceNanos.get(),
                archiveScanSliceMaxNanos.get(), archiveScanVerticalSteps.get(),
                archiveScanPauses.get(), archiveInternalColumnCommits.get(),
                archiveCompactPublications.get(),
                archivePageFingerprintChanges.get(), blockStateReads.get(),
                airSectionSkips.get(), solidSectionSkips.get(), stateCacheHits.get(),
                stateCacheMisses.get(), stateDynamicFallbacks.get(), graphNanos.get(),
                resolvedPages.get(), resolvedPageCacheHits.get(),
                resolvedPageCacheMisses.get(), tileGraphNanos.get(),
                tileGraphCacheHits.get(), tileGraphCacheMisses.get(), pageBuilds.get(),
                pageUploads.get(), lodBuilds.get(), dirtyRectangleUploads.get(),
                gpuPboUploads.get(), gpuDirectUploads.get(), gpuUploadedPixels.get(),
                atlasEvictions.get(), tileLoads.get(), tileSaves.get(),
                regionCompactions.get(), compactionBytesReclaimed.get(), schedulerQueue,
                pageRequests, pageBuildQueue, loadedTiles);
    }

    public void logIfEnabled() {
        if (!LOG_ENABLED) return;
        long now = System.currentTimeMillis();
        if (now - lastLogMs < LOG_INTERVAL_MS) return;
        lastLogMs = now;
        Snapshot v = snapshot();
        LOGGER.info("Simple Map cave telemetry: columns={} rechecks={} changed={} "
                        + "scan={}ms reads={} sectionSkips={}/{} stateCache={}/{} fallback={} "
                        + "graph={}ms tileGraph={}ms pages={} pageCache={}/{} tileGraphCache={}/{} "
                        + "builds={} uploads={} lods={} dirty={} gpu(pbo/direct/pixels)={}/{}/{} "
                        + "atlasEvictions={} tiles(load/save/resident)={}/{}/{} compactions={} "
                        + "reclaimed={}KB queues(scan/request/build)={}/{}/{}",
                v.scannedColumns(), v.revalidatedColumns(), v.changedRevalidations(),
                formatMs(v.scanNanos()), v.blockStateReads(), v.airSectionSkips(),
                v.solidSectionSkips(), v.stateCacheHits(), v.stateCacheMisses(),
                v.stateDynamicFallbacks(), formatMs(v.graphNanos()),
                formatMs(v.tileGraphNanos()), v.resolvedPages(),
                v.resolvedPageCacheHits(), v.resolvedPageCacheMisses(),
                v.tileGraphCacheHits(), v.tileGraphCacheMisses(), v.pageBuilds(),
                v.pageUploads(), v.lodBuilds(), v.dirtyRectangleUploads(),
                v.gpuPboUploads(), v.gpuDirectUploads(), v.gpuUploadedPixels(),
                v.atlasEvictions(), v.tileLoads(), v.tileSaves(), v.loadedTiles(),
                v.regionCompactions(), v.compactionBytesReclaimed() / 1024L,
                v.schedulerQueue(), v.pageRequests(), v.pageBuildQueue());
    }

    private static String formatMs(long nanos) {
        return String.format("%.2f", nanos / 1_000_000.0);
    }

    public record Snapshot(long scannedColumns, long revalidatedColumns,
            long changedRevalidations, long scanNanos,
            long archiveScanSlices, long archiveScanSliceNanos,
            long archiveScanSliceMaxNanos, long archiveScanVerticalSteps,
            long archiveScanPauses, long archiveInternalColumnCommits,
            long archiveCompactPublications, long archivePageFingerprintChanges,
            long blockStateReads,
            long airSectionSkips, long solidSectionSkips, long stateCacheHits,
            long stateCacheMisses, long stateDynamicFallbacks, long graphNanos,
            long resolvedPages, long resolvedPageCacheHits,
            long resolvedPageCacheMisses, long tileGraphNanos,
            long tileGraphCacheHits, long tileGraphCacheMisses, long pageBuilds,
            long pageUploads, long lodBuilds, long dirtyRectangleUploads,
            long gpuPboUploads, long gpuDirectUploads, long gpuUploadedPixels,
            long atlasEvictions, long tileLoads, long tileSaves,
            long regionCompactions, long compactionBytesReclaimed,
            int schedulerQueue, int pageRequests, int pageBuildQueue,
            int loadedTiles) {
    }
}
