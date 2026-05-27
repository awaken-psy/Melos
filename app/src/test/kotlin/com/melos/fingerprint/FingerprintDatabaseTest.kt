package com.melos.fingerprint

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FingerprintDatabaseTest {

    private lateinit var db: FingerprintDatabase

    private fun buildSampleJson(
        lat: Double, lng: Double, accuracy: Double,
        wifiCount: Int, cellCount: Int
    ): String {
        val wifiArr = (0 until wifiCount).joinToString(",") { i ->
            """{"bssid":"aa:bb:cc:dd:ee:f$i","ssid":"AP$i","rssi":${-50 - i * 3},"frequency":2437,"capabilities":"[WPA2-PSK-CCMP][ESS]"}"""
        }
        val cellArr = (0 until cellCount).joinToString(",") { i ->
            """{"type":"WCDMA","mcc":460,"mnc":1,"lac":43013,"cid":${10000 + i},"psc":${100 + i},"dbm":${-90 - i * 5},"asu":${20 - i},"level":2,"registered":${i == 0}}"""
        }
        return """{
            "venue":"test","device":"test",
            "entries":[{
                "type":"continuous","id":1,
                "startTime":0,"endTime":1000,"durationSec":10,"sampleCount":1,
                "samples":[{
                    "id":0,"timestamp":0,
                    "gps":{"latitude":$lat,"longitude":$lng,"altitude":20.0,"accuracy":$accuracy,"speed":2.5},
                    "wifi":[$wifiArr],
                    "cell":[$cellArr]
                }]
            }]
        }"""
    }

    @Before
    fun setup() {
        db = FingerprintDatabase()
    }

    @Test
    fun loadFromJson_parsesSamples() {
        val count = db.loadFromJson(buildSampleJson(31.0, 121.0, 5.0, 3, 2))
        assertEquals(1, count)
        assertTrue(db.isLoaded)
        assertEquals(1, db.sampleCount)
    }

    @Test
    fun loadFromJson_filtersPoorAccuracy() {
        val count = db.loadFromJson(buildSampleJson(31.0, 121.0, 50.0, 3, 2))
        assertEquals(0, count)
        assertTrue(!db.isLoaded)
    }

    @Test
    fun loadFromJson_skipsZeroLat() {
        val count = db.loadFromJson(buildSampleJson(0.0, 121.0, 5.0, 1, 1))
        assertEquals(0, count)
    }

    @Test
    fun loadFromJson_multipleEntries() {
        val json = """{
            "venue":"test","entries":[
                {"type":"continuous","id":1,"startTime":0,"endTime":1,"durationSec":1,"sampleCount":2,
                 "samples":[
                    {"id":0,"timestamp":0,"gps":{"latitude":31.0,"longitude":121.0,"altitude":20.0,"accuracy":5.0,"speed":2.5},"wifi":[],"cell":[]},
                    {"id":1,"timestamp":1,"gps":{"latitude":31.001,"longitude":121.001,"altitude":20.0,"accuracy":5.0,"speed":2.5},"wifi":[],"cell":[]}
                 ]},
                {"type":"continuous","id":2,"startTime":0,"endTime":1,"durationSec":1,"sampleCount":1,
                 "samples":[
                    {"id":0,"timestamp":0,"gps":{"latitude":31.002,"longitude":121.002,"altitude":20.0,"accuracy":5.0,"speed":2.5},"wifi":[],"cell":[]}
                 ]}
            ]
        }"""
        val count = db.loadFromJson(json)
        assertEquals(3, count)
    }

    @Test
    fun query_returnsWifiAndCell() {
        db.loadFromJson(buildSampleJson(31.0, 121.0, 5.0, 5, 3))
        val result = db.query(31.0, 121.0)
        assertTrue(result.wifi.isNotEmpty())
        assertTrue(result.cell.isNotEmpty())
        assertEquals(3, result.cell.size)
    }

    @Test
    fun query_nearbyReturnsData() {
        db.loadFromJson(buildSampleJson(31.0, 121.0, 5.0, 8, 2))
        // ~11m offset — should still match (default maxDist=100m)
        val result = db.query(31.0001, 121.0001)
        assertTrue(result.wifi.isNotEmpty())
    }

    @Test
    fun query_farAwayReturnsEmpty() {
        db.loadFromJson(buildSampleJson(31.0, 121.0, 5.0, 5, 2))
        val result = db.query(32.0, 122.0)
        assertTrue(result.wifi.isEmpty())
        assertTrue(result.cell.isEmpty())
    }

    @Test
    fun query_emptyDatabase() {
        val result = db.query(31.0, 121.0)
        assertTrue(result.wifi.isEmpty())
        assertTrue(result.cell.isEmpty())
    }

    @Test
    fun query_rssiHasNoise() {
        db.loadFromJson(buildSampleJson(31.0, 121.0, 5.0, 5, 1))
        val rssis = mutableSetOf<Int>()
        repeat(20) {
            val r = db.query(31.0, 121.0)
            r.wifi.forEach { rssis.add(it.rssi) }
        }
        // With ±3 dBm noise, we should see some variation
        assertTrue("RSSI should vary across queries", rssis.size > 1)
    }

    @Test
    fun query_cellDedup() {
        // Two samples at same location with same CID — should dedup
        val json = """{
            "venue":"test","entries":[{
                "type":"continuous","id":1,"startTime":0,"endTime":1,"durationSec":1,"sampleCount":2,
                "samples":[
                    {"id":0,"timestamp":0,"gps":{"latitude":31.0,"longitude":121.0,"altitude":20.0,"accuracy":5.0,"speed":2.5},
                     "wifi":[],"cell":[{"type":"WCDMA","mcc":460,"mnc":1,"lac":43013,"cid":99999,"psc":100,"dbm":-90,"asu":20,"level":2,"registered":true}]},
                    {"id":1,"timestamp":1,"gps":{"latitude":31.0001,"longitude":121.0001,"altitude":20.0,"accuracy":5.0,"speed":2.5},
                     "wifi":[],"cell":[{"type":"WCDMA","mcc":460,"mnc":1,"lac":43013,"cid":99999,"psc":100,"dbm":-85,"asu":22,"level":2,"registered":true}]}
                ]
            }]
        }"""
        db.loadFromJson(json)
        val result = db.query(31.0, 121.0)
        assertEquals(1, result.cell.size)
    }

    @Test
    fun query_wifiApCount_inRange() {
        db.loadFromJson(buildSampleJson(31.0, 121.0, 5.0, 15, 1))
        repeat(10) {
            val result = db.query(31.0, 121.0)
            assertTrue("wifi count ${result.wifi.size} in 6..12", result.wifi.size in 6..12)
        }
    }

    @Test
    fun loadFromJson_wifiFields() {
        db.loadFromJson(buildSampleJson(31.0, 121.0, 5.0, 1, 0))
        val result = db.query(31.0, 121.0)
        val ap = result.wifi.first()
        assertEquals("aa:bb:cc:dd:ee:f0", ap.bssid)
        assertEquals("AP0", ap.ssid)
        assertEquals(2437, ap.frequency)
        assertTrue(ap.rssi > -100)
    }

    @Test
    fun loadFromJson_cellFields() {
        db.loadFromJson(buildSampleJson(31.0, 121.0, 5.0, 1, 1))
        val result = db.query(31.0, 121.0)
        val cell = result.cell.first()
        assertEquals("WCDMA", cell.type)
        assertEquals(460, cell.mcc)
        assertEquals(1, cell.mnc)
        assertEquals(43013, cell.lac)
        assertEquals(10000, cell.cid)
        assertTrue(cell.registered)
    }

    @Test
    fun loadFromJson_mixedAccuracy() {
        val json = """{
            "venue":"test","entries":[{
                "type":"continuous","id":1,"startTime":0,"endTime":1,"durationSec":1,"sampleCount":2,
                "samples":[
                    {"id":0,"timestamp":0,"gps":{"latitude":31.0,"longitude":121.0,"altitude":20.0,"accuracy":5.0,"speed":2.5},"wifi":[{"bssid":"aa:bb:cc:dd:ee:f0","ssid":"Good","rssi":-50,"frequency":2437,"capabilities":"[ESS]"}],"cell":[]},
                    {"id":1,"timestamp":1,"gps":{"latitude":31.001,"longitude":121.001,"altitude":20.0,"accuracy":50.0,"speed":2.5},"wifi":[{"bssid":"aa:bb:cc:dd:ee:f1","ssid":"Bad","rssi":-40,"frequency":2437,"capabilities":"[ESS]"}],"cell":[]}
                ]
            }]
        }"""
        val count = db.loadFromJson(json)
        assertEquals(1, count) // only good-accuracy sample
    }
}
