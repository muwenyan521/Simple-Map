package com.velorise.simplemap.client.cave;

/**
 * Single semantic epoch for every derived Cave cache.
 *
 * <p>Binary readability is not enough for a derived map cache. A change to scan,
 * empty-proof, source-revision or publication semantics can make an older payload
 * semantically invalid even when its byte layout still decodes. Xaero isolates its
 * derived caches by the map processor global version (cache_&lt;globalVersion&gt;).
 * Simple Map uses the same rule through one shared Cave epoch so a correctness
 * change cannot leave old strong-empty pages authoritative forever.</p>
 */
public final class CaveCacheSchema {
    /** PASS154: first epoch after strong-empty/source-revision authority repair. */
    public static final int EPOCH = 8;

    public static final int RAW_REGION_VERSION = 7;
    public static final int RAW_SNAPSHOT_VERSION = 10;
    public static final int DISPLAY_REGION_VERSION = 10;
    public static final int DISPLAY_TILE_VERSION = 10;
    public static final int REGION_IMAGE_VERSION = 10;
    public static final int LOD_BRANCH_VERSION = 16;
    public static final int CAVE_LOD_KIND_VERSION = 13;

    /** ASCII "CAV8" in the high 32 bits; low 16 bits remain colour mode. */
    public static final long ARCHIVE_STYLE_BASE = 0x4341563800000000L;

    private CaveCacheSchema() { }

    /**
     * Source/content revisions are 64-bit fingerprints, not positive counters.
     * Preserve the sign bit; reserve only zero as the unknown/unset sentinel.
     */
    public static long canonicalContentRevision(long revision) {
        return revision == 0L ? 1L : revision;
    }
}
