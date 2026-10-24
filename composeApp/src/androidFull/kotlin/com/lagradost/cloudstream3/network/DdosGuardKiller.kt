package com.lagradost.cloudstream3.network

import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.cookies
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** Binary-compatible DDoS-Guard interceptor expected by standard CloudStream plugins. */
@AnyThread
class DdosGuardKiller(private val alwaysBypass: Boolean) : Interceptor {
    val savedCookiesMap: MutableMap<String, Map<String, String>> = ConcurrentHashMap()
    private var ddosBypassPath: String? = null

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()
        if (alwaysBypass) return@runBlocking bypassDdosGuard(chain, request)
        val response = chain.proceed(request)
        if (response.code == 403) {
            response.close()
            bypassDdosGuard(chain, request)
        } else {
            response
        }
    }

    private suspend fun bypassDdosGuard(chain: Interceptor.Chain, request: Request): Response {
        ddosBypassPath = ddosBypassPath ?: Regex("'(.*?)'").find(
            app.get("https://check.ddos-guard.net/check.js").text,
        )?.groupValues?.getOrNull(1)

        val cookies = savedCookiesMap[request.url.host]
            ?: app.get(
                request.url.scheme + "://" + request.url.host + ddosBypassPath.orEmpty(),
            ).cookies.also { savedCookiesMap[request.url.host] = it }
        val headers = getHeaders(request.headers.toMap(), cookies + request.cookies)
        return chain.proceed(request.newBuilder().headers(headers).build())
    }
}
