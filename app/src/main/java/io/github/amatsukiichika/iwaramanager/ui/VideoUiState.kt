package io.github.amatsukiichika.iwaramanager.ui

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.amatsukiichika.iwaramanager.IwaraApp
import io.github.amatsukiichika.iwaramanager.ScanService
import io.github.amatsukiichika.iwaramanager.ScanState
import io.github.amatsukiichika.iwaramanager.data.GlobalFilterHolder
import io.github.amatsukiichika.iwaramanager.data.TagEntity
import io.github.amatsukiichika.iwaramanager.data.VideoEntity
import io.github.amatsukiichika.iwaramanager.data.filterBy
import io.github.amatsukiichika.iwaramanager.data.localVideoKeys
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

/* ---------- 本地库 UI 模型（原 VideoViewModel.kt 模型区块） ---------- */
enum class LibraryMode { ALL, FOLDER, AUTHOR, TIME, TAG }

enum class SortMode(val label: String) {
    NEWEST("最新"),
    OLDEST("最早"),
    NAME_ASC("名称 A→Z"),
    NAME_DESC("名称 Z→A"),
    COUNT_DESC("数量 多→少"),
    COUNT_ASC("数量 少→多"),
    ADDED_NEW("下载时间 新→旧"),
    ADDED_OLD("下载时间 旧→新")
}

data class AuthorBucket(val id: String, val name: String, val count: Int)

data class TagBucket(val id: String, val name: String, val count: Int)

data class FolderBucket(val key: String, val name: String, val count: Int)

data class TimeBucket(
    val key: String,
    val label: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val count: Int
)

data class ActiveFilter(
    val label: String,
    val type: LibraryMode
)

/**
 * 标 `@Immutable`：编译器对 5 个 `List` 字段只能判 unstable，而这份状态被本地库 / 设置页 /
 * 顶栏计数三处接收 —— 不标的话任何字段变化都会让它们全部重组。
 * 承诺成立前提：[VideoViewModel.computeState] 每次都构造**全新**列表、从不原地改动。
 */
@Immutable
data class UiState(
    val videos: List<VideoEntity> = emptyList(),
    val folders: List<FolderBucket> = emptyList(),
    val authors: List<AuthorBucket> = emptyList(),
    val timeBuckets: List<TimeBucket> = emptyList(),
    val tags: List<TagBucket> = emptyList(),
    val mode: LibraryMode = LibraryMode.ALL,
    val activeFilter: ActiveFilter? = null,
    val sortMode: SortMode = SortMode.NEWEST,
    val showTags: Boolean = true,
    val query: String = "",
    /** 本地库分级筛选（all / general / ecchi）；「默认 R18」开启时它被强制为 ecchi 且 UI 隐藏。 */
    val ratingFilter: String = "all",
    /** 默认 R18：隐藏分级筛选项并强制只看 R18（在线 + 本地一致）。 */
    val defaultR18: Boolean = false,
    /** 卡片是否显示 R18 角标（独立开关）。 */
    val showR18Tag: Boolean = true,
    /**
     * 全局筛选器：被全局筛选器**隐藏掉**的条目数。
     * 用途：把"筛完是空的"与"库里本来就没有"这两种空态分开显示（否则会以为筛选器坏了）。
     */
    val hiddenByFilter: Int = 0,
    /** 本地库当前选中的 tag（多选；标签分类列表用它显示"已选中"状态）。 */
    val selectedTags: List<String> = emptyList(),
    /**
     * 四种分类模式的分段索引标记，随 [UiState] 在**后台线程**算好。
     * 若在 LibraryScreen 组合期对全库逐项做日期格式化（monthLabel/dayLabel），扫描期间
     * 每条 DB 写都要在主线程重跑一趟全库；下沉后主线程零计算，读现成字段即可。
     */
    val videoMarkers: List<SectionMarker> = emptyList(),
    val folderMarkers: List<SectionMarker> = emptyList(),
    val authorMarkers: List<SectionMarker> = emptyList(),
    val timeMarkers: List<SectionMarker> = emptyList()
)

/**
 * **扫描进度独立成流**（不在 [UiState] 里）。
 *
 * 扫描进度若和"视频列表"塞在同一个 data class 里，每个 tick 都要换掉**整个** [UiState]；
 * 而 `videos: List<VideoEntity>` 在 Compose 眼里是不稳定类型（接口类型必然不稳定）⇒
 * `VideoGrid` 无法跳过重组 ⇒ **扫描期间整个网格每个 tick 重组一遍**。
 *
 * 拆开后：进度只影响订阅了本流的那一行进度条，列表一点都不动。
 */
data class ScanProgress(
    val running: Boolean = false,
    val label: String = "",
    val done: Int = 0,
    val total: Int = 0
)

/**
 * 作者二级页「视频」栏**本地部分**的纯函数实现（单独抽出来是为了可单测）。
 *
 * - 分组键：`authorId` 为空/空白 → 「未指定」桶（与本地库「作者」分类的归组一致）；
 * - 排序：**固定**按发布最新 → 最旧（`createdAt` 缺失时退化用 `addedAt`），
 *   不跟随本地库的排序设置。
 */
object LocalAuthorVideos {

    fun keyOf(v: VideoEntity): String =
        v.authorId?.takeIf { it.isNotBlank() } ?: VideoViewModel.UNKNOWN_AUTHOR_KEY

    fun of(source: List<VideoEntity>, key: String): List<VideoEntity> =
        source.filter { keyOf(it) == key }.sortedByDescending { it.createdAt ?: it.addedAt }
}
