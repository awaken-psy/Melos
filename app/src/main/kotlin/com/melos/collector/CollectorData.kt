package com.melos.collector

import org.json.JSONArray
import org.json.JSONObject

sealed class CaptureEntry {
    abstract val id: Int
    abstract fun toJson(): JSONObject
    abstract fun toMapPoints(): JSONArray
}

data class PointEntry(
    override val id: Int,
    val data: CaptureData
) : CaptureEntry() {
    override fun toJson() = JSONObject().apply {
        put("type", "point"); put("id", id); put("capture", data.toJson())
    }
    override fun toMapPoints() = JSONArray().apply {
        put(JSONObject().apply {
            put("lat", data.latitude); put("lng", data.longitude)
            put("acc", data.accuracy.toDouble()); put("wifi", data.wifi.size); put("cell", data.cell.size)
        })
    }
}

data class ContinuousEntry(
    override val id: Int,
    val startTime: Long, val endTime: Long,
    val samples: List<CaptureData>
) : CaptureEntry() {
    override fun toJson() = JSONObject().apply {
        put("type", "continuous"); put("id", id)
        put("startTime", startTime); put("endTime", endTime)
        put("durationSec", (endTime - startTime) / 1000.0)
        put("sampleCount", samples.size)
        put("samples", JSONArray().apply { for (s in samples) put(s.toJson()) })
    }
    override fun toMapPoints() = JSONArray().apply {
        for (s in samples) {
            put(JSONObject().apply {
                put("lat", s.latitude); put("lng", s.longitude)
                put("acc", s.accuracy.toDouble()); put("wifi", s.wifi.size); put("cell", s.cell.size)
            })
        }
    }
}

data class CaptureData(
    val id: Int, val timestamp: Long,
    val latitude: Double, val longitude: Double, val altitude: Double,
    val accuracy: Float, val speed: Float,
    val wifi: List<WifiAp>, val cell: List<CellData>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("timestamp", timestamp)
        put("gps", JSONObject().apply {
            put("latitude", latitude); put("longitude", longitude)
            put("altitude", altitude); put("accuracy", accuracy.toDouble()); put("speed", speed.toDouble())
        })
        put("wifi", JSONArray().apply { for (ap in wifi) put(ap.toJson()) })
        put("cell", JSONArray().apply { for (c in cell) put(c.toJson()) })
    }
}

data class WifiAp(val bssid: String, val ssid: String, val rssi: Int, val frequency: Int, val capabilities: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("bssid", bssid); put("ssid", ssid); put("rssi", rssi)
        put("frequency", frequency); put("capabilities", capabilities)
    }
}

data class CellData(
    val type: String, val mcc: Int, val mnc: Int,
    val tac: Int? = null, val lac: Int? = null, val pci: Int? = null,
    val psc: Int? = null, val ci: Int? = null, val cid: Int? = null, val nci: Long? = null,
    val dbm: Int, val asu: Int, val level: Int, val registered: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type); put("mcc", mcc); put("mnc", mnc)
        if (tac != null) put("tac", tac); if (lac != null) put("lac", lac)
        if (pci != null) put("pci", pci); if (psc != null) put("psc", psc)
        if (ci != null) put("ci", ci); if (cid != null) put("cid", cid)
        if (nci != null) put("nci", nci)
        put("dbm", dbm); put("asu", asu); put("level", level); put("registered", registered)
    }
}
