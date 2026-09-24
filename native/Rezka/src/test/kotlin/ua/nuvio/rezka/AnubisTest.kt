package ua.nuvio.rezka

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class AnubisTest {
    private val challenge = """<script type="application/json" id="anubis_challenge">{"rules":{"algorithm":"fast","difficulty":2},"challenge":{"id":"synthetic-id","randomData":"synthetic-seed"}}</script>"""

    @Test fun parsesChallengeAndJsonStringPrefix() {
        val html = challenge + """<script id="anubis_base_prefix" type="application/json">"/proxy"</script>"""
        val parsed = AnubisPow.parse(html)
        assertEquals(Challenge("synthetic-id", "synthetic-seed", 2, "/proxy"), parsed)
        assertEquals("", AnubisPow.parse(challenge).prefix)
        assertTrue(AnubisPow.isChallenge(challenge))
        assertFalse(AnubisPow.isChallenge("<div class=\"b-content__main\"></div>"))
    }

    @Test fun solutionIsARealSha256WithLeadingZeros() {
        val solution = AnubisPow.solve(AnubisPow.parse(challenge))
        val hash = MessageDigest.getInstance("SHA-256").digest(("synthetic-seed" + solution.nonce).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(hash, solution.hash)
        assertTrue(hash.startsWith("00"))
    }

    @Test fun rejectsUnsafeInput() {
        for (bad in listOf(
            challenge + """<script id="anubis_base_prefix" type="application/json">123</script>""",
            challenge + """<script id="anubis_base_prefix" type="application/json">"//evil"</script>""",
            challenge.replace("\"difficulty\":2", "\"difficulty\":9"),
            challenge.replace("fast", "other")
        )) {
            try {
                AnubisPow.parse(bad)
                fail("Expected rejection: $bad")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun proofBudgetIsEnforced() {
        try {
            AnubisPow.solve(Challenge("id", "seed", 5, ""), budgetMs = 0)
            fail("Expected proof budget limit")
        } catch (_: IllegalStateException) { }
    }

    @Test fun cookiesRespectHostPathSecureAndDeletion() {
        val jar = SessionCookies()
        val origin = "https://rezka.example/private/start".toHttpUrl()
        jar.saveFromResponse(origin, listOf(Cookie.parse(origin, "session=x; Path=/private; Secure")!!))
        assertEquals(1, jar.loadForRequest(origin).size)
        assertTrue(jar.loadForRequest("https://other.example/private/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://rezka.example/public".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://rezka.example/private/".toHttpUrl()).isEmpty())
        jar.saveFromResponse(origin, listOf(Cookie.parse(origin, "session=; Path=/private; Max-Age=0; Secure")!!))
        assertTrue(jar.loadForRequest(origin).isEmpty())
    }
}
