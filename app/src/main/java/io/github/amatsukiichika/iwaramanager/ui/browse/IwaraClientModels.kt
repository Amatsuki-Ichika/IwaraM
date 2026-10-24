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

/* ---------- 在线播放/关系计数的公开模型 + 作者 id 判据（原 IwaraClientViewModel.kt 顶层声明） ---------- */
/** 在线播放解析结果：选中地址 + 全部清晰度 + 选中档名（给播放器做档位切换）。 */
data class OnlinePlayData(
    val url: String,
    val liked: Boolean,
    val qualities: List<QualityOption>,
    val qualityName: String
)

/** 播放页「相关推荐」一次拉取的条数。 */
internal const val RELATED_VIDEOS_LIMIT = 20

/**
 * 本地视频匹配到 iwara 后，从在线详情补出的互动状态。
 * 只有当本地视频带 iwaraId 时才可能拿到；拉取失败 = 网络不可用 → 调用方把操作区置灰。
 */
data class LocalOnlineState(
    val liked: Boolean,
    /** 作者 UUID（关注接口需要）；列表数据可能缺失。 */
    val authorUserId: String?,
    /** 作者 @handle（点作者名进作者页需要）。 */
    val authorHandle: String?
)

/**
 * 作者页头部的三个计数。任一项为 null = 拿不到（离线 / 未登录 / 接口没给），
 * 由 UI 显示成「—」占位且不可点。
 */
data class IwaraRelationCounts(
    val follows: Int? = null,
    val followers: Int? = null,
    val videos: Int? = null
)

/**
 * 是否是 Iwara 的账号 UUID（作者 id）。卡片的 `user.id` 与 `/profile` 的 `user.id` 都是 36 位
 * 带连字符的 UUID；而 username 是字母数字下划线。用来判断"传进来的到底是 id 还是 handle"，
 * 从而决定要不要再打一次 profile 接口。
 */
internal fun isIwaraUserUuid(value: String): Boolean {
    val v = value.trim()
    if (v.length != 36) return false
    return v.withIndex().all { (i, c) ->
        if (i == 8 || i == 13 || i == 18 || i == 23) c == '-' else c.isDigit() || c in 'a'..'f' || c in 'A'..'F'
    }
}
