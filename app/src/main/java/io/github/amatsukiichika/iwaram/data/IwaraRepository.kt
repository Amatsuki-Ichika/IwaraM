package io.github.amatsukiichika.iwaram.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.amatsukiichika.iwaram.ui.DiagLog
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

/**
 * 业务仓库：连接 Room 数据库、iwara API 与本地文件系统。
 */
class IwaraRepository(
    internal val context: Context,
    private val db: AppDatabase
) {
    internal val dao = db.videoDao()
    internal val historyDao = db.watchHistoryDao()

    internal val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /**
     * 半透明材质的默认不透明度（0f..1f）。
     *
     * 与 `ui/components/GlassMaterial.kt` 里 `GlassSettings.opacity` 的默认值**必须一致**
     * （那里是不提供设置时的兜底，这里是读不到 pref 时的兜底）。
     */
    private companion object {
        /** 与 `GlassMaterial.DEFAULT_GLASS_OPACITY` 同值（0–100% 语义，默认 80%）。 */
        const val DEFAULT_GLASS_OPACITY = 0.80f

        /** 与 `GlassMaterial` 的默认材质档位一致：2 = 液态玻璃（磨砂）。 */
        const val DEFAULT_GLASS_MODE = 2
    }

    internal val gson = Gson()
    internal val downloadHistoryType = object : TypeToken<List<DownloadHistoryEntry>>() {}.type
    internal val pendingDownloadType = object : TypeToken<List<PendingDownload>>() {}.type

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
        _rootName.value = name ?: ""
    }

    fun getRootName(): String? =
        prefs.getString("root_name", null)?.takeIf { it.isNotBlank() }

    private val _rootName = MutableStateFlow(getRootName().orEmpty())

    /** 根目录显示名的单一真源（导入扫描内部更新，设置页经流自动刷新）。 */
    val rootName: StateFlow<String> = _rootName.asStateFlow()

    /** 在线视频的自定义下载目录；null = 跟随管理器根目录（默认）。 */
    fun setDownloadRoot(treeUri: String?) {
        prefs.edit().putString("download_root_uri", treeUri).apply()
    }

    fun getDownloadRoot(): String? =
        prefs.getString("download_root_uri", null)?.takeIf { it.isNotBlank() }

    fun setDownloadRootName(name: String?) {
        prefs.edit().putString("download_root_name", name ?: "").apply()
        _downloadRootName.value = name ?: ""
    }

    fun getDownloadRootName(): String? =
        prefs.getString("download_root_name", null)?.takeIf { it.isNotBlank() }

    private val _downloadRootName = MutableStateFlow(getDownloadRootName().orEmpty())

    /** 自定义下载目录显示名的单一真源；空串 = 跟随管理器根目录。 */
    val downloadRootName: StateFlow<String> = _downloadRootName.asStateFlow()

    fun setFullscreenOrientation(value: String) {
        prefs.edit().putString("fs_orientation", value).apply()
        _fullscreenOrientation.value = value
    }

    fun getFullscreenOrientation(): String =
        prefs.getString("fs_orientation", "smart") ?: "smart"

    private val _fullscreenOrientation = MutableStateFlow(getFullscreenOrientation())

    /** 全屏方向偏好的单一真源。 */
    val fullscreenOrientation: StateFlow<String> = _fullscreenOrientation.asStateFlow()

    /* ---------- 主题与显示偏好 ---------- */

    fun setThemeMode(value: String) {
        prefs.edit().putString("theme_mode", value).apply()
        _themeMode.value = value
    }

    fun getThemeMode(): String = prefs.getString("theme_mode", "system") ?: "system"

    private val _themeMode = MutableStateFlow(getThemeMode())

    /** 主题模式的单一真源。 */
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    fun setThemeColor(value: String) {
        prefs.edit().putString("theme_color", value).apply()
        _themeColor.value = value
    }

    fun getThemeColor(): String = prefs.getString("theme_color", "blue") ?: "blue"

    private val _themeColor = MutableStateFlow(getThemeColor())

    /** 主题色的单一真源。 */
    val themeColor: StateFlow<String> = _themeColor.asStateFlow()

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
     * 材质方案（外观页）：三档 —— **0 关（实心不透明）/ 1 半透明 / 2 液态玻璃（磨砂，默认）**。
     *
     * 历史值一次性迁移（读旧键 `glass_mode` → 映射 → 写新键 `glass_mode_v2`）：
     * 旧 1/2（省电/精细真模糊）→ **2 液态玻璃**（意图最接近）；旧 0（关闭=色罩）/ 3（模拟磨砂）→ **1 半透明**
     * （视觉等价）。**全新安装**（两键都没有）直接落默认档 2，不进迁移分支。
     * 新键落地后旧键不再读，改档写的是新值域 0..2。
     */
    fun setGlassMode(value: Int) {
        val v = value.coerceIn(0, 2)
        prefs.edit().putInt("glass_mode_v2", v).apply()
        _glassMode.value = v
    }

    fun getGlassMode(): Int {
        if (prefs.contains("glass_mode_v2")) {
            return prefs.getInt("glass_mode_v2", DEFAULT_GLASS_MODE)
        }
        if (!prefs.contains("glass_mode")) {
            // 全新安装（没有旧值）→ 直接用新默认，不写脏数据。
            return DEFAULT_GLASS_MODE
        }
        val legacy = prefs.getInt("glass_mode", 0)
        val migrated = if (legacy == 1 || legacy == 2) 2 else 1
        prefs.edit().putInt("glass_mode_v2", migrated).apply()
        return migrated
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

    private val _videoSort = MutableStateFlow(getVideoSort())

    /** 视频流排序偏好的单一真源。 */
    val videoSort: StateFlow<String> = _videoSort.asStateFlow()

    fun setVideoSort(value: String) {
        prefs.edit().putString(KEY_VIDEO_SORT, value).apply()
        _videoSort.value = value
    }

    fun getRating(): String = prefs.getString("iwara_rating", "all") ?: "all"

    private val _rating = MutableStateFlow(getRating())

    /** 在线页上次手选分级的单一真源（与本地库 [localRating] 相互独立）。 */
    val rating: StateFlow<String> = _rating.asStateFlow()

    fun setRating(value: String) {
        prefs.edit().putString("iwara_rating", value).apply()
        _rating.value = value
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

    private val _localRating = MutableStateFlow(getLocalRating())

    /** 本地库分级筛选的单一真源。 */
    val localRating: StateFlow<String> = _localRating.asStateFlow()

    fun setLocalRating(value: String) {
        prefs.edit().putString("local_rating", value).apply()
        _localRating.value = value
    }

    /**
     * 隐藏未匹配的本地视频：开启后本地库主列表不显示还没匹配到 Iwara id 的条目
     * （文件名解析不出 id 且未手动指定的）。判据只看 [VideoEntity.iwaraId] 是否为空 ——
     * 「有 id 但元数据还没拉到」是瞬态（下载后/弱网重试期间），不能算未匹配，否则列表会闪。
     * 单一真源同 [defaultR18]：StateFlow + prefs 双写。
     */
    fun getHideUnmatched(): Boolean = prefs.getBoolean("hide_unmatched", false)

    private val _hideUnmatched = MutableStateFlow(getHideUnmatched())

    /** 「隐藏未匹配的视频」的单一真源。 */
    val hideUnmatched: StateFlow<Boolean> = _hideUnmatched.asStateFlow()

    fun setHideUnmatched(value: Boolean) {
        prefs.edit().putBoolean("hide_unmatched", value).apply()
        _hideUnmatched.value = value
    }

    fun getQualityPref(): String = prefs.getString("iwara_quality", "Source") ?: "Source"

    private val _qualityPref = MutableStateFlow(getQualityPref())

    /** 在线播放默认分辨率的单一真源。 */
    val qualityPref: StateFlow<String> = _qualityPref.asStateFlow()

    fun setQualityPref(value: String) {
        prefs.edit().putString("iwara_quality", value).apply()
        _qualityPref.value = value
    }

    /** 下载分辨率：独立于在线播放的默认分辨率。 */
    fun getDownloadQuality(): String = prefs.getString("iwara_download_quality", "Source") ?: "Source"

    private val _downloadQuality = MutableStateFlow(getDownloadQuality())

    /** 下载分辨率的单一真源。 */
    val downloadQuality: StateFlow<String> = _downloadQuality.asStateFlow()

    fun setDownloadQuality(value: String) {
        prefs.edit().putString("iwara_download_quality", value).apply()
        _downloadQuality.value = value
    }

    /** 超分（Anime4K 风格）：默认是否启用与模型档位（C/B/A）。 */
    fun getSuperResEnabled(): Boolean = prefs.getBoolean("sr_enabled", false)

    private val _superResEnabled = MutableStateFlow(getSuperResEnabled())

    /** 超分默认开关的单一真源。 */
    val superResEnabled: StateFlow<Boolean> = _superResEnabled.asStateFlow()

    fun setSuperResEnabled(value: Boolean) {
        prefs.edit().putBoolean("sr_enabled", value).apply()
        _superResEnabled.value = value
    }

    // 默认 "A4K_3D"：MMD / 3D 画面优先（Anime4K 3D 专用放大 + 抗锯齿）。
    // 旧版本存的是自写锐化的 "A"/"B"/"C" —— UI 侧统一走 SuperRes.normalize() 迁移。
    fun getSuperResModel(): String = prefs.getString("sr_model", "A4K_3D") ?: "A4K_3D"

    private val _superResModel = MutableStateFlow(getSuperResModel())

    /** 超分模型档位的单一真源（旧档位由 VideoViewModel 启动时迁移落库）。 */
    val superResModel: StateFlow<String> = _superResModel.asStateFlow()

    fun setSuperResModel(value: String) {
        prefs.edit().putString("sr_model", value).apply()
        _superResModel.value = value
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
    internal val prefsJsonLock = Any()

    /* ---------- 观看历史 ---------- */

    /**
     * 观看记录的写入方是 PlayerScreen（onDispose 时页面组合已在外走），不能用页面作用域；
     * 这里持一个私有 IO 作用域做 fire-and-forget，单线程顺序落库（Room 串行写，无并发抖动）。
     */
    internal val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 观看历史上限，超出按 lastWatchAt 最旧裁剪。 */
    internal val watchHistoryLimit = 500

    /**
     * 扫描导入的攒批大小 —— 每这么多个新文件做一次批量 upsert
     * （= 一次写库事务 + 一次 observeAll 失效），见 [importFolder] 内注释。
     */
    internal val importBatchSize = 20

    /* ---------- Token（可选的登录模式；留空即公开无 token 模式） ---------- */

    fun setAccessToken(token: String?) {
        if (token.isNullOrBlank()) prefs.edit().remove("access_token").apply()
        // 手动粘贴的 access token 同样落盘前加密（Keystore 不可用时降级明文，保持旧行为）。
        else prefs.edit().putString("access_token", TokenCrypto.encrypt(token) ?: token).apply()
        IwaraApi.accessToken = token?.takeIf { it.isNotBlank() }
        _accessToken.value = token?.takeIf { it.isNotBlank() }.orEmpty()
    }

    /** 兼容旧版明文与 `v1:` 密文；密文解密失败（密钥丢失）按公开无 token 模式处理。 */
    fun getAccessToken(): String? =
        prefs.getString("access_token", null)?.takeIf { it.isNotBlank() }?.let { TokenCrypto.decrypt(it) }

    private val _accessToken = MutableStateFlow(getAccessToken().orEmpty())

    /** 手动 access token 的单一真源（空串 = 公开无 token 模式）。 */
    val accessToken: StateFlow<String> = _accessToken.asStateFlow()

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
