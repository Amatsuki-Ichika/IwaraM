package io.github.amatsukiichika.iwaram.ui

/**
 * 卡片文本归一化：让「标题 / 作者 / 标签」三行在视觉上统一对齐。
 *
 * 卡片文本的来源很杂（Iwara 接口的 title/user.name、本地文件名解析出的作者、tag slug），
 * 常见脏数据有三类：
 * 1. 首尾空白 —— 含全角空格 U+3000、NBSP U+00A0 等「看起来像空格但 trim() 不认」的字符；
 * 2. 中间连续空白 —— 从网页复制来的标题常带多个连续空格，导致行内文字宽度忽宽忽窄；
 * 3. 全角字母数字 —— 日文输入法下 `ＡＢＣ１２３` 与 `ABC123` 混排，视觉宽度不一致。
 *
 * 规则：
 * - 空白（Unicode 空白 + 各类空格分隔符）统一折叠为**单个半角空格**，并去掉首尾；
 * - 零宽字符（U+200B/U+200C/U+200D/U+FEFF）直接丢弃，不占位；
 * - 全角数字 ０-９、全角字母 Ａ-Ｚ ａ-ｚ 转半角；
 * - **标点保持原样**（不动 `！`、`（）`、`？` 等全角标点，避免把日文排版改丑）。
 *
 * 纯函数、无副作用，可直接单测（见 CardTextTest）。
 */
fun normalizeCardText(raw: String?): String {
    if (raw.isNullOrEmpty()) return ""
    val sb = StringBuilder(raw.length)
    var pendingSpace = false
    for (ch in raw) {
        // 零宽字符：丢弃，且不制造空格（避免 "a\u200Bb" 变成 "a b"）
        if (ch == '\u200B' || ch == '\u200C' || ch == '\u200D' || ch == '\uFEFF') continue

        // 空白：isWhitespace 覆盖 \t\n\r 与 U+3000，isSpaceChar 覆盖 U+00A0/U+2007/U+202F 等
        // 不间断空格（isWhitespace 对它们返回 false，是 "trim() 无效" 的根源）。
        if (ch.isWhitespace() || Character.isSpaceChar(ch)) {
            pendingSpace = sb.isNotEmpty()
            continue
        }

        if (pendingSpace) {
            sb.append(' ')
            pendingSpace = false
        }
        sb.append(
            when (ch) {
                // 全角 ０-９ Ａ-Ｚ ａ-ｚ → 半角（Unicode 全角区与 ASCII 相差 0xFEE0）
                in '\uFF10'..'\uFF19', in '\uFF21'..'\uFF3A', in '\uFF41'..'\uFF5A' -> ch - 0xFEE0
                else -> ch
            }
        )
    }
    return sb.toString()
}

/** 一分钟的毫秒数（[cardDateText] 用）。 */
private const val MINUTE_MS = 60L * 1000L

/** 一小时的毫秒数（[cardDateText] 用）。 */
private const val HOUR_MS = 60L * MINUTE_MS

/** 一天的毫秒数（[cardDateText] 用）。 */
private const val DAY_MS = 24L * HOUR_MS

/** 「n 天前」的上限：超过就显示具体日期（30 天内才用相对时间）。 */
private const val RELATIVE_DAYS = 30L

/**
 * 卡片左下角日期胶囊的文案（纯函数，单测见 `CardTextTest`）。
 *
 * 口径：
 * - **不足 1 分钟** → 「刚刚」
 * - **1~59 分钟**  → 「n 分钟前」（向下取整）
 * - 不足 24 小时   → 「n 小时前」（23h59m 仍是「23 小时前」）
 * - 不足 30 天     → 「n 天前」（按 24 小时为单位向下取整）
 * - 其余           → 「yyyy-MM-dd」（本地时区）
 * - **未来时间**（设备时钟偏差 / 服务器给的时间超前）→ 按「刚刚」，绝不出现负数
 *
 * 纯函数：把 `nowMs` 当参数传进来，测试里可以钉死每一个边界。
 */
fun cardDateText(createdAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diff = nowMs - createdAtMs
    if (diff <= 0L) return "刚刚"
    return when {
        diff < MINUTE_MS -> "刚刚"
        diff < HOUR_MS -> "${diff / MINUTE_MS} 分钟前"
        diff < DAY_MS -> "${diff / HOUR_MS} 小时前"
        diff < RELATIVE_DAYS * DAY_MS -> "${diff / DAY_MS} 天前"
        else -> java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.format(
            java.time.Instant.ofEpochMilli(createdAtMs)
                .atZone(java.time.ZoneId.systemDefault())
        )
    }
}
