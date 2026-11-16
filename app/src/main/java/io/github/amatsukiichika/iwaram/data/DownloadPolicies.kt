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
