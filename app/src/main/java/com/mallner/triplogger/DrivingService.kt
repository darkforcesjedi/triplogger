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
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DrivingService : Service() {

    companion object {
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        const val ACTION_NEW_TRIP = "NEW_TRIP"
        const val ACTION_REBUILD_LOGS = "REBUILD_LOGS"
    }

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

    /** Single-flight guard: two quick onStartCommand calls must not run recovery twice. */
    private val recovering = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var lastSettings: AppSettings? = null

    private fun settingsSnapshot(): AppSettings = kotlinx.coroutines.runBlocking {
        appSettingsRepository.appSettingsFlow.first().also { lastSettings = it }
    }

    /** Journal store: the user-selected temp folder (pending/archive/failed subfolders). */
    private fun journalStore(): JournalStore? {
        val uri = settingsSnapshot().tempLocationUri
        if (uri.isEmpty()) {
            // Nothing may be written outside a user-selected folder.
            return null
        }
        journalStore?.let { if (journalStoreUri == uri) return it }
        val store = try {
            SafJournalStore(this, Uri.parse(uri))
        } catch (e: Exception) {
            Log.e("DrivingService", "Temp storage unavailable", e)
            return null
        }
        journalStore = store
        journalStoreUri = uri
        return store
    }

    /** Output storage for a selected folder; null when the user has not chosen one. */
    private fun outputStorage(uri: String): TripStorage? {
        val settings = settingsSnapshot()
        val effective = uri.ifEmpty { settings.storageLocationUri }
        return if (effective.isNotEmpty()) {
            runCatching { SafTreeStorage(this, Uri.parse(effective)) }.getOrNull()
        } else null
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
    private fun isDeviceConnected(triggerPattern: String): Boolean =
        connectedTriggerName(triggerPattern) != null

    /** Name of the first connected (or reflection-probed) device matching the pattern. */
    @SuppressLint("MissingPermission")
    private fun connectedTriggerName(triggerPattern: String): String? {
        // Check if Bluetooth is enabled
        if (!bluetoothAdapter.isEnabled || triggerPattern.isBlank()) {
            return null
        }
        val pairedDevices: Set<BluetoothDevice>? = try {
            bluetoothAdapter.bondedDevices
        } catch (_: SecurityException) {
            null
        }
        return pairedDevices?.firstOrNull {
            it.bondState == BluetoothDevice.BOND_BONDED && triggerMatches(triggerPattern, it.name) && isConnected(it)
        }?.name
    }

    /** OBD tile state while no logging session is running. */
    private fun updateIdleObdLink(settings: AppSettings) {
        lastSettings = settings
        DrivingStatus.obdLink.value =
            if (settings.obdEnabled) ObdLinkState.WAITING_FOR_DEVICE else ObdLinkState.IDLE
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
                        startLogging(settings, device?.name ?: "device")
                    } else if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED && !settings.forceLogging) {
                        stopLogging()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_NEW_TRIP) {
            // "New Trip" button: close the current trip (converted normally) and
            // continue logging into a fresh journal. Only meaningful while logging.
            if (DrivingStatus.isLogging.value) {
                serviceScope.launch {
                    val settings = appSettingsRepository.appSettingsFlow.first()
                    finalizeCurrentJournal()
                    createJournal(settings)
                }
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_REBUILD_LOGS) {
            // "Rebuild logs from archive": one-shot re-conversion, works even
            // when the logging service is otherwise disabled.
            rebuildFromArchive()
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP_SERVICE) {
            serviceScope.launch {
                appSettingsRepository.updateServiceEnabled(false)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        recoverPendingJournals()

        serviceScope.launch {
            val settings = appSettingsRepository.appSettingsFlow.first()
            if (settings.tempLocationUri.isEmpty()) {
                // Without a temporary storage folder logging can never start; do not
                // linger as a foreground service (Home switch is disabled meanwhile).
                Log.w("DrivingService", "Temporary storage not set; stopping service")
                stopSelf()
                return@launch
            }
            updateIdleObdLink(settings)
            DrivingStatus.isConnected.value = isDeviceConnected(settings.bluetoothDeviceName)
            val triggerName = connectedTriggerName(settings.bluetoothDeviceName)
            when {
                triggerName != null -> startLogging(settings, triggerName)
                settings.forceLogging -> startLogging(settings, "manual")
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
        observeForceLogging()
    }

    /**
     * React to the Home "force logging" toggle while the service is already running.
     * The toggle itself only persists the setting; without this observer it would
     * take effect no earlier than the next service start.
     */
    private fun observeForceLogging() {
        serviceScope.launch {
            appSettingsRepository.appSettingsFlow
                .distinctUntilChangedBy { it.forceLogging }
                .collect { settings -> handleLoggingToggle(settings) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleLoggingToggle(settings: AppSettings) {
        val triggerName = connectedTriggerName(settings.bluetoothDeviceName)
        when {
            DrivingStatus.isLogging.value ->
                if (!settings.forceLogging && triggerName == null) {
                    // Manual session ended by the toggle (a connected trigger keeps logging).
                    stopLogging()
                }
            triggerName != null -> startLogging(settings, triggerName)
            settings.forceLogging -> startLogging(settings, "manual")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DrivingStatus.isEnabled.value = false
        DrivingStatus.isConnected.value = false
        DrivingStatus.triggeredBy.value = null
        DrivingStatus.obdLink.value = ObdLinkState.IDLE
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
    private fun startLogging(settings: AppSettings, trigger: String? = null) {
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
        Log.d("DrivingService", "Started logging (trigger: $trigger)")
        DrivingStatus.isLogging.value = true
        DrivingStatus.triggeredBy.value =
            trigger ?: connectedTriggerName(settings.bluetoothDeviceName) ?: "manual"

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
        startNotificationTicker()
    }

    @Volatile private var fixSinceLogging = false

    // Guards against a cancelled ticker's last tick overwriting the idle
    // notification posted by stopLogging().
    @Volatile private var notifyGeneration = 0
    private var notificationJob: Job? = null

    /**
     * Ticks the recording notification once per second: duration and entry count
     * move continuously and OBD-only logging has no GPS fixes, so location
     * callbacks alone cannot keep it current.
     */
    private fun startNotificationTicker() {
        notificationJob?.cancel()
        fixSinceLogging = false
        val generation = ++notifyGeneration
        notificationJob = serviceScope.launch {
            while (isActive && DrivingStatus.isLogging.value) {
                val settings = appSettingsRepository.appSettingsFlow.first()
                val startedAt = DrivingStatus.startedAtMs.value ?: System.currentTimeMillis()
                val entries = synchronized(journalLock) { currentJournal?.entriesWritten ?: 0 }
                if (notifyGeneration == generation) {
                    runCatching {
                        notificationManager.updateRecording(
                            durationMs = System.currentTimeMillis() - startedAt,
                            entries = entries,
                            gpsEnabled = settings.gpsEnabled,
                            hasFix = fixSinceLogging,
                            obdEnabled = settings.obdEnabled,
                            obdConnected = DrivingStatus.obdLink.value == ObdLinkState.CONNECTED
                        )
                    }.onFailure { Log.e("DrivingService", "Notification update failed", it) }
                }
                delay(1000)
            }
        }
    }

    private fun stopNotificationTicker() {
        notifyGeneration++
        notificationJob?.cancel()
        notificationJob = null
    }

    private fun stopLogging() {
        Log.d("DrivingService", "Stopped logging")
        DrivingStatus.isLogging.value = false
        stopNotificationTicker()
        DrivingStatus.triggeredBy.value = null
        DrivingStatus.isObdConnected.value = false
        DrivingStatus.obdValues.value = emptyMap()
        lastObdValues = emptyMap()
        DrivingStatus.obdLink.value =
            if (lastSettings?.obdEnabled == true) ObdLinkState.WAITING_FOR_DEVICE else ObdLinkState.IDLE

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
                DrivingStatus.startedAtMs.value = journal.startMs
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

            // Segmented journaling: when the current segment passes the size cap,
            // continue the SAME trip in a new pair of files (no new trip, no finalize).
            if (journal != null && settings.splitEnabled &&
                journal.approxBytes > settings.maxSegmentSizeKb * 1024
            ) {
                try {
                    journal.split()
                    Log.i("DrivingService", "Journal ${journal.tripId} split at segment ${journal.segmentCount}")
                } catch (e: Exception) {
                    Log.e("DrivingService", "Segment split failed", e)
                }
            }
            journal ?: return@launch

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
                fixSinceLogging = true
            } catch (e: Exception) {
                Log.e("DrivingService", "Error writing to log journal", e)
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // OBD-II (ELM327) polling
    // -----------------------------------------------------------------------------------------

    private fun startObdPolling(settings: AppSettings) {
        if (!settings.obdEnabled || settings.obdDeviceName.isBlank()) {
            DrivingStatus.obdStatusText.value = null
            DrivingStatus.obdLink.value = ObdLinkState.IDLE
            return
        }
        obdJob?.cancel()
        val connector = obdConnector
        obdJob = serviceScope.launch {
            var backoffMs = 2_000L
            while (isActive) {
                DrivingStatus.isObdConnected.value = false
                DrivingStatus.obdLink.value = ObdLinkState.CONNECTING
                DrivingStatus.obdStatusText.value = getString(R.string.obd_status_connecting)
                var client: Elm327Client? = null
                try {
                    var device = connector.resolve(settings.obdDeviceName)
                    if (device == null) {
                        device = connector.scanFor(settings.obdDeviceName)
                        if (device != null) connector.remember(device)
                    }
                    if (device == null) {
                        Log.d("DrivingService", "OBD adapter '${settings.obdDeviceName}' not found; will retry")
                        DrivingStatus.obdLink.value = ObdLinkState.TIMED_OUT
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                        continue
                    }
                    client = Elm327Client(device)
                    client.connect()
                    DrivingStatus.isObdConnected.value = true
                    DrivingStatus.obdLink.value = ObdLinkState.CONNECTED
                    DrivingStatus.obdStatusText.value = null
                    backoffMs = 2_000L
                    pollLoop(client, settings)
                } catch (e: Exception) {
                    if (isActive) {
                        Log.w("DrivingService", "OBD connection lost: ${e.message}")
                        DrivingStatus.obdLink.value = ObdLinkState.TIMED_OUT
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

    /**
     * PIDs to poll every cycle: the log selection plus 0D/0C (gauges always need
     * speed + RPM) and the indicator PIDs whose corner icon is switched on.
     * Only settings.obdSelectedPids are written to the journal; the rest update
     * the live gauges/indicators (LiveJournal ignores map keys it has no
     * column for).
     */
    private fun pollPidsFor(settings: AppSettings): List<ObdPid> {
        val wanted = LinkedHashSet(settings.obdSelectedPids.map { it.uppercase() })
        wanted.add("0D")
        wanted.add("0C")
        if (settings.showHybridCharge) wanted.add("5B")
        if (settings.showFuelLevel) wanted.add("2F")
        return ObdPids.ALL.filter { it.hex in wanted }
    }

    private suspend fun pollLoop(client: Elm327Client, initial: AppSettings) {
        var settings = initial
        var pids = pollPidsFor(settings)
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

            // pick up sensor/indicator/gauge settings changes mid-trip without reconnecting
            settings = settingsSnapshot()
            pids = pollPidsFor(settings)

            val interval = settings.obdPollIntervalMs.coerceAtLeast(250).toLong()
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
        DrivingStatus.startedAtMs.value = null

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
     * Converts [trip] to the configured outputs. When [relocate] is true the
     * journal moves to archive/ or failed/; when false (rebuild from archive)
     * the outputs are overwritten but the archived journal is untouched.
     */
    private fun finalizeTrip(store: JournalStore, trip: OrphanTrip, relocate: Boolean = true): FinalizeResult {
        val settings = settingsSnapshot()
        val kmzStorage = if (settings.generateKmz) outputStorage(settings.kmzLocationUri) else null
        val odsStorage = if (settings.saveOds) outputStorage(settings.odsLocationUri) else null

        if ((settings.generateKmz && kmzStorage == null) || (settings.saveOds && odsStorage == null)) {
            Log.e("DrivingService", "Output storage unavailable; trip ${trip.name} stays as is")
            return FinalizeResult.Failed(IllegalStateException("Output storage unavailable"))
        }

        val result = TripFinalizer.finalize(
            trip,
            FinalizeSettings(
                pidHexes = settings.obdSelectedPids,
                generateKmz = settings.generateKmz,
                saveOds = settings.saveOds,
                usUnits = settings.usUnits
            ),
            kmzStorage,
            odsStorage,
            plotRenderer
        )
        when (result) {
            is FinalizeResult.Completed -> {
                Log.i("DrivingService", "Trip ${result.label} converted (ods=${result.wroteOds} kmz=${result.kmzDayLabel})")
                if (relocate) {
                    runCatching { store.relocate(trip, toArchive = true) }
                        .onFailure { Log.e("DrivingService", "Could not relocate ${trip.name} to archive", it) }
                    toast(getString(R.string.toast_trip_complete))
                }
            }
            is FinalizeResult.Skipped -> {
                Log.i("DrivingService", "Trip ${trip.name} skipped: ${result.reason}")
                if (relocate) runCatching { store.delete(trip) }
            }
            is FinalizeResult.Failed -> {
                Log.e("DrivingService", "Trip ${trip.name} conversion failed", result.error)
                if (relocate) {
                    runCatching { store.relocate(trip, toArchive = false) }
                        .onFailure { Log.e("DrivingService", "Could not relocate ${trip.name} to failed", it) }
                }
            }
        }
        return result
    }

    /** Toasts run on the main looper; safe to call from any service coroutine. */
    private fun toast(message: String) {
        android.os.Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun recoverPendingJournals() {
        if (!recovering.compareAndSet(false, true)) return // already running
        serviceScope.launch {
            try {
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
                purgeArchive(store)
            } finally {
                recovering.set(false)
            }
        }
    }

    /** Applies the archive retention policy (age / minimum trip duration). */
    private fun purgeArchive(store: JournalStore) {
        val settings = settingsSnapshot()
        val policy = ArchivePolicy(settings.archiveRetentionDays, settings.archiveMinTripSec)
        if (policy.retentionDays <= 0 && policy.minTripSec <= 0) return
        val now = System.currentTimeMillis()
        for (trip in store.archivedTrips()) {
            try {
                if (policy.shouldDelete(trip.startMs(), trip.durationMs(), now)) {
                    Log.i("DrivingService", "Purging archived trip ${trip.name} (retention policy)")
                    store.delete(trip)
                }
            } catch (e: Exception) {
                Log.e("DrivingService", "Could not evaluate archived trip ${trip.name}", e)
            }
        }
    }

    /**
     * Re-runs every archived trip journal through the converter: regenerates
     * KMZ tracks (idempotent - existing track ids are skipped, so nothing is
     * duplicated) and ODS sheets for whichever formats are switched on now.
     */
    private fun rebuildFromArchive() {
        serviceScope.launch {
            val store = journalStore() ?: run {
                toast(getString(R.string.toast_rebuild_no_temp))
                return@launch
            }
            var rebuilt = 0
            for (trip in store.archivedTrips()) {
                try {
                    if (finalizeTrip(store, trip, relocate = false) is FinalizeResult.Completed) rebuilt++
                } catch (e: Exception) {
                    Log.e("DrivingService", "Rebuild failed for ${trip.name}", e)
                }
            }
            toast(getString(R.string.toast_rebuild_done, rebuilt))
        }
    }
}
