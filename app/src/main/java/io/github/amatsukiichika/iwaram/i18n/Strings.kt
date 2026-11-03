package io.github.amatsukiichika.iwaram.i18n

/**
 * 双语取词：`zh` 是简体中文原文，`en` 是英文。
 *
 * 约定见 docs/decisions/0008-bilingual-string-catalog.md：
 * - 两种语言都写在调用点，不建资源表、不做 key 间接层；
 * - 组合期读取 [AppLanguage] 的调用点会在语言切换后自动重组，不需要重建 Activity；
 * - 纯 Kotlin 文案（ViewModel 状态消息等）同样走这里；测试默认中文（见 [AppLanguage]）。
 */
fun tr(zh: String, en: String): String = if (AppLanguage.isEnglish) en else zh

/**
 * 带数量的取词：只返回**量词/名词部分**，数量由调用点拼在前面。
 *
 * 中文没有单复数变化，永远取 [zh]（如「个视频」）；英文按 [count] 取 [enOne] / [enMany]
 * （如 1 video / 2 videos）。调用点示例：`"$count " + trCount(count, "个视频", "video", "videos")`。
 */
fun trCount(count: Int, zh: String, enOne: String, enMany: String): String = when {
    !AppLanguage.isEnglish -> zh
    count == 1 -> enOne
    else -> enMany
}
