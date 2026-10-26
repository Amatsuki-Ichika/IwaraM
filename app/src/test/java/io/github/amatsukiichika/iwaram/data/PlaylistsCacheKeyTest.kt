package io.github.amatsukiichika.iwaram.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 播放列表离线缓存按账号分区。
 *
 * 曾经的全局 key 会把 A 的播放列表在断网时显示给 B，而界面无从判断这份缓存属于谁。
 * 这里钉住两条契约：不同账号的 key 必须不同；拿不到账号归属时干脆没有 key
 * （不读也不写），宁可没有缓存也不冒串号的风险。
 */
class PlaylistsCacheKeyTest {

    @Test
    fun `cache keys are partitioned per account so one account never reads another ones playlists`() {
        assertEquals("playlists_cache_u1", playlistsCacheKey("u1"))
        assertNotEquals(playlistsCacheKey("u1"), playlistsCacheKey("u2"))
    }

    @Test
    fun `an unknown account has no cache slot at all`() {
        assertNull(playlistsCacheKey(""))
        assertNull(playlistsCacheKey("   "))
    }
}
