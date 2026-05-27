package com.melos.trajectory

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TrajectoryGeneratorTest {

    private lateinit var track: TrackProfile
    private lateinit var generator: TrajectoryGenerator

    @Before
    fun setUp() {
        val center = LatLng(31.2506, 121.5045)
        track = TrackProfile(
            name = "Test 400m",
            waypoints = listOf(
                GeoUtils.offsetMeters(center, -36.5, -42.2),
                GeoUtils.offsetMeters(center, -36.5, 42.2),
                GeoUtils.offsetMeters(center, 36.5, 42.2),
                GeoUtils.offsetMeters(center, 36.5, -42.2),
            )
        )
        generator = TrajectoryGenerator(
            trackProfile = track,
            meanSpeedMps = 2.5,
            speedVariation = 0.15,
            wanderMeters = 2.0,
        )
    }

    // ── Basic generation ────────────────────────────────────────────

    @Test
    fun `nextPoint returns valid trajectory point`() {
        val point = generator.nextPoint(1.0)
        assertNotNull(point.position)
        assertTrue(point.speedMps >= 0f)
        assertTrue(point.accuracyMeters > 0f)
        assertTrue(point.altitudeMeters > 0.0)
    }

    @Test
    fun `nextPoint at time 0 produces reasonable values`() {
        val point = generator.nextPoint(0.0)
        assertEquals(0.0, point.elapsedDistanceMeters, 0.1)
    }

    @Test
    fun `consecutive points advance in time`() {
        val p1 = generator.nextPoint(1.0)
        val p2 = generator.nextPoint(2.0)
        assertEquals(1000L, p1.timestampMillis)
        assertEquals(2000L, p2.timestampMillis)
        assertTrue(p2.elapsedDistanceMeters >= p1.elapsedDistanceMeters)
    }

    @Test
    fun `consecutive points advance in distance`() {
        val points = (1..10).map { generator.nextPoint(it.toDouble()) }
        for (i in 1 until points.size) {
            assertTrue(
                "Distance should increase: ${points[i - 1].elapsedDistanceMeters} -> ${points[i].elapsedDistanceMeters}",
                points[i].elapsedDistanceMeters >= points[i - 1].elapsedDistanceMeters
            )
        }
    }

    @Test
    fun `positions stay near track`() {
        val points = (1..20).map { generator.nextPoint(it.toDouble()) }
        for (point in points) {
            val minDistToWaypoint = track.loop.minOf { wp ->
                GeoUtils.haversineMeters(point.position, wp)
            }
            // Wander is up to 2m, track half-width is ~36.5m, so max distance to any waypoint ~50m
            assertTrue(
                "Point too far from track: ${minDistToWaypoint}m at ${point.position}",
                minDistToWaypoint < 100.0
            )
        }
    }

    // ── Speed ───────────────────────────────────────────────────────

    @Test
    fun `speed is in reasonable running range after warmup`() {
        // Points after 30s should be at full speed
        val points = (31..60).map { generator.nextPoint(it.toDouble()) }
        for (point in points) {
            assertTrue(
                "Speed ${point.speedMps} out of range [0.5, 5.0]",
                point.speedMps in 0.5f..5.0f
            )
        }
    }

    @Test
    fun `warmup phase has lower speed`() {
        val gen2 = TrajectoryGenerator(track, meanSpeedMps = 2.5, speedVariation = 0.0, wanderMeters = 0.0)
        val early = gen2.nextPoint(5.0)
        val late = gen2.nextPoint(60.0)
        assertTrue(
            "Early speed (${early.speedMps}) should be less than late (${late.speedMps})",
            early.speedMps < late.speedMps
        )
    }

    @Test
    fun `speed does not exceed human limits`() {
        val points = (1..120).map { generator.nextPoint(it.toDouble()) }
        for (point in points) {
            assertTrue("Speed too high: ${point.speedMps}", point.speedMps < 12.0f)
        }
    }

    // ── Warm-up quadratic ramp ──────────────────────────────────────

    @Test
    fun `warmup speed increases over first 30 seconds`() {
        val gen2 = TrajectoryGenerator(track, meanSpeedMps = 2.5, speedVariation = 0.0, wanderMeters = 0.0)
        val speeds = mutableListOf<Float>()
        for (t in 1..30) {
            speeds.add(gen2.nextPoint(t.toDouble()).speedMps)
        }
        // General trend should be upward (not every single step due to accel limits)
        val first5Avg = speeds.take(5).average()
        val last5Avg = speeds.takeLast(5).average()
        assertTrue(
            "Warmup should show speed increase: first5=$first5Avg last5=$last5Avg",
            last5Avg > first5Avg
        )
    }

    // ── Altitude ────────────────────────────────────────────────────

    @Test
    fun `altitude is in reasonable range`() {
        val points = (1..60).map { generator.nextPoint(it.toDouble()) }
        for (point in points) {
            assertTrue(
                "Altitude ${point.altitudeMeters} out of range",
                point.altitudeMeters in 5.0..20.0
            )
        }
    }

    @Test
    fun `altitude varies smoothly`() {
        val points = (1..60).map { generator.nextPoint(it.toDouble()) }
        for (i in 1 until points.size) {
            val delta = kotlin.math.abs(points[i].altitudeMeters - points[i - 1].altitudeMeters)
            assertTrue("Altitude jumped $delta in 1 second", delta < 5.0)
        }
    }

    // ── GPS accuracy ────────────────────────────────────────────────

    @Test
    fun `accuracy is in valid GPS range`() {
        val points = (1..60).map { generator.nextPoint(it.toDouble()) }
        for (point in points) {
            assertTrue(
                "Accuracy ${point.accuracyMeters}m out of range [2.5, 18.0]",
                point.accuracyMeters in 2.5f..18.0f
            )
        }
    }

    @Test
    fun `accuracy varies smoothly`() {
        val points = (1..60).map { generator.nextPoint(it.toDouble()) }
        for (i in 1 until points.size) {
            val delta = kotlin.math.abs(points[i].accuracyMeters - points[i - 1].accuracyMeters)
            // Degradation bursts (2% chance, up to +5m) can cause jumps up to ~5.5m
            assertTrue("Accuracy jumped $delta in 1 second", delta < 6.0)
        }
    }

    // ── Bearing ─────────────────────────────────────────────────────

    @Test
    fun `bearing is in valid range`() {
        val points = (1..20).map { generator.nextPoint(it.toDouble()) }
        for (point in points) {
            assertTrue("Bearing ${point.bearingDeg} not in [0, 360)", point.bearingDeg in 0f..360f)
        }
    }

    // ── Time reversal protection ────────────────────────────────────

    @Test
    fun `time reversal does not produce negative distance`() {
        generator.nextPoint(10.0)
        val point = generator.nextPoint(5.0) // time goes backward
        assertTrue("Distance should not decrease on time reversal", point.elapsedDistanceMeters >= 0)
    }

    @Test
    fun `time reversal produces dt=0 so no advancement`() {
        val p1 = generator.nextPoint(10.0)
        val p2 = generator.nextPoint(5.0)
        assertEquals(p1.elapsedDistanceMeters, p2.elapsedDistanceMeters, 0.001)
    }

    // ── Multi-lap ──────────────────────────────────────────────────

    @Test
    fun `generator completes multiple laps`() {
        val lapTime = track.perimeterMeters / 2.5
        val totalTime = lapTime * 3
        val point = generator.nextPoint(totalTime)
        assertTrue(
            "Expected ~${track.perimeterMeters * 3}m after 3 laps, got ${point.elapsedDistanceMeters}",
            point.elapsedDistanceMeters > track.perimeterMeters * 2
        )
    }

    // ── Reset ───────────────────────────────────────────────────────

    @Test
    fun `reset clears distance`() {
        generator.nextPoint(100.0)
        assertTrue(generator.getCurrentDistance() > 0)
        generator.reset()
        assertEquals(0.0, generator.getCurrentDistance(), 0.001)
    }

    @Test
    fun `reset allows fresh start`() {
        generator.nextPoint(100.0)
        generator.reset()
        val point = generator.nextPoint(1.0)
        // Warmup phase: speed can be as low as 0.5 m/s, so 1 second → max ~0.5m
        assertTrue("Distance after reset should be small: ${point.elapsedDistanceMeters}", point.elapsedDistanceMeters < 2.0)
    }

    // ── estimateTimeForLaps ─────────────────────────────────────────

    @Test
    fun `estimateTimeForLaps is positive`() {
        val time = generator.estimateTimeForLaps(1)
        assertTrue(time > 0)
    }

    @Test
    fun `estimateTimeForLaps scales linearly`() {
        val t1 = generator.estimateTimeForLaps(1)
        val t3 = generator.estimateTimeForLaps(3)
        assertEquals(t1 * 3, t3, 0.01)
    }

    // ── getCurrentDistance ──────────────────────────────────────────

    @Test
    fun `getCurrentDistance tracks cumulative distance`() {
        assertEquals(0.0, generator.getCurrentDistance(), 0.001)
        generator.nextPoint(1.0)
        val d1 = generator.getCurrentDistance()
        assertTrue(d1 >= 0)
        generator.nextPoint(2.0)
        val d2 = generator.getCurrentDistance()
        assertTrue(d2 >= d1)
    }

    // ── Real speed/altitude profile interpolation ────────────────

    @Test
    fun `real profile produces valid speeds`() {
        val gen = TrajectoryGenerator(
            trackProfile = track,
            meanSpeedMps = 2.5,
            realSpeedAltitudeProfile = simpleProfile(),
        )
        val points = (31..60).map { gen.nextPoint(it.toDouble()) }
        for (p in points) {
            assertTrue("Speed ${p.speedMps} out of range", p.speedMps in 0.5f..6.0f)
        }
    }

    @Test
    fun `real profile produces valid altitudes`() {
        val gen = TrajectoryGenerator(
            trackProfile = track,
            meanSpeedMps = 2.5,
            realSpeedAltitudeProfile = simpleProfile(),
        )
        val points = (31..60).map { gen.nextPoint(it.toDouble()) }
        for (p in points) {
            // Altitude from profile is 10-20m, plus micro variation ±0.1m
            assertTrue("Altitude ${p.altitudeMeters} out of range", p.altitudeMeters in 5.0..25.0)
        }
    }

    @Test
    fun `real profile speed differs from no-profile`() {
        val genNoProfile = TrajectoryGenerator(
            trackProfile = track,
            meanSpeedMps = 2.5,
            speedVariation = 0.0,
            wanderMeters = 0.0,
        )
        val genWithProfile = TrajectoryGenerator(
            trackProfile = track,
            meanSpeedMps = 2.5,
            speedVariation = 0.0,
            wanderMeters = 0.0,
            realSpeedAltitudeProfile = simpleProfile(),
        )
        val p1 = genNoProfile.nextPoint(60.0)
        val p2 = genWithProfile.nextPoint(60.0)
        // With a real profile, speeds should differ from mathematical model
        assertTrue("Speeds should differ: ${p1.speedMps} vs ${p2.speedMps}",
            kotlin.math.abs(p1.speedMps - p2.speedMps) > 0.01f)
    }

    private fun simpleProfile(): List<ProfilePoint> = listOf(
        ProfilePoint(0.0, 2.0, 12.0),
        ProfilePoint(0.25, 2.8, 13.0),
        ProfilePoint(0.5, 2.5, 11.0),
        ProfilePoint(0.75, 2.3, 12.5),
        ProfilePoint(1.0, 2.0, 12.0),
    )
}
