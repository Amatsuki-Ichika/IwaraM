package io.github.amatsukiichika.iwaram.data

import java.util.Locale

/**
 * MP4 容器损坏的原因。UI 按原因给不同提示；重下策略只看"是不是 Broken"，不细分原因。
 *
 * [Missing] 由仓库层产生（本地文件不存在 / 没有下载记录）—— [walkMp4] 只看到字节，看不到"文件没了"。
 * [NoTrack] 需要解析 moov 内部（L3 可选的 MediaExtractor 级校验），顶层 box 遍历不产生它，
 * 保留给后续加严的校验。
 */
enum class BrokenReason { Missing, Truncated, ExtraBytes, NotMp4, NoMoov, NoMdat, BoxOverflow, NoTrack }

/**
 * 一次完整性校验的结论。
 *
 * [Unknown] 是"没法下结论"（读不了文件 / 长度不可知），与 [Broken] 的区别是有意的：
 * 不确定时绝不触发自动重下 —— 把读失败的正常文件删掉重下，比不修更糟。
 */
sealed interface IntegrityVerdict {
    data object Ok : IntegrityVerdict
    data class Broken(val reason: BrokenReason, val detail: String? = null) : IntegrityVerdict
    data object Unknown : IntegrityVerdict
}

/** 顶层 box 的头：4 字节大端长度 + 4 字节类型。 */
private const val BOX_HEADER_BYTES = 8

/** `size == 1` 时用 64 位 largesize，头变成 16 字节。 */
private const val LARGE_BOX_HEADER_BYTES = 16

/** 文本嗅探窗口：HTML/JSON 错误页开头几个字节就足以判定。 */
private const val TEXT_SNIFF_BYTES = 32

/** `ftyp` 之前允许出现的填充类 box（部分封装器会先写 free/wide）。 */
private val LEADING_FILLER_BOXES = setOf("free", "skip", "wide")

/**
 * 校验一个 MP4 文件的顶层容器结构（纯逻辑，不依赖 Android，不解析媒体流）。
 *
 * 输入是"文件长度 + 按偏移读字节"而不是真实文件：调用方负责把 SAF/ContentResolver 包成
 * [readAt]；[readAt] 返回 null 表示 IO 错误（读失败）→ [IntegrityVerdict.Unknown]，
 * 不触发重下。所有校验都是**只读**的，不会改动文件。
 *
 * 校验项（任何一项失败都算 [IntegrityVerdict.Broken]）：
 * 1. 长度：文件长度必须为正，且与期望长度 [expectedBytes]（服务端 Content-Length，未知为 null）一致；
 * 2. 内容：拒绝 HTML/JSON 文本（下载被登录页/错误页顶替的表现）；
 * 3. 结构：第一个 box 必须是 `ftyp`（允许先出现 free/skip/wide），`moov` 恰好一个，
 *    至少一个 `mdat`；
 * 4. 字节账：所有顶层 box 的长度之和必须正好等于文件长度，box 不得越过文件末尾；
 *    `size == 1` 读 64 位 largesize，`size == 0` 表示延伸到文件末尾（必须是最后一个 box）。
 *
 * @param length 文件长度（字节）
 * @param expectedBytes 服务端报告的应得长度；null 表示未知（跳过长度一致性校验）
 * @param readAt 按偏移读取的方法：offset 起最多 size 字节；读到文件末尾时返回较短数组即可，
 *               返回 null 表示 IO 错误
 */
fun walkMp4(
    length: Long,
    expectedBytes: Long?,
    readAt: (offset: Long, size: Int) -> ByteArray?
): IntegrityVerdict {
    if (length <= 0L) return IntegrityVerdict.Broken(BrokenReason.Truncated, "文件长度为 $length")
    if (expectedBytes != null && expectedBytes > 0L && length != expectedBytes) {
        return if (length < expectedBytes) {
            IntegrityVerdict.Broken(BrokenReason.Truncated, "文件长度 $length 小于期望 $expectedBytes")
        } else {
            IntegrityVerdict.Broken(BrokenReason.ExtraBytes, "文件长度 $length 大于期望 $expectedBytes")
        }
    }
    val sniffSize = minOf(length, TEXT_SNIFF_BYTES.toLong()).toInt()
    val head = readAt(0L, sniffSize) ?: return IntegrityVerdict.Unknown
    if (head.size < BOX_HEADER_BYTES) {
        return IntegrityVerdict.Broken(BrokenReason.Truncated, "文件只剩 ${head.size} 字节，放不下一个 box 头")
    }
    if (looksLikeText(head)) {
        return IntegrityVerdict.Broken(BrokenReason.NotMp4, "文件内容像文本（HTML/JSON 错误页），不是 MP4")
    }

    var offset = 0L
    var firstBox = true
    var ftypSeen = false
    var moovCount = 0
    var mdatCount = 0
    while (offset < length) {
        val remaining = length - offset
        if (remaining < BOX_HEADER_BYTES) {
            return IntegrityVerdict.Broken(
                BrokenReason.Truncated,
                "末尾剩 $remaining 字节，不足一个 box 头"
            )
        }
        val header = readAt(offset, BOX_HEADER_BYTES) ?: return IntegrityVerdict.Unknown
        if (header.size < BOX_HEADER_BYTES) {
            return IntegrityVerdict.Broken(BrokenReason.Truncated, "读取 box 头时文件提前结束")
        }
        val declaredSize = readUInt32BE(header, 0)
        val type = boxType(header, 4)
        if (!type.all { it.code in 0x20..0x7E }) {
            return IntegrityVerdict.Broken(BrokenReason.NotMp4, "无法识别的 box 类型")
        }
        var boxSize = declaredSize
        var extendsToEof = false
        when {
            declaredSize == 1L -> {
                if (remaining < LARGE_BOX_HEADER_BYTES) {
                    return IntegrityVerdict.Broken(
                        BrokenReason.BoxOverflow,
                        "$type 需要 16 字节 largesize 头，但文件只剩 $remaining 字节"
                    )
                }
                val largeHeader = readAt(offset, LARGE_BOX_HEADER_BYTES) ?: return IntegrityVerdict.Unknown
                if (largeHeader.size < LARGE_BOX_HEADER_BYTES) {
                    return IntegrityVerdict.Broken(BrokenReason.Truncated, "读取 $type 的 largesize 时文件提前结束")
                }
                boxSize = readUInt64BE(largeHeader, BOX_HEADER_BYTES)
                if (boxSize < LARGE_BOX_HEADER_BYTES) {
                    return IntegrityVerdict.Broken(
                        BrokenReason.BoxOverflow,
                        "$type 的 largesize=$boxSize 小于 box 头长度"
                    )
                }
            }
            declaredSize == 0L -> {
                boxSize = remaining
                extendsToEof = true
            }
            declaredSize < BOX_HEADER_BYTES -> {
                return IntegrityVerdict.Broken(
                    BrokenReason.BoxOverflow,
                    "$type 的 size=$declaredSize 小于 box 头长度"
                )
            }
        }
        if (boxSize > remaining) {
            return IntegrityVerdict.Broken(
                BrokenReason.BoxOverflow,
                "$type 越界：box 结束于 ${offset + boxSize}，文件长 $length"
            )
        }
        if (firstBox) {
            if (type != "ftyp" && type !in LEADING_FILLER_BOXES) {
                return IntegrityVerdict.Broken(BrokenReason.NotMp4, "首个 box 是 $type，不是 ftyp")
            }
            firstBox = false
        }
        when (type) {
            "ftyp" -> ftypSeen = true
            "moov" -> moovCount++
            "mdat" -> mdatCount++
        }
        offset += boxSize
        if (extendsToEof) break
    }
    if (!ftypSeen) return IntegrityVerdict.Broken(BrokenReason.NotMp4, "缺少 ftyp box")
    if (moovCount == 0) return IntegrityVerdict.Broken(BrokenReason.NoMoov, "缺少 moov box")
    if (moovCount > 1) return IntegrityVerdict.Broken(BrokenReason.NoMoov, "moov 出现了 $moovCount 次（应唯一）")
    if (mdatCount == 0) return IntegrityVerdict.Broken(BrokenReason.NoMdat, "缺少 mdat box")
    if (offset != length) return IntegrityVerdict.Broken(BrokenReason.BoxOverflow, "box 总长 $offset 与文件长 $length 不一致")
    return IntegrityVerdict.Ok
}

/**
 * 这次校验结论要不要自动重新下载（纯函数）。
 *
 * 只有确定坏了（[IntegrityVerdict.Broken]）且本进程还没自动修过才修：
 * - [IntegrityVerdict.Unknown] 不修：读失败不等于文件坏，重下会白费流量还可能删掉好文件；
 * - 已经自动修过一次仍坏，说明问题多半在服务端/签名/存储，再重下只会死循环 → 只通知用户。
 */
fun shouldAutoRedownload(verdict: IntegrityVerdict, alreadyRetried: Boolean): Boolean =
    verdict is IntegrityVerdict.Broken && !alreadyRetried

/**
 * 写入下载历史 `verifyNote` 的机器可读标签（不面向用户：UI 自己按 [BrokenReason] 出双语文案）。
 * 只用于诊断与"上次校验结果"状态判断。
 */
fun integrityVerdictNote(verdict: IntegrityVerdict): String = when (verdict) {
    IntegrityVerdict.Ok -> "ok"
    IntegrityVerdict.Unknown -> "unknown"
    is IntegrityVerdict.Broken -> "broken:" + verdict.reason.name.lowercase(Locale.ROOT)
}

/** 内容看起来是文本（HTML 错误页 / JSON 报错）而不是二进制容器。 */
private fun looksLikeText(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes.all { b ->
    val value = b.toInt() and 0xFF
    value == 0x09 || value == 0x0A || value == 0x0D || value in 0x20..0x7E
}

/** 4 字节大端无符号整数（box size 字段）。 */
private fun readUInt32BE(bytes: ByteArray, offset: Int): Long {
    var value = 0L
    for (i in 0 until 4) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
    return value
}

/** 8 字节大端无符号整数（largesize 字段）；超过 Long.MAX_VALUE 的畸形值按非法处理。 */
private fun readUInt64BE(bytes: ByteArray, offset: Int): Long {
    var value = 0L
    for (i in 0 until 8) {
        value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        if (value < 0L) return -1L
    }
    return value
}

private fun boxType(bytes: ByteArray, offset: Int): String =
    String(bytes, offset, 4, Charsets.US_ASCII)
