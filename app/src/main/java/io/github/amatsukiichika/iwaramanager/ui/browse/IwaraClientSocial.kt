package io.github.amatsukiichika.iwaramanager.ui.browse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.amatsukiichika.iwaramanager.DownloadService
import io.github.amatsukiichika.iwaramanager.IwaraApp
import io.github.amatsukiichika.iwaramanager.data.IwaraAccount
import io.github.amatsukiichika.iwaramanager.data.IwaraClient
import io.github.amatsukiichika.iwaramanager.data.IwaraComment
import io.github.amatsukiichika.iwaramanager.data.IwaraCommentBody
import io.github.amatsukiichika.iwaramanager.data.IwaraFollowEdge
import io.github.amatsukiichika.iwaramanager.data.IwaraImageItem
import io.github.amatsukiichika.iwaramanager.data.IwaraPlaylist
import io.github.amatsukiichika.iwaramanager.data.IwaraPlaylistBody
import io.github.amatsukiichika.iwaramanager.data.IwaraPostItem
import io.github.amatsukiichika.iwaramanager.data.IwaraProfileData
import io.github.amatsukiichika.iwaramanager.data.IwaraSession
import io.github.amatsukiichika.iwaramanager.data.IwaraSign
import io.github.amatsukiichika.iwaramanager.data.IwaraTag
import io.github.amatsukiichika.iwaramanager.data.IwaraVideoItem
import io.github.amatsukiichika.iwaramanager.data.VideoEntity
import io.github.amatsukiichika.iwaramanager.data.describeIwaraError
import io.github.amatsukiichika.iwaramanager.data.ensureIwaraSuccess
import io.github.amatsukiichika.iwaramanager.data.GlobalFilterHolder
import io.github.amatsukiichika.iwaramanager.data.parseIwaraDate
import io.github.amatsukiichika.iwaramanager.ui.DiagLog
import io.github.amatsukiichika.iwaramanager.ui.QualityOption
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
            onDone(null, "请先登录后再点赞")
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
            onDone(null, "请先登录后再关注")
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
