package com.velorise.simplemap.client;

/**
 * Stable stage names for low-overhead latency telemetry. Values are intentionally
 * coarse: they identify the subsystem that owns latency without retaining per-page
 * traces or allocating hot-path diagnostic objects.
 */
public enum MapPipelineStage {
    SOURCE_QUEUE,
    ANVIL_READ,
    DATA_FIX,
    CHUNK_DECODE,
    WORLD_SOURCE_FANOUT,
    SOURCE_WAIT,
    SURFACE_CAPTURE,
    SURFACE_ASSEMBLY,
    SURFACE_PROJECTION,
    CAVE_PROJECTION,
    CAVE_ARCHIVE_SCAN,
    EXACT_QUEUE,
    EXACT_BUILD,
    EXACT_UPLOAD,
    BRANCH_QUEUE,
    BRANCH_DERIVE,
    BRANCH_UPLOAD
}
