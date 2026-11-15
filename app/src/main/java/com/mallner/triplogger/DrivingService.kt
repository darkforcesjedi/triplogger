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

        /** How often the dashboard odometer (Mode 01 A6) is read while OBD is connected. */
        const val ODO_POLL_INTERVAL_MS = 15_000L
        const val ACTION_LOOKUP_PLACES = "LOOKUP_PLACES"
        const val EXTRA_LOOKUPS_UNTAGGED_ONLY = "untagged_only"
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
    private val recoveryAttempted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True while booted armed under specialUse, awaiting a sanctioned promotion. */
    private var pendingTypePromotion = false

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

    private fun triggerMatches(pattern: String, name: String?): Boolean =
        triggerNameMatches(pattern, name)

    /** The while-in-use types used once a sanctioned event (UI, ACL) is in play. */
    private fun fullFgsTypes(): Int =
        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

    /**
     * Enter the foreground, degrading gracefully: boot is not a sanctioned
     * moment for the while-in-use location/connectedDevice types on Android
     * 14+, so a SecurityException there falls back to specialUse (or the
     * legacy call pre-34) and leaves [pendingTypePromotion] set. The moment
     * logging actually starts from a sanctioned event we promote to the
     * full types. Any OEM that refuses types entirely still gets the
     * legacy untyped foreground service.
     */
    private fun enterForeground(notification: android.app.Notification) {
        val id = notificationManager.notificationID()
        try {
            ServiceCompat.startForeground(this, id, notification, fullFgsTypes())
            return
        } catch (e: Exception) {
            Log.w("DrivingService", "while-in-use FGS types refused (not a sanctioned start?)", e)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                ServiceCompat.startForeground(
                    this, id, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                pendingTypePromotion = true
                return
            } catch (e: Exception) {
                Log.w("DrivingService", "specialUse FGS refused; using legacy call", e)
            }
        }
        @Suppress("DEPRECATION")
        runCatching { startForeground(id, notification) }
            .onFailure { Log.e("DrivingService", "Could not enter foreground", it) }
    }

    /** Promote a boot-armed specialUse service to the real types when sanctioned. */
    private fun promoteForegroundIfNeeded(notification: android.app.Notification) {
        if (!pendingTypePromotion) return
        pendingTypePromotion = false
        runCatching {
            ServiceCompat.startForeground(
                this, notificationManager.notificationID(), notification, fullFgsTypes()
            )
        }.onFailure {
            Log.w("DrivingService", "FGS type promotion refused", it)
            pendingTypePromotion = true // retry at the next sanctioned moment
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
        // A running session owns the state (the watcher publishes CONNECTING /
        // TIMED_OUT / CONNECTED); overwriting it with an idle value here made
        // the tile flash "Waiting for trigger" while logging was active.
        if (DrivingStatus.isLogging.value) return
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
                    createJournal(settings, DrivingStatus.triggeredBy.value ?: "manual")
                    liveDistance.reset()
                    odoProbeFailedTrip = false
                    odoSupported = false
                    odoLastPollMs = 0L
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
        if (intent?.action == ACTION_LOOKUP_PLACES) {
            lookupPlacesForArchive(intent.getBooleanExtra(EXTRA_LOOKUPS_UNTAGGED_ONLY, false))
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
        ActivityLog.init(this)
        DrivingStatus.isEnabled.value = true
        // Keep the boot gate mirror fresh; every service start passes through
        // the real settings anyway (also seeds the mirror after an upgrade).
        serviceScope.launch { runCatching { BootFlags.resync(this@DrivingService) } }
        appSettingsRepository = AppSettingsRepository(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        notificationManager = AppNotificationManager(this)

        val initialNotification = notificationManager.buildInitialNotification(
            getString(R.string.service_enabled), getString(R.string.awaiting_bluetooth_connection)
        )
        enterForeground(initialNotification)

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
        if (Storage.metadataStore(this, settings) == null) {
            // Trips must be recorded in the metadata database; without it a
            // journal folder would go unnoticed after conversion.
            Log.w("DrivingService", "Not logging: trip metadata database unavailable")
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
        ActivityLog.log("trip_started", DrivingStatus.triggeredBy.value ?: "")

        val notification = notificationManager.buildInitialNotification(
            getString(R.string.service_enabled),
            getString(R.string.awaiting_bluetooth_connection)
        )
        notificationManager.notify(notificationManager.notificationID(), notification)
        // If boot armed us under specialUse, this is a sanctioned moment (the
        // app is visible, or the trigger's ACL_CONNECTED just woke us).
        promoteForegroundIfNeeded(notification)

        createJournal(settings, DrivingStatus.triggeredBy.value)
        if (settings.gpsEnabled) {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, settings.loggingFrequency * 1000L).build()
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }

        startObdPolling(settings)
        startNotificationTicker()
        liveDistance.reset()
        odoProbeFailedTrip = false
        odoSupported = false
        odoLastPollMs = 0L
    }

    @Volatile private var fixSinceLogging = false

    /** Live mileage accumulators for the Home screen (best available source). */
    private val liveDistance = LiveDistance()

    /**
     * Odometer (Mode 01 A6) probe state. A6 never enters the live PID list;
     * when probed it rides the poll loop on its own 15 s cadence. A car that
     * does not answer A6 is not asked again for the rest of the trip.
     */
    @Volatile private var odoProbeFailedTrip = false
    @Volatile private var odoSupported = false
    @Volatile private var odoLastPollMs = 0L

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
        liveDistance.reset()
        DrivingStatus.distanceM.value = null
        DrivingStatus.obdLink.value =
            if (lastSettings?.obdEnabled == true) ObdLinkState.WAITING_FOR_DEVICE else ObdLinkState.IDLE

        obdJob?.cancel()
        obdJob = null

        runCatching {
            val journal = synchronized(journalLock) { currentJournal }
            val entries = journal?.entriesWritten ?: 0
            val startedAt = DrivingStatus.startedAtMs.value
            ActivityLog.log(
                "trip_ended",
                journal?.tripId ?: "",
                "entries" to entries.toString(),
                "duration_s" to ((System.currentTimeMillis() - (startedAt ?: System.currentTimeMillis())) / 1000).toString()
            )
        }
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

    private fun createJournal(settings: AppSettings, trigger: String?) {
        synchronized(journalLock) {
            if (currentJournal != null) return
            try {
                val store = journalStore() ?: return
                val tripId = tripIdFor(System.currentTimeMillis())
                val journal = store.createTrip(tripId, System.currentTimeMillis(), settings.obdSelectedPids, trigger ?: "")
                // Pair the folder with its permanent record right away, so
                // even a crash seconds later leaves a recoverable pending row.
                runCatching {
                    Storage.metadataStore(this, settings)?.insertLiveTrip(
                        id = "t${journal.startMs}",
                        folder = tripId,
                        startMs = journal.startMs,
                        pids = settings.obdSelectedPids.joinToString(",").ifEmpty { null },
                        trigger = trigger?.takeIf { it.isNotEmpty() },
                        tzId = TripFormats.deviceZoneId(),
                        tzOffsetMin = TripFormats.deviceOffsetMin(journal.startMs)
                    )
                }.onFailure { Log.e("DrivingService", "Cannot insert trips row for $tripId", it) }
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
                if (currentJournal == null) createJournal(settings, DrivingStatus.triggeredBy.value)
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
                    ActivityLog.log("journal_split", journal.tripId, "segment" to journal.segmentCount.toString())
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
                liveDistance.onGps(location.time, location.latitude, location.longitude, location.speed)
                DrivingStatus.distanceM.value = liveDistance.bestMeters()
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
                        ActivityLog.log("obd_timeout", settings.obdDeviceName, "reason" to "not found")
                        DrivingStatus.obdLink.value = ObdLinkState.TIMED_OUT
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                        continue
                    }
                    client = Elm327Client(device)
                    client.connect()
                    DrivingStatus.isObdConnected.value = true
                    DrivingStatus.obdLink.value = ObdLinkState.CONNECTED
                    ActivityLog.log("obd_connected", settings.obdDeviceName)
                    DrivingStatus.obdStatusText.value = null
                    backoffMs = 2_000L
                    pollLoop(client, settings)
                } catch (e: Exception) {
                    if (isActive) {
                        Log.w("DrivingService", "OBD connection lost: ${e.message}")
                        ActivityLog.log("obd_lost", e.message ?: "error")
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
            liveDistance.onObdSpeed(cycleStart, values["0D"]?.let { it / 3.6 })
            maybePollOdometer(client, cycleStart)
            DrivingStatus.distanceM.value = liveDistance.bestMeters()

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

    /**
     * Silent odometer probe (Mode 01 A6) riding the poll loop: probed once per
     * connection and, when answered, re-read every ~15 s. Cars that reject or
     * ignore A6 are never asked again for the rest of the trip (some cars
     * choke on unsupported PIDs), and a failed probe never fails the
     * connection. Accepted readings are journaled as they arrive so the
     * odometer delta survives ignition-off and crashes; the finalizer turns
     * them into the best distance estimate.
     */
    private fun maybePollOdometer(client: Elm327Client, nowMs: Long) {
        if (odoProbeFailedTrip) return
        if (odoSupported && odoLastPollMs > 0 && nowMs - odoLastPollMs < ODO_POLL_INTERVAL_MS) return
        val response = runCatching { client.queryPid("A6") }.getOrNull()
        val pid = ObdPids.byHex("A6") ?: return
        when (response) {
            is ObdResponse.Ok -> {
                val km = ObdPids.parseValue(pid, response.dataHex)
                if (km != null && liveDistance.acceptOdometer(km, nowMs)) {
                    odoSupported = true
                    odoLastPollMs = nowMs
                    synchronized(journalLock) { currentJournal?.appendOdometer(nowMs, km) }
                }
            }
            else -> {
                // NoData / Error / BusStopped / Timeout: treat as "no odometer on
                // this car" for this trip (an odometer that answers once but
                // stops is caught by the finalizer's validation instead).
                if (!odoSupported) {
                    odoProbeFailedTrip = true
                    ActivityLog.log("obd_odo_unsupported")
                }
            }
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
                    runCatching {
                        Storage.metadataStore(this@DrivingService, settingsSnapshot())
                            ?.deleteTrip("t${journal.startMs}")
                    }
                    return@launch
                }
                finalizeTrip(store, trip, tripState = TripDatabase.STATE_PENDING)
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
    private fun finalizeTrip(
        store: JournalStore,
        trip: OrphanTrip,
        relocate: Boolean = true,
        tripState: String = TripDatabase.STATE_PENDING
    ): FinalizeResult {
        val settings = settingsSnapshot()
        val db = Storage.metadataStore(this, settings)
        val row = runCatching { db?.let { JournalSync.ensureRow(it, trip, tripState) } }
            .onFailure { Log.e("DrivingService", "No trips row for ${trip.name}", it) }
            .getOrNull()

        // "Discard trips with fewer than N entries": journals below the
        // threshold skip post-processing and go to failed/ with a reason
        // (visible in the Trips tab, clearable) instead of vanishing - this
        // also keeps test runs out of the real history when set high. Truly
        // empty journals are still deleted outright (they were never trips).
        if (settings.discardBelowEntries > 0) {
            val entries = runCatching { trip.entryCount() }.getOrDefault(Int.MAX_VALUE)
            if (entries < settings.discardBelowEntries) {
                Log.i("DrivingService", "Discarding trip ${trip.name}: $entries entries < ${settings.discardBelowEntries}")
                if (relocate) {
                    runCatching {
                        row?.let {
                            db?.setStatusReason(it.id, "discarded: $entries entries below threshold")
                        }
                    }
                    runCatching { store.relocate(trip, toArchive = false) }
                        .onFailure { Log.e("DrivingService", "Could not move discarded trip ${trip.name} to failed", it) }
                    runCatching { row?.let { db?.relocate(it.id, null, TripDatabase.STATE_FAILED) } }
                    ActivityLog.log("trip_discarded", trip.name, "entries" to entries.toString(), "threshold" to settings.discardBelowEntries.toString())
                    toast(getString(R.string.toast_trip_discarded, entries, settings.discardBelowEntries))
                }
                return FinalizeResult.Skipped("below minimum entry threshold")
            }
        }

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
                usUnits = settings.usUnits,
                appVersionName = BuildConfig.VERSION_NAME
            ),
            kmzStorage,
            odsStorage,
            plotRenderer,
            meta = if (row != null) db else null,
            target = row?.toTarget()
        )
        when (result) {
            is FinalizeResult.Completed -> {
                Log.i("DrivingService", "Trip ${result.label} converted (ods=${result.wroteOds} kmz=${result.kmzDayLabel})")
                ActivityLog.log("trip_converted", result.label, "kmz" to (result.kmzDayLabel ?: "-"), "ods" to result.wroteOds.toString())
                if (relocate) {
                    runCatching { store.relocate(trip, toArchive = true) }
                        .onFailure { Log.e("DrivingService", "Could not relocate ${trip.name} to archive", it) }
                    row?.let { runCatching { db?.relocate(it.id, null, TripDatabase.STATE_ARCHIVE) } }
                    toast(getString(R.string.toast_trip_complete))
                    if (settings.placeNameLookups && settings.gpsEnabled) {
                        // Fire-and-forget Nominatim resolution (policy-compliant,
                        // failures leave the coordinates as the display fallback)
                        val name = trip.name
                        serviceScope.launch {
                            runCatching { TripsRepository(this@DrivingService).lookUpPlaces(name) }
                        }
                    }
                }
            }
            is FinalizeResult.Skipped -> {
                Log.i("DrivingService", "Trip ${trip.name} skipped: ${result.reason}")
                if (relocate) {
                    runCatching { store.delete(trip) }
                    row?.let { runCatching { db?.deleteTrip(it.id) } }
                }
            }
            is FinalizeResult.Failed -> {
                Log.e("DrivingService", "Trip ${trip.name} conversion failed", result.error)
                ActivityLog.log("trip_failed", trip.name, "error" to (result.error.message ?: "unknown"))
                if (relocate) {
                    runCatching { row?.let { db?.setStatusReason(it.id, result.error.message ?: "conversion failed") } }
                    runCatching { store.relocate(trip, toArchive = false) }
                        .onFailure { Log.e("DrivingService", "Could not relocate ${trip.name} to failed", it) }
                    runCatching { row?.let { db?.relocate(it.id, null, TripDatabase.STATE_FAILED) } }
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

    /**
     * Finalizes journals interrupted by a crash once per service instance.
     * Every UI visit re-asserts the service with startService(), and
     * onStartCommand must not turn each of those into a recovery pass that
     * re-toasts discards and rewrites failed folders.
     */
    private fun recoverPendingJournals() {
        if (!recoveryAttempted.compareAndSet(false, true)) return
        if (!recovering.compareAndSet(false, true)) return // already running
        serviceScope.launch {
            try {
                // Migration/reconciliation first: every folder needs its
                // row before conversion (and strays get imported).
                val settings = appSettingsRepository.appSettingsFlow.first()
                if (JournalSync.ensureSynced(this@DrivingService, settings) == null) {
                    Log.w("DrivingService", "Metadata database unavailable; recovery deferred")
                    recoveryAttempted.set(false) // retry on the next start
                    return@launch
                }
                val store = journalStore() ?: run { recoveryAttempted.set(false); return@launch }
                val db = Storage.metadataStore(this@DrivingService, settings)
                val unfinished = store.pendingTrips().map { it to TripDatabase.STATE_PENDING } +
                    store.failedTrips().map { it to TripDatabase.STATE_FAILED }
                for ((trip, state) in unfinished) {
                    if (synchronized(journalLock) { currentJournal?.tripId } == trip.name) continue
                    if (!trip.listFileNames().any { it.endsWith(".csv") }) {
                        // Empty scratch folder (crash between create steps).
                        runCatching { store.delete(trip) }
                        runCatching { db?.tripByFolder(trip.name)?.let { db.deleteTrip(it.id) } }
                        continue
                    }
                    // Discarded trips are user-visible decisions, not
                    // recoverable work: below-threshold ones would re-toast
                    // forever, and manual discards must not be converted
                    // behind the user's back.
                    if (db?.tripByFolder(trip.name)?.statusReason?.startsWith("discarded") == true) continue
                    Log.i("DrivingService", "Recovering unfinished trip ${trip.name}")
                    ActivityLog.log("trip_recovered", trip.name)
                    try {
                        finalizeTrip(store, trip, tripState = state)
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

    /**
     * Applies the archive retention policy (age / minimum trip duration):
     * journal folder files first, then the row's raw_state flips to
     * purged_retention, so a failed delete leaves the trip `present` and
     * the next sweep retries. Metadata outlives its raw samples by design.
     */
    private fun purgeArchive(store: JournalStore) {
        val settings = settingsSnapshot()
        val policy = ArchivePolicy(settings.archiveRetentionDays, settings.archiveMinTripSec)
        if (policy.retentionDays <= 0 && policy.minTripSec <= 0) return
        val db = Storage.metadataStore(this, settings) ?: return
        val now = System.currentTimeMillis()
        val folders = store.archivedTrips().associateBy { it.name }
        for (row in db.trips(TripDatabase.STATE_ARCHIVE)) {
            if (row.rawState != TripDatabase.RAW_PRESENT) continue
            try {
                // Row times when converted; CSV span for legacy imports that
                // never recorded an end time.
                val duration = if (row.endMs != null && row.endMs > row.startMs) row.endMs - row.startMs
                else folders[row.folder]?.durationMs() ?: 0L
                if (policy.shouldDelete(row.startMs, duration, now)) {
                    // A row whose folder is not in the listing is externally
                    // deleted (reconciliation flags it missing), not purged.
                    val orphan = folders[row.folder] ?: continue
                    Log.i("DrivingService", "Purging archived trip ${row.folder} (retention policy)")
                    runCatching { store.delete(orphan) }.onFailure { throw it }
                    db.setRawState(row.id, TripDatabase.RAW_PURGED_RETENTION, now)
                    ActivityLog.log("trip_purged", row.folder, "policy" to "retention")
                }
            } catch (e: Exception) {
                Log.e("DrivingService", "Could not purge archived trip ${row.folder}", e)
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
            val trips = store.archivedTrips()
            val settings = settingsSnapshot()
            val formats = listOfNotNull(
                "KMZ".takeIf { settings.generateKmz },
                "ODS".takeIf { settings.saveOds }
            ).joinToString("+").ifEmpty { "KMZ+ODS" }
            var rebuilt = 0
            try {
                trips.forEachIndexed { index, trip ->
                    notificationManager.showProgress(
                        getString(R.string.progress_rebuilding),
                        getString(R.string.progress_rebuild_text, index + 1, trips.size, formats),
                        trips.size,
                        index + 1
                    )
                    try {
                        if (finalizeTrip(store, trip, relocate = false,
                                tripState = TripDatabase.STATE_ARCHIVE) is FinalizeResult.Completed) rebuilt++
                    } catch (e: Exception) {
                        Log.e("DrivingService", "Rebuild failed for ${trip.name}", e)
                    }
                }
            } finally {
                notificationManager.cancelProgress()
            }
            toast(getString(R.string.toast_rebuild_done, rebuilt))
            ActivityLog.log("rebuild_run", "$rebuilt trips re-converted")
        }
    }

    /** Bulk placename resolution over archived trips (see Settings). */
    private fun lookupPlacesForArchive(untaggedOnly: Boolean) {
        serviceScope.launch {
            val repository = TripsRepository(this@DrivingService)
            try {
                val resolved = repository.lookUpAll(untaggedOnly) { done, total, label ->
                    notificationManager.showProgress(
                        getString(R.string.progress_lookup_title),
                        if (label.isEmpty()) getString(R.string.progress_lookup_done, done, total)
                        else getString(R.string.progress_lookup_text, done, total, label),
                        total,
                        done
                    )
                }
                toast(getString(R.string.toast_lookup_done, resolved))
            } finally {
                notificationManager.cancelProgress()
            }
        }
    }
}

/**
 * Live per-trip distance accumulators for the Home screen, one per source
 * that has data; reset with each new journal. Mirrors the finalizer's math
 * incrementally: odometer delta wins over the OBD wheel-speed integral,
 * which wins over the ZUPT-gated GPS speed integral, which wins over the
 * jitter-gated haversine track.
 */
internal class LiveDistance {
    private var trackM = 0.0
    private var gpsSpeedM = 0.0
    private var obdM = 0.0
    private var odoFirstKm: Double? = null
    private var odoLastKm: Double? = null

    @Volatile private var lastFixTimeMs = 0L
    @Volatile private var lastLat = Double.NaN
    @Volatile private var lastLon = Double.NaN
    @Volatile private var lastSpeedMps = Float.NaN
    @Volatile private var lastObdTimeMs = 0L
    @Volatile private var lastObdMps: Double? = null
    @Volatile private var odoLastTimeMs = 0L

    @Synchronized
    fun reset() {
        trackM = 0.0; gpsSpeedM = 0.0; obdM = 0.0
        odoFirstKm = null; odoLastKm = null
        lastFixTimeMs = 0L; lastLat = Double.NaN; lastLon = Double.NaN; lastSpeedMps = Float.NaN
        lastObdTimeMs = 0L; lastObdMps = null; odoLastTimeMs = 0L
    }

    @Synchronized
    fun onGps(timeMs: Long, lat: Double, lon: Double, speedMps: Float) {
        if (lat.isNaN() || lon.isNaN()) return
        val prevTime = lastFixTimeMs
        lastFixTimeMs = timeMs
        if (prevTime > 0L && !lastLat.isNaN()) {
            val dt = (timeMs - prevTime) / 1000.0
            val d = haversineBetween(lastLat, lastLon, lat, lon)
            if (d > DistanceEstimators.JITTER_FLOOR_M) trackM += d
            if (dt > 0.0 && dt <= DistanceEstimators.MAX_STEP_S) {
                val va = lastSpeedMps
                if (!va.isNaN() && va >= 0f && !speedMps.isNaN() && speedMps >= 0f) {
                    if (va >= DistanceEstimators.ZUPT_SPEED_MPS || speedMps >= DistanceEstimators.ZUPT_SPEED_MPS) {
                        gpsSpeedM += (va + speedMps) / 2.0 * dt
                    }
                } else if (d > DistanceEstimators.JITTER_FLOOR_M) {
                    gpsSpeedM += d
                }
            }
        }
        lastLat = lat; lastLon = lon; lastSpeedMps = speedMps
    }

    @Synchronized
    fun onObdSpeed(timeMs: Long, mps: Double?) {
        if (mps == null || mps.isNaN()) return
        val prev = lastObdMps
        val prevTime = lastObdTimeMs
        lastObdTimeMs = timeMs
        lastObdMps = mps
        if (prev != null && prevTime > 0L) {
            val dt = (timeMs - prevTime) / 1000.0
            if (dt > 0.0 && dt <= DistanceEstimators.MAX_STEP_S) obdM += (prev + mps) / 2.0 * dt
        }
    }

    /**
     * Feed one A6 reading. Accepted only when it plausibly moved (monotonic,
     * within 160 km/h plus jitter allowance between polls); returns whether it
     * was taken, and the caller journals accepted values.
     */
    @Synchronized
    fun acceptOdometer(km: Double, timeMs: Long): Boolean {
        val dtHours = if (odoLastTimeMs > 0L) (timeMs - odoLastTimeMs) / 3_600_000.0 else 0.0
        val maxStepKm = (160.0 * dtHours).coerceAtLeast(0.5)
        if (!DistanceEstimators.liveOdoAccepts(odoLastKm, km, maxStepKm)) return false
        if (odoFirstKm == null) odoFirstKm = km
        odoLastKm = km
        odoLastTimeMs = timeMs
        return true
    }

    @Synchronized
    fun bestMeters(): Double? {
        val first = odoFirstKm
        val last = odoLastKm
        if (first != null && last != null && last > first) return (last - first) * 1000.0
        if (obdM > 0.0) return obdM
        if (gpsSpeedM > 0.0) return gpsSpeedM
        if (trackM > 0.0) return trackM
        return null
    }

    private fun haversineBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val fakeA = GpsRow(0, 0, lat1, lon1, 0.0, 0f, 0f, 0f)
        val fakeB = GpsRow(0, 0, lat2, lon2, 0.0, 0f, 0f, 0f)
        return DistanceEstimators.haversineMeters(fakeA, fakeB)
    }
}
