package io.github.amatsukiichika.iwaram.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.amatsukiichika.iwaram.data.IwaraClient
import io.github.amatsukiichika.iwaram.data.IwaraImageItem
import io.github.amatsukiichika.iwaram.data.IwaraPostItem
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.components.debugAwareErrorText

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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("返回", "Back"), tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    detail.title ?: tr("图片", "Image"),
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
                        tr("  ·  ♥ ${detail.numLikes ?: 0}  ·  评论 ${detail.numComments ?: 0}", "  ·  ♥ ${detail.numLikes ?: 0}  ·  comments ${detail.numComments ?: 0}"),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            IconButton(onClick = {
                val id = detail.id ?: return@IconButton
                if (!session.loggedIn) {
                    feedback = tr("请先登录后再点赞", "Sign in to like")
                    return@IconButton
                }
                cvm.toggleLike("image", id, liked, scope = pageScope) { _, err -> feedback = err }
            }) {
                Icon(
                    if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = tr("点赞", "Like"),
                    tint = if (liked) MaterialTheme.colorScheme.primary else Color.White
                )
            }
            IconButton(onClick = {
                detail.id?.let { id -> actions.openComments("image", id, detail.title ?: tr("图片", "Image")) }
            }) {
                Icon(Icons.Filled.TextFields, contentDescription = tr("评论", "Comments"), tint = Color.White)
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
            title = detail.title ?: tr("帖子", "Post"),
            onBack = onClose,
            trailing = {
                IconButton(onClick = {
                    val id = detail.id ?: return@IconButton
                    if (!session.loggedIn) {
                        feedback = tr("请先登录后再点赞", "Sign in to like")
                        return@IconButton
                    }
                    cvm.toggleLike("post", id, liked, scope = pageScope) { _, err -> feedback = err }
                }) {
                    Icon(
                        if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = tr("点赞", "Like"),
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
                    Text(detail.user?.name ?: detail.user?.username ?: tr("匿名", "Anonymous"), style = MaterialTheme.typography.labelLarge)
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
                detail.id?.let { id -> actions.openComments("post", id, detail.title ?: tr("帖子", "Post")) }
            }) {
                Text(tr("查看评论（${detail.numComments ?: 0}）", "View comments (${detail.numComments ?: 0})"))
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
