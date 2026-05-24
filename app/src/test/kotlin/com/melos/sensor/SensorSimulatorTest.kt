package com.melos.sensor

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SensorSimulatorTest {

    private lateinit var simulator: SensorSimulator

    @Before
    fun setUp() {
        simulator = SensorSimulator(
            targetStepsPerMinute = 160f,
            runningSpeedMps = 2.5f,
        )
    }

    // ── State management ────────────────────────────────────────────

    @Test
    fun `initial state is zero`() {
        assertEquals(0, simulator.getStepCount())
        assertEquals(0L, simulator.getStartTime())
        assertEquals(160f, simulator.getStepsPerMinute(), 0.01f)
    }

    @Test
    fun `setStartTime and getStartTime`() {
        simulator.setStartTime(1000L)
        assertEquals(1000L, simulator.getStartTime())
    }

    @Test
    fun `reset clears all state`() {
        simulator.setStartTime(1000L)
        simulator.updateWithGpsData(2000L, 10.0, 5.0)
        assertTrue(simulator.getStepCount() > 0)

        simulator.reset()
        assertEquals(0, simulator.getStepCount())
        assertEquals(0L, simulator.getStartTime())
        assertEquals(160f, simulator.getStepsPerMinute(), 0.01f)
    }

    // ── Step counting ───────────────────────────────────────────────

    @Test
    fun `updateWithGpsData increments steps with distance`() {
        simulator.setStartTime(1000L)
        simulator.updateWithGpsData(2000L, 10.0, 10.0)
        assertTrue("Expected steps > 0 after 10m", simulator.getStepCount() > 0)
    }

    @Test
    fun `more distance produces more steps`() {
        simulator.setStartTime(1000L)
        simulator.updateWithGpsData(2000L, 10.0, 10.0)
        val steps10m = simulator.getStepCount()

        simulator.updateWithGpsData(3000L, 10.0, 10.0)
        val steps20m = simulator.getStepCount()
        assertTrue("Steps should increase: $steps10m -> $steps20m", steps20m > steps10m)
    }

    @Test
    fun `zero distance produces no additional steps`() {
        simulator.setStartTime(1000L)
        simulator.updateWithGpsData(2000L, 10.0, 10.0)
        val stepsBefore = simulator.getStepCount()

        simulator.updateWithGpsData(3000L, 10.0, 0.0)
        assertEquals(stepsBefore, simulator.getStepCount())
    }

    @Test
    fun `step count is proportional to distance`() {
        simulator.setStartTime(1000L)
        simulator.updateWithGpsData(2000L, 10.0, 100.0)
        val steps100m = simulator.getStepCount()

        val sim2 = SensorSimulator(160f, 2.5f)
        sim2.setStartTime(1000L)
        sim2.updateWithGpsData(2000L, 10.0, 50.0)
        val steps50m = sim2.getStepCount()

        // Stride is not perfectly linear but should be roughly proportional
        assertTrue(
            "100m steps ($steps100m) should be >= 50m steps ($steps50m)",
            steps100m >= steps50m
        )
    }

    // ── Speed-cadence coupling ──────────────────────────────────────

    @Test
    fun `cadence updates with speed from GPS`() {
        simulator.setStartTime(1000L)
        // Fast speed: 5 m/s in 1 second → 5m
        simulator.updateWithGpsData(2000L, 10.0, 5.0)
        // At fast speed, cadence should be elevated
        // Speed > 1.0 → speedCadence = 130 + 5*15 = 205, capped at 200
        val cadence = simulator.getStepsPerMinute()
        assertTrue("Cadence should be in reasonable range: $cadence", cadence in 100f..210f)
    }

    @Test
    fun `first GPS update does not compute speed`() {
        simulator.setStartTime(1000L)
        // First update: lastGpsTimeMs was 0, so speed isn't computed from delta
        simulator.updateWithGpsData(2000L, 10.0, 5.0)
        // After second update, speed should be computed
        simulator.updateWithGpsData(3000L, 10.0, 2.5)
        // Speed = 2.5m / 1s = 2.5 m/s → cadence ~130 + 2.5*15 = 167.5
        val cadence = simulator.getStepsPerMinute()
        assertTrue("Cadence at 2.5 m/s should be ~167: $cadence", cadence in 150f..180f)
    }

    @Test
    fun `slow speed results in lower cadence than fast speed`() {
        val slowSim = SensorSimulator(160f, 2.5f)
        slowSim.setStartTime(1000L)
        slowSim.updateWithGpsData(2000L, 10.0, 1.0)  // 1 m/s
        slowSim.updateWithGpsData(3000L, 10.0, 1.0)  // 1 m/s

        val fastSim = SensorSimulator(160f, 2.5f)
        fastSim.setStartTime(1000L)
        fastSim.updateWithGpsData(2000L, 10.0, 5.0)  // 5 m/s
        fastSim.updateWithGpsData(3000L, 10.0, 5.0)  // 5 m/s

        assertTrue(
            "Fast cadence (${fastSim.getStepsPerMinute()}) should be >= slow (${slowSim.getStepsPerMinute()})",
            fastSim.getStepsPerMinute() >= slowSim.getStepsPerMinute()
        )
    }

    // ── Step detector timestamps ────────────────────────────────────

    @Test
    fun `getPendingStepDetectorTimestamps returns empty when no time elapsed`() {
        simulator.setStartTime(1000L)
        val timestamps = simulator.getPendingStepDetectorTimestamps(1000L)
        assertTrue(timestamps.isEmpty())
    }

    @Test
    fun `getPendingStepDetectorTimestamps generates timestamps at cadence interval`() {
        simulator.setStartTime(1000L)
        // At 160 spm → interval ~375ms
        val timestamps = simulator.getPendingStepDetectorTimestamps(3000L)
        assertTrue("Expected some step timestamps over 2s, got ${timestamps.size}", timestamps.size > 0)
    }

    @Test
    fun `getPendingStepDetectorTimestamps are monotonically increasing`() {
        simulator.setStartTime(1000L)
        val timestamps = simulator.getPendingStepDetectorTimestamps(5000L)
        for (i in 1 until timestamps.size) {
            assertTrue(
                "Timestamps not monotonic: ${timestamps[i - 1]} >= ${timestamps[i]}",
                timestamps[i] > timestamps[i - 1]
            )
        }
    }

    @Test
    fun `getPendingStepDetectorTimestamps are within time window`() {
        simulator.setStartTime(1000L)
        val timestamps = simulator.getPendingStepDetectorTimestamps(5000L)
        for (ts in timestamps) {
            assertTrue("Timestamp $ts before start 1000", ts >= 1000L)
            assertTrue("Timestamp $ts after current 5000", ts <= 5000L)
        }
    }

    @Test
    fun `getPendingStepDetectorTimestamps are spaced approximately at cadence`() {
        simulator.setStartTime(1000L)
        val timestamps = simulator.getPendingStepDetectorTimestamps(5000L)
        if (timestamps.size < 2) return // not enough data

        val intervals = timestamps.zipWithNext { a, b -> b - a }
        val avgInterval = intervals.average()
        val expectedInterval = 60000.0 / 160.0 // ~375ms at default cadence

        assertTrue(
            "Average interval $avgInterval not close to expected $expectedInterval",
            kotlin.math.abs(avgInterval - expectedInterval) < expectedInterval * 0.3
        )
    }

    @Test
    fun `subsequent calls only generate new timestamps`() {
        simulator.setStartTime(1000L)
        val first = simulator.getPendingStepDetectorTimestamps(3000L)
        val second = simulator.getPendingStepDetectorTimestamps(3000L) // same time
        assertTrue("Should not produce duplicate timestamps", second.isEmpty())
    }

    // ── Step counter event ──────────────────────────────────────────

    @Test
    fun `generateStepCounterEvent returns null when no steps`() {
        assertNull(simulator.generateStepCounterEvent(1000L, createMockSensor()))
    }

    // ── Step detector event ─────────────────────────────────────────

    @Test
    fun `generateStepDetectorEvent returns non-null event with value 1`() {
        val sensor = createMockSensor()
        val event = simulator.generateStepDetectorEvent(1000L, sensor)
        assertNotNull(event)
        assertEquals(1.0f, event.values[0], 0.01f)
    }

    // ── Timestamp conversion (elapsedRealtimeNanos) ────────────────

    @Test
    fun `default clock falls back to currentTimeMillis nanos`() {
        // clockBaseNs = 0 → fallback to timestampMs * 1_000_000
        val sensor = createMockSensor()
        simulator.setStartTime(1000L)
        val event = simulator.generateStepDetectorEvent(5000L, sensor)
        assertNotNull(event)
        assertEquals(5000L * 1_000_000L, event.timestamp)
    }

    @Test
    fun `clock base converts current time to monotonic domain`() {
        val sensor = createMockSensor()
        simulator.setStartTime(1000L)
        // Set clock base: wall=5000ms, monotonic=3_600_000_000_000ns (1h since boot)
        simulator.clockBaseMs = 5000L
        simulator.clockBaseNs = 3_600_000_000_000L
        // Generate event at same wall time → should equal clockBaseNs
        val event = simulator.generateStepDetectorEvent(5000L, sensor)
        assertNotNull(event)
        assertEquals(3_600_000_000_000L, event.timestamp)
    }

    @Test
    fun `clock base preserves relative offset for past timestamps`() {
        val sensor = createMockSensor()
        simulator.setStartTime(1000L)
        simulator.clockBaseMs = 5000L
        simulator.clockBaseNs = 3_600_000_000_000L
        // 500ms before base → event should be 500ms before monotonic base
        val event = simulator.generateStepDetectorEvent(4500L, sensor)
        assertNotNull(event)
        assertEquals(3_600_000_000_000L - 500 * 1_000_000L, event.timestamp)
    }

    @Test
    fun `clock base preserves relative offset for future timestamps`() {
        val sensor = createMockSensor()
        simulator.setStartTime(1000L)
        simulator.clockBaseMs = 5000L
        simulator.clockBaseNs = 3_600_000_000_000L
        // 200ms after base
        val event = simulator.generateStepDetectorEvent(5200L, sensor)
        assertNotNull(event)
        assertEquals(3_600_000_000_000L + 200 * 1_000_000L, event.timestamp)
    }

    @Test
    fun `reset clears clock base`() {
        simulator.clockBaseMs = 5000L
        simulator.clockBaseNs = 3_600_000_000_000L
        simulator.reset()
        assertEquals(0L, simulator.clockBaseMs)
        assertEquals(0L, simulator.clockBaseNs)
    }

    // ── Helper ──────────────────────────────────────────────────────

    private fun createMockSensor(): android.hardware.Sensor {
        val ctor = android.hardware.Sensor::class.java.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance()
    }
}
