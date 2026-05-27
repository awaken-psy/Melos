package com.melos.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.melos.MelosConfig
import com.melos.R

class SimulateFragment : Fragment() {

    private lateinit var spinnerVenue: Spinner
    private lateinit var seekbarSpeed: SeekBar
    private lateinit var tvSpeedLabel: TextView
    private lateinit var seekbarLaps: SeekBar
    private lateinit var tvLapsLabel: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnStartStop: Button

    private var isRunning = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_simulate, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        spinnerVenue = view.findViewById(R.id.spinner_venue)
        seekbarSpeed = view.findViewById(R.id.seekbar_speed)
        tvSpeedLabel = view.findViewById(R.id.tv_speed_label)
        seekbarLaps = view.findViewById(R.id.seekbar_laps)
        tvLapsLabel = view.findViewById(R.id.tv_laps_label)
        tvStatus = view.findViewById(R.id.tv_status)
        btnStartStop = view.findViewById(R.id.btn_start_stop)

        setupVenueSpinner()
        setupSpeedSlider()
        setupLapsSlider()
        setupStartStop()

        loadCurrentConfig()
    }

    override fun onResume() {
        super.onResume()
        // Refresh status from config (might have been changed externally)
        val config = MelosConfig.readConfig()
        isRunning = config.enabled
        updateUI()
    }

    private fun setupVenueSpinner() {
        val names = MelosConfig.VENUES.map { it.name }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVenue.adapter = adapter
    }

    private fun setupSpeedSlider() {
        // SeekBar: 0-45 → 1.0-5.5 m/s (step 0.1)
        seekbarSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = (progress + 10) / 10.0
                val kmh = speed * 3.6
                tvSpeedLabel.text = "配速: ${"%.1f".format(speed)} m/s (${ "%.1f".format(kmh)} km/h)"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupLapsSlider() {
        seekbarLaps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvLapsLabel.text = "圈数: ${progress.coerceAtLeast(1)}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupStartStop() {
        btnStartStop.setOnClickListener {
            if (isRunning) {
                stopSimulation()
            } else {
                startSimulation()
            }
        }
    }

    private fun loadCurrentConfig() {
        val config = MelosConfig.readConfig()
        isRunning = config.enabled

        // Set venue
        val venueIdx = MelosConfig.VENUES.indexOfFirst { it.id == config.venueId }.coerceAtLeast(0)
        spinnerVenue.setSelection(venueIdx)

        // Set speed (1.0-5.5 → 0-45)
        seekbarSpeed.progress = (config.speedMps * 10).toInt() - 10

        // Set laps
        seekbarLaps.progress = config.laps.coerceIn(1, 50)

        updateUI()
    }

    private fun startSimulation() {
        val venueIdx = spinnerVenue.selectedItemPosition
        val venue = MelosConfig.VENUES[venueIdx]
        val speed = (seekbarSpeed.progress + 10) / 10.0
        val laps = seekbarLaps.progress.coerceAtLeast(1)

        val venueLabel = venue.name
        val kmh = speed * 3.6
        val track = MelosConfig.venueById(venue.id)!!
        val dist = String.format("%.0f", 400.0 * laps) // approximate
        val timeMin = String.format("%.1f", 400.0 * laps / speed / 60.0)

        AlertDialog.Builder(requireContext())
            .setTitle("确认开始模拟")
            .setMessage("场地: $venueLabel\n配速: ${"%.1f".format(speed)} m/s (${ "%.1f".format(kmh)} km/h)\n圈数: $laps 圈\n预计: ${dist}m / ${timeMin}min")
            .setPositiveButton("开始") { _, _ ->
                val config = MelosConfig.SimConfig(
                    enabled = true,
                    venueId = venue.id,
                    speedMps = speed,
                    laps = laps,
                )
                MelosConfig.writeConfig(config)
                MelosConfig.appendLog("I", "Melos", "模拟开始: $venueLabel ${"%.1f".format(speed)}m/s x ${laps}圈")
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
            btnStartStop.text = "停止模拟"
            btnStartStop.setBackgroundColor(0xFFF44336.toInt()) // red
            tvStatus.text = "● 运行中 — ${venue?.name ?: config.venueId} ${"%.1f".format(config.speedMps)}m/s × ${config.laps}圈"
            spinnerVenue.isEnabled = false
            seekbarSpeed.isEnabled = false
            seekbarLaps.isEnabled = false
        } else {
            btnStartStop.text = "开始模拟"
            btnStartStop.setBackgroundColor(0xFF4CAF50.toInt()) // green
            tvStatus.text = "未启动"
            spinnerVenue.isEnabled = true
            seekbarSpeed.isEnabled = true
            seekbarLaps.isEnabled = true
        }
    }
}
