package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapRequestLane;

/**
 * Immutable ownership ticket for one Cave presentation transaction.
 *
 * <p>A viewport pulse may disappear while source/projection work is still valid.
 * This ticket therefore follows the stable loading projection rather than an
 * individual {@code PageRequest}. Bounds and focus can move without replacing the
 * ticket; dimension, view, exact Top-Y or lane changes create a new generation.</p>
 */
public record CavePresentationGeneration(long id, long writerViewportId,
        String dimension, CaveView view, int normalizedLayer,
        int projectionTopY, MapRequestLane lane) {

    public CavePresentationGeneration {
        if (id <= 0L || writerViewportId <= 0L) {
            throw new IllegalArgumentException("Presentation generation ids must be positive");
        }
        dimension = dimension == null ? "" : dimension;
        lane = lane == null ? MapRequestLane.BACKGROUND : lane;
    }

    public boolean matches(String candidateDimension, CaveView candidateView,
            int candidateNormalizedLayer, int candidateProjectionTopY,
            MapRequestLane candidateLane) {
        return dimension.equals(candidateDimension)
                && view == candidateView
                && normalizedLayer == candidateNormalizedLayer
                && projectionTopY == candidateProjectionTopY
                && lane == candidateLane;
    }
}
