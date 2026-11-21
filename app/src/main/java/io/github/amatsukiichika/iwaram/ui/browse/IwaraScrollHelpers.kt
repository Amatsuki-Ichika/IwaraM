package io.github.amatsukiichika.iwaram.ui.browse

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import io.github.amatsukiichika.iwaram.i18n.tr
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
 * **焦点是一次性事件，不是展开态的副作用**：只有点了小球才在下一帧 requestFocus + show(IME)；
 * 展开态本身（切 tab 回来、旋转恢复、状态提升后重建）永远不请求焦点，因此不会自动拉起输入法。
 * 失焦与用户收键盘（IME 高度下降沿）都通过 [onLostFocus] / [onImeClosed] 交给调用方收敛到
 * 「展开但无焦点」——框不收、词不清、结果不动。状态机见 ui/components/SearchInteraction.kt。
 *
 * 胶囊框本体已抽成公用组件 `ui/components/AppSearchField.kt` 的 [SearchPill]（筛选面板共用同一份
 * 样式）。本函数只剩"宽度补间 + 整条槽可点 + 收起态盖点击层"这三件事。
 */
// isImeVisible 仍是实验 API（WindowInsets 的高度下降沿）；只在这里登记 OptIn。
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompactSearchSlot(
    open: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    /** 输入框真的拿到焦点（用户在编辑）。 */
    onFocused: (() -> Unit)? = null,
    /** 输入框失焦：调用方据此收键盘、保留输入与结果。 */
    onLostFocus: (() -> Unit)? = null,
    /** 用户主动收起软键盘（IME 高度下降沿）：与失焦同一个归宿。 */
    onImeClosed: (() -> Unit)? = null
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // 焦点只由点击驱动：pendingFocus 是一次点击要请求焦点的意图，展开态本身不触发任何 requestFocus。
    //   （旧实现 LaunchedEffect(open) { requestFocus() } 在每次切 tab 重建顶栏时都会再弹一次输入法。）
    var pendingFocus by remember { mutableStateOf(false) }
    LaunchedEffect(pendingFocus) {
        if (!pendingFocus) return@LaunchedEffect
        // 等展开那一帧落定再请求焦点：补间首帧的布局还没就位，早请求会让 IME 把展开过程抖一下。
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
        pendingFocus = false
    }
    // 用户收键盘（返回键 / 收起手势）没有焦点事件可听，只能看 IME 高度的下降沿。
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(imeVisible) }
    LaunchedEffect(imeVisible) {
        // 只认"本槽展开时的收键盘"：收起态下别处（筛选面板等）开关键盘不能把这里当成编辑结束。
        if (open && imeWasVisible && !imeVisible) onImeClosed?.invoke()
        imeWasVisible = imeVisible
    }

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
                ) {
                    onOpen()
                    pendingFocus = true
                }
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
            // 先收键盘、再收起（收起后失焦回调看到的是已收起，不会把 ✕ 当成一次提交），最后去焦点。
            onClear = {
                keyboard?.hide()
                onClose()
                focusManager.clearFocus()
            },
            onSearch = onSearch,
            focusRequester = focusRequester,
            onFocused = onFocused,
            onLostFocus = onLostFocus,
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
                    ) {
                        onOpen()
                        pendingFocus = true
                    }
            )
        }
    }
}

/** 「搜索结果：xxx ✕」chip：一眼看出当前在看搜索结果，点 ✕ 清词回信息流（框保持展开）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultChip(
    label: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    InputChip(
        selected = true,
        onClick = onClear,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = {
            Icon(
                Icons.Filled.Close,
                contentDescription = tr("清除搜索", "Clear search"),
                modifier = Modifier.size(16.dp)
            )
        },
        colors = accentInputChipColors(),
        modifier = modifier
    )
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
