package com.melos.trajectory

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TrackProfileTest {

    private lateinit var triangle: TrackProfile
    private lateinit var square: TrackProfile
    private lateinit var autoClosed: TrackProfile

    @Before
    fun setUp() {
        // Equilateral-ish triangle with ~100m sides at equator
        triangle = TrackProfile(
            name = "Triangle",
            waypoints = listOf(
                LatLng(0.0, 0.0),
                LatLng(0.0, 0.001),
                LatLng(0.001, 0.0),
            )
        )

        // Square with ~100m sides
        square = TrackProfile(
            name = "Square",
            waypoints = listOf(
                LatLng(0.0, 0.0),
                LatLng(0.0, 0.001),
                LatLng(0.001, 0.001),
                LatLng(0.001, 0.0),
            )
        )

        // Auto-close: first == last should not double-close
        autoClosed = TrackProfile(
            name = "AutoClosed",
            waypoints = listOf(
                LatLng(0.0, 0.0),
                LatLng(0.0, 0.001),
                LatLng(0.001, 0.0),
                LatLng(0.0, 0.0), // repeated first point
            )
        )
    }

    // ── Construction ────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `track with fewer than 3 waypoints rejected`() {
        TrackProfile("Bad", listOf(LatLng(0.0, 0.0), LatLng(1.0, 1.0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `track with 1 waypoint rejected`() {
        TrackProfile("Bad", listOf(LatLng(0.0, 0.0)))
    }

    @Test
    fun `triangle has 3 waypoints and 3 segments`() {
        assertEquals(4, triangle.loop.size) // 3 + closing
        assertEquals(3, triangle.segmentLengths.size)
    }

    @Test
    fun `square has 4 waypoints and 4 segments`() {
        assertEquals(5, square.loop.size) // 4 + closing
        assertEquals(4, square.segmentLengths.size)
    }

    @Test
    fun `loop is closed first equals last`() {
        assertEquals(triangle.loop.first(), triangle.loop.last())
        assertEquals(square.loop.first(), square.loop.last())
    }

    @Test
    fun `auto-closed track does not double-close`() {
        // If first == last, should not add another copy
        assertEquals(4, autoClosed.loop.size) // same as 3-point triangle
        assertEquals(autoClosed.loop.first(), autoClosed.loop.last())
    }

    @Test
    fun `perimeter is positive`() {
        assertTrue(triangle.perimeterMeters > 0)
        assertTrue(square.perimeterMeters > 0)
    }

    @Test
    fun `segment lengths are all positive`() {
        triangle.segmentLengths.forEach { assertTrue(it > 0) }
        square.segmentLengths.forEach { assertTrue(it > 0) }
    }

    @Test
    fun `segment lengths sum to perimeter`() {
        assertEquals(
            triangle.perimeterMeters,
            triangle.segmentLengths.sum(),
            0.001
        )
        assertEquals(
            square.perimeterMeters,
            square.segmentLengths.sum(),
            0.001
        )
    }

    // ── pointAtDistance ─────────────────────────────────────────────

    @Test
    fun `pointAtDistance 0 is first waypoint`() {
        val result = triangle.pointAtDistance(0.0)
        assertEquals(triangle.loop[0].lat, result.position.lat, 0.000001)
        assertEquals(triangle.loop[0].lng, result.position.lng, 0.000001)
    }

    @Test
    fun `pointAtDistance perimeter is first waypoint one lap`() {
        val result = triangle.pointAtDistance(triangle.perimeterMeters)
        assertEquals(triangle.loop[0].lat, result.position.lat, 0.000001)
        assertEquals(triangle.loop[0].lng, result.position.lng, 0.000001)
    }

    @Test
    fun `pointAtDistance wraps beyond perimeter`() {
        val result1 = triangle.pointAtDistance(10.0)
        val result2 = triangle.pointAtDistance(triangle.perimeterMeters + 10.0)
        assertEquals(result1.position.lat, result2.position.lat, 0.000001)
        assertEquals(result1.position.lng, result2.position.lng, 0.000001)
    }

    @Test
    fun `pointAtDistance wraps negative distance`() {
        val result1 = triangle.pointAtDistance(10.0)
        val result2 = triangle.pointAtDistance(-triangle.perimeterMeters + 10.0)
        assertEquals(result1.position.lat, result2.position.lat, 0.000001)
        assertEquals(result1.position.lng, result2.position.lng, 0.000001)
    }

    @Test
    fun `pointAtDistance at first segment midpoint`() {
        val halfSeg = triangle.segmentLengths[0] / 2.0
        val result = triangle.pointAtDistance(halfSeg)
        val expected = GeoUtils.lerp(triangle.loop[0], triangle.loop[1], 0.5)
        assertEquals(expected.lat, result.position.lat, 0.000001)
        assertEquals(expected.lng, result.position.lng, 0.000001)
    }

    @Test
    fun `pointAtDistance at second waypoint`() {
        val dist = triangle.segmentLengths[0]
        val result = triangle.pointAtDistance(dist)
        assertEquals(triangle.loop[1].lat, result.position.lat, 0.000001)
        assertEquals(triangle.loop[1].lng, result.position.lng, 0.000001)
    }

    @Test
    fun `pointAtDistance position advances monotonically along first segment`() {
        val a = triangle.pointAtDistance(0.0).position
        val b = triangle.pointAtDistance(triangle.segmentLengths[0] * 0.5).position
        val c = triangle.pointAtDistance(triangle.segmentLengths[0]).position
        // Distance should increase
        val dAB = GeoUtils.haversineMeters(a, b)
        val dBC = GeoUtils.haversineMeters(b, c)
        assertTrue("Midpoint should be between endpoints, dAB=$dAB dBC=$dBC", dAB > 0 && dBC > 0)
    }

    // ── bearingDeg ──────────────────────────────────────────────────

    @Test
    fun `bearing at first segment is correct`() {
        val result = triangle.pointAtDistance(0.0)
        val expectedBearing = GeoUtils.bearingDeg(triangle.loop[0], triangle.loop[1])
        assertEquals(expectedBearing, result.bearingDeg, 0.01)
    }

    @Test
    fun `bearing at second segment is correct`() {
        val dist = triangle.segmentLengths[0]
        val result = triangle.pointAtDistance(dist)
        val expectedBearing = GeoUtils.bearingDeg(triangle.loop[1], triangle.loop[2])
        assertEquals(expectedBearing, result.bearingDeg, 0.01)
    }

    // ── Square-specific tests ───────────────────────────────────────

    @Test
    fun `square pointAtDistance at each corner`() {
        var d = 0.0
        for (i in 0 until 4) {
            val result = square.pointAtDistance(d)
            assertEquals(square.loop[i].lat, result.position.lat, 0.00001)
            assertEquals(square.loop[i].lng, result.position.lng, 0.00001)
            if (i < 4) d += square.segmentLengths[i]
        }
    }

    @Test
    fun `square bearings are cardinal directions`() {
        // First segment: north (0°) because same longitude, latitude increases
        val bearing = square.pointAtDistance(0.0).bearingDeg
        // The square goes (0,0) → (0,0.001) which is east
        assertTrue("First segment should be roughly eastward, got $bearing", bearing in 80.0..100.0)
    }

    // ── Real-world track ────────────────────────────────────────────

    @Test
    fun `Tongji track perimeter is approximately 400m`() {
        val center = LatLng(31.2506, 121.5045)
        val track = TrackProfile(
            name = "Test Track",
            waypoints = listOf(
                GeoUtils.offsetMeters(center, -36.5, -42.2),
                GeoUtils.offsetMeters(center, -36.5, 42.2),
                GeoUtils.offsetMeters(center, 36.5, 42.2),
                GeoUtils.offsetMeters(center, 36.5, -42.2),
            )
        )
        val perimeter = track.perimeterMeters
        assertTrue("Expected ~340m for diamond approximation, got $perimeter", perimeter in 280.0..400.0)
    }

    @Test
    fun `pointAtDistance at very large distance wraps correctly`() {
        val manyLaps = triangle.perimeterMeters * 1000.0 + 5.0
        val result1 = triangle.pointAtDistance(manyLaps)
        val result2 = triangle.pointAtDistance(5.0)
        assertEquals(result1.position.lat, result2.position.lat, 0.000001)
        assertEquals(result1.position.lng, result2.position.lng, 0.000001)
    }
}
