package com.melos.trajectory

/** A WGS84 geographic coordinate, in decimal degrees. */
data class LatLng(val lat: Double, val lng: Double)

/**
 * One synthesized fix along a run. These fields map 1:1 onto the values an
 * Android [android.location.Location] exposes, so a hook can replay them directly.
 *
 * @param elapsedDistanceMeters cumulative distance run when this fix was produced;
 *        handy for sanity-checking laps and for matching against a step count.
 */
data class TrajectoryPoint(
    val position: LatLng,
    val altitudeMeters: Double,
    val bearingDeg: Float,
    val speedMps: Float,
    val accuracyMeters: Float,
    val timestampMillis: Long,
    val elapsedDistanceMeters: Double,
)
