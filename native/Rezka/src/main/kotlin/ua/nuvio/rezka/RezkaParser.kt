package ua.nuvio.rezka

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

internal enum class Kind { MOVIE, SERIES }

/** [names] holds every title the site knows the entry by (Russian + original + aliases). */
internal data class SearchHit(val title: String, val url: String, val year: Int?, val kind: Kind?, val names: List<String>)

internal data class Dub(
    val id: String, val translator: String, val label: String,
    val camrip: String = "0", val ads: String = "0", val director: String = "0",
    val ukrainian: Boolean = false
)

internal data class EpisodeRef(val season: Int, val episode: Int, val name: String)

internal data class ContentPage(
    val url: String,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val poster: String?,
    val plot: String?,
    val isSeries: Boolean,
    val id: String,
    val dubs: List<Dub>,
    val favs: String,
    val episodes: List<EpisodeRef>
)

internal data class Stream(val url: String, val quality: Int, val label: String)
internal data class Subtitle(val lang: String, val url: String)

/** Pure HTML/text parsing, kept apart from the host's CloudStream API so it runs in JVM tests. */
internal object RezkaParser {
    private val YEAR = Regex("""(?:^|,\s*)((?:19|20)\d{2})\b""")
    private val YEAR_RANGE = Regex("""(?:19|20)\d{2}\s*-\s*(?:(?:19|20)\d{2}|\.\.\.)""")
    private val CYRILLIC_TAGS = Regex("""(,\s*[а-яёіїєґ\s-]+)+$""", RegexOption.IGNORE_CASE)
    private val BOOT = Regex("""initCDN(Movies|Series)Events\(\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([^,)]*))?(?:\s*,\s*([^,)]*))?(?:\s*,\s*([^,)]*))?""")

    /**
     * Live markup: `<li><a href="URL"><span class="enty">Title</span> (Paren)<span class="rating">`
     * where Paren is e.g. "The Matrix, 1999", "Breaking Bad, сериал, 2008-2013",
     * "Rick and Morty, мультфильм, 2013 - ...", or just "2019" for a Russian-original title.
     * The year is the first comma-led 4-digit group ("Blade Runner 2049, 2017" keeps 2049).
     */
    fun search(html: String, origin: String, mirrorHosts: Set<String> = emptySet()): List<SearchHit> {
        val base = origin.toHttpUrlOrNull() ?: return emptyList()
        return Jsoup.parse(html, origin).select("li a[href]").mapNotNull { a ->
            val local = a.selectFirst(".enty")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            var url = base.resolve(a.attr("href")) ?: return@mapNotNull null
            // A link to a sibling mirror is kept on the mirror whose Anubis gate was just passed.
            if (!RezkaSession.sameOrigin(base, url) && url.host in mirrorHosts) {
                url = base.resolve(url.encodedPath) ?: return@mapNotNull null
            }
            if (!RezkaSession.sameOrigin(base, url) || !url.encodedPath.endsWith(".html")) return@mapNotNull null
            val paren = a.ownText().trim().removePrefix("(").substringBeforeLast(")")
            val yearMatch = YEAR.find(paren)
            val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
            val head = if (yearMatch != null) paren.substring(0, yearMatch.range.first) else ""
            val original = head.replace(CYRILLIC_TAGS, "").trim()
            val path = url.encodedPath
            val lowered = paren.lowercase()
            val kind = when {
                path.contains("/series/") -> Kind.SERIES
                path.contains("/films/") -> Kind.MOVIE
                lowered.contains("сериал") || YEAR_RANGE.containsMatchIn(paren) -> Kind.SERIES
                else -> null // cartoon/anime with a single year: film or ongoing show, unknown here
            }
            val names = (original.split(" / ") + local.split(" / "))
                .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
            SearchHit(original.substringBefore(" / ").ifBlank { local }, url.toString(), year, kind, names)
        }.distinctBy { it.url }.take(30)
    }

    fun page(html: String, url: String): ContentPage {
        val doc = Jsoup.parse(html, url)
        val boot = BOOT.find(html)
        val isSeries = boot?.groupValues?.get(1) == "Series" ||
            doc.selectFirst("#simple-episodes-tabs, .b-simple_episode__item") != null ||
            doc.selectFirst("meta[property=og:type]")?.attr("content")?.contains("tv_series") == true
        val pageId = boot?.groupValues?.get(2)
            ?: doc.selectFirst(".b-simple_episode__item[data-id]")?.attr("data-id")
            ?: Regex("""/(\d+)-[^/]*\.html""").find(url)?.groupValues?.get(1).orEmpty()
        fun flag(s: String) = if (s.trim() == "1") "1" else "0"

        val dubs = doc.select(".b-translator__item, .b-translator__items").mapNotNull { e ->
            val id = e.attr("data-id").ifBlank { pageId }
            val translator = e.attr("data-translator_id")
            if (!id.matches(Regex("""\d+""")) || !translator.matches(Regex("""\d+"""))) return@mapNotNull null
            val flagImg = e.selectFirst("img[title]")
            val language = flagImg?.attr("title").orEmpty().trim()
            val name = e.attr("title").trim().ifBlank { e.text().trim() }.ifBlank { "Rezka" }
            val label = if (language.isNotEmpty() && !name.contains(language, true)) "$name ($language)" else name
            val ukrainian = flagImg?.attr("src")?.contains("/flags/ua") == true || isUkrainian(label)
            Dub(id, translator, label, flag(e.attr("data-camrip")), flag(e.attr("data-ads")),
                flag(e.attr("data-director")), ukrainian)
        }.distinctBy { it.translator + ":" + it.director }.toMutableList()

        // A title with a single dub has no translator list; the player bootstrap holds its ids:
        // initCDNMoviesEvents(id, translator, camrip, ads, director, ...) for films,
        // initCDNSeriesEvents(id, translator, season, episode, ...) for series.
        if (dubs.isEmpty() && boot != null) {
            val g = boot.groupValues
            dubs.add(if (g[1] == "Movies") Dub(g[2], g[3], "Rezka", flag(g[4]), flag(g[5]), flag(g[6]))
                else Dub(g[2], g[3], "Rezka"))
        }
        // Ukrainian dubs first; otherwise keep the site's own order (its default dub leads).
        val sorted = dubs.sortedByDescending { it.ukrainian }

        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            .ifBlank { ogTitle.replace(Regex("""\s*\(\d{4}\)\s*$"""), "") }.ifBlank { "Rezka" }
        val year = Regex("""\((\d{4})\)""").find(ogTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""/\d+-[^/]*-((?:19|20)\d{2})(?:-latest)?\.html""").find(url)?.groupValues?.get(1)?.toIntOrNull()
        val plot = doc.selectFirst(".b-post__description_text")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.ifBlank { null }
        val poster = doc.selectFirst(".b-sidecover img[src]")?.absUrl("src")?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }

        return ContentPage(
            url = url, title = title,
            originalTitle = doc.selectFirst(".b-post__origtitle")?.text()?.trim()?.ifBlank { null },
            year = year, poster = poster, plot = plot, isSeries = isSeries, id = pageId,
            dubs = sorted, favs = doc.selectFirst("#ctrl_favs")?.attr("value").orEmpty(),
            episodes = if (isSeries) episodes(html) else emptyList()
        )
    }

    /** Works on the page itself and on the "episodes" HTML that action=get_episodes returns. */
    fun episodes(html: String): List<EpisodeRef> =
        Jsoup.parse(html).select(".b-simple_episode__item[data-season_id][data-episode_id]").mapNotNull { e ->
            val season = e.attr("data-season_id").toIntOrNull() ?: return@mapNotNull null
            val episode = e.attr("data-episode_id").toIntOrNull() ?: return@mapNotNull null
            EpisodeRef(season, episode, e.text().trim().ifBlank { "Серия $episode" })
        }.distinctBy { it.season to it.episode }.sortedWith(compareBy({ it.season }, { it.episode }))

    /** Seasons a dub covers, from get_episodes' "seasons" HTML (`data-tab_id="2"`). */
    fun seasons(html: String): Set<Int> =
        Regex("""data-tab_id="(\d+)"""").findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()

    /**
     * "[360p]urlA or urlB,[480p]url,...,[<span class="pjs-prem-quality">4K<img/></span>]url".
     * The "premium" qualities come back with working URLs; the lock is UI-only on the site.
     */
    fun streams(raw: String, dub: String): List<Stream> =
        Regex("""\[([^\]]*)\]([^\[]+?)(?=,\[|$)""").findAll(raw).mapNotNull { match ->
            val qualityLabel = Jsoup.parse(match.groupValues[1]).text().trim()
            val url = match.groupValues[2].split(" or ").mapNotNull { it.trim().toHttpUrlOrNull() }
                .firstOrNull { it.username.isEmpty() && it.password.isEmpty() } ?: return@mapNotNull null
            Stream(url.toString(), quality(qualityLabel), "$dub · $qualityLabel".trim())
        }.distinctBy { it.url }.toList()

    fun quality(label: String): Int = when {
        label.contains("4K", true) -> 2160
        label.contains("2K", true) -> 1440
        else -> Regex("""\d{3,4}""").find(label)?.value?.toIntOrNull() ?: 0
    }

    /** "[Русский]https://…vtt,[Українська]https://…vtt,[English]https://…vtt" */
    fun subtitles(raw: String?): List<Subtitle> {
        if (raw.isNullOrBlank() || raw == "false") return emptyList()
        return Regex("""\[([^\]]+)\](https?://[^,\[\s]+)""").findAll(raw).map {
            Subtitle(Parser.unescapeEntities(it.groupValues[1], false).trim(), it.groupValues[2])
        }.distinctBy { it.url }.toList()
    }

    fun isUkrainian(label: String) =
        label.contains("укра", true) || label.contains("украї", true) || label.contains("ukrain", true)
}
