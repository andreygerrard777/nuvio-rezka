package ua.nuvio.rezka

import org.junit.Assert.*
import org.junit.Test

class RezkaParserTest {
    // Shapes copied from live rezka.ag search responses.
    private val search = """
        <div class="b-search__live_section"><ul class="b-search__section_list">
        <li><a href="https://rezka.ag/films/fiction/1394-terminator-1984.html"><span class="enty">Терминатор</span> (The Terminator, 1984)<span class="rating"><i>8.1</i></span></a></li>
        <li><a href="https://rezka.ag/series/thriller/646-vo-vse-tyazhkie-2008-latest.html"><span class="enty">Во все тяжкие</span> (Breaking Bad, сериал, 2008-2013)<span class="rating"><i>8.9</i></span></a></li>
        <li><a href="https://rezka.ag/cartoons/comedy/2136-rik-i-morti-2013-latest.html"><span class="enty">Рик и Морти</span> (Rick and Morty, мультфильм, 2013 - ...)</a></li>
        <li><a href="https://rezka.ag/animation/adventures/829-unesennye-prizrakami-2001.html"><span class="enty">Унесённые призраками</span> (Sen to Chihiro no kamikakushi / Spirited Away, аниме, 2001)</a></li>
        <li><a href="https://rezka.ag/films/comedy/32821-holop-2019.html"><span class="enty">Холоп</span> (2019)</a></li>
        <li><a href="https://rezka-tv.org/films/fiction/1-blade.html"><span class="enty">Бегущий</span> (Blade Runner 2049, 2017)</a></li>
        <li><a href="https://other.example/films/9-wrong.html"><span class="enty">Wrong</span> (1984)</a></li>
        </ul></div>
    """.trimIndent()

    @Test fun searchReadsTitlesYearsAndKinds() {
        val hits = RezkaParser.search(search, "https://rezka.ag", setOf("rezka.ag", "rezka-tv.org"))
        assertEquals(6, hits.size)
        val (terminator, breaking, rick, spirited, holop, blade) = hits
        assertEquals("The Terminator", terminator.title)
        assertEquals(1984, terminator.year)
        assertEquals(Kind.MOVIE, terminator.kind)
        assertEquals(listOf("The Terminator", "Терминатор"), terminator.names)

        assertEquals(Kind.SERIES, breaking.kind)
        assertEquals(2008, breaking.year)
        assertEquals("Breaking Bad", breaking.title)

        assertEquals(Kind.SERIES, rick.kind) // open year range
        assertEquals(2013, rick.year)

        assertNull(spirited.kind) // single-year anime: film or show, decided by the page
        assertEquals(listOf("Sen to Chihiro no kamikakushi", "Spirited Away", "Унесённые призраками"), spirited.names)

        assertEquals("Холоп", holop.title)
        assertEquals(listOf("Холоп"), holop.names)

        assertEquals(2017, blade.year)
        assertEquals("Blade Runner 2049", blade.title)
        assertEquals("https://rezka.ag/films/fiction/1-blade.html", blade.url) // kept on the passed mirror
    }

    private val seriesPage = """
        <html><head>
        <meta property="og:type" content="video.tv_series">
        <meta property="og:title" content="Во все тяжкие (2008)">
        <meta property="og:image" content="https://static.example/poster.jpg">
        <meta property="og:description" content="Учитель химии.">
        </head><body>
        <h1 itemprop="name">Во все тяжкие</h1>
        <div class="b-post__origtitle" itemprop="alternativeHeadline">Breaking Bad</div>
        <ul id="translators-list" class="b-translator__list">
        <li><a title="Дубляж" class="b-translator__items" data-translator_id="56" href="https://rezka-tv.org/x/56.html">Дубляж</a></li>
        <li><a title="Украинский многоголосый " class="b-translator__items" data-translator_id="359" href="#">Украинский многоголосый <img title="Украинский" src="https://static.hdrezka.ac/i/flags/ua.png"></a></li>
        </ul>
        <ul><li><a title="Дубляж" class="b-translator__item" data-translator_id="56" href="#">Дубляж</a></li></ul>
        <input type="hidden" id="ctrl_favs" value="favs-token">
        <div id="simple-episodes-tabs">
        <ul id="simple-episodes-list-1">
        <a class="b-simple_episode__item active" data-id="646" data-season_id="1" data-episode_id="1" href="#">Серия 1</a>
        <a class="b-simple_episode__item" data-id="646" data-season_id="1" data-episode_id="2" href="#">Серия 2</a>
        </ul><ul id="simple-episodes-list-2">
        <a class="b-simple_episode__item" data-id="646" data-season_id="2" data-episode_id="1" href="#">Серия 1</a>
        </ul></div>
        <script>sof.tv.initCDNSeriesEvents(646, 56, 1, 1, false, 'rezka.ag', false, true, {"id":"cdnplayer"});</script>
        </body></html>
    """.trimIndent()

    @Test fun seriesPageYieldsDubsEpisodesAndMetadata() {
        val page = RezkaParser.page(seriesPage, "https://rezka.ag/series/thriller/646-vo-vse-tyazhkie-2008-latest.html")
        assertTrue(page.isSeries)
        assertEquals("646", page.id)
        assertEquals("Во все тяжкие", page.title)
        assertEquals("Breaking Bad", page.originalTitle)
        assertEquals(2008, page.year)
        assertEquals("https://static.example/poster.jpg", page.poster)
        assertEquals("favs-token", page.favs)
        assertEquals(listOf("359", "56"), page.dubs.map { it.translator }) // Ukrainian first, no duplicates
        assertTrue(page.dubs.first().ukrainian)
        assertEquals("Украинский многоголосый", page.dubs.first().label)
        assertTrue(page.dubs.all { it.id == "646" })
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), page.episodes.map { it.season to it.episode })
        assertEquals("Серия 2", page.episodes[1].name)
    }

    @Test fun moviePageSupportsUnorderedAttributesAndFlags() {
        val html = """
            <h1>Терминатор</h1><input value="test-favs" id="ctrl_favs">
            <li class="b-translator__item" data-translator_id="7" title="Other">Other</li>
            <a data-translator_id="8" class="b-translator__items" data-director="1" title="Studio"><img title="Український" src="/i/flags/ua.png"></a>
            <script>sof.tv.initCDNMoviesEvents(123, 7, 0, 0, 0, 'rezka.ag');</script>
        """.trimIndent()
        val page = RezkaParser.page(html, "https://rezka.ag/films/action/123-terminator-1984.html")
        assertFalse(page.isSeries)
        assertEquals(1984, page.year)
        assertEquals(Dub("123", "8", "Studio (Український)", director = "1", ukrainian = true), page.dubs.first())
        assertEquals(2, page.dubs.size)
        assertTrue(page.episodes.isEmpty())
    }

    @Test fun singleDubFallsBackToPlayerBootstrap() {
        val movie = RezkaParser.page("<script>sof.tv.initCDNMoviesEvents(123, 9, 1, 0, 1, 'rezka.ag');</script>",
            "https://rezka.ag/films/123-film.html")
        assertEquals(Dub("123", "9", "Rezka", "1", "0", "1"), movie.dubs.single())
        val series = RezkaParser.page("<script>sof.tv.initCDNSeriesEvents(77, 5, 2, 3, false, 'rezka.ag');</script>",
            "https://rezka.ag/series/77-show.html")
        assertTrue(series.isSeries)
        assertEquals(Dub("77", "5", "Rezka"), series.dubs.single())
    }

    @Test fun extractsQualitiesIncludingPremiumAndRejectsNonHttpUrls() {
        val raw = "[360p]https://cdn.example/a.mp4:hls:manifest.m3u8 or https://cdn.example/a.mp4," +
            "[1080p]https://cdn.example/b.m3u8,[<span class=\"pjs-prem-quality\">1080p Ultra<img src=\"x\"></span>]https://cdn.example/c.m3u8," +
            "[<span class=\"pjs-prem-quality\">4K<img src=\"x\"></span>]https://cdn.example/d.m3u8,[480p]javascript:bad"
        val links = RezkaParser.streams(raw, "Дубляж")
        assertEquals(listOf(360, 1080, 1080, 2160), links.map { it.quality })
        assertEquals("https://cdn.example/a.mp4:hls:manifest.m3u8", links.first().url)
        assertEquals("Дубляж · 1080p Ultra", links[2].label)
        assertTrue(RezkaParser.streams("false", "x").isEmpty())
    }

    @Test fun parsesSubtitles() {
        val subs = RezkaParser.subtitles("[Русский]https://s.example/ru.vtt,[Українська]https://s.example/uk.vtt,[English]https://s.example/en.vtt")
        assertEquals(listOf("Русский", "Українська", "English"), subs.map { it.lang })
        assertEquals("https://s.example/uk.vtt", subs[1].url)
        assertTrue(RezkaParser.subtitles("false").isEmpty())
        assertTrue(RezkaParser.subtitles(null).isEmpty())
    }

    @Test fun linkDataRoundTrips() {
        val episode = LinkData("https://rezka.ag/series/1.html", 2, 5)
        assertEquals(episode, LinkData.parse(episode.toJson()))
        val movie = LinkData("https://rezka.ag/films/1.html", null, null)
        assertEquals(movie, LinkData.parse(movie.toJson()))
        assertEquals(movie, LinkData.parse("https://rezka.ag/films/1.html"))
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
