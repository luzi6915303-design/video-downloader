package com.zorro.videodl.core.handlers

import com.zorro.videodl.core.DownloadSpec
import com.zorro.videodl.core.Quality
import com.zorro.videodl.core.SiteHandler
import com.zorro.videodl.core.YtDlpOption
import com.zorro.videodl.core.cappedSelector

/**
 * Fallback for everything yt-dlp supports that has no dedicated handler yet.
 * Never matched by pattern — [com.zorro.videodl.core.SiteRegistry] falls back
 * to it explicitly.
 */
object GenericHandler : SiteHandler {
    override val id = "generic"
    override val displayName = "其他站点"
    override val urlPatterns = emptyList<Regex>()
    override val loginUrl = null
    override val cookieDomains = emptyList<String>()

    override fun matches(url: String) = false

    override suspend fun normalizeUrl(url: String): String =
        Regex("""https?://\S+""").find(url)?.value ?: url

    override fun formatSelector(quality: Quality): String = cappedSelector(quality)

    override fun extraOptions(spec: DownloadSpec) = listOf(YtDlpOption("--merge-output-format", "mp4"))
}
