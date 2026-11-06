package com.mallner.triplogger

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Minimal PNG writer for color-type-3 (indexed palette) images, matching the
 * small indexed files the Python TorqueLogs converter produced: a 4-color
 * plot at 2 bits per pixel compresses to kilobytes where a truecolor
 * Bitmap.compress PNG costs hundreds of kilobytes - across hundreds of
 * megabytes of logs that difference is several gigabytes.
 *
 * Pure JVM so it is unit-testable: pixels arrive one row at a time through
 * [readRow] (an Android Bitmap is read row-wise to avoid a 100 MB int array
 * at max plot size) and are quantized to the nearest palette entry, which
 * absorbs RGB_565 expansion shifts (the renderer draws aliased text and
 * geometry so pixels are already exact palette colors).
 */
object IndexedPng {

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val EMPTY = ByteArray(0)

    /**
     * @param palette  up to 256 opaque colors as 0xAARRGGBB (alpha ignored);
     *                 bit depth is derived: <=2 colors 1 bpp, <=4 2 bpp, <=16 4 bpp
     * @param readRow  fills argb[0 until width] with row y
     */
    fun encode(width: Int, height: Int, palette: IntArray, readRow: (y: Int, argb: IntArray) -> Unit): ByteArray {
        require(width > 0 && height > 0) { "empty image" }
        require(palette.isNotEmpty() && palette.size <= 256) { "palette must hold 1..256 colors" }
        val bits = when {
            palette.size <= 2 -> 1
            palette.size <= 4 -> 2   // the 4-color plot design
            palette.size <= 16 -> 4
            else -> 8
        }
        val perByte = 8 / bits
        val rowBytes = (width + perByte - 1) / perByte

        val pr = IntArray(palette.size) { (palette[it] shr 16) and 0xFF }
        val pg = IntArray(palette.size) { (palette[it] shr 8) and 0xFF }
        val pb = IntArray(palette.size) { palette[it] and 0xFF }

        val raw = ByteArray(height * (rowBytes + 1))
        val px = IntArray(width)
        var pos = 0
        var lastColor = Int.MIN_VALUE
        var lastIndex = 0
        for (y in 0 until height) {
            readRow(y, px)
            raw[pos++] = 0 // filter: none
            var x = 0
            while (x < width) {
                var acc = 0
                for (q in 0 until perByte) {
                    if (x < width) {
                        val color = px[x++]
                        if (color != lastColor) {
                            lastColor = color
                            lastIndex = nearest(color, pr, pg, pb)
                        }
                        acc = (acc shl bits) or lastIndex
                    } else {
                        acc = acc shl bits // padding bits stay palette index 0 / zero
                    }
                }
                raw[pos++] = acc.toByte()
            }
        }

        val deflater = Deflater(9)
        deflater.setInput(raw, 0, pos)
        deflater.finish()
        val idat = ByteArrayOutputStream(pos / 8 + 64)
        val buffer = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            if (n > 0) idat.write(buffer, 0, n)
        }
        deflater.end()

        val ihdr = ByteArray(13)
        writeInt(ihdr, 0, width)
        writeInt(ihdr, 4, height)
        ihdr[8] = bits.toByte()
        ihdr[9] = 3 // color type: indexed
        ihdr[10] = 0 // deflate
        ihdr[11] = 0 // adaptive filtering (we use filter 0 on every row)
        ihdr[12] = 0 // no interlace

        val plte = ByteArray(palette.size * 3)
        palette.forEachIndexed { i, c ->
            plte[i * 3] = ((c shr 16) and 0xFF).toByte()
            plte[i * 3 + 1] = ((c shr 8) and 0xFF).toByte()
            plte[i * 3 + 2] = (c and 0xFF).toByte()
        }

        val png = ByteArrayOutputStream(idat.size() + 256)
        png.write(PNG_SIGNATURE)
        writeChunk(png, "IHDR", ihdr)
        writeChunk(png, "PLTE", plte)
        writeChunk(png, "IDAT", idat.toByteArray())
        writeChunk(png, "IEND", EMPTY)
        return png.toByteArray()
    }

    private fun nearest(argb: Int, pr: IntArray, pg: IntArray, pb: IntArray): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        var best = 0
        var bestDist = Int.MAX_VALUE
        for (i in pr.indices) {
            val dr = r - pr[i]
            val dg = g - pg[i]
            val db = b - pb[i]
            val d = dr * dr + dg * dg + db * db
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeChunk(out: OutputStream, type: String, data: ByteArray) {
        val length = ByteArray(4)
        writeInt(length, 0, data.size)
        out.write(length)
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val checksum = ByteArray(4)
        writeInt(checksum, 0, crc.value.toInt())
        out.write(checksum)
    }
}
