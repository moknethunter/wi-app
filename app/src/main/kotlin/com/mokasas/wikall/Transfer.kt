package com.mokasas.wikall

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal enum class Transfer(val entry: String) {
    REQUEST("activation-request.wirequest"),
    ACTIVATION("activation.wiactivation"),
}

internal const val ARCHIVE_LIMIT = FILE_LIMIT + 1024

internal fun packTransfer(kind: Transfer, payload: ByteArray): ByteArray {
    require(payload.size in 1..FILE_LIMIT)
    val archive = ByteArrayOutputStream()
    ZipOutputStream(archive).use { zip ->
        zip.putNextEntry(ZipEntry(kind.entry))
        zip.write(payload)
        zip.closeEntry()
    }
    return archive.toByteArray()
}

internal fun unpackTransfer(kind: Transfer, data: ByteArray): ByteArray {
    require(data.size in 1..ARCHIVE_LIMIT)
    if (data.size < 2 || data[0] != 'P'.code.toByte() || data[1] != 'K'.code.toByte()) {
        require(data.size <= FILE_LIMIT)
        return data
    }
    return ZipInputStream(ByteArrayInputStream(data)).use { zip ->
        val entry = requireNotNull(zip.nextEntry)
        require(entry.name == kind.entry && !entry.isDirectory)
        val payload = boundedRead(zip, FILE_LIMIT)
        require(zip.nextEntry == null)
        payload
    }
}

internal fun boundedRead(source: InputStream, max: Int): ByteArray {
    val buffer = ByteArray(max + 1)
    var size = 0
    while (size < buffer.size) {
        val read = source.read(buffer, size, buffer.size - size)
        if (read < 0) break
        size += read
    }
    require(size in 1..max)
    return buffer.copyOf(size)
}
