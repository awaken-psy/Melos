package com.melos.trajectory

import kotlin.math.sin
import kotlin.math.cos

/**
 * Generates stationary GPS fixes at a fixed coordinate with realistic GPS noise.
 *
 * A real GPS receiver at rest still reports:
 * - Small position drift (±3–8 m) from multi-path and satellite geometry changes
 * - Speed near zero with tiny fluctuations
 * - Slowly varying accuracy (random walk)
 * - Gentle altitude oscillation (arm/body sway)
 */
class StaticPointGenerator(
    private val fixedPosition: LatLng,
    private val altitudeMeters: Double = 10.0,
) {
    companion object {
        private const val DRIFT_AMPLITUDE = 4.0    // metres, max position drift
        private const val SPEED_NOISE_MAX = 0.15    // m/s, tiny speed fluctuation
        private const val ALTITUDE_WOBBLE = 0.3     // metres, arm/body sway
        private const val ACCURACY_MIN = 3.0f
        private const val ACCURACY_MAX = 12.0f
    }

    // GPS accuracy random walk — smooth transitions
    private var currentAccuracyMeters = 5.0f

    /**
     * Generate a stationary trajectory point at the given elapsed time.
     * Position oscillates gently around the fixed point.
     */
    fun nextPoint(elapsedSeconds: Double): TrajectoryPoint {
        // Multi-frequency sinusoidal drift — organic, non-periodic feel
        val driftEast = (sin(elapsedSeconds * 0.07) * DRIFT_AMPLITUDE * 0.5 +
                        sin(elapsedSeconds * 0.19) * DRIFT_AMPLITUDE * 0.3 +
                        sin(elapsedSeconds * 0.41) * DRIFT_AMPLITUDE * 0.2)

        val driftNorth = (cos(elapsedSeconds * 0.09) * DRIFT_AMPLITUDE * 0.5 +
                         cos(elapsedSeconds * 0.23) * DRIFT_AMPLITUDE * 0.3 +
                         cos(elapsedSeconds * 0.37) * DRIFT_AMPLITUDE * 0.2)

        val position = GeoUtils.offsetMeters(fixedPosition, driftEast, driftNorth)

        // Tiny speed noise (real GPS reports ~0–0.15 m/s when stationary)
        val speed = (Math.random() * SPEED_NOISE_MAX).toFloat()

        // Altitude: base + gentle oscillation
        val altitude = altitudeMeters + sin(elapsedSeconds * 2.0) * ALTITUDE_WOBBLE

        // Accuracy: slow random walk
        val targetAcc = 5.0f + (Math.random() - 0.5).toFloat() * 2.0f
        currentAccuracyMeters += (targetAcc - currentAccuracyMeters) * 0.05f
        currentAccuracyMeters += ((Math.random() - 0.5) * 0.4).toFloat()
        currentAccuracyMeters = currentAccuracyMeters.coerceIn(ACCURACY_MIN, ACCURACY_MAX)

        return TrajectoryPoint(
            position = position,
            altitudeMeters = altitude,
            bearingDeg = 0f,
            speedMps = speed,
            accuracyMeters = currentAccuracyMeters,
            timestampMillis = (elapsedSeconds * 1000).toLong(),
            elapsedDistanceMeters = 0.0,
        )
    }

    /**
     * Reset generator state.
     */
    fun reset() {
        currentAccuracyMeters = 5.0f
    }
}
