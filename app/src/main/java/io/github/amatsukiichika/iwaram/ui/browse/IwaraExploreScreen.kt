package io.github.amatsukiichika.iwaram.ui.browse

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.amatsukiichika.iwaram.ui.components.GlassTopBar
import io.github.amatsukiichika.iwaram.ui.components.TOP_BAR_CONTENT_HEIGHT
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.amatsukiichika.iwaram.data.imageKeys
import io.github.amatsukiichika.iwaram.data.postKeys
import io.github.amatsukiichika.iwaram.data.videoKeys
import io.github.amatsukiichika.iwaram.ui.components.PillSegment
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.components.horizontalSlideSpec
import io.github.amatsukiichika.iwaram.ui.components.PillSelector
import io.github.amatsukiichika.iwaram.ui.components.SearchPhase
import io.github.amatsukiichika.iwaram.ui.components.TagSuggestionRow
import io.github.amatsukiichika.iwaram.ui.components.shouldCommitOnDefocus
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
    topPadding: Dp = 0.dp,
    /** 主页面在最上层（没有二级页 / 播放页压着）时，返回键才先收搜索。 */
    backEnabled: Boolean = true
) {
    // 分段提升到 VM：切 tab 不丢、顶栏刷新与播放页作者筛选可感知。
    val segment = cvm.videoSegment
    // 搜索状态由 VM 持有（SearchPhase + 输入文字 + query/tag）—— 页面级 remember 会在切 tab / 重进页面时重置。
    val searchQuery by cvm.searchQuery.collectAsStateWithLifecycle()
    val tagSuggestions by cvm.tagSuggestions.collectAsStateWithLifecycle()
    // 当前 tag 筛选是一组（多标签，全都含）
    val activeTags by cvm.searchTags.collectAsStateWithLifecycle()
    // 回填只在 VM.expandSearch() 里做（从收起态展开时一次）；这里不再监听展开态 ——
    //   监听会在旋转/切 tab 重建时冲掉用户还没提交的输入。
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // 返回键：搜索展开 / 结果在屏时先收搜索，收回小球并清结果，再让下一次返回走主界面层级返回。
    //   键盘在屏时系统先吃掉第一次返回（只收键盘）→ IME 下降沿落到「展开无焦点」，再按一次才走到这里。
    BackHandler(
        enabled = backEnabled &&
            (cvm.searchExpanded || searchQuery.isNotBlank() || activeTags.isNotEmpty())
    ) {
        focusManager.clearFocus()
        keyboard?.hide()
        cvm.clearSearch("返回键")
        cvm.collapseSearch()
    }

    // 顶栏已提升到 MainActivity 的主 Scaffold（固定不随页面滑动），这里只负责内容。
    // 内容区铺满整页，网格顶部 contentPadding 预留顶栏高度，卡片因此从顶栏底下穿过。
    // 见 GlassBackdrop.kt 里 GlassTopBar 的结构约束。
    Box(
        Modifier
            .fillMaxSize()
            // 点内容区空白（卡片之间的空隙）算「点输入框外」：交还焦点、收键盘，落到「展开无焦点」。
            //   卡片自己的点击会先消费按下事件（Compose 主遍历子节点优先），所以点卡片照常打开，
            //   滚动拖动也会因手势被消费而取消本次 tap，不会误触。
            .pointerInput(Unit) {
                detectTapGestures {
                    if (cvm.searchPhase == SearchPhase.EDITING) {
                        focusManager.clearFocus()
                        keyboard?.hide()
                    }
                }
            }
    ) {
        // 页面条的实测高度不再由本页测量：悬浮顶栏已移入 MainActivity 那条静态材质条
        //   （见 [IwaraExploreScreen] 的 topPadding 参数说明），高度由它实测后下发。

        // 背景源由各网格自建（滚动期可冻结的内容级源），回顶按钮消费网格那一份。
        Box(Modifier.fillMaxSize()) {

        // 内容区：按方向左右滑动切换（与本地页 modeContent 同规格：300ms 滑动 + 220ms 淡入淡出）。
        // 搜索/筛选结果视为最左的独立一档（index = -1），方向按内容序判定；query 一并进 key，
        // 让"再搜一次"也能带走方向动画，而不是硬切。
        // 结果态由 query/tag 决定，与"搜索框是否持有焦点"无关：收键盘、切 tab 回来结果都还在。
        val showSearchResults = activeTags.isNotEmpty() || searchQuery.isNotBlank()
        val tab = if (showSearchResults) ExploreTab(-1, searchQuery) else ExploreTab(segment.ordinal, null)
        // 内层分段（关注/视频/图片/帖子/搜索）各自保存状态：切走再切回时恢复滚动位置，
        //   而不是回到顶部重来。key 取分段身份；搜索档额外带上 query（换词转场时新旧两档会
        //   同时组合，同 key 会互相覆盖保存状态）。
        val saveableStateHolder = rememberSaveableStateHolder()
        AnimatedContent(
            targetState = tab,
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            transitionSpec = horizontalSlideSpec { target, initial -> if (target.index >= initial.index) 1 else -1 },
            label = "exploreContent"
        ) { t ->
            // 分段内容各自进 SaveableStateProvider：切走的那个分段保留滚动位置/列表状态，
            //   切回来时恢复（而不是从顶部重来）。搜索档的 key 带上 query —— 两次搜索同时在
            //   转场里组合（新旧各一份）时，key 不能相同，否则旧的那份会在退场时覆盖新档状态。
            saveableStateHolder.SaveableStateProvider(
                key = if (t.index == -1) "search:" + t.query.orEmpty() else "segment:" + t.index
            ) {
                when (t.index) {
                    -1 -> SearchResultsGrid(cvm, t.query.orEmpty(), actions, bottomPadding, topPadding = topPadding)
                    Segment.SUBSCRIBED.ordinal -> VideosGrid(cvm, actions, subscribedOnly = true, bottomPadding, topPadding = topPadding)
                    Segment.VIDEOS.ordinal -> VideosGrid(cvm, actions, subscribedOnly = false, bottomPadding, topPadding = topPadding)
                    Segment.IMAGES.ordinal -> ImagesGrid(cvm, actions, bottomPadding, topPadding = topPadding)
                    else -> PostsList(cvm, actions, bottomPadding, topPadding = topPadding)
                }
            }
        }
        }   // Box(内容区)

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
    val searchPhase = cvm.searchPhase
    val searchQuery by cvm.searchQuery.collectAsStateWithLifecycle()
    val tagSuggestions by cvm.tagSuggestions.collectAsStateWithLifecycle()
    val activeTags by cvm.searchTags.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // 失焦 / 关键盘时把"没提交过的输入"提交掉再收敛：空输入不提交（不闪空态），
    //   已提交过的 query（如键盘搜索键刚提交）也不重复请求。
    //   判断读 VM 的实时值而不是组合期捕获的 searchPhase/searchQuery：去焦点可能发生在
    //   "提交 + 状态写入"之间的同一事件里，捕获值会晚一帧，导致同一 query 被提交两次。
    fun commitIfEditing() {
        if (shouldCommitOnDefocus(cvm.searchPhase, cvm.searchInput, cvm.searchQuery.value)) {
            cvm.search(cvm.searchInput)
        }
    }

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
                        placeholder = tr("搜索视频 / #tag…", "Search videos / #tag…"),
                        onOpen = { cvm.expandSearch() },
                        onClose = {
                            cvm.collapseSearch()
                            cvm.clearSearch("搜索框✕")
                        },
                        onSearch = {
                            // 提交是显式动作：先提交（决定结果），再收键盘、去焦点。
                            //   失焦回调此时看到的 query 已相等，不会重复请求。
                            if (searchText.isNotBlank()) cvm.search(searchText)
                            focusManager.clearFocus()
                            keyboard?.hide()
                            cvm.submitSearch()
                        },
                        onFocused = { cvm.onSearchFocused() },
                        onLostFocus = {
                            commitIfEditing()
                            cvm.defocusSearch()
                        },
                        onImeClosed = {
                            commitIfEditing()
                            focusManager.clearFocus()
                            cvm.defocusSearch()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (!searchMode) {
                        SelectorChip(sort, VIDEO_SORTS, cvm::setVideoSort)
                        if (segment != Segment.POSTS && !defaultR18) {
                            SelectorChip(rating, RATING_OPTIONS, cvm::setRating)
                        }
                    }
                }

                // 结果态自解释：键盘收起后仍能看出「现在看的是搜索结果」，点 ✕ 清词回信息流。
                //   只清搜索、保持展开（ExpandedIdle），不收回小球。
                if (searchQuery.isNotBlank() && activeTags.isEmpty()) {
                    SearchResultChip(
                        label = tr("搜索结果：", "Results for: ") + searchQuery,
                        onClear = {
                            cvm.clearSearch("结果chip")
                            cvm.updateSearchInput("")
                        },
                        modifier = Modifier.padding(horizontal = 12.dp).zIndex(1f)
                    )
                }

                // tag 联想（网页版同款）：展开输入时防抖拉取，点选即按 tag 过滤（`/videos?tags=`，全都含）。
                // 出现/收起带本地同款过渡，不再让下方内容跳动。
                val suggestable = searchPhase == SearchPhase.EDITING && searchText.isNotBlank() &&
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
                        // 结果已按 tag 刷新：收键盘让结果露出来（仍保持展开）。
                        focusManager.clearFocus()
                        keyboard?.hide()
                        cvm.onSearchTagPick()
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
) {
    val state by (if (subscribedOnly) cvm.subscribed else cvm.videos).collectAsStateWithLifecycle()
    val session by cvm.session.collectAsStateWithLifecycle()
    if (subscribedOnly && !session.loggedIn && !session.checking) {
        LoginRequiredPanel(tr("订阅流需要登录后使用", "Sign in to use the subscription feed"))
        return
    }
    // key 里带上 `session.checking` —— 冷启动时"已登录但凭证还没换新"窗口里拉到的订阅数据
    // 不可信（见 IwaraClientViewModel.loadMoreSubscribed 的说明）；会话就绪（checking 翻 false）
    // 时这里会重跑，但列表已有内容就直接跳过（见下）。不可信数据由 VM 的 `subscribedTrusted`
    // 在用户下一次翻页/重试时自愈：那时 load 会强制从第 0 页重拉。
    LaunchedEffect(subscribedOnly, session.loggedIn, session.checking) {
        // 内层分段用 SaveableStateProvider 保留滚动位置：切回来时列表非空，这里不能再补拉一页。
        //   FeedPager.load(force = false) 只挡 loading/endReached，分页器被重置后挡不住，
        //   白拉一页还会把恢复的滚动位置顶掉。只有空列表才首拉。
        if (state.items.isNotEmpty()) return@LaunchedEffect
        // 冷启动"已登录但凭证还没换新"窗口里拉到的订阅数据不可信；此时列表为空，
        //   force = true 让 VM 从第 0 页重拉（见 IwaraClientViewModel.loadMoreSubscribed 的说明）。
        if (subscribedOnly) cvm.loadMoreSubscribed(force = true) else cvm.loadMoreVideos(force = true)
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
        emptyHint = if (subscribedOnly) tr("还没有订阅内容", "No subscriptions yet") else tr("暂无视频", "No videos yet"),
        globalFilter = ::videoKeys,
        entranceKey = if (subscribedOnly) "subscribed" else "videos",
        bottomPadding = bottomPadding,
        topPadding = topPadding,
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
                    tr(
                        "当前分级筛选是「${if (rating == "ecchi") "只看 R18" else "只看一般"}」，可能把它过滤掉了",
                        "The rating filter is “${if (rating == "ecchi") "R18 only" else "general only"}”, which may be hiding it"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                if (defaultR18) {
                    // 这份 ecchi 是「默认 R18」设置项强制的：清除按钮无用（会被再强制回去），
                    // 只能如实提示去哪儿关。
                    Text(
                        tr("设置里开着「默认 R18」，关掉它才能看到全部分级", "“Default R18” is on in settings — turn it off to see every rating"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                } else {
                    TextButton(onClick = { cvm.setRating("all") }) { Text(tr("改看全部内容", "Show all content")) }
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
        emptyHint = tr("没有找到相关视频", "No matching videos"),
        globalFilter = ::videoKeys,
        entranceKey = "search",
        emptyExtra = ratingHint,
        bottomPadding = bottomPadding,
        topPadding = topPadding,
        itemKey = { it.id.orEmpty().ifEmpty { it.hashCode().toString() } },
    ) { video ->
        OnlineVideoCard(video, actions)
    }
}

@Composable
fun ImagesGrid(cvm: IwaraClientViewModel, actions: IwaraNavActions, bottomPadding: Dp = 0.dp, topPadding: Dp = 0.dp) {
    val state by cvm.images.collectAsStateWithLifecycle()
    // 登录感知 key：LaunchedEffect(Unit) 只在首次组合跑一次，登录后进入页面不加载、
    // 首拉失败也不重试。守卫防循环：items 非空不 force（FeedPager 的 force=true 会
    // 从第 0 页重拉），loading 中不重复触发；翻页交给 InfiniteGrid.onLoadMore。
    val session by cvm.session.collectAsStateWithLifecycle()
    // 图片流需要登录：未登录时给登录入口，而不是一句"暂无图片"让人以为站点没内容。
    // （与订阅段同一个 LoginRequiredPanel；顶栏刷新在 VM 的 loadMoreImages 里同样被拦。）
    if (!session.loggedIn && !session.checking) {
        LoginRequiredPanel(tr("图片流需要登录后使用", "Sign in to use the image feed"))
        return
    }
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
        emptyHint = tr("暂无图片", "No images yet"),
        globalFilter = ::imageKeys,
        entranceKey = "images",
        bottomPadding = bottomPadding,
        topPadding = topPadding,
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
fun PostsList(cvm: IwaraClientViewModel, actions: IwaraNavActions, bottomPadding: Dp = 0.dp, topPadding: Dp = 0.dp) {
    val state by cvm.posts.collectAsStateWithLifecycle()
    // 同 ImagesGrid，登录感知 key + 防循环守卫。
    val session by cvm.session.collectAsStateWithLifecycle()
    // 同 ImagesGrid：帖子流同样需要登录，未登录时给出登录入口。
    if (!session.loggedIn && !session.checking) {
        LoginRequiredPanel(tr("帖子流需要登录后使用", "Sign in to use the posts feed"))
        return
    }
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
        emptyHint = tr("暂无帖子", "No posts yet"),
        globalFilter = ::postKeys,
        entranceKey = "posts",
        bottomPadding = bottomPadding,
        topPadding = topPadding,
        itemKey = { it.id.orEmpty().ifEmpty { it.hashCode().toString() } },
    ) { post ->
        PostCard(
            post = post,
            onClick = { actions.openPost(post) },
            onAuthorClick = { post.user?.let { actions.openAccountProfile(it) } }
        )
    }
}
