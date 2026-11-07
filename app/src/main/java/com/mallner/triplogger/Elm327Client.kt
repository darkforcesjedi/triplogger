package com.mallner.triplogger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

sealed class ObdResponse {
    data class Ok(val dataHex: String) : ObdResponse()
    data object NoData : ObdResponse()
    data object BusStopped : ObdResponse()
    data object Error : ObdResponse()
    data object Timeout : ObdResponse()
}

/**
 * Talks to an ELM327-compatible adapter over the classic Bluetooth SPP RFCOMM
 * channel. Adapters like the "JFIND" clone cannot be paired normally, so an
 * insecure socket is used (no bonding required).
 */
class Elm327Client(private val device: BluetoothDevice) {

    companion object {
        private const val TAG = "Elm327Client"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val INIT_TIMEOUT_MS = 5000L
        private const val CMD_TIMEOUT_MS = 2500L
        private const val MAX_TRIES = 3

        /** Classify a cleaned mode-01 reply for [hex]; visible for tests. */
        internal fun classifyReply(cleaned: String, hex: String): ObdResponse {
            val expected = "41$hex"
            return when {
                cleaned.startsWith(expected, ignoreCase = true) -> {
                    val data = cleaned.substring(expected.length)
                    if (data.isEmpty() || data == "." || data.startsWith("ER")) ObdResponse.NoData else ObdResponse.Ok(data)
                }
                cleaned.contains("NODATA", true) || cleaned == "." -> ObdResponse.NoData
                cleaned.contains("BUS", true) || cleaned.contains("STOPPED", true) -> ObdResponse.BusStopped
                cleaned.contains("ERROR", true) || cleaned.contains("UNKNOWN", true) -> ObdResponse.Error
                else -> ObdResponse.Error
            }
        }

        /** True when the reply is a well-formed "41xx" frame for a different PID. */
        internal fun isStaleReplyFor(reply: String, hex: String): Boolean =
            Regex("^41[0-9A-Fa-f]{2}").containsMatchIn(reply) && !reply.startsWith("41$hex", ignoreCase = true)
    }

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    val isConnected: Boolean get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun connect() {
        var lastError: Exception? = null
        // ELM327 clones (like JFIND) often have broken SDP records and/or demand an
        // encrypted link. Try, in order, strategies from least to most intrusive:
        //  1. insecure socket on the SPP UUID            - no bonding, most clones
        //  2. insecure socket on channel 1 directly     - no bonding, bypasses SDP
        //  3. secure socket on the SPP UUID             - may trigger pairing prompt
        //  4. secure socket on channel 1                - may trigger pairing prompt
        val insecureChannelOne = {
            @Suppress("DiscouragedPrivateApi")
            val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            m.invoke(device, 1) as BluetoothSocket
        }
        val secureChannelOne = {
            @Suppress("DiscouragedPrivateApi")
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            m.invoke(device, 1) as BluetoothSocket
        }
        val attempts = listOf<() -> BluetoothSocket>(
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            insecureChannelOne,
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            secureChannelOne
        )
        for (factory in attempts) {
            try {
                val s = factory()
                s.connect()
                socket = s
                input = s.inputStream
                output = s.outputStream
                Log.d(TAG, "Socket connected to ${device.name}")
                initialize()
                return
            } catch (e: Exception) {
                lastError = e
                try { socket?.close() } catch (_: Exception) {}
                socket = null
            }
        }
        // All socket strategies failed. If the adapter is not bonded yet, ask the
        // system to bond it so the next retry (a few seconds away) has a chance to
        // use an encrypted link.
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            try {
                Log.i(TAG, "Attempting to bond ${device.name} (enter PIN 1234 if prompted)")
                device.createBond()
            } catch (_: SecurityException) {
            }
        }
        throw IOException("Could not connect to ${device.name}: ${lastError?.message}", lastError)
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }

    private fun initialize() {
        drain()
        // Reset: expect "ELM327 ..." banner then OK
        var banner: String? = null
        for (i in 0 until MAX_TRIES) {
            banner = send("ATZ", INIT_TIMEOUT_MS)
            if (banner != null) break
        }
        if (banner == null) throw IOException("ELM327 did not answer ATZ")
        send("ATE0", CMD_TIMEOUT_MS)      // echo off
        send("ATL0", CMD_TIMEOUT_MS)      // no linefeeds
        send("ATSP0", CMD_TIMEOUT_MS)     // protocol 0 = automatic
        // Some clones do not obey ATSP0; verify with a live PID and fall back to CAN 500k
        if (queryPid("01") is ObdResponse.Timeout) {
            send("ATSP6", CMD_TIMEOUT_MS)
        }
    }

    /** Mode 01 query. hex e.g. "0D". */
    fun queryPid(hex: String): ObdResponse {
        val first = queryOnce(hex)
        // A reply framed for a *different* PID means a late response to an earlier
        // timed-out command: the adapter is lagging one step behind. Ask again once;
        // if it happens again treat the link as unreliable rather than logging the
        // foreign (and possibly stale) value.
        if (first.second?.let { isStaleReplyFor(it, hex) } == true) {
            drain()
            val second = queryOnce(hex)
            return if (second.second?.let { isStaleReplyFor(it, hex) } == true) ObdResponse.Error else second.first
        }
        return first.first
    }

    private fun queryOnce(hex: String): Pair<ObdResponse, String?> {
        val reply = send("01$hex", CMD_TIMEOUT_MS) ?: return ObdResponse.Timeout to null
        val cleaned = reply.trim().replace(" ", "").replace("\r", "").replace("\n", "")
        return classifyReply(cleaned, hex) to cleaned
    }

    fun sendRaw(command: String, timeoutMs: Long = CMD_TIMEOUT_MS): String? = send(command, timeoutMs)

    private fun send(command: String, timeoutMs: Long): String? {
        val out = output ?: throw IOException("not connected")
        // Discard anything that arrived after a previous timed-out command, so a
        // late reply can never be mistaken for the response to this request.
        drain()
        out.write((command + "\r").toByteArray(StandardCharsets.US_ASCII))
        out.flush()
        return readUntilPrompt(timeoutMs)
    }

    /**
     * Reads up to the ELM327 '>' end-of-response prompt. A timeout returns null
     * even if partial bytes arrived: an incomplete frame is worse than none, and
     * the remainder is drained before the next command.
     */
    private fun readUntilPrompt(timeoutMs: Long): String? {
        val `in` = input ?: return null
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteArray(128)
        while (System.currentTimeMillis() < deadline) {
            while (`in`.available() > 0) {
                val n = `in`.read(buffer)
                if (n < 0) throw IOException("adapter disconnected")
                val chunk = String(buffer, 0, n, StandardCharsets.US_ASCII)
                sb.append(chunk)
                if (chunk.contains('>')) {
                    val response = sb.toString().substringBeforeLast('>')
                    return response.trim().ifEmpty { null }
                }
            }
            try {
                Thread.sleep(15)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    private fun drain() {
        try {
            val `in` = input ?: return
            while (`in`.available() > 0) `in`.read(ByteArray(64))
        } catch (_: Exception) {
        }
    }
}
