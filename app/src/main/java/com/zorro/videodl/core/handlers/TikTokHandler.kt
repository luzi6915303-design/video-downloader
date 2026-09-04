package com.zorro.videodl.core.handlers

import com.zorro.videodl.core.DownloadSpec
import com.zorro.videodl.core.Quality
import com.zorro.videodl.core.SiteHandler
import com.zorro.videodl.core.YtDlpOption
import com.zorro.videodl.core.cappedSelector

object TikTokHandler : SiteHandler {
    override val id = "tiktok"
    override val displayName = "TikTok"

    override val urlPatterns = listOf(
        Regex("""(?:www\.|m\.)?tiktok\.com/@[\w.-]+/video/\d+""", RegexOption.IGNORE_CASE),
        Regex("""(?:vm|vt)\.tiktok\.com/\S+""", RegexOption.IGNORE_CASE),
    )

    override val supportNote = "无需登录"
    override val loginUrl = "https://www.tiktok.com/login"
    override val cookieDomains = listOf(".tiktok.com")

    override suspend fun normalizeUrl(url: String): String =
        // yt-dlp's TikTok extractor resolves vm/vt short links itself, but
        // following the redirect here keeps behaviour consistent with the
        // other short-link sites and doesn't rely on that being true forever.
        if (url.contains("vm.tiktok.com") || url.contains("vt.tiktok.com")) UrlResolver.follow(url) else url

    override fun formatSelector(quality: Quality): String = cappedSelector(quality)

    override fun extraOptions(spec: DownloadSpec): List<YtDlpOption> = listOf(
        YtDlpOption("--merge-output-format", "mp4"),
        YtDlpOption("--no-playlist"),
    )

    /** Captions run long and are full of hashtag spam; uploader + id is stable and short. */
    override fun outputTemplate(): String = "%(uploader)s - %(id)s.%(ext)s"
}
