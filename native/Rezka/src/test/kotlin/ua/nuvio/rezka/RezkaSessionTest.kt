package ua.nuvio.rezka

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class RezkaSessionTest {
    private val challenge = """<script id="anubis_challenge" type="application/json">{"rules":{"algorithm":"fast","difficulty":1},"challenge":{"id":"test-id","randomData":"test-seed"}}</script>"""

    private fun searchPage(base: String) = """
        <li><a href="$base/films/action/123-terminator-1984.html"><span class="enty">Терминатор</span> (The Terminator, 1984)<span class="rating">8</span></a></li>
        <li><a href="$base/series/drama/77-show-2020.html"><span class="enty">Шоу</span> (The Show, сериал, 2020-2022)</a></li>
    """.trimIndent()

    private val movie = """
        <h1>Терминатор</h1><input value="test-favs" id="ctrl_favs">
        <li class="b-translator__item" data-translator_id="7" title="Other">Other</li>
        <a data-translator_id="8" class="b-translator__items" title="Studio"><img title="Украинский" src="/flags/ua.png"></a>
        <script>sof.tv.initCDNMoviesEvents(123, 7, 0, 0, 0, 'rezka.ag');</script>
    """.trimIndent()

    private val series = """
        <h1>Шоу</h1><input value="f" id="ctrl_favs">
        <a class="b-translator__item" data-translator_id="5" title="A">A</a>
        <a class="b-simple_episode__item" data-id="77" data-season_id="1" data-episode_id="1">Серия 1</a>
        <script>sof.tv.initCDNSeriesEvents(77, 5, 1, 1, false, 'rezka.ag');</script>
    """.trimIndent()

    private fun form(body: String) = body.split('&').associate {
        val (k, v) = (it.split('=', limit = 2) + "").take(2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    @Test fun movieFlowSolvesTheGatedResponsesChallengeAndKeepsCookieForEveryRequest() {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setBody(challenge) // search POST is gated
                .addHeader("Set-Cookie", "techaro.lol-anubis-cookie-verification=test-id; Path=/"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/")
                .addHeader("Set-Cookie", "techaro.lol-anubis-auth=test-auth; Path=/; HttpOnly"))
            server.enqueue(MockResponse().setBody(searchPage(base)))
            server.enqueue(MockResponse().setBody(movie))
            server.enqueue(MockResponse().setBody("""{"success":true,"url":"[720p]https://cdn.example/uk.m3u8","subtitle":"[Українська]https://cdn.example/uk.vtt"}"""))
            server.enqueue(MockResponse().setBody("""{"success":false,"message":"x"}"""))

            val session = RezkaSession(listOf(base))
            val hits = session.search("The Terminator")
            assertEquals(Kind.MOVIE, hits[0].kind)
            assertEquals(Kind.SERIES, hits[1].kind)
            val page = session.page(hits[0].url)
            assertEquals("8", page.dubs.first().translator) // Ukrainian first
            val uk = session.movieLinks(page, page.dubs[0])
            val other = session.movieLinks(page, page.dubs[1])
            assertEquals(listOf(720), uk.streams.map { it.quality })
            assertEquals("Українська", uk.subtitles.single().lang)
            assertTrue(other.streams.isEmpty())
            assertSame(page, session.page(hits[0].url)) // cached: no extra request

            val requests = (1..6).map { server.takeRequest(1, TimeUnit.SECONDS)!! }
            assertEquals(6, server.requestCount)
            assertEquals("POST", requests[0].method)
            assertEquals("The Terminator", form(requests[0].body.readUtf8())["q"])
            val pass = requests[1]
            assertEquals("/.within.website/x/cmd/anubis/api/pass-challenge", pass.requestUrl!!.encodedPath)
            assertTrue(pass.getHeader("Cookie")!!.contains("cookie-verification=test-id"))
            val nonce = pass.requestUrl!!.queryParameter("nonce")!!
            val hash = MessageDigest.getInstance("SHA-256").digest(("test-seed" + nonce).toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(hash, pass.requestUrl!!.queryParameter("response"))
            for (i in 2..5) assertTrue(requests[i].getHeader("Cookie")!!.contains("anubis-auth=test-auth"))
            val movieForm = form(requests[4].body.readUtf8())
            assertEquals("8", movieForm["translator_id"])
            assertEquals("123", movieForm["id"])
            assertEquals("get_movie", movieForm["action"])
            assertEquals("test-favs", movieForm["favs"])
            assertEquals("/ajax/get_cdn_series/", requests[4].requestUrl!!.encodedPath)
            assertEquals("XMLHttpRequest", requests[4].getHeader("X-Requested-With"))
            assertEquals(base, requests[4].getHeader("Origin"))
            assertEquals(hits[0].url, requests[4].getHeader("Referer"))
            assertEquals(requests[0].getHeader("User-Agent"), requests[4].getHeader("User-Agent"))
        }
    }

    @Test fun gateWithoutChallengeJsonUsesHomePageChallenge() {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setBody("""<a href="/.within.website/x/cmd/anubis/">gate</a>"""))
            server.enqueue(MockResponse().setBody(challenge))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/"))
            server.enqueue(MockResponse().setBody(movie))
            assertEquals("Терминатор", RezkaSession(listOf(base)).page("$base/films/1-x.html").title)
            server.takeRequest()
            assertEquals("/", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("/.within.website/x/cmd/anubis/api/pass-challenge", server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    @Test fun busyServerIsRetriedAndPageFallsBackToAnotherMirror() {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                repeat(3) { first.enqueue(MockResponse().setResponseCode(503)) }
                second.enqueue(MockResponse().setResponseCode(503))
                second.enqueue(MockResponse().setBody(movie))
                val firstBase = first.url("/").toString().trimEnd('/')
                val secondBase = second.url("/").toString().trimEnd('/')
                val page = RezkaSession(listOf(firstBase, secondBase)).page("$firstBase/films/action/123-terminator-1984.html")
                assertEquals("$secondBase/films/action/123-terminator-1984.html", page.url)
                assertEquals(3, first.requestCount)
                assertEquals(2, second.requestCount)
            }
        }
    }

    @Test fun seriesFlowRequestsTheExactEpisode() {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setBody(series))
            server.enqueue(MockResponse().setBody("""{"success":true,"url":"[1080p]https://cdn.example/e.m3u8"}"""))
            val session = RezkaSession(listOf(base))
            val page = session.page("$base/series/drama/77-show-2020.html")
            assertTrue(page.isSeries)
            assertEquals(listOf(1 to 1), page.episodes.map { it.season to it.episode })
            val links = session.episodeLinks(page, page.dubs.single(), 3, 7)
            assertEquals(1080, links.streams.single().quality)
            server.takeRequest()
            val sent = form(server.takeRequest().body.readUtf8())
            assertEquals(mapOf("id" to "77", "translator_id" to "5", "season" to "3", "episode" to "7",
                "action" to "get_stream", "favs" to "f"), sent)
        }
    }

    @Test fun episodesFallBackToServerList() {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setBody("<script>sof.tv.initCDNSeriesEvents(9, 4, 1, 1, false);</script>"))
            server.enqueue(MockResponse().setBody("""{"success":true,"episodes":"<li class=\"b-simple_episode__item\" data-season_id=\"2\" data-episode_id=\"3\">Серия 3</li>"}"""))
            val session = RezkaSession(listOf(base))
            val page = session.page("$base/series/9-x.html")
            assertEquals(listOf(EpisodeRef(2, 3, "Серия 3")), session.episodesOf(page, page.dubs.single()))
        }
    }

    @Test fun secondMirrorIsUsedWhenFirstFails() {
        MockWebServer().use { bad ->
            MockWebServer().use { good ->
                repeat(3) { bad.enqueue(MockResponse().setResponseCode(503)) }
                val goodBase = good.url("/").toString().trimEnd('/')
                good.enqueue(MockResponse().setBody(searchPage(goodBase)))
                val session = RezkaSession(listOf(bad.url("/").toString(), goodBase))
                val hits = session.search("x")
                assertEquals(2, hits.size)
                assertTrue(hits.all { it.url.startsWith(goodBase) })
            }
        }
    }

    @Test fun rejectedPassReportsStage() {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setBody(challenge))
            server.enqueue(MockResponse().setResponseCode(403))
            try {
                RezkaSession(listOf(base)).page("$base/films/1-x.html")
                fail("Expected rejection")
            } catch (e: IllegalStateException) {
                assertEquals("REZKA stage=anubis pass=403 result=PASS_REJECTED", e.message)
            }
        }
    }

    @Test fun stillChallengedAfterPassDoesNotLoop() {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            repeat(2) {
                server.enqueue(MockResponse().setBody(challenge))
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/"))
            }
            server.enqueue(MockResponse().setBody(challenge))
            try {
                RezkaSession(listOf(base)).page("$base/films/1-x.html")
                fail("Expected challenge failure")
            } catch (e: IllegalStateException) {
                assertEquals("REZKA stage=anubis result=CHALLENGED", e.message)
            }
            assertEquals(5, server.requestCount) // two passes, then give up: no endless loop
        }
    }

    @Test fun foreignUrlsAreRejectedBeforeAnyRequest() {
        MockWebServer().use { server ->
            try {
                RezkaSession(listOf(server.url("/").toString())).page("https://other.example/films/1.html")
                fail("Expected origin check")
            } catch (_: IllegalArgumentException) { }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun crossOriginRedirectIsNotFollowed() {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://other.example/"))
            try {
                RezkaSession(listOf(base)).page("$base/films/1-x.html")
                fail("Expected redirect rejection")
            } catch (e: IllegalStateException) {
                assertEquals("REZKA stage=http result=CROSS_ORIGIN", e.message)
            }
            assertEquals(1, server.requestCount)
        }
    }
}
