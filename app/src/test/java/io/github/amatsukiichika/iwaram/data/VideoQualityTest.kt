package io.github.amatsukiichika.iwaram.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 从文件名认档位 —— 多源共存后，这一层是"哪份文件更高画质"的唯一依据。
 *
 * 两个必须守住的边界：**只认括号里的档位**（标题里出现 `1080p60fps` 时不能误判），
 * 以及**认不出时返回 null 而不是猜一个**（猜高了会导致默认播放错的源）。
 */
class VideoQualityTest {

    @Test
    fun `our own download naming is parsed`() {
        assertEquals("Source", qualityLabelOfFileName("[IeGtwtMrjKeEUa] Cynical Night Plan (Source).mp4"))
        assertEquals("1080p", qualityLabelOfFileName("[abc] LADY CRAZY (1080p).mp4"))
        assertEquals("360p", qualityLabelOfFileName("[abc] x (360p).mp4"))
    }

    @Test
    fun `third party bracket naming is parsed too`() {
        assertEquals("Source", qualityLabelOfFileName("Iwara - Title [IeGtwtMrjKeEUa] [Source].mp4"))
        assertEquals("720p", qualityLabelOfFileName("Iwara - Title [IeGtwtMrjKeEUa][720p].mp4"))
    }

    @Test
    fun `the last bracket wins because the suffix is what the downloader appended`() {
        assertEquals(
            "1080p",
            qualityLabelOfFileName("[abc] Title (Source) (1080p).mp4")
        )
    }

    @Test
    fun `a quality looking word inside the title is not a quality`() {
        assertNull(qualityLabelOfFileName("Iwara - title [1080p60fps].mp4"))
        assertNull(qualityLabelOfFileName("hello 1080p world.mp4"))
        assertNull(qualityLabelOfFileName(null))
    }

    @Test
    fun `rank orders source above numbers above preview`() {
        val source = qualityPlayRank("Source")!!
        val f1080 = qualityPlayRank("1080p")!!
        val f360 = qualityPlayRank("360p")!!
        val preview = qualityPlayRank("Preview")!!
        assertTrue(source > f1080)
        assertTrue(f1080 > f360)
        assertTrue(f360 > preview)
    }

    @Test
    fun `an unknown label has no rank so callers can park it last`() {
        assertNull(qualityPlayRank(null))
        assertNull(qualityPlayRank(""))
        assertNull(qualityPlayRank("本地"))
    }
}
