package io.github.amatsukiichika.iwaram.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线网格 Lazy `key` 唯一化的回归用例。
 *
 * `InfiniteGrid` 需要把 `itemKey` 作为 LazyGrid 的 `key` 传入，否则下拉刷新在头部插入新条目时
 * Compose 识别不出条目身份，整个可见网格会被当成"全变了"重建。但直接使用 `itemKey`
 * 有硬风险：`key` 重复会让 LazyLayout 直接抛
 * `IllegalArgumentException: Key "x" was already used`（整页崩），而调用点的写法
 * `it.id.orEmpty().ifEmpty { it.hashCode().toString() }` 在 id 为空且内容相同时必然撞车。
 * [uniqueLazyKeys] 提供确定性兜底，这个文件锁的就是它。
 */
class LazyItemKeysTest {

    private data class Row(val id: String?, val extra: Int)

    private fun keyOf(row: Row) = row.id.orEmpty().ifEmpty { row.hashCode().toString() }

    @Test
    fun `distinct keys are returned verbatim`() {
        val rows = listOf(Row("a", 1), Row("b", 2), Row("c", 3))
        assertEquals(listOf("a", "b", "c"), uniqueLazyKeys(rows, ::keyOf))
    }

    @Test
    fun `duplicate keys never crash - later ones get a numeric suffix`() {
        val rows = listOf(Row("dup", 1), Row("dup", 2), Row("dup", 3))
        val keys = uniqueLazyKeys(rows, ::keyOf)
        assertEquals(listOf("dup", "dup#1", "dup#2"), keys)
        assertEquals("key 必须全列表唯一，否则 LazyLayout 会崩", keys.size, keys.toSet().size)
    }

    @Test
    fun `identical rows with blank id - the exact case that used to collide`() {
        // id 都为空、内容相同 ⇒ keyOf 回退到内容哈希 ⇒ 三条完全一样的 key。
        val rows = listOf(Row("", 7), Row("", 7), Row("", 7))
        val keys = uniqueLazyKeys(rows, ::keyOf)
        assertEquals(3, keys.toSet().size)
        assertTrue(keys.all { it.isNotBlank() })
    }

    @Test
    fun `blank keys fall back to a stable placeholder`() {
        val rows = listOf(Row("   ", 1), Row("", 2))
        val keys = uniqueLazyKeys(rows) { it.id.orEmpty() }
        assertEquals(listOf("item", "item#1"), keys)
    }

    @Test
    fun `first occurrence keeps the plain key so identity stays stable on prepend`() {
        // 下拉刷新会在头部插入新条目；原有条目的 key 必须保持不变（否则整屏重建）。
        val before = uniqueLazyKeys(listOf(Row("a", 0), Row("b", 0)), ::keyOf)
        val after = uniqueLazyKeys(listOf(Row("new", 0), Row("a", 0), Row("b", 0)), ::keyOf)
        assertEquals(listOf("a", "b"), before)
        assertEquals(listOf("new", "a", "b"), after)
    }

    @Test
    fun `empty list is handled`() {
        assertEquals(emptyList<String>(), uniqueLazyKeys(emptyList<Row>(), ::keyOf))
    }
}
