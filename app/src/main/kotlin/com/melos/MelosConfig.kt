package com.melos

import android.content.Context
import com.melos.trajectory.LatLng
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object MelosConfig {
    private const val PREFS_NAME = "melos_config"
    private const val LOG_PATH = "/data/local/tmp/melos.log"
    private const val LOCATIONS_PATH = "/data/local/tmp/melos_locations.json"

    /** Must be set from Application/Activity for SharedPreferences access. */
    lateinit var appContext: Context

    data class Venue(
        val id: String,
        val name: String,
        val centerLat: Double,
        val centerLng: Double,
        val perimeterMeters: Double = 400.0,
    )

    val VENUES = listOf(
        Venue("jiading", "嘉定大操场", 31.29217, 121.21242),
        Venue("tongji", "同济四平操场", 31.2506, 121.5045),
    )

    fun venueById(id: String): Venue? = VENUES.find { it.id == id }

    // ── User-saved fixed-point locations ─────────────────────────

    data class FixedLocation(
        val id: String,
        val name: String,
        val lat: Double,
        val lng: Double,
    )

    fun loadSavedLocations(): List<FixedLocation> {
        val file = File(LOCATIONS_PATH)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                FixedLocation(
                    id = obj.optString("id", ""),
                    name = obj.optString("name", ""),
                    lat = obj.optDouble("lat", 0.0),
                    lng = obj.optDouble("lng", 0.0),
                ).takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun saveLocation(name: String, lat: Double, lng: Double): FixedLocation {
        val locations = loadSavedLocations().toMutableList()
        val newLoc = FixedLocation(
            id = UUID.randomUUID().toString().take(8),
            name = name,
            lat = lat,
            lng = lng,
        )
        locations.add(newLoc)
        writeLocations(locations)
        return newLoc
    }

    fun deleteLocation(id: String) {
        val locations = loadSavedLocations().filter { it.id != id }
        writeLocations(locations)
    }

    private fun writeLocations(locations: List<FixedLocation>) {
        val arr = JSONArray()
        for (loc in locations) {
            arr.put(JSONObject().apply {
                put("id", loc.id)
                put("name", loc.name)
                put("lat", loc.lat)
                put("lng", loc.lng)
            })
        }
        writeViaRoot(LOCATIONS_PATH, arr.toString(2))
    }

    // ── Simulation config (SharedPreferences) ────────────────────

    data class SimConfig(
        val enabled: Boolean = false,
        val mode: String = "trajectory",   // "trajectory" | "fixed_point"
        val venueId: String = "jiading",
        val speedMps: Double = 2.5,
        val laps: Int = 5,
        val fixedLat: Double = 0.0,
        val fixedLng: Double = 0.0,
    )

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readConfig(): SimConfig {
        if (!::appContext.isInitialized) return SimConfig()
        val p = prefs()
        return SimConfig(
            enabled = p.getBoolean("enabled", false),
            mode = p.getString("mode", "trajectory") ?: "trajectory",
            venueId = p.getString("venue_id", "jiading") ?: "jiading",
            speedMps = p.getFloat("speed_mps", 2.5f).toDouble(),
            laps = p.getInt("laps", 5),
            fixedLat = p.getFloat("fixed_lat", 0.0f).toDouble(),
            fixedLng = p.getFloat("fixed_lng", 0.0f).toDouble(),
        )
    }

    fun writeConfig(config: SimConfig) {
        prefs().edit().apply {
            putBoolean("enabled", config.enabled)
            putString("mode", config.mode)
            putString("venue_id", config.venueId)
            putFloat("speed_mps", config.speedMps.toFloat())
            putInt("laps", config.laps)
            putFloat("fixed_lat", config.fixedLat.toFloat())
            putFloat("fixed_lng", config.fixedLng.toFloat())
        }.apply()
    }

    // ── Logging ──────────────────────────────────────────────────

    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String,
    ) {
        val isError: Boolean get() = level == "E" || level == "W"
    }

    fun appendLog(level: String, tag: String, message: String) {
        try {
            val line = "${System.currentTimeMillis()}|$level|$tag|$message\n"
            File(LOG_PATH).apply {
                appendText(line)
                // Keep last 500KB
                if (length() > 500_000) {
                    val lines = readLines().takeLast(3000)
                    writeViaRoot(LOG_PATH, lines.joinToString("\n", postfix = "\n"))
                }
            }
        } catch (_: Exception) {}
    }

    fun readLogs(): List<LogEntry> {
        val file = File(LOG_PATH)
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            val parts = line.split("|", limit = 4)
            if (parts.size >= 4) {
                LogEntry(
                    timestamp = parts[0].toLongOrNull() ?: 0L,
                    level = parts[1],
                    tag = parts[2],
                    message = parts[3],
                )
            } else null
        }
    }

    fun clearLogs() {
        File(LOG_PATH).delete()
    }

    // ── Root file I/O (for /data/local/tmp/ writes) ─────────────

    private fun writeViaRoot(path: String, content: String) {
        try {
            File(path).writeText(content)
            return
        } catch (_: Exception) {}
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "tee '$path'"))
            proc.outputStream.write(content.toByteArray(Charsets.UTF_8))
            proc.outputStream.flush()
            proc.outputStream.close()
            proc.waitFor()
        } catch (_: Exception) {}
    }
}
