package com.melos.trajectory

/** A position resolved at some distance along the track, plus the heading there. */
data class PositionOnTrack(val position: LatLng, val bearingDeg: Double)

/**
 * The physical running loop, described by an ordered list of [waypoints] traced
 * along the track (e.g. corners and straights of a 400 m oval). The loop is closed
 * automatically — you do not need to repeat the first point at the end.
 *
 * Distances are measured along the polyline, so [pointAtDistance] can place a runner
 * anywhere on the loop and wrap cleanly across the start/finish line for multiple laps.
 */
class TrackProfile(
    val name: String,
    waypoints: List<LatLng>,
) {
    /** Waypoints with the loop explicitly closed (last == first). */
    val loop: List<LatLng>

    /** Length of each closed-loop segment, in metres; `segmentLengths[i]` joins `loop[i]`→`loop[i+1]`. */
    val segmentLengths: List<Double>

    /** Cumulative distance at the start of each segment; same length as [segmentLengths]. */
    private val cumulative: List<Double>

    /** Total loop length (one lap), in metres. */
    val perimeterMeters: Double

    init {
        require(waypoints.size >= 3) { "A track needs at least 3 waypoints, got ${waypoints.size}" }
        loop = if (waypoints.first() == waypoints.last()) waypoints else waypoints + waypoints.first()

        val lengths = ArrayList<Double>(loop.size - 1)
        val cum = ArrayList<Double>(loop.size - 1)
        var acc = 0.0
        for (i in 0 until loop.size - 1) {
            cum.add(acc)
            val seg = GeoUtils.haversineMeters(loop[i], loop[i + 1])
            lengths.add(seg)
            acc += seg
        }
        segmentLengths = lengths
        cumulative = cum
        perimeterMeters = acc
        require(perimeterMeters > 0.0) { "Track perimeter must be positive" }
    }

    /**
     * Resolve a position [distance] metres into the loop. Values outside one lap wrap,
     * so callers can pass an ever-growing cumulative distance for multi-lap runs.
     */
    fun pointAtDistance(distance: Double): PositionOnTrack {
        var d = distance % perimeterMeters
        if (d < 0.0) d += perimeterMeters

        // Last segment whose start is <= d. Linear scan is fine for a handful of waypoints.
        var idx = 0
        for (i in segmentLengths.indices) {
            if (cumulative[i] <= d) idx = i else break
        }

        val into = d - cumulative[idx]
        val segLen = segmentLengths[idx]
        val t = if (segLen > 0.0) (into / segLen).coerceIn(0.0, 1.0) else 0.0

        // Catmull-Rom control points from closed loop
        val n = loop.size - 1
        val cp0 = loop[Math.floorMod(idx - 1, n)]
        val cp1 = loop[idx]
        val cp2 = loop[idx + 1]
        val cp3 = loop[Math.floorMod(idx + 2, n)]

        val pos = GeoUtils.catmullRom(cp0, cp1, cp2, cp3, t)

        // Bearing from spline tangent (numerical differentiation)
        val eps = 0.001
        val ta = (t - eps).coerceIn(0.0, 1.0)
        val tb = (t + eps).coerceIn(0.0, 1.0)
        val pa = GeoUtils.catmullRom(cp0, cp1, cp2, cp3, ta)
        val pb = GeoUtils.catmullRom(cp0, cp1, cp2, cp3, tb)
        val bearing = GeoUtils.bearingDeg(pa, pb)

        return PositionOnTrack(position = pos, bearingDeg = bearing)
    }
}
