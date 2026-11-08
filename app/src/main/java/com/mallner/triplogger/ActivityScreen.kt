package com.mallner.triplogger

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity tab: the rolling logger event history (what actually happened and
 * when), newest first, plus a share-diagnostics action that attaches the log
 * for bug reports.
 */
@Composable
fun ActivityScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val events by ActivityLog.recent.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.activity_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    val intent: Intent = ActivityLog.shareIntent(context)
                    context.startActivity(intent)
                },
                enabled = events.isNotEmpty()
            ) { Text(stringResource(R.string.activity_share)) }
        }
        if (events.isEmpty()) {
            Spacer(Modifier.width(0.dp))
            Text(
                stringResource(R.string.activity_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(events, key = { "${it.timeMs}-${it.event}-${it.message}" }) { event ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors()
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Column(modifier = Modifier.width(110.dp)) {
                            Text(
                                SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(event.timeMs)),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.timeMs)),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(eventLabel(event.event), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            if (event.message.isNotEmpty()) {
                                Text(event.message, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (event.fields.isNotEmpty()) {
                                Text(
                                    event.fields.entries.joinToString("  ") { "${it.key}=${it.value}" },
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun eventLabel(event: String): String {
    val id = when (event) {
        "trip_started" -> R.string.activity_event_trip_started
        "trip_ended" -> R.string.activity_event_trip_ended
        "trip_converted" -> R.string.activity_event_trip_converted
        "trip_reconverted" -> R.string.activity_event_trip_reconverted
        "trip_recovered" -> R.string.activity_event_trip_recovered
        "trip_discarded" -> R.string.activity_event_trip_discarded
        "trip_discarded_manual" -> R.string.activity_event_trip_discarded_manual
        "trip_failed" -> R.string.activity_event_trip_failed
        "trip_renamed" -> R.string.activity_event_trip_renamed
        "places_resolved" -> R.string.activity_event_places_resolved
        "places_lookup_all" -> R.string.activity_event_places_lookup_all
        "obd_connected" -> R.string.activity_event_obd_connected
        "obd_lost" -> R.string.activity_event_obd_lost
        "obd_timeout" -> R.string.activity_event_obd_timeout
        "journal_split" -> R.string.activity_event_journal_split
        "rebuild_run" -> R.string.activity_event_rebuild_run
        "failed_cleared" -> R.string.activity_event_failed_cleared
        else -> null
    }
    return id?.let { stringResource(it) } ?: event
}
