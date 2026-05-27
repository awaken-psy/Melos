package com.melos.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.melos.R

class MainActivity : AppCompatActivity() {

    private val simulateFragment = SimulateFragment()
    private val collectorFragment = CollectorFragment()
    private val logFragment = LogFragment()

    private var activeFragment: Fragment = simulateFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        supportFragmentManager.beginTransaction().apply {
            add(R.id.nav_host, logFragment, "log").hide(logFragment)
            add(R.id.nav_host, collectorFragment, "collector").hide(collectorFragment)
            add(R.id.nav_host, simulateFragment, "simulate")
        }.commit()

        findViewById<BottomNavigationView>(R.id.bottom_nav).setOnItemSelectedListener { item ->
            val target = when (item.itemId) {
                R.id.nav_simulate -> simulateFragment
                R.id.nav_collector -> collectorFragment
                R.id.nav_log -> logFragment
                else -> simulateFragment
            }
            if (target != activeFragment) {
                supportFragmentManager.beginTransaction().apply {
                    hide(activeFragment)
                    show(target)
                }.commit()
                activeFragment = target
            }
            true
        }
    }
}
