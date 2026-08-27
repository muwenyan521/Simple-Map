package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapConfig;

/**
 * Stable identity of the final cave presentation pass.
 *
 * <p>World-save projection, CIMG persistence and exact GPU publication must use
 * the same style namespace. Keeping the signature in one place prevents a
 * pre-rendered region from being accepted under different slope, colour or
 * material rules.</p>
 */
final class CaveProjectionStyle {
    private static final int STYLE_SIGNATURE_VERSION = 20;

    private CaveProjectionStyle() {
    }

    static int signature() {
        int hash = 0x43494D47;
        hash = 31 * hash + MapConfig.terrainSlopes;
        hash = 31 * hash + MapConfig.mapColorProfile;
        hash = 31 * hash + MapConfig.blockColourMode;
        // v20: live and decoded archives retain floor/fluid/emissive facts
        // separately and defer their only composition to CavePageStyler.
        return 31 * hash + STYLE_SIGNATURE_VERSION;
    }
}
