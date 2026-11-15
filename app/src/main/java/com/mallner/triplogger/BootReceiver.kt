package com.mallner.triplogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // goAsync keeps the broadcast (and its service-start eligibility
        // window) alive while the settings read completes; launching a
        // detached coroutine and returning used to hand the process back to
        // "background", where Android 14+ rejects the start outright.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = AppSettingsRepository(context).appSettingsFlow.first()
                if (settings.serviceEnabled && settings.tempLocationUri.isNotEmpty()) {
                    runCatching {
                        ContextCompat.startForegroundService(
                            context, Intent(context, DrivingService::class.java)
                        )
                    }.onFailure {
                        // The service starts from the app UI whenever it next
                        // opens, so a refused boot start is not fatal.
                        Log.w("BootReceiver", "Boot start refused; service starts with the app", it)
                    }
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Boot handling failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}