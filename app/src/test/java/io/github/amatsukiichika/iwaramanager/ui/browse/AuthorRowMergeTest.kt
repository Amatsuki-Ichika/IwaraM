package io.github.amatsukiichika.iwaramanager.ui.browse

import io.github.amatsukiichika.iwaramanager.data.IwaraAccount
import io.github.amatsukiichika.iwaramanager.data.IwaraVideoItem
import io.github.amatsukiichika.iwaramanager.data.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作者页「视频」栏「全部」模式行序的回归守卫。
 *
 * 症状是"上面全是本地的、下面全是在线的，没有穿插成在线列表的样子"：本地记录的
 * `createdAt`（发布时间）在下载 / 扫描写入时可能为 null，若回落到 `addedAt`（导入时间）
 * 就会把未匹配的本地全部排到所有在线条目前面。现行规则是构造式的（主干恒等于在线顺序），
 * 这里逐条钉死。
 */
class AuthorRowMergeTest {

    private fun online(id: String, title: String, created: String?, user: String = "author") = IwaraVideoItem(
        id = id,
        title = title,
        user = IwaraAccount(id = "u1", name = user, username = user),
        createdAt = created
    )

    private fun local(
        uri: String,
        iwaraId: String? = null,
        title: String = uri,
        author: String = "author",
        createdAt: Long? = null,
        addedAt: Long = 0L
    ) = VideoEntity(
        localUri = uri,
        fileName = "$uri.mp4",
        iwaraId = iwaraId,
        title = title,
        author = author,
        authorId = author,
        createdAt = createdAt,
        viewCount = null,
        likeCount = null,
        commentCount = null,
        rating = null,
        thumbnailUrl = null,
        localThumbnailPath = null,
        relPath = null,
        tagsCsv = null,
        metaFetched = createdAt != null,
        addedAt = addedAt
    )

    private fun keys(rows: List<AuthorVideoRow>) = rows.map {
        when (it) {
            is AuthorVideoRow.LocalV -> "L:" + it.video.localUri
            is AuthorVideoRow.OnlineV -> "O:" + it.item.id
        }
    }

    // ---- 1. 命中的在线卡被本地版本就地顶替 ----

    @Test
    fun `matched local replaces the online card in place`() {
        val rows = AuthorRows.mergeAll(
            online = listOf(online("a", "A", D3), online("b", "B", D2), online("c", "C", D1)),
            local = listOf(local("fileB", iwaraId = "b", createdAt = 2L)),
            chronological = true
        )
        assertEquals(listOf("O:a", "L:fileB", "O:c"), keys(rows))
    }

    // ---- 2. iwaraId 缺失时用「标题 + 作者」兜底匹配 ----

    @Test
    fun `title plus author matches when the local record has no iwara id`() {
        val rows = AuthorRows.mergeAll(
            online = listOf(online("a", "Hello World", null), online("b", "Second", null)),
            local = listOf(local("f1", iwaraId = null, title = "hello   world", author = "Author")),
            chronological = true
        )
        assertEquals(listOf("L:f1", "O:b"), keys(rows))
    }

    @Test
    fun `blank title never matches anything`() {
        val rows = AuthorRows.mergeAll(
            online = listOf(online("a", "", null)),
            local = listOf(local("f1", title = "")),
            chronological = true
        )
        // 空标题不能当匹配键：在线那条仍是"在线"，本地那条按未匹配处理（排到主干之后）。
        assertEquals(listOf("O:a", "L:f1"), keys(rows))
        assertTrue(rows.first() is AuthorVideoRow.OnlineV)
    }

    // ---- 3. 未匹配本地：有发布日期 → 按时间插进主干 ----

    @Test
    fun `unmatched local with a publish date is inserted by date`() {
        val rows = AuthorRows.mergeAll(
            online = listOf(online("new", "N", "2024-06-01T00:00:00Z"), online("old", "O", "2024-01-01T00:00:00Z")),
            local = listOf(local("mid", createdAt = iso("2024-03-01T00:00:00Z"))),
            chronological = true
        )
        assertEquals(listOf("O:new", "L:mid", "O:old"), keys(rows))
    }

    // ---- 4. 未匹配本地：时间未知 → 排到主干之后 ----

    @Test
    fun `unmatched local without a publish date goes after the online trunk`() {
        val rows = AuthorRows.mergeAll(
            online = listOf(online("a", "A", "2024-06-01T00:00:00Z"), online("b", "B", "2024-05-01T00:00:00Z")),
            local = listOf(
                local("justDownloaded", createdAt = null, addedAt = 9_000L),
                local("olderImport", createdAt = null, addedAt = 1_000L)
            ),
            chronological = true
        )
        // 关键：本地不再因为"入库时间=刚刚"而跑到最前面。
        assertEquals(listOf("O:a", "O:b", "L:justDownloaded", "L:olderImport"), keys(rows))
        assertTrue(rows.first() is AuthorVideoRow.OnlineV)
    }

    // ---- 5. 在线为空 / 无法排序时，未匹配的整块排在主干之后 ----

    @Test
    fun `empty online list yields pure local rows`() {
        val rows = AuthorRows.mergeAll(
            online = emptyList(),
            local = listOf(local("x", createdAt = 5L), local("y", createdAt = null, addedAt = 3L)),
            chronological = true
        )
        assertEquals(listOf("L:x", "L:y"), keys(rows))
    }

    @Test
    fun `non chronological sort appends every unmatched local after the trunk`() {
        val rows = AuthorRows.mergeAll(
            online = listOf(online("a", "A", "2024-06-01T00:00:00Z"), online("b", "B", "2024-01-01T00:00:00Z")),
            local = listOf(
                local("dated", createdAt = iso("2024-03-01T00:00:00Z")),
                local("undated", createdAt = null, addedAt = 1L)
            ),
            chronological = false
        )
        assertEquals(listOf("O:a", "O:b", "L:dated", "L:undated"), keys(rows))
    }

    @Test
    fun `online dates that cannot be parsed never push locals to the top`() {
        val rows = AuthorRows.mergeAll(
            online = listOf(online("a", "A", null), online("b", "B", "不是时间")),
            local = listOf(local("dated", createdAt = 9_999_999L)),
            chronological = true
        )
        assertEquals(listOf("O:a", "O:b", "L:dated"), keys(rows))
    }

    // ---- 6. 一条本地只被消费一次 ----

    @Test
    fun `one local record never fills two online slots`() {
        val shared = local("dup", iwaraId = "same")
        val rows = AuthorRows.mergeAll(
            online = listOf(online("same", "X", null), online("same", "X", null)),
            local = listOf(shared),
            chronological = true
        )
        assertEquals(1, keys(rows).count { it.startsWith("L:") })
    }

    private fun iso(raw: String): Long = io.github.amatsukiichika.iwaramanager.data.parseIwaraDate(raw)!!

    private companion object {
        const val D1 = "2024-01-01T00:00:00Z"
        const val D2 = "2024-02-01T00:00:00Z"
        const val D3 = "2024-03-01T00:00:00Z"
    }
}
