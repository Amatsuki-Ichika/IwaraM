package io.github.amatsukiichika.iwaram.ui

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
import io.github.amatsukiichika.iwaram.ui.browse.CompactSearchSlot
import io.github.amatsukiichika.iwaram.ui.browse.RATING_OPTIONS
import io.github.amatsukiichika.iwaram.ui.browse.SEARCH_ROW_HEIGHT
import io.github.amatsukiichika.iwaram.ui.browse.ScrollToTopButton
import io.github.amatsukiichika.iwaram.ui.browse.SelectorChip
import io.github.amatsukiichika.iwaram.ui.browse.TagFilterRow
import io.github.amatsukiichika.iwaram.ui.components.TagSuggestionRow
import io.github.amatsukiichika.iwaram.ui.browse.accentChipColors
import io.github.amatsukiichika.iwaram.ui.browse.accentInputChipColors
import io.github.amatsukiichika.iwaram.ui.browse.GRID_CARD_GAP
import io.github.amatsukiichika.iwaram.ui.browse.animateScrollToTop
import io.github.amatsukiichika.iwaram.ui.components.PillSegment
import io.github.amatsukiichika.iwaram.ui.components.horizontalSlideSpec
import io.github.amatsukiichika.iwaram.ui.components.PillSelector
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.amatsukiichika.iwaram.ui.components.TOP_BAR_CONTENT_HEIGHT
import io.github.amatsukiichika.iwaram.ui.components.glassViewBackdrop
import io.github.amatsukiichika.iwaram.ui.components.rememberGlassViewBackdrop
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.amatsukiichika.iwaram.data.VideoEntity
import kotlinx.coroutines.launch

/* ---------- 本地库各模式视图（原 LibraryScreen.kt 视图区块） ---------- */
@Composable
internal fun VideoGrid(
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
internal fun AuthorList(
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
internal fun FolderList(
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
internal fun TimeList(
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
internal fun TagCloud(
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
internal fun BucketRow(title: String, count: Int, onClick: () -> Unit) {
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
internal fun EmptyState(onImport: (() -> Unit)?, hint: String = "还没有视频，导入本地文件夹开始管理") {
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
