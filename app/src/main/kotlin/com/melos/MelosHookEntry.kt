package com.melos

import android.content.Context
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.app.PendingIntent
import com.melos.fingerprint.FingerprintDatabase
import com.melos.fingerprint.WifiCellHookManager
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

        // IAAF-style 400m track builder. Same geometry reused for all campuses.
        private fun build400mTrack(center: LatLng, name: String): TrackProfile {
            val halfStraight = 84.39 / 2.0
            val radius = 36.5
            val arcSteps = 8

            fun local(eastM: Double, northM: Double): LatLng =
                GeoUtils.offsetMeters(center, eastM, northM)

            val pts = ArrayList<LatLng>()
            pts.add(local(-radius, -halfStraight))
            pts.add(local(-radius, +halfStraight))
            for (i in 1 until arcSteps) {
                val phi = Math.toRadians(180.0 - 180.0 * i / arcSteps)
                pts.add(local(radius * Math.cos(phi), halfStraight + radius * Math.sin(phi)))
            }
            pts.add(local(+radius, +halfStraight))
            pts.add(local(+radius, -halfStraight))
            for (i in 1 until arcSteps) {
                val phi = Math.toRadians(-180.0 * i / arcSteps)
                pts.add(local(radius * Math.cos(phi), -halfStraight + radius * Math.sin(phi)))
            }
            return TrackProfile(name = name, waypoints = pts)
        }

        private val JIADING_TRACK = build400mTrack(
            LatLng(31.29217, 121.21242), "Jiading 400m Track"
        )
        private val TONGJI_TRACK = build400mTrack(
            LatLng(31.2506, 121.5045), "Tongji 400m Track"
        )
    }

    // Per-process simulation state (each app process gets its own instance)
    private val simulator = SensorSimulator(
        targetStepsPerMinute = STEPS_PER_MINUTE,
        runningSpeedMps = RUNNING_SPEED_MPS
    )

    private val trajectoryGenerator = TrajectoryGenerator(
        trackProfile = JIADING_TRACK,
        meanSpeedMps = RUNNING_SPEED_MPS.toDouble(),
        speedVariation = 0.15,
        wanderMeters = 2.0
    )

    private var lastTrajectoryPoint: TrajectoryPoint? = null
    private var sensorHookManager: SensorHookManager? = null
    private val fingerprintDatabase = FingerprintDatabase()
    private var wifiCellHookManager: WifiCellHookManager? = null

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

            // Initialize WiFi/Cell hook manager
            wifiCellHookManager = WifiCellHookManager(lpparam, fingerprintDatabase)
            wifiCellHookManager?.installHooks()

            // Install all hooks
            hookLocationGetters()
            hookLocationManager(lpparam)
            hookLocationListeners(lpparam)
            hookNewLocationAPIs(lpparam)
            hookSensorManager(lpparam)
            hookFusedLocationProvider(lpparam)

            XposedBridge.log("[$TAG] All hooks installed successfully")
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] Hook installation failed: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Hook Location.getLatitude() / getLongitude() to replace any real GPS
     * that leaks through unhooked APIs (e.g. GMS FusedLocationProvider).
     * Our own spoofed Locations are marked and left untouched.
     */
    private fun hookLocationGetters() {
        val replaceIfReal = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val loc = param.thisObject as? Location ?: return
                if (loc.extras?.getBoolean("melos_spoofed") == true) return
                val spoofed = lastSpoofedLocation ?: getCurrentSpoofedLocation("anti-leak")
                when (param.method.name) {
                    "getLatitude" -> param.result = spoofed.latitude
                    "getLongitude" -> param.result = spoofed.longitude
                    "getAltitude" -> param.result = spoofed.altitude
                    "getSpeed" -> param.result = spoofed.speed
                    "getBearing" -> param.result = spoofed.bearing
                    "getAccuracy" -> param.result = spoofed.accuracy
                }
            }
        }
        XposedHelpers.findAndHookMethod(Location::class.java, "getLatitude", replaceIfReal)
        XposedHelpers.findAndHookMethod(Location::class.java, "getLongitude", replaceIfReal)
        XposedHelpers.findAndHookMethod(Location::class.java, "getAltitude", replaceIfReal)
        XposedHelpers.findAndHookMethod(Location::class.java, "getSpeed", replaceIfReal)
        XposedHelpers.findAndHookMethod(Location::class.java, "getBearing", replaceIfReal)
        XposedHelpers.findAndHookMethod(Location::class.java, "getAccuracy", replaceIfReal)
        XposedBridge.log("[$TAG] Location getter hooks installed (anti-leak)")
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

        // Hook requestLocationUpdates(String, long, float, LocationListener, Looper)
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

                    XposedBridge.log("[$TAG] requestLocationUpdates(String,Long,Float,Listener,Looper) intercepted")
                    param.result = null
                    scheduleLocationUpdates(lpparam, listener, minTime)
                }
            }
        )

        // Hook requestLocationUpdates(Criteria, long, float, LocationListener, Looper)
        runCatching {
            val criteriaClass = XposedHelpers.findClass(
                "android.location.Criteria", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                locationManagerClass,
                "requestLocationUpdates",
                criteriaClass,
                Long::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                android.location.LocationListener::class.java,
                android.os.Looper::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[3] as? android.location.LocationListener ?: return
                        val minTime = param.args[1] as? Long ?: 1000L

                        XposedBridge.log("[$TAG] requestLocationUpdates(Criteria,Long,Float,Listener,Looper) intercepted")
                        param.result = null
                        scheduleLocationUpdates(lpparam, listener, minTime)
                    }
                }
            )
        }.onFailure { XposedBridge.log("[$TAG] Criteria-based requestLocationUpdates hook skipped: ${it.message}") }
    }

    /**
     * Cover all LocationManager overloads not handled by hookLocationListeners:
     *
     *  - classic requestLocationUpdates(String, long, float, LocationListener) — no Looper
     *  - API 30+ getCurrentLocation(...)
     *  - API 31+ requestLocationUpdates(LocationRequest, ...)
     *  - PendingIntent variants → block (our listener-based injection is sufficient)
     *  - flushLocations() → block to prevent real-location leaks
     */
    private fun hookNewLocationAPIs(lpparam: XC_LoadPackage.LoadPackageParam) {
        val lmClass = XposedHelpers.findClass(
            "android.location.LocationManager", lpparam.classLoader
        )

        // ── 1. Classic: requestLocationUpdates(String, long, float, LocationListener) ──
        //    This is the MOST COMMON overload used by apps.  It uses the caller's Looper.
        runCatching {
            XposedHelpers.findAndHookMethod(
                lmClass,
                "requestLocationUpdates",
                String::class.java,
                Long::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                android.location.LocationListener::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[3] as? android.location.LocationListener ?: return
                        val minTime = param.args[1] as? Long ?: 1000L
                        XposedBridge.log("[$TAG] requestLocationUpdates(classic, no looper) intercepted")
                        param.result = null
                        scheduleLocationUpdates(lpparam, listener, minTime)
                    }
                }
            )
        }.onFailure { XposedBridge.log("[$TAG] classic no-looper overload: ${it.message}") }

        // ── 2. API 30+: getCurrentLocation(String, CancellationSignal, Executor, Consumer) ──
        runCatching {
            val cslClass = XposedHelpers.findClass(
                "android.os.CancellationSignal", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                lmClass,
                "getCurrentLocation",
                String::class.java,
                cslClass,
                java.util.concurrent.Executor::class.java,
                java.util.function.Consumer::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        val consumer = param.args[3] as? java.util.function.Consumer<Location> ?: return
                        val executor = param.args[2] as? java.util.concurrent.Executor ?: return
                        XposedBridge.log("[$TAG] getCurrentLocation(provider) intercepted")
                        param.result = null
                        val spoofed = getCurrentSpoofedLocation(param.args[0] as? String ?: "gps")
                        executor.execute { consumer.accept(spoofed) }
                    }
                }
            )
        }.onFailure { XposedBridge.log("[$TAG] getCurrentLocation(provider): ${it.message}") }

        // ── 3. API 31+: getCurrentLocation(LocationRequest, ...) ──
        runCatching {
            val lrClass = XposedHelpers.findClass(
                "android.location.LocationRequest", lpparam.classLoader
            )
            val cslClass = XposedHelpers.findClass(
                "android.os.CancellationSignal", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                lmClass,
                "getCurrentLocation",
                lrClass,
                cslClass,
                java.util.concurrent.Executor::class.java,
                java.util.function.Consumer::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        val consumer = param.args[3] as? java.util.function.Consumer<Location> ?: return
                        val executor = param.args[2] as? java.util.concurrent.Executor ?: return
                        XposedBridge.log("[$TAG] getCurrentLocation(LocationRequest) intercepted")
                        param.result = null
                        val spoofed = getCurrentSpoofedLocation("gps")
                        executor.execute { consumer.accept(spoofed) }
                    }
                }
            )
        }.onFailure { XposedBridge.log("[$TAG] getCurrentLocation(LocationRequest): ${it.message}") }

        // ── 4. API 31+: requestLocationUpdates(LocationRequest, LocationListener, Looper) ──
        runCatching {
            val lrClass = XposedHelpers.findClass(
                "android.location.LocationRequest", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                lmClass,
                "requestLocationUpdates",
                lrClass,
                android.location.LocationListener::class.java,
                android.os.Looper::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[1] as? android.location.LocationListener ?: return
                        XposedBridge.log("[$TAG] requestLocationUpdates(LocationRequest,Listener,Looper) intercepted")
                        param.result = null
                        scheduleLocationUpdates(lpparam, listener, 1000L)
                    }
                }
            )
        }.onFailure { XposedBridge.log("[$TAG] LocationRequest+Listener+Looper: ${it.message}") }

        // ── 5. API 33+: requestLocationUpdates(LocationRequest, Executor, LocationListener) ──
        runCatching {
            val lrClass = XposedHelpers.findClass(
                "android.location.LocationRequest", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                lmClass,
                "requestLocationUpdates",
                lrClass,
                java.util.concurrent.Executor::class.java,
                android.location.LocationListener::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[2] as? android.location.LocationListener ?: return
                        XposedBridge.log("[$TAG] requestLocationUpdates(LocationRequest,Executor,Listener) intercepted")
                        param.result = null
                        scheduleLocationUpdates(lpparam, listener, 1000L)
                    }
                }
            )
        }.onFailure { XposedBridge.log("[$TAG] LocationRequest+Executor+Listener: ${it.message}") }

        // ── 6. PendingIntent variants → block ──
        // Apps using PendingIntent for location updates typically run a Service that
        // processes the intent.  We can't easily inject into that pipeline, so we
        // block it.  Our listener-based hooks cover the normal usage path.
        val blockPending = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                XposedBridge.log("[$TAG] requestLocationUpdates(PendingIntent) blocked")
                param.result = null
            }
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                lmClass, "requestLocationUpdates",
                String::class.java,
                Long::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                android.app.PendingIntent::class.java,
                blockPending
            )
        }.onFailure { /* best effort */ }
        runCatching {
            XposedHelpers.findAndHookMethod(
                lmClass, "requestLocationUpdates",
                Long::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                android.location.Criteria::class.java,
                android.app.PendingIntent::class.java,
                blockPending
            )
        }.onFailure { /* best effort */ }
        runCatching {
            val lrClass = XposedHelpers.findClass(
                "android.location.LocationRequest", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                lmClass, "requestLocationUpdates",
                lrClass,
                android.app.PendingIntent::class.java,
                blockPending
            )
        }.onFailure { /* best effort */ }

        // ── 7. flushLocations() → block ──
        // Forces delivery of cached system fixes — would leak real (stationary) data.
        runCatching {
            XposedHelpers.findAndHookMethod(
                lmClass, "flushLocations",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null
                    }
                }
            )
        }.onFailure { /* best effort */ }

        XposedBridge.log("[$TAG] New-style location API hooks installed")
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
        val effectiveInterval = if (intervalMs <= 0) 1000L else intervalMs
        val thread = Thread({
            try {
                while (true) {
                    Thread.sleep(effectiveInterval)
                    val spoofed = getCurrentSpoofedLocation("gps")
                    val lat = spoofed.latitude
                    val lng = spoofed.longitude

                    // Post to main thread for the listener callback
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            listener.onLocationChanged(spoofed)
                        } catch (e: Throwable) {
                            XposedBridge.log("[$TAG] Listener error: ${e.message}")
                        }
                    }

                    lastTrajectoryPoint?.let { point ->
                        sensorHookManager?.startSensorInjection()
                        sensorHookManager?.updateBearing(
                            point.bearingDeg,
                            lastBearingChangeRate
                        )
                    }

                    XposedBridge.log("[$TAG] Loc update: ${String.format("%.6f,%.6f spd=%.1f", lat, lng, spoofed.speed)}")
                }
            } catch (e: InterruptedException) {
                XposedBridge.log("[$TAG] Location thread interrupted")
            } catch (e: Throwable) {
                XposedBridge.log("[$TAG] Location thread error: ${e.message}")
                e.printStackTrace()
            }
        }, "Melos-LocThread")
        thread.isDaemon = true
        thread.start()

        XposedBridge.log("[$TAG] Location thread started (~${effectiveInterval}ms)")
    }

    /**
     * Get the current spoofed location based on trajectory generation.
     */
    private fun getCurrentSpoofedLocation(provider: String): Location {
        val now = System.currentTimeMillis()
        val elapsedSeconds = if (simulator.getStartTime() == 0L) {
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

        // Sync position to WiFi/Cell fingerprint hook
        wifiCellHookManager?.updatePosition(point.position.lat, point.position.lng)

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
            putBoolean("melos_spoofed", true)
        }
        location.extras = extras

        return location
    }
}
