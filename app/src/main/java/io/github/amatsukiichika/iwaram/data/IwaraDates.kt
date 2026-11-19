package io.github.amatsukiichika.iwaram.data

import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 解析 iwara 返回的 ISO 时间字符串，失败返回 null。
 *
 * 上游有两种形态：Instant.parse 能吃的 "...Z"，以及带偏移的 ISO_OFFSET_DATE_TIME。
 * 纯格式解析吞异常是有意的：依次尝试两种形态，都不是则按"无时间"处理。
 */
fun parseIwaraDate(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return try {
        Instant.parse(raw).toEpochMilli()
    } catch (_: Exception) {
        try {
            java.time.OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}
