package com.zorro.videodl.download

import android.content.Context
import android.util.Log
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
            val spec = DownloadSpec(
                url = resolvedUrl,
                quality = task.quality,
                outputDir = workDir,
                cookieFile = cookies,
            )

            // Some sites (Douyin) reject the first attempt but leave the cookie
            // file in a state that makes the next one work — see
            // SiteHandler.transientFailureRetries. Re-run in place rather than
            // making the user tap "download" again.
            var result: Result<List<File>> = Result.failure(IllegalStateException("未开始"))
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
                val cancelled = _tasks.value.firstOrNull { it.id == task.id }?.state is TaskState.Cancelled
                if (ok || cancelled || attempt == maxAttempts) break
                update(task.id) { it.copy(state = TaskState.Running(0f, "请求被拦截，正在自动重试…")) }
                // Back off further each round: these rejections are rate-based,
                // so retrying at a fixed interval can keep hitting the same wall.
                delay(1_500L * attempt)
            }

            result.fold(
                onSuccess = { files ->
                    if (files.isEmpty()) {
                        update(task.id) { it.copy(state = TaskState.Failed("yt-dlp 没有产出文件")) }
                    } else {
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
                },
                onFailure = { e ->
                    val cancelled = _tasks.value.firstOrNull { it.id == task.id }?.state is TaskState.Cancelled
                    if (!cancelled) {
                        update(task.id) { it.copy(state = TaskState.Failed(friendlyError(e, handler.id))) }
                    }
                },
            )

            workDir.deleteRecursively()
        }
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
