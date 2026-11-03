package io.github.amatsukiichika.iwaram.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.amatsukiichika.iwaram.i18n.tr

/**
 * 全仓库唯一的自绘弹窗（全屏遮罩 + 居中卡片）。
 *
 * 不使用 M3 AlertDialog：项目把 dialogTheme 重定向成非浮窗全屏主题后（见 themes.xml），
 * M3 AlertDialog 的出现动画会有"上跳"的观感，因此弹窗一律自绘。动画规格：遮罩纯淡入 +
 * 卡片轻微 scale（0.92→1，LinearOutSlowInEasing，无过冲、无位移），点遮罩即取消。
 *
 * 两种用法：
 * - **说明**（设置页说明入口等）：只传 [title] + [text]/[content]，单按钮"知道了"（默认）；
 * - **确认**（清空历史等）：传 [onConfirm] + [dismissText]，[confirmDestructive]=true 时
 *   确认按钮使用 error 色。
 */
@Composable
fun AppDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    text: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    confirmText: String? = tr("知道了", "Got it"),
    onConfirm: (() -> Unit)? = null,
    dismissText: String? = null,
    confirmDestructive: Boolean = false
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(120)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
                .padding(horizontal = 36.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = scaleIn(
                    initialScale = 0.92f,
                    animationSpec = tween(160, easing = LinearOutSlowInEasing)
                ) + fadeIn(tween(140)),
                exit = scaleOut(
                    targetScale = 0.94f,
                    animationSpec = tween(120, easing = LinearOutSlowInEasing)
                ) + fadeOut(tween(120))
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        if (!text.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (content != null) {
                            Spacer(Modifier.height(8.dp))
                            content()
                        }
                        if (confirmText != null) {
                            Spacer(Modifier.height(16.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                if (onConfirm != null && dismissText != null) {
                                    TextButton(onClick = onDismiss) { Text(dismissText) }
                                }
                                TextButton(onClick = { (onConfirm ?: onDismiss).invoke() }) {
                                    Text(
                                        confirmText,
                                        color = if (confirmDestructive) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
