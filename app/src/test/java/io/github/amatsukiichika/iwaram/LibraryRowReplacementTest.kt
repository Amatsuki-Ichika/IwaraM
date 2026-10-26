package io.github.amatsukiichika.iwaram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载完成时，什么情况下应当**替换**一条已有的库记录而不是新增一条。
 *
 * 复现路径：下载 → 在文件管理器里删掉文件 → 再下一次同一支视频。旧行还指着已经消失的 URI，
 * 新文件拿到新 URI，按 URI 查不到旧行就再插一条 —— 库里同 iwaraId 两条，
 * 而 `byIwaraId(...)` 是 `LIMIT 1` 且无排序，命中哪条看运气，点播可能打到死 URI。
 */
class LibraryRowReplacementTest {

    private val oldUri = "content://tree/old.mp4"
    private val newUri = "content://tree/new.mp4"

    @Test
    fun `a row pointing at a vanished file for the same video is stale`() {
        assertTrue(
            isStaleRowForSameVideo(
                rowUri = oldUri, rowIwaraId = "video-1", newLocalUri = newUri, oldFileExists = false
            )
        )
    }

    @Test
    fun `a row whose file still exists is user data and must not be replaced`() {
        assertFalse(
            isStaleRowForSameVideo(
                rowUri = oldUri, rowIwaraId = "video-1", newLocalUri = newUri, oldFileExists = true
            )
        )
    }

    @Test
    fun `the row being written is not stale`() {
        assertFalse(
            isStaleRowForSameVideo(
                rowUri = newUri, rowIwaraId = "video-1", newLocalUri = newUri, oldFileExists = false
            )
        )
    }

    @Test
    fun `a row without an iwara id cannot be matched to this video`() {
        assertFalse(
            isStaleRowForSameVideo(
                rowUri = oldUri, rowIwaraId = null, newLocalUri = newUri, oldFileExists = false
            )
        )
        assertFalse(
            isStaleRowForSameVideo(
                rowUri = oldUri, rowIwaraId = "  ", newLocalUri = newUri, oldFileExists = false
            )
        )
    }
}
