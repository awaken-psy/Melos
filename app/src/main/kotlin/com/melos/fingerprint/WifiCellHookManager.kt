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

        // Block real WiFi scan
        XposedHelpers.findAndHookMethod(
            wmClass, "startScan",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            }
        )

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
                    param.result = fp.cell.mapNotNull { createCellInfo(it) }
                    XposedBridge.log("[$TAG] getAllCellInfo() → ${fp.cell.size} cells")
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
            val clazz = XposedHelpers.findClass(
                "android.telephony.CellInfo${cell.type}", lpparam.classLoader
            )
            val cellInfo = clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

            XposedHelpers.setBooleanField(cellInfo, "mRegistered", cell.registered)
            XposedHelpers.setLongField(cellInfo, "mTimeStamp", SystemClock.elapsedRealtimeNanos())

            when (cell.type) {
                "Wcdma" -> {
                    val id = constructCellIdentityWcdma(cell)
                    XposedHelpers.setObjectField(cellInfo, "mCellIdentityWcdma", id)
                    val sig = constructCellSignalStrengthWcdma(cell)
                    XposedHelpers.setObjectField(cellInfo, "mCellSignalStrengthWcdma", sig)
                }
                "Lte" -> {
                    val id = constructCellIdentityLte(cell)
                    XposedHelpers.setObjectField(cellInfo, "mCellIdentityLte", id)
                    val sig = constructCellSignalStrengthLte(cell)
                    XposedHelpers.setObjectField(cellInfo, "mCellSignalStrengthLte", sig)
                }
            }
            cellInfo
        }.getOrElse { e ->
            XposedBridge.log("[$TAG] CellInfo creation failed: ${e.message}")
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun constructCellIdentityWcdma(cell: CellTower): Any {
        val clazz = XposedHelpers.findClass(
            "android.telephony.CellIdentityWcdma", lpparam.classLoader
        )
        return clazz.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java,
            Int::class.java, Int::class.java, Int::class.java,
            Collection::class.java
        ).apply { isAccessible = true }.newInstance(
            cell.mcc, cell.mnc, cell.lac, cell.cid, cell.psc, 0,
            java.util.Collections.emptyList<Any>()
        )
    }

    private fun constructCellSignalStrengthWcdma(cell: CellTower): Any {
        val clazz = XposedHelpers.findClass(
            "android.telephony.CellSignalStrengthWcdma", lpparam.classLoader
        )
        return clazz.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java
        ).apply { isAccessible = true }.newInstance(
            cell.dbm, cell.asu, cell.level
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun constructCellIdentityLte(cell: CellTower): Any {
        val clazz = XposedHelpers.findClass(
            "android.telephony.CellIdentityLte", lpparam.classLoader
        )
        return clazz.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java,
            Int::class.java, Int::class.java, Int::class.java,
            Int::class.java, String::class.java, Collection::class.java
        ).apply { isAccessible = true }.newInstance(
            cell.mcc, cell.mnc, cell.cid, cell.psc, cell.lac, 0, 0,
            "", java.util.Collections.emptyList<Any>()
        )
    }

    private fun constructCellSignalStrengthLte(cell: CellTower): Any {
        val clazz = XposedHelpers.findClass(
            "android.telephony.CellSignalStrengthLte", lpparam.classLoader
        )
        return clazz.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            Int::class.java, Int::class.java, Int::class.java
        ).apply { isAccessible = true }.newInstance(
            cell.dbm, cell.asu, cell.level, 0, 0, 0, 0
        )
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
