package io.github.amatsukiichika.iwaramanager.ui.browse

import io.github.amatsukiichika.iwaramanager.data.parseIwaraDate

/* ---------- 小工具 ---------- */

fun formatCount(n: Int): String = when {
    n >= 100_000_000 -> "${"%.1f".format(n / 100_000_000f)}亿"
    n >= 10_000 -> "${"%.1f".format(n / 10_000f)}万"
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
        diff < hour -> "${(diff / minute).coerceAtLeast(1)} 分钟前"
        diff < day -> "${diff / hour} 小时前"
        diff < 30 * day -> "${diff / day} 天前"
        else -> timeAgoDateFormatter.format(
            java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
        )
    }
}
