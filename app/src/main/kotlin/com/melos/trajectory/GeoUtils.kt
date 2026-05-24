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
}
