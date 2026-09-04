package com.zorro.videodl.core.handlers

import com.zorro.videodl.core.DownloadSpec
import com.zorro.videodl.core.Quality
import com.zorro.videodl.core.SiteHandler
import com.zorro.videodl.core.YtDlpOption
import com.zorro.videodl.core.cappedSelector

object XiaohongshuHandler : SiteHandler {
    override val id = "xiaohongshu"
    override val displayName = "小红书"

    override val urlPatterns = listOf(
        Regex("""xiaohongshu\.com/(explore|discovery/item)/\w+""", RegexOption.IGNORE_CASE),
        Regex("""xhslink\.com/\S+""", RegexOption.IGNORE_CASE),
    )

    override val supportNote = "公开笔记无需登录"
    override val loginUrl = "https://www.xiaohongshu.com/explore"
    override val cookieDomains = listOf(".xiaohongshu.com")

    override suspend fun normalizeUrl(url: String): String {
        // Share text looks like "99 复制打开小红书… http://xhslink.com/a/xxxx 点击链接…",
        // so pull the bare URL out before touching the network.
        val bare = Regex("""https?://\S+""").find(url)?.value?.trimEnd('，', '。', '、', ',', '.') ?: url
        val expanded = if (bare.contains("xhslink.com")) UrlResolver.follow(bare) else bare
        // Unlike other sites the query string is load-bearing here: the
        // xsec_token/xsec_source pair is what authorises the note fetch.
        return expanded
    }

    override fun formatSelector(quality: Quality): String = cappedSelector(quality)

    override fun extraOptions(spec: DownloadSpec): List<YtDlpOption> = buildList {
        add(YtDlpOption("--merge-output-format", "mp4"))
        add(YtDlpOption("--referer", "https://www.xiaohongshu.com/"))
        // Must be a DESKTOP user agent even though this is a phone app: served a
        // mobile UA, Xiaohongshu returns a page with no video data at all and the
        // extractor fails with "No video formats found". Verified by isolating the
        // UA against the same URL and yt-dlp build.
        add(
            YtDlpOption(
                "--user-agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            )
        )
    }
}
