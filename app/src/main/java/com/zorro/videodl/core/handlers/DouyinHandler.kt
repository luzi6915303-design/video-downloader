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

    // ttwid is the one cookie that matters: measured against the extractor's
    // own endpoint, a jar holding nothing else answers just as often as a full
    // 28-cookie browser jar, and a request with no cookies at all is refused
    // every single time.
    //
    // It used to require s_v_web_id instead, which is why this failed in
    // mainland China and nowhere else. Douyin serves a mobile UA there a
    // cut-down "download the app" landing page whose scripts never run, so
    // s_v_web_id and odin_tt never appear — the bootstrap waited for one of
    // them, timed out, and returned without ever writing a jar. The download
    // then ran with no cookies, which cannot succeed. That page does still set
    // ttwid, so requiring only ttwid is what makes this work there.
    //
    // The other two stay listed as optional: on networks that serve the real
    // page they show up within a second and a fuller jar does no harm.
    override val cookieBootstrap = CookieBootstrap(
        url = "https://www.douyin.com/",
        requiredCookie = "ttwid",
        serverCookies = listOf("s_v_web_id", "odin_tt"),
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

    /**
     * Disabled after measuring it (2026-09-08). Kept, rather than deleted,
     * because the mechanism is sound for sites that simply play on load — it is
     * Douyin specifically that defeats it.
     *
     * On a real device the watch page loads, but the `<video>` element sits at
     * `readyState = 0` with an empty `currentSrc` for the full 40s window and
     * issues no media request at all: the player waits on the verification
     * challenge (`rc-verifycenter/rmc-captcha` shows up in the page's console)
     * before it will source anything. It plays on some runs and not others, so
     * enabling this would trade a fast, accurate error for a 40-second wait
     * that usually ends in the same error.
     *
     * Two facts worth keeping for whoever picks this up: the page *does* fetch
     * `aweme/v1/web/aweme/detail/` with fully-signed query parameters — the
     * very signature yt-dlp cannot compute — so capturing that request and
     * replaying it is the promising route; and `shouldInterceptRequest` never
     * sees `<video>` media loads at all (they bypass the renderer's network
     * path), so any future attempt must read the player, not the requests.
     */
    override fun sniffPageUrl(resolvedUrl: String): String? = null

    override fun formatSelector(quality: Quality): String = cappedSelector(quality)

    // The Referer is not decoration: DouyinIE fetches aweme/v1/web/aweme/detail/
    // without one, and that request is refused every time when the jar holds
    // only ttwid. Sending it took the same request from 0/6 to 5/6 overseas and
    // 2/6 from a mainland exit. A forced --user-agent is still deliberately
    // absent — the UA measurably does not matter here, and guessing one burned
    // us on Xiaohongshu.
    override fun extraOptions(spec: DownloadSpec): List<YtDlpOption> = listOf(
        YtDlpOption("--merge-output-format", "mp4"),
        YtDlpOption("--no-playlist"),
        YtDlpOption("--referer", "https://www.douyin.com/"),
    )

    // Douyin answers this endpoint probabilistically rather than deterministically:
    // repeating one identical, valid request gets a video roughly half the time
    // from an overseas exit and about a third of the time from a mainland one.
    // Nothing distinguishes the good runs from the bad, so the only defence is
    // to ask again. At worst a third of the time, six attempts miss on all six
    // once in ~700 downloads; two attempts missed both roughly one time in nine,
    // which is what "it works for me and never for him" actually looked like.
    //
    // Cheap, too: a refusal comes back in about a second, and re-minting the
    // cookie jar between tries measurably does not help (fresh ttwid 6/10 vs
    // reused 6/10), so the retries do not repeat the slow WebView bootstrap.
    override val transientFailureRetries = 6

    /** Captions run long and are full of hashtag spam; uploader + id is stable and short. */
    override fun outputTemplate(): String = "%(uploader)s - %(id)s.%(ext)s"
}
