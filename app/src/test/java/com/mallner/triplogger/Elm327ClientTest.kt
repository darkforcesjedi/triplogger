package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reply classification for the ELM327 line protocol (pure string logic, no socket).
 */
class Elm327ClientTest {

    @Test
    fun okReplyForOwnPidParsesData() {
        val r = Elm327Client.classifyReply("410D46", "0D")
        assertTrue(r is ObdResponse.Ok)
        assertEquals("46", (r as ObdResponse.Ok).dataHex)
    }

    @Test
    fun emptyAndErDataAreNoData() {
        assertEquals(ObdResponse.NoData, Elm327Client.classifyReply("410D", "0D"))
        assertEquals(ObdResponse.NoData, Elm327Client.classifyReply("410DER NV", "0D"))
    }

    @Test
    fun busAndErrorStringsClassify() {
        // classifyReply receives text already stripped of spaces/CR/LF by queryOnce.
        assertEquals(ObdResponse.BusStopped, Elm327Client.classifyReply("BUSRESET", "0C"))
        assertEquals(ObdResponse.NoData, Elm327Client.classifyReply("NODATA", "0C"))
        assertEquals(ObdResponse.Error, Elm327Client.classifyReply("UNABLETOCONNECT", "0C"))
        assertEquals(ObdResponse.Error, Elm327Client.classifyReply("??????", "0C"))
    }

    @Test
    fun foreignPidFrameIsDetectedAsStale() {
        // The late reply to a previous command must never be logged as current.
        assertTrue(Elm327Client.isStaleReplyFor("410C1A3C", "0D"))
        assertFalse(Elm327Client.isStaleReplyFor("410D46", "0D"))
        assertFalse(Elm327Client.isStaleReplyFor("NO DATA", "0D"))
        // Multi-PID A/B frames (48..) are not mode-01 replies for our pid either,
        // but they are not the "41xx" stale shape and classify as Error instead.
        assertFalse(Elm327Client.isStaleReplyFor("48A410D46", "0D"))
    }
}
