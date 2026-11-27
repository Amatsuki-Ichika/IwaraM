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
import java.io.FileInputStream

/** 视频流排序偏好的 prefs 键（文件级，供读取与一次性重置共用）。 */
private const val KEY_VIDEO_SORT = "iwara_video_sort"

/**
 * 默认排序改「最新」时的一次性重置标记。
 * 置位后 [IwaraRepository.getVideoSort] 不再干预手选值（只重置一次）。
 */
private const val KEY_SORT_RESET_V1 = "iwara_video_sort_reset_v1"

/** 视频流默认排序：date（「最新」）。 */
internal const val DEFAULT_VIDEO_SORT = "date"

/** SharedPreferences 文件名：仓库与进程启动期（语言恢复）共用这一份。 */
internal const val APP_PREFS_NAME = "prefs"

/** 界面语言偏好键（system / zh / en，见 i18n.LanguageMode）。 */
internal const val KEY_LANGUAGE = "language"

/** 首次使用引导完成标记（「跳过」同样置位）。 */
internal const val KEY_ONBOARDING_DONE = "onboarding_done"

/** 18+ 自我声明标记：引导完成或跳过路径写入；已装用户升级后保持 false，不重复询问（见 docs/decisions/0028）。 */
internal const val KEY_ADULT_CONFIRMED = "adult_confirmed"

/** 截图保护开关：开启时主窗口加 FLAG_SECURE，截屏/录屏/最近任务缩略图为黑屏。 */
internal const val KEY_SCREENSHOT_PROTECTION = "screenshot_protection"

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
    internal val context: Context,
    private val db: AppDatabase
) {
    internal val dao = db.videoDao()
    internal val historyDao = db.watchHistoryDao()

    internal val prefs = context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 半透明材质的默认不透明度（0f..1f）。
     *
     * 与 `ui/components/GlassMaterial.kt` 里 `GlassSettings.opacity` 的默认值**必须一致**
     * （那里是不提供设置时的兜底，这里是读不到 pref 时的兜底）。
     */
    private companion object {
        /** 与 `GlassMaterial.DEFAULT_GLASS_OPACITY` 同值（0–100% 语义，默认 70%）。 */
        const val DEFAULT_GLASS_OPACITY = 0.70f

        /** 与 `GlassMaterial` 的默认材质档位一致：2 = 液态玻璃（磨砂）。 */
        const val DEFAULT_GLASS_MODE = 2
    }

    internal val gson = Gson()
    internal val downloadHistoryType = object : TypeToken<List<DownloadHistoryEntry>>() {}.type
    internal val pendingDownloadType = object : TypeToken<List<PendingDownload>>() {}.type

    init {
        // 旧版本的播放列表离线缓存用全局 key（不区分账号），换号后断网会读到上一个账号的
        // 内容。新 key 按 userId 分区（见 playlistsCacheKey），这一份直接丢弃：
        // 它是纯缓存、可重建，不需要迁移。
        prefs.edit().remove(LEGACY_PLAYLISTS_CACHE_KEY).apply()
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

    /* ---------- 下载自动继续 ---------- */

    /** 打开 App 时是否自动续跑「排队 / 进行中」的下载；暂停的任务永不自动开始。 */
    fun setAutoResumeDownloads(value: Boolean) {
        prefs.edit().putBoolean("auto_resume_downloads", value).apply()
        _autoResumeDownloads.value = value
    }

    fun getAutoResumeDownloads(): Boolean = prefs.getBoolean("auto_resume_downloads", true)

    private val _autoResumeDownloads = MutableStateFlow(getAutoResumeDownloads())

    /** 下载自动继续开关的单一真源。 */
    val autoResumeDownloads: StateFlow<Boolean> = _autoResumeDownloads.asStateFlow()

    /* ---------- 应用内检查更新 ---------- */

    /** 更新通道：stable（正式版）/ beta（含 rc 与预发布）。缺省跟随正式版。 */
    fun setUpdateChannel(value: String) {
        val normalized = if (value == "beta") "beta" else "stable"
        prefs.edit()
            .putString("update_channel", normalized)
            // 切通道后旧通道的「上次检查时间」与「忽略版本」不再适用：清掉让新通道立即生效，
            // 否则用户切到测试版后可能被正式版遗留的忽略项挡住，或要等到下一个周期才检查。
            .putLong("update_last_check", 0L)
            .remove("update_ignored_version")
            .apply()
        _updateChannel.value = normalized
        _updateLastCheck.value = 0L
        _updateIgnoredVersion.value = ""
    }

    fun getUpdateChannel(): String =
        if (prefs.getString("update_channel", "stable") == "beta") "beta" else "stable"

    private val _updateChannel = MutableStateFlow(getUpdateChannel())

    /** 更新通道的单一真源（见 [io.github.amatsukiichika.iwaram.data.UpdateChannel]）。 */
    val updateChannel: StateFlow<String> = _updateChannel.asStateFlow()

    /** 是否按 [getUpdateInterval] 在打开 App 时自动检查更新。 */
    fun setUpdateAutoCheck(value: Boolean) {
        prefs.edit().putBoolean("update_auto_check", value).apply()
        _updateAutoCheck.value = value
    }

    fun getUpdateAutoCheck(): Boolean = prefs.getBoolean("update_auto_check", true)

    private val _updateAutoCheck = MutableStateFlow(getUpdateAutoCheck())

    /** 自动检查开关的单一真源。 */
    val updateAutoCheck: StateFlow<Boolean> = _updateAutoCheck.asStateFlow()

    /** 自动检查频率：each_start / daily / weekly（缺省每周）。 */
    fun setUpdateInterval(value: String) {
        val normalized = value.takeIf { it in setOf("each_start", "daily", "weekly") } ?: "weekly"
        prefs.edit().putString("update_interval", normalized).apply()
        _updateInterval.value = normalized
    }

    fun getUpdateInterval(): String =
        prefs.getString("update_interval", "weekly")?.takeIf { it in setOf("each_start", "daily", "weekly") } ?: "weekly"

    private val _updateInterval = MutableStateFlow(getUpdateInterval())

    /** 自动检查频率的单一真源。 */
    val updateInterval: StateFlow<String> = _updateInterval.asStateFlow()

    /** 上次自动检查的时间戳（毫秒）；0 = 从未检查。 */
    fun setUpdateLastCheck(value: Long) {
        prefs.edit().putLong("update_last_check", value).apply()
        _updateLastCheck.value = value
    }

    fun getUpdateLastCheck(): Long = prefs.getLong("update_last_check", 0L)

    private val _updateLastCheck = MutableStateFlow(getUpdateLastCheck())

    /** 上次自动检查时间的单一真源。 */
    val updateLastCheck: StateFlow<Long> = _updateLastCheck.asStateFlow()

    /** 自动检查要忽略的版本（tag 或 versionName）；空串 = 不忽略。手动检查不受它影响。 */
    fun setUpdateIgnoredVersion(value: String?) {
        if (value.isNullOrBlank()) prefs.edit().remove("update_ignored_version").apply()
        else prefs.edit().putString("update_ignored_version", value).apply()
        _updateIgnoredVersion.value = value.orEmpty()
    }

    fun getUpdateIgnoredVersion(): String? =
        prefs.getString("update_ignored_version", null)?.takeIf { it.isNotBlank() }

    private val _updateIgnoredVersion = MutableStateFlow(getUpdateIgnoredVersion().orEmpty())

    /** 被忽略版本的单一真源（空串 = 未忽略）。 */
    val updateIgnoredVersion: StateFlow<String> = _updateIgnoredVersion.asStateFlow()

    /* ---------- 界面语言（简体中文 / English，见 i18n.AppLanguage） ---------- */

    /**
     * 语言偏好：system / zh / en。单一真源（[language]），设置页与引导页都只改这一处。
     *
     * 切换后界面立即重组（`AppLanguage.mode` 是 Compose 状态），不重建 Activity；
     * 落盘值在下次进程启动时由 `IwaraApp` 在首帧前恢复。
     */
    fun setLanguage(value: String) {
        prefs.edit().putString(KEY_LANGUAGE, value).apply()
        _language.value = value
    }

    fun getLanguage(): String = prefs.getString(KEY_LANGUAGE, "system") ?: "system"

    private val _language = MutableStateFlow(getLanguage())

    /** 界面语言偏好的单一真源。 */
    val language: StateFlow<String> = _language.asStateFlow()

    /* ---------- 首次使用引导 ---------- */

    /**
     * 首次启动引导是否已完成（「跳过」同样置位，之后不再出现）。
     *
     * 只由引导页的完成/跳过写入；MainActivity 根部据此决定渲染引导页还是主界面。
     */
    fun setOnboardingDone(value: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()
        _onboardingDone.value = value
    }

    fun getOnboardingDone(): Boolean = prefs.getBoolean(KEY_ONBOARDING_DONE, false)

    private val _onboardingDone = MutableStateFlow(getOnboardingDone())

    /** 引导完成状态的单一真源。 */
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    /* ---------- 内容安全（18+ 自我声明 / 截图保护，见 docs/decisions/0028） ---------- */

    /**
     * 是否已做过 18+ 自我声明（引导最后一页勾选或跳过时确认）。
     *
     * 只是本机自述，不做真实年龄验证；已装用户升级后保持 false，且不会再次弹出引导（有意取舍）。
     */
    fun setAdultConfirmed(value: Boolean) {
        prefs.edit().putBoolean(KEY_ADULT_CONFIRMED, value).apply()
        _adultConfirmed.value = value
    }

    fun getAdultConfirmed(): Boolean = prefs.getBoolean(KEY_ADULT_CONFIRMED, false)

    private val _adultConfirmed = MutableStateFlow(getAdultConfirmed())

    /** 18+ 自我声明状态的单一真源。 */
    val adultConfirmed: StateFlow<Boolean> = _adultConfirmed.asStateFlow()

    /**
     * 截图保护：开启后 MainActivity 给窗口加 FLAG_SECURE，截屏、录屏与最近任务预览均为黑屏。
     *
     * 默认关闭（opt-in）：用户可能确实需要截图/录屏，默认开启会被误认为功能故障。
     */
    fun setScreenshotProtection(value: Boolean) {
        prefs.edit().putBoolean(KEY_SCREENSHOT_PROTECTION, value).apply()
        _screenshotProtection.value = value
    }

    fun getScreenshotProtection(): Boolean = prefs.getBoolean(KEY_SCREENSHOT_PROTECTION, false)

    private val _screenshotProtection = MutableStateFlow(getScreenshotProtection())

    /** 截图保护开关的单一真源（设置页读写这里，UI 不直接读 prefs）。 */
    val screenshotProtection: StateFlow<Boolean> = _screenshotProtection.asStateFlow()

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
     * 扫描导入的攒批大小 —— 每这么多个新文件做一次批量 upsert，
     * 同一批的元数据也是先全部拉完再一次事务批写（每批各一次失效，而不是每文件一次），
     * 见 [importFolder] 与 [VideoDao.applyFetchedMetadataBatch] 内注释。
     */
    internal val importBatchSize = 20

    /* ---------- 下载文件完整性校验 ---------- */

    /**
     * 校验某个视频的本地下载文件是否完整（L1 长度 + L2 MP4 顶层 box 结构，见 [walkMp4]）。
     *
     * 纯读取：不改动文件、不删记录；是否重下由调用方按 [shouldAutoRedownload] 决定。
     * 只核对下载服务自己写入的文件：扫描导入、手动关联或下载记录已被裁剪的文件没有
     * 可比对的下载记录 → [IntegrityVerdict.NoDownloadRecord]，既不检查也不删除（见 ADR 0036）。
     * 本地文件不存在 → [IntegrityVerdict.Broken] + [BrokenReason.Missing]；
     * 读不出来（IO / 权限 / 长度不可知）→ [IntegrityVerdict.Unknown] —— 不确定不等于坏，不触发重下。
     */
    suspend fun checkDownloadedIntegrity(videoId: String): IntegrityVerdict = withContext(Dispatchers.IO) {
        val entry = integrityCheckEntry(loadDownloadHistory(), videoId)
            ?: return@withContext IntegrityVerdict.NoDownloadRecord
        val uriString = entry.localUri ?: return@withContext IntegrityVerdict.NoDownloadRecord
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return@withContext IntegrityVerdict.Broken(BrokenReason.Missing, "本地文件地址无法解析")
        // 不用 DocumentFile.exists()：它吞掉查询异常并返回 false，会把暂时的 provider
        // 故障误判成"文件已丢失"进而触发删除重下。三值查询只信确定的 false。
        when (documentExists(context, uri)) {
            false -> return@withContext IntegrityVerdict.Broken(BrokenReason.Missing, "本地文件已不存在")
            null -> return@withContext IntegrityVerdict.Unknown
            true -> Unit
        }
        val document = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
            ?: return@withContext IntegrityVerdict.Unknown
        val length = resolveLocalFileLength(uri, document) ?: return@withContext IntegrityVerdict.Unknown
        walkMp4(length, entry.totalBytes) { offset, size -> readLocalFileBytes(uri, offset, size) }
    }

    /** 读本地文件长度；SAF 部分 provider 的 DocumentFile.length() 返回 0/-1，回退到文件描述符长度。 */
    private fun resolveLocalFileLength(uri: Uri, document: DocumentFile): Long? {
        val fromDocument = runCatching { document.length() }.getOrNull()
        if (fromDocument != null && fromDocument > 0L) return fromDocument
        val fromDescriptor = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        return fromDescriptor?.takeIf { it > 0L }
    }

    /** 按偏移随机读一小段（只用于 box 头）；返回 null = IO 错误 → [IntegrityVerdict.Unknown]。 */
    private fun readLocalFileBytes(uri: Uri, offset: Long, size: Int): ByteArray? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                stream.channel.position(offset)
                val buffer = ByteArray(size)
                var read = 0
                while (read < size) {
                    val n = stream.read(buffer, read, size - read)
                    if (n < 0) break
                    read += n
                }
                buffer.copyOf(read)
            }
        }
    }.getOrNull()

    /**
     * 把一次完整性校验的结论回写到下载历史（静默、尽力而为，失败不影响下载流程）。
     * 复用 [addDownloadHistory] 的读-改-写锁与同 videoId 去重语义，不在第二处手写 prefs JSON。
     */
    fun markDownloadVerified(videoId: String, verdict: IntegrityVerdict) {
        val existing = loadDownloadHistory().firstOrNull { it.videoId == videoId && it.success } ?: return
        addDownloadHistory(
            existing.copy(
                verifiedAt = System.currentTimeMillis(),
                verifyNote = integrityVerdictNote(verdict)
            )
        )
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
    val localUri: String? = null,
    /** 下载完成时服务端报告的文件长度（Content-Length），供之后的完整性校验比对；旧记录为 null。 */
    val totalBytes: Long? = null,
    /**
     * 这份文件是否由 DownloadService 自己写入。
     *
     * 只有真正下载/续传完成才为 true；"本地库已有，跳过下载"的快捷路径为 false。
     * 旧 JSON 没有该字段 → 默认 false：来源无法证明的历史记录不参与完整性自动修复，
     * 宁可不修也不删用户文件（见 ADR 0036）。
     */
    val downloadedByApp: Boolean = false,
    /** 最近一次完整性校验的时间戳（毫秒）；从未校验过为 null，旧记录天然缺省。 */
    val verifiedAt: Long? = null,
    /** 最近一次完整性校验的机器可读结论（见 [integrityVerdictNote]）：ok / unknown / broken:xxx。 */
    val verifyNote: String? = null
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
    val state: String = "paused",
    /** 本轮已自动重试次数；旧记录缺省 0，无需数据迁移。 */
    val attempts: Int = 0
)
