package ua.nuvio.rezka

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Replays how Nuvio TV picks a result (ExternalExtensionRunner.findBestMatch, ported below):
 * search the TMDB localized title, then original/alt titles, then score names by similarity,
 * year (±1 hard limit) and type. Live, so skipped unless REZKA_LIVE=1.
 */
class NuvioMatchLiveTest {
    @Before fun onlyWhenAsked() = assumeTrue(System.getenv("REZKA_LIVE") == "1")

    private data class Case(val titles: List<String>, val year: Int, val movie: Boolean, val urlPart: String)

    private val cases = listOf(
        Case(listOf("Матриця", "The Matrix"), 1999, true, "/981-"),
        Case(listOf("Пуститися берега", "Breaking Bad"), 2008, false, "/646-"),
        Case(listOf("Рік і Морті", "Rick and Morty"), 2013, false, "/2136-"),
        Case(listOf("Віднесені привидами", "千と千尋の神隠し", "Spirited Away"), 2001, true, "/829-"),
        Case(listOf("Термінатор", "The Terminator"), 1984, true, "/1394-"),
        Case(listOf("Холоп", "Холоп"), 2019, true, "/32821-")
    )

    private class Result(val name: String, val url: String, val year: Int?, val kind: Kind?)

    @Test fun nuvioPicksTheRightPage() {
        val session = RezkaSession()
        for (case in cases) {
            var results = emptyList<Result>()
            for (query in case.titles) { // Nuvio: localized first, then original/alt titles
                results = session.search(query).flatMap { hit -> hit.names.map { Result(it, hit.url, hit.year, hit.kind) } }
                if (results.isNotEmpty()) break
            }
            val best = results.mapNotNull { r ->
                val sim = case.titles.maxOf { similarity(r.name, it) }
                val typeOk = r.kind == null || (if (case.movie) r.kind == Kind.MOVIE else r.kind == Kind.SERIES)
                if (!typeOk && sim < 0.95) return@mapNotNull null
                if (r.year != null && kotlin.math.abs(r.year - case.year) > 1) return@mapNotNull null
                val score = sim + (if (r.year == case.year) 0.15 else 0.0) + (if (r.kind != null && typeOk) 0.05 else 0.0)
                (r to score).takeIf { score >= 0.5 }
            }.maxByOrNull { it.second }?.first
            println("${case.titles.first()} -> ${best?.name} ${best?.url}")
            assertNotNull("no match for ${case.titles}", best)
            assertTrue("${case.titles} matched ${best!!.url}", best.url.contains(case.urlPart))
            val page = session.page(best.url)
            assertEquals(!case.movie, page.isSeries)
        }
    }

    private fun similarity(s1: String, s2: String): Double {
        val a = s1.lowercase().trim(); val b = s2.lowercase().trim()
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val an = norm(a); val bn = norm(b)
        if (an == bn) return 0.95
        if (an.isEmpty() || bn.isEmpty()) return 0.0
        if ((an.contains(bn) || bn.contains(an)) && minOf(an.length, bn.length).toDouble() / maxOf(an.length, bn.length) >= 0.8) return 0.85
        return 1.0 - levenshtein(an, bn).toDouble() / maxOf(an.length, bn.length)
    }

    private fun norm(s: String) = s.replace(Regex("\\(\\d{4}\\)"), " ").replace(Regex("\\b\\d{4}\\b"), " ")
        .replace(Regex("[:\\-–—]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return dp[a.length][b.length]
    }
}
