package io.github.amatsukiichika.iwaram.data

import io.github.amatsukiichika.iwaram.DownloadState
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * 自动重试上限：初次尝试之外，最多再自动重试几次。
 * 退避表、UI 文案（"第 n/3 次自动重试"）都以它为准。
 */
internal const val DOWNLOAD_MAX_AUTO_RETRIES = 3

/**
 * 第 [attempt] 次自动重试前的退避时长（毫秒）：3s / 10s / 30s。
 * 超出上限返回 0；纯函数方便单测盯住"退避只会越来越长"。
 */
internal fun downloadRetryDelayMs(attempt: Int): Long = when (attempt) {
    0 -> 3_000L
    1 -> 10_000L
    2 -> 30_000L
    else -> 0L
}

/**
 * 这次下载失败值不值得自动重试（纯函数，Service 只是调用方）。
 *
 * - 次数预算：已重试 [attempts] 次，达到 [DOWNLOAD_MAX_AUTO_RETRIES] 后一律不重试；
 * - 不可重试类：用户取消（CancellationException）、权限（SecurityException）、磁盘满
 *   （ENOSPC，可能是 IOException 的 message 或 cause）、以及 401/403/404 等确定性 4xx；
 *   其中 401/403 的"重新解析签名地址"由调用方先做一次，做完仍失败就不能再重试；
 * - 可重试类：408/429/5xx，以及没有 HTTP 码时的网络类 IOException
 *   （IOException 覆盖 SocketTimeoutException / UnknownHostException）。
 */
internal fun shouldRetryDownload(httpCode: Int?, error: Throwable?, attempts: Int): Boolean {
    if (attempts >= DOWNLOAD_MAX_AUTO_RETRIES) return false
    if (error != null) {
        if (error is CancellationException) return false
        if (error is SecurityException) return false
        if (error.isNoSpaceLeftError()) return false
    }
    if (httpCode != null) {
        return when {
            httpCode == 408 || httpCode == 429 -> true
            httpCode in 500..599 -> true
            // 401/403 由调用方先重新解析一次地址再判；404 及其它 4xx 重试也不会变好。
            else -> false
        }
    }
    // 没有状态码：只信网络类 IO 异常。其余异常（解析失败、写文件失败等）不自动重试。
    return error is IOException
}

/**
 * 服务器给的总长度与实际收到的字节数不一致（连接被截断，或续传只回来一部分）。
 *
 * 归为 [IOException]：调用方按"网络类传输失败"自动重试，重试会从磁盘真实长度续传；
 * 预算用尽后走既有失败路径，半成品保留（见 [shouldRetryDownload]）。
 */
internal class DownloadIncompleteException(received: Long, expected: Long) : IOException(
    "下载不完整：收到 $received 字节，期望 $expected 字节"
)

/**
 * 206 续传响应的 `Content-Range` 与请求的起点对不上（头缺失、起点错位、区间越界）。
 *
 * 继续 append 只会把两次内容拼成损坏文件，所以必须在写文件之前中止；
 * 同样归为 [IOException]，交给自动重试（多半是 CDN 忽略 Range，重试一次可能就好了）。
 */
internal class DownloadRangeMismatchException(
    val contentRange: String?,
    val requestedFrom: Long
) : IOException("续传区间不匹配：请求从 $requestedFrom 字节继续，响应 " +
    (contentRange?.let { "Content-Range=$it" } ?: "缺少 Content-Range 头"))

/** HTTP `Content-Range: bytes start-end/total` 的解析结果；总量未知时 [total] 为 -1。 */
internal data class ContentRange(val start: Long, val end: Long, val total: Long)

/**
 * 解析 `Content-Range`（纯函数）。
 *
 * 支持 `bytes 100-199/2000`、`bytes 100-199/*`；`bytes */2000`（区间不可满足）
 * 与任何格式错误一律返回 null —— 调用方按"不能安全续传"处理。
 */
internal fun parseContentRange(header: String?): ContentRange? {
    val value = header?.trim().orEmpty()
    if (!value.startsWith("bytes ", ignoreCase = true)) return null
    val spec = value.substringAfter(' ').trim()
    val rangePart = spec.substringBefore('/').trim()
    val totalPart = spec.substringAfter('/', "").trim()
    if (rangePart.isEmpty() || totalPart.isEmpty()) return null
    val start = rangePart.substringBefore('-', "").trim().toLongOrNull() ?: return null
    val end = rangePart.substringAfter('-', "").trim().toLongOrNull() ?: return null
    val total = if (totalPart == "*") -1L else totalPart.toLongOrNull() ?: return null
    if (start < 0 || end < start) return null
    return ContentRange(start, end, total)
}

/**
 * 校验 206 响应用来续传是否安全（纯函数）：区间起点必须等于请求的 [requestedFrom]，
 * 且区间终点不能越过已知总量。返回 null 表示"不能拼"，调用方必须按失败处理。
 */
internal fun resumeRange(contentRange: String?, requestedFrom: Long): ContentRange? {
    val range = parseContentRange(contentRange) ?: return null
    if (range.start != requestedFrom) return null
    // end 是闭区间下标：end >= total 说明服务器报的区间越界（含 end == total 这种差一位）。
    if (range.total > 0 && range.end >= range.total) return null
    return range
}

/**
 * 收到 [received] 字节后能否按"完成"收尾（纯函数）。
 *
 * [expected] 是这次尝试应得的总字节数：206 用 Content-Range 的能力终点/总量，
 * 200 用 Content-Length。只有正好收满才算完成；总长度未知（<= 0，200 分块响应）时
 * 除了 EOF 无从校验，只能放行（协议上无法做得更严）。
 */
internal fun isDownloadComplete(received: Long, expected: Long): Boolean =
    expected <= 0 || received == expected

/**
 * 库里已有的那份文件是否**收满**（纯函数，下载服务"已在本地库就跳过"分支用）。
 *
 * 旧实现只判断 `length > 0` 就收尾：被扫描器提前入库的半成品（长度 > 0）会被当成
 * 已下载完成——清单里显示成功、播放到断点后黑屏，用户既没有"继续下载"入口也不会重试。
 * 有未完成的 pending 记录时用它的总长度当基准：文件长度必须达到 total 才算完整，
 * 否则应继续走续传。总长度未知（<= 0，服务器没给 Content-Length）时无法区分
 * 完整与半成品，只能按"完整"处理（与 isDownloadComplete 的协议限制一致），
 * 避免把用户自己放进目录的文件误判成半成品而重下一遍。
 */
internal fun libraryFileIsComplete(fileLength: Long?, pendingTotal: Long): Boolean {
    if (fileLength == null || fileLength <= 0L) return false
    return pendingTotal <= 0L || fileLength >= pendingTotal
}

/**
 * 磁盘满判定：ENOSPC 可能作为 message 出现在 IOException 上，也可能包在 cause 链里；
 * 用 message 关键字判断，避免把"空间不足"当成可重试的网络错误反复重试。
 */
private fun Throwable.isNoSpaceLeftError(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 4) {
        val message = current.message.orEmpty()
        if (message.contains("ENOSPC", ignoreCase = true) ||
            message.contains("No space left", ignoreCase = true)
        ) {
            return true
        }
        current = current.cause
        depth++
    }
    return false
}

/**
 * 应用重启后要自动续上的任务（纯函数）：
 * - [enabled] 为 false 时一律不续（用户关了"自动继续"开关）；
 * - [liveIds] 里的视频当前已有活动 Job（服务进程还在），不能重复入队；
 * - 只续 queued / running 这两种"被中断"的记录；paused / failed 永不自动续
 *   （暂停是用户意图，失败要用户手动重试 —— 自动重试只在一次 Job 内进行）；
 * - 保持输入顺序，不重新排序也不去重。
 */
internal fun resumePlan(
    pending: List<PendingDownload>,
    liveIds: Set<String>,
    enabled: Boolean
): List<PendingDownload> {
    if (!enabled) return emptyList()
    return pending.filter { p ->
        p.videoId !in liveIds &&
            (p.state == DownloadState.STATE_QUEUED || p.state == DownloadState.STATE_RUNNING)
    }
}

/**
 * 启动自动继续的实际接线（纯函数，MainActivity 只负责调用）。
 *
 * [liveTasksBeforeRestore] 必须是**把记录恢复进列表之前**的内存快照：
 * 先 restoreIfAbsent 再取 liveIds，会把本轮要续的任务也当成"已在跑"，
 * 计划恒为空 —— 这正是自动继续一直不工作的根因，单测钉住这个顺序。
 */
internal fun autoResumePlanBeforeRestore(
    pending: List<PendingDownload>,
    liveTasksBeforeRestore: List<DownloadState.Task>,
    enabled: Boolean
): List<PendingDownload> = resumePlan(
    pending = pending,
    liveIds = liveTasksBeforeRestore.mapTo(mutableSetOf()) { it.videoId },
    enabled = enabled
)

/**
 * 下载卡片失败态要显示的"已自动重试次数"：非失败状态返回 null（不显示任何重试提示）。
 *
 * 返回 Int 而不是双语字符串：中英文案由 UI 用 tr(...) 按这个次数组装，
 * data 层不硬编码用户可见文案（仓库规则见 ui/AGENTS.md）。
 */
internal fun downloadCardFailureReason(state: String, attempts: Int): Int? =
    if (state == DownloadState.STATE_FAILED) {
        attempts.coerceIn(0, DOWNLOAD_MAX_AUTO_RETRIES)
    } else {
        null
    }

/**
 * 入队去重：同一 videoId 在排队或运行时只允许一个任务。
 *
 * 两个 worker 同时写同一文件会让 mdat 交错；暂停/取消是一次性标记，也只能被其中一个消费。
 * @return true = 本次拿到队列位；false = 已有任务排队/运行，调用方应丢弃这次请求。
 */
internal fun reserveDownloadSlot(videoId: String, reserved: MutableSet<String>): Boolean =
    reserved.add(videoId)
