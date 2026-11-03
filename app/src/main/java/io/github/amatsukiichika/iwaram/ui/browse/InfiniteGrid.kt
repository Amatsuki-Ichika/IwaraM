package io.github.amatsukiichika.iwaram.ui.browse

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.LayerBackdrop
import io.github.amatsukiichika.iwaram.data.GlobalFilterHolder
import io.github.amatsukiichika.iwaram.data.filterBy
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.FastScrollbarBasic
import io.github.amatsukiichika.iwaram.ui.components.debugAwareErrorText
import io.github.amatsukiichika.iwaram.ui.components.glassViewBackdrop
import io.github.amatsukiichika.iwaram.ui.components.rememberGlassViewBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ---------- 信息流通用组件：无限网格 + 入场动画 ---------- */
/**
 * 入场动画"已看过"标记（key = "网格key:条目id"）。进程级普通集合（非 Compose state）：
 * 以【条目】为粒度——没看过的内容（首屏、翻页新加载、刷新后新出现的视频）第一次
 * 出现时播入场动画；看过的（滚动回看/切 tab 划回/同 id 刷新）直接显示、不重播不重建。
 * [resetEntrancePlayed] 在离开应用（onStop）时清空：回到 app 后重新按"没看过"判定。
 */
private val seenItemKeys = mutableSetOf<String>()

/** 离开应用（MainActivity.onStop 调用）时重置入场动画标记。 */
fun resetEntrancePlayed() {
    seenItemKeys.clear()
}

/** 无限滚动网格：滚动接近底部自动 [onLoadMore]，带加载/错误/空态。columns 用 GridCells.Fixed(2) 与管理器一致。
 *  [entranceKey] 非空时，该网格的卡片入场动画只在内容首次出现时播放一次。
 *  [itemKey] **必填**：既是 Lazy 网格的 `key`，也是入场动画的判定键。
 *  必须把它作为 `key` 传给 [itemsIndexed]：否则下拉刷新在头部插入新条目时 Compose 无法识别条目身份，
 *  会把整个可见网格当成"全变了"重建，Coil 图片请求也被重新驱动。
 *  也不给「内容哈希」这类默认值：数据一变（例如点赞数刷新）key 就变，会连带重建入场动画的组合组、
 *  让一批卡片重播淡入，所以强制调用方显式给稳定 id。 */
@Composable
fun <T> InfiniteGrid(
    items: List<T>,
    loading: Boolean,
    endReached: Boolean,
    error: String?,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    itemKey: (T) -> String,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    columns: GridCells = GridCells.Fixed(2),
    emptyHint: String = tr("暂无内容", "Nothing here yet"),
    /**
     * 全局筛选器：网格**渲染前**过最后一道 —— 全仓库所有在线列表共用这一处实现。
     *
     * - 传 null = 这个列表**不参与**全局筛选（"人 / 播放列表"这种不是作品的列表）；
     * - 返回的 tags 为 **null** 表示"本列表没有标签字段"（在线图片 / 帖子）⇒ 标签规则对它豁免；
     * - 过滤只影响**显示**：翻页判定仍按过滤后的可见条数（照旧语义）；被筛空时底部会给
     *   「继续加载」按钮 —— 列表空了就滚不到底，不给入口会让人以为筛选器坏了。
     *
     * 取键统一用 data 里的 `::videoKeys` / `::imageKeys` / `::postKeys`（见 GlobalFilter.kt）。
     */
    globalFilter: ((T) -> Pair<String, List<String>?>)? = null,
    entranceKey: String? = null,
    /**
     * **空态**下追加的自定义内容（跟在空态文案后面、`继续加载` 之前）。
     * 目前只有搜索结果在用 —— 结果为空时要说清"当前有几条筛选在起作用"，
     * 否则只会看到"网页上有、app 里搜不到"这种无从下手的现象。
     */
    emptyExtra: (@Composable () -> Unit)? = null,
    /**
     * 列表底部额外让位（悬浮玻璃底栏的高度）——主 tab 的网格要"穿到栏后"，
     * 尾部空白由 contentPadding(bottom) 承担；其余全屏调用点默认 0 零影响。
     */
    bottomPadding: Dp = 0.dp,
    /**
     * **悬浮顶栏**的高度 → 顶部 contentPadding。
     * 必须走 contentPadding（不能给容器加 padding）—— 否则网格视口被整个推到条下面，
     * 卡片就再也不会"从顶栏底下穿过"了。
     */
    topPadding: Dp = 0.dp,
    /**
     * **外部背景源**（顶栏要用同一份）。
     *
     * 顶栏不能自己在上层再录一遍："**嵌套平台 RenderNode × 外层 RenderEffect 模糊**"这条
     * 合成路径会把该层内容丢成透明。
     * 所以顶栏用**网格自己那一份**源（由调用方从上面传进来，网格负责挂载；块是源的兄弟节点）。
     * null = 网格自建（其它所有调用点行为不变）。
     */
    backdrop: LayerBackdrop? = null,
    itemContent: @Composable (T) -> Unit
) {
    val gridState = state
    val scope = rememberCoroutineScope()
    // 全局筛选：不生效时 filterBy **原样返回同一个实例**（连一次遍历都不做）。
    //   lambda 参与 remember 的 key ⇒ 若每帧都是新实例会多跑一遍过滤；列表很小（一页 20~24 条），
    //   相比之下"取键逻辑变了却不重算"的风险更大，所以这里取保守写法。
    // 列表消费端订阅防抖视图：打字期间不再每键全列表重算。
    val filter by GlobalFilterHolder.listState.collectAsStateWithLifecycle()
    val shown = remember(items, filter, globalFilter) {
        if (globalFilter == null) items else items.filterBy(filter, globalFilter)
    }
    val hiddenByFilter = items.size - shown.size
    // 唯一化后的 key 列表：与 items 结构相等则复用（内容没变不重算）。
    val lazyKeys = remember(shown) { uniqueLazyKeys(shown, itemKey) }
    val shouldLoadMore by remember(shown.size, loading, endReached) {
        derivedStateOf {
            if (shown.isEmpty() || loading || endReached) return@derivedStateOf false
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= shown.size - 6
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }
    // 背景源由 **GlassBackdrop 模块**统一创建，它负责两件容易漏的事（见模块文件头）：
    //   ① **录制时先铺页面底色** —— 否则录出来的层在"卡片之间的缝隙"全是透明像素，
    //      模糊算法无像素可采，玻璃里的卡片外边缘会"糊不动"；
    //   ② 统一判据（液态玻璃档 + API 31+）；非玻璃档返回 null ⇒ 零录制成本。
    //   录制层挂在**网格**上、回顶按钮是它的兄弟节点（录制子树外，防自采样 SIGSEGV）。
    // 外部给了源就用它（顶栏共用同一份），否则自建。
    val viewBackdrop = backdrop ?: rememberGlassViewBackdrop()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = columns,
            state = gridState,
            modifier = modifier
                .fillMaxSize()
                .glassViewBackdrop(viewBackdrop),
            // 卡片间距收一点（10 → [GRID_CARD_GAP]），外侧对称留白不动。
            horizontalArrangement = Arrangement.spacedBy(GRID_CARD_GAP),
            verticalArrangement = Arrangement.spacedBy(GRID_CARD_GAP),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp, end = 12.dp, top = 12.dp + topPadding, bottom = 12.dp + bottomPadding
            )
        ) {
            // 声明 contentType —— 内容条目与通栏状态条（加载/错误/空态）
            //   不再共享复用槽，LazyGrid 的槽位复用更准（重拉时状态条不会顶掉卡片槽）。
            itemsIndexed(shown, key = { index, _ -> lazyKeys[index] }, contentType = { _, _ -> "content" }) { index, item ->
                val key = entranceKey
                if (key == null) {
                    itemContent(item)
                } else {
                    // 按条目定格：没看过的条目播入场动画（渲染后标记），看过的直接显示。
                    val seenKey = "$key:${lazyKeys[index]}"
                    val animateThisSlot = remember(seenKey) { !seenItemKeys.contains(seenKey) }
                    if (animateThisSlot) {
                        Entrance(seenKey, index) { itemContent(item) }
                    } else {
                        itemContent(item)
                    }
                }
            }
            if (loading) {
                item(contentType = "status", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            error?.let { msg ->
                item(contentType = "status", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Filled.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        // 普通模式显示人话（friendlyIwaraError），debug 开关打开时显示原样技术串
                        Text(
                            debugAwareErrorText(msg),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        TextButton(onClick = onRetry) { Text(tr("重试", "Retry")) }
                    }
                }
            }
            if (!loading && shown.isEmpty() && error == null) {
                item(contentType = "status", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            // 被全局筛选器筛空 vs 真的没内容 —— 两句话必须分开，
                            // 否则会把"筛选生效"当成"这一页加载失败/坏了"。
                            if (hiddenByFilter > 0) tr("筛选后没有符合的内容（已隐藏 $hiddenByFilter 项）", "No content matches the filters ($hiddenByFilter hidden)") else emptyHint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        emptyExtra?.invoke()
                        // 筛空时列表是空的 ⇒ 没法再"滚到底"触发翻页，后面几页里可能有命中的内容。
                        // 给一个手动入口，而不是让人以为筛选器不工作。
                        if (hiddenByFilter > 0 && !endReached) {
                            TextButton(onClick = onLoadMore, enabled = !loading) { Text(tr("继续加载", "Load more")) }
                        }
                    }
                }
            }
            // 列表**非空**时的尾部状态行 —— 把两种"看不见的原因"变成看得见的一句话。
            //
            // 没有它时"被筛选器隐藏了一部分"与"已经翻到底了"**在界面上没有任何痕迹**，
            // 看到的就是"我记得有这条视频，现在翻遍也找不到"，容易被当成"内容丢失/被隐藏"。
            // 排序上"隐藏条数"优先于"到底"——前者是**可能让内容消失**的那一个，必须显眼。
            if (shown.isNotEmpty() && error == null && !loading) {
                val tailText = when {
                    hiddenByFilter > 0 -> tr("已按筛选条件隐藏 $hiddenByFilter 项", "$hiddenByFilter items hidden by the active filters")
                    endReached -> tr("已经到底了", "End of list")
                    else -> null
                }
                if (tailText != null) {
                    item(contentType = "tail", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                tailText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 一键清除，不用再去记住"那个漏斗图标在哪、里面留了什么规则"。
                            if (hiddenByFilter > 0) {
                                TextButton(onClick = { GlobalFilterHolder.reset() }) { Text(tr("清除筛选", "Clear filters")) }
                            }
                        }
                    }
                }
            }
        }
        // 信息流无分类：连续比例滚动条 + 回到顶部
        FastScrollbarBasic(
            state = gridState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(horizontal = 2.dp)
        )
        val showTop by remember {
            derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 }
        }
        ScrollToTopButton(
            visible = showTop,
            onClick = { scope.launch { gridState.animateScrollToTop() } },
            // 落在"底栏顶边之上 13dp"的右下角（贴内容区底会压在底栏右侧）。
            // 液态玻璃档下消费本视图背景层（真模糊 + 折射）。
            backdrop = viewBackdrop,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = scrollToTopBottomPadding(bottomPadding))
        )
    }
}

/** 网格卡片入场：渐入 + 上浮，按序错峰（最多 8 档），克制不抢戏。
 *  渲染后（SideEffect）把该条目的 [seenKey] 标记为已看过。 */
@Composable
private fun Entrance(seenKey: String, index: Int, content: @Composable () -> Unit) {
    SideEffect { seenItemKeys.add(seenKey) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index % 8) * 30L)
        progress.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 24f
        }
    ) { content() }
}
