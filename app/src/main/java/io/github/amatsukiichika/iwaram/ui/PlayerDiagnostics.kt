package io.github.amatsukiichika.iwaram.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 播放诊断日志：内存环形缓冲，任意线程可写（GL 线程 / IO / 主线程），诊断面板读取。
 *
 * 用途：设备上抓不到 logcat 时，排障手段就是"打开诊断面板 → 截图 / 一键复制"。
 * 播放器（onVideoSizeChanged / onRenderedFirstFrame / onPlayerError）、超分（buildEffects /
 * configure 降级）、在线解析（清晰度档名）都往这里写 —— 一份日志看清整条链路。
 *
 * 环形上限 [MAX_LINES] 行，超出丢弃最旧的；每行截断到 300 字防长 URL 刷屏。
 */
object DiagLog {
    private const val MAX_LINES = 150

    /**
     * 用 `DateTimeFormatter` 而非共享的 `SimpleDateFormat` —— 后者**非线程安全**，而本对象的
     * `log()` 会从 GL 线程 / IO / 主线程并发调用，并发 `format` 会产生错乱输出甚至
     * `ArrayIndexOutOfBoundsException`。`DateTimeFormatter` 不可变、线程安全，且是常量复用。
     */
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun log(tag: String, message: String) {
        val line = "${LocalTime.now().format(fmt)} $tag: ${message.replace("\n", " ").trim().take(300)}"
        synchronized(this) {
            _lines.value = (_lines.value + line).takeLast(MAX_LINES)
        }
    }

    /** 诊断面板"复制"按钮的内容：全部日志行，每行一条。 */
    fun dump(): String = synchronized(this) { _lines.value.joinToString("\n") }

    fun clear() = synchronized(this) { _lines.value = emptyList() }
}
