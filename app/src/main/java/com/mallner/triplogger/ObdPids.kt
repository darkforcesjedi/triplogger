package com.mallner.triplogger

/**
 * Catalog of supported OBD-II mode 01 PIDs plus ELM327 hex response parsing.
 * List order is the display order in Settings: most useful / most commonly
 * supported sensors first. Values are parsed to their SI units; conversions to
 * mph etc. happen in the exporters.
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
        ObdPid("5F", "Ambient Air Temp", "°C", 1) { a -> (a[0] - 40).toDouble() },
        ObdPid("42", "Control Module Voltage", "V", 2) { a -> (a[0] * 256 + a[1]) / 1000.0 },
        ObdPid("51", "Fuel Level", "%", 1) { a -> a[0] * 100.0 / 255.0 },
        ObdPid("04", "Calculated Engine Load", "%", 1) { a -> a[0] * 100.0 / 255.0 },
        ObdPid("0B", "Intake Manifold Pressure", "kPa", 1) { a -> a[0].toDouble() },
        ObdPid("10", "MAF Air Flow", "g/s", 2) { a -> (a[0] * 256 + a[1]) / 100.0 },
        ObdPid("5E", "Fuel Rate", "L/h", 2) { a -> (a[0] * 256 + a[1]) / 20.0 },
        ObdPid("0E", "Timing Advance", "°", 1) { a -> a[0] / 2.0 - 35.0 },
        ObdPid("0A", "Fuel Pressure", "kPa", 1) { a -> a[0] * 3.0 },
        ObdPid("23", "Fuel Rail Pressure (gauge)", "kPa", 2) { a -> (a[0] * 256 + a[1]) * 10.0 },
        ObdPid("33", "Barometric Pressure", "kPa", 1) { a -> a[0].toDouble() },
        ObdPid("06", "O2 Trim Short Term B1", "%", 1) { a -> a[0] * 100.0 / 1.28 - 10000.0 },
        ObdPid("07", "O2 Trim Long Term B1", "%", 1) { a -> a[0] * 100.0 / 1.28 - 10000.0 },
        ObdPid("08", "O2 Trim Short Term B2", "%", 1) { a -> a[0] * 100.0 / 1.28 - 10000.0 },
        ObdPid("09", "O2 Trim Long Term B2", "%", 1) { a -> a[0] * 100.0 / 1.28 - 10000.0 },
        ObdPid("1F", "Run Time Since Engine Start", "s", 2) { a -> (a[0] * 256 + a[1]).toDouble() },
        ObdPid("21", "Distance Since DTC Clear", "km", 2) { a -> (a[0] * 256 + a[1]).toDouble() },
        ObdPid("2C", "EGR Error", "%", 1) { a -> (a[0] - 128) * 100.0 / 255.0 },
        ObdPid("2D", "Commanded EGR", "%", 1) { a -> a[0] * 100.0 / 255.0 },
        ObdPid("34", "Catalyst Temp B1S1", "°C", 2) { a -> ((a[0] * 256 + a[1])) / 10.0 - 40.0 },
        ObdPid("35", "Catalyst Temp B1S2", "°C", 2) { a -> ((a[0] * 256 + a[1])) / 10.0 - 40.0 },
        ObdPid("4C", "Commanded EVAP Purge", "%", 1) { a -> a[0] * 100.0 / 255.0 },
        ObdPid("49", "PTO Engine Load", "%", 1) { a -> a[0] * 100.0 / 2.57 },
        ObdPid("62", "Pedal Position D", "%", 2) { a -> (a[0] * 256 + a[1]) * 100.0 / 65536.0 },
        ObdPid("63", "Pedal Position E", "%", 2) { a -> (a[0] * 256 + a[1]) * 100.0 / 65536.0 }
    )

    /** Enabled when OBD logging is first switched on. */
    val DEFAULTS: List<String> = listOf("0D", "0C", "11", "05", "0F", "42", "51")

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
