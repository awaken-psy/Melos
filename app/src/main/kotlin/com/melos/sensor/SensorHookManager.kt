package com.melos.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Hooks SensorManager to inject synthetic sensor data.
 *
 * Intercepts sensor registration and delivery to ensure our synthetic
 * data reaches the target application.
 */
class SensorHookManager(
    private val lpparam: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam,
    private val sensorSimulator: SensorSimulator,
) {
    companion object {
        private const val TAG = "Melos-SensorHook"
    }

    // Track active sensor listeners for targeted injection
    private val activeListeners = mutableMapOf<SensorEventListener, SensorInfo>()

    fun installHooks() {
        hookRegisterListener()
        hookUnregisterListener()
        XposedBridge.log("[$TAG] Sensor hooks installed")
    }

    /**
     * Hook registerListener to intercept and track sensor subscriptions.
     */
    private fun hookRegisterListener() {
        val sensorManagerClass = XposedHelpers.findClass(
            "android.hardware.SensorManager",
            lpparam.classLoader
        )

        // Hook registerListener(SensorEventListener, Sensor, int, int)
        XposedHelpers.findAndHookMethod(
            sensorManagerClass,
            "registerListener",
            SensorEventListener::class.java,
            Sensor::class.java,
            Int::class.javaPrimitiveType,  // samplingPeriodUs
            Int::class.javaPrimitiveType,  // maxReportLatencyUs
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val listener = param.args[0] as? SensorEventListener ?: return
                    val sensor = param.args[1] as? Sensor ?: return
                    val samplingPeriodUs = param.args[2] as? Int ?: return

                    val registered = param.result as? Boolean ?: return
                    if (!registered) return

                    val sensorType = sensor.type
                    if (isSpoofedSensor(sensorType)) {
                        activeListeners[listener] = SensorInfo(
                            sensor = sensor,
                            samplingPeriodUs = samplingPeriodUs,
                            registrationTime = System.currentTimeMillis()
                        )

                        XposedBridge.log("[$TAG] Tracked listener for sensor: ${getSensorName(sensorType)}")

                        // Optionally disable the real sensor to prevent conflicts
                        // This depends on the detection system's behavior
                        // param.result = false  // Uncomment to block real sensor
                    }
                }
            }
        )

        // Hook registerListener(SensorEventListener, Sensor, int)
        XposedHelpers.findAndHookMethod(
            sensorManagerClass,
            "registerListener",
            SensorEventListener::class.java,
            Sensor::class.java,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val listener = param.args[0] as? SensorEventListener ?: return
                    val sensor = param.args[1] as? Sensor ?: return
                    val samplingPeriodUs = param.args[2] as? Int ?: return

                    val registered = param.result as? Boolean ?: return
                    if (!registered) return

                    val sensorType = sensor.type
                    if (isSpoofedSensor(sensorType)) {
                        activeListeners[listener] = SensorInfo(
                            sensor = sensor,
                            samplingPeriodUs = samplingPeriodUs,
                            registrationTime = System.currentTimeMillis()
                        )

                        XposedBridge.log("[$TAG] Tracked listener (2-param) for: ${getSensorName(sensorType)}")
                    }
                }
            }
        )
    }

    /**
     * Hook unregisterListener to clean up our tracking.
     */
    private fun hookUnregisterListener() {
        val sensorManagerClass = XposedHelpers.findClass(
            "android.hardware.SensorManager",
            lpparam.classLoader
        )

        XposedHelpers.findAndHookMethod(
            sensorManagerClass,
            "unregisterListener",
            SensorEventListener::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val listener = param.args[0] as? SensorEventListener ?: return
                    activeListeners.remove(listener)
                    XposedBridge.log("[$TAG] Unregistered sensor listener")
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            sensorManagerClass,
            "unregisterListener",
            SensorEventListener::class.java,
            Sensor::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val listener = param.args[0] as? SensorEventListener ?: return
                    val sensor = param.args[1] as? Sensor ?: return

                    if (activeListeners[listener]?.sensor == sensor) {
                        activeListeners.remove(listener)
                        XposedBridge.log("[$TAG] Unregistered specific sensor listener")
                    }
                }
            }
        )
    }

    /**
     * Inject synthetic sensor events to all tracked listeners.
     * Should be called periodically (e.g., every 100ms) from a timer or within GPS hooks.
     *
     * @param bearingDeg Current heading (for magnetometer/gyroscope)
     * @param bearingChangeRate Rate of heading change (deg/s, for gyroscope)
     */
    fun injectSensorEvents(bearingDeg: Float = 0f, bearingChangeRate: Float = 0f) {
        val now = System.currentTimeMillis()

        activeListeners.forEach { (listener, info) ->
            try {
                val event = when (info.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> sensorSimulator.generateAccelerometerEvent(
                        now, info.sensor
                    )
                    Sensor.TYPE_PRESSURE -> sensorSimulator.generateBarometerEvent(
                        now, info.sensor
                    )
                    Sensor.TYPE_MAGNETIC_FIELD -> sensorSimulator.generateMagnetometerEvent(
                        now, info.sensor, bearingDeg
                    )
                    Sensor.TYPE_GYROSCOPE -> sensorSimulator.generateGyroscopeEvent(
                        now, info.sensor, bearingChangeRate
                    )
                    else -> null
                }

                event?.let {
                    listener.onSensorChanged(it)
                    // Also call onAccuracyChanged occasionally
                    if (now % 5000 < info.samplingPeriodUs / 1000) {
                        listener.onAccuracyChanged(info.sensor, it.accuracy)
                    }
                }
            } catch (e: Throwable) {
                XposedBridge.log("[$TAG] Error injecting sensor event: ${e.message}")
            }
        }
    }

    /**
     * Check if we should spoof this sensor type.
     */
    private fun isSpoofedSensor(type: Int): Boolean {
        return type in listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_STEP_COUNTER,
            Sensor.TYPE_STEP_DETECTOR
        )
    }

    private fun getSensorName(type: Int): String {
        return when (type) {
            Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
            Sensor.TYPE_PRESSURE -> "Barometer"
            Sensor.TYPE_MAGNETIC_FIELD -> "Magnetometer"
            Sensor.TYPE_GYROSCOPE -> "Gyroscope"
            Sensor.TYPE_STEP_COUNTER -> "Step Counter"
            Sensor.TYPE_STEP_DETECTOR -> "Step Detector"
            else -> "Unknown($type)"
        }
    }

    /**
     * Get the count of actively tracked sensor listeners.
     */
    fun getActiveListenerCount(): Int = activeListeners.size

    /**
     * Data class to track sensor listener registration details.
     */
    data class SensorInfo(
        val sensor: Sensor,
        val samplingPeriodUs: Int,
        val registrationTime: Long,
    )
}
