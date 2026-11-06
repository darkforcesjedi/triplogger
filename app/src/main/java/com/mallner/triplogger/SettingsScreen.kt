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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/**
 * Settings screen layout (progressive disclosure: child options appear only
 * when their parent toggle is on; every control applies immediately):
 *
 *  Log Triggers            -> trigger device pattern / picker
 *  GPS                     -> enable + interval
 *  OBD (ELM327 Compatible) -> enable + adapter (scan picker) + interval + PIDs
 *  Trip Log Settings       -> KMZ/ODS toggles + their output folders + temp folder
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, appSettingsRepository: AppSettingsRepository) {
    val appSettings by appSettingsRepository.appSettingsFlow.collectAsState(initial = AppSettings("", 10, 1024 * 1024, false, "", serviceEnabled = false))
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    var showTriggerDialog by remember { mutableStateOf(false) }
    var showAdapterDialog by remember { mutableStateOf(false) }
    var showPidDialog by remember { mutableStateOf(false) }

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
            bluetoothManager.adapter?.bondedDevices?.mapNotNull { it.name } ?: emptyList()
        } else {
            emptyList()
        }
    }

    @Composable
    fun pickFolder(update: suspend (String) -> Unit) =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                coroutineScope.launch { update(it.toString()) }
            }
        }
    val kmzPicker = pickFolder { appSettingsRepository.updateKmzLocationUri(it) }
    val odsPicker = pickFolder { appSettingsRepository.updateOdsLocationUri(it) }
    val tempPicker = pickFolder { appSettingsRepository.updateTempLocationUri(it) }

    Column(modifier = modifier
        .padding(16.dp)
        .verticalScroll(rememberScrollState())) {
        Text(text = "Settings", fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Spacer(modifier = Modifier.height(16.dp))

        // ------------------------------------------------------------- Triggers
        SectionHeader("Log Triggers")
        LocationField(
            label = "Bluetooth Device(s)",
            value = appSettings.bluetoothDeviceName.ifEmpty { "None set - logging never triggers" },
            isError = appSettings.bluetoothDeviceName.isEmpty(),
            onSelect = { showTriggerDialog = true }
        )

        // ------------------------------------------------------------------ GPS
        Spacer(modifier = Modifier.height(16.dp))
        SectionHeader("GPS")
        ToggleRow(
            label = "Use GPS to Log Location",
            checked = appSettings.gpsEnabled,
            onCheckedChange = { on ->
                coroutineScope.launch {
                    appSettingsRepository.updateGpsEnabled(on)
                    if (!on && appSettings.generateKmz) {
                        // KMZ tracks require GPS; keep the two consistent
                        appSettingsRepository.updateGenerateKmz(false)
                    }
                }
            }
        )
        if (appSettings.gpsEnabled) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = appSettings.loggingFrequency.toString(),
                onValueChange = { coroutineScope.launch { appSettingsRepository.updateLoggingFrequency(it.toIntOrNull() ?: 10) } },
                label = { Text("Poll Interval (seconds)") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            )
        }

        // -------------------------------------------------------------- OBD-II
        Spacer(modifier = Modifier.height(16.dp))
        SectionHeader("OBD (ELM327 Compatible)")
        ToggleRow(
            label = "Use OBD Adapter to Log Engine Data",
            checked = appSettings.obdEnabled,
            onCheckedChange = { on -> coroutineScope.launch { appSettingsRepository.updateObdEnabled(on) } }
        )
        if (appSettings.obdEnabled) {
            LocationField(
                label = "Adapter",
                value = appSettings.obdDeviceName.ifEmpty { "Not set - tap Select to scan" },
                isError = appSettings.obdDeviceName.isEmpty(),
                buttonLabel = "Scan",
                onSelect = { showAdapterDialog = true }
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = appSettings.obdPollIntervalMs.toString(),
                onValueChange = { coroutineScope.launch { appSettingsRepository.updateObdPollIntervalMs(it.toIntOrNull() ?: 1000) } },
                label = { Text("Poll Interval (milliseconds)") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = { showPidDialog = true }) {
                Text("Sensors (${appSettings.obdSelectedPids.size} selected)")
            }
        }

        // ----------------------------------------------------------- Trip logs
        Spacer(modifier = Modifier.height(16.dp))
        SectionHeader("Trip Log Settings")
        ToggleRow(
            label = "Generate Google Maps (KMZ) Logs",
            caption = if (!appSettings.gpsEnabled) "Requires GPS logging to be enabled" else null,
            enabled = appSettings.gpsEnabled,
            checked = appSettings.generateKmz,
            onCheckedChange = { on ->
                coroutineScope.launch {
                    appSettingsRepository.updateGenerateKmz(on)
                    if (on && appSettings.kmzLocationUri.isEmpty() && appSettings.storageLocationUri.isEmpty()) {
                        kmzPicker.launch(null)
                    }
                }
            }
        )
        ToggleRow(
            label = "Generate Spreadsheet (ODS) Logs",
            checked = appSettings.saveOds,
            onCheckedChange = { on ->
                coroutineScope.launch {
                    appSettingsRepository.updateSaveOds(on)
                    if (on && appSettings.odsLocationUri.isEmpty() && appSettings.storageLocationUri.isEmpty()) {
                        odsPicker.launch(null)
                    }
                }
            }
        )
        if (appSettings.generateKmz) {
            LocationField(
                label = "KMZ Output Location",
                value = effectiveLocation(appSettings.kmzLocationUri, appSettings.storageLocationUri),
                onSelect = { kmzPicker.launch(null) }
            )
        }
        if (appSettings.saveOds) {
            LocationField(
                label = "ODS Output Location",
                value = effectiveLocation(appSettings.odsLocationUri, appSettings.storageLocationUri),
                onSelect = { odsPicker.launch(null) }
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LocationField(
            label = "Temporary Storage Location",
            value = appSettings.tempLocationUri.let { if (it.isEmpty()) "Not set" else displayPath(it) },
            required = true,
            isError = appSettings.tempLocationUri.isEmpty(),
            onSelect = { tempPicker.launch(null) }
        )
        if (appSettings.tempLocationUri.isEmpty()) {
            Text(
                text = "Required: logging cannot be enabled until a temporary folder is chosen.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showTriggerDialog) {
        TriggerDeviceDialog(
            initial = appSettings.bluetoothDeviceName,
            pairedDevices = pairedDevices,
            onDismiss = { showTriggerDialog = false },
            onConfirm = { pattern ->
                coroutineScope.launch { appSettingsRepository.updateBluetoothDeviceName(pattern) }
                showTriggerDialog = false
            }
        )
    }

    if (showAdapterDialog) {
        AdapterScanDialog(
            pairedDevices = pairedDevices,
            onDismiss = { showAdapterDialog = false },
            onPick = { name ->
                coroutineScope.launch { appSettingsRepository.updateObdDeviceName(name) }
                showAdapterDialog = false
            }
        )
    }

    if (showPidDialog) {
        SensorPickerDialog(
            selected = appSettings.obdSelectedPids.toSet(),
            onDismiss = { showPidDialog = false },
            onConfirm = { pids ->
                coroutineScope.launch { appSettingsRepository.updateObdSelectedPids(pids.toList()) }
                showPidDialog = false
            }
        )
    }
}

// ------------------------------------------------------------------------------------------------
// Composables
// ------------------------------------------------------------------------------------------------

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    caption: String? = null,
    enabled: Boolean = true
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp)
            if (caption != null) {
                Text(caption, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

/** One-line setting: outlined frame with the current path (ellipsized) and an inline Select button. */
@Composable
private fun LocationField(
    label: String,
    value: String,
    onSelect: () -> Unit,
    buttonLabel: String = "Select",
    required: Boolean = false,
    isError: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .height(IntrinsicSize.Min)
            .border(
                1.dp,
                if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                MaterialTheme.shapes.extraSmall
            )
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp)
        ) {
            Text(
                text = if (required) "$label (required)" else label,
                fontSize = 12.sp,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        VerticalDivider(
            modifier = Modifier.height(32.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
        TextButton(onClick = onSelect, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(buttonLabel)
        }
    }
}

/** Trigger pattern editor: free text plus tap-to-add paired device names. */
@Composable
private fun TriggerDeviceDialog(
    initial: String,
    pairedDevices: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trigger Device(s)") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Name or names separated by |") },
                    supportingText = { Text("e.g. ford|car box|chev - starts logging when a matching Bluetooth device connects") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Tap a paired device to add it:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                pairedDevices.forEach { deviceName ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clickable { text = if (text.isBlank()) deviceName else "$text|$deviceName" }
                    ) {
                        Text(deviceName, fontSize = 15.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        if (text.contains(deviceName, ignoreCase = true)) {
                            Text("added", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (pairedDevices.isEmpty()) {
                    Text("No paired devices found", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Adapter chooser: opening it starts a Bluetooth discovery immediately and
 * lists devices as they are found (ELM327 clones usually cannot be paired, so
 * scanning - not the paired list - is the primary path). A manual name field
 * covers adapters that hide from discovery.
 */
@Composable
private fun AdapterScanDialog(
    pairedDevices: List<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val context = LocalContext.current
    val connector = remember { ObdDeviceConnector(context) }
    val discovered = remember { mutableStateOf(listOf<String>()) }
    val scanning = remember { mutableStateOf(true) }
    var manualName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val results = connector.scanUntil(
            predicate = { name -> !name.isNullOrBlank() },
            timeoutMs = ObdDeviceConnector.SCAN_TIMEOUT_MS,
            onDeviceFound = { device ->
                device.name?.let { name -> discovered.value = (discovered.value + name).distinct() }
            }
        )
        results.mapNotNull { it.name }.forEach { name -> discovered.value = (discovered.value + name).distinct() }
        scanning.value = false
    }

    val obdRanked = (discovered.value + pairedDevices).distinct()
        .sortedByDescending { connector.looksLikeObdAdapter(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OBD Adapter") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                if (scanning.value) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Scanning for nearby devices…", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                } else {
                    Text("Nearby devices (tap to select):", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    obdRanked.forEach { deviceName ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .clickable { onPick(deviceName) }
                        ) {
                            Text(deviceName, fontSize = 15.sp)
                            if (connector.looksLikeObdAdapter(deviceName)) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("OBD?", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (obdRanked.isEmpty() && !scanning.value) {
                        Text("Nothing found yet. Wake the adapter (ignition on) and scan again.", fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualName,
                        onValueChange = { manualName = it },
                        label = { Text("Or type the adapter name") },
                        singleLine = true,
                        trailingIcon = {
                            TextButton(onClick = { if (manualName.isNotBlank()) onPick(manualName.trim()) }, enabled = manualName.isNotBlank()) {
                                Text("Use")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Scrollable multi-select list of PIDs, most useful first (catalog order). */
@Composable
private fun SensorPickerDialog(
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val checked = remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sensors to log") },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                ObdPids.ALL.forEach { pid ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .clickable {
                                checked.value = if (pid.hex in checked.value) checked.value - pid.hex else checked.value + pid.hex
                            }
                    ) {
                        Checkbox(
                            checked = pid.hex in checked.value,
                            onCheckedChange = { on ->
                                checked.value = if (on) checked.value + pid.hex else checked.value - pid.hex
                            }
                        )
                        Text(
                            text = "${pid.name} (${pid.unit})",
                            modifier = Modifier.weight(1f),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(text = "0x${pid.hex}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(checked.value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ------------------------------------------------------------------------------------------------

private fun effectiveLocation(specific: String, fallback: String): String =
    displayPath(specific.ifEmpty { fallback })

private fun displayPath(uri: String): String = when {
    uri.isEmpty() -> "Not set (app storage)"
    else -> {
        val decoded = try {
            java.net.URLDecoder.decode(uri.substringAfterLast('/'), "UTF-8")
        } catch (_: Exception) {
            uri
        }
        decoded.removePrefix("primary:").removePrefix("raw:")
    }
}
