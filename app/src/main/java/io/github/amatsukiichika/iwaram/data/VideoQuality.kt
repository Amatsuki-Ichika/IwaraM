package io.github.amatsukiichika.iwaram.data

/**
 * 从文件名里取档位标签。
 *
 * 自家下载写作 `[id] 标题 (Source).mp4`；外部工具常见 `[id] 标题 [Source].mp4` 或
 * `...[1080p].mp4`。**只认方括号/圆括号包起来的记号**：标题里出现 1080p60fps 这种字串时
 * 若按子串认，会把标题当档位。
 *
 * 这里用字符扫描而不是正则：Java 的正则不允许 `]` 出现在字符类开头（`[])]` 直接抛
 * PatternSyntaxException），而 Kotlin 原始字符串里又没法转义，写起来比扫一遍更容易错。
 *
 * 取**最后一个**括号组：文件名结尾的那个才是下载器追加的档位后缀。
 */
internal fun qualityLabelOfFileName(fileName: String?): String? {
    val name = fileName?.takeIf { it.isNotBlank() } ?: return null
    var found: String? = null
    var i = 0
    while (i < name.length) {
        val open = name[i]
        if (open == '[' || open == '(') {
            val close = if (open == '[') ']' else ')'
            val end = name.indexOf(close, i + 1)
            if (end > i + 1) {
                parseQualityToken(name.substring(i + 1, end))?.let { found = it }
                i = end
            }
        }
        i++
    }
    return found
}

/** 单个括号组里的内容是不是档位；是就返回规范化后的标签。 */
private fun parseQualityToken(raw: String): String? {
    val token = raw.trim()
    if (token.equals("source", ignoreCase = true)) return "Source"
    if (token.equals("preview", ignoreCase = true)) return "Preview"
    if (token.length !in 4..5) return null
    if (!token.endsWith("p", ignoreCase = true)) return null
    val digits = token.dropLast(1)
    if (digits.any { !it.isDigit() }) return null
    return digits + "p"
}

/**
 * 档位的可比较序：数字越大画质越高，越靠前越优先播放。
 *
 * `Source` 是原片，按最高处理；`Preview` 是预览，按最低；**认不出的返回 null**
 * ——调用方据此把未知档位排在最后，绝不把它当成最高画质去默认播放。
 */
internal fun qualityPlayRank(label: String?): Int? {
    val value = label?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    return when {
        value == "source" -> 10_000
        value == "preview" -> 0
        value.length in 4..5 && value.endsWith("p") -> value.dropLast(1).toIntOrNull()
        else -> null
    }
}

/** 展示用标签：认得出档位就用它，认不出返回 null（调用方决定回退文案）。 */
internal fun VideoEntity.qualityLabel(): String? = qualityLabelOfFileName(fileName)
