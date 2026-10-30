package io.github.amatsukiichika.iwaram.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * 界面语言偏好：跟随系统 / 简体中文 / English。
 *
 * 落盘值与 [AppLanguage] 的恢复逻辑一一对应；读取见 [fromPref]，写入见 [toPref]。
 */
enum class LanguageMode {
    SYSTEM,
    ZH,
    EN;

    /** 写入 SharedPreferences 的稳定值（不要用 enum name 落盘，改名会丢偏好）。 */
    fun toPref(): String = when (this) {
        SYSTEM -> "system"
        ZH -> "zh"
        EN -> "en"
    }

    companion object {
        /** 读取 SharedPreferences 的值；未知值（含旧版本没写过的 null）一律跟随系统。 */
        fun fromPref(value: String?): LanguageMode = when (value?.lowercase(Locale.ROOT)) {
            "zh" -> ZH
            "en" -> EN
            else -> SYSTEM
        }
    }
}

/**
 * 进程级界面语言状态。
 *
 * 语言切换必须**立即**作用于界面（不重建 Activity）：[mode] 是 Compose 状态，
 * 组合期经 [tr] 读取它的调用点会在切换后自动重组。
 *
 * 默认 [LanguageMode.ZH] 是刻意选择：JVM 单测没有 Application 初始化，默认中文让既有断言
 * 保持确定性；正式进程在 `IwaraApp.onCreate` 里按 prefs 覆盖（首帧前完成）。
 */
object AppLanguage {
    /** 当前语言模式；只经 [set] 修改。 */
    var mode: LanguageMode by mutableStateOf(LanguageMode.ZH)
        private set

    /** 设置语言模式（启动恢复、设置页、引导页共用）。 */
    fun set(mode: LanguageMode) {
        this.mode = mode
    }

    /** 当前是否使用英文；[LanguageMode.SYSTEM] 时跟随系统 Locale（中文系统→中文，其余→英文）。 */
    val isEnglish: Boolean
        get() = when (mode) {
            LanguageMode.EN -> true
            LanguageMode.ZH -> false
            LanguageMode.SYSTEM -> !isSystemChinese()
        }

    /** 当前生效的 Locale（日期、数字、相对时间格式化用）。 */
    val locale: Locale
        get() = if (isEnglish) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE

    private fun isSystemChinese(): Boolean =
        Locale.getDefault().language.equals("zh", ignoreCase = true)
}
