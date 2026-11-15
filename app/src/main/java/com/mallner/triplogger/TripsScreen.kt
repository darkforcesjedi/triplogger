package com.mallner.triplogger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    var restoreName by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        val loaded = repository.load()
        entries = loaded
        // An empty list with storage set is the corruption signature;
        // offer a restore only when a backup can actually fix it (the
        // repository checks the backup contains one of the folders).
        restoreName = if (loaded.isEmpty() && appSettings.tempLocationUri.isNotEmpty()) {
            repository.restoreOffer()
        } else {
            null
        }
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
            val offer = restoreName
            if (offer != null) {
                Text(stringResource(R.string.trips_db_damaged), color = MaterialTheme.colorScheme.error, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.trips_db_damaged_body, offer),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { confirmRestore = true }) {
                    Text(stringResource(R.string.trips_restore_action))
                }
            } else {
                Text(stringResource(R.string.trips_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
                    // Close the sheet right away: the SAF move plus a full list
                    // reload can take seconds, and a sheet left open over a
                    // discarded trip is confusing. Failure re-surfaces as a
                    // message once the reload lands.
                    message = null
                    detail = null
                    coroutineScope.launch {
                        repository.discardToFailed(trip)
                            .onFailure { message = it.message }
                        entries = repository.load()
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
                DialogActionButton(stringResource(R.string.trips_rename_apply)) {
                    coroutineScope.launch {
                        repository.setTitle(trip, text.trim().takeIf { t -> t.isNotEmpty() })
                        editingTitle = null
                        entries = repository.load()
                    }
                }
            },
            dismissButton = {
                DialogActionButton(stringResource(R.string.trips_rename_clear)) {
                    coroutineScope.launch {
                        repository.setTitle(trip, null)
                        editingTitle = null
                        entries = repository.load()
                    }
                }
            }
        )
    }

    if (confirmClearFailed) {
        AlertDialog(
            onDismissRequest = { confirmClearFailed = false },
            title = { Text(stringResource(R.string.trips_clear_failed)) },
            text = { Text(stringResource(R.string.trips_clear_failed_confirm)) },
            confirmButton = {
                DialogActionButton(stringResource(R.string.trips_clear_failed)) {
                    coroutineScope.launch {
                        repository.clearFailed()
                        confirmClearFailed = false
                        entries = repository.load()
                    }
                }
            },
            dismissButton = { DialogActionButton(stringResource(R.string.action_cancel)) { confirmClearFailed = false } }
        )
    }

    if (confirmRestore && restoreName != null) {
        val name = restoreName!!
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.trips_restore_title, name)) },
            text = { Text(stringResource(R.string.trips_restore_body)) },
            confirmButton = {
                DialogActionButton(stringResource(R.string.trips_restore_continue)) {
                    coroutineScope.launch {
                        val outcome = repository.restoreBackup(name)
                        confirmRestore = false
                        message = if (outcome.ok) {
                            context.getString(R.string.trips_restore_done, outcome.name ?: name)
                        } else {
                            context.getString(R.string.trips_restore_failed, outcome.error ?: "unknown")
                        }
                        refreshKey++
                    }
                }
            },
            dismissButton = { DialogActionButton(stringResource(R.string.action_cancel)) { confirmRestore = false } }
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
        // One layout for every trip, named or not:
        //   title (or italic placeholder)
        //   start time            duration:  <span>
        //   <distance> · <n> entries        [KMZ] [ODS]
        Column(modifier = Modifier.padding(12.dp)) {
            if (trip.title != null) {
                Text(trip.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            } else {
                Text(
                    stringResource(R.string.trips_unnamed),
                    fontSize = 15.sp,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(trip.startMs)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(
                        R.string.trips_tile_duration,
                        if (trip.durationMs > 0) AppNotificationManager.formatChipDuration(trip.durationMs) else "—"
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val stats = listOfNotNull(
                    trip.bestDistanceM?.let {
                        stringResource(
                            R.string.trips_distance_value,
                            "%.1f".format(UnitFormat.convert("km", it / 1000.0, usUnits).first),
                            stringResource(if (usUnits) R.string.unit_miles else R.string.unit_km)
                        )
                    },
                    trip.entries.takeIf { it > 0 }
                        ?.let { pluralStringResource(R.plurals.notification_entries, it, it) }
                ).joinToString(" · ").ifEmpty { "—" }
                Text(stats, fontSize = 12.sp)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripDetail(
    trip: TripEntry,
    usUnits: Boolean,
    busy: Boolean,
    onRename: () -> Unit,
    onRerun: () -> Unit,
    onDiscard: () -> Unit,
    onLookUp: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { TripsRepository(context) }
    var coords by remember(trip.name) {
        mutableStateOf<Pair<Pair<Double, Double>?, Pair<Double, Double>?>?>(null)
    }
    LaunchedEffect(trip.name) { coords = repository.endpoints(trip.name) }

    val timeFmt = SimpleDateFormat("MMM d, h:mm:ss a", Locale.getDefault())
    val tz = trip.tzAbbrev
    val hasPlaces = trip.placeStart != null || trip.placeEnd != null

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(trip.displayTitle, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onRename) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.trips_rename_title)) }
        }

        EndPointLine(
            label = stringResource(R.string.trips_start),
            place = trip.placeStart,
            coordinates = coords?.first?.let { formatCoordinates(it.first, it.second) },
            time = trip.startMs.takeIf { it > 0 }?.let { timeFmt.format(Date(it)) },
            tz = tz,
            showLookup = !hasPlaces,
            onLookUp = onLookUp,
            onOpenMap = coords?.first?.let { (lat, lon) -> { openInMaps(context, lat, lon) } }
        )
        EndPointLine(
            label = stringResource(R.string.trips_end),
            place = trip.placeEnd,
            coordinates = coords?.second?.let { formatCoordinates(it.first, it.second) },
            time = trip.endMs.takeIf { it > 0 }?.let { timeFmt.format(Date(it)) },
            tz = tz,
            showLookup = false,
            onLookUp = {},
            onOpenMap = coords?.second?.let { (lat, lon) -> { openInMaps(context, lat, lon) } }
        )

        Spacer(Modifier.height(16.dp))
        // The method behind the headline distance reads as an icon; the full
        // wordy labels would crowd the three-column stat row.
        val srcIcon = trip.distanceSrc
            ?.takeIf { trip.distanceSources.size > 1 }
            ?.let { distanceSourceIcon(it) }
        DetailStatRow(
            stringResource(R.string.trips_stat_distance), formatDistance(trip, usUnits),
            stringResource(R.string.trips_stat_duration), AppNotificationManager.formatChipDuration(trip.durationMs),
            stringResource(R.string.trips_stat_max), if (trip.maxSpeedMps.isNaN()) "—" else stringResource(
                R.string.trips_distance_value,
                "%.1f".format(UnitFormat.mpsToDisplay(trip.maxSpeedMps.toDouble(), usUnits)),
                stringResource(if (usUnits) R.string.unit_mph else R.string.unit_kph)
            ),
            leadingIcon1 = srcIcon
        )
        Spacer(Modifier.height(8.dp))
        DetailStatRow(
            stringResource(R.string.trips_stat_entries), trip.entries.toString(),
            stringResource(R.string.trips_stat_segments), if (trip.segments > 0) trip.segments.toString() else "—",
            stringResource(R.string.trips_stat_trigger), trip.trigger ?: "—"
        )

        // Every distance method that had data, hidden behind a link so the
        // headline stays the single best estimate (audit trail for expense reports).
        if (trip.distanceSources.size > 1) {
            var showAllDistances by remember { mutableStateOf(false) }
            TextButton(
                onClick = { showAllDistances = !showAllDistances },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    stringResource(R.string.trips_distance_all_toggle, trip.distanceSources.size),
                    fontSize = 13.sp
                )
            }
            if (showAllDistances) {
                trip.distanceSources.entries.sortedBy {
                    listOf(DistanceEstimators.SOURCE_ODO, DistanceEstimators.SOURCE_OBD,
                        DistanceEstimators.SOURCE_GPS_SPEED, DistanceEstimators.SOURCE_TRACK).indexOf(it.key)
                }.forEach { (source, meters) ->
                    val value = UnitFormat.convert("km", meters / 1000.0, usUnits).first
                    val tag = stringResource(distanceSourceLabel(source))
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            distanceSourceIcon(source),
                            contentDescription = tag,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tag,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(
                                R.string.trips_distance_value,
                                "%.1f".format(value),
                                stringResource(if (usUnits) R.string.unit_miles else R.string.unit_km)
                            ),
                            fontSize = 12.sp
                        )
                        if (source == trip.distanceSrc) {
                            Spacer(Modifier.width(6.dp))
                            Text("★", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

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
        trip.loggedWith?.let {
            Text(
                stringResource(R.string.trips_logged_with, it),
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

/**
 * Full-precision display: the shortest string that round-trips to the exact
 * stored double - never rounded, since even 1e-5 degrees is ~1 m.
 */
private fun formatCoordinates(lat: Double, lon: Double): String = "$lat, $lon"

/** Open the point in OpenStreetMap (app if installed, browser otherwise). */
private fun openInMaps(context: android.content.Context, lat: Double, lon: Double) {
    val uri = android.net.Uri.parse(
        "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=19/$lat/$lon"
    )
    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
        .onFailure { android.widget.Toast.makeText(context, R.string.trips_no_map_app, android.widget.Toast.LENGTH_SHORT).show() }
}

/**
 * One Start/End row: prominent place name (coordinates until a lookup ran),
 * subtle time + zone abbreviation beneath, optional Look-up button; the
 * place line taps through to OpenStreetMap.
 */
@Composable
private fun EndPointLine(
    label: String,
    place: String?,
    coordinates: String?,
    time: String?,
    tz: String?,
    showLookup: Boolean,
    onLookUp: () -> Unit,
    onOpenMap: (() -> Unit)?
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(48.dp)
            )
            val text = place ?: coordinates ?: "—"
            if (onOpenMap != null) {
                Text(
                    text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onOpenMap)
                )
            } else {
                Text(
                    text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            if (showLookup) {
                TextButton(onClick = onLookUp) { Text(stringResource(R.string.trips_lookup), fontSize = 12.sp) }
            }
        }
        Row(modifier = Modifier.padding(start = 48.dp)) {
            Text(
                listOfNotNull(time, tz).joinToString(" "),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DetailStatRow(
    label1: String,
    value1: String,
    label2: String,
    value2: String,
    label3: String,
    value3: String,
    leadingIcon1: Painter? = null
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Stat(label1, value1, Modifier.weight(1f), leadingIcon1)
        Stat(label2, value2, Modifier.weight(1f))
        Stat(label3, value3, Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, leadingIcon: Painter? = null) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (leadingIcon == null) {
            Text(value, fontSize = 14.sp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(4.dp))
                Text(value, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun formatDistance(trip: TripEntry, usUnits: Boolean): String {
    val meters = trip.bestDistanceM ?: return "—"
    val value = UnitFormat.convert("km", meters / 1000.0, usUnits).first
    val headline = stringResource(
        R.string.trips_distance_value,
        "%.1f".format(value),
        stringResource(if (usUnits) R.string.unit_miles else R.string.unit_km)
    )
    // The source rides as an icon in the stat row, not as a wordy suffix.
    return headline
}

/**
 * Source glyphs for the distance estimates (vendored Material Symbols: the
 * classic androidx icon library is frozen before these symbols shipped):
 * steering wheel = odometer, ring = wheel speed, satellite = GPS Doppler
 * speed, road pin = GPS track.
 */
@Composable
private fun distanceSourceIcon(source: String): Painter = painterResource(
    when (source) {
        DistanceEstimators.SOURCE_ODO -> R.drawable.ic_src_odo
        DistanceEstimators.SOURCE_OBD -> R.drawable.ic_src_obd
        DistanceEstimators.SOURCE_GPS_SPEED -> R.drawable.ic_src_gps_speed
        else -> R.drawable.ic_src_track
    }
)

@Composable
private fun distanceSourceLabel(source: String): Int = when (source) {
    DistanceEstimators.SOURCE_ODO -> R.string.trips_distance_src_odo
    DistanceEstimators.SOURCE_OBD -> R.string.trips_distance_src_obd
    DistanceEstimators.SOURCE_GPS_SPEED -> R.string.trips_distance_src_gps_speed
    else -> R.string.trips_distance_src_track
}
