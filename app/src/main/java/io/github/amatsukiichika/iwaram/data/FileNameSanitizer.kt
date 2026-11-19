package io.github.amatsukiichika.iwaram.data

/**
 * 下载/入库文件名清洗：SAF 路径里的非法字符替换为全角下划线，
 * 控制字符剔除、首尾点号与空白修剪、超长截断，采用各平台通用的保守规则。
 *
 * 截断有两条上限，且**扩展名始终保留**：
 * - 码点数上限 [MAX_CODE_POINTS]：保持可读性，也避免界面/日志里出现超长名字；
 * - UTF-8 字节数上限 [MAX_BYTES]：文件名落盘前必须放得进文件系统。主流文件系统的单段文件名
 *   上限是 255 字节，这里取 200，给扩展名、不同 provider 的编码差异和未来加后缀留出余量。
 *
 * 旧实现按码点从尾部整串截断：长标题会把 “.mp4” 一起切掉，落盘文件没有扩展名，
 * 扫描器认不出视频类型；字节数则完全没有约束（中文/emoji 按字节算可达码点数的三四倍）。
 */
object FileNameSanitizer {

    /** 码点截断上限（按 **Unicode 码点** 计，不是 UTF-16 码元）。 */
    private const val MAX_CODE_POINTS = 120

    /**
     * UTF-8 字节数上限。255 是文件系统上限，200 留出 55 字节余量（扩展名、provider 编码差异、
     * 重名后缀）；对中文标题仍能放下约 66 个汉字。[sanitize] 与 [compose] 共用这个上限。
     */
    internal const val MAX_BYTES = 200

    /** 清洗后为空（或只剩点号/空白）时使用的回退名，保证 SAF 建文件不会拿到空名字。 */
    private const val FALLBACK_NAME = "video"

    /** 扩展名最长认 10 个 ASCII 字母数字（.mp4/.webm/.m4v/.jpeg…），更长的当标题的一部分。 */
    private const val MAX_EXTENSION_LENGTH = 10

    private val illegalChars = Regex("[\\\\/:*?\"<>|]")
    private val controlChars = Regex("[\\x00-\\x1f]")
    private val extensionChars = Regex("[A-Za-z0-9]+")

    fun sanitize(name: String): String = fit(clean(name))

    /**
     * 拼「固定前缀 + 标题 + 固定后缀」的文件名，**只有标题会被截断**。
     *
     * 下载文件名是 `[iwaraId] 标题 (档位).mp4`：扫描器靠 `[iwaraId]` 认视频身份
     *（见 [IwaraIdParser]）、[qualityLabelOfFileName] 靠末尾括号认档位、播放器靠扩展名认容器。
     * 先拼再 [sanitize] 不行 —— [sanitize] 从尾部截断，标题一长就先切掉档位和 `.mp4`。
     * 这里先按总上限给标题分配预算，前缀/后缀只做非法字符清洗、原样保留；拼出来的名字本身
     * 就满足 [sanitize] 的约束，下游再清洗不会改变它。
     *
     * 前缀/后缀本身异常长（吃掉全部预算）时按原样保留并返回：宁可超限，也不丢身份标记/档位。
     */
    internal fun compose(prefix: String, title: String, suffix: String): String {
        val head = cleanPart(prefix)
        val tail = cleanPart(suffix)
        val cleanedTitle = clean(title)
        val maxTitleCodePoints =
            (MAX_CODE_POINTS - codePointCount(head) - codePointCount(tail)).coerceAtLeast(0)
        val maxTitleBytes =
            (MAX_BYTES - utf8Length(head) - utf8Length(tail)).coerceAtLeast(0)
        val fittedTitle = truncateByBytes(
            truncateByCodePoints(cleanedTitle, maxTitleCodePoints),
            maxTitleBytes
        )
        return (head + fittedTitle + tail).ifBlank { FALLBACK_NAME }
    }

    /** 非法字符替换 + 控制字符剔除 + 首尾修剪；空串表示没有任何可用字符。 */
    private fun clean(name: String): String =
        name.replace(illegalChars, "＿")
            .replace(controlChars, "")
            .trim()
            .trim('.')
            .trim()

    /** 片段清洗：只替换非法字符、剔除控制字符，不做首尾修剪 —— 空格是拼接格式的一部分。 */
    private fun cleanPart(part: String): String =
        part.replace(illegalChars, "＿").replace(controlChars, "")

    /**
     * 清洗后按两条上限裁剪，扩展名（若有）原样保留。
     *
     * 先按码点截标题、再按字节截 —— 命中更严的那条；两步都停在完整码点边界上。
     * 截断后修剪尾部的空白与点号，避免出现 “… .mp4” 这种尾巴；标题被裁空时用 [FALLBACK_NAME]。
     */
    private fun fit(cleaned: String): String {
        if (cleaned.isEmpty()) return FALLBACK_NAME
        val extension = extensionOf(cleaned)
        val stem = if (extension.isEmpty()) cleaned else cleaned.dropLast(extension.length)
        val maxStemCodePoints = (MAX_CODE_POINTS - codePointCount(extension)).coerceAtLeast(0)
        val maxStemBytes = (MAX_BYTES - utf8Length(extension)).coerceAtLeast(0)
        val fittedStem = truncateByBytes(truncateByCodePoints(stem, maxStemCodePoints), maxStemBytes)
            .trimEnd()
            .trimEnd('.')
        return (fittedStem.ifEmpty { FALLBACK_NAME }) + extension
    }

    /**
     * 取扩展名（含点号，例如 ".mp4"）：最后一个点号之后必须是 1..10 个 ASCII 字母数字，
     * 且至少含一个字母。认不出返回空串 —— “part 1.5”/“标题.5” 的数字尾段不当扩展名保护，
     * 避免把普通标题的结尾误当成扩展名。
     */
    private fun extensionOf(cleaned: String): String {
        val dot = cleaned.lastIndexOf('.')
        if (dot <= 0) return ""
        val candidate = cleaned.substring(dot + 1)
        if (candidate.length !in 1..MAX_EXTENSION_LENGTH) return ""
        if (!extensionChars.matches(candidate)) return ""
        if (candidate.none { it.isLetter() }) return ""
        return cleaned.substring(dot)
    }

    /**
     * 按 Unicode 码点截断。`take(120)` 按 UTF-16 码元切分：一颗 emoji 或生僻字由两个码元
     * （代理对）组成，切在中间会留下孤立代理项，产生非法文件名的风险
     * （SAF 写入异常或显示为乱码方块）。
     */
    private fun truncateByCodePoints(s: String, max: Int): String {
        // 码点数 ≤ 码元数 ⇒ 长度没超上限就一定没超码点数（快路径，绝大多数调用走这里）。
        if (s.length <= max) return s
        if (codePointCount(s) <= max) return s
        return s.substring(0, s.offsetByCodePoints(0, max))
    }

    /**
     * 按 UTF-8 字节数截断（同样只停在完整码点边界上）。
     *
     * 不能先转成字节数组再 `copyOf(n)`：切在码点中间会得到非法 UTF-8（落盘显示为乱码）。
     * 这里逐码点累加 UTF-8 长度，超预算就停在当前完整字符之前。
     */
    private fun truncateByBytes(s: String, maxBytes: Int): String {
        if (utf8Length(s) <= maxBytes) return s
        var bytes = 0
        var index = 0
        while (index < s.length) {
            val codePoint = s.codePointAt(index)
            val size = utf8Length(codePoint)
            if (bytes + size > maxBytes) break
            bytes += size
            index += Character.charCount(codePoint)
        }
        return s.substring(0, index)
    }

    /** 单个码点的 UTF-8 字节数（1..4）；与 `String.toByteArray(Charsets.UTF_8)` 的编码一致。 */
    private fun utf8Length(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    /** 字符串的 UTF-8 字节数：按码点累加，不分配临时 [ByteArray]。 */
    private fun utf8Length(s: String): Int {
        var bytes = 0
        var index = 0
        while (index < s.length) {
            val codePoint = s.codePointAt(index)
            bytes += utf8Length(codePoint)
            index += Character.charCount(codePoint)
        }
        return bytes
    }

    private fun codePointCount(s: String): Int = s.codePointCount(0, s.length)
}
