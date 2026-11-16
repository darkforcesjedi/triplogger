package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveUpdateHintTest {

    @Test
    fun suppressedOnOneUi7To9WhenPlatformRefuses() {
        for (oneUiMajor in listOf(7, 8, 9)) {
            assertEquals(true, LiveUpdateHint.showHint(36, oneUiMajor, canPromote = false))
        }
    }

    @Test
    fun silentWhenThePlatformAllowsPromotion() {
        for (oneUiMajor in listOf(7, 8, 9)) {
            assertEquals(false, LiveUpdateHint.showHint(36, oneUiMajor, canPromote = true))
        }
    }

    @Test
    fun silentOffOneUiAndOnOlderAndroid() {
        // Stock Android (no OneUi), OneUI 6 (before Live Updates), OneUI 10.
        assertEquals(false, LiveUpdateHint.showHint(36, null, canPromote = false))
        assertEquals(false, LiveUpdateHint.showHint(36, 6, canPromote = false))
        assertEquals(false, LiveUpdateHint.showHint(36, 10, canPromote = false))
        // Live Updates and the gating API do not exist before API 36.
        assertEquals(false, LiveUpdateHint.showHint(35, 8, canPromote = false))
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
