package io.github.amatsukiichika.iwaram.ui.browse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.amatsukiichika.iwaram.DownloadService
import io.github.amatsukiichika.iwaram.IwaraApp
import io.github.amatsukiichika.iwaram.data.IwaraAccount
import io.github.amatsukiichika.iwaram.data.IwaraClient
import io.github.amatsukiichika.iwaram.data.IwaraComment
import io.github.amatsukiichika.iwaram.data.IwaraCommentBody
import io.github.amatsukiichika.iwaram.data.IwaraFollowEdge
import io.github.amatsukiichika.iwaram.data.IwaraImageItem
import io.github.amatsukiichika.iwaram.data.IwaraPlaylist
import io.github.amatsukiichika.iwaram.data.IwaraPlaylistBody
import io.github.amatsukiichika.iwaram.data.IwaraPostItem
import io.github.amatsukiichika.iwaram.data.IwaraProfileData
import io.github.amatsukiichika.iwaram.data.IwaraSession
import io.github.amatsukiichika.iwaram.data.IwaraSign
import io.github.amatsukiichika.iwaram.data.IwaraTag
import io.github.amatsukiichika.iwaram.data.IwaraVideoItem
import io.github.amatsukiichika.iwaram.data.VideoEntity
import io.github.amatsukiichika.iwaram.data.describeIwaraError
import io.github.amatsukiichika.iwaram.data.ensureIwaraSuccess
import io.github.amatsukiichika.iwaram.data.GlobalFilterHolder
import io.github.amatsukiichika.iwaram.data.parseIwaraDate
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.DiagLog
import io.github.amatsukiichika.iwaram.ui.QualityOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/* ---------- 评论域（原 IwaraClientViewModel 方法，扩展函数形式） ---------- */
fun IwaraClientViewModel.openComments(type: String, id: String, title: String) {
        commentsByTarget[commentsKey(type, id)] =
            IwaraClientViewModel.CommentsState(type = type, targetId = id, title = title)
        loadMoreComments(type, id, force = true)
    }

fun IwaraClientViewModel.loadMoreComments(type: String, targetId: String, force: Boolean = false) {
        val key = commentsKey(type, targetId)
        val current = commentsByTarget[key] ?: return
        if (current.loading || current.targetId.isBlank()) return
        if (!force && current.endReached) return
        val page = if (force) 0 else current.page + 1
        commentsByTarget[key] = current.copy(loading = true, error = if (force) null else current.error)
        viewModelScope.launch {
            try {
                val items = IwaraClient.call {
                    it.comments(type, targetId, page, IwaraClientViewModel.PAGE_LIMIT, null).results.orEmpty()
                }
                // 面板已关闭或账号已切换：条目没了就丢弃结果，绝不把旧账号/旧目标的列表写回来。
                val snapshot = commentsByTarget[key] ?: return@launch
                commentsByTarget[key] = snapshot.copy(
                    items = if (page == 0) items else snapshot.items + items,
                    page = page,
                    loading = false,
                    endReached = items.size < IwaraClientViewModel.PAGE_LIMIT
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val snapshot = commentsByTarget[key] ?: return@launch
                commentsByTarget[key] = snapshot.copy(loading = false, error = describeIwaraError(t))
            }
        }
    }

fun IwaraClientViewModel.loadReplies(
        comment: IwaraComment,
        type: String,
        targetId: String,
        scope: CoroutineScope? = null,
        onDone: (List<IwaraComment>, String?) -> Unit
    ) {
        (scope ?: viewModelScope).launch {
            try {
                val items = IwaraClient.call {
                    it.comments(type, targetId, 0, IwaraClientViewModel.PAGE_LIMIT, comment.id)
                        .results.orEmpty()
                }
                onDone(items, null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onDone(emptyList(), describeIwaraError(t))
            }
        }
    }

fun IwaraClientViewModel.setReplyTo(type: String, targetId: String, comment: IwaraComment?) {
        val key = commentsKey(type, targetId)
        val current = commentsByTarget[key] ?: return
        commentsByTarget[key] = current.copy(replyTo = comment)
    }

fun IwaraClientViewModel.sendComment(
        type: String,
        targetId: String,
        text: String,
        scope: CoroutineScope? = null,
        onDone: (String?) -> Unit
    ) {
        val key = commentsKey(type, targetId)
        val current = commentsByTarget[key] ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        if (!_session.value.loggedIn) {
            onDone(tr("请先登录后再评论", "Sign in to comment"))
            return
        }
        commentsByTarget[key] = current.copy(sending = true)
        (scope ?: viewModelScope).launch {
            try {
                IwaraClient.call {
                    it.postComment(
                        type,
                        targetId,
                        IwaraCommentBody(body = body, parentId = current.replyTo?.id)
                    )
                }.ensureIwaraSuccess()
                // 服务端返回体不含评论对象，直接重载该目标的第一页以展示新评论。
                commentsByTarget[key]?.let { commentsByTarget[key] = it.copy(sending = false, replyTo = null) }
                loadMoreComments(type, targetId, force = true)
                onDone(null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                commentsByTarget[key]?.let { commentsByTarget[key] = it.copy(sending = false) }
                onDone(describeIwaraError(t))
            }
        }
    }

fun IwaraClientViewModel.deleteComment(
        type: String,
        targetId: String,
        commentId: String,
        scope: CoroutineScope? = null,
        onDone: (String?) -> Unit
    ) {
        val key = commentsKey(type, targetId)
        (scope ?: viewModelScope).launch {
            try {
                IwaraClient.call { it.deleteComment(commentId) }.ensureIwaraSuccess()
                commentsByTarget[key]?.let { snapshot ->
                    commentsByTarget[key] = snapshot.copy(items = snapshot.items.filterNot { it.id == commentId })
                }
                onDone(null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onDone(describeIwaraError(t))
            }
        }
    }
