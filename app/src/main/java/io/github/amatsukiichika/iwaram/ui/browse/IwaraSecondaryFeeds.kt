package io.github.amatsukiichika.iwaram.ui.browse

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.shape.CircleShape
import android.net.Uri
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.documentfile.provider.DocumentFile
import io.github.amatsukiichika.iwaram.DownloadService
import io.github.amatsukiichika.iwaram.DownloadState
import io.github.amatsukiichika.iwaram.IwaraApp
import io.github.amatsukiichika.iwaram.LocalAppRepository
import io.github.amatsukiichika.iwaram.activeDownloadTasks
import io.github.amatsukiichika.iwaram.pendingToTask
import io.github.amatsukiichika.iwaram.data.DownloadHistoryEntry
import io.github.amatsukiichika.iwaram.data.IwaraAccount
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import io.github.amatsukiichika.iwaram.data.IwaraClient
import io.github.amatsukiichika.iwaram.data.imageKeys
import io.github.amatsukiichika.iwaram.data.videoKeys
import io.github.amatsukiichika.iwaram.data.loadDownloadHistory
import io.github.amatsukiichika.iwaram.data.loadPendingDownloads
import io.github.amatsukiichika.iwaram.data.removeDownloadHistory
import io.github.amatsukiichika.iwaram.data.removePendingDownload
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.FastScrollbarBasic
import io.github.amatsukiichika.iwaram.ui.components.debugAwareErrorText
import io.github.amatsukiichika.iwaram.ui.components.horizontalSlideSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 收藏覆盖层（从「我的」页进入）：顶栏返回 + 视频/图片分段。 */
@Composable
fun FavoritesOverlay(cvm: IwaraClientViewModel, actions: IwaraNavActions, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        OverlayTopBar(title = tr("我的收藏", "My favorites"), onBack = onClose)
        FavoritesContent(cvm, actions)
    }
}

@Composable
private fun FavoritesContent(cvm: IwaraClientViewModel, actions: IwaraNavActions) {
    var segment by remember { mutableStateOf(FavSegment.VIDEOS) }
    val session by cvm.session.collectAsStateWithLifecycle()

    if (!session.loggedIn && !session.checking) {
        LoginRequiredPanel(tr("收藏需要登录后查看", "Sign in to view favorites"))
        return
    }

    Column(Modifier.fillMaxSize()) {
        // 收藏页 视频/图片 切换用 PillSegmentSelector，与全站药丸分段统一。
        PillSegmentSelector(FavSegment.entries.map { it.label }, segment.ordinal) {
            segment = FavSegment.entries[it]
        }
        Box(Modifier.weight(1f)) {
            // 内容区：按方向左右滑动切换（与下载列表同规格：300ms 滑动 + 220ms 淡入淡出）。
            AnimatedContent(
                targetState = segment,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
                transitionSpec = horizontalSlideSpec { target, initial -> if (target > initial) 1 else -1 },
                label = "favSegment"
            ) { seg ->
                when (seg) {
                    FavSegment.VIDEOS -> FavoriteVideosGrid(cvm, actions)
                    FavSegment.IMAGES -> FavoriteImagesGrid(cvm, actions)
                }
            }
        }
    }
}

private enum class FavSegment(private val labelZh: String, private val labelEn: String) {
    VIDEOS("视频", "Videos"),
    IMAGES("图片", "Images");

    val label: String get() = tr(labelZh, labelEn)
}

@Composable
private fun FavoriteVideosGrid(cvm: IwaraClientViewModel, actions: IwaraNavActions) {
    val state by cvm.favoriteVideos.collectAsStateWithLifecycle()
    // 订阅式登录感知（若用组合期读一次的快照值作 key，登录完成后该页未重组时 effect 不会重跑
    // → 收藏流"偶发不加载"）。force 在 collect 体内读 VM 当前值，不捕获首帧。
    LaunchedEffect(Unit) {
        cvm.session.map { it.loggedIn }.distinctUntilChanged().collect { loggedIn ->
            if (loggedIn) cvm.loadMoreFavoriteVideos(force = cvm.favoriteVideos.value.items.isEmpty())
        }
    }
    InfiniteGrid(
        items = state.items,
        loading = state.loading,
        endReached = state.endReached,
        error = state.error,
        onLoadMore = { cvm.loadMoreFavoriteVideos() },
        onRetry = { cvm.loadMoreFavoriteVideos(force = true) },
        emptyHint = tr("还没有收藏的视频", "No favorite videos yet"),
        // 全局筛选器也管到这里（"全局"= 所有内容列表，不只是首页那几个分段）。
        globalFilter = ::videoKeys,
        entranceKey = "favVideos",
        itemKey = { it.id.orEmpty().ifEmpty { it.hashCode().toString() } },
    ) { video ->
        OnlineVideoCard(video, actions)
    }
}

@Composable
private fun FavoriteImagesGrid(cvm: IwaraClientViewModel, actions: IwaraNavActions) {
    val state by cvm.favoriteImages.collectAsStateWithLifecycle()
    // 同上，订阅式登录感知。
    LaunchedEffect(Unit) {
        cvm.session.map { it.loggedIn }.distinctUntilChanged().collect { loggedIn ->
            if (loggedIn) cvm.loadMoreFavoriteImages(force = cvm.favoriteImages.value.items.isEmpty())
        }
    }
    InfiniteGrid(
        items = state.items,
        loading = state.loading,
        endReached = state.endReached,
        error = state.error,
        onLoadMore = { cvm.loadMoreFavoriteImages() },
        onRetry = { cvm.loadMoreFavoriteImages(force = true) },
        columns = GridCells.Adaptive(120.dp),
        emptyHint = tr("还没有收藏的图片", "No favorite images yet"),
        globalFilter = ::imageKeys,
        entranceKey = "favImages",
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

/* ---------- 下载列表：左"正在下载"右"已完成" ---------- */

// DateTimeFormatter 不可变、线程安全：共享的 SimpleDateFormat 一旦被多线程并发 format 会输出
// 错乱结果（PlayerDiagnostics 的注释里有同款告诫），改用 java.time 消除这颗埋雷。
private val downloadTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

/** 下载列表覆盖层：药丸滑块切换 正在下载/已完成，列表带基础滚动条。 */
@Composable
fun DownloadsOverlay(cvm: IwaraClientViewModel, onClose: () -> Unit) {
    var segment by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        OverlayTopBar(title = tr("下载列表", "Downloads"), onBack = onClose)
        PillSegmentSelector(listOf(tr("正在下载", "Downloading"), tr("已完成", "Completed")), segment) { segment = it }
        Box(Modifier.weight(1f)) {
            // 内容区：按方向左右滑动切换（与本地页 modeContent 同规格：300ms 滑动 + 220ms 淡入淡出）。
            AnimatedContent(
                targetState = segment,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
                transitionSpec = horizontalSlideSpec { target, initial -> if (target >= initial) 1 else -1 },
                label = "downloadSegment"
            ) { seg ->
                if (seg == 0) ActiveDownloadsList(cvm, Modifier.fillMaxSize())
                else DownloadHistoryList(cvm, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ActiveDownloadsList(cvm: IwaraClientViewModel, modifier: Modifier = Modifier) {
    val tasks by DownloadState.tasks.collectAsStateWithLifecycle()
    // 注意：`filter { !it.finished }` 会把**失败任务**也整条过滤掉（finished = DONE **或 FAILED**），
    //   「重新下载 / 移除」那两个按钮（when 的 STATE_FAILED 分支）就成了到不了的死代码：
    //   下载一失败就从列表里消失、既看不到也续不了（"断点续传无法使用"）。
    //   故统一走 activeDownloadTasks()（只排除已完成，有单测盯住）。
    val active = activeDownloadTasks(tasks)
    val ctx = LocalContext.current
    val repo = LocalAppRepository.current
    val scope = rememberCoroutineScope()
    // 应用重启后恢复持久化的未完成任务（排队/暂停/失败/中断），续传记录不丢。
    LaunchedEffect(Unit) {
        // prefs JSON 读取放 IO（LaunchedEffect 默认跑在主线程）。
        val pending = withContext(Dispatchers.IO) { repo.loadPendingDownloads() }
        DownloadState.restoreIfAbsent(pending.map { pendingToTask(it) })
    }
    if (active.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(tr("没有正在进行的下载", "No active downloads"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val listState = rememberLazyListState()
    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(active, key = { it.videoId }) { task ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(task.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.size(8.dp))
                        if (task.total > 0) {
                            val fraction = (task.received.toFloat() / task.total).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                (fraction * 100).toInt().toString() + "%  " +
                                    formatBytes(task.received) + " / " + formatBytes(task.total),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.size(6.dp))
                            Text(
                                tr("已接收 ", "Received ") + formatBytes(task.received),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        task.message?.let { msg ->
                            Spacer(Modifier.size(4.dp))
                            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.size(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            when (task.state) {
                                DownloadState.STATE_RUNNING -> TextButton(onClick = { DownloadService.pause(task.videoId) }) {
                                    Text(tr("暂停", "Pause"), style = MaterialTheme.typography.bodySmall)
                                }
                                DownloadState.STATE_PAUSED, DownloadState.STATE_QUEUED -> {
                                    TextButton(onClick = {
                                        cvm.startDownloadById(task.videoId, scope = scope) { showDownloadToast(ctx, it) }
                                    }) { Text(tr("继续", "Resume"), style = MaterialTheme.typography.bodySmall) }
                                    TextButton(onClick = {
                                        // 弃掉整个未完成任务：先让服务放下这个任务（排队中的会在出队时
                                        // 被丢弃，正好在跑的由读循环收尾），再删记录 + 删半成品文件。
                                        // 顺序不能反：先删记录的话，worker 出队会重新登记一条 running。
                                        // 文件删失败要**说出来**。
                                        scope.launch {
                                            DownloadService.cancel(task.videoId)
                                            removePendingCompletely(ctx, task.videoId, task.docUri)
                                                ?.let { showDownloadToast(ctx, it) }
                                        }
                                    }) {
                                        Text(tr("取消", "Cancel"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                DownloadState.STATE_FAILED -> {
                                    TextButton(onClick = {
                                        cvm.startDownloadById(task.videoId, scope = scope) { showDownloadToast(ctx, it) }
                                    }) { Text(tr("重新下载", "Retry download"), style = MaterialTheme.typography.bodySmall) }
                                    TextButton(onClick = {
                                        scope.launch {
                                            removePendingCompletely(ctx, task.videoId, task.docUri)
                                                ?.let { showDownloadToast(ctx, it) }
                                        }
                                    }) {
                                        Text(tr("移除", "Remove"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        FastScrollbarBasic(
            state = listState,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
        )
    }
}

@Composable
private fun DownloadHistoryList(cvm: IwaraClientViewModel, modifier: Modifier = Modifier) {
    val repo = LocalAppRepository.current
    val scope = rememberCoroutineScope()
    // 历史加载挪出组合期（在组合期同步读 SharedPreferences+Gson 会在滚动/进入时卡顿）。
    // prefs+gson 读取极快，加载中的空窗显示既有"还没有下载记录"占位，肉眼不可见。
    var history by remember { mutableStateOf(emptyList<DownloadHistoryEntry>()) }
    LaunchedEffect(Unit) {
        history = withContext(Dispatchers.IO) { repo.loadDownloadHistory() }
    }
    if (history.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(tr("还没有下载记录", "No download history yet"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val listState = rememberLazyListState()
    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(history, key = { it.videoId + it.time }) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                entry.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.size(8.dp))
                            if (entry.success) {
                                Text(
                                    "✓",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            } else {
                                Text(
                                    "✕",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                        Spacer(Modifier.size(4.dp))
                        Text(
                            buildString {
                                if (entry.quality.isNotBlank()) append(entry.quality)
                                append(" · ")
                                append(
                                    downloadTimeFormat.format(
                                        Instant.ofEpochMilli(entry.time).atZone(ZoneId.systemDefault())
                                    )
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        entry.message?.let { msg ->
                            Spacer(Modifier.size(4.dp))
                            Text(
                                msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (entry.success) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        // 文件丢失检测（记录里有 SAF 地址才可判）。
                        // DocumentFile.exists() 是 Binder IPC + 磁盘 IO，放在组合期会随 LazyColumn
                        // 滚动反复执行；这里挪进 LaunchedEffect + IO，默认不丢、查完再标。
                        val ctx = LocalContext.current
                        var fileMissing by remember(entry.videoId + entry.time) { mutableStateOf(false) }
                        LaunchedEffect(entry.videoId + entry.time) {
                            val localUri = entry.localUri ?: return@LaunchedEffect
                            fileMissing = withContext(Dispatchers.IO) {
                                runCatching {
                                    val df = DocumentFile.fromSingleUri(ctx, Uri.parse(localUri))
                                    df == null || !df.exists() || df.length() == 0L
                                }.getOrDefault(false)
                            }
                        }
                        if (fileMissing) {
                            Spacer(Modifier.size(4.dp))
                            Text(tr("文件丢失", "File missing"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.size(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            // ⋮ 菜单 —— 重新下载（丢失/失败）/ 移除任务 / 移除任务和文件。
                            var menuOpen by remember { mutableStateOf(false) }
                            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.MoreVert, contentDescription = tr("更多操作", "More actions"), modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (!entry.success || fileMissing) {
                                    DropdownMenuItem(
                                        text = { Text(tr("重新下载", "Retry download")) },
                                        onClick = {
                                            menuOpen = false
                                            cvm.startDownloadById(entry.videoId, scope = scope) { showDownloadToast(ctx, it) }
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(tr("移除任务", "Remove task")) },
                                    onClick = {
                                        menuOpen = false
                                        // 删记录是同步的（SharedPreferences.apply 的改动立刻对读可见），
                                        // 但要先把**磁盘文件保留**、只清记录，所以统一走 suspend 版本保证顺序。
                                        scope.launch {
                                            removeHistoryOnly(ctx, entry.videoId)
                                            history = withContext(Dispatchers.IO) { repo.loadDownloadHistory() }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(tr("移除任务和文件", "Remove task and file"), color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menuOpen = false
                                        // 等删除**真的做完**再刷新列表，并把"文件没删掉"的原因
                                        // 弹出来 —— DocumentFile.delete() 内部吞掉异常后返回 false，若把
                                        // 返回值丢掉又立刻刷新，就成了"条目消失了、文件还在"
                                        // （即"删除文件无法使用"）。
                                        scope.launch {
                                            val err = removeHistoryAndFile(ctx, entry.videoId, entry.localUri)
                                            history = withContext(Dispatchers.IO) { repo.loadDownloadHistory() }
                                            err?.let { showDownloadToast(ctx, it) }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        FastScrollbarBasic(
            state = listState,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
        )
    }
}

/** 下载操作失败提示（继续/重新下载解析出错、删除文件被拒时弹 toast）。 */
private fun showDownloadToast(context: android.content.Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

/**
 * 删除一个 SAF 文档并**如实回报结果**。
 *
 * `DocumentFile.delete()` 会把异常吞掉后返回 false，所以"删失败"是完全静默的。返回 null＝成功
 * （或本来就没有文件），非 null＝失败原因（供 toast 显示）。
 */
private suspend fun deleteDocument(context: android.content.Context, uri: String?): String? {
    if (uri.isNullOrBlank()) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val df = DocumentFile.fromSingleUri(context, Uri.parse(uri))
            when {
                df == null || !df.exists() -> null
                df.delete() -> null
                else -> tr(
                    "文件删除被系统拒绝（可能未授予该目录的写入权限，请在设置里重选下载目录）",
                    "The system refused to delete the file (the folder may lack write permission — reselect the download folder in settings)"
                )
            }
        }.getOrElse { it.message ?: it.javaClass.simpleName }
    }
}

/**
 * 取消/移除未完成任务：删待续记录 + 删半成品文件 + 移除列表条目。
 * 返回 null＝成功；非 null＝**文件没删掉**的原因（记录已删，调用方可 toast 提示）。
 */
private suspend fun removePendingCompletely(
    context: android.content.Context,
    videoId: String,
    docUri: String?
): String? {
    val app = context.applicationContext as IwaraApp
    withContext(Dispatchers.IO) { app.repository.removePendingDownload(videoId) }
    DownloadState.remove(videoId)
    return deleteDocument(app, docUri)
}

/** 只移除历史/待续记录，**保留磁盘文件**（「移除任务」）。 */
private suspend fun removeHistoryOnly(context: android.content.Context, videoId: String) {
    val app = context.applicationContext as IwaraApp
    withContext(Dispatchers.IO) {
        app.repository.removeDownloadHistory(videoId)
        app.repository.removePendingDownload(videoId)
    }
    DownloadState.remove(videoId)
}

/**
 * 移除任务和文件：删历史记录 + 删待续记录 + 删文件本体
 * （不含管理器库条目 —— 库条目由「从库中移除」管理）。
 */
private suspend fun removeHistoryAndFile(
    context: android.content.Context,
    videoId: String,
    localUri: String?
): String? {
    val app = context.applicationContext as IwaraApp
    withContext(Dispatchers.IO) {
        app.repository.removeDownloadHistory(videoId)
        app.repository.removePendingDownload(videoId)
    }
    DownloadState.remove(videoId)
    return deleteDocument(app, localUri)
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / (1L shl 30).toFloat())
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toFloat())
    bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / (1L shl 10).toFloat())
    else -> bytes.toString() + " B"
}

/* ---------- 我的关注（独立二级页，从账号卡片进入） ---------- */

/** 「我的关注」覆盖层：关注账号列表（头像/名称/主页入口），拉取失败可重试。 */
@Composable
fun FollowingOverlay(cvm: IwaraClientViewModel, actions: IwaraNavActions, onClose: () -> Unit) {
    val pageScope = rememberCoroutineScope()
    val session by cvm.session.collectAsStateWithLifecycle()
    var accounts by remember { mutableStateOf<List<IwaraAccount>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        if (session.userId.isBlank()) {
            error = tr("登录信息缺失，请重新登录", "Sign-in information is missing — please sign in again")
            loading = false
            return
        }
        loading = true
        error = null
        cvm.loadFollowing(session.userId, 0, scope = pageScope) { list, _, err ->
            accounts = list
            error = err
            loading = false
        }
    }
    LaunchedEffect(session.userId) { load() }

    Column(Modifier.fillMaxSize()) {
        OverlayTopBar(title = tr("我的关注", "Following"), onBack = onClose)
        Box(Modifier.weight(1f)) {
            when {
                loading && accounts.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                error != null && accounts.isEmpty() -> {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            // 普通模式显示人话，debug 开关打开时显示原样技术串
                            debugAwareErrorText(error) ?: tr("加载失败", "Failed to load"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.size(12.dp))
                        TextButton(onClick = { load() }) { Text(tr("重试", "Retry")) }
                    }
                }
                accounts.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(tr("还没有关注任何用户", "Not following anyone yet"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    val listState = rememberLazyListState()
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                accounts,
                                key = { it.id ?: it.username ?: it.name ?: it.hashCode().toString() }
                            ) { account ->
                                Card(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val avatar = IwaraClient.avatarUrl(account.avatar)
                                        if (avatar != null) {
                                            AsyncImage(
                                                model = avatar,
                                                contentDescription = null,
                                                modifier = Modifier.size(40.dp).clip(CircleShape),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            Icon(
                                                Icons.Filled.Person,
                                                contentDescription = null,
                                                modifier = Modifier.size(40.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Spacer(Modifier.size(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                account.name ?: account.username ?: tr("未知用户", "Unknown user"),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            account.username?.let { username ->
                                                Text(
                                                    "@$username",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        account.username?.let { username ->
                                            TextButton(onClick = { actions.openAccountProfile(account) }) { Text(tr("主页", "Profile")) }
                                        }
                                    }
                                }
                            }
                        }
                        FastScrollbarBasic(
                            state = listState,
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}
