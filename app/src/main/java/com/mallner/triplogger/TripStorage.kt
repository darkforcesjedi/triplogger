package com.mallner.triplogger

import java.io.File

/** Storage for final output artifacts (daily KMZ files, ODS spreadsheets). */
interface TripStorage {
    fun read(name: String): ByteArray?

    /** Write atomically where possible (temp file + rename). */
    fun write(name: String, bytes: ByteArray)
}

/** Simple java.io.File implementation (app files dir, unit tests). */
class DirStorage(private val dir: File) : TripStorage {
    override fun read(name: String): ByteArray? = File(dir, name).takeIf { it.isFile }?.readBytes()

    override fun write(name: String, bytes: ByteArray) {
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }
}
