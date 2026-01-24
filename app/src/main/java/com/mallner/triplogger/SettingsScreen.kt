package com.mallner.triplogger

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, appSettingsRepository: AppSettingsRepository) {
    val appSettings by appSettingsRepository.appSettingsFlow.collectAsState(initial = AppSettings("", 10, 1024 * 1024, false, "", serviceEnabled = false))
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    var expanded by remember { mutableStateOf(false) }

    val hasBluetoothPermission = remember {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    val pairedDevices = remember(hasBluetoothPermission) {
        if (hasBluetoothPermission) {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
            bluetoothAdapter?.bondedDevices?.map { it.name } ?: emptyList()
        } else {
            emptyList()
        }
    }

    val directoryPicker = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            coroutineScope.launch {
                appSettingsRepository.updateStorageLocationUri(it.toString())
            }
        }
    }

    Column(modifier = modifier.padding(16.dp)) {
        Text(
            text = "Settings",
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp
        )
        Spacer(modifier = Modifier.height(16.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                readOnly = true,
                value = appSettings.bluetoothDeviceName,
                onValueChange = {},
                label = { Text("Bluetooth Device Name") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                if (hasBluetoothPermission) {
                    if (pairedDevices.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No paired devices found") },
                            onClick = { expanded = false }
                        )
                    } else {
                        pairedDevices.forEach { deviceName ->
                            DropdownMenuItem(
                                text = { Text(deviceName) },
                                onClick = {
                                    coroutineScope.launch {
                                        appSettingsRepository.updateBluetoothDeviceName(deviceName)
                                    }
                                    expanded = false
                                }
                            )
                        }
                    }
                } else {
                    DropdownMenuItem(
                        text = { Text("Bluetooth permission not granted") },
                        onClick = { expanded = false }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = appSettings.loggingFrequency.toString(),
            onValueChange = { coroutineScope.launch { appSettingsRepository.updateLoggingFrequency(it.toIntOrNull() ?: 10) } },
            label = { Text("Logging Frequency (seconds)") }
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = appSettings.maxLogFileSize.toString(),
            onValueChange = { coroutineScope.launch { appSettingsRepository.updateMaxLogFileSize(it.toLongOrNull() ?: 1024 * 1024) } },
            label = { Text("Max Log File Size (bytes)") }
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = appSettings.storageLocationUri,
            onValueChange = {},
            label = { Text("Storage Location") },
            readOnly = true
        )
        Button(onClick = { directoryPicker.launch(null) }) {
            Text("Select Storage Location")
        }
    }
}
