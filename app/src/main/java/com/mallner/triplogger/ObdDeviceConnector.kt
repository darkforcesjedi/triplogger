package com.mallner.triplogger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds the OBD adapter by name. "JFIND"-style adapters usually cannot be
 * paired, so discovery scanning is used when the adapter is not bonded and not
 * in the connected-devices cache.
 */
class ObdDeviceConnector(private val context: Context) {

    companion object {
        private const val TAG = "ObdDeviceConnector"
        const val SCAN_TIMEOUT_MS = 15_000L

        /** Names typically used by ELM327 adapters. */
        private val OBD_NAME_REGEX = Regex("(?i)(JFIND|ELM327|OBD|VGATE|CARBOX|CAR?BOX|SCANTOOL|BNRZ|LEDRIVER|OBDII|VLINK|iLab)")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    @SuppressLint("MissingPermission")
    fun bondedMatching(): List<BluetoothDevice> =
        try {
            adapter?.bondedDevices?.filter { it.name != null }.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }

    fun looksLikeObdAdapter(name: String?): Boolean = name != null && OBD_NAME_REGEX.containsMatchIn(name)

    /** Bonded devices and anything currently connected, matching the configured name exactly. */
    @SuppressLint("MissingPermission")
    fun resolve(name: String): BluetoothDevice? {
        if (name.isBlank() || adapter == null) return null
        return try {
            adapter.bondedDevices.firstOrNull { it.name == name }
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Starts discovery and collects devices whose names match [predicate] until
     * the timeout elapses. Used by the Settings screen to offer adapter choices.
     */
    @SuppressLint("MissingPermission")
    suspend fun scanUntil(predicate: (String?) -> Boolean, timeoutMs: Long = SCAN_TIMEOUT_MS): List<BluetoothDevice> {
        val bt = adapter ?: return emptyList()
        if (!bt.isEnabled) return emptyList()

        val found = HashMap<String, BluetoothDevice>()
        val finished = CompletableDeferred<Unit>()
        val handler = Handler(Looper.getMainLooper())

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (device != null && predicate(device.name)) {
                    found[device.address] = device
                }
            }
        }
        try {
            context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        } catch (_: SecurityException) {
            return emptyList()
        }

        return try {
            try {
                bt.cancelDiscovery()
                Thread.sleep(200)
                bt.startDiscovery()
            } catch (_: SecurityException) {
                return emptyList()
            }
            withTimeoutOrNull(timeoutMs) { finished.await() }
            found.values.sortedBy { it.name }
        } finally {
            handler.post {
                try {
                    bt.cancelDiscovery()
                } catch (_: SecurityException) {
                }
            }
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Starts discovery and waits for a device with the given name to appear.
     * Returns null when the name was not seen within [SCAN_TIMEOUT_MS].
     */
    @SuppressLint("MissingPermission")
    suspend fun scanFor(name: String, timeoutMs: Long = SCAN_TIMEOUT_MS): BluetoothDevice? {
        val bt = adapter ?: return null
        if (!bt.isEnabled) return null

        val deferred = CompletableDeferred<BluetoothDevice?>()
        val handler = Handler(Looper.getMainLooper())

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val deviceName = device?.name
                if (device != null && deviceName == name) {
                    if (!deferred.isCompleted) deferred.complete(device)
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)

        try {
            context.registerReceiver(receiver, filter)
        } catch (_: SecurityException) {
            return null
        }

        return try {
            try {
                bt.cancelDiscovery() // restart cleanly
                Thread.sleep(200)
                bt.startDiscovery()
            } catch (_: SecurityException) {
                return null
            }

            val found = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (found == null) {
                Log.d(TAG, "OBD adapter '$name' not found in $timeoutMs ms scan")
            }
            found
        } finally {
            handler.post {
                try {
                    bt.cancelDiscovery()
                } catch (_: SecurityException) {
                }
            }
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }
}
