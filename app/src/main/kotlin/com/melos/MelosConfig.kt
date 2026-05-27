package com.melos

import com.melos.trajectory.LatLng
import org.json.JSONObject
import java.io.File

object MelosConfig {
    private const val CONFIG_PATH = "/data/local/tmp/melos_config.json"
    private const val LOG_PATH = "/data/local/tmp/melos.log"

    data class Venue(
        val id: String,
        val name: String,
        val centerLat: Double,
        val centerLng: Double,
    )

    val VENUES = listOf(
        Venue("jiading", "嘉定大操场", 31.29217, 121.21242),
        Venue("tongji", "同济四平操场", 31.2506, 121.5045),
    )

    fun venueById(id: String): Venue? = VENUES.find { it.id == id }

    // ── Simulation config ────────────────────────────────────────

    data class SimConfig(
        val enabled: Boolean = false,
        val venueId: String = "jiading",
        val speedMps: Double = 2.5,
        val laps: Int = 5,
    )

    fun readConfig(): SimConfig {
        val file = File(CONFIG_PATH)
        if (!file.exists()) return SimConfig()
        return try {
            val json = JSONObject(file.readText())
            SimConfig(
                enabled = json.optBoolean("enabled", false),
                venueId = json.optString("venue_id", "jiading"),
                speedMps = json.optDouble("speed_mps", 2.5),
                laps = json.optInt("laps", 5),
            )
        } catch (_: Exception) { SimConfig() }
    }

    fun writeConfig(config: SimConfig) {
        val json = JSONObject().apply {
            put("enabled", config.enabled)
            put("venue_id", config.venueId)
            put("speed_mps", config.speedMps)
            put("laps", config.laps)
        }
        File(CONFIG_PATH).writeText(json.toString(2))
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
                    writeText(lines.joinToString("\n", postfix = "\n"))
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
}
