package com.melos.trajectory

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.abs

/**
 * Generates natural-looking running trajectories with realistic speed variation,
 * cornering, and pacing patterns.
 *
 * Human runners exhibit:
 * - Slight speed variations (not perfectly constant)
 * - Smoother cornering (not sharp turns)
 * - Natural drift (small deviations from ideal path)
 * - Acceleration/deceleration phases
 */
class TrajectoryGenerator(
    private val trackProfile: TrackProfile,
    private val meanSpeedMps: Double = 2.5,  // ~9 km/h, comfortable jog
    private val speedVariation: Double = 0.15,  // ±15% speed variation
    private val wanderMeters: Double = 2.0,    // Max path wander from center line
    private val realSpeedAltitudeProfile: List<ProfilePoint>? = null,
) {
    companion object {
        // Human motion constraints
        private const val MAX_ACCEL = 1.5            // m/s², sustainable acceleration
        private const val MAX_DECEL = 2.0            // m/s², comfortable braking
        private const val MIN_CORNER_SPEED = 1.0     // m/s, slowest safe cornering
        private const val DEFAULT_CORNER_RADIUS = 15.0 // metres, typical on a 400m track
    }

    private var currentSpeed = meanSpeedMps
    private var lastTimeSeconds = 0.0
    private var currentDistance = 0.0

    // GPS accuracy random walk — smooth transitions, not instant jumps
    private var currentAccuracyMeters = 5.0f

    // Per-lap offset for track variation (1–2 m drift each lap)
    private var lapOffsetEast = 0.0
    private var lapOffsetNorth = 0.0
    private var completedLaps = 0

    // Track geometry caching
    private val cornerZones = detectCornerZones()

    /**
     * Identify corner zones on the track for appropriate speed modulation.
     * Returns list of (startDistance, endDistance) pairs for corners.
     */
    private fun detectCornerZones(): List<Pair<Double, Double>> {
        val corners = mutableListOf<Pair<Double, Double>>()
        val perimeter = trackProfile.perimeterMeters

        if (perimeter > 300 && perimeter < 500) {
            val cornerLength = perimeter * 0.15
            corners.add(Pair(perimeter * 0.25 - cornerLength/2, perimeter * 0.25 + cornerLength/2))
            corners.add(Pair(perimeter * 0.75 - cornerLength/2, perimeter * 0.75 + cornerLength/2))
        }

        return corners
    }

    /**
     * Generate the next trajectory point at the given elapsed time.
     * @param elapsedSeconds Total time since run start
     */
    fun nextPoint(elapsedSeconds: Double): TrajectoryPoint {
        val dt = if (elapsedSeconds >= lastTimeSeconds) elapsedSeconds - lastTimeSeconds else 0.0
        lastTimeSeconds = max(lastTimeSeconds, elapsedSeconds)

        // Determine target speed based on track position and natural variation
        val targetSpeed = calculateTargetSpeed(currentDistance, elapsedSeconds)

        // Apply realistic acceleration limits
        val speedDelta = targetSpeed - currentSpeed
        val maxChange = when {
            speedDelta > 0 -> MAX_ACCEL * dt
            else -> -MAX_DECEL * dt
        }
        currentSpeed = when {
            abs(speedDelta) < abs(maxChange) -> targetSpeed
            speedDelta > 0 -> currentSpeed + maxChange
            else -> currentSpeed + maxChange
        }

        // Advance along track
        currentDistance += currentSpeed * dt

        // Detect lap completion and apply per-lap drift
        val newLap = (currentDistance / trackProfile.perimeterMeters).toInt()
        if (newLap > completedLaps) {
            completedLaps = newLap
            lapOffsetEast += (Math.random() - 0.5) * 1.0
            lapOffsetNorth += (Math.random() - 0.5) * 1.0
        }

        // Get position on track
        val posOnTrack = trackProfile.pointAtDistance(currentDistance)

        // Add natural wander (Perlin-like noise using sin superposition) + lap drift
        val wanderOffset = calculateWander(elapsedSeconds)
        val actualPosition = GeoUtils.offsetMeters(
            posOnTrack.position,
            wanderOffset.first + lapOffsetEast,
            wanderOffset.second + lapOffsetNorth
        )

        // Calculate realistic altitude (vary gently along track)
        val altitude = calculateAltitude(currentDistance, elapsedSeconds)

        val timestampMillis = (elapsedSeconds * 1000).toLong()

        return TrajectoryPoint(
            position = actualPosition,
            altitudeMeters = altitude,
            bearingDeg = posOnTrack.bearingDeg.toFloat(),
            speedMps = currentSpeed.toFloat(),
            accuracyMeters = calculateAccuracy(currentSpeed, elapsedSeconds),
            timestampMillis = timestampMillis,
            elapsedDistanceMeters = currentDistance,
        )
    }

    /**
     * Calculate target speed based on track geometry.
     * Applies warm-up ramp (quadratic) for first 30 seconds,
     * slows down for corners, speeds up on straights.
     */
    private fun calculateTargetSpeed(distance: Double, elapsedSeconds: Double): Double {
        val perimeter = trackProfile.perimeterMeters
        val fraction = (distance % perimeter) / perimeter

        // Base speed: real profile or mathematical model
        val baseSpeed = if (realSpeedAltitudeProfile != null && realSpeedAltitudeProfile.size >= 2) {
            interpolateProfile(fraction) { it.speedMps }.coerceIn(0.5, 6.0)
        } else {
            val relativeDist = distance % perimeter
            val inCorner = cornerZones.any { (start, end) ->
                when {
                    start < end -> relativeDist in start..end
                    else -> relativeDist >= start || relativeDist <= end
                }
            }
            if (inCorner) min(meanSpeedMps * 0.7, MIN_CORNER_SPEED * 1.5)
            else meanSpeedMps * 1.1
        }

        // Perlin-like speed variation
        val variation = (sin(distance * 0.01) * 0.5 +
                        sin(distance * 0.03) * 0.3 +
                        sin(distance * 0.1) * 0.2) * speedVariation

        // Warm-up: quadratic ramp from 0 over first 30 seconds
        val warmupFactor = if (elapsedSeconds < 30.0) {
            val t = elapsedSeconds / 30.0
            t * t
        } else {
            1.0
        }

        return max(0.5, baseSpeed * (1 + variation) * warmupFactor)
    }

    private inline fun interpolateProfile(fraction: Double, selector: (ProfilePoint) -> Double): Double {
        val profile = realSpeedAltitudeProfile ?: return meanSpeedMps
        val f = fraction.coerceIn(0.0, 1.0)
        // Find surrounding points
        var lo = 0
        var hi = profile.size - 1
        for (i in 0 until profile.size - 1) {
            if (profile[i].fraction <= f && profile[i + 1].fraction >= f) {
                lo = i; hi = i + 1; break
            }
        }
        val segLen = profile[hi].fraction - profile[lo].fraction
        val t = if (segLen > 0.0) (f - profile[lo].fraction) / segLen else 0.0
        return selector(profile[lo]) * (1 - t) + selector(profile[hi]) * t
    }

    /**
     * Calculate wander offset from track center line.
     * Returns (eastMeters, northMeters) offset.
     */
    private fun calculateWander(elapsedSeconds: Double): Pair<Double, Double> {
        // Multi-frequency sin superposition creates smooth, organic wander
        val east = (sin(elapsedSeconds * 0.5) * wanderMeters * 0.5 +
                   sin(elapsedSeconds * 1.3) * wanderMeters * 0.3 +
                   sin(elapsedSeconds * 2.1) * wanderMeters * 0.2)

        val north = (cos(elapsedSeconds * 0.4) * wanderMeters * 0.5 +
                    cos(elapsedSeconds * 1.1) * wanderMeters * 0.3 +
                    cos(elapsedSeconds * 1.9) * wanderMeters * 0.2)

        return Pair(east, north)
    }

    /**
     * Calculate GPS accuracy with smooth random walk.
     * Real GPS accuracy drifts slowly, correlated with speed and satellite geometry.
     */
    private fun calculateAccuracy(speed: Double, elapsedSeconds: Double): Float {
        // Target accuracy varies: faster = slightly worse, but within realistic range
        val speedPenalty = (speed - meanSpeedMps) * 0.5  // ±0.5m per m/s deviation
        val baseTarget = 4.5f + speedPenalty.toFloat()

        // Slow drift toward base target (convergence rate ~0.1 per step)
        currentAccuracyMeters += (baseTarget - currentAccuracyMeters) * 0.1f

        // Random walk step (±0.3m per update)
        currentAccuracyMeters += ((Math.random() - 0.5) * 0.6).toFloat()

        // Occasional degradation bursts (multi-path, canopy) — ~2% chance
        if (Math.random() < 0.02) {
            currentAccuracyMeters += (Math.random() * 5).toFloat()
        }

        // Clamp to realistic consumer GPS range
        currentAccuracyMeters = currentAccuracyMeters.coerceIn(2.5f, 18.0f)

        return currentAccuracyMeters
    }

    /**
     * Calculate altitude at a given track position.
     * Real tracks have slight elevation changes; we simulate gentle variation.
     */
    private fun calculateAltitude(distance: Double, time: Double): Double {
        // Real altitude profile if available
        if (realSpeedAltitudeProfile != null && realSpeedAltitudeProfile.size >= 2) {
            val fraction = ((distance % trackProfile.perimeterMeters) / trackProfile.perimeterMeters)
            val base = interpolateProfile(fraction) { it.altitudeMeters }
            // Micro variation (arm bob, GPS noise)
            return base + sin(time * 10) * 0.1
        }

        val baseAltitude = 10.0  // Starting altitude, metres
        val perimeter = trackProfile.perimeterMeters

        // Elevation varies smoothly around the track
        val elevationVariation = sin(distance / perimeter * 2 * kotlin.math.PI) * 1.5 +  // Main hill
                               sin(distance / perimeter * 4 * kotlin.math.PI) * 0.5    // Minor undulation

        // Small time-based variation (runner hopping, arm motion)
        val microVariation = sin(time * 10) * 0.1

        return baseAltitude + elevationVariation + microVariation
    }

    /**
     * Reset generator state for a new run.
     */
    fun reset() {
        currentSpeed = meanSpeedMps
        lastTimeSeconds = 0.0
        currentDistance = 0.0
        currentAccuracyMeters = 5.0f
    }

    /**
     * Get current accumulated distance.
     */
    fun getCurrentDistance(): Double = currentDistance

    /**
     * Get estimated time to complete a given number of laps.
     */
    fun estimateTimeForLaps(laps: Int): Double {
        val totalDistance = trackProfile.perimeterMeters * laps
        return totalDistance / meanSpeedMps  // Rough estimate, assumes average speed
    }
}
