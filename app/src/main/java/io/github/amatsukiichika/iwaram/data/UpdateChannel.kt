package io.github.amatsukiichika.iwaram.data

/**
 * 应用内更新的通道，与 IwaraRepository 持久化的 "stable"/"beta" 字符串一一对应。
 *
 * - [STABLE]：正式版通道，只接收正式发布（GitHub Release 的 prerelease = false）；
 * - [BETA]：测试版通道，正式版与 rc 等预发布都接收。
 *
 * 纯枚举 + 纯函数，不依赖 Android；单测见 UpdateVersionTest。
 */
enum class UpdateChannel {
    STABLE,
    BETA
}

/**
 * 该通道是否接受预发布版本。
 *
 * @param prerelease 该版本是否为预发布（GitHub Release 的 prerelease 标记；
 *   发布工作流按 tag 里是否含 "-" 设置，如 v0.2.2-rc.1）。
 */
fun UpdateChannel.accepts(prerelease: Boolean): Boolean = when (this) {
    // 正式版通道绝不把 rc/预发布推给用户。
    UpdateChannel.STABLE -> !prerelease
    // 测试版通道两种都要，由版本比较决定最终推哪个。
    UpdateChannel.BETA -> true
}
