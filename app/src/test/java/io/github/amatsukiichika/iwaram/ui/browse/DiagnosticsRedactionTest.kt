package io.github.amatsukiichika.iwaram.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断日志脱敏（[redactDiagUrl]）用例。
 *
 * 诊断日志会随「一键复制」导出给他人，搜索词 / tag / 作者标识属于用户数据；
 * 脱敏后仍要保留定位请求所需的端点与分页参数。
 */
class DiagnosticsRedactionTest {

    @Test
    fun `search query never survives into the diagnostics log`() {
        val redacted = redactDiagUrl(
            "search?query=privateSearchTerm&type=videos&page=2&limit=32&sort=date&rating=all"
        )

        assertFalse("搜索词必须被抹掉", redacted.contains("privateSearchTerm"))
        assertTrue("端点必须保留", redacted.contains("search?"))
        assertTrue("type 必须保留", redacted.contains("type=videos"))
        assertTrue("页码必须保留", redacted.contains("page=2"))
        assertTrue("分页大小必须保留", redacted.contains("limit=32"))
    }

    @Test
    fun `tag names never survive into the diagnostics log`() {
        val redacted = redactDiagUrl(
            "videos?page=0&limit=32&sort=date&rating=all&tags=secretTagA,secretTagB"
        )

        assertFalse("tag 原文必须被抹掉", redacted.contains("secretTagA"))
        assertFalse("tag 原文必须被抹掉", redacted.contains("secretTagB"))
        assertTrue("页码必须保留", redacted.contains("page=0"))
    }

    @Test
    fun `author identifiers never survive into the diagnostics log`() {
        val redacted = redactDiagUrl(
            "videos?page=0&limit=32&user=author-uuid&handle=somebody&uid=another-id"
        )

        assertFalse("作者 UUID 必须被抹掉", redacted.contains("author-uuid"))
        assertFalse("handle 必须被抹掉", redacted.contains("somebody"))
        assertFalse("uid 必须被抹掉", redacted.contains("another-id"))
    }

    @Test
    fun `feed urls without personal values are left untouched`() {
        val url = "videos?page=3&limit=32&sort=date&rating=ecchi&subscribed=true"

        assertEquals(url, redactDiagUrl(url))
    }

    @Test
    fun `call-site placeholders that only keep a count are preserved`() {
        val url = "videos?page=0&limit=32&tags=<已隐去:2个>"

        assertEquals(url, redactDiagUrl(url))
    }
}
