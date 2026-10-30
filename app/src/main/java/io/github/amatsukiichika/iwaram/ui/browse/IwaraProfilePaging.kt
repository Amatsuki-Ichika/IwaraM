package io.github.amatsukiichika.iwaram.ui.browse

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.amatsukiichika.iwaram.data.GlobalFilterHolder
import io.github.amatsukiichika.iwaram.data.IwaraAccount
import io.github.amatsukiichika.iwaram.data.IwaraClient
import io.github.amatsukiichika.iwaram.data.IwaraImageItem
import io.github.amatsukiichika.iwaram.data.IwaraPostItem
import io.github.amatsukiichika.iwaram.data.IwaraProfileData
import io.github.amatsukiichika.iwaram.data.IwaraVideoItem
import io.github.amatsukiichika.iwaram.data.VideoEntity
import io.github.amatsukiichika.iwaram.data.filterBy
import io.github.amatsukiichika.iwaram.data.localVideoKeys
import io.github.amatsukiichika.iwaram.data.videoKeys
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.DiagLog
import io.github.amatsukiichika.iwaram.ui.components.debugAwareErrorText
import kotlin.math.roundToInt
import io.github.amatsukiichika.iwaram.ui.VideoCard
import io.github.amatsukiichika.iwaram.ui.VideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ---------- 作者页通用分页组件（原 IwaraProfilePage.kt 分页区块） ---------- */
/** 单个分段的分页状态（每段独立加载，互不阻塞）。 */
internal data class PagedState<T>(
    val items: List<T> = emptyList(),
    val page: Int = 0,
    val end: Boolean = false,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null
)

/** 解析资料失败（在线内容拉不到）时的占位。 */
@Composable
internal fun OnlineFailedHint(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                tr("在线内容加载失败", "Failed to load online content"),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.size(12.dp))
            TextButton(onClick = onRetry) { Text(tr("重试", "Retry")) }
        }
    }
}

/** 离线 / 无在线内容时的分段占位。 */
@Composable
internal fun OfflineHint(unmatchedLocal: Boolean, offlineText: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            if (unmatchedLocal) tr("这些本地视频未匹配到 iwara，没有在线内容", "These local videos did not match iwara, so there is no online content") else offlineText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

/** 「在线可用性还没确定」时的占位（正在解析资料）—— 不能误显示成"需要联网"。 */
@Composable
internal fun SegmentLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * 关注 / 粉丝关系列表二级页（从计数点进来）。
 * 复用 `PagedAccountList` 与 `loadFollowing` / `loadUserFollowers`。
 */

/* ---------- 内容容器 ---------- */

@Composable
internal fun <T> PagedGrid(
    state: PagedState<T>,
    columns: GridCells,
    emptyHint: String,
    gridState: LazyGridState,
    header: @Composable () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    key: (T) -> Any,
    itemContent: @Composable (T) -> Unit
) {
    val scope = rememberCoroutineScope()
    val showTop by remember {
        derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 }
    }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = columns,
            modifier = Modifier.fillMaxSize(),
            // 顶部 12dp 由首项（作者信息）自带 —— 与「视频」栏同样是吸顶位移的干净线性关系。
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 头部 = 通栏首 item（作者信息 + 吸顶块占位），与「视频」栏同一条滚动链。
            item(span = { GridItemSpan(maxLineSpan) }, key = "__header__") { header() }
            if (state.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "__placeholder__") {
                    // 懒布局子项高度约束无限，占位给固定高度才能居中。
                    Box(
                        Modifier.fillMaxWidth().height(280.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        SegmentPlaceholder(state, emptyHint, onRetry)
                    }
                }
            } else {
                items(state.items, key = key) { itemContent(it) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PagedFooter(state, onLoadMore, onRetry)
                }
            }
        }
        ScrollToTopButton(
            visible = showTop,
            onClick = { scope.launch { gridState.animateScrollToTop() } },
            modifier = Modifier
            .align(Alignment.BottomEnd)
            // 统一落点（这些二级页盖住底栏，传 0 ⇒ 仍是贴屏幕底 + 13dp）。
            .padding(end = 24.dp, bottom = scrollToTopBottomPadding(0.dp))
        )
    }
}

@Composable
internal fun PagedAccountList(
    state: PagedState<IwaraAccount>,
    emptyHint: String,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    /** 回传整个账号对象（含 UUID）—— 作者页凭它直接拉内容，省掉一次 profile 解析。 */
    onOpen: (IwaraAccount) -> Unit
) {
    if (state.items.isEmpty()) {
        SegmentPlaceholder(state, emptyHint, onRetry)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            state.items,
            key = { it.id ?: it.username ?: it.name ?: it.hashCode().toString() }
        ) { account ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AccountAvatar(account, 40)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            account.name ?: account.username ?: tr("未知用户", "Unknown user"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        account.username?.let { handle ->
                            Text(
                                "@$handle",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    account.username?.let {
                        TextButton(onClick = { onOpen(account) }) { Text(tr("主页", "Profile")) }
                    }
                }
            }
        }
        item(key = "__footer__") {
            PagedFooter(state, onLoadMore, onRetry)
        }
    }
}

/** 列表/网格底部：加载中 / 加载更多 / 出错重试。 */
@Composable
internal fun <T> PagedFooter(state: PagedState<T>, onLoadMore: () -> Unit, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        when {
            state.loading -> CircularProgressIndicator()
            state.error != null -> TextButton(onClick = onRetry) { Text(tr("重试", "Retry")) }
            !state.end -> TextButton(onClick = onLoadMore) { Text(tr("加载更多", "Load more")) }
            else -> Text(
                tr("没有更多了", "No more items"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 空 / 错误 / 加载占位（首屏）。 */
@Composable
internal fun <T> SegmentPlaceholder(state: PagedState<T>, emptyHint: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            state.loading -> CircularProgressIndicator()
            state.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    // 普通模式显示人话，debug 开关打开时显示原样技术串
                    debugAwareErrorText(state.error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(Modifier.size(12.dp))
                TextButton(onClick = onRetry) { Text(tr("重试", "Retry")) }
            }
            else -> Text(emptyHint, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
