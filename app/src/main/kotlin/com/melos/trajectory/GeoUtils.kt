package com.melos.trajectory

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spherical-earth geodesy. At stadium scale (hundreds of metres) the spherical
 * model is accurate to well under a centimetre, which is far below GPS noise, so
 * the extra cost of an ellipsoidal model buys nothing here.
 */
object GeoUtils {
    private const val EARTH_RADIUS_M = 6_371_008.8 // IUGG mean radius

    private fun Double.toRad() = this * Math.PI / 180.0
    private fun Double.toDeg() = this * 180.0 / Math.PI

    /** Great-circle distance between two coordinates, in metres. */
    fun haversineMeters(a: LatLng, b: LatLng): Double {
        val dLat = (b.lat - a.lat).toRad()
        val dLng = (b.lng - a.lng).toRad()
        val lat1 = a.lat.toRad()
        val lat2 = b.lat.toRad()
        val h = sin(dLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h).coerceAtMost(1.0))
    }

    /** Initial bearing from [from] to [to], degrees clockwise from true north in [0, 360). */
    fun bearingDeg(from: LatLng, to: LatLng): Double {
        val lat1 = from.lat.toRad()
        val lat2 = to.lat.toRad()
        val dLng = (to.lng - from.lng).toRad()
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (atan2(y, x).toDeg() + 360.0) % 360.0
    }

    /** Point reached by travelling [distanceMeters] from [from] along [bearingDeg]. */
    fun destination(from: LatLng, bearingDeg: Double, distanceMeters: Double): LatLng {
        val angular = distanceMeters / EARTH_RADIUS_M
        val brg = bearingDeg.toRad()
        val lat1 = from.lat.toRad()
        val lng1 = from.lng.toRad()
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(brg))
        val lng2 = lng1 + atan2(
            sin(brg) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sin(lat2),
        )
        return LatLng(lat2.toDeg(), lng2.toDeg())
    }

    /**
     * Offset a point by [eastMeters] / [northMeters] in the local tangent plane.
     * Used to add isotropic GPS jitter without distorting the track shape.
     */
    fun offsetMeters(p: LatLng, eastMeters: Double, northMeters: Double): LatLng {
        val dLat = (northMeters / EARTH_RADIUS_M).toDeg()
        val dLng = (eastMeters / (EARTH_RADIUS_M * cos(p.lat.toRad()))).toDeg()
        return LatLng(p.lat + dLat, p.lng + dLng)
    }

    /** Linear interpolation between two nearby coordinates (good enough at metre scale). */
    fun lerp(a: LatLng, b: LatLng, t: Double): LatLng =
        LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)

    // ── WGS-84 → GCJ-02 coordinate conversion ────────────────────────

    private const val GCJ_A = 6378245.0
    private const val GCJ_EE = 0.00669342162296594323

    /**
     * Convert WGS-84 coordinates to GCJ-02 (China coordinate system).
     * Returns input unchanged for coordinates outside China.
     */
    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271) return Pair(lat, lng)
        var dLat = gcjTransformLat(lng - 105.0, lat - 35.0)
        var dLng = gcjTransformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        val magic = 1 - GCJ_EE * sin(radLat).let { it * it }
        val sqrtMagic = kotlin.math.sqrt(magic)
        dLat = (dLat * 180.0) / ((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic) * Math.PI)
        dLng = (dLng * 180.0) / (GCJ_A / sqrtMagic * cos(radLat) * Math.PI)
        return Pair(lat + dLat, lng + dLng)
    }

    private fun gcjTransformLat(x: Double, y: Double): Double {
        var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * kotlin.math.sqrt(kotlin.math.abs(x))
        r += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        r += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        r += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return r
    }

    private fun gcjTransformLng(x: Double, y: Double): Double {
        var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * kotlin.math.sqrt(kotlin.math.abs(x))
        r += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        r += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        r += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return r
    }
}
