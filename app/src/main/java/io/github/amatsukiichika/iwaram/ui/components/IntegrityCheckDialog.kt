package io.github.amatsukiichika.iwaram.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.amatsukiichika.iwaram.data.IntegrityVerdict
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.IntegrityCheckPhase
import io.github.amatsukiichika.iwaram.ui.IntegrityCheckState
import io.github.amatsukiichika.iwaram.ui.integrityConfirmMessage
import io.github.amatsukiichika.iwaram.ui.integrityResultMessage
import io.github.amatsukiichika.iwaram.ui.integrityResultTitle
import io.github.amatsukiichika.iwaram.ui.integrityRunningMessage
import io.github.amatsukiichika.iwaram.ui.integrityRunningTitle

/**
 * 本地文件完整性检查弹窗：确认 → 进度 → 结果，三态共用一个自绘 [AppDialog]
 * （视觉规格见 .zcode/plans/ui-mockups.html 图 4）。
 *
 * 三态的图标与配色刻意不同：确认 = primary 盾牌（有风险的动作先解释），结果通过 = primary 对勾、
 * 损坏 = error 警示、无法确认 = 中性问号。标题在损坏态直接用 error 色，和 mockup 的 h4 一致。
 *
 * 进度条**只做不确定进度**：校验是按需读几个 box 头，没有可靠百分比，画假进度比不画更糟；
 * 阶段说明同理，状态机不暴露子进度，所以三行如实画成"待办"，不伪造"当前/已完成"。
 * 检查期间点遮罩、按返回都不会关闭（避免"以为取消了其实文件还在读"）；只有「取消」会真正取消。
 */
@Composable
internal fun IntegrityCheckDialog(
    state: IntegrityCheckState?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismissResult: () -> Unit,
    onOpenDownloads: (() -> Unit)? = null
) {
    val current = state ?: return
    val phase = current.phase
    when (phase) {
        IntegrityCheckPhase.Confirming -> AppDialog(
            visible = true,
            onDismiss = onCancel,
            title = tr("检查文件完整性", "Check file integrity"),
            text = integrityConfirmMessage(current.target),
            icon = Icons.Filled.VerifiedUser,
            confirmText = tr("开始检查", "Start check"),
            onConfirm = onConfirm,
            dismissText = tr("取消", "Cancel")
        )

        IntegrityCheckPhase.Running -> AppDialog(
            visible = true,
            // 检查中点遮罩 / 按返回都不关闭：取消要显式点按钮，避免误触后 IO 还在跑。
            onDismiss = {},
            dismissOnOutside = false,
            title = integrityRunningTitle(),
            text = integrityRunningMessage(current.target),
            content = { RunningContent() },
            confirmText = tr("取消", "Cancel"),
            // 唯一按钮是中性动作：压成灰色文字按钮，和 mockup 图 4-② 的 plain 取消一致。
            confirmTint = MaterialTheme.colorScheme.onSurfaceVariant,
            onConfirm = onCancel
        )

        is IntegrityCheckPhase.Finished -> ResultDialog(
            verdict = phase.verdict,
            text = integrityResultMessage(phase.verdict, current.redownloadRequested),
            onDismissResult = onDismissResult,
            onOpenDownloads = onOpenDownloads
        )
    }
}

/** 检查中的内容区：不确定进度条 + 三步阶段说明（mockup 图 4-② 的 .bar + .stages）。 */
@Composable
private fun RunningContent() {
    Column(Modifier.fillMaxWidth()) {
        // 6dp 高的胶囊进度条；不确定进度由 M3 自己驱动动画，不显示百分比。
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(99.dp))
        )
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            CheckStageRow(tr("核对文件大小", "Check file size"), CheckStage.Pending)
            CheckStageRow(
                tr("检查 MP4 结构（moov / mdat / 字节和）", "Check MP4 structure (moov / mdat / byte sums)"),
                CheckStage.Pending
            )
            CheckStageRow(tr("完成", "Done"), CheckStage.Pending)
        }
    }
}

/** 阶段行的三种视觉状态；当前没有子进度来源，运行时只用 [CheckStage.Pending]。 */
private enum class CheckStage { Pending, Current, Done }

/**
 * 一行阶段说明：14dp 圆点（描边 = 待办 / primary 描边 = 当前 / 对勾 = 已完成）+ 12sp 说明文字。
 *
 * 保留三种状态的绘制而不是直接写死一行文案：将来校验器能上报子进度时，只要把状态传进来即可，
 * 不用再改一遍视觉。
 */
@Composable
private fun CheckStageRow(text: String, stage: CheckStage) {
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val ok = MaterialTheme.colorScheme.primary
    val dotColor = when (stage) {
        CheckStage.Pending -> outline
        CheckStage.Current -> primary
        CheckStage.Done -> ok
    }
    val textColor = when (stage) {
        CheckStage.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
        CheckStage.Current -> MaterialTheme.colorScheme.onSurface
        CheckStage.Done -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .border(1.5.dp, dotColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (stage == CheckStage.Done) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = dotColor,
                    modifier = Modifier.size(9.dp)
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(text = text, fontSize = 12.sp, lineHeight = 16.sp, color = textColor)
    }
}

/** 结果弹窗：通过 / 损坏 / 无法确认三种，图标、配色与按钮数量都不同。 */
@Composable
private fun ResultDialog(
    verdict: IntegrityVerdict,
    text: String,
    onDismissResult: () -> Unit,
    onOpenDownloads: (() -> Unit)?
) {
    val colors = MaterialTheme.colorScheme
    when (verdict) {
        IntegrityVerdict.Ok -> AppDialog(
            visible = true,
            onDismiss = onDismissResult,
            title = integrityResultTitle(verdict),
            text = text,
            // 没有独立的成功色 token：用主题强调色画对勾，与 mockup 的 --ok 角色一致。
            icon = Icons.Filled.CheckCircle,
            iconTint = colors.primary,
            confirmText = tr("好", "OK"),
            onConfirm = onDismissResult
        )

        is IntegrityVerdict.Broken -> {
            // 损坏态额外给一个「查看下载」：自动重下已入队，用户多半想去看进度。
            // 点它先关结果弹窗再走导航，避免弹窗和新页面同帧叠在一起。
            AppDialog(
                visible = true,
                onDismiss = onDismissResult,
                title = integrityResultTitle(verdict),
                text = text,
                icon = Icons.Filled.ErrorOutline,
                iconTint = colors.error,
                titleTint = colors.error,
                confirmText = if (onOpenDownloads != null) {
                    tr("查看下载", "View downloads")
                } else {
                    tr("关闭", "Close")
                },
                confirmTint = if (onOpenDownloads != null) null else colors.onSurfaceVariant,
                onConfirm = {
                    onDismissResult()
                    onOpenDownloads?.invoke()
                },
                dismissText = if (onOpenDownloads != null) tr("关闭", "Close") else null
            )
        }

        IntegrityVerdict.Unknown -> AppDialog(
            visible = true,
            onDismiss = onDismissResult,
            title = integrityResultTitle(verdict),
            text = text,
            icon = Icons.Filled.HelpOutline,
            iconTint = colors.onSurfaceVariant,
            confirmText = tr("好", "OK"),
            onConfirm = onDismissResult
        )
    }
}
