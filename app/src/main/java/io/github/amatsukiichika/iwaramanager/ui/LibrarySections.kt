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

/* ---------- 本地库分桶/分段纯函数（原 VideoViewModel.kt 尾部纯函数区块） ---------- */
/* ---------- 本地搜索匹配 ----------
 * 自 [VideoViewModel.buildFiltered] 下沉为顶层纯函数（单测直接引用）：
 * 搜索词同时匹配「界面上能看到的 / 能唯一定位这条视频的」所有字段。
 * 注意 tagsCsv 存的就是标签 slug（与卡片/详情页显示一致），本身即搜索目标，无需再做映射。 */

/** 空词视为"不筛选"。title 与 fileName 非空，其余可空字段逐个判空。 */
internal fun matchesQuery(v: VideoEntity, query: String): Boolean {
    if (query.isBlank()) return true
    return v.title.contains(query, true) ||
        v.fileName.contains(query, true) ||
        (v.author?.contains(query, true) == true) ||
        (v.authorId?.contains(query, true) == true) ||
        (v.iwaraId?.contains(query, true) == true) ||
        (v.tagsCsv?.contains(query, true) == true)
}

/**
 * 分类视图（作者/文件夹/时间/标签）的桶列表按搜索词过滤：名字包含即保留。
 * 搜索词不只作用于「全部」视图的最终列表，同一份 query 在所有视图同步生效。
 * 空词原样返回（同一实例，避免无谓换列表）。
 */
internal fun <T> filterBucket(list: List<T>, query: String, nameOf: (T) -> String): List<T> {
    val q = query.trim()
    if (q.isBlank()) return list
    return list.filter { nameOf(it).contains(q, true) }
}

/* ---------- 分段索引：按当前排序生成右侧字母 / 月份 / 数量档位标记 ----------
 * 自 LibraryScreen 下沉为顶层纯函数：计算随 [VideoViewModel.computeState]
 * 落在后台线程；LibrarySectionTest 直接引用 videoSection / timeBucketSection，可见性不变。 */

internal fun <T> buildMarkers(sorted: List<T>, labelOf: (T) -> String): List<SectionMarker> {
    val out = mutableListOf<SectionMarker>()
    sorted.forEachIndexed { i, item ->
        val label = labelOf(item)
        if (out.isEmpty() || out.last().label != label) out.add(SectionMarker(label, i))
    }
    return out
}

private fun nameSection(name: String): String {
    val c = name.trim().firstOrNull() ?: return "#"
    val l = c.lowercaseChar()
    return if (l in 'a'..'z') c.uppercaseChar().toString() else "#"
}

private fun countTier(n: Int): String = when {
    n >= 10000 -> "1万+"
    n >= 1000 -> "1千+"
    n >= 100 -> "100+"
    else -> "<100"
}

private fun monthLabel(epochMs: Long): String {
    val ym = YearMonth.from(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    return "${ym.year}年${ym.monthValue}月"
}

/** 下载时间排序的分节标签按天 —— 集中在最近一两个月下载时按月只剩 1 个分节，滚动条不渲染。 */
private fun dayLabel(epochMs: Long): String {
    val d = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    return "%02d月%02d日".format(d.monthValue, d.dayOfMonth)
}

/**
 * 全部视频：名称排序→首字母，数量排序→数量档位，时间排序→月份，下载时间排序→按天。
 *
 * 下载时间用"入库日期"而非"入库月份"分节：按月分节在视频集中在最近一两个月时只剩 1 个分节
 * → `buildMarkers` 只产 1 个标记 → FastScrollbar 的 `markers.size < 2` 整条不渲染
 * （表现为"下载时间排序没有滚动条"）。按天分节保证近期下载也有可跳的分节；
 * 分节过多时滚动条自动只显示滑块不显示文字（FastScrollbar 既有逻辑）。
 */
internal fun videoSection(v: VideoEntity, sortMode: SortMode): String = when (sortMode) {
    SortMode.NAME_ASC, SortMode.NAME_DESC -> nameSection(v.title)
    SortMode.COUNT_ASC, SortMode.COUNT_DESC -> countTier(v.viewCount ?: 0)
    SortMode.NEWEST, SortMode.OLDEST -> v.createdAt?.let(::monthLabel) ?: "未知"
    SortMode.ADDED_NEW, SortMode.ADDED_OLD -> dayLabel(v.addedAt)
}

/** 作者/文件夹分组：数量排序→档位，其余（名称、时间均回退为名称）→首字母。 */
internal fun bucketSection(name: String, count: Int, sortMode: SortMode): String = when (sortMode) {
    SortMode.COUNT_ASC, SortMode.COUNT_DESC -> countTier(count)
    else -> nameSection(name)
}

/**
 * 时间分组：时间排序与下载时间排序→月份标签，数量排序→档位，名称排序→首字母。
 *
 * `ADDED_NEW/ADDED_OLD`（下载时间）必须与 NEWEST/OLDEST 一样返回月份标签：否则落到 else
 * 分支的 `nameSection("2026年1月")` —— 首字符不是字母 → 全部归 "#" → buildMarkers 只剩 1 个标记
 * → FastScrollbar 的 `markers.size < 2` 直接不渲染，"下载时间"排序右边就没有滚动条。
 */
internal fun timeBucketSection(b: TimeBucket, sortMode: SortMode): String = when (sortMode) {
    SortMode.NEWEST, SortMode.OLDEST, SortMode.ADDED_NEW, SortMode.ADDED_OLD -> b.label
    SortMode.COUNT_ASC, SortMode.COUNT_DESC -> countTier(b.count)
    else -> nameSection(b.label)
}
