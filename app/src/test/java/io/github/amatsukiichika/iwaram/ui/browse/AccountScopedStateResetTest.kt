package io.github.amatsukiichika.iwaram.ui.browse

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.amatsukiichika.iwaram.IwaraApp
import io.github.amatsukiichika.iwaram.data.IwaraImageItem
import io.github.amatsukiichika.iwaram.data.IwaraPlaylist
import io.github.amatsukiichika.iwaram.data.IwaraSession
import io.github.amatsukiichika.iwaram.data.IwaraVideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 账号级状态必须在三种换账号路径上清空（Robolectric：ViewModel 需要 Application 上下文）。
 *
 * 这些槽位装的是"上一个账号能看到什么"。曾经 logout() 只清了关注/点赞，播放列表与评论
 * 原样留着，而 `PlaylistsOverlay` 又只在 `loaded == false` 时才重新拉取 —— B 登录后直接看到
 * A 的播放列表。收藏分页器同理：只在 items 为空时强制刷新，于是 B 的下一页被追加到 A 的列表后面。
 */
@RunWith(RobolectricTestRunner::class)
// 单元测试变体的 manifest 里没有 android:name，Robolectric 默认只会造一个裸 Application；
// 这里显式指定，否则测试拿到的是 android.app.Application，转不成 IwaraApp。
@Config(sdk = [34], application = IwaraApp::class)
class AccountScopedStateResetTest {

    private fun newViewModel(): IwaraClientViewModel =
        IwaraClientViewModel(ApplicationProvider.getApplicationContext<IwaraApp>())

    private fun IwaraClientViewModel.seedAccountScopedState() {
        _playlists.value = IwaraClientViewModel.PlaylistsState(
            items = listOf(IwaraPlaylist(id = "pl-1", title = "A 的播放列表")),
            loaded = true
        )
        commentsByTarget[commentsKey("video", "video-1")] = IwaraClientViewModel.CommentsState(
            type = "video",
            targetId = "video-1",
            title = "A 正在看的作品",
            items = emptyList()
        )
        followStates["author-1"] = true
        likeStates["video:video-1"] = true
        // 账号级分页器：直接向内部 FeedPager 种数据（公开的加载函数要求登录且走网络，单测不可用）。
        // load 在 viewModelScope 上执行，Robolectric 主线程 idle 后数据才会落进状态。
        subscribedPager.load { listOf(IwaraVideoItem(id = "sub-1")) }
        favoriteVideosPager.load { listOf(IwaraVideoItem(id = "fav-1")) }
        favoriteImagesPager.load { listOf(IwaraImageItem(id = "fav-img-1")) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `logout moves the session epoch so in-flight account results are dropped`() {
        val vm = newViewModel()
        val before = vm.sessionEpoch.get()

        vm.logout()

        val after = vm.sessionEpoch.get()
        assertTrue("登出必须推进账号代际", after != before)
        assertTrue(accountResultIsStale(before, after))
        assertFalse(accountResultIsStale(after, after))
    }

    @Test
    fun `a session expiring moves the session epoch before the reset coroutine runs`() {
        val vm = newViewModel()
        val before = vm.sessionEpoch.get()

        IwaraSession.listener?.onSessionExpired()

        assertTrue("过期回调必须同步推进代际", vm.sessionEpoch.get() != before)
    }

    @Test
    fun `comment state is keyed per target so two panels cannot overwrite each other`() {
        val vm = newViewModel()
        vm.commentsByTarget[commentsKey("video", "a")] = IwaraClientViewModel.CommentsState(
            type = "video", targetId = "a", title = "A"
        )
        vm.commentsByTarget[commentsKey("image", "b")] = IwaraClientViewModel.CommentsState(
            type = "image", targetId = "b", title = "B"
        )

        assertEquals("a", vm.commentsState("video", "a").targetId)
        assertEquals("b", vm.commentsState("image", "b").targetId)
        val missing = vm.commentsState("video", "missing")
        assertEquals("缺少条目时返回带目标标识的空态", "missing", missing.targetId)
        assertTrue("空态不应有内容", missing.items.isEmpty())
        assertFalse(
            "同一 id 的不同目标类型也必须是不同槽位",
            commentsKey("video", "a") == commentsKey("image", "a")
        )
    }

    @Test
    fun `logout clears the account scoped state so the next account cannot inherit it`() {
        val vm = newViewModel()
        vm.seedAccountScopedState()
        // 先确认种子确实落进分页器：否则下面的"清空"断言会空转通过。
        assertTrue("订阅流的分页器必须先有数据", vm.subscribed.value.items.isNotEmpty())
        assertTrue("收藏视频的分页器必须先有数据", vm.favoriteVideos.value.items.isNotEmpty())
        assertTrue("收藏图片的分页器必须先有数据", vm.favoriteImages.value.items.isNotEmpty())

        vm.logout()

        assertTrue("播放列表必须清空", vm.playlists.value.items.isEmpty())
        assertFalse("loaded 必须复位，否则覆盖层不会重新拉取", vm.playlists.value.loaded)
        assertTrue("关注快照必须清空", vm.followStates.isEmpty())
        assertTrue("点赞快照必须清空", vm.likeStates.isEmpty())
        assertTrue("评论面板必须回到初始态", vm.commentsByTarget.isEmpty())
        // 三个账号级分页器必须回到初始态：漏掉任一个，B 登录后都会接着看到 A 的列表。
        assertEquals("订阅流必须回到初始态", FeedState<IwaraVideoItem>(), vm.subscribed.value)
        assertEquals("收藏视频必须回到初始态", FeedState<IwaraVideoItem>(), vm.favoriteVideos.value)
        assertEquals("收藏图片必须回到初始态", FeedState<IwaraImageItem>(), vm.favoriteImages.value)
    }

    @Test
    fun `a session expiring clears the same slots as an explicit logout`() {
        val vm = newViewModel()
        vm.seedAccountScopedState()
        assertTrue("订阅流的分页器必须先有数据", vm.subscribed.value.items.isNotEmpty())
        assertTrue("收藏视频的分页器必须先有数据", vm.favoriteVideos.value.items.isNotEmpty())
        assertTrue("收藏图片的分页器必须先有数据", vm.favoriteImages.value.items.isNotEmpty())

        // 真实路径：IwaraSession 在续期失败后清凭证并回调 VM.init 注册的 listener。
        // 回调可能来自 OkHttp 线程，VM 用 viewModelScope 回到主线程再清。
        IwaraSession.listener?.onSessionExpired()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("播放列表必须清空", vm.playlists.value.items.isEmpty())
        assertFalse("loaded 必须复位", vm.playlists.value.loaded)
        assertTrue("评论面板必须回到初始态", vm.commentsByTarget.isEmpty())
        assertTrue("关注快照必须清空", vm.followStates.isEmpty())
        assertEquals("订阅流必须回到初始态", FeedState<IwaraVideoItem>(), vm.subscribed.value)
        assertEquals("收藏视频必须回到初始态", FeedState<IwaraVideoItem>(), vm.favoriteVideos.value)
        assertEquals("收藏图片必须回到初始态", FeedState<IwaraImageItem>(), vm.favoriteImages.value)
    }
}
