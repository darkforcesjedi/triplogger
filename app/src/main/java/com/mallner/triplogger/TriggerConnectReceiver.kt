package com.mallner.triplogger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The trigger-connect guarantee. Unlike the service's own receiver (which
 * only exists while the service runs), a manifest receiver wakes the app
 * for every Bluetooth connect even after a reboot or a process kill, and
 * the connect event is a sanctioned while-in-use trigger, so starting the
 * location/connected-device foreground service from here is legal.
 *
 * The boot-armed service is reassurance; this receiver is the promise that
 * auto-logging cannot silently die.
 */
class TriggerConnectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val device = runCatching {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }.getOrNull()
        // Keep the broadcast (and its service-start eligibility) alive
        // while the settings read completes.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = AppSettingsRepository(context).appSettingsFlow.first()
                if (!settings.serviceEnabled || settings.tempLocationUri.isEmpty()) return@launch
                if (settings.bluetoothDeviceName.isBlank()) return@launch
                val name = runCatching { device?.name }.getOrNull()
                if (!triggerNameMatches(settings.bluetoothDeviceName, name)) return@launch
                // Idempotent while the service runs (isLogging guards);
                // starts and arms it when it does not.
                runCatching {
                    ContextCompat.startForegroundService(
                        context, Intent(context, DrivingService::class.java)
                    )
                }.onFailure {
                    Log.w("TriggerConnectReceiver", "Service start refused; app launch retries", it)
                }
            } catch (e: Exception) {
                Log.e("TriggerConnectReceiver", "Trigger wake failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * The trigger setting is a pipe-separated list of name fragments,
 * e.g. "ford|car box|chev" (case-insensitive substring match). Shared by
 * the service and the wake receiver so both agree on what the trigger is.
 */
internal fun triggerNameMatches(pattern: String, name: String?): Boolean {
    if (name == null || pattern.isBlank()) return false
    return pattern.split('|').any { token ->
        token.isNotBlank() && name.contains(token.trim(), ignoreCase = true)
    }
}
