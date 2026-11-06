package com.mallner.triplogger

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val bluetoothDeviceName: String,
    val loggingFrequency: Int,
    val splitEnabled: Boolean,
    val maxSegmentSizeKb: Long,
    val forceLogging: Boolean,
    val storageLocationUri: String,
    val serviceEnabled: Boolean,
    // OBD-II (ELM327)
    val gpsEnabled: Boolean = true,
    val obdEnabled: Boolean = false,
    val obdDeviceName: String = "",
    val obdPollIntervalMs: Int = 1000,
    val obdSelectedPids: List<String> = ObdPids.DEFAULTS,
    val generateKmz: Boolean = true,
    val saveOds: Boolean = false,
    /** Separate user-selectable folders (fall back to storageLocationUri / app storage) */
    val tempLocationUri: String = "",
    val kmzLocationUri: String = "",
    val odsLocationUri: String = "",
    /** Display/export units: true = US customary (mph, °F, psi), false = metric */
    val usUnits: Boolean = true,
    /** Delete archived trip journals older than N days (0 = keep forever) */
    val archiveRetentionDays: Int = 0,
    /** Delete archived trip journals shorter than N seconds (0 = keep all) */
    val archiveMinTripSec: Int = 0
)

object AppSettingsKeys {
    val BLUETOOTH_DEVICE_NAME = stringPreferencesKey("bluetooth_device_name")
    val LOGGING_FREQUENCY = intPreferencesKey("logging_frequency")
    val SPLIT_ENABLED = booleanPreferencesKey("split_enabled")
    val MAX_SEGMENT_SIZE_KB = longPreferencesKey("max_segment_size_kb")
    val FORCE_LOGGING = booleanPreferencesKey("force_logging")
    val SERVICE_ENABLED = booleanPreferencesKey("logging_service_enabled")
    val STORAGE_LOCATION_URI = stringPreferencesKey("storage_location_uri")
    val GPS_ENABLED = booleanPreferencesKey("gps_enabled")
    val OBD_ENABLED = booleanPreferencesKey("obd_enabled")
    val OBD_DEVICE_NAME = stringPreferencesKey("obd_device_name")
    val OBD_POLL_INTERVAL_MS = intPreferencesKey("obd_poll_interval_ms")
    val OBD_SELECTED_PIDS = stringPreferencesKey("obd_selected_pids")
    val GENERATE_KMZ = booleanPreferencesKey("generate_kmz")
    val SAVE_ODS = booleanPreferencesKey("save_ods")
    val TEMP_LOCATION_URI = stringPreferencesKey("temp_location_uri")
    val KMZ_LOCATION_URI = stringPreferencesKey("kmz_location_uri")
    val ODS_LOCATION_URI = stringPreferencesKey("ods_location_uri")
    val US_UNITS = booleanPreferencesKey("us_units")
    val ARCHIVE_RETENTION_DAYS = intPreferencesKey("archive_retention_days")
    val ARCHIVE_MIN_TRIP_SEC = intPreferencesKey("archive_min_trip_sec")
}

class AppSettingsRepository(private val context: Context) {

    val appSettingsFlow: Flow<AppSettings> = context.dataStore.data.map {
        mapAppSettings(it)
    }

    suspend fun updateBluetoothDeviceName(name: String) {
        context.dataStore.edit {
            it[AppSettingsKeys.BLUETOOTH_DEVICE_NAME] = name
        }
    }

    suspend fun updateLoggingFrequency(frequency: Int) {
        context.dataStore.edit {
            it[AppSettingsKeys.LOGGING_FREQUENCY] = frequency
        }
    }

    suspend fun updateSplitEnabled(enabled: Boolean) {
        context.dataStore.edit {
            it[AppSettingsKeys.SPLIT_ENABLED] = enabled
        }
    }

    suspend fun updateMaxSegmentSizeKb(sizeKb: Long) {
        context.dataStore.edit {
            it[AppSettingsKeys.MAX_SEGMENT_SIZE_KB] = sizeKb.coerceIn(16L, 1_048_576L)
        }
    }

    suspend fun updateForceLogging(force: Boolean) {
        context.dataStore.edit {
            it[AppSettingsKeys.FORCE_LOGGING] = force
        }
    }

    suspend fun updateStorageLocationUri(uri: String) {
        context.dataStore.edit {
            it[AppSettingsKeys.STORAGE_LOCATION_URI] = uri
        }
    }

    suspend fun updateServiceEnabled(enabled: Boolean) {
        context.dataStore.edit {
            it[AppSettingsKeys.SERVICE_ENABLED] = enabled
        }
    }

    suspend fun updateGpsEnabled(enabled: Boolean) {
        context.dataStore.edit {
            it[AppSettingsKeys.GPS_ENABLED] = enabled
        }
    }

    suspend fun updateObdEnabled(enabled: Boolean) {
        context.dataStore.edit {
            it[AppSettingsKeys.OBD_ENABLED] = enabled
        }
    }

    suspend fun updateObdDeviceName(name: String) {
        context.dataStore.edit {
            it[AppSettingsKeys.OBD_DEVICE_NAME] = name
        }
    }

    suspend fun updateObdPollIntervalMs(intervalMs: Int) {
        context.dataStore.edit {
            it[AppSettingsKeys.OBD_POLL_INTERVAL_MS] = intervalMs.coerceIn(250, 60_000)
        }
    }

    suspend fun updateObdSelectedPids(pids: List<String>) {
        context.dataStore.edit {
            it[AppSettingsKeys.OBD_SELECTED_PIDS] = pids.joinToString(",")
        }
    }

    suspend fun updateGenerateKmz(enabled: Boolean) {
        context.dataStore.edit {
            it[AppSettingsKeys.GENERATE_KMZ] = enabled
        }
    }

    suspend fun updateSaveOds(enabled: Boolean) {
        context.dataStore.edit {
            it[AppSettingsKeys.SAVE_ODS] = enabled
        }
    }

    suspend fun updateTempLocationUri(uri: String) {
        context.dataStore.edit {
            it[AppSettingsKeys.TEMP_LOCATION_URI] = uri
        }
    }

    suspend fun updateKmzLocationUri(uri: String) {
        context.dataStore.edit {
            it[AppSettingsKeys.KMZ_LOCATION_URI] = uri
        }
    }

    suspend fun updateOdsLocationUri(uri: String) {
        context.dataStore.edit {
            it[AppSettingsKeys.ODS_LOCATION_URI] = uri
        }
    }

    suspend fun updateUsUnits(us: Boolean) {
        context.dataStore.edit {
            it[AppSettingsKeys.US_UNITS] = us
        }
    }

    suspend fun updateArchiveRetentionDays(days: Int) {
        context.dataStore.edit {
            it[AppSettingsKeys.ARCHIVE_RETENTION_DAYS] = days.coerceIn(0, 3650)
        }
    }

    suspend fun updateArchiveMinTripSec(seconds: Int) {
        context.dataStore.edit {
            it[AppSettingsKeys.ARCHIVE_MIN_TRIP_SEC] = seconds.coerceIn(0, 86_400)
        }
    }

    private fun mapAppSettings(preferences: Preferences): AppSettings {
        val bluetoothDeviceName = preferences[AppSettingsKeys.BLUETOOTH_DEVICE_NAME] ?: ""
        val loggingFrequency = preferences[AppSettingsKeys.LOGGING_FREQUENCY] ?: 10
        val splitEnabled = preferences[AppSettingsKeys.SPLIT_ENABLED] ?: false
        val maxSegmentSizeKb = preferences[AppSettingsKeys.MAX_SEGMENT_SIZE_KB] ?: 10 * 1024L // 10 MB default
        val forceLogging = preferences[AppSettingsKeys.FORCE_LOGGING] ?: false
        val serviceEnabled = preferences[AppSettingsKeys.SERVICE_ENABLED] ?: false
        val storageLocationUri = preferences[AppSettingsKeys.STORAGE_LOCATION_URI] ?: ""
        val gpsEnabled = preferences[AppSettingsKeys.GPS_ENABLED] ?: true
        val obdEnabled = preferences[AppSettingsKeys.OBD_ENABLED] ?: false
        val obdDeviceName = preferences[AppSettingsKeys.OBD_DEVICE_NAME] ?: ""
        val obdPollIntervalMs = preferences[AppSettingsKeys.OBD_POLL_INTERVAL_MS] ?: 1000
        val obdSelectedPids = preferences[AppSettingsKeys.OBD_SELECTED_PIDS]
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: ObdPids.DEFAULTS
        val generateKmz = preferences[AppSettingsKeys.GENERATE_KMZ] ?: true
        val saveOds = preferences[AppSettingsKeys.SAVE_ODS] ?: false
        val tempLocationUri = preferences[AppSettingsKeys.TEMP_LOCATION_URI] ?: ""
        val kmzLocationUri = preferences[AppSettingsKeys.KMZ_LOCATION_URI] ?: ""
        val odsLocationUri = preferences[AppSettingsKeys.ODS_LOCATION_URI] ?: ""
        val usUnits = preferences[AppSettingsKeys.US_UNITS] ?: true
        val archiveRetentionDays = preferences[AppSettingsKeys.ARCHIVE_RETENTION_DAYS] ?: 0
        val archiveMinTripSec = preferences[AppSettingsKeys.ARCHIVE_MIN_TRIP_SEC] ?: 0
        return AppSettings(
            bluetoothDeviceName, loggingFrequency, splitEnabled, maxSegmentSizeKb, forceLogging, storageLocationUri, serviceEnabled,
            gpsEnabled, obdEnabled, obdDeviceName, obdPollIntervalMs, obdSelectedPids, generateKmz, saveOds,
            tempLocationUri, kmzLocationUri, odsLocationUri, usUnits, archiveRetentionDays, archiveMinTripSec
        )
    }
}