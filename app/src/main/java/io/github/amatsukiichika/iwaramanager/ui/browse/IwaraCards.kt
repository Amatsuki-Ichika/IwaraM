package io.github.amatsukiichika.iwaramanager.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.amatsukiichika.iwaramanager.data.IwaraAccount
import io.github.amatsukiichika.iwaramanager.data.IwaraClient
import io.github.amatsukiichika.iwaramanager.data.IwaraImageItem
import io.github.amatsukiichika.iwaramanager.data.IwaraPostItem
import io.github.amatsukiichika.iwaramanager.data.IwaraVideoItem
import io.github.amatsukiichika.iwaramanager.data.parseIwaraDate
import io.github.amatsukiichika.iwaramanager.ui.VideoCardContent
import io.github.amatsukiichika.iwaramanager.ui.VideoCardModel
import io.github.amatsukiichika.iwaramanager.ui.normalizeCardText

/** 在线视频卡片的标准接线（各信息流共用）：点击播放、长按动作面板、点作者就地筛选、点 tag 就地筛选。 */
@Composable
fun OnlineVideoCard(video: IwaraVideoItem, actions: IwaraNavActions) {
    VideoCardOnline(
        video = video,
        onClick = { actions.playVideo(video) },
        onLongClick = { actions.openVideoActions(video) },
        onAuthorClick = { video.user?.let { u -> actions.openAccountProfile(u) } },
        onTagClick = { actions.openTagVideos(it) }
    )
}

/** 未登录占位（订阅/收藏/互动等功能）。 */
@Composable
fun LoginRequiredPanel(hint: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.size(12.dp))
        Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "请到「我的」页登录 Iwara 账号",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/* ---------- 卡片 ---------- */

/**
 * 在线视频卡片：把 [IwaraVideoItem] 映射成 [VideoCardModel] 后交给 **同一个** [VideoCardContent] 渲染。
 *
 * 与本地卡片的差别只剩两处，都是**数据**层面的：
 * 1. 缩略图走 iwara 封面 URL；
 * 2. **显示**左上角「本地」标志 —— 当本地库已有该 iwaraId 时（判据 [LocalDownloadedIwaraIds]）。
 */
@Composable
fun VideoCardOnline(
    video: IwaraVideoItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onAuthorClick: () -> Unit = {},
    onTagClick: (String) -> Unit = {},
    showTags: Boolean = true
) {
    val downloaded = video.id != null && LocalDownloadedIwaraIds.current.contains(video.id)
    VideoCardContent(
        model = VideoCardModel(
            thumbnail = IwaraClient.thumbnailUrl(video.file?.id),
            title = normalizeCardText(video.title ?: "（无标题）"),
            author = normalizeCardText(video.user?.name ?: video.user?.username.orEmpty()),
            tags = if (showTags) {
                video.tags.orEmpty().map { normalizeCardText(it.id) }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            },
            rating = video.rating,
            showR18 = LocalShowR18Tag.current,
            showLocalBadge = downloaded,
            // 封面左下角日期胶囊（在线列表 DTO 自带 createdAt）。
            createdAtMs = parseIwaraDate(video.createdAt)
        ),
        onClick = onClick,
        onTagClick = onTagClick,
        onLongClick = onLongClick,
        // 拿不到作者对象时作者行不是可点组件。
        onAuthorClick = if (video.user != null) onAuthorClick else null,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun AuthorRow(user: IwaraAccount?, onClick: () -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(enabled = user != null, onClick = onClick)
    ) {
        // 有头像/无头像都占同一 18dp 槽位，避免同一排卡片作者行高度参差。
        val avatar = IwaraClient.avatarUrl(user?.avatar)
        if (avatar != null) {
            AsyncImage(
                model = avatar,
                contentDescription = null,
                modifier = Modifier.size(18.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.size(4.dp))
        Text(
            normalizeCardText(user?.name ?: user?.username).ifEmpty { "未知作者" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatText(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageCardOnline(
    image: IwaraImageItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    /** 作者名可点（就地按作者筛选），默认不响应以兼容旧调用点。 */
    onAuthorClick: () -> Unit = {}
) {
    Card(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(3f / 4f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                // 封面字段是 thumbnail（回退 file），只读 file 会让图片板块整片白板。
                val thumb = IwaraClient.imageThumbnailUrl(image.thumbRef)
                if (thumb != null) {
                    AsyncImage(
                        model = thumb,
                        contentDescription = image.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                // 图集多图角标（numImages 缺失时用 files 数量兜底）
                val total = (image.numImages ?: 0).takeIf { it > 1 } ?: image.fileRefs.size.takeIf { it > 1 } ?: 0
                if (total > 0) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.55f),
                        contentColor = Color.White,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    ) {
                        Text("$total 图", fontSize = 10.sp, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                    }
                }
            }
            Column(Modifier.padding(8.dp)) {
                Text(
                    normalizeCardText(image.title ?: "（无标题）"),
                    style = MaterialTheme.typography.bodyMedium,
                    minLines = 1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.size(4.dp))
                AuthorRow(image.user, onAuthorClick)
                // 与帖子卡片同款，点赞 / 评论 / 时间三项都展示。
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    StatText(Icons.Filled.Star, formatCount(image.numLikes ?: 0))
                    StatText(Icons.Filled.Person, formatCount(image.numComments ?: 0))
                    timeAgoLabel(image.createdAt)?.let { StatText(Icons.Filled.Subscriptions, it) }
                }
            }
        }
    }
}

@Composable
fun PostCard(
    post: IwaraPostItem,
    onClick: () -> Unit,
    /** 作者名可点（就地按作者筛选），默认不响应以兼容旧调用点。 */
    onAuthorClick: () -> Unit = {}
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                normalizeCardText(post.title ?: "（无标题）"),
                style = MaterialTheme.typography.titleSmall,
                minLines = 1,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.size(6.dp))
            AuthorRow(post.user, onAuthorClick)
            post.body?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.size(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                StatText(Icons.Filled.Star, formatCount(post.numLikes ?: 0))
                StatText(Icons.Filled.Person, formatCount(post.numComments ?: 0))
                timeAgoLabel(post.createdAt)?.let { StatText(Icons.Filled.Subscriptions, it) }
            }
        }
    }
}
