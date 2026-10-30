package io.github.amatsukiichika.iwaram.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.amatsukiichika.iwaram.data.GlobalFilterHolder
import io.github.amatsukiichika.iwaram.data.IwaraPlaylist
import io.github.amatsukiichika.iwaram.data.filterBy
import io.github.amatsukiichika.iwaram.data.videoKeys
import io.github.amatsukiichika.iwaram.ui.FastScrollbarBasic
import io.github.amatsukiichika.iwaram.ui.components.debugAwareErrorText
import kotlinx.coroutines.launch

/* ---------- 播放列表覆盖层与详情（原 IwaraOverlays.kt 播放列表区块） ---------- */
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
