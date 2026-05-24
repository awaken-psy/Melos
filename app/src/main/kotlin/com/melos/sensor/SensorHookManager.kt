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
 * data reaches the target application. Real sensor events are blocked
 * to prevent sensor fusion conflicts.
 */
class SensorHookManager(
    private val lpparam: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam,
    private val sensorSimulator: SensorSimulator,
) {
    companion object {
        private const val TAG = "Melos-SensorHook"
    }

    private val activeListeners = mutableMapOf<SensorEventListener, SensorInfo>()

    fun installHooks() {
        hookRegisterListener()
        hookUnregisterListener()
        XposedBridge.log("[$TAG] Sensor hooks installed")
    }

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
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val listener = param.args[0] as? SensorEventListener ?: return
                    val sensor = param.args[1] as? Sensor ?: return
                    val samplingPeriodUs = param.args[2] as? Int ?: return

                    if (!isSpoofedSensor(sensor.type)) return

                    param.result = true  // Block real registration, report success

                    activeListeners[listener] = SensorInfo(
                        sensor = sensor,
                        samplingPeriodUs = samplingPeriodUs,
                        registrationTime = System.currentTimeMillis()
                    )

                    XposedBridge.log("[$TAG] Spoofed sensor (real blocked): ${getSensorName(sensor.type)}")
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
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val listener = param.args[0] as? SensorEventListener ?: return
                    val sensor = param.args[1] as? Sensor ?: return
                    val samplingPeriodUs = param.args[2] as? Int ?: return

                    if (!isSpoofedSensor(sensor.type)) return

                    param.result = true

                    activeListeners[listener] = SensorInfo(
                        sensor = sensor,
                        samplingPeriodUs = samplingPeriodUs,
                        registrationTime = System.currentTimeMillis()
                    )

                    XposedBridge.log("[$TAG] Spoofed sensor (real blocked): ${getSensorName(sensor.type)}")
                }
            }
        )
    }

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
     * Step detector events are retroactively fired for missed steps.
     */
    fun injectSensorEvents(bearingDeg: Float = 0f, bearingChangeRate: Float = 0f) {
        val now = System.currentTimeMillis()

        injectStepDetectorEvents(now)

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
                    Sensor.TYPE_STEP_COUNTER -> sensorSimulator.generateStepCounterEvent(
                        now, info.sensor
                    )
                    Sensor.TYPE_STEP_DETECTOR -> null  // Handled by injectStepDetectorEvents
                    else -> null
                }

                event?.let {
                    listener.onSensorChanged(it)
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
     * Retroactively fire step detector events for steps that occurred
     * since the last injection. Step intervals include natural jitter.
     */
    private fun injectStepDetectorEvents(now: Long) {
        val pendingTimestamps = sensorSimulator.getPendingStepDetectorTimestamps(now)
        if (pendingTimestamps.isEmpty()) return

        val stepDetectorListeners = activeListeners.entries.filter {
            it.value.sensor.type == Sensor.TYPE_STEP_DETECTOR
        }

        stepDetectorListeners.forEach { (listener, info) ->
            pendingTimestamps.forEach { stepTime ->
                try {
                    val event = sensorSimulator.generateStepDetectorEvent(stepTime, info.sensor)
                    listener.onSensorChanged(event)
                } catch (e: Throwable) {
                    XposedBridge.log("[$TAG] Error injecting step detector event: ${e.message}")
                }
            }
        }
    }

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

    fun getActiveListenerCount(): Int = activeListeners.size

    data class SensorInfo(
        val sensor: Sensor,
        val samplingPeriodUs: Int,
        val registrationTime: Long,
    )
}
