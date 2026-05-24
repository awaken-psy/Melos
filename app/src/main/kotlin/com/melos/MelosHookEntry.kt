package com.melos

import android.content.Context
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
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

        // Standard 400m running track at Tongji Siping Campus (approximate)
        private val TONGJI_TRACK = TrackProfile(
            name = "Tongji 400m Track",
            waypoints = listOf(
                LatLng(31.2503, 121.5045),  // Start/finish line
                LatLng(31.2506, 121.5047),  // North corner
                LatLng(31.2509, 121.5045),  // East corner
                LatLng(31.2506, 121.5043),  // South corner
            )
        )
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

    // Monotonically increasing timestamp tracking
    private var lastLocationTimeMs = 0L

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != WECHAT_PACKAGE) {
            return
        }

        XposedBridge.log("[$TAG] WeChat detected, initializing Melos hooks...")

        try {
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
                override fun afterHookedMethod(param: MethodHookParam) {
                    val listener = param.args[4] as? android.location.LocationListener ?: return
                    val minTime = param.args[2] as? Long ?: 1000L

                    XposedBridge.log("[$TAG] Location listener registered, scheduling updates...")

                    // Schedule periodic location updates
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
                override fun afterHookedMethod(param: MethodHookParam) {
                    val listener = param.args[3] as? android.location.LocationListener ?: return
                    val minTime = param.args[1] as? Long ?: 1000L

                    XposedBridge.log("[$TAG] Location listener registered (no criteria), scheduling updates...")
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
     * Hook FusedLocationProvider (Google Play Services) if present.
     */
    private fun hookFusedLocationProvider(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findClass(
                "com.google.android.gms.location.LocationResult",
                lpparam.classLoader
            )

            XposedBridge.log("[$TAG] FusedLocationProvider detected")

            // Note: Full FusedLocationProvider spoofing requires additional work
            // The LocationListener hooks should cover most WeChat usage
        } catch (e: Throwable) {
            // FusedLocationProvider not available
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

                        lastTrajectoryPoint?.let { point ->
                            sensorHookManager?.injectSensorEvents(
                                bearingDeg = point.bearingDeg,
                                bearingChangeRate = 0f
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
        lastTrajectoryPoint = point

        // Update sensor simulator state
        val distDelta = point.elapsedDistanceMeters - prevDist

        // Use reflection to call updateWithGpsData (it's private)
        simulator.updateWithGpsData(
            timestampMs = now,
            altitudeMeters = point.altitudeMeters,
            distanceDeltaMeters = distDelta
        )

        // Build Location object from trajectory point
        return createLocationFromTrajectory(point, provider)
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

        val location = Location(provider).apply {
            latitude = point.position.lat
            longitude = point.position.lng
            time = locationTime
            accuracy = point.accuracyMeters
            altitude = point.altitudeMeters
            bearing = point.bearingDeg
            speed = point.speedMps

            if (android.os.Build.VERSION.SDK_INT >= 18) {
                try {
                    // Use real elapsed realtime, not simulated
                    val elapsedNanos = android.os.SystemClock.elapsedRealtimeNanos()
                    setElapsedRealtimeNanos(elapsedNanos)
                } catch (e: Throwable) {
                    // Ignore on older platforms
                }
            }
        }

        // Extras with slight satellite count variation
        val extras = Bundle()
        extras.putInt("satellites", 9 + (Math.random() * 5).toInt())
        location.extras = extras

        return location
    }
}

/**
 * Criteria class stub for LocationManager hooking (nullable param).
 */
private class Criteria
