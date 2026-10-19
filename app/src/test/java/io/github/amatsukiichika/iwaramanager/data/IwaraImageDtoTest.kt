package io.github.amatsukiichika.iwaramanager.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「图片板块整片白板 / 帖子与图片的点赞评论数据缺失」的回归用例。
 *
 * 站点实际 JSON 里：列表项封面字段是 **`thumbnail`**（不是 `file`），图集多图字段是
 * **`files`**（不是 `fileList`）。DTO 只读 `file`/`fileList` 会让 [IwaraImageItem.thumbRef]
 * 恒为 null，卡片没有封面，整个板块看起来是空白。
 * 旧的 `file`/`fileList` 形态保留为兼容回退，这里一并回归。
 */
class IwaraImageDtoTest {

    private val gson = Gson()

    @Test
    fun `image list item reads thumbnail files and counters`() {
        val json = """
            {
              "id": "img-1",
              "slug": "my-album",
              "title": "album",
              "numImages": 3,
              "numLikes": 12,
              "numComments": 4,
              "numViews": 345,
              "thumbnail": {"id": "thumb-1", "name": "thumb.jpg"},
              "files": [
                {"id": "f-1", "name": "1.jpg"},
                {"id": "f-2", "name": "2.jpg"},
                {"id": "f-3", "name": "3.jpg"}
              ]
            }
        """.trimIndent()
        val item = gson.fromJson(json, IwaraImageItem::class.java)

        // 封面必须来自 thumbnail —— 这是整个板块显示空白的直接原因。
        assertEquals("thumb-1", item.thumbRef?.id)
        // 多图必须来自 files，否则查看器只有 1 页。
        assertEquals(3, item.fileRefs.size)
        assertEquals(listOf("f-1", "f-2", "f-3"), item.fileRefs.map { it.id })
        // 点赞 / 评论 / 浏览 / 图片数
        assertEquals(3, item.numImages)
        assertEquals(12, item.numLikes)
        assertEquals(4, item.numComments)
        assertEquals(345, item.numViews)
        assertEquals("my-album", item.slug)
    }

    @Test
    fun `legacy file and fileList shapes still resolve`() {
        val legacy = """
            {"id":"img-2","file":{"id":"legacy-cover","name":"c.jpg"},
             "fileList":[{"id":"l-1","name":"1.jpg"},{"id":"l-2","name":"2.jpg"}]}
        """.trimIndent()
        val item = gson.fromJson(legacy, IwaraImageItem::class.java)
        assertEquals("legacy-cover", item.thumbRef?.id)
        assertEquals(listOf("l-1", "l-2"), item.fileRefs.map { it.id })
    }

    @Test
    fun `single image album falls back to the thumbnail`() {
        val item = gson.fromJson(
            """{"id":"img-3","thumbnail":{"id":"only","name":"only.jpg"}}""",
            IwaraImageItem::class.java
        )
        assertEquals(1, item.fileRefs.size)
        assertEquals("only", item.fileRefs.first().id)
    }

    @Test
    fun `item without any image fields yields no refs instead of crashing`() {
        val item = gson.fromJson("""{"id":"img-4","title":"broken"}""", IwaraImageItem::class.java)
        assertNull(item.thumbRef)
        assertEquals(0, item.fileRefs.size)
    }

    @Test
    fun `post item reads numViews and slug`() {
        val post = gson.fromJson(
            """{"id":"p-1","slug":"hello","numLikes":7,"numComments":2,"numViews":88}""",
            IwaraPostItem::class.java
        )
        assertEquals("hello", post.slug)
        assertEquals(7, post.numLikes)
        assertEquals(2, post.numComments)
        assertEquals(88, post.numViews)
    }
}
