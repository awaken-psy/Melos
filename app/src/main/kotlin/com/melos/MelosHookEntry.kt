package com.melos

import android.content.Context
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import com.melos.hide.AntiDetection
import com.melos.sensor.SensorHookManager
import com.melos.sensor.SensorSimulator
import com.melos.trajectory.GeoUtils
import com.melos.trajectory.LatLng
import com.melos.trajectory.TrackProfile
import com.melos.trajectory.TrajectoryGenerator
import com.melos.trajectory.TrajectoryPoint
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Melos LSPosed Module Entry Point
 *
 * Hooks WeChat (com.tencent.mm) to inject synthetic GPS/step/sensor data
 * for academic research purposes (defeating course exercise tracker).
 *
 * Target: WeChat mini-program exercise tracker via LocationManager and
 * SensorManager spoofing with multi-sensor temporal consistency.
 */
class MelosHookEntry : IXposedHookLoadPackage {

    companion object {
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val TAG = "Melos"

        // Default location: Tongji University Siping Campus
        private const val DEFAULT_LAT = 31.2503
        private const val DEFAULT_LON = 121.5045

        // Running parameters
        private const val RUNNING_SPEED_MPS = 2.5f  // ~9 km/h
        private const val STEPS_PER_MINUTE = 160f

        // Standard 400m running track at Tongji Siping Campus.
        // Long axis oriented roughly North–South to match the real field.
        private val TONGJI_TRACK = buildTongjiTrack()

        /**
         * Build an IAAF-style 400m track (lane 1): two 84.39 m straights joined
         * by two 36.5 m-radius semicircular bends (~398 m perimeter). The bends
         * are densely sampled so the polyline interpolation traces a smooth curve
         * instead of the geometric diamond a hand-picked 4-point loop produces.
         */
        private fun buildTongjiTrack(): TrackProfile {
            val center = LatLng(31.2506, 121.5045)
            val halfStraight = 84.39 / 2.0   // metres, half of one straight
            val radius = 36.5                // metres, bend radius
            val arcSteps = 8                 // segments per semicircular bend

            fun local(eastM: Double, northM: Double): LatLng =
                GeoUtils.offsetMeters(center, eastM, northM)

            val pts = ArrayList<LatLng>()
            // West straight, south → north (east = -radius)
            pts.add(local(-radius, -halfStraight))
            pts.add(local(-radius, +halfStraight))
            // North bend, west → east (φ: 180° → 0°), interior points only
            for (i in 1 until arcSteps) {
                val phi = Math.toRadians(180.0 - 180.0 * i / arcSteps)
                pts.add(local(radius * Math.cos(phi), halfStraight + radius * Math.sin(phi)))
            }
            // East straight, north → south (east = +radius)
            pts.add(local(+radius, +halfStraight))
            pts.add(local(+radius, -halfStraight))
            // South bend, east → west (φ: 0° → -180°), interior points only
            for (i in 1 until arcSteps) {
                val phi = Math.toRadians(-180.0 * i / arcSteps)
                pts.add(local(radius * Math.cos(phi), -halfStraight + radius * Math.sin(phi)))
            }
            // TrackProfile closes the loop back to the first point automatically.
            return TrackProfile(name = "Tongji 400m Track", waypoints = pts)
        }
    }

    // Per-process simulation state (each app process gets its own instance)
    private val simulator = SensorSimulator(
        targetStepsPerMinute = STEPS_PER_MINUTE,
        runningSpeedMps = RUNNING_SPEED_MPS
    )

    private val trajectoryGenerator = TrajectoryGenerator(
        trackProfile = TONGJI_TRACK,
        meanSpeedMps = RUNNING_SPEED_MPS.toDouble(),
        speedVariation = 0.15,
        wanderMeters = 2.0
    )

    private var lastTrajectoryPoint: TrajectoryPoint? = null
    private var sensorHookManager: SensorHookManager? = null

    // Recently emitted fixes, kept so fused getLocations()/getLastLocation()
    // stay mutually consistent and can return a plausible short batch.
    private var lastSpoofedLocation: Location? = null
    private val recentLocations = mutableListOf<Location>()
    private val maxRecentLocations = 12

    // Monotonically increasing timestamp tracking
    private var lastLocationTimeMs = 0L

    // Bearing change rate for gyroscope synchronization
    private var lastBearingChangeRate = 0f

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != WECHAT_PACKAGE) {
            return
        }

        XposedBridge.log("[$TAG] WeChat detected, initializing Melos hooks...")

        try {
            // Hide the root/Xposed environment first — if the tracker detects a
            // tampered device it can reject the run before any spoofing matters.
            AntiDetection.installHooks(lpparam)

            // Initialize sensor hook manager
            sensorHookManager = SensorHookManager(lpparam, simulator)

            // Install all hooks
            hookLocationManager(lpparam)
            hookLocationListeners(lpparam)
            hookSensorManager(lpparam)
            hookFusedLocationProvider(lpparam)

            XposedBridge.log("[$TAG] All hooks installed successfully")
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] Hook installation failed: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Hook LocationManager.getLastKnownLocation() to return our spoofed location.
     */
    private fun hookLocationManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        val locationManagerClass = XposedHelpers.findClass(
            "android.location.LocationManager",
            lpparam.classLoader
        )

        XposedHelpers.findAndHookMethod(
            locationManagerClass,
            "getLastKnownLocation",
            String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val provider = param.args[0] as? String ?: return

                    // Only spoof GPS and network providers
                    if (provider !in listOf("gps", "network", "passive")) {
                        return
                    }

                    val spoofed = getCurrentSpoofedLocation(provider)
                    param.result = spoofed

                    XposedBridge.log("[$TAG] Spoofed getLastKnownLocation($provider)")
                }
            }
        )
    }

    /**
     * Hook LocationListener registration and callbacks.
     */
    private fun hookLocationListeners(lpparam: XC_LoadPackage.LoadPackageParam) {
        val locationManagerClass = XposedHelpers.findClass(
            "android.location.LocationManager",
            lpparam.classLoader
        )

        // Hook requestLocationUpdates to capture listener registration
        XposedHelpers.findAndHookMethod(
            locationManagerClass,
            "requestLocationUpdates",
            String::class.java,     // provider
            Criteria::class.java,   // criteria (nullable)
            Long::class.javaPrimitiveType,  // minTime
            Float::class.javaPrimitiveType, // minDistance
            android.location.LocationListener::class.java,
            android.os.Looper::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val listener = param.args[4] as? android.location.LocationListener ?: return
                    val minTime = param.args[2] as? Long ?: 1000L

                    XposedBridge.log("[$TAG] requestLocationUpdates intercepted (criteria), blocking real provider")

                    // Block the real registration so genuine (stationary) fixes
                    // never reach the listener; feed it our trajectory instead.
                    param.result = null
                    scheduleLocationUpdates(lpparam, listener, minTime)
                }
            }
        )

        // Hook the overload with Criteria
        XposedHelpers.findAndHookMethod(
            locationManagerClass,
            "requestLocationUpdates",
            String::class.java,
            Long::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            android.location.LocationListener::class.java,
            android.os.Looper::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val listener = param.args[3] as? android.location.LocationListener ?: return
                    val minTime = param.args[1] as? Long ?: 1000L

                    XposedBridge.log("[$TAG] requestLocationUpdates intercepted (no criteria), blocking real provider")
                    param.result = null
                    scheduleLocationUpdates(lpparam, listener, minTime)
                }
            }
        )
    }

    /**
     * Hook SensorManager for accelerometer/pressure/magnetometer spoofing.
     */
    private fun hookSensorManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        sensorHookManager?.installHooks()
    }

    /**
     * Hook FusedLocationProvider (Google Play Services).
     *
     * Spoofs LocationResult so all fused location data returns our
     * synthetic trajectory, covering apps that use GMS location APIs
     * instead of platform LocationManager.
     */
    private fun hookFusedLocationProvider(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val locationResultClass = XposedHelpers.findClass(
                "com.google.android.gms.location.LocationResult",
                lpparam.classLoader
            )

            // Hook getLocations() to return a short, chronologically-ordered
            // batch of our recent fixes (oldest → newest), as a real fused
            // result would when a couple of updates are coalesced.
            XposedHelpers.findAndHookMethod(
                locationResultClass,
                "getLocations",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        getCurrentSpoofedLocation("fused")  // advance + cache one fresh fix
                        val batch = recentLocations.takeLast(minOf(2, recentLocations.size))
                        param.result = ArrayList(batch)
                    }
                }
            )

            // Hook getLastLocation() to return the most recent cached fix without
            // advancing the trajectory, so it stays equal to getLocations().last().
            XposedHelpers.findAndHookMethod(
                locationResultClass,
                "getLastLocation",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = lastSpoofedLocation ?: getCurrentSpoofedLocation("fused")
                    }
                }
            )

            XposedBridge.log("[$TAG] FusedLocationProvider hooks installed")
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] FusedLocationProvider not available: ${e.message}")
        }
    }

    /**
     * Schedule periodic location updates to a registered listener.
     * Adds jitter (±15%) to intervals to avoid detectable periodicity.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun scheduleLocationUpdates(
        lpparam: XC_LoadPackage.LoadPackageParam,
        listener: android.location.LocationListener,
        intervalMs: Long,
    ) {
        try {
            val looperClass = XposedHelpers.findClass("android.os.Looper", lpparam.classLoader)
            val handlerClass = XposedHelpers.findClass("android.os.Handler", lpparam.classLoader)

            val mainLooper = XposedHelpers.callStaticMethod(looperClass, "getMainLooper")
            val handler = handlerClass.getConstructor(looperClass).newInstance(mainLooper)

            val locationRunnable = object : Runnable {
                override fun run() {
                    try {
                        val spoofed = getCurrentSpoofedLocation("gps")
                        listener.onLocationChanged(spoofed)

                        // Update bearing for independent sensor injection loop
                        lastTrajectoryPoint?.let { point ->
                            sensorHookManager?.startSensorInjection()
                            sensorHookManager?.updateBearing(
                                point.bearingDeg,
                                lastBearingChangeRate
                            )
                        }

                        // Jitter: ±15% variation on each interval
                        val jitter = (Math.random() * 0.3 - 0.15).toFloat()
                        val nextInterval = (intervalMs * (1.0 + jitter)).toLong()

                        XposedHelpers.callMethod(
                            handler,
                            "postDelayed",
                            this,
                            nextInterval
                        )
                    } catch (e: Throwable) {
                        XposedBridge.log("[$TAG] Error in location update: ${e.message}")
                    }
                }
            }

            XposedHelpers.callMethod(
                handler,
                "postDelayed",
                locationRunnable,
                intervalMs
            )

            XposedBridge.log("[$TAG] Scheduled location updates ~${intervalMs}ms (with jitter)")
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] Failed to schedule updates: ${e.message}")
        }
    }

    /**
     * Get the current spoofed location based on trajectory generation.
     */
    private fun getCurrentSpoofedLocation(provider: String): Location {
        val now = System.currentTimeMillis()
        val elapsedSeconds = if (simulator.getStepCount() == 0) {
            simulator.setStartTime(now)
            0.0
        } else {
            (now - simulator.getStartTime()) / 1000.0
        }

        // Generate next trajectory point
        val prevDist = lastTrajectoryPoint?.elapsedDistanceMeters ?: 0.0
        val point = trajectoryGenerator.nextPoint(elapsedSeconds)

        // Compute bearing change rate from consecutive trajectory points
        val prevPoint = lastTrajectoryPoint
        if (prevPoint != null) {
            val dtSec = elapsedSeconds - prevPoint.timestampMillis / 1000.0
            if (dtSec > 0.01) {
                var delta = point.bearingDeg - prevPoint.bearingDeg
                if (delta > 180f) delta -= 360f
                if (delta < -180f) delta += 360f
                lastBearingChangeRate = delta / dtSec.toFloat()
            } else {
                lastBearingChangeRate = 0f
            }
        }

        lastTrajectoryPoint = point

        // Update sensor simulator state
        val distDelta = point.elapsedDistanceMeters - prevDist

        // Use reflection to call updateWithGpsData (it's private)
        simulator.updateWithGpsData(
            timestampMs = now,
            altitudeMeters = point.altitudeMeters,
            distanceDeltaMeters = distDelta
        )

        // Build Location object from trajectory point, then cache it so the
        // fused-provider hooks can return a consistent recent history.
        val location = createLocationFromTrajectory(point, provider)
        lastSpoofedLocation = location
        recentLocations.add(location)
        if (recentLocations.size > maxRecentLocations) {
            recentLocations.removeAt(0)
        }
        return location
    }

    /**
     * Create an Android Location object from a TrajectoryPoint.
     * Uses real wall-clock timestamps to prevent detectable time anomalies.
     */
    private fun createLocationFromTrajectory(point: TrajectoryPoint, provider: String): Location {
        val now = System.currentTimeMillis()

        // Ensure monotonic timestamps — never go backward
        val locationTime = if (now > lastLocationTimeMs) now else lastLocationTimeMs + 1
        lastLocationTimeMs = locationTime

        // GPS cold start: degrade accuracy for first 20 seconds
        val runElapsedMs = if (simulator.getStartTime() > 0L) now - simulator.getStartTime() else 0L
        val coldStartFactor = if (runElapsedMs in 1..20000) {
            1.0f + (1.0f - runElapsedMs.toFloat() / 20000f) * 4.0f  // 5x at start → 1x at 20s
        } else {
            1.0f
        }

        val effectiveAccuracy = (point.accuracyMeters * coldStartFactor).coerceIn(2.5f, 50.0f)

        val location = Location(provider).apply {
            latitude = point.position.lat
            longitude = point.position.lng
            time = locationTime
            accuracy = effectiveAccuracy
            altitude = point.altitudeMeters
            bearing = point.bearingDeg
            speed = point.speedMps

            if (android.os.Build.VERSION.SDK_INT >= 18) {
                try {
                    val elapsedNanos = android.os.SystemClock.elapsedRealtimeNanos()
                    setElapsedRealtimeNanos(elapsedNanos)
                } catch (e: Throwable) {}
            }

            if (android.os.Build.VERSION.SDK_INT >= 26) {
                try {
                    setSpeedAccuracyMetersPerSecond((0.1f + Math.random() * 0.2).toFloat() * coldStartFactor)
                    setBearingAccuracyDegrees(
                        if (point.speedMps > 0.5f) (15.0f + Math.random() * 15.0).toFloat() * coldStartFactor
                        else 180.0f
                    )
                    setVerticalAccuracyMeters(effectiveAccuracy * 1.5f)
                } catch (e: Throwable) {}
            }
        }

        // Realistic GPS extras with cold-start satellite ramp
        val satellites = if (runElapsedMs in 1..20000) {
            Math.max(3, (9.0 * runElapsedMs.toDouble() / 20000).toInt())
        } else {
            9 + (Math.random() * 5).toInt()
        }
        val hdop = effectiveAccuracy / 5.5f
        val vdop = hdop * (1.2f + Math.random() * 0.6).toFloat()
        val pdop = Math.sqrt((hdop * hdop + vdop * vdop).toDouble()).toFloat()

        val extras = Bundle().apply {
            putInt("satellites", satellites)
            putFloat("hdop", hdop)
            putFloat("vdop", vdop)
            putFloat("pdop", pdop)
        }
        location.extras = extras

        return location
    }
}

/**
 * Criteria class stub for LocationManager hooking (nullable param).
 */
private class Criteria
