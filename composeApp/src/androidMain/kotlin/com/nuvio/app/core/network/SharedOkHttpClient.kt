package com.nuvio.app.core.network

import okhttp3.OkHttpClient

internal object SharedOkHttpClient {
    val base: OkHttpClient by lazy { OkHttpClient.Builder().dns(NuvioDns).build() }
}
