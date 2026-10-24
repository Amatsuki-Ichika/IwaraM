package io.github.amatsukiichika.iwaramanager.ui.browse

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.amatsukiichika.iwaramanager.ui.components.GlassTopBar
import io.github.amatsukiichika.iwaramanager.ui.components.TOP_BAR_CONTENT_HEIGHT
import io.github.amatsukiichika.iwaramanager.ui.components.glassViewBackdrop
import io.github.amatsukiichika.iwaramanager.ui.components.rememberGlassViewBackdrop
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.amatsukiichika.iwaramanager.data.imageKeys
import io.github.amatsukiichika.iwaramanager.data.postKeys
import io.github.amatsukiichika.iwaramanager.data.videoKeys
import io.github.amatsukiichika.iwaramanager.ui.components.PillSegment
import com.kyant.backdrop.backdrops.LayerBackdrop
import io.github.amatsukiichika.iwaramanager.ui.components.horizontalSlideSpec
import io.github.amatsukiichika.iwaramanager.ui.components.PillSelector
import io.github.amatsukiichika.iwaramanager.ui.components.TagSuggestionRow
import kotlinx.coroutines.delay

/** 探索页（底栏「视频」tab）：订阅/视频/图片/帖子 四段内容 + 排序/分级/搜索/tag 检索/作者筛选。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IwaraExploreScreen(
    cvm: IwaraClientViewModel,
    actions: IwaraNavActions,
    /** 悬浮玻璃底栏高度 → 各网格底部 contentPadding（内容穿到栏后）。 */
    bottomPadding: Dp = 0.dp,
    /**
     * 顶栏占位高度：由 MainActivity 的静态材质条实测后下发（内容体见本文件底部的 [IwaraTopBar]）。
     *
     * 悬浮顶栏（分段栏 + 搜索/tag 块）已移到主 tab 的 `AnimatedContent` 之外：材质盒满宽，
     * 若仍随 tab 横滑，切页时它的左/右边界会扫入屏幕内，表现为顶栏边缘一条竖线划入。
     * 本页不再自行测量，只把该高度喂给各网格。
     * 值 = 页面条自身高度 + [TOP_BAR_CONTENT_HEIGHT]（App 顶栏那一格）。
     */
    topPadding: Dp = 0.dp
) {
    // 分段提升到 VM：切 tab 不丢、顶栏刷新与播放页作者筛选可感知。
    val segment = cvm.videoSegment
    // 搜索框的展开态与输入文字由 VM 持有 —— 页面级 remember 会在切 tab / 重进页面时重置。
    val searchMode = cvm.searchExpanded
    val searchText = cvm.searchInput
    val searchQuery by cvm.searchQuery.collectAsStateWithLifecycle()
    val tagSuggestions by cvm.tagSuggestions.collectAsStateWithLifecycle()
    // 当前 tag 筛选是一组（多标签，全都含）
    val activeTags by cvm.searchTags.collectAsStateWithLifecycle()
    // tag 筛选从外部应用时不再自动回填输入框 —— 输入框只承载键盘输入，
    // 筛选状态由下方 TagFilterRow 表达；仅在展开搜索框时回填一次生效中的 `#a #b` 作引导。
    LaunchedEffect(searchMode) { if (searchMode) cvm.syncSearchInput() }

    // 顶栏已提升到 MainActivity 的主 Scaffold（固定不随页面滑动），这里只负责内容。
    // 内容区铺满整页，网格顶部 contentPadding 预留顶栏高度，卡片因此从顶栏底下穿过。
    // 见 GlassBackdrop.kt 里 GlassTopBar 的结构约束。
    Box(Modifier.fillMaxSize()) {
        val topBackdrop = rememberGlassViewBackdrop()
        // 页面条的实测高度不再由本页测量：悬浮顶栏已移入 MainActivity 那条静态材质条
        //   （见 [IwaraExploreScreen] 的 topPadding 参数说明），高度由它实测后下发。

        // 这里不能再包一层 `glassViewBackdrop(topBackdrop)`：嵌套录制 + 模糊会把整层丢成透明。
        // 改为把同一个源下沉给网格去挂载，网格与回顶按钮是它的兄弟节点，全屏只录一次。
        Box(Modifier.fillMaxSize()) {

        // 内容区：按方向左右滑动切换（与本地页 modeContent 同规格：300ms 滑动 + 220ms 淡入淡出）。
        // 搜索/筛选结果视为最左的独立一档（index = -1），方向按内容序判定；query 一并进 key，
        // 让"再搜一次"也能带走方向动画，而不是硬切。
        val showSearchResults = activeTags.isNotEmpty() ||
            (searchMode && searchQuery.isNotBlank())
        val tab = if (showSearchResults) ExploreTab(-1, searchQuery) else ExploreTab(segment.ordinal, null)
        AnimatedContent(
            targetState = tab,
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            transitionSpec = horizontalSlideSpec { target, initial -> if (target.index >= initial.index) 1 else -1 },
            label = "exploreContent"
        ) { t ->
            when (t.index) {
                -1 -> SearchResultsGrid(cvm, t.query.orEmpty(), actions, bottomPadding, topPadding = topPadding, backdrop = topBackdrop)
                Segment.SUBSCRIBED.ordinal -> VideosGrid(cvm, actions, subscribedOnly = true, bottomPadding, topPadding = topPadding, backdrop = topBackdrop)
                Segment.VIDEOS.ordinal -> VideosGrid(cvm, actions, subscribedOnly = false, bottomPadding, topPadding = topPadding, backdrop = topBackdrop)
                Segment.IMAGES.ordinal -> ImagesGrid(cvm, actions, bottomPadding, topPadding = topPadding, backdrop = topBackdrop)
                else -> PostsList(cvm, actions, bottomPadding, topPadding = topPadding, backdrop = topBackdrop)
            }
        }
        }   // Box(内容区 + 背景源)

        // 本页不再自己绘制顶栏：材质与内容都由 MainActivity 那条静态材质条绘制
        //   （内容体见本文件底部的 [IwaraTopBar]），本页只留内容区（网格），
        //   顶部占位高度由 `topPadding` 下发。
    }
}

/**
 * Iwara 页的悬浮顶栏内容：由 MainActivity 的静态材质条在主 tab 的 `AnimatedContent` 之外绘制。
 *
 * 材质盒满宽，若仍留在页面内随 tab 横滑，切页时它的左/右边界会扫入屏幕内（表现为顶栏边缘一条竖线）；
 * 移出后材质不横滑，且仍只绘制一次，页面内其余网格照旧随 tab 横滑。
 *
 * 本函数自带全部状态订阅（与 [IwaraExploreScreen] 各收一份，互不干扰）。
 */
@Composable
fun IwaraTopBar(cvm: IwaraClientViewModel) {
    val segment = cvm.videoSegment
    val searchMode = cvm.searchExpanded
    val searchText = cvm.searchInput
    val tagSuggestions by cvm.tagSuggestions.collectAsStateWithLifecycle()
    val activeTags by cvm.searchTags.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth()) {
        // 内容分段：管理器 ModeSelector 同款滑块，四段（订阅最左）均分撑满宽度
        SegmentSelector(current = segment, onSelect = {
            cvm.selectVideoSegment(it)
            cvm.collapseSearch()
        })

        // 订阅分段保持原订阅页形态：无搜索/排序/分级（订阅流固定最新排序）。
        // 订阅段与其它三段切换时内容高度会跳变，故行显隐加过渡动画
        // （与下方 tag 联想行同款规格：expand/shrink + fade）。
        AnimatedVisibility(
            visible = segment != Segment.SUBSCRIBED,
            enter = fadeIn(tween(220)) + expandVertically(tween(240)),
            exit = fadeOut(tween(180)) + shrinkVertically(tween(200))
        ) {
            Column {
                // 搜索二级展开靠左 / 排序分级靠右（搜索展开时隐藏，输入框独占全宽不挤压文字）
                val sort by cvm.videoSort.collectAsStateWithLifecycle()
                val rating by cvm.rating.collectAsStateWithLifecycle()
                // 设置里开启「默认 R18」时隐藏分级筛选（此时已强制只看 R18）。
                val defaultR18 by cvm.defaultR18.collectAsStateWithLifecycle()
                Row(
                    // 行高钉死：chip 的显隐不改变行高（否则下方内容会上移几像素）。
                    modifier = Modifier
                        .fillMaxWidth()
                        // 搜索行压在 tag 行之上：新增的 tag chip 从上方落下，
                        //   压在它下面才会表现为从搜索栏底下钻出来。
                        .zIndex(1f)
                        .heightIn(min = SEARCH_ROW_HEIGHT)
                        // `vertical` 内边距 2dp（配 SEARCH_ROW_HEIGHT）：搜索槽仍是 48dp，
                        //   只把"搜索槽底边 → 材质底边"的空白收掉一半。
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactSearchSlot(
                        open = searchMode,
                        query = searchText,
                        onQueryChange = { cvm.updateSearchInput(it) },
                        placeholder = "搜索视频 / #tag…",
                        onOpen = { cvm.expandSearch() },
                        onClose = {
                            cvm.collapseSearch()
                            cvm.clearSearch("搜索框✕")
                        },
                        onSearch = { cvm.search(searchText) },
                        modifier = Modifier.weight(1f)
                    )
                    if (!searchMode) {
                        SelectorChip(sort, VIDEO_SORTS, cvm::setVideoSort)
                        if (segment != Segment.POSTS && !defaultR18) {
                            SelectorChip(rating, RATING_OPTIONS, cvm::setRating)
                        }
                    }
                }

                // tag 联想（网页版同款）：展开输入时防抖拉取，点选即按 tag 过滤（`/videos?tags=`，全都含）。
                // 出现/收起带本地同款过渡，不再让下方内容跳动。
                val suggestable = searchMode && searchText.isNotBlank() &&
                    !searchText.startsWith("#") && !searchText.startsWith("@")
                // 防抖 effect 常驻组合位（不放进 if(suggestable) 条件块）：条件块写法会在条件翻转时
                // 销毁重建 effect，350ms 内的联想请求被中断而丢失；
                // 改由 suggestable 在 effect 体内先行短路。
                LaunchedEffect(searchText, suggestable) {
                    if (!suggestable) return@LaunchedEffect
                    delay(350)
                    cvm.searchTagSuggestions(searchText)
                }
                // 联想行用公用组件（筛选面板共用同一份），点选即追加一个 tag（再点同一个取消），
                //   并清空输入框；筛选状态由下方 TagFilterRow 表达。
                val suggestionIds = remember(tagSuggestions) {
                    tagSuggestions.mapNotNull { it.id }.filter { it.isNotBlank() }
                }
                TagSuggestionRow(
                    suggestions = if (suggestable) suggestionIds else emptyList(),
                    onPick = { id ->
                        cvm.searchByTag(id)
                        cvm.updateSearchInput("")
                    },
                    // 联想行同样压在 tag 行之上（同上）。
                    modifier = Modifier.padding(horizontal = 12.dp).zIndex(1f)
                )

                // 过滤生效时显示可点击清除的标签
                // 用公用 TagFilterRow（本地页共用同一份）：每枚 chip 自己管进场，
                //   避免把整个列表当动画 key（否则点第二枚时第一枚会先缩回去）。
                // 移除 tag 后不回填输入框。
                TagFilterRow(
                    tags = activeTags,
                    onRemove = {
                        cvm.removeSearchTag(it)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/** 探索页内容身份：index 决定滑动方向（搜索/筛选结果固定 = -1，其余 = [Segment] 序号），query 仅搜索档有意义。 */
private data class ExploreTab(val index: Int, val query: String?)

/** 视频/图片/帖子 三段选择器：PillSelector 滑块，三格均分撑满宽度。 */
@Composable
private fun SegmentSelector(current: Segment, onSelect: (Segment) -> Unit) {
    PillSelector(
        segments = Segment.entries.map { PillSegment(it, it.label) },
        selected = current,
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        cornerRadius = 16.dp,
        evenWeight = true
    )
}

@Composable
fun VideosGrid(
    cvm: IwaraClientViewModel,
    actions: IwaraNavActions,
    subscribedOnly: Boolean,
    /** 悬浮底栏高度 → 网格底部 contentPadding（内容穿到栏后）。 */
    bottomPadding: Dp = 0.dp,
    /** 悬浮顶栏高度 → 网格顶部 contentPadding（内容从条底下穿过）。 */
    topPadding: Dp = 0.dp,
    /** 悬浮顶栏共用的背景源（网格负责挂载，全屏只录一次）。 */
    backdrop: LayerBackdrop? = null
) {
    val state by (if (subscribedOnly) cvm.subscribed else cvm.videos).collectAsStateWithLifecycle()
    val session by cvm.session.collectAsStateWithLifecycle()
    if (subscribedOnly && !session.loggedIn && !session.checking) {
        LoginRequiredPanel("订阅流需要登录后使用")
        return
    }
    // key 里带上 `session.checking` —— 冷启动时"已登录但凭证还没换新"窗口里拉到的订阅数据
    // 不可信（见 IwaraClientViewModel.loadMoreSubscribed 的说明）；会话就绪（checking 翻 false）
    // 时这里会重跑一次，配合 VM 里的 `subscribedTrusted` 自动从第 0 页重拉，无需手动刷新。
    LaunchedEffect(subscribedOnly, session.loggedIn, session.checking) {
        val empty = state.items.isEmpty()
        if (subscribedOnly) cvm.loadMoreSubscribed(force = empty) else cvm.loadMoreVideos(force = empty)
    }
    // 全局筛选器在渲染前过最后一道 —— 过滤实现收在 InfiniteGrid 内部
    // （所有在线列表共用一处，调用处只声明"怎么取键"）。
    InfiniteGrid(
        items = state.items,
        loading = state.loading,
        endReached = state.endReached,
        error = state.error,
        onLoadMore = { if (subscribedOnly) cvm.loadMoreSubscribed() else cvm.loadMoreVideos() },
        onRetry = {
            if (subscribedOnly) cvm.loadMoreSubscribed(force = true) else cvm.loadMoreVideos(force = true)
        },
        emptyHint = if (subscribedOnly) "还没有订阅内容" else "暂无视频",
        globalFilter = ::videoKeys,
        entranceKey = if (subscribedOnly) "subscribed" else "videos",
        bottomPadding = bottomPadding,
        topPadding = topPadding,
        backdrop = backdrop,
        itemKey = { it.id.orEmpty().ifEmpty { it.hashCode().toString() } },
    ) { video ->
        OnlineVideoCard(video, actions)
    }
}

@Composable
private fun SearchResultsGrid(
    cvm: IwaraClientViewModel,
    query: String,
    actions: IwaraNavActions,
    bottomPadding: Dp = 0.dp,
    /** 悬浮顶栏高度 → 网格顶部 contentPadding。 */
    topPadding: Dp = 0.dp,
    /** 悬浮顶栏共用的背景源（网格负责挂载，全屏只录一次）。 */
    backdrop: LayerBackdrop? = null
) {
    val state by cvm.searchResults.collectAsStateWithLifecycle()
    // 搜索结果为空时要说清"当前有几条看不见的规则在起作用"。
    // 搜索框展开时排序/分级 chip 被隐藏（见上方 `if (!searchMode)`），但 `rating` 依然会带进
    // `/search` 请求 —— 而界面上看不到这条隐藏规则。
    val rating by cvm.rating.collectAsStateWithLifecycle()
    val defaultR18 by cvm.defaultR18.collectAsStateWithLifecycle()
    LaunchedEffect(query) { if (state.items.isEmpty()) cvm.loadMoreSearch() }
    val ratingHint: (@Composable () -> Unit)? = if (rating == "all") null else {
        {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 10.dp)
            ) {
                Text(
                    "当前分级筛选是「${if (rating == "ecchi") "只看 R18" else "只看一般"}」，可能把它过滤掉了",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                if (defaultR18) {
                    // 这份 ecchi 是「默认 R18」设置项强制的：清除按钮无用（会被再强制回去），
                    // 只能如实提示去哪儿关。
                    Text(
                        "设置里开着「默认 R18」，关掉它才能看到全部分级",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                } else {
                    TextButton(onClick = { cvm.setRating("all") }) { Text("改看全部内容") }
                }
            }
        }
    }
    // 全局筛选器：搜索结果同样要过最后一道。
    InfiniteGrid(
        items = state.items,
        loading = state.loading,
        endReached = state.endReached,
        error = state.error,
        onLoadMore = { cvm.loadMoreSearch() },
        onRetry = { cvm.retrySearch() },
        emptyHint = "没有找到相关视频",
        globalFilter = ::videoKeys,
        entranceKey = "search",
        emptyExtra = ratingHint,
        bottomPadding = bottomPadding,
        topPadding = topPadding,
        backdrop = backdrop,
        itemKey = { it.id.orEmpty().ifEmpty { it.hashCode().toString() } },
    ) { video ->
        OnlineVideoCard(video, actions)
    }
}

@Composable
fun ImagesGrid(cvm: IwaraClientViewModel, actions: IwaraNavActions, bottomPadding: Dp = 0.dp, topPadding: Dp = 0.dp, backdrop: LayerBackdrop? = null) {
    val state by cvm.images.collectAsStateWithLifecycle()
    // 登录感知 key：LaunchedEffect(Unit) 只在首次组合跑一次，登录后进入页面不加载、
    // 首拉失败也不重试。守卫防循环：items 非空不 force（FeedPager 的 force=true 会
    // 从第 0 页重拉），loading 中不重复触发；翻页交给 InfiniteGrid.onLoadMore。
    val session by cvm.session.collectAsStateWithLifecycle()
    LaunchedEffect(session.loggedIn) {
        if (session.loggedIn && cvm.images.value.let { it.items.isEmpty() && !it.loading }) {
            cvm.loadMoreImages(force = true)
        }
    }
    // 全局筛选器：图片列表不含标签字段，故只过名字规则（见 imageKeys）。
    InfiniteGrid(
        items = state.items,
        loading = state.loading,
        endReached = state.endReached,
        error = state.error,
        onLoadMore = { cvm.loadMoreImages() },
        onRetry = { cvm.loadMoreImages(force = true) },
        columns = GridCells.Adaptive(120.dp),
        emptyHint = "暂无图片",
        globalFilter = ::imageKeys,
        entranceKey = "images",
        bottomPadding = bottomPadding,
        topPadding = topPadding,
        backdrop = backdrop,
        itemKey = { it.id.orEmpty().ifEmpty { it.hashCode().toString() } },
    ) { image ->
        ImageCardOnline(
            image = image,
            onClick = { actions.openImage(image) },
            onLongClick = { actions.openImageActions(image) },
            onAuthorClick = { image.user?.let { actions.openAccountProfile(it) } }
        )
    }
}

@Composable
fun PostsList(cvm: IwaraClientViewModel, actions: IwaraNavActions, bottomPadding: Dp = 0.dp, topPadding: Dp = 0.dp, backdrop: LayerBackdrop? = null) {
    val state by cvm.posts.collectAsStateWithLifecycle()
    // 同 ImagesGrid，登录感知 key + 防循环守卫。
    val session by cvm.session.collectAsStateWithLifecycle()
    LaunchedEffect(session.loggedIn) {
        if (session.loggedIn && cvm.posts.value.let { it.items.isEmpty() && !it.loading }) {
            cvm.loadMorePosts(force = true)
        }
    }
    // 全局筛选器：帖子列表同样不含标签字段，故只过名字规则。
    InfiniteGrid(
        items = state.items,
        loading = state.loading,
        endReached = state.endReached,
        error = state.error,
        onLoadMore = { cvm.loadMorePosts() },
        onRetry = { cvm.loadMorePosts(force = true) },
        columns = GridCells.Fixed(1),
        emptyHint = "暂无帖子",
        globalFilter = ::postKeys,
        entranceKey = "posts",
        bottomPadding = bottomPadding,
        topPadding = topPadding,
        backdrop = backdrop,
        itemKey = { it.id.orEmpty().ifEmpty { it.hashCode().toString() } },
    ) { post ->
        PostCard(
            post = post,
            onClick = { actions.openPost(post) },
            onAuthorClick = { post.user?.let { actions.openAccountProfile(it) } }
        )
    }
}
