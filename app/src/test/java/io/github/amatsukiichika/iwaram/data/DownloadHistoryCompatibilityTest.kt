package io.github.amatsukiichika.iwaram.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 下载历史的向后兼容契约：旧版本写进 prefs 的 JSON 里没有完整性校验字段，
 * 升级后必须仍能反序列化，新字段默认 null（不能因为字段缺失整条历史读不出来）。
 */
class DownloadHistoryCompatibilityTest {

    private val gson = Gson()
    private val listType = object : TypeToken<List<DownloadHistoryEntry>>() {}.type

    @Test
    fun `old history json without integrity fields still parses`() {
        // 旧版本写出的字段集合：没有 totalBytes / verifiedAt / verifyNote。
        val oldJson = """
            [{"videoId":"abc123","title":"标题","quality":"Source","time":1700000000000,
              "success":true,"message":null,"localUri":"content://doc/video.mp4"}]
        """.trimIndent()
        val entries = gson.fromJson<List<DownloadHistoryEntry>>(oldJson, listType)
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("abc123", entry.videoId)
        assertEquals("content://doc/video.mp4", entry.localUri)
        assertNull(entry.totalBytes)
        assertNull(entry.verifiedAt)
        assertNull(entry.verifyNote)
        // 旧记录无法证明文件由本应用写入，默认不参与完整性自动修复。
        assertFalse(entry.downloadedByApp)
    }

    @Test
    fun `integrity fields survive a json round trip`() {
        val entry = DownloadHistoryEntry(
            videoId = "abc123",
            title = "标题",
            quality = "Source",
            time = 1700000000000,
            success = true,
            localUri = "content://doc/video.mp4",
            totalBytes = 1048576L,
            downloadedByApp = true,
            verifiedAt = 1700000005000L,
            verifyNote = "ok"
        )
        val decoded = gson.fromJson<List<DownloadHistoryEntry>>(
            gson.toJson(listOf(entry)),
            listType
        )[0]
        assertEquals(entry, decoded)
    }
}
