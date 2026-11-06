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
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DrivingService : Service() {

    private lateinit var appSettingsRepository: AppSettingsRepository
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var notificationManager: AppNotificationManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val plotRenderer by lazy { AndroidPlotRenderer(this) }

    private var journalStore: JournalStore? = null
    private var journalStoreUri: String? = null
    private var currentJournal: LiveJournal? = null
    private val journalLock = Any()

    private var obdJob: Job? = null
    private var lastObdValues: Map<String, Double?> = emptyMap()
    private val obdConnector by lazy { ObdDeviceConnector(this) }

    private fun settingsSnapshot(): AppSettings = kotlinx.coroutines.runBlocking {
        appSettingsRepository.appSettingsFlow.first()
    }

    /** Journal store: user-selected temp folder (with pending/success/failed subfolders). */
    private fun journalStore(): JournalStore? {
        val uri = settingsSnapshot().tempLocationUri
        journalStore?.let { if (journalStoreUri == uri) return it }
        val store = try {
            if (uri.isNotEmpty()) {
                SafJournalStore(this, Uri.parse(uri))
            } else {
                FileJournalStore(File(getExternalFilesDir(null), "trips/journal"))
            }
        } catch (e: Exception) {
            Log.e("DrivingService", "Temp storage unavailable, using app storage", e)
            FileJournalStore(File(getExternalFilesDir(null), "trips/journal"))
        }
        journalStore = store
        journalStoreUri = uri
        return store
    }

    private fun outputStorage(uri: String, fallbackSubdir: String): TripStorage {
        val settings = settingsSnapshot()
        val effective = uri.ifEmpty { settings.storageLocationUri }
        return if (effective.isNotEmpty()) {
            SafTreeStorage(this, Uri.parse(effective))
        } else {
            DirStorage(File(getExternalFilesDir(null), "trips/$fallbackSubdir"))
        }
    }

    private fun isConnected(device: BluetoothDevice): Boolean {
        return try {
            val m = device.javaClass.getMethod("isConnected")
            m.invoke(device) as Boolean
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The trigger setting is a pipe-separated list of name fragments,
     * e.g. "ford|car box|chev" (case-insensitive substring match).
     */
    private fun triggerMatches(pattern: String, name: String?): Boolean {
        if (name == null || pattern.isBlank()) return false
        return pattern.split('|').any { token ->
            token.isNotBlank() && name.contains(token.trim(), ignoreCase = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun isDeviceConnected(triggerPattern: String): Boolean {
        // Check if Bluetooth is enabled
        if (!bluetoothAdapter.isEnabled || triggerPattern.isBlank()) {
            return false
        }
        val pairedDevices: Set<BluetoothDevice>? = try {
            bluetoothAdapter.bondedDevices
        } catch (_: SecurityException) {
            null
        }
        return pairedDevices?.any {
            it.bondState == BluetoothDevice.BOND_BONDED && triggerMatches(triggerPattern, it.name) && isConnected(it)
        } ?: false
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

            serviceScope.launch {
                val settings = appSettingsRepository.appSettingsFlow.first()
                if (triggerMatches(settings.bluetoothDeviceName, device?.name)) {
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
            serviceScope.launch {
                appSettingsRepository.updateServiceEnabled(false)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        recoverPendingJournals()

        serviceScope.launch {
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

        val initialNotification = notificationManager.buildInitialNotification(
            getString(R.string.service_enabled), getString(R.string.awaiting_bluetooth_connection)
        )
        try {
            ServiceCompat.startForeground(
                this,
                notificationManager.notificationID(),
                initialNotification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } catch (e: Exception) {
            // Some OEMs reject certain foreground service types; fall back to the legacy call
            Log.w("DrivingService", "startForeground with types failed", e)
            @Suppress("DEPRECATION")
            startForeground(notificationManager.notificationID(), initialNotification)
        }

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
        serviceScope.cancel()

        stopForeground(STOP_FOREGROUND_DETACH)
        notificationManager.cancelNotification()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    @SuppressLint("MissingPermission")
    private fun startLogging(settings: AppSettings) {
        if (DrivingStatus.isLogging.value) return
        if (settings.tempLocationUri.isEmpty()) {
            // No journal location = no logging at all (requirement: nothing may run unlogged)
            Log.w("DrivingService", "Not logging: temporary storage location is not set")
            return
        }
        if (!settings.gpsEnabled && !settings.obdEnabled) {
            Log.w("DrivingService", "Not logging: neither GPS nor OBD is enabled")
            return
        }
        Log.d("DrivingService", "Started logging")
        DrivingStatus.isLogging.value = true

        val notification = notificationManager.buildInitialNotification(
            getString(R.string.service_enabled),
            getString(R.string.awaiting_bluetooth_connection)
        )
        notificationManager.notify(notificationManager.notificationID(), notification)

        createJournal(settings)
        if (settings.gpsEnabled) {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, settings.loggingFrequency * 1000L).build()
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }

        startObdPolling(settings)
    }

    private fun stopLogging() {
        Log.d("DrivingService", "Stopped logging")
        DrivingStatus.isLogging.value = false
        DrivingStatus.isObdConnected.value = false
        DrivingStatus.obdValues.value = emptyMap()
        lastObdValues = emptyMap()

        obdJob?.cancel()
        obdJob = null

        fusedLocationClient.removeLocationUpdates(locationCallback)
        finalizeCurrentJournal()

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

    // -----------------------------------------------------------------------------------------
    // GPS journaling
    // -----------------------------------------------------------------------------------------

    private fun tripIdFor(timestampMs: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return fmt.format(Date(timestampMs))
    }

    private fun createJournal(settings: AppSettings) {
        synchronized(journalLock) {
            if (currentJournal != null) return
            try {
                val store = journalStore() ?: return
                val tripId = tripIdFor(System.currentTimeMillis())
                val journal = store.createTrip(tripId, System.currentTimeMillis(), settings.obdSelectedPids)
                currentJournal = journal
                DrivingStatus.currentLogFile.value = "pending/$tripId"
            } catch (e: Exception) {
                Log.e("DrivingService", "Error creating log journal", e)
            }
        }
    }

    private fun logLocation(location: Location) {
        serviceScope.launch {
            val settings = appSettingsRepository.appSettingsFlow.first()
            var journal: LiveJournal? = synchronized(journalLock) {
                if (currentJournal == null) createJournal(settings)
                currentJournal
            }

            // Rollover: close this trip and start a fresh journal past the size cap
            if (journal != null && journal.approxBytes > settings.maxLogFileSize) {
                finalizeCurrentJournal()
                createJournal(settings)
                journal = synchronized(journalLock) { currentJournal }
            }
            journal ?: return@launch

            val shortTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val shortTime = shortTimeFormat.format(Date())
            val speedMPH = location.speed * 2.23694f

            try {
                journal.append(
                    GpsRow(
                        ts = System.currentTimeMillis(),
                        gpsTime = location.time,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        altitude = if (location.hasAltitude()) location.altitude else 0.0,
                        speedMps = location.speed,
                        bearing = location.bearing,
                        accuracyM = if (location.hasAccuracy()) location.accuracy else 0f
                    )
                )
            } catch (e: Exception) {
                Log.e("DrivingService", "Error writing to log journal", e)
            }

            val obdSummary = obdSummaryText()
            notificationManager.updateStatus(
                statusText = getString(R.string.logging_active),
                lastFix = shortTime,
                speed = speedMPH.toInt().toString(),
                extra = obdSummary
            )
        }
    }

    private fun obdSummaryText(): String? {
        if (!DrivingStatus.isObdConnected.value) return DrivingStatus.obdStatusText.value
        val rpm = lastObdValues["0C"]?.toInt()?.toString() ?: "-"
        val speed = lastObdValues["0D"]?.let { (it * TripFormats.KMH_TO_MPH).toInt().toString() } ?: "-"
        return "OBD: ${speed}mph ${rpm}rpm"
    }

    // -----------------------------------------------------------------------------------------
    // OBD-II (ELM327) polling
    // -----------------------------------------------------------------------------------------

    private fun startObdPolling(settings: AppSettings) {
        if (!settings.obdEnabled || settings.obdDeviceName.isBlank()) {
            DrivingStatus.obdStatusText.value = null
            return
        }
        obdJob?.cancel()
        val connector = obdConnector
        obdJob = serviceScope.launch {
            var backoffMs = 2_000L
            while (isActive) {
                DrivingStatus.isObdConnected.value = false
                DrivingStatus.obdStatusText.value = "OBD: connecting…"
                var client: Elm327Client? = null
                try {
                    var device = connector.resolve(settings.obdDeviceName)
                    if (device == null) {
                        device = connector.scanFor(settings.obdDeviceName)
                        if (device != null) connector.remember(device)
                    }
                    if (device == null) {
                        Log.d("DrivingService", "OBD adapter '${settings.obdDeviceName}' not found; will retry")
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                        continue
                    }
                    client = Elm327Client(device)
                    client.connect()
                    DrivingStatus.isObdConnected.value = true
                    DrivingStatus.obdStatusText.value = null
                    backoffMs = 2_000L
                    pollLoop(client, settings)
                } catch (e: Exception) {
                    if (isActive) {
                        Log.w("DrivingService", "OBD connection lost: ${e.message}")
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                    }
                } finally {
                    DrivingStatus.isObdConnected.value = false
                    client?.close()
                }
            }
        }
    }

    private suspend fun pollLoop(client: Elm327Client, settings: AppSettings) {
        val pids = settings.obdSelectedPids.mapNotNull { ObdPids.byHex(it) }
        val interval = settings.obdPollIntervalMs.coerceAtLeast(250).toLong()
        while (kotlin.coroutines.coroutineContext[Job]?.isActive != false) {
            val cycleStart = System.currentTimeMillis()
            val values = HashMap<String, Double?>()
            var consecutiveTimeouts = 0
            for (pid in pids) {
                val response = try {
                    client.queryPid(pid.hex)
                } catch (e: java.io.IOException) {
                    throw e // reconnect
                }
                when (response) {
                    is ObdResponse.Ok -> {
                        consecutiveTimeouts = 0
                        values[pid.hex] = ObdPids.parseValue(pid, response.dataHex)
                    }
                    is ObdResponse.NoData, is ObdResponse.Error -> {
                        values[pid.hex] = null
                        consecutiveTimeouts = 0
                    }
                    is ObdResponse.BusStopped -> {
                        values[pid.hex] = null // engine off / no comm expected
                    }
                    is ObdResponse.Timeout -> {
                        values[pid.hex] = null
                        consecutiveTimeouts++
                        if (consecutiveTimeouts >= pids.size && pids.isNotEmpty()) {
                            throw java.io.IOException("ELM327 stopped responding")
                        }
                    }
                }
            }

            lastObdValues = values
            DrivingStatus.obdValues.value = values

            val location = DrivingStatus.lastLocation.value
            synchronized(journalLock) {
                currentJournal?.append(
                    ObdRow(
                        ts = cycleStart,
                        gpsTime = location?.time,
                        latitude = location?.latitude,
                        longitude = location?.longitude,
                        altitude = if (location != null && location.hasAltitude()) location.altitude else null,
                        values = values
                    )
                )
            }

            val elapsed = System.currentTimeMillis() - cycleStart
            delay((interval - elapsed).coerceAtLeast(50L))
        }
    }

    // -----------------------------------------------------------------------------------------
    // Trip finalization + crash recovery
    // -----------------------------------------------------------------------------------------

    private fun finalizeCurrentJournal() {
        val journal = synchronized(journalLock) {
            val j = currentJournal
            currentJournal = null
            j
        } ?: return
        journal.close()
        DrivingStatus.currentLogFile.value = null

        serviceScope.launch {
            val store = journalStore() ?: run {
                Log.e("DrivingService", "Cannot finalize trip: temp storage unavailable")
                return@launch
            }
            try {
                val trip = store.orphanOf(journal)
                if (trip.isEmpty()) {
                    store.delete(trip)
                    return@launch
                }
                finalizeTrip(store, trip)
            } catch (e: Exception) {
                Log.e("DrivingService", "Trip ${journal.tripId} left for recovery", e)
            }
        }
    }

    /**
     * Converts [trip] to the configured outputs and relocates its journal into
     * the temp folder's success/ or failed/ subfolder. If the output storage is
     * not reachable the trip stays in pending/ for the next recovery pass.
     */
    private fun finalizeTrip(store: JournalStore, trip: OrphanTrip) {
        val settings = settingsSnapshot()
        val kmzStorage = if (settings.generateKmz) {
            runCatching { outputStorage(settings.kmzLocationUri, "kmz") }.getOrNull()
        } else null
        val odsStorage = if (settings.saveOds) {
            runCatching { outputStorage(settings.odsLocationUri, "ods") }.getOrNull()
        } else null

        if ((settings.generateKmz && kmzStorage == null) || (settings.saveOds && odsStorage == null)) {
            Log.e("DrivingService", "Output storage unavailable; trip ${trip.name} stays pending")
            return
        }

        val result = TripFinalizer.finalize(
            trip,
            FinalizeSettings(
                pidHexes = settings.obdSelectedPids,
                generateKmz = settings.generateKmz,
                saveOds = settings.saveOds
            ),
            kmzStorage,
            odsStorage,
            plotRenderer
        )
        when (result) {
            is FinalizeResult.Completed -> {
                Log.i("DrivingService", "Trip ${result.label} converted (ods=${result.wroteOds} kmz=${result.kmzDayLabel})")
                runCatching { store.relocate(trip, toSuccess = true) }
                    .onFailure { Log.e("DrivingService", "Could not relocate ${trip.name} to success", it) }
            }
            is FinalizeResult.Skipped -> {
                Log.i("DrivingService", "Trip ${trip.name} skipped: ${result.reason}")
                runCatching { store.delete(trip) }
            }
            is FinalizeResult.Failed -> {
                Log.e("DrivingService", "Trip ${trip.name} conversion failed; moving to failed/", result.error)
                runCatching { store.relocate(trip, toSuccess = false) }
                    .onFailure { Log.e("DrivingService", "Could not relocate ${trip.name} to failed", it) }
            }
        }
    }

    private fun recoverPendingJournals() {
        serviceScope.launch {
            val store = journalStore() ?: return@launch
            for (trip in store.unfinishedTrips()) {
                if (synchronized(journalLock) { currentJournal?.tripId } == trip.name) continue
                Log.i("DrivingService", "Recovering unfinished trip ${trip.name}")
                try {
                    finalizeTrip(store, trip)
                } catch (e: Exception) {
                    Log.e("DrivingService", "Recovery failed for ${trip.name}", e)
                }
            }
        }
    }
}
