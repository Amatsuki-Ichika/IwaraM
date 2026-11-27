package io.github.amatsukiichika.iwaram.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.amatsukiichika.iwaram.data.GitHubRelease
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.browse.accentChipColors
import io.github.amatsukiichika.iwaram.ui.components.AppEntryCard
import io.github.amatsukiichika.iwaram.ui.components.PillSegment
import io.github.amatsukiichika.iwaram.ui.components.PillSelector

/**
 * 检查频率选项；除 [OFF] 外 value 与 IwaraRepository 的存储值一致。
 *
 * [OFF] 不是存储值：它表示"自动检查已关闭"（仓库里的 updateAutoCheck = false）。
 * 关掉时保留上一次选的频率，重新选一个频率就会连同开关一起打开。
 */
private enum class IntervalOption(
    private val labelZh: String,
    private val labelEn: String,
    val value: String
) {
    OFF("禁用自动检测", "Never check", "off"),
    EACH_START("每次启动", "Every launch", "each_start"),
    DAILY("每天", "Daily", "daily"),
    WEEKLY("每周", "Weekly", "weekly");

    /** 当前界面语言下的选项名（组合期读取，切换语言会重组）。 */
    val label: String get() = tr(labelZh, labelEn)
}

/** 更新通道二选；值与 IwaraRepository 的存储值一致。 */
private fun channelSegments(): List<PillSegment<String>> = listOf(
    PillSegment("stable", tr("跟随正式版", "Follow stable")),
    PillSegment("beta", tr("跟随测试版", "Follow beta"))
)

/** 失败类型 -> 双语文案（状态摘要与弹窗共用）。 */
internal fun updateFailureText(kind: UpdateFailureKind): String = when (kind) {
    UpdateFailureKind.NETWORK ->
        tr("网络请求失败，请检查网络后重试", "Network request failed. Check your connection and try again.")
    UpdateFailureKind.SHA_MISMATCH ->
        tr("安装包校验失败，已删除，请重新下载", "The download failed its SHA-256 check and was removed. Download it again.")
    UpdateFailureKind.INSTALL ->
        tr("无法打开系统安装程序，请允许安装未知应用后重试", "Could not open the system installer. Allow installing unknown apps and try again.")
    UpdateFailureKind.CANCELLED ->
        tr("已取消", "Cancelled")
    UpdateFailureKind.UNKNOWN ->
        tr("更新失败，请稍后重试", "Update failed. Try again later.")
}

/** 状态摘要（一行，放卡片副标题）。 */
private fun updateStatusSummary(
    state: UpdateUiState,
    ignoredVersion: String,
    latestRelease: GitHubRelease?
): String = when (state) {
    UpdateUiState.Idle -> when {
        ignoredVersion.isNotBlank() ->
            tr("已忽略 " + ignoredVersion, "Ignoring " + ignoredVersion)
        latestRelease != null ->
            tr("上次检查到最新版本 " + latestRelease.versionName, "Last check found " + latestRelease.versionName)
        else -> tr("尚未检查", "Not checked yet")
    }
    UpdateUiState.Checking -> tr("正在检查…", "Checking…")
    is UpdateUiState.UpToDate -> tr("已是最新版本", "You are up to date")
    is UpdateUiState.Available ->
        tr("发现新版本 " + state.release.versionName, "Version " + state.release.versionName + " available")
    is UpdateUiState.Downloading -> {
        val progress = state.progress
        if (progress == null) {
            tr("正在下载…", "Downloading…")
        } else {
            val percent = (progress * 100).toInt().toString() + "%"
            tr("正在下载 " + percent, "Downloading " + percent)
        }
    }
    is UpdateUiState.ReadyToInstall -> tr("安装包已就绪", "Ready to install")
    is UpdateUiState.Failed -> updateFailureText(state.kind)
}

/**
 * 设置页的「应用内更新」分组。
 *
 * 只负责展示与转发操作：当前版本、通道、自动检查、频率、立即检查、取消忽略。
 * 下载/安装的进度与结果由 [UpdateDialogs] 展示，本组合函数不弹窗。
 */
@Composable
fun UpdateContent(updateVm: UpdateViewModel, modifier: Modifier = Modifier) {
    val state by updateVm.state.collectAsStateWithLifecycle()
    val channel by updateVm.channel.collectAsStateWithLifecycle()
    val autoCheck by updateVm.autoCheck.collectAsStateWithLifecycle()
    val interval by updateVm.interval.collectAsStateWithLifecycle()
    val ignoredVersion by updateVm.ignoredVersion.collectAsStateWithLifecycle()
    val latestRelease by updateVm.latestRelease.collectAsStateWithLifecycle()
    val current = state

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 当前版本只读展示：不是入口，所以不传 onClick。
        AppEntryCard(
            title = tr("当前版本", "Installed version"),
            trailing = {
                Text(
                    updateVm.currentVersionName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        )
        AppEntryCard(
            title = tr("更新通道", "Update channel"),
            subtitle = tr(
                "正式版更稳定；测试版会先收到预发布版本",
                "Stable is safer; beta receives pre-release builds first"
            )
        ) {
            PillSelector(
                segments = channelSegments(),
                selected = channel,
                onSelect = updateVm::setChannel,
                modifier = Modifier.fillMaxWidth(),
                evenWeight = true,
                segmentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            )
        }
        AppEntryCard(
            title = tr("自动检查更新", "Check for updates automatically"),
            subtitle = tr(
                "启动时在后台静默检查，不打断使用",
                "Checks silently in the background at launch"
            ),
            trailing = {
                Switch(checked = autoCheck, onCheckedChange = updateVm::setAutoCheck)
            }
        )
        AppEntryCard(
            title = tr("检查频率", "Check frequency"),
            subtitle = if (autoCheck) {
                tr(
                    "距上次成功检查满一个周期后才会再次检查",
                    "Checks again once a full period has passed since the last successful check"
                )
            } else {
                tr(
                    "自动检查已关闭（含禁用项），选一个频率即可重新开启",
                    "Automatic checks are off; pick a frequency to turn them back on"
                )
            }
        ) {
            // 四个选项在窄屏放不下一行，用 FlowRow 允许换行（禁用自动检测是常规诉求）。
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IntervalOption.entries.forEach { option ->
                    FilterChip(
                        selected = if (autoCheck) interval == option.value else option == IntervalOption.OFF,
                        onClick = {
                            if (option == IntervalOption.OFF) {
                                updateVm.setAutoCheck(false)
                            } else {
                                updateVm.setInterval(option.value)
                                if (!autoCheck) updateVm.setAutoCheck(true)
                            }
                        },
                        label = { Text(option.label) },
                        colors = accentChipColors()
                    )
                }
            }
        }
        AppEntryCard(
            title = tr("更新状态", "Update status"),
            subtitle = updateStatusSummary(current, ignoredVersion, latestRelease),
            trailing = {
                val latest = latestRelease
                if (latest != null) {
                    Text(
                        tr("最新 ", "Latest ") + latest.versionName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = { updateVm.checkForUpdates(manual = true) },
                    enabled = current !is UpdateUiState.Checking && current !is UpdateUiState.Downloading
                ) {
                    Text(
                        if (current is UpdateUiState.Checking) {
                            tr("检查中…", "Checking…")
                        } else {
                            tr("立即检查更新", "Check now")
                        }
                    )
                }
                if (current is UpdateUiState.Failed) {
                    TextButton(onClick = updateVm::retry) {
                        Text(tr("重试", "Retry"))
                    }
                }
            }
        }
        // 「忽略此版本」只在 Available 弹窗里提供：该状态同时会弹出全屏模态对话框，
        // 设置页里再放一张同名卡片会被弹窗挡住、永远点不到，是重复入口造成的死代码。
        if (ignoredVersion.isNotBlank()) {
            AppEntryCard(
                title = tr("已忽略的版本", "Ignored version"),
                subtitle = tr(
                    "当前忽略 " + ignoredVersion + "，取消后重新提示",
                    "Ignoring " + ignoredVersion + " until you stop ignoring it"
                ),
                trailing = {
                    TextButton(onClick = updateVm::clearIgnoredVersion) {
                        Text(tr("取消忽略", "Stop ignoring"))
                    }
                }
            )
        }
    }
}
