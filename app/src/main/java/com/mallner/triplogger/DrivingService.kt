package com.mallner.triplogger

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DrivingService : Service() {

    private lateinit var appSettingsRepository: AppSettingsRepository
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var notificationManager: AppNotificationManager
    private var logFile: DocumentFile? = null
    private var fileWriter: FileWriter? = null
    private var outputStream: OutputStream? = null
    private var currentLogFileIndex = 0
    private var originalLogFileName: String? = null
    private lateinit var bluetoothAdapter: BluetoothAdapter


    private fun isConnected(device: BluetoothDevice): Boolean {
        return try {
            val m = device.javaClass.getMethod("isConnected")
            m.invoke(device) as Boolean
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun isDeviceConnected(deviceName: String): Boolean {
        // Check if Bluetooth is enabled
        if (!bluetoothAdapter.isEnabled) {
            return false
        }
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
        return pairedDevices?.any { it.name == deviceName && it.bondState == BluetoothDevice.BOND_BONDED && isConnected(it) } ?: false
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val action: String? = intent.action

            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            CoroutineScope(Dispatchers.IO).launch {
                val settings = appSettingsRepository.appSettingsFlow.first()
                if (device?.name == settings.bluetoothDeviceName) {
                    DrivingStatus.isConnected.value = action == BluetoothDevice.ACTION_ACL_CONNECTED
                    if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                        startLogging(settings)
                    } else if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED && !settings.forceLogging) {
                        stopLogging()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            CoroutineScope(Dispatchers.IO).launch {
                appSettingsRepository.updateServiceEnabled(false)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        CoroutineScope(Dispatchers.IO).launch {
            val settings = appSettingsRepository.appSettingsFlow.first()
            DrivingStatus.isConnected.value = isDeviceConnected(settings.bluetoothDeviceName)
            if (settings.forceLogging || DrivingStatus.isConnected.value) {
                startLogging(settings)
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        DrivingStatus.isEnabled.value = true
        appSettingsRepository = AppSettingsRepository(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        notificationManager = AppNotificationManager(this)


        startForeground(
            notificationManager.notificationID(),
            notificationManager.buildInitialNotification(getString(R.string.service_enabled), getString(R.string.awaiting_bluetooth_connection))
        )

        val bluetoothManager: BluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(bluetoothReceiver, filter)
        setupLocationCallback()
    }

    override fun onDestroy() {
        super.onDestroy()
        DrivingStatus.isEnabled.value = false
        DrivingStatus.isConnected.value = false
        unregisterReceiver(bluetoothReceiver)
        stopLogging()

        stopForeground(true)
        notificationManager.cancelNotification()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    @SuppressLint("MissingPermission")
    private fun startLogging(settings: AppSettings) {
        Log.d("DrivingService", "Started logging")
        DrivingStatus.isLogging.value = true

        val notification = notificationManager.buildInitialNotification(
            getString(R.string.service_enabled),
            getString(R.string.awaiting_bluetooth_connection)
        )

        notificationManager.notify(notificationManager.notificationID(), notification)

        createLogFile(settings)
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, settings.loggingFrequency * 1000L).build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun stopLogging() {
        Log.d("DrivingService", "Stopped logging")
        DrivingStatus.isLogging.value = false
        originalLogFileName = null

        fusedLocationClient.removeLocationUpdates(locationCallback)
        closeLogFile()

        val notification = notificationManager.buildInitialNotification(
            getString(R.string.service_enabled),
            getString(R.string.awaiting_bluetooth_connection)
        )

        notificationManager.notify(notificationManager.notificationID(), notification)
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.locations.forEach { location ->
                    DrivingStatus.lastLocation.value = location
                    logLocation(location)
                }
            }
        }
    }

    private fun createLogFile(settings: AppSettings) {
        if (originalLogFileName == null) {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            originalLogFileName = "trip_$timestamp.csv"
        }

        val fileName = if (currentLogFileIndex == 0) {
            originalLogFileName!!
        } else {
            originalLogFileName!!.replace(".csv", "_$currentLogFileIndex.csv")
        }

        if (settings.storageLocationUri.isNotEmpty()) {
            val treeUri = Uri.parse(settings.storageLocationUri)
            val docFile = DocumentFile.fromTreeUri(this, treeUri)
            logFile = docFile?.createFile("text/csv", fileName)
            logFile?.uri?.let {
                outputStream = contentResolver.openOutputStream(it, "wa")
                outputStream?.write("timestamp,gps_timestamp,latitude,longitude,speed\n".toByteArray())
            }
            DrivingStatus.currentLogFile.value = logFile?.uri.toString()
        } else {
            val file = File(getExternalFilesDir(null), fileName)
            try {
                fileWriter = FileWriter(file, true)
                fileWriter?.append("timestamp,gps_timestamp,latitude,longitude,speed\n")
            } catch (e: Exception) {
                Log.e("DrivingService", "Error creating log file", e)
            }
            DrivingStatus.currentLogFile.value = file.absolutePath
        }
    }

    private fun logLocation(location: Location) {
        CoroutineScope(Dispatchers.IO).launch {
            val settings = appSettingsRepository.appSettingsFlow.first()
            if ((logFile?.length() ?: 0) > settings.maxLogFileSize) {
                currentLogFileIndex++
                closeLogFile()
                createLogFile(settings)
            }

            val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSXXX", Locale.getDefault())
            val shortTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val timestamp = simpleDateFormat.format(Date())
            val shortTime = shortTimeFormat.format(Date())
            val gpsTimestamp = simpleDateFormat.format(Date(location.time))
            val speedMPH = location.speed * 2.23694f
            val logEntry = "$timestamp,$gpsTimestamp,${location.latitude},${location.longitude},${speedMPH}\n"

            notificationManager.updateStatus(statusText = getString(R.string.logging_active), lastFix = shortTime, speed = speedMPH.toInt().toString())

            try {
                outputStream?.write(logEntry.toByteArray())
                outputStream?.flush()
                fileWriter?.append(logEntry)
                fileWriter?.flush()
            } catch (e: Exception) {
                Log.e("DrivingService", "Error writing to log file", e)
            }
        }
    }

    private fun closeLogFile() {
        try {
            outputStream?.close()
            fileWriter?.close()
        } catch (e: Exception) {
            Log.e("DrivingService", "Error closing log file", e)
        }
    }
}