package com.zorro.videodl.core.handlers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Expands short links (xhslink.com, t.co) by walking their redirect chain. */
object UrlResolver {
    private const val MAX_HOPS = 6
    private const val UA =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"

    suspend fun follow(url: String): String = withContext(Dispatchers.IO) {
        var current = url
        repeat(MAX_HOPS) {
            val next = hop(current) ?: return@withContext current
            current = next
        }
        current
    }

    private fun hop(url: String): String? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            try {
                if (responseCode in 300..399) {
                    getHeaderField("Location")?.let { loc ->
                        if (loc.startsWith("http")) loc else URL(URL(url), loc).toString()
                    }
                } else {
                    null
                }
            } finally {
                disconnect()
            }
        }
    } catch (_: Exception) {
        // A dead short link is not fatal — hand the original back and let
        // yt-dlp produce the real error message.
        null
    }
}
