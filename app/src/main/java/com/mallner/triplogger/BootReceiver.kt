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
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val appSettingsRepository = AppSettingsRepository(context)
            CoroutineScope(Dispatchers.IO).launch {
                val settings = appSettingsRepository.appSettingsFlow.first()
                if (settings.serviceEnabled && settings.tempLocationUri.isNotEmpty()) {
                    // startForegroundService, not startService: by the time
                    // the settings read completes the process is no longer
                    // exempt from background-start limits (the plain call
                    // crashed the app with BackgroundServiceStartNotAllowed).
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
            }
        }
    }
}