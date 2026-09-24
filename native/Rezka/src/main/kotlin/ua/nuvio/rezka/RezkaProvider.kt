package ua.nuvio.rezka

import android.content.Context
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

@CloudstreamPlugin
class RezkaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RezkaProvider())
    }
}

/**
 * Nuvio's flow for a search-based provider: TMDB title → search() → best title/year/type match
 * → load(url) → episode with the exact (season, episode) → loadLinks(data). Links collected
 * before Nuvio's 60 s loadLinks cap are kept, so every dub is emitted as soon as it resolves.
 */
class RezkaProvider : MainAPI() {
    override var name = "HDRezka"
    override var mainUrl = RezkaSession.DEFAULT_MIRRORS.first()
    override var lang = "uk"
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Anime, TvType.AnimeMovie)

    private val session by lazy { RezkaSession() }

    private suspend fun <T> guarded(stage: String, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: LinkageError) {
            // Surfaces in Nuvio's provider test report instead of a bare "0 streams".
            throw IllegalStateException(("REZKA stage=$stage error=${e.javaClass.simpleName} " +
                (e.message ?: "")).take(160))
        } catch (e: Exception) {
            val report = e.message?.takeIf { it.startsWith("REZKA ") }
                ?: ("REZKA stage=$stage error=" + e.javaClass.simpleName)
            throw IllegalStateException(report.take(160))
        }
    }

    /**
     * One result per known title of an entry (Russian, original and aliases, all with the same
     * URL): Nuvio compares result names against the TMDB localized/original titles, so the
     * alias closest to what it searched for is the one that gets matched.
     */
    override suspend fun search(query: String): List<SearchResponse> = guarded("search") {
        session.search(query).flatMap { hit ->
            hit.names.map { title ->
                when (hit.kind) {
                    Kind.SERIES -> newTvSeriesSearchResponse(title, hit.url, TvType.TvSeries, false) {
                        year = hit.year
                    }
                    Kind.MOVIE -> newMovieSearchResponse(title, hit.url, TvType.Movie, false) {
                        year = hit.year
                    }
                    // Cartoon/anime without a clear marker: leave the type open so Nuvio's type
                    // filter does not drop it; load() reads the real type from the page.
                    null -> newMovieSearchResponse(title, hit.url, TvType.Movie, false) {
                        year = hit.year
                        type = null
                    }
                }
            }
        }.take(60)
    }

    override suspend fun load(url: String): LoadResponse = guarded("load") {
        val page = session.page(url)
        check(page.dubs.isNotEmpty()) { "REZKA stage=load result=NO_PLAYER" }
        if (page.isSeries) {
            val refs = page.episodes.ifEmpty { session.episodesOf(page, page.dubs.first()) }
            check(refs.isNotEmpty()) { "REZKA stage=load result=NO_EPISODES" }
            val episodes = refs.map { ref ->
                newEpisode(LinkData(url, ref.season, ref.episode).toJson(), {
                    name = ref.name
                    season = ref.season
                    episode = ref.episode
                }, false)
            }
            newTvSeriesLoadResponse(page.title, url, TvType.TvSeries, episodes) {
                posterUrl = page.poster
                plot = page.plot
                year = page.year
            }
        } else {
            newMovieLoadResponse(page.title, url, TvType.Movie, LinkData(url, null, null).toJson()) {
                posterUrl = page.poster
                plot = page.plot
                year = page.year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = guarded("links") {
        val link = LinkData.parse(data)
        val page = session.page(link.url)
        check(page.dubs.isNotEmpty()) { "REZKA stage=links result=NO_DUBS" }
        val limit = Semaphore(PARALLEL_REQUESTS)
        val seenSubtitles = HashSet<String>()
        val found = coroutineScope {
            page.dubs.map { dub ->
                async(Dispatchers.IO) {
                    val links = limit.withPermit {
                        try {
                            if (link.season != null && link.episode != null) {
                                session.episodeLinks(page, dub, link.season, link.episode)
                            } else {
                                session.movieLinks(page, dub)
                            }
                        } catch (e: Exception) {
                            if (e is java.util.concurrent.CancellationException) throw e
                            Links(emptyList(), emptyList()) // one broken dub must not hide the rest
                        }
                    }
                    emit(page, links, seenSubtitles, subtitleCallback, callback)
                    links.streams.size
                }
            }.awaitAll().sum()
        }
        check(found > 0) { "REZKA stage=links result=NO_PLAYABLE_URLS dubs=" + page.dubs.size }
        true
    }

    private suspend fun emit(
        page: ContentPage, links: Links, seenSubtitles: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val origin = NativeHttp.originOf(page.url)
        for (stream in links.streams) {
            val isHls = stream.url.substringBefore('?').endsWith(".m3u8", true)
            callback(newExtractorLink(
                source = name,
                name = stream.label,
                url = stream.url,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                quality = stream.quality
                referer = "$origin/"
                headers = mapOf("User-Agent" to NativeHttp.USER_AGENT)
            })
        }
        for (subtitle in links.subtitles) {
            val fresh = synchronized(seenSubtitles) { seenSubtitles.add(subtitle.url) }
            if (fresh) subtitleCallback(newSubtitleFile(subtitle.lang, subtitle.url))
        }
    }

    companion object {
        /** Rezka starts answering 503 when hammered; 4 parallel ajax calls stay well clear of it. */
        private const val PARALLEL_REQUESTS = 4
    }
}
