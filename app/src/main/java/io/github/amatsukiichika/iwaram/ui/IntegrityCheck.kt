package io.github.amatsukiichika.iwaram.ui

import io.github.amatsukiichika.iwaram.data.BrokenReason
import io.github.amatsukiichika.iwaram.data.IntegrityVerdict
import io.github.amatsukiichika.iwaram.i18n.tr

/**
 * 本地文件完整性检查的纯逻辑：状态机 + 用户文案。
 *
 * 一次检查只有三个阶段：等待确认 → 正在检查 → 出结果（[IntegrityCheckPhase]）。所有分支收在这里，
 * 对话框只负责画、宿主只负责按 [IntegrityCheckEffect] 真正跑 IO / 请求重下 ——
 * "重复触发、取消后迟到的结果、只有坏文件才自动重下"这些容易写错的规则才能被 JVM 单测钉住。
 *
 * 状态可为 null：null = 当前没有检查弹窗（还没开始或用户已关掉）。
 */

/** 一次检查指向的视频：[title] 只用于文案，[videoId] 才是仓库与下载服务的键。 */
internal data class IntegrityCheckTarget(val videoId: String, val title: String)

internal sealed interface IntegrityCheckPhase {
    data object Confirming : IntegrityCheckPhase
    data object Running : IntegrityCheckPhase
    data class Finished(val verdict: IntegrityVerdict) : IntegrityCheckPhase
}

internal data class IntegrityCheckState(
    val target: IntegrityCheckTarget,
    val phase: IntegrityCheckPhase,
    /** 已按 Broken 结论自动请求过重下：结果文案据此说明"已加入重下队列"。 */
    val redownloadRequested: Boolean = false
)

internal sealed interface IntegrityCheckEvent {
    /** 用户在确认框点了「开始检查」。 */
    data object Confirm : IntegrityCheckEvent

    /** 用户取消检查，或在结果框关闭 / 点遮罩关闭。 */
    data object Cancel : IntegrityCheckEvent

    /** 仓库校验返回（成功或异常都归一到 [IntegrityVerdict]）。 */
    data class Result(val verdict: IntegrityVerdict) : IntegrityCheckEvent
}

/** 需要宿主执行的外部动作；纯状态机自己不做 IO。 */
internal sealed interface IntegrityCheckEffect {
    data object RunCheck : IntegrityCheckEffect
    data object CancelCheck : IntegrityCheckEffect
    data class RequestRedownload(val videoId: String) : IntegrityCheckEffect
}

/**
 * 唯一的状态转移函数：返回"下一个状态 + 宿主该做的副作用"。
 *
 * 规则：确认只在等待确认阶段生效（运行中重复点无效）；取消一律关掉弹窗，运行中额外要求取消 IO；
 * 只有运行中收到的结果才算数 —— 取消后才回来的结果必须丢掉，否则会凭空弹出一个结果框；
 * 只有 [IntegrityVerdict.Broken] 触发自动重下（[IntegrityVerdict.Unknown] 不确定，不动文件）。
 */
internal fun reduceIntegrityCheck(
    state: IntegrityCheckState?,
    event: IntegrityCheckEvent
): Pair<IntegrityCheckState?, List<IntegrityCheckEffect>> = when (event) {
    IntegrityCheckEvent.Confirm -> {
        if (state?.phase == IntegrityCheckPhase.Confirming) {
            state.copy(phase = IntegrityCheckPhase.Running) to listOf(IntegrityCheckEffect.RunCheck)
        } else {
            // 运行中/已出结果/弹窗已关：不重复开查。
            state to emptyList()
        }
    }

    IntegrityCheckEvent.Cancel -> {
        val effects = if (state?.phase == IntegrityCheckPhase.Running) {
            listOf(IntegrityCheckEffect.CancelCheck)
        } else {
            emptyList()
        }
        null to effects
    }

    is IntegrityCheckEvent.Result -> {
        if (state != null && state.phase == IntegrityCheckPhase.Running) {
            val broken = event.verdict is IntegrityVerdict.Broken
            state.copy(
                phase = IntegrityCheckPhase.Finished(event.verdict),
                redownloadRequested = broken
            ) to if (broken) {
                listOf(IntegrityCheckEffect.RequestRedownload(state.target.videoId))
            } else {
                emptyList()
            }
        } else {
            // 用户已取消 / 状态已关：迟到的结果直接丢。
            state to emptyList()
        }
    }
}

/* ---------- 文案（双语：中文在前，英文在后） ---------- */

internal fun integrityConfirmMessage(target: IntegrityCheckTarget): String = tr(
    "将读取《${target.title}》的文件结构并核对大小。不会修改或删除文件，大文件可能需要几十秒。",
    "This reads the structure of \"${target.title}\" and checks its size. The file is not modified or deleted; large files can take a while."
)

internal fun integrityRunningTitle(): String = tr("正在检查…", "Checking…")

internal fun integrityRunningMessage(target: IntegrityCheckTarget): String = tr(
    "正在读取《${target.title}》的文件结构并核对大小，不会修改或删除文件。",
    "Reading the structure of \"${target.title}\" and checking its size; the file is left untouched."
)

internal fun integrityResultTitle(verdict: IntegrityVerdict): String = when (verdict) {
    IntegrityVerdict.Ok -> tr("文件完整", "File is intact")
    is IntegrityVerdict.Broken -> tr("文件已损坏", "File is damaged")
    IntegrityVerdict.Unknown -> tr("无法确认", "Could not verify")
}

internal fun integrityResultMessage(
    verdict: IntegrityVerdict,
    redownloadRequested: Boolean
): String = when (verdict) {
    IntegrityVerdict.Ok -> tr(
        "本地文件的大小与 MP4 结构与下载记录一致。",
        "The local file's size and MP4 structure match the download record."
    )

    is IntegrityVerdict.Broken -> buildString {
        append(integrityBrokenReasonText(verdict.reason))
        if (redownloadRequested) {
            append("\n\n")
            append(
                tr(
                    "已自动加入重新下载队列（第 1 次修复）。",
                    "Queued for automatic re-download (first repair attempt)."
                )
            )
        }
    }

    IntegrityVerdict.Unknown -> tr(
        "读取失败，无法确认文件是否完整。文件没有被改动，可以稍后再试。",
        "Could not read the file, so its integrity is unknown. Nothing was modified; try again later."
    )
}

/** 把枚举原因翻成人话；同一原因在两种结论下文案一致，便于用户理解"坏在哪"。 */
internal fun integrityBrokenReasonText(reason: BrokenReason): String = when (reason) {
    BrokenReason.Missing -> tr(
        "本地文件已不存在，或没有该视频的下载记录。",
        "The local file is missing, or there is no download record for this video."
    )

    BrokenReason.Truncated -> tr(
        "文件在下载完成前被截断，末尾数据不完整。",
        "The file was truncated before the download finished; the end is incomplete."
    )

    BrokenReason.ExtraBytes -> tr(
        "文件比下载记录的长度更大，可能被其它数据覆盖。",
        "The file is larger than the recorded download; other data may have overwritten it."
    )

    BrokenReason.NotMp4 -> tr(
        "文件内容不是有效的 MP4，可能下载到了网页或错误提示。",
        "The file is not a valid MP4; it may contain a web page or an error message instead."
    )

    BrokenReason.NoMoov -> tr(
        "MP4 的索引（moov）缺失，播放器无法定位画面。",
        "The MP4 index (moov) is missing, so players cannot locate the video."
    )

    BrokenReason.NoMdat -> tr(
        "MP4 的媒体数据（mdat）缺失，文件里没有画面数据。",
        "The MP4 media data (mdat) is missing; the file contains no video data."
    )

    BrokenReason.BoxOverflow -> tr(
        "MP4 内部结构越界，文件可能已损坏。",
        "The MP4 internal structure overflows; the file is likely damaged."
    )

    BrokenReason.NoTrack -> tr(
        "MP4 中没有找到可播放的轨道。",
        "No playable track was found in the MP4."
    )
}
