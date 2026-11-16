package com.mallner.triplogger

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Detection for the Home screen hint about Samsung suppressing our live
 * notification (timer chip + promoted card).
 *
 * OneUI 9 (at least the 2026-10 Android 17 firmware) keeps third-party apps
 * off the live-notification treatment unless the Developer-options entry
 * "Live notifications for all apps" is enabled. On this build that entry
 * writes the SECURE setting `enable_notification_nowbar_test` (verified by
 * settings-table diffing: flipping the entry flips only that value), so it
 * is read directly - unlike canPostPromotedNotifications(), which stayed
 * true while the chip was still hidden. The version and SDK gates keep the
 * hint off firmware without the quirk (the key being absent simply means
 * "not suppressed").
 */
object LiveUpdateHint {

    private const val ALL_APPS_LIVE_KEY = "enable_notification_nowbar_test"

    /**
     * True when the platform will hide our chip/promotion AND the user has
     * a switch to fix it. An absent key (other firmware, other OEM) is NOT
     * treated as suppression, so this can only ever fire on builds that
     * actually have the Developer-options entry.
     */
    fun showHint(sdkInt: Int, oneUiMajor: Int?, flagValue: String?): Boolean =
        sdkInt >= Build.VERSION_CODES.BAKLAVA && oneUiMajor in 7..9 &&
            flagValue != null && flagValue != "1"

    /** "90000" -> 9, "85000" -> 8; anything unparseable -> null (no hint). */
    fun parseOneUiMajor(raw: String?): Int? =
        raw?.trim()?.toIntOrNull()?.let { if (it >= 10000) it / 10000 else null }

    /** ro.build.version.oneui via SystemProperties (hidden API, read-only). */
    fun oneUiMajor(context: Context): Int? = runCatching {
        val holder = Class.forName("android.os.SystemProperties")
        val getter = holder.getMethod("get", String::class.java)
        parseOneUiMajor(getter.invoke(null, "ro.build.version.oneui") as? String)
    }.getOrNull()

    /** The allow-all switch's current value; null when this firmware lacks it. */
    fun readLiveFlag(context: Context): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, ALL_APPS_LIVE_KEY)
    }.getOrNull()
}
