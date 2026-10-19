package io.github.amatsukiichika.iwaramanager.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 默认排序改「最新」时的一次性重置规则（纯函数）。
 *
 * 背景：旧默认是 `trending`（趋势），而 `trending` 只在手动切过排序时才写进 prefs ——
 * 也就是说 prefs 里出现 `trending` 时，"旧默认留下的"与"特意选的趋势"无法区分。
 * 只改默认值会导致一部分机器升级后仍是趋势。所以把所有机器统一重置为「最新」一次
 * （靠 [resolveVideoSort] 的 migrated 标记），之后一律按存储值走。这个文件锁的就是这两条语义。
 */
class VideoSortPreferenceTest {

    @Test
    fun `未重置过时一律给最新 - 旧默认与手选值无法区分`() {
        assertEquals("date", resolveVideoSort(stored = null, migrated = false))
        assertEquals("date", resolveVideoSort(stored = "trending", migrated = false))
        assertEquals("date", resolveVideoSort(stored = "date", migrated = false))
    }

    @Test
    fun `重置过之后尊重用户手选值`() {
        assertEquals("trending", resolveVideoSort(stored = "trending", migrated = true))
        assertEquals("date", resolveVideoSort(stored = "date", migrated = true))
        assertEquals("popularity", resolveVideoSort(stored = "popularity", migrated = true))
    }

    @Test
    fun `重置过之后空值回退到最新`() {
        assertEquals("date", resolveVideoSort(stored = null, migrated = true))
        assertEquals("date", resolveVideoSort(stored = "", migrated = true))
        assertEquals("date", resolveVideoSort(stored = "   ", migrated = true))
    }

    /** 默认常量必须是"最新"，别被顺手改回去（那正是这个 bug 的复现条件）。 */
    @Test
    fun `默认常量是最新`() {
        assertEquals("date", DEFAULT_VIDEO_SORT)
    }
}
