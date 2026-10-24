package io.github.amatsukiichika.iwaram.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.amatsukiichika.iwaram.data.friendlyIwaraError

/**
 * Debug 模式开关：错误文案两轨制的分发层。
 * 真源是 `IwaraRepository.debugDetails`（StateFlow + prefs），
 * 这里只负责把值撒进组合树，不是第二份内存副本。
 */
val LocalDebugDetails = staticCompositionLocalOf { false }

/**
 * 错误文案的统一渲染出口：debug 开 → 原样技术细节；关 → [friendlyIwaraError] 人话。
 * 传入串若本就是人话（如「邮箱或密码错误」）原样透传，因此可以无脑套在所有错误渲染点上。
 */
@Composable
fun debugAwareErrorText(detail: String): String =
    if (LocalDebugDetails.current) detail else friendlyIwaraError(detail)

/**
 * 可空版：null 透传（`error != null` 直渲染的场景用）。
 * 与非空版仅可空性不同 —— 两个 @Composable 重载擦除后 JVM 签名相同，必须 @JvmName 区分。
 */
@JvmName("debugAwareErrorTextNullable")
@Composable
fun debugAwareErrorText(detail: String?): String? {
    if (detail == null) return null
    return if (LocalDebugDetails.current) detail else friendlyIwaraError(detail)
}
