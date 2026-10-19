package io.github.amatsukiichika.iwaramanager

import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 全局未捕获异常记录器：把崩溃堆栈写入 Logcat 与应用日志文件，
 * 用于在复现困难时定位「闪退 / 再次打开即退出」这类问题。
 */
object CrashLogger {
    private const val TAG = "IwaraManager"

    /** 日志文件上限：写入前超过即裁剪，只保留尾部一半，避免长期运行无限膨胀占满存储。 */
    private const val MAX_LOG_BYTES = 256 * 1024

    /**
     * 崩溃处理器运行在**任意线程**上，故用不可变、线程安全、可复用的
     * `DateTimeFormatter`（`SimpleDateFormat` 非线程安全）。
     */
    private val LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 落盘文件引用（install 时赋值；dumpTail 用）。 */
    private var logFile: File? = null

    fun install(app: IwaraApp) {
        val dir = File(app.filesDir, "logs").apply { mkdirs() }
        val logFile = File(dir, "crash_log.txt")
        this.logFile = logFile
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val ts = LOG_TIME.format(LocalDateTime.now())
                val entry = "\n===== $ts =====\nThread: ${thread.name}\n$sw"
                Log.e(TAG, entry)
                // 先裁剪再追加：反复崩溃 / 循环异常会把日志文件写爆，故写入前先裁到上限。
                if (logFile.length() > MAX_LOG_BYTES) {
                    val tail = runCatching { logFile.readText().takeLast(MAX_LOG_BYTES / 2) }.getOrNull()
                    logFile.writeText(tail ?: "")
                }
                logFile.appendText(entry)
            } catch (_: Throwable) {
                // 纯格式落盘 + 自身失败不能再抛/再记（递归记日志会死循环），吞异常是有意的。
            } finally {
                prev?.uncaughtException(thread, throwable)
            }
        }
    }

    /**
     * 读崩溃日志尾部（诊断面板「复制崩溃日志」按钮用）。
     * 文件不存在 / 读失败 / 内容为空都返回 null，由调用方给「暂无崩溃记录」。
     */
    fun dumpTail(maxChars: Int = 8000): String? = logFile
        ?.takeIf { it.isFile }
        ?.let { f -> runCatching { f.readText().takeLast(maxChars) }.getOrNull() }
        ?.takeIf { it.isNotBlank() }
}