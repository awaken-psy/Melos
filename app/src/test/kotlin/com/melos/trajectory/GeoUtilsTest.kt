package com.melos.trajectory

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GeoUtilsTest {

    // ── haversineMeters ──────────────────────────────────────────────

    @Test
    fun `haversine same point is zero`() {
        val p = LatLng(31.0, 121.0)
        assertEquals(0.0, GeoUtils.haversineMeters(p, p), 0.01)
    }

    @Test
    fun `haversine 1 degree latitude ~111 km`() {
        val a = LatLng(0.0, 0.0)
        val b = LatLng(1.0, 0.0)
        val meters = GeoUtils.haversineMeters(a, b)
        // IUGG mean radius 6371008.8m → 1° lat ≈ 111195m
        assertTrue("Expected ~111195m, got $meters", meters in 111000.0..111400.0)
    }

    @Test
    fun `haversine 1 degree longitude at equator ~111 km`() {
        val a = LatLng(0.0, 0.0)
        val b = LatLng(0.0, 1.0)
        val meters = GeoUtils.haversineMeters(a, b)
        assertTrue("Expected ~111195m, got $meters", meters in 111000.0..111400.0)
    }

    @Test
    fun `haversine 1 degree longitude at 60N ~55 km`() {
        val a = LatLng(60.0, 0.0)
        val b = LatLng(60.0, 1.0)
        val meters = GeoUtils.haversineMeters(a, b)
        assertTrue("Expected ~55600m, got $meters", meters in 55400.0..55800.0)
    }

    @Test
    fun `haversine is symmetric`() {
        val a = LatLng(31.2503, 121.5045)
        val b = LatLng(31.2520, 121.5070)
        assertEquals(
            GeoUtils.haversineMeters(a, b),
            GeoUtils.haversineMeters(b, a),
            0.001
        )
    }

    @Test
    fun `haversine known distance Tongji campus`() {
        // 0.001° lat ≈ 111m, 0.001° lng at 31°N ≈ 95m → diagonal ≈ 146m
        val a = LatLng(31.2500, 121.5040)
        val b = LatLng(31.2510, 121.5050)
        val meters = GeoUtils.haversineMeters(a, b)
        assertTrue("Expected ~130-160m, got $meters", meters in 130.0..160.0)
    }

    @Test
    fun `haversine antipodal points`() {
        // Antipodal points should be ~20015 km (half circumference)
        val a = LatLng(0.0, 0.0)
        val b = LatLng(0.0, 180.0)
        val meters = GeoUtils.haversineMeters(a, b)
        assertTrue("Expected ~20015km, got ${meters / 1000}km", meters in 20_010_000.0..20_020_000.0)
    }

    // ── bearingDeg ──────────────────────────────────────────────────

    @Test
    fun `bearing due north is 0`() {
        val a = LatLng(0.0, 0.0)
        val b = LatLng(1.0, 0.0)
        assertEquals(0.0, GeoUtils.bearingDeg(a, b), 0.01)
    }

    @Test
    fun `bearing due east is 90`() {
        val a = LatLng(0.0, 0.0)
        val b = LatLng(0.0, 1.0)
        assertEquals(90.0, GeoUtils.bearingDeg(a, b), 0.01)
    }

    @Test
    fun `bearing due south is 180`() {
        val a = LatLng(1.0, 0.0)
        val b = LatLng(0.0, 0.0)
        assertEquals(180.0, GeoUtils.bearingDeg(a, b), 0.01)
    }

    @Test
    fun `bearing due west is 270`() {
        val a = LatLng(0.0, 1.0)
        val b = LatLng(0.0, 0.0)
        assertEquals(270.0, GeoUtils.bearingDeg(a, b), 0.01)
    }

    @Test
    fun `bearing is in range 0-360`() {
        val a = LatLng(31.2503, 121.5045)
        val b = LatLng(31.2520, 121.5070)
        val bearing = GeoUtils.bearingDeg(a, b)
        assertTrue("Bearing $bearing not in [0,360)", bearing >= 0.0 && bearing < 360.0)
    }

    @Test
    fun `bearing NE is roughly 45`() {
        val a = LatLng(0.0, 0.0)
        val b = LatLng(1.0, 1.0)
        val bearing = GeoUtils.bearingDeg(a, b)
        assertTrue("Expected ~44-46, got $bearing", bearing in 44.0..46.0)
    }

    // ── destination ─────────────────────────────────────────────────

    @Test
    fun `destination north 1000m`() {
        val start = LatLng(0.0, 0.0)
        val dest = GeoUtils.destination(start, 0.0, 1000.0)
        assertEquals(0.0, dest.lng, 0.0001) // longitude unchanged
        assertTrue("Latitude should increase", dest.lat > start.lat)
        val dist = GeoUtils.haversineMeters(start, dest)
        assertEquals(1000.0, dist, 1.0)
    }

    @Test
    fun `destination east 1000m at equator`() {
        val start = LatLng(0.0, 0.0)
        val dest = GeoUtils.destination(start, 90.0, 1000.0)
        assertEquals(0.0, dest.lat, 0.0001) // latitude unchanged
        assertTrue("Longitude should increase", dest.lng > start.lng)
        val dist = GeoUtils.haversineMeters(start, dest)
        assertEquals(1000.0, dist, 1.0)
    }

    @Test
    fun `destination roundtrip preserves distance`() {
        val start = LatLng(31.2503, 121.5045)
        val bearing = 137.0
        val distance = 500.0
        val dest = GeoUtils.destination(start, bearing, distance)
        val backDist = GeoUtils.haversineMeters(start, dest)
        assertEquals(distance, backDist, 1.0)
    }

    @Test
    fun `destination zero distance returns same point`() {
        val start = LatLng(31.0, 121.0)
        val dest = GeoUtils.destination(start, 45.0, 0.0)
        assertEquals(start.lat, dest.lat, 0.0000001)
        assertEquals(start.lng, dest.lng, 0.0000001)
    }

    // ── offsetMeters ────────────────────────────────────────────────

    @Test
    fun `offset pure north moves latitude`() {
        val p = LatLng(31.0, 121.0)
        val offset = GeoUtils.offsetMeters(p, 0.0, 1000.0)
        assertEquals(p.lng, offset.lng, 0.0001) // longitude unchanged
        assertTrue("Latitude should increase", offset.lat > p.lat)
    }

    @Test
    fun `offset pure east moves longitude`() {
        val p = LatLng(31.0, 121.0)
        val offset = GeoUtils.offsetMeters(p, 1000.0, 0.0)
        assertEquals(p.lat, offset.lat, 0.0001) // latitude unchanged
        assertTrue("Longitude should increase", offset.lng > p.lng)
    }

    @Test
    fun `offset is invertible`() {
        val p = LatLng(31.25, 121.50)
        val moved = GeoUtils.offsetMeters(p, 50.0, 30.0)
        val back = GeoUtils.offsetMeters(moved, -50.0, -30.0)
        assertEquals(p.lat, back.lat, 0.0000001)
        assertEquals(p.lng, back.lng, 0.0000001)
    }

    @Test
    fun `offset magnitude matches at equator`() {
        val p = LatLng(0.0, 0.0)
        val offset = GeoUtils.offsetMeters(p, 100.0, 0.0)
        val dist = GeoUtils.haversineMeters(p, offset)
        assertEquals(100.0, dist, 1.0)
    }

    // ── lerp ────────────────────────────────────────────────────────

    @Test
    fun `lerp t=0 returns first point`() {
        val a = LatLng(31.0, 121.0)
        val b = LatLng(32.0, 122.0)
        val result = GeoUtils.lerp(a, b, 0.0)
        assertEquals(a.lat, result.lat, 0.0000001)
        assertEquals(a.lng, result.lng, 0.0000001)
    }

    @Test
    fun `lerp t=1 returns second point`() {
        val a = LatLng(31.0, 121.0)
        val b = LatLng(32.0, 122.0)
        val result = GeoUtils.lerp(a, b, 1.0)
        assertEquals(b.lat, result.lat, 0.0000001)
        assertEquals(b.lng, result.lng, 0.0000001)
    }

    @Test
    fun `lerp t=0_5 returns midpoint`() {
        val a = LatLng(31.0, 121.0)
        val b = LatLng(33.0, 123.0)
        val result = GeoUtils.lerp(a, b, 0.5)
        assertEquals(32.0, result.lat, 0.0000001)
        assertEquals(122.0, result.lng, 0.0000001)
    }

    @Test
    fun `lerp is linear`() {
        val a = LatLng(0.0, 0.0)
        val b = LatLng(10.0, 10.0)
        val r1 = GeoUtils.lerp(a, b, 0.3)
        val r2 = GeoUtils.lerp(a, b, 0.7)
        assertEquals(3.0, r1.lat, 0.0001)
        assertEquals(7.0, r2.lat, 0.0001)
    }
}
