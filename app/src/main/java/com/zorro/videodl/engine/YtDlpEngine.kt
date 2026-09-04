package com.zorro.videodl.engine

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import com.zorro.videodl.core.DownloadSpec
import com.zorro.videodl.core.Quality
import com.zorro.videodl.core.SiteHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thin wrapper over the bundled yt-dlp. Owns one-time native init and turns a
 * [SiteHandler] + [DownloadSpec] into an actual process invocation.
 */
object YtDlpEngine {

    private const val TAG = "YtDlpEngine"
    private val initMutex = Mutex()
    @Volatile private var initialized = false

    /** Unpacks Python, yt-dlp and ffmpeg. Safe to call repeatedly. */
    suspend fun ensureInit(context: Context) = withContext(Dispatchers.IO) {
        if (initialized) return@withContext
        initMutex.withLock {
            if (initialized) return@withLock
            val app = context.applicationContext
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            initialized = true
            Log.i(TAG, "yt-dlp ready: ${runCatching { YoutubeDL.getInstance().version(app) }.getOrNull()}")
        }
    }

    suspend fun version(context: Context): String? = withContext(Dispatchers.IO) {
        ensureInit(context)
        runCatching { YoutubeDL.getInstance().version(context.applicationContext) }.getOrNull()
    }

    /** Pulls yt-dlp's latest release so newly-broken extractors get fixed without an app update. */
    suspend fun updateYtDlp(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ensureInit(context)
            val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext)
            val v = YoutubeDL.getInstance().version(context.applicationContext).orEmpty()
            when (status) {
                YoutubeDL.UpdateStatus.DONE -> "已更新到 $v"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "已是最新 ($v)"
                else -> "状态未知 ($v)"
            }
        }
    }

    /** Metadata-only pass, used to show a title/thumbnail before committing to a download. */
    suspend fun probe(context: Context, url: String, handler: SiteHandler, cookieFile: File?): Result<VideoInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureInit(context)
                val request = YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addOption("--socket-timeout", "20")
                    applyCookies(cookieFile)
                    handler.extraOptions(DownloadSpec(url, Quality.BEST, File(""), cookieFile))
                        .filter { it.flag in METADATA_SAFE_FLAGS }
                        .forEach { opt -> opt.value?.let { addOption(opt.flag, it) } ?: addOption(opt.flag) }
                }
                YoutubeDL.getInstance().getInfo(request)
            }
        }

    /**
     * Runs the download. yt-dlp writes into [DownloadSpec.outputDir], which the
     * caller supplies as a fresh empty directory — whatever lands there is the
     * result, which sidesteps having to predict the final filename.
     *
     * @param processId opaque handle for [cancel].
     */
    suspend fun download(
        context: Context,
        spec: DownloadSpec,
        handler: SiteHandler,
        processId: String,
        onProgress: (percent: Float, etaSeconds: Long, line: String) -> Unit,
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        runCatching {
            ensureInit(context)
            spec.outputDir.mkdirs()

            val request = YoutubeDLRequest(spec.url).apply {
                addOption("-f", handler.formatSelector(spec.quality))
                addOption("-o", File(spec.outputDir, handler.outputTemplate()).absolutePath)
                addOption("--no-mtime")
                addOption("--newline")
                addOption("--no-warnings")
                addOption("--socket-timeout", "20")
                addOption("--retries", "5")
                addOption("--fragment-retries", "10")
                // Progress lines arrive on stdout; without this the % never moves.
                addOption("--progress")
                applyCookies(spec.cookieFile)
                // The engine owns where files land; a handler that redirects output
                // would send writes to the process working directory ("/" on
                // Android, read-only). Filename shape belongs in outputTemplate().
                handler.extraOptions(spec)
                    .filterNot { it.flag in OUTPUT_FLAGS }
                    .forEach { opt -> opt.value?.let { addOption(opt.flag, it) } ?: addOption(opt.flag) }
            }

            val response = YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
                onProgress(progress, eta, line)
            }
            if (response.exitCode != 0) {
                throw IllegalStateException(response.err.ifBlank { "yt-dlp exit ${response.exitCode}" })
            }

            spec.outputDir.listFiles()
                ?.filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") }
                ?.sortedByDescending { it.length() }
                ?: emptyList()
        }
    }

    fun cancel(processId: String): Boolean = runCatching {
        YoutubeDL.getInstance().destroyProcessById(processId)
    }.getOrDefault(false)

    private fun YoutubeDLRequest.applyCookies(cookieFile: File?) {
        if (cookieFile != null && cookieFile.exists() && cookieFile.length() > 0) {
            addOption("--cookies", cookieFile.absolutePath)
        }
    }

    /** Output routing is reserved to the engine. */
    private val OUTPUT_FLAGS = setOf("-o", "--output", "-P", "--paths")

    /** Flags that make sense during a metadata probe; the rest only affect muxing/output. */
    private val METADATA_SAFE_FLAGS = setOf("--user-agent", "--referer", "--extractor-args", "--no-playlist")
}
