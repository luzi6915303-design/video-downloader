package com.zorro.videodl.cookie

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import com.zorro.videodl.core.SiteHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fetches a site's account-free anti-bot cookie by loading its home page in an
 * off-screen WebView.
 *
 * Douyin is the case this exists for: yt-dlp's extractor refuses to return
 * formats without an `s_v_web_id` cookie, and yt-dlp cannot compute one — its
 * own source says fresh cookies are needed and offers no solver, because the
 * value comes out of the page's JavaScript. Nothing about that requires an
 * account, so making the user walk through a "login" screen with no credentials
 * to enter was busywork; this does the same thing invisibly.
 */
object CookieBootstrapper {

    private const val TAG = "CookieBootstrap"
    private const val TIMEOUT_MS = 25_000L
    private const val POLL_MS = 400L

    /**
     * Ensures the handler's bootstrap cookie is on disk. Returns true when the
     * site needs no bootstrap, when one was already stored, or when this run
     * captured it. A false return is not fatal — the caller should still try the
     * download and let yt-dlp produce the real error.
     */
    suspend fun ensure(context: Context, handler: SiteHandler): Boolean {
        val bootstrap = handler.cookieBootstrap ?: return true
        val app = context.applicationContext
        if (alreadyStored(app, handler, bootstrap.requiredCookie)) return true

        Log.i(TAG, "${handler.id}: fetching ${bootstrap.requiredCookie} from ${bootstrap.url}")
        val captured = withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main) { load(app, handler, bootstrap.url, bootstrap.requiredCookie) }
        } ?: false

        Log.i(TAG, "${handler.id}: bootstrap ${if (captured) "succeeded" else "timed out"}")
        return captured
    }

    /** The cookie file already carries the value, so there is nothing to fetch. */
    private fun alreadyStored(context: Context, handler: SiteHandler, cookieName: String): Boolean =
        CookieStore.cookieFileOrNull(context, handler.id)
            ?.readLines()
            ?.any { line -> !line.startsWith("#") && line.split('\t').getOrNull(5) == cookieName }
            ?: false

    /**
     * Must run on the main thread — WebView requires a Looper and refuses to be
     * created or destroyed anywhere else.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(
        context: Context,
        handler: SiteHandler,
        url: String,
        cookieName: String,
    ): Boolean {
        val manager = CookieManager.getInstance().apply { setAcceptCookie(true) }
        val webView = WebView(context).apply {
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = MOBILE_UA
            loadUrl(url)
        }

        try {
            // Poll rather than waiting on onPageFinished: the cookie is set by a
            // script that can run well before the page is "finished", and on a
            // slow network it can also arrive well after.
            repeat((TIMEOUT_MS / POLL_MS).toInt()) {
                delay(POLL_MS)
                val raw = manager.getCookie(url).orEmpty()
                if (raw.split(';').any { it.substringBefore('=').trim() == cookieName }) {
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

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"
}
