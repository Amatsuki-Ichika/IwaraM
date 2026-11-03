package io.github.amatsukiichika.iwaram.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.amatsukiichika.iwaram.i18n.tr
import kotlin.math.roundToInt

/* ---------- 超分运行状态（原 SuperResolution.kt 状态区块） ---------- */
/**
 * 超分运行状态：GL 线程写、UI 线程读，供播放器 SR chip 展示真实倍率与降级提示。
 * - [activeScale] 实际输出倍率（相对源分辨率）；null = 尚未生效/已关闭；
 * - [degraded]   构建或分配失败、已降级（画面照常播放，但超分未按预期生效）；
 * - [engine]     实际生效的引擎名。
 */
object SuperResStatus {
    var activeScale by mutableStateOf<Float?>(null)
    var degraded by mutableStateOf(false)
    var engine by mutableStateOf<String?>(null)

    /**
     * 失败原因（一行人类可读文本，来自异常 message）。
     * 直接显示在播放器 SR 下拉菜单里，无需抓 logcat。
     */
    var reason by mutableStateOf<String?>(null)

    /**
     * 特效管线在播放中发生 7001、已由播放器自动关闭 SR 并恢复播放。
     * 与 [degraded] 的区别：这是管线级失败（播放已被打断过一次），chip 显示
     * "SR 自动停用"提醒；换视频或重新打开 SR 时清零。
     */
    var pipelineFailed by mutableStateOf(false)

    /**
     * 真实源分辨率（GPU 实际拿到的帧尺寸）。
     *
     * 由 `Anime4kProgram.configure()` 记录 —— media3 把解码后的帧纹理交给我们时喂进来的
     * width/height 就是它，这是唯一权威的"源分辨率"。
     *
     * 不能改用播放器上报的 `onVideoSizeChanged`：装了特效管线后那个值可能是特效的输出尺寸
     * （显示成 1272×2262 这种看不懂的数），也可能因上游 issue #2505 根本不更新。
     */
    var sourceWidth by mutableStateOf<Int?>(null)
    var sourceHeight by mutableStateOf<Int?>(null)

    /** 特效实际输出分辨率（只修复 / 只降噪的档 = 源分辨率）。 */
    var outputWidth by mutableStateOf<Int?>(null)
    var outputHeight by mutableStateOf<Int?>(null)

    /** `1920x1080` 形式；任一维取不到就返回 null。 */
    private fun sizeText(w: Int?, h: Int?): String? =
        if (w != null && h != null && w > 0 && h > 0) "${w}x$h" else null

    /** 实测源分辨率文案。 */
    val sourceText: String? get() = sizeText(sourceWidth, sourceHeight)

    /** 实测输出分辨率文案。 */
    val outputText: String? get() = sizeText(outputWidth, outputHeight)

    /**
     * SR 下拉菜单顶部那条只读说明：「源 1080x1920 · 输出 1080x1920 · 不放大」。
     * 只显示 configure() 实测到的尺寸，不出现看似识别错误的分辨率。
     */
    fun resolutionInfo(isUpscalingModel: Boolean, scale: Float?): String? {
        val src = sourceText ?: return null
        val out = outputText ?: src
        val ratio = if (isUpscalingModel && scale != null && scale > 1.01f) {
            " · ${formatScale(scale)}"
        } else {
            tr(" · 不放大", " · no upscaling")
        }
        return tr("源 $src · 输出 $out$ratio", "Source $src · Output $out$ratio")
    }

    fun reset() {
        activeScale = null
        degraded = false
        engine = null
        reason = null
        pipelineFailed = false
        sourceWidth = null
        sourceHeight = null
        outputWidth = null
        outputHeight = null
    }

    /**
     * 降级状态赋值入口：degraded=true + 清倍率 + 记原因，避免多处重复。
     */
    fun markDegraded(reason: String?) {
        degraded = true
        activeScale = null
        this.reason = reason
    }

    /**
     * 底部条 SR chip 文案：
     * - 关闭 → `SR`；管线级失败自动停用 → `SR 自动停用`；降级 → `SR 未生效`；
     *   生效 → `SR·3D 2×`（倍率实际 >1 才展示）。
     * 恒为单行短串，避免把右侧清晰度 chip 挤出屏幕。
     */
    fun chipLabel(enabled: Boolean, model: String, scale: Float?, degraded: Boolean): String = when {
        !enabled && pipelineFailed -> tr("SR 自动停用", "SR auto-off")
        !enabled -> "SR"
        degraded -> tr("SR 未生效", "SR inactive")
        scale != null && scale > 1.01f -> "SR·${SuperRes.shortTag(model)} ${formatScale(scale)}"
        else -> "SR·${SuperRes.shortTag(model)}"
    }

    /** 倍率文案：整数倍显示 `2×`，非整数保留一位小数（如 `1.5×`）。 */
    fun formatScale(scale: Float): String {
        val tenths = (scale * 10f).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10}×" else "${tenths / 10f}×"
    }
}
