package ua.nuvio.rezka

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.security.MessageDigest

/**
 * Every Rezka mirror sits behind Anubis, a proof-of-work gate (not a CAPTCHA): the client must
 * find a nonce so that sha256(randomData + nonce) starts with `difficulty` hex zeros, then call
 * pass-challenge, which answers 302 + the techaro.lol-anubis-auth cookie. Difficulty 2 is seen
 * live (~256 hashes); anything above 5 is refused so a site cranking it up cannot pin the TV.
 */
internal data class Challenge(val id: String, val seed: String, val difficulty: Int, val prefix: String)
internal data class Solution(val hash: String, val nonce: Long, val elapsedMs: Long)

internal object AnubisPow {
    fun isChallenge(body: String) =
        body.contains("anubis_challenge") || body.contains("/.within.website/x/cmd/anubis/")

    fun parse(html: String): Challenge {
        val doc = Jsoup.parse(html)
        val raw = doc.getElementById("anubis_challenge")?.data() ?: error("missing challenge")
        val root = JSONObject(raw)
        val rules = root.getJSONObject("rules")
        // "fast" and "slow" are the same algorithm in Anubis's own client.
        require(rules.getString("algorithm") in setOf("fast", "slow"))
        val difficulty = rules.get("difficulty").toString()
        require(difficulty.matches(Regex("[0-5]")))
        val challenge = root.getJSONObject("challenge")
        val id = challenge.getString("id")
        val seed = challenge.getString("randomData")
        require(id.length in 1..1024 && seed.length in 1..4096)
        val prefix = doc.getElementById("anubis_base_prefix")?.data()
            ?.let {
                val value = JSONArray("[$it]")
                require(value.length() == 1 && value.get(0) is String)
                value.getString(0)
            } ?: ""
        require(prefix.isEmpty() || (prefix.startsWith("/") && !prefix.startsWith("//") &&
            !prefix.contains("..") && !prefix.contains('?') && !prefix.contains('#') &&
            !prefix.contains('\\')))
        return Challenge(id, seed, difficulty.toInt(), prefix.trimEnd('/'))
    }

    fun solve(ch: Challenge, budgetMs: Long = 10_000): Solution {
        require(ch.difficulty in 0..5)
        val start = System.nanoTime()
        val digest = MessageDigest.getInstance("SHA-256")
        val seed = ch.seed.toByteArray(Charsets.UTF_8)
        for (nonce in 0L until 50_000_000L) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (nonce % 1024 == 0L && (System.nanoTime() - start) / 1_000_000 >= budgetMs) {
                error("proof budget exhausted")
            }
            digest.update(seed)
            val bytes = digest.digest(nonce.toString().toByteArray(Charsets.US_ASCII))
            val zero = (0 until ch.difficulty).all { n ->
                val value = bytes[n / 2].toInt() and 255
                (if (n % 2 == 0) value shr 4 else value and 15) == 0
            }
            if (zero) {
                val hex = "0123456789abcdef"
                val hash = buildString {
                    bytes.forEach { b ->
                        val value = b.toInt() and 255
                        append(hex[value shr 4]); append(hex[value and 15])
                    }
                }
                return Solution(hash, nonce, (System.nanoTime() - start) / 1_000_000)
            }
        }
        error("proof iteration limit")
    }
}
