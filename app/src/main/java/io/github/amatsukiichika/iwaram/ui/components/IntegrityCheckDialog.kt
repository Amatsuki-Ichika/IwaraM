package io.github.amatsukiichika.iwaram.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.IntegrityCheckPhase
import io.github.amatsukiichika.iwaram.ui.IntegrityCheckState
import io.github.amatsukiichika.iwaram.ui.integrityConfirmMessage
import io.github.amatsukiichika.iwaram.ui.integrityResultMessage
import io.github.amatsukiichika.iwaram.ui.integrityResultTitle
import io.github.amatsukiichika.iwaram.ui.integrityRunningMessage
import io.github.amatsukiichika.iwaram.ui.integrityRunningTitle

/**
 * 本地文件完整性检查弹窗：确认 → 进度 → 结果，三态共用一个自绘 [AppDialog]。
 *
 * 进度条**只做不确定进度**：校验是按需读几个 box 头，没有可靠百分比，画假进度比不画更糟。
 * 检查期间点遮罩不会关闭（避免"以为取消了其实文件还在读"）；只有「取消」会真正取消。
 *
 * 结果由宿主决定何时关（[onDismissResult]）：Ok 让用户看完"文件完整"再走，
 * Broken 的自动重下已在宿主侧发出，弹窗只负责把原因说清楚。
 */
@Composable
internal fun IntegrityCheckDialog(
    state: IntegrityCheckState?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismissResult: () -> Unit
) {
    val current = state ?: return
    val phase = current.phase
    when (phase) {
        IntegrityCheckPhase.Confirming -> AppDialog(
            visible = true,
            onDismiss = onCancel,
            title = tr("检查文件完整性", "Check file integrity"),
            text = integrityConfirmMessage(current.target),
            confirmText = tr("开始检查", "Start check"),
            onConfirm = onConfirm,
            dismissText = tr("取消", "Cancel")
        )

        IntegrityCheckPhase.Running -> AppDialog(
            visible = true,
            // 检查中点遮罩不关闭：取消要显式点按钮，避免误触后 IO 还在跑。
            onDismiss = {},
            title = integrityRunningTitle(),
            text = integrityRunningMessage(current.target),
            content = {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmText = tr("取消", "Cancel"),
            onConfirm = onCancel
        )

        is IntegrityCheckPhase.Finished -> AppDialog(
            visible = true,
            onDismiss = onDismissResult,
            title = integrityResultTitle(phase.verdict),
            text = integrityResultMessage(phase.verdict, current.redownloadRequested),
            confirmText = tr("好", "OK"),
            onConfirm = onDismissResult
        )
    }
}
