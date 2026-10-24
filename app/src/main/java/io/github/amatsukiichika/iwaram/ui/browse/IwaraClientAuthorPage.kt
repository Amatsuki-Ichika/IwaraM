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

/* ---------- 作者页域（原 IwaraClientViewModel 方法，扩展函数形式） ---------- */
internal suspend fun IwaraClientViewModel.ensureFollowStatesSeeded() {
        if (followSeeded) return
        val myId = _session.value.userId.takeIf { it.isNotBlank() } ?: return
        followSeedMutex.withLock {
            if (followSeeded) return
            runCatching {
                for (page in 0 until IwaraClientViewModel.FOLLOW_SEED_MAX_PAGES) {
                    val edges: List<IwaraFollowEdge> = IwaraClient.call {
                        it.following(myId, page, IwaraClientViewModel.PAGE_LIMIT).results.orEmpty()
                    }
                    edges.mapNotNull { e -> e.asAccount()?.id }.forEach { id -> followStates[id] = true }
                    // 不满一页说明到底了（与 loadFollowing 的判定一致）。
                    if (edges.size < IwaraClientViewModel.PAGE_LIMIT) break
                }
                followSeeded = true
            }.onFailure {
                // 取消原样上抛：打底只是尽力而为，但不能把取消伪装成"打底失败"记进日志。
                if (it is CancellationException) throw it
                DiagLog.log("Follow", "关注列表打底失败: ${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

fun IwaraClientViewModel.loadProfile(username: String, scope: CoroutineScope? = null, onDone: (IwaraProfileData?, String?) -> Unit) {        (scope ?: viewModelScope).launch {
            try {
                val profile = IwaraClient.call { it.profile(username) }
                // 先把"我关注了谁"拉一次打底，再写这条 uid，否则已关注的作者会显示成未关注。
                ensureFollowStatesSeeded()
                // uid 访问器兼容 {"user":{...}} 嵌套响应（扁平响应下 id 为 null）。
                profile.uid?.let { followStates[it] = followStates[it] ?: false }
                onDone(profile, null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onDone(null, describeIwaraError(t))
            }
        }
    }

    /**
     * 作者页「视频」分段：首选 `GET /videos?user=<作者 UUID>`（yt-dlp `IwaraUserIE` 使用的路径，
     * 与作者页同款），失败回退 `GET /user/{id}/content/video`。
     *
     * `content/video` 实际返回 404，故仅作回退；主路径是 `videos?user=`。
     */
fun IwaraClientViewModel.loadUserVideos(
        userId: String,
        page: Int,
        sort: String = "date",
        scope: CoroutineScope? = null,
        onDone: (List<IwaraVideoItem>, Boolean, String?) -> Unit
    ) {
        (scope ?: viewModelScope).launch {
            try {
                val items = try {
                    IwaraClient.call {
                        it.videos(page, IwaraClientViewModel.PAGE_LIMIT, sort, _rating.value, null, userId).results.orEmpty()
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    IwaraClient.call {
                        it.userContent(userId, "video", page, IwaraClientViewModel.PAGE_LIMIT, sort).results.orEmpty()
                    }
                }
                onDone(items, items.size < IwaraClientViewModel.PAGE_LIMIT, null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onDone(emptyList(), true, describeIwaraError(t))
            }
        }
    }

fun IwaraClientViewModel.loadFollowing(
        userId: String,
        page: Int,
        scope: CoroutineScope? = null,
        onDone: (List<IwaraAccount>, Boolean, String?) -> Unit
    ) {
        (scope ?: viewModelScope).launch {
            try {
                val edges: List<IwaraFollowEdge> = IwaraClient.call {
                    it.following(userId, page, IwaraClientViewModel.PAGE_LIMIT).results.orEmpty()
                }
                // 兼容多种响应形态：包装字段（user/follower）或扁平对象。
                val accounts = edges.mapNotNull { it.asAccount() }
                onDone(accounts, edges.size < IwaraClientViewModel.PAGE_LIMIT, null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onDone(emptyList(), true, describeIwaraError(t))
            }
        }
    }

    /* ---------- 作者页各分段 ---------- */

    /** 作者页「图片」分段：GET /images?user=<作者 UUID>。 */
fun IwaraClientViewModel.loadUserImages(
        userId: String,
        page: Int,
        sort: String,
        scope: CoroutineScope? = null,
        onDone: (List<IwaraImageItem>, Boolean, String?) -> Unit
    ) {
        (scope ?: viewModelScope).launch {
            try {
                val items = IwaraClient.call {
                    it.images(page, IwaraClientViewModel.PAGE_LIMIT, sort, _rating.value, null, userId).results.orEmpty()
                }
                onDone(items, items.size < IwaraClientViewModel.PAGE_LIMIT, null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onDone(emptyList(), true, describeIwaraError(t))
            }
        }
    }

    /**
     * 作者页「帖子」分段：首选 `GET /posts?user=<作者 UUID>`（与视频/图片同为 `?user=` 家族），
     * 失败自动回退 `GET /user/{id}/content/post`；两者都失败则由调用方显示空态。
     */
fun IwaraClientViewModel.loadUserPosts(
        userId: String,
        page: Int,
        sort: String,
        scope: CoroutineScope? = null,
        onDone: (List<IwaraPostItem>, Boolean, String?) -> Unit
    ) {
        (scope ?: viewModelScope).launch {
            try {
                val items = try {
                    IwaraClient.call {
                        it.postsByUser(userId, page, IwaraClientViewModel.PAGE_LIMIT, sort, _rating.value).results.orEmpty()
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    IwaraClient.call {
                        it.userContentPosts(userId, "post", page, IwaraClientViewModel.PAGE_LIMIT, sort).results.orEmpty()
                    }
                }
                onDone(items, items.size < IwaraClientViewModel.PAGE_LIMIT, null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onDone(emptyList(), true, describeIwaraError(t))
            }
        }
    }

    /** 作者页「粉丝」分段：GET /user/{id}/followers。 */
fun IwaraClientViewModel.loadUserFollowers(
        userId: String,
        page: Int,
        scope: CoroutineScope? = null,
        onDone: (List<IwaraAccount>, Boolean, String?) -> Unit
    ) {
        (scope ?: viewModelScope).launch {
            try {
                val edges: List<IwaraFollowEdge> = IwaraClient.call {
                    it.followers(userId, page, IwaraClientViewModel.PAGE_LIMIT).results.orEmpty()
                }
                val accounts = edges.mapNotNull { it.asAccount() }
                onDone(accounts, edges.size < IwaraClientViewModel.PAGE_LIMIT, null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onDone(emptyList(), true, describeIwaraError(t))
            }
        }
    }

    /**
     * 作者页头部的「关注 / 粉丝 / 视频」计数。
     *
     * `GET /profile/{username}` 常常不返回计数字段，因此用各分页端点的 `IwaraPage.count` 兜底：
     * `limit = 1` 只取一条，代价极小。任一项失败 → 该项为 null，UI 显示「—」且不可点。
     */
fun IwaraClientViewModel.loadUserCounts(userId: String, scope: CoroutineScope? = null, onDone: (IwaraRelationCounts) -> Unit) {
        (scope ?: viewModelScope).launch {
            suspend fun one(block: suspend (io.github.amatsukiichika.iwaram.data.IwaraBrowseService) -> Int?): Int? =
                try {
                    IwaraClient.call { block(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    null
                }
            // 三个计数并发请求 —— 串行要等三趟往返才出数字。
            val follows = async { one { it.following(userId, 0, 1).count } }
            val followers = async { one { it.followers(userId, 0, 1).count } }
            val videos = async { one { it.videos(0, 1, null, _rating.value, null, userId).count } }
            onDone(IwaraRelationCounts(follows.await(), followers.await(), videos.await()))
        }
    }
