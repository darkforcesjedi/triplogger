package com.mallner.triplogger

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow

/** Lifecycle of the OBD adapter link, used by the Home status tile. */
enum class ObdLinkState {
    /** OBD logging disabled or service not watching. */
    IDLE,

    /** Enabled, but the trigger device is not connected yet. */
    WAITING_FOR_DEVICE,

    /** Trigger is here: resolving/connecting to the adapter. */
    CONNECTING,

    /** ELM327 session open and polling. */
    CONNECTED
}

object DrivingStatus {
    val isEnabled = MutableStateFlow(false)
    val isConnected = MutableStateFlow(false)
    val isLogging = MutableStateFlow(false)
    val currentLogFile = MutableStateFlow<String?>(null)
    val lastLocation = MutableStateFlow<Location?>(null)
    val isObdConnected = MutableStateFlow(false)
    val obdValues = MutableStateFlow<Map<String, Double?>>(emptyMap())
    val obdStatusText = MutableStateFlow<String?>(null)

    /**
     * What started logging: the matched Bluetooth device name, "manual" when
     * force logging is on, or null while idle. Drives the Home status light.
     */
    val triggeredBy = MutableStateFlow<String?>(null)

    val obdLink = MutableStateFlow(ObdLinkState.IDLE)
}
