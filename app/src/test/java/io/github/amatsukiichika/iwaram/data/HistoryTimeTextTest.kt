package io.github.amatsukiichika.iwaram.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * historyTimeText 的固定格式「M月d日 HH:mm」。
 *
 * 时区显式传 UTC：epoch 0 在东、西半球不是同一天，依赖机器时区会让断言在负时区必挂。
 */
class HistoryTimeTextTest {

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `formats single digit month and day without padding`() {
        assertEquals("1月5日 09:07", historyTimeText(utcMillis(2026, 1, 5, 9, 7), ZoneOffset.UTC))
    }

    @Test
    fun `formats double digit values`() {
        assertEquals(
            "10月19日 14:30",
            historyTimeText(utcMillis(2026, 10, 19, 14, 30), ZoneOffset.UTC)
        )
    }

    @Test
    fun `zero epoch renders as 1970-01-01 in the given zone`() {
        // 固定 UTC：epoch 0 = 1970-01-01T00:00Z，不再随运行机器的时区漂到 1969-12-31。
        assertEquals("1月1日 00:00", historyTimeText(0L, ZoneOffset.UTC))
    }

    @Test
    fun `negative offset still derives the calendar day from the given zone`() {
        // 同一瞬间在负时区是 1969-12-31：显式时区参数是行为的一部分，不是测试妥协。
        assertEquals(
            "12月31日 19:00",
            historyTimeText(0L, java.time.ZoneId.of("America/New_York"))
        )
    }
}
