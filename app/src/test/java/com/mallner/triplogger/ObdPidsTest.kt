package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdPidsTest {

    @Test
    fun parsesRpm() {
        val rpm = ObdPids.byHex("0C")!!
        // 0x1AF8 = 6904 / 4 = 1726
        assertEquals(1726.0, ObdPids.parseValue(rpm, "1AF8")!!, 0.001)
    }

    @Test
    fun parsesSpeed() {
        val speed = ObdPids.byHex("0D")!!
        // 0x50 = 80 km/h
        assertEquals(80.0, ObdPids.parseValue(speed, "50")!!, 0.001)
    }

    @Test
    fun parsesTemperatures() {
        val coolant = ObdPids.byHex("05")!!
        // 0x71 = 113 - 40 = 73 C
        assertEquals(73.0, ObdPids.parseValue(coolant, "71")!!, 0.001)
    }

    @Test
    fun parsesFuelTrimZeroAt128() {
        val trim = ObdPids.byHex("07")!!
        assertEquals(0.0, ObdPids.parseValue(trim, "80")!!, 0.5)
    }

    @Test
    fun parseValueToleratesSpacesAndLowercase() {
        val rpm = ObdPids.byHex("0C")!!
        assertEquals(1726.0, ObdPids.parseValue(rpm, "1a f8")!!, 0.001)
    }

    @Test
    fun parseValueRejectsShortOrGarbage() {
        val rpm = ObdPids.byHex("0C")!!
        assertNull(ObdPids.parseValue(rpm, "1A"))
        assertNull(ObdPids.parseValue(rpm, ""))
        assertNull(ObdPids.parseValue(rpm, null))
        assertNull(ObdPids.parseValue(rpm, "ER"))
    }

    @Test
    fun allPidsHaveUniqueHexAndPositiveLength() {
        assertEquals(ObdPids.ALL.size, ObdPids.ALL.map { it.hex }.distinct().size)
        assertTrue(ObdPids.ALL.all { it.bytes in 1..4 })
    }

    @Test
    fun defaultsAreRealPids() {
        assertTrue(ObdPids.DEFAULTS.all { ObdPids.byHex(it) != null })
    }
}
