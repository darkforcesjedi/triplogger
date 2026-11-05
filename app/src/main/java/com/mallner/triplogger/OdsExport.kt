package com.mallner.triplogger

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal OpenDocument spreadsheet writer (no external dependencies).
 * Produces .ods files that open in Google Sheets, Excel and LibreOffice.
 */
object OdsExport {

    fun build(sheetName: String, headers: List<String>, rows: List<List<Any?>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // The mimetype entry must be first and stored uncompressed
            val mimetype = ZipEntry("mimetype")
            mimetype.method = ZipEntry.STORED
            val mimeBytes = "application/vnd.oasis.opendocument.spreadsheet".toByteArray(Charsets.UTF_8)
            mimetype.size = mimeBytes.size.toLong()
            mimetype.compressedSize = mimeBytes.size.toLong()
            val crc = CRC32()
            crc.update(mimeBytes)
            mimetype.crc = crc.value
            zip.putNextEntry(mimetype)
            zip.write(mimeBytes)
            zip.closeEntry()

            zip.writeEntry("META-INF/manifest.xml", MANIFEST_XML)
            zip.writeEntry("content.xml", contentXml(sheetName, headers, rows))
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private const val MANIFEST_XML =
        """<?xml version="1.0" encoding="UTF-8"?>""" + "\n" +
        """<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">""" + "\n" +
        """ <manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.spreadsheet"/>""" + "\n" +
        """ <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>""" + "\n" +
        """</manifest:manifest>"""

    private fun contentXml(sheetName: String, headers: List<String>, rows: List<List<Any?>>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<office:document-content ")
        sb.append("xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" ")
        sb.append("xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" ")
        sb.append("xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" ")
        sb.append("office:version=\"1.2\">\n")
        sb.append("<office:body><office:spreadsheet>")
        sb.append("<table:table table:name=\"").append(escape(sheetName)).append("\">")

        // Column headers
        sb.append("<table:table-row>")
        headers.forEach { h ->
            sb.append("<table:table-cell office:value-type=\"string\"><text:p>")
                .append(escape(h)).append("</text:p></table:table-cell>")
        }
        sb.append("</table:table-row>\n")

        rows.forEach { row ->
            sb.append("<table:table-row>")
            row.forEach { cell ->
                when (cell) {
                    null -> sb.append("<table:table-cell/>")
                    is Double, is Float -> {
                        val v = (cell as Number).toDouble()
                        if (v.isNaN() || v.isInfinite()) sb.append("<table:table-cell/>")
                        else sb.append("<table:table-cell office:value-type=\"float\" office:value=\"").append(v)
                            .append("\"><text:p>").append(v).append("</text:p></table:table-cell>")
                    }
                    is Number -> sb.append("<table:table-cell office:value-type=\"float\" office:value=\"").append(cell)
                        .append("\"><text:p>").append(cell).append("</text:p></table:table-cell>")
                    else -> sb.append("<table:table-cell office:value-type=\"string\"><text:p>")
                        .append(escape(cell.toString())).append("</text:p></table:table-cell>")
                }
            }
            sb.append("</table:table-row>\n")
        }

        sb.append("</table:table></office:spreadsheet></office:body></office:document-content>")
        return sb.toString()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
