package com.zorro.videodl.core.handlers

import com.zorro.videodl.core.DownloadSpec
import com.zorro.videodl.core.Quality
import com.zorro.videodl.core.SiteHandler
import com.zorro.videodl.core.YtDlpOption

object BilibiliHandler : SiteHandler {
    override val id = "bilibili"
    override val displayName = "哔哩哔哩"

    override val urlPatterns = listOf(
        // BV ids are the current form, av numbers still resolve for old links.
        Regex("""(?:www\.|m\.)?bilibili\.com/video/(BV[\w]+|av\d+)""", RegexOption.IGNORE_CASE),
        // Bangumi/movie pages are a separate extractor but the same handler works.
        Regex("""(?:www\.|m\.)?bilibili\.com/bangumi/play/(ep|ss)\d+""", RegexOption.IGNORE_CASE),
        Regex("""b23\.tv/\S+""", RegexOption.IGNORE_CASE),
    )

    override val supportNote = "1080P 及以上需登录"

    // Without an account Bilibili caps most videos at 480p; signing in unlocks
    // 1080p, and a 大会员 account unlocks 1080p+/4K.
    override val loginUrl = "https://passport.bilibili.com/login"
    override val cookieDomains = listOf(".bilibili.com")

    override suspend fun normalizeUrl(url: String): String {
        // Share text looks like "【标题】 https://b23.tv/xxxx", so pull the bare
        // URL out before touching the network.
        val bare = Regex("""https?://\S+""").find(url)?.value?.trimEnd('，', '。', '、', ',', '.') ?: url
        // The query string can be load-bearing — `?p=3` selects which part of a
        // multi-part video to fetch — so expand the short link but keep it whole.
        return if (bare.contains("b23.tv")) UrlResolver.follow(bare) else bare
    }

    override fun formatSelector(quality: Quality): String = when (quality) {
        Quality.AUDIO_ONLY -> "ba[ext=m4a]/ba/b"
        // Prefer H.264: Bilibili also serves HEVC and AV1, which the system
        // gallery player on these phones will not decode. Falling through to an
        // unconstrained pair keeps 4K/HDR-only videos downloadable.
        Quality.BEST -> "bv*[vcodec^=avc]+ba/bv*+ba/b"
        else -> {
            val maxHeight = when (quality) {
                Quality.P1080 -> 1080
                Quality.P720 -> 720
                else -> 480
            }
            "bv*[height<=?$maxHeight][vcodec^=avc]+ba/" +
                "bv*[height<=?$maxHeight]+ba/" +
                "b[height<=?$maxHeight]/b"
        }
    }

    override fun extraOptions(spec: DownloadSpec): List<YtDlpOption> = buildList {
        // A bare BV link to a multi-part video expands to every part, which on
        // a lecture series is dozens of files. `?p=N` still selects one part.
        add(YtDlpOption("--no-playlist"))
        if (spec.quality == Quality.AUDIO_ONLY) {
            add(YtDlpOption("--extract-audio"))
            add(YtDlpOption("--audio-format", "m4a"))
        } else {
            // Video and audio always arrive as separate DASH streams here.
            add(YtDlpOption("--merge-output-format", "mp4"))
        }
        // Deliberately no forced --user-agent/--referer. A real 412 failure did
        // show up, but measuring it against the same URL proved headers are not
        // the cause: bare yt-dlp failed twice then succeeded, and adding a
        // Referer succeeded twice then failed. See transientFailureRetries.
    }

    // Bilibili answers HTTP 412 to repeat requests for the same page in quick
    // succession, and the metadata probe means every download asks twice. The
    // rejection is purely rate-based — verified by re-running one URL with
    // identical arguments and getting a different result each time — so the
    // fix is to back off and ask again, not to dress the request up.
    override val transientFailureRetries = 2
}
