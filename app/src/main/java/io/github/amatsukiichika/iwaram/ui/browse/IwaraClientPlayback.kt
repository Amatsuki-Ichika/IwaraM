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
import io.github.amatsukiichika.iwaram.data.IwaraQualitySource
import io.github.amatsukiichika.iwaram.data.IwaraSession
import io.github.amatsukiichika.iwaram.data.IwaraSign
import io.github.amatsukiichika.iwaram.data.IwaraTag
import io.github.amatsukiichika.iwaram.data.IwaraVideoItem
import io.github.amatsukiichika.iwaram.data.SignedQuality
import io.github.amatsukiichika.iwaram.data.VideoEntity
import io.github.amatsukiichika.iwaram.data.describeIwaraError
import io.github.amatsukiichika.iwaram.data.ensureIwaraSuccess
import io.github.amatsukiichika.iwaram.data.GlobalFilterHolder
import io.github.amatsukiichika.iwaram.data.isSameDownloadRendition
import io.github.amatsukiichika.iwaram.data.loadPendingDownloads
import io.github.amatsukiichika.iwaram.data.parseIwaraDate
import io.github.amatsukiichika.iwaram.data.qualityLabelOfFileName
import io.github.amatsukiichika.iwaram.data.playbackSignedUrlCache
import io.github.amatsukiichika.iwaram.data.resolveDownloadTarget
import io.github.amatsukiichika.iwaram.data.selectPlaybackQuality
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

/* ---------- 在线播放解析 / 下载（原 IwaraClientViewModel 方法，扩展函数形式） ---------- */
    /* ---------- 本地播放页的在线状态 ---------- */

    /**
     * 本地视频匹配到 iwara（带 iwaraId）时，异步拉一次在线详情，补齐「点赞 / 关注作者」所需的
     * 状态。网络不可用 / 视频已删 → onResult(null, err)，调用方据此把操作区置灰（网络门控）。
     */
fun IwaraClientViewModel.loadVideoOnlineState(iwaraId: String, scope: CoroutineScope? = null, onResult: (LocalOnlineState?, String?) -> Unit) {
        (scope ?: viewModelScope).launch {
            try {
                val detail = IwaraClient.call { it.video(iwaraId) }
                val liked = detail.liked == true
                likeStates[likeKey("video", iwaraId)] = liked
                // 同 loadProfile：先给关注状态打底，避免把"已关注"显示成"关注"。
                ensureFollowStatesSeeded()
                detail.user?.id?.let { followStates[it] = followStates[it] ?: false }
                onResult(
                    LocalOnlineState(
                        liked = liked,
                        authorUserId = detail.user?.id,
                        authorHandle = detail.user?.username
                    ),
                    null
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onResult(null, describeIwaraError(t))
            }
        }
    }

    /* ---------- 在线播放解析 ---------- */

    /**
     * 播放页「相关推荐」：`video/{id}/related` 拉一页。
     * 静默加载 —— 失败或页面关闭取消一律回调空列表，UI 不弹错误、不占加载态。
     */
fun IwaraClientViewModel.loadRelatedVideos(
        id: String,
        scope: CoroutineScope? = null,
        onDone: (List<IwaraVideoItem>) -> Unit
    ) {
        val jobScope = scope ?: viewModelScope
        jobScope.launch {
            val related = runCatching {
                IwaraClient.call { it.relatedVideos(id, 0, RELATED_VIDEOS_LIMIT) }.results.orEmpty()
            }.getOrNull().orEmpty()
            onDone(related)
            // 列表一到就顺手预取「下一集」的签名地址（只挑 1 个；回调已经发过、不等它）：
            // 点下一集时只剩「详情」一跳，清晰度那一跳由缓存省掉；预取失败也只是回到现取，
            // 不影响播放，见 docs/decisions/0038-online-playback-latency.md。
            related.firstOrNull { it.id != null && it.id != id }?.let { next ->
                jobScope.launch { prefetchSignedUrls(next) }
            }
        }
    }

    /**
     * 用列表项数据立即构造可展示的 VideoEntity（播放地址留空占位），
     * 让播放页秒开；真实播放地址由 [resolveOnlineUrl] 异步补上。
     */
fun IwaraClientViewModel.buildEntity(item: IwaraVideoItem): VideoEntity {
        val id = item.id.orEmpty()
        return VideoEntity(
            localUri = "",
            fileName = "[$id] ${item.title.orEmpty()}.mp4",
            iwaraId = id,
            title = item.title ?: id,
            author = item.user?.name,
            authorId = item.user?.username ?: item.user?.id,
            createdAt = parseIwaraDate(item.createdAt),
            viewCount = item.numViews,
            likeCount = item.numLikes,
            commentCount = item.numComments,
            rating = item.rating,
            thumbnailUrl = IwaraClient.thumbnailUrl(item.file?.id),
            localThumbnailPath = null,
            relPath = null,
            tagsCsv = item.tags.orEmpty().mapNotNull { it.id }.joinToString(","),
            metaFetched = true,
            addedAt = System.currentTimeMillis()
        )
    }

    /**
     * 服务端清晰度 → 缓存用的签名档。档名保持服务端原样（清晰度偏好按它匹配、缓存键也按它）；
     * 地址统一归一化成 https，`//host/path` 这类协议相对地址播放器与网络栈都不接受。
     */
private fun signedQualitiesOf(qualities: List<IwaraQualitySource>): List<SignedQuality> =
        qualities.mapNotNull { q ->
            val view = q.src?.view?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SignedQuality(q.name.orEmpty(), IwaraSign.httpsUrl(view))
        }

    /**
     * 预取某个未播视频的签名清晰度地址（详情 → 清晰度 → 写缓存）。只用于高概率播放的「下一集」，
     * 限 1 个；失败静默 —— 预取只省一跳，未命中时 [resolveOnlineUrl] 照常现取，不能影响播放。
     */
private suspend fun IwaraClientViewModel.prefetchSignedUrls(video: IwaraVideoItem) {
        val id = video.id?.takeIf { it.isNotBlank() } ?: return
        // 已在缓存（刚解析过 / 另一次预取已完成）就不再打网络。
        if (playbackSignedUrlCache.cachedQualities(id).isNotEmpty()) return
        try {
            val fileUrl = IwaraClient.call { it.video(id) }.fileUrl?.takeIf { it.isNotBlank() } ?: return
            withContext(Dispatchers.IO) {
                playbackSignedUrlCache.loadQualities(id) { signedQualitiesOf(IwaraSign.fetchQualities(fileUrl)) }
            }
            DiagLog.log("Iwara", "已预取下一集签名地址")
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            DiagLog.log("Iwara", "预取下一集签名地址失败: " + t.javaClass.simpleName)
        }
    }

    /**
     * 后台解析在线视频的播放地址（现取详情保证签名不过期 → X-Version 取清晰度）。
     * @param onResult (解析结果, 错误)；result 为空表示失败
     */
fun IwaraClientViewModel.resolveOnlineUrl(
        video: IwaraVideoItem,
        scope: CoroutineScope? = null,
        onResult: (result: OnlinePlayData?, error: String?) -> Unit
    ) {
        val id = video.id ?: run { onResult(null, tr("视频数据异常", "Invalid video data")); return }
        (scope ?: viewModelScope).launch {
            try {
                // 详情现取：fileUrl 带时效签名，不能复用列表数据。
                val detail = IwaraClient.call { it.video(id) }
                likeStates[likeKey("video", id)] = detail.liked == true
                // 关注态打底放在解析之后单独跑：它最多要翻 5 页关注列表，塞进主链路会把
                // "点开即播"拖慢好几拍。本地视频路径（loadVideoOnlineState）早有这一步，
                // 纯在线路径此前漏了 —— 已关注的作者在播放页显示成「关注」，
                // 用户第一下点击反而是去关注，而不是取消关注。
                detail.user?.id?.let { authorId ->
                    viewModelScope.launch {
                        ensureFollowStatesSeeded()
                        followStates[authorId] = followStates[authorId] ?: false
                    }
                }
                val fileUrl = detail.fileUrl
                if (fileUrl.isNullOrBlank()) {
                    onResult(
                        null,
                        if (detail.embedUrl != null) {
                            tr(
                                "该视频为外嵌内容，可在详情里用「打开原视频」观看",
                                "This video is embedded — open it via “Open original video” in the details"
                            )
                        } else {
                            tr("该视频没有可播放的文件", "This video has no playable file")
                        }
                    )
                    return@launch
                }
                // 签名地址缓存优先：预取（开播 / 下一集）或上一次解析留下的地址直接可用，
                // 省掉「详情之后再取一次清晰度」的串行网络往返；未命中才现取，且与正在进行的
                // 预取按视频合并成一次请求（见 SignedUrlCache.loadQualities）。
                val cachedQualities = playbackSignedUrlCache.cachedQualities(id)
                val qualities: List<SignedQuality> = if (cachedQualities.isNotEmpty()) {
                    DiagLog.log("Iwara", "签名地址命中缓存，跳过清晰度解析")
                    cachedQualities
                } else {
                    withContext(Dispatchers.IO) {
                        playbackSignedUrlCache.loadQualities(id) {
                            val fetched = IwaraSign.fetchQualities(fileUrl)
                            // 档名打进诊断日志：在播放器诊断面板即可看到服务端给出的档位，
                            // 用于分辨"清晰度只剩 Source/Preview"是个例数据还是普遍问题。
                            DiagLog.log("Iwara", "清晰度档位: [" + fetched.joinToString(", ") { it.name.orEmpty().ifBlank { "未命名" } } + "]")
                            signedQualitiesOf(fetched)
                        }
                    }
                }
                if (qualities.isEmpty()) throw IwaraSession.IwaraApiException(tr("没有可用的播放地址", "No playable stream available"))
                // 默认分辨率是偏好而非强制：该档缺失时回退最高可用档，但不改写设置。
                val chosen = selectPlaybackQuality(repo.getQualityPref(), qualities)
                    ?: throw IwaraSession.IwaraApiException(tr("没有可用的播放地址", "No playable stream available"))
                val chosenName = chosen.name.ifBlank { tr("未知", "Unknown") }
                val options = qualities.map { q -> QualityOption(q.name.ifBlank { tr("未知", "Unknown") }, q.url) }
                reportView(id)
                onResult(OnlinePlayData(chosen.url, detail.liked == true, options, chosenName), null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onResult(null, describeIwaraError(t))
            }
        }
    }

internal fun IwaraClientViewModel.reportView(id: String) {
        viewModelScope.launch {
            runCatching {
                IwaraClient.call {
                    it.reportView(id, okhttp3.RequestBody.create(null, "{\"stats\":null}"))
                }
            }
        }
    }

    /* ---------- 下载 ---------- */

fun IwaraClientViewModel.downloadVideo(
        video: IwaraVideoItem,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit
    ) {
        val id = video.id ?: return onError(tr("视频数据异常", "Invalid video data"))
        startDownloadById(id, scope = null, onSuccess = onSuccess, onError = onError)
    }

    /**
     * 按视频 id 重新解析并启动下载 —— 「下载列表」的继续/重新下载/失败重试都走这里。
     * 每次都会重新解析出新的签名地址（旧地址带 expires 会过期，不能复用）；
     * 若存在同视频的半成品文件，DownloadService 会自动从断点续传。
     */
fun IwaraClientViewModel.startDownloadById(
        id: String,
        scope: CoroutineScope? = null,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit
    ) {
        (scope ?: viewModelScope).launch {
            try {
                // 先读一次待续传记录：它既是"续跑还是全新下载"的判据，也决定重新解析要用
                // **哪个档位**。解析签名地址要两次网络往返，期间用户可能取消或永久删除
                // （两条路径都会先删待续传记录、再置取消意图）。
                val pending = withContext(Dispatchers.IO) {
                    runCatching { repo.loadPendingDownloads().firstOrNull { it.videoId == id } }
                        .getOrNull()
                }
                val hadPending = pending != null
                // 续跑沿用记录里的档位，而不是当前设置：否则用户改过下载清晰度后点「继续」，
                // 会拿新档位的地址往旧半成品的偏移后面续写，拼出损坏文件（review Medium-58）。
                // 旧记录的 quality 字段可能为空，文件名里的 `(档位)` 是同一信息的可读来源。
                val pendingQuality = pending?.let { p ->
                    p.quality.takeIf { it.isNotBlank() } ?: qualityLabelOfFileName(p.fileName)
                }
                // 解析（详情 → 清晰度 → 归一化签名地址）与下载服务"地址过期后重解析"共用一条路径。
                val target = resolveDownloadTarget(repo, id, pendingQuality?.takeIf { it.isNotBlank() })
                if (!pendingQuality.isNullOrBlank() &&
                    !isSameDownloadRendition(pendingQuality, target.quality)
                ) {
                    // 首选档位已不可用（chooseDownloadQuality 回退到了别的档）：宁可不续传，
                    // 也不能把另一种编码追加到旧文件后面；让用户按新档位重新下载。
                    onError(
                        tr(
                            "所选清晰度已不可用，请重新下载",
                            "The selected quality is no longer available. Download it again"
                        )
                    )
                    return@launch
                }
                if (hadPending) {
                    // 记录已不在 = 解析期间被取消/永久删除：不能再启动，否则删掉的视频会被重新下回库里。
                    // 读取失败按"还在"处理（true）—— 服务侧对取消意图还有一道拒绝，不能因一次读取抖动误丢任务。
                    val stillPending = withContext(Dispatchers.IO) {
                        runCatching { repo.loadPendingDownloads().any { it.videoId == id } }
                            .getOrDefault(true)
                    }
                    if (!stillPending) {
                        DiagLog.log("Iwara", "解析期间任务已被取消/删除，不启动下载：$id")
                        return@launch
                    }
                }
                val started = DownloadService.start(
                    app,
                    DownloadService.Job(
                        videoId = id,
                        title = target.title,
                        url = target.url,
                        fileName = target.fileName,
                        quality = target.quality,
                        // 续跑：服务在解析期间出现取消意图时拒绝入队，而不是清掉它。
                        continuation = hadPending
                    )
                )
                // 服务拒绝启动（续跑前已取消/删除）：不提示"已加入下载队列"。
                if (!started) return@launch
                // 提示必须在真正入队之后：解析与建地址都可能失败，
                // 先弹"已加入下载队列"再弹错误，用户会以为任务已经下起来了。
                onSuccess()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onError(describeIwaraError(t))
            }
        }
    }
