package com.nuvio.app.features.vega

import platform.Foundation.NSUserDefaults

internal actual object VegaStorage {
    private const val payloadKey = "vega_providers_payload"
    private const val scriptPrefix = "vega_script_"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey)

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey)
    }

    actual fun loadScript(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey("$scriptPrefix$key")

    actual fun saveScript(key: String, script: String) {
        NSUserDefaults.standardUserDefaults.setObject(script, forKey = "$scriptPrefix$key")
    }
}
