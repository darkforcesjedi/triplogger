package com.mallner.triplogger

import android.content.Context
import kotlinx.coroutines.flow.first

/**
 * Mirror of the two boot-gating decisions in SharedPreferences so the
 * boot receiver can act SYNCHRONOUSLY in onReceive().
 *
 * A BOOT_COMPLETED receiver briefly puts the app on the temporary
 * allowlist, and while inside that window a while-in-use foreground
 * service (location) may start. DataStore's first read under boot load
 * measured 26 seconds on the test device, far outside the window, which
 * is what forced the service to arm under specialUse without GPS. The
 * mirror turns the boot decision into a memory-mapped read, so
 * startForegroundService runs within milliseconds of the broadcast and
 * the service's typed startForeground lands while the grace is alive.
 *
 * DataStore remains the single truth; the mirror is written through on
 * every relevant change and re-synced whenever the app or service starts
 * (which also seeds it after an upgrade). Worst case, a missed write only
 * costs one boot's worth of auto-start.
 */
object BootFlags {

    private const val PREFS = "boot_flags"
    private const val KEY_SERVICE = "service_enabled"
    private const val KEY_JOURNAL = "journal_set"

    data class Gate(val serviceEnabled: Boolean, val journalSet: Boolean)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SERVICE, enabled).apply()
    }

    fun setJournalSet(context: Context, set: Boolean) {
        prefs(context).edit().putBoolean(KEY_JOURNAL, set).apply()
    }

    fun gate(context: Context): Gate = prefs(context).let {
        Gate(it.getBoolean(KEY_SERVICE, false), it.getBoolean(KEY_JOURNAL, false))
    }

    /** Re-align the mirror with the real settings store (upgrade seeding, drift). */
    suspend fun resync(context: Context) {
        val settings = AppSettingsRepository(context).appSettingsFlow.first()
        prefs(context).edit()
            .putBoolean(KEY_SERVICE, settings.serviceEnabled)
            .putBoolean(KEY_JOURNAL, settings.tempLocationUri.isNotEmpty())
            .apply()
    }
}
