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
 */
class SensorSimulator(
    private val targetStepsPerMinute: Float = 160f, // Typical jogging cadence
    private val runningSpeedMps: Float = 2.5f,      // ~9 km/h, typical jog
) {
    companion object {
        private const val TAG = "Melos-Sensor"

        // Physical constants
        private const val GRAVITY = 9.81f                    // m/s²
        private const val SEA_LEVEL_PRESSURE_HPA = 1013.25f  // hPa
        private const val PRESSURE_LAPSE_RATE = 0.012f       // hPa/m, standard atmosphere

        // Step detection thresholds
        private const val STEP_THRESHOLD = 12.0f             // m/s², peak acceleration threshold
        private const val STEP_MIN_INTERVAL_MS = 250L        // Minimum time between steps

        // Anti-detection: cadence variation bounds
        private const val CADENCE_VARIATION_PERCENT = 0.08f  // ±8% natural cadence drift
        private const val CADENCE_DRIFT_SPEED = 0.0003       // Slow drift rate for cadence
    }

    // State tracking
    private var lastStepTimeMs = 0L
    private var totalSteps = 0
    private var currentAltitude = 10.0f
    private var currentCadence = targetStepsPerMinute

    // Timing
    private var startTimeMs = 0L
    private var elapsedDistanceMeters = 0.0

    // Anti-detection: seed offsets for deterministic-looking but varied noise
    private val noiseSeedX = Math.random() * 1000.0
    private val noiseSeedY = Math.random() * 1000.0
    private val noiseSeedZ = Math.random() * 1000.0

    fun reset() {
        lastStepTimeMs = 0L
        totalSteps = 0
        currentAltitude = 10.0f
        startTimeMs = 0L
        elapsedDistanceMeters = 0.0
        currentCadence = targetStepsPerMinute
    }

    fun setStartTime(timeMs: Long) {
        startTimeMs = timeMs
    }

    fun getStartTime(): Long = startTimeMs

    /**
     * Update simulation state with new position data.
     * Called by GPS hook to maintain sensor-GPS consistency.
     */
    fun updateWithGpsData(
        timestampMs: Long,
        altitudeMeters: Double,
        distanceDeltaMeters: Double,
    ) {
        if (startTimeMs == 0L) startTimeMs = timestampMs

        currentAltitude = altitudeMeters.toFloat()
        elapsedDistanceMeters += distanceDeltaMeters

        // Estimate step count from distance (average stride ~0.75m when jogging)
        val estimatedSteps = (elapsedDistanceMeters / 0.75).toInt()
        totalSteps = estimatedSteps
    }

    /**
     * Generate synthetic accelerometer event for a given timestamp.
     * Simulates the characteristic dual-peak pattern of running steps
     * with natural cadence drift and amplitude variation.
     */
    fun generateAccelerometerEvent(
        timestampMs: Long,
        sensor: Sensor,
    ): SensorEvent? {
        val elapsedMs = timestampMs - startTimeMs
        if (elapsedMs < 0) return null

        // Update cadence with slow drift (±8% over time)
        val cadenceDrift = (sin(elapsedMs * CADENCE_DRIFT_SPEED) * CADENCE_VARIATION_PERCENT).toFloat()
        currentCadence = targetStepsPerMinute * (1f + cadenceDrift)

        val timeSinceLastStep = timestampMs - lastStepTimeMs
        val stepIntervalMs = (60000.0 / currentCadence).toLong()

        // Generate step-like acceleration pattern
        val phase = (elapsedMs % stepIntervalMs).toFloat() / stepIntervalMs

        // Amplitude variation per step (±15%) — no two steps are identical
        val ampVar = 1.0f + ((sin(elapsedMs * 0.007) * 0.1 + sin(elapsedMs * 0.013) * 0.05)).toFloat()

        val values = if (phase < 0.3f) {
            // Heel strike: sharp upward spike
            val peak = sin(phase / 0.3f * Math.PI).toFloat()
            floatArrayOf(
                0.5f * peak * ampVar,
                GRAVITY + 8f * peak * ampVar,
                -2f * peak * ampVar
            )
        } else if (phase < 0.6f) {
            // Mid-stance: relatively stable with tiny noise
            val micro = (sin(elapsedMs * 0.05) * 0.15).toFloat()
            floatArrayOf(micro, GRAVITY + micro * 0.3f, 1f + micro * 0.2f)
        } else {
            // Toe-off: second, smaller peak
            val localPhase = (phase - 0.6f) / 0.4f
            val peak = sin(localPhase * Math.PI).toFloat()
            floatArrayOf(
                -0.3f * peak * ampVar,
                GRAVITY + 4f * peak * ampVar,
                3f * peak * ampVar
            )
        }

        // Update step counter on heel strike
        if (phase < 0.1f && timeSinceLastStep >= STEP_MIN_INTERVAL_MS) {
            lastStepTimeMs = timestampMs
            totalSteps++
        }

        return createSensorEvent(sensor, values, timestampMs, accuracy = 3)
    }

    /**
     * Generate synthetic barometer (pressure) event.
     * Pressure varies with altitude according to the barometric formula
     * with slow sensor drift and per-sample noise.
     */
    fun generateBarometerEvent(
        timestampMs: Long,
        sensor: Sensor,
    ): SensorEvent {
        val elapsedMs = timestampMs - startTimeMs

        val pressure = SEA_LEVEL_PRESSURE_HPA - (currentAltitude * PRESSURE_LAPSE_RATE / 100f)

        // Slow atmospheric drift (±0.3 hPa over minutes) + fast noise (±0.05 hPa)
        val drift = (sin(elapsedMs * 0.0001) * 0.3).toFloat()
        val noise = (sin(elapsedMs * 0.01 + noiseSeedX) * 0.05).toFloat()
        val values = floatArrayOf(pressure + drift + noise)

        return createSensorEvent(sensor, values, timestampMs, accuracy = 3)
    }

    /**
     * Generate synthetic magnetometer (compass) event.
     * Returns the device heading relative to magnetic north
     * with smooth, correlated noise (not independent per-axis).
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

        // Correlated smooth noise using seeded sin — axes share similar timestamps
        val nx = (sin(elapsedMs * 0.008 + noiseSeedX) * 0.4).toFloat()
        val ny = (sin(elapsedMs * 0.009 + noiseSeedY) * 0.4).toFloat()
        val nz = (sin(elapsedMs * 0.007 + noiseSeedZ) * 0.3).toFloat()

        val values = floatArrayOf(x + nx, y + ny, z + nz)

        return createSensorEvent(sensor, values, timestampMs, accuracy = 2)
    }

    /**
     * Generate synthetic gyroscope event.
     * Simulates rotation rate changes during running with amplitude variation.
     */
    fun generateGyroscopeEvent(
        timestampMs: Long,
        sensor: Sensor,
        bearingChangeRate: Float = 0f,
    ): SensorEvent {
        val elapsedMs = timestampMs - startTimeMs

        val swayFreq = 2.0f * Math.PI.toFloat() * currentCadence / 60.0f
        val swayPhase = (timestampMs / 1000.0f * swayFreq) % (2 * Math.PI.toFloat())

        // Amplitude variation synced with cadence drift
        val ampVar = 1.0f + sin(elapsedMs * 0.0004).toFloat() * 0.2f

        val values = floatArrayOf(
            (sin(swayPhase.toDouble()) * 0.5 * ampVar).toFloat(),
            bearingChangeRate,
            (cos(swayPhase.toDouble()) * 0.3 * ampVar).toFloat()
        )

        return createSensorEvent(sensor, values, timestampMs, accuracy = 3)
    }

    /**
     * Get current step count since start.
     */
    fun getStepCount(): Int = totalSteps

    /**
     * Get estimated step rate (steps per minute) over the last period.
     */
    fun getStepsPerMinute(): Float = targetStepsPerMinute

    private fun createSensorEvent(
        sensor: Sensor,
        values: FloatArray,
        timestampMs: Long,
        accuracy: Int,
    ): SensorEvent {
        // Use reflection to create SensorEvent since constructor is not public
        val event = SensorEvent::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).newInstance(values.size, 0)

        // Set values via reflection
        val sensorField = SensorEvent::class.java.getDeclaredField("sensor")
        sensorField.isAccessible = true
        sensorField.set(event, sensor)

        val valuesField = SensorEvent::class.java.getDeclaredField("values")
        valuesField.isAccessible = true
        valuesField.set(event, values)

        val timestampField = SensorEvent::class.java.getDeclaredField("timestamp")
        timestampField.isAccessible = true
        // Sensor timestamp is in nanoseconds
        timestampField.setLong(event, timestampMs * 1_000_000L)

        val accuracyField = SensorEvent::class.java.getDeclaredField("accuracy")
        accuracyField.isAccessible = true
        accuracyField.setInt(event, accuracy)

        return event
    }
}
