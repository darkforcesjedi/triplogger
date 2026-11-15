package com.mallner.triplogger

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Encoding and merging of the settings rows in the journal database
 * (TODO "Trip metadata database", Stage C). Values are JSON scalars so a
 * future app version can read rows it does not know. temp_location_uri is
 * deliberately NOT stored: it points at the database and must stay
 * device-local (DataStore). Pure logic, JVM tested.
 */
internal object SettingsCodec {
    const val KEY_BLUETOOTH_DEVICE_NAME = "bluetooth_device_name"
    const val KEY_LOGGING_FREQUENCY = "logging_frequency"
    const val KEY_SPLIT_ENABLED = "split_enabled"
    const val KEY_MAX_SEGMENT_SIZE_KB = "max_segment_size_kb"
    const val KEY_FORCE_LOGGING = "force_logging"
    const val KEY_SERVICE_ENABLED = "logging_service_enabled"
    const val KEY_STORAGE_LOCATION_URI = "storage_location_uri"
    const val KEY_GPS_ENABLED = "gps_enabled"
    const val KEY_OBD_ENABLED = "obd_enabled"
    const val KEY_OBD_DEVICE_NAME = "obd_device_name"
    const val KEY_OBD_POLL_INTERVAL_MS = "obd_poll_interval_ms"
    const val KEY_OBD_SELECTED_PIDS = "obd_selected_pids"
    const val KEY_GENERATE_KMZ = "generate_kmz"
    const val KEY_SAVE_ODS = "save_ods"
    const val KEY_KMZ_LOCATION_URI = "kmz_location_uri"
    const val KEY_ODS_LOCATION_URI = "ods_location_uri"
    const val KEY_US_UNITS = "us_units"
    const val KEY_PLACE_NAME_LOOKUPS = "place_name_lookups"
    const val KEY_ARCHIVE_RETENTION_DAYS = "archive_retention_days"
    const val KEY_ARCHIVE_MIN_TRIP_SEC = "archive_min_trip_sec"
    const val KEY_DISCARD_BELOW_ENTRIES = "discard_below_entries"
    const val KEY_SHOW_HYBRID_CHARGE = "show_hybrid_charge"
    const val KEY_SHOW_FUEL_LEVEL = "show_fuel_level"
    const val KEY_SPEED_GAUGE_MAX_US = "speed_gauge_max_us"
    const val KEY_SPEED_GAUGE_MAX_METRIC = "speed_gauge_max_metric"
    const val KEY_RPM_GAUGE_MAX = "rpm_gauge_max"

    // Android's org.json only exposes object/array APIs, so scalars are
    // stored as the payload of a single-key object: {"v":<scalar>}.
    private const val WRAP = "v"

    fun enc(value: Any): String =
        JSONObject().put(WRAP, value).toString()
            .removePrefix("""{"$WRAP":""").removeSuffix("}")

    private fun dec(json: String): Any? =
        runCatching { JSONTokener("""{"$WRAP":$json}""").nextValue() }
            .getOrNull()
            ?.let { (it as? JSONObject)?.opt(WRAP) }
            .takeUnless { it === JSONObject.NULL }

    private fun decStr(json: String): String? = dec(json) as? String
    private fun decBool(json: String): Boolean? = dec(json) as? Boolean
    private fun decInt(json: String): Int? = (dec(json) as? Number)?.toInt()
    private fun decLong(json: String): Long? = (dec(json) as? Number)?.toLong()

    /** Full snapshot written to a fresh (empty) settings table. */
    fun encodeAll(s: AppSettings): Map<String, String> = mapOf(
        KEY_BLUETOOTH_DEVICE_NAME to enc(s.bluetoothDeviceName),
        KEY_LOGGING_FREQUENCY to enc(s.loggingFrequency),
        KEY_SPLIT_ENABLED to enc(s.splitEnabled),
        KEY_MAX_SEGMENT_SIZE_KB to enc(s.maxSegmentSizeKb),
        KEY_FORCE_LOGGING to enc(s.forceLogging),
        KEY_SERVICE_ENABLED to enc(s.serviceEnabled),
        KEY_STORAGE_LOCATION_URI to enc(s.storageLocationUri),
        KEY_GPS_ENABLED to enc(s.gpsEnabled),
        KEY_OBD_ENABLED to enc(s.obdEnabled),
        KEY_OBD_DEVICE_NAME to enc(s.obdDeviceName),
        KEY_OBD_POLL_INTERVAL_MS to enc(s.obdPollIntervalMs),
        KEY_OBD_SELECTED_PIDS to enc(s.obdSelectedPids.joinToString(",")),
        KEY_GENERATE_KMZ to enc(s.generateKmz),
        KEY_SAVE_ODS to enc(s.saveOds),
        KEY_KMZ_LOCATION_URI to enc(s.kmzLocationUri),
        KEY_ODS_LOCATION_URI to enc(s.odsLocationUri),
        KEY_US_UNITS to enc(s.usUnits),
        KEY_PLACE_NAME_LOOKUPS to enc(s.placeNameLookups),
        KEY_ARCHIVE_RETENTION_DAYS to enc(s.archiveRetentionDays),
        KEY_ARCHIVE_MIN_TRIP_SEC to enc(s.archiveMinTripSec),
        KEY_DISCARD_BELOW_ENTRIES to enc(s.discardBelowEntries),
        KEY_SHOW_HYBRID_CHARGE to enc(s.showHybridCharge),
        KEY_SHOW_FUEL_LEVEL to enc(s.showFuelLevel),
        KEY_SPEED_GAUGE_MAX_US to enc(s.speedGaugeMaxUs),
        KEY_SPEED_GAUGE_MAX_METRIC to enc(s.speedGaugeMaxMetric),
        KEY_RPM_GAUGE_MAX to enc(s.rpmGaugeMax),
    )

    /**
     * Database rows win over the DataStore mirror, field by field: missing
     * or unreadable rows keep the mirror value (forward/backward tolerant).
     * tempLocationUri never comes from the database.
     */
    fun mergeOnto(base: AppSettings, db: Map<String, String>): AppSettings {
        if (db.isEmpty()) return base
        fun str(key: String, def: String) = db[key]?.let(::decStr) ?: def
        fun bool(key: String, def: Boolean) = db[key]?.let(::decBool) ?: def
        fun int(key: String, def: Int) = db[key]?.let(::decInt) ?: def
        fun lng(key: String, def: Long) = db[key]?.let(::decLong) ?: def
        return base.copy(
            bluetoothDeviceName = str(KEY_BLUETOOTH_DEVICE_NAME, base.bluetoothDeviceName),
            loggingFrequency = int(KEY_LOGGING_FREQUENCY, base.loggingFrequency),
            splitEnabled = bool(KEY_SPLIT_ENABLED, base.splitEnabled),
            maxSegmentSizeKb = lng(KEY_MAX_SEGMENT_SIZE_KB, base.maxSegmentSizeKb),
            forceLogging = bool(KEY_FORCE_LOGGING, base.forceLogging),
            serviceEnabled = bool(KEY_SERVICE_ENABLED, base.serviceEnabled),
            storageLocationUri = str(KEY_STORAGE_LOCATION_URI, base.storageLocationUri),
            gpsEnabled = bool(KEY_GPS_ENABLED, base.gpsEnabled),
            obdEnabled = bool(KEY_OBD_ENABLED, base.obdEnabled),
            obdDeviceName = str(KEY_OBD_DEVICE_NAME, base.obdDeviceName),
            obdPollIntervalMs = int(KEY_OBD_POLL_INTERVAL_MS, base.obdPollIntervalMs),
            obdSelectedPids = db[KEY_OBD_SELECTED_PIDS]?.let(::decStr)
                ?.let(::sanitizePids) ?: base.obdSelectedPids,
            generateKmz = bool(KEY_GENERATE_KMZ, base.generateKmz),
            saveOds = bool(KEY_SAVE_ODS, base.saveOds),
            kmzLocationUri = str(KEY_KMZ_LOCATION_URI, base.kmzLocationUri),
            odsLocationUri = str(KEY_ODS_LOCATION_URI, base.odsLocationUri),
            usUnits = bool(KEY_US_UNITS, base.usUnits),
            placeNameLookups = bool(KEY_PLACE_NAME_LOOKUPS, base.placeNameLookups),
            archiveRetentionDays = int(KEY_ARCHIVE_RETENTION_DAYS, base.archiveRetentionDays),
            archiveMinTripSec = int(KEY_ARCHIVE_MIN_TRIP_SEC, base.archiveMinTripSec),
            discardBelowEntries = int(KEY_DISCARD_BELOW_ENTRIES, base.discardBelowEntries),
            showHybridCharge = bool(KEY_SHOW_HYBRID_CHARGE, base.showHybridCharge),
            showFuelLevel = bool(KEY_SHOW_FUEL_LEVEL, base.showFuelLevel),
            speedGaugeMaxUs = int(KEY_SPEED_GAUGE_MAX_US, base.speedGaugeMaxUs),
            speedGaugeMaxMetric = int(KEY_SPEED_GAUGE_MAX_METRIC, base.speedGaugeMaxMetric),
            rpmGaugeMax = int(KEY_RPM_GAUGE_MAX, base.rpmGaugeMax),
        )
    }
}

/** Settings table behind the [SettingsDb] cache seam; faked in JVM tests. */
internal interface SettingsKv {
    /** Identity for "already attached to this store" checks. */
    val id: Any
    fun loadAll(): Map<String, String>
    fun put(key: String, valueJson: String)
}

private class StoreSettingsKv(private val store: TripMetadataStore) : SettingsKv {
    override val id: Any get() = store
    override fun loadAll(): Map<String, String> = store.settingsAll()
    override fun put(key: String, valueJson: String) = store.settingPut(key, valueJson)
}

/**
 * Process cache of the journal database settings table (Stage C). The
 * repository merges [snapshot] over the DataStore mirror; writes land in
 * the cache synchronously (UI stays instant) and in the database
 * asynchronously. Before attachment, writes are kept pending and are
 * overlaid ONTO the table at attach, so a setting changed before the DB
 * opened is never reverted by the attach.
 *
 * Attachment seeds a fresh (empty) table from the current settings, which
 * covers the one-time import and every re-picked storage root. DataStore
 * remains the degraded-mode source when no journal database is open.
 */
internal object SettingsDb {

    private val lock = Any()
    private var values: Map<String, String> = emptyMap()
    private var pending: MutableMap<String, String> = mutableMapOf()
    private var kv: SettingsKv? = null

    /** Version counter; the settings flow re-emits whenever it moves. */
    private val versionFlow = MutableStateFlow(0L)
    val changes: Flow<Long> = versionFlow
    private fun bump() { versionFlow.update { it + 1 } }

    fun snapshot(): Map<String, String> = synchronized(lock) { values }

    /** Attach the settings table of the configured journal root, if any. */
    suspend fun ensureAttached(context: Context, current: AppSettings) {
        if (current.tempLocationUri.isEmpty()) return
        // The store factory opens the SQLite file; keep it off the caller's
        // (possibly main) dispatcher.
        val store = withContext(Dispatchers.IO) {
            Storage.metadataStore(context, current.tempLocationUri)
        } ?: return
        attachFor(store, current)
        ActivityLog.attachFor(store)
    }

    /** No-op when this exact store is already attached (root changes re-attach). */
    suspend fun attachFor(store: TripMetadataStore, current: AppSettings) {
        if (synchronized(lock) { kv?.id === store }) return
        attach(StoreSettingsKv(store), current)
    }

    /**
     * Read the table into the cache. Seeds it from [current] when empty;
     * local writes made before attachment win over stored rows. A failed
     * read leaves any previous attachment (and the mirror fallback) alone.
     */
    suspend fun attach(target: SettingsKv, current: AppSettings) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { target.loadAll() }.getOrNull()
        } ?: return
        val flushed = synchronized(lock) { pending.toMap() }
        // Fresh table: seed it from the current (merged) settings.
        val rows = if (loaded.isEmpty()) SettingsCodec.encodeAll(current) else loaded
        val toWrite = (if (loaded.isEmpty()) rows else emptyMap()) + flushed
        withContext(Dispatchers.IO) {
            toWrite.forEach { (k, v) -> runCatching { target.put(k, v) } }
        }
        // Handoff: everything still pending here was written while kv was
        // null and is NOT in the flushed snapshot; drain owns those rows.
        // Every put from now on sees the attached kv and writes itself.
        val late = synchronized(lock) {
            values = rows + flushed // writes made before attach win over rows
            pending.keys.removeAll(flushed.keys)
            val owned = pending.toMap()
            pending.keys.removeAll(owned.keys)
            kv = target
            owned
        }
        bump()
        if (late.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                late.forEach { (k, v) -> runCatching { target.put(k, v) } }
            }
        }
    }

    /** Cache now, database when attached. Called after the DataStore write. */
    suspend fun put(key: String, valueJson: String) {
        val store = synchronized(lock) {
            values = values + (key to valueJson)
            pending[key] = valueJson
            kv
        }
        bump()
        if (store != null) {
            withContext(Dispatchers.IO) { runCatching { store.put(key, valueJson) } }
        }
    }

    /** Test seam: return the cache to the pristine detached state. */
    internal fun resetForTests() {
        synchronized(lock) {
            values = emptyMap(); pending.clear(); kv = null
        }
        bump()
    }
}
