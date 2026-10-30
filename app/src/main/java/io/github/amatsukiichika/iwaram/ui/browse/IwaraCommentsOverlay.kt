package io.github.amatsukiichika.iwaram.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.amatsukiichika.iwaram.data.IwaraComment
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.i18n.trCount
import io.github.amatsukiichika.iwaram.ui.FastScrollbarBasic
import io.github.amatsukiichika.iwaram.ui.components.debugAwareErrorText

/* ---------- 评论覆盖层与面板（原 IwaraOverlays.kt 评论区块） ---------- */
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
        OverlayTopBar(title = tr("评论 · $title", "Comments · $title"), onBack = onClose)
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
                        TextButton(onClick = { cvm.loadMoreComments(force = true) }) { Text(tr("重试", "Retry")) }
                    }
                }
            }
            if (!state.loading && state.items.isEmpty() && state.error == null) {
                item {
                    Text(
                        tr("还没有评论", "No comments yet"),
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
                    tr(
                        "回复 ${reply.user?.name ?: reply.user?.username ?: "…"}：${reply.body?.take(30) ?: ""}",
                        "Replying to ${reply.user?.name ?: reply.user?.username ?: "…"}: ${reply.body?.take(30) ?: ""}"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { cvm.setReplyTo(null) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = tr("取消回复", "Cancel reply"), modifier = Modifier.size(14.dp))
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
                                    if (state.replyTo != null) tr("回复…", "Reply…") else tr("发表评论…", "Write a comment…"),
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
                        contentDescription = tr("发送", "Send"),
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
                        comment.user?.name ?: comment.user?.username ?: tr("匿名", "Anonymous"),
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
                        Icon(Icons.Filled.Delete, contentDescription = tr("删除", "Delete"), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
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
                    tr("回复", "Reply"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onReply)
                )
                val n = comment.numReplies ?: 0
                if (n > 0) {
                    Text(
                        if (replies == null) "View $n " + trCount(n, "条回复", "reply", "replies") else tr("收起回复", "Hide replies"),
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
                            reply.user?.name ?: reply.user?.username ?: tr("匿名", "Anonymous"),
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
