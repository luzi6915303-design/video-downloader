package com.zorro.videodl.core.handlers

import com.zorro.videodl.core.DownloadSpec
import com.zorro.videodl.core.Quality
import com.zorro.videodl.core.SiteHandler
import com.zorro.videodl.core.YtDlpOption
import com.zorro.videodl.core.cappedSelector

object InstagramHandler : SiteHandler {
    override val id = "instagram"
    override val displayName = "Instagram"

    override val urlPatterns = listOf(
        Regex("""(?:www\.)?instagram\.com/(?:p|reel|reels|tv)/[\w-]+""", RegexOption.IGNORE_CASE),
        Regex("""instagr\.am/(?:p|reel|tv)/[\w-]+""", RegexOption.IGNORE_CASE),
    )

    override val supportNote = "建议登录，否则常被拦截"
    override val loginUrl = "https://www.instagram.com/accounts/login/"
    override val cookieDomains = listOf(".instagram.com")

    override fun formatSelector(quality: Quality): String = cappedSelector(quality)

    override fun extraOptions(spec: DownloadSpec): List<YtDlpOption> = listOf(
        YtDlpOption("--merge-output-format", "mp4"),
        YtDlpOption("--no-playlist"),
    )

    /** Captions run long and are full of hashtag spam; uploader + id is stable and short. */
    override fun outputTemplate(): String = "%(uploader)s - %(id)s.%(ext)s"
}
