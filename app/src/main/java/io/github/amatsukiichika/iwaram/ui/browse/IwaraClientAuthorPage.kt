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
import io.github.amatsukiichika.iwaram.data.IwaraBrowseService
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
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/* ---------- 作者页域（原 IwaraClientViewModel 方法，扩展函数形式） ---------- */

/* ---------- 作者页请求域：并发闸门 + 资料请求重试 ---------- */

/**
 * 作者页最多同时占用 2 条连接。
 *
 * 一次作者页会同时想发：资料 1 个 + 头部计数 3 个 + 关注打底一串 + 各分段分页。
 * OkHttp 每主机只复用 5 条连接，全并发会把连接池顶满、让分页在后面排队；
 * 限成 2 条后资料/计数不再和分段抢连接，最坏只是慢一轮。
 */
private val authorPageRequests = Semaphore(2)

/** 作者页网络请求统一入口：在并发闸门内走 [IwaraClient.call]（保留主/备域回退）。 */
private suspend fun <T> authorPageCall(block: suspend (IwaraBrowseService) -> T): T =
    authorPageRequests.withPermit { IwaraClient.call(block) }

/** 资料请求最多尝试次数：首次 + 可重试错误重试 1 次。 */
private const val PROFILE_MAX_ATTEMPTS = 2

/** 重试前等一小会儿：429/5xx 多是瞬时故障，立刻重试容易再撞同一状态。 */
private const val PROFILE_RETRY_DELAY_MS = 500L

/**
 * 拉作者资料：429 / 5xx 重试一次，其余错误（401/404 等）直接抛给调用方。
 * 判定逻辑 [retryableProfileError] 在 IwaraProfileTarget.kt（有单测）。
 */
private suspend fun requestIwaraProfile(handle: String): IwaraProfileData {
    var attempt = 1
    while (true) {
        try {
            return authorPageCall { it.profile(handle) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (attempt >= PROFILE_MAX_ATTEMPTS) throw t
            attempt++
            if (!retryableProfileError((t as? retrofit2.HttpException)?.code())) throw t
            delay(PROFILE_RETRY_DELAY_MS)
        }
    }
}

/* ---------- 关注列表打底失败冷却 ---------- */

/** 打底失败记录：哪个账号、何时失败（按 ViewModel 实例隔离，换账号不受旧冷却影响）。 */
private data class FollowSeedFailure(val userId: String, val atMs: Long)

private val followSeedFailures = WeakHashMap<IwaraClientViewModel, FollowSeedFailure>()

/** 读失败时间；账号变了（userId 不同）当作没失败过。 */
private fun IwaraClientViewModel.followSeedFailureAt(userId: String): Long? =
    synchronized(followSeedFailures) {
        followSeedFailures[this]?.takeIf { it.userId == userId }?.atMs
    }

private fun IwaraClientViewModel.recordFollowSeedFailure(userId: String) {
    synchronized(followSeedFailures) {
        followSeedFailures[this] = FollowSeedFailure(userId, System.currentTimeMillis())
    }
}

private fun IwaraClientViewModel.clearFollowSeedFailure() {
    synchronized(followSeedFailures) { followSeedFailures.remove(this) }
}

/**
 * 关注列表打底：最多翻 5 页「我关注了谁」记进 [IwaraClientViewModel.followStates]，
 * 否则已关注的作者会先显示成「关注」。
 *
 * 并发调用只真正发一轮请求（single-flight）；失败后 5 分钟内不再重试
 * （[FOLLOW_SEED_RETRY_COOLDOWN_MS]），冷却过后自动允许重来 —— 失败不会把关注按钮永久卡死。
 */
internal suspend fun IwaraClientViewModel.ensureFollowStatesSeeded() {
    if (followSeeded) return
    val myId = _session.value.userId.takeIf { it.isNotBlank() } ?: return
    if (!followSeedRetryAllowed(followSeedFailureAt(myId), System.currentTimeMillis())) return
    followSeedMutex.withLock {
        // 等锁期间可能已经打底完成 / 刚失败进冷却：再判一次，保证并发只真正发一轮。
        if (followSeeded) return
        if (!followSeedRetryAllowed(followSeedFailureAt(myId), System.currentTimeMillis())) return
        runCatching {
            for (page in 0 until IwaraClientViewModel.FOLLOW_SEED_MAX_PAGES) {
                val edges: List<IwaraFollowEdge> = authorPageCall {
                    it.following(myId, page, IwaraClientViewModel.PAGE_LIMIT).results.orEmpty()
                }
                edges.mapNotNull { e -> e.asAccount()?.id }.forEach { id -> followStates[id] = true }
                // 不满一页说明到底了（与 loadFollowing 的判定一致）。
                if (edges.size < IwaraClientViewModel.PAGE_LIMIT) break
            }
            followSeeded = true
            clearFollowSeedFailure()
        }.onFailure {
            // 取消原样上抛：打底只是尽力而为，但不能把取消伪装成"打底失败"记进日志。
            if (it is CancellationException) throw it
            recordFollowSeedFailure(myId)
            DiagLog.log("Follow", "关注列表打底失败: ${it.message ?: it.javaClass.simpleName}")
        }
    }
}

/**
 * 作者页「资料 + 关注打底」。
 *
 * [target] 是归一后的作者参数（见 [iwaraProfileTargetOf]）：只有 UUID 时不打 /profile，
 * 直接回调 null（页面用 UUID 拉内容）；只有 handle 时打一次资料（429/5xx 自动重试 1 次）。
 *
 * 打底与资料请求**互不依赖**：资料 404 / 网络失败时打底照常跑，关注按钮不会永久显示
 * 「关注」；资料成功后等打底结束再写这一条 uid，避免已关注先闪成未关注。
 */
fun IwaraClientViewModel.loadProfile(
    target: IwaraProfileTarget,
    scope: CoroutineScope? = null,
    onDone: (IwaraProfileData?, String?) -> Unit
) {
    (scope ?: viewModelScope).launch {
        if (target.unavailable) {
            onDone(null, null)
            return@launch
        }
        // 打底是全页共享状态：挂到 viewModelScope，关掉页面也让它跑完。
        val seed = viewModelScope.launch { ensureFollowStatesSeeded() }
        val handle = target.handle
        if (handle == null) {
            // 只有 UUID：不用查资料；页面用 contentId 拉内容，打底在后台补齐关注态。
            target.uid?.let { followStates[it] = followStates[it] ?: false }
            onDone(null, null)
            return@launch
        }
        try {
            val profile = requestIwaraProfile(handle)
            seed.join()
            (profile.uid ?: target.uid)?.let { followStates[it] = followStates[it] ?: false }
            onDone(profile, null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            onDone(null, describeIwaraError(t))
        }
    }
}

/**
 * 兼容旧调用点（只给 @handle）：按 [iwaraProfileTargetOf] 归一后走上面的重载。
 * 空 handle ⇒ 目标不可用，直接回调 null，不再白发一次请求。
 */
fun IwaraClientViewModel.loadProfile(username: String, scope: CoroutineScope? = null, onDone: (IwaraProfileData?, String?) -> Unit) {
    loadProfile(iwaraProfileTargetOf(username, userId = null), scope, onDone)
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
                    authorPageCall {
                        it.videos(page, IwaraClientViewModel.PAGE_LIMIT, sort, _rating.value, null, userId).results.orEmpty()
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    authorPageCall {
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
                val edges: List<IwaraFollowEdge> = authorPageCall {
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
                val items = authorPageCall {
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
                    authorPageCall {
                        it.postsByUser(userId, page, IwaraClientViewModel.PAGE_LIMIT, sort, _rating.value).results.orEmpty()
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    authorPageCall {
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
                val edges: List<IwaraFollowEdge> = authorPageCall {
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
     *
     * 三项**串行**：每项各占一条连接位，并发会和资料 / 打底抢连接；每一项失败都静默
     * （该项 null），不向页面报错 —— 计数拿不到不影响看内容。
     */
fun IwaraClientViewModel.loadUserCounts(userId: String, scope: CoroutineScope? = null, onDone: (IwaraRelationCounts) -> Unit) {
        (scope ?: viewModelScope).launch {
            suspend fun one(block: suspend (IwaraBrowseService) -> Int?): Int? =
                try {
                    authorPageCall { block(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    null
                }
            val follows = one { it.following(userId, 0, 1).count }
            val followers = one { it.followers(userId, 0, 1).count }
            val videos = one { it.videos(0, 1, null, _rating.value, null, userId).count }
            onDone(IwaraRelationCounts(follows, followers, videos))
        }
    }
