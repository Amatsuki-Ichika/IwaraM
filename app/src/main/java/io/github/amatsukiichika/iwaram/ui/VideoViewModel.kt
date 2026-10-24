package io.github.amatsukiichika.iwaram.ui

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.amatsukiichika.iwaram.IwaraApp
import io.github.amatsukiichika.iwaram.ScanService
import io.github.amatsukiichika.iwaram.ScanState
import io.github.amatsukiichika.iwaram.data.GlobalFilterHolder
import io.github.amatsukiichika.iwaram.data.TagEntity
import io.github.amatsukiichika.iwaram.data.VideoEntity
import io.github.amatsukiichika.iwaram.data.filterBy
import io.github.amatsukiichika.iwaram.data.localVideoKeys
import io.github.amatsukiichika.iwaram.data.linkIwaraId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Iwara 客户端模块与本地库共用的本地视频 ViewModel：筛选、排序、导入、扫描、设置。 */
class VideoViewModel(private val app: IwaraApp) : ViewModel() {

    private val dao = app.database.videoDao()
    private val repo = app.repository

    private var rawVideos: List<VideoEntity> = emptyList()
    private var rawTags: List<TagEntity> = emptyList()
    private var mode = LibraryMode.ALL
    private var selectedAuthorId: String? = null
    /** 本地库选中的 tag（多选，**全都含**语义；空 = 没在按 tag 筛）。 */
    private var selectedTagIds: List<String> = emptyList()
    private var selectedTime: TimeBucket? = null
    private var selectedFolder: String? = null
    private var sortMode = SortMode.NEWEST
    private var showTags = repo.getShowTags()
    private var query = ""
    /** 手选的分级（持久化）；「默认 R18」开启时不影响它，只影响生效值。 */
    private var ratingFilter = repo.getLocalRating()
    private var defaultR18 = repo.getDefaultR18()
    private var showR18Tag = repo.getShowR18Tag()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 扫描进度（与 [UiState] 分离，见 [ScanProgress] 的注释）。 */
    private val _scanProgress = MutableStateFlow(ScanProgress())
    val scanProgress: StateFlow<ScanProgress> = _scanProgress.asStateFlow()

    /**
     * 卡片 R18 角标开关的**窄出口**（真源就是 [IwaraRepository.showR18Tag] 那条流）。
     *
     * 用途：`MainActivity` 最外层若为了拿 `showR18Tag` 一个布尔值而收集**整个 [UiState]**，
     * 每次视频列表变化都会重跑一次包裹整棵 UI 树的 `CompositionLocalProvider`。
     * 换成这条窄流之后，只有真正读该开关的地方会重组。
     */
    val showR18TagFlow: StateFlow<Boolean> = repo.showR18Tag

    /** 「显示标签」开关的窄出口（真源 [IwaraRepository.showTags]），设置页专用。 */
    val showTagsFlow: StateFlow<Boolean> = repo.showTags

    /** 「默认 R18」的窄出口（真源 [IwaraRepository.defaultR18]），设置页专用。 */
    val defaultR18Flow: StateFlow<Boolean> = repo.defaultR18

    /**
     * Debug 模式开关的**窄出口**（真源 [IwaraRepository.debugDetails]，不复制状态）。
     * 两个消费端：MainActivity 的 `LocalDebugDetails` 分发、设置页的开关行。
     */
    val debugDetailsFlow: StateFlow<Boolean> = repo.debugDetails


    /**
     * 重算代数号 —— 只在**主线程**自增（所有 setter / 收藏器都跑在主线程）。
     *
     * 计算体现在跑在 [Dispatchers.Default] 上，完成时只有"最新一代"允许写回 [_uiState]：
     * 否则快速连续触发（搜索逐键、扫描期间 DB 连续变化）时，先发后至的旧结果会把新列表覆盖掉，
     * 界面表现为"列表回退 / 闪烁"。
     *
     * **不变式（改这个类时务必守住）**：每一个会改变计算输入的字段，赋值之后**必须**调用
     * [recompute]。这条成立，则任何"读到了半新半旧字段"的计算都必然被更晚的一代取代并丢弃，
     * 所以不需要给那十几个字段逐个加同步。
     */
    @Volatile
    private var generation = 0L

    /** 搜索防抖任务（见 [setQuery]）。 */
    private var queryJob: Job? = null

    /**
     * 本地库已入库的 iwara 视频 id 集合 —— 在线卡片左上角「已下载」药丸勾的判据
     * （「本地库已有」即算已下载：扫描入库与 App 下载都写同一张表）。
     */
    private val _downloadedIwaraIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedIwaraIds: StateFlow<Set<String>> = _downloadedIwaraIds.asStateFlow()

    /**
     * **全部**本地视频（未经本地库当前筛选/搜索/排序处理）。
     * 作者二级页要用它按 [authorKeyOf] 分组，算出"该作者的本地视频"并保证离线可用；
     * 本地导入/扫描完成后这里会跟着 [recomputeInternal] 自动刷新。
     */
    private val _allVideos = MutableStateFlow<List<VideoEntity>>(emptyList())
    val allVideos: StateFlow<List<VideoEntity>> = _allVideos.asStateFlow()

    /**
     * 全局筛选器：本地库里**出现过的标签名**（`tagsCsv` 去重、按名排序）。
     *
     * 用途：筛选面板「标签 · 精确」模式的**联想候选** —— 精确匹配要求"与已存在的标签完全相等"，
     * 手打很容易错一个字就筛不出东西。在线那一侧的联想走上游 `GET /tags?filter=`。
     *
     * 随 [buildTagBuckets] 的那一趟遍历顺手收集（不额外遍历全库），只在内容真的变了才换实例。
     */
    private val _localTagNames = MutableStateFlow<List<String>>(emptyList())
    val localTagNames: StateFlow<List<String>> = _localTagNames.asStateFlow()

    // 以下设置全部直通 IwaraRepository 的单一真源流（窄出口模式，同上面的
    // showR18TagFlow 等）：本 VM 不再自持镜像副本，setter 只写仓库，广播由仓库负责。

    /** 手动 access token（空串 = 公开无 token 模式）。 */
    val token: StateFlow<String> = repo.accessToken

    val fsOrientation: StateFlow<String> = repo.fullscreenOrientation

    val themeMode: StateFlow<String> = repo.themeMode

    /** 根目录显示名；空串 = 未导入（导入扫描时由仓库更新，此处经流自动刷新）。 */
    val rootName: StateFlow<String> = repo.rootName

    /** 自定义下载目录的显示名；空串 = 跟随管理器根目录（默认）。 */
    val downloadRootName: StateFlow<String> = repo.downloadRootName

    /** 在线播放的默认分辨率（偏好档；该档缺失时回退最高可用档）。 */
    val qualityPref: StateFlow<String> = repo.qualityPref

    /** 下载分辨率（独立于在线播放的默认分辨率；该档缺失时回退最高可用档）。 */
    val downloadQualityPref: StateFlow<String> = repo.downloadQuality

    /** 超分（Anime4K 风格）：默认启用开关。 */
    val srEnabled: StateFlow<Boolean> = repo.superResEnabled

    /** 超分模型档位；旧档位（自写锐化 A/B/C）在 init 里一次性迁移落库。 */
    val srModel: StateFlow<String> = repo.superResModel

    /** 应用主题色（THEME_COLOR_OPTIONS 的 value）。 */
    val themeColor: StateFlow<String> = repo.themeColor

    /** 当前是否为无 token 公开模式。 */
    fun isPublicMode(): Boolean = repo.accessToken.value.isBlank()

    fun setToken(value: String) = repo.setAccessToken(value.trim())

    fun setFsOrientation(value: String) = repo.setFullscreenOrientation(value)

    fun setThemeMode(value: String) = repo.setThemeMode(value)

    fun setThemeColor(value: String) = repo.setThemeColor(value)

    fun setQualityPref(value: String) = repo.setQualityPref(value)

    fun setDownloadQualityPref(value: String) = repo.setDownloadQuality(value)

    fun setSrEnabled(value: Boolean) = repo.setSuperResEnabled(value)

    fun setSrModel(value: String) = repo.setSuperResModel(value)

    /**
     * 设置自定义下载目录；null = 恢复默认（跟随管理器根目录）。
     * 目录名用 DocumentFile 解析（SAF 树 URI 本身不可读），失败时存空名但仍保存 URI。
     * `DocumentFile.fromTreeUri(...).name` 是 SAF Binder IPC，放到 IO 线程
     * （设置页选择器回调在主线程，目录多时会卡一下）；URI 本身先同步落库，
     * 显示名经仓库的 downloadRootName 流广播回 UI。
     */
    fun setDownloadRoot(treeUri: Uri?) {
        if (treeUri == null) {
            repo.setDownloadRoot(null)
            repo.setDownloadRootName(null)
            return
        }
        repo.setDownloadRoot(treeUri.toString())
        viewModelScope.launch(Dispatchers.IO) {
            val name = runCatching {
                DocumentFile.fromTreeUri(app, treeUri)?.name
            }.getOrNull()
            repo.setDownloadRootName(name)
        }
    }

    /** 按 iwara id 查本地已入库的视频（下载或元数据关联过）；无则返回 null。 */
    fun findLocalByIwaraId(id: String?): VideoEntity? =
        id?.takeIf { it.isNotBlank() }?.let { wanted -> rawVideos.firstOrNull { it.iwaraId == wanted } }

    /** 观看历史：按 localUri 查本地已入库的视频（历史行点开时判断"文件还在不在库"）。 */
    fun findLocalByUri(uri: String?): VideoEntity? =
        uri?.takeIf { it.isNotBlank() }?.let { wanted -> rawVideos.firstOrNull { it.localUri == wanted } }

    init {
        // 旧超分档位（自写锐化 "A"/"B"/"C"）一次性迁移到成品算法档位并落回 prefs，
        // 之后 srModel 直接透传仓库的单一真源流，不再每读一次归一化。
        val rawSrModel = repo.getSuperResModel()
        val normalizedSrModel = SuperRes.normalize(rawSrModel)
        if (normalizedSrModel != rawSrModel) repo.setSuperResModel(normalizedSrModel)

        viewModelScope.launch {
            dao.observeAll().collect {
                rawVideos = it
                // 作者二级页订阅的原始列表（不受本地库当前筛选影响）：随 DB 变化立刻反映。
                // 这一步必须写在重算体之外：重算跑在后台且可能被作废，
                //   而 _allVideos 的正确性只取决于 rawVideos，直接在这里赋值更准也更快。
                _allVideos.value = it
                // 内容没变就不换实例 —— 这个 Set 通过 CompositionLocal 下发给**每张
                //   在线卡片**，换新实例会让所有可见卡片重新读一次并重组。DB 变化很频繁
                //   （扫描/下载期间尤甚），而"已下载 id 集合"其实很少真的变。
                val ids = it.mapNotNull { v -> v.iwaraId?.takeIf { id -> id.isNotBlank() } }.toSet()
                if (ids != _downloadedIwaraIds.value) _downloadedIwaraIds.value = ids
                recompute()
            }
        }
        viewModelScope.launch {
            dao.observeAllTags().collect {
                rawTags = it.sortedBy { t -> t.name.lowercase() }
                recompute()
            }
        }
        viewModelScope.launch {
            ScanState.progress.collect { p ->
                _scanProgress.value = ScanProgress(
                    running = p != null,
                    label = p?.phase ?: "",
                    done = p?.done ?: 0,
                    total = p?.total ?: 0
                )
                // 这里**不调用 recompute()** —— 扫描进度与"库里有哪些视频"无关，
                //   否则每个进度 tick 都要重算一遍全库（最多 6 趟 filter + 排序 + 4 次 groupBy/sort），
                //   是"扫描/导入时掉帧"最直接的来源。进度走 _scanProgress 这条独立流。
            }
        }
        // 两个内容分级开关 + 显示标签 + 本地分级筛选，统一订阅 repo 的**单一真源** ——
        //   设置页改一处，本地库与在线页同时生效（两份内存副本互不通知时，在线页要重启 App 才生效）。
        //   这四个只是 recompute 的输入快照（后台线程读字段），不是对外暴露的第二份状态。
        viewModelScope.launch {
            repo.defaultR18.collect { v ->
                if (v != defaultR18) {
                    defaultR18 = v
                    recompute()
                }
            }
        }
        viewModelScope.launch {
            repo.showR18Tag.collect { v ->
                if (v != showR18Tag) {
                    showR18Tag = v
                    recompute()
                }
            }
        }
        viewModelScope.launch {
            repo.showTags.collect { v ->
                if (v != showTags) {
                    showTags = v
                    recompute()
                }
            }
        }
        viewModelScope.launch {
            repo.localRating.collect { v ->
                if (v != ratingFilter) {
                    ratingFilter = v
                    recompute()
                }
            }
        }
        // 全局筛选器：筛选状态一变就重算本地库列表 ——
        //   本地库与在线各页共用同一份状态，这里让"本地"这条通路跟上。
        //   丢掉首个发射（订阅时的当前值）：进程启动时它必然是默认态，而 DB 首拉紧接着就会算一遍。
        //   （即便丢掉也不会漏：buildFiltered() 每次都**现读** GlobalFilterHolder.value。）
        viewModelScope.launch {
            GlobalFilterHolder.listState.drop(1).collect { recompute() }
        }
    }

    /** 启动时不自动扫描：直接展示已缓存结果，是否更新由手动点击刷新/导入。 */
    fun onStart() {
        // 无操作：数据由 Room 观察自动加载，避免每次启动都触发后台检索。
    }

    fun importFolder(treeUri: Uri) {
        repo.setRoot(treeUri.toString())
        ScanService.start(app)
    }

    fun retryFetchMissing() {
        ScanService.start(app)
    }

    /** 刷新：重扫已保存根目录（找新文件）并补拉缺失元数据。 */
    fun refresh() {
        if (repo.getRoot() != null) {
            ScanService.start(app)
        }
    }

    /** 手动给某条视频指定 iwara ID。 */
    fun linkIwaraId(video: VideoEntity, id: String) {
        viewModelScope.launch {
            repo.linkIwaraId(video, id)
        }
    }

    /** 仅从本地库移除该条目（不删除视频文件）；重新扫描会把文件重新入库。 */
    fun removeFromLibrary(video: VideoEntity) {
        viewModelScope.launch {
            // 清关联 + 删记录收进单个 Room 事务，原子生效。
            dao.deleteVideoWithTags(video)
        }
    }

    fun setMode(m: LibraryMode) {
        mode = m
        recompute()
    }

    fun selectAuthor(id: String?) {
        selectedAuthorId = id
        selectedTagIds = emptyList()
        selectedTime = null
        selectedFolder = null
        if (id != null) mode = LibraryMode.ALL
        recompute()
    }

    /**
     * 多标签：点一个 tag = **追加**；再点同一个 = **取消**。
     *
     * 语义与在线检索一致 —— 选中多个时要求"**全都含**"（见 [buildFiltered]）。
     * 传 null 表示清空全部 tag 条件（保留给"清筛选"的调用点）。
     */
    fun selectTag(id: String?) {
        if (id == null) {
            selectedTagIds = emptyList()
        } else {
            selectedTagIds = if (selectedTagIds.any { it.equals(id, ignoreCase = true) }) {
                selectedTagIds.filterNot { it.equals(id, ignoreCase = true) }
            } else {
                selectedTagIds + id
            }
            mode = LibraryMode.ALL
        }
        selectedAuthorId = null
        selectedTime = null
        selectedFolder = null
        recompute()
    }

    fun selectTime(bucket: TimeBucket?) {
        selectedTime = bucket
        selectedAuthorId = null
        selectedTagIds = emptyList()
        selectedFolder = null
        if (bucket != null) mode = LibraryMode.ALL
        recompute()
    }

    fun selectFolder(key: String?) {
        selectedFolder = key
        selectedAuthorId = null
        selectedTagIds = emptyList()
        selectedTime = null
        if (key != null) mode = LibraryMode.ALL
        recompute()
    }

    fun clearFilter() {
        selectedAuthorId = null
        selectedTagIds = emptyList()
        selectedTime = null
        selectedFolder = null
        recompute()
    }

    fun setSortMode(m: SortMode) {
        sortMode = m
        recompute()
    }

    /** 只写仓库真源，界面刷新由 init 里的订阅统一驱动。 */
    fun setShowTags(value: Boolean) {
        repo.setShowTags(value)
    }

    fun setRatingFilter(value: String) {
        repo.setLocalRating(value)
    }

    /** 清除分级筛选（返回键逐层清除用）。 */
    fun clearRatingFilter() {
        repo.setLocalRating("all")
    }

    /**
     * 默认 R18：隐藏分级筛选项并强制只看 R18（在线 + 本地一致）。
     * 只写 repo 的单一真源；内存字段由上面的订阅回调更新（在线页共用同一份）。
     */
    fun setDefaultR18(value: Boolean) {
        repo.setDefaultR18(value)
    }

    /** 卡片是否显示 R18 角标（独立开关）。同样只写单一真源。 */
    fun setShowR18Tag(value: Boolean) {
        repo.setShowR18Tag(value)
    }

    /** Debug 模式开关（开发者细节）。只写单一真源，UI 端经 [debugDetailsFlow] 生效。 */
    fun setDebugDetails(value: Boolean) {
        repo.setDebugDetails(value)
    }

    /* ---------- 半透明材质（外观页两根设置） ---------- */

    /** 材质不透明度窄出口（底栏与角标共用一份设置）。 */
    val glassOpacityFlow: StateFlow<Float> = repo.glassOpacity

    /** 材质方案窄出口（0 关=实心 / 1 半透明（默认）/ 2 液态玻璃）。 */
    val glassModeFlow: StateFlow<Int> = repo.glassMode

    fun setGlassOpacity(value: Float) {
        repo.setGlassOpacity(value)
    }

    fun setGlassMode(value: Int) {
        repo.setGlassMode(value)
    }

    /**
     * 搜索词：**立刻回显**（输入框不能滞后一个字），**防抖 250ms 后再重算**。
     *
     * 每敲一个键都立刻跑一遍全库重算时，连打 6 个字符就是 6 次
     * 「6 趟 filter + 排序 + 4 次 groupBy/sort」。防抖后只算最后那一次。
     */
    fun setQuery(q: String) {
        if (q == query) return
        query = q
        // 立即回显：只换一个字段，不触发任何重算（输入框必须跟手）。
        _uiState.value = _uiState.value.copy(query = q)
        // 让"正在飞"的旧计算作废：否则它落地时会把输入框里的新词回退掉。
        generation++
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            delay(QUERY_DEBOUNCE_MS)
            recompute()
        }
    }

    /* ---------- 状态计算 ---------- */

    /**
     * 请求一次重算：**输入字段变了就调它**（见 [generation] 处的不变式）。
     *
     * 计算体跑在 [Dispatchers.Default] 而非 `Dispatchers.Main.immediate`（`viewModelScope` 默认）。
     * 全库 filter/排序/分桶跑在主线程上会被这些高频路径触发：搜索逐键、DB 每次变化、以及
     * 每个扫描进度 tick —— 库越大越卡，扫描/搜索时掉帧。
     */
    private fun recompute() {
        val gen = ++generation
        viewModelScope.launch(Dispatchers.Default) {
            val next = try {
                computeState()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // 单条坏数据不应导致崩溃循环：记录后沿用上一次的界面状态。
                android.util.Log.e("IwaraManager", "recompute failed", t)
                return@launch
            }
            // 只有最新一代允许写回：乱序完成不能让界面回退到旧列表。
            if (gen == generation) _uiState.value = next
        }
    }

    /** 纯计算：读当前字段产出下一份 [UiState]。由 [recompute] 丢到后台线程调用。 */
    private fun computeState(): UiState {
        val buckets = buckets()
        val filtered = buildFiltered()
        // 分类视图的桶列表也响应搜索词（见 filterBucket）——
        //   四个分类 tab 与「全部」视图共用同一份 query。分段标记必须跟着过滤后的列表走，
        //   否则滚动条下标与可见列表错位；activeFilterFor 仍用全量 authors（按 id 找当前筛选）。
        val folders = filterBucket(buckets.folders, query) { it.name }
        val authors = filterBucket(buckets.authors, query) { it.name }
        val times = filterBucket(buckets.times, query) { it.label }
        val tagBuckets = filterBucket(buckets.tagBuckets, query) { it.name }
        return UiState(
            videos = filtered.first,
            folders = folders,
            authors = authors,
            timeBuckets = times,
            tags = tagBuckets,
            mode = mode,
            activeFilter = activeFilterFor(buckets.authors),
            sortMode = sortMode,
            showTags = showTags,
            query = query,
            ratingFilter = ratingFilter,
            defaultR18 = defaultR18,
            showR18Tag = showR18Tag,
            hiddenByFilter = filtered.second,
            selectedTags = selectedTagIds,
            // 分段标记随 UiState 在后台线程算（见 UiState 字段注释）。
            videoMarkers = buildMarkers(filtered.first) { videoSection(it, sortMode) },
            folderMarkers = buildMarkers(folders) { bucketSection(it.name, it.count, sortMode) },
            authorMarkers = buildMarkers(authors) { bucketSection(it.name, it.count, sortMode) },
            timeMarkers = buildMarkers(times) { timeBucketSection(it, sortMode) }
        )
    }

    /** 当前筛选条件的展示文案（选中作者 / 时间桶 / 标签 / 文件夹四选一）。 */
    private fun activeFilterFor(authors: List<AuthorBucket>): ActiveFilter? = when {
        selectedAuthorId != null -> {
            val a = authors.find { it.id == selectedAuthorId }
            ActiveFilter(a?.name ?: "作者", LibraryMode.AUTHOR)
        }
        selectedTime != null -> ActiveFilter(selectedTime!!.label, LibraryMode.TIME)
        // 多标签：多个 tag 拼成一条 chip 文案（清除时去掉全部 tag 条件）
        selectedTagIds.isNotEmpty() -> ActiveFilter(
            selectedTagIds.joinToString(" + "),
            LibraryMode.TAG
        )
        selectedFolder != null -> ActiveFilter(
            selectedFolder.takeIf { it != ROOT_FOLDER_KEY } ?: ROOT_FOLDER_NAME,
            LibraryMode.FOLDER
        )
        else -> null
    }

    /**
     * 四个分类桶（作者 / 时间 / 文件夹 / 标签）的**记忆化快照**。
     *
     * 这四个计算**只依赖 `rawVideos` + `rawTags` + `sortMode`**，与搜索词和各类筛选
     * **完全无关**，但每敲一个键、每次 DB 变化、每次切换筛选都会把它们连同 `rawVideos` 的
     * 多次遍历一起重算一遍。这里按"输入换没换"缓存：失效判据用**引用相等**
     * （两个列表都是 DAO 整体替换的，`===` 就够，不需要 O(n) 的 equals）。
     */
    private class BucketSnapshot(
        val videos: List<VideoEntity>,
        val tags: List<TagEntity>,
        val sort: SortMode,
        val authors: List<AuthorBucket>,
        val times: List<TimeBucket>,
        val folders: List<FolderBucket>,
        val tagBuckets: List<TagBucket>
    )

    /** 只在后台计算线程读写（缓存跨线程可见性靠 @Volatile；并发重算互相写同一个值，无副作用）。 */
    @Volatile
    private var bucketSnapshot: BucketSnapshot? = null

    private fun buckets(): BucketSnapshot {
        val cached = bucketSnapshot
        if (cached != null &&
            cached.videos === rawVideos &&
            cached.tags === rawTags &&
            cached.sort == sortMode
        ) {
            return cached
        }
        return BucketSnapshot(
            videos = rawVideos,
            tags = rawTags,
            sort = sortMode,
            authors = buildAuthors(),
            times = buildTimeBuckets(),
            folders = buildFolders(),
            tagBuckets = buildTagBuckets()
        ).also { bucketSnapshot = it }
    }

    /**
     * 当前筛选条件下的最终列表 + 被全局筛选器隐藏的条数（`first` = 列表，`second` = 隐藏数）。
     *
     * 用 Pair 回传而不是写成员字段：本方法跑在后台计算线程、可能有两代并存在飞，
     * 成员字段会被后一代覆写，而 Pair 是这个调用的**局部结果**，天然不会有这个竞态。
     */
    private fun buildFiltered(): Pair<List<VideoEntity>, Int> {
        var list = rawVideos
        selectedAuthorId?.let { id -> list = list.filter { authorKeyOf(it) == id } }
        // 多标签：选中的每一个都要命中（**全都含**，与在线检索/网站一致）。
        if (selectedTagIds.isNotEmpty()) {
            val wanted = selectedTagIds
            list = list.filter { v -> wanted.all { hasTag(v, it) } }
        }
        selectedFolder?.let { k -> list = list.filter { topFolderKey(it) == k } }
        selectedTime?.let { b ->
            list = if (b.key == TIME_UNKNOWN_KEY) {
                list.filter { it.createdAt == null }
            } else {
                list.filter { v -> v.createdAt != null && v.createdAt in b.startEpochMs until b.endEpochMs }
            }
        }
        // 分级筛选：「默认 R18」开启时强制 ecchi；未匹配/未知分级的本地视频按 R18 归类。
        val ratingWanted = effectiveRatingFilter()
        if (ratingWanted != "all") {
            list = list.filter { localRatingOf(it) == ratingWanted }
        }
        if (query.isNotBlank()) {
            // 匹配规则收口到 matchesQuery —— 覆盖 fileName / authorId / iwaraId
            //   （未匹配元数据的视频卡片上显示的就是文件名；ID 类字段用于"拿 id 找片"场景）。
            list = list.filter { matchesQuery(it, query) }
        }
        // 全局筛选器：**最后一道**关卡 —— 无论上面筛成什么，最终列表都要过它。
        //   放在排序之前：先筛小再排，排序的开销也跟着降。取键统一走 `localVideoKeys`
        //   （名字 = 标题/文件名，标签 = tagsCsv），与在线各页共用同一套规则（见 data/GlobalFilter.kt）。
        val beforeFilter = list.size
        list = list.filterBy(GlobalFilterHolder.value, ::localVideoKeys)

        // 排序统一走 VideoSorter 纯函数（可单元测试）。
        return VideoSorter.sortVideos(list, sortMode) to (beforeFilter - list.size)
    }

    /** 取相对路径的第一段作为顶级文件夹，用于文件夹分类；根目录视频归入 ROOT_FOLDER_KEY。 */
    private fun topFolderKey(v: VideoEntity): String =
        v.relPath?.substringBefore('/')?.takeIf { it.isNotBlank() } ?: ROOT_FOLDER_KEY

    /** 文件夹分类：按顶级文件夹分组。 */
    private fun buildFolders(): List<FolderBucket> {
        val buckets = rawVideos.groupBy { topFolderKey(it) }
            .map { (k, vs) ->
                FolderBucket(
                    key = k,
                    name = k.takeIf { it != ROOT_FOLDER_KEY } ?: ROOT_FOLDER_NAME,
                    count = vs.size
                )
            }
        return VideoSorter.sortFolderBuckets(buckets, sortMode)
    }

    /**
     * 作者分组：**未匹配到 iwara**（无 authorId）的本地视频也入组，统一归到「未指定」。
     * 若用 `filter { it.authorId != null }` 把它们整个滤掉，它们在「作者」分类里会完全看不到。
     */
    private fun buildAuthors(): List<AuthorBucket> {
        val list = rawVideos
            .groupBy { authorKeyOf(it) }
            .map { (key, vs) ->
                AuthorBucket(
                    id = key,
                    name = if (key == UNKNOWN_AUTHOR_KEY) UNKNOWN_AUTHOR_NAME
                    else vs.firstNotNullOfOrNull { v -> v.author?.takeIf { it.isNotBlank() } } ?: key,
                    count = vs.size
                )
            }
        return VideoSorter.sortAuthorBuckets(list, sortMode)
    }

    /** 分级的**生效值**：默认 R18 开启时恒为 ecchi，否则用手选值。 */
    private fun effectiveRatingFilter(): String = if (defaultR18) "ecchi" else ratingFilter

    /**
     * 一条本地视频的分级：显式 general 才算「一般」，其余（ecchi / 未知 / 未匹配到 iwara）
     * 一律按 R18 归类（无法匹配的本地视频归「未指定」、分级为 R18）。
     */
    private fun localRatingOf(v: VideoEntity): String =
        if (v.rating?.equals("general", true) == true) "general" else "ecchi"

    /** 作者分组键：无 authorId 的本地视频归到「未指定」桶。public 以便作者二级页共用。 */
    fun authorKeyOf(v: VideoEntity): String = LocalAuthorVideos.keyOf(v)

    /**
     * 作者二级页「视频」栏的**本地部分**：按 [key]（= [authorKeyOf] 的分组键）过滤，
     * 并**固定按发布最新 → 最旧**排序 —— 这段排序不跟随本地库的排序设置。
     *
     * [source] 默认取 [allVideos]，便于单测直接喂数据。
     */
    fun localVideosOfAuthor(key: String, source: List<VideoEntity> = _allVideos.value): List<VideoEntity> =
        LocalAuthorVideos.of(source, key)

    /**
     * 播放页「相关推荐」的本地部分：同作者优先，空则回退同标签；
     * 均不含当前视频本身，按发布/入库时间倒序，最多 [max] 条。纯本地计算，无网络。
     */
    fun localRelatedOf(video: VideoEntity, max: Int = RELATED_VIDEO_MAX): List<VideoEntity> {
        val others = rawVideos.asSequence().filter { it.localUri != video.localUri }
        val sameAuthor = others
            .filter { authorKeyOf(it) == authorKeyOf(video) }
            .sortedByDescending { it.createdAt ?: it.addedAt }
            .take(max)
            .toList()
        if (sameAuthor.isNotEmpty()) return sameAuthor
        val wanted = video.tagsCsv?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        if (wanted.isEmpty()) return emptyList()
        return others
            .filter { v -> wanted.any { hasTag(v, it) } }
            .sortedByDescending { it.createdAt ?: it.addedAt }
            .take(max)
            .toList()
    }

    private fun buildTagBuckets(): List<TagBucket> {
        // 单次遍历统计各标签出现次数，避免 O(标签 × 视频) 的重复拆分拖慢主线程（点击分类卡顿的主因）。
        val counts = HashMap<String, Int>()
        // 同一趟遍历顺手收「本地出现过的标签名」（按小写去重、保留原样大小写）——
        //   全局筛选面板「标签·精确」的联想候选要用它，这样零额外开销（见 [localTagNames]）。
        val names = HashMap<String, String>()
        for (v in rawVideos) {
            val csv = v.tagsCsv ?: continue
            for (raw in csv.split(",")) {
                if (raw.isBlank()) continue
                val key = raw.lowercase()
                counts[key] = (counts[key] ?: 0) + 1
                names.putIfAbsent(key, raw.trim())
            }
        }
        // 只在内容真的变了才换实例（它是一条独立流，换实例 = 订阅方重组一次）。
        val sorted = names.values.sortedBy { it.lowercase() }
        if (sorted != _localTagNames.value) _localTagNames.value = sorted
        val list = rawTags.map { t ->
            TagBucket(t.id, t.name, counts[t.id.lowercase()] ?: 0)
        }
        return VideoSorter.sortTagBuckets(list, sortMode)
    }

    private fun buildTimeBuckets(): List<TimeBucket> {
        val zone = ZoneId.systemDefault()
        val withDate = rawVideos.filter { it.createdAt != null }
        val dated = withDate.groupBy { v ->
            YearMonth.from(Instant.ofEpochMilli(v.createdAt!!).atZone(zone))
        }.map { (ym, vs) ->
            val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            TimeBucket(
                key = ym.toString(),
                label = "${ym.year}年${ym.monthValue}月",
                startEpochMs = start,
                endEpochMs = end,
                count = vs.size
            )
        }

        val unknownCount = rawVideos.count { it.createdAt == null }
        val unknown = if (unknownCount > 0) {
            listOf(
                TimeBucket(
                    key = TIME_UNKNOWN_KEY,
                    label = "未识别时间",
                    startEpochMs = 0,
                    endEpochMs = 0,
                    count = unknownCount
                )
            )
        } else emptyList()

        return VideoSorter.sortTimeBuckets(dated, unknown, sortMode)
    }

    private fun hasTag(v: VideoEntity, tag: String): Boolean =
        v.tagsCsv?.split(",")?.any { it.equals(tag, true) } == true

    companion object {
        /** 搜索防抖窗口（毫秒）：输入框本身立刻回显，只把"重算"推迟到这里。 */
        private const val QUERY_DEBOUNCE_MS = 250L

        /** 播放页「相关推荐」本地部分的最大条数。 */
        private const val RELATED_VIDEO_MAX = 12

        private const val TIME_UNKNOWN_KEY = "__unknown__"
        private const val ROOT_FOLDER_KEY = "__root__"
        private const val ROOT_FOLDER_NAME = "（根目录）"

        /** 无 authorId（未匹配到 iwara）的本地视频统一归到这个分组键。 */
        const val UNKNOWN_AUTHOR_KEY = "__unknown_author__"

        /** 上面那个分组的显示名。 */
        const val UNKNOWN_AUTHOR_NAME = "未指定"
    }
}
