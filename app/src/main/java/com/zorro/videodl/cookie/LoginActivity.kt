package com.zorro.videodl.cookie

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.HttpURLConnection
import java.net.URL
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.zorro.videodl.core.SiteRegistry
import com.zorro.videodl.ui.AppTheme
import android.widget.Toast

/**
 * In-app browser used purely to obtain cookies for a site. Nothing is sent
 * anywhere — the cookies land in a local file that yt-dlp reads.
 */
class LoginActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val siteId = intent.getStringExtra(EXTRA_SITE_ID).orEmpty()
        val handler = SiteRegistry.byId(siteId)
        val startUrl = handler.loginUrl
        if (startUrl == null) {
            finish()
            return
        }

        CookieManager.getInstance().setAcceptCookie(true)
        // Lets `chrome://inspect` on a desktop Chrome attach to this WebView over
        // adb — the fastest way to see what a login page is actually doing when
        // it renders blank. Fine to leave on; it only activates for a dev with
        // adb access, never for an ordinary install.
        WebView.setWebContentsDebuggingEnabled(true)

        setContent {
            AppTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("登录 ${handler.displayName}") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                }
                            },
                            actions = {
                                TextButton(onClick = {
                                    val n = CookieStore.captureFromWebView(this@LoginActivity, handler)
                                    Toast.makeText(
                                        this@LoginActivity,
                                        if (n > 0) "已保存 $n 条登录信息" else "没读到登录信息，确认已登录成功",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    if (n > 0) finish()
                                }) { Text("保存登录") }
                            },
                        )
                    },
                ) { padding ->
                    AndroidView(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        factory = { context ->
                            WebView(context).apply {
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                // Without these, the WebView parses a modern responsive
                                // page as if it had no viewport meta tag at all: the JS
                                // runs and the page "finishes loading" (confirmed via
                                // onPageFinished + console logs) but real content lands
                                // outside the visible viewport, leaving only background
                                // color on screen.
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.userAgentString = MOBILE_UA
                                // Meta's static assets are content-hashed and cached
                                // aggressively; a stylesheet fetched before the dvh
                                // rewrite below existed would otherwise keep being
                                // served from the WebView's disk cache forever,
                                // bypassing shouldInterceptRequest entirely.
                                clearCache(true)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldInterceptRequest(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): WebResourceResponse? {
                                        val url = request.url.toString()
                                        if (!looksLikeCss(url)) return null
                                        return rewriteDvhInCss(url) ?: null
                                    }

                                    override fun onPageFinished(view: WebView, url: String) {
                                        Log.d(TAG, "onPageFinished: $url")
                                        view.evaluateJavascript(DVH_PATCH_JS, null)
                                    }

                                    override fun onReceivedError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        error: WebResourceError,
                                    ) {
                                        Log.w(
                                            TAG,
                                            "onReceivedError: ${request.url} code=${error.errorCode} " +
                                                "desc=${error.description}",
                                        )
                                    }

                                    override fun onReceivedHttpError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        errorResponse: android.webkit.WebResourceResponse,
                                    ) {
                                        Log.w(
                                            TAG,
                                            "onReceivedHttpError: ${request.url} " +
                                                "status=${errorResponse.statusCode}",
                                        )
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                        Log.d(
                                            TAG,
                                            "console[${message.messageLevel()}] ${message.message()} " +
                                                "(${message.sourceId()}:${message.lineNumber()})",
                                        )
                                        return true
                                    }

                                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                                        Log.d(TAG, "progress: $newProgress%")
                                    }
                                }
                                loadUrl(startUrl)
                            }
                        },
                    )
                }
            }
        }
    }

    private fun looksLikeCss(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').endsWith(".css", ignoreCase = true)

    /**
     * Some Meta/Instagram stylesheets size full-screen containers with the
     * `dvh`/`svh`/`lvh` viewport units. This WebView build parses them but
     * resolves them to 0 instead of falling back to the static viewport
     * height — confirmed by measuring a `height: 100dvh` test element, which
     * came back 0 even though `window.innerHeight` reported the real value.
     * A real Chrome tab handles this fine (it's specific to WebView's lack of
     * on-screen browser chrome, which is what these units are designed
     * around), so the fix has to happen here rather than by asking anyone to
     * update anything. Rewriting the unit to plain `vh` on the way in is a
     * blunt fix — it loses the "ignore the on-screen keyboard" nuance `dvh`
     * has over `vh` — but for a login page that trade-off is invisible.
     */
    private fun rewriteDvhInCss(url: String): WebResourceResponse? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", MOBILE_UA)
            CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
        }
        val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val fixed = text
            .replace(Regex("""(\d)dvh\b"""), "$1vh")
            .replace(Regex("""(\d)svh\b"""), "$1vh")
            .replace(Regex("""(\d)lvh\b"""), "$1vh")
        // The response MUST carry CORS headers. Meta loads every stylesheet with
        // `crossorigin="anonymous"`, and the three-argument WebResourceResponse
        // constructor sends no headers at all, so the browser rejects the sheet
        // for a missing Access-Control-Allow-Origin and the page renders
        // unstyled — which is what turned the post-login cookie-consent dialog
        // into a 5000px wall of raw text. Proved by requesting one failing sheet
        // twice through this same interceptor: with crossOrigin set the <link>
        // fired onerror, without it onload.
        WebResourceResponse(
            "text/css",
            "utf-8",
            200,
            "OK",
            mapOf("Access-Control-Allow-Origin" to "*"),
            fixed.byteInputStream(Charsets.UTF_8),
        ).also { connection.disconnect() }
    }.onFailure { Log.w(TAG, "rewriteDvhInCss failed for $url: ${it.message}") }.getOrNull()

    companion object {
        private const val TAG = "LoginActivity"
        private const val EXTRA_SITE_ID = "site_id"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"

        /**
         * Second line of defense against the dvh bug, for CSS that never went
         * through [rewriteDvhInCss] because it arrived as an inline `<style>`
         * tag rather than a fetched `.css` file — common for a login flow's
         * "critical CSS", and for SPA sub-screens (like a verification-code
         * step) that swap content without a fresh navigation. Operates on the
         * already-parsed CSSOM, so it doesn't touch cross-origin sheets that
         * lack CORS headers (rare in practice; the network-level fix already
         * covers those), and re-runs on a timer since some of these screens
         * mutate their own stylesheets after the fact.
         */
        private const val DVH_PATCH_JS = """
            (function() {
              // Meta's "Bloks" screens (the verification-code step, at least)
              // nest several `display:flex; flex-direction:column` containers
              // that size children with `height:100%`. A flex item's default
              // min-height is "as tall as its content", not 0, so a percentage
              // height can fail to reach all the way down the chain and the
              // whole thing collapses to 0 — confirmed by walking the DOM from
              // the actual verification-code <input> up to <html> and finding
              // a working 600px ancestor two levels above a 0px one. Real
              // Chrome tabs don't hit this (sites are usually authored/tested
              // against it), so the reset happens here instead.
              if (!window.__minHeightResetInjected) {
                window.__minHeightResetInjected = true;
                var style = document.createElement('style');
                style.textContent = '*, *::before, *::after { min-height: 0 !important; min-width: 0 !important; }';
                document.documentElement.appendChild(style);
              }
              function patchSheet(sheet) {
                var rules;
                try { rules = sheet.cssRules || sheet.rules; } catch (e) { return; }
                if (!rules) return;
                for (var i = rules.length - 1; i >= 0; i--) {
                  var rule = rules[i];
                  try {
                    if (rule.cssRules) { patchSheet(rule); continue; }
                    if (rule.cssText && /\d(dvh|svh|lvh)\b/.test(rule.cssText)) {
                      var fixed = rule.cssText.replace(/(\d)(dvh|svh|lvh)\b/g, '${'$'}1vh');
                      sheet.deleteRule(i);
                      sheet.insertRule(fixed, i);
                    }
                  } catch (e) {}
                }
              }
              // Third bug, distinct from the two above and the one that made the
              // email-verification screen render as a plain white page: on that
              // screen Instagram puts the content inside an `overflow: auto`
              // scroller, so the container's height comes from its parent rather
              // than from its own content. That chain has to terminate at a real
              // height on <html>, and here it doesn't — <html> measured 16px (one
              // default line box) against a 673px viewport, <body> measured 0, and
              // so did all 15 elements between <body> and the verification-code
              // <input>, which itself measured a healthy 248x20. The login form
              // escapes this only because it is normal flow, so its content pushes
              // <body> open from the inside.
              //
              // `height: 100%` on <html> does NOT fix it — it resolves to 0 here,
              // the same root-containing-block failure that makes dvh resolve to 0
              // above — so the root has to be pinned in absolute pixels. Once it
              // is, each collapsed descendant can take `height: 100%`, but only
              // applied outermost-first: an inner 100% needs its parent to already
              // have a real height to resolve against.
              function fixRootCollapse() {
                var body = document.body;
                if (!body) return;
                var vh = window.innerHeight;
                if (!vh) return;
                // Only intervene on an actually-collapsed page. A healthy screen
                // (the login form) must be left completely alone.
                if (body.getBoundingClientRect().height > 1) return;
                if (!body.innerText || !body.innerText.trim()) return;

                // Anchor on the deepest element that still has a real box: the
                // collapse runs from <body> down to just above it.
                var seed = null, seedDepth = -1;
                var all = body.querySelectorAll('*');
                for (var i = 0; i < all.length; i++) {
                  if (all[i].getBoundingClientRect().height <= 0) continue;
                  var d = 0;
                  for (var p = all[i]; p; p = p.parentElement) d++;
                  if (d > seedDepth) { seedDepth = d; seed = all[i]; }
                }
                if (!seed) return;

                var chain = [];
                for (var el = seed; el && el !== document.documentElement; el = el.parentElement) {
                  if (el.getBoundingClientRect().height === 0) chain.push(el);
                }
                document.documentElement.style.setProperty('height', vh + 'px', 'important');
                body.style.setProperty('height', '100%', 'important');
                chain.reverse();
                for (var j = 0; j < chain.length; j++) {
                  chain[j].style.setProperty('height', '100%', 'important');
                }
              }

              // The pinned root height is in pixels, so it goes stale whenever the
              // viewport changes — most often the on-screen keyboard opening over
              // the verification-code field.
              if (!window.__rootFixResizeBound) {
                window.__rootFixResizeBound = true;
                window.addEventListener('resize', function() {
                  if (document.documentElement.style.height) {
                    document.documentElement.style.setProperty(
                      'height', window.innerHeight + 'px', 'important');
                  }
                });
              }

              function patchAll() {
                try { Array.prototype.forEach.call(document.styleSheets, patchSheet); } catch (e) {}
                try { fixRootCollapse(); } catch (e) {}
              }
              patchAll();
              try {
                new MutationObserver(patchAll).observe(document.documentElement, {childList: true, subtree: true});
              } catch (e) {}
              if (!window.__dvhPatchTimer) {
                window.__dvhPatchTimer = setInterval(patchAll, 800);
              }
            })();
        """

        fun intent(context: Context, siteId: String): Intent =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_SITE_ID, siteId)
    }
}
