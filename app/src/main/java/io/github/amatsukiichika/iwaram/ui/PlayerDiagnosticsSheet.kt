package io.github.amatsukiichika.iwaram.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.amatsukiichika.iwaram.CrashLogger
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.browse.PillSegmentSelector
import io.github.amatsukiichika.iwaram.ui.components.AppBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放诊断面板：状态 / 日志 双 tab + 日志一键复制，便于在设备上抓不到 logcat 时
 * 通过「⋮ → 截图 / 复制」回传整条诊断链。
 *
 * 同一实现也供设置页使用（[standalone] = true）：该入口没有播放上下文，
 * 因此不显示播放相关项（避免出现上一次播放的残留值），默认落在「日志」tab。
 * 日志区高度为屏高的 62%。
 *
 * 抽屉本体统一走 [AppBottomSheet]（只有开/关两态、至少占屏高 70%），
 * 日志区占屏高 62%，两者相加正好铺满，超出部分整页可上滑。
 */
/** 播放页诊断日志 tag（与 PlayerScreen 同名常量，取值一致）。 */
private const val TAG = "PlayerScreen"

@Composable
internal fun DiagnosticsSheet(
    onDismiss: () -> Unit,
    /** true = 从设置页打开（无播放上下文）。 */
    standalone: Boolean = false,
    isLocal: Boolean = false,
    qualityLabel: String? = null,
    videoWidth: Int? = null,
    videoHeight: Int? = null,
    localWidth: Int? = null,
    localHeight: Int? = null,
    positionMs: () -> Long = { 0L },
    durationMs: Long = 0L,
    playError: String? = null
) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    // 崩溃日志是同步文件读，点击后挪到 IO 线程取，剪贴板在主线程写。
    val copyScope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(if (standalone) 1 else 0) }
    val logs by DiagLog.lines.collectAsState()
    // 抽屉统一走 AppBottomSheet：只有开/关两态（由组件统一保证）、
    // 至少占屏高 70%、内容超高整页可上滑。
    AppBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            PillSegmentSelector(options = listOf(tr("状态", "Status"), tr("日志", "Logs")), current = tab, onSelect = { tab = it })
            Spacer(Modifier.height(14.dp))
            if (tab == 0) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (standalone) {
                        DiagRow(
                            tr("说明", "About"),
                            tr(
                                "设置页入口只显示全局状态；播放相关项请在播放页「⋮」里查看",
                                "The settings entry shows global status only; open ⋮ on the player page for playback details"
                            )
                        )
                    } else {
                        DiagRow(
                            tr("播放源", "Source"),
                            if (isLocal) tr("本地文件", "Local file") else tr("在线", "Online") + (qualityLabel?.let { " · $it" } ?: "")
                        )
                    }
                    // 源分辨率优先取超分实测值（configure() 真正拿到的帧尺寸），
                    // 其次播放器回调，最后本地文件的 MetadataRetriever。
                    // 装了特效管线后播放器回调值可能是特效的输出尺寸，不能直接当"分辨率"显示；
                    // 播放器上报值单列一行，两者对不上时一眼可见。
                    if (!standalone) {
                        val reportedRes =
                            if (videoWidth != null || videoHeight != null) "${videoWidth ?: "?"}x${videoHeight ?: "?"}" else null
                        val localRes =
                            if (localWidth != null && localHeight != null) "${localWidth}x$localHeight" else null
                        DiagRow(
                            tr("源分辨率", "Source resolution"),
                            SuperResStatus.sourceText ?: reportedRes ?: localRes ?: tr("未知", "Unknown")
                        )
                        SuperResStatus.outputText?.let { DiagRow(tr("输出分辨率", "Output resolution"), it) }
                        if (reportedRes != null) DiagRow(tr("播放器上报", "Player-reported"), reportedRes)
                        DiagRow(tr("进度", "Progress"), "${formatTime(positionMs())} / ${formatTime(durationMs)}")
                    }
                    val sr = listOfNotNull(
                        SuperResStatus.engine?.let { tr("引擎 $it", "Engine $it") },
                        SuperResStatus.activeScale?.let {
                            tr("倍率 ${SuperResStatus.formatScale(it)}", "Scale ${SuperResStatus.formatScale(it)}")
                        },
                        if (SuperResStatus.degraded) tr("已降级", "Degraded") else null,
                        if (SuperResStatus.pipelineFailed) tr("管线失败已自动停用", "SR disabled after pipeline failure") else null
                    ).joinToString(" · ").ifBlank { tr("关闭", "Off") }
                    DiagRow(tr("超分", "Super resolution"), sr)
                    SuperResStatus.reason?.let { DiagRow(tr("超分原因", "SR reason"), it) }
                    DiagRow(tr("错误", "Error"), playError ?: tr("无", "None"))
                }
            } else {
                DiagnosticsLogTab(logs = logs, copyScope = copyScope, clipboard = clipboard)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** 诊断面板状态 tab 的键值行。 */
@Composable
private fun DiagRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 诊断面板「日志」tab（自 DiagnosticsSheet 抽出，降低主体的分支嵌套）。
 * 日志区高度 = 屏高 62%（与 AppBottomSheet 相加正好铺满）；新日志到来自动滚到底，
 * 上翻阅读时不打扰。
 */
@Composable
private fun DiagnosticsLogTab(
    logs: List<String>,
    copyScope: kotlinx.coroutines.CoroutineScope,
    clipboard: androidx.compose.ui.platform.ClipboardManager
) {
    val cs = MaterialTheme.colorScheme
    // 日志区高度取屏高的 62%。
    val logBoxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.62f
    Box(Modifier.fillMaxWidth().height(logBoxHeight)) {
        val scroll = rememberScrollState()
        // 新日志到来时自动滚到底部；仅在 scroll 停在底部时跟随，上翻阅读时不打扰。
        LaunchedEffect(logs.size) {
            if (!scroll.isScrollInProgress) scroll.animateScrollTo(scroll.maxValue)
        }
        Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
            if (logs.isEmpty()) {
                Text(tr("暂无日志", "No logs yet"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            logs.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
        // 右下角一键复制按钮（悬浮圆角方块 + 复制图标）。
        // 上方一颗「复制崩溃日志」，对应 CrashLogger 落盘文件。
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = cs.surfaceVariant,
                shadowElevation = 4.dp,
                onClick = {
                    copyScope.launch {
                        val text = withContext(Dispatchers.IO) { CrashLogger.dumpTail() }
                            ?: tr("（暂无崩溃记录）", "(No crash records)")
                        clipboard.setText(AnnotatedString(text))
                        DiagLog.log(TAG, "崩溃日志已复制到剪贴板")
                    }
                }
            ) {
                Icon(
                    Icons.Filled.BugReport,
                    contentDescription = tr("复制崩溃日志", "Copy crash log"),
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = cs.primary,
                shadowElevation = 4.dp,
                onClick = {
                    clipboard.setText(AnnotatedString(DiagLog.dump()))
                    DiagLog.log(TAG, "日志已复制到剪贴板")
                }
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = tr("复制全部日志", "Copy all logs"),
                    tint = cs.onPrimary,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}
