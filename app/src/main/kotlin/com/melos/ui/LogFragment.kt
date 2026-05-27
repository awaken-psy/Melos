package com.melos.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.melos.MelosConfig
import com.melos.MelosConfig.LogEntry
import com.melos.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogFragment : Fragment() {

    private lateinit var rvAnomaly: RecyclerView
    private lateinit var rvAll: RecyclerView
    private lateinit var tabLayout: TabLayout

    private var showingAll = false

    private val anomalyAdapter = LogAdapter()
    private val allAdapter = LogAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_log, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvAnomaly = view.findViewById(R.id.rv_anomaly)
        rvAll = view.findViewById(R.id.rv_all)
        tabLayout = view.findViewById(R.id.tab_layout)

        rvAnomaly.layoutManager = LinearLayoutManager(requireContext())
        rvAnomaly.adapter = anomalyAdapter
        rvAll.layoutManager = LinearLayoutManager(requireContext())
        rvAll.adapter = allAdapter

        tabLayout.addTab(tabLayout.newTab().setText("异常"))
        tabLayout.addTab(tabLayout.newTab().setText("全部"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showingAll = tab.position == 1
                rvAll.visibility = if (showingAll) View.VISIBLE else View.GONE
                rvAnomaly.visibility = if (showingAll) View.GONE else View.VISIBLE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        view.findViewById<View>(R.id.fabClearLog).setOnClickListener {
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

        tabLayout.getTabAt(0)?.text = "异常 (${anomalies.size})"
        tabLayout.getTabAt(1)?.text = "全部 (${logs.size})"
    }
}

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
            "E" -> 0xFFC62828.toInt()
            "W" -> 0xFFE65100.toInt()
            "I" -> 0xFF1565C0.toInt()
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
