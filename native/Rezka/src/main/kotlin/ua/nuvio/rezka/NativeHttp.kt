package ua.nuvio.rezka

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The plugin keeps its own cookie jar instead of the host's shared client: the Anubis auth
 * cookie arrives on a 302 and must be sent on every later search/page/ajax request, which the
 * host client does not guarantee. Scope and expiry are enforced; values never reach reports.
 */
internal class SessionCookies : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        this.cookies.removeAll { it.expiresAt <= now }
        cookies.forEach { incoming ->
            this.cookies.removeAll {
                it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path
            }
            if (incoming.expiresAt > now) this.cookies.add(incoming)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }.sortedByDescending { it.path.length }
    }
}

internal data class Page(val status: Int, val body: String, val location: String?)

internal class NativeHttp {
    val cookies = SessionCookies()
    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        // Redirects are followed by the caller, so a 302 carrying Set-Cookie is never skipped
        // and a redirect can never leave the Rezka mirror it started on.
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /** GET when [fields] is null, otherwise an XHR-style form POST as the site's own player sends. */
    fun request(url: String, referer: String, fields: Map<String, String>? = null): Page {
        val builder = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "ru-RU,ru;q=0.9,uk;q=0.8,en;q=0.7")
            .header("Referer", referer)
        if (fields != null) {
            val form = FormBody.Builder()
            fields.forEach { (key, value) -> form.add(key, value) }
            builder.post(form.build())
                .header("Origin", originOf(referer))
                .header("X-Requested-With", "XMLHttpRequest")
        }
        return client.newCall(builder.build()).execute().use { response ->
            val body = response.peekBody(MAX_BODY + 1)
            check(body.contentLength() <= MAX_BODY) { "REZKA stage=http result=BODY_TOO_LARGE" }
            Page(response.code, body.string(), response.header("Location"))
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
        private const val MAX_BODY = 4L * 1024 * 1024

        fun originOf(url: String): String {
            val scheme = url.substringBefore("://", "https")
            val host = url.substringAfter("://").substringBefore('/')
            return "$scheme://$host"
        }
    }
}
