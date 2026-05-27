package com.melos.trajectory

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RealTrackLoaderTest {

    // ── Null / edge cases ────────────────────────────────────────

    @Test
    fun `returns null with empty entries`() {
        assertNull(RealTrackLoader.loadFromJson("""{"entries": []}"""))
    }

    @Test
    fun `returns null with no entries key`() {
        assertNull(RealTrackLoader.loadFromJson("""{"other": []}"""))
    }

    @Test
    fun `returns null with too few samples`() {
        val json = buildJson(circularPoints(count = 10))
        assertNull(RealTrackLoader.loadFromJson(json))
    }

    @Test
    fun `returns null when all samples have poor accuracy`() {
        val points = circularPoints(count = 100).map { it.copy(accuracy = 20.0) }
        val json = buildJson(points)
        assertNull(RealTrackLoader.loadFromJson(json))
    }

    @Test
    fun `handles entry with no samples array`() {
        assertNull(RealTrackLoader.loadFromJson("""{"entries": [{"other": 1}]}"""))
    }

    @Test
    fun `handles sample with no gps object`() {
        assertNull(RealTrackLoader.loadFromJson(
            """{"entries": [{"samples": [{"other": 1}]}]}"""
        ))
    }

    @Test
    fun `handles sample with zero lat or lng`() {
        val points = listOf(
            sampleData(0.0, 121.0),  // zero lat → filtered
            sampleData(31.0, 0.0),   // zero lng → filtered
        )
        val json = buildJson(points)
        assertNull(RealTrackLoader.loadFromJson(json))
    }

    // ── Lap detection ────────────────────────────────────────────

    @Test
    fun `detects laps from circular trajectory`() {
        val points = circularLaps(laps = 5, pointsPerLap = 200)
        val json = buildJson(points)
        val result = RealTrackLoader.loadFromJson(json)
        assertNotNull("Should detect laps from circular data", result)
    }

    @Test
    fun `track has positive perimeter`() {
        val result = loadCircularTrack()
        assertTrue(
            "Perimeter should be > 100m, got ${result.trackProfile.perimeterMeters}",
            result.trackProfile.perimeterMeters > 100.0
        )
    }

    @Test
    fun `track perimeter is near 400m for standard track radius`() {
        val result = loadCircularTrack()
        val perimeter = result.trackProfile.perimeterMeters
        assertTrue("Expected ~300-500m, got $perimeter", perimeter in 300.0..500.0)
    }

    @Test
    fun `track has loop points`() {
        val result = loadCircularTrack()
        assertTrue("Should have loop points", result.trackProfile.loop.isNotEmpty())
    }

    @Test
    fun `loop points are near track center`() {
        val centerLat = 31.29217
        val centerLng = 121.21242
        val result = loadCircularTrack(centerLat, centerLng)
        val center = LatLng(centerLat, centerLng)
        for (wp in result.trackProfile.loop) {
            val dist = GeoUtils.haversineMeters(center, wp)
            assertTrue("Waypoint too far from center: ${dist}m", dist < 150.0)
        }
    }

    // ── Speed / altitude profile ─────────────────────────────────

    @Test
    fun `profile is non-empty`() {
        val result = loadCircularTrack()
        assertTrue("Profile should have points", result.speedAltitudeProfile.isNotEmpty())
    }

    @Test
    fun `profile speeds are non-negative`() {
        val result = loadCircularTrack()
        for (pp in result.speedAltitudeProfile) {
            assertTrue("Speed ${pp.speedMps} should be >= 0", pp.speedMps >= 0.0)
        }
    }

    @Test
    fun `profile fractions are monotonically increasing`() {
        val result = loadCircularTrack()
        val profile = result.speedAltitudeProfile
        for (i in 1 until profile.size) {
            assertTrue(
                "Fraction should increase: ${profile[i - 1].fraction} -> ${profile[i].fraction}",
                profile[i].fraction >= profile[i - 1].fraction
            )
        }
    }

    @Test
    fun `profile fractions span 0 to 1`() {
        val result = loadCircularTrack()
        val profile = result.speedAltitudeProfile
        assertTrue("First fraction should be ~0", profile.first().fraction < 0.1)
        assertTrue("Last fraction should be ~1", profile.last().fraction > 0.9)
    }

    // ── Spike removal ────────────────────────────────────────────

    @Test
    fun `trajectory with injected spike still produces valid result`() {
        val points = circularLaps(laps = 5, pointsPerLap = 200).toMutableList()
        // Inject a big spike at the midpoint
        val spikeIdx = points.size / 2
        val spike = points[spikeIdx].copy(lat = points[spikeIdx].lat + 0.01)
        points[spikeIdx] = spike
        val json = buildJson(points)
        val result = RealTrackLoader.loadFromJson(json)
        assertNotNull("Should handle spike gracefully", result)
    }

    // ── Speed outlier handling ────────────────────────────────────

    @Test
    fun `trajectory with far-away outlier still produces valid result`() {
        val points = circularLaps(laps = 5, pointsPerLap = 200).toMutableList()
        // Inject a far-away point (GPS jump)
        points.add(100, sampleData(32.0, 122.0, speed = 20.0))
        val json = buildJson(points)
        val result = RealTrackLoader.loadFromJson(json)
        assertNotNull("Should handle speed outlier gracefully", result)
    }

    // ── Accuracy filtering ───────────────────────────────────────

    @Test
    fun `mixes good and bad accuracy samples`() {
        val good = circularLaps(laps = 3, pointsPerLap = 200)
        val bad = circularLaps(laps = 2, pointsPerLap = 200).map { it.copy(accuracy = 20.0) }
        val json = buildJson(good + bad)
        val result = RealTrackLoader.loadFromJson(json)
        // Should work using the good samples
        assertNotNull("Should use good-accuracy samples", result)
    }

    // ── Multi-entry data ─────────────────────────────────────────

    @Test
    fun `combines samples from multiple entries`() {
        val entry1 = circularLaps(laps = 2, pointsPerLap = 200)
        val entry2 = circularLaps(laps = 3, pointsPerLap = 200)
        val json = buildMultiEntryJson(listOf(entry1, entry2))
        val result = RealTrackLoader.loadFromJson(json)
        assertNotNull("Should combine entries", result)
        assertTrue(result!!.trackProfile.loop.isNotEmpty())
    }

    // ── Helpers ──────────────────────────────────────────────────

    private data class SampleData(
        val lat: Double, val lng: Double,
        val speed: Double = 2.5, val altitude: Double = 10.0,
        val accuracy: Double = 5.0,
    )

    private fun sampleData(lat: Double, lng: Double, speed: Double = 2.5) =
        SampleData(lat, lng, speed)

    private fun circularPoints(
        count: Int,
        centerLat: Double = 31.29217,
        centerLng: Double = 121.21242,
        radiusDeg: Double = 0.00057,
    ): List<SampleData> {
        val cosLat = cos(Math.toRadians(centerLat))
        return (0 until count).map { i ->
            val angle = 2.0 * Math.PI * i / count
            SampleData(
                lat = centerLat + radiusDeg * cos(angle),
                lng = centerLng + radiusDeg * sin(angle) / cosLat,
            )
        }
    }

    private fun circularLaps(
        laps: Int,
        pointsPerLap: Int = 200,
        centerLat: Double = 31.29217,
        centerLng: Double = 121.21242,
        radiusDeg: Double = 0.00057,
        speed: Double = 2.5,
    ): List<SampleData> {
        val result = mutableListOf<SampleData>()
        for (lap in 0 until laps) {
            val cosLat = cos(Math.toRadians(centerLat))
            for (i in 0 until pointsPerLap) {
                val angle = 2.0 * Math.PI * i / pointsPerLap
                result.add(SampleData(
                    lat = centerLat + radiusDeg * cos(angle),
                    lng = centerLng + radiusDeg * sin(angle) / cosLat,
                    speed = speed,
                ))
            }
        }
        return result
    }

    private fun loadCircularTrack(
        centerLat: Double = 31.29217,
        centerLng: Double = 121.21242,
    ): RealTrackData {
        val points = circularLaps(laps = 5, pointsPerLap = 200, centerLat = centerLat, centerLng = centerLng)
        val json = buildJson(points)
        return RealTrackLoader.loadFromJson(json)!!
    }

    private fun buildJson(points: List<SampleData>): String {
        val root = JSONObject()
        val entries = JSONArray()
        val entry = JSONObject()
        val samples = JSONArray()
        for (p in points) {
            val gps = JSONObject().apply {
                put("latitude", p.lat)
                put("longitude", p.lng)
                put("accuracy", p.accuracy)
                put("speed", p.speed)
                put("altitude", p.altitude)
            }
            samples.put(JSONObject().put("gps", gps))
        }
        entry.put("samples", samples)
        entries.put(entry)
        root.put("entries", entries)
        return root.toString()
    }

    private fun buildMultiEntryJson(entries: List<List<SampleData>>): String {
        val root = JSONObject()
        val entriesArr = JSONArray()
        for (points in entries) {
            val samples = JSONArray()
            for (p in points) {
                val gps = JSONObject().apply {
                    put("latitude", p.lat)
                    put("longitude", p.lng)
                    put("accuracy", p.accuracy)
                    put("speed", p.speed)
                    put("altitude", p.altitude)
                }
                samples.put(JSONObject().put("gps", gps))
            }
            entriesArr.put(JSONObject().put("samples", samples))
        }
        root.put("entries", entriesArr)
        return root.toString()
    }
}
