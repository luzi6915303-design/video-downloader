package com.zorro.videodl.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zorro.videodl.core.SiteHandler
import com.zorro.videodl.core.SiteRegistry
import com.zorro.videodl.download.MediaStoreSaver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    ytDlpVersion: String?,
    updating: Boolean,
    loggedInSites: Set<String>,
    clipboardWatch: Boolean,
    clipboardAutoStart: Boolean,
    onDismiss: () -> Unit,
    onUpdateYtDlp: () -> Unit,
    onLogin: (SiteHandler) -> Unit,
    onLogout: (SiteHandler) -> Unit,
    onClipboardWatchChange: (Boolean) -> Unit,
    onClipboardAutoStartChange: (Boolean) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("设置", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            SectionTitle("支持的网站")
            SupportedSitesTable()

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle("下载引擎")
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("yt-dlp", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        ytDlpVersion ?: "读取中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onUpdateYtDlp, enabled = !updating) {
                    Text(if (updating) "更新中…" else "检查更新")
                }
            }
            Text(
                "网站改版导致下载失败时，先更新 yt-dlp——多数情况下这就能修好，不用重装 App。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle("站点登录")
            Text(
                "只有需要登录才能看的内容才用得上。登录信息只保存在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SiteRegistry.loginCapable.forEach { site ->
                val loggedIn = site.id in loggedInSites
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(site.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (loggedIn) "已保存登录状态" else "未登录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (loggedIn) {
                        TextButton(onClick = { onLogout(site) }) { Text("退出") }
                    }
                    TextButton(onClick = { onLogin(site) }) { Text(if (loggedIn) "重新登录" else "登录") }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle("剪贴板")
            ToggleRow(
                title = "回到 App 时自动识别链接",
                subtitle = "Android 禁止后台读剪贴板，所以只在切回本 App 的瞬间检查一次",
                checked = clipboardWatch,
                onCheckedChange = onClipboardWatchChange,
            )
            ToggleRow(
                title = "识别后直接开始下载",
                subtitle = "关闭则先弹窗确认",
                checked = clipboardAutoStart,
                enabled = clipboardWatch,
                onCheckedChange = onClipboardAutoStartChange,
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle("保存位置")
            Text(
                "视频：Movies/${MediaStoreSaver.ALBUM}\n音频：Music/${MediaStoreSaver.ALBUM}\n" +
                    "存在系统相册里，卸载 App 也不会删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Reads straight off [SiteRegistry], so adding a handler adds a row here with
 * no extra step — the same property that makes adding a site cheap everywhere
 * else in the app.
 */
@Composable
private fun SupportedSitesTable() {
    Column(
        Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            ),
    ) {
        SiteRegistry.handlers.forEachIndexed { index, site ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "支持",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    site.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    site.supportNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "其他网站也可以试——识别不出来时会交给 yt-dlp 通用解析，成功率看站点。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
