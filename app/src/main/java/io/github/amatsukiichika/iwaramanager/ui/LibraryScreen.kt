package io.github.amatsukiichika.iwaramanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.github.amatsukiichika.iwaramanager.ui.browse.CompactSearchSlot
import io.github.amatsukiichika.iwaramanager.ui.browse.RATING_OPTIONS
import io.github.amatsukiichika.iwaramanager.ui.browse.SEARCH_ROW_HEIGHT
import io.github.amatsukiichika.iwaramanager.ui.browse.ScrollToTopButton
import io.github.amatsukiichika.iwaramanager.ui.browse.SelectorChip
import io.github.amatsukiichika.iwaramanager.ui.browse.TagFilterRow
import io.github.amatsukiichika.iwaramanager.ui.components.TagSuggestionRow
import io.github.amatsukiichika.iwaramanager.ui.browse.accentChipColors
import io.github.amatsukiichika.iwaramanager.ui.browse.accentInputChipColors
import io.github.amatsukiichika.iwaramanager.ui.browse.GRID_CARD_GAP
import io.github.amatsukiichika.iwaramanager.ui.browse.animateScrollToTop
import io.github.amatsukiichika.iwaramanager.ui.components.PillSegment
import io.github.amatsukiichika.iwaramanager.ui.components.horizontalSlideSpec
import io.github.amatsukiichika.iwaramanager.ui.components.PillSelector
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.amatsukiichika.iwaramanager.ui.components.TOP_BAR_CONTENT_HEIGHT
import io.github.amatsukiichika.iwaramanager.ui.components.glassViewBackdrop
import io.github.amatsukiichika.iwaramanager.ui.components.rememberGlassViewBackdrop
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.amatsukiichika.iwaramanager.data.VideoEntity
import kotlinx.coroutines.launch

/**
 * 检索/导入进度条。
 *
 * **自己订阅 [VideoViewModel.scanProgress]**，不让 [LibraryScreen] 顶层读进度状态：
 * 进度若是 `UiState` 的字段，每个进度 tick 都会换掉整个 UiState ⇒ `videos: List<>` 不稳定
 * ⇒ 网格无法跳过重组 ⇒ 扫描期间整个网格每 tick 重组。进度独立成流、只被这一个小组件订阅后，
 * 一个 tick 只重组这一小块。
 */
@Composable
private fun ImportProgressBar(vm: VideoViewModel) {
    val progress by vm.scanProgress.collectAsStateWithLifecycle()
    AnimatedVisibility(visible = progress.running) {
        Column {
            LinearProgressIndicator(
                progress = {
                    if (progress.total > 0) progress.done.toFloat() / progress.total else 0f
                },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = if (progress.total > 0) {
                    "${progress.label}  ${progress.done}/${progress.total}"
                } else progress.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    vm: VideoViewModel,
    onOpenVideo: (VideoEntity) -> Unit,
    onImport: () -> Unit,
    /**
     * 进作者二级页。
     * [authorKey] = 本地分组键（`VideoEntity.authorId`，未匹配时为 `UNKNOWN_AUTHOR_KEY`）；
     * [authorName] = 显示名（缺失时作者页回退到分组键）。
     */
    onOpenAuthor: (authorKey: String, authorName: String?) -> Unit = { _, _ -> },
    /**
     * **只有主页面在最上层时**，返回键才去"逐层清除筛选"。
     * 上层压着二级页/播放页/抽屉时，返回属于那一层 —— 否则从作者页/播放页返回时，
     * 这一按会被本页吃掉、顺手把筛选（药丸）清掉。
     */
    backEnabled: Boolean = true,
    /**
     * 悬浮玻璃底栏高度 → 四视图列表底部 contentPadding
     * （底栏后内容要穿过去，尾部空白由 contentPadding 承担，玻璃后面不露白块）。
     */
    bottomPadding: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    /**
     * 顶栏占位高度：由 MainActivity 的静态材质条实测后下发（内容体见本文件底部的 [LibraryTopBar]）。
     *
     * 悬浮顶栏（药丸栏 + 搜索行）已移到主 tab 的 `AnimatedContent` 之外：材质盒满宽，
     * 若仍随 tab 横滑，切页时它的左/右边界会扫入屏幕内，表现为顶栏边缘一条竖线划入。
     * 本页不再自行测量，只把该高度作为各视图的 `contentPadding(top)`。
     * 值 = 页面条自身高度 + [TOP_BAR_CONTENT_HEIGHT]（App 顶栏那一格）。
     */
    topPadding: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f)
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var linkTarget by remember { mutableStateOf<VideoEntity?>(null) }

    // 有搜索词 / 筛选条件 / 分级筛选时，返回键逐层清除（先搜索词、再筛选、再分级），清空后返回才退出应用
    BackHandler(
        enabled = backEnabled &&
            (state.query.isNotBlank() ||
                state.activeFilter != null ||
                (!state.defaultR18 && state.ratingFilter != "all"))
    ) {
        if (state.query.isNotBlank()) {
            vm.setQuery("")
        } else if (state.activeFilter != null) {
            vm.clearFilter()
        } else {
            vm.clearRatingFilter()
        }
    }

    // 顶栏（标题/数量/刷新/设置）已提升到 MainActivity 主 Scaffold，这里只负责内容。
    // 内容区铺满整页，顶部由网格的 contentPadding 预留顶栏高度，滚动时卡片从顶栏底下穿过。
    //
    // 结构约束（见 GlassBackdrop.kt 的注释）：
    // ① 背景源挂在内容那一层上，回顶按钮是它的兄弟节点（消费点不在录制子树内，防自采样 SIGSEGV）；
    // ② 顶栏是覆盖层，其高度必须喂回内容的 contentPadding(top)，否则首行被压在条底下。
    Box(modifier = Modifier.fillMaxSize()) {
        val topBackdrop = rememberGlassViewBackdrop()
        // 页面条的实测高度不再由本页测量：悬浮顶栏已移入 MainActivity 那条静态材质条
        //   （见 [LibraryScreen] 的 topPadding 参数说明），高度由它实测后下发。

        // 四个视图的滚动状态**提升到页面级** —— 因为"回顶按钮"必须待在**录制子树之外**。
        //   录制层包住整个内容区（`glassViewBackdrop` 在下面那层 Box 上），按钮若留在各模式内部
        //   就落在录制子树里了 ⇒ 违反库的"消费点不得位于源的录制子树内"（自采样 SIGSEGV）。
        //   副作用（正向）：切模式时滚动位置不再重建。
        val allState = rememberLazyGridState()
        val folderState = rememberLazyListState()
        val authorState = rememberLazyListState()
        val timeState = rememberLazyListState()
        val pageScope = rememberCoroutineScope()

        Box(Modifier.fillMaxSize().glassViewBackdrop(topBackdrop)) {
            // 内容区：按方向左右滑动切换（右往左进时向左推，左往右进时向右推）
            AnimatedContent(
                targetState = state.mode,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
                transitionSpec = horizontalSlideSpec { target, initial -> if (target.ordinal >= initial.ordinal) 1 else -1 },
                label = "modeContent"
            ) { mode ->
                when (mode) {
                    LibraryMode.ALL -> {
                        // 分段标记由 VideoViewModel 在后台线程随 UiState 算好。
                        val markers = state.videoMarkers
                        Box(Modifier.fillMaxSize()) {
                            // 背景源已提升到页面级（`topBackdrop`，与回顶按钮共用），
                            //   这里不再各自 `rememberGlassViewBackdrop()`，避免两层嵌套录制白付成本。
                            //   网格自己铺满整个区域（顶部 contentPadding = 悬浮条高度）⇒ 卡片会**从顶栏底下穿过**。
                            VideoGrid(
                                videos = state.videos,
                                showTags = state.showTags,
                                showR18Tag = state.showR18Tag,
                                onOpenVideo = onOpenVideo,
                                onTagClick = vm::selectTag,
                                onLongClick = { linkTarget = it },
                                onImport = onImport,
                                onAuthorClick = { v ->
                                    onOpenAuthor(v.authorId ?: VideoViewModel.UNKNOWN_AUTHOR_KEY, v.author)
                                },
                                // 全局筛选器：筛空时换一条空态文案（不然会显示"还没有视频，导入…"）
                                hiddenByFilter = state.hiddenByFilter,
                                state = allState,
                                bottomPadding = bottomPadding,
                                // 顶栏是覆盖层，首行需为其让出高度。
                                topPadding = topPadding
                            )
                            // 换在线同款连续滚动条（拖动实时滚）+ 居中分节气泡；
                            //   左右 12dp 对称后滚动条悬浮右缘，不再占位。
                            FastScrollbarBasicWithBubble(allState, markers, Modifier.fillMaxSize())
                        }
                    }
                    LibraryMode.FOLDER -> {
                        val listState = folderState
                        val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
                        // 分段标记由 VideoViewModel 在后台线程随 UiState 算好。
                        val markers = state.folderMarkers
                        Box(Modifier.fillMaxSize()) {
                            // 背景源已提升到页面级（见「全部」视图的说明）。
                            FolderList(state.folders, vm::selectFolder, listState, bottomPadding, topPadding)
                            FastScrollbar(
                                markers = markers,
                                currentIndex = { firstVisible },
                                onScrollToIndex = { idx -> pageScope.launch { listState.scrollToItem(idx) } },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    LibraryMode.AUTHOR -> {
                        val listState = authorState
                        val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
                        // 分段标记由 VideoViewModel 在后台线程随 UiState 算好。
                        val markers = state.authorMarkers
                        Box(Modifier.fillMaxSize()) {
                            // 背景源已提升到页面级（见「全部」视图的说明）。
                            AuthorList(state.authors, { bucket -> onOpenAuthor(bucket.id, bucket.name) }, listState, bottomPadding, topPadding)
                            FastScrollbar(
                                markers = markers,
                                currentIndex = { firstVisible },
                                onScrollToIndex = { idx -> pageScope.launch { listState.scrollToItem(idx) } },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    LibraryMode.TIME -> {
                        val listState = timeState
                        // 分段标记由 VideoViewModel 在后台线程随 UiState 算好。
                        val markers = state.timeMarkers
                        Box(Modifier.fillMaxSize()) {
                            // 背景源已提升到页面级（见「全部」视图的说明）。
                            TimeList(state.timeBuckets, vm::selectTime, listState, bottomPadding, topPadding)
                            // 连续滚动条 + 居中分节气泡（月份），同「全部」视图。
                            FastScrollbarBasicWithBubble(listState, markers, Modifier.fillMaxSize())
                        }
                    }
                    LibraryMode.TAG -> TagCloud(state.tags, state.selectedTags, vm::selectTag, bottomPadding, topPadding)
                }
            }
        }

        // 回顶按钮统一提到这里 —— **录制子树之外**（见上面 allState 的注释）。
        //   位置/样式仍走唯一的 BoxScope.ScrollToTopButton（落点口径不变）。
        val showTop by remember {
            derivedStateOf {
                when (state.mode) {
                    LibraryMode.ALL -> allState.firstVisibleItemIndex > 0 || allState.firstVisibleItemScrollOffset > 0
                    LibraryMode.FOLDER -> folderState.firstVisibleItemIndex > 0 || folderState.firstVisibleItemScrollOffset > 0
                    LibraryMode.AUTHOR -> authorState.firstVisibleItemIndex > 0 || authorState.firstVisibleItemScrollOffset > 0
                    LibraryMode.TIME -> timeState.firstVisibleItemIndex > 0 || timeState.firstVisibleItemScrollOffset > 0
                    LibraryMode.TAG -> false
                }
            }
        }
        ScrollToTopButton(
            visible = showTop,
            onClick = {
                pageScope.launch {
                    when (state.mode) {
                        LibraryMode.ALL -> allState.animateScrollToTop()
                        LibraryMode.FOLDER -> folderState.animateScrollToTop()
                        LibraryMode.AUTHOR -> authorState.animateScrollToTop()
                        LibraryMode.TIME -> timeState.animateScrollToTop()
                        LibraryMode.TAG -> Unit
                    }
                }
            },
            endPadding = 26.dp,
            bottomPadding = bottomPadding,
            backdrop = topBackdrop
        )

        // 本页不再自己绘制顶栏：材质与内容都由 MainActivity 那条静态材质条绘制
        //   （内容体见本文件底部的 [LibraryTopBar]），本页只留内容区 + 回顶按钮，
        //   顶部占位高度由 `topPadding` 参数下发（见 [LibraryScreen] 的说明）。
    }

    linkTarget?.let { target ->
        ManualLinkDialog(
            video = target,
            onDismiss = { linkTarget = null },
            onConfirm = { id ->
                vm.linkIwaraId(target, id)
                linkTarget = null
            },
            onRemove = { vm.removeFromLibrary(target) }
        )
    }
}

/**
 * 本地页的悬浮顶栏内容：由 MainActivity 的静态材质条在主 tab 的 `AnimatedContent` 之外绘制。
 *
 * 材质盒满宽，若仍留在页面内随 tab 横滑，切页时它的左/右边界会扫入屏幕内（表现为顶栏边缘一条竖线）；
 * 移出后材质不横滑，且仍只绘制一次，页面内其余内容照旧随 tab 横滑。
 *
 * 本函数自带 `vm.uiState` 订阅（与 [LibraryScreen] 各收一份，互不干扰）。
 * 高度由调用方实测（`onSizeChanged`）并回灌给各视图的 `contentPadding(top)`。
 */
@Composable
fun LibraryTopBar(vm: VideoViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        // 进度条自己订阅 scanProgress（进度不进 UiState），进度 tick 只重组这一小块，
        //   不会带上整个本地库页面。
        ImportProgressBar(vm)

        // 分类标签（滑块随选中项左右滑动）
        ModeSelector(
            current = state.mode,
            onSelect = vm::setMode
        )

        // 搜索 + 排序 + 分级（分级与在线页同款：全部 / 一般 / R18）
        // 搜索输入逻辑：输入时下方自动匹配本地 tag 候选，点候选即应用为筛选 tag 并清空输入框
        //   （与全局筛选器的标签输入同语义）。
        // zIndex(1f)：新增的 tag chip 从上方落下，需压在搜索行下面，才表现为从搜索栏底下钻出来；
        //   zIndex 只在同一父节点内的兄弟之间生效，这两行都在下面这个 Column 里。
        SearchSortRow(
            query = state.query,
            sortMode = state.sortMode,
            onQueryChange = vm::setQuery,
            onSortChange = vm::setSortMode,
            ratingFilter = state.ratingFilter,
            onRatingChange = vm::setRatingFilter,
            // 「默认 R18」开启时隐藏分级筛选项（强制只看 R18）。
            showRatingChip = !state.defaultR18,
            allTags = state.tags.map { it.id },
            onPickSuggestedTag = { tag ->
                vm.selectTag(tag)
                vm.setQuery("")
            },
            modifier = Modifier.zIndex(1f)
        )

        // 当前过滤条件。
        // TagFilterRow 本身即"空则高度 0"的容器，常驻组合才能在删掉最后一枚时把收起动画播完；
        //   它内部的展开/收起（220/240ms，与全仓其它行显隐同一组参数）形成
        //   "tag 出现 → 下面的视频向下移动一小段"的效果。
        TagFilterRow(
            tags = state.selectedTags,
            onRemove = vm::selectTag,
            // 本地库的 tag 是标签名字（不是在线那种 id），原样显示
            labelOf = { it },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        )
        if (state.selectedTags.isEmpty()) {
            AnimatedContent(
                targetState = state.activeFilter,
                transitionSpec = {
                    (fadeIn(tween(220)) + expandVertically(tween(240))) togetherWith
                        (fadeOut(tween(180)) + shrinkVertically(tween(200)))
                },
                label = "activeFilter"
            ) { filter ->
                if (filter != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        InputChip(
                            selected = true,
                            onClick = vm::clearFilter,
                            label = { Text(filter.label) },
                            trailingIcon = {
                                Icon(Icons.Filled.Close, contentDescription = "清除", Modifier.heightIn(max = 18.dp))
                            },
                            shape = RoundedCornerShape(50),
                            colors = accentInputChipColors()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeSelector(
    current: LibraryMode,
    onSelect: (LibraryMode) -> Unit
) {
    val labels = remember {
        mapOf(
            LibraryMode.ALL to "全部",
            LibraryMode.FOLDER to "文件夹",
            LibraryMode.AUTHOR to "作者",
            LibraryMode.TIME to "时间",
            LibraryMode.TAG to "标签"
        )
    }

    // 滑块实现统一委托给 PillSelector（全仓库唯一实现）。
    PillSelector(
        segments = listOf(
            LibraryMode.ALL, LibraryMode.FOLDER, LibraryMode.AUTHOR,
            LibraryMode.TIME, LibraryMode.TAG
        ).map { PillSegment(it, labels.getValue(it)) },
        selected = current,
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        cornerRadius = 16.dp,
        rowModifier = Modifier,
        rowArrangement = Arrangement.spacedBy(8.dp),
        rowVerticalAlignment = Alignment.CenterVertically,
        // 段有水平 14dp 内边距，必须传入否则文字挤在一起。
        segmentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    )
}

@Composable
private fun SearchSortRow(
    query: String,
    sortMode: SortMode,
    onQueryChange: (String) -> Unit,
    onSortChange: (SortMode) -> Unit,
    /** 本地库分级筛选当前值（all / general / ecchi）。 */
    ratingFilter: String = "all",
    onRatingChange: (String) -> Unit = {},
    /** 「默认 R18」开启时隐藏分级筛选项。 */
    showRatingChip: Boolean = true,
    /** 本地 tag 名全集（联想候选源）。 */
    allTags: List<String> = emptyList(),
    /** 点候选 tag = 应用为筛选 + 清空输入（由调用方接管两件事）。 */
    onPickSuggestedTag: (String) -> Unit = {},
    /** 调用方用 zIndex 把这一整块抬到标签行之上（见调用处注释）。 */
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    // 候选 = 本地 tag 名对输入做不区分大小写 contains（列表在内存里，无需防抖；
    //   在线页打接口才要 350ms 防抖）。已打全的词不再提示。
    val suggestions = remember(query, allTags) {
        if (query.isBlank()) {
            emptyList()
        } else {
            allTags
                .filter { it.contains(query, ignoreCase = true) && !it.equals(query, ignoreCase = true) }
                .take(10)
        }
    }
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 行高钉死：chip 的显隐不改变行高（否则下方网格会上移几像素）。
                // `vertical` 内边距 2dp（配 SEARCH_ROW_HEIGHT）：搜索槽仍是 48dp，
                //   只把"搜索槽底边 → 材质底边"的空白收掉一半。
                .heightIn(min = SEARCH_ROW_HEIGHT)
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 紧凑二级搜索（与管理器 44dp 槽同款）；管理器为实时过滤，✕ = 清空并收起
            CompactSearchSlot(
                open = searchOpen,
                query = query,
                onQueryChange = onQueryChange,
                placeholder = "搜索标题 / 作者 / 标签",
                onOpen = { searchOpen = true },
                onClose = {
                    onQueryChange("")
                    searchOpen = false
                },
                modifier = Modifier.weight(1f)
            )
            if (!searchOpen) {
                Box {
                    FilterChip(
                        selected = true,
                        onClick = { expanded = true },
                        label = { Text(sortMode.label) },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                        shape = RoundedCornerShape(50),
                        colors = accentChipColors()
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        SortMode.entries.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.label) },
                                onClick = {
                                    onSortChange(m)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                // 分级筛选（与在线页同款 chip；「默认 R18」开启时整项隐藏）。
                if (showRatingChip) {
                    SelectorChip(ratingFilter, RATING_OPTIONS, onRatingChange)
                }
            }
        }
        // 展开搜索且有输入时，下方摆 tag 候选；点候选由 onPickSuggestedTag
        //  （应用为筛选 + 清空输入）。联想行保持静态出现。
        if (searchOpen && suggestions.isNotEmpty()) {
            TagSuggestionRow(
                suggestions = suggestions,
                onPick = onPickSuggestedTag,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun VideoGrid(
    videos: List<VideoEntity>,
    showTags: Boolean,
    showR18Tag: Boolean = true,
    onOpenVideo: (VideoEntity) -> Unit,
    onTagClick: (String) -> Unit,
    onLongClick: (VideoEntity) -> Unit,
    onImport: () -> Unit,
    onAuthorClick: (VideoEntity) -> Unit = {},
    /** 被全局筛选器隐藏的条数（>0 ⇒ 空态文案要说"是筛掉的"，不是"库里没有"）。 */
    hiddenByFilter: Int = 0,
    state: LazyGridState = rememberLazyGridState(),
    bottomPadding: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    /**
     * 悬浮顶栏的高度 → 顶部 contentPadding。
     * **必须走 contentPadding，不能给容器加 padding** —— 容器 padding 会把网格的视口整个推到条下面，
     * 卡片就不会"从顶栏底下穿过"了。
     */
    topPadding: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f)
) {
    if (videos.isEmpty()) {
        if (hiddenByFilter > 0) {
            EmptyState(onImport = null, hint = "筛选后没有符合的内容（已隐藏 $hiddenByFilter 项）")
        } else {
            EmptyState(onImport = onImport)
        }
        return
    }
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp + topPadding, bottom = 12.dp + bottomPadding),
        // 卡片间距用 GRID_CARD_GAP，收一点；**外侧仍是左右各 12dp 的对称留白**
        //   （滚动条悬浮在那 12dp 里，收外侧会压到卡片 / 破坏对称）。
        horizontalArrangement = Arrangement.spacedBy(GRID_CARD_GAP),
        verticalArrangement = Arrangement.spacedBy(GRID_CARD_GAP),
        modifier = Modifier.fillMaxSize()
    ) {
        items(videos, key = { it.localUri }) { video ->
            VideoCard(
                video = video,
                onClick = { onOpenVideo(video) },
                onTagClick = onTagClick,
                showTags = showTags,
                showR18Tag = showR18Tag,
                onLongClick = { onLongClick(video) },
                onAuthorClick = { onAuthorClick(video) }
            )
        }
    }
}

@Composable
private fun AuthorList(
    authors: List<AuthorBucket>,
    /** 回传整个桶 —— 点作者名要进作者页，需要 id（分组键）+ name（显示名）。 */
    onSelect: (AuthorBucket) -> Unit,
    state: LazyListState = rememberLazyListState(),
    bottomPadding: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    /** 悬浮顶栏高度 → 顶部 contentPadding（理由见 VideoGrid）。 */
    topPadding: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f)
) {
    if (authors.isEmpty()) {
        EmptyState(onImport = null, hint = "暂无作者信息，请先导入并联网拉取")
        return
    }
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp + topPadding, bottom = 12.dp + bottomPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(authors, key = { it.id }) { a ->
            BucketRow(title = a.name, count = a.count) { onSelect(a) }
        }
    }
}

@Composable
private fun FolderList(
    folders: List<FolderBucket>,
    onSelect: (String) -> Unit,
    state: LazyListState = rememberLazyListState(),
    bottomPadding: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    /** 悬浮顶栏高度 → 顶部 contentPadding（理由见 VideoGrid）。 */
    topPadding: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f)
) {
    if (folders.isEmpty()) {
        EmptyState(onImport = null, hint = "暂无文件夹分类，请先导入本地文件夹")
        return
    }
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp + topPadding, bottom = 12.dp + bottomPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(folders, key = { it.key }) { f ->
            BucketRow(title = f.name, count = f.count) { onSelect(f.key) }
        }
    }
}

@Composable
private fun TimeList(
    buckets: List<TimeBucket>,
    onSelect: (TimeBucket) -> Unit,
    state: LazyListState = rememberLazyListState(),
    bottomPadding: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    /** 悬浮顶栏高度 → 顶部 contentPadding（理由见 VideoGrid）。 */
    topPadding: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f)
) {
    if (buckets.isEmpty()) {
        EmptyState(onImport = null, hint = "暂无时间信息")
        return
    }
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp + topPadding, bottom = 12.dp + bottomPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(buckets, key = { it.key }) { b ->
            BucketRow(title = b.label, count = b.count) { onSelect(b) }
        }
    }
}

/**
 * 标签分类云。
 *
 * 支持**多选** —— 点一个追加、再点同一个取消；
 * [selected] 用来显示"已选中"状态（选中多个时按"全都含"筛）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagCloud(
    tags: List<TagBucket>,
    selected: List<String>,
    onSelect: (String) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    /** 悬浮顶栏高度 → 顶部 contentPadding（这个视图本来没有，一并补上）。 */
    topPadding: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f)
) {
    if (tags.isEmpty()) {
        EmptyState(onImport = null, hint = "暂无标签，请先导入并联网拉取")
        return
    }
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp + topPadding, bottom = 12.dp + bottomPadding)) {
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tags.forEach { t ->
                    val on = selected.any { it.equals(t.id, ignoreCase = true) }
                    InputChip(
                        // 显示"已选中"状态（多选；再点取消）
                        selected = on,
                        onClick = { onSelect(t.id) },
                        label = { Text("${t.name} (${t.count})") },
                        colors = if (on) accentInputChipColors() else InputChipDefaults.inputChipColors()
                    )
                }
            }
        }
    }
}

@Composable
private fun BucketRow(title: String, count: Int, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$count",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun EmptyState(onImport: (() -> Unit)?, hint: String = "还没有视频，导入本地文件夹开始管理") {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
            Text(
                text = hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            if (onImport != null) {
                androidx.compose.material3.Button(
                    onClick = onImport,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(" 导入文件夹")
                }
            }
        }
    }
}

@Composable
private fun ManualLinkDialog(
    video: VideoEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onRemove: () -> Unit
) {
    var input by remember { mutableStateOf(video.iwaraId ?: "") }
    // 两步式：默认显示手动关联表单；点「从库中移除」后切换为移除确认，防误触。
    var confirmingRemove by remember { mutableStateOf(false) }
    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("从库中移除？") },
            text = {
                Column {
                    Text(
                        text = video.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "仅从本地库移除该条目，不删除视频文件；重新扫描可找回。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemove()
                    onDismiss()
                }) {
                    Text("确认移除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动指定 iwara ID") },
        text = {
            Column {
                Text(
                    text = video.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("视频 ID 或链接") },
                    placeholder = { Text("例如 1ywe1sbkqwumpdxz5") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                TextButton(
                    onClick = { confirmingRemove = true },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) { Text("从库中移除…", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val id = sanitizeIwaraId(input)
                if (id.isNotBlank()) onConfirm(id)
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun sanitizeIwaraId(raw: String): String =
    io.github.amatsukiichika.iwaramanager.data.IwaraIdParser.parse(raw) ?: ""

