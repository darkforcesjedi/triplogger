package com.mallner.triplogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Arms the service after a reboot. Everything here must stay SYNCHRONOUS:
 * the broadcast's temporary-allowlist window is what legally allows the
 * while-in-use (location) foreground service to start at boot, and any
 * deferred work (a coroutine, a DataStore read) outlives it. The gate is
 * the SharedPreferences mirror (BootFlags), a memory-mapped read.
 *
 * Samsung also re-fires BOOT_COMPLETED at random times on running devices,
 * so a refusal here must never crash the process.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val gate = BootFlags.gate(context)
        if (!gate.serviceEnabled || !gate.journalSet) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, DrivingService::class.java))
        }.onFailure {
            // The service starts from the app UI whenever it next opens, so
            // a refused boot start is not fatal.
            Log.w("BootReceiver", "Boot start refused; service starts with the app", it)
        }
    }
}
