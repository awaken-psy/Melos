package com.melos.fingerprint

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.PI

class FingerprintDatabase {

    private val samples = mutableListOf<FingerprintSample>()

    val isLoaded: Boolean get() = samples.isNotEmpty()
    val sampleCount: Int get() = samples.size

    fun loadFromJson(jsonString: String): Int {
        samples.clear()
        val root = JSONObject(jsonString)
        val entries = root.optJSONArray("entries") ?: return 0

        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val samplesArr = entry.optJSONArray("samples") ?: continue

            for (j in 0 until samplesArr.length()) {
                val obj = samplesArr.getJSONObject(j)
                val gps = obj.optJSONObject("gps") ?: continue

                val accuracy = gps.optDouble("accuracy", 999.0)
                if (accuracy > 10.0) continue

                val lat = gps.optDouble("latitude", 0.0)
                val lng = gps.optDouble("longitude", 0.0)
                if (lat == 0.0 || lng == 0.0) continue

                val wifi = parseWifiArray(obj.optJSONArray("wifi") ?: JSONArray())
                val cell = parseCellArray(obj.optJSONArray("cell") ?: JSONArray())

                samples.add(FingerprintSample(lat, lng, wifi, cell))
            }
        }
        return samples.size
    }

    fun loadFromFile(path: String): Int {
        val file = File(path)
        if (!file.exists()) return 0
        return loadFromJson(file.readText())
    }

    /**
     * Query WiFi/cell data for a given GPS position.
     * Finds K nearest samples and merges their data with realistic noise.
     * Returns empty result if nearest sample is beyond [maxDistMeters].
     */
    fun query(
        lat: Double,
        lng: Double,
        maxDistMeters: Double = 100.0,
        k: Int = 3
    ): FingerprintResult {
        if (samples.isEmpty()) return FingerprintResult(emptyList(), emptyList())

        val cosLat = cos(lat * PI / 180.0)
        val scored = samples.map { sample ->
            val dLat = (sample.lat - lat) * 111000.0
            val dLng = (sample.lng - lng) * 111000.0 * cosLat
            sqrt(dLat * dLat + dLng * dLng) to sample
        }.sortedBy { it.first }

        if (scored.first().first > maxDistMeters) {
            return FingerprintResult(emptyList(), emptyList())
        }

        val nearest = scored.take(k)

        // Merge WiFi APs from nearest samples, keep strongest RSSI per BSSID
        val wifiApMap = linkedMapOf<String, WifiAp>()
        for ((_, sample) in nearest) {
            for (ap in sample.wifi) {
                val existing = wifiApMap[ap.bssid]
                if (existing == null || ap.rssi > existing.rssi) {
                    val noise = (Math.random() * 6 - 3).toInt()
                    wifiApMap[ap.bssid] = ap.copy(rssi = ap.rssi + noise)
                }
            }
        }

        // Return 6-12 APs sorted by RSSI (strongest first)
        val allAps = wifiApMap.values.sortedByDescending { it.rssi }
        val count = (6 + (Math.random() * 7).toInt()).coerceIn(6, 12).coerceAtMost(allAps.size)
        val wifi = allAps.take(count).map { ap ->
            ap.copy(rssi = ap.rssi + (Math.random() * 4 - 2).toInt())
        }

        // Merge cell towers (dedup by CID)
        val cellMap = linkedMapOf<Int, CellTower>()
        for ((_, sample) in nearest) {
            for (cell in sample.cell) {
                if (cell.cid !in cellMap) {
                    val noise = (Math.random() * 4 - 2).toInt()
                    cellMap[cell.cid] = cell.copy(dbm = cell.dbm + noise)
                }
            }
        }

        return FingerprintResult(wifi, cellMap.values.toList())
    }

    private fun parseWifiArray(arr: JSONArray): List<WifiAp> {
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.getJSONObject(i)
            WifiAp(
                bssid = obj.getString("bssid"),
                ssid = obj.optString("ssid", ""),
                rssi = obj.getInt("rssi"),
                frequency = obj.getInt("frequency"),
                capabilities = obj.optString("capabilities", "[ESS]")
            )
        }
    }

    private fun parseCellArray(arr: JSONArray): List<CellTower> {
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.getJSONObject(i)
            CellTower(
                type = obj.getString("type"),
                mcc = obj.getInt("mcc"),
                mnc = obj.getInt("mnc"),
                lac = obj.optInt("lac", 0),
                cid = obj.optInt("cid", 0),
                psc = obj.optInt("psc", 0),
                dbm = obj.getInt("dbm"),
                asu = obj.optInt("asu", 0),
                level = obj.optInt("level", 0),
                registered = obj.optBoolean("registered", false)
            )
        }
    }
}
