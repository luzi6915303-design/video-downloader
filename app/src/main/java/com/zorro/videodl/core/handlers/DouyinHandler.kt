package com.zorro.videodl.core.handlers

import com.zorro.videodl.core.CookieBootstrap
import com.zorro.videodl.core.DownloadSpec
import com.zorro.videodl.core.Quality
import com.zorro.videodl.core.SiteHandler
import com.zorro.videodl.core.YtDlpOption
import com.zorro.videodl.core.cappedSelector

object DouyinHandler : SiteHandler {
    override val id = "douyin"
    override val displayName = "抖音"

    override val urlPatterns = listOf(
        Regex("""(?:www\.)?douyin\.com/video/\d+""", RegexOption.IGNORE_CASE),
        Regex("""v\.douyin\.com/\S+""", RegexOption.IGNORE_CASE),
    )

    override val supportNote = "无需登录，自动处理"

    // No loginUrl: nothing here needs an account, so the site is deliberately
    // absent from the settings login list. What it does need is the anti-bot
    // cookie below, which the app now fetches on its own.
    override val loginUrl: String? = null
    override val cookieDomains = listOf(".douyin.com")

    // yt-dlp's Douyin extractor returns no formats without this, and cannot
    // generate it (its source says fresh cookies are needed and ships no
    // solver) — the value comes out of douyin.com's own JavaScript.
    override val cookieBootstrap = CookieBootstrap(
        url = "https://www.douyin.com/",
        requiredCookie = "s_v_web_id",
    )

    override suspend fun normalizeUrl(url: String): String {
        // Unlike TikTok's short domains, yt-dlp's Douyin extractor is not
        // guaranteed to resolve v.douyin.com itself, so this is required, not
        // just defensive.
        val expanded = if (url.contains("v.douyin.com")) UrlResolver.follow(url) else url
        // A share link lands on iesdouyin.com, which the bundled yt-dlp does not
        // recognise — it falls through to the generic extractor, whose idea of a
        // video id is the last "/"-separated chunk, i.e. the whole query string.
        // That happens to scrape a video off some share pages and fails on
        // others, which is why this looked intermittent. Rewriting to the
        // canonical form also drops ~800 characters of tracking parameters.
        return SHARE_VIDEO_ID.find(expanded)
            ?.let { "https://www.douyin.com/video/${it.groupValues[1]}" }
            ?: expanded
    }

    private val SHARE_VIDEO_ID = Regex("""iesdouyin\.com/share/video/(\d+)""", RegexOption.IGNORE_CASE)

    override fun formatSelector(quality: Quality): String = cappedSelector(quality)

    // Deliberately no forced --user-agent/--referer here: guessing one burned
    // us on Xiaohongshu (a mobile UA made the extractor come back empty).
    // Add headers only after a real failure shows yt-dlp's own defaults
    // aren't enough for this site.
    override fun extraOptions(spec: DownloadSpec): List<YtDlpOption> = listOf(
        YtDlpOption("--merge-output-format", "mp4"),
        YtDlpOption("--no-playlist"),
    )

    // Douyin's anti-bot rejects the first request made with only the bootstrap
    // cookies, but its rejection response sets the extra cookies (ttwid/odin_tt/
    // passport_csrf_token/…) that a real fetch needs. yt-dlp persists those back
    // into the cookie file on exit, so an immediate re-run succeeds. This is the
    // "tap download twice" workaround, done automatically.
    override val transientFailureRetries = 1

    /** Captions run long and are full of hashtag spam; uploader + id is stable and short. */
    override fun outputTemplate(): String = "%(uploader)s - %(id)s.%(ext)s"
}
