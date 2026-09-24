package ua.nuvio.rezka

import org.json.JSONObject

/** loadLinks() payload: the content page plus, for a series, the episode to play. */
internal data class LinkData(val url: String, val season: Int?, val episode: Int?) {
    fun toJson(): String = JSONObject().apply {
        put("url", url)
        if (season != null) put("season", season)
        if (episode != null) put("episode", episode)
    }.toString()

    companion object {
        fun parse(data: String): LinkData {
            if (!data.trimStart().startsWith("{")) return LinkData(data.trim(), null, null)
            val json = JSONObject(data)
            fun int(key: String) = if (json.has(key)) json.optInt(key, -1).takeIf { it >= 0 } else null
            return LinkData(json.getString("url"), int("season"), int("episode"))
        }
    }
}
