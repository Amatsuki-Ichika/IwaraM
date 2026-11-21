package io.github.amatsukiichika.iwaram.data

import kotlinx.coroutines.CompletableDeferred
import java.net.URI

/**
 * 在线播放签名地址缓存：按 (videoId, quality) 存 [SignedQuality]，带 TTL。
 *
 * 播放地址带 `expires` 签名、寿命很短，而开播链路要串行走「视频详情 → 清晰度解析」两跳网络。
 * 把清晰度解析结果缓存起来后，播放页「相关推荐」预取的下一集在点开时可直接命中，省掉一跳；
 * [loadQualities] 同时把并发请求合并成一次加载，预取与现取不会重复打同一个接口。
 *
 * 这里只有纯逻辑：网络由调用方注入的 loader 完成，时钟可注入 —— 单测不需要 sleep。
 */
internal data class SignedUrlKey(val videoId: String, val quality: String)

/** 一个清晰度档位的播放地址（档名保持服务端原样，用于偏好匹配与缓存键）。 */
internal data class SignedQuality(val name: String, val url: String)

/** 签名地址在真实过期前提前多久判为失效：给播放器建立连接与一次重试留出余量。 */
internal const val PLAYBACK_URL_EXPIRY_SLACK_MS = 30_000L

/** 地址里没有 `expires` 时按多长寿命缓存（正常签名地址总带 expires，这里只是兜底）。 */
internal const val PLAYBACK_URL_FALLBACK_TTL_MS = 10 * 60_000L

/** 缓存条目上限：一集最多几档，256 条约等于几十集；超出按最旧写入淘汰。 */
internal const val PLAYBACK_URL_MAX_ENTRIES = 256

internal class SignedUrlCache(private val clock: () -> Long = { System.currentTimeMillis() }) {

    private class Entry(val url: String, val expiresAtMs: Long)

    private class LoadSlot(val deferred: CompletableDeferred<List<SignedQuality>>, val isLeader: Boolean)

    private val lock = Any()
    private val entries = LinkedHashMap<SignedUrlKey, Entry>()
    private val inFlight = HashMap<String, CompletableDeferred<List<SignedQuality>>>()

    /** 该视频当前仍有效的档位（保持服务端顺序）；没有缓存或全部过期返回空表。 */
    fun cachedQualities(videoId: String): List<SignedQuality> = synchronized(lock) {
        freshLocked(videoId).orEmpty()
    }

    /** 记录一次解析结果；空表不写，避免「没有可用地址」覆盖掉仍然有效的旧缓存。 */
    fun store(videoId: String, qualities: List<SignedQuality>) {
        if (qualities.isEmpty()) return
        synchronized(lock) { storeLocked(videoId, qualities) }
    }

    /**
     * 取该视频的档位：命中缓存直接返回（不调用 [loader]），未命中才加载；
     * 同一视频的并发调用共享一次加载。[loader] 抛出的异常原样抛给所有等待者。
     *
     * 注意：调用方应确保 [loader] 的阻塞网络工作在 IO 线程完成。
     */
    suspend fun loadQualities(
        videoId: String,
        loader: suspend () -> List<SignedQuality>
    ): List<SignedQuality> {
        val slot = synchronized(lock) {
            freshLocked(videoId)?.let { return it }
            loadSlotLocked(videoId)
        }
        if (!slot.isLeader) return slot.deferred.await()
        try {
            val qualities = loader()
            if (qualities.isNotEmpty()) synchronized(lock) { storeLocked(videoId, qualities) }
            slot.deferred.complete(qualities)
            return qualities
        } catch (t: Throwable) {
            // 失败也通知等待者：它们与领头者共享同一次网络结果，各自上层按自己的错误文案处理。
            slot.deferred.completeExceptionally(t)
            throw t
        } finally {
            synchronized(lock) {
                if (inFlight[videoId] === slot.deferred) inFlight.remove(videoId)
            }
        }
    }

    /**
     * 淘汰被 CDN 拒绝的地址（播放器收到 403/404/410 时调用）：下一轮解析不再复用它，
     * 播放页既有的「自动重试解析」便会拿到新签名，而不是在死地址上反复重试。
     */
    fun invalidateUrl(url: String) {
        synchronized(lock) {
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                if (sameResource(iterator.next().value.url, url)) iterator.remove()
            }
        }
    }

    /** 返回 null 表示没有任何未过期缓存；顺手清掉该视频的过期条目。 */
    private fun freshLocked(videoId: String): List<SignedQuality>? {
        val now = clock()
        val result = ArrayList<SignedQuality>()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val next = iterator.next()
            if (next.key.videoId != videoId) continue
            if (next.value.expiresAtMs <= now) {
                iterator.remove()
                continue
            }
            result.add(SignedQuality(next.key.quality, next.value.url))
        }
        return result.ifEmpty { null }
    }

    private fun storeLocked(videoId: String, qualities: List<SignedQuality>) {
        val now = clock()
        qualities.forEach { quality ->
            val key = SignedUrlKey(videoId, quality.name)
            // 先删再插：覆盖写会排到插入序末尾，淘汰时按写入新旧挑最旧的。
            entries.remove(key)
            entries[key] = Entry(quality.url, expiresAtMs(quality.url, now))
        }
        pruneLocked(now)
    }

    /** 生效过期时间 = 地址自带的 expires - [PLAYBACK_URL_EXPIRY_SLACK_MS]；无 expires 用兜底寿命。 */
    private fun expiresAtMs(url: String, nowMs: Long): Long {
        val ttlMs = runCatching { signedUrlTtlMs(url, nowMs) }.getOrNull() ?: PLAYBACK_URL_FALLBACK_TTL_MS
        return nowMs + (ttlMs - PLAYBACK_URL_EXPIRY_SLACK_MS).coerceAtLeast(0L)
    }

    private fun pruneLocked(nowMs: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAtMs <= nowMs) iterator.remove()
        }
        var overflow = entries.size - PLAYBACK_URL_MAX_ENTRIES
        if (overflow <= 0) return
        val oldest = entries.entries.iterator()
        while (overflow > 0 && oldest.hasNext()) {
            oldest.next()
            oldest.remove()
            overflow--
        }
    }

    private fun loadSlotLocked(videoId: String): LoadSlot {
        val running = inFlight[videoId]
        if (running != null) return LoadSlot(running, isLeader = false)
        val deferred = CompletableDeferred<List<SignedQuality>>()
        inFlight[videoId] = deferred
        return LoadSlot(deferred, isLeader = true)
    }

    /** 整串相等，或 host + path 相同（兼容 OkHttp 对 URL 的再规范化），都视为同一资源。 */
    private fun sameResource(cachedUrl: String, requestedUrl: String): Boolean {
        if (cachedUrl == requestedUrl) return true
        val cached = runCatching { URI(cachedUrl) }.getOrNull() ?: return false
        val requested = runCatching { URI(requestedUrl) }.getOrNull() ?: return false
        return cached.host == requested.host && cached.rawPath == requested.rawPath
    }
}

/**
 * 选档纯逻辑：偏好档存在就用它，否则回退列表第一个（最高可用档），候选为空返回 null。
 * 与旧的 `firstOrNull { name == preferred } ?: first()` 等价，抽出来便于单测。
 */
internal fun selectPlaybackQuality(preferred: String?, candidates: List<SignedQuality>): SignedQuality? {
    if (candidates.isEmpty()) return null
    return candidates.firstOrNull { it.name == preferred } ?: candidates.first()
}

/** 进程级缓存：清晰度预取与播放解析共用一份（不落盘，进程结束即清空）。 */
internal val playbackSignedUrlCache = SignedUrlCache()
