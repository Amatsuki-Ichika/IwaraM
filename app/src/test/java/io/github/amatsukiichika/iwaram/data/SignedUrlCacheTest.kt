package io.github.amatsukiichika.iwaram.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 签名播放地址缓存的纯逻辑：TTL、覆盖、过期、并发合并，以及「预取命中后选档不再进网」。
 *
 * 时间一律由注入的固定时钟推进（不 sleep），并发合并用 CompletableDeferred 卡住第一次加载、
 * Unconfined 让第二次调用确定性地撞进同一个 in-flight 槽位。
 */
class SignedUrlCacheTest {

    private var now = 1_000_000L

    private fun newCache() = SignedUrlCache { now }

    /** 生成一个在 now + [ttlMs] 过期的签名地址（服务端以秒为单位给 expires）。 */
    private fun signedUrl(path: String, ttlMs: Long = 600_000L) =
        "https://cdn.iwara.tv/$path.mp4?expires=" + ((now + ttlMs) / 1000)

    @Test
    fun `a prefetched quality is selected without another network load`() = runBlocking {
        val cache = newCache()
        cache.store(
            "v1",
            listOf(
                SignedQuality("1080p", signedUrl("v1-1080")),
                SignedQuality("Source", signedUrl("v1-source"))
            )
        )
        var loads = 0
        val qualities = cache.loadQualities("v1") {
            loads++
            error("缓存命中时不该再加载")
        }
        assertEquals(0, loads)
        val chosen = selectPlaybackQuality("Source", qualities)
        assertEquals("Source", chosen?.name)
        assertEquals(signedUrl("v1-source"), chosen?.url)
    }

    @Test
    fun `a cache miss loads once and the next call is served from memory`() = runBlocking {
        val cache = newCache()
        var loads = 0
        val first = cache.loadQualities("v1") {
            loads++
            listOf(SignedQuality("Source", signedUrl("source")))
        }
        val second = cache.loadQualities("v1") {
            loads++
            error("第二次应当命中缓存")
        }
        assertEquals(1, loads)
        assertEquals(first, second)
        assertEquals("Source", cache.cachedQualities("v1").single().name)
    }

    @Test
    fun `concurrent loads for the same video share one network call`() = runBlocking {
        val cache = newCache()
        val firstLoadStarted = CompletableDeferred<Unit>()
        val releaseFirstLoad = CompletableDeferred<Unit>()
        var loads = 0
        val first = async(Dispatchers.Default) {
            cache.loadQualities("v1") {
                loads++
                firstLoadStarted.complete(Unit)
                releaseFirstLoad.await()
                listOf(SignedQuality("Source", signedUrl("source")))
            }
        }
        firstLoadStarted.await()
        // Unconfined 上会同步执行到 await：此时第一次加载已在 in-flight 里，第二次只能等它。
        val second = async(Dispatchers.Unconfined) {
            cache.loadQualities("v1") {
                loads++
                error("并发调用不该自己再加载一次")
            }
        }
        releaseFirstLoad.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, loads)
    }

    @Test
    fun `a failed load is not cached so the next call retries`() = runBlocking {
        val cache = newCache()
        var loads = 0
        try {
            cache.loadQualities("v1") {
                loads++
                throw IllegalStateException("网络失败")
            }
            fail("加载失败必须抛给调用方")
        } catch (expected: IllegalStateException) {
            assertEquals("网络失败", expected.message)
        }
        val retried = cache.loadQualities("v1") {
            loads++
            listOf(SignedQuality("Source", signedUrl("source")))
        }
        assertEquals(2, loads)
        assertEquals("Source", retried.single().name)
    }

    @Test
    fun `an expired signed url is dropped before its real expiry`() {
        val cache = newCache()
        cache.store("v1", listOf(SignedQuality("Source", signedUrl("source"))))
        // 提前 PLAYBACK_URL_EXPIRY_SLACK_MS 判失效：留出播放器建连与一次重试的余量。
        now += 600_000L - PLAYBACK_URL_EXPIRY_SLACK_MS - 1
        assertEquals(1, cache.cachedQualities("v1").size)
        now += 1
        assertTrue(cache.cachedQualities("v1").isEmpty())
    }

    @Test
    fun `only the expired quality of a video is dropped`() {
        val cache = newCache()
        cache.store(
            "v1",
            listOf(
                SignedQuality("Preview", signedUrl("preview", ttlMs = 120_000L)),
                SignedQuality("Source", signedUrl("source", ttlMs = 600_000L))
            )
        )
        now += 120_000L - PLAYBACK_URL_EXPIRY_SLACK_MS + 1
        assertEquals(listOf("Source"), cache.cachedQualities("v1").map { it.name })
    }

    @Test
    fun `a url without expires uses the fallback ttl`() {
        val cache = newCache()
        cache.store("v1", listOf(SignedQuality("Source", "https://cdn.iwara.tv/source.mp4")))
        assertTrue(cache.cachedQualities("v1").isNotEmpty())
        now += PLAYBACK_URL_FALLBACK_TTL_MS - PLAYBACK_URL_EXPIRY_SLACK_MS - 1
        assertTrue(cache.cachedQualities("v1").isNotEmpty())
        now += 1
        assertTrue(cache.cachedQualities("v1").isEmpty())
    }

    @Test
    fun `storing the same quality again replaces the cached url`() {
        val cache = newCache()
        cache.store("v1", listOf(SignedQuality("Source", signedUrl("old"))))
        cache.store("v1", listOf(SignedQuality("Source", signedUrl("new"))))
        assertEquals(signedUrl("new"), cache.cachedQualities("v1").single().url)
    }

    @Test
    fun `storing an empty result does not evict the warm cache`() {
        val cache = newCache()
        cache.store("v1", listOf(SignedQuality("Source", signedUrl("source"))))
        cache.store("v1", emptyList())
        assertEquals(1, cache.cachedQualities("v1").size)
    }

    @Test
    fun `invalidating a rejected url forces the next resolve to load again`() = runBlocking {
        val cache = newCache()
        val stale = signedUrl("source")
        cache.store("v1", listOf(SignedQuality("Source", stale)))
        cache.invalidateUrl(stale)
        var loads = 0
        val fresh = cache.loadQualities("v1") {
            loads++
            listOf(SignedQuality("Source", signedUrl("source-2")))
        }
        assertEquals(1, loads)
        assertEquals(signedUrl("source-2"), fresh.single().url)
    }

    @Test
    fun `invalidating a re-serialized query still evicts the cached entry`() {
        val cache = newCache()
        cache.store("v1", listOf(SignedQuality("Source", "https://cdn.iwara.tv/source.mp4?expires=1600&hash=abc")))
        // OkHttp 会把 query 重新序列化，整串比较可能失配；按 host + path 仍能认出同一资源。
        cache.invalidateUrl("https://cdn.iwara.tv/source.mp4?hash=abc&expires=1600")
        assertTrue(cache.cachedQualities("v1").isEmpty())
    }

    @Test
    fun `selection uses the preferred quality and falls back to the first available`() {
        val candidates = listOf(
            SignedQuality("Source", "https://cdn.iwara.tv/source.mp4"),
            SignedQuality("360p", "https://cdn.iwara.tv/360.mp4")
        )
        assertEquals("360p", selectPlaybackQuality("360p", candidates)?.name)
        assertEquals("Source", selectPlaybackQuality("1080p", candidates)?.name)
        assertEquals("Source", selectPlaybackQuality(null, candidates)?.name)
        assertNull(selectPlaybackQuality("360p", emptyList()))
    }
}
