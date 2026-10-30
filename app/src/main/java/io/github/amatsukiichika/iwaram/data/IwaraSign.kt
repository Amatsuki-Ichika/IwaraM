package io.github.amatsukiichika.iwaram.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.amatsukiichika.iwaram.i18n.tr
import okhttp3.Request
import java.net.URI
import java.security.MessageDigest

/**
 * Iwara 文件服务器（files.iwara.tv）的 X-Version 签名与播放地址解析。
 *
 * 算法（yt-dlp / LoveIwara / IwaraDownloadTool 三方一致）：
 *   X-Version = SHA1("{fileUrl最后一段}_{expires参数}_{SALT}")，小写 hex。
 * 不带该头时文件接口只返回 preview / 360p 两档。
 *
 * 盐值若服务端更换，只需修改 [SALT]。签名 URL 短时效，播放/下载前必须现取 fileUrl。
 */
object IwaraSign {

    private const val SALT = "mSvL05GfEmeEmsEYfGCnVpEjYgTJraJN"

    private val gson = Gson()
    private val qualityListType = object : TypeToken<List<IwaraQualitySource>>() {}.type

    fun xVersion(fileUrl: String): String {
        val uri = URI(fileUrl)
        val fileName = uri.path.substringAfterLast('/')
        val expires = uri.rawQuery?.split('&')
            ?.firstOrNull { it.startsWith("expires=") }
            ?.substringAfter("expires=")
            .orEmpty()
        val seed = "${fileName}_${expires}_$SALT"
        val digest = MessageDigest.getInstance("SHA-1").digest(seed.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    /**
     * 请求 fileUrl（带 X-Version），返回全部清晰度列表。
     * 使用不带鉴权头的 [IwaraClient.fileHttp]（CDN 地址自带签名，多余的头可能被拒）。
     */
    fun fetchQualities(fileUrl: String): List<IwaraQualitySource> {
        val request = Request.Builder()
            .url(fileUrl)
            .header("X-Version", xVersion(fileUrl))
            .header("User-Agent", IwaraClient.USER_AGENT)
            .header("Referer", IwaraClient.SITE_URL + "/")
            .header("Accept", "application/json")
            .get()
            .build()
        IwaraClient.fileHttp.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IwaraSession.IwaraApiException(tr("解析播放地址失败（HTTP ${resp.code}）", "Failed to resolve the playback address (HTTP ${resp.code})"))
            }
            // JSON 解析在 try 中抛带上下文的异常：调用方（播放页/下载）已有 catch，
            // 直接抛出的 JsonSyntaxException 无上下文，排查时不知道是哪个地址解析失败。
            return try {
                gson.fromJson(text, qualityListType) ?: emptyList()
            } catch (e: Exception) {
                throw IwaraSession.IwaraApiException(
                    tr(
                        "解析播放地址失败：响应不是有效的清晰度列表（${e.javaClass.simpleName}）",
                        "Failed to resolve the playback address: the response is not a valid quality list " +
                            "(${e.javaClass.simpleName})"
                    ),
                    isAuthError = false
                )
            }
        }
    }

    /**
     * 按偏好挑清晰度：优先精确匹配 [preferredName]（如 "Source"），
     * 否则取列表第一项（服务端一般把最高画质放在最前）。
     * @return (清晰度名, view 地址)；列表为空或字段缺失返回 null
     */
    fun pickViewUrl(
        qualities: List<IwaraQualitySource>,
        preferredName: String?
    ): Pair<String, String>? {
        val usable = qualities.filter { !it.src?.view.isNullOrBlank() }
        if (usable.isEmpty()) return null
        val match = preferredName?.let { name -> usable.firstOrNull { it.name == name } }
        val chosen = match ?: usable.first()
        return chosen.name.orEmpty() to httpsUrl(chosen.src!!.view!!)
    }

    /** 兼容协议相对地址（//host/...），与 yt-dlp 的 _proto_relative_url 同款处理。 */
    fun httpsUrl(url: String): String = if (url.startsWith("//")) "https:$url" else url

    /**
     * 构造下载地址：归一化协议相对地址（src.download 常为 //host/...，不归一化 OkHttp 直接报
     * "Expected URL scheme..."）→ 清空白 → 追加 download= 文件名参数（供浏览器另存名）。
     * 结果必须以 http(s) 开头，否则返回 null（调用方报"下载地址无效"）。
     */
    fun buildDownloadUrl(rawUrl: String, fileName: String): String? {
        val base = httpsUrl(rawUrl.trim())
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        val encoded = java.net.URLEncoder.encode(fileName, "UTF-8")
        return base + (if (base.contains('?')) "&" else "?") + "download=" + encoded
    }
}
