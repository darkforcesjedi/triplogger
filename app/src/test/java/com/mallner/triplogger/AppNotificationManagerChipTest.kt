package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Status-chip logic (shown only while unlocked): connection glyphs while
 * anything is still connecting, the recording timer once all enabled sensors
 * are connected, glyphs again on dropout. No entry counts on the chip.
 * Locked surfaces get no short-critical-text at all (see
 * AppNotificationManager.updateRecording) and keep the rich contentText lines.
 */
class AppNotificationManagerChipTest {

    @Test
    fun glyphsWhileConnectingShowEnabledSensorsOnly() {
        assertEquals("G\u00B7O\u00B7", AppNotificationManager.chipText(true, false, true, false, 5_000))
        assertEquals("G\u2713O\u00B7", AppNotificationManager.chipText(true, true, true, false, 5_000))
        // Disabled sensors drop out of the chip entirely.
        assertEquals("G\u00B7", AppNotificationManager.chipText(true, false, false, false, 5_000))
        assertEquals("O\u00B7", AppNotificationManager.chipText(false, false, true, false, 5_000))
    }

    @Test
    fun timerWhenAllEnabledConnected() {
        assertEquals("0:07", AppNotificationManager.chipText(true, true, true, true, 7_000))
        // One sensor enabled and connected is enough to switch to the timer.
        assertEquals("1:05", AppNotificationManager.chipText(true, true, false, false, 65_000))
        // Dropout falls back to the per-sensor glyphs.
        assertEquals("G\u00B7O\u2713", AppNotificationManager.chipText(true, false, true, true, 600_000))
    }

    @Test
    fun durationFormat() {
        assertEquals("0:00", AppNotificationManager.formatChipDuration(0))
        assertEquals("9:59", AppNotificationManager.formatChipDuration(9 * 60_000 + 59_000))
        assertEquals("59:59", AppNotificationManager.formatChipDuration(59 * 60_000 + 59_000))
        assertEquals("1:00:00", AppNotificationManager.formatChipDuration(3_600_000))
        assertEquals("1:02:03", AppNotificationManager.formatChipDuration(3_723_000))
    }
}
