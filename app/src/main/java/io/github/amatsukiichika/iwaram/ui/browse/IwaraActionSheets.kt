package io.github.amatsukiichika.iwaram.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.amatsukiichika.iwaram.data.IwaraImageItem
import io.github.amatsukiichika.iwaram.data.IwaraVideoItem
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.components.AppBottomSheet

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
                    video.title ?: tr("视频", "Video"),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                FeedbackText(feedback)
                ActionRow(
                    icon = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    label = if (liked) tr("取消收藏", "Unfavorite") else tr("收藏", "Favorite"),
                    onClick = {
                        val id = video.id ?: return@ActionRow
                        if (!session.loggedIn) {
                            feedback = tr("请先登录后再收藏", "Sign in to favorite")
                            return@ActionRow
                        }
                        cvm.toggleLike("video", id, liked, scope = pageScope) { ok, err ->
                            feedback = when {
                                ok == true -> tr("已收藏", "Favorited")
                                ok == false -> tr("已取消收藏", "Unfavorited")
                                else -> err
                            }
                        }
                    }
                )
                ActionRow(
                    icon = Icons.Filled.Add,
                    label = tr("加入播放列表", "Add to playlist"),
                    onClick = {
                        if (!session.loggedIn) {
                            feedback = tr("请先登录后使用播放列表", "Sign in to use playlists")
                            return@ActionRow
                        }
                        cvm.loadPlaylists()
                        mode = ActionsMode.PICK_PLAYLIST
                    }
                )
                ActionRow(
                    icon = Icons.Filled.Download,
                    label = tr("下载到管理器", "Download to manager"),
                    onClick = {
                        feedback = tr("已加入下载队列，可在通知栏查看进度", "Added to the download queue — check progress in the notification")
                        cvm.downloadVideo(video) { err -> feedback = err }
                    }
                )
                ActionRow(
                    icon = Icons.Filled.PlayArrow,
                    label = tr("播放", "Play"),
                    onClick = { actions.playVideo(video) }
                )
                ActionRow(
                    icon = Icons.Filled.TextFields,
                    label = tr("评论（${video.numComments ?: 0}）", "Comments (${video.numComments ?: 0})"),
                    onClick = {
                        video.id?.let { id -> actions.openComments("video", id, video.title ?: tr("视频", "Video")) }
                    }
                )
                ActionRow(
                    icon = Icons.Filled.Person,
                    label = tr("作者主页", "Author profile"),
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
                        placeholder = { Text(tr("新播放列表标题", "New playlist title")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        if (newTitle.isNotBlank()) {
                            cvm.createPlaylist(newTitle, scope = pageScope) { err -> feedback = err ?: tr("已创建", "Created") }
                            newTitle = ""
                        }
                    }) { Text(tr("创建", "Create")) }
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
                                feedback = if (err == null) tr("已加入「${pl.title}」", "Added to ${pl.title}") else err
                            }
                        }) { Text(pl.title ?: tr("未命名", "Untitled")) }
                    }
                }
                if (playlists.loading) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
                if (playlists.loaded && playlists.items.isEmpty()) {
                    Text(
                        tr("还没有播放列表，先在上面创建一个吧", "No playlists yet — create one above"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                TextButton(
                    onClick = { mode = ActionsMode.MAIN },
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) { Text(tr("返回", "Back")) }
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
            image.title ?: tr("图片", "Image"),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        FeedbackText(feedback)
        ActionRow(
            icon = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            label = if (liked) tr("取消收藏", "Unfavorite") else tr("收藏", "Favorite"),
            onClick = {
                val id = image.id ?: return@ActionRow
                if (!session.loggedIn) {
                    feedback = tr("请先登录后再收藏", "Sign in to favorite")
                    return@ActionRow
                }
                cvm.toggleLike("image", id, liked, scope = pageScope) { ok, err ->
                    feedback = when {
                        ok == true -> tr("已收藏", "Favorited")
                        ok == false -> tr("已取消收藏", "Unfavorited")
                        else -> err
                    }
                }
            }
        )
        ActionRow(
            icon = Icons.Filled.TextFields,
            label = tr("评论（${image.numComments ?: 0}）", "Comments (${image.numComments ?: 0})"),
            onClick = {
                image.id?.let { id -> actions.openComments("image", id, image.title ?: tr("图片", "Image")) }
            }
        )
        ActionRow(
            icon = Icons.Filled.Person,
            label = tr("作者主页", "Author profile"),
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
