package io.github.amatsukiichika.iwaram.data

import io.github.amatsukiichika.iwaram.i18n.tr
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次下载要用的目标信息：签名地址、落盘文件名、档位。 */
internal data class DownloadTarget(
    val url: String,
    val title: String,
    val fileName: String,
    val quality: String
)

/**
 * 从服务端给出的清晰度列表里挑一档用于下载（纯函数，可单测）。
 *
 * 只考虑**能下载**的档（`src.download` 或 `src.view` 至少有一个），按优先级：
 *  ① 用户设置的档位；② [IwaraSign.pickViewUrl] 推荐的档；③ 列表第一档（服务端一般按画质降序）。
 * 全都不可用时返回 null，由调用方报"没有可用的下载地址"。
 */
internal fun chooseDownloadQuality(
    qualities: List<IwaraQualitySource>,
    preferred: String
): IwaraQualitySource? {
    val usable = qualities.filter {
        !it.src?.download.isNullOrBlank() || !it.src?.view.isNullOrBlank()
    }
    return usable.firstOrNull { it.name == preferred }
        ?: IwaraSign.pickViewUrl(qualities, preferred)?.let { picked ->
            usable.firstOrNull { it.name == picked.first }
        }
        ?: usable.firstOrNull()
}

/**
 * 解析一条可下载的签名地址（详情 → 清晰度 → 归一化 URL + 文件名）。
 *
 * 下载入口与"排队期间地址过期"的重新解析走同一条路径：
 * 地址带 `expires`，解析一次不能复用（见 [isSignedUrlExpired]）。
 *
 * @param qualityName 指定档位；null 表示用设置里的下载清晰度
 * @throws IwaraSession.IwaraApiException 视频不可下载或没有可用地址
 */
internal suspend fun resolveDownloadTarget(
    repo: IwaraRepository,
    videoId: String,
    qualityName: String? = null
): DownloadTarget {
    val detail = IwaraClient.call { it.video(videoId) }
    val fileUrl = detail.fileUrl
    if (fileUrl.isNullOrBlank()) {
        throw IwaraSession.IwaraApiException(
            if (detail.embedUrl != null) {
                tr("外嵌视频暂不支持下载", "Embedded videos cannot be downloaded yet")
            } else {
                tr("该视频没有可下载的文件", "This video has no downloadable file")
            },
            isAuthError = false
        )
    }
    val qualities = withContext(Dispatchers.IO) { IwaraSign.fetchQualities(fileUrl) }
    val chosen = chooseDownloadQuality(qualities, qualityName ?: repo.getDownloadQuality())
        ?: throw IwaraSession.IwaraApiException(
            tr("没有可用的下载地址", "No download address available"),
            isAuthError = false
        )
    val title = detail.title?.takeIf { it.isNotBlank() } ?: videoId
    // 文件名里带 [iwaraId] 与档位：扫描时据此识别视频身份，续传时据此确认档位一致。
    val fileName = "[$videoId] $title (${chosen.name}).mp4"
    val raw = chosen.src?.download?.takeIf { it.isNotBlank() } ?: chosen.src?.view.orEmpty()
    val url = IwaraSign.buildDownloadUrl(raw, fileName)
        ?: throw IwaraSession.IwaraApiException(
            tr("下载地址无效", "Invalid download address"),
            isAuthError = false
        )
    return DownloadTarget(url, title, fileName, chosen.name.orEmpty())
}

/**
 * 算签名地址还有多久过期（毫秒）；地址不带 `expires` 或值不可解析时返回 null（视为不会过期）。
 *
 * `expires` 是秒级 Unix 时间戳；个别接口给毫秒，这里按量级判定，避免把毫秒当秒
 * （那样会把所有地址判成已过期，每次下载都白解析一次）。
 * 抽出纯函数是为了能单测边界：排队任务正是靠它判断"排了这么久，地址还能用吗"。
 */
internal fun signedUrlTtlMs(url: String, nowMs: Long): Long? {
    val raw = runCatching {
        URI(url).rawQuery?.split('&')
            ?.firstOrNull { it.startsWith("expires=") }
            ?.substringAfter("expires=")
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    val value = raw.toLongOrNull() ?: return null
    // 1e12 以上必然是毫秒（秒级到 1e12 要到公元 33658 年）。
    val expiresAtMs = if (value >= 1_000_000_000_000L) value else value * 1000L
    return expiresAtMs - nowMs
}

/** 地址是否已经过期（或余量不足 [slackMs]）：队列里等了很久的任务据此提前重新解析。 */
internal fun isSignedUrlExpired(
    url: String,
    nowMs: Long,
    slackMs: Long = DOWNLOAD_URL_EXPIRY_SLACK_MS
): Boolean {
    val ttl = signedUrlTtlMs(url, nowMs) ?: return false
    return ttl <= slackMs
}

/** 留出的安全余量：地址余量不足一分钟就换新的，避免请求在路上过期。 */
internal const val DOWNLOAD_URL_EXPIRY_SLACK_MS = 60_000L
