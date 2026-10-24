package io.github.amatsukiichika.iwaram.ui.browse

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import io.github.amatsukiichika.iwaram.ui.components.BAR_BOTTOM_INSET
import io.github.amatsukiichika.iwaram.ui.components.BAR_HEIGHT_BASE
import io.github.amatsukiichika.iwaram.ui.components.GLASS_BAR_SIZE
import io.github.amatsukiichika.iwaram.ui.components.GlassRole
import io.github.amatsukiichika.iwaram.ui.components.LocalGlassSettings
import io.github.amatsukiichika.iwaram.ui.components.SearchPill
import io.github.amatsukiichika.iwaram.ui.components.glassMaterialFor
import io.github.amatsukiichika.iwaram.ui.components.glassPillEffects
import io.github.amatsukiichika.iwaram.ui.components.glassScale

/* ---------- 搜索槽 / 回顶按钮 / 网格间距 ---------- */
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
 * 回顶按钮的**落点偏移**（全仓库唯一口径）。
 *
 * 底栏是**浮**在内容之上的（内容一直铺到屏幕底），按钮落点若写成"贴内容区底"
 * （bottom 18~20dp）就会落到"底栏那一层"的右方。统一为：**底栏顶边之上再留 13dp**
 * = "列表末行与底栏之间"的右下角。
 *
 * 传各页自己的 [bottomPadding]：主 tab 是底栏高度（≥0）；全屏二级页盖住底栏时传 0
 * （那时"贴屏幕底"本来就是对的）。
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
 * 卡片网格的**卡片之间**的间距（两轴共用）。
 *
 * - **只动这里**（卡片彼此的间隙）：10dp → 8dp；
 * - **不动**网格外侧的 `contentPadding`（本地库与在线都是左右各 12dp 的**对称**留白）——
 *   滚动条悬浮在右侧那条 12dp 里，收掉它就会"压到卡片"或者把留白变得不对称。
 * 本地库（LibraryScreen.VideoGrid）与在线信息流（[InfiniteGrid]）共用这一个常量。
 */
internal val GRID_CARD_GAP = 8.dp

/**
 * 一键回到顶部：内容下滚后于右下角以缩放+上浮弹性动画出现。
 *  底色走**半透明材质模块**，与底栏 / 角标胶囊同一份"材质不透明度"，不自己写 surface 底色。
 *  **自绘圆钮**（不用 M3 Card）：M3 Surface 的 shadow / 最小触控机制与"半透明底色"组合时
 *  会出现"八边形边框"。改成与 [GlassPill] 同一套画法（半透明底 + 1dp 内描边、无 M3 机制），
 *  PILL 材质随「材质方案」变化；涟漪关闭（玻璃上就是"扩散噪点"，与底栏同判）。
 *  **液态玻璃档**：[backdrop] 非 null 且方案选了液态玻璃时走 AndroidLiquidGlass 的 drawBackdrop
 *  （真背景模糊 + 边缘折射）。调用方必须保证按钮不在 backdrop 的录制子树内（各页把录制层挂在
 *  **滚动内容**上、按钮是它的兄弟节点 —— 防库文档明示的自采样 SIGSEGV）。
 *  null（二级页未接背景层 / 半透明 / 关档）→ 半透明路径。
 */
@Composable
fun ScrollToTopButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null
) {
    val cs = MaterialTheme.colorScheme
    val settings = LocalGlassSettings.current
    val glassActive = backdrop != null &&
        settings.mode.isLiquid &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val isLight = cs.surface.luminance() > 0.5f
    val material = glassMaterialFor(
        role = GlassRole.PILL,
        settings = settings,
        surface = cs.surface,
        dark = !isLight,
        backdropActive = glassActive
    )
    // 主题自适应配色 —— 浅色主题玻璃=亮雾面 ⇒ 深色图标/浅描边；
    //   深色主题玻璃=黑雾面 ⇒ 白图标。半透明/关档的黑底路径维持白字不变。
    val iconTint = if (glassActive && isLight) cs.onSurface.copy(alpha = 0.9f)
    else Color.White.copy(alpha = 0.92f)
    // 描边只在**非玻璃档**画，与底栏同一判据（玻璃激活时不描边）。
    //   玻璃自带边界感，再叠一圈线就是"外轮廓描边"。半透明/关档保留（浅色下需要一条边界才不糊成一片）。
    val borderColor = Color.White.copy(alpha = material.edgeHighlight)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(160)) + scaleIn(
            initialScale = 0.6f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
        ),
        exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.6f, animationSpec = tween(150))
    ) {
        val shape = CircleShape
        val baseModifier = Modifier
            .size(44.dp)
            .then(
                if (glassActive && backdrop != null) {
                    // highlight=null（库默认边缘光 = 灰线，同底栏）；
                    // onDrawSurface 画材质罩（不画罩时玻璃太透、白图标在浅内容上看不清）；
                    // 效果配方收敛到 `GlassBackdrop` 模块（唯一处）——
                    //   小半径模糊 + 轻折射（折射带 3dp / 位移 8dp，比底栏小一档）。
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        highlight = null,
                        // 库默认注入 `Shadow.Default`（radius 24dp / offset (0, 4dp)，画在形状之外）。
                        //   这个 44dp 的小圆钮底下挂着 24dp 大投影不是设计要的（自绘圆钮本来就去掉了 M3 shadow）。
                        shadow = null,
                        effects = { glassPillEffects() },
                        onDrawSurface = { drawRect(color = material.fill) }
                    )
                } else {
                    Modifier.clip(shape).background(material.fill)
                }
            )
        Box(
            modifier = baseModifier
                // 玻璃档不描边（见上面 borderColor 注释）。
                .then(if (glassActive) Modifier else Modifier.border(1.dp, borderColor, shape))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "回到顶部"
                ) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = "回到顶部",
                tint = iconTint,
                modifier = Modifier.size(28.dp)
            )
        }
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
