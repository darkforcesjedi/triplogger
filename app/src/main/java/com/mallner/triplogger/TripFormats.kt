package com.mallner.triplogger

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Date/number formatting shared by the journal writers and the trip exporters.
 * KML formats here must stay compatible with what Google Earth accepts and
 * with the track naming in the user's existing KMZ archive.
 */
object TripFormats {
    private val DEVICE_TIME = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val DAY_LABEL = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val TRIP_LABEL = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US)

    // "2026-01-19T06:10:10" - <when> elements in KML (naive local time, matches existing KMZ files)
    private val KML_WHEN = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    fun deviceTime(ms: Long): String = synchronized(DEVICE_TIME) { DEVICE_TIME.format(Date(ms)) }
    fun dayLabel(ms: Long): String = synchronized(DAY_LABEL) { DAY_LABEL.format(Date(ms)) }
    fun tripLabel(ms: Long): String = synchronized(TRIP_LABEL) { TRIP_LABEL.format(Date(ms)) }
    fun kmlWhen(ms: Long): String = synchronized(KML_WHEN) { KML_WHEN.format(Date(ms)) }

    fun num(value: Double?, digits: Int = 8): String =
        if (value == null || value.isNaN()) "" else String.format(Locale.US, "%.${digits}f", value)

    fun num(value: Float?, digits: Int = 8): String =
        if (value == null || value.isNaN()) "" else String.format(Locale.US, "%.${digits}f", value)

    fun long(value: Long?): String = if (value == null || value <= 0L) "" else value.toString()

    // Speed conversions
    const val MPS_TO_MPH = 2.2369362920544
    const val KMH_TO_MPH = 0.62137119223733
}
