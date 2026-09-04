package com.zorro.videodl.core

import java.io.File

/** A single yt-dlp command line switch, optionally carrying a value. */
data class YtDlpOption(val flag: String, val value: String? = null)

/**
 * An anti-bot cookie a site's extractor demands but that involves no account —
 * loading [url] in a real browser engine is enough, because the value is
 * produced by the page's own JavaScript. Declaring one lets the app fetch it
 * silently instead of sending the user through a "login" with nothing to log
 * in to.
 */
data class CookieBootstrap(val url: String, val requiredCookie: String)

enum class Quality(val label: String) {
    BEST("最高画质"),
    P1080("1080p"),
    P720("720p"),
    P480("480p"),
    AUDIO_ONLY("仅音频 (m4a)"),
}

/** Everything a handler needs to turn a URL into a yt-dlp invocation. */
data class DownloadSpec(
    val url: String,
    val quality: Quality,
    val outputDir: File,
    val cookieFile: File?,
)

/** Progress/terminal states a download moves through. */
sealed interface TaskState {
    data object Queued : TaskState
    data object Resolving : TaskState
    data class Running(val percent: Float, val line: String) : TaskState
    data class Done(val savedTo: String, val uri: String?, val fileCount: Int = 1) : TaskState
    data class Failed(val reason: String) : TaskState
    data object Cancelled : TaskState
}

data class DownloadTask(
    val id: String,
    val url: String,
    val siteId: String,
    val siteName: String,
    val quality: Quality,
    val title: String? = null,
    val thumbnail: String? = null,
    val state: TaskState = TaskState.Queued,
    val createdAt: Long = System.currentTimeMillis(),
)
