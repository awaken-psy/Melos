package com.melos

import android.content.Context
import android.location.Location
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Melos LSPosed Module Entry Point
 *
 * Hooks WeChat (com.tencent.mm) to inject synthetic GPS/step data
 * for academic research purposes (defeating course exercise tracker).
 *
 * Target: WeChat mini-program exercise tracker via LocationManager spoofing.
 */
class MelosHookEntry : IXposedHookLoadPackage {

    companion object {
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val TAG = "Melos"

        // Test location: Tongji University Siping Campus
        private const val TEST_LAT = 31.2503
        private const val TEST_LON = 121.5045
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != WECHAT_PACKAGE) {
            return
        }

        XposedBridge.log("[$TAG] WeChat detected, injecting GPS spoofing hooks...")

        try {
            hookLocationManager(lpparam)
            hookLocationListeners(lpparam)
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

                    val spoofed = createSpoofedLocation(provider)
                    param.result = spoofed

                    XposedBridge.log("[$TAG] Spoofed getLastKnownLocation($provider) -> $spoofed")
                }
            }
        )
    }

    /**
     * Hook LocationListener.onLocationChanged() callbacks.
     */
    private fun hookLocationListeners(lpparam: XC_LoadPackage.LoadPackageParam) {
        val locationManagerClass = XposedHelpers.findClass(
            "android.location.LocationManager",
            lpparam.classLoader
        )

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

                    // Immediately deliver a spoofed location
                    val spoofed = createSpoofedLocation("gps")
                    listener.onLocationChanged(spoofed)

                    XposedBridge.log("[$TAG] Injected location via onLocationChanged callback")
                }
            }
        )
    }

    /**
     * Hook FusedLocationProvider (Google Play Services) if present.
     */
    private fun hookFusedLocationProvider(lpparam: XC_LoadPackage.LoadPackageParam) {
        // WeChat may use FusedLocationProviderClient for better accuracy
        try {
            // Check if FusedLocationProvider is available
            XposedHelpers.findClass(
                "com.google.android.gms.location.LocationResult",
                lpparam.classLoader
            )

            XposedBridge.log("[$TAG] FusedLocationProvider detected, hooking...")

            XposedHelpers.findAndHookMethod(
                "com.google.android.gms.location.FusedLocationProviderClient",
                lpparam.classLoader,
                "getLastLocation",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // Task is async; would need ResultCallback hook
                        // For now, we rely on LocationListener hooks which are synchronous
                        @Suppress("UNUSED_VARIABLE")
                        val task = param.result
                    }
                }
            )
        } catch (e: Throwable) {
            // FusedLocationProvider not available in this context
            XposedBridge.log("[$TAG] FusedLocationProvider not available: ${e.message}")
        }
    }

    private fun createSpoofedLocation(provider: String): Location {
        val location = Location(provider).apply {
            latitude = TEST_LAT
            longitude = TEST_LON
            time = System.currentTimeMillis()
            accuracy = 10.0f
            altitude = 10.0
            bearing = 0f
            speed = 0f

            // Android 4.2+ fields
            if (android.os.Build.VERSION.SDK_INT >= 18) {
                //.elapsedRealtimeNanos = System.nanoTime() // Requires API 18
                try {
                    setElapsedRealtimeNanos(System.nanoTime())
                } catch (e: Throwable) {
                    // Ignore on older platforms
                }
            }
        }

        // Add extras for realism
        val extras = Bundle()
        extras.putInt("satellites", 12)
        location.extras = extras

        return location
    }
}

/**
 * Criteria class stub for LocationManager hooking (nullable param).
 */
private class Criteria
