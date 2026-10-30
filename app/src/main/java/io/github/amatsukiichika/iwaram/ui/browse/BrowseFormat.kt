package io.github.amatsukiichika.iwaram.ui.browse

import io.github.amatsukiichika.iwaram.data.parseIwaraDate
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.i18n.trCount

/* ---------- 小工具 ---------- */

fun formatCount(n: Int): String = when {
    n >= 100_000_000 -> tr("${"%.1f".format(n / 100_000_000f)}亿", "${"%.0f".format(n / 1_000_000f)}M")
    n >= 10_000 -> tr("${"%.1f".format(n / 10_000f)}万", "${"%.0f".format(n / 1_000f)}K")
    else -> n.toString()
}

/** 「yyyy-MM-dd」回退格式器：DateTimeFormatter 不可变线程安全，文件级缓存避免每个卡片调用都新建。 */
private val timeAgoDateFormatter =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd", java.util.Locale.CHINA)

/** ISO 时间 → 相对时间文案；解析失败返回 null。 */
fun timeAgoLabel(iso: String?): String? {
    val ms = parseIwaraDate(iso) ?: return null
    val diff = System.currentTimeMillis() - ms
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < hour -> {
            val n = (diff / minute).coerceAtLeast(1).toInt()
            "$n " + trCount(n, "分钟前", "minute ago", "minutes ago")
        }
        diff < day -> {
            val n = (diff / hour).toInt()
            "$n " + trCount(n, "小时前", "hour ago", "hours ago")
        }
        diff < 30 * day -> {
            val n = (diff / day).toInt()
            "$n " + trCount(n, "天前", "day ago", "days ago")
        }
        else -> timeAgoDateFormatter.format(
            java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
        )
    }
}
