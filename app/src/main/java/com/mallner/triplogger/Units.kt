package com.mallner.triplogger

/**
 * Unit conversion for display and export. PIDs parse to SI values (km/h, °C,
 * kPa, km, L/h); exports convert to US customary when the user selects it.
 * Unit symbols are international standard symbols and intentionally not
 * localized; the settings screen labels are strings resources.
 */
object UnitFormat {

    private const val KPA_TO_PSI = 0.14503773773
    private const val L_PER_HOUR_TO_GAL_PER_HOUR = 0.264172052
    private const val KM_TO_MI = 0.62137119223733
    private const val MPS_TO_KMH = 3.6

    fun speedUnitLabel(us: Boolean): String = if (us) "mph" else "km/h"

    fun mpsToDisplay(mps: Double, us: Boolean): Double =
        if (us) mps * TripFormats.MPS_TO_MPH else mps * MPS_TO_KMH

    /** Converts an SI value (per the PID's declared unit) into the display unit. */
    fun convert(unit: String, value: Double, us: Boolean): Pair<Double, String> =
        when {
            unit == "km/h" && us -> Pair(value * TripFormats.KMH_TO_MPH, "mph")
            unit == "°C" && us -> Pair(value * 9.0 / 5.0 + 32.0, "°F")
            unit == "kPa" && us -> Pair(value * KPA_TO_PSI, "psi")
            unit == "km" && us -> Pair(value * KM_TO_MI, "mi")
            unit == "L/h" && us -> Pair(value * L_PER_HOUR_TO_GAL_PER_HOUR, "gal/h")
            else -> Pair(value, unit)
        }

    /** Display unit for a PID's SI unit without converting a value. */
    fun unitLabel(unit: String, us: Boolean): String = convert(unit, 0.0, us).second
}
