package com.melos.fingerprint

import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class WifiCellHookManager(
    private val lpparam: XC_LoadPackage.LoadPackageParam,
    private val database: FingerprintDatabase
) {
    companion object {
        private const val TAG = "Melos-WifiCell"
        private const val FINGERPRINT_PATH = "/data/local/tmp/melos_fingerprint.json"
    }

    @Volatile
    private var currentLat: Double = 0.0
    @Volatile
    private var currentLng: Double = 0.0
    private var lastFingerprint: FingerprintResult? = null

    fun installHooks() {
        if (!database.isLoaded) {
            val count = database.loadFromFile(FINGERPRINT_PATH)
            if (count == 0) {
                XposedBridge.log("[$TAG] No fingerprint data at $FINGERPRINT_PATH, skipping WiFi/Cell hooks")
                return
            }
        }
        XposedBridge.log("[$TAG] Fingerprint data loaded: ${database.sampleCount} samples")

        hookWifiManager()
        hookTelephonyManager()

        XposedBridge.log("[$TAG] WiFi/Cell hooks installed")
    }

    fun updatePosition(lat: Double, lng: Double) {
        currentLat = lat
        currentLng = lng
    }

    private fun getCurrentFingerprint(): FingerprintResult {
        val result = database.query(currentLat, currentLng)
        if (result.wifi.isNotEmpty() || result.cell.isNotEmpty()) {
            lastFingerprint = result
        }
        return lastFingerprint ?: FingerprintResult(emptyList(), emptyList())
    }

    // ── WifiManager hooks ──────────────────────────────────────────────

    private fun hookWifiManager() {
        val wmClass = XposedHelpers.findClass(
            "android.net.wifi.WifiManager", lpparam.classLoader
        )

        // Don't block startScan() — WeChat needs real scans during startup.
        // We only override the results in getScanResults() when we have a position.

        // Spoof scan results
        XposedHelpers.findAndHookMethod(
            wmClass, "getScanResults",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val fp = getCurrentFingerprint()
                    if (fp.wifi.isEmpty()) return
                    param.result = fp.wifi.mapNotNull { createScanResult(it) }
                    XposedBridge.log("[$TAG] getScanResults() → ${fp.wifi.size} APs")
                }
            }
        )
    }

    // ── TelephonyManager hooks ─────────────────────────────────────────

    private fun hookTelephonyManager() {
        val tmClass = XposedHelpers.findClass(
            "android.telephony.TelephonyManager", lpparam.classLoader
        )

        XposedHelpers.findAndHookMethod(
            tmClass, "getAllCellInfo",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val fp = getCurrentFingerprint()
                    if (fp.cell.isEmpty()) return
                    val spoofed = fp.cell.mapNotNull { createCellInfo(it) }
                    if (spoofed.isEmpty()) return  // Keep original if construction fails
                    param.result = spoofed
                    XposedBridge.log("[$TAG] getAllCellInfo() → ${spoofed.size} cells")
                }
            }
        )

        runCatching {
            XposedHelpers.findAndHookMethod(
                tmClass, "getCellLocation",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val fp = getCurrentFingerprint()
                        val cell = fp.cell.firstOrNull { it.registered }
                            ?: fp.cell.firstOrNull() ?: return

                        val gclClass = XposedHelpers.findClass(
                            "android.telephony.gsm.GsmCellLocation", lpparam.classLoader
                        )
                        param.result = gclClass.getConstructor(
                            Int::class.java, Int::class.java
                        ).newInstance(cell.lac, cell.cid)
                        XposedBridge.log("[$TAG] getCellLocation() spoofed")
                    }
                }
            )
        }.onFailure { XposedBridge.log("[$TAG] getCellLocation() hook skipped: ${it.message}") }

        runCatching {
            XposedHelpers.findAndHookMethod(
                tmClass, "getNeighboringCellInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val fp = getCurrentFingerprint()
                        if (fp.cell.size < 2) return
                        val neighbors = fp.cell.filter { !it.registered }.mapNotNull { cell ->
                            createNeighboringCellInfo(cell)
                        }
                        if (neighbors.isNotEmpty()) {
                            param.result = neighbors
                            XposedBridge.log("[$TAG] getNeighboringCellInfo() → ${neighbors.size}")
                        }
                    }
                }
            )
        }.onFailure { /* deprecated API, may not exist */ }
    }

    // ── Android object construction ────────────────────────────────────

    private fun createScanResult(ap: WifiAp): Any? {
        return runCatching {
            val clazz = XposedHelpers.findClass(
                "android.net.wifi.ScanResult", lpparam.classLoader
            )
            val sr = clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            XposedHelpers.setObjectField(sr, "SSID", ap.ssid)
            XposedHelpers.setObjectField(sr, "BSSID", ap.bssid)
            XposedHelpers.setIntField(sr, "level", ap.rssi)
            XposedHelpers.setIntField(sr, "frequency", ap.frequency)
            XposedHelpers.setObjectField(sr, "capabilities", ap.capabilities)
            XposedHelpers.setLongField(sr, "timestamp", SystemClock.elapsedRealtime() * 1000)
            sr
        }.getOrElse { e ->
            XposedBridge.log("[$TAG] ScanResult creation failed: ${e.message}")
            null
        }
    }

    private fun createCellInfo(cell: CellTower): Any? {
        return runCatching {
            val suffix = cell.type.lowercase().replaceFirstChar { it.uppercase() }
            val infoClazz = XposedHelpers.findClass(
                "android.telephony.CellInfo$suffix", lpparam.classLoader
            )
            val idClazz = XposedHelpers.findClass(
                "android.telephony.CellIdentity$suffix", lpparam.classLoader
            )
            val sigClazz = XposedHelpers.findClass(
                "android.telephony.CellSignalStrength$suffix", lpparam.classLoader
            )

            val cellInfo = allocateInstance(infoClazz)
            val cellIdentity = allocateInstance(idClazz)
            val cellSignal = allocateInstance(sigClazz)

            // CellInfo fields
            unsafePutBoolean(cellInfo, "mRegistered", cell.registered)
            unsafePutLong(cellInfo, "mTimeStamp", SystemClock.elapsedRealtimeNanos())

            // CellIdentity fields (in parent CellIdentity — final, need Unsafe)
            unsafePutInt(cellIdentity, "mMcc", cell.mcc)
            unsafePutInt(cellIdentity, "mMnc", cell.mnc)

            when (suffix) {
                "Wcdma" -> {
                    unsafePutInt(cellIdentity, "mLac", cell.lac)
                    unsafePutInt(cellIdentity, "mCid", cell.cid)
                    unsafePutInt(cellIdentity, "mPsc", cell.psc)
                    unsafePutInt(cellIdentity, "mUarfcn", 0)
                    XposedHelpers.setObjectField(cellInfo, "mCellIdentityWcdma", cellIdentity)
                    unsafePutInt(cellSignal, "mDbm", cell.dbm)
                    unsafePutInt(cellSignal, "mAsuLevel", cell.asu)
                    unsafePutInt(cellSignal, "mLevel", cell.level)
                    XposedHelpers.setObjectField(cellInfo, "mCellSignalStrengthWcdma", cellSignal)
                }
                "Lte" -> {
                    unsafePutInt(cellIdentity, "mCi", cell.cid)
                    unsafePutInt(cellIdentity, "mPci", cell.psc)
                    unsafePutInt(cellIdentity, "mTac", cell.lac)
                    unsafePutInt(cellIdentity, "mEarfcn", 0)
                    XposedHelpers.setObjectField(cellInfo, "mCellIdentityLte", cellIdentity)
                    unsafePutInt(cellSignal, "mRsrp", cell.dbm)
                    unsafePutInt(cellSignal, "mAsuLevel", cell.asu)
                    unsafePutInt(cellSignal, "mLevel", cell.level)
                    XposedHelpers.setObjectField(cellInfo, "mCellSignalStrengthLte", cellSignal)
                }
            }
            cellInfo
        }.getOrElse { e ->
            XposedBridge.log("[$TAG] CellInfo creation failed: ${e.message}")
            null
        }
    }

    // Use Unsafe to bypass final field restrictions
    @Suppress("BanJDBC")
    private fun allocateInstance(clazz: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(theUnsafe, clazz)
    }

    private fun unsafePutInt(obj: Any, fieldName: String, value: Int) {
        val field = findField(obj.javaClass, fieldName) ?: return
        val (unsafe, offset) = getUnsafeOffset(field)
        unsafe.javaClass.getMethod("putInt", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(unsafe, obj, offset, value)
    }

    private fun unsafePutBoolean(obj: Any, fieldName: String, value: Boolean) {
        val field = findField(obj.javaClass, fieldName) ?: return
        val (unsafe, offset) = getUnsafeOffset(field)
        unsafe.javaClass.getMethod("putBoolean", Any::class.java, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .invoke(unsafe, obj, offset, value)
    }

    private fun unsafePutLong(obj: Any, fieldName: String, value: Long) {
        val field = findField(obj.javaClass, fieldName) ?: return
        val (unsafe, offset) = getUnsafeOffset(field)
        unsafe.javaClass.getMethod("putLong", Any::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .invoke(unsafe, obj, offset, value)
    }

    private fun findField(clazz: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = clazz
        while (c != null) {
            try { return c.getDeclaredField(name) } catch (_: NoSuchFieldException) { c = c.superclass }
        }
        return null
    }

    @Suppress("BanJDBC")
    private fun getUnsafeOffset(field: java.lang.reflect.Field): Pair<Any, Long> {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)!!
        val offset = unsafeClass.getMethod("objectFieldOffset", java.lang.reflect.Field::class.java)
            .invoke(theUnsafe, field) as Long
        return theUnsafe to offset
    }

    private fun createNeighboringCellInfo(cell: CellTower): Any? {
        return runCatching {
            val clazz = XposedHelpers.findClass(
                "android.telephony.NeighboringCellInfo", lpparam.classLoader
            )
            clazz.getConstructor(Int::class.java, Int::class.java, Int::class.java)
                .newInstance(cell.dbm, cell.cid, cell.lac)
        }.getOrNull()
    }
}
