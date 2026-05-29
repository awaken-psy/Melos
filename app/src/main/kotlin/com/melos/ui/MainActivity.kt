package com.melos.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.melos.MelosConfig
import com.melos.R

class MainActivity : AppCompatActivity() {

    private val simulateFragment = SimulateFragment()
    private val collectorFragment = CollectorFragment()
    private val logFragment = LogFragment()

    private var activeFragment: Fragment = simulateFragment
    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MelosConfig.appContext = applicationContext
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        toolbar.title = "模拟"

        supportFragmentManager.beginTransaction().apply {
            add(R.id.nav_host, logFragment, "log").hide(logFragment)
            add(R.id.nav_host, collectorFragment, "collector").hide(collectorFragment)
            add(R.id.nav_host, simulateFragment, "simulate")
        }.commit()

        findViewById<BottomNavigationView>(R.id.bottom_nav).setOnItemSelectedListener { item ->
            val target = when (item.itemId) {
                R.id.nav_simulate -> simulateFragment to "模拟"
                R.id.nav_collector -> collectorFragment to "采集"
                R.id.nav_log -> logFragment to "日志"
                else -> simulateFragment to "模拟"
            }
            if (target.first != activeFragment) {
                supportFragmentManager.beginTransaction().apply {
                    hide(activeFragment)
                    show(target.first)
                }.commit()
                activeFragment = target.first
            }
            toolbar.title = target.second
            true
        }
    }
}
