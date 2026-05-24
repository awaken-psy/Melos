package com.melos.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Hooks SensorManager to inject synthetic sensor data.
 *
 * Uses an independent high-frequency timer (50Hz base) to inject each
 * sensor at its natural sampling rate, instead of batching all sensors
 * at the GPS update interval. This prevents the detection pattern where
 * all sensor events share identical timestamps.
 */
class SensorHookManager(
    private val lpparam: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam,
    private val sensorSimulator: SensorSimulator,
) {
    companion object {
        private const val TAG = "Melos-SensorHook"
        private const val BASE_INJECTION_INTERVAL_MS = 20L  // 50Hz base rate
    }

    private val activeListeners = mutableMapOf<SensorEventListener, SensorInfo>()

    // Per-sensor injection timing
    private val sensorLastInjectMs = mutableMapOf<Int, Long>()

    // Injection loop
    private var injectionHandler: Handler? = null
    private var injectionRunnable: Runnable? = null
    private var injectionRunning = false

    // Bearing state updated by GPS hook
    var currentBearingDeg = 0f
    var currentBearingChangeRate = 0f

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
                    if (activeListeners.isEmpty()) stopSensorInjection()
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
                        if (activeListeners.isEmpty()) stopSensorInjection()
                    }
                }
            }
        )
    }

    /**
     * Update bearing values from GPS hook.
     */
    fun updateBearing(bearingDeg: Float, bearingChangeRate: Float) {
        currentBearingDeg = bearingDeg
        currentBearingChangeRate = bearingChangeRate
    }

    /**
     * Start independent sensor injection loop at 50Hz base rate.
     * Each sensor fires at its own interval derived from samplingPeriodUs.
     */
    fun startSensorInjection() {
        if (injectionRunning) return
        injectionRunning = true

        injectionHandler = Handler(Looper.getMainLooper())
        injectionRunnable = object : Runnable {
            override fun run() {
                if (!injectionRunning) return
                injectAllSensors()
                injectionHandler?.postDelayed(this, BASE_INJECTION_INTERVAL_MS)
            }
        }
        injectionHandler?.post(injectionRunnable!!)
        XposedBridge.log("[$TAG] Sensor injection loop started (${BASE_INJECTION_INTERVAL_MS}ms base)")
    }

    private fun stopSensorInjection() {
        injectionRunning = false
        injectionRunnable?.let { injectionHandler?.removeCallbacks(it) }
        injectionHandler = null
        injectionRunnable = null
        sensorLastInjectMs.clear()
        XposedBridge.log("[$TAG] Sensor injection loop stopped")
    }

    /**
     * Inject sensor events at each sensor's natural sampling rate.
     * High-rate sensors (accelerometer/gyroscope ~50Hz) fire more frequently
     * than low-rate sensors (barometer ~5Hz, magnetometer ~10Hz).
     */
    private fun injectAllSensors() {
        val now = System.currentTimeMillis()

        injectStepDetectorEvents(now)

        activeListeners.forEach { (listener, info) ->
            val intervalMs = getSensorIntervalMs(info.sensor.type, info.samplingPeriodUs)
            val lastTime = sensorLastInjectMs[info.sensor.type] ?: (now - intervalMs)

            if (now - lastTime < intervalMs) return@forEach
            sensorLastInjectMs[info.sensor.type] = now

            val event = when (info.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> sensorSimulator.generateAccelerometerEvent(now, info.sensor)
                Sensor.TYPE_PRESSURE -> sensorSimulator.generateBarometerEvent(now, info.sensor)
                Sensor.TYPE_MAGNETIC_FIELD -> sensorSimulator.generateMagnetometerEvent(now, info.sensor, currentBearingDeg)
                Sensor.TYPE_GYROSCOPE -> sensorSimulator.generateGyroscopeEvent(now, info.sensor, currentBearingChangeRate)
                Sensor.TYPE_STEP_COUNTER -> sensorSimulator.generateStepCounterEvent(now, info.sensor)
                Sensor.TYPE_STEP_DETECTOR -> null
                else -> null
            }

            event?.let {
                try {
                    listener.onSensorChanged(it)
                    if (now % 5000 < intervalMs) {
                        listener.onAccuracyChanged(info.sensor, it.accuracy)
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("[$TAG] Error injecting sensor event: ${e.message}")
                }
            }
        }
    }

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

    private fun getSensorIntervalMs(type: Int, requestedPeriodUs: Int): Long {
        val requestedMs = requestedPeriodUs / 1000L
        if (requestedMs in 5..1000) return requestedMs
        return when (type) {
            Sensor.TYPE_ACCELEROMETER -> 20L
            Sensor.TYPE_GYROSCOPE -> 20L
            Sensor.TYPE_MAGNETIC_FIELD -> 100L
            Sensor.TYPE_PRESSURE -> 200L
            Sensor.TYPE_STEP_COUNTER -> 500L
            else -> 1000L
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
