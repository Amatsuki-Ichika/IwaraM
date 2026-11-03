package io.github.amatsukiichika.iwaram.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.amatsukiichika.iwaram.i18n.tr

/**
 * 播放页视频区的中央覆盖层（自 PlayerScreen 抽出）。
 * 两处加载提示与两处错误面板原本逐字重复，这里各收成一份 —— 主体（PlayerScreen）只留
 * 「什么时候显示哪一块」的判断，外观与文案组装都在本文件。
 */

/** 视频区中央的加载提示：地址解析中 / 拖动后缓冲同款外观，仅文案不同。 */
@Composable
internal fun CenterLoadingOverlay(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = Color.White)
        Spacer(Modifier.size(10.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 在线地址**解析失败**面板（断连 / 私密 403 / 外嵌都留在页内，不自动关页）。
 * 私密与外嵌大概率打不开，主行文案直接点明；往下还能看详情页的「打开原视频」。
 * 技术细节副行只在 Debug 开关打开时显示（普通模式只留那句人话）。
 */
@Composable
internal fun ResolveErrorOverlay(
    message: String,
    debugDetails: Boolean,
    onRetryResolve: (() -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.72f), MaterialTheme.shapes.small)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = when {
                message.contains("privateVideo", true) || message.contains("私密") -> tr(
                    "该视频已被作者设为私密，无法在线播放",
                    "This video was made private by its author and cannot be streamed"
                )
                message.contains("embed", true) -> tr(
                    "该视频是外嵌视频，暂不支持在线播放",
                    "This is an embedded video and cannot be streamed here"
                )
                else -> tr("无法获取播放地址", "Could not get the playback URL")
            },
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
        if (debugDetails) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.size(10.dp))
        OverlayRetryExitRow(onRetry = onRetryResolve, onBack = onBack)
    }
}

/**
 * 播放出错面板：带诊断信息与重试/退出。
 * 普通模式显示人话主行（friendly），debug 模式显示完整技术串（detail）。
 */
@Composable
internal fun PlayErrorOverlay(
    error: PlayerErrorUi,
    debugDetails: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.65f), MaterialTheme.shapes.small)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (debugDetails) error.detail else error.friendly,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.size(8.dp))
        OverlayRetryExitRow(onRetry = onRetry, onBack = onBack)
    }
}

/** 错误面板底部的「重试 / 退出」按钮对（解析失败面板与播放错误面板共用）。 */
@Composable
private fun OverlayRetryExitRow(onRetry: (() -> Unit)?, onBack: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        onRetry?.let { retry ->
            TextButton(onClick = retry) { Text(tr("重试", "Retry"), color = Color.White) }
        }
        TextButton(onClick = onBack) { Text(tr("退出", "Exit"), color = Color.White) }
    }
}

/**
 * 播放错误的两轨文案（由 Debug 模式开关选择）。
 * [friendly] = 普通模式的人话短句；[detail] = debug 模式的完整技术串
 * （错误码名 + HTTP 码 + 栈帧 + 原因链），诊断面板「错误」行始终显示它。
 * 定义收在 PlayerOverlays，播放页 UI 的错误表示统一归本文件管。
 */
internal data class PlayerErrorUi(val friendly: String, val detail: String)
