package com.melos.fingerprint

data class WifiAp(
    val bssid: String,
    val ssid: String,
    val rssi: Int,
    val frequency: Int,
    val capabilities: String
)

data class CellTower(
    val type: String,
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Int,
    val psc: Int,
    val dbm: Int,
    val asu: Int,
    val level: Int,
    val registered: Boolean
)

data class FingerprintSample(
    val lat: Double,
    val lng: Double,
    val wifi: List<WifiAp>,
    val cell: List<CellTower>
)

data class FingerprintResult(
    val wifi: List<WifiAp>,
    val cell: List<CellTower>
)
