package io.github.amatsukiichika.iwaram.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** 进入：淡入 120ms、从锚点那一角放大 160ms（错开一点，观感是"先亮起来再长出来"）。 */
private const val MENU_ENTER_FADE_MS = 120
private const val MENU_ENTER_SCALE_MS = 160

/** 退出必须更快：点外部要立刻让位，淡出 90ms、缩回 120ms。 */
private const val MENU_EXIT_FADE_MS = 90
private const val MENU_EXIT_SCALE_MS = 120

/** 落位完全交给 [AnchorMenuGeometry]：间距/边距按当前 density 换成 px 后原样传进去。 */
private class AnchorMenuPositionProvider(
    private val gap: Int,
    private val margin: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset = AnchorMenuGeometry.offset(anchorBounds, windowSize, popupContentSize, gap, margin)
}

/**
 * SR / 清晰度菜单的唯一弹出实现（替代 M3 DropdownMenu）。
 *
 * - 落位交给 [AnchorMenuGeometry]：方向取放得下的一侧，贴边距、内部滚动，菜单整块永不越出窗口
 *   （不变量只写在该文件 KDoc 里）。底栏 chip 贴着屏底，所以实际总是向上展开，展开期间会盖住视频下缘
 *   —— 这是 `docs/decisions/0007-player-menu-placement.md` 记录并接受的代价。
 * - 动画：淡入 + 从锚点那一角轻微放大（与 M3 DropdownMenu 两段式同构，退出更快）；
 *   退出动画跑完前 Popup 必须还在场，所以用 [MutableTransitionState] 记住"上一帧展开过"。
 * - 紧凑规格：行高 32dp（原 DropdownMenuItem 48dp），字号不动，只压空白。
 * - 点外部/选中一项关闭（与 DropdownMenu 语义一致，含返回键收起）。
 *
 * [anchor] 是锚点（chip）在窗口内的像素范围，由调用方用 onGloballyPositioned 量出；
 * 还没量出（[IntRect.Zero]）时按"尺寸未知"取空间大的一侧，首帧不会闪错方向。
 */
@Composable
fun AnchorMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchor: IntRect,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val gapPx = with(density) { anchorMenuGap.roundToPx() }
    val marginPx = with(density) { anchorMenuMargin.roundToPx() }
    // 窗口尺寸取 Configuration（活动窗口的可用区域）；Popup 布局阶段还会拿到真实窗口尺寸再夹一次。
    val windowSize = with(density) {
        IntSize(config.screenWidthDp.dp.roundToPx(), config.screenHeightDp.dp.roundToPx())
    }
    val maxHeight = with(density) {
        AnchorMenuGeometry.maxHeight(anchor, windowSize, gapPx, marginPx).toDp()
    }.coerceAtLeast(anchorMenuMinHeight)
    // 缩放原点贴锚点那一角：向上展开取右下、向下展开取右上（右缘对齐，所以 x 恒为 1f）。
    val origin = if (AnchorMenuGeometry.opensUpward(anchor, windowSize, 0, gapPx, marginPx)) {
        TransformOrigin(1f, 1f)
    } else {
        TransformOrigin(1f, 0f)
    }

    val state = remember { MutableTransitionState(false) }
    state.targetState = expanded
    // 退出动画期间 Popup 必须留在组合里，否则动画会被整块摘掉。
    if (state.currentState || state.targetState || expanded) {
        Popup(
            popupPositionProvider = remember(gapPx, marginPx) {
                AnchorMenuPositionProvider(gapPx, marginPx)
            },
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true)
        ) {
            AnimatedVisibility(
                visibleState = state,
                enter = fadeIn(animationSpec = tween(MENU_ENTER_FADE_MS)) +
                    scaleIn(
                        animationSpec = tween(MENU_ENTER_SCALE_MS),
                        initialScale = 0.92f,
                        transformOrigin = origin
                    ),
                exit = fadeOut(animationSpec = tween(MENU_EXIT_FADE_MS)) +
                    scaleOut(
                        animationSpec = tween(MENU_EXIT_SCALE_MS),
                        targetScale = 0.96f,
                        transformOrigin = origin
                    )
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
