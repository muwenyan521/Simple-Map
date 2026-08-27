package com.velorise.simplemap.client.cave;

/**
 * Evidence that a complete cave page legitimately contains no visible pixels.
 *
 * <p>Disk/header absence is deliberately not representable here. It is a
 * short-lived source retry hint, never presentation authority.</p>
 */
public enum CaveEmptyProof {
    NONE(0, false),
    COMPLETE_SAVED_SOURCE_EMPTY(1, true),
    COMPLETE_LIVE_SOURCE_EMPTY(2, true);

    private final int persistedCode;
    private final boolean strong;

    CaveEmptyProof(int persistedCode, boolean strong) {
        this.persistedCode = persistedCode;
        this.strong = strong;
    }

    public boolean strong() {
        return strong;
    }

    public int persistedCode() {
        return persistedCode;
    }

    public static CaveEmptyProof fromPersistedCode(int code) {
        for (CaveEmptyProof proof : values()) {
            if (proof.persistedCode == code) return proof;
        }
        return NONE;
    }
}
