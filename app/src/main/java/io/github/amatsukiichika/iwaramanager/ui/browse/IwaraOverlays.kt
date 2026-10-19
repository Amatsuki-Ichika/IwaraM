package io.github.amatsukiichika.iwaramanager.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.amatsukiichika.iwaramanager.data.GlobalFilterHolder
import io.github.amatsukiichika.iwaramanager.data.IwaraAccount
import io.github.amatsukiichika.iwaramanager.data.IwaraClient
import io.github.amatsukiichika.iwaramanager.data.IwaraComment
import io.github.amatsukiichika.iwaramanager.data.IwaraImageItem
import io.github.amatsukiichika.iwaramanager.data.IwaraPlaylist
import io.github.amatsukiichika.iwaramanager.data.IwaraPostItem
import io.github.amatsukiichika.iwaramanager.data.IwaraProfileData
import io.github.amatsukiichika.iwaramanager.data.IwaraVideoItem
import io.github.amatsukiichika.iwaramanager.data.filterBy
import io.github.amatsukiichika.iwaramanager.data.videoKeys
import io.github.amatsukiichika.iwaramanager.ui.FastScrollbarBasic
import io.github.amatsukiichika.iwaramanager.ui.components.AppBottomSheet
import io.github.amatsukiichika.iwaramanager.ui.components.debugAwareErrorText
import kotlinx.coroutines.launch

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

/** 播放列表覆盖层（从「我的」页进入）：顶栏带新建，整卡点击进详情。 */
@Composable
fun PlaylistsOverlay(cvm: IwaraClientViewModel, actions: IwaraNavActions, onClose: () -> Unit) {
    val pageScope = rememberCoroutineScope()
    val playlists by cvm.playlists.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { cvm.loadPlaylists(force = !playlists.loaded) }

    Column(Modifier.fillMaxSize()) {
        OverlayTopBar(
            title = "我的播放列表",
            onBack = onClose,
            trailing = {
                IconButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "新建播放列表")
                }
            }
        )
        if (playlists.loading && playlists.items.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        playlists.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.contains("缓存")) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        if (playlists.loaded && playlists.items.isEmpty() && !playlists.loading) {
            Text(
                "还没有播放列表",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(playlists.items, key = { it.id ?: it.title ?: "" }) { pl ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            pl.id?.let { id -> actions.openPlaylist(id, pl.title ?: "播放列表") }
                        }
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(pl.title ?: "（未命名）", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${pl.numVideos ?: 0} 个视频",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { title ->
                cvm.createPlaylist(title, scope = pageScope) { showCreateDialog = false }
            }
        )
    }
}

@Composable
private fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建播放列表") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("标题") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { if (title.isNotBlank()) onCreate(title) }) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun Avatar(account: IwaraAccount?, size: Int = 36) {
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
private fun FeedbackText(message: String?) {
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

/* ---------- 评论 ---------- */

@Composable
fun CommentsOverlay(
    type: String,
    targetId: String,
    title: String,
    cvm: IwaraClientViewModel,
    actions: IwaraNavActions,
    onClose: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        OverlayTopBar(title = "评论 · $title", onBack = onClose)
        // 面板本体与播放页评论 tab 共用（唯一实现）；
        //   键盘只推面板，顶栏不被顶起。
        CommentsPanel(type, targetId, title, cvm, actions, modifier = Modifier.fillMaxSize())
    }
}

/**
 * 评论面板：列表 + 错误/重试 + 回复提示 + 输入行。
 * **独立评论页（图片/帖子/两个动作面板入口）与播放页「评论」tab 的唯一共用实现**，
 * `cvm.comments` 状态机零改动。
 * [modifier] 由调用方给尺寸（独立页与播放页 tab 都是 fillMaxSize）。
 */
@Composable
fun CommentsPanel(
    type: String,
    targetId: String,
    title: String,
    cvm: IwaraClientViewModel,
    actions: IwaraNavActions,
    modifier: Modifier = Modifier
) {
    val pageScope = rememberCoroutineScope()
    val state by cvm.comments.collectAsStateWithLifecycle()
    val session by cvm.session.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var actionError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(targetId) { cvm.openComments(type, targetId, title) }

    // 底部导航条让位 —— 输入行不贴手势条、不被屏幕圆角压住
    // （键盘弹出时 imePadding 照常叠加；播放页评论 tab 与独立页共用本面板）。
    Column(modifier.navigationBarsPadding().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
        val commentsListState = rememberLazyListState()
        LazyColumn(
            state = commentsListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.items, key = { it.id ?: it.hashCode().toString() }) { comment ->
                CommentItem(
                    comment = comment,
                    isMine = comment.user?.id != null && comment.user?.id == session.userId,
                    onProfile = { comment.user?.let { u -> actions.openAccountProfile(u) } },
                    onReply = { cvm.setReplyTo(comment) },
                    onDelete = {
                        comment.id?.let { cid ->
                            cvm.deleteComment(cid, scope = pageScope) { err -> actionError = err }
                        }
                    },
                    onLoadReplies = { onDone ->
                        cvm.loadReplies(comment, scope = pageScope) { replies, err ->
                            actionError = err
                            onDone(replies)
                        }
                    }
                )
            }
            if (state.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
            state.error?.let {
                item {
                    Column {
                        Text(
                            debugAwareErrorText(it), // 按 debug 开关取人话/技术串
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = { cvm.loadMoreComments(force = true) }) { Text("重试") }
                    }
                }
            }
            if (!state.loading && state.items.isEmpty() && state.error == null) {
                item {
                    Text(
                        "还没有评论",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }
        }
        FastScrollbarBasic(state = commentsListState, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }

        FeedbackText(actionError)

        // 回复目标提示
        state.replyTo?.let { reply ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    "回复 ${reply.user?.name ?: reply.user?.username ?: "…"}：${reply.body?.take(30) ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { cvm.setReplyTo(null) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "取消回复", modifier = Modifier.size(14.dp))
                }
            }
        }

        // 输入行：对齐全局输入语言 —— 胶囊形输入容器（与搜索条同款：主色 10% 底、
        // 主色光标、onSurfaceVariant 占位）+ 主色圆形发送键（与"关注"按钮同一套主色语言）。
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                    .padding(horizontal = 14.dp)
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Box {
                            if (input.text.isEmpty()) {
                                Text(
                                    if (state.replyTo != null) "回复…" else "发表评论…",
                                    style = TextStyle(
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                            inner()
                        }
                    }
                )
            }
            Spacer(Modifier.size(8.dp))
            val canSend = input.text.isNotBlank() && !state.sending
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (canSend) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable(enabled = canSend) {
                        val text = input.text
                        input = TextFieldValue("")
                        cvm.sendComment(text, scope = pageScope) { err ->
                            if (err != null) {
                                actionError = err
                                input = TextFieldValue(text) // 发送失败，恢复输入
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (state.sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        modifier = Modifier.size(18.dp),
                        tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentItem(
    comment: IwaraComment,
    isMine: Boolean,
    onProfile: () -> Unit,
    onReply: () -> Unit,
    onDelete: () -> Unit,
    onLoadReplies: (onDone: (List<IwaraComment>) -> Unit) -> Unit
) {
    var replies by remember { mutableStateOf<List<IwaraComment>?>(null) }
    var loadingReplies by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onProfile)) {
                Avatar(comment.user, 28)
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        comment.user?.name ?: comment.user?.username ?: "匿名",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    timeAgoLabel(comment.createdAt)?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (isMine) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.size(6.dp))
            comment.body?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.size(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "回复",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onReply)
                )
                val n = comment.numReplies ?: 0
                if (n > 0) {
                    Text(
                        if (replies == null) "查看 $n 条回复" else "收起回复",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            if (replies != null) {
                                replies = null
                            } else {
                                loadingReplies = true
                                onLoadReplies { list ->
                                    replies = list
                                    loadingReplies = false
                                }
                            }
                        }
                    )
                }
            }
            if (loadingReplies) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 6.dp).size(16.dp), strokeWidth = 2.dp)
            }
            replies?.forEach { reply ->
                Row(Modifier.padding(top = 6.dp).padding(start = 20.dp)) {
                    Avatar(reply.user, 20)
                    Spacer(Modifier.size(6.dp))
                    Column {
                        Text(
                            reply.user?.name ?: reply.user?.username ?: "匿名",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        reply.body?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}


/* ---------- 播放列表详情 ---------- */

@Composable
fun PlaylistDetailOverlay(
    playlistId: String,
    title: String,
    cvm: IwaraClientViewModel,
    actions: IwaraNavActions,
    onClose: () -> Unit
) {
    val pageScope = rememberCoroutineScope()
    var playlist by remember { mutableStateOf<IwaraPlaylist?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(playlistId) {
        cvm.loadPlaylistDetail(playlistId, scope = pageScope) { p, err ->
            playlist = p
            error = err
        }
    }

    Column(Modifier.fillMaxSize()) {
        OverlayTopBar(
            title = title,
            onBack = onClose,
            trailing = {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除播放列表", tint = MaterialTheme.colorScheme.error)
                }
            }
        )
        error?.let {
            Text(
                debugAwareErrorText(it), // 按 debug 开关取人话/技术串
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )
        }
        val contents = playlist?.contents?.results.orEmpty()
        // 全局筛选器：播放列表内容也是"作品列表"，同样要过最后一道。
        //   空判断分两种：列表本身为空 / 被筛选器筛空（后者要把话说清楚，否则像 bug）。
        // 列表消费端订阅防抖视图。
        val filter by GlobalFilterHolder.listState.collectAsStateWithLifecycle()
        val shown = remember(contents, filter) { contents.filterBy(filter, ::videoKeys) }
        if (playlist != null && shown.isEmpty()) {
            Text(
                if (contents.isNotEmpty()) "筛选后没有符合的内容（已隐藏 ${contents.size} 项）"
                else "播放列表为空（或该列表内容暂无法获取）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        val detailListState = rememberLazyListState()
        val detailScope = rememberCoroutineScope()
        LazyColumn(
            state = detailListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(shown, key = { it.id ?: it.hashCode().toString() }) { video ->
                VideoCardOnline(
                    video = video,
                    onClick = { actions.playVideo(video) },
                    onLongClick = {
                        // 长按 = 从列表移除
                        video.id?.let { vid ->
                            cvm.removeFromPlaylist(playlistId, vid, scope = pageScope) { err ->
                                if (err == null) {
                                    cvm.loadPlaylistDetail(playlistId, scope = pageScope) { p, e ->
                                        playlist = p
                                        error = e
                                    }
                                }
                            }
                        }
                    },
                    onTagClick = { actions.openTagVideos(it) }
                )
            }
        }
        FastScrollbarBasic(state = detailListState, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        ScrollToTopButton(
            visible = detailListState.firstVisibleItemIndex > 2,
            onClick = { detailScope.launch { detailListState.animateScrollToTop() } },
            modifier = Modifier
            .align(Alignment.BottomEnd)
            // 统一落点（这些二级页盖住底栏，传 0 ⇒ 仍是贴屏幕底 + 13dp）。
            .padding(end = 24.dp, bottom = scrollToTopBottomPadding(0.dp))
        )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除播放列表") },
            text = { Text("确定删除「$title」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    cvm.deletePlaylist(playlistId, scope = pageScope) { _ -> onClose() }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
}

/* ---------- 图片查看器 ---------- */

@Composable
fun ImageViewerOverlay(image: IwaraImageItem, cvm: IwaraClientViewModel, actions: IwaraNavActions, onClose: () -> Unit) {
    val pageScope = rememberCoroutineScope()
    var detail by remember { mutableStateOf(image) }
    var error by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val session by cvm.session.collectAsStateWithLifecycle()
    val liked = cvm.likeStates[cvm.likeKey("image", image.id ?: "")] ?: (image.liked == true)

    LaunchedEffect(image.id) {
        image.id?.let { id ->
            cvm.loadImageDetail(id, scope = pageScope) { d, err ->
                if (d != null) detail = d
                error = err
            }
        }
    }

    // 图集多图字段是 files（回退 fileList/file）；只读 fileList 会永远为空、只有 1 页。
    val files = detail.fileRefs
    val pageCount = files.size.coerceAtLeast(1)
    val pagerState = rememberPagerState { pageCount }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val url = files.getOrNull(page)?.let { IwaraClient.imageOriginalUrl(it) }
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = detail.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
        // 顶部信息与操作
        // 补 statusBarsPadding —— 全 app 是 edge-to-edge，图片铺满全屏延伸进状态栏底下没问题，
        // 但这条顶栏 Row 从 y=0 排版时，返回键/标题/操作按钮会被系统状态栏（时钟/电量）压住。
        // 色带在 padding **之前**画 ⇒ 半透明黑带垫到状态栏底下、内容避让（播放器式观感）。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp)
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    detail.title ?: "图片",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${pagerState.currentPage + 1} / $pageCount",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    // 详情里也把点赞/评论数据显出来（与卡片一致的计数口径）。
                    Text(
                        "  ·  ♥ ${detail.numLikes ?: 0}  ·  评论 ${detail.numComments ?: 0}",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            IconButton(onClick = {
                val id = detail.id ?: return@IconButton
                if (!session.loggedIn) {
                    feedback = "请先登录后再点赞"
                    return@IconButton
                }
                cvm.toggleLike("image", id, liked, scope = pageScope) { _, err -> feedback = err }
            }) {
                Icon(
                    if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "点赞",
                    tint = if (liked) MaterialTheme.colorScheme.primary else Color.White
                )
            }
            IconButton(onClick = {
                detail.id?.let { id -> actions.openComments("image", id, detail.title ?: "图片") }
            }) {
                Icon(Icons.Filled.TextFields, contentDescription = "评论", tint = Color.White)
            }
        }
        error?.let {
            Text(
                debugAwareErrorText(it), // 按 debug 开关取人话/技术串
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        Box(Modifier.align(Alignment.BottomCenter)) { FeedbackText(feedback) }
    }
}

/* ---------- 帖子详情 ---------- */

@Composable
fun PostDetailOverlay(post: IwaraPostItem, cvm: IwaraClientViewModel, actions: IwaraNavActions, onClose: () -> Unit) {
    val pageScope = rememberCoroutineScope()
    val session by cvm.session.collectAsStateWithLifecycle()
    var feedback by remember { mutableStateOf<String?>(null) }
    // 列表响应常不带正文与点赞/评论计数 → 进详情页补拉一次 GET /post/{id}，
    // 否则「帖子」模块会显示成"没内容的空卡片"、计数恒 0。
    var detail by remember { mutableStateOf(post) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(post.id) {
        post.id?.let { id ->
            cvm.loadPostDetail(id, scope = pageScope) { d, err ->
                if (d != null) detail = d
                error = err
            }
        }
    }
    val liked = cvm.likeStates[cvm.likeKey("post", detail.id ?: "")] ?: (detail.liked == true)

    Column(Modifier.fillMaxSize()) {
        OverlayTopBar(
            title = detail.title ?: "帖子",
            onBack = onClose,
            trailing = {
                IconButton(onClick = {
                    val id = detail.id ?: return@IconButton
                    if (!session.loggedIn) {
                        feedback = "请先登录后再点赞"
                        return@IconButton
                    }
                    cvm.toggleLike("post", id, liked, scope = pageScope) { _, err -> feedback = err }
                }) {
                    Icon(
                        if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "点赞",
                        tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { detail.user?.let { actions.openAccountProfile(it) } }
            ) {
                Avatar(detail.user, 32)
                Spacer(Modifier.size(8.dp))
                Column {
                    Text(detail.user?.name ?: detail.user?.username ?: "匿名", style = MaterialTheme.typography.labelLarge)
                    timeAgoLabel(detail.createdAt)?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            detail.body?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, fontSize = 15.sp, lineHeight = 22.sp)
            }
            Spacer(Modifier.size(12.dp))
            // 点赞 / 评论 / 浏览（列表与详情都缺时显示 0，不隐藏整行，避免"板块看起来坏了"）
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatLine(Icons.Filled.Favorite, "${detail.numLikes ?: 0}")
                StatLine(Icons.Filled.TextFields, "${detail.numComments ?: 0}")
                StatLine(Icons.Filled.RemoveRedEye, "${detail.numViews ?: 0}")
            }
            Spacer(Modifier.size(16.dp))
            TextButton(onClick = {
                detail.id?.let { id -> actions.openComments("post", id, detail.title ?: "帖子") }
            }) {
                Text("查看评论（${detail.numComments ?: 0}）")
            }
        }
        error?.let {
            Text(
                debugAwareErrorText(it), // 按 debug 开关取人话/技术串
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        FeedbackText(feedback)
    }
}

/** 详情页的统计项：图标 + 数字。 */
@Composable
private fun StatLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/* ---------- 动作面板（长按视频/图片） ---------- */

private enum class ActionsMode { MAIN, PICK_PLAYLIST }

@Composable
fun VideoActionsSheet(
    video: IwaraVideoItem,
    cvm: IwaraClientViewModel,
    actions: IwaraNavActions,
    onClose: () -> Unit,
    /** true = 跳过主菜单直达"加入播放列表"（播放页「收藏」按钮 = 网站"添加到"）。 */
    pickPlaylistDirectly: Boolean = false
) {
    val pageScope = rememberCoroutineScope()
    val session by cvm.session.collectAsStateWithLifecycle()
    val liked = cvm.likeStates[cvm.likeKey("video", video.id ?: "")] ?: (video.liked == true)
    var mode by remember { mutableStateOf(if (pickPlaylistDirectly) ActionsMode.PICK_PLAYLIST else ActionsMode.MAIN) }
    // 直达播放列表模式时预载列表（主菜单路径是点击时才 loadPlaylists）。
    LaunchedEffect(pickPlaylistDirectly) { if (pickPlaylistDirectly) cvm.loadPlaylists() }
    var feedback by remember { mutableStateOf<String?>(null) }

    // 统一走 AppBottomSheet —— 四个抽屉高度完全一致（屏高 88%，锁死一个值）。
    AppBottomSheet(onDismiss = onClose) {
        when (mode) {
            ActionsMode.MAIN -> {
                Text(
                    video.title ?: "视频",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                FeedbackText(feedback)
                ActionRow(
                    icon = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    label = if (liked) "取消收藏" else "收藏",
                    onClick = {
                        val id = video.id ?: return@ActionRow
                        if (!session.loggedIn) {
                            feedback = "请先登录后再收藏"
                            return@ActionRow
                        }
                        cvm.toggleLike("video", id, liked, scope = pageScope) { ok, err ->
                            feedback = when {
                                ok == true -> "已收藏"
                                ok == false -> "已取消收藏"
                                else -> err
                            }
                        }
                    }
                )
                ActionRow(
                    icon = Icons.Filled.Add,
                    label = "加入播放列表",
                    onClick = {
                        if (!session.loggedIn) {
                            feedback = "请先登录后使用播放列表"
                            return@ActionRow
                        }
                        cvm.loadPlaylists()
                        mode = ActionsMode.PICK_PLAYLIST
                    }
                )
                ActionRow(
                    icon = Icons.Filled.Download,
                    label = "下载到管理器",
                    onClick = {
                        feedback = "已加入下载队列，可在通知栏查看进度"
                        cvm.downloadVideo(video) { err -> feedback = err }
                    }
                )
                ActionRow(
                    icon = Icons.Filled.PlayArrow,
                    label = "播放",
                    onClick = { actions.playVideo(video) }
                )
                ActionRow(
                    icon = Icons.Filled.TextFields,
                    label = "评论（${video.numComments ?: 0}）",
                    onClick = {
                        video.id?.let { id -> actions.openComments("video", id, video.title ?: "视频") }
                    }
                )
                ActionRow(
                    icon = Icons.Filled.Person,
                    label = "作者主页",
                    onClick = { video.user?.let { actions.openAccountProfile(it) } }
                )
                Spacer(Modifier.size(24.dp))
            }
            ActionsMode.PICK_PLAYLIST -> {
                val playlists by cvm.playlists.collectAsStateWithLifecycle()
                var newTitle by remember { mutableStateOf("") }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        placeholder = { Text("新播放列表标题") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        if (newTitle.isNotBlank()) {
                            cvm.createPlaylist(newTitle, scope = pageScope) { err -> feedback = err ?: "已创建" }
                            newTitle = ""
                        }
                    }) { Text("创建") }
                }
                FeedbackText(feedback)
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(playlists.items, key = { it.id ?: "" }) { pl ->
                        OutlinedButton(onClick = {
                            val pid = pl.id ?: return@OutlinedButton
                            val vid = video.id ?: return@OutlinedButton
                            cvm.addToPlaylist(pid, vid, scope = pageScope) { err ->
                                feedback = if (err == null) "已加入「${pl.title}」" else err
                            }
                        }) { Text(pl.title ?: "未命名") }
                    }
                }
                if (playlists.loading) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
                if (playlists.loaded && playlists.items.isEmpty()) {
                    Text(
                        "还没有播放列表，先在上面创建一个吧",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                TextButton(
                    onClick = { mode = ActionsMode.MAIN },
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) { Text("返回") }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

@Composable
fun ImageActionsSheet(image: IwaraImageItem, cvm: IwaraClientViewModel, actions: IwaraNavActions, onClose: () -> Unit) {
    val pageScope = rememberCoroutineScope()
    val session by cvm.session.collectAsStateWithLifecycle()
    val liked = cvm.likeStates[cvm.likeKey("image", image.id ?: "")] ?: (image.liked == true)
    var feedback by remember { mutableStateOf<String?>(null) }

    // 同视频动作面板 —— 统一走 AppBottomSheet（高度与其他三个抽屉一致）。
    AppBottomSheet(onDismiss = onClose) {
        Text(
            image.title ?: "图片",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        FeedbackText(feedback)
        ActionRow(
            icon = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            label = if (liked) "取消收藏" else "收藏",
            onClick = {
                val id = image.id ?: return@ActionRow
                if (!session.loggedIn) {
                    feedback = "请先登录后再收藏"
                    return@ActionRow
                }
                cvm.toggleLike("image", id, liked, scope = pageScope) { ok, err ->
                    feedback = when {
                        ok == true -> "已收藏"
                        ok == false -> "已取消收藏"
                        else -> err
                    }
                }
            }
        )
        ActionRow(
            icon = Icons.Filled.TextFields,
            label = "评论（${image.numComments ?: 0}）",
            onClick = {
                image.id?.let { id -> actions.openComments("image", id, image.title ?: "图片") }
            }
        )
        ActionRow(
            icon = Icons.Filled.Person,
            label = "作者主页",
            onClick = { image.user?.let { actions.openAccountProfile(it) } }
        )
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
