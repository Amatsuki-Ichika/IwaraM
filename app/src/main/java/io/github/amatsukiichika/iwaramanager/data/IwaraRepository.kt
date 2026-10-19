package io.github.amatsukiichika.iwaramanager.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.amatsukiichika.iwaramanager.ui.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 视频流排序偏好的 prefs 键（文件级，供读取与一次性重置共用）。 */
private const val KEY_VIDEO_SORT = "iwara_video_sort"

/**
 * 默认排序改「最新」时的一次性重置标记。
 * 置位后 [IwaraRepository.getVideoSort] 不再干预手选值（只重置一次）。
 */
private const val KEY_SORT_RESET_V1 = "iwara_video_sort_reset_v1"

/** 视频流默认排序：date（「最新」）。 */
internal const val DEFAULT_VIDEO_SORT = "date"

/**
 * 排序偏好的落点（**纯函数**，便于单测）。
 *
 * - [migrated] = false（从未重置过）⇒ 无论存了什么，统一给「最新」一次，
 *   因为旧默认 `trending` 与手选值在 prefs 里**无法区分**，只改默认值会让一部分机器"改了没生效"；
 * - [migrated] = true ⇒ 尊重手选值，只在读到空值时回退默认。
 */
internal fun resolveVideoSort(stored: String?, migrated: Boolean): String =
    if (!migrated) DEFAULT_VIDEO_SORT
    else stored?.takeIf { it.isNotBlank() } ?: DEFAULT_VIDEO_SORT

/**
 * 业务仓库：连接 Room 数据库、iwara API 与本地文件系统。
 */
class IwaraRepository(
    private val context: Context,
    private val db: AppDatabase
) {
    private val dao = db.videoDao()
    private val historyDao = db.watchHistoryDao()

    private val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /**
     * 半透明材质的默认不透明度（0f..1f）。
     *
     * 与 `ui/components/GlassMaterial.kt` 里 `GlassSettings.opacity` 的默认值**必须一致**
     * （那里是不提供设置时的兜底，这里是读不到 pref 时的兜底）。
     */
    private companion object {
        /** 与 `GlassMaterial.DEFAULT_GLASS_OPACITY` 同值（0–100% 语义，默认 78%）。 */
        const val DEFAULT_GLASS_OPACITY = 0.78f
    }

    private val gson = Gson()
    private val downloadHistoryType = object : TypeToken<List<DownloadHistoryEntry>>() {}.type
    private val pendingDownloadType = object : TypeToken<List<PendingDownload>>() {}.type

    init {
        // 与 IwaraSession.restore 协调初始化——IwaraApp.onCreate 先行 restore，
        // 已把会话 access token 同步给 IwaraApi；本处只在 legacy 手动 token 存在时覆盖
        // （手动模式优先级更高，与 restore 的语义一致），不存在时保留会话 token，
        // 避免本 init 在 repository 首次创建时用 null 抹掉已恢复的鉴权头。
        prefs.getString("access_token", null)?.takeIf { it.isNotBlank() }?.let {
            IwaraApi.accessToken = it
        }
    }

    /* ---------- 视频 ID 解析 ---------- */

    fun parseIwaraId(input: String?): String? = IwaraIdParser.parse(input)

    /* ---------- 根目录持久化 ---------- */

    fun setRoot(treeUri: String) {
        prefs.edit().putString("root_uri", treeUri).apply()
    }

    fun getRoot(): String? = prefs.getString("root_uri", null)

    fun setRootName(name: String?) {
        prefs.edit().putString("root_name", name ?: "").apply()
    }

    fun getRootName(): String? =
        prefs.getString("root_name", null)?.takeIf { it.isNotBlank() }

    /** 在线视频的自定义下载目录；null = 跟随管理器根目录（默认）。 */
    fun setDownloadRoot(treeUri: String?) {
        prefs.edit().putString("download_root_uri", treeUri).apply()
    }

    fun getDownloadRoot(): String? =
        prefs.getString("download_root_uri", null)?.takeIf { it.isNotBlank() }

    fun setDownloadRootName(name: String?) {
        prefs.edit().putString("download_root_name", name ?: "").apply()
    }

    fun getDownloadRootName(): String? =
        prefs.getString("download_root_name", null)?.takeIf { it.isNotBlank() }

    fun setFullscreenOrientation(value: String) {
        prefs.edit().putString("fs_orientation", value).apply()
    }

    fun getFullscreenOrientation(): String =
        prefs.getString("fs_orientation", "auto") ?: "auto"

    /* ---------- 主题与显示偏好 ---------- */

    fun setThemeMode(value: String) {
        prefs.edit().putString("theme_mode", value).apply()
    }

    fun getThemeMode(): String = prefs.getString("theme_mode", "system") ?: "system"

    fun setThemeColor(value: String) {
        prefs.edit().putString("theme_color", value).apply()
    }

    fun getThemeColor(): String = prefs.getString("theme_color", "blue") ?: "blue"

    fun setShowTags(value: Boolean) {
        prefs.edit().putBoolean("show_tags", value).apply()
        _showTags.value = value
    }

    fun getShowTags(): Boolean = prefs.getBoolean("show_tags", true)

    private val _showTags = MutableStateFlow(getShowTags())

    /** 卡片是否显示标签的单一真源（设置页经窄流订阅，不再收整份 UiState）。 */
    val showTags: StateFlow<Boolean> = _showTags.asStateFlow()

    /* ---------- 半透明材质（底栏玻璃 / 角标胶囊统一走这一份） ---------- */

    /**
     * 材质不透明度 0f..1f（外观页滑条）。
     *
     * 语义为 **0 = 完全透明 / 1 = 完全不透明**（线性直取，见
     * `GlassMaterial.glassMaterialFor`）。旧值域是"插进一段区间"的中间量，所以这里做**一次性迁移**：
     * 旧公式浅色 alpha = lerp(0.55, 0.96, old) ⇒ 新值 = 0.55 + 0.41×old（默认 0.55 → 0.7755 ≈ 78%），
     * 于是"不动滑条观感不变"。新键 `glass_opacity_v2` 落地后旧键不再读。
     */
    fun setGlassOpacity(value: Float) {
        val v = value.coerceIn(0f, 1f)
        prefs.edit().putFloat("glass_opacity_v2", v).apply()
        _glassOpacity.value = v
    }

    fun getGlassOpacity(): Float {
        if (!prefs.contains("glass_opacity_v2")) {
            // 首次启动（没有旧值）→ 直接用新默认，不写脏数据。
            if (!prefs.contains("glass_opacity")) return DEFAULT_GLASS_OPACITY
            val legacy = prefs.getFloat("glass_opacity", 0.55f).coerceIn(0f, 1f)
            val migrated = (0.55f + 0.41f * legacy).coerceIn(0f, 1f)
            prefs.edit().putFloat("glass_opacity_v2", migrated).apply()
            return migrated
        }
        return prefs.getFloat("glass_opacity_v2", DEFAULT_GLASS_OPACITY)
    }

    private val _glassOpacity = MutableStateFlow(getGlassOpacity())

    /** 材质不透明度的单一真源。 */
    val glassOpacity: StateFlow<Float> = _glassOpacity.asStateFlow()

    /**
     * 材质方案（外观页）：三档 —— **0 关（实心不透明）/ 1 半透明（默认）/ 2 液态玻璃**。
     *
     * 历史值一次性迁移（读旧键 `glass_mode` → 映射 → 写新键 `glass_mode_v2`）：
     * 旧 1/2（省电/精细真模糊）→ **2 液态玻璃**（意图最接近）；旧 0（关闭=色罩）/ 3（模拟磨砂）/ 缺省 → **1 半透明**
     * （视觉等价，且新能力不作默认）。新键落地后旧键不再读，改档写的是新值域 0..2。
     */
    fun setGlassMode(value: Int) {
        val v = value.coerceIn(0, 2)
        prefs.edit().putInt("glass_mode_v2", v).apply()
        _glassMode.value = v
    }

    fun getGlassMode(): Int {
        if (!prefs.contains("glass_mode_v2")) {
            val legacy = prefs.getInt("glass_mode", 0)
            val migrated = if (legacy == 1 || legacy == 2) 2 else 1
            prefs.edit().putInt("glass_mode_v2", migrated).apply()
            return migrated
        }
        return prefs.getInt("glass_mode_v2", 1)
    }

    private val _glassMode = MutableStateFlow(getGlassMode())

    /** 磨砂方案的单一真源。 */
    val glassMode: StateFlow<Int> = _glassMode.asStateFlow()

    /* ---------- Iwara 浏览模块偏好（客户端 tab 用，与上述互不影响） ---------- */

    /**
     * 视频流的排序偏好读取。
     *
     * 默认值从 `trending` 改为 `date`，并做**一次性重置**（[KEY_SORT_RESET_V1]）。
     *
     * 为什么需要"一次性重置"而不是只改默认值：`trending` 作为旧默认**只在手动切过排序时
     * 才会写进 prefs**，但也可能确实手动选过"趋势"。只改默认值 ⇒ 这类机器升级后仍是趋势，
     * 感受就是"改了没生效"。所以把**所有**机器（含已存过值的）统一重置为「最新」
     * 一次，之后怎么选都按存的来（标记位保证只发生一次）。
     *
     * 顺带：`date` 对**偏移式分页**友好得多 —— 新内容只会插在列表头部，翻页时的页边界漂移
     * 远小于"趋势榜打分实时变动"的排序（重复/漏条的主要来源）。
     */
    fun getVideoSort(): String {
        val migrated = prefs.getBoolean(KEY_SORT_RESET_V1, false)
        val resolved = resolveVideoSort(prefs.getString(KEY_VIDEO_SORT, null), migrated)
        if (!migrated) {
            prefs.edit()
                .putBoolean(KEY_SORT_RESET_V1, true)
                .putString(KEY_VIDEO_SORT, resolved)
                .apply()
        }
        return resolved
    }

    fun setVideoSort(value: String) {
        prefs.edit().putString(KEY_VIDEO_SORT, value).apply()
    }

    fun getRating(): String = prefs.getString("iwara_rating", "all") ?: "all"

    fun setRating(value: String) {
        prefs.edit().putString("iwara_rating", value).apply()
    }

    /**
     * 默认 R18：开启后**隐藏分级筛选项**并在在线/本地都强制只看 R18。
     * 与 [getRating]（上次手选的分级）互不覆盖 —— 关闭后回到原来的选择。
     *
     * 这里同时是**单一真源**（[defaultR18] 这个 StateFlow）。
     * 若本地库与在线页各自持有一份内存副本、互不通知 —— 设置页只更新本地库那份，
     * 在线页读的那份 `IwaraClientViewModel.setDefaultR18()` 就不会被调用，
     * 于是"开了默认 R18，在线页的分级筛选照样显示"，且**必须重启 App 才生效**。
     * 现在两个 ViewModel 都订阅这个 flow，设置页只改这一处，两边立即同时生效。
     */
    fun getDefaultR18(): Boolean = prefs.getBoolean("default_r18", false)

    private val _defaultR18 = MutableStateFlow(getDefaultR18())

    /** 默认 R18 的单一真源（持久化 + 广播）。 */
    val defaultR18: StateFlow<Boolean> = _defaultR18.asStateFlow()

    fun setDefaultR18(value: Boolean) {
        prefs.edit().putBoolean("default_r18", value).apply()
        _defaultR18.value = value
    }

    /** 卡片是否显示 R18 角标（独立开关，默认显示）。同样是单一真源（见 [showR18Tag]）。 */
    fun getShowR18Tag(): Boolean = prefs.getBoolean("show_r18_tag", true)

    private val _showR18Tag = MutableStateFlow(getShowR18Tag())

    val showR18Tag: StateFlow<Boolean> = _showR18Tag.asStateFlow()

    fun setShowR18Tag(value: Boolean) {
        prefs.edit().putBoolean("show_r18_tag", value).apply()
        _showR18Tag.value = value
    }

    /* ---------- Debug 模式开关 ---------- */

    /**
     * 开发者细节开关：关闭（默认）= 界面报错只显示人话短句，技术细节（错误码/URL/异常串）
     * 只进诊断日志；开启 = 界面直接显示完整技术细节，并恢复诊断模块入口
     * （播放页 ⋮ / 设置页「运行状态 / 日志」）。诊断日志本身**常开记录**，不丢信息。
     * 单一真源同 [defaultR18]：StateFlow + prefs 双写；MainActivity 经 CompositionLocal 分发
     * （分发不构成第二副本）。
     */
    fun getDebugDetails(): Boolean = prefs.getBoolean("debug_details", false)

    private val _debugDetails = MutableStateFlow(getDebugDetails())

    val debugDetails: StateFlow<Boolean> = _debugDetails.asStateFlow()

    fun setDebugDetails(value: Boolean) {
        prefs.edit().putBoolean("debug_details", value).apply()
        _debugDetails.value = value
    }


    /** 本地库的分级筛选（all / general / ecchi）；与在线页的 [getRating] 相互独立。 */
    fun getLocalRating(): String = prefs.getString("local_rating", "all") ?: "all"

    fun setLocalRating(value: String) {
        prefs.edit().putString("local_rating", value).apply()
    }

    fun getQualityPref(): String = prefs.getString("iwara_quality", "Source") ?: "Source"

    fun setQualityPref(value: String) {
        prefs.edit().putString("iwara_quality", value).apply()
    }

    /** 下载分辨率：独立于在线播放的默认分辨率。 */
    fun getDownloadQuality(): String = prefs.getString("iwara_download_quality", "Source") ?: "Source"

    fun setDownloadQuality(value: String) {
        prefs.edit().putString("iwara_download_quality", value).apply()
    }

    /** 超分（Anime4K 风格）：默认是否启用与模型档位（C/B/A）。 */
    fun getSuperResEnabled(): Boolean = prefs.getBoolean("sr_enabled", false)

    fun setSuperResEnabled(value: Boolean) {
        prefs.edit().putBoolean("sr_enabled", value).apply()
    }

    // 默认 "A4K_3D"：MMD / 3D 画面优先（Anime4K 3D 专用放大 + 抗锯齿）。
    // 旧版本存的是自写锐化的 "A"/"B"/"C" —— UI 侧统一走 SuperRes.normalize() 迁移。
    fun getSuperResModel(): String = prefs.getString("sr_model", "A4K_3D") ?: "A4K_3D"

    fun setSuperResModel(value: String) {
        prefs.edit().putString("sr_model", value).apply()
    }

    /**
     * 下载历史 / 待续传都是「prefs 单键 JSON 的**读-改-写**」，而它会被
     * `DownloadService`（IO 线程）与 UI（主线程）**并发**调用 —— 读与写之间原本没有互斥：
     * 两个线程各自读到同一份旧列表、再各自写回，后写者就把先写者的记录**丢掉了**（lost update），
     * 表现为"续传记录无痕消失"（与静默吞错叠加后极难归因）。
     *
     * 这里用一把私有锁把整段读-改-写串起来。用 `synchronized` 而不是 `Mutex`：这几个函数
     * 都不是 suspend、内部也不调用挂起函数，没必要把 suspend 传染给所有调用方。
     * （`synchronized` 可重入，所以锁内再调 [loadDownloadHistory] / [loadPendingDownloads] 安全。）
     */
    private val prefsJsonLock = Any()

    /** 下载历史：最新在前，同一视频只保留最近一次记录，上限 100 条。 */
    fun addDownloadHistory(entry: DownloadHistoryEntry) = synchronized(prefsJsonLock) {
        val list = loadDownloadHistory().filterNot { it.videoId == entry.videoId }.toMutableList()
        list.add(0, entry)
        prefs.edit().putString("download_history_cache", gson.toJson(list.take(100))).apply()
    }

    fun loadDownloadHistory(): List<DownloadHistoryEntry> =
        prefs.getString("download_history_cache", null)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                runCatching {
                    gson.fromJson<List<DownloadHistoryEntry>>(it, downloadHistoryType)
                }.getOrNull()
            }
            .orEmpty()

    /** 移除某视频的下载历史记录（「移除任务 / 移除任务和文件」用）。 */
    fun removeDownloadHistory(videoId: String) = synchronized(prefsJsonLock) {
        val rest = loadDownloadHistory().filterNot { it.videoId == videoId }
        prefs.edit().putString("download_history_cache", gson.toJson(rest)).apply()
    }

    /** 未完成下载（暂停/失败）持久化：重启后「下载列表」还能看到并可续传/重新下载。 */
    fun savePendingDownload(p: PendingDownload) = synchronized(prefsJsonLock) {
        val list = loadPendingDownloads().filterNot { it.videoId == p.videoId }.toMutableList()
        list.add(0, p)
        prefs.edit().putString("download_pending_cache", gson.toJson(list.take(50))).apply()
    }

    fun removePendingDownload(videoId: String) = synchronized(prefsJsonLock) {
        val rest = loadPendingDownloads().filterNot { it.videoId == videoId }
        prefs.edit().putString("download_pending_cache", gson.toJson(rest)).apply()
    }

    fun loadPendingDownloads(): List<PendingDownload> =
        prefs.getString("download_pending_cache", null)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                runCatching {
                    gson.fromJson<List<PendingDownload>>(it, pendingDownloadType)
                }.onFailure { e ->
                    // 解析失败若无痕迹地清空整个续传列表，会变成"数据不对但查不出来"。
                    // 这里只记原文长度，绝不打原始内容（内含文件路径等本地信息）。
                    DiagLog.log("Repo", "待续传记录解析失败(${e.javaClass.simpleName})，已忽略 ${it.length} 字符的旧记录")
                }.getOrNull()
            }
            .orEmpty()

    /** 播放列表离线缓存（JSON），断网时「我的」页展示。 */
    fun savePlaylistsCache(json: String) {
        prefs.edit().putString("playlists_cache", json).apply()
    }

    fun loadPlaylistsCache(): String? = prefs.getString("playlists_cache", null)?.takeIf { it.isNotBlank() }

    /* ---------- 观看历史 ---------- */

    /**
     * 观看记录的写入方是 PlayerScreen（onDispose 时页面组合已在外走），不能用页面作用域；
     * 这里持一个私有 IO 作用域做 fire-and-forget，单线程顺序落库（Room 串行写，无并发抖动）。
     */
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 观看历史上限，超出按 lastWatchAt 最旧裁剪。 */
    private val watchHistoryLimit = 500

    /**
     * 扫描导入的攒批大小 —— 每这么多个新文件做一次批量 upsert
     * （= 一次写库事务 + 一次 observeAll 失效），见 [importFolder] 内注释。
     */
    private val importBatchSize = 20

    /**
     * 记一条观看（fire-and-forget）。同 key 重看经唯一索引 REPLACE 原地覆盖 = 天然置顶；
     * 超上限裁最旧。key 推不出（无 iwaraId 且无 localUri）直接放弃，不写脏行。
     */
    fun recordWatch(
        iwaraId: String?,
        localUri: String?,
        title: String,
        author: String?,
        authorId: String?,
        thumbnail: String?,
        positionMs: Long = 0,
        durationMs: Long = 0,
    ) {
        val key = watchHistoryKey(iwaraId, localUri) ?: return
        historyScope.launch {
            runCatching {
                historyDao.upsert(
                    WatchHistoryEntity(
                        videoKey = key,
                        iwaraId = iwaraId?.takeIf { it.isNotBlank() },
                        localUri = localUri?.takeIf { it.isNotBlank() },
                        title = title,
                        author = author,
                        authorId = authorId,
                        thumbnail = thumbnail,
                        isLocal = !localUri.isNullOrBlank(),
                        lastWatchAt = System.currentTimeMillis(),
                        positionMs = positionMs.coerceAtLeast(0),
                        durationMs = durationMs.coerceAtLeast(0),
                    )
                )
                val count = historyDao.count()
                // 裁剪收进单条 SQL（逐条 deleteById 会给每条一个事务）。
                if (count > watchHistoryLimit) {
                    historyDao.deleteOldest(count - watchHistoryLimit)
                }
            }.onFailure { e ->
                DiagLog.log("Repo", "观看记录写入失败(${e.javaClass.simpleName}): ${e.message}")
            }
        }
    }

    /**
     * 更新进度（fire-and-forget）。记录不存在时忽略——进度更新只跟随首帧那条记录，
     * 不凭空造行；durationMs > 0 时顺带补写总时长（首帧时常常还拿不到）。
     */
    fun updateWatchProgress(key: String, positionMs: Long, durationMs: Long) {
        historyScope.launch {
            runCatching {
                val row = historyDao.byKey(key) ?: return@launch
                historyDao.upsert(
                    row.copy(
                        positionMs = positionMs.coerceAtLeast(0),
                        durationMs = if (durationMs > 0) durationMs else row.durationMs,
                        lastWatchAt = System.currentTimeMillis(),
                    )
                )
            }.onFailure { e ->
                DiagLog.log("Repo", "观看进度更新失败(${e.javaClass.simpleName}): ${e.message}")
            }
        }
    }

    fun observeWatchHistory(): Flow<List<WatchHistoryEntity>> = historyDao.observeAll()

    suspend fun deleteWatchHistory(id: Long) = withContext(Dispatchers.IO) { historyDao.deleteById(id) }

    suspend fun clearWatchHistory() = withContext(Dispatchers.IO) { historyDao.clearAll() }

    /** 历史行点击本地条目时查"文件还在不在库里"。 */
    suspend fun findVideoByUri(uri: String): VideoEntity? = withContext(Dispatchers.IO) { dao.byUri(uri) }

    /* ---------- Token（可选的登录模式；留空即公开无 token 模式） ---------- */

    fun setAccessToken(token: String?) {
        if (token.isNullOrBlank()) prefs.edit().remove("access_token").apply()
        else prefs.edit().putString("access_token", token).apply()
        IwaraApi.accessToken = token?.takeIf { it.isNotBlank() }
    }

    fun getAccessToken(): String? = prefs.getString("access_token", null)?.takeIf { it.isNotBlank() }

    /* ---------- 扫描导入 ---------- */

    /**
     * 扫描 SAF 树下的所有视频文件，逐个入库并拉取 iwara 元数据。
     */
    suspend fun importFolder(treeUri: Uri, onProgress: (done: Int, total: Int) -> Unit) {
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext
            setRootName(root.name)
            val files = mutableListOf<Pair<DocumentFile, String>>()
            collectVideoFiles(root, "", files)

            // 一次性把已入库的条目读进内存判重 —— 对**每个文件**都 `dao.byUri(uri)`
            //   查一次库是 N 次查询。这里一次性取 URI + iwaraId + fileName（最小投影），
            //   既做判重，也供下面「老条目补 ID」用。
            val existing = dao.getAllRefs().associateBy { it.localUri }
            // 同一批里重复出现时只处理一次（防御性：与原实现的 set.add 语义一致）。
            val handled = HashSet<String>(files.size)

            var done = 0
            // 新文件**攒批落库**（每 [importBatchSize] 个一次批量 upsert）。
            //   逐文件 upsert 会在千级库扫描时写库千次，每次写库都触发 observeAll 重发射 ⇒
            //   全链路（重算 / 列表 diff / 作者页过滤）被放大千遍。攒批后每批只发一次失效；
            //   元数据拉取是逐条网络请求（天然节流），放在批落库之后跑 —— 行先可见、资料后到，
            //   最终态与原行为一致（中断时未拉到资料的行由 fetchAllMissing 兜底补拉）。
            val batch = ArrayList<Pair<VideoEntity, String?>>(importBatchSize) // row → 待拉元数据的 iwaraId
            suspend fun flushBatch() {
                if (batch.isEmpty()) return
                dao.upsertAll(batch.map { it.first })
                for ((v, id) in batch) {
                    if (id != null) fetchAndApplyMetadata(v)
                }
                batch.clear()
            }
            for ((f, rel) in files) {
                val name = f.name ?: continue
                val uri = f.uri.toString()
                if (!handled.add(uri)) {
                    done++
                    onProgress(done, files.size)
                    continue
                }
                val row = existing[uri]
                if (row == null) {
                    val id = parseIwaraId(name)
                    val local = VideoEntity(
                        localUri = uri,
                        fileName = name,
                        iwaraId = id,
                        title = name,
                        author = null,
                        authorId = null,
                        createdAt = null,
                        viewCount = null,
                        likeCount = null,
                        commentCount = null,
                        rating = null,
                        thumbnailUrl = null,
                        localThumbnailPath = generateLocalThumbnail(f.uri),
                        relPath = rel.ifBlank { null },
                        tagsCsv = null,
                        metaFetched = false,
                        addedAt = System.currentTimeMillis()
                    )
                    batch.add(local to id)
                    if (batch.size >= importBatchSize) flushBatch()
                } else if (row.iwaraId.isNullOrBlank()) {
                    // 老条目补救：更早的解析器要求 ID 必须含数字，纯字母 ID 全被拒 ⇒ 这些文件早已入库、
                    //   iwaraId 为 null、metaFetched=false。而导入判重是按 URI 命中的，
                    //   所以**只修解析器、重扫一遍也不会重新解析它们**。
                    //   这里对"已存在但没 ID"的条目就地补一次解析 ⇒ 重扫一次即可，
                    //   历史数据自动修复，不需要删库重来。
                    parseIwaraId(row.fileName)?.let { fixedId ->
                        dao.setIwaraId(uri, fixedId)
                        // 重新读出完整实体（upsert 需要整行），再补元数据。
                        dao.byUri(uri)?.let { fetchAndApplyMetadata(it) }
                    }
                }
                done++
                onProgress(done, files.size)
            }
            flushBatch()
        }
    }

    /** 重扫已保存的根目录（用于刷新时增量更新）。 */
    suspend fun rescanStoredRoot(onProgress: (Int, Int) -> Unit) {
        getRoot()?.let { importFolder(Uri.parse(it), onProgress) }
    }

    /** 为所有尚未拉取元数据的视频补拉信息（含手动录入 ID 后的重试）。 */
    suspend fun fetchAllMissing(onProgress: (Int, Int) -> Unit) {
        withContext(Dispatchers.IO) {
            // 查询下推到 SQL（getAll() 整表读进内存再 filter 开销更大）。
            val pending = dao.getMissingMetadata()
            var done = 0
            for (v in pending) {
                fetchAndApplyMetadata(v)
                done++
                onProgress(done, pending.size)
            }
        }
    }

    /** 已入库视频总数。 */
    suspend fun countVideos(): Int = dao.count()

    private fun collectVideoFiles(
        dir: DocumentFile,
        rel: String,
        out: MutableList<Pair<DocumentFile, String>>
    ) {
        for (f in dir.listFiles()) {
            when {
                f.isDirectory -> collectVideoFiles(
                    f,
                    if (rel.isEmpty()) (f.name ?: "") else "$rel/${f.name}",
                    out
                )
                f.isFile && isVideo(f.name) -> out.add(f to rel)
            }
        }
    }

    private fun isVideo(name: String?): Boolean {
        if (name == null) return false
        val n = name.lowercase()
        return n.endsWith(".mp4") || n.endsWith(".webm") ||
            n.endsWith(".mkv") || n.endsWith(".mov") || n.endsWith(".m4v")
    }

    /* ---------- 元数据拉取与落库 ---------- */

    suspend fun fetchAndApplyMetadata(video: VideoEntity) {
        val id = video.iwaraId ?: return
        try {
            val resp = IwaraApi.service.video(id)
            if (resp.title.isNullOrBlank() && resp.id == null) return

            val tags = resp.tags.orEmpty().mapNotNull { it.id }.filter { it.isNotBlank() }
            val updated = video.copy(
                title = resp.title?.takeIf { it.isNotBlank() } ?: video.title,
                author = resp.user?.name,
                authorId = resp.user?.username ?: resp.user?.id,
                createdAt = parseIwaraDate(resp.createdAt),
                viewCount = resp.numViews,
                likeCount = resp.numLikes,
                commentCount = resp.numComments,
                rating = resp.rating,
                thumbnailUrl = resp.file?.id?.let { IwaraApi.thumbnailUrl(it) },
                tagsCsv = tags.joinToString(","),
                metaFetched = true
            )
            // 四步写收进单个 Room 事务（upsert → 清旧关联 → 重建标签/关联），
            // 中途崩溃不再留下孤儿 cross-ref 或半更新的条目。
            dao.upsertVideoWithTags(
                video = updated,
                tags = tags.map { TagEntity(id = it, name = it) },
                refs = tags.map { VideoTagCrossRef(video.localUri, it) }
            )
        } catch (e: Exception) {
            // 网络失败时保留本地条目，等待后续重试。
            // 无痕迹时"检索完成但元数据缺失"类问题无法归因，这里落 DiagLog。
            DiagLog.log("Repo", "元数据拉取失败 ${video.iwaraId}: ${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }

    /** 手动指定某条视频的 iwara ID 后调用。 */
    suspend fun linkIwaraId(video: VideoEntity, id: String) {
        withContext(Dispatchers.IO) {
            dao.upsert(video.copy(iwaraId = id, metaFetched = false))
            fetchAndApplyMetadata(video.copy(iwaraId = id))
        }
    }

    /* ---------- 本地缩略图 ---------- */

    fun generateLocalThumbnail(uri: Uri): String? {
        return try {
            val mmr = MediaMetadataRetriever()
            val frame: Bitmap? = try {
                mmr.setDataSource(context, uri)
                val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                // 从多个时间点取样，遇到过暗（开头黑场）的帧就往后找，最多取 3 帧，控制成本。
                pickDecentFrame(mmr, durationMs)
            } finally {
                mmr.release()
            }
            if (frame == null) {
                null
            } else {
                val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
                val f = File(dir, "thumb_${uri.hashCode()}.jpg")
                f.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                frame.recycle()
                f.absolutePath
            }
        } catch (e: Throwable) {
            // 缩略图失败（含个别大分辨率/损坏文件导致的 OOM）不阻断扫描，回退到在线封面或占位图。
            // 留痕以便区分"文件损坏"与"解析不到封面"。
            DiagLog.log("Repo", "本地缩略图生成失败 uri#${uri.hashCode()}: ${e.javaClass.simpleName}")
            null
        }
    }

    /** 依次在 1s / 20% / 50% 处取样，返回第一帧不是近黑的帧；全部过暗则返回最后一帧。 */
    private fun pickDecentFrame(mmr: MediaMetadataRetriever, durationMs: Long): Bitmap? {
        val durUs = durationMs * 1000
        val candidates = buildList {
            add(1_000_000L)
            if (durUs > 0) {
                add(durUs / 5)
                add(durUs / 2)
            }
        }.filter { durUs <= 0L || it < durUs }.distinct()

        var last: Bitmap? = null
        for (t in candidates) {
            val f = grabFrame(mmr, t) ?: continue
            last?.recycle()
            last = f
            if (!isMostlyBlack(f)) return f
        }
        return last
    }

    /** 优先抽缩小帧（降低大分辨率视频的内存占用，避免 OOM 崩溃），API 26 回退整帧。 */
    private fun grabFrame(mmr: MediaMetadataRetriever, timeUs: Long): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            mmr.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
        } else {
            mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }

    /**
     * 缩放后按平均亮度判断是否为近黑帧（阈值约 12/255）。
     *
     * 注意两点：
     *  1. 逐像素 `getPixel` 每次都是 JNI 调用 —— 32×32 即 1024 次、扫 1000 个视频就是
     *     300 万次 JNI。这里用**一次 `getPixels`** 把 1024 个像素读进 IntArray，再在 Kotlin 里循环。
     *  2. `Bitmap.createScaledBitmap` 在入参**恰好就是 32×32** 时会返回**同一个实例**，
     *     无条件 `recycle()` 会把调用方那一帧回收掉。故加 `!==` 判断（当前调用路径不会命中）。
     */
    private fun isMostlyBlack(bitmap: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val pixels = IntArray(32 * 32)
        small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        var sum = 0L
        for (p in pixels) {
            sum += (android.graphics.Color.red(p) +
                android.graphics.Color.green(p) +
                android.graphics.Color.blue(p)) / 3
        }
        if (small !== bitmap) small.recycle()
        return (sum / 1024) < 12
    }
}

/** 一条下载历史记录（已完成/失败），持久化于 prefs JSON 供「下载列表」展示。 */
data class DownloadHistoryEntry(
    val videoId: String,
    val title: String,
    val quality: String,
    val time: Long,
    val success: Boolean,
    val message: String? = null,
    /** 完成后文件的 SAF 地址 —— 「文件丢失检测」与「移除任务和文件」依赖它；旧记录为 null。 */
    val localUri: String? = null
)

/** 一条未完成下载（排队/暂停/失败），持久化于 prefs JSON，供「继续 / 重新下载」断点续传。 */
data class PendingDownload(
    val videoId: String,
    val title: String,
    val fileName: String,
    val quality: String,
    val received: Long,
    val total: Long,
    /** 可空：排队（queued）/刚开跑（running）的记录还没有半成品文件地址。 */
    val docUri: String? = null,
    val updatedAt: Long,
    /** "queued" / "paused" / "failed" / "running"（与 DownloadState.STATE_* 同值字符串）。 */
    val state: String = "paused"
)
