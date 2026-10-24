package io.github.amatsukiichika.iwaramanager.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

/** historyTimeText 的固定格式「M月d日 HH:mm」（本地时区）。 */
class HistoryTimeTextTest {

    @Test
    fun `formats single digit month and day without padding`() {
        val ms = ZonedDateTime.of(2026, 1, 5, 9, 7, 0, 0, java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        assertEquals("1月5日 09:07", historyTimeText(ms))
    }

    @Test
    fun `formats double digit values`() {
        val ms = ZonedDateTime.of(2026, 10, 19, 14, 30, 0, 0, java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        assertEquals("10月19日 14:30", historyTimeText(ms))
    }

    @Test
    fun `zero epoch renders as local 1970-01-01`() {
        // 只验证可解析、格式含"日"，不硬编码时区相关的小时
        val text = historyTimeText(0L)
        assertEquals(true, text.matches(Regex("""1月1日 \d{2}:\d{2}""")))
    }
}
