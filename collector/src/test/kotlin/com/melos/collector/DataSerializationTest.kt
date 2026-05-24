package com.melos.collector

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DataSerializationTest {

    // ── WifiAp serialization ────────────────────────────────────────

    @Test
    fun `WifiAp toJson has all fields`() {
        val ap = WifiAp("AA:BB:CC:DD:EE:FF", "TestWiFi", -50, 2437, "[WPA2-PSK-CCMP]")
        val json = ap.toJson()

        assertEquals("AA:BB:CC:DD:EE:FF", json.getString("bssid"))
        assertEquals("TestWiFi", json.getString("ssid"))
        assertEquals(-50, json.getInt("rssi"))
        assertEquals(2437, json.getInt("frequency"))
        assertEquals("[WPA2-PSK-CCMP]", json.getString("capabilities"))
    }

    @Test
    fun `WifiAp toJson roundtrip`() {
        val ap = WifiAp("11:22:33:44:55:66", "MyNetwork", -70, 5180, "[WPA3]")
        val json = ap.toJson()
        assertEquals(ap.bssid, json.getString("bssid"))
        assertEquals(ap.rssi, json.getInt("rssi"))
    }

    // ── CellData serialization ──────────────────────────────────────

    @Test
    fun `CellData LTE toJson has all fields`() {
        val cell = CellData(
            type = "LTE", mcc = 460, mnc = 0,
            tac = 12345, pci = 200, ci = 300,
            dbm = -90, asu = 20, level = 3, registered = true
        )
        val json = cell.toJson()

        assertEquals("LTE", json.getString("type"))
        assertEquals(460, json.getInt("mcc"))
        assertEquals(0, json.getInt("mnc"))
        assertEquals(12345, json.getInt("tac"))
        assertEquals(200, json.getInt("pci"))
        assertEquals(300, json.getInt("ci"))
        assertEquals(-90, json.getInt("dbm"))
        assertEquals(20, json.getInt("asu"))
        assertEquals(3, json.getInt("level"))
        assertTrue(json.getBoolean("registered"))
    }

    @Test
    fun `CellData GSM toJson omits null optional fields`() {
        val cell = CellData(
            type = "GSM", mcc = 460, mnc = 1,
            lac = 1000, cid = 2000,
            dbm = -80, asu = 15, level = 2, registered = false
        )
        val json = cell.toJson()

        assertEquals("GSM", json.getString("type"))
        assertEquals(1000, json.getInt("lac"))
        assertEquals(2000, json.getInt("cid"))
        assertFalse(json.has("tac"))  // tac is null, should not appear
        assertFalse(json.has("pci"))
        assertFalse(json.has("nci"))
    }

    @Test
    fun `CellData WCDMA toJson has psc`() {
        val cell = CellData(
            type = "WCDMA", mcc = 460, mnc = 0,
            lac = 500, cid = 600, psc = 100,
            dbm = -75, asu = 25, level = 4, registered = true
        )
        val json = cell.toJson()

        assertTrue(json.has("psc"))
        assertEquals(100, json.getInt("psc"))
        assertTrue(json.has("lac"))
    }

    @Test
    fun `CellData with nci includes nci field`() {
        val cell = CellData(
            type = "NR", mcc = 460, mnc = 11,
            nci = 123456789L,
            dbm = -100, asu = 10, level = 1, registered = false
        )
        val json = cell.toJson()
        assertTrue(json.has("nci"))
        assertEquals(123456789L, json.getLong("nci"))
    }

    // ── CaptureData serialization ──────────────────────────────────

    @Test
    fun `CaptureData toJson has all sections`() {
        val capture = CaptureData(
            id = 1,
            timestamp = 1700000000000L,
            latitude = 31.2503,
            longitude = 121.5045,
            altitude = 10.0,
            accuracy = 5.0f,
            speed = 2.5f,
            wifi = listOf(
                WifiAp("AA:BB:CC:DD:EE:FF", "WiFi1", -50, 2437, "[WPA2]")
            ),
            cell = listOf(
                CellData("LTE", 460, 0, tac = 12345, pci = 200, ci = 300, dbm = -90, asu = 20, level = 3, registered = true)
            )
        )
        val json = capture.toJson()

        assertEquals(1, json.getInt("id"))
        assertEquals(1700000000000L, json.getLong("timestamp"))

        val gps = json.getJSONObject("gps")
        assertEquals(31.2503, gps.getDouble("latitude"), 0.000001)
        assertEquals(121.5045, gps.getDouble("longitude"), 0.000001)
        assertEquals(10.0, gps.getDouble("altitude"), 0.01)
        assertEquals(5.0, gps.getDouble("accuracy"), 0.01)
        assertEquals(2.5, gps.getDouble("speed"), 0.01)

        val wifi = json.getJSONArray("wifi")
        assertEquals(1, wifi.length())
        assertEquals("AA:BB:CC:DD:EE:FF", wifi.getJSONObject(0).getString("bssid"))

        val cell = json.getJSONArray("cell")
        assertEquals(1, cell.length())
        assertEquals("LTE", cell.getJSONObject(0).getString("type"))
    }

    @Test
    fun `CaptureData toJson with empty lists`() {
        val capture = CaptureData(
            id = 2,
            timestamp = 1700000001000L,
            latitude = 31.25,
            longitude = 121.50,
            altitude = 10.0,
            accuracy = 5.0f,
            speed = 0f,
            wifi = emptyList(),
            cell = emptyList()
        )
        val json = capture.toJson()

        assertEquals(0, json.getJSONArray("wifi").length())
        assertEquals(0, json.getJSONArray("cell").length())
    }

    @Test
    fun `CaptureData toJson with multiple wifi and cell`() {
        val capture = CaptureData(
            id = 3,
            timestamp = 1700000002000L,
            latitude = 31.2500,
            longitude = 121.5050,
            altitude = 12.0,
            accuracy = 3.5f,
            speed = 2.5f,
            wifi = listOf(
                WifiAp("AA:BB:CC:DD:EE:FF", "WiFi1", -50, 2437, "[WPA2]"),
                WifiAp("11:22:33:44:55:66", "WiFi2", -70, 5180, "[WPA3]"),
                WifiAp("AA:BB:CC:DD:EE:00", "WiFi3", -60, 2412, "[OPEN]")
            ),
            cell = listOf(
                CellData("LTE", 460, 0, tac = 12345, pci = 200, ci = 300, dbm = -90, asu = 20, level = 3, registered = true),
                CellData("GSM", 460, 0, lac = 1000, cid = 2000, dbm = -80, asu = 15, level = 2, registered = false)
            )
        )
        val json = capture.toJson()

        assertEquals(3, json.getJSONArray("wifi").length())
        assertEquals(2, json.getJSONArray("cell").length())
    }

    // ── Data class equality ─────────────────────────────────────────

    @Test
    fun `WifiAp data class equality`() {
        val ap1 = WifiAp("AA:BB:CC:DD:EE:FF", "WiFi", -50, 2437, "[WPA2]")
        val ap2 = WifiAp("AA:BB:CC:DD:EE:FF", "WiFi", -50, 2437, "[WPA2]")
        assertEquals(ap1, ap2)
        assertEquals(ap1.hashCode(), ap2.hashCode())
    }

    @Test
    fun `CellData data class equality`() {
        val c1 = CellData("LTE", 460, 0, tac = 123, pci = 200, ci = 300, dbm = -90, asu = 20, level = 3, registered = true)
        val c2 = CellData("LTE", 460, 0, tac = 123, pci = 200, ci = 300, dbm = -90, asu = 20, level = 3, registered = true)
        assertEquals(c1, c2)
    }

    @Test
    fun `CaptureData data class equality`() {
        val c1 = CaptureData(1, 1000L, 31.0, 121.0, 10.0, 5f, 2f, emptyList(), emptyList())
        val c2 = CaptureData(1, 1000L, 31.0, 121.0, 10.0, 5f, 2f, emptyList(), emptyList())
        assertEquals(c1, c2)
    }
}
