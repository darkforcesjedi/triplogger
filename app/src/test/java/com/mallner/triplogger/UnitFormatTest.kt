package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitFormatTest {

    @Test
    fun speedFromMetersPerSecond() {
        assertEquals(22.37, UnitFormat.mpsToDisplay(10.0, us = true), 0.01)
        assertEquals(36.0, UnitFormat.mpsToDisplay(10.0, us = false), 0.001)
    }

    @Test
    fun speedUnitLabels() {
        assertEquals("mph", UnitFormat.speedUnitLabel(us = true))
        assertEquals("km/h", UnitFormat.speedUnitLabel(us = false))
    }

    @Test
    fun speedConversion() {
        val (mph, label) = UnitFormat.convert("km/h", 100.0, us = true)
        assertEquals(62.14, mph, 0.01)
        assertEquals("mph", label)
        assertEquals(100.0, UnitFormat.convert("km/h", 100.0, us = false).first, 0.001)
        assertEquals("km/h", UnitFormat.convert("km/h", 100.0, us = false).second)
    }

    @Test
    fun temperatureConversion() {
        assertEquals(212.0, UnitFormat.convert("°C", 100.0, us = true).first, 0.001)
        assertEquals("°F", UnitFormat.convert("°C", 100.0, us = true).second)
        assertEquals(-40.0, UnitFormat.convert("°C", -40.0, us = true).first, 0.001) // the one point where they meet
        assertEquals(20.0, UnitFormat.convert("°C", 20.0, us = false).first, 0.001)
    }

    @Test
    fun pressureDistanceAndFuelRate() {
        assertEquals(37.79, UnitFormat.convert("kPa", 260.5, us = true).first, 0.01)
        assertEquals("psi", UnitFormat.convert("kPa", 260.5, us = true).second)
        assertEquals(62.14, UnitFormat.convert("km", 100.0, us = true).first, 0.01)
        assertEquals("mi", UnitFormat.convert("km", 100.0, us = true).second)
        assertEquals(2.64, UnitFormat.convert("L/h", 10.0, us = true).first, 0.01)
        assertEquals("gal/h", UnitFormat.convert("L/h", 10.0, us = true).second)
    }

    @Test
    fun unitlessValuesPassThrough() {
        val (rpm, rpmUnit) = UnitFormat.convert("rpm", 1726.0, us = true)
        assertEquals(1726.0, rpm, 0.001)
        assertEquals("rpm", rpmUnit)
        assertEquals(13.9, UnitFormat.convert("V", 13.9, us = true).first, 0.001)
        assertEquals("V", UnitFormat.convert("V", 13.9, us = true).second)
        assertEquals("%", UnitFormat.convert("%", 50.0, us = true).second)
        assertEquals("°", UnitFormat.convert("°", 12.0, us = true).second)
    }

    @Test
    fun unitLabelMatchesConvert() {
        assertEquals("mph", UnitFormat.unitLabel("km/h", us = true))
        assertEquals("°F", UnitFormat.unitLabel("°C", us = true))
        assertEquals("kPa", UnitFormat.unitLabel("kPa", us = false))
    }
}
