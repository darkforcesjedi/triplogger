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
    val maxLogFileSize: Long,
    val forceLogging: Boolean,
    val storageLocationUri: String,
    val serviceEnabled: Boolean
)

object AppSettingsKeys {
    val BLUETOOTH_DEVICE_NAME = stringPreferencesKey("bluetooth_device_name")
    val LOGGING_FREQUENCY = intPreferencesKey("logging_frequency")
    val MAX_LOG_FILE_SIZE = longPreferencesKey("max_log_file_size")
    val FORCE_LOGGING = booleanPreferencesKey("force_logging")
    val SERVICE_ENABLED = booleanPreferencesKey("logging_service_enabled")
    val STORAGE_LOCATION_URI = stringPreferencesKey("storage_location_uri")
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

    suspend fun updateMaxLogFileSize(size: Long) {
        context.dataStore.edit {
            it[AppSettingsKeys.MAX_LOG_FILE_SIZE] = size
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

    private fun mapAppSettings(preferences: Preferences): AppSettings {
        val bluetoothDeviceName = preferences[AppSettingsKeys.BLUETOOTH_DEVICE_NAME] ?: ""
        val loggingFrequency = preferences[AppSettingsKeys.LOGGING_FREQUENCY] ?: 10
        val maxLogFileSize = preferences[AppSettingsKeys.MAX_LOG_FILE_SIZE] ?: 1024 * 1024 // 1MB default
        val forceLogging = preferences[AppSettingsKeys.FORCE_LOGGING] ?: false
        val serviceEnabled = preferences[AppSettingsKeys.SERVICE_ENABLED] ?: false
        val storageLocationUri = preferences[AppSettingsKeys.STORAGE_LOCATION_URI] ?: ""
        return AppSettings(bluetoothDeviceName, loggingFrequency, maxLogFileSize, forceLogging, storageLocationUri, serviceEnabled)
    }
}