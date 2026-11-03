package io.github.amatsukiichika.iwaram.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.amatsukiichika.iwaram.i18n.AppLanguage
import io.github.amatsukiichika.iwaram.i18n.tr
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 观看历史。
 * 一行 = 一个"看过的视频"，本地与在线共用一张表、用 [isLocal] 区分。
 * 同一个视频重看不新增行：靠 [videoKey] 的唯一索引原地更新（lastWatchAt 刷新即天然置顶）。
 *
 * 字段落位：视频 id = [videoKey]+[iwaraId]、标题 = [title]、作者 = [author]/[authorId]、
 * 封面 = [thumbnail]、本地或在线 = [isLocal]、最后观看时间 = [lastWatchAt]、
 * 进度 = [positionMs]、总时长 = [durationMs]、来源（重开定位） = [localUri]/[iwaraId]。
 */
@Entity(
    tableName = "watch_history",
    indices = [Index(value = ["videoKey"], unique = true)]
)
data class WatchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 去重键，由 [watchHistoryKey] 推导。 */
    val videoKey: String,
    val iwaraId: String?,
    /** 本地文件的 content:// URI；在线条目为 null。 */
    val localUri: String?,
    val title: String,
    val author: String?,
    val authorId: String?,
    /** 封面：本地抽帧路径或在线封面 URL，直接可给 VideoCardModel.thumbnail。 */
    val thumbnail: String?,
    val isLocal: Boolean,
    val lastWatchAt: Long,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * 去重键推导（纯函数，配单测）：有 iwaraId 用 iwaraId（本地副本与在线同源，key 相同才能合并）；
 * 没有 iwaraId 的本地文件用 "local:<uri>" 兜底。两者皆空返回 null（不该发生，调用方跳过记录）。
 */
fun watchHistoryKey(iwaraId: String?, localUri: String?): String? = when {
    !iwaraId.isNullOrBlank() -> iwaraId
    !localUri.isNullOrBlank() -> "local:$localUri"
    else -> null
}

/**
 * 续播起点（纯函数，配单测）：进度在 5 秒以上且不足总时长 95% 才值得续播，
 * 否则返回 0（从头播）——快看完的重进自动重看，刚点开就退出的不留进度。
 */
fun resumeStartMs(positionMs: Long, durationMs: Long): Long =
    if (durationMs > 0 && positionMs >= 5_000L && positionMs < durationMs * 95 / 100) positionMs else 0L

/**
 * 从封面 URL 反解文件 id（纯函数，配单测）：在线封面 URL 是稳定模板
 * `https://files.iwara.tv/image/thumbnail/{fileId}/thumbnail-00.jpg`（IwaraClient.thumbnailUrl）。
 * 历史行点开重建在线条目时靠它把封面带回 IwaraVideoItem.file。
 */
fun thumbnailFileIdFromUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return Regex("/image/thumbnail/([^/]+)/").find(url)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
}

/* ---------- 历史页的三个纯函数（配单测） ---------- */

/**
 * 日期分组头：今天 / 昨天 / M月d日（跨年带年份）。按本地时区的**日历日**分组，
 * 与 B 站历史页的分组一致。
 */
fun historyGroupLabel(lastWatchAt: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(lastWatchAt).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        day == today -> tr("今天", "Today")
        day == today.minusDays(1) -> tr("昨天", "Yesterday")
        day.year == today.year -> tr(
            "${day.monthValue}月${day.dayOfMonth}日",
            DateTimeFormatter.ofPattern("MMM d", AppLanguage.locale).format(day)
        )
        else -> tr(
            "${day.year}年${day.monthValue}月${day.dayOfMonth}日",
            DateTimeFormatter.ofPattern("MMM d, yyyy", AppLanguage.locale).format(day)
        )
    }
}

/** 观看时间行文本：「M月d日 HH:mm」（本地时区）。 */
fun historyTimeText(lastWatchAt: Long): String {
    val zoned = Instant.ofEpochMilli(lastWatchAt).atZone(ZoneId.systemDefault())
    val pattern = tr("M'月'd'日' HH:mm", "MMM d, HH:mm")
    return zoned.format(DateTimeFormatter.ofPattern(pattern, AppLanguage.locale))
}

/**
 * 时长角标：看完（≥95%，与 [resumeStartMs] 同一阈值）→「已看完」；
 * 否则「当前 / 总时长」（≥1 小时用 h:mm:ss，不足用 mm:ss）；总时长未知 → 只显示当前。
 */
fun historyBadge(positionMs: Long, durationMs: Long): String {
    fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(java.util.Locale.US, "%02d:%02d", m, sec)
    }
    return when {
        durationMs > 0 && positionMs >= durationMs * 95 / 100 -> tr("已看完", "Watched")
        durationMs <= 0 -> fmt(positionMs)
        else -> fmt(positionMs) + " / " + fmt(durationMs)
    }
}
