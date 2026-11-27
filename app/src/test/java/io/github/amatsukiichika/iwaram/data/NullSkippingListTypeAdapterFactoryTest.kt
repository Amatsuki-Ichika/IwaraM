package io.github.amatsukiichika.iwaram.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 坏响应里的 null 数组元素必须在反序列化时丢掉（Medium-56 回归）。
 *
 * 默认 Gson 会把 JSON 的 `null` 元素塞进 Kotlin 的 `List<T>`，消费端一解引用就 NPE；
 * 这里的工厂只处理 List，Set 等集合保持 Gson 原语义。
 */
class NullSkippingListTypeAdapterFactoryTest {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapterFactory(NullSkippingListTypeAdapterFactory)
        .create()

    @Test
    fun nullElementsInPageResultsAreDropped() {
        val json = """{"results":[{"id":"v1"},null,{"id":"v2"}],"count":3}"""
        val page: IwaraPage<IwaraVideoItem> =
            gson.fromJson(json, object : TypeToken<IwaraPage<IwaraVideoItem>>() {}.type)
        assertEquals(listOf("v1", "v2"), page.results?.map { it.id })
        assertEquals(3, page.count)
    }

    @Test
    fun nullElementsInVideoTagsAreDropped() {
        val json = """{"id":"v1","tags":[null,{"id":"tag-a"},null]}"""
        val video = gson.fromJson(json, IwaraVideoItem::class.java)
        assertEquals(listOf("tag-a"), video.tags?.map { it.id })
    }

    @Test
    fun nullJsonForListFieldStillMeansNull() {
        val video = gson.fromJson("""{"id":"v1","tags":null}""", IwaraVideoItem::class.java)
        assertNull(video.tags)
    }

    @Test
    fun setFieldsKeepDefaultGsonSemantics() {
        val parsed = gson.fromJson("""{"values":["a","b","a"]}""", SetHolder::class.java)
        assertEquals(setOf("a", "b"), parsed.values)
    }

    /** 仅用于验证 Set 不受工厂影响（Set 不是 List 字段）。 */
    internal class SetHolder(val values: Set<String>? = null)
}
