package com.melos.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.melos.MelosConfig
import com.melos.R
import com.melos.TrajectoryMapActivity
import kotlin.math.roundToInt

class SimulateFragment : Fragment() {

    private lateinit var spinnerVenue: AutoCompleteTextView
    private lateinit var sliderPace: Slider
    private lateinit var tvPaceLabel: TextView
    private lateinit var sliderLaps: Slider
    private lateinit var tvLapsLabel: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnStartStop: MaterialButton
    private lateinit var btnPreviewTrack: MaterialButton

    private var isRunning = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_simulate, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        spinnerVenue = view.findViewById(R.id.spinner_venue)
        sliderPace = view.findViewById(R.id.slider_pace)
        tvPaceLabel = view.findViewById(R.id.tv_pace_label)
        sliderLaps = view.findViewById(R.id.slider_laps)
        tvLapsLabel = view.findViewById(R.id.tv_laps_label)
        tvStatus = view.findViewById(R.id.tv_status)
        btnStartStop = view.findViewById(R.id.btn_start_stop)
        btnPreviewTrack = view.findViewById(R.id.btn_preview_track)

        setupVenueSpinner()
        setupPaceSlider()
        setupLapsSlider()
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
        updateUI()
    }

    private fun setupVenueSpinner() {
        val names = MelosConfig.VENUES.map { it.name }
        val adapter = ArrayAdapter(requireContext(), R.layout.item_venue_dropdown, names)
        spinnerVenue.setAdapter(adapter)
        spinnerVenue.setOnItemClickListener { _, _, position, _ ->
            // selection handled via selectedItemPosition in startSimulation
        }
    }

    private fun setupPaceSlider() {
        sliderPace.addOnChangeListener { _, value, fromUser ->
            if (fromUser) updatePaceLabel(value)
        }
    }

    private fun setupLapsSlider() {
        sliderLaps.addOnChangeListener { _, value, fromUser ->
            if (fromUser) tvLapsLabel.text = "圈数  ${value.toInt()} 圈"
        }
    }

    private fun updatePaceLabel(paceSeconds: Float) {
        val min = paceSeconds.toInt() / 60
        val sec = paceSeconds.toInt() % 60
        tvPaceLabel.text = "配速  ${min}分${sec.toString().padStart(2, '0')}秒/公里"
    }

    private fun setupStartStop() {
        btnStartStop.setOnClickListener {
            if (isRunning) stopSimulation() else startSimulation()
        }
    }

    private fun loadCurrentConfig() {
        val config = MelosConfig.readConfig()
        isRunning = config.enabled

        val venueIdx = MelosConfig.VENUES.indexOfFirst { it.id == config.venueId }.coerceAtLeast(0)
        spinnerVenue.setText(MelosConfig.VENUES[venueIdx].name, false)

        val paceSec = speedToPace(config.speedMps).coerceIn(180f, 540f)
        sliderPace.value = (paceSec / 10f).roundToInt() * 10f
        updatePaceLabel(sliderPace.value)

        sliderLaps.value = config.laps.coerceIn(1, 10).toFloat()
        tvLapsLabel.text = "圈数  ${config.laps} 圈"

        updateUI()
    }

    private fun startSimulation() {
        val selectedName = spinnerVenue.text.toString()
        val venueIdx = MelosConfig.VENUES.indexOfFirst { it.name == selectedName }.coerceAtLeast(0)
        val venue = MelosConfig.VENUES[venueIdx]
        val paceSeconds = sliderPace.value.toInt()
        val speedMps = paceToSpeed(paceSeconds.toFloat())
        val laps = sliderLaps.value.toInt()

        val venueLabel = venue.name
        val paceMin = paceSeconds / 60
        val paceSec = paceSeconds % 60
        val track = MelosConfig.venueById(venue.id)!!
        val dist = String.format("%.0f", track.perimeterMeters * laps)
        val timeMin = String.format("%.1f", track.perimeterMeters * laps / speedMps / 60.0)

        AlertDialog.Builder(requireContext())
            .setTitle("确认开始模拟")
            .setMessage("场地: $venueLabel\n配速: ${paceMin}分${paceSec.toString().padStart(2, '0')}秒/公里\n圈数: $laps 圈\n预计: ${dist}m / ${timeMin}min")
            .setPositiveButton("开始") { _, _ ->
                val config = MelosConfig.SimConfig(
                    enabled = true,
                    venueId = venue.id,
                    speedMps = speedMps,
                    laps = laps,
                )
                MelosConfig.writeConfig(config)
                MelosConfig.appendLog("I", "Melos", "模拟开始: $venueLabel ${paceMin}:${paceSec}/km x ${laps}圈")
                isRunning = true
                updateUI()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun stopSimulation() {
        AlertDialog.Builder(requireContext())
            .setTitle("停止模拟")
            .setMessage("确定要停止当前模拟吗？")
            .setPositiveButton("停止") { _, _ ->
                val config = MelosConfig.readConfig().copy(enabled = false)
                MelosConfig.writeConfig(config)
                MelosConfig.appendLog("I", "Melos", "模拟停止")
                isRunning = false
                updateUI()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateUI() {
        if (isRunning) {
            val config = MelosConfig.readConfig()
            val venue = MelosConfig.venueById(config.venueId)
            val pace = speedToPace(config.speedMps)
            val paceMin = pace.toInt() / 60
            val paceSec = pace.toInt() % 60
            btnStartStop.text = "停止模拟"
            btnStartStop.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.error))
            tvStatus.text = "● 运行中 — ${venue?.name ?: config.venueId} ${paceMin}:${paceSec}/km × ${config.laps}圈"
            tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.error))
            spinnerVenue.isEnabled = false
            sliderPace.isEnabled = false
            sliderLaps.isEnabled = false
            btnPreviewTrack.visibility = View.GONE
        } else {
            btnStartStop.text = "开始模拟"
            btnStartStop.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.success))
            tvStatus.text = "未启动"
            tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            spinnerVenue.isEnabled = true
            sliderPace.isEnabled = true
            sliderLaps.isEnabled = true
            btnPreviewTrack.visibility = View.VISIBLE
        }
    }

    companion object {
        fun paceToSpeed(paceSeconds: Float): Double = 1000.0 / paceSeconds
        fun speedToPace(speedMps: Double): Float = (1000.0 / speedMps).toFloat()
    }
}
