package io.github.amatsukiichika.iwaram.data

/**
 * 解析后的应用版本号（semver 子集：major.minor.patch[-pre]）。
 *
 * 只通过 [parse] 构造能保证 [versionCode] 用到的数字都在公式可表示的范围内；
 * 直接构造非法组合（负数、超上限）不在使用契约内。
 *
 * @param pre 预发布标识段，如 0.2.2-rc.1 解析为 ["rc", "1"]；null 表示正式版。
 *   非 null 即"预发布"（[parse] 拒绝空段，所以不会出现空列表）。
 */
data class UpdateVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val pre: List<String>? = null
) : Comparable<UpdateVersion> {

    /** 规范化版本名（去掉 v 前缀，预发布段用 "." 连接还原），如 0.2.2-rc.1。 */
    val versionName: String
        get() = buildString {
            append(major).append('.').append(minor).append('.').append(patch)
            if (!pre.isNullOrEmpty()) append('-').append(pre.joinToString("."))
        }

    /**
     * 与 app/build.gradle.kts 一致的 versionCode 公式（忽略预发布段）：
     * major * 10000 + minor * 100 + patch。
     *
     * 忽略 pre 是刻意的：同一核心版本的 rc 与正式版共享 versionCode，
     * 测试者才能从 rc 版 APK 原地升级到正式版（见 build.gradle.kts 注释）。
     * 数字范围由 [parse] 限制，不会溢出。
     */
    fun versionCode(): Int = major * 10000 + minor * 100 + patch

    /** semver 比较；优先顺序：major → minor → patch → 预发布段。 */
    override fun compareTo(other: UpdateVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        val left = pre
        val right = other.pre
        // 同一核心版本下，正式版大于一切预发布（0.2.2 > 0.2.2-rc.2）。
        if (left == null) return if (right == null) 0 else 1
        if (right == null) return -1
        val shared = minOf(left.size, right.size)
        for (index in 0 until shared) {
            val result = comparePrereleasePart(left[index], right[index])
            if (result != 0) return result
        }
        // 前缀相同时段数少的更小（0.2.2-rc < 0.2.2-rc.1）。
        return left.size.compareTo(right.size)
    }
}

/**
 * 解析版本串，失败返回 null。
 *
 * 接受：0.2.2、v0.2.2、V0.2.2、0.2.2-rc.1、0.2.2-beta（预发布段按 "." 或 "-" 再分段）。
 * 其它形态（缺段、非数字、空预发布段、超出公式可表示范围）一律 null ——
 * 宁可这次不提示更新，也不解析出一个错误的版本。
 */
fun parse(raw: String?): UpdateVersion? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    // Release tag 形如 v0.2.2；版本名本身不带前缀。
    val text = if (trimmed[0] == 'v' || trimmed[0] == 'V') trimmed.substring(1) else trimmed
    if (text.isEmpty()) return null

    // 第一个 "-" 之后是预发布段，核心三段用 "." 切分。
    val dash = text.indexOf('-')
    val coreText = if (dash >= 0) text.substring(0, dash) else text
    val preText = if (dash >= 0) text.substring(dash + 1) else null

    val core = coreText.split('.')
    if (core.size != 3) return null
    val major = core[0].toVersionPart(VERSION_MAJOR_MAX) ?: return null
    val minor = core[1].toVersionPart(VERSION_MINOR_MAX) ?: return null
    val patch = core[2].toVersionPart(VERSION_PATCH_MAX) ?: return null

    val pre = if (preText == null) {
        null
    } else {
        val parts = preText.split('.', '-')
        if (parts.any { !isPrereleasePart(it) }) return null
        parts
    }
    return UpdateVersion(major, minor, patch, pre)
}

/**
 * versionCode 公式里各段的上限。
 *
 * minor/patch 在公式里各占两位十进制，超过 99 会和更大的 major 撞码；
 * major 上限由 Android/Play 的 versionCode 上限 2_100_000_000 反推
 * （209_999 * 10000 + 99 * 100 + 99 = 2_099_999_999）。超范围视为非法版本。
 */
private const val VERSION_MAJOR_MAX = 209_999
private const val VERSION_MINOR_MAX = 99
private const val VERSION_PATCH_MAX = 99

/** 解析一段十进制数字并收敛到公式可表示的上限；非法返回 null。 */
private fun String.toVersionPart(max: Int): Int? {
    if (this.isEmpty() || any { !it.isDigit() }) return null
    val value = toIntOrNull() ?: return null
    return value.takeIf { it <= max }
}

/** 预发布段的一段：非空且只含字母/数字（如 rc、beta2、1）。 */
private fun isPrereleasePart(part: String): Boolean =
    part.isNotEmpty() && part.all { it.isLetterOrDigit() }

/** semver 预发布段比较：数字段按数值，数字段低于字母段，字母段按字典序。 */
private fun comparePrereleasePart(left: String, right: String): Int {
    val leftNumeric = left.all { it.isDigit() }
    val rightNumeric = right.all { it.isDigit() }
    return when {
        leftNumeric && rightNumeric -> compareNumericText(left, right)
        // semver：数字标识符优先级低于字母数字标识符（rc.1 < rc.alpha）。
        leftNumeric -> -1
        rightNumeric -> 1
        else -> left.compareTo(right)
    }
}

/** 纯数字串按数值比较；先去掉前导零再比长度，避免超长数字转 Int 溢出。 */
private fun compareNumericText(left: String, right: String): Int {
    val a = left.trimStart('0').ifEmpty { "0" }
    val b = right.trimStart('0').ifEmpty { "0" }
    if (a.length != b.length) return a.length.compareTo(b.length)
    return a.compareTo(b)
}

/**
 * 是否需要提示更新（纯函数，双判据）。
 *
 * 判据一（安装硬门槛）：远端 versionCode 更高 → 需要更新；更低 → 不需要。
 * 判据二（同号换正式版）：versionCode 相同代表同一个核心版本（公式忽略预发布），
 * 只有"本机是预发布、远端是正式版"算升级，例如 0.2.2-rc.1 → 0.2.2。
 * 正式版之间、预发布之间、正式版看到预发布（降级）都不提示。
 *
 * 远端名字解析失败时按不需要更新处理：只有 versionCode 判据成立才会提示，
 * 因为 code 才是安装器真正比较的依据。
 */
fun needsUpdate(
    localVersionName: String?,
    remoteVersionName: String?,
    localVersionCode: Int,
    remoteVersionCode: Int
): Boolean {
    if (remoteVersionCode > localVersionCode) return true
    if (remoteVersionCode < localVersionCode) return false
    val local = parse(localVersionName) ?: return false
    val remote = parse(remoteVersionName) ?: return false
    return local.pre != null && remote.pre == null
}
