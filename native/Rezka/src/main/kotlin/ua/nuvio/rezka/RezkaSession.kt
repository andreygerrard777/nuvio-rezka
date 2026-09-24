package ua.nuvio.rezka

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

internal data class Links(val streams: List<Stream>, val subtitles: List<Subtitle>)

/**
 * One cookie jar is shared by search, page, Anubis and ajax requests, so a solved gate is reused
 * for as long as Nuvio keeps the plugin loaded. Mirrors are tried in order for search; every
 * later request stays on the mirror the search result came from.
 */
internal class RezkaSession(
    mirrors: List<String> = DEFAULT_MIRRORS,
    private val http: NativeHttp = NativeHttp()
) {
    private val bases = mirrors.map { (it.trimEnd('/') + "/").toHttpUrl() }
    private val mirrorHosts = bases.map { it.host }.toSet()
    private val gateLock = Any()
    private val cache = object : LinkedHashMap<String, Pair<Long, ContentPage>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, ContentPage>>?) = size > 20
    }

    private fun baseFor(url: HttpUrl): HttpUrl =
        bases.firstOrNull { sameOrigin(it, url) } ?: throw IllegalArgumentException("REZKA stage=http result=FOREIGN_URL")

    private fun originOf(base: HttpUrl) = base.toString().trimEnd('/')

    /** GET (or form POST) that follows same-origin redirects, passes Anubis and retries 5xx. */
    fun fetch(url: String, referer: String? = null, fields: Map<String, String>? = null): String {
        val target = url.toHttpUrl()
        val base = baseFor(target)
        val ref = referer ?: base.toString()
        fun once(): Page {
            var current = target
            repeat(5) {
                val page = http.request(current.toString(), ref, fields)
                if (page.status !in REDIRECTS) return page
                check(fields == null) { "REZKA stage=http result=POST_REDIRECT" }
                current = current.resolve(page.location ?: error("REZKA stage=http result=NO_LOCATION"))
                    ?: error("REZKA stage=http result=BAD_LOCATION")
                check(sameOrigin(base, current)) { "REZKA stage=http result=CROSS_ORIGIN" }
            }
            error("REZKA stage=http result=REDIRECT_LIMIT")
        }
        var page = once()
        var passes = 0
        var retries = 0
        while (true) {
            if (AnubisPow.isChallenge(page.body)) {
                // rezka.ag re-challenges individual pages under load even with a valid auth
                // cookie, so a second pass is allowed before giving up.
                check(passes < MAX_GATE_PASSES) { "REZKA stage=anubis result=CHALLENGED" }
                passes++
                passGate(base, page.body)
            } else if (page.status in RETRYABLE && retries < MAX_RETRIES) {
                retries++
                Thread.sleep(RETRY_DELAY_MS * retries) // the site answers 503 when hammered
            } else {
                break
            }
            page = once()
        }
        check(page.status in 200..299) { "REZKA stage=http status=" + page.status }
        return page.body
    }

    /**
     * Solves the challenge the gated response carried (it is bound to the verification cookie
     * that response just set), or a fresh one from the home page when the body had none.
     * The pass answers 302 + the auth cookie.
     */
    private fun passGate(base: HttpUrl, gated: String) = synchronized(gateLock) {
        val home = base.toString()
        val source = if (gated.contains("id=\"anubis_challenge\"")) gated else {
            val initial = http.request(home, home)
            if (!AnubisPow.isChallenge(initial.body)) return@synchronized
            initial.body
        }
        val challenge = try {
            AnubisPow.parse(source)
        } catch (e: IllegalArgumentException) {
            error("REZKA stage=anubis result=UNSUPPORTED_CHALLENGE")
        } catch (e: org.json.JSONException) {
            error("REZKA stage=anubis result=BAD_CHALLENGE")
        }
        val solution = AnubisPow.solve(challenge)
        val passUrl = base.newBuilder()
            .encodedPath(challenge.prefix + "/.within.website/x/cmd/anubis/api/pass-challenge")
            .query(null)
            .addQueryParameter("id", challenge.id)
            .addQueryParameter("response", solution.hash)
            .addQueryParameter("nonce", solution.nonce.toString())
            .addQueryParameter("redir", home)
            .addQueryParameter("elapsedTime", solution.elapsedMs.toString())
            .build().toString()
        var passed = http.request(passUrl, home)
        // A real browser session once got a transient 503 here before the 302: retry once.
        if (passed.status >= 500) {
            Thread.sleep(RETRY_DELAY_MS)
            passed = http.request(passUrl, home)
        }
        check(passed.status in 200..399) { "REZKA stage=anubis pass=" + passed.status + " result=PASS_REJECTED" }
    }

    /** Tries each mirror in order; the first one that answers (even with 0 hits) wins. */
    fun search(query: String): List<SearchHit> {
        val q = query.trim().take(150)
        if (q.isEmpty()) return emptyList()
        var failure: Exception? = null
        for (base in bases) {
            try {
                val html = fetch(base.resolve("engine/ajax/search.php").toString(), fields = mapOf("q" to q))
                return RezkaParser.search(html, originOf(base), mirrorHosts)
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException || e is InterruptedException) throw e
                failure = e
            }
        }
        throw failure ?: IllegalStateException("REZKA stage=search result=NO_MIRROR")
    }

    fun page(url: String): ContentPage {
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[url]?.let { (at, page) -> if (now - at < CACHE_MS) return page }
        }
        // Mirrors share one backend and one URL layout, so a page the search mirror fails to
        // serve is fetched from the others; its ajax calls then follow the mirror that answered.
        val target = url.toHttpUrl()
        val first = baseFor(target)
        val candidates = listOf(target) + bases.filterNot { it == first }.mapNotNull { it.resolve(target.encodedPath) }
        var failure: Exception? = null
        for (candidate in candidates) {
            try {
                val page = RezkaParser.page(fetch(candidate.toString()), candidate.toString())
                synchronized(cache) { cache[url] = now to page }
                return page
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException || e is InterruptedException) throw e
                failure = e
            }
        }
        throw failure!!
    }

    private fun ajax(page: ContentPage, fields: Map<String, String>): JSONObject? {
        val base = baseFor(page.url.toHttpUrl())
        val body = fetch(
            base.resolve("ajax/get_cdn_series/?t=" + System.currentTimeMillis()).toString(),
            referer = page.url, fields = fields + ("favs" to page.favs)
        )
        val json = try { JSONObject(body) } catch (e: org.json.JSONException) { return null }
        return if (json.optBoolean("success")) json else null
    }

    private fun links(json: JSONObject?, dub: Dub): Links {
        if (json == null) return Links(emptyList(), emptyList())
        val streams = RezkaParser.streams(json.optString("url"), dub.label)
        val subtitles = if (streams.isEmpty()) emptyList() else RezkaParser.subtitles(json.optString("subtitle"))
        return Links(streams, subtitles)
    }

    fun movieLinks(page: ContentPage, dub: Dub): Links = links(ajax(page, mapOf(
        "id" to dub.id, "translator_id" to dub.translator, "is_camrip" to dub.camrip,
        "is_ads" to dub.ads, "is_director" to dub.director, "action" to "get_movie"
    )), dub)

    fun episodeLinks(page: ContentPage, dub: Dub, season: Int, episode: Int): Links = links(ajax(page, mapOf(
        "id" to dub.id, "translator_id" to dub.translator,
        "season" to season.toString(), "episode" to episode.toString(), "action" to "get_stream"
    )), dub)

    /** Real frame width of a CDN mp4, read from its first 64 KiB; null when not measurable. */
    fun mp4Width(url: String): Int? = try {
        http.head(url, 64 * 1024)?.let(RezkaParser::mp4Width)
    } catch (e: Exception) {
        if (e is java.util.concurrent.CancellationException) throw e
        null
    }

    /** Episode list of a dub as the server reports it (used when the page itself has none). */
    fun episodesOf(page: ContentPage, dub: Dub): List<EpisodeRef> {
        val json = ajax(page, mapOf("id" to dub.id, "translator_id" to dub.translator, "action" to "get_episodes"))
            ?: return emptyList()
        return RezkaParser.episodes(json.optString("episodes"))
    }

    companion object {
        val DEFAULT_MIRRORS = listOf("https://rezka.ag", "https://rezka-tv.org")
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val RETRYABLE = setOf(429, 500, 502, 503, 504)
        private const val MAX_GATE_PASSES = 2
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 700L
        private const val CACHE_MS = 10 * 60 * 1000L

        fun sameOrigin(a: HttpUrl, b: HttpUrl) =
            a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }
}
