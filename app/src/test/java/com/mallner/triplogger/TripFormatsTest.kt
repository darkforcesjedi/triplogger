package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Test

class TripFormatsTest {

    // 2026-11-05 23:30:00 UTC
    private val beforeMidnightUtc = 1793921400000L

    @Test
    fun offsetLabel() {
        assertEquals("+00:00", TripFormats.offsetLabel(0))
        assertEquals("-05:00", TripFormats.offsetLabel(-300))
        assertEquals("+05:30", TripFormats.offsetLabel(330))
        assertEquals("-03:30", TripFormats.offsetLabel(-210))
    }

    @Test
    fun labelsFollowTheGivenOffsetNotTheDevice() {
        // same instant rendered in two zones lands on different days
        assertEquals("2026-11-05", TripFormats.dayLabel(beforeMidnightUtc, 0))
        assertEquals("2026-11-06", TripFormats.dayLabel(beforeMidnightUtc, 120))
        assertEquals("2026-11-05 23.30.00", TripFormats.tripLabel(beforeMidnightUtc, 0))
        assertEquals("2026-11-06 01.30.00", TripFormats.tripLabel(beforeMidnightUtc, 120))
    }

    @Test
    fun kmlWhenCarriesIsoOffset() {
        assertEquals("2026-11-05T23:30:00+00:00", TripFormats.kmlWhen(beforeMidnightUtc, 0))
        assertEquals("2026-11-05T18:30:00-05:00", TripFormats.kmlWhen(beforeMidnightUtc, -300))
    }

    @Test
    fun odsTimeCarriesExplicitOffset() {
        assertEquals("2026-11-05 23:30:00+00:00", TripFormats.odsTime(beforeMidnightUtc, 0))
        assertEquals("2026-11-06 04:30:00+05:00", TripFormats.odsTime(beforeMidnightUtc, 300))
    }
}
