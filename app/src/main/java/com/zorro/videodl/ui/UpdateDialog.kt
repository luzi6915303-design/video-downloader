package com.zorro.videodl.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import com.zorro.videodl.update.Release

/** Where the self-update flow currently is. */
sealed interface UpdateUi {
    val release: Release

    data class Available(override val release: Release) : UpdateUi
    data class Downloading(override val release: Release, val progress: Float) : UpdateUi
    /** Downloaded and verified; [needsPermission] means the install-apps toggle is still off. */
    data class Ready(override val release: Release, val needsPermission: Boolean) : UpdateUi
    data class Failed(override val release: Release, val message: String) : UpdateUi
}

@Composable
fun UpdateDialog(
    state: UpdateUi,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val release = state.release
    val busy = state is UpdateUi.Downloading
    AlertDialog(
        // A stray tap outside must not abandon a half-finished download.
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = !busy),
        title = { Text("发现新版本 v${release.versionName}") },
        text = {
            Column {
                Text(
                    "安装包 ${"%.1f".format(release.size / 1_048_576f)} MB" +
                        if (release.publishedAt.isNotBlank()) "，${release.publishedAt} 发布" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (release.notes.isNotEmpty()) {
                    Column(
                        Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text("这次更新：", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        release.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                when (state) {
                    is UpdateUi.Available -> Unit
                    is UpdateUi.Downloading -> {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("正在下载 ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                    is UpdateUi.Ready -> Text(
                        if (state.needsPermission) {
                            "已下载。第一次更新需要允许「视频下载」安装应用：在打开的设置页里打开开关，返回后再点「安装」。"
                        } else {
                            "已下载并校验通过。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is UpdateUi.Failed -> Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateUi.Available -> TextButton(onClick = onUpdate) { Text("更新") }
                is UpdateUi.Downloading -> TextButton(onClick = {}, enabled = false) { Text("下载中…") }
                is UpdateUi.Ready -> TextButton(onClick = onInstall) { Text("安装") }
                is UpdateUi.Failed -> TextButton(onClick = onUpdate) { Text("重试") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (busy) "取消" else "以后再说") }
        },
    )
}
