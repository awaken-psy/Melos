package com.melos.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.melos.MelosConfig
import com.melos.R
import com.melos.collector.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CollectorFragment : Fragment() {

    private val entries = mutableListOf<CaptureEntry>()
    private var currentLocation: Location? = null
    private var wifiResults: List<ScanResult> = emptyList()
    private var cellResults: List<CellInfo> = emptyList()
    private var venueName = ""

    private lateinit var tvGps: TextView
    private lateinit var tvWifi: TextView
    private lateinit var tvCell: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: CaptureAdapter
    private lateinit var btnCapture: Button
    private lateinit var btnContinuous: Button

    private var isRecording = false
    private var recordingSamples = mutableListOf<CaptureData>()
    private var recordingStartTime = 0L
    private var recordingHandler: Handler? = null
    private var scanRunnable: Runnable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_collector, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvGps = view.findViewById(R.id.tvGps)
        tvWifi = view.findViewById(R.id.tvWifi)
        tvCell = view.findViewById(R.id.tvCell)
        listView = view.findViewById(R.id.lvCaptures)
        btnCapture = view.findViewById(R.id.btnCapture)
        btnContinuous = view.findViewById(R.id.btnContinuous)

        adapter = CaptureAdapter(entries,
            onDelete = { pos -> confirmDelete(pos) },
            onClick = { pos -> openMapForEntry(pos) }
        )
        listView.adapter = adapter

        requestPermissions()

        btnCapture.setOnClickListener { capturePoint() }
        btnContinuous.setOnClickListener { toggleContinuous() }
        view.findViewById<Button>(R.id.btnExport).setOnClickListener { showVenueDialog() }
    }

    override fun onResume() {
        super.onResume()
        if (isRecording && !RecordingService.isRunning) {
            finalizeRecording()
        }
    }

    // ── Permissions ──────────────────────────────────────────────────

    private fun requestPermissions() {
        val needed = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val ungranted = needed.filter {
            requireContext().checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(requireActivity(), ungranted.toTypedArray(), 1)
        } else {
            startListening()
        }
    }

    // ── Location Listeners ────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startListening() {
        val ctx = requireContext().applicationContext
        val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (p in providers) {
            if (lm.isProviderEnabled(p)) {
                lm.getLastKnownLocation(p)?.let {
                    if (currentLocation == null || it.accuracy < currentLocation!!.accuracy) {
                        currentLocation = it
                    }
                }
                lm.requestLocationUpdates(p, 1000, 0f, object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        currentLocation = loc
                        if (isRecording) recordSample()
                        activity?.runOnUiThread { updateStatus() }
                    }
                    override fun onProviderDisabled(provider: String) {}
                    override fun onProviderEnabled(provider: String) {}
                })
            }
        }
        refreshWifiAndCell()
        updateStatus()
    }

    @SuppressLint("MissingPermission")
    private fun refreshWifiAndCell() {
        try {
            val ctx = requireContext().applicationContext
            val wm = ctx.getSystemService(android.content.Context.WIFI_SERVICE) as WifiManager
            wm.startScan()
            wifiResults = wm.scanResults ?: emptyList()
            val tm = ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE) as TelephonyManager
            cellResults = tm.allCellInfo ?: emptyList()
        } catch (_: Exception) {
            wifiResults = emptyList()
            cellResults = emptyList()
        }
    }

    private fun updateStatus() {
        val loc = currentLocation
        val prefix = if (isRecording) "● 录制中 " else ""
        tvGps.text = if (loc != null) {
            "${prefix}GPS: ${String.format("%.6f", loc.latitude)}, ${String.format("%.6f", loc.longitude)}  精度${String.format("%.1f", loc.accuracy)}m"
        } else {
            "GPS: 等待定位..."
        }
        tvWifi.text = "WiFi: ${wifiResults.size} 个 AP"
        tvCell.text = "基站: ${cellResults.size} 小区"
    }

    // ── Point Capture ─────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun capturePoint() {
        if (isRecording) {
            Toast.makeText(requireContext(), "录制中，请先停止连续采集", Toast.LENGTH_SHORT).show()
            return
        }
        refreshWifiAndCell()
        val loc = currentLocation
        if (loc == null) {
            Toast.makeText(requireContext(), "GPS 尚未定位，请稍等", Toast.LENGTH_SHORT).show()
            return
        }
        val capture = buildCaptureData(loc)
        entries.add(PointEntry(entries.size + 1, capture))
        adapter.notifyDataSetChanged()
        Toast.makeText(requireContext(), "已采集第 ${entries.size} 个点", Toast.LENGTH_SHORT).show()
    }

    // ── Continuous Recording ──────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun toggleContinuous() {
        if (isRecording) stopContinuous() else startContinuous()
    }

    @SuppressLint("MissingPermission")
    private fun startContinuous() {
        if (currentLocation == null) {
            Toast.makeText(requireContext(), "GPS 尚未定位，请稍等", Toast.LENGTH_SHORT).show()
            return
        }
        isRecording = true
        recordingStartTime = System.currentTimeMillis()
        recordingSamples.clear()
        btnContinuous.text = "停止录制"
        btnCapture.isEnabled = false

        requireContext().startForegroundService(Intent(requireContext(), RecordingService::class.java))
        recordSample()

        recordingHandler = Handler(Looper.getMainLooper())
        scanRunnable = object : Runnable {
            override fun run() {
                if (!isRecording) return
                refreshWifiAndCell()
                recordingHandler?.postDelayed(this, 10_000)
            }
        }
        recordingHandler?.post(scanRunnable!!)

        updateStatus()
        Toast.makeText(requireContext(), "开始连续录制（可熄屏/后台运行）", Toast.LENGTH_SHORT).show()
    }

    private fun stopContinuous() {
        isRecording = false
        recordingHandler?.removeCallbacks(scanRunnable!!)
        recordingHandler = null
        scanRunnable = null
        requireContext().stopService(Intent(requireContext(), RecordingService::class.java))
        finalizeRecording()
    }

    private fun finalizeRecording() {
        isRecording = false
        recordingHandler?.removeCallbacks(scanRunnable!!)
        recordingHandler = null
        scanRunnable = null
        btnContinuous.text = "连续采集"
        btnCapture.isEnabled = true

        if (recordingSamples.isEmpty()) {
            Toast.makeText(requireContext(), "未采集到任何数据", Toast.LENGTH_SHORT).show()
            updateStatus()
            return
        }
        val session = ContinuousEntry(
            id = entries.size + 1,
            startTime = recordingStartTime,
            endTime = System.currentTimeMillis(),
            samples = recordingSamples.toList()
        )
        entries.add(session)
        adapter.notifyDataSetChanged()
        val duration = (session.endTime - session.startTime) / 1000
        Toast.makeText(requireContext(), "录制完成：${duration}s / ${recordingSamples.size} 个采样", Toast.LENGTH_SHORT).show()
        recordingSamples.clear()
        updateStatus()
    }

    @SuppressLint("MissingPermission")
    private fun recordSample() {
        val loc = currentLocation ?: return
        recordingSamples.add(buildCaptureData(loc).copy(id = recordingSamples.size))
    }

    // ── Map ───────────────────────────────────────────────────────────

    private fun openMapForEntry(pos: Int) {
        val entry = entries[pos]
        val intent = Intent(requireContext(), MapActivity::class.java).apply {
            putExtra("points", entry.toMapPoints().toString())
            putExtra("venue", venueName)
            putExtra("mode", when (entry) {
                is PointEntry -> "point"
                is ContinuousEntry -> "trajectory"
            })
        }
        startActivity(intent)
    }

    // ── Delete ────────────────────────────────────────────────────────

    private fun confirmDelete(pos: Int) {
        val entry = entries[pos]
        val (typeLabel, info) = when (entry) {
            is PointEntry -> "点" to "GPS: ${String.format("%.6f", entry.data.latitude)}, ${String.format("%.6f", entry.data.longitude)}"
            is ContinuousEntry -> "录制" to "${entry.samples.size} 个采样 / ${(entry.endTime - entry.startTime) / 1000}s"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("删除$typeLabel ${pos + 1}？")
            .setMessage(info)
            .setPositiveButton("删除") { _, _ ->
                entries.removeAt(pos)
                adapter.notifyDataSetChanged()
                Toast.makeText(requireContext(), "已删除，剩余 ${entries.size} 条", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── Export ────────────────────────────────────────────────────────

    private fun showVenueDialog() {
        if (entries.isEmpty()) {
            Toast.makeText(requireContext(), "还没有采集数据", Toast.LENGTH_SHORT).show()
            return
        }
        val input = android.widget.EditText(requireContext()).apply {
            hint = "如：同济四平操场"
            setText(venueName)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("输入场地名称")
            .setView(input)
            .setPositiveButton("导出") { _, _ ->
                venueName = input.text.toString().trim()
                exportJson()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportJson() {
        val root = JSONObject().apply {
            put("venue", venueName)
            put("device", "Pixel 4 XL")
            put("captureTime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            put("entries", JSONArray().apply { for (e in entries) put(e.toJson()) })
        }
        val dir = File(requireContext().getExternalFilesDir(null), "exports")
        dir.mkdirs()
        val file = File(dir, "melos_${venueName.ifEmpty { "venue" }}_${System.currentTimeMillis() / 1000}.json")
        file.writeText(root.toString(2))
        MelosConfig.appendLog("I", "Collector", "导出: ${file.name} (${entries.size}条)")
        Toast.makeText(requireContext(), "导出成功: ${file.name}", Toast.LENGTH_LONG).show()
    }

    // ── Helpers ──────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun buildCaptureData(loc: Location): CaptureData {
        return CaptureData(
            id = 0,
            timestamp = System.currentTimeMillis(),
            latitude = loc.latitude,
            longitude = loc.longitude,
            altitude = loc.altitude,
            accuracy = loc.accuracy,
            speed = if (loc.hasSpeed()) loc.speed else 0f,
            wifi = wifiResults.map { ap ->
                WifiAp(ap.BSSID, ap.SSID, ap.level, ap.frequency, ap.capabilities)
            }.sortedByDescending { it.rssi },
            cell = cellResults.mapNotNull { cellInfoToMap(it) }
        )
    }

    private fun cellInfoToMap(info: CellInfo): CellData? {
        return when {
            info is android.telephony.CellInfoLte && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val sig = info.cellSignalStrength; val id = info.cellIdentity
                CellData("LTE", id.mcc, id.mnc, tac = id.tac, pci = id.pci, ci = id.ci, dbm = sig.dbm, asu = sig.asuLevel, level = sig.level, registered = info.isRegistered)
            }
            info is android.telephony.CellInfoGsm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val sig = info.cellSignalStrength; val id = info.cellIdentity
                CellData("GSM", id.mcc, id.mnc, lac = id.lac, cid = id.cid, dbm = sig.dbm, asu = sig.asuLevel, level = sig.level, registered = info.isRegistered)
            }
            info is android.telephony.CellInfoWcdma && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val sig = info.cellSignalStrength; val id = info.cellIdentity
                CellData("WCDMA", id.mcc, id.mnc, lac = id.lac, cid = id.cid, psc = id.psc, dbm = sig.dbm, asu = sig.asuLevel, level = sig.level, registered = info.isRegistered)
            }
            else -> null
        }
    }
}

// ── CaptureAdapter (inline) ─────────────────────────────────────────

class CaptureAdapter(
    private val items: List<CaptureEntry>,
    private val onDelete: (Int) -> Unit,
    private val onClick: (Int) -> Unit,
) : BaseAdapter() {

    override fun getCount() = items.size
    override fun getItem(pos: Int) = items[pos]
    override fun getItemId(pos: Int) = pos.toLong()

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_capture, parent, false)
        val entry = items[pos]
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        when (entry) {
            is PointEntry -> {
                val c = entry.data
                val time = timeFmt.format(Date(c.timestamp))
                view.findViewById<TextView>(R.id.tvPointTitle).text = "点 ${pos + 1}  $time"
                view.findViewById<TextView>(R.id.tvPointDetail).text = buildString {
                    append(String.format("%.6f", c.latitude))
                    append(", ")
                    append(String.format("%.6f", c.longitude))
                    append("  ")
                    append(String.format("%.1f", c.accuracy))
                    append("m\n")
                    append("WiFi ${c.wifi.size} AP | 基站 ${c.cell.size} 小区")
                    val reg = c.cell.find { it.registered }
                    if (reg != null) append(" | ${reg.type} ${reg.dbm}dBm")
                }
            }
            is ContinuousEntry -> {
                val start = timeFmt.format(Date(entry.startTime))
                val end = timeFmt.format(Date(entry.endTime))
                val dur = (entry.endTime - entry.startTime) / 1000
                view.findViewById<TextView>(R.id.tvPointTitle).text = "录制 ${pos + 1}  $start ~ $end"
                val avgWifi = if (entry.samples.isEmpty()) 0 else entry.samples.map { it.wifi.size }.average().toInt()
                val avgCell = if (entry.samples.isEmpty()) 0 else entry.samples.map { it.cell.size }.average().toInt()
                view.findViewById<TextView>(R.id.tvPointDetail).text = buildString {
                    append("${dur}s | ${entry.samples.size} 采样\n")
                    append("WiFi ~${avgWifi} AP | 基站 ~${avgCell} 小区")
                }
            }
        }

        view.setOnClickListener { onClick(pos) }
        view.findViewById<ImageButton>(R.id.btnDelete).setOnClickListener { onDelete(pos) }
        return view
    }
}
