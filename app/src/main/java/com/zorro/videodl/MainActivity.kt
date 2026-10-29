package com.zorro.videodl

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zorro.videodl.core.Quality
import com.zorro.videodl.core.SiteHandler
import com.zorro.videodl.core.SiteRegistry
import com.zorro.videodl.cookie.CookieStore
import com.zorro.videodl.cookie.LoginActivity
import com.zorro.videodl.data.Settings
import com.zorro.videodl.data.DownloadHistory
import com.zorro.videodl.download.DownloadRepository
import com.zorro.videodl.download.DownloadService
import com.zorro.videodl.engine.YtDlpEngine
import com.zorro.videodl.ui.AppTheme
import com.zorro.videodl.ui.HistorySheet
import com.zorro.videodl.ui.MainScreen
import com.zorro.videodl.ui.SettingsSheet
import com.zorro.videodl.ui.UpdateDialog
import com.zorro.videodl.ui.UpdateUi
import com.zorro.videodl.update.AppUpdater
import com.zorro.videodl.update.Release
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var settings: Settings

    /** Link handed in by a share intent, consumed once by the composition. */
    private val incomingShare = MutableStateFlow<String?>(null)

    /** Bumped after a login returns so the settings sheet re-reads the cookie files. */
    private val loginEpoch = MutableStateFlow(0)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* progress only */ }

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            loginEpoch.value += 1
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settings = Settings(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Unpacking Python takes a beat; get it out of the way before the first download.
        lifecycleScope.launch { runCatching { YtDlpEngine.ensureInit(this@MainActivity) } }

        handleIntent(intent)

        setContent {
            AppTheme {
                AppContent()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Accepts both "分享 → 本 App" (ACTION_SEND) and a tapped link (ACTION_VIEW). */
    private fun handleIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        SiteRegistry.extractUrl(text)?.let { incomingShare.value = it }
    }

    @androidx.compose.runtime.Composable
    private fun AppContent() {
        val tasks by DownloadRepository.tasks.collectAsState()
        val share by incomingShare.collectAsState()
        val epoch by loginEpoch.collectAsState()

        var urlText by remember { mutableStateOf("") }
        var quality by remember { mutableStateOf(settings.defaultQuality) }
        var showSettings by remember { mutableStateOf(false) }
        var showHistory by remember { mutableStateOf(false) }
        val history by DownloadHistory.entries.collectAsState()
        var pendingClip by remember { mutableStateOf<String?>(null) }
        var ytDlpVersion by remember { mutableStateOf<String?>(null) }
        var updating by remember { mutableStateOf(false) }
        var clipboardWatch by remember { mutableStateOf(settings.clipboardWatch) }
        var clipboardAutoStart by remember { mutableStateOf(settings.clipboardAutoStart) }
        var updateUi by remember { mutableStateOf<UpdateUi?>(null) }
        var updateJob by remember { mutableStateOf<Job?>(null) }
        var updateApk by remember { mutableStateOf<File?>(null) }
        var checkingUpdate by remember { mutableStateOf(false) }
        var appUpdateStatus by remember { mutableStateOf<String?>(null) }
        val loggedInSites = remember(epoch, showSettings) {
            SiteRegistry.loginCapable.filter { CookieStore.hasCookies(this, it.id) }.map { it.id }.toSet()
        }
        val snackbar = remember { SnackbarHostState() }

        fun start(url: String) {
            val task = DownloadRepository.enqueue(this, url, quality)
            if (task == null) {
                lifecycleScope.launch { snackbar.showSnackbar("没找到有效链接") }
            } else {
                DownloadService.ensureRunning(this)
                urlText = ""
            }
        }

        // A shared link skips the confirmation dialog — sharing *is* the confirmation.
        LaunchedEffect(share) {
            share?.let {
                incomingShare.value = null
                settings.lastHandledClip = it
                start(it)
            }
        }

        LaunchedEffect(Unit) {
            // version() returns null on a fresh install: the library only records a
            // version string after an update run, so null means "bundled", not "failed".
            ytDlpVersion = YtDlpEngine.version(this@MainActivity) ?: "已就绪（内置版本，检查更新后显示版本号）"
        }

        fun downloadUpdate(release: Release) {
            updateUi = UpdateUi.Downloading(release, 0f)
            updateJob = lifecycleScope.launch {
                AppUpdater.download(this@MainActivity, release) { p ->
                    if (updateUi is UpdateUi.Downloading) updateUi = UpdateUi.Downloading(release, p)
                }.onSuccess {
                    updateApk = it
                    updateUi = UpdateUi.Ready(release, needsPermission = !AppUpdater.canInstall(this@MainActivity))
                }.onFailure {
                    if (updateUi is UpdateUi.Downloading) {
                        updateUi = UpdateUi.Failed(release, "下载失败：${it.message?.take(120)}")
                    }
                }
            }
        }

        fun installUpdate(release: Release) {
            val apk = updateApk ?: return downloadUpdate(release)
            if (AppUpdater.canInstall(this)) {
                startActivity(AppUpdater.installIntent(this, apk))
            } else {
                updateUi = UpdateUi.Ready(release, needsPermission = true)
                startActivity(AppUpdater.permissionIntent(this))
            }
        }

        // Checked on every launch; a failed check stays silent so being offline
        // never gets in the way of downloading.
        LaunchedEffect(Unit) {
            AppUpdater.check().getOrNull()?.let { updateUi = UpdateUi.Available(it) }
        }

        // Clipboard is only readable while focused, so re-check on every resume.
        LaunchedEffect(clipboardWatch) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (!clipboardWatch) return@repeatOnLifecycle
                val clip = readClipboard() ?: return@repeatOnLifecycle
                val url = SiteRegistry.extractUrl(clip) ?: return@repeatOnLifecycle
                if (url == settings.lastHandledClip) return@repeatOnLifecycle
                if (tasks.any { it.url == url }) return@repeatOnLifecycle
                settings.lastHandledClip = url
                if (clipboardAutoStart) start(url) else pendingClip = url
            }
        }

        MainScreen(
            tasks = tasks,
            quality = quality,
            urlText = urlText,
            snackbarHostState = snackbar,
            pendingClipUrl = pendingClip,
            onUrlChange = { urlText = it },
            onQualityChange = {
                quality = it
                settings.defaultQuality = it
            },
            onPaste = { readClipboard()?.let { c -> urlText = SiteRegistry.extractUrl(c) ?: c } },
            onDownload = { start(urlText) },
            onCancel = DownloadRepository::cancel,
            onRetry = { DownloadRepository.retry(this, it); DownloadService.ensureRunning(this) },
            onRemove = DownloadRepository::remove,
            onClearFinished = DownloadRepository::clearFinished,
            onOpenSettings = { showSettings = true },
            onOpenHistory = { showHistory = true },
            onAcceptClip = {
                pendingClip?.let { start(it) }
                pendingClip = null
            },
            onDismissClip = { pendingClip = null },
        )

        if (showHistory) {
            HistorySheet(
                entries = history,
                onDismiss = { showHistory = false },
                onOpen = { uri ->
                    // The file may have been deleted from the gallery since; that
                    // is not worth a crash, the viewer simply fails to open.
                    runCatching {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, uri.toUri())
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        )
                    }
                },
                onRemove = DownloadHistory::remove,
                onClear = DownloadHistory::clear,
            )
        }

        if (showSettings) {
            SettingsSheet(
                ytDlpVersion = ytDlpVersion,
                updating = updating,
                loggedInSites = loggedInSites,
                clipboardWatch = clipboardWatch,
                clipboardAutoStart = clipboardAutoStart,
                onDismiss = { showSettings = false },
                onUpdateYtDlp = {
                    updating = true
                    lifecycleScope.launch {
                        val result = YtDlpEngine.updateYtDlp(this@MainActivity)
                        updating = false
                        ytDlpVersion = YtDlpEngine.version(this@MainActivity)
                        snackbar.showSnackbar(
                            result.getOrElse { "更新失败：${it.message?.take(120)}" }
                        )
                    }
                },
                appVersion = BuildConfig.VERSION_NAME,
                checkingUpdate = checkingUpdate,
                appUpdateStatus = appUpdateStatus,
                onCheckAppUpdate = {
                    checkingUpdate = true
                    appUpdateStatus = null
                    lifecycleScope.launch {
                        val result = AppUpdater.check()
                        checkingUpdate = false
                        result.onSuccess { release ->
                            // Shown inside the sheet: a snackbar would sit behind it.
                            if (release == null) {
                                appUpdateStatus = "已是最新"
                            } else {
                                showSettings = false
                                updateUi = UpdateUi.Available(release)
                            }
                        }.onFailure { appUpdateStatus = "检查失败：${it.message?.take(80)}" }
                    }
                },
                onLogin = { site: SiteHandler -> loginLauncher.launch(LoginActivity.intent(this, site.id)) },
                onLogout = { site: SiteHandler ->
                    CookieStore.clear(this, site.id)
                    // Without this the WebView stays signed in and "重新登录"
                    // reopens an already-logged-in page.
                    CookieStore.clearWebViewCookies(site)
                    loginEpoch.value += 1
                },
                onClipboardWatchChange = {
                    clipboardWatch = it
                    settings.clipboardWatch = it
                },
                onClipboardAutoStartChange = {
                    clipboardAutoStart = it
                    settings.clipboardAutoStart = it
                },
            )
        }

        updateUi?.let { state ->
            UpdateDialog(
                state = state,
                onUpdate = { downloadUpdate(state.release) },
                onInstall = { installUpdate(state.release) },
                onDismiss = {
                    updateJob?.cancel()
                    updateUi = null
                },
            )
        }
    }

    private fun readClipboard(): String? {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
    }
}
