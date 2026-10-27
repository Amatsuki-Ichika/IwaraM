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
        (scope ?: viewModelScope).launch {
            onDone(
                runCatching {
                    IwaraClient.call { it.relatedVideos(id, 0, RELATED_VIDEOS_LIMIT) }.results.orEmpty()
                }.getOrNull().orEmpty()
            )
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
     * 后台解析在线视频的播放地址（现取详情保证签名不过期 → X-Version 取清晰度）。
     * @param onResult (解析结果, 错误)；result 为空表示失败
     */
fun IwaraClientViewModel.resolveOnlineUrl(
        video: IwaraVideoItem,
        scope: CoroutineScope? = null,
        onResult: (result: OnlinePlayData?, error: String?) -> Unit
    ) {
        val id = video.id ?: run { onResult(null, "视频数据异常"); return }
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
                        if (detail.embedUrl != null) "该视频为外嵌内容，可在详情里用「打开原视频」观看"
                        else "该视频没有可播放的文件"
                    )
                    return@launch
                }
                val qualities = withContext(Dispatchers.IO) { IwaraSign.fetchQualities(fileUrl) }
                // 档名打进诊断日志：在播放器诊断面板即可看到服务端给出的档位，
                // 用于分辨"清晰度只剩 Source/Preview"是个例数据还是普遍问题。
                DiagLog.log("Iwara", "清晰度档位: [" + qualities.joinToString(", ") { it.name.orEmpty().ifBlank { "未命名" } } + "]")
                val options = qualities.mapNotNull { q ->
                    val view = q.src?.view?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    QualityOption(q.name.orEmpty().ifBlank { "未知" }, IwaraSign.httpsUrl(view))
                }
                if (options.isEmpty()) throw IwaraSession.IwaraApiException("没有可用的播放地址")
                // 默认分辨率是偏好而非强制：该档缺失时回退最高可用档，但不改写设置。
                val preferred = repo.getQualityPref()
                val chosen = options.firstOrNull { it.name == preferred } ?: options.first()
                reportView(id)
                onResult(OnlinePlayData(chosen.url, detail.liked == true, options, chosen.name), null)
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

fun IwaraClientViewModel.downloadVideo(video: IwaraVideoItem, onError: (String) -> Unit) {
        val id = video.id ?: return onError("视频数据异常")
        startDownloadById(id, scope = null, onError = onError)
    }

    /**
     * 按视频 id 重新解析并启动下载 —— 「下载列表」的继续/重新下载/失败重试都走这里。
     * 每次都会重新解析出新的签名地址（旧地址带 expires 会过期，不能复用）；
     * 若存在同视频的半成品文件，DownloadService 会自动从断点续传。
     */
fun IwaraClientViewModel.startDownloadById(id: String, scope: CoroutineScope? = null, onError: (String) -> Unit) {
        (scope ?: viewModelScope).launch {
            try {
                val detail = IwaraClient.call { it.video(id) }
                val fileUrl = detail.fileUrl
                if (fileUrl.isNullOrBlank()) {
                    onError(if (detail.embedUrl != null) "外嵌视频暂不支持下载" else "该视频没有可下载的文件")
                    return@launch
                }
                val qualities = withContext(Dispatchers.IO) { IwaraSign.fetchQualities(fileUrl) }
                // 下载分辨率是独立设置，不复用在线播放的默认分辨率。
                val preferred = repo.getDownloadQuality()
                val usable = qualities.filter { !it.src?.download.isNullOrBlank() || !it.src?.view.isNullOrBlank() }
                val chosen = usable.firstOrNull { it.name == preferred }
                    ?: IwaraSign.pickViewUrl(qualities, preferred)?.let { p -> usable.firstOrNull { it.name == p.first } }
                    ?: usable.firstOrNull()
                    ?: throw IwaraSession.IwaraApiException("没有可用的下载地址")
                val fileName = "[${id}] ${detail.title ?: id} (${chosen.name}).mp4"
                // src.download 常为 //host/... 协议相对地址，必须归一化成 https 才能请求，
                // 否则抛出 "Expected URL scheme 'http' or 'https'..."。
                val url = IwaraSign.buildDownloadUrl(
                    chosen.src?.download ?: chosen.src?.view!!,
                    fileName
                ) ?: run {
                    onError("下载地址无效")
                    return@launch
                }
                DownloadService.start(
                    app,
                    DownloadService.Job(
                        videoId = id,
                        title = detail.title ?: id,
                        url = url,
                        fileName = fileName,
                        quality = chosen.name.orEmpty()
                    )
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onError(describeIwaraError(t))
            }
        }
    }
