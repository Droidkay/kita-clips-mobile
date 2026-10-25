package com.kita.clips

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Hub.init(context)
        // Settings load asynchronously; read the flags straight from prefs for the decision.
        val prefs = context.getSharedPreferences("kita_clips", Context.MODE_PRIVATE)
        val paired = (prefs.getString("server", "") ?: "").isNotBlank() &&
            (prefs.getString("room", "") ?: "").isNotBlank()
        if (paired && prefs.getBoolean("background", true)) ClipService.start(context)
    }
}
