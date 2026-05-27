package com.melos.trajectory

import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Loads real GPS trajectory data from the fingerprint JSON, cleans it, and
 * produces a [TrackProfile] with speed/altitude profiles for [TrajectoryGenerator].
 *
 * Cleaning pipeline:
 *   1. Accuracy filter  — drop samples with accuracy > [MAX_ACCURACY]
 *   2. Spike removal    — drop single-point GPS jumps (far from both neighbours)
 *   3. Speed filter     — drop points whose implied speed > [MAX_RUN_SPEED]
 *   4. Multi-pass       — repeat spike+speed checks until stable
 *   5. Smoothing        — moving-average on remaining lat/lng
 *   6. Lap detection    — haversine-based lap boundary detection
 *   7. Multi-lap median — median position at each fraction across all laps
 */
object RealTrackLoader {

    private const val FINGERPRINT_PATH = "/data/local/tmp/melos_fingerprint.json"
    private const val MAX_ACCURACY = 15.0
    private const val SPIKE_HOP_METERS = 20.0    // if point is >20m from BOTH neighbours…
    private const val SPIKE_SKIP_METERS = 15.0   // …but neighbours are <15m apart → spike
    private const val MAX_RUN_SPEED = 8.0         // m/s (~29 km/h), anything above is GPS error
    private const val SMOOTH_WINDOW = 5           // moving-average window size
    private const val LAP_CLOSE_METERS = 15.0
    private const val MIN_LAP_METERS = 300.0
    private const val MIN_LAP_SAMPLES = 40
    private const val WAYPOINT_COUNT = 24
    private const val OUTWARD_SHIFT_METERS = 4.0

    fun load(): RealTrackData? {
        val file = File(FINGERPRINT_PATH)
        if (!file.exists()) return null
        return loadFromJson(file.readText())
    }

    fun loadFromJson(json: String): RealTrackData? {
        val root = JSONObject(json)
        val entries = root.optJSONArray("entries") ?: return null

        // 1. Collect + accuracy filter
        val raw = mutableListOf<GpsSample>()
        for (i in 0 until entries.length()) {
            val samples = entries.getJSONObject(i).optJSONArray("samples") ?: continue
            for (j in 0 until samples.length()) {
                val gps = samples.getJSONObject(j).optJSONObject("gps") ?: continue
                val accuracy = gps.optDouble("accuracy", 999.0)
                if (accuracy > MAX_ACCURACY) continue
                val lat = gps.optDouble("latitude", 0.0)
                val lng = gps.optDouble("longitude", 0.0)
                if (lat == 0.0 || lng == 0.0) continue
                raw.add(GpsSample(lat, lng, gps.optDouble("speed", 0.0), gps.optDouble("altitude", 0.0)))
            }
        }
        if (raw.size < MIN_LAP_SAMPLES) return null

        // 2–4. Multi-pass spike + speed cleaning
        val cleaned = multiPassClean(raw)

        if (cleaned.size < MIN_LAP_SAMPLES) return null

        // 5. Smooth trajectory
        val smoothed = smoothTrajectory(cleaned)

        // 6. Detect laps
        val laps = detectLaps(smoothed)
        if (laps.isEmpty()) return null

        // 7. Build track from multi-lap median
        return buildFromLaps(laps)
    }

    // ── cleaning ──────────────────────────────────────────────────────────

    private data class GpsSample(
        val lat: Double, val lng: Double,
        val speed: Double, val altitude: Double,
    )

    private data class Lap(val samples: List<GpsSample>, val distance: Double)

    /**
     * Repeatedly remove spikes and speed-outliers until the list stops shrinking.
     */
    private fun multiPassClean(points: List<GpsSample>): List<GpsSample> {
        var current = points
        for (pass in 0..5) {
            val afterSpikes = removeSpikes(current)
            val afterSpeed = removeSpeedOutliers(afterSpikes)
            if (afterSpeed.size == current.size) break   // stable
            current = afterSpeed
        }
        return current
    }

    /**
     * Remove single-point GPS spikes: point[i] is far from both neighbours,
     * but neighbours are close to each other.
     */
    private fun removeSpikes(points: List<GpsSample>): List<GpsSample> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size) { true }
        for (i in 1 until points.size - 1) {
            val dPrev = haversine(points[i - 1], points[i])
            val dNext = haversine(points[i], points[i + 1])
            val dSkip = haversine(points[i - 1], points[i + 1])
            if (dPrev > SPIKE_HOP_METERS && dNext > SPIKE_HOP_METERS && dSkip < SPIKE_SKIP_METERS) {
                keep[i] = false
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * Remove points where implied speed between consecutive samples
     * exceeds [MAX_RUN_SPEED].
     */
    private fun removeSpeedOutliers(points: List<GpsSample>): List<GpsSample> {
        if (points.size < 2) return points
        val result = mutableListOf(points.first())
        for (i in 1 until points.size) {
            val d = haversine(points[i - 1], points[i])
            // GPS samples are ~1s apart; be conservative
            if (d <= MAX_RUN_SPEED * 1.5) {
                result.add(points[i])
            }
            // else: skip this point (GPS jump), next point will compare against
            // the last kept point — handles paired jump-out/jump-back
        }
        return result
    }

    /**
     * Moving-average smoothing on lat/lng to reduce residual GPS noise.
     */
    private fun smoothTrajectory(points: List<GpsSample>): List<GpsSample> {
        if (points.size <= SMOOTH_WINDOW) return points
        val half = SMOOTH_WINDOW / 2
        return points.mapIndexed { i, _ ->
            val from = (i - half).coerceAtLeast(0)
            val to = (i + half + 1).coerceAtMost(points.size)
            val window = points.subList(from, to)
            GpsSample(
                lat = window.map { it.lat }.average(),
                lng = window.map { it.lng }.average(),
                speed = window.map { it.speed }.average(),
                altitude = points[i].altitude,  // keep original altitude
            )
        }
    }

    // ── lap detection & averaging ─────────────────────────────────────────

    private fun detectLaps(points: List<GpsSample>): List<Lap> {
        val laps = mutableListOf<Lap>()
        val start = points.first()

        var lapBuf = mutableListOf<GpsSample>()
        var cumDist = 0.0

        for (i in points.indices) {
            val p = points[i]
            lapBuf.add(p)

            if (i > 0) {
                cumDist += haversine(points[i - 1], p)
            }

            if (lapBuf.size > MIN_LAP_SAMPLES && cumDist > MIN_LAP_METERS) {
                val distToStart = haversine(p, start)
                if (distToStart < LAP_CLOSE_METERS) {
                    laps.add(Lap(lapBuf.toList(), cumDist))
                    lapBuf = mutableListOf()
                    cumDist = 0.0
                }
            }
        }
        if (lapBuf.size > MIN_LAP_SAMPLES && cumDist > MIN_LAP_METERS) {
            laps.add(Lap(lapBuf.toList(), cumDist))
        }
        return laps
    }

    private fun buildFromLaps(laps: List<Lap>): RealTrackData {
        // Normalize each lap to N evenly-spaced fractions, then take median
        val n = WAYPOINT_COUNT * 3  // higher resolution for median computation
        val normalized = laps.map { lap -> resampleLap(lap, n) }

        // Median across laps at each fraction
        val medianWaypoints = (0 until n).map { idx ->
            val lats = normalized.mapNotNull { it.getOrNull(idx)?.lat }.sorted()
            val lngs = normalized.mapNotNull { it.getOrNull(idx)?.lng }.sorted()
            val speeds = normalized.mapNotNull { it.getOrNull(idx)?.speed }.sorted()
            val alts = normalized.mapNotNull { it.getOrNull(idx)?.altitude }.sorted()
            GpsSample(
                lat = lats[lats.size / 2],
                lng = lngs[lngs.size / 2],
                speed = speeds.getOrNull(speeds.size / 2) ?: 2.5,
                altitude = alts.getOrNull(alts.size / 2) ?: 10.0,
            )
        }

        // Shift waypoints outward from centroid (compensate for inner-lane collection)
        val centroidLat = medianWaypoints.map { it.lat }.average()
        val centroidLng = medianWaypoints.map { it.lng }.average()
        val centroid = LatLng(centroidLat, centroidLng)
        val shifted = medianWaypoints.map { p ->
            val bearing = GeoUtils.bearingDeg(centroid, LatLng(p.lat, p.lng))
            val dest = GeoUtils.destination(LatLng(p.lat, p.lng), bearing, OUTWARD_SHIFT_METERS)
            p.copy(lat = dest.lat, lng = dest.lng)
        }

        // Downsample to final waypoint count
        val waypoints = downsample(shifted, WAYPOINT_COUNT)

        // Compute average lap distance from all laps
        val avgDistance = laps.map { it.distance }.average()

        val track = TrackProfile(
            name = "Real ${avgDistance.toInt()}m (${laps.size} laps, median)",
            waypoints = waypoints,
        )

        // Build speed/altitude profile from the median lap
        val profile = buildProfile(medianWaypoints)

        return RealTrackData(track, profile)
    }

    /**
     * Resample a lap's points into exactly [n] evenly-spaced-by-distance samples.
     */
    private fun resampleLap(lap: Lap, n: Int): List<GpsSample> {
        val samples = lap.samples
        if (samples.isEmpty()) return emptyList()

        // Compute cumulative distances
        val cumDist = mutableListOf(0.0)
        for (i in 1 until samples.size) {
            cumDist.add(cumDist.last() + haversine(samples[i - 1], samples[i]))
        }
        val total = cumDist.last().coerceAtLeast(1.0)

        return (0 until n).map { idx ->
            val targetDist = (idx.toDouble() / n) * total
            // Find segment containing targetDist
            var segIdx = 0
            for (i in 1 until cumDist.size) {
                if (cumDist[i] >= targetDist) { segIdx = i - 1; break }
                if (i == cumDist.size - 1) segIdx = i - 1
            }
            val segLen = cumDist[segIdx + 1] - cumDist[segIdx]
            val t = if (segLen > 0) (targetDist - cumDist[segIdx]) / segLen else 0.0
            val a = samples[segIdx]
            val b = samples[segIdx + 1]
            GpsSample(
                lat = a.lat + (b.lat - a.lat) * t,
                lng = a.lng + (b.lng - a.lng) * t,
                speed = a.speed + (b.speed - a.speed) * t,
                altitude = a.altitude + (b.altitude - a.altitude) * t,
            )
        }
    }

    private fun buildProfile(samples: List<GpsSample>): List<ProfilePoint> {
        // Cumulative distance
        val cumDist = mutableListOf(0.0)
        for (i in 1 until samples.size) {
            cumDist.add(cumDist.last() + haversine(samples[i - 1], samples[i]))
        }
        val total = cumDist.last().coerceAtLeast(1.0)

        // Smooth speed with moving average
        val windowSize = 5
        val rawSpeeds = samples.map { it.speed }
        val smoothSpeeds = rawSpeeds.mapIndexed { i, _ ->
            val from = (i - windowSize / 2).coerceAtLeast(0)
            val to = (i + windowSize / 2 + 1).coerceAtMost(rawSpeeds.size)
            rawSpeeds.subList(from, to).average()
        }

        return samples.indices.map { i ->
            ProfilePoint(
                fraction = cumDist[i] / total,
                speedMps = smoothSpeeds[i],
                altitudeMeters = samples[i].altitude,
            )
        }
    }

    private fun downsample(samples: List<GpsSample>, count: Int): List<LatLng> {
        if (samples.size <= count) return samples.map { LatLng(it.lat, it.lng) }
        val step = samples.size.toDouble() / count
        return (0 until count).map { i ->
            val idx = (i * step).toInt().coerceIn(0, samples.size - 1)
            LatLng(samples[idx].lat, samples[idx].lng)
        }
    }

    private fun haversine(a: GpsSample, b: GpsSample): Double =
        GeoUtils.haversineMeters(LatLng(a.lat, a.lng), LatLng(b.lat, b.lng))
}

data class ProfilePoint(
    val fraction: Double,   // 0‥1 along one lap
    val speedMps: Double,
    val altitudeMeters: Double,
)

data class RealTrackData(
    val trackProfile: TrackProfile,
    val speedAltitudeProfile: List<ProfilePoint>,
)
