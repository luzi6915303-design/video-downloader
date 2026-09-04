package com.zorro.videodl.core

/**
 * One implementation per supported site. Adding a new site means adding a
 * handler and registering it in [SiteRegistry] — nothing else in the app
 * needs to know the site exists.
 */
interface SiteHandler {
    /** Stable identifier, persisted with tasks. */
    val id: String

    /** Shown in the UI. */
    val displayName: String

    /** URL shapes this handler claims. */
    val urlPatterns: List<Regex>

    /**
     * One line for the "supported sites" table, saying what a user has to do
     * for this site — not what the code does. Keep it short enough to sit in a
     * table cell on a phone.
     */
    val supportNote: String get() = "无需登录"

    /** Page to open for an in-app WebView login, or null if login is not supported. */
    val loginUrl: String?

    /** Cookie domains harvested after a login, for the Netscape cookie jar. */
    val cookieDomains: List<String>

    /**
     * Set when the site gates its extractor behind an account-free anti-bot
     * cookie, which the app fetches in a hidden WebView before downloading.
     * Independent of [loginUrl]: a site can need this and no login at all.
     */
    val cookieBootstrap: CookieBootstrap? get() = null

    fun matches(url: String): Boolean = urlPatterns.any { it.containsMatchIn(url) }

    /**
     * Clean up or expand the URL before handing it to yt-dlp. Runs off the main
     * thread, so following a short-link redirect here is fine.
     */
    suspend fun normalizeUrl(url: String): String = url

    /** yt-dlp `-f` expression for the requested quality. */
    fun formatSelector(quality: Quality): String

    /** Site-specific switches merged into every request. */
    fun extraOptions(spec: DownloadSpec): List<YtDlpOption> = emptyList()

    /**
     * How many extra times to re-run a failed download before surfacing the
     * error. Meant only for sites whose first attempt reliably "primes"
     * something the next attempt needs — e.g. an anti-bot that issues fresh
     * cookies in its rejection response, which yt-dlp then writes back into the
     * `--cookies` file, so a second run with the same file just works. Default
     * 0: most failures are real and retrying only wastes the user's time.
     */
    val transientFailureRetries: Int get() = 0

    /** yt-dlp `-o` template. `.100B` caps the title at 100 *bytes* so CJK
     *  filenames cannot overflow the filesystem limit. */
    fun outputTemplate(): String = "%(title).100B [%(id)s].%(ext)s"
}

/** Shared default: progressive-fallback selector capped at a height. */
internal fun cappedSelector(quality: Quality): String = when (quality) {
    Quality.BEST -> "bv*+ba/b"
    Quality.P1080 -> "bv*[height<=?1080]+ba/b[height<=?1080]/bv*+ba/b"
    Quality.P720 -> "bv*[height<=?720]+ba/b[height<=?720]/bv*+ba/b"
    Quality.P480 -> "bv*[height<=?480]+ba/b[height<=?480]/bv*+ba/b"
    Quality.AUDIO_ONLY -> "ba[ext=m4a]/ba/b"
}
