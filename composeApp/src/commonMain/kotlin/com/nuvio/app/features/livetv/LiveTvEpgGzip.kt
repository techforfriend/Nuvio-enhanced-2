package com.nuvio.app.features.livetv

internal expect fun liveTvGunzip(bytes: ByteArray, maxOutputBytes: Int): ByteArray
