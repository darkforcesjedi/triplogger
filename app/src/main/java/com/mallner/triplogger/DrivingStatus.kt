package com.mallner.triplogger

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow

object DrivingStatus {
    val isEnabled = MutableStateFlow(false)
    val isConnected = MutableStateFlow(false)
    val isLogging = MutableStateFlow(false)
    val currentLogFile = MutableStateFlow<String?>(null)
    val lastLocation = MutableStateFlow<Location?>(null)
    val isObdConnected = MutableStateFlow(false)
    val obdValues = MutableStateFlow<Map<String, Double?>>(emptyMap())
    val obdStatusText = MutableStateFlow<String?>(null)
}
