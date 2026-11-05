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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

private fun displayPath(uri: String): String = when {
    uri.isEmpty() -> "(default app storage)"
    else -> try {
        java.net.URLDecoder.decode(uri.substringAfterLast('/'), "UTF-8")
    } catch (_: Exception) {
        uri
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, appSettingsRepository: AppSettingsRepository) {
    val appSettings by appSettingsRepository.appSettingsFlow.collectAsState(initial = AppSettings("", 10, 1024 * 1024, false, "", serviceEnabled = false))
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    var expanded by remember { mutableStateOf(false) }
    var obdExpanded by remember { mutableStateOf(false) }
    var showPidDialog by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var discoveredDevices by remember { mutableStateOf<List<String>>(emptyList()) }

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

    val tempPicker = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            coroutineScope.launch { appSettingsRepository.updateTempLocationUri(it.toString()) }
        }
    }
    val kmzPicker = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            coroutineScope.launch { appSettingsRepository.updateKmzLocationUri(it.toString()) }
        }
    }
    val odsPicker = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            coroutineScope.launch { appSettingsRepository.updateOdsLocationUri(it.toString()) }
        }
    }
    val legacyPicker = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            coroutineScope.launch { appSettingsRepository.updateStorageLocationUri(it.toString()) }
        }
    }

    val obdConnector = remember { ObdDeviceConnector(context) }

    fun startObdScan() {
        scanning = true
        coroutineScope.launch {
            val found = obdConnector
                .scanUntil(predicate = { name: String? -> obdConnector.looksLikeObdAdapter(name) })
                .mapNotNull { device -> device.name }
                .distinct()
            discoveredDevices = found
            scanning = false
            if (found.size == 1) {
                appSettingsRepository.updateObdDeviceName(found.first())
            }
        }
    }

    Column(modifier = modifier
        .padding(16.dp)
        .verticalScroll(rememberScrollState())) {
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
            label = { Text("GPS Logging Frequency (seconds)") }
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = appSettings.maxLogFileSize.toString(),
            onValueChange = { coroutineScope.launch { appSettingsRepository.updateMaxLogFileSize(it.toLongOrNull() ?: 1024 * 1024) } },
            label = { Text("Max Log File Size (bytes)") }
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Folders",
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = displayPath(appSettings.tempLocationUri),
            onValueChange = {},
            label = { Text("Temp Files (pending → success/failed)") },
            readOnly = true
        )
        Button(onClick = { tempPicker.launch(null) }) {
            Text("Select Temp Folder")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = displayPath(if (appSettings.kmzLocationUri.isEmpty()) appSettings.storageLocationUri else appSettings.kmzLocationUri),
            onValueChange = {},
            label = { Text("KMZ Output") },
            readOnly = true
        )
        Button(onClick = { kmzPicker.launch(null) }) {
            Text("Select KMZ Folder")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = displayPath(if (appSettings.odsLocationUri.isEmpty()) appSettings.storageLocationUri else appSettings.odsLocationUri),
            onValueChange = {},
            label = { Text("Spreadsheet (ODS) Output") },
            readOnly = true
        )
        Button(onClick = { odsPicker.launch(null) }) {
            Text("Select ODS Folder")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = displayPath(appSettings.storageLocationUri),
            onValueChange = {},
            label = { Text("Default Folder (fallback)") },
            readOnly = true
        )
        Button(onClick = { legacyPicker.launch(null) }) {
            Text("Select Default Folder")
        }

        // -----------------------------------------------------------------------
        // OBD-II (ELM327) section
        // -----------------------------------------------------------------------
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "OBD-II (ELM327)",
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .weight(5f),
                text = "Log Engine Data"
            )
            Checkbox(
                checked = appSettings.obdEnabled,
                onCheckedChange = { coroutineScope.launch { appSettingsRepository.updateObdEnabled(it) } }
            )
        }

        if (appSettings.obdEnabled) {
            ExposedDropdownMenuBox(
                expanded = obdExpanded,
                onExpandedChange = { obdExpanded = !obdExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    readOnly = true,
                    value = appSettings.obdDeviceName,
                    onValueChange = {},
                    label = { Text(if (scanning) "Scanning…" else "OBD Adapter") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = obdExpanded) },
                )
                ExposedDropdownMenu(
                    expanded = obdExpanded,
                    onDismissRequest = { obdExpanded = false },
                ) {
                    discoveredDevices.forEach { deviceName ->
                        DropdownMenuItem(
                            text = { Text(deviceName) },
                            onClick = {
                                coroutineScope.launch { appSettingsRepository.updateObdDeviceName(deviceName) }
                                obdExpanded = false
                            }
                        )
                    }
                    pairedDevices.filter { obdConnector.looksLikeObdAdapter(it) }.forEach { deviceName ->
                        DropdownMenuItem(
                            text = { Text(deviceName) },
                            onClick = {
                                coroutineScope.launch { appSettingsRepository.updateObdDeviceName(deviceName) }
                                obdExpanded = false
                            }
                        )
                    }
                    pairedDevices.filter { !obdConnector.looksLikeObdAdapter(it) }.forEach { deviceName ->
                        DropdownMenuItem(
                            text = { Text(deviceName) },
                            onClick = {
                                coroutineScope.launch { appSettingsRepository.updateObdDeviceName(deviceName) }
                                obdExpanded = false
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (scanning) "Scanning for adapters…" else "Scan for OBD adapters") },
                        onClick = {
                            obdExpanded = false
                            if (!scanning) startObdScan()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = appSettings.obdPollIntervalMs.toString(),
                onValueChange = { coroutineScope.launch { appSettingsRepository.updateObdPollIntervalMs(it.toIntOrNull() ?: 1000) } },
                label = { Text("Poll Interval (milliseconds)") }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { showPidDialog = true }) {
                Text("Sensors (${appSettings.obdSelectedPids.size} selected)")
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Trip outputs (written when a trip completes)",
                style = MaterialTheme.typography.titleSmall
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(modifier = Modifier.align(Alignment.CenterVertically).weight(5f), text = "Generate KMZ (Google Earth track + speed plot)")
                Checkbox(checked = appSettings.generateKmz, onCheckedChange = { coroutineScope.launch { appSettingsRepository.updateGenerateKmz(it) } })
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(modifier = Modifier.align(Alignment.CenterVertically).weight(5f), text = "Save ODS spreadsheet (PIDs + GPS per row)")
                Checkbox(checked = appSettings.saveOds, onCheckedChange = { coroutineScope.launch { appSettingsRepository.updateSaveOds(it) } })
            }
        }

        if (showPidDialog) {
            val checked = remember { mutableStateOf(appSettings.obdSelectedPids.toSet()) }
            AlertDialog(
                onDismissRequest = { showPidDialog = false },
                title = { Text("Sensors to log") },
                text = {
                    Column {
                        ObdPids.ALL.forEach { pid ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = pid.hex in checked.value,
                                    onCheckedChange = { on ->
                                        checked.value =
                                            if (on) checked.value + pid.hex else checked.value - pid.hex
                                    }
                                )
                                Text(
                                    text = "${pid.name} (${pid.unit})",
                                    modifier = Modifier.weight(1f),
                                    fontSize = 14.sp
                                )
                                Text(text = "0x${pid.hex}", fontSize = 12.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        coroutineScope.launch { appSettingsRepository.updateObdSelectedPids(checked.value.toList()) }
                        showPidDialog = false
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showPidDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}
