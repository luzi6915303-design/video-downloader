package com.zorro.videodl.core.handlers

import com.zorro.videodl.core.DownloadSpec
import com.zorro.videodl.core.Quality
import com.zorro.videodl.core.SiteHandler
import com.zorro.videodl.core.YtDlpOption

object YouTubeHandler : SiteHandler {
    override val id = "youtube"
    override val displayName = "YouTube"

    override val urlPatterns = listOf(
        Regex("""(?:www\.|m\.|music\.)?youtube\.com/(watch|shorts|live|embed|playlist)""", RegexOption.IGNORE_CASE),
        Regex("""youtu\.be/[\w-]+""", RegexOption.IGNORE_CASE),
    )

    override val supportNote = "会员/年龄限制内容需登录"
    override val loginUrl = "https://accounts.google.com/ServiceLogin?service=youtube"
    override val cookieDomains = listOf(".youtube.com", ".google.com")

    override fun formatSelector(quality: Quality): String = when (quality) {
        Quality.AUDIO_ONLY -> "ba[ext=m4a]/ba/b"
        Quality.BEST -> "bv*[ext=mp4]+ba[ext=m4a]/bv*+ba/b"
        else -> {
            val maxHeight = when (quality) {
                Quality.P1080 -> 1080
                Quality.P720 -> 720
                else -> 480
            }
            // The mp4/m4a pair must come first or it is never reached: yt-dlp takes
            // the leftmost alternative that resolves, and an unconstrained
            // "bv*+ba" ahead of it would always win and pull VP9/webm instead.
            "bv*[height<=?$maxHeight][ext=mp4]+ba[ext=m4a]/" +
                "bv*[height<=?$maxHeight]+ba/" +
                "b[height<=?$maxHeight]/b"
        }
    }

    override fun extraOptions(spec: DownloadSpec): List<YtDlpOption> = buildList {
        // A /watch URL that also carries &list= would otherwise pull the whole playlist.
        if (!spec.url.contains("/playlist", ignoreCase = true)) add(YtDlpOption("--no-playlist"))
        if (spec.quality == Quality.AUDIO_ONLY) {
            add(YtDlpOption("--extract-audio"))
            add(YtDlpOption("--audio-format", "m4a"))
        } else {
            add(YtDlpOption("--merge-output-format", "mp4"))
        }
        add(YtDlpOption("--embed-thumbnail"))
        add(YtDlpOption("--embed-metadata"))
        // Android clients dodge most of the throttling and SABR playback issues
        // the web client hits from a mobile IP.
        add(YtDlpOption("--extractor-args", "youtube:player_client=android,web_safari"))
    }
}
