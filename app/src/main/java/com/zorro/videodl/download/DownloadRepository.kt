package com.zorro.videodl.download

import android.content.Context
import android.util.Log
import com.zorro.videodl.BuildConfig
import com.zorro.videodl.core.DownloadSpec
import com.zorro.videodl.core.DownloadTask
import com.zorro.videodl.core.Quality
import com.zorro.videodl.core.SiteRegistry
import com.zorro.videodl.core.TaskState
import com.zorro.videodl.cookie.CookieBootstrapper
import com.zorro.videodl.cookie.CookieStore
import com.zorro.videodl.engine.YtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID

/**
 * Owns the task list and drives execution. The UI only ever observes [tasks];
 * the foreground service only mirrors them into a notification.
 */
object DownloadRepository {

    private const val TAG = "DownloadRepo"
    private const val MAX_PARALLEL = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(MAX_PARALLEL)

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    fun activeCount(): Int = _tasks.value.count {
        it.state is TaskState.Queued || it.state is TaskState.Resolving || it.state is TaskState.Running
    }

    fun enqueue(context: Context, rawInput: String, quality: Quality): DownloadTask? {
        val url = SiteRegistry.extractUrl(rawInput) ?: rawInput.trim().takeIf { it.startsWith("http") } ?: return null
        val handler = SiteRegistry.resolve(url)
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = url,
            siteId = handler.id,
            siteName = handler.displayName,
            quality = quality,
        )
        _tasks.value = listOf(task) + _tasks.value
        run(context.applicationContext, task)
        return task
    }

    fun retry(context: Context, taskId: String) {
        val existing = _tasks.value.firstOrNull { it.id == taskId } ?: return
        update(taskId) { it.copy(state = TaskState.Queued) }
        run(context.applicationContext, existing)
    }

    fun cancel(taskId: String) {
        YtDlpEngine.cancel(taskId)
        update(taskId) { it.copy(state = TaskState.Cancelled) }
    }

    fun remove(taskId: String) {
        YtDlpEngine.cancel(taskId)
        _tasks.value = _tasks.value.filterNot { it.id == taskId }
    }

    fun clearFinished() {
        _tasks.value = _tasks.value.filter {
            it.state is TaskState.Queued || it.state is TaskState.Resolving || it.state is TaskState.Running
        }
    }

    private fun run(context: Context, task: DownloadTask) = scope.launch {
        gate.withPermit {
            // A cancel that landed while the task sat in the queue must still win.
            if (_tasks.value.firstOrNull { it.id == task.id }?.state is TaskState.Cancelled) return@withPermit

            val handler = SiteRegistry.byId(task.siteId)

            update(task.id) { it.copy(state = TaskState.Resolving) }

            // Sites like Douyin gate their extractor behind an anti-bot cookie
            // that needs no account, just a real browser engine. Fetch it here
            // rather than making the user find a "login" button for it.
            CookieBootstrapper.ensure(context, handler)
            val cookies = CookieStore.cookieFileOrNull(context, handler.id)

            val resolvedUrl = runCatching { handler.normalizeUrl(task.url) }.getOrDefault(task.url)
            // Worth keeping: when a share link resolves to something yt-dlp then
            // fails on, the expanded URL is the first thing you need to see.
            Log.d(TAG, "${handler.id}: ${task.url} -> $resolvedUrl")

            // Best-effort metadata so the row has a real title while it downloads.
            YtDlpEngine.probe(context, resolvedUrl, handler, cookies).getOrNull()?.let { info ->
                update(task.id) { it.copy(title = info.title ?: info.fulltitle, thumbnail = info.thumbnail) }
            }

            val workDir = File(context.cacheDir, "work/${task.id}").apply { mkdirs() }
            var spec = DownloadSpec(
                url = resolvedUrl,
                quality = task.quality,
                outputDir = workDir,
                cookieFile = cookies,
            )

            // Some sites (Douyin) answer an identical, valid request only some
            // of the time — see SiteHandler.transientFailureRetries. Re-run in
            // place rather than making the user tap "download" again.
            var result: Result<List<File>> = Result.failure(IllegalStateException("未开始"))
            var refetchedCookies = false
            val maxAttempts = 1 + handler.transientFailureRetries.coerceAtLeast(0)
            for (attempt in 1..maxAttempts) {
                result = YtDlpEngine.download(context, spec, handler, task.id) { percent, _, line ->
                    val shown = percentOf(line, percent)
                    update(task.id) { current ->
                        if (current.state is TaskState.Cancelled) current
                        else current.copy(state = TaskState.Running(shown, tidyProgress(line)))
                    }
                }
                val ok = result.getOrNull()?.isNotEmpty() == true
                if (ok || isCancelled(task.id) || attempt == maxAttempts) break

                // A jar the site refuses is worth replacing once — it may simply
                // be too old. Only once, though: re-minting it measurably does
                // not improve the odds, and each bootstrap costs a WebView load
                // of up to 40s, which across six attempts would be minutes of
                // waiting to buy nothing.
                if (needsFreshCookies(result.exceptionOrNull()) && !refetchedCookies) {
                    refetchedCookies = true
                    update(task.id) { it.copy(state = TaskState.Running(0f, "凭证失效，正在重新获取…")) }
                    CookieBootstrapper.ensure(context, handler, force = true)
                    spec = spec.copy(cookieFile = CookieStore.cookieFileOrNull(context, handler.id))
                } else {
                    val note = "请求被拦截，正在重试（$attempt/${maxAttempts - 1}）…"
                    update(task.id) { it.copy(state = TaskState.Running(0f, note)) }
                }
                // A short back-off, capped: the refusals are probabilistic rather
                // than rate-based, so waiting longer buys nothing and the user is
                // watching a spinner the whole time.
                delay(minOf(1_500L * attempt, 3_000L))
            }

            var files = result.getOrNull().orEmpty()

            // yt-dlp has run out of options. Fall back to letting the site's own
            // player fetch the video and taking the URL it uses — see
            // [MediaSniffer] for why that survives where the extractor does not.
            if (files.isEmpty() && !isCancelled(task.id) && handler.sniffPageUrl(resolvedUrl) != null) {
                update(task.id) { it.copy(state = TaskState.Running(0f, "常规解析失败，改用浏览器方式…")) }
                MediaSniffer.sniff(context, handler, resolvedUrl)?.let { sniffed ->
                    val name = "${handler.displayName} - ${sniffed.pageUrl.substringAfterLast('/')}.mp4"
                    DirectDownloader.fetch(sniffed, File(workDir, name)) { percent, line ->
                        update(task.id) { current ->
                            if (current.state is TaskState.Cancelled) current
                            else current.copy(state = TaskState.Running(percent, line))
                        }
                    }.fold(
                        onSuccess = { files = listOf(it) },
                        onFailure = { e -> Log.w(TAG, "${handler.id}: direct fetch failed", e) },
                    )
                }
            }

            when {
                isCancelled(task.id) -> Unit
                files.isNotEmpty() -> {
                    val saved = files.mapNotNull { MediaStoreSaver.publish(context, it).getOrNull() }
                    if (saved.isEmpty()) {
                        update(task.id) { it.copy(state = TaskState.Failed("保存到相册失败")) }
                    } else {
                        val first = saved.first()
                        update(task.id) {
                            it.copy(state = TaskState.Done(first.publicPath, first.uri, saved.size))
                        }
                    }
                }
                else -> {
                    val error = result.exceptionOrNull()
                    val reason = error?.let { friendlyError(it, handler.id) } ?: "没有产出文件"
                    val detail = diagnostics(context, handler, resolvedUrl, error)
                    update(task.id) { it.copy(state = TaskState.Failed(reason, detail)) }
                }
            }

            workDir.deleteRecursively()
        }
    }

    private fun isCancelled(taskId: String): Boolean =
        _tasks.value.firstOrNull { it.id == taskId }?.state is TaskState.Cancelled

    /**
     * Douyin's exact wording when its anti-bot refuses the jar we sent. Matched
     * narrowly on purpose: the same extractor also mentions cookies when a
     * video simply does not exist, and re-fetching cookies for that would just
     * burn 30 seconds before failing anyway.
     */
    private fun needsFreshCookies(e: Throwable?): Boolean =
        e?.message?.contains("fresh cookies", ignoreCase = true) == true

    /**
     * Everything needed to tell apart "this phone's cookies are broken" from
     * "this network is blocked" without being in the same room as the phone.
     * Surfaced behind the 详情 button on a failed row.
     */
    private suspend fun diagnostics(
        context: Context,
        handler: com.zorro.videodl.core.SiteHandler,
        resolvedUrl: String,
        error: Throwable?,
    ): String = buildString {
        appendLine("站点: ${handler.id}")
        appendLine("解析后链接: $resolvedUrl")
        val stored = CookieStore.storedCookieNames(context, handler.id)
        appendLine("已存凭证: ${if (stored.isEmpty()) "（无）" else stored.joinToString(", ")}")
        handler.cookieBootstrap?.let { bootstrap ->
            val wanted = listOf(bootstrap.requiredCookie) + bootstrap.serverCookies
            appendLine("缺少凭证: ${wanted.filterNot { it in stored }.ifEmpty { listOf("（无）") }.joinToString(", ")}")
        }
        CookieStore.ageSinceFetch(context, handler.id)?.let { appendLine("凭证获取于 ${it / 1000} 秒前") }
        appendLine("yt-dlp: ${YtDlpEngine.version(context) ?: "未知"}")
        appendLine("应用版本: ${BuildConfig.VERSION_NAME}")
        appendLine()
        appendLine("原始错误:")
        append(error?.message?.trim()?.takeIf { it.isNotBlank() } ?: error?.toString() ?: "（无输出）")
    }

    private val PERCENT = Regex("""(\d{1,3}(?:\.\d+)?)%""")

    /**
     * The library's progress callback reports 0 for some extractors even while
     * yt-dlp prints a percentage, so fall back to reading it off the line.
     */
    private fun percentOf(line: String, reported: Float): Float =
        if (reported > 0f) reported.coerceIn(0f, 100f)
        else PERCENT.find(line)?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(0f, 100f) ?: 0f

    /** Collapses yt-dlp's padded output into something that fits a phone row. */
    private fun tidyProgress(line: String): String =
        line.trim()
            .removePrefix("[download]")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun update(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
    }

    /** Turns yt-dlp's stderr wall of text into something actionable. */
    private fun friendlyError(e: Throwable, siteId: String): String {
        val raw = (e.message ?: e.toString())
        val lower = raw.lowercase()
        val handler = SiteRegistry.byId(siteId)
        return when {
            // A bootstrap site has no login screen to send anyone to; a failure
            // here means the anti-bot cookie didn't stick, and retrying is the
            // only useful advice. Match only Douyin's exact wording — the
            // extractor also says "cookies" when the video simply doesn't
            // exist, and mislabelling that as a credentials problem sends the
            // user chasing the wrong thing.
            lower.contains("fresh cookies") ->
                "${handler.displayName}的访问凭证失效，请重试（无需账号）"
            lower.contains("sign in") || lower.contains("login required") || lower.contains("account") ->
                if (handler.loginUrl != null) {
                    "需要登录：请到「设置 → 登录 ${handler.displayName}」登录一次后重试"
                } else {
                    "${handler.displayName}拒绝了这次请求，请稍后重试"
                }
            lower.contains("private") || lower.contains("unavailable") -> "视频不可用或已被设为私密"
            lower.contains("unsupported url") -> "yt-dlp 不认识这个链接（可能不是视频页）"
            lower.contains("404") || lower.contains("not found") -> "链接失效 (404)"
            lower.contains("timed out") || lower.contains("timeout") || lower.contains("resolve host") ->
                "网络超时，检查代理或重试"
            lower.contains("age") && lower.contains("restrict") -> "年龄限制内容，需要登录后重试"
            else -> raw.lineSequence().lastOrNull { it.isNotBlank() }?.take(300) ?: "下载失败"
        }
    }
}
