package io.github.amatsukiichika.iwaram.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * GitHub Release 中应用内更新需要的字段。
 *
 * @property versionName 规范化版本名（tag 去 v，保留预发布段），如 0.2.2-rc.1。
 * @property tagName Release 原始 tag，如 v0.2.2-rc.1。
 * @property body Release 说明正文（GitHub 未填写时为空串）。
 * @property apkUrl 名字以 -arm64-v8a.apk 结尾的安装包下载地址。
 * @property shaUrl 与 [apkUrl] 同名 .apk.sha256 校验文件的下载地址。
 * @property prerelease GitHub 的预发布标记（发布工作流按 tag 是否含 "-" 自动设置）。
 */
data class GitHubRelease(
    val versionName: String,
    val tagName: String,
    val body: String,
    val apkUrl: String,
    val shaUrl: String,
    val prerelease: Boolean
)

/**
 * GitHub Releases 客户端（应用内更新用）。
 *
 * 只读公开仓库的 releases 列表，不需要鉴权；正式/测试通道的取舍由 [UpdateChannel] 决定。
 * [fetch] 负责网络与 IO 线程，[parseReleaseList] 是纯函数：单测直接喂固定 JSON，不联网
 * （见 GitHubReleaseParseTest）。
 */
object GitHubReleaseClient {

    private const val RELEASES_URL =
        "https://api.github.com/repos/Amatsuki-Ichika/IwaraM/releases?per_page=30"

    /** 整次请求（连接 + 读响应）的上限；更新检查是后台辅助功能，不能挂住调用方。 */
    private const val TIMEOUT_SECONDS = 15L

    /** APK 资产命名：IwaraM-<version>-arm64-v8a.apk，校验文件同名 + .sha256（见 release 工作流）。 */
    private const val APK_SUFFIX = "-arm64-v8a.apk"
    private const val SHA_SUFFIX = ".sha256"

    /**
     * 复用 IwaraClient 的 Dispatcher 与 ConnectionPool（不再养第二套线程/连接池），
     * 但不继承 IwaraClient.http 的拦截器：那组拦截器会给每个请求注入 Iwara 站点头和
     * Bearer token，绝不能把登录凭证发给第三方域名 api.github.com。
     */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(IwaraClient.sharedDispatcher)
            .connectionPool(IwaraClient.sharedConnectionPool)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 拉取 releases 并按 [channel] 选出可更新的最新版本。
     *
     * @param currentVersionName 当前安装的版本名，只用于 User-Agent（GitHub API 要求带 UA）。
     * @return 可更新版本；网络失败、HTTP 非 2xx、没有满足通道的候选一律 null（这次没查到）。
     */
    suspend fun fetch(channel: UpdateChannel, currentVersionName: String): GitHubRelease? =
        withContext(Dispatchers.IO) {
            val userAgent = "IwaraM/" + currentVersionName.ifBlank { "unknown" }
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", userAgent)
                .get()
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    parseReleaseList(body, channel)
                }
            } catch (_: IOException) {
                // 断网 / DNS / 超时都按"这次没查到"处理，让调用方显示可重试提示；
                // CancellationException 不属于 IOException，不会被这里吞掉。
                null
            }
        }

    /**
     * 纯函数：从 GitHub releases JSON 数组里挑出该通道下 semver 最大的可用版本。
     *
     * 过滤规则：
     * - draft = true 的 Release 一律跳过（尚未发布）；
     * - [UpdateChannel.STABLE] 跳过 prerelease = true；
     * - tag_name 不是合法版本（[parse] 返回 null）的跳过；
     * - 必须同时存在 *-arm64-v8a.apk 资产和与它同名的 .apk.sha256 资产，否则跳过；
     * - 结果取 semver 最大，不依赖数组顺序（GitHub 返回按创建时间倒序，但别赌）。
     *
     * @return 无候选时 null。
     */
    fun parseReleaseList(json: String, channel: UpdateChannel): GitHubRelease? {
        val releases = runCatching { JSONArray(json) }.getOrNull() ?: return null
        var bestVersion: UpdateVersion? = null
        var bestRelease: GitHubRelease? = null
        for (index in 0 until releases.length()) {
            val item = releases.optJSONObject(index) ?: continue
            if (item.optBoolean("draft", false)) continue
            val prerelease = item.optBoolean("prerelease", false)
            if (!channel.accepts(prerelease)) continue
            val tagName = item.optString("tag_name").trim()
            if (tagName.isEmpty()) continue
            val version = parse(tagName) ?: continue
            val release = releaseOf(item, tagName, version, prerelease) ?: continue
            val best = bestVersion
            if (best == null || version > best) {
                bestVersion = version
                bestRelease = release
            }
        }
        return bestRelease
    }

    /** 单个 Release 的资产配对与字段读取；缺 APK 或缺配套 sha256 时返回 null（跳过该条）。 */
    private fun releaseOf(
        item: JSONObject,
        tagName: String,
        version: UpdateVersion,
        prerelease: Boolean
    ): GitHubRelease? {
        val assets = item.optJSONArray("assets") ?: return null
        val items = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        val expectedName = "IwaraM-" + version.versionName + APK_SUFFIX
        val apkAssets = items.filter { assetName(it).endsWith(APK_SUFFIX) }
        // 先按本版本的标准命名找；历史 Release 资产名不规范时，接受唯一的 arm64 APK。
        // 无论如何都不盲取 assets[0]（首个资产常常是源码包）。
        val apk = apkAssets.firstOrNull { assetName(it) == expectedName }
            ?: apkAssets.singleOrNull()
            ?: return null
        val apkName = assetName(apk)
        val apkUrl = downloadUrl(apk) ?: return null
        // 校验文件必须与 APK 同名（apk 名 + .sha256），不能拿别的资产的校验和。
        val shaUrl = items
            .firstOrNull { assetName(it) == apkName + SHA_SUFFIX }
            ?.let { downloadUrl(it) }
            ?: return null
        return GitHubRelease(
            versionName = version.versionName,
            tagName = tagName,
            body = if (item.isNull("body")) "" else item.optString("body"),
            apkUrl = apkUrl,
            shaUrl = shaUrl,
            prerelease = prerelease
        )
    }
}

/** 资产名（去首尾空白）。 */
private fun assetName(asset: JSONObject): String = asset.optString("name").trim()

/** 资产的下载地址；缺失时 null。 */
private fun downloadUrl(asset: JSONObject): String? =
    asset.optString("browser_download_url").trim().takeIf { it.isNotEmpty() }

/**
 * 纯函数入口（包级转发）：[GitHubReleaseClient.parseReleaseList] 在网络层之外单独可测，
 * 这里让调用方也能按仓库里纯逻辑的惯例直接当函数用。
 */
fun parseReleaseList(json: String, channel: UpdateChannel): GitHubRelease? =
    GitHubReleaseClient.parseReleaseList(json, channel)
