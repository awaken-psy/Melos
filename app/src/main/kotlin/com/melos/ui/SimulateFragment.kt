package com.melos.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.melos.LocationPickerActivity
import com.melos.MelosConfig
import com.melos.R
import com.melos.TrajectoryMapActivity
import kotlin.math.roundToInt

class SimulateFragment : Fragment() {

    // Mode toggle
    private lateinit var toggleMode: MaterialButtonToggleGroup
    private lateinit var btnModeTrajectory: MaterialButton
    private lateinit var btnModeFixed: MaterialButton

    // Trajectory mode views
    private lateinit var cardVenue: MaterialCardView
    private lateinit var spinnerVenue: AutoCompleteTextView
    private lateinit var cardPace: MaterialCardView
    private lateinit var sliderPace: Slider
    private lateinit var tvPaceLabel: TextView
    private lateinit var cardLaps: MaterialCardView
    private lateinit var sliderLaps: Slider
    private lateinit var tvLapsLabel: TextView
    private lateinit var btnPreviewTrack: MaterialButton

    // Fixed-point mode views
    private lateinit var cardFixedLocation: MaterialCardView
    private lateinit var spinnerSavedLocation: AutoCompleteTextView
    private lateinit var btnPickLocation: MaterialButton

    // Shared views
    private lateinit var tvStatus: TextView
    private lateinit var btnStartStop: MaterialButton

    private var isRunning = false
    private var currentMode = "trajectory"

    // Currently selected fixed-point location
    private var selectedFixedLocation: MelosConfig.FixedLocation? = null

    // Elapsed time timer
    private val mainHandler = Handler(Looper.getMainLooper())
    private var runStartTimeMs: Long = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            // Guard: if startTime was never set (e.g. restored from config without persistence),
            // use current time so the display starts from 00:00 instead of epoch.
            if (runStartTimeMs == 0L) {
                runStartTimeMs = System.currentTimeMillis()
                saveRunStartTime()
            }
            val elapsed = (System.currentTimeMillis() - runStartTimeMs) / 1000
            val min = (elapsed / 60).toInt()
            val sec = (elapsed % 60).toInt()
            tvStatus.text = "● 运行中  %02d:%02d".format(min, sec)
            mainHandler.postDelayed(this, 1000L)
        }
    }

    // Activity result launcher for location picker
    private val pickLocationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val loc = MelosConfig.FixedLocation(
                id = data.getStringExtra("id") ?: "",
                name = data.getStringExtra("name") ?: "",
                lat = data.getDoubleExtra("lat", 0.0),
                lng = data.getDoubleExtra("lng", 0.0),
            )
            selectedFixedLocation = loc
            refreshSavedLocations()
            spinnerSavedLocation.setText(loc.name, false)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_simulate, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Mode toggle
        toggleMode = view.findViewById(R.id.toggle_mode)
        btnModeTrajectory = view.findViewById(R.id.btn_mode_trajectory)
        btnModeFixed = view.findViewById(R.id.btn_mode_fixed)

        // Trajectory views
        cardVenue = view.findViewById(R.id.card_venue)
        spinnerVenue = view.findViewById(R.id.spinner_venue)
        cardPace = view.findViewById(R.id.card_pace)
        sliderPace = view.findViewById(R.id.slider_pace)
        tvPaceLabel = view.findViewById(R.id.tv_pace_label)
        cardLaps = view.findViewById(R.id.card_laps)
        sliderLaps = view.findViewById(R.id.slider_laps)
        tvLapsLabel = view.findViewById(R.id.tv_laps_label)
        btnPreviewTrack = view.findViewById(R.id.btn_preview_track)

        // Fixed-point views
        cardFixedLocation = view.findViewById(R.id.card_fixed_location)
        spinnerSavedLocation = view.findViewById(R.id.spinner_saved_location)
        btnPickLocation = view.findViewById(R.id.btn_pick_location)

        // Shared views
        tvStatus = view.findViewById(R.id.tv_status)
        btnStartStop = view.findViewById(R.id.btn_start_stop)

        setupModeToggle()
        setupVenueSpinner()
        setupPaceSlider()
        setupLapsSlider()
        setupSavedLocationSpinner()
        setupPickLocationButton()
        setupStartStop()

        btnPreviewTrack.setOnClickListener {
            val speedMps = paceToSpeed(sliderPace.value)
            val laps = sliderLaps.value.toInt()
            startActivity(Intent(requireContext(), TrajectoryMapActivity::class.java).apply {
                putExtra("speed_mps", speedMps)
                putExtra("laps", laps)
            })
        }

        loadCurrentConfig()
    }

    override fun onResume() {
        super.onResume()
        val config = MelosConfig.readConfig()
        isRunning = config.enabled
        if (isRunning) runStartTimeMs = loadRunStartTime()
        // Only override mode from config when running; preserve UI toggle when idle
        if (isRunning) {
            currentMode = config.mode
        }
        refreshSavedLocations()
        syncModeUI()
        updateUI()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(tickRunnable)
    }

    // ── Mode toggle ─────────────────────────────────────────────

    private fun setupModeToggle() {
        toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentMode = when (checkedId) {
                R.id.btn_mode_fixed -> "fixed_point"
                else -> "trajectory"
            }
            syncModeUI()
            updatePreviewStatus()
        }
    }

    private fun syncModeUI() {
        val isFixed = currentMode == "fixed_point"
        if (isFixed) {
            toggleMode.check(R.id.btn_mode_fixed)
        } else {
            toggleMode.check(R.id.btn_mode_trajectory)
        }

        cardVenue.visibility = if (isFixed) View.GONE else View.VISIBLE
        cardPace.visibility = if (isFixed) View.GONE else View.VISIBLE
        cardLaps.visibility = if (isFixed) View.GONE else View.VISIBLE
        cardFixedLocation.visibility = if (isFixed) View.VISIBLE else View.GONE
        btnPreviewTrack.visibility = if (!isFixed && !isRunning) View.VISIBLE else View.GONE
    }

    // ── Trajectory mode ─────────────────────────────────────────

    private fun setupVenueSpinner() {
        val names = MelosConfig.VENUES.map { it.name }
        val adapter = ArrayAdapter(requireContext(), R.layout.item_venue_dropdown, names)
        spinnerVenue.setAdapter(adapter)
        spinnerVenue.setOnItemClickListener { _, _, _, _ ->
            updatePreviewStatus()
        }
    }

    private fun setupPaceSlider() {
        sliderPace.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                updatePaceLabel(value)
                updatePreviewStatus()
            }
        }
    }

    private fun setupLapsSlider() {
        sliderLaps.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                tvLapsLabel.text = "圈数  ${value.toInt()} 圈"
                updatePreviewStatus()
            }
        }
    }

    private fun updatePaceLabel(paceSeconds: Float) {
        val min = paceSeconds.toInt() / 60
        val sec = paceSeconds.toInt() % 60
        tvPaceLabel.text = "配速  ${min}分${sec.toString().padStart(2, '0')}秒/公里"
    }

    /**
     * Update status text with a live preview of the current slider/spinner selection.
     * Only called when NOT running.
     */
    private fun updatePreviewStatus() {
        if (isRunning) return
        if (currentMode != "trajectory") return

        val selectedName = spinnerVenue.text.toString()
        val venueIdx = MelosConfig.VENUES.indexOfFirst { it.name == selectedName }.coerceAtLeast(0)
        val venue = MelosConfig.VENUES[venueIdx]
        val paceSeconds = sliderPace.value.toInt()
        val speedMps = paceToSpeed(paceSeconds.toFloat())
        val laps = sliderLaps.value.toInt()
        val paceMin = paceSeconds / 60
        val paceSec = paceSeconds % 60
        val track = MelosConfig.venueById(venue.id)
        val dist = if (track != null) String.format("%.0f", track.perimeterMeters * laps) else "?"
        val timeMin = if (track != null) String.format("%.1f", track.perimeterMeters * laps / speedMps / 60.0) else "?"

        tvStatus.text = "${venue.name} · ${laps}圈 · ${paceMin}:${paceSec.toString().padStart(2, '0')}/km · ${dist}m / ${timeMin}min"
        tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
    }

    // ── Fixed-point mode ────────────────────────────────────────

    private fun setupSavedLocationSpinner() {
        refreshSavedLocations()
        spinnerSavedLocation.setOnItemClickListener { _, _, position, _ ->
            val locations = MelosConfig.loadSavedLocations()
            if (position < locations.size) {
                selectedFixedLocation = locations[position]
            }
        }
    }

    private fun refreshSavedLocations() {
        val locations = MelosConfig.loadSavedLocations()
        val names = locations.map { it.name }
        val adapter = ArrayAdapter(requireContext(), R.layout.item_venue_dropdown, names)
        spinnerSavedLocation.setAdapter(adapter)
    }

    private fun setupPickLocationButton() {
        btnPickLocation.setOnClickListener {
            pickLocationLauncher.launch(Intent(requireContext(), LocationPickerActivity::class.java))
        }
    }

    // ── Start / Stop ────────────────────────────────────────────

    private fun setupStartStop() {
        btnStartStop.setOnClickListener {
            if (isRunning) stopSimulation() else startSimulation()
        }
    }

    private fun loadCurrentConfig() {
        val config = MelosConfig.readConfig()
        isRunning = config.enabled
        runStartTimeMs = loadRunStartTime()
        currentMode = config.mode

        // Trajectory config
        val venueIdx = MelosConfig.VENUES.indexOfFirst { it.id == config.venueId }.coerceAtLeast(0)
        spinnerVenue.setText(MelosConfig.VENUES[venueIdx].name, false)

        val paceSec = speedToPace(config.speedMps).coerceIn(180f, 540f)
        sliderPace.value = (paceSec / 10f).roundToInt() * 10f
        updatePaceLabel(sliderPace.value)

        sliderLaps.value = config.laps.coerceIn(1, 10).toFloat()
        tvLapsLabel.text = "圈数  ${config.laps} 圈"

        // Fixed-point config: restore selected location
        if (config.fixedLat != 0.0 && config.fixedLng != 0.0) {
            val saved = MelosConfig.loadSavedLocations().find {
                it.lat == config.fixedLat && it.lng == config.fixedLng
            }
            selectedFixedLocation = saved ?: MelosConfig.FixedLocation(
                "", String.format("%.5f,%.5f", config.fixedLat, config.fixedLng),
                config.fixedLat, config.fixedLng
            )
            if (saved != null) {
                spinnerSavedLocation.setText(saved.name, false)
            }
        }

        syncModeUI()
        updateUI()
    }

    private fun startSimulation() {
        if (currentMode == "fixed_point") startFixedPointSimulation()
        else startTrajectorySimulation()
    }

    private fun startTrajectorySimulation() {
        val selectedName = spinnerVenue.text.toString()
        val venueIdx = MelosConfig.VENUES.indexOfFirst { it.name == selectedName }.coerceAtLeast(0)
        val venue = MelosConfig.VENUES[venueIdx]
        val paceSeconds = sliderPace.value.toInt()
        val speedMps = paceToSpeed(paceSeconds.toFloat())
        val laps = sliderLaps.value.toInt()

        val paceMin = paceSeconds / 60
        val paceSec = paceSeconds % 60
        val track = MelosConfig.venueById(venue.id)!!
        val dist = String.format("%.0f", track.perimeterMeters * laps)
        val timeMin = String.format("%.1f", track.perimeterMeters * laps / speedMps / 60.0)

        AlertDialog.Builder(requireContext())
            .setTitle("确认开始模拟")
            .setMessage("场地: ${venue.name}\n配速: ${paceMin}分${paceSec.toString().padStart(2, '0')}秒/公里\n圈数: $laps 圈\n预计: ${dist}m / ${timeMin}min")
            .setPositiveButton("开始") { _, _ ->
                val config = MelosConfig.SimConfig(
                    enabled = true,
                    mode = "trajectory",
                    venueId = venue.id,
                    speedMps = speedMps,
                    laps = laps,
                )
                MelosConfig.writeConfig(config)
                MelosConfig.appendLog("I", "Melos", "轨迹模拟开始: ${venue.name} ${paceMin}:${paceSec}/km x ${laps}圈")
                isRunning = true
                runStartTimeMs = System.currentTimeMillis()
                saveRunStartTime()
                updateUI()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startFixedPointSimulation() {
        val loc = selectedFixedLocation
        if (loc == null || loc.lat == 0.0 || loc.lng == 0.0) {
            AlertDialog.Builder(requireContext())
                .setTitle("请先选择位置")
                .setMessage("还没有选择打卡位置，请点击「选择新位置」在地图上选点")
                .setPositiveButton("去选位置") { _, _ ->
                    pickLocationLauncher.launch(Intent(requireContext(), LocationPickerActivity::class.java))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("确认定点打卡")
            .setMessage("位置: ${loc.name}\n坐标: ${String.format("%.5f", loc.lat)}, ${String.format("%.5f", loc.lng)}\n将保持静止定位")
            .setPositiveButton("开始") { _, _ ->
                val config = MelosConfig.SimConfig(
                    enabled = true,
                    mode = "fixed_point",
                    fixedLat = loc.lat,
                    fixedLng = loc.lng,
                )
                MelosConfig.writeConfig(config)
                MelosConfig.appendLog("I", "Melos", "定点打卡开始: ${loc.name} (${loc.lat},${loc.lng})")
                isRunning = true
                runStartTimeMs = System.currentTimeMillis()
                saveRunStartTime()
                updateUI()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun stopSimulation() {
        val label = if (currentMode == "fixed_point") "打卡" else "模拟"
        AlertDialog.Builder(requireContext())
            .setTitle("停止${label}")
            .setMessage("确定要停止当前${label}吗？")
            .setPositiveButton("停止") { _, _ ->
                val config = MelosConfig.readConfig().copy(enabled = false)
                MelosConfig.writeConfig(config)
                MelosConfig.appendLog("I", "Melos", "${label}停止")
                isRunning = false
                mainHandler.removeCallbacks(tickRunnable)
                clearRunStartTime()
                updateUI()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateUI() {
        if (isRunning) {
            val errorColor = ContextCompat.getColor(requireContext(), R.color.error)
            btnStartStop.setBackgroundColor(errorColor)
            tvStatus.setTextColor(errorColor)

            // Start elapsed timer for both modes
            mainHandler.removeCallbacks(tickRunnable)
            tickRunnable.run()  // first tick immediately

            btnStartStop.text = if (currentMode == "fixed_point") "停止打卡" else "停止模拟"
            toggleMode.isEnabled = false
            spinnerVenue.isEnabled = false
            sliderPace.isEnabled = false
            sliderLaps.isEnabled = false
            spinnerSavedLocation.isEnabled = false
            btnPickLocation.isEnabled = false
            btnPreviewTrack.visibility = View.GONE
        } else {
            val successColor = ContextCompat.getColor(requireContext(), R.color.success)
            btnStartStop.text = if (currentMode == "fixed_point") "开始打卡" else "开始模拟"
            btnStartStop.setBackgroundColor(successColor)
            tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            toggleMode.isEnabled = true
            spinnerVenue.isEnabled = true
            sliderPace.isEnabled = true
            sliderLaps.isEnabled = true
            spinnerSavedLocation.isEnabled = true
            btnPickLocation.isEnabled = true
            syncModeUI()
            updatePreviewStatus()
        }
    }


    // ── Run start time persistence ───────────────────────────────

    private fun saveRunStartTime() {
        requireContext().getSharedPreferences("melos_config", android.content.Context.MODE_PRIVATE)
            .edit().putLong("start_time_ms", runStartTimeMs).apply()
    }

    private fun loadRunStartTime(): Long =
        requireContext().getSharedPreferences("melos_config", android.content.Context.MODE_PRIVATE)
            .getLong("start_time_ms", 0L)

    private fun clearRunStartTime() {
        runStartTimeMs = 0L
        requireContext().getSharedPreferences("melos_config", android.content.Context.MODE_PRIVATE)
            .edit().remove("start_time_ms").apply()
    }

    companion object {
        fun paceToSpeed(paceSeconds: Float): Double = 1000.0 / paceSeconds
        fun speedToPace(speedMps: Double): Float = (1000.0 / speedMps).toFloat()
    }
}
