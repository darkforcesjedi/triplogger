package com.mallner.triplogger

import android.content.Context
import android.os.Build

/**
 * Detection for the Home screen hint about Samsung suppressing our live
 * notification (timer chip + promoted card).
 *
 * OneUI 7-9 keep third-party apps off the live-notification allowlist: the
 * notification carries the promoted flag and renders perfectly on Pixel, but
 * Samsung hides the chip and the promoted treatment unless the "Live
 * notifications for all apps" developer option is enabled (observed first
 * hand on OneUI 9 / Android 17 firmware from 2026-10; the OS reports this
 * via canPostPromotedNotifications()). The version gate keeps the hint off
 * builds without the quirk, and off non-Samsung firmware entirely.
 */
object LiveUpdateHint {

    /** True when the platform will hide our chip/promotion and the user can fix it. */
    fun showHint(sdkInt: Int, oneUiMajor: Int?, canPromote: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.BAKLAVA && oneUiMajor in 7..9 && !canPromote

    /** "90000" -> 9, "85000" -> 8; anything unparseable -> null (no hint). */
    fun parseOneUiMajor(raw: String?): Int? =
        raw?.trim()?.toIntOrNull()?.let { if (it >= 10000) it / 10000 else null }

    /** ro.build.version.oneui via SystemProperties (hidden API, read-only). */
    fun oneUiMajor(context: Context): Int? = runCatching {
        val holder = Class.forName("android.os.SystemProperties")
        val getter = holder.getMethod("get", String::class.java)
        parseOneUiMajor(getter.invoke(null, "ro.build.version.oneui") as? String)
    }.getOrNull()
}
