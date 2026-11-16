package io.github.amatsukiichika.iwaram.ui.browse

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import io.github.amatsukiichika.iwaram.ui.components.SearchPill

/* ---------- 搜索槽 / 网格间距 ---------- */
// 回顶按钮模块已独立到 `ui/components/ScrollToTopButton.kt`（唯一入口，跨页面复用）。
/** 收起态搜索槽宽度：与定高 44dp 同宽 → 正圆（"小球"）。 */
private val COLLAPSED_SEARCH_WIDTH = 44.dp

/**
 * 紧凑二级搜索槽（定高 44dp）：收起 = 同高 stadium 圆钮（只显示放大镜），点击后**同一个输入框
 * 就地向左长宽**铺满可用宽度。
 *
 * 只有一个容器 + 一个 `BasicTextField`：收起时容器宽 44dp、输入框宽 0（藏在放大镜后面），
 * 展开时容器宽度补间到可用宽度 —— 由宽度动画驱动全部布局（图标留在左边、输入框逐渐显形、
 * 清除按钮跟着长出来），焦点 / IME / 文本全程不换实例。
 *
 * 宽度用"**可用宽 × 比例**"驱动：外层 `BoxWithConstraints` 吃掉调用方给的 `weight(1f)`（`maxWidth`
 * 就是整条槽的可用宽度），**展开比例恒为 1f** ⇒ 宽度 = 可用宽的 100%，由布局直接保证铺满，
 * 与任何补间终值无关；**收起比例 = 44dp ÷ 可用宽** ⇒ 仍是 44dp 正圆。`Row` 用
 * `fillMaxWidth(比例)`，约束是真实变化的，补间也真的在长宽。另**点击区域固定为整条槽**
 * （不随小球收缩），免得 44dp 那么小、手指没点准就"打不开"。
 *
 * 无搜索按钮——在线页用键盘搜索键触发 [onSearch]（本地页实时过滤，可不传）。
 * 清除按钮用于清空并收起。文字 14sp、图标 18dp；展开态把排序/分级 chip 挤走的布局由调用方配合 `if (!open)` 实现。
 *
 * 胶囊框本体已抽成公用组件 `ui/components/AppSearchField.kt` 的 [SearchPill]（筛选面板共用同一份
 * 样式）。本函数只剩"宽度补间 + 整条槽可点 + 收起态盖点击层"这三件事。
 */
@Composable
fun CompactSearchSlot(
    open: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null
) {
    val focusRequester = remember { FocusRequester() }
    // 展开后再请求焦点；runCatching 兜住"还没 attach"的情况（本项目 Compose 版本没有 awaitFrame）
    LaunchedEffect(open) { if (open) runCatching { focusRequester.requestFocus() } }

    // 收起态：整条槽可点（无涟漪，展开本身就是反馈）；展开态：交给输入框与 ✕。
    val interaction = remember { MutableInteractionSource() }

    // 外层 BoxWithConstraints 吃掉调用方的 weight(1f)：maxWidth 就是"整条槽"的可用宽度。
    BoxWithConstraints(
        modifier = modifier
            .height(44.dp)
            .then(
                if (open) Modifier
                else Modifier.clickable(
                    interactionSource = interaction,
                    indication = null
                ) { onOpen() }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        // 收起宽度与 maxWidth **解耦**：原实现收起宽度 = fillMaxWidth(44dp ÷ maxWidth)，而
        //   maxWidth 在启动后 1~2 帧才落定（右侧 chip 显隐 / 行宽稳定）⇒ 动画目标一变就播 tween
        //   = 冷启动搜索小球"缩放一下"。现在动画只挂在"展开↔收起"一个 0..1 进度上，
        //   宽度 = 44dp→铺满 的插值：收起恒 44dp、展开恒铺满、首帧贴 0 进度零动画。
        val expandProgress by animateFloatAsState(
            targetValue = if (open) 1f else 0f,
            animationSpec = tween(260, easing = FastOutSlowInEasing),
            label = "searchWidth"
        )
        val pillWidth = lerp(COLLAPSED_SEARCH_WIDTH, maxWidth, expandProgress)
        // 胶囊框本体用公用组件 [SearchPill]（筛选面板共用同一份样式与行为）。
        //   这里只负责"宽度补间"（展开铺满 / 收起成 44dp 小球）与"整条槽可点"。
        SearchPill(
            value = query,
            onValueChange = onQueryChange,
            placeholder = placeholder,
            modifier = Modifier.width(pillWidth),
            collapsed = !open,
            onClear = onClose,
            onSearch = onSearch,
            focusRequester = focusRequester,
            containerAlpha = if (open) 0.10f else 0.06f
        )

        // 收起态在**整条槽的最上层**盖一层透明点击层：
        //   任何位置（小球本身、小球右边那片空白）的点击都只会进 onOpen() —— 从结构上杜绝
        //   "点击被行内某个子项抢走 → 它自己聚焦弹键盘、搜索条却不展开"。点哪儿都是
        //   "展开 + 自动聚焦弹键盘"。展开态不加，输入框与清除按钮才接得到手。
        if (!open) {
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = interaction,
                        indication = null
                    ) { onOpen() }
            )
        }
    }
}

/**
 * 卡片网格的**卡片之间**的间距（两轴共用）。
 *
 * - **只动这里**（卡片彼此的间隙）：10dp → 8dp；
 * - **不动**网格外侧的 `contentPadding`（本地库与在线都是左右各 12dp 的**对称**留白）——
 *   滚动条悬浮在右侧那条 12dp 里，收掉它就会"压到卡片"或者把留白变得不对称。
 * 本地库（LibraryScreen.VideoGrid）与在线信息流（[InfiniteGrid]）共用这一个常量。
 */
internal val GRID_CARD_GAP = 8.dp
