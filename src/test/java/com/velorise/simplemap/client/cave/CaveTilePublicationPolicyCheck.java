package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapRequestLane;

public final class CaveTilePublicationPolicyCheck {
    private CaveTilePublicationPolicyCheck() { }

    public static void main(String[] args) {
        long now = 10_000L;
        require(!CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.FULLSCREEN, true, false, 0, now, now),
                "zero tiles");
        require(!CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.FULLSCREEN, false, false, false, 1, now, now),
                "isolated cold publication");
        require(CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.FULLSCREEN, false, false, false,
                CaveTilePublicationPolicy.FULLSCREEN_FIRST_BATCH_TILES, now, now),
                "complete cold fullscreen page");
        require(!CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.FULLSCREEN, false, false, false,
                CaveTilePublicationPolicy.MINIMAP_FIRST_BATCH_TILES, now, now),
                "partial cold fullscreen page");
        require(!CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.FULLSCREEN, false, false, true, 1, now,
                now + CaveTilePublicationPolicy.FIRST_MAX_HOLD_MS - 1L),
                "leading page hold");
        require(!CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.FULLSCREEN, false, false, true, 1, now,
                now + CaveTilePublicationPolicy.FIRST_MAX_HOLD_MS),
                "leading fullscreen page remains atomic after deadline");
        require(CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.MINIMAP, false, false, true, 1, now,
                now + CaveTilePublicationPolicy.FIRST_MAX_HOLD_MS),
                "leading minimap page deadline");
        require(!CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.MINIMAP, true, false, 1, now, now),
                "initialized minimap single-tile coalescing");
        require(CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.MINIMAP, true, false, 1, now,
                now + CaveTilePublicationPolicy.MAX_HOLD_MS),
                "initialized minimap bounded latency");
        require(CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.FULLSCREEN, true, true, 16, now, now),
                "projection replacement");
        require(!CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.FULLSCREEN, true, false, 1, now,
                now + CaveTilePublicationPolicy.MAX_HOLD_MS - 1L),
                "bounded coalescing");
        require(CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.FULLSCREEN, true, false, 1, now,
                now + CaveTilePublicationPolicy.MAX_HOLD_MS),
                "deadline publication");
        require(CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.FULLSCREEN, true, false,
                CaveTilePublicationPolicy.MIN_BATCH_TILES, now, now + 1L),
                "batch publication");
        require(CaveTilePublicationPolicy.largestConnectedMask(0x0033) == 0x0033,
                "connected square retained");
        require(Integer.bitCount(
                CaveTilePublicationPolicy.largestConnectedMask(0x9009)) == 1,
                "isolated callbacks do not form one publication batch");
        System.out.println("CAVE_TILE_PUBLICATION_POLICY_PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
