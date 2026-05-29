package com.melos

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Exposes Melos simulation config to the Xposed hook running in WeChat's process.
 * Uses ContentProvider.call() for simple key-value IPC, bypassing SELinux file access restrictions.
 */
class ConfigProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.melos.config"
        const val METHOD_GET_CONFIG = "get_config"
    }

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_GET_CONFIG) return super.call(method, arg, extras)
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences("melos_config", Context.MODE_PRIVATE)
        return Bundle().apply {
            putBoolean("enabled", prefs.getBoolean("enabled", false))
            putString("mode", prefs.getString("mode", "trajectory"))
            putString("venue_id", prefs.getString("venue_id", "jiading"))
            putFloat("speed_mps", prefs.getFloat("speed_mps", 2.5f))
            putInt("laps", prefs.getInt("laps", 0))
            putFloat("fixed_lat", prefs.getFloat("fixed_lat", 0.0f))
            putFloat("fixed_lng", prefs.getFloat("fixed_lng", 0.0f))
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
