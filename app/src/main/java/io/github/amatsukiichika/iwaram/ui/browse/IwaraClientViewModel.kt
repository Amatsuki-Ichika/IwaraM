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

/** Iwara 客户端模块的统一 ViewModel：会话、信息流分页、互动、播放解析、下载。 */
class IwaraClientViewModel(internal val app: IwaraApp) : ViewModel() {

    internal val repo = app.repository

    internal val gson = Gson()
    internal val playlistsCacheType = object : TypeToken<List<IwaraPlaylist>>() {}.type

    /* ---------- 会话 ---------- */
    data class SessionState(
        val loggedIn: Boolean = false,
        val checking: Boolean = true,
        val busy: Boolean = false,
        val username: String = "",
        val userId: String = "",
        val avatarUrl: String? = null,
        val message: String? = null
    )

    internal val _session = MutableStateFlow(SessionState())
    val session: StateFlow<SessionState> = _session.asStateFlow()

    init {
        // 会话恢复已在 IwaraApp.onCreate（进程起点）完成，这里只注册 listener，
        // 不再依赖浏览 tab 的组合时序。
        IwaraSession.listener = object : IwaraSession.Listener {
            override fun onSessionExpired() {
                _session.value = _session.value.copy(
                    loggedIn = false, username = "", userId = "", avatarUrl = null,
                    message = "登录已过期，请重新登录"
                )
            }
        }
        if (IwaraSession.isLoggedIn) {
            // 先用本地缓存立即显示（离线/弱网不空白转圈），再后台刷新远端资料。
            _session.value = SessionState(
                loggedIn = true,
                checking = true,
                username = IwaraSession.savedUsername().orEmpty(),
                userId = IwaraSession.savedUserId().orEmpty(),
                avatarUrl = IwaraSession.savedAvatarUrl()
            )
            viewModelScope.launch { refreshSession(loadRemote = true) }
        } else {
            _session.value = _session.value.copy(checking = false)
        }
    }

    private suspend fun refreshSession(loadRemote: Boolean) {
        val username = IwaraSession.savedUsername().orEmpty()
        val userId = IwaraSession.savedUserId().orEmpty()
        try {
            IwaraSession.ensureAccessToken()
            var avatar: String? = IwaraSession.savedAvatarUrl()
            var name = username
            var id = userId
            // /profile/{username} 回退补头像时**必须**用 @handle（不能拿昵称去查，昵称可能带空格）。
            var handle = username
            if (loadRemote) {
                // GET /user 与 /profile/{username} 同族，真实响应是嵌套的 `{"user":{...}}`；
                // 按扁平字段反序列化会得到 id/username 全 null，导致 session.userId 恒空、
                // 关注页报"登录信息缺失"、收藏/播放列表/点赞一并失效。
                // 这里复用 IwaraProfileData 的嵌套访问器（同时兼容扁平响应）。
                val me: IwaraProfileData? = try {
                    IwaraClient.call { it.me() }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    // 头像/登录信息回退本地缓存是预期行为，但失败要留痕（网络/权限问题归因）。
                    DiagLog.log("Browse", "拉取当前用户信息失败，回退本地缓存: ${e.javaClass.simpleName}")
                    null
                }
                if (me != null) {
                    val account = me.asAccount()
                    // 展示名优先取昵称（与本地页"过滤：<昵称>"一致），handle 单独留作接口参数。
                    handle = account.username ?: handle
                    name = account.name ?: account.username ?: name
                    id = account.id ?: id
                    avatar = IwaraClient.avatarUrl(account.avatar)
                    // 把远端最新的账号标识写回，保证关注/评论/播放列表等需要 userId 的功能可用。
                    IwaraSession.saveUserInfo(name, id)
                }
                // /user 偶发带不回头像时回退个人主页端点补拉。
                // 登录兜底可能把邮箱存成 username，profile(邮箱) 必 404，跳过。
                if (avatar.isNullOrBlank() && handle.isNotBlank() && !handle.contains("@")) {
                    avatar = runCatching {
                        IwaraClient.avatarUrl(IwaraClient.call { it.profile(handle) }.avatarRef)
                    }.getOrNull()
                }
                if (!avatar.isNullOrBlank()) IwaraSession.saveUserInfo(name, id, avatar)
            }
            _session.value = _session.value.copy(
                loggedIn = true,
                checking = false,
                username = name,
                userId = id,
                avatarUrl = avatar,
                message = null
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // 网络不可用等临时故障：保留已显示的缓存内容继续用；
            // token 彻底失效时 IwaraSession 会清空并回调 onSessionExpired 回到登录页。
            _session.value = _session.value.copy(checking = false, message = describeIwaraError(t))
        }
    }

    /** 「我的」页可见时补拉一次资料（头像缺失等场景的兜底刷新）。 */
    fun refreshMe() {
        viewModelScope.launch { refreshSession(loadRemote = true) }
    }

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _session.value = _session.value.copy(message = "请填写邮箱和密码")
            return
        }
        viewModelScope.launch {
            _session.value = _session.value.copy(busy = true, message = null)
            try {
                IwaraSession.login(email, password)
                refreshSession(loadRemote = true)
                _session.value = _session.value.copy(busy = false, loggedIn = true)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _session.value = _session.value.copy(busy = false, message = describeIwaraError(t))
            }
        }
    }

    fun logout() {
        IwaraSession.logout()
        _session.value = SessionState(checking = false, loggedIn = false)
        // 登出后订阅流数据不再可信（可能属于上一个账号），下次登录强制重拉。
        subscribedTrusted = false
        followStates.clear()
        // 打底标记一并复位：换账号登录后必须重新拉一次"我关注了谁"。
        followSeeded = false
        likeStates.clear()
    }

    fun dismissSessionMessage() {
        _session.value = _session.value.copy(message = null)
    }

    /* ---------- 信息流通用状态 ----------
     * FeedState / 分页加载逻辑在 FeedPager.kt：7 条信息流统一走 FeedPager，
     * force 刷新会取消进行中的任务并从第 0 页重拉（顶栏刷新最高优先级）。 */

    /* ---------- 视频页顶部分段 ---------- */

    /** 视频页顶部分类（订阅/视频/图片/帖子）。提升到 VM：切 tab 不丢、顶栏刷新与播放页作者筛选可感知。 */
    var videoSegment by mutableStateOf(Segment.VIDEOS)
        private set

    fun selectVideoSegment(segment: Segment) {
        if (videoSegment == segment) return
        videoSegment = segment
        // 切段即清筛选，避免上一段的搜索/tag/作者过滤状态串进新段。
        clearSearch("切分段")
    }

    /* ---------- 视频流（首页） ---------- */

    private val videosPager = FeedPager<IwaraVideoItem>(viewModelScope, PAGE_LIMIT, OnlineIdentity::video, ::describeIwaraError)
    val videos: StateFlow<FeedState<IwaraVideoItem>> = videosPager.state

    /** 视频流排序：直通仓库单一真源流（窄出口，不自持镜像）。 */
    val videoSort: StateFlow<String> = repo.videoSort

    /**
     * 默认 R18（设置项）：开启后隐藏分级筛选并强制只看 R18。
     *
     * 直通仓库的单一真源（`repo.defaultR18`），不保留内存副本；
     * 否则设置页与 Iwara 页各持一份、互不通知，改动要重启 App 才生效。
     */
    val defaultR18: StateFlow<Boolean> = repo.defaultR18

    /**
     * **当前生效**的分级（所有请求统一用它）：默认 R18 开启时恒为 "ecchi"。
     * 这样各请求点无需感知设置项，关闭设置后自动回到手选值（仓库 [IwaraRepository.rating]）。
     */
    internal val _rating = MutableStateFlow(if (repo.defaultR18.value) "ecchi" else repo.rating.value)
    val rating: StateFlow<String> = _rating.asStateFlow()

    init {
        viewModelScope.launch {
            // StateFlow 首帧必为当前值，跳过；之后的每次变更重算生效分级并重拉当前流。
            repo.defaultR18.drop(1).collect {
                syncEffectiveRating()
                // 分级变化会影响所有按分级过滤的流 → 强制重拉当前可见的那条。
                refreshIwaraTab()
            }
        }
    }

    private fun syncEffectiveRating() {
        _rating.value = if (repo.defaultR18.value) "ecchi" else repo.rating.value
    }

    /** 只写单一真源，生效分级由上面的订阅回调更新（本地库共用同一份）。 */
    fun setDefaultR18(value: Boolean) {
        repo.setDefaultR18(value)
    }

    /**
     * 把一次在线列表/搜索请求的等效 URL 与结果条数写进诊断日志。
     *
     * 用于区分两类根因：
     * 1. URL 变了（分级 / 排序 / tag 被改过）⇒ 本地参数问题，可直接修；
     * 2. URL 相同但返回里少了某条 ⇒ 上游按账号态过滤，客户端只能补救。
     * 同时带上全局筛选器的开关状态，排除"被筛选器藏起来"的可能。
     */
    private fun logFeedRequest(label: String, url: String, items: List<IwaraVideoItem>) {
        val filter = GlobalFilterHolder.value
        DiagLog.log(
            "Iwara",
            "列表[$label] $url → ${items.size} 条 首条=${items.firstOrNull()?.id ?: "-"}" +
                " 全局筛选器=${if (filter.effective) "开" else "关"}"
        )
    }

    fun loadMoreVideos(force: Boolean = false) {
        videosPager.load(force) { page ->
            val sort = videoSort.value
            val rating = _rating.value
            val items = IwaraClient.call {
                it.videos(page, PAGE_LIMIT, sort, rating, null, null).results.orEmpty()
            }
            logFeedRequest("视频", "videos?page=$page&limit=$PAGE_LIMIT&sort=$sort&rating=$rating", items)
            items
        }
    }

    fun setVideoSort(sort: String) {
        if (sort == videoSort.value) return
        repo.setVideoSort(sort)
        loadMoreVideos(force = true)
    }

    fun setRating(rating: String) {
        if (rating == repo.rating.value) return
        repo.setRating(rating)
        syncEffectiveRating()
        loadMoreVideos(force = true)
    }

    /* ---------- 订阅流 ---------- */

    private val subscribedPager = FeedPager<IwaraVideoItem>(viewModelScope, PAGE_LIMIT, OnlineIdentity::video, ::describeIwaraError)
    val subscribed: StateFlow<FeedState<IwaraVideoItem>> = subscribedPager.state

    /**
     * 当前订阅流数据是否是在有效凭证下取得的。
     * false ⇒ 下一次加载强制从第 0 页重拉（自愈），避免首批数据错误后一直往后翻页、
     * 把错内容留在列表里。
     */
    private var subscribedTrusted = false

    /**
     * 订阅流（`GET /videos?subscribed=true`）。
     *
     * 只检查 `_session.value.loggedIn`（本地存有 refresh token）不足以判断凭证有效：
     * 冷启动时 init 先同步置 loggedIn=true、再异步续期，恢复出来的是上次会话留下的
     * 过期凭证；`authHeaders()` 只注入不续期，`refreshOn401()` 仅在服务器回 401 时补救。
     * api.iwara.tv 对"凭证无效 + subscribed=true"会静默忽略该过滤并返回全站最新内容，
     * 且不报错 —— 表现为订阅页显示成普通视频流。
     *
     * 因此这里在凭证层拦截：拿不到有效凭证就不发请求、落错误态（面板提供「重试」），
     * 宁可明着报错也不静默返回错内容。
     *
     * 续期固定走 [IwaraClient.subscriptionBase]，避免凭证与请求打到不同服务端。
     */
    fun loadMoreSubscribed(force: Boolean = false) {
        if (!_session.value.loggedIn) {
            // 登出/会话失效后数据不再可信：下次登录必须重新拉一遍。
            subscribedTrusted = false
            return
        }
        // 自愈：上一批若不是在有效凭证下拿到的，一律视为不可信，强制从第 0 页重拉。
        subscribedPager.load(force || !subscribedTrusted) { page ->
            val token = try {
                IwaraSession.ensureAccessToken(base = IwaraClient.subscriptionBase)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // 只有"续期这一步失败"会走到这里，两类要分开说：
                //  · 凭证被服务器拒绝（IwaraApiException）⇒ 换一句能对上账号状态的话；
                //  · 网络类失败 ⇒ 原样上抛，错误面板显示"网络异常，请检查网络"。
                throw if (t is IwaraSession.IwaraApiException) {
                    IwaraSession.IwaraApiException("登录已过期，请重新登录")
                } else t
            }
            if (token.isNullOrBlank()) throw IwaraSession.IwaraApiException("登录已过期，请重新登录")
            // 订阅流走 callSubscription（固定 api.iwara.tv）—— apiq 会静默忽略
            //   `subscribed=true` 并返回全站最新视频。
            //   订阅流固定按最新排序（订阅页不提供排序筛选），分级沿用视频页选择。
            val rating = _rating.value
            val items = IwaraClient.callSubscription {
                it.videos(page, PAGE_LIMIT, "date", rating, true, null).results.orEmpty()
            }
            subscribedTrusted = true
            logFeedRequest("订阅", "videos?page=$page&limit=$PAGE_LIMIT&sort=date&rating=$rating&subscribed=true", items)
            items
        }
    }

    /* ---------- 搜索 ---------- */

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * 当前 tag 筛选是一组（多标签）。
     *
     * 语义与 iwara 网站一致：`/videos?tags=a,b` = 全都含（选两个标签只出两者都有的视频）。
     * 空列表 = 当前结果不是 tag 筛选（走关键词搜索 / 作者筛选 / 普通信息流）。
     */
    private val _searchTags = MutableStateFlow<List<String>>(emptyList())
    val searchTags: StateFlow<List<String>> = _searchTags.asStateFlow()

    /**
     * 搜索框的展开态与输入文字也放在 VM。
     *
     * 页面级 `remember` 会在切 tab、页面重建、动画期间重进时被重置，
     * 表现为筛选/搜索状态丢失；与 tag 集合一起放在 VM 里可以保持。
     */
    var searchExpanded by mutableStateOf(false)
        private set

    var searchInput by mutableStateOf("")
        private set

    fun expandSearch() {
        searchExpanded = true
    }

    /** 输入框打字（属性名与 setter 会撞 JVM 签名，所以这里用 update 前缀）。 */
    fun updateSearchInput(v: String) {
        searchInput = v
    }

    fun collapseSearch() {
        searchExpanded = false
        searchInput = ""
    }

    /**
     * 把当前生效的查询词回填到输入框。
     * 仅在展开搜索框时由 UI 调用一次（此时展示生效中的 `#a #b` 是合理引导）；
     * 点选联想 tag / 移除 tag 之后不再回填 —— 输入框只反映键盘输入，
     * 筛选状态由下方 TagFilterRow 表达。
     */
    fun syncSearchInput() {
        searchInput = _searchQuery.value
    }

    // 作者内容只走作者二级页：`loadProfile` + `loadUserVideos/Images/Posts`，
    // 不做"按作者就地筛选"。

    private val searchPager = FeedPager<IwaraVideoItem>(viewModelScope, PAGE_LIMIT, OnlineIdentity::video, ::describeIwaraError)
    val searchResults: StateFlow<FeedState<IwaraVideoItem>> = searchPager.state

    private val _tagSuggestions = MutableStateFlow<List<IwaraTag>>(emptyList())
    val tagSuggestions: StateFlow<List<IwaraTag>> = _tagSuggestions.asStateFlow()

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        _searchQuery.value = q
        _searchTags.value = emptyList()
        _tagSuggestions.value = emptyList()
        searchPager.load(force = true) { page ->
            // type 必须用复数 videos：单数 video 服务端直接 HTTP 500（LoveIwara 同款修复）。
            val rating = _rating.value
            val items = IwaraClient.call {
                it.searchVideos(q, "videos", page, PAGE_LIMIT, "date", rating).results.orEmpty()
            }
            logFeedRequest(
                "搜索",
                "search?query=$q&type=videos&page=$page&limit=$PAGE_LIMIT&sort=date&rating=$rating",
                items
            )
            items
        }
    }

    /**
     * 按 tag 筛选（`GET /videos?tags=a,b`，多个 id 逗号分隔、全都含）。
     *
     * 多标签语义为追加 / 再点取消：
     * - [append] = true（默认）：tag 已在集合里就移除、不在就追加（卡片 tag chip 走这条）；
     * - [append] = false：替换成只有这一个 tag（用于从零开始筛一个 tag）。
     */
    fun searchByTag(tagId: String, force: Boolean = false, append: Boolean = true) {
        val t = tagId.trim()
        if (t.isEmpty()) return
        val current = _searchTags.value
        val next = when {
            !append -> listOf(t)
            current.any { it.equals(t, ignoreCase = true) } -> current.filterNot { it.equals(t, true) }
            else -> current + t
        }
        // 同集合就不重复拉（顶栏刷新等场景走 force=true 强制重拉）
        if (!force && next == current) return
        applyTagFilter(next)
    }

    /** 把一组 tag 落成当前筛选（含搜索框文案、作者筛选清空）。[tags] 为空 = 取消 tag 筛选。 */
    private fun applyTagFilter(tags: List<String>) {
        _searchTags.value = tags
        _tagSuggestions.value = emptyList()
        if (tags.isEmpty()) {
            // 取消全部 tag ⇒ 回到普通信息流（清空搜索框与结果）
            _searchQuery.value = ""
            searchPager.reset()
            return
        }
        // 搜索框显示 "#a #b"（多个空格分隔，与网站的写法一致）
        _searchQuery.value = tags.joinToString(" ") { "#$it" }
        val joined = tags.joinToString(",")
        searchPager.load(force = true) { page ->
            val sort = videoSort.value
            val rating = _rating.value
            val items = IwaraClient.call {
                it.videos(page, PAGE_LIMIT, sort, rating, null, null, joined).results.orEmpty()
            }
            logFeedRequest("tag筛选", "videos?page=$page&limit=$PAGE_LIMIT&sort=$sort&rating=$rating&tags=$joined", items)
            items
        }
    }

    /** 取消某个 tag（面板/过滤 chip 上的 ✕）。 */
    fun removeSearchTag(tagId: String) {
        val next = _searchTags.value.filterNot { it.equals(tagId.trim(), true) }
        if (next == _searchTags.value) return
        applyTagFilter(next)
    }

    fun loadMoreSearch() {
        if (_searchQuery.value.isBlank()) return
        val tags = _searchTags.value
        searchPager.load(force = false) { page ->
            val rating = _rating.value
            val sort = videoSort.value
            when {
                tags.isNotEmpty() -> {
                    val joined = tags.joinToString(",")
                    val items = IwaraClient.call {
                        it.videos(page, PAGE_LIMIT, sort, rating, null, null, joined).results.orEmpty()
                    }
                    logFeedRequest("tag筛选", "videos?page=$page&limit=$PAGE_LIMIT&sort=$sort&rating=$rating&tags=$joined", items)
                    items
                }
                else -> {
                    val q = _searchQuery.value
                    val items = IwaraClient.call {
                        it.searchVideos(q, "videos", page, PAGE_LIMIT, "date", rating).results.orEmpty()
                    }
                    logFeedRequest(
                        "搜索",
                        "search?query=$q&type=videos&page=$page&limit=$PAGE_LIMIT&sort=date&rating=$rating",
                        items
                    )
                    items
                }
            }
        }
    }

    /** 搜索结果出错后的重试：按当前模式（关键词 / tag / 作者）强制重新拉第一页。 */
    fun retrySearch() {
        when {
            // tag 筛选用"重新落一遍当前集合"（多标签时不能只传一个）
            _searchTags.value.isNotEmpty() -> applyTagFilter(_searchTags.value)
            else -> search(_searchQuery.value)
        }
    }

    /** 顶栏刷新（视频 tab）：有关键词/tag/作者筛选就重拉结果，否则重拉探索信息流。 */
    fun refreshCurrentFeed() {
        if (_searchTags.value.isNotEmpty() || _searchQuery.value.isNotBlank()) {
            retrySearch()
        } else {
            loadMoreVideos(force = true)
        }
    }

    /**
     * 顶栏刷新图标强制刷新整个 Iwara 页。
     *
     * 按当前分段分发，每个分段重拉自己那条流：只重拉视频流会导致停在
     * 「图片 / 帖子」分段时点刷新页面纹丝不动。
     */
    fun refreshIwaraTab() {
        // 搜索/tag/作者筛选结果覆盖在当前分段之上（且只可能出现在视频分段）→ 优先重拉它。
        if (videoSegment == Segment.VIDEOS &&
            (_searchTags.value.isNotEmpty() || _searchQuery.value.isNotBlank())
        ) {
            retrySearch()
            return
        }
        when (videoSegment) {
            Segment.SUBSCRIBED -> loadMoreSubscribed(force = true)
            Segment.IMAGES -> loadMoreImages(force = true)
            Segment.POSTS -> loadMorePosts(force = true)
            Segment.VIDEOS -> refreshCurrentFeed()
        }
    }

    /** tag 联想（GET /tags?filter=前缀）。失败静默：联想只是辅助。 */
    fun searchTagSuggestions(prefix: String) {
        val p = prefix.trim()
        if (p.isEmpty()) {
            _tagSuggestions.value = emptyList()
            return
        }
        viewModelScope.launch {
            runCatching { IwaraClient.call { it.tags(p, 10).results.orEmpty() } }
                .onSuccess { _tagSuggestions.value = it }
        }
    }

    /**
     * 全局筛选面板「标签 · 精确」的联想，独立于搜索框的 [tagSuggestions]。
     *
     * 面板与搜索框都会打字，若共用一条 StateFlow，面板输入会覆盖搜索行的联想结果。
     * 两条流各自独立、互不干扰；失败静默 —— 联想只是辅助，本地侧还有
     * `VideoViewModel.localTagNames` 兜底。
     */
    private val _filterTagSuggestions = MutableStateFlow<List<String>>(emptyList())
    val filterTagSuggestions: StateFlow<List<String>> = _filterTagSuggestions.asStateFlow()

    /** 按前缀取标签候选（GET /tags?filter=）；空前缀即清空。 */
    fun loadFilterTagSuggestions(prefix: String) {
        val p = prefix.trim()
        if (p.isEmpty()) {
            _filterTagSuggestions.value = emptyList()
            return
        }
        viewModelScope.launch {
            runCatching { IwaraClient.call { it.tags(p, 10).results.orEmpty() } }
                .onSuccess { list ->
                    val ids = list.mapNotNull { t -> t.id }.filter { it.isNotBlank() }
                    if (ids != _filterTagSuggestions.value) _filterTagSuggestions.value = ids
                }
        }
    }

    /**
     * 清空关键词 / tag 筛选。
     *
     * [reason] 会写进诊断日志 —— 筛选药丸偶发丢失时，可借日志确认是哪一步清的，
     * 而不必只凭现象推测。
     */
    fun clearSearch(reason: String = "未标注") {
        if (_searchTags.value.isNotEmpty() || _searchQuery.value.isNotBlank()) {
            DiagLog.log("Filter", "清空筛选（原因：$reason）tags=${_searchTags.value}")
        }
        _searchQuery.value = ""
        _searchTags.value = emptyList()
        searchPager.reset()
        _tagSuggestions.value = emptyList()
    }

    /* ---------- 收藏 ---------- */

    private val favoriteVideosPager = FeedPager<IwaraVideoItem>(viewModelScope, PAGE_LIMIT, OnlineIdentity::video, ::describeIwaraError)
    val favoriteVideos: StateFlow<FeedState<IwaraVideoItem>> = favoriteVideosPager.state

    private val favoriteImagesPager = FeedPager<IwaraImageItem>(viewModelScope, PAGE_LIMIT, OnlineIdentity::image, ::describeIwaraError)
    val favoriteImages: StateFlow<FeedState<IwaraImageItem>> = favoriteImagesPager.state

    fun loadMoreFavoriteVideos(force: Boolean = false) {
        if (!_session.value.loggedIn) return
        favoriteVideosPager.load(force) { page ->
            IwaraClient.call {
                it.favoriteVideos(page, PAGE_LIMIT).results.orEmpty().mapNotNull { e -> e.video }
            }
        }
    }

    fun loadMoreFavoriteImages(force: Boolean = false) {
        if (!_session.value.loggedIn) return
        favoriteImagesPager.load(force) { page ->
            IwaraClient.call {
                it.favoriteImages(page, PAGE_LIMIT).results.orEmpty().mapNotNull { e -> e.image }
            }
        }
    }

    /* ---------- 图片 / 帖子流 ---------- */

    private val imagesPager = FeedPager<IwaraImageItem>(viewModelScope, PAGE_LIMIT, OnlineIdentity::image, ::describeIwaraError)
    val images: StateFlow<FeedState<IwaraImageItem>> = imagesPager.state

    fun loadMoreImages(force: Boolean = false) {
        imagesPager.load(force) { page ->
            IwaraClient.call {
                it.images(page, PAGE_LIMIT, videoSort.value, _rating.value, null).results.orEmpty()
            }
        }
    }

    private val postsPager = FeedPager<IwaraPostItem>(viewModelScope, PAGE_LIMIT, OnlineIdentity::post, ::describeIwaraError)
    val posts: StateFlow<FeedState<IwaraPostItem>> = postsPager.state

    fun loadMorePosts(force: Boolean = false) {
        postsPager.load(force) { page ->
            IwaraClient.call {
                it.posts(page, PAGE_LIMIT, videoSort.value, _rating.value).results.orEmpty()
            }
        }
    }

    /* ---------- 点赞 / 关注 ---------- */

    /** "type:id" -> 是否已赞（交互后的本地快照，避免整页刷新）。 */
    val likeStates = mutableStateMapOf<String, Boolean>()

    /** 账号 id -> 是否已关注。 */
    val followStates = mutableStateMapOf<String, Boolean>()

    /* ---------- 评论 ---------- */

    data class CommentsState(
        val type: String = "",
        val targetId: String = "",
        val title: String = "",
        val items: List<IwaraComment> = emptyList(),
        val page: Int = 0,
        val loading: Boolean = false,
        val endReached: Boolean = false,
        val error: String? = null,
        val sending: Boolean = false,
        val replyTo: IwaraComment? = null
    )

    internal val _comments = MutableStateFlow(CommentsState())
    val comments: StateFlow<CommentsState> = _comments.asStateFlow()

    /* ---------- 播放列表 ---------- */

    data class PlaylistsState(
        val items: List<IwaraPlaylist> = emptyList(),
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val error: String? = null
    )

    internal val _playlists = MutableStateFlow(PlaylistsState())
    val playlists: StateFlow<PlaylistsState> = _playlists.asStateFlow()

    /**
     * 关注状态打底。
     *
     * 只在本地写 `followStates[uid] = false` 而不向服务端校验，会把"已关注"始终显示成"关注"。
     * 因此会话内先拉一次自己的关注列表打底（分页拉，最多 [FOLLOW_SEED_MAX_PAGES] 页），
     * 之后 toggle 只在本地做增量。拉取失败不阻塞页面，仅记诊断日志，下次进页重试。
     */
    internal var followSeeded = false
    internal val followSeedMutex = Mutex()

    companion object {
        const val PAGE_LIMIT = 32

        /**
         * 「关注状态打底」最多翻几页（每页 [PAGE_LIMIT] 条）。
         * 5 页 = 最多 160 个关注；再多也不影响使用（没打底到的作者只是显示成未关注），
         * 但能避免关注上千人时进页要等一长串请求。
         */
        internal const val FOLLOW_SEED_MAX_PAGES = 5
    }
}
