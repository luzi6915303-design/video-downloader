package com.zorro.videodl.core.handlers

import com.zorro.videodl.core.DownloadSpec
import com.zorro.videodl.core.Quality
import com.zorro.videodl.core.SiteHandler
import com.zorro.videodl.core.YtDlpOption
import com.zorro.videodl.core.cappedSelector

object ToutiaoHandler : SiteHandler {
    override val id = "toutiao"
    override val displayName = "今日头条"

    // The app hands out several shapes for the same video — /video/, /a<id>,
    // /w/ (微头条) and the m. host — so claim the domain rather than one path.
    override val urlPatterns = listOf(
        Regex("""(?:www\.|m\.)?toutiao\.com/\S+""", RegexOption.IGNORE_CASE),
    )

    override val supportNote = "无需登录"

    // yt-dlp's Toutiao extractor fetches its own ttwid anti-bot cookie, so
    // unlike Douyin this needs neither a login nor a cookie bootstrap.
    override val loginUrl: String? = null
    override val cookieDomains = listOf(".toutiao.com")

    override suspend fun normalizeUrl(url: String): String {
        // Share text arrives as "标题 - 今日头条 https://..." with trailing
        // punctuation, so isolate the link first.
        val bare = Regex("""https?://\S+""").find(url)?.value?.trimEnd('，', '。', '、', ',', '.') ?: url
        VIDEO_ID.find(bare)?.let { return canonical(it.groupValues[1]) }
        // Anything else (/a<id>, /w/<id>, a short link) redirects to a page whose
        // URL does carry the id — the extractor only claims www.toutiao.com, so
        // rewriting it here is what keeps this off the generic extractor.
        val expanded = UrlResolver.follow(bare)
        return VIDEO_ID.find(expanded)?.let { canonical(it.groupValues[1]) }
            ?: ANY_ID.find(expanded)?.let { canonical(it.groupValues[1]) }
            ?: expanded
    }

    private fun canonical(id: String) = "https://www.toutiao.com/video/$id/"

    private val VIDEO_ID = Regex("""toutiao\.com/video/(\d+)""", RegexOption.IGNORE_CASE)
    private val ANY_ID = Regex("""toutiao\.com/[aw]/?(\d{15,})""", RegexOption.IGNORE_CASE)

    override fun formatSelector(quality: Quality): String = cappedSelector(quality)

    // Toutiao serves single muxed h264 mp4 files, so there is nothing to merge
    // and no codec the phones' gallery player would refuse.
    override fun extraOptions(spec: DownloadSpec): List<YtDlpOption> = buildList {
        add(YtDlpOption("--no-playlist"))
        if (spec.quality == Quality.AUDIO_ONLY) {
            add(YtDlpOption("--extract-audio"))
            add(YtDlpOption("--audio-format", "m4a"))
        }
    }
}
