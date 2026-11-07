package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Minified status-chip logic (shown only while unlocked): per-sensor glyphs
 * + entry count while connecting, plain ticks + entries once connected.
 * Locked surfaces deliberately get no short-critical-text (see
 * AppNotificationManager.updateRecording) and keep the rich contentText lines.
 */
class AppNotificationManagerChipTest {

    @Test
    fun glyphsPlusEntriesWhileConnecting() {
        assertEquals("G\u00B7O\u00B70", AppNotificationManager.chipText(true, false, true, false, 0))
        assertEquals("G\u2713O\u00B712", AppNotificationManager.chipText(true, true, true, false, 12))
        // Disabled sensors drop out of the glyph prefix entirely.
        assertEquals("G\u00B75", AppNotificationManager.chipText(true, false, false, false, 5))
        assertEquals("O\u00B75", AppNotificationManager.chipText(false, false, true, false, 5))
    }

    @Test
    fun ticksPlusEntriesWhenAllEnabledConnected() {
        assertEquals("\u2713\u271334", AppNotificationManager.chipText(true, true, true, true, 34))
        // One sensor enabled and connected: single tick.
        assertEquals("\u27138", AppNotificationManager.chipText(true, true, false, false, 8))
        // Dropout falls back to the per-sensor glyphs.
        assertEquals("G\u00B7O\u271399", AppNotificationManager.chipText(true, false, true, true, 99))
    }

    @Test
    fun staysWithinCompactCharacterBudget() {
        // Typical trip: entries stay <= 3 digits for the vast majority of trips;
        // even 4-digit counts fit the 7-char guideline with single-sensor glyphs.
        assertEquals(4, AppNotificationManager.chipText(true, true, true, true, 34).length)
        assertEquals("G\u2713O\u00B71200".length, AppNotificationManager.chipText(true, true, true, false, 1200).length)
    }
}
