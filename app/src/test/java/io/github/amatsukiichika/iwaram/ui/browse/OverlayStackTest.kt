package io.github.amatsukiichika.iwaram.ui.browse

import io.github.amatsukiichika.iwaram.data.IwaraVideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二级页"卡片化让位"的**唯一规则** —— 某层是不是"被盖住"，只由**真实栈序**决定
 * （它后面还有没有别的页面，动作面板不算）。
 *
 * 规则被破坏时顶层页会被当成底层让位（整体左移 15% + 压暗、右边露出黑垫）、
 * 退场集合互相误删 ⇒ "复位不完全 / 底页位置错位"。抽成纯函数是为了能直接单测。
 */
class OverlayStackTest {

    private fun page(id: Long) = OverlayEntry(id, IwaraOverlay.Profile("a$id"))
    private val sheet = OverlayEntry(99L, IwaraOverlay.VideoActions(IwaraVideoItem()))

    @Test
    fun `empty stack covers nothing`() {
        assertFalse(isCoveredAt(emptyList(), 0))
    }

    @Test
    fun `top page is never covered`() {
        val stack = listOf(page(1), page(2), page(3))
        assertFalse(isCoveredAt(stack, 2))
        assertFalse(isCoveredAt(stack, -1))
        assertFalse(isCoveredAt(stack, 3))
    }

    @Test
    fun `every page below the top is covered`() {
        val stack = listOf(page(1), page(2), page(3))
        assertTrue(isCoveredAt(stack, 0))
        assertTrue(isCoveredAt(stack, 1))
    }

    @Test
    fun `an action sheet on top does not cover the page below it`() {
        // 动作面板是独立窗口（ModalBottomSheet），不占页面栈 —— 它在栈顶时，
        // 顶下的那一页仍然是"顶层页"，不能被当成被盖住的去让位。
        val stack = listOf(page(1), sheet)
        assertFalse(isCoveredAt(stack, 0))
    }

    @Test
    fun `a page below another page is covered even with a sheet further up`() {
        val stack = listOf(page(1), page(2), sheet)
        assertTrue(isCoveredAt(stack, 0))
        assertFalse(isCoveredAt(stack, 1))
    }

    @Test
    fun `live page stack drops action sheets and keeps order`() {
        val stack = listOf(page(1), sheet, page(2))
        assertEquals(listOf(1L, 2L), livePageStack(stack).map { it.seq })
    }

    @Test
    fun `live page stack of only sheets is empty`() {
        assertTrue(livePageStack(listOf(sheet)).isEmpty())
    }

    @Test
    fun `index beyond the stack is never covered`() {
        val stack = listOf(page(1))
        assertFalse(isCoveredAt(stack, 1))
        assertFalse(isCoveredAt(stack, 5))
    }

    /**
     * 让位量的**动画目标**必须把跟手值算进去：松手那一帧 `gestureCover` 变 null，
     * 目标从"手指位置"落回"由栈序决定"，而动画当前值还停在 1 ⇒ 底页会先跳回"盖住"的位置再滑出。
     */
    @Test
    fun `cover target follows the finger while a gesture is in progress`() {
        assertEquals(0.4f, coverTargetFor(gestureCover = 0.4f, covered = true))
        assertEquals(0.4f, coverTargetFor(gestureCover = 0.4f, covered = false))
        assertEquals(1f, coverTargetFor(gestureCover = null, covered = true))
        assertEquals(0f, coverTargetFor(gestureCover = null, covered = false))
    }

    @Test
    fun `release frame keeps the gesture position as the animation start`() {
        // 松手那一帧：手势绑定已清空（gestureCover = null）且被拖的页已弹栈（下层变成顶层 = 露出）。
        // 目标 = 0，而动画当前值是最后贴上的手指位置 ⇒ 从手指位置接着滑出，不跳回 1。
        // 这里钉住"目标确实变成 0"，防止有人把它改回 covered 时的 1。
        assertEquals(0f, coverTargetFor(gestureCover = null, covered = false))
    }

    /**
     * 打开播放页要不要补入场滑入。
     *
     * 播放页被二级页盖住时**不销毁**（可见状态恒 true，只是让位左移 15%），而它的根 Box 透明
     * ⇒ 只要它带着让位位移冒到最上层，右边就露出一条缝、缝里正是下面那页。
     * 所以**每次真正把它提到台前都要补一次右滑入**；只有"已经在台前"不用补。
     */
    @Test
    fun `opening a player that is not alive always replays the entry slide`() {
        // 没开着 → 新打开，必须补（不能因为"顶层没有更大的层"就漏判成已在台前）。
        assertTrue(
            shouldReplayPlayerEntry(
                showingPlayer = false, playerSeq = 0L, overlayTopSeq = null,
                showingSettings = false, settingsSeq = 0L
            )
        )
    }

    @Test
    fun `player already in front does not replay`() {
        // 活着且就是最上层（序号 7，上面既没有页面也没有设置页）→ 只是刷新层序，不补。
        assertFalse(
            shouldReplayPlayerEntry(
                showingPlayer = true, playerSeq = 7L, overlayTopSeq = null,
                showingSettings = false, settingsSeq = 0L
            )
        )
        // 覆盖层栈里有页面，但序号比播放页小（被播放页盖着）→ 仍是"在台前"。
        assertFalse(
            shouldReplayPlayerEntry(
                showingPlayer = true, playerSeq = 7L, overlayTopSeq = 5L,
                showingSettings = false, settingsSeq = 0L
            )
        )
    }

    @Test
    fun `player covered by a page above it replays the entry slide`() {
        // 关键用例：作者页序号更大 = 正盖在播放页上面 ⇒ 再点视频时必须补滑入，把播放页从右侧带上来。
        assertTrue(
            shouldReplayPlayerEntry(
                showingPlayer = true, playerSeq = 5L, overlayTopSeq = 7L,
                showingSettings = false, settingsSeq = 0L
            )
        )
    }

    @Test
    fun `player covered by the settings page replays the entry slide`() {
        assertTrue(
            shouldReplayPlayerEntry(
                showingPlayer = true, playerSeq = 5L, overlayTopSeq = null,
                showingSettings = true, settingsSeq = 9L
            )
        )
        // 设置页关着 → 它的序号不作数（别拿一个已经关掉的层的旧序号去判"被盖住"）。
        assertFalse(
            shouldReplayPlayerEntry(
                showingPlayer = true, playerSeq = 5L, overlayTopSeq = null,
                showingSettings = false, settingsSeq = 9L
            )
        )
    }

    // ---- 绘制顺序必须包含"正在退场的层" ----
    // 按返回那一帧 `showingPlayer` 变 false，调用处 `takeIf { showingPlayer }` 变 null
    // ⇒ zIndex 掉到 0（低于覆盖层栈）⇒ 正在向右滑出的播放页被下面的作者页盖住，只剩黑缝里一条。

    @Test
    fun `a layer still counts as on screen while it is opening, open, or exiting`() {
        // 打开着
        assertTrue(layerStillOnScreen(shown = true, currentState = false, targetState = false))
        // 退场中：shown 已 false，但 currentState 还是可见态
        assertTrue(layerStillOnScreen(shown = false, currentState = true, targetState = false))
        // 刚变 false 的那一帧：SideEffect 还没落目标值，targetState 仍为 true
        assertTrue(layerStillOnScreen(shown = false, currentState = false, targetState = true))
        // 真正结束了：三个都为 false 才算离场
        assertFalse(layerStillOnScreen(shown = false, currentState = false, targetState = false))
    }

    @Test
    fun `an exiting player still ranks above the page underneath it`() {
        val authorSeq = 11L
        val playerSeq = 12L
        // 退场中：shown=false，但动画没播完 ⇒ 仍要参与绘制顺序。
        val seqs = drawOrderSeqs(
            overlayTopSeq = authorSeq,
            settingsSeq = 0L, settingsOnScreen = false,
            playerSeq = playerSeq, playerOnScreen = true
        )
        assertEquals(listOf(authorSeq, playerSeq), seqs)
        // 关键断言：退场中的播放页 zIndex 必须**仍然高于**下面那页。
        assertTrue(zIndexOf(playerSeq, seqs) > zIndexOf(authorSeq, seqs))
        assertEquals(2f, zIndexOf(playerSeq, seqs), 0f)
        assertEquals(1f, zIndexOf(authorSeq, seqs), 0f)
    }

    @Test
    fun `a player that finished exiting drops out of the draw order`() {
        val seqs = drawOrderSeqs(
            overlayTopSeq = 11L,
            settingsSeq = 0L, settingsOnScreen = false,
            playerSeq = 12L, playerOnScreen = false
        )
        assertEquals(listOf(11L), seqs)
    }

    @Test
    fun `zIndexOf gives the newest open layer the highest rank`() {
        val seqs = drawOrderSeqs(
            overlayTopSeq = 3L,
            settingsSeq = 5L, settingsOnScreen = true,
            playerSeq = 7L, playerOnScreen = true
        )
        assertEquals(listOf(3L, 5L, 7L), seqs)
        assertTrue(zIndexOf(7L, seqs) > zIndexOf(5L, seqs))
        assertTrue(zIndexOf(5L, seqs) > zIndexOf(3L, seqs))
        // 底色/压暗层/主内容用 null → 0。
        assertEquals(0f, zIndexOf(null, seqs), 0f)
    }

    @Test
    fun `settings page keeps its rank while it slides out`() {
        // 与播放页对称：设置页退场时同样不能被下面的页面盖住。
        val seqs = drawOrderSeqs(
            overlayTopSeq = 4L,
            settingsSeq = 9L, settingsOnScreen = true,
            playerSeq = 0L, playerOnScreen = false
        )
        assertTrue(zIndexOf(9L, seqs) > zIndexOf(4L, seqs))
    }

    /* ---------- 同一个作者页只允许在栈里存在一层 ---------- */

    @Test
    fun `same profile by userId`() {
        assertTrue(sameProfileKey(IwaraOverlay.Profile("a", userId = "u1"), IwaraOverlay.Profile("b", userId = "u1")))
        assertFalse(sameProfileKey(IwaraOverlay.Profile("a", userId = "u1"), IwaraOverlay.Profile("b", userId = "u2")))
    }

    @Test
    fun `same profile by handle and by local key`() {
        assertTrue(sameProfileKey(IwaraOverlay.Profile("Alice"), IwaraOverlay.Profile("alice")))
        assertTrue(
            sameProfileKey(
                IwaraOverlay.Profile("", localAuthorKey = "u9"),
                IwaraOverlay.Profile("x", localAuthorKey = "u9")
            )
        )
    }

    @Test
    fun `unknown profiles are never merged`() {
        // 两条都问不出身份（无 id、无 handle、无本地键）⇒ 不能错并成一层
        assertFalse(sameProfileKey(IwaraOverlay.Profile(""), IwaraOverlay.Profile("")))
    }

    @Test
    fun `author page opened from player reverts to the one already in stack`() {
        // 现场：作者页 A → 视频（播放页）→ 播放页里再点作者 A。
        // 从信息流推进来的那份带 localAuthorKey，从播放页推进来的不带。
        val inStack = IwaraOverlay.Profile(username = "", userId = "u1", localAuthorKey = "u1", localAuthorName = "A")
        val fromPlayer = IwaraOverlay.Profile(username = "", userId = "u1")
        val stack = listOf<IwaraOverlay>(
            IwaraOverlay.Profile("") // 更下面的别的作者页
        )
        assertEquals("-1：栈里没有 ⇒ 正常压栈", -1, profileRevertIndex(stack, fromPlayer))
        // 栈里已有同一作者（哪怕字段不完全相同）⇒ 回退到那一层，不再新压
        val stack2 = listOf<IwaraOverlay>(inStack)
        assertEquals(0, profileRevertIndex(stack2, fromPlayer))
        // 收藏页 + 作者页：回退到作者页那一层（下标 1），上面（没有别的）不动
        val stack3 = listOf<IwaraOverlay>(IwaraOverlay.Favorites, inStack, IwaraOverlay.TagVideos("t1"))
        assertEquals(1, profileRevertIndex(stack3, fromPlayer))
    }
}
