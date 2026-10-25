package com.nuvio.app.features.vega

import co.touchlab.kermit.Logger
import com.nuvio.app.features.plugins.runtime.crypto.CryptoBridge
import com.nuvio.app.features.plugins.runtime.dom.DomBridge
import com.nuvio.app.features.plugins.runtime.host.HostApiRegistry
import com.nuvio.app.features.plugins.runtime.host.HostFunctions
import com.nuvio.app.features.plugins.runtime.js.JsBindings
import com.nuvio.app.features.plugins.runtime.js.JsRuntime
import com.nuvio.app.features.plugins.runtime.network.FetchBridge
import com.nuvio.app.features.plugins.runtime.network.UrlBridge
import com.nuvio.app.features.plugins.runtime.wasm.WasmBridge
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import com.nuvio.app.features.streams.StreamSubtitle
import com.nuvio.app.features.streams.normalizeStreamType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

internal object VegaRuntime {
    private val log = Logger.withTag("VegaRuntime")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val TIMEOUT_MS = 25_000L

    private val vegaAxiosContextCode = """
        var axios = (function() {
            async function ax(urlOrConfig, maybeConfig) {
                var config = maybeConfig || {};
                var url = urlOrConfig;
                if (typeof urlOrConfig === 'object' && urlOrConfig !== null) {
                    config = urlOrConfig;
                    url = config.url;
                }
                var method = (config.method || 'GET').toUpperCase();
                var headers = Object.assign({}, config.headers || {});
                var body = config.data;
                if (body && typeof body === 'object' && !(body instanceof FormData) && !(body instanceof URLSearchParams)) {
                    if (!headers['Content-Type'] && !headers['content-type']) {
                        headers['Content-Type'] = 'application/json';
                    }
                    if (headers['Content-Type'] && headers['Content-Type'].indexOf('application/json') !== -1) {
                        body = JSON.stringify(body);
                    }
                }
                var fetchOpts = {
                    method: method,
                    headers: headers
                };
                if (method !== 'GET' && method !== 'HEAD' && body !== undefined) {
                    fetchOpts.body = body;
                }
                var response = await fetch(url, fetchOpts);
                var text = await response.text();
                var data = text;
                try {
                    data = JSON.parse(text);
                } catch (e) {
                    data = text;
                }
                var respHeaders = {};
                if (response.headers) {
                    if (typeof response.headers.forEach === 'function') {
                        response.headers.forEach(function(v, k) { respHeaders[k.toLowerCase()] = v; });
                    } else {
                        for (var k in response.headers) {
                            respHeaders[k.toLowerCase()] = response.headers[k];
                        }
                    }
                }
                return {
                    data: data,
                    status: response.status,
                    statusText: response.statusText,
                    headers: respHeaders,
                    config: config
                };
            }
            ax.get = function(url, cfg) { return ax(url, Object.assign({}, cfg, { method: 'GET' })); };
            ax.post = function(url, data, cfg) { return ax(url, Object.assign({}, cfg, { method: 'POST', data: data })); };
            ax.put = function(url, data, cfg) { return ax(url, Object.assign({}, cfg, { method: 'PUT', data: data })); };
            ax.delete = function(url, cfg) { return ax(url, Object.assign({}, cfg, { method: 'DELETE' })); };
            return ax;
        })();

        var providerContext = {
            axios: axios,
            cheerio: (typeof cheerio !== 'undefined' ? cheerio : (typeof window !== 'undefined' ? window.cheerio : undefined)),
            commonHeaders: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                'Accept': '*/*'
            },
            kvStore: {
                get: async function(key) { return null; },
                set: async function(key, val) { return true; }
            }
        };
    """.trimIndent()

    suspend fun executeProvider(
        provider: VegaProviderItem,
        code: String,
        tmdbId: String,
        imdbId: String?,
        title: String,
        year: String?,
        mediaType: String,
        season: Int?,
        episode: Int?,
    private suspend fun executeJs(
        provider: VegaProviderItem,
        code: String,
        invocationJs: String,
        timeoutMs: Long = TIMEOUT_MS,
    ): String = withContext(Dispatchers.Default) {
        val jsRuntime = JsRuntime()
        val deferred = CompletableDeferred<String>()
        val domBridge = DomBridge()

        val hostRegistry = HostApiRegistry().apply {
            addModule(
                HostFunctions(
                    scraperId = provider.value,
                    scraperSettingsJson = "{}",
                    onResult = { deferred.complete(it) },
                ),
            )
            addModule(FetchBridge())
            addModule(UrlBridge())
            addModule(CryptoBridge())
            addModule(WasmBridge())
            addModule(domBridge)
        }

        try {
            withTimeout(timeoutMs) {
                jsRuntime.use {
                    hostRegistry.registerAll(this)
                    evaluate<Any?>(JsBindings.staticPolyfillCode)
                    evaluate<Any?>(vegaAxiosContextCode)

                    val wrappedCode = """
                        var module = { exports: {} };
                        var exports = module.exports;
                        (function() {
                            $code
                        })();
                    """.trimIndent()
                    evaluate<Any?>(wrappedCode)
                    evaluate<Any?>(invocationJs)
                    deferred.await()
                }
            }
        } catch (e: Exception) {
            log.w(e) { "Vega JS execution error in ${provider.displayName}" }
            "[]"
        }
    }

    suspend fun executeCatalog(provider: VegaProviderItem, code: String): List<VegaCatalogCategory> {
        val invocation = """
            (async function() {
                try {
                    var cat = module.exports.catalog || exports.catalog || globalThis.catalog;
                    if (typeof cat === 'function') cat = cat();
                    if (!cat || !Array.isArray(cat)) cat = [];
                    __capture_result(JSON.stringify(cat));
                } catch(e) {
                    console.error("Vega catalog error in ${provider.displayName}:", e && e.message ? e.message : e);
                    __capture_result(JSON.stringify([]));
                }
            })();
        """.trimIndent()
        val raw = executeJs(provider, code, invocation)
        return runCatching {
            json.decodeFromString<List<VegaCatalogCategory>>(raw)
        }.getOrElse {
            listOf(VegaCatalogCategory(title = "Featured", filter = ""))
        }.ifEmpty {
            listOf(VegaCatalogCategory(title = "Featured", filter = ""))
        }
    }

    suspend fun executePosts(provider: VegaProviderItem, code: String, filter: String, page: Int): List<VegaPost> {
        val filterArg = JsonPrimitive(filter).toString()
        val pageArg = page.toString()
        val invocation = """
            (async function() {
                try {
                    var fn = module.exports.getPosts || exports.getPosts || globalThis.getPosts;
                    if (!fn) {
                        __capture_result(JSON.stringify([]));
                        return;
                    }
                    var posts = await fn({
                        filter: $filterArg,
                        page: $pageArg,
                        providerContext: providerContext,
                        signal: undefined
                    });
                    __capture_result(JSON.stringify(posts || []));
                } catch(e) {
                    console.error("Vega getPosts error in ${provider.displayName}:", e && e.message ? e.message : e);
                    __capture_result(JSON.stringify([]));
                }
            })();
        """.trimIndent()
        val raw = executeJs(provider, code, invocation)
        return runCatching {
            json.decodeFromString<List<VegaPost>>(raw)
        }.getOrElse { emptyList() }
    }

    suspend fun executeSearchPosts(provider: VegaProviderItem, code: String, query: String, page: Int): List<VegaPost> {
        val queryArg = JsonPrimitive(query).toString()
        val pageArg = page.toString()
        val invocation = """
            (async function() {
                try {
                    var fn = module.exports.getSearchPosts || exports.getSearchPosts || globalThis.getSearchPosts;
                    if (!fn) {
                        __capture_result(JSON.stringify([]));
                        return;
                    }
                    var posts = await fn({
                        searchQuery: $queryArg,
                        page: $pageArg,
                        providerContext: providerContext,
                        signal: undefined
                    });
                    __capture_result(JSON.stringify(posts || []));
                } catch(e) {
                    console.error("Vega getSearchPosts error in ${provider.displayName}:", e && e.message ? e.message : e);
                    __capture_result(JSON.stringify([]));
                }
            })();
        """.trimIndent()
        val raw = executeJs(provider, code, invocation)
        return runCatching {
            json.decodeFromString<List<VegaPost>>(raw)
        }.getOrElse { emptyList() }
    }

    suspend fun executeEpisodes(provider: VegaProviderItem, episodesCode: String, url: String): List<VegaEpisode> {
        val urlArg = JsonPrimitive(url).toString()
        val invocation = """
            (async function() {
                try {
                    var fn = module.exports.getEpisodes || exports.getEpisodes || globalThis.getEpisodes;
                    if (!fn) {
                        __capture_result(JSON.stringify([]));
                        return;
                    }
                    var eps = await fn({
                        url: $urlArg,
                        providerContext: providerContext,
                        signal: undefined
                    });
                    __capture_result(JSON.stringify(eps || []));
                } catch(e) {
                    console.error("Vega getEpisodes error in ${provider.displayName}:", e && e.message ? e.message : e);
                    __capture_result(JSON.stringify([]));
                }
            })();
        """.trimIndent()
        val raw = executeJs(provider, episodesCode, invocation)
        return runCatching {
            json.decodeFromString<List<VegaEpisode>>(raw)
        }.getOrElse { emptyList() }
    }

    suspend fun executeMeta(provider: VegaProviderItem, metaCode: String, episodesCode: String?, link: String): VegaMetaResult? {
        val linkArg = JsonPrimitive(link).toString()
        val invocation = """
            (async function() {
                try {
                    var fn = module.exports.getMeta || exports.getMeta || globalThis.getMeta;
                    if (!fn) {
                        __capture_result(JSON.stringify(null));
                        return;
                    }
                    var meta = await fn({
                        link: $linkArg,
                        providerContext: providerContext,
                        signal: undefined
                    });
                    __capture_result(JSON.stringify(meta || null));
                } catch(e) {
                    console.error("Vega getMeta error in ${provider.displayName}:", e && e.message ? e.message : e);
                    __capture_result(JSON.stringify(null));
                }
            })();
        """.trimIndent()
        val raw = executeJs(provider, metaCode, invocation)
        if (raw.isBlank() || raw == "null" || raw == "[]") return null
        var metaResult = runCatching {
            json.decodeFromString<VegaMetaResult>(raw)
        }.getOrNull() ?: return null

        if (episodesCode != null && metaResult.episodes.isEmpty()) {
            val episodesLinks = metaResult.linkList.mapNotNull { it.episodesLink }.filter { it.isNotBlank() }
            val allEpisodes = mutableListOf<VegaEpisode>()
            if (episodesLinks.isNotEmpty()) {
                for (epLink in episodesLinks) {
                    val eps = executeEpisodes(provider, episodesCode, epLink)
                    allEpisodes.addAll(eps)
                }
            } else if (metaResult.type.equals("series", ignoreCase = true) || metaResult.type.equals("tv", ignoreCase = true)) {
                val eps = executeEpisodes(provider, episodesCode, link)
                allEpisodes.addAll(eps)
            }
            if (allEpisodes.isNotEmpty()) {
                metaResult = metaResult.copy(episodes = allEpisodes)
            }
        }
        return metaResult
    }

    suspend fun executeDirectStream(provider: VegaProviderItem, code: String, link: String, type: String): List<StreamItem> {
        val linkArg = JsonPrimitive(link).toString()
        val typeArg = JsonPrimitive(type).toString()
        val invocation = """
            (async function() {
                try {
                    var getStream = module.exports.getStream || globalThis.getStream;
                    if (!getStream && typeof module.exports === 'function') {
                        getStream = module.exports;
                    }
                    if (!getStream) {
                        console.error("getStream not found in provider ${provider.displayName}");
                        __capture_result(JSON.stringify([]));
                        return;
                    }
                    var result = await getStream({
                        link: $linkArg,
                        type: $typeArg,
                        providerContext: providerContext,
                        signal: undefined,
                        isDownload: false
                    });
                    __capture_result(JSON.stringify(result || []));
                } catch (e) {
                    console.error("Vega direct getStream error in ${provider.displayName}:", e && e.message ? e.message : e);
                    __capture_result(JSON.stringify([]));
                }
            })();
        """.trimIndent()
        val raw = executeJs(provider, code, invocation)
        return parseVegaResults(provider, raw)
    }

    suspend fun executeProvider(
        provider: VegaProviderItem,
        code: String,
        tmdbId: String,
        imdbId: String?,
        title: String,
        year: String?,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): List<StreamItem> = withContext(Dispatchers.Default) {
        log.d { "Executing Vega provider ${provider.displayName} for title=$title tmdb=$tmdbId type=$mediaType" }

        val tmdbIdArg = JsonPrimitive(tmdbId).toString()
        val imdbIdArg = JsonPrimitive(imdbId.orEmpty()).toString()
        val titleArg = JsonPrimitive(title).toString()
        val yearArg = JsonPrimitive(year.orEmpty()).toString()
        val mediaTypeArg = JsonPrimitive(mediaType).toString()
        val seasonArg = season?.toString() ?: "1"
        val episodeArg = episode?.toString() ?: "1"

        val callCode = """
            (async function() {
                try {
                    var getStream = module.exports.getStream || globalThis.getStream;
                    if (!getStream && typeof module.exports === 'function') {
                        getStream = module.exports;
                    }
                    if (!getStream) {
                        console.error("getStream not found in provider ${provider.displayName}");
                        __capture_result(JSON.stringify([]));
                        return;
                    }
                    var payload = {
                        tmdbId: $tmdbIdArg,
                        imdbId: $imdbIdArg,
                        title: $titleArg,
                        year: $yearArg,
                        type: $mediaTypeArg,
                        season: $seasonArg,
                        episode: $episodeArg
                    };
                    var result = await getStream({
                        link: JSON.stringify(payload),
                        type: $mediaTypeArg,
                        providerContext: providerContext,
                        signal: undefined,
                        isDownload: false
                    });
                    __capture_result(JSON.stringify(result || []));
                } catch (e) {
                    console.error("Vega getStream error in ${provider.displayName}:", e && e.message ? e.message : e);
                    __capture_result(JSON.stringify([]));
                }
            })();
        """.trimIndent()

        val rawResult = executeJs(provider, code, callCode)
        parseVegaResults(provider, rawResult)
    }

    private fun parseVegaResults(provider: VegaProviderItem, raw: String): List<StreamItem> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return emptyList()

        return runCatching {
            val results = json.decodeFromString<List<VegaStreamResult>>(trimmed)
            results.mapNotNull { item ->
                val link = item.link.trim()
                if (link.isBlank()) return@mapNotNull null

                val requestHeaders = item.headers.orEmpty()
                    .filterKeys { it.isNotBlank() && !it.equals("Range", ignoreCase = true) }

                val qualityStr = item.quality?.takeIf { it.isNotBlank() }
                val typeStr = item.type?.takeIf { it.isNotBlank() }
                val subtitleParts = listOfNotNull(qualityStr, typeStr?.uppercase())

                StreamItem(
                    name = item.server.ifBlank { provider.displayName },
                    title = "${provider.displayName} • ${item.server.ifBlank { "Stream" }}",
                    description = subtitleParts.joinToString(" • ").ifBlank { null },
                    url = link,
                    sourceName = item.server.ifBlank { provider.displayName },
                    addonName = "Vega - ${provider.displayName}",
                    addonId = "vega:${provider.value}",
                    streamType = normalizeStreamType(item.type),
                    behaviorHints = if (requestHeaders.isEmpty()) {
                        StreamBehaviorHints()
                    } else {
                        StreamBehaviorHints(
                            notWebReady = true,
                            proxyHeaders = StreamProxyHeaders(request = requestHeaders),
                        )
                    },
                    externalSubtitles = item.subtitles?.mapNotNull { sub ->
                        val subUri = sub.uri.trim()
                        if (subUri.isBlank()) null
                        else StreamSubtitle(
                            url = subUri,
                            lang = sub.language ?: "en",
                            title = sub.title,
                        )
                    },
                )
            }
        }.getOrElse { error ->
            log.w(error) { "Failed to parse Vega results from ${provider.displayName}" }
            emptyList()
        }
    }
}
