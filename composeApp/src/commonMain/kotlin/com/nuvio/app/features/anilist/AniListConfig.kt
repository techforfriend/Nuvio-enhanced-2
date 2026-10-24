package com.nuvio.app.features.anilist

object AniListConfig {
    const val CLIENT_ID = "53329"
    const val REDIRECT_URI = "nuvio://auth/anilist"
    const val AUTHORIZE_URL = "https://anilist.co/api/v2/oauth/authorize"
    const val GRAPHQL_URL = "https://graphql.anilist.co"
    const val WEBSITE_URL = "https://anilist.co"
    const val ARM_BASE_URL = "https://arm.haglund.dev/api/v2"

    val isConfigured: Boolean
        get() = CLIENT_ID.isNotBlank() && CLIENT_ID.all(Char::isDigit)
}
