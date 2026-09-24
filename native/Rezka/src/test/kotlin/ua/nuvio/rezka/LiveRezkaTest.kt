package ua.nuvio.rezka

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Hits the real site, so it is skipped unless REZKA_LIVE=1 (never in CI):
 *   REZKA_LIVE=1 ./gradlew :Rezka:testDebugUnitTest --tests '*LiveRezkaTest*'
 */
class LiveRezkaTest {
    @Before fun onlyWhenAsked() = assumeTrue(System.getenv("REZKA_LIVE") == "1")

    private fun mirrors() = System.getenv("REZKA_MIRROR")?.let { listOf(it) } ?: RezkaSession.DEFAULT_MIRRORS

    @Test fun movieEndToEnd() {
        val session = RezkaSession(mirrors())
        val hit = session.search("The Matrix").first { it.title == "The Matrix" }
        assertEquals(1999, hit.year)
        assertEquals(Kind.MOVIE, hit.kind)
        val page = session.page(hit.url)
        assertFalse(page.isSeries)
        assertTrue(page.dubs.size > 3)
        val all = page.dubs.map { session.movieLinks(page, it) }
        val streams = all.flatMap { it.streams }
        println("movie: dubs=${page.dubs.size} withLinks=${all.count { it.streams.isNotEmpty() }} streams=${streams.size} " +
            "subs=${all.flatMap { it.subtitles }.map { it.lang }.distinct()} first=${page.dubs.first().label}")
        println("qualities=" + streams.map { it.quality }.distinct().sorted())
        assertTrue(streams.any { it.quality >= 1080 })
        assertTrue(page.dubs.first().ukrainian)
    }

    @Test fun seriesEndToEnd() {
        val session = RezkaSession(mirrors())
        // Ukrainian TMDB title: Rezka's search still finds the show.
        val hit = session.search("Пуститися берега").first { it.url.contains("/646-") }
        assertEquals(Kind.SERIES, hit.kind)
        assertTrue(hit.names.contains("Breaking Bad"))
        val page = session.page(hit.url)
        assertTrue(page.isSeries)
        assertTrue(page.episodes.size >= 62)
        val links = page.dubs.map { session.episodeLinks(page, it, 5, 16) }
        println("series: dubs=${page.dubs.map { it.label }} withLinks=${links.count { it.streams.isNotEmpty() }}")
        println("S5E16 sample: " + links.first { it.streams.isNotEmpty() }.streams.map { it.label })
        assertTrue(links.first().streams.isNotEmpty()) // Ukrainian dub first and playable
    }
}
