package io.github.amatsukiichika.iwaram.ui.browse

import io.github.amatsukiichika.iwaram.data.IwaraBrowseService
import io.github.amatsukiichika.iwaram.data.IwaraImageItem
import io.github.amatsukiichika.iwaram.data.IwaraVideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 作者二级页端点与分级文案的契约用例。
 *
 * 钉住三类"改坏了既不报错也不崩、只会悄悄少数据 / 显示错 / 整页 404"的事：
 * 1. **作品主路径**必须是 `GET /videos?user=<uuid>`，`user/{id}/content/{type}` 只作**回退**
 *    （回退被当唯一路径时作者页视频分段会 404，`errors.notFound`）；
 * 2. **帖子主路径**必须是 `GET /posts?user=<uuid>`，`content/post` 降级为回退；
 *    图片走 `images?user=`；粉丝端点 `GET user/{id}/followers`；
 * 3. 分级文案的**单一来源** [RATING_OPTIONS] 与接口原始值 general / ecchi 的对应关系。
 *
 * 注意：`videos` / `images` / `postsByUser` 的参数都是**按位置**传的，
 * 所以下面用 [queryNames] 断言完整的 `@Query` 顺序 —— 换顺序 = 静默传错参数。
 */
class IwaraAuthorPageTest {

    private fun method(name: String) =
        IwaraBrowseService::class.java.methods.first { it.name == name }

    /** 按声明顺序取一个 Retrofit 方法上的 @Query 名（suspend 末尾的 Continuation 无注解会被过滤）。 */
    private fun queryNames(name: String): List<String> =
        method(name).parameterAnnotations
            .map { arr -> arr.filterIsInstance<Query>().firstOrNull()?.value }
            .filterNotNull()

    private fun pathValue(name: String): String? =
        method(name).annotations.filterIsInstance<GET>().firstOrNull()?.value

    @Test
    fun `author videos use the verified user query path`() {
        assertEquals("videos", pathValue("videos"))
        // VM 调用：videos(page, PAGE_LIMIT, sort, rating, subscribed, user) —— 顺序不能变。
        assertEquals(
            listOf("page", "limit", "sort", "rating", "subscribed", "user", "tags"),
            queryNames("videos")
        )
        assertTrue("videos 必须能按作者过滤", queryNames("videos").contains("user"))
    }

    @Test
    fun `author posts prefer the posts user query`() {
        assertEquals("posts", pathValue("postsByUser"))
        assertEquals(listOf("user", "page", "limit", "sort", "rating"), queryNames("postsByUser"))
    }

    @Test
    fun `user content path survives as the fallback`() {
        assertEquals("user/{id}/content/{type}", pathValue("userContent"))
        assertEquals("user/{id}/content/{type}", pathValue("userContentPosts"))
        assertEquals(listOf("page", "limit", "sort"), queryNames("userContent"))
    }

    @Test
    fun `followers endpoint is user id scoped`() {
        assertEquals("user/{id}/followers", pathValue("followers"))
        assertEquals(listOf("page", "limit"), queryNames("followers"))
    }

    @Test
    fun `following and followers share the same shape`() {
        assertEquals("user/{id}/following", pathValue("following"))
        assertEquals("user/{id}/followers", pathValue("followers"))
        assertEquals(queryNames("following"), queryNames("followers"))
    }

    @Test
    fun `author images filter by the user query`() {
        assertTrue("images 需要 @Query(\"user\") 才能按作者过滤", queryNames("images").contains("user"))
    }

    @Test
    fun `rating option labels match the interface raw values`() {
        val byValue = RATING_OPTIONS.associate { it.value to it.label }
        assertEquals("全部", byValue["all"])
        assertEquals("一般", byValue["general"])
        assertEquals("R18", byValue["ecchi"])
    }

    @Test
    fun `profile overlay payload carries the uuid fallback`() {
        val p = IwaraOverlay.Profile("alice", "uuid-1", "Alice")
        assertEquals("alice", p.username)
        assertEquals("uuid-1", p.userId)
        assertEquals("Alice", p.displayName)
        assertNull(p.localAuthorKey)
        assertNull(p.localAuthorName)

        // handle 缺失时允许 username 为空 —— 作者页靠 userId 仍能拉到作品/关注/粉丝。
        val byId = IwaraOverlay.Profile("", userId = "uuid-2")
        assertTrue(byId.username.isEmpty())
        assertEquals("uuid-2", byId.userId)
        assertNull(byId.displayName)
    }

    @Test
    fun `local author payload carries the local grouping key`() {
        // 本地库点作者名 / 「作者」分类列表进来：username 为空、带本地分组键。
        val local = IwaraOverlay.Profile(
            username = "",
            userId = "uuid-local",
            displayName = "某作者",
            localAuthorKey = "uuid-local",
            localAuthorName = "某作者"
        )
        assertEquals("uuid-local", local.localAuthorKey)
        assertEquals("某作者", local.localAuthorName)

        // 「未指定」桶：没有 UUID，连 userId 都是 null（作者页据此完全不发网络请求）。
        val unknown = IwaraOverlay.Profile(
            username = "",
            userId = null,
            displayName = "未指定",
            localAuthorKey = "unknown-key",
            localAuthorName = "未指定"
        )
        assertNull(unknown.userId)
        assertEquals("unknown-key", unknown.localAuthorKey)
    }

    @Test
    fun `relations overlay payload keeps the user id and kind`() {
        val r = IwaraOverlay.Relations("uuid-1", RelationKind.FOLLOWING)
        assertEquals("uuid-1", r.userId)
        assertEquals(RelationKind.FOLLOWING, r.kind)
        assertEquals("关注", RelationKind.FOLLOWING.label)
        assertEquals("粉丝", RelationKind.FOLLOWERS.label)
    }

    /**
     * 覆盖层栈按**自增序号**区分同一页 —— 两个结构相等的 `Profile`
     * 必须是两个不同的 [OverlayEntry]（否则组合 key 撞车 → 层级错乱 / 页面消失）。
     */
    @Test
    fun `overlay entries with identical payloads are still distinct stacks`() {
        val payload = IwaraOverlay.Profile("alice", "uuid-1")
        val a = OverlayEntry(1L, payload)
        val b = OverlayEntry(2L, payload)
        assertEquals("负载结构相等（仅靠 seq 区分）", a.overlay, b.overlay)
        assertTrue("序号必须不同，否则组合 key 撞车", a.seq != b.seq)
        assertTrue("条目本身必须不相等 —— 退场集合按 seq 比对才不会误删", a != b)
    }

    @Test
    fun `overlay entry flags action sheets so they stay out of the page stack`() {
        assertTrue(OverlayEntry(1L, IwaraOverlay.VideoActions(IwaraVideoItem())).isActionSheet)
        assertTrue(OverlayEntry(2L, IwaraOverlay.ImageActions(IwaraImageItem())).isActionSheet)
        assertTrue(!OverlayEntry(3L, IwaraOverlay.Profile("a")).isActionSheet)
    }
}
