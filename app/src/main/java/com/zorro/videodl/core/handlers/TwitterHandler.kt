package com.zorro.videodl.core.handlers

import com.zorro.videodl.core.DownloadSpec
import com.zorro.videodl.core.Quality
import com.zorro.videodl.core.SiteHandler
import com.zorro.videodl.core.YtDlpOption
import com.zorro.videodl.core.cappedSelector

object TwitterHandler : SiteHandler {
    override val id = "twitter"
    override val displayName = "X (Twitter)"

    override val urlPatterns = listOf(
        Regex("""(?:www\.|mobile\.)?(twitter|x)\.com/[^/]+/status/\d+""", RegexOption.IGNORE_CASE),
        Regex("""t\.co/\w+""", RegexOption.IGNORE_CASE),
    )

    override val supportNote = "受保护账号需登录"
    override val loginUrl = "https://x.com/i/flow/login"
    override val cookieDomains = listOf(".x.com", ".twitter.com")

    override suspend fun normalizeUrl(url: String): String {
        val expanded = if (url.contains("t.co/")) UrlResolver.follow(url) else url
        // Share sheets append ?s=20&t=… which yt-dlp does not need.
        return expanded.substringBefore("?").ifBlank { expanded }
    }

    override fun formatSelector(quality: Quality): String = cappedSelector(quality)

    override fun extraOptions(spec: DownloadSpec): List<YtDlpOption> = listOf(
        YtDlpOption("--merge-output-format", "mp4"),
        // A status URL can resolve to the whole conversation; keep it to the
        // one tweet the user actually shared.
        YtDlpOption("--no-playlist"),
    )

    /** Tweet text makes a poor filename; the uploader + id is stable and short. */
    override fun outputTemplate(): String = "%(uploader_id)s - %(id)s.%(ext)s"
}
