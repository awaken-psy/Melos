package com.melos.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import de.robv.android.xposed.XposedBridge
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI

/**
 * Central sensor simulation coordinator.
 *
 * Manages all synthetic sensor data generation and injection to ensure
 * multi-sensor temporal consistency (GPS, accelerometer, barometer, etc.).
 *
 * Anti-detection features:
 * - Speed-cadence coupling: cadence derived from GPS speed, not independent
 * - Hash-based white noise: flat spectrum, no discrete sin spectral lines
 * - Slow sin drift preserved for low-frequency variations
 */
class SensorSimulator(
    private val targetStepsPerMinute: Float = 160f,
    private val runningSpeedMps: Float = 2.5f,
) {
    companion object {
        private const val TAG = "Melos-Sensor"

        private const val GRAVITY = 9.81f
        private const val SEA_LEVEL_PRESSURE_HPA = 1013.25f
        private const val PRESSURE_LAPSE_RATE = 0.012f

        private const val CADENCE_VARIATION_PERCENT = 0.08f
        private const val CADENCE_DRIFT_SPEED = 0.0003
    }

    // State tracking
    private var totalSteps = 0
    private var currentAltitude = 10.0f
    private var currentCadence = targetStepsPerMinute
    private var lastStepDetectorFiredMs = 0L

    // Speed tracking for cadence-speed coupling
    private var currentSpeedMps = runningSpeedMps
    private var lastGpsTimeMs = 0L

    // Timing
    private var startTimeMs = 0L
    private var elapsedDistanceMeters = 0.0

    // Noise seeds for pseudoNoise (Long for hash mixing)
    private val noiseSeedX = (Math.random() * Long.MAX_VALUE).toLong()
    private val noiseSeedY = (Math.random() * Long.MAX_VALUE).toLong()
    private val noiseSeedZ = (Math.random() * Long.MAX_VALUE).toLong()

    fun reset() {
        totalSteps = 0
        currentAltitude = 10.0f
        startTimeMs = 0L
        elapsedDistanceMeters = 0.0
        currentCadence = targetStepsPerMinute
        currentSpeedMps = runningSpeedMps
        lastGpsTimeMs = 0L
        lastStepDetectorFiredMs = 0L
    }

    fun setStartTime(timeMs: Long) {
        startTimeMs = timeMs
    }

    fun getStartTime(): Long = startTimeMs

    /**
     * Update simulation state with new position data.
     * Tracks speed for cadence coupling and increments steps with
     * speed-dependent stride length.
     */
    fun updateWithGpsData(
        timestampMs: Long,
        altitudeMeters: Double,
        distanceDeltaMeters: Double,
    ) {
        if (startTimeMs == 0L) startTimeMs = timestampMs

        if (lastGpsTimeMs > 0) {
            val dtSec = (timestampMs - lastGpsTimeMs) / 1000.0
            if (dtSec > 0.01) {
                currentSpeedMps = (distanceDeltaMeters / dtSec).toFloat()
            }
        }
        lastGpsTimeMs = timestampMs

        currentAltitude = altitudeMeters.toFloat()
        elapsedDistanceMeters += distanceDeltaMeters

        // Incremental steps with speed-dependent stride
        val stride = (0.6f + currentSpeedMps * 0.15f).coerceIn(0.5f, 1.2f)
        if (stride > 0 && distanceDeltaMeters > 0) {
            totalSteps += (distanceDeltaMeters / stride).toInt()
        }
    }

    /**
     * Generate synthetic accelerometer event.
     * Cadence is derived from current GPS speed (coupled), with slow sin drift.
     * Mid-stance noise uses hash-based white noise instead of sin.
     */
    fun generateAccelerometerEvent(
        timestampMs: Long,
        sensor: Sensor,
    ): SensorEvent? {
        val elapsedMs = timestampMs - startTimeMs
        if (elapsedMs < 0) return null

        // Speed-dependent cadence: 130 + speed*15 spm for running range
        val speedCadence = if (currentSpeedMps > 1.0f) {
            (130f + currentSpeedMps * 15f).coerceIn(100f, 200f)
        } else {
            110f
        }
        val cadenceDrift = (sin(elapsedMs * CADENCE_DRIFT_SPEED) * CADENCE_VARIATION_PERCENT).toFloat()
        currentCadence = speedCadence * (1f + cadenceDrift)

        val stepIntervalMs = (60000.0 / currentCadence).toLong()

        val phase = (elapsedMs % stepIntervalMs).toFloat() / stepIntervalMs
        val ampVar = 1.0f + ((sin(elapsedMs * 0.007) * 0.1 + sin(elapsedMs * 0.013) * 0.05)).toFloat()

        val values = if (phase < 0.3f) {
            val peak = sin(phase / 0.3f * Math.PI).toFloat()
            floatArrayOf(
                0.5f * peak * ampVar,
                GRAVITY + 8f * peak * ampVar,
                -2f * peak * ampVar
            )
        } else if (phase < 0.6f) {
            val micro = pseudoNoise(elapsedMs, noiseSeedX) * 0.15f
            floatArrayOf(micro, GRAVITY + micro * 0.3f, 1f + micro * 0.2f)
        } else {
            val localPhase = (phase - 0.6f) / 0.4f
            val peak = sin(localPhase * Math.PI).toFloat()
            floatArrayOf(
                -0.3f * peak * ampVar,
                GRAVITY + 4f * peak * ampVar,
                3f * peak * ampVar
            )
        }

        return createSensorEvent(sensor, values, timestampMs, accuracy = 3)
    }

    /**
     * Generate synthetic barometer event.
     * Low-frequency drift uses sin (physically appropriate).
     * Per-sample noise uses hash-based white noise.
     */
    fun generateBarometerEvent(
        timestampMs: Long,
        sensor: Sensor,
    ): SensorEvent {
        val elapsedMs = timestampMs - startTimeMs
        val pressure = SEA_LEVEL_PRESSURE_HPA - (currentAltitude * PRESSURE_LAPSE_RATE / 100f)

        val drift = (sin(elapsedMs * 0.0001) * 0.3).toFloat()
        val noise = pseudoNoise(elapsedMs, noiseSeedX) * 0.05f
        val values = floatArrayOf(pressure + drift + noise)

        return createSensorEvent(sensor, values, timestampMs, accuracy = 3)
    }

    /**
     * Generate synthetic magnetometer event.
     * Per-axis noise uses hash-based white noise for realistic spectrum.
     */
    fun generateMagnetometerEvent(
        timestampMs: Long,
        sensor: Sensor,
        bearingDeg: Float,
    ): SensorEvent {
        val elapsedMs = timestampMs - startTimeMs

        val intensity = 48.0f
        val inclination = sin(45.0 * PI / 180.0).toFloat()
        val bearingRad = bearingDeg * PI / 180.0f
        val horizontal = intensity * cos(inclination.toDouble()).toFloat()
        val x = horizontal * sin(bearingRad.toDouble()).toFloat()
        val y = horizontal * cos(bearingRad.toDouble()).toFloat()
        val z = intensity * sin(inclination.toDouble()).toFloat()

        val nx = pseudoNoise(elapsedMs, noiseSeedX) * 0.4f
        val ny = pseudoNoise(elapsedMs + 1, noiseSeedY) * 0.4f
        val nz = pseudoNoise(elapsedMs + 2, noiseSeedZ) * 0.3f

        val values = floatArrayOf(x + nx, y + ny, z + nz)
        return createSensorEvent(sensor, values, timestampMs, accuracy = 2)
    }

    /**
     * Generate synthetic gyroscope event.
     * Amplitude envelope uses sin (slow variation, physically appropriate).
     */
    fun generateGyroscopeEvent(
        timestampMs: Long,
        sensor: Sensor,
        bearingChangeRate: Float = 0f,
    ): SensorEvent {
        val elapsedMs = timestampMs - startTimeMs
        val swayFreq = 2.0f * Math.PI.toFloat() * currentCadence / 60.0f
        val swayPhase = (timestampMs / 1000.0f * swayFreq) % (2 * Math.PI.toFloat())
        val ampVar = 1.0f + sin(elapsedMs * 0.0004).toFloat() * 0.2f

        val values = floatArrayOf(
            (sin(swayPhase.toDouble()) * 0.5 * ampVar).toFloat(),
            bearingChangeRate,
            (cos(swayPhase.toDouble()) * 0.3 * ampVar).toFloat()
        )

        return createSensorEvent(sensor, values, timestampMs, accuracy = 3)
    }

    fun getStepCount(): Int = totalSteps

    fun getStepsPerMinute(): Float = currentCadence

    fun generateStepCounterEvent(
        timestampMs: Long,
        sensor: Sensor,
    ): SensorEvent? {
        if (totalSteps == 0) return null
        return createSensorEvent(sensor, floatArrayOf(totalSteps.toFloat()), timestampMs, accuracy = 3)
    }

    fun generateStepDetectorEvent(
        timestampMs: Long,
        sensor: Sensor,
    ): SensorEvent {
        return createSensorEvent(sensor, floatArrayOf(1.0f), timestampMs, accuracy = 3)
    }

    fun getPendingStepDetectorTimestamps(currentTimeMs: Long): List<Long> {
        val timestamps = mutableListOf<Long>()
        val baseIntervalMs = (60000.0 / currentCadence).toLong()

        if (lastStepDetectorFiredMs == 0L) {
            lastStepDetectorFiredMs = startTimeMs
        }

        var nextStep = lastStepDetectorFiredMs + baseIntervalMs
        while (nextStep <= currentTimeMs) {
            timestamps.add(nextStep)
            lastStepDetectorFiredMs = nextStep
            val jitter = (pseudoNoise(nextStep, noiseSeedX) * 0.05 * baseIntervalMs).toLong()
            nextStep = nextStep + baseIntervalMs + jitter
        }

        return timestamps
    }

    /**
     * Hash-based pseudo-random noise producing flat white noise spectrum.
     * Unlike sin() which produces discrete spectral lines, this generates
     * a continuous spectrum indistinguishable from real sensor noise under
     * frequency analysis. Deterministic for same inputs (reproducible).
     */
    private fun pseudoNoise(t: Long, seed: Long): Float {
        var h = t xor (seed * 0x517cc1b727220a95L)
        h = ((h ushr 32) xor h) * 0x45d9f3bL
        h = ((h ushr 32) xor h) * 0x45d9f3bL
        h = (h ushr 32) xor h
        return ((h and 0x7FFF).toFloat() / 0x7FFF) * 2.0f - 1.0f
    }

    private fun createSensorEvent(
        sensor: Sensor,
        values: FloatArray,
        timestampMs: Long,
        accuracy: Int,
    ): SensorEvent {
        val event = SensorEvent::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).newInstance(values.size, 0)

        val sensorField = SensorEvent::class.java.getDeclaredField("sensor")
        sensorField.isAccessible = true
        sensorField.set(event, sensor)

        val valuesField = SensorEvent::class.java.getDeclaredField("values")
        valuesField.isAccessible = true
        valuesField.set(event, values)

        val timestampField = SensorEvent::class.java.getDeclaredField("timestamp")
        timestampField.isAccessible = true
        timestampField.setLong(event, timestampMs * 1_000_000L)

        val accuracyField = SensorEvent::class.java.getDeclaredField("accuracy")
        accuracyField.isAccessible = true
        accuracyField.setInt(event, accuracy)

        return event
    }
}
