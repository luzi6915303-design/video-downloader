package com.zorro.videodl.download

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.zorro.videodl.core.CapturedMedia
import com.zorro.videodl.core.PageCapture
import com.zorro.videodl.core.Quality
import com.zorro.videodl.core.SiteHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Loads a watch page in an off-screen WebView and reads the media out of the
 * response to the page's own API call.
 *
 * Douyin is why this exists. Since mid-September 2026 its detail API answers
 * 403 "Blocked by ArgusSecurityPlugin" to every request that the page's
 * security SDK did not sign — yt-dlp went from ~5/6 to 0/6, cookies or not.
 * The page itself still gets a full answer, because it *is* the page. So
 * instead of imitating the call, let the page make it and listen in.
 *
 * Listening in means wrapping XHR and fetch before the page's scripts run.
 * `shouldInterceptRequest` cannot do this job: it sees the request URL but
 * never the response body, and replaying that URL from outside is exactly the
 * kind of request the anti-bot now refuses.
 */
object PageCapturer {

    private const val TAG = "PageCapturer"
    private const val BRIDGE = "__vdlBridge"

    /**
     * Per page load. A good load answers in 5–16s; the rest of the window is
     * for slow networks. Measured in desktop Chrome, about one load in four
     * never issues the API call at all, and a reload fixes it — hence two
     * shorter loads rather than one long one.
     */
    private const val LOAD_TIMEOUT_MS = 25_000L
    private const val LOADS = 2

    // A mobile UA gets the "open in the app" page, whose player only carries a
    // watermarked 720p stream and never calls the detail API.
    // Public because the CDN links in the response are fetched afterwards with
    // the same identity that obtained them.
    const val DESKTOP_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

    /** Returns what the page's API response describes, or null if nothing usable arrived. */
    suspend fun capture(context: Context, handler: SiteHandler, capture: PageCapture, quality: Quality): CapturedMedia? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Without it the hook would land after the page's scripts had
            // already fired the call. Falling through to yt-dlp is the honest
            // answer rather than a load that cannot succeed.
            Log.w(TAG, "${handler.id}: WebView lacks DOCUMENT_START_SCRIPT, skipping")
            return null
        }
        for (load in 1..LOADS) {
            Log.i(TAG, "${handler.id}: loading ${capture.pageUrl} ($load/$LOADS)")
            // Constructing a WebView can throw outright (system package disabled
            // or mid-update); that must not strand the task, so treat it as a miss.
            val found = runCatching {
                withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) { run(context.applicationContext, capture, quality) }
                }
            }.onFailure { Log.w(TAG, "${handler.id}: capture failed", it) }.getOrNull()
            if (found != null) {
                Log.i(TAG, "${handler.id}: captured ${found.files.size} file(s)")
                return found
            }
        }
        Log.i(TAG, "${handler.id}: nothing captured")
        return null
    }

    /** WebView is main-thread only, for construction, teardown and everything between. */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun run(context: Context, capture: PageCapture, quality: Quality): CapturedMedia? {
        val result = CompletableDeferred<CapturedMedia>()
        CookieManager.getInstance().setAcceptCookie(true)
        val webView = WebView(context).apply {
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = DESKTOP_UA
            // Lay the page out at desktop width; otherwise the site's own
            // responsive code can still switch to its mobile layout.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            disguiseAsDesktop(this)
            webViewClient = WebViewClient()
            addJavascriptInterface(
                object {
                    // Runs on the WebView's binder thread, not main. The page
                    // can call the API more than once (a first empty answer is
                    // common), so keep listening until one parses.
                    @JavascriptInterface
                    fun onResponse(url: String, body: String) {
                        if (result.isCompleted) return
                        val parsed = runCatching { capture.parse(body, quality) }
                            .onFailure { Log.w(TAG, "parse failed for $url", it) }
                            .getOrNull()
                        if (parsed != null && parsed.files.isNotEmpty()) result.complete(parsed)
                        else Log.i(TAG, "unusable response (${body.length} bytes) from $url")
                    }
                },
                BRIDGE,
            )
            WebViewCompat.addDocumentStartJavaScript(this, DESKTOP_JS + hookJs(capture.apiPath), setOf("*"))
            loadUrl(capture.pageUrl)
        }

        try {
            return result.await()
        } finally {
            webView.stopLoading()
            webView.destroy()
        }
    }

    /**
     * A desktop UA alone is not enough: a WebView still announces itself in
     * the Client Hints headers ("Android WebView", platform Android), in an
     * X-Requested-With header naming this app, and through navigator.platform
     * and touch support. Douyin's risk check reads those, sees an Android app
     * claiming to be a Mac, and answers with a slider captcha instead of the
     * video. Each is brought in line with the UA where the WebView allows it.
     */
    private fun disguiseAsDesktop(webView: WebView) {
        val settings = webView.settings
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            fun brand(name: String, major: String, full: String) = UserAgentMetadata.BrandVersion.Builder()
                .setBrand(name).setMajorVersion(major).setFullVersion(full).build()
            WebSettingsCompat.setUserAgentMetadata(
                settings,
                UserAgentMetadata.Builder()
                    .setBrandVersionList(
                        listOf(
                            brand("Google Chrome", "131", "131.0.0.0"),
                            brand("Chromium", "131", "131.0.0.0"),
                            brand("Not_A Brand", "24", "24.0.0.0"),
                        ),
                    )
                    .setFullVersion("131.0.0.0")
                    .setPlatform("macOS")
                    .setPlatformVersion("14.5.0")
                    .setArchitecture("arm")
                    .setBitness(64)
                    .setModel("")
                    .setMobile(false)
                    .build(),
            )
        }
    }

    /** The in-page half of [disguiseAsDesktop], for what no WebView setting reaches. */
    private val DESKTOP_JS = """
        (function () {
          function fake(obj, name, value) {
            try { Object.defineProperty(obj, name, { get: function () { return value; }, configurable: true }); } catch (e) {}
          }
          fake(Navigator.prototype, 'platform', 'MacIntel');
          fake(Navigator.prototype, 'maxTouchPoints', 0);
        })();
    """.trimIndent() + "\n"

    /**
     * Wraps XHR and fetch so any response from [apiPath] is copied to the
     * bridge. The page's own requests are otherwise untouched: same arguments,
     * same promise, same object back to the caller.
     */
    private fun hookJs(apiPath: String): String = """
        (function () {
          if (window.__vdlHooked) return;
          window.__vdlHooked = true;
          var PATH = ${JSONObject.quote(apiPath)};
          function report(url, body) {
            try {
              if (url && String(url).indexOf(PATH) >= 0 && body) $BRIDGE.onResponse(String(url), String(body));
            } catch (e) {}
          }
          var open = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function (method, url) {
            this.__vdlUrl = url;
            return open.apply(this, arguments);
          };
          var send = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.send = function () {
            var xhr = this;
            xhr.addEventListener('load', function () {
              try {
                var url = xhr.responseURL || xhr.__vdlUrl;
                if (xhr.responseType === '' || xhr.responseType === 'text') report(url, xhr.responseText);
                else if (xhr.responseType === 'json') report(url, JSON.stringify(xhr.response));
              } catch (e) {}
            });
            return send.apply(this, arguments);
          };
          if (window.fetch) {
            var nativeFetch = window.fetch;
            window.fetch = function (input) {
              var pending = nativeFetch.apply(this, arguments);
              pending.then(function (response) {
                try {
                  var url = response.url || (typeof input === 'string' ? input : input && input.url);
                  if (url && String(url).indexOf(PATH) >= 0) {
                    response.clone().text().then(function (t) { report(url, t); });
                  }
                } catch (e) {}
              }, function () {});
              return pending;
            };
          }
        })();
    """.trimIndent()
}
