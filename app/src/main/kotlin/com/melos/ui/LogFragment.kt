package com.melos.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.melos.MelosConfig
import com.melos.MelosConfig.LogEntry
import com.melos.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogFragment : Fragment() {

    private lateinit var rvAnomaly: RecyclerView
    private lateinit var rvAll: RecyclerView
    private lateinit var sectionAnomaly: View
    private lateinit var sectionAll: View
    private lateinit var tvAnomalyHeader: TextView
    private lateinit var tvAllHeader: TextView
    private lateinit var tvAnomalyCollapsed: TextView

    private var anomalyExpanded = true
    private var allExpanded = false

    private val anomalyAdapter = LogAdapter()
    private val allAdapter = LogAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_log, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvAnomaly = view.findViewById(R.id.rv_anomaly)
        rvAll = view.findViewById(R.id.rv_all)
        sectionAnomaly = view.findViewById(R.id.section_anomaly)
        sectionAll = view.findViewById(R.id.section_all)
        tvAnomalyHeader = view.findViewById(R.id.tv_anomaly_header)
        tvAllHeader = view.findViewById(R.id.tv_all_header)
        tvAnomalyCollapsed = view.findViewById(R.id.tv_anomaly_collapsed)

        rvAnomaly.layoutManager = LinearLayoutManager(requireContext())
        rvAnomaly.adapter = anomalyAdapter
        rvAll.layoutManager = LinearLayoutManager(requireContext())
        rvAll.adapter = allAdapter

        tvAnomalyHeader.setOnClickListener { toggleAnomaly() }
        tvAnomalyCollapsed.setOnClickListener { toggleAnomaly() }
        tvAllHeader.setOnClickListener { toggleAll() }

        view.findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("清除日志")
                .setMessage("确定要清除所有日志吗？")
                .setPositiveButton("清除") { _, _ ->
                    MelosConfig.clearLogs()
                    refresh()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val logs = MelosConfig.readLogs()
        val anomalies = logs.filter { it.isError }

        anomalyAdapter.submitList(anomalies)
        allAdapter.submitList(logs)

        tvAnomalyHeader.text = "${if (anomalyExpanded) "▼" else "▶"} 异常日志 (${anomalies.size})"
        tvAnomalyCollapsed.text = "${if (anomalyExpanded) "▼" else "▶"} 异常日志 (${anomalies.size})"
        tvAllHeader.text = "${if (allExpanded) "▼" else "▶"} 全部日志 (${logs.size})"
    }

    private fun toggleAnomaly() {
        anomalyExpanded = !anomalyExpanded
        updateSections()
        refresh()
    }

    private fun toggleAll() {
        allExpanded = !allExpanded
        updateSections()
        refresh()
    }

    private fun updateSections() {
        if (allExpanded) {
            // Show both sections with anomaly collapsed to header only
            sectionAnomaly.visibility = View.GONE
            tvAnomalyCollapsed.visibility = View.VISIBLE
            sectionAll.visibility = View.VISIBLE
        } else {
            // Show anomaly section (expanded or collapsed)
            tvAnomalyCollapsed.visibility = View.GONE
            sectionAnomaly.visibility = if (anomalyExpanded) View.VISIBLE else View.GONE
            sectionAll.visibility = View.GONE
        }
    }
}

// ── Adapter ──────────────────────────────────────────────────────────

class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {
    private val items = mutableListOf<LogEntry>()

    fun submitList(list: List<LogEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        holder.tvTime.text = sdf.format(Date(entry.timestamp))
        holder.tvLevel.text = entry.level
        holder.tvTag.text = entry.tag
        holder.tvMsg.text = entry.message

        val color = when (entry.level) {
            "E" -> 0xFFD32F2F.toInt()
            "W" -> 0xFFF57C00.toInt()
            "I" -> 0xFF1976D2.toInt()
            "D" -> 0xFF757575.toInt()
            else -> 0xFF757575.toInt()
        }
        holder.tvLevel.setTextColor(color)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tv_log_time)
        val tvLevel: TextView = view.findViewById(R.id.tv_log_level)
        val tvTag: TextView = view.findViewById(R.id.tv_log_tag)
        val tvMsg: TextView = view.findViewById(R.id.tv_log_msg)
    }
}
