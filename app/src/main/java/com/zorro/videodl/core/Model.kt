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
data class CookieBootstrap(
    val url: String,
    /**
     * The cookie whose presence proves the page's scripts ran. Typically
     * generated locally by that JavaScript, so it appears almost immediately.
     */
    val requiredCookie: String,
    /**
     * Cookies worth waiting a little longer for, but which the jar is still
     * usable without. They arrive after [requiredCookie], and on some networks
     * never at all — Douyin serves mainland China a cut-down landing page that
     * simply does not set them. Requiring them there meant no jar was ever
     * written, so put only genuinely optional extras here.
     */
    val serverCookies: List<String> = emptyList(),
    /** How long to keep waiting for [serverCookies] after [requiredCookie] shows up. */
    val settleMs: Long = 10_000,
    /**
     * How long a stored jar is trusted before it is fetched again. These
     * cookies are short-lived anti-bot tokens; the stored expiry cannot be
     * read back from the WebView, so age is tracked separately.
     */
    val maxAgeMs: Long = 6 * 60 * 60 * 1000,
)

/**
 * A watch page whose own API call carries the media. The app loads [pageUrl]
 * in a WebView, lets the page's scripts sign and send that call as they
 * normally would, and hands the response body to [parse].
 */
data class PageCapture(
    val pageUrl: String,
    /** Substring identifying the API request whose response carries the media. */
    val apiPath: String,
    /** Turns that response into files to fetch, or null when it holds nothing usable. */
    val parse: (body: String, quality: Quality) -> CapturedMedia?,
)

data class CapturedMedia(
    val title: String?,
    val thumbnail: String?,
    val files: List<MediaFile>,
)

/** One direct CDN link and the name to save it under, extension included. */
data class MediaFile(val url: String, val fileName: String)

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
    /**
     * [reason] is the one-line explanation shown in the row; [detail] keeps the
     * untouched tool output behind a "详情" button, because a failure that only
     * reproduces on someone else's phone is otherwise impossible to diagnose.
     */
    data class Failed(val reason: String, val detail: String? = null) : TaskState
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
