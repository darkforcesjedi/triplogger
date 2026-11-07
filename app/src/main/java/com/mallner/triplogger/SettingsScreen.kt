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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    val appSettings by appSettingsRepository.appSettingsFlow.collectAsState(initial = AppSettings("", 10, false, 10 * 1024, false, "", serviceEnabled = false))
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
        Text(text = stringResource(R.string.settings_title), fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Spacer(modifier = Modifier.height(16.dp))

        // ------------------------------------------------------------- Triggers
        SectionHeader(stringResource(R.string.settings_section_triggers))
        LocationField(
            label = stringResource(R.string.settings_trigger_label),
            value = appSettings.bluetoothDeviceName.ifEmpty { stringResource(R.string.settings_trigger_none) },
            isError = appSettings.bluetoothDeviceName.isEmpty(),
            onSelect = { showTriggerDialog = true }
        )

        // ------------------------------------------------------------------ GPS
        Spacer(modifier = Modifier.height(16.dp))
        SectionHeader(stringResource(R.string.settings_section_gps))
        ToggleRow(
            label = stringResource(R.string.settings_gps_use),
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
                label = { Text(stringResource(R.string.settings_gps_interval)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            )
        }

        // -------------------------------------------------------------- OBD-II
        Spacer(modifier = Modifier.height(16.dp))
        SectionHeader(stringResource(R.string.settings_section_obd))
        ToggleRow(
            label = stringResource(R.string.settings_obd_use),
            checked = appSettings.obdEnabled,
            onCheckedChange = { on -> coroutineScope.launch { appSettingsRepository.updateObdEnabled(on) } }
        )
        if (appSettings.obdEnabled) {
            LocationField(
                label = stringResource(R.string.settings_obd_adapter),
                value = appSettings.obdDeviceName.ifEmpty { stringResource(R.string.settings_obd_adapter_none) },
                isError = appSettings.obdDeviceName.isEmpty(),
                buttonLabel = stringResource(R.string.action_scan),
                onSelect = { showAdapterDialog = true }
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = appSettings.obdPollIntervalMs.toString(),
                onValueChange = { coroutineScope.launch { appSettingsRepository.updateObdPollIntervalMs(it.toIntOrNull() ?: 1000) } },
                label = { Text(stringResource(R.string.settings_obd_interval)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = { showPidDialog = true }) {
                Text(stringResource(R.string.settings_sensors_button, appSettings.obdSelectedPids.size))
            }
            ToggleRow(
                label = stringResource(R.string.obd_show_hybrid_charge),
                checked = appSettings.showHybridCharge,
                onCheckedChange = { on -> coroutineScope.launch { appSettingsRepository.updateShowHybridCharge(on) } }
            )
            ToggleRow(
                label = stringResource(R.string.obd_show_fuel_level),
                checked = appSettings.showFuelLevel,
                onCheckedChange = { on -> coroutineScope.launch { appSettingsRepository.updateShowFuelLevel(on) } }
            )
            SectionHeader(stringResource(R.string.settings_section_gauges))
            val speedUnit = stringResource(if (appSettings.usUnits) R.string.unit_mph else R.string.unit_kph)
            GaugeSliderRow(
                label = stringResource(R.string.settings_speed_gauge_max, speedUnit),
                value = if (appSettings.usUnits) appSettings.speedGaugeMaxUs else appSettings.speedGaugeMaxMetric,
                valueRange = if (appSettings.usUnits) 10..200 else 10..350,
                step = 5,
                suffix = speedUnit,
                onValueChange = { v -> coroutineScope.launch { appSettingsRepository.updateSpeedGaugeMax(appSettings.usUnits, v) } }
            )
            GaugeSliderRow(
                label = stringResource(R.string.settings_rpm_gauge_max),
                value = appSettings.rpmGaugeMax,
                valueRange = 1000..15000,
                step = 500,
                suffix = "rpm",
                onValueChange = { v -> coroutineScope.launch { appSettingsRepository.updateRpmGaugeMax(v) } }
            )
        }

        // ---------------------------------------------------------------- Units
        Spacer(modifier = Modifier.height(16.dp))
        SectionHeader(stringResource(R.string.settings_section_units))
        UnitOption(
            label = stringResource(R.string.settings_units_us),
            selected = appSettings.usUnits,
            onSelect = { coroutineScope.launch { appSettingsRepository.updateUsUnits(true) } }
        )
        UnitOption(
            label = stringResource(R.string.settings_units_metric),
            selected = !appSettings.usUnits,
            onSelect = { coroutineScope.launch { appSettingsRepository.updateUsUnits(false) } }
        )

        // ----------------------------------------------------------- Trip logs
        Spacer(modifier = Modifier.height(16.dp))
        SectionHeader(stringResource(R.string.settings_section_logs))
        ToggleRow(
            label = stringResource(R.string.settings_kmz_toggle),
            caption = if (!appSettings.gpsEnabled) stringResource(R.string.settings_kmz_needs_gps) else null,
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
            label = stringResource(R.string.settings_ods_toggle),
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
                label = stringResource(R.string.settings_kmz_location),
                value = effectiveLocation(context, appSettings.kmzLocationUri, appSettings.storageLocationUri),
                onSelect = { kmzPicker.launch(null) }
            )
        }
        if (appSettings.saveOds) {
            LocationField(
                label = stringResource(R.string.settings_ods_location),
                value = effectiveLocation(context, appSettings.odsLocationUri, appSettings.storageLocationUri),
                onSelect = { odsPicker.launch(null) }
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LocationField(
            label = stringResource(R.string.settings_temp_location),
            value = appSettings.tempLocationUri.let { if (it.isEmpty()) stringResource(R.string.not_set) else displayPath(context, it) },
            required = true,
            isError = appSettings.tempLocationUri.isEmpty(),
            onSelect = { tempPicker.launch(null) }
        )
        if (appSettings.tempLocationUri.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_temp_required_error),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        ToggleRow(
            label = stringResource(R.string.settings_split_toggle),
            caption = stringResource(R.string.settings_split_caption),
            checked = appSettings.splitEnabled,
            onCheckedChange = { on -> coroutineScope.launch { appSettingsRepository.updateSplitEnabled(on) } }
        )
        if (appSettings.splitEnabled) {
            MaxSegmentSizeField(
                initialMb = appSettings.maxSegmentSizeKb / 1024.0,
                onApply = { mb ->
                    coroutineScope.launch { appSettingsRepository.updateMaxSegmentSizeKb((mb * 1024).toLong()) }
                }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        NumberField(
            value = appSettings.archiveRetentionDays,
            label = stringResource(R.string.settings_archive_retention),
            supporting = stringResource(R.string.settings_archive_retention_support),
            onApply = { coroutineScope.launch { appSettingsRepository.updateArchiveRetentionDays(it) } }
        )
        NumberField(
            value = appSettings.archiveMinTripSec,
            label = stringResource(R.string.settings_min_trip_length),
            supporting = stringResource(R.string.settings_min_trip_length_support),
            onApply = { coroutineScope.launch { appSettingsRepository.updateArchiveMinTripSec(it) } }
        )
        NumberField(
            value = appSettings.discardBelowEntries,
            label = stringResource(R.string.settings_min_trip_entries),
            supporting = stringResource(R.string.settings_min_trip_entries_support),
            onApply = { coroutineScope.launch { appSettingsRepository.updateDiscardBelowEntries(it) } }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            enabled = appSettings.tempLocationUri.isNotEmpty() &&
                (appSettings.generateKmz || appSettings.saveOds),
            onClick = {
                context.startService(
                    Intent(context, DrivingService::class.java).apply { action = DrivingService.ACTION_REBUILD_LOGS }
                )
            }
        ) {
            Text(stringResource(R.string.settings_rebuild))
        }
        Text(
            text = stringResource(R.string.settings_rebuild_caption),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
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

/** Slider for an integer gauge maximum, snapped to `step`; value shown at the end. */
@Composable
private fun GaugeSliderRow(
    label: String,
    value: Int,
    valueRange: IntRange,
    step: Int,
    suffix: String,
    onValueChange: (Int) -> Unit
) {
    val steps = ((valueRange.last - valueRange.first) / step - 1).coerceAtLeast(0)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, fontSize = 16.sp)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "$value $suffix",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value.toFloat().coerceIn(valueRange.first.toFloat(), valueRange.last.toFloat()),
            onValueChange = { raw -> onValueChange((raw / step).roundToInt() * step) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = steps
        )
    }
}

/** Single-select unit system row (radio + label). */
@Composable
private fun UnitOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onSelect)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, fontSize = 16.sp)
    }
}

/** Integer setting with immediate apply; 0 and blank both mean "off". */
@Composable
private fun NumberField(
    value: Int,
    label: String,
    supporting: String,
    onApply: (Int) -> Unit
) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) { if (text.toIntOrNull() != value) text = value.toString() }
    OutlinedTextField(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() }
            text.toIntOrNull()?.let { onApply(it) }
        },
        label = { Text(label) },
        supportingText = { Text(supporting) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
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
    buttonLabel: String = stringResource(R.string.action_select),
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
                text = if (required) stringResource(R.string.location_field_required, label) else label,
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

/**
 * Max segment size editor: free-form local text (so decimals can be typed),
 * applied as soon as it parses to a positive number of megabytes.
 */
@Composable
private fun MaxSegmentSizeField(initialMb: Double, onApply: (Double) -> Unit) {
    var text by remember { mutableStateOf(if (initialMb % 1.0 == 0.0) initialMb.toLong().toString() else initialMb.toString()) }
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = text,
        onValueChange = { input ->
            text = input
            val mb = input.trim().toDoubleOrNull()
            if (mb != null && mb > 0) onApply(mb)
        },
        label = { Text(stringResource(R.string.settings_max_size)) },
        supportingText = { Text(stringResource(R.string.settings_max_size_support)) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
    )
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
        title = { Text(stringResource(R.string.trigger_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.trigger_dialog_field)) },
                    supportingText = { Text(stringResource(R.string.trigger_dialog_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.trigger_dialog_tap_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text(stringResource(R.string.trigger_dialog_added), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (pairedDevices.isEmpty()) {
                    Text(stringResource(R.string.trigger_dialog_no_paired), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text(stringResource(R.string.action_set)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
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
        title = { Text(stringResource(R.string.adapter_dialog_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                if (scanning.value) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.adapter_scanning), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                } else {
                    Text(stringResource(R.string.adapter_nearby), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text(stringResource(R.string.adapter_obd_guess), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (obdRanked.isEmpty() && !scanning.value) {
                        Text(stringResource(R.string.adapter_nothing_found), fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualName,
                        onValueChange = { manualName = it },
                        label = { Text(stringResource(R.string.adapter_manual_entry)) },
                        singleLine = true,
                        trailingIcon = {
                            TextButton(onClick = { if (manualName.isNotBlank()) onPick(manualName.trim()) }, enabled = manualName.isNotBlank()) {
                                Text(stringResource(R.string.action_use))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
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
        title = { Text(stringResource(R.string.sensors_dialog_title)) },
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
        confirmButton = { TextButton(onClick = { onConfirm(checked.value) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

// ------------------------------------------------------------------------------------------------

private fun effectiveLocation(context: android.content.Context, specific: String, fallback: String): String =
    displayPath(context, specific.ifEmpty { fallback })

private fun displayPath(context: android.content.Context, uri: String): String = when {
    uri.isEmpty() -> context.getString(R.string.location_not_set_app_storage)
    else -> {
        val decoded = try {
            java.net.URLDecoder.decode(uri.substringAfterLast('/'), "UTF-8")
        } catch (_: Exception) {
            uri
        }
        decoded.removePrefix("primary:").removePrefix("raw:")
    }
}
