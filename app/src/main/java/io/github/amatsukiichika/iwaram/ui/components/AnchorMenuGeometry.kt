package io.github.amatsukiichika.iwaram.ui.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** 锚点（chip）与菜单之间的间距。 */
internal val anchorMenuGap = 6.dp

/** 菜单离窗口边缘的最小距离。 */
internal val anchorMenuMargin = 8.dp

/** 菜单高度下限：锚点在首帧布局之前还没量出位置，先给一个能看的兜底。 */
internal val anchorMenuMinHeight = 96.dp

/**
 * [AnchorMenu] 的落位几何（纯函数，回归护栏见 `AnchorMenuGeometryTest`）。
 *
 * 方向不写死：先看锚点**下方**放不放得下整块菜单（常规下拉），放不下再翻到**上方**——
 * 底栏 chip 贴着屏幕底边，所以实际总是向上展开；两侧都放不下时选空间大的一侧，
 * 并把菜单整块压回窗口内（超高由菜单内部滚动消化）。
 *
 * 三条不变量（改 [AnchorMenu] 时不许破）：
 * ① 菜单整块留在窗口内：四条边都与窗口边缘至少隔 [anchorMenuMargin]；
 * ② 菜单不压锚点：向上展开时菜单底边 = 锚点顶边 − [anchorMenuGap]，向下展开时菜单顶边 = 锚点底边 + [anchorMenuGap]；
 * ③ 右缘尽量对齐锚点右缘（chip 靠屏幕右侧，菜单向左生长）；对齐后会顶到窗口右边距时
 *    收进边距——宁可比 chip 靠左几 px，也不许贴到屏幕边上。
 */
internal object AnchorMenuGeometry {

    /** 锚点上方可用高度：锚点顶边到窗口顶边，减掉间距与边距。 */
    fun spaceAbove(anchor: IntRect, gap: Int, margin: Int): Int =
        (anchor.top - gap - margin).coerceAtLeast(0)

    /** 锚点下方可用高度：锚点底边到窗口底边，减掉间距与边距。 */
    fun spaceBelow(anchor: IntRect, window: IntSize, gap: Int, margin: Int): Int =
        (window.height - anchor.bottom - gap - margin).coerceAtLeast(0)

    /**
     * 是否向上展开。[popupHeight] 传 0 表示"内容还没量出尺寸"（Popup 首帧），
     * 此时按两侧空间大小取边：底栏 chip 上方永远比下方宽裕，首帧就不会先闪一下下方。
     */
    fun opensUpward(anchor: IntRect, window: IntSize, popupHeight: Int, gap: Int, margin: Int): Boolean {
        val above = spaceAbove(anchor, gap, margin)
        val below = spaceBelow(anchor, window, gap, margin)
        // 常规下拉：下方放得下就向下；放不下（或尺寸未知）就挑空间大的一侧，平局仍向下。
        if (popupHeight > 0 && popupHeight <= below) return false
        return above > below
    }

    /** 菜单高度上限：两侧里更宽裕的那侧可用高度（菜单最终落哪侧都不会被 heightIn 截短）。 */
    fun maxHeight(anchor: IntRect, window: IntSize, gap: Int, margin: Int): Int =
        max(spaceAbove(anchor, gap, margin), spaceBelow(anchor, window, gap, margin))

    /**
     * 菜单左上角的窗口坐标（px）。[popup] 为 [IntSize.Zero] 时只做方向判断（Popup 首帧），
     * 量出内容尺寸后 Popup 会带着真实尺寸再回调一次，位置随之收敛。
     */
    fun offset(anchor: IntRect, window: IntSize, popup: IntSize, gap: Int, margin: Int): IntOffset {
        val upward = opensUpward(anchor, window, popup.height, gap, margin)
        val y = if (upward) anchor.top - gap - popup.height else anchor.bottom + gap
        // 内容比可用空间还高时贴边距：多出的部分交给菜单内部滚动，宁可压住锚点也不许出屏。
        val x = (anchor.right - popup.width)
            .coerceIn(margin, max(margin, window.width - margin - popup.width))
        return IntOffset(x, y.coerceIn(margin, max(margin, window.height - margin - popup.height)))
    }
}
