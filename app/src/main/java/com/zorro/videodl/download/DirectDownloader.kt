package com.zorro.videodl.download

import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Plain HTTP fetch for a media URL that [MediaSniffer] read off a page.
 *
 * yt-dlp is not used for this leg on purpose: the URL is already the final
 * stream, and these CDN links are signed against the browser session that
 * produced them, so they have to be fetched with that session's user-agent,
 * referer and cookies or the CDN returns a 403.
 */
object DirectDownloader {

    private const val TAG = "DirectDownloader"
    private const val BUFFER = 64 * 1024

    suspend fun fetch(
        sniffed: MediaSniffer.Sniffed,
        target: File,
        onProgress: (percent: Float, line: String) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(sniffed.mediaUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", sniffed.userAgent)
                setRequestProperty("Referer", sniffed.pageUrl)
                setRequestProperty("Accept", "*/*")
                CookieManager.getInstance().getCookie(sniffed.pageUrl)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { setRequestProperty("Cookie", it) }
            }

            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("CDN 返回 HTTP $code")

                val total = connection.contentLengthLong
                var written = 0L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            val percent = if (total > 0) written * 100f / total else 0f
                            onProgress(percent, "浏览器直连下载 ${mib(written)}${if (total > 0) " / ${mib(total)}" else ""}")
                        }
                    }
                }
                Log.i(TAG, "fetched ${target.name}: $written bytes")
                if (written <= 0L) throw IllegalStateException("直连下载没有拿到数据")
                target
            } finally {
                connection.disconnect()
            }
        }.onFailure { target.delete() }
    }

    private fun mib(bytes: Long): String = String.format("%.1fMB", bytes / 1024.0 / 1024.0)
}
