package io.github.amatsukiichika.iwaram.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迟到刷新结果的归属判据：只有代际与 refresh token 都没变，结果才允许写回。
 *
 * 这些断言对应 IwaraSession 的并发窗口：登出或换号后，旧账号的刷新可能刚好返回。
 */
class SessionGenerationTest {

    @Test
    fun `an unchanged session keeps a refresh result`() {
        assertTrue(sessionTokenIsCurrent(7, 7, "refresh-a", "refresh-a"))
    }

    @Test
    fun `a newer session generation drops the result`() {
        assertFalse(sessionTokenIsCurrent(7, 8, "refresh-a", "refresh-a"))
    }

    @Test
    fun `a different refresh token drops the result`() {
        assertFalse(sessionTokenIsCurrent(7, 7, "refresh-a", "refresh-b"))
    }

    @Test
    fun `a logged out session drops the result`() {
        assertFalse(sessionTokenIsCurrent(7, 7, "refresh-a", null))
    }

    @Test
    fun `a refresh without a captured token is never current`() {
        assertFalse(sessionTokenIsCurrent(7, 7, null, null))
    }
}
