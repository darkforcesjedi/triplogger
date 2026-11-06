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

    @Test
    fun fuelLevelIsPercentageNotFuelType() {
        // 2F: 100*A/255 (the old "51" returned 1 on this car: fuel *type* gasoline)
        val fuel = ObdPids.byHex("2F")!!
        assertEquals(100.0, ObdPids.parseValue(fuel, "FF")!!, 0.001)
        assertEquals(50.2, ObdPids.parseValue(fuel, "80")!!, 0.3)
        assertNull(ObdPids.byHex("51"))
    }

    @Test
    fun ambientAirTempIs46Not5F() {
        // 5F is fuel rail gauge pressure; 46 is the J1979 ambient air temperature
        assertEquals("Ambient Air Temp", ObdPids.byHex("46")!!.name)
        assertEquals(34.0, ObdPids.parseValue(ObdPids.byHex("46")!!, "4A")!!, 0.001)
        assertNull(ObdPids.byHex("5F"))
    }

    @Test
    fun distanceSinceDtcClearIs31() {
        assertEquals("Distance Since DTC Clear", ObdPids.byHex("31")!!.name)
        assertEquals(5000.0, ObdPids.parseValue(ObdPids.byHex("31")!!, "1388")!!, 0.001)
        assertNull(ObdPids.byHex("21")) // 21 is distance since trouble code clear *mil on*, mislabeled before
    }

    @Test
    fun timingAdvanceHasMinus64Offset() {
        assertEquals(0.0, ObdPids.parseValue(ObdPids.byHex("0E")!!, "80")!!, 0.001)   // A/2-64
        assertEquals(-64.0, ObdPids.parseValue(ObdPids.byHex("0E")!!, "00")!!, 0.001)
    }

    @Test
    fun trimsUse128ScaleAndMinus100Offset() {
        assertEquals(-100.0, ObdPids.parseValue(ObdPids.byHex("06")!!, "00")!!, 0.001)
        assertEquals(37.5, ObdPids.parseValue(ObdPids.byHex("06")!!, "B0")!!, 0.01)   // 176/1.28-100
    }

    @Test
    fun evapPurgeIs2EAndCatalystsAre3Cand3E() {
        assertEquals("Commanded EVAP Purge", ObdPids.byHex("2E")!!.name)
        assertNull(ObdPids.byHex("4C"))
        assertNull(ObdPids.byHex("34"))
        assertNull(ObdPids.byHex("35"))
        // 3C: (A*256+B)/10 - 40; 0x06A4 = 1700 -> 130 C
        assertEquals(130.0, ObdPids.parseValue(ObdPids.byHex("3C")!!, "06A4")!!, 0.001)
    }

    @Test
    fun equivalenceRatioAndOdometer() {
        // 44: A*256+B * 2/65536 ; 0x8000 -> lambda 1.0
        assertEquals(1.0, ObdPids.parseValue(ObdPids.byHex("44")!!, "8000")!!, 0.001)
        // A6: raw/10 km ; 0x000F4240 = 1_000_000 -> 100000 km
        assertEquals(100000.0, ObdPids.parseValue(ObdPids.byHex("A6")!!, "000F4240")!!, 0.001)
    }

    @Test
    fun hybridBatteryChargeAvailable() {
        assertEquals("Hybrid Battery Charge", ObdPids.byHex("5B")!!.name)
        assertEquals(100.0, ObdPids.parseValue(ObdPids.byHex("5B")!!, "FF")!!, 0.001)
    }
}
