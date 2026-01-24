package com.mallner.triplogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
                if (settings.serviceEnabled) {
                    context.startService(Intent(context, DrivingService::class.java))
                }
            }
        }
    }
}