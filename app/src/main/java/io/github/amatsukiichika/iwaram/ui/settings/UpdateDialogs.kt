package io.github.amatsukiichika.iwaram.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.components.AppDialog
import kotlin.math.roundToInt

/** 发布说明在弹窗里最多展示的字符数（GitHub Release 正文可能很长）。 */
private const val RELEASE_NOTES_LIMIT = 400

/**
 * 应用内更新的全部弹窗（同一时刻至多一个）。
 *
 * 由 [UpdateViewModel.state] 驱动：宿主只需把它挂在界面根部，不需要传 visible。
 * 弹窗的隐藏由 [UpdateViewModel.dismiss] 完成；[onDismiss] 只是额外通知宿主
 * （例如让宿主同步清除自己的遮罩状态），宿主无需再改任何更新状态。
 */
@Composable
fun UpdateDialogs(updateVm: UpdateViewModel, onDismiss: () -> Unit) {
    val state by updateVm.state.collectAsStateWithLifecycle()
    val close: () -> Unit = {
        updateVm.dismiss()
        onDismiss()
    }
    when (val current = state) {
        UpdateUiState.Idle, UpdateUiState.Checking -> Unit
        is UpdateUiState.UpToDate -> AppDialog(
            visible = true,
            onDismiss = close,
            title = tr("已是最新版本", "You are up to date"),
            text = tr("当前版本 " + current.currentVersion, "Current version " + current.currentVersion),
            confirmText = tr("好", "OK"),
            onConfirm = close
        )
        is UpdateUiState.Available -> {
            val release = current.release
            AppDialog(
                visible = true,
                onDismiss = close,
                title = if (release.prerelease) {
                    tr("发现测试版 " + release.versionName, "Beta " + release.versionName + " available")
                } else {
                    tr("发现新版本 " + release.versionName, "Version " + release.versionName + " available")
                },
                confirmText = null,
                content = {
                    val notes = release.body.trim()
                    if (notes.isEmpty()) {
                        Text(
                            tr("本次发布未填写更新说明。", "This release has no notes."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val summary = if (notes.length > RELEASE_NOTES_LIMIT) {
                            notes.take(RELEASE_NOTES_LIMIT) + "…"
                        } else {
                            notes
                        }
                        Column(
                            Modifier
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            updateVm.ignoreCurrentVersion()
                            onDismiss()
                        }) {
                            Text(tr("忽略此版本", "Ignore"))
                        }
                        TextButton(onClick = close) {
                            Text(tr("稍后", "Later"))
                        }
                    }
                    TextButton(
                        onClick = { updateVm.downloadAndInstall() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(tr("下载并安装", "Download and install"))
                    }
                }
            )
        }
        is UpdateUiState.Downloading -> AppDialog(
            visible = true,
            onDismiss = { },
            title = tr("正在下载更新", "Downloading update"),
            confirmText = null,
            content = {
                val progress = current.progress
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("正在下载…", "Downloading…"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val fraction = progress
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        (fraction * 100).roundToInt().toString() + "%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        updateVm.cancelDownload()
                        onDismiss()
                    }) {
                        Text(tr("取消", "Cancel"))
                    }
                }
            }
        )
        is UpdateUiState.ReadyToInstall -> AppDialog(
            visible = true,
            onDismiss = close,
            title = tr("安装包已下载", "Update downloaded"),
            text = tr(
                "系统安装程序已打开；若未出现，可重新打开安装器。",
                "The system installer has been opened. If it did not appear, open it again."
            ),
            confirmText = null,
            content = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = close) {
                        Text(tr("关闭", "Close"))
                    }
                    TextButton(onClick = { updateVm.downloadAndInstall() }) {
                        Text(tr("重新打开安装器", "Open installer again"))
                    }
                }
            }
        )
        is UpdateUiState.Failed -> AppDialog(
            visible = true,
            onDismiss = close,
            title = tr("更新未完成", "Update failed"),
            text = buildString {
                append(updateFailureText(current.kind))
                current.httpCode?.let { code ->
                    append("\nHTTP ")
                    append(code)
                }
            },
            confirmText = null,
            content = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = close) {
                        Text(tr("关闭", "Close"))
                    }
                    TextButton(onClick = { updateVm.retry() }) {
                        Text(tr("重试", "Retry"))
                    }
                }
            }
        )
    }
}
