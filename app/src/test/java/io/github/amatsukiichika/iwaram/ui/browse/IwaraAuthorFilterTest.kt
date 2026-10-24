package io.github.amatsukiichika.iwaram.ui.browse

import com.google.gson.Gson
import io.github.amatsukiichika.iwaram.data.IwaraProfileData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「在线视频按作者筛选拉不到内容」的回归用例。
 *
 * 根因：`GET /profile/{username}` 的真实响应是**嵌套**的 `{"user":{...}}`
 * （UUID 挂在 `user` 里），而按**扁平**解析会得到 null id →
 * 作者筛选直接抛「无法获取作者信息」、作者主页的「TA 的视频」也拉不出来。
 */
class IwaraAuthorFilterTest {

    private val gson = Gson()

    @Test
    fun `profile parses the nested user wrapper`() {
        val uid = "3f6a1c8e-7b2d-4a91-9e5f-0c1d2e3f4a5b"
        val json = """
            {"user":{"id":"$uid","name":"Lyu ya","username":"user792540","avatar":{"id":"abc"},
             "followsCount":12,"followersCount":345,"videoCount":70},
             "images":[],"videos":[]}
        """.trimIndent()
        val p = gson.fromJson(json, IwaraProfileData::class.java)
        assertEquals(uid, p.uid)
        assertEquals("Lyu ya", p.displayName)
        assertEquals("user792540", p.handle)
        assertEquals("abc", p.avatarRef?.id)
        // counts 也可能挂在 user 里面（顶层缺失时要用嵌套值）
        assertEquals(12, p.followsTotal)
        assertEquals(345, p.followersTotal)
        assertEquals(70, p.videoTotal)
        // 关键：作者筛选要用 uid，不能再是 null
        assertEquals(uid, p.asAccount().id)
    }

    @Test
    fun `profile still accepts the flat response shape`() {
        val uid = "11111111-2222-3333-4444-555555555555"
        val json = """{"id":"$uid","name":"Flat","username":"flatuser","followsCount":1}"""
        val p = gson.fromJson(json, IwaraProfileData::class.java)
        assertEquals(uid, p.uid)
        assertEquals("Flat", p.displayName)
        assertEquals("flatuser", p.handle)
        assertEquals(1, p.followsTotal)
    }

    @Test
    fun `profile tolerates a body-only response without crashing`() {
        val p = gson.fromJson("""{"message":"errors.notFound"}""", IwaraProfileData::class.java)
        assertNull(p.uid)
        assertNull(p.displayName)
        assertNull(p.handle)
        assertNull(p.bio)
    }

    @Test
    fun `uuid detection distinguishes user id from username`() {
        assertTrue(isIwaraUserUuid("3f6a1c8e-7b2d-4a91-9e5f-0c1d2e3f4a5b"))
        assertTrue(isIwaraUserUuid("3F6A1C8E-7B2D-4A91-9E5F-0C1D2E3F4A5B"))
        assertTrue(isIwaraUserUuid("  3f6a1c8e-7b2d-4a91-9e5f-0c1d2e3f4a5b  "))
        assertFalse(isIwaraUserUuid("user792540"))
        assertFalse(isIwaraUserUuid("Lyu ya"))
        assertFalse(isIwaraUserUuid(""))
        // 长度对但连字符位置不对 → 不是 UUID
        assertFalse(isIwaraUserUuid("3f6a1c8e7b2d-4a91-9e5f-0c1d2e3f4a5b"))
    }
}
