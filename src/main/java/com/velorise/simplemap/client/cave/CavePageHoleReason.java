package com.velorise.simplemap.client.cave;

/** Single classified reason why a currently requested Cave page cannot draw. */
public enum CavePageHoleReason {
    NO_MCA_RECORD,
    MCA_PRESENT_NOT_DECODED,
    SOURCE_DECODE_IN_FLIGHT,
    ARCHIVE_INDEXED_NOT_RESIDENT,
    ARCHIVE_MISSING,
    PROJECTION_NOT_READY,
    PROJECTION_STALE,
    LOADING_GENERATION_NOT_ADMITTED,
    HANDOFF_REJECTED,
    EXACT_GPU_NOT_READY,
    CURRENT_MISSING_PREVIOUS_AVAILABLE,
    CURRENT_AND_PREVIOUS_MISSING,
    GPU_BUDGET_DENIED
}
