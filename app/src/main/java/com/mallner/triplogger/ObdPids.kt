package com.mallner.triplogger

/**
 * Catalog of supported OBD-II mode 01 PIDs plus ELM327 hex response parsing.
 * Formulas verified against SAE J1979 references (Wikipedia "OBD-II PIDs",
 * DashLogic J1979 PID listing, csselectronics PID table).
 * List order is the display order in Settings: most useful / most commonly
 * supported sensors first. Values are parsed to SI units; the Units setting
 * converts them at display/export time.
 */
data class ObdPid(
    val hex: String,                 // e.g. "0C"
    val name: String,                // display name
    val unit: String,                // SI unit of the parsed value
    val bytes: Int,                  // expected response data length
    val formula: (IntArray) -> Double?  // bytes -> value, null if unsupported
)

object ObdPids {
    val ALL: List<ObdPid> = listOf(
        ObdPid("0D", "Speed (OBD)", "km/h", 1) { a -> a[0].toDouble() },
        ObdPid("0C", "Engine RPM", "rpm", 2) { a -> (a[0] * 256 + a[1]) / 4.0 },
        ObdPid("11", "Throttle Position", "%", 1) { a -> a[0] * 100.0 / 255.0 },
        ObdPid("05", "Coolant Temp", "°C", 1) { a -> (a[0] - 40).toDouble() },
        ObdPid("0F", "Intake Air Temp", "°C", 1) { a -> (a[0] - 40).toDouble() },
        ObdPid("46", "Ambient Air Temp", "°C", 1) { a -> (a[0] - 40).toDouble() },
        ObdPid("42", "Control Module Voltage", "V", 2) { a -> (a[0] * 256 + a[1]) / 1000.0 },
        ObdPid("2F", "Fuel Level", "%", 1) { a -> a[0] * 100.0 / 255.0 },
        ObdPid("5B", "Hybrid Battery Charge", "%", 1) { a -> a[0] * 100.0 / 255.0 },
        ObdPid("04", "Calculated Engine Load", "%", 1) { a -> a[0] * 100.0 / 255.0 },
        ObdPid("0B", "Intake Manifold Pressure", "kPa", 1) { a -> a[0].toDouble() },
        ObdPid("10", "MAF Air Flow", "g/s", 2) { a -> (a[0] * 256 + a[1]) / 100.0 },
        ObdPid("5E", "Fuel Rate", "L/h", 2) { a -> (a[0] * 256 + a[1]) / 20.0 },
        ObdPid("0E", "Timing Advance", "°", 1) { a -> a[0] / 2.0 - 64.0 },
        ObdPid("0A", "Fuel Pressure", "kPa", 1) { a -> a[0] * 3.0 },
        ObdPid("23", "Fuel Rail Pressure (gauge)", "kPa", 2) { a -> (a[0] * 256 + a[1]) * 10.0 },
        ObdPid("33", "Barometric Pressure", "kPa", 1) { a -> a[0].toDouble() },
        ObdPid("06", "Fuel Trim Short Term B1", "%", 1) { a -> a[0] / 1.28 - 100.0 },
        ObdPid("07", "Fuel Trim Long Term B1", "%", 1) { a -> a[0] / 1.28 - 100.0 },
        ObdPid("08", "Fuel Trim Short Term B2", "%", 1) { a -> a[0] / 1.28 - 100.0 },
        ObdPid("09", "Fuel Trim Long Term B2", "%", 1) { a -> a[0] / 1.28 - 100.0 },
        ObdPid("1F", "Run Time Since Engine Start", "s", 2) { a -> (a[0] * 256 + a[1]).toDouble() },
        ObdPid("31", "Distance Since DTC Clear", "km", 2) { a -> (a[0] * 256 + a[1]).toDouble() },
        ObdPid("2C", "Commanded EGR", "%", 1) { a -> a[0] * 100.0 / 255.0 },
        ObdPid("2D", "EGR Error", "%", 1) { a -> a[0] / 1.28 - 100.0 },
        ObdPid("2E", "Commanded EVAP Purge", "%", 1) { a -> a[0] * 100.0 / 255.0 },
        ObdPid("3C", "Catalyst Temp B1S1", "°C", 2) { a -> ((a[0] * 256 + a[1])) / 10.0 - 40.0 },
        ObdPid("3E", "Catalyst Temp B1S2", "°C", 2) { a -> ((a[0] * 256 + a[1])) / 10.0 - 40.0 },
        ObdPid("44", "Commanded Equivalence Ratio", "λ", 2) { a -> (a[0] * 256 + a[1]) * 2.0 / 65536.0 },
        ObdPid("49", "Accelerator Pedal D", "%", 1) { a -> a[0] * 100.0 / 255.0 },
        ObdPid("4A", "Accelerator Pedal E", "%", 1) { a -> a[0] * 100.0 / 255.0 },
        ObdPid("5C", "Engine Oil Temp", "°C", 1) { a -> (a[0] - 40).toDouble() },
        ObdPid("A6", "Odometer", "km", 4) { a ->
            (a[0] * 16777216.0 + a[1] * 65536.0 + a[2] * 256.0 + a[3]) / 10.0
        }
    )

    /** Enabled when OBD logging is first switched on. */
    val DEFAULTS: List<String> = listOf("0D", "0C", "11", "05", "0F", "42", "2F")

    fun byHex(hex: String): ObdPid? = ALL.firstOrNull { it.hex.equals(hex.trim(), ignoreCase = true) }

    /**
     * Parse an ELM327 mode 01 response payload (the hex after "41<hex>", e.g. "1AF8")
     * into the PID value. Returns null when the payload is missing/short or the
     * adapter replied with an error token (".", "ER", "NO", "VV", etc).
     */
    fun parseValue(pid: ObdPid, dataHex: String?): Double? {
        val clean = dataHex?.filter { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' } ?: return null
        if (clean.length < pid.bytes * 2) return null
        val bytes = IntArray(pid.bytes)
        for (i in 0 until pid.bytes) {
            bytes[i] = clean.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
        }
        return try {
            pid.formula(bytes)
        } catch (_: Exception) {
            null
        }
    }
}
