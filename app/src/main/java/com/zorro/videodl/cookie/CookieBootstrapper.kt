package com.zorro.videodl.cookie

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import com.zorro.videodl.core.CookieBootstrap
import com.zorro.videodl.core.SiteHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fetches a site's account-free anti-bot cookies by loading its home page in an
 * off-screen WebView.
 *
 * Douyin is the case this exists for: yt-dlp's extractor refuses to return
 * formats without the site's anti-bot cookies, and yt-dlp cannot compute them —
 * its own source says fresh cookies are needed and offers no solver, because
 * the values come out of the page's JavaScript. Nothing about that requires an
 * account, so making the user walk through a "login" screen with no credentials
 * to enter was busywork; this does the same thing invisibly.
 *
 * Two things here are less obvious than they look, and both caused real
 * failures:
 *
 * 1. The cookies do not all arrive together. The JavaScript-generated one shows
 *    up almost instantly; the ones the server issues take a round trip more.
 *    Returning at the first sight of the former stores a jar that *looks*
 *    complete, which is why this worked on one phone and never on another.
 * 2. A stored jar goes stale. These are short-lived tokens, and the WebView API
 *    does not hand back expiry times, so freshness is tracked by [CookieStore]
 *    stamp instead of trusting whatever is on disk forever.
 */
object CookieBootstrapper {

    private const val TAG = "CookieBootstrap"
    private const val TIMEOUT_MS = 30_000L
    private const val POLL_MS = 300L

    /**
     * Ensures the handler's bootstrap cookies are on disk and recent. Returns
     * true when the site needs no bootstrap, when a fresh jar was already
     * stored, or when this run captured one. A false return is not fatal — the
     * caller should still try the download and let yt-dlp produce the real
     * error.
     *
     * @param force re-fetch even if the stored jar still looks fresh, used
     *   after the site has told us the cookies it holds are no longer accepted.
     */
    suspend fun ensure(context: Context, handler: SiteHandler, force: Boolean = false): Boolean {
        val bootstrap = handler.cookieBootstrap ?: return true
        val app = context.applicationContext
        if (!force && isFresh(app, handler, bootstrap)) return true
        if (force) CookieStore.clear(app, handler.id)

        Log.i(TAG, "${handler.id}: fetching cookies from ${bootstrap.url} (force=$force)")
        val captured = withTimeoutOrNull(TIMEOUT_MS + bootstrap.settleMs) {
            withContext(Dispatchers.Main) { load(app, handler, bootstrap) }
        } ?: false

        if (captured) CookieStore.markFetched(app, handler.id)
        Log.i(
            TAG,
            "${handler.id}: bootstrap ${if (captured) "succeeded" else "failed"}, " +
                "have=${CookieStore.storedCookieNames(app, handler.id)}",
        )
        return captured
    }

    /**
     * True when the stored jar carries the cookie the site actually needs and
     * was fetched recently enough to still be accepted.
     *
     * Only [CookieBootstrap.requiredCookie] is checked. Demanding the
     * [CookieBootstrap.serverCookies] too made this return false forever on any
     * network where the site does not set them, so every single download paid
     * for a full bootstrap and still got nowhere.
     */
    private fun isFresh(context: Context, handler: SiteHandler, bootstrap: CookieBootstrap): Boolean {
        val stored = CookieStore.storedCookieNames(context, handler.id)
        if (bootstrap.requiredCookie !in stored) {
            Log.i(TAG, "${handler.id}: jar missing ${bootstrap.requiredCookie}, have=$stored")
            return false
        }
        val age = CookieStore.ageSinceFetch(context, handler.id)
        if (age == null || age > bootstrap.maxAgeMs) {
            Log.i(TAG, "${handler.id}: jar stale (age=$age)")
            return false
        }
        return true
    }

    /**
     * Must run on the main thread — WebView requires a Looper and refuses to be
     * created or destroyed anywhere else.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(context: Context, handler: SiteHandler, bootstrap: CookieBootstrap): Boolean {
        val manager = CookieManager.getInstance().apply { setAcceptCookie(true) }
        val webView = WebView(context).apply {
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = MOBILE_UA
            loadUrl(bootstrap.url)
        }

        try {
            // Poll rather than waiting on onPageFinished: the cookies are set by
            // scripts that can run well before the page is "finished", and on a
            // slow network well after it.
            var requiredSeenAt: Long? = null
            repeat(((TIMEOUT_MS + bootstrap.settleMs) / POLL_MS).toInt()) {
                delay(POLL_MS)
                val present = presentCookies(manager, bootstrap.url)

                if (bootstrap.requiredCookie !in present) return@repeat
                if (requiredSeenAt == null) requiredSeenAt = System.currentTimeMillis()

                // Keep waiting for the server-issued cookies, but not forever —
                // a jar with the required cookie alone is still worth more than
                // nothing, so settle rather than time out empty-handed.
                val haveAll = bootstrap.serverCookies.all { it in present }
                val settled = System.currentTimeMillis() - requiredSeenAt!! >= bootstrap.settleMs
                if (haveAll || settled) {
                    Log.i(TAG, "${handler.id}: captured $present (complete=$haveAll)")
                    manager.flush()
                    return CookieStore.captureFromWebView(context, handler) > 0
                }
            }
            return false
        } finally {
            webView.stopLoading()
            webView.destroy()
        }
    }

    private fun presentCookies(manager: CookieManager, url: String): Set<String> =
        manager.getCookie(url).orEmpty()
            .split(';')
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"
}
