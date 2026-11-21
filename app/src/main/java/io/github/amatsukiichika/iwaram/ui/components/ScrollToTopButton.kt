package io.github.amatsukiichika.iwaram.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import io.github.amatsukiichika.iwaram.i18n.tr

/*
 * 回顶按钮的**唯一模块**：落点口径 + 圆钮样式 + 滚动过渡动画都收在这里。
 *
 * 以前它散在 `ui/browse/IwaraScrollHelpers.kt` 里，主页与各二级页各自算 `align + padding`，
 * 于是出现"作者页按钮比主页低一截""材质一页一个样"这类不一致。现在：
 *   ① 位置只有 [scrollToTopBottomPadding] / [scrollToTopBottomPaddingOverlaid] 两把尺子；
 *   ② 材质只有 [ScrollToTopButton] 一处（PILL 角色 = 与底栏同源底色）；
 *   ③ 动画只有一对 [animateScrollToTop] 重载。
 * 调用方只负责把"要不要显示"和"点了滚哪个 state"给进来。
 */

/**
 * 回顶按钮的**落点偏移**（全仓库唯一口径）。
 *
 * 底栏是**浮**在内容之上的（内容一直铺到屏幕底），按钮落点若写成"贴内容区底"
 * （bottom 18~20dp）就会落到"底栏那一层"的右方。统一为：**底栏顶边之上再留 13dp**
 * = "列表末行与底栏之间"的右下角。
 *
 * 传各页自己的 [bottomPadding]：主 tab 是底栏高度（≥0）；二级页直接用
 * [scrollToTopBottomPaddingOverlaid]。
 */
fun scrollToTopBottomPadding(bottomPadding: Dp): Dp = bottomPadding + 13.dp

/**
 * **盖住底栏的二级页**（作者页 / 收藏 / 播放列表…）用的落点。
 *
 * 二级页把底栏盖住了，不能直接用主页那个"底栏高度 + 13dp"，但可以把这段高度算回来：
 * 玻璃高度（常量）+ 底部悬浮 10dp + 手势条高度 + 13dp ⇒ 与主页**逐像素同高**。
 */
@Composable
fun scrollToTopBottomPaddingOverlaid(): Dp {
    val barBlock = BAR_HEIGHT_BASE * glassScale(GLASS_BAR_SIZE) + BAR_BOTTOM_INSET
    val navPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return barBlock + navPadding + 13.dp
}

/**
 * **全仓库唯一**的回顶按钮入口（在 `Box` 里调用）——
 * 位置（右下角 + [scrollToTopBottomPadding] 的落点口径）与样式（半透明玻璃圆钮）都收在这里，
 * 主页 / 本地库四视图 / 作者页 / 各二级页**同一个模块**，不再各自写 align + padding。
 *
 * @param bottomPadding 落点的"底部让位"：主 tab 传底栏高度；盖住底栏的二级页传
 *   [scrollToTopBottomPaddingOverlaid]（它自己把底栏那段高度算回来）。
 * @param backdrop 视图级液态玻璃背景层（null = 半透明路径；二级页未接 → null）。
 */
@Composable
fun BoxScope.ScrollToTopButton(
    visible: Boolean,
    onClick: () -> Unit,
    bottomPadding: Dp,
    endPadding: Dp = 24.dp,
    backdrop: Backdrop? = null,
    modifier: Modifier = Modifier
) {
    ScrollToTopButton(
        visible = visible,
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier
            .align(Alignment.BottomEnd)
            .padding(end = endPadding, bottom = scrollToTopBottomPadding(bottomPadding))
    )
}

/**
 * 一键回到顶部：内容下滚后于右下角以缩放+上浮弹性动画出现。
 *
 * 圆钮本身（底色 / 磨砂 / 图标色 / 涟漪关闭）全部交给 [GlassRoundButton] ——
 * 与播放页的返回 / 更多按钮**共用同一份材质**，材质一改只有一处。
 * **磨砂档**：[backdrop] 非 null 且方案选了磨砂时走 AndroidLiquidGlass 的 drawBackdrop
 * （真背景模糊 + 边缘折射）。调用方必须保证按钮不在 backdrop 的录制子树内（各页把录制层挂在
 * **滚动内容**上、按钮是它的兄弟节点 —— 防库文档明示的自采样 SIGSEGV）。
 * null（二级页未接背景层 / 半透明 / 关档）→ 半透明路径。
 */
@Composable
fun ScrollToTopButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(160)) + scaleIn(
            initialScale = 0.6f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
        ),
        exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.6f, animationSpec = tween(150))
    ) {
        GlassRoundButton(
            icon = Icons.Filled.KeyboardArrowUp,
            contentDescription = tr("回到顶部", "Back to top"),
            onClick = onClick,
            backdrop = backdrop,
            iconSize = 28.dp
        )
    }
}

/**
 * 回到顶部的**滚动过渡动画**（[ScrollToTopButton] 各调用点共用一对重载）。
 *
 * 不直接 `animateScrollToItem(0)`：千级库从深处回顶要飞越上千个条目（沿途合成/回收一大串，
 * 有掉帧风险）；而直接 `scrollToItem(0)` 会"闪回去"。折中：比 [TO_TOP_ANIMATE_WINDOW] 远时
 * 先瞬移进窗口、再对最后一段播动画 —— 任何深度都有可见的收尾滑动，合成工作量又恒定。
 * 动画中再点/拖滚动条会自然打断（scroll 互斥）。
 */
private const val TO_TOP_ANIMATE_WINDOW = 24

suspend fun LazyGridState.animateScrollToTop() {
    if (firstVisibleItemIndex > TO_TOP_ANIMATE_WINDOW) scrollToItem(TO_TOP_ANIMATE_WINDOW)
    animateScrollToItem(0)
}

/** 列表版：LazyListState 与 LazyGridState 无公共接口，同款各写一份薄实现。 */
suspend fun LazyListState.animateScrollToTop() {
    if (firstVisibleItemIndex > TO_TOP_ANIMATE_WINDOW) scrollToItem(TO_TOP_ANIMATE_WINDOW)
    animateScrollToItem(0)
}
