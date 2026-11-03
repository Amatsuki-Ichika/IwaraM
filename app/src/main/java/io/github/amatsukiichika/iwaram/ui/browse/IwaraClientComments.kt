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
        _comments.value = IwaraClientViewModel.CommentsState(type = type, targetId = id, title = title)
        loadMoreComments(force = true)
    }

fun IwaraClientViewModel.loadMoreComments(force: Boolean = false) {
        val current = _comments.value
        if (current.loading || current.targetId.isBlank()) return
        if (!force && current.endReached) return
        val page = if (force) 0 else current.page + 1
        _comments.value = current.copy(loading = true, error = if (force) null else current.error)
        viewModelScope.launch {
            try {
                val items = IwaraClient.call {
                    it.comments(current.type, current.targetId, page, IwaraClientViewModel.PAGE_LIMIT, null).results.orEmpty()
                }
                val snapshot = _comments.value
                _comments.value = snapshot.copy(
                    items = if (page == 0) items else snapshot.items + items,
                    page = page,
                    loading = false,
                    endReached = items.size < IwaraClientViewModel.PAGE_LIMIT
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _comments.value = _comments.value.copy(loading = false, error = describeIwaraError(t))
            }
        }
    }

fun IwaraClientViewModel.loadReplies(comment: IwaraComment, scope: CoroutineScope? = null, onDone: (List<IwaraComment>, String?) -> Unit) {
        (scope ?: viewModelScope).launch {
            try {
                val items = IwaraClient.call {
                    it.comments(_comments.value.type, _comments.value.targetId, 0, IwaraClientViewModel.PAGE_LIMIT, comment.id)
                        .results.orEmpty()
                }
                onDone(items, null)
            } catch (t: Throwable) {
                onDone(emptyList(), describeIwaraError(t))
            }
        }
    }

fun IwaraClientViewModel.setReplyTo(comment: IwaraComment?) {
        _comments.value = _comments.value.copy(replyTo = comment)
    }

fun IwaraClientViewModel.sendComment(text: String, scope: CoroutineScope? = null, onDone: (String?) -> Unit) {
        val current = _comments.value
        val body = text.trim()
        if (body.isEmpty()) return
        if (!_session.value.loggedIn) {
            onDone(tr("请先登录后再评论", "Sign in to comment"))
            return
        }
        _comments.value = current.copy(sending = true)
        (scope ?: viewModelScope).launch {
            try {
                IwaraClient.call {
                    it.postComment(
                        current.type,
                        current.targetId,
                        IwaraCommentBody(body = body, parentId = current.replyTo?.id)
                    )
                }.ensureIwaraSuccess()
                // 服务端返回体不含评论对象，直接重载第一页以展示新评论。
                _comments.value = _comments.value.copy(sending = false, replyTo = null)
                loadMoreComments(force = true)
                onDone(null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _comments.value = _comments.value.copy(sending = false)
                onDone(describeIwaraError(t))
            }
        }
    }

fun IwaraClientViewModel.deleteComment(commentId: String, scope: CoroutineScope? = null, onDone: (String?) -> Unit) {
        (scope ?: viewModelScope).launch {
            try {
                IwaraClient.call { it.deleteComment(commentId) }.ensureIwaraSuccess()
                _comments.value = _comments.value.copy(items = _comments.value.items.filterNot { it.id == commentId })
                onDone(null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onDone(describeIwaraError(t))
            }
        }
    }
