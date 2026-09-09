package com.zorro.videodl.download

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.zorro.videodl.core.SiteHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Last-resort resolver: loads the site's own watch page in a WebView and takes
 * the media URL off the requests the page makes while playing.
 *
 * The reason this beats the extractor when the extractor fails is that it is
 * not impersonating a browser — it *is* one, running the site's real player
 * with the site's real scripts, so an anti-bot that refuses a bare API call has
 * nothing to object to. The cost is that it needs the page to actually start
 * playing, hence the autoplay nudge below.
 */
object MediaSniffer {

    private const val TAG = "MediaSniffer"
    private const val TIMEOUT_MS = 40_000L
    private const val POLL_MS = 1_000L

    // The watch page is desktop-only in practice; a mobile UA gets an
    // "open in the app" wall with no player on it.
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

    data class Sniffed(val mediaUrl: String, val pageUrl: String, val userAgent: String)

    /** Returns the media URL the page fetched, or null if none showed up in time. */
    suspend fun sniff(context: Context, handler: SiteHandler, resolvedUrl: String): Sniffed? {
        val pageUrl = handler.sniffPageUrl(resolvedUrl) ?: return null
        Log.i(TAG, "${handler.id}: sniffing $pageUrl")

        // This is already the fallback path, so anything thrown here has no
        // better plan behind it. Constructing a WebView can fail outright (the
        // system package disabled or mid-update), and letting that escape would
        // strand the task in "downloading" forever instead of reporting the
        // original error.
        val found = runCatching {
            withTimeoutOrNull(TIMEOUT_MS) {
                withContext(Dispatchers.Main) { run(context.applicationContext, handler, pageUrl) }
            }
        }.onFailure { Log.w(TAG, "${handler.id}: sniff failed", it) }.getOrNull()

        Log.i(TAG, "${handler.id}: sniff ${if (found != null) "hit" else "found nothing"}")
        return found?.let { Sniffed(it, pageUrl, DESKTOP_UA) }
    }

    /** WebView is main-thread only, for construction, teardown and everything between. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun run(context: Context, handler: SiteHandler, pageUrl: String): String? {
        val result = CompletableDeferred<String>()
        CookieManager.getInstance().setAcceptCookie(true)
        val webView = WebView(context).apply {
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = DESKTOP_UA
            webViewClient = object : WebViewClient() {
                // Called off the main thread for every subresource. Only reads
                // the URL and lets the request continue untouched.
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val url = request?.url?.toString()
                    if (url != null && handler.isMediaUrl(url) && !result.isCompleted) {
                        Log.i(TAG, "${handler.id}: media $url")
                        result.complete(url)
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    // Muted playback is allowed to autostart, and a muted
                    // stream is the same file as an unmuted one.
                    view?.evaluateJavascript(AUTOPLAY_JS, null)
                }
            }
            loadUrl(pageUrl)
        }

        try {
            return coroutineScope {
                // Asking the player for its own source is the reliable half of
                // this: shouldInterceptRequest above never sees the media load,
                // because a <video> element is served by the media stack rather
                // than the renderer's network path. It still runs, since an
                // extractor-shaped request (HLS manifest, progressive fetch)
                // does pass through there.
                val poller = launch {
                    while (isActive && !result.isCompleted) {
                        delay(POLL_MS)
                        readVideoSrc(webView)?.let { src ->
                            if (!result.isCompleted) {
                                Log.i(TAG, "${handler.id}: player src $src")
                                result.complete(src)
                            }
                        }
                    }
                }
                val found = result.await()
                poller.cancel()
                found
            }
        } finally {
            webView.stopLoading()
            webView.destroy()
        }
    }

    /**
     * Reads the playing element's URL, or null while it has none yet.
     *
     * A `blob:` source means the page is feeding the element through Media
     * Source Extensions, and there is no single URL to fetch — treated as "not
     * found" so the caller reports the original error instead of a link that
     * cannot be downloaded.
     */
    private suspend fun readVideoSrc(webView: WebView): String? {
        val raw = CompletableDeferred<String?>()
        webView.evaluateJavascript(VIDEO_SRC_JS) { raw.complete(it) }
        val decoded = decodeJsString(raw.await()) ?: return null
        return decoded.takeIf { it.startsWith("http") }
    }

    /** `evaluateJavascript` hands back a JSON-encoded value, quotes and all. */
    private fun decodeJsString(value: String?): String? {
        if (value.isNullOrBlank() || value == "null" || value == "\"\"") return null
        return value.trim('"')
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .takeIf { it.isNotBlank() }
    }

    private const val VIDEO_SRC_JS =
        "(function(){var v=document.querySelector('video');" +
            "return v ? (v.currentSrc || v.src || '') : '';})()"

    private val AUTOPLAY_JS = """
        (function () {
          function play() {
            document.querySelectorAll('video').forEach(function (v) {
              v.muted = true;
              var p = v.play();
              if (p && p.catch) p.catch(function () {});
            });
          }
          play();
          setInterval(play, 1000);
        })();
    """.trimIndent()
}
