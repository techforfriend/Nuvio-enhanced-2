package com.nuvio.app.features.vega

internal expect object VegaStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
    fun loadScript(key: String): String?
    fun saveScript(key: String, script: String)
}
