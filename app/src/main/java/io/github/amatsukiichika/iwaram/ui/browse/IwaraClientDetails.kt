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

/* ---------- 图片 / 帖子详情（原 IwaraClientViewModel 方法，扩展函数形式） ---------- */
    /* ---------- 详情页 ---------- */

fun IwaraClientViewModel.loadImageDetail(imageId: String, scope: CoroutineScope? = null, onDone: (IwaraImageItem?, String?) -> Unit) {
        (scope ?: viewModelScope).launch {
            try {
                val detail = IwaraClient.call { it.image(imageId) }
                if (detail.id != null) {
                    likeStates[likeKey("image", detail.id)] = detail.liked == true
                }
                onDone(detail, null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onDone(null, describeIwaraError(t))
            }
        }
    }

    /**
     * 帖子详情（GET /post/{id}）。
     * 列表响应常不带正文与点赞/评论计数，进详情页补拉一次补齐。
     */
fun IwaraClientViewModel.loadPostDetail(postId: String, scope: CoroutineScope? = null, onDone: (IwaraPostItem?, String?) -> Unit) {
        (scope ?: viewModelScope).launch {
            try {
                val detail = IwaraClient.call { it.post(postId) }
                if (detail.id != null) {
                    likeStates[likeKey("post", detail.id)] = detail.liked == true
                }
                onDone(detail, null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onDone(null, describeIwaraError(t))
            }
        }
    }
