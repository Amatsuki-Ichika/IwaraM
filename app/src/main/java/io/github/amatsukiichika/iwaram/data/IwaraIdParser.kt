package io.github.amatsukiichika.iwaram.data

/**
 * 智能解析本地文件名中的 iwara 视频 ID。
 *
 * iwara 视频 ID 是 base62 随机串（大小写字母 + 数字，常见 11~20 位）。
 * 括号内 / URL 内 / 被分隔符明确标出的候选不要求含数字；
 * 随机串每位是字母的概率约 52/62 ≈ 84%，14 位 ID 全为字母的概率约 8.5%，
 * 若强制要求含数字会漏掉这部分合法 ID。
 * 只有"整名主干就是 ID"那条兜底仍然排除纯数字串（那是日期，不是 ID）。
 *
 * 支持的文件名形态（自上而下按优先级匹配）：
 *  - 显式链接：`https://www.iwara.tv/video/xxxx`
 *  - 任意括号包裹（半角/全角方括号、圆括号、花括号）：`[id]`、`【id】`、`(id)`、`{id}`、`（id）`
 *    —— 含"ID 写在扩展名之后"（`title.mp4 [id]`）与"多个括号"（取最靠后的合规候选，
 *    因为下载模板通常把 ID 放在 `[Source]`/`[1080p]` 这类短标记之前）。
 *  - `_id` / `-id` 结尾（可带扩展名）：`2023-10-15_xxx_ka2k2tjLvw8hGg.mp4`、`iwara_xxx.mp4`
 *  - 整个文件名主干就是 ID：`blggmfno8ghl725bg.mp4`（顺带容忍重复下载产生的 ` (1)` 后缀）
 */
object IwaraIdParser {

    /** ID 的字符集与长度：base62，11~20 位。 */
    private const val ID_PATTERN = "[A-Za-z0-9]{11,20}"

    /** 显式 iwara 链接，优先级最高。 */
    private val URL = Regex("""(?:www\.|ecchi\.)?iwara\.tv/videos?/($ID_PATTERN)""")

    /** 各种括号里的 ID（半角/全角方括号、圆括号、花括号成对出现）。 */
    private val BRACKETED = Regex("""[\[【(（{]\s*($ID_PATTERN)\s*[\]】)）}]""")

    /** 结尾被 _ 或 - 隔开的 ID，后跟扩展名或直接结尾。 */
    private val TRAILING = Regex("""[_-]($ID_PATTERN)(?:\.\w{2,5})?$""")

    /** 重复下载产生的 ` (1)` / ` (2)` 后缀（SAF 建重名文件时会加）。 */
    private val DUPLICATE_SUFFIX = Regex("""\s*\(\d+\)$""")

    /**
     * 按 URL → 括号 → 尾部下划线 → 整名主干 的顺序解析；找不到返回 null。
     *
     * 用"取**最靠后**的括号候选"而不是第一个：下载命名模板普遍形如
     * `<title> [<id>] [<quality>]`，前面的方括号常是分类/作者等**长词**（也满足长度门槛），
     * 而 ID 总是紧挨着末尾的短标记。取最后一个能同时吃下 `[分类] [ID] [Source]`。
     */
    fun parse(input: String?): String? {
        if (input.isNullOrBlank()) return null
        URL.find(input)?.let { return it.groupValues[1] }
        BRACKETED.findAll(input).lastOrNull()?.let { return it.groupValues[1] }
        TRAILING.find(input)?.let { m ->
            return m.groupValues[1].takeIf { !it.all { c -> c.isDigit() } }
        }
        val stem = input.substringAfterLast('/').substringBeforeLast('.').replace(DUPLICATE_SUFFIX, "")
        return stem.takeIf {
            it.length in 11..20 && it.all { c -> c.isLetterOrDigit() } && !it.all { c -> c.isDigit() }
        }
    }
}
