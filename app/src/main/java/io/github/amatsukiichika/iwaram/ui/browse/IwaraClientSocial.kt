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

/* ---------- 点赞 / 关注（原 IwaraClientViewModel 方法，扩展函数形式） ---------- */
fun IwaraClientViewModel.likeKey(type: String, id: String) = "$type:$id"

fun IwaraClientViewModel.toggleLike(type: String, id: String, currentlyLiked: Boolean, scope: CoroutineScope? = null, onDone: (Boolean?, String?) -> Unit) {
        if (!_session.value.loggedIn) {
            onDone(null, tr("请先登录后再点赞", "Sign in to like"))
            return
        }
        (scope ?: viewModelScope).launch {
            try {
                // 写操作固定走主域 apiq（备用域 api 的 like 假成功），204 空体也算成功。
                IwaraClient.callPrimary {
                    if (currentlyLiked) it.unlike(type, id) else it.like(type, id)
                }.ensureIwaraSuccess()
                likeStates[likeKey(type, id)] = !currentlyLiked
                onDone(!currentlyLiked, null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onDone(null, describeIwaraError(t))
            }
        }
    }

fun IwaraClientViewModel.toggleFollow(userId: String, currentlyFollowing: Boolean, scope: CoroutineScope? = null, onDone: (Boolean?, String?) -> Unit) {
        if (!_session.value.loggedIn) {
            onDone(null, tr("请先登录后再关注", "Sign in to follow"))
            return
        }
        (scope ?: viewModelScope).launch {
            try {
                IwaraClient.callPrimary {
                    if (currentlyFollowing) it.unfollow(userId) else it.follow(userId)
                }.ensureIwaraSuccess()
                followStates[userId] = !currentlyFollowing
                onDone(!currentlyFollowing, null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onDone(null, describeIwaraError(t))
            }
        }
    }
