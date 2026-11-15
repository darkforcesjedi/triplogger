package com.mallner.triplogger

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage C settings-into-database logic: JSON codec round trips, DB-over-
 * mirror merge tolerance, and the SettingsDb attach/seed/pending semantics
 * against a fake key-value table (the real one needs device SQLite).
 */
class SettingsDbTest {

    private class FakeKv(
        initial: Map<String, String> = emptyMap(),
        override val id: Any = Any()
    ) : SettingsKv {
        val rows = initial.toMutableMap()
        override fun loadAll(): Map<String, String> = rows.toMap()
        override fun put(key: String, valueJson: String) { rows[key] = valueJson }
    }

    private fun settings() = AppSettings(
        bluetoothDeviceName = "ford|car box",
        loggingFrequency = 7,
        splitEnabled = true,
        maxSegmentSizeKb = 40_000L,
        forceLogging = true,
        storageLocationUri = "content://tree/storage",
        serviceEnabled = true,
        gpsEnabled = false,
        obdEnabled = true,
        obdDeviceName = "OBDII",
        obdSelectedPids = listOf("0D", "0C"),
        generateKmz = false,
        saveOds = true,
        tempLocationUri = "content://tree/journal",
        kmzLocationUri = "content://tree/kmz",
        odsLocationUri = "",
        usUnits = false,
        placeNameLookups = false,
        archiveRetentionDays = 30,
        archiveMinTripSec = 120,
        discardBelowEntries = 25,
        showHybridCharge = true,
        speedGaugeMaxUs = 120,
        speedGaugeMaxMetric = 220,
        rpmGaugeMax = 6500
    )

    @After
    fun tearDown() = SettingsDb.resetForTests()

    @Test
    fun encodeAllRoundTripsEveryField() {
        val original = settings()
        // Mirror = bare defaults, DB rows = the original: merge rebuilds it.
        val rows = SettingsCodec.encodeAll(original)
        assertFalse(rows.containsKey("temp_location_uri"))
        val merged = SettingsCodec.mergeOnto(AppSettings("", 10, false, 1L, false, "", false), rows)
        assertEquals(original.bluetoothDeviceName, merged.bluetoothDeviceName)
        assertEquals(original.loggingFrequency, merged.loggingFrequency)
        assertEquals(original.splitEnabled, merged.splitEnabled)
        assertEquals(original.maxSegmentSizeKb, merged.maxSegmentSizeKb)
        assertEquals(original.forceLogging, merged.forceLogging)
        assertEquals(original.serviceEnabled, merged.serviceEnabled)
        assertEquals(original.storageLocationUri, merged.storageLocationUri)
        assertEquals(original.gpsEnabled, merged.gpsEnabled)
        assertEquals(original.obdEnabled, merged.obdEnabled)
        assertEquals(original.obdDeviceName, merged.obdDeviceName)
        assertEquals(original.obdPollIntervalMs, merged.obdPollIntervalMs)
        assertEquals(original.obdSelectedPids, merged.obdSelectedPids)
        assertEquals(original.generateKmz, merged.generateKmz)
        assertEquals(original.saveOds, merged.saveOds)
        assertEquals(original.kmzLocationUri, merged.kmzLocationUri)
        assertEquals(original.odsLocationUri, merged.odsLocationUri)
        assertEquals(original.usUnits, merged.usUnits)
        assertEquals(original.placeNameLookups, merged.placeNameLookups)
        assertEquals(original.archiveRetentionDays, merged.archiveRetentionDays)
        assertEquals(original.archiveMinTripSec, merged.archiveMinTripSec)
        assertEquals(original.discardBelowEntries, merged.discardBelowEntries)
        assertEquals(original.showHybridCharge, merged.showHybridCharge)
        assertEquals(original.showFuelLevel, merged.showFuelLevel)
        assertEquals(original.speedGaugeMaxUs, merged.speedGaugeMaxUs)
        assertEquals(original.speedGaugeMaxMetric, merged.speedGaugeMaxMetric)
        assertEquals(original.rpmGaugeMax, merged.rpmGaugeMax)
    }

    @Test
    fun mergeIgnoresTempUriAndKeepsMirrorForMissingAndGarbage() {
        val base = settings()
        val rows = mapOf(
            "temp_location_uri" to SettingsCodec.enc("content://tree/elsewhere"),
            SettingsCodec.KEY_BLUETOOTH_DEVICE_NAME to SettingsCodec.enc("car"),
            SettingsCodec.KEY_LOGGING_FREQUENCY to "not json",
            SettingsCodec.KEY_FORCE_LOGGING to SettingsCodec.enc("yes") // wrong type
        )
        val merged = SettingsCodec.mergeOnto(base, rows)
        assertEquals("content://tree/journal", merged.tempLocationUri)
        assertEquals("car", merged.bluetoothDeviceName)
        assertEquals(base.loggingFrequency, merged.loggingFrequency)
        assertEquals(base.forceLogging, merged.forceLogging)
    }

    @Test
    fun mergeDecodesPidListLikeTheMirror() {
        val rows = mapOf(SettingsCodec.KEY_OBD_SELECTED_PIDS to SettingsCodec.enc("0d, zz ,0c,0d"))
        val merged = SettingsCodec.mergeOnto(settings(), rows)
        assertEquals(listOf("0D", "0C"), merged.obdSelectedPids)
    }

    @Test
    fun attachSeedsFreshTableFromCurrentSettings() = runBlocking {
        val kv = FakeKv()
        SettingsDb.attach(kv, settings())
        val rows = kv.rows
        assertEquals(SettingsCodec.encodeAll(settings()).keys, rows.keys)
        assertFalse(rows.containsKey("temp_location_uri"))
        assertEquals(7, SettingsCodec.mergeOnto(AppSettings("", 10, false, 1L, false, "", false), SettingsDb.snapshot()).loggingFrequency)
    }

    @Test
    fun attachLoadsExistingTableAndLocalWritesWin() = runBlocking {
        val stored = mapOf(
            SettingsCodec.KEY_BLUETOOTH_DEVICE_NAME to SettingsCodec.enc("stored car"),
            SettingsCodec.KEY_LOGGING_FREQUENCY to SettingsCodec.enc(5)
        )
        // A write made before attachment (mirror-only so far) must survive.
        SettingsDb.put(SettingsCodec.KEY_OBD_DEVICE_NAME, SettingsCodec.enc("elm"))
        val kv = FakeKv(stored)
        SettingsDb.attach(kv, settings())
        assertEquals("stored car", SettingsCodec.mergeOnto(settings(), SettingsDb.snapshot()).bluetoothDeviceName)
        assertEquals("elm", SettingsCodec.mergeOnto(settings(), SettingsDb.snapshot()).obdDeviceName)
        assertEquals(SettingsCodec.enc("elm"), kv.rows[SettingsCodec.KEY_OBD_DEVICE_NAME])
        // No 26-row seed was written: only the 2 stored rows plus the
        // single flushed pending write.
        assertEquals(3, kv.rows.size)
    }

    @Test
    fun putAfterAttachWritesThroughToTable() = runBlocking {
        val kv = FakeKv(mapOf(SettingsCodec.KEY_US_UNITS to SettingsCodec.enc(true)))
        SettingsDb.attach(kv, settings())
        SettingsDb.put(SettingsCodec.KEY_US_UNITS, SettingsCodec.enc(false))
        assertEquals(SettingsCodec.enc(false), kv.rows[SettingsCodec.KEY_US_UNITS])
        assertEquals(SettingsCodec.enc(false), SettingsDb.snapshot()[SettingsCodec.KEY_US_UNITS])
    }

    @Test
    fun detachedPutNeverTouchesAnyTableAndSurvivesNextAttach() = runBlocking {
        SettingsDb.put(SettingsCodec.KEY_RPM_GAUGE_MAX, SettingsCodec.enc(8000))
        val kv = FakeKv(mapOf(SettingsCodec.KEY_RPM_GAUGE_MAX to SettingsCodec.enc(5000)))
        SettingsDb.attach(kv, settings())
        assertEquals("8000", kv.rows[SettingsCodec.KEY_RPM_GAUGE_MAX])
        assertEquals(8000, SettingsCodec.mergeOnto(settings(), SettingsDb.snapshot()).rpmGaugeMax)
    }

    @Test
    fun emptiedTableIsReseededOnNextAttach() = runBlocking {
        val kv = FakeKv()
        SettingsDb.attach(kv, settings())
        kv.rows.clear() // simulate an emptied table; same store instance
        SettingsDb.attach(kv, settings())
        assertTrue(kv.rows.isNotEmpty()) // re-seeded because the load was empty
    }
}
