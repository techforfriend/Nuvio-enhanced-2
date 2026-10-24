package com.nuvio.app.features.wetrakr

object WeTrakrConfig {
    const val CLIENT_ID = "193cadea493eaf3f7f4cdcbba020f577"
    const val API_BASE_URL = "https://api.wetrakr.com"
    const val API_VERSION = "1"
    const val WEBSITE_URL = "https://wetrakr.com"
    const val DEFAULT_VERIFICATION_URL = "https://wetrakr.com/activate"
    const val USER_AGENT = "NuvioMobile"
    const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p"

    val isConfigured: Boolean
        get() = CLIENT_ID.isNotBlank()
}
