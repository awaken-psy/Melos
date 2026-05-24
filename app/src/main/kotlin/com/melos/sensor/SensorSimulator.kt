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
    }

    // State tracking
    private var lastStepTimeMs = 0L
    private var totalSteps = 0
    private var currentAltitude = 10.0f                      // Starting altitude, metres

    // Timing
    private var startTimeMs = 0L
    private var elapsedDistanceMeters = 0.0

    fun reset() {
        lastStepTimeMs = 0L
        totalSteps = 0
        currentAltitude = 10.0f
        startTimeMs = 0L
        elapsedDistanceMeters = 0.0
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
     * Simulates the characteristic dual-peak pattern of running steps.
     */
    fun generateAccelerometerEvent(
        timestampMs: Long,
        sensor: Sensor,
    ): SensorEvent? {
        val elapsedMs = timestampMs - startTimeMs
        if (elapsedMs < 0) return null

        // Check if we should generate a step peak
        val timeSinceLastStep = timestampMs - lastStepTimeMs
        val stepIntervalMs = (60000.0 / targetStepsPerMinute).toLong()

        // Generate step-like acceleration pattern
        // Running creates two peaks per step: heel strike (braking) and toe-off (pushing)
        val phase = (elapsedMs % stepIntervalMs).toFloat() / stepIntervalMs

        val values = if (phase < 0.3f) {
            // Heel strike: sharp upward spike
            val peak = sin(phase / 0.3f * Math.PI).toFloat()
            floatArrayOf(
                0.5f * peak,                      // X: lateral sway
                GRAVITY + 8f * peak,              // Y: vertical (primary motion)
                -2f * peak                        // Z: forward braking
            )
        } else if (phase < 0.6f) {
            // Mid-stance: relatively stable
            floatArrayOf(0f, GRAVITY, 1f)
        } else {
            // Toe-off: second, smaller peak
            val localPhase = (phase - 0.6f) / 0.4f
            val peak = sin(localPhase * Math.PI).toFloat()
            floatArrayOf(
                -0.3f * peak,
                GRAVITY + 4f * peak,
                3f * peak
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
     * Pressure varies with altitude according to the barometric formula.
     */
    fun generateBarometerEvent(
        timestampMs: Long,
        sensor: Sensor,
    ): SensorEvent {
        // International barometric formula (simplified)
        // P = P0 * (1 - L*h/T0)^(gM/R*L)
        // Simplified: pressure drops ~12 hPa per 100m near sea level
        val pressure = SEA_LEVEL_PRESSURE_HPA - (currentAltitude * PRESSURE_LAPSE_RATE / 100f)

        // Add small sensor noise (±0.05 hPa, typical sensor noise)
        val noise = ((Math.random() - 0.5) * 0.1).toFloat()
        val values = floatArrayOf(pressure + noise)

        return createSensorEvent(sensor, values, timestampMs, accuracy = 3)
    }

    /**
     * Generate synthetic magnetometer (compass) event.
     * Returns the device heading relative to magnetic north.
     */
    fun generateMagnetometerEvent(
        timestampMs: Long,
        sensor: Sensor,
        bearingDeg: Float,
    ): SensorEvent {
        // Simplified magnetic field for Shanghai region
        // ~48 μT total intensity, ~45° inclination
        val intensity = 48.0f
        val inclination = sin(45.0 * PI / 180.0).toFloat()

        // Rotate based on bearing
        val bearingRad = bearingDeg * PI / 180.0f
        val horizontal = intensity * cos(inclination.toDouble()).toFloat()
        val x = horizontal * sin(bearingRad.toDouble()).toFloat()
        val y = horizontal * cos(bearingRad.toDouble()).toFloat()
        val z = intensity * sin(inclination.toDouble()).toFloat()

        // Add small noise
        val noise = 0.5f
        val values = floatArrayOf(
            x + ((Math.random() - 0.5) * noise).toFloat(),
            y + ((Math.random() - 0.5) * noise).toFloat(),
            z + ((Math.random() - 0.5) * noise).toFloat()
        )

        return createSensorEvent(sensor, values, timestampMs, accuracy = 2)
    }

    /**
     * Generate synthetic gyroscope event.
     * Simulates rotation rate changes during running (arm swing, body lean).
     */
    fun generateGyroscopeEvent(
        timestampMs: Long,
        sensor: Sensor,
        bearingChangeRate: Float = 0f,
    ): SensorEvent {
        // Natural sway during running
        val swayFreq = 2.0f * Math.PI.toFloat() * targetStepsPerMinute / 60.0f
        val swayPhase = (timestampMs / 1000.0f * swayFreq) % (2 * Math.PI.toFloat())

        // Small rotations from arm swing and body motion
        val values = floatArrayOf(
            (sin(swayPhase.toDouble()) * 0.5).toFloat(),   // X: pitch variation
            bearingChangeRate,                              // Y: intentional turning
            (cos(swayPhase.toDouble()) * 0.3).toFloat()    // Z: roll variation
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
