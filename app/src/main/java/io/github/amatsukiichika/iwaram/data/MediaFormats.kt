package io.github.amatsukiichika.iwaram.data

/**
 * 本地媒体文件的扩展名白名单。
 *
 * 只按扩展名判断 media3 能否在进程内打开这个容器（ProgressiveMediaSource + 平台解码器）；
 * 不读取文件内容，也不承诺容器里的编码一定能解码 —— 解码失败由播放器的解码器回退兜底，
 * 仍失败时播放页错误面板提供"用其他应用打开"。
 *
 * 视频容器用于下载与本地扫描；音频容器只用于本地扫描（纯音频没有视频轨，播放页显示音频占位）。
 */
object MediaFormats {
    /** 视频容器 -> MIME。 */
    private val videoMimeTypes = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mov" to "video/quicktime",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "ts" to "video/mp2t",
        "m2ts" to "video/mp2t",
        "mts" to "video/mp2t",
        "flv" to "video/x-flv",
        "ogv" to "video/ogg",
        "avi" to "video/x-msvideo",
        "3gp" to "video/3gpp",
        "3g2" to "video/3gpp2",
    )

    /** 音频容器 -> MIME。 */
    private val audioMimeTypes = mapOf(
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "m4b" to "audio/mp4",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/opus",
        "wav" to "audio/wav",
        "amr" to "audio/amr",
        "mka" to "audio/x-matroska",
    )

    /**
     * 文件名是否是支持的媒体文件（不区分大小写）。
     *
     * `null`、空白、没有扩展名一律返回 `false` —— 判据只看最后一段扩展名。
     */
    fun isSupportedMediaFile(name: String?): Boolean = mimeTypeFor(name) != null

    /**
     * 文件名对应容器的 MIME 类型；不支持的扩展名返回 `null`。
     *
     * 供外部播放器 chooser 用：本地 content:// 与在线 http(s) 都可能带查询串，
     * 调用点应只传路径最后一段（例如 `url.substringAfterLast('/')`）。
     */
    fun mimeTypeFor(name: String?): String? {
        val extension = extensionOf(name) ?: return null
        return videoMimeTypes[extension] ?: audioMimeTypes[extension]
    }

    /** 取最后一段扩展名（小写）；`null`、空白、无扩展名返回 `null`。 */
    private fun extensionOf(name: String?): String? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val extension = trimmed.substringAfterLast('.', missingDelimiterValue = "")
        return extension.lowercase().ifEmpty { null }
    }
}
