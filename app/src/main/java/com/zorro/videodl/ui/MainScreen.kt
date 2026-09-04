package com.zorro.videodl.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.zorro.videodl.core.DownloadTask
import com.zorro.videodl.core.Quality
import com.zorro.videodl.core.SiteRegistry
import com.zorro.videodl.core.TaskState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    tasks: List<DownloadTask>,
    quality: Quality,
    urlText: String,
    snackbarHostState: SnackbarHostState,
    pendingClipUrl: String?,
    onUrlChange: (String) -> Unit,
    onQualityChange: (Quality) -> Unit,
    onPaste: () -> Unit,
    onDownload: () -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearFinished: () -> Unit,
    onOpenSettings: () -> Unit,
    onAcceptClip: () -> Unit,
    onDismissClip: () -> Unit,
) {
    val context = LocalContext.current

    if (pendingClipUrl != null) {
        val site = SiteRegistry.resolve(pendingClipUrl)
        AlertDialog(
            onDismissRequest = onDismissClip,
            title = { Text("检测到${site.displayName}链接") },
            text = { Text(pendingClipUrl, maxLines = 4, overflow = TextOverflow.Ellipsis) },
            confirmButton = { TextButton(onClick = onAcceptClip) { Text("下载") } },
            dismissButton = { TextButton(onClick = onDismissClip) { Text("忽略") } },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("视频下载") },
                actions = {
                    if (tasks.any { it.state is TaskState.Done || it.state is TaskState.Failed }) {
                        IconButton(onClick = onClearFinished) {
                            Icon(Icons.Default.Delete, contentDescription = "清除已完成")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = urlText,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("粘贴视频链接") },
                placeholder = { Text("YouTube / X / 小红书 …") },
                maxLines = 3,
                trailingIcon = {
                    IconButton(onClick = onPaste) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "从剪贴板粘贴")
                    }
                },
            )

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Quality.entries.forEach { q ->
                    FilterChip(
                        selected = q == quality,
                        onClick = { onQualityChange(q) },
                        label = { Text(q.label) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Solid primary fill rather than a container tint: this is the one
            // action on the screen and it has to read as a button at a glance.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FilledIconButton(
                    onClick = onDownload,
                    enabled = urlText.isNotBlank(),
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "开始下载",
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (tasks.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "支持 " + SiteRegistry.handlers.joinToString("、") { it.displayName },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "详情见「设置 → 支持的网站」",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "复制链接后回到本页会自动识别\n也可以在其他 App 里「分享 → 视频下载」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tasks, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            onCancel = { onCancel(task.id) },
                            onRetry = { onRetry(task.id) },
                            onRemove = { onRemove(task.id) },
                            onOpen = { uri ->
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, uri.toUri())
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: DownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = task.title ?: task.url,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${task.siteName} · ${task.quality.label}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            when (val state = task.state) {
                is TaskState.Queued -> StatusRow("排队中…") {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Cancel, "取消") }
                }

                is TaskState.Resolving -> StatusRow("解析链接中…") {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                }

                is TaskState.Running -> {
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    // The line already carries percent, size, speed and ETA, so the
                    // parsed percentage only drives the bar above.
                    StatusRow(state.line.take(100).ifBlank { "正在下载…" }) {
                        IconButton(onClick = onCancel) { Icon(Icons.Default.Cancel, "取消") }
                    }
                }

                is TaskState.Done -> StatusRow(
                    if (state.fileCount > 1) "已保存 ${state.fileCount} 个文件到 ${state.savedTo} 等"
                    else "已保存到 ${state.savedTo}"
                ) {
                    Row {
                        if (state.uri != null) {
                            IconButton(onClick = { onOpen(state.uri) }) {
                                Icon(Icons.Default.PlayArrow, "播放")
                            }
                        }
                        IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "移除") }
                    }
                }

                is TaskState.Failed -> StatusRow(state.reason, error = true) {
                    Row {
                        IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, "重试") }
                        IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "移除") }
                    }
                }

                is TaskState.Cancelled -> StatusRow("已取消") {
                    Row {
                        IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, "重试") }
                        IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "移除") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(text: String, error: Boolean = false, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}
