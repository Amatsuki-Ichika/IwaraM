package io.github.amatsukiichika.iwaram.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 本地库空态判据：**原始库条数 − 当前可见条数** 才是"被筛掉"的条数。
 *
 * 搜索词 / 作者 / 标签 / 文件夹 / 时间 / 分级筛选都发生在全局筛选器之前；
 * 只统计最后一道会让"搜索没有结果"被渲染成"还没有视频，导入本地文件夹开始管理"（连同导入按钮），
 * 用户会以为搜索坏了。这里钉住"任何一层筛掉都算"的语义。
 */
class VideoLibraryFilterCountTest {

    @Test
    fun `a query that hides the whole non-empty library counts every video as filtered out`() {
        // 搜索词命中 0 条：空态必须说"筛选后没有符合的内容"，而不是"库里还没有视频"。
        assertEquals(10, hiddenByFilterCount(rawCount = 10, visibleCount = 0))
    }

    @Test
    fun `partial filtering counts only the removed rows`() {
        assertEquals(3, hiddenByFilterCount(rawCount = 10, visibleCount = 7))
    }

    @Test
    fun `an empty raw library reports nothing filtered`() {
        // 原始库为空 ⇒ 仍走"导入文件夹"空态。
        assertEquals(0, hiddenByFilterCount(rawCount = 0, visibleCount = 0))
    }

    @Test
    fun `a visible list larger than the raw library never reports a negative count`() {
        assertEquals(0, hiddenByFilterCount(rawCount = 2, visibleCount = 5))
    }
}
