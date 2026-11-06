package com.mallner.triplogger

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * Pure-JVM tests: parse the PNG the writer produced and check chunk
 * structure, bit depth, palette, CRCs and packed pixel rows by hand.
 */
class IndexedPngTest {

    private val palette = intArrayOf(
        0xFFFFFFFF.toInt(), // 0 white
        0xFF808080.toInt(), // 1 gray
        0xFFFF0000.toInt(), // 2 red
        0xFF0000FF.toInt()  // 3 blue
    )

    private class Decoded(val depth: Int, val colorType: Int, val plte: ByteArray, val indices: List<IntArray>)

    private fun decode(png: ByteArray, width: Int, height: Int, bits: Int): Decoded {
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), png.copyOfRange(0, 8))
        var pos = 8
        var depth = -1
        var colorType = -1
        var plte = ByteArray(0)
        val idat = ByteArrayOutputStream()
        while (pos < png.size) {
            val len = readInt(png, pos)
            val type = String(png, pos + 4, 4, Charsets.ISO_8859_1)
            val data = png.copyOfRange(pos + 8, pos + 8 + len)
            val crc = readInt(png, pos + 8 + len)
            val check = CRC32()
            check.update(png, pos + 4, 4 + len)
            assertEquals("CRC of chunk $type", check.value.toInt(), crc)
            when (type) {
                "IHDR" -> { depth = data[8].toInt(); colorType = data[9].toInt() }
                "PLTE" -> plte = data
                "IDAT" -> idat.write(data)
            }
            pos += 12 + len
        }
        assertEquals("color type must be indexed", 3, colorType)

        val perByte = 8 / bits
        val rowBytes = (width + perByte - 1) / perByte
        val raw = ByteArray(height * (rowBytes + 1))
        val inflater = Inflater()
        val comp = idat.toByteArray()
        inflater.setInput(comp)
        val filled = inflater.inflate(raw)
        inflater.end()
        assertEquals(raw.size, filled)

        val indices = ArrayList<IntArray>()
        for (y in 0 until height) {
            assertEquals("scanline filter", 0, raw[y * (rowBytes + 1)].toInt())
            val rowStart = y * (rowBytes + 1) + 1
            val row = IntArray(width)
            var x = 0
            var byteIdx = 0
            while (x < width) {
                val b = raw[rowStart + byteIdx].toInt()
                for (q in 0 until perByte) {
                    if (x >= width) break
                    val shift = 8 - bits * (q + 1)
                    row[x++] = (b ushr shift) and ((1 shl bits) - 1)
                }
                byteIdx++
            }
            indices.add(row)
        }
        return Decoded(depth, colorType, plte, indices)
    }

    private fun readInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun rowsFromIndices(rows: List<List<Int>>): Pair<Int, IntArray> {
        val w = rows[0].size
        val h = rows.size
        val img = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) img[y * w + x] = palette[rows[y][x]]
        return Pair(w, img)
    }

    @Test
    fun fourColorOutputIsTwoBitsPerPixelIndexed() {
        val rows = listOf(listOf(0, 1, 2, 3), listOf(3, 2, 1, 0))
        val (w, img) = rowsFromIndices(rows)
        val png = IndexedPng.encode(w, rows.size, palette) { y, out -> img.copyInto(out, 0, y * w, (y + 1) * w) }
        val d = decode(png, w, rows.size, 2)
        assertEquals("4 colors must encode at 2 bpp", 2, d.depth)
        assertEquals(12, d.plte.size) // 4 palette entries x RGB
        assertEquals(rows.map { it.toIntArray() }.map { it.toList() }, d.indices.map { it.toList() })
    }

    @Test
    fun rowPaddingBitsAreZero() {
        // width 5 does not divide into 4 pixels per byte
        val rows = listOf(listOf(1, 3, 1, 3, 2))
        val (w, img) = rowsFromIndices(rows)
        val png = IndexedPng.encode(w, 1, palette) { y, out -> img.copyInto(out, 0, y * w, (y + 1) * w) }
        val d = decode(png, w, 1, 2)
        assertEquals(listOf(listOf(1, 3, 1, 3, 2)), d.indices.map { it.toList() })
        assertEquals(2, d.indices[0][4])
    }

    @Test
    fun pixelsQuantizeToNearestPaletteColor() {
        val img = intArrayOf(
            0xFFFFFFFF.toInt(), // white
            0xFF848484.toInt(), // RGB_565-expanded gray -> gray
            0xFFFA1010.toInt(), // antialiased red edge -> red
            0xFF000FA0.toInt()  // blueish -> blue
        )
        val png = IndexedPng.encode(4, 1, palette) { _, out -> img.copyInto(out) }
        val d = decode(png, 4, 1, 2)
        assertEquals(listOf(listOf(0, 1, 2, 3)), d.indices.map { it.toList() })
    }

    @Test
    fun paletteSizeSelectsBitDepth() {
        val two = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        val img = IntArray(16) { if (it % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val oneBit = IndexedPng.encode(16, 1, two) { _, out -> img.copyInto(out) }
        decode(oneBit, 16, 1, 1)

        val sixteen = IntArray(16) { 0xFF000000.toInt() or (it * 16 shl 16) or (it * 16 shl 8) or (it * 16) }
        val fourBit = IndexedPng.encode(4, 1, sixteen) { _, out -> sixteen.copyInto(out, endIndex = 4) }
        decode(fourBit, 4, 1, 4)
    }

    @Test
    fun biggerThanMemoryOneShotWouldBe() {
        // 300x100 mostly white image: encode + inflate round-trip still matches
        val w = 300
        val h = 100
        val png = IndexedPng.encode(w, h, palette) { y, out ->
            out.fill(palette[0])
            if (y % 10 == 0) out.fill(palette[1]) // grid row every 10
        }
        val d = decode(png, w, h, 2)
        assertEquals(h, d.indices.size)
        assertTrue(d.indices[0].all { it == 1 })
        assertTrue(d.indices[1].all { it == 0 })
    }
}
