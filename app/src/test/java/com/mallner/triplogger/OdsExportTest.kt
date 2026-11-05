package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class OdsExportTest {

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                map[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        return map
    }

    @Test
    fun producesValidOdsStructure() {
        val bytes = OdsExport.build(
            "engine",
            listOf("Time", "RPM (rpm)"),
            listOf(
                listOf<Any?>("2026-01-19 06:00:00.000", 1726.0),
                listOf<Any?>("2026-01-19 06:00:01.000", null)
            )
        )
        val entries = entries(bytes)
        val names = entries.keys.toList()
        assertEquals("mimetype must be first entry", "mimetype", names.first())
        assertEquals("application/vnd.oasis.opendocument.spreadsheet", String(entries["mimetype"]!!))
        assertTrue(entries.containsKey("META-INF/manifest.xml"))
        assertTrue(entries.containsKey("content.xml"))

        // content.xml must be well-formed XML and contain the data
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(entries["content.xml"]!!))
        val xml = String(entries["content.xml"]!!)
        assertTrue(xml.contains("1726.0"))
        assertTrue(xml.contains("2026-01-19 06:00:00.000"))
        assertEquals(3, doc.getElementsByTagName("table:table-row").length)
    }
}
