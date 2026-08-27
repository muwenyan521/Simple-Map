package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapVisualClassifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;

/**
 * Shared vertical-entry semantics for live, decoded-Anvil and archived cave data.
 *
 * <p>Xaero's Full Cave writer does not pick a Y for a whole chunk. Every X/Z
 * column descends from the surface, first enters real terrain, then waits for the
 * first air/fluid cavity and finally resolves the solid floor below that cavity.
 * Keeping this predicate shared prevents the three SimpleMap source authorities
 * from disagreeing about where "underground" begins.</p>
 */
final class CaveProjectionSemantics {
    private CaveProjectionSemantics() {
    }

    static boolean isTerrainEntry(BlockState state,
            MapVisualClassifier.VisualInfo visual, boolean collisionEmpty) {
        if (state == null || state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.ignitedByLava() || state.canBeReplaced()
                || state.getPistonPushReaction() == PushReaction.DESTROY) {
            return false;
        }
        if (visual != null
                && visual.role() != MapVisualClassifier.Role.OPAQUE_BASE) {
            return false;
        }
        return state.blocksMotion() || !collisionEmpty;
    }

    static boolean isOpenDecoration(BlockState state,
            MapVisualClassifier.VisualInfo visual, boolean collisionEmpty) {
        if (state == null || state.isAir() || !state.getFluidState().isEmpty()) {
            return true;
        }
        if (state.ignitedByLava() || state.canBeReplaced()
                || state.getPistonPushReaction() == PushReaction.DESTROY) {
            return true;
        }
        if (visual != null && visual.role() != MapVisualClassifier.Role.OPAQUE_BASE) {
            return true;
        }
        return collisionEmpty;
    }

    /** Lightweight ABGR overlay used by the primitive vertical archive. */
    static int blendOverlay(int base, int overlay, int alpha256) {
        if (base == 0) return overlay;
        if (overlay == 0) return base;
        int alpha = Math.max(0, Math.min(256, alpha256));
        int inverse = 256 - alpha;
        int red = ((base & 0xFF) * inverse + (overlay & 0xFF) * alpha) >> 8;
        int green = (((base >>> 8) & 0xFF) * inverse
                + ((overlay >>> 8) & 0xFF) * alpha) >> 8;
        int blue = (((base >>> 16) & 0xFF) * inverse
                + ((overlay >>> 16) & 0xFF) * alpha) >> 8;
        int outAlpha = Math.max((base >>> 24) & 0xFF, (overlay >>> 24) & 0xFF);
        return (outAlpha << 24) | (blue << 16) | (green << 8) | red;
    }

}
