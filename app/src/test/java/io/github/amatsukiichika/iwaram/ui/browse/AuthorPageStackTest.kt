package io.github.amatsukiichika.iwaram.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「播放页点作者」的导航计划 —— 作者页与播放页谁在上面，决定要不要关播放页。
 *
 * 规则被破坏时的表现：作者页 → 视频 → 点作者 = 播放页先关掉、作者页新压一层（用户看到的是
 * 视频页闪退再进作者页，返回后视频没了）；播放页 → 点作者 = 作者页盖在播放页上（这是期望行为，
 * 返回后应继续看到暂停中的播放页）。
 */
class AuthorPageStackTest {

    private fun profile(
        username: String = "",
        userId: String? = null,
        localAuthorKey: String? = null
    ) = IwaraOverlay.Profile(
        username = username,
        userId = userId,
        localAuthorKey = localAuthorKey
    )

    private fun page(seq: Long, overlay: IwaraOverlay) = OverlayEntry(seq, overlay)

    @Test
    fun `no author page in stack pushes a new one on top of the player`() {
        val plan = authorNavPlan(emptyList(), playerSeq = 2L, showingPlayer = true, incoming = profile("alice"))
        assertEquals(AuthorNavPlan.PushOnTop, plan)
    }

    @Test
    fun `author below the player is popped to and the player closes`() {
        // 作者页 → 进视频：作者页在下面，播放页盖着它。
        val stack = listOf(page(1L, profile("alice")))
        val plan = authorNavPlan(stack, playerSeq = 5L, showingPlayer = true, incoming = profile("alice"))
        assertEquals(AuthorNavPlan.PopTo(targetIndex = 0, closesPlayer = true), plan)
    }

    @Test
    fun `author above the player is popped to and the player stays`() {
        // 播放页 → 点作者：作者页压在播放页上面。
        val stack = listOf(page(5L, profile("alice")))
        val plan = authorNavPlan(stack, playerSeq = 2L, showingPlayer = true, incoming = profile("alice"))
        assertEquals(AuthorNavPlan.PopTo(targetIndex = 0, closesPlayer = false), plan)
    }

    @Test
    fun `no player open never closes a player`() {
        val stack = listOf(page(1L, profile("alice")))
        val plan = authorNavPlan(stack, playerSeq = 99L, showingPlayer = false, incoming = profile("alice"))
        assertEquals(AuthorNavPlan.PopTo(targetIndex = 0, closesPlayer = false), plan)
    }

    @Test
    fun `same author matched by user id reverts instead of pushing a duplicate`() {
        // 一个入口只带 handle、另一个只带 UUID：字段不同但是同一个人，不能压出两层作者页。
        val stack = listOf(page(1L, profile(username = "", userId = "uuid-1")))
        val plan = authorNavPlan(stack, playerSeq = 5L, showingPlayer = true, incoming = profile("alice", userId = "uuid-1"))
        assertEquals(AuthorNavPlan.PopTo(targetIndex = 0, closesPlayer = true), plan)
    }

    @Test
    fun `same author matched by local author key reverts`() {
        val stack = listOf(page(1L, profile(username = "", localAuthorKey = "local-key")))
        val plan = authorNavPlan(stack, playerSeq = 5L, showingPlayer = true, incoming = profile("alice", localAuthorKey = "local-key"))
        assertEquals(AuthorNavPlan.PopTo(targetIndex = 0, closesPlayer = true), plan)
    }

    @Test
    fun `different authors push a new page even with a player open`() {
        val stack = listOf(page(1L, profile("alice")))
        val plan = authorNavPlan(stack, playerSeq = 5L, showingPlayer = true, incoming = profile("bob"))
        assertEquals(AuthorNavPlan.PushOnTop, plan)
    }

    @Test
    fun `pop target is the last page of the same author`() {
        // 栈里同名作者只应存在一层（push 时已合并），这里锁住"取最后一个同作者页"的兜底语义。
        val stack = listOf(
            page(1L, profile("alice")),
            page(2L, profile("bob")),
            page(3L, profile("alice"))
        )
        val plan = authorNavPlan(stack, playerSeq = 5L, showingPlayer = true, incoming = profile("alice"))
        assertEquals(AuthorNavPlan.PopTo(targetIndex = 2, closesPlayer = true), plan)
    }

    @Test
    fun `pages above the author are popped together with a covering player`() {
        val stack = listOf(
            page(1L, profile("alice")),
            page(3L, IwaraOverlay.Comments("video", "v1", "video"))
        )
        val plan = authorNavPlan(stack, playerSeq = 5L, showingPlayer = true, incoming = profile("alice"))
        assertTrue(plan is AuthorNavPlan.PopTo)
        plan as AuthorNavPlan.PopTo
        assertEquals(0, plan.targetIndex)
        assertTrue(plan.closesPlayer)
    }

    @Test
    fun `action sheets above the author do not affect the plan`() {
        // 动作面板不占页面栈：回退时它随栈一起弹掉，但不影响"目标作者页 / 是否关播放页"。
        val stack = listOf(
            page(1L, profile("alice")),
            page(2L, IwaraOverlay.Favorites)
        )
        val plan = authorNavPlan(stack, playerSeq = 4L, showingPlayer = true, incoming = profile("alice"))
        assertEquals(AuthorNavPlan.PopTo(targetIndex = 0, closesPlayer = true), plan)
    }
}
