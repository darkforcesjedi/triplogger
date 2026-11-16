package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveUpdateHintTest {

    @Test
    fun suppressedOnOneUi7To9WhenSwitchOff() {
        for (oneUiMajor in listOf(7, 8, 9)) {
            assertEquals(true, LiveUpdateHint.showHint(36, oneUiMajor, "0"))
        }
    }

    @Test
    fun silentWhenSwitchOn() {
        for (oneUiMajor in listOf(7, 8, 9)) {
            assertEquals(false, LiveUpdateHint.showHint(36, oneUiMajor, "1"))
        }
    }

    @Test
    fun silentWhenFirmwareLacksTheSwitch() {
        // An absent key must never nag: another OEM build, older OneUI, or a
        // future firmware where Samsung renamed the entry.
        assertEquals(false, LiveUpdateHint.showHint(36, 8, null))
        assertEquals(false, LiveUpdateHint.showHint(36, 9, null))
    }

    @Test
    fun silentOffOneUiAndOnOlderAndroid() {
        // Stock Android (no OneUi), OneUI 6 (before Live Updates), OneUI 10.
        assertEquals(false, LiveUpdateHint.showHint(36, null, "0"))
        assertEquals(false, LiveUpdateHint.showHint(36, 6, "0"))
        assertEquals(false, LiveUpdateHint.showHint(36, 10, "0"))
        // Live Updates do not exist before API 36.
        assertEquals(false, LiveUpdateHint.showHint(35, 8, "0"))
    }

    @Test
    fun oneUiMajorParsesBuildProperty() {
        assertEquals(9, LiveUpdateHint.parseOneUiMajor("90000"))
        assertEquals(8, LiveUpdateHint.parseOneUiMajor("85000"))
        assertEquals(7, LiveUpdateHint.parseOneUiMajor("70000"))
        assertEquals(null, LiveUpdateHint.parseOneUiMajor(""))
        assertEquals(null, LiveUpdateHint.parseOneUiMajor(null))
        assertEquals(null, LiveUpdateHint.parseOneUiMajor("unknown"))
        // A raw major number is not the OneUI property format.
        assertEquals(null, LiveUpdateHint.parseOneUiMajor("9"))
    }
}
