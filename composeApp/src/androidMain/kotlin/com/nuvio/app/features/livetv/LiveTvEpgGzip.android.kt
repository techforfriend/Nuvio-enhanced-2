package com.nuvio.app.features.livetv

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.zip.GZIPInputStream

internal actual fun liveTvGunzip(bytes: ByteArray, maxOutputBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxOf(bytes.size * 4, 64 * 1024), maxOutputBytes))
    GZIPInputStream(ByteArrayInputStream(bytes), 64 * 1024).use { input ->
        val buffer = ByteArray(64 * 1024)
        var total = 0
        try {
            while (total < maxOutputBytes) {
                val read = input.read(buffer, 0, minOf(buffer.size, maxOutputBytes - total))
                if (read <= 0) break
                output.write(buffer, 0, read)
                total += read
            }
        } catch (_: EOFException) {
        }
    }
    return output.toByteArray()
}
