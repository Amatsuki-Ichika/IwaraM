package io.github.amatsukiichika.iwaram.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作者页参数归一 / 资料重试判定 / 关注打底冷却的纯逻辑用例。
 *
 * 覆盖：仅 handle、仅 UUID、两者都有、userId 里混进 handle、空白参数、不可用目标，
 * 以及 429/5xx 可重试、4xx 不可重试、5 分钟失败冷却与时钟回拨。
 */
class IwaraProfileTargetTest {

    private val uuid = "3f6a1c8e-7b2d-4a91-9e5f-0c1d2e3f4a5b"

    @Test
    fun `username only needs a profile lookup`() {
        val target = iwaraProfileTargetOf("user792540", null)
        assertEquals("user792540", target.handle)
        assertNull(target.uid)
        assertEquals("user792540", target.contentId)
        assertTrue(target.needsProfileLookup)
        assertFalse(target.unavailable)
    }

    @Test
    fun `uuid only skips the profile lookup`() {
        val target = iwaraProfileTargetOf(null, uuid)
        assertNull(target.handle)
        assertEquals(uuid, target.uid)
        assertEquals(uuid, target.contentId)
        assertFalse(target.needsProfileLookup)
        assertFalse(target.unavailable)
    }

    @Test
    fun `uuid wins as the content key when both entries are present`() {
        val target = iwaraProfileTargetOf("user792540", uuid)
        assertEquals("user792540", target.handle)
        assertEquals(uuid, target.uid)
        assertEquals(uuid, target.contentId)
        assertFalse(target.needsProfileLookup)
        assertFalse(target.unavailable)
    }

    @Test
    fun `non uuid user id falls back to handle`() {
        val target = iwaraProfileTargetOf("", "user792540")
        assertEquals("user792540", target.handle)
        assertNull(target.uid)
        assertEquals("user792540", target.contentId)
        assertTrue(target.needsProfileLookup)
        assertFalse(target.unavailable)
    }

    @Test
    fun `username wins over a non uuid user id`() {
        val target = iwaraProfileTargetOf("realName", "user792540")
        assertEquals("realName", target.handle)
        assertNull(target.uid)
        assertEquals("realName", target.contentId)
        assertTrue(target.needsProfileLookup)
    }

    @Test
    fun `parameters are trimmed before use`() {
        val trimmed = iwaraProfileTargetOf("  user792540  ", "   ")
        assertEquals("user792540", trimmed.handle)
        assertEquals("user792540", trimmed.contentId)
        assertTrue(trimmed.needsProfileLookup)

        val spacedUuid = iwaraProfileTargetOf("   ", "  $uuid  ")
        assertEquals(uuid, spacedUuid.uid)
        assertNull(spacedUuid.handle)
        assertFalse(spacedUuid.needsProfileLookup)
    }

    @Test
    fun `empty parameters produce an unavailable target`() {
        val target = iwaraProfileTargetOf(null, null)
        assertNull(target.handle)
        assertNull(target.uid)
        assertNull(target.contentId)
        assertFalse(target.needsProfileLookup)
        assertTrue(target.unavailable)

        assertTrue(iwaraProfileTargetOf("", "  ").unavailable)
    }

    @Test
    fun `profile failure is fatal only without a content key`() {
        assertFalse(profileFailureIsFatal(iwaraProfileTargetOf("user792540", null)))
        assertFalse(profileFailureIsFatal(iwaraProfileTargetOf(null, uuid)))
        assertTrue(profileFailureIsFatal(iwaraProfileTargetOf(null, null)))
    }

    @Test
    fun `rate limit and server errors are retryable`() {
        assertTrue(retryableProfileError(429))
        assertTrue(retryableProfileError(500))
        assertTrue(retryableProfileError(503))
        assertTrue(retryableProfileError(599))
    }

    @Test
    fun `client errors and missing codes are not retryable`() {
        assertFalse(retryableProfileError(null))
        assertFalse(retryableProfileError(400))
        assertFalse(retryableProfileError(401))
        assertFalse(retryableProfileError(403))
        assertFalse(retryableProfileError(404))
        assertFalse(retryableProfileError(499))
    }

    @Test
    fun `follow seed retry is blocked during the cooldown`() {
        val failedAt = 1_000_000L
        assertTrue(followSeedRetryAllowed(null, failedAt))
        assertFalse(followSeedRetryAllowed(failedAt, failedAt))
        assertFalse(followSeedRetryAllowed(failedAt, failedAt + FOLLOW_SEED_RETRY_COOLDOWN_MS - 1))
        assertTrue(followSeedRetryAllowed(failedAt, failedAt + FOLLOW_SEED_RETRY_COOLDOWN_MS))
    }

    @Test
    fun `follow seed retry survives a clock jumping backwards`() {
        val failedAt = 10_000_000L
        assertTrue(followSeedRetryAllowed(failedAt, failedAt - 60_000L))
    }
}
