package io.github.amatsukiichika.iwaram.ui.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.amatsukiichika.iwaram.data.IwaraAccount
import io.github.amatsukiichika.iwaram.data.IwaraClient
import io.github.amatsukiichika.iwaram.data.videoKeys
import io.github.amatsukiichika.iwaram.ui.components.debugAwareErrorText

/* ---------- 通用部件 ---------- */

/**
 * 二级页统一顶栏：与主页面/设置页同款 M3 TopAppBar（返回箭头 + titleLarge 标题 + trailing），自带状态栏避让。
 *
 * [titleContent] 非空时**接管标题槽**（忽略 [title]）。作者页精简模式要把「小头像 + 名字」
 * 画在标题位置——必须交给 TopAppBar 自己排版，才能和普通模式的大标题、和返回键**逐位对齐**；
 * 自己叠一层 Row 手算内缩（padding start = 56.dp）会两头都错：横向对不上标题内缩量，
 * 纵向按"整条含状态栏的高"居中，而真标题只在状态栏下面的内容带里居中。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayTopBar(
    title: String,
    onBack: () -> Unit,
    trailing: @Composable () -> Unit = {},
    titleContent: (@Composable () -> Unit)? = null
) {
    TopAppBar(
        title = {
            if (titleContent != null) {
                titleContent()
            } else {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = { trailing() }
    )
}

/** tag 视频列表覆盖层：在线视频的标签点进来，按 tag 过滤（/videos?tags=）。 */
@Composable
fun TagVideosOverlay(tagId: String, cvm: IwaraClientViewModel, actions: IwaraNavActions, onClose: () -> Unit) {
    val state by cvm.searchResults.collectAsStateWithLifecycle()
    LaunchedEffect(tagId) { cvm.searchByTag(tagId) }
    Column(Modifier.fillMaxSize()) {
        OverlayTopBar(title = "#$tagId", onBack = onClose)
        InfiniteGrid(
            items = state.items,
            loading = state.loading,
            endReached = state.endReached,
            error = state.error,
            onLoadMore = { cvm.loadMoreSearch() },
            onRetry = { cvm.retrySearch() },
            // itemKey 用稳定 id（"内容哈希"默认值会随数据变化，导致整批入场动画重播）。
            itemKey = { it.id.orEmpty().ifEmpty { it.hashCode().toString() } },
            // tag 详情页也是内容列表 ⇒ 同样过全局筛选器。
            globalFilter = ::videoKeys,
            emptyHint = "没有该 tag 的视频"
        ) { video ->
            OnlineVideoCard(video, actions)
        }
    }
}

@Composable
internal fun Avatar(account: IwaraAccount?, size: Int = 36) {
    val url = IwaraClient.avatarUrl(account?.avatar)
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.size(size.dp).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Icon(
            Icons.Filled.Person,
            contentDescription = null,
            modifier = Modifier.size(size.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun FeedbackText(message: String?) {
    message?.let {
        Text(
            // 人话/技术细节按 debug 开关取轨（成功类短句原样透传不受影响）
            debugAwareErrorText(it),
            style = MaterialTheme.typography.bodySmall,
            // 颜色判定含「没有权限」（401/403 的人话映射里没有"失败/错误/无法"字样）
            color = if (it.contains("失败") || it.contains("错误") || it.contains("无法") ||
                it.contains("请先登录") || it.contains("没有权限")
            )
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}
