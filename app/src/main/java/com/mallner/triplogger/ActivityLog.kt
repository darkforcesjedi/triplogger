package com.mallner.triplogger

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Rolling activity log ("Activity" tab): what the logger actually did, as
 * JSONL in app-private storage, newest first, capped at [MAX_EVENTS].
 *
 * Every line: {"t": <epoch ms>, "e": "<event>", ...fields}. Written from
 * arbitrary threads (service loops, finalizer, UI actions) - a single
 * synchronized append keeps it cheap; events are one per meaningful moment,
 * never per sample.
 */
object ActivityLog {

    const val MAX_EVENTS = 500

    data class Event(
        val timeMs: Long,
        val event: String,
        val message: String,
        val fields: Map<String, String> = emptyMap()
    )

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    // Trip ids arrive in message/field text in two historical shapes: the
    // journal folder name "20261106_221901" and the display label
    // "2026-11-06 22.19.01". Both are rewritten to one readable form at read
    // time so old entries render consistently too.
    private val labelTimestamps = Regex("""\b(\d{4})-(\d{2})-(\d{2}) (\d{2})\.(\d{2})\.(\d{2})\b""")
    private val folderTimestamps = Regex("""(?<=\D|^)(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})(?=\D|$)""")

    internal fun normalizeTimestamps(text: String): String {
        var out = labelTimestamps.replace(text) { m ->
            "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]} ${m.groupValues[4]}:${m.groupValues[5]}:${m.groupValues[6]}"
        }
        out = folderTimestamps.replace(out) { m ->
            "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]} ${m.groupValues[4]}:${m.groupValues[5]}:${m.groupValues[6]}"
        }
        return out
    }

    private var file: File? = null
    private val events = MutableStateFlow<List<Event>>(emptyList())
    val recent: StateFlow<List<Event>> = events.asStateFlow()

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "activity-log.jsonl")
        file = f
        events.value = readEvents(f)
    }

    /** Append one event. Safe before [init] (no-op until the app starts). */
    @Synchronized
    fun log(event: String, message: String = "", vararg fields: Pair<String, String>) {
        val f = file ?: return
        val record = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("e", event)
            .apply { if (message.isNotEmpty()) put("m", message) }
        fields.forEach { (k, v) -> record.put(k, v) }
        try {
            val keep = MAX_EVENTS - 1
            val existing = if (f.isFile) f.readLines() else emptyList()
            val lines = (if (existing.size > keep) existing.takeLast(keep) else existing) + record.toString()
            f.writeText(lines.joinToString("\n", postfix = "\n"))
            events.value = readEvents(f)
        } catch (e: Exception) {
            Log.w("ActivityLog", "Could not append $event", e)
        }
    }

    private fun readEvents(f: File): List<Event> {
        if (!f.isFile) return emptyList()
        return try {
            f.readLines().asReversed().mapNotNull { line ->
                try {
                    val o = JSONObject(line)
                    val event = o.getString("e")
                    val message = normalizeTimestamps(o.optString("m", ""))
                    val fields = o.keys().asSequence()
                        .filter { it != "t" && it != "e" && it != "m" }
                        .associateWith { normalizeTimestamps(o.get(it).toString()) }
                    Event(o.getLong("t"), event, message, fields)
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Newest-first single-line text for the share-diagnostics intent. */
    fun asText(): String = events.value.joinToString("\n") { e ->
        val base = "${timeFormat.format(Date(e.timeMs))}  ${e.event}" +
            (e.message.takeIf { it.isNotEmpty() }?.let { "  — $it" } ?: "")
        val extra = e.fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
        if (extra.isEmpty()) base else "$base  $extra"
    }

    fun shareIntent(context: Context): Intent {
        // Write the current view to cache; the share sheet snapshots the file.
        val out = File(context.cacheDir, "triplogger-activity.log")
        out.writeText(asText())
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            null
        )
    }
}
