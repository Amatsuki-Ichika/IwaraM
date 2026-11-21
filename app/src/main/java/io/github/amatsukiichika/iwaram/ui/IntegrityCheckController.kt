package io.github.amatsukiichika.iwaram.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.amatsukiichika.iwaram.data.IntegrityVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 完整性检查的宿主侧小控制器：持有弹窗状态、跑一次校验、按 [IntegrityCheckEffect] 调外部动作。
 *
 * 状态机本身在 [reduceIntegrityCheck]（纯函数、JVM 单测覆盖）；这里只做三件事：
 * 同一时刻只允许一个检查（[open] 在已有弹窗时忽略）、校验期间可取消（协程取消）、
 * 异常一律当 [IntegrityVerdict.Unknown]（读不出来不等于坏，不触发重下）。
 *
 * 放在 MainActivity 的 remember 里，让播放页 / 主页卡片 / 本地库三个入口共用同一个弹窗。
 */
internal class IntegrityCheckController(
    private val scope: CoroutineScope,
    private val check: suspend (videoId: String) -> IntegrityVerdict,
    private val requestRedownload: (videoId: String) -> Unit
) {
    var state by mutableStateOf<IntegrityCheckState?>(null)
        private set

    private var job: Job? = null

    /** 打开确认框；已有检查在展示时不重复打开（防止连点长按叠出多个弹窗）。 */
    fun open(target: IntegrityCheckTarget) {
        if (state != null) return
        state = IntegrityCheckState(target = target, phase = IntegrityCheckPhase.Confirming)
    }

    fun confirm() = dispatch(IntegrityCheckEvent.Confirm)

    fun cancel() = dispatch(IntegrityCheckEvent.Cancel)

    /** 结果框关闭（Ok/Broken/Unknown 之后）：只清状态，不删文件、不改历史。 */
    fun dismissResult() {
        job?.cancel()
        job = null
        state = null
    }

    private fun dispatch(event: IntegrityCheckEvent) {
        val (next, effects) = reduceIntegrityCheck(state, event)
        state = next
        for (effect in effects) {
            when (effect) {
                IntegrityCheckEffect.RunCheck -> {
                    val target = next?.target
                    if (target != null) {
                        job?.cancel()
                        job = scope.launch {
                            val verdict = runCatching { check(target.videoId) }
                                .getOrElse { IntegrityVerdict.Unknown }
                            dispatch(IntegrityCheckEvent.Result(verdict))
                        }
                    }
                }

                IntegrityCheckEffect.CancelCheck -> {
                    job?.cancel()
                    job = null
                }

                is IntegrityCheckEffect.RequestRedownload -> requestRedownload(effect.videoId)
            }
        }
    }
}
