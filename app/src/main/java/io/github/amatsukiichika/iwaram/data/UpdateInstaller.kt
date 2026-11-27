package io.github.amatsukiichika.iwaram.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.TimeUnit

/**
 * 应用内更新在 data 层的失败原因。
 *
 * 这里不放用户文案：UI 层按类型映射成中英双语提示。
 */
enum class UpdateInstallFailure {
    /** 网络不可用、HTTP 非 2xx、下载中断、校验文件拉取失败，或下载地址被主机白名单拒绝。 */
    NETWORK,

    /** 下载完成但 SHA-256 与 Release 附带的校验文件不一致（文件已删除）。 */
    SHA_MISMATCH,

    /** 文件已下载并通过校验，但系统安装器打不开（未允许安装未知应用等）。 */
    INSTALL,

    /** 其它意外（缓存目录不可写等）。 */
    UNKNOWN
}

/** [UpdateInstaller] 的类型化失败；调用方按 [kind] 决定提示与能否重试。 */
class UpdateInstallException(
    val kind: UpdateInstallFailure,
    message: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * 应用内更新的下载、校验与安装。
 *
 * 只做机械动作、不持有状态：进度经 [download] 的 onProgress 回调交给调用方。
 * - [download]：OkHttp 流式下载到 cacheDir/updates，再拉同名 .apk.sha256 做 SHA-256 校验；
 * - [install]：用 FileProvider 把缓存 APK 以 content:// 交给系统包安装器。
 *
 * 网络客户端复用 IwaraClient 的 Dispatcher/ConnectionPool，但不继承注入 Iwara 站点头和
 * Bearer token 的拦截器：APK 与校验文件都在 GitHub 域名上，绝不能带上登录凭据。
 * 只允许 https 且主机在 [UPDATE_DOWNLOAD_HOSTS] 内的地址；OkHttp 跟随的每一跳重定向
 * 也会被网络拦截器逐跳重新校验。
 */
object UpdateInstaller {

    /** 单次连接/读/写停滞的上限；APK 体积不可控，所以不设 callTimeout（整体时长不设限）。 */
    private const val TIMEOUT_SECONDS = 30L

    /** 下载目录名；与 res/xml/file_paths.xml 中的 cache-path 对应。 */
    private const val UPDATES_DIR = "updates"

    /** FileProvider authorities 后缀；与 AndroidManifest 中 provider 的 authorities 对应。 */
    private const val AUTHORITY_SUFFIX = ".updates"

    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val BUFFER_SIZE = 64 * 1024

    /** GitHub 资产名形如 IwaraM-0.2.2-arm64-v8a.apk；只接受安全字符，防路径穿越。 */
    private val APK_FILE_NAME = Regex("[A-Za-z0-9._-]+\\.apk", RegexOption.IGNORE_CASE)

    /**
     * 逐跳主机白名单：OkHttp 跟随 30x 时，每个重定向后的请求都会重新经过网络拦截器，
     * 因此指向非白名单主机的 Location 在发请求前就被拒绝。
     *
     * 用 [ProtocolException] 而不是普通 IOException：OkHttp 不把它当作可恢复错误，
     * 不会对同一个被拒地址反复重试；最终仍按 IOException 归为 [UpdateInstallFailure.NETWORK]。
     */
    private val hostAllowlistInterceptor = Interceptor { chain ->
        if (!isAllowedUpdateUrl(chain.request().url)) {
            throw ProtocolException("update request blocked: host not allowlisted")
        }
        chain.proceed(chain.request())
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(IwaraClient.sharedDispatcher)
            .connectionPool(IwaraClient.sharedConnectionPool)
            // 网络拦截器在每一跳请求（含自动跟随的重定向）上执行，逐跳校验主机白名单。
            .addNetworkInterceptor(hostAllowlistInterceptor)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 下载并校验 [release] 的 APK。
     *
     * @param onProgress 进度回调（0f..1f）；服务器未给 Content-Length 时传 null。
     * @return 已通过 SHA-256 校验的缓存文件（位于 cacheDir/updates）。
     * @throws UpdateInstallException 网络失败、校验失败或其它意外；协程取消时抛 CancellationException。
     */
    suspend fun download(
        context: Context,
        release: GitHubRelease,
        onProgress: (Float?) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, UPDATES_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw UpdateInstallException(UpdateInstallFailure.UNKNOWN, "cannot create update cache dir")
        }
        // 每次下载前清掉旧安装包：同一时刻只需要一份，也避免 cache 长期堆积。
        dir.listFiles()?.forEach { stale -> stale.delete() }
        val target = File(dir, apkFileName(release))
        try {
            downloadApk(release, target, onProgress)
            onProgress(1f)
            verifySha256(release, target)
            target
        } catch (cancelled: CancellationException) {
            target.delete()
            throw cancelled
        } catch (failure: UpdateInstallException) {
            target.delete()
            throw failure
        } catch (error: IOException) {
            target.delete()
            // 协程取消时 OkHttp 会以 IOException 结束；还原成取消语义，别报成网络失败。
            currentCoroutineContext().ensureActive()
            throw UpdateInstallException(UpdateInstallFailure.NETWORK, "I/O failure while downloading", error)
        }
    }

    /**
     * 把已下载的 APK 交给系统包安装器（ACTION_VIEW + content://）。
     *
     * 需要 AndroidManifest 的 REQUEST_INSTALL_PACKAGES、FileProvider 声明与
     * res/xml/file_paths.xml 的 cache-path；用户未允许"安装未知应用"时由系统安装器自行引导。
     *
     * @throws UpdateInstallException 路径未被 FileProvider 暴露或系统没有安装器（INSTALL）。
     */
    fun install(context: Context, file: File) {
        val uri = try {
            FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        } catch (error: IllegalArgumentException) {
            throw UpdateInstallException(
                UpdateInstallFailure.INSTALL,
                "APK path is not exposed by FileProvider",
                error
            )
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // 部分系统安装器只认 ClipData 里的读授权，补一份（与 data 同 URI，无副作用）。
        intent.clipData = ClipData.newRawUri(null, uri)
        try {
            context.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            throw UpdateInstallException(UpdateInstallFailure.INSTALL, "no package installer activity", error)
        }
    }

    /**
     * 构造下载 GET：地址先按 https + 主机白名单收窄，不合法按网络失败处理。
     *
     * 解析阶段已校验过，这里再拦一次是给直接构造的 [GitHubRelease]（或将来新的调用方）
     * 兜底，也让 Request.Builder 不会因非法字符串抛 IllegalArgumentException。
     */
    private fun updateRequest(url: String, release: GitHubRelease): Request {
        val checkedUrl = allowedUpdateUrl(url)
            ?: throw UpdateInstallException(UpdateInstallFailure.NETWORK, "update URL is not allowlisted")
        return Request.Builder()
            .url(checkedUrl)
            .header("User-Agent", userAgent(release))
            .get()
            .build()
    }

    /** 流式下载到 [target]；Content-Length 可得时按已写字节比例回调进度。 */
    // invokeOnCompletion(onCancelling = true) 仍标 internal：公开重载只在 Job 完成后回调，
    // 对阻塞中的 OkHttp execute 太晚。这里用它换取"取消一开始就 cancel call"。
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun downloadApk(
        release: GitHubRelease,
        target: File,
        onProgress: (Float?) -> Unit
    ) {
        val request = updateRequest(release.apkUrl, release)
        val call = http.newCall(request)
        // OkHttp 的阻塞式 execute 不响应协程取消：取消开始时主动 cancel，让读循环尽快退出。
        val handle = currentCoroutineContext()[Job]
            ?.invokeOnCompletion(onCancelling = true) { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw UpdateInstallException(UpdateInstallFailure.NETWORK, "APK HTTP " + response.code)
                }
                val body = response.body ?: throw UpdateInstallException(
                    UpdateInstallFailure.NETWORK,
                    "empty APK response body"
                )
                val total = body.contentLength()
                body.byteStream().use { input ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            // 分块传输没有 Content-Length 时给 null，界面显示不确定进度。
                            if (total > 0L) {
                                onProgress((written.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                            } else {
                                onProgress(null)
                            }
                        }
                    }
                }
            }
        } finally {
            handle?.dispose()
        }
    }

    /** 拉取 Release 的 sha256 校验文件并与本地文件比对；不一致抛 [UpdateInstallFailure.SHA_MISMATCH]。 */
    private fun verifySha256(release: GitHubRelease, file: File) {
        val expected = fetchExpectedSha256(release)
        val actual = sha256Of(file)
        if (!UpdateChecksum.hashesMatch(expected, actual)) {
            throw UpdateInstallException(UpdateInstallFailure.SHA_MISMATCH, "SHA-256 mismatch")
        }
    }

    /** 拉取 .sha256 资产并解析出期望摘要；解析交给 [UpdateChecksum.parseExpectedHash]，这里只管网络与错误分类。 */
    private fun fetchExpectedSha256(release: GitHubRelease): String {
        val request = updateRequest(release.shaUrl, release)
        val text = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw UpdateInstallException(UpdateInstallFailure.NETWORK, "SHA HTTP " + response.code)
                }
                response.body?.string() ?: throw UpdateInstallException(
                    UpdateInstallFailure.NETWORK,
                    "empty SHA response body"
                )
            }
        } catch (error: IOException) {
            throw UpdateInstallException(UpdateInstallFailure.NETWORK, "I/O failure while fetching SHA-256", error)
        }
        return UpdateChecksum.parseExpectedHash(text)
            ?: throw UpdateInstallException(UpdateInstallFailure.SHA_MISMATCH, "invalid SHA-256 file")
    }

    /** 流式计算文件 SHA-256，返回小写十六进制串。 */
    private fun sha256Of(file: File): String =
        file.inputStream().buffered().use { input -> UpdateChecksum.sha256Hex(input) }

    /** 优先用 URL 末段的资产名；不合法时回退到发布工作流的标准命名。 */
    private fun apkFileName(release: GitHubRelease): String {
        val fromUrl = release.apkUrl.substringAfterLast('/').substringBefore('?').trim()
        if (APK_FILE_NAME.matches(fromUrl)) return fromUrl
        val version = release.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown" }
        return "IwaraM-" + version + "-arm64-v8a.apk"
    }

    /** GitHub 要求每个请求带 User-Agent。 */
    private fun userAgent(release: GitHubRelease): String =
        "IwaraM/" + release.versionName.ifBlank { "unknown" }
}
