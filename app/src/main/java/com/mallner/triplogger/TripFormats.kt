package com.mallner.triplogger

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Date/number formatting shared by the journal writers and the trip exporters.
 * All journal timestamps are epoch milliseconds (UTC); every formatting
 * function here takes an explicit UTC offset in minutes so trips that crossed
 * a time zone or DST boundary are rendered with the offset their samples were
 * recorded with - never the device time zone at export time. (Calendar/SDF
 * based: the app runs on API 24 where java.time is unavailable.)
 */
object TripFormats {
    /** Minutes east of UTC for `ms` in the device time zone (historically correct). */
    fun deviceOffsetMin(ms: Long): Int = TimeZone.getDefault().getOffset(ms) / 60_000

    fun deviceZoneId(): String = TimeZone.getDefault().id

    private fun format(pattern: String, ms: Long, tzOffsetMin: Int?): String {
        val fmt = SimpleDateFormat(pattern, Locale.US)
        fmt.timeZone = if (tzOffsetMin == null) TimeZone.getDefault()
        else TimeZone.getTimeZone("GMT" + offsetLabel(tzOffsetMin))
        return fmt.format(Date(ms))
    }

    /** "yyyy-MM-dd" - the KMZ day-file name; pass null only for device-local rendering. */
    fun dayLabel(ms: Long, tzOffsetMin: Int? = null): String = format("yyyy-MM-dd", ms, tzOffsetMin)

    /** Trip name "yyyy-MM-dd HH.mm.ss" in the time zone the trip started in. */
    fun tripLabel(ms: Long, tzOffsetMin: Int? = null): String = format("yyyy-MM-dd HH.mm.ss", ms, tzOffsetMin)

    /** KML <when>: ISO 8601 with the recorded offset, e.g. 2026-11-05T21:43:52-05:00. */
    fun kmlWhen(ms: Long, tzOffsetMin: Int?): String {
        val offset = tzOffsetMin ?: deviceOffsetMin(ms)
        return format("yyyy-MM-dd'T'HH:mm:ss", ms, offset) + offsetLabel(offset)
    }

    /** Spreadsheet timestamp with explicit offset, e.g. "2026-11-05 21:43:52-05:00". */
    fun odsTime(ms: Long, tzOffsetMin: Int?): String {
        val offset = tzOffsetMin ?: deviceOffsetMin(ms)
        return format("yyyy-MM-dd HH:mm:ss", ms, offset) + offsetLabel(offset)
    }

    /** "+hh:mm" / "-hh:mm" from minutes east of UTC (never "" for zero like ZoneOffset does). */
    fun offsetLabel(minutes: Int): String {
        val sign = if (minutes < 0) '-' else '+'
        val abs = kotlin.math.abs(minutes)
        return "%c%02d:%02d".format(sign, abs / 60, abs % 60)
    }

    /** UTC-referenced offset label per ISO 8601 usage, e.g. "UTC-05:00" / "UTC+05:30". */
    fun utcLabel(minutes: Int): String = "UTC" + offsetLabel(minutes)

    /** Time zone for "HH:mm" plot axis labels in a recorded offset. */
    fun offsetTimeZone(tzOffsetMin: Int?): TimeZone =
        if (tzOffsetMin == null) TimeZone.getDefault()
        else TimeZone.getTimeZone("GMT" + offsetLabel(tzOffsetMin))

    fun num(value: Double?, digits: Int = 8): String =
        if (value == null || value.isNaN()) "" else String.format(Locale.US, "%.${digits}f", value)

    fun num(value: Float?, digits: Int = 8): String =
        if (value == null || value.isNaN()) "" else String.format(Locale.US, "%.${digits}f", value)

    fun long(value: Long?): String = if (value == null || value <= 0L) "" else value.toString()

    // Speed conversions
    const val MPS_TO_MPH = 2.2369362920544
    const val KMH_TO_MPH = 0.62137119223733
}
