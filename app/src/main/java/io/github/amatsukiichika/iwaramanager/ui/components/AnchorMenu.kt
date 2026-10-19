package io.github.amatsukiichika.iwaramanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.max

/**
 * 强制"锚点下方"的弹出定位：右缘对齐锚点右缘（两个 chip 都靠屏幕右侧），不足时收进屏内；
 * y = 锚点底 + 4dp。M3 DropdownMenu 的自动翻面（空间不足时向上）会盖住视频，这里从机制上禁掉向上。
 */
private object BelowAnchorEndPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        // 首帧内容还没量出尺寸：先贴锚点左下，量出后 Popup 会再次回调本方法。
        if (popupContentSize == IntSize.Zero) {
            return IntOffset(anchorBounds.left, anchorBounds.bottom)
        }
        val x = (anchorBounds.right - popupContentSize.width)
            .coerceIn(0, max(0, windowSize.width - popupContentSize.width))
        return IntOffset(x, anchorBounds.bottom + 4)
    }
}

/**
 * SR / 清晰度菜单的唯一弹出实现（替代 M3 DropdownMenu）。
 *
 * - 强制向下展开（[BelowAnchorEndPositionProvider]）：绝不向上翻、绝不盖住视频；
 * - 内部滚动：[maxHeight] = 锚点底到屏底的可用高度（调用方算好传入），菜单超高时内部滚动而不是翻面，
 *   横屏全屏（chip 贴屏底）也成立；
 * - 紧凑规格：行高 32dp（原 DropdownMenuItem 48dp），字号不动，只压空白；
 * - 点外部/选中一项关闭（与 DropdownMenu 语义一致，含返回键收起）。
 */
@Composable
fun AnchorMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    /** 菜单最大高度（锚点底到屏底的距离 − 余量），超出内部滚动。 */
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (!expanded) return
    Popup(
        popupPositionProvider = BelowAnchorEndPositionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = modifier
                .widthIn(min = 150.dp, max = 260.dp)
                .heightIn(max = maxHeight),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 5.dp, vertical = 5.dp)
            ) {
                content()
            }
        }
    }
}

/**
 * 菜单条目（紧凑 32dp 行）：[selected] 显示主色 + ✓；[enabled]=false 时整行置灰不可点；
 * [textColor] 覆盖文字色（信息行灰字用）。字号沿用 M3 bodyLarge，只压空白。
 */
@Composable
fun AnchorMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    textColor: Color = Color.Unspecified,
    maxLines: Int = 1
) {
    val cs = MaterialTheme.colorScheme
    val fg = when {
        textColor != Color.Unspecified -> textColor
        !enabled -> cs.onSurfaceVariant.copy(alpha = 0.6f)
        selected -> cs.primary
        else -> cs.onSurface
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = fg,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (selected) {
                Spacer(Modifier.size(6.dp))
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已选中",
                    tint = cs.primary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/**
 * 菜单里的信息行（源/输出分辨率、SR 失败原因）：不可点、灰字、同 32dp 行高
 * （[maxLines] = 2 给原因长文），只读展示。
 */
@Composable
fun AnchorMenuInfo(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (maxLines > 1) 26.dp else 32.dp)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}
