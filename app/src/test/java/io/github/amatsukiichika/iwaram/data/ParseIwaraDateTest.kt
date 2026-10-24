package io.github.amatsukiichika.iwaram.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** parseIwaraDate 的纯解析行为：Instant / 带偏移两种上游格式，以及失败路径。 */
class ParseIwaraDateTest {

    @Test
    fun `parses UTC instant strings`() {
        // 2026-01-01T00:00:00Z 的固定毫秒值，避免用被测实现自证
        assertEquals(1767225600000L, parseIwaraDate("2026-01-01T00:00:00Z"))
    }

    @Test
    fun `parses offset date time without Z suffix`() {
        // +08:00 的 08:00 与 UTC 的 00:00 是同一时刻
        assertEquals(1767225600000L, parseIwaraDate("2026-01-01T08:00:00+08:00"))
    }

    @Test
    fun `keeps millisecond precision`() {
        assertEquals(
            Instant.parse("2026-05-06T07:08:09.123Z").toEpochMilli(),
            parseIwaraDate("2026-05-06T07:08:09.123Z")
        )
    }

    @Test
    fun `returns null for null or blank input`() {
        assertNull(parseIwaraDate(null))
        assertNull(parseIwaraDate(""))
        assertNull(parseIwaraDate("   "))
    }

    @Test
    fun `returns null for garbage strings`() {
        assertNull(parseIwaraDate("not-a-date"))
        assertNull(parseIwaraDate("2026-13-40T99:99:99Z"))
    }
}
