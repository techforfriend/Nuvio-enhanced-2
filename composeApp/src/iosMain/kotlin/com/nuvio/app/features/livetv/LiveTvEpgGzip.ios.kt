package com.nuvio.app.features.livetv

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.memset
import platform.zlib.MAX_WBITS
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion

@OptIn(ExperimentalForeignApi::class)
internal actual fun liveTvGunzip(bytes: ByteArray, maxOutputBytes: Int): ByteArray {
    if (bytes.isEmpty()) return bytes
    val chunks = mutableListOf<ByteArray>()
    var total = 0
    memScoped {
        val stream = alloc<z_stream>()
        memset(stream.ptr, 0, sizeOf<z_stream>().convert())
        val initResult = inflateInit2_(
            stream.ptr,
            16 + MAX_WBITS,
            zlibVersion()?.toKString(),
            sizeOf<z_stream>().toInt(),
        )
        check(initResult == Z_OK) { "Unable to decompress guide data." }
        try {
            val chunk = ByteArray(64 * 1024)
            bytes.usePinned { input ->
                chunk.usePinned { output ->
                    stream.next_in = input.addressOf(0).reinterpret()
                    stream.avail_in = bytes.size.convert()
                    while (total < maxOutputBytes) {
                        stream.next_out = output.addressOf(0).reinterpret()
                        stream.avail_out = chunk.size.convert()
                        val result = inflate(stream.ptr, Z_NO_FLUSH)
                        val produced = chunk.size - stream.avail_out.toInt()
                        if (produced > 0) {
                            val allowed = minOf(produced, maxOutputBytes - total)
                            chunks += chunk.copyOf(allowed)
                            total += allowed
                        }
                        if (result != Z_OK) break
                    }
                }
            }
        } finally {
            inflateEnd(stream.ptr)
        }
    }
    val result = ByteArray(total)
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(result, offset)
        offset += chunk.size
    }
    return result
}
