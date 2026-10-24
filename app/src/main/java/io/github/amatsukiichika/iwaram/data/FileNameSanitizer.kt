package io.github.amatsukiichika.iwaram.data

/**
 * 下载/入库文件名清洗：SAF 路径里的非法字符替换为全角下划线，
 * 控制字符剔除、首尾点号与空白修剪、超长截断，采用各平台通用的保守规则。
 */
object FileNameSanitizer {

    /** 截断上限（按 **Unicode 码点** 计，不是 UTF-16 码元）。 */
    private const val MAX_LENGTH = 120

    private val illegalChars = Regex("[\\\\/:*?\"<>|]")
    private val controlChars = Regex("[\\x00-\\x1f]")

    fun sanitize(name: String): String =
        name.replace(illegalChars, "＿")
            .replace(controlChars, "")
            .trim()
            .trim('.')
            .trim()
            .let { truncateByCodePoints(it, MAX_LENGTH) }
            .ifBlank { "video" }

    /**
     * 按 Unicode 码点截断。`take(120)` 按 UTF-16 码元切分：一颗 emoji 或生僻字由两个码元
     * （代理对）组成，切在中间会留下孤立代理项，产生非法文件名的风险
     * （SAF 写入异常或显示为乱码方块）。
     */
    private fun truncateByCodePoints(s: String, max: Int): String {
        // 码点数 ≤ 码元数 ⇒ 长度没超上限就一定没超码点数（快路径，绝大多数调用走这里）。
        if (s.length <= max) return s
        if (s.codePointCount(0, s.length) <= max) return s
        return s.substring(0, s.offsetByCodePoints(0, max))
    }
}
