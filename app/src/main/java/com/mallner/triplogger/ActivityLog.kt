package com.mallner.triplogger

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Rolling activity log ("Activity" tab): what the logger actually did.
 *
 * Storage is the journal database `activity` table (Stage C). Until a
 * database is attached (boot, no journal chosen yet, corrupt file) events
 * spool to JSONL in app-private storage; on attach the spool is imported
 * and the database becomes the only source, capped at [DB_MAX_ROWS].
 *
 * Every event: timestamp, event name, message, optional fields. Written
 * from arbitrary threads (service loops, finalizer, UI actions); database
 * writes queue on a single-worker scope so they keep event order.
 */
object ActivityLog {

    /** In-memory window / tab page size. */
    const val MAX_EVENTS = 500

    /** Database table cap. */
    const val DB_MAX_ROWS = 10_000

    data class Event(
        val timeMs: Long,
        val event: String,
        val message: String,
        val fields: Map<String, String> = emptyMap()
    )

    // Trip ids arrive in message/field text in two historical shapes: the
    // journal folder name "20261106_221901" and the display label
    // "2026-11-06 22.19.01". Both are rewritten to one readable form at read
    // time so old entries render consistently too.
    private val labelTimestamps = Regex("""\b(\d{4})-(\d{2})-(\d{2}) (\d{2})\.(\d{2})\.(\d{2})\b""")
    private val folderTimestamps = Regex("""(?<=\D|^)(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})(?=\D|$)""")
    private val folderToken = Regex("""\b\d{8}_\d{6}\b""")

    internal fun normalizeTimestamps(text: String): String {
        var out = labelTimestamps.replace(text) { m ->
            "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]} ${m.groupValues[4]}:${m.groupValues[5]}:${m.groupValues[6]}"
        }
        out = folderTimestamps.replace(out) { m ->
            "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]} ${m.groupValues[4]}:${m.groupValues[5]}:${m.groupValues[6]}"
        }
        return out
    }

    /**
     * Pure: parse one JSONL spool line; null for garbage. Normalization is
     * a display concern: [normalize]=false keeps the stored/imported text
     * raw, so trip folder tokens stay findable in the database.
     */
    internal fun parseLine(line: String, normalize: Boolean = true): Event? = try {
        val o = JSONObject(line)
        val fields = o.keys().asSequence()
            .filter { it != "t" && it != "e" && it != "m" }
            .associateWith { o.get(it).toString() }
            .let { if (normalize) it.mapValues { (_, v) -> normalizeTimestamps(v) } else it }
        val message = o.optString("m", "")
        Event(o.getLong("t"), o.getString("e"), if (normalize) normalizeTimestamps(message) else message, fields)
    } catch (_: Exception) {
        null
    }

    internal fun rowToEvent(row: TripMetadataStore.ActivityRow): Event = Event(
        timeMs = row.ts,
        event = row.event,
        message = normalizeTimestamps(row.message ?: ""),
        fields = row.fields?.let { json ->
            runCatching {
                val o = JSONObject(json)
                o.keys().asSequence().associateWith { normalizeTimestamps(o.get(it).toString()) }
            }.getOrNull()
        } ?: emptyMap()
    )

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    private var file: File? = null
    @Volatile private var store: TripMetadataStore? = null
    private val events = MutableStateFlow<List<Event>>(emptyList())
    val recent: StateFlow<List<Event>> = events.asStateFlow()

    /** Single worker keeps database insert order equal to event order. */
    private val dbScope = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor { r -> Thread(r, "ActivityLogDb") }.asCoroutineDispatcher()
    )
    private var writesSinceTrim = 0

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "activity-log.jsonl")
        file = f
        if (store == null) events.value = readSpool(f)
    }

    /**
     * Route events to the journal database. The first attachment imports
     * the JSONL spool (covering boot and pre-journal events), refreshes
     * the in-memory window from the table, and deletes the spool.
     */
    @Synchronized
    fun attachFor(store: TripMetadataStore) {
        if (this.store === store) return
        val first = this.store == null
        this.store = store
        dbScope.launch {
            runCatching {
                if (first) {
                    file?.takeIf { it.isFile }?.let { f ->
                        // Raw parse: the database stores exactly what happened.
                        f.readLines()
                            .mapNotNull { parseLine(it, normalize = false) }
                            .asReversed()
                            .forEach { insert(store, it) }
                        f.delete()
                    }
                }
                events.value = store.recentActivity(MAX_EVENTS).map { rowToEvent(it) }
                store.trimActivity(DB_MAX_ROWS)
                writesSinceTrim = 0
            }.onFailure { Log.w("ActivityLog", "Activity database attach failed", it) }
        }
    }

    /** Detach (restore flow replaced the database file underneath us). */
    @Synchronized
    fun clearAttachment() {
        store = null
    }

    /** Append one event. Safe before [init] (memory only until something starts). */
    @Synchronized
    fun log(event: String, message: String = "", vararg fields: Pair<String, String>) {
        val record = Event(System.currentTimeMillis(), event, message, fields.toMap())
        events.value = (listOf(record) + events.value).take(MAX_EVENTS)
        val s = store
        if (s != null) {
            dbScope.launch {
                runCatching { insert(s, record) }
                    .onFailure { Log.w("ActivityLog", "Could not store $event", it) }
            }
        } else {
            appendToSpool(record)
        }
    }

    private fun insert(store: TripMetadataStore, event: Event) {
        val tripId = folderToken.find(event.message)?.value
            ?.let { runCatching { store.tripByFolder(it)?.id }.getOrNull() }
        val fieldsJson = event.fields.takeIf { it.isNotEmpty() }
            ?.let { JSONObject(it as Map<*, *>).toString() }
        store.logActivity(event.timeMs, event.event, tripId, event.message, fieldsJson)
        if (++writesSinceTrim >= 500) {
            writesSinceTrim = 0
            store.trimActivity(DB_MAX_ROWS)
        }
    }

    private fun appendToSpool(event: Event) {
        val f = file ?: return
        val record = JSONObject()
            .put("t", event.timeMs)
            .put("e", event.event)
            .apply { if (event.message.isNotEmpty()) put("m", event.message) }
        event.fields.forEach { (k, v) -> record.put(k, v) }
        try {
            val keep = MAX_EVENTS - 1
            val existing = if (f.isFile) f.readLines() else emptyList()
            val lines = (if (existing.size > keep) existing.takeLast(keep) else existing) + record.toString()
            f.writeText(lines.joinToString("\n", postfix = "\n"))
        } catch (e: Exception) {
            Log.w("ActivityLog", "Could not spool ${event.event}", e)
        }
    }

    private fun readSpool(f: File): List<Event> {
        if (!f.isFile) return emptyList()
        return try {
            f.readLines().asReversed().mapNotNull { parseLine(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Newest-first single-line text for the share-diagnostics intent. */
    private fun format(events: List<Event>): String = events.joinToString("\n") { e ->
        val base = "${timeFormat.format(Date(e.timeMs))}  ${e.event}" +
            (e.message.takeIf { it.isNotEmpty() }?.let { "  - $it" } ?: "")
        val extra = e.fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
        if (extra.isEmpty()) base else "$base  $extra"
    }

    fun asText(): String = format(queryEvents(MAX_EVENTS))

    /** Share the full retained history, not just the UI window. */
    fun shareIntent(context: Context): Intent {
        val out = File(context.cacheDir, "triplogger-activity.log")
        out.writeText(format(queryEvents(DB_MAX_ROWS)))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            null
        )
    }

    private fun queryEvents(limit: Int): List<Event> =
        store?.let { s ->
            runCatching { s.recentActivity(limit).map { rowToEvent(it) } }.getOrNull()
        } ?: events.value.take(limit)
}
