package com.mallner.triplogger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Trips tab: every journal in the temp storage (archive / unfinished /
 * failed+discarded), newest first, built purely from trip.properties
 * metadata. Rows open a detail sheet with rename, re-run, discard and
 * place-name lookup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(modifier: Modifier = Modifier, appSettingsRepository: AppSettingsRepository) {
    val context = LocalContext.current
    val repository = remember { TripsRepository(context) }
    val coroutineScope = rememberCoroutineScope()
    val appSettings by appSettingsRepository.appSettingsFlow.collectAsState(initial = AppSettings("", 10, false, 10 * 1024, false, "", serviceEnabled = false))

    var entries by remember { mutableStateOf<List<TripEntry>?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<TripEntry?>(null) }
    var editingTitle by remember { mutableStateOf<TripEntry?>(null) }
    var confirmClearFailed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) } // trip name under action
    var message by remember { mutableStateOf<String?>(null) }
    var pendingOpen by remember { mutableStateOf(false) }
    var failedOpen by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        entries = repository.load()
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.trips_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { refreshKey++ }) { Text(stringResource(R.string.trips_refresh)) }
        }
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
        }

        val list = entries
        if (list == null) {
            Spacer(Modifier.height(48.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                CircularProgressIndicator()
            }
            return@Column
        }

        if (appSettings.tempLocationUri.isEmpty()) {
            Text(stringResource(R.string.trips_no_storage), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }

        // ------------------------------------------------------------ archive
        val archive = list.filter { it.section == TripSection.ARCHIVE }
        if (archive.isEmpty()) {
            Text(stringResource(R.string.trips_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        archive.groupBy { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it.startMs)) }
            .forEach { (day, trips) ->
                Text(day, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                trips.forEach { TripRow(it, appSettings.usUnits) { detail = it } }
            }

        // ------------------------------------------------------------ pending
        val pending = list.filter { it.section == TripSection.PENDING }
        if (pending.isNotEmpty()) {
            SectionHeader(
                stringResource(R.string.trips_unfinished, pending.size),
                "⚠", pendingOpen,
                { pendingOpen = !pendingOpen }, null
            )
            if (pendingOpen) pending.forEach { TripRow(it, appSettings.usUnits) { detail = it } }
        }

        // ------------------------------------------------------------- failed
        val failed = list.filter { it.section == TripSection.FAILED }
        if (failed.isNotEmpty()) {
            SectionHeader(
                stringResource(R.string.trips_failed_section, failed.size),
                "✕", failedOpen,
                { failedOpen = !failedOpen },
                { confirmClearFailed = true }
            )
            if (failedOpen) failed.forEach { TripRow(it, appSettings.usUnits) { detail = it } }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.trips_attribution),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // ------------------------------------------------------------- detail sheet
    detail?.let { trip ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { detail = null }, sheetState = sheetState) {
            TripDetail(
                trip = trip,
                usUnits = appSettings.usUnits,
                busy = busy == trip.name,
                lookupEnabled = true,
                onRename = { editingTitle = trip },
                onRerun = {
                    busy = trip.name; message = null
                    coroutineScope.launch {
                        repository.rerun(trip)
                            .onSuccess { message = null }
                            .onFailure { message = it.message }
                        busy = null
                        entries = repository.load()
                        detail = entries?.firstOrNull { it.name == trip.name } ?: detail
                    }
                },
                onDiscard = {
                    busy = trip.name; message = null
                    coroutineScope.launch {
                        repository.discardToFailed(trip)
                            .onFailure { message = it.message }
                        busy = null
                        entries = repository.load()
                        detail = null
                    }
                },
                onLookUp = {
                    busy = trip.name; message = null
                    coroutineScope.launch {
                        repository.lookUpPlaces(trip.name)
                            .onFailure { message = context.getString(R.string.trips_lookup_failed, it.message ?: "") }
                        busy = null
                        entries = repository.load()
                        detail = entries?.firstOrNull { it.name == trip.name }
                    }
                }
            )
        }
    }

    // ------------------------------------------------------------- dialogs
    editingTitle?.let { trip ->
        var text by remember { mutableStateOf(trip.title ?: "") }
        AlertDialog(
            onDismissRequest = { editingTitle = null },
            title = { Text(stringResource(R.string.trips_rename_title)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text(stringResource(R.string.trips_rename_hint)) }) },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        repository.setTitle(trip, text.trim().takeIf { t -> t.isNotEmpty() })
                        editingTitle = null
                        entries = repository.load()
                    }
                }) { Text(stringResource(R.string.trips_rename_apply)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        repository.setTitle(trip, null)
                        editingTitle = null
                        entries = repository.load()
                    }
                }) { Text(stringResource(R.string.trips_rename_clear)) }
            }
        )
    }

    if (confirmClearFailed) {
        AlertDialog(
            onDismissRequest = { confirmClearFailed = false },
            title = { Text(stringResource(R.string.trips_clear_failed)) },
            text = { Text(stringResource(R.string.trips_clear_failed_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        repository.clearFailed()
                        confirmClearFailed = false
                        entries = repository.load()
                    }
                }) { Text(stringResource(R.string.trips_clear_failed)) }
            },
            dismissButton = { TextButton(onClick = { confirmClearFailed = false }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
}

@Composable
private fun SectionHeader(label: String, glyph: String, expanded: Boolean, onToggle: () -> Unit, action: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(top = 16.dp, bottom = 4.dp)
    ) {
        Text("$glyph $label", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        if (action != null) {
            TextButton(onClick = action) { Text(stringResource(R.string.trips_clear_failed), fontSize = 13.sp) }
        }
        Text(if (expanded) "▾" else "▸", fontSize = 14.sp)
    }
}

@Composable
private fun TripRow(trip: TripEntry, usUnits: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(trip.displayTitle, fontSize = 15.sp, fontWeight = if (trip.title != null) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                val stats = listOfNotNull(
                    if (trip.distanceM.isNaN()) null else stringResource(
                        R.string.trips_distance_value,
                        "%.1f".format(UnitFormat.convert("km", trip.distanceM / 1000.0, usUnits).first),
                        stringResource(if (usUnits) R.string.unit_miles else R.string.unit_km)
                    ),
                    trip.entries.takeIf { it > 0 }
                        ?.let { pluralStringResource(R.plurals.notification_entries, it, it) }
                ).joinToString(" · ").ifEmpty { "—" }
                Text(
                    stats,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (trip.title != null) {
                    Text(
                        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(trip.startMs)),
                        fontSize = 13.sp
                    )
                }
                if (trip.durationMs > 0) {
                    Text(
                        AppNotificationManager.formatChipDuration(trip.durationMs),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    if (trip.kmzDay != null) Badge("KMZ")
                    if (trip.odsFile != null) Badge("ODS")
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun TripDetail(
    trip: TripEntry,
    usUnits: Boolean,
    busy: Boolean,
    lookupEnabled: Boolean,
    onRename: () -> Unit,
    onRerun: () -> Unit,
    onDiscard: () -> Unit,
    onLookUp: () -> Unit
) {
    val timeFmt = SimpleDateFormat("MMM d, h:mm:ss a", Locale.getDefault())
    val tz = trip.tzName ?: trip.tzId
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(trip.displayTitle, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onRename) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.trips_rename_title)) }
        }

        PlaceLine(
            stringResource(R.string.trips_start),
            trip.placeStart,
            listOf(trip.startMs),
            timeFmt, tz,
            trip.placeEnd == null && lookupEnabled // no places at all -> offer lookup on start line
        ) { onLookUp() }
        if (trip.placeStart != null || trip.placeEnd != null) {
            PlaceLine(stringResource(R.string.trips_end), trip.placeEnd, listOf(trip.endMs), timeFmt, tz, false) {}
        }
        if (tz != null && trip.placeStart == null) {
            Text(tz, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(16.dp))
        DetailStatRow(
            stringResource(R.string.trips_stat_distance), formatDistance(trip, usUnits),
            stringResource(R.string.trips_stat_duration), AppNotificationManager.formatChipDuration(trip.durationMs),
            stringResource(R.string.trips_stat_max), if (trip.maxSpeedMps.isNaN()) "—" else stringResource(
                R.string.trips_distance_value,
                "%.1f".format(UnitFormat.mpsToDisplay(trip.maxSpeedMps.toDouble(), usUnits)),
                stringResource(if (usUnits) R.string.unit_mph else R.string.unit_kph)
            )
        )
        Spacer(Modifier.height(8.dp))
        DetailStatRow(
            stringResource(R.string.trips_stat_entries), trip.entries.toString(),
            stringResource(R.string.trips_stat_segments), if (trip.segments > 0) trip.segments.toString() else "—",
            stringResource(R.string.trips_stat_trigger), trip.trigger ?: "—"
        )

        trip.statusReason?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }

        if (trip.kmzDay != null || trip.odsFile != null) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.trips_outputs), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            val outputs = listOfNotNull(trip.kmzDay?.let { "$it.kmz" }, trip.odsFile)
            Text(outputs.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trip.appVersion > 0) {
            Text(
                stringResource(R.string.trips_logged_with, trip.appVersion),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onRerun, enabled = !busy) {
                Text(stringResource(R.string.trips_rerun))
            }
            if (trip.section != TripSection.FAILED) {
                OutlinedButton(onClick = onDiscard, enabled = !busy) {
                    Text(stringResource(R.string.trips_discard))
                }
            }
            if (busy) CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
        }
    }
}

@Composable
private fun PlaceLine(label: String, place: String?, times: List<Long>, fmt: SimpleDateFormat, tz: String?, showLookup: Boolean, onLookUp: () -> Unit) {
    val time = times.firstOrNull()?.takeIf { it > 0 }?.let { fmt.format(Date(it)) } ?: "—"
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$label  ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(place ?: time, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
            if (showLookup) {
                TextButton(onClick = onLookUp) { Text(stringResource(R.string.trips_lookup), fontSize = 12.sp) }
            }
        }
        if (place != null) {
            Text(time + (tz?.let { " · $it" } ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailStatRow(label1: String, value1: String, label2: String, value2: String, label3: String, value3: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Stat(label1, value1, Modifier.weight(1f))
        Stat(label2, value2, Modifier.weight(1f))
        Stat(label3, value3, Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp)
    }
}

@Composable
private fun formatDistance(trip: TripEntry, usUnits: Boolean): String {
    if (trip.distanceM.isNaN()) return "—"
    val value = UnitFormat.convert("km", trip.distanceM / 1000.0, usUnits).first
    return stringResource(R.string.trips_distance_value, "%.1f".format(value), stringResource(if (usUnits) R.string.unit_miles else R.string.unit_km))
}
