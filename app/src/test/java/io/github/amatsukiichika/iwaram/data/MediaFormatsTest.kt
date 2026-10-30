package io.github.amatsukiichika.iwaram.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地媒体格式白名单：扩展名判据与 MIME 映射。
 *
 * 扫描曾只认 5 种视频容器，音频与 TS/FLV/AVI 等容器即使下载到本地也不会进库；
 * 这里把"哪些扩展名算支持"钉死在测试里，避免以后新增格式只改注释、不改判据。
 */
class MediaFormatsTest {

    @Test
    fun `video containers are supported`() {
        val names = listOf(
            "a.mp4", "a.m4v", "a.mov", "a.mkv", "a.webm", "a.ts", "a.m2ts", "a.mts",
            "a.flv", "a.ogv", "a.avi", "a.3gp", "a.3g2"
        )
        names.forEach { name -> assertTrue("$name 应被支持", MediaFormats.isSupportedMediaFile(name)) }
    }

    @Test
    fun `audio containers are supported`() {
        val names = listOf(
            "a.mp3", "a.m4a", "a.m4b", "a.aac", "a.flac", "a.ogg", "a.oga",
            "a.opus", "a.wav", "a.amr", "a.mka"
        )
        names.forEach { name -> assertTrue("$name 应被支持", MediaFormats.isSupportedMediaFile(name)) }
    }

    @Test
    fun `extension matching ignores case`() {
        listOf("A.MP4", "b.M4a", "C.FlAc", "clip.MKV", "sound.OGG").forEach { name ->
            assertTrue("$name 应被支持", MediaFormats.isSupportedMediaFile(name))
        }
    }

    @Test
    fun `only the last extension decides`() {
        assertTrue(MediaFormats.isSupportedMediaFile("my.cool.video.mp4"))
        assertTrue(MediaFormats.isSupportedMediaFile("  判据先 trim 再取扩展名.mp4  "))
        assertFalse(MediaFormats.isSupportedMediaFile("film.mp4.part"))
        assertFalse(MediaFormats.isSupportedMediaFile("trailer.mp4.txt"))
    }

    @Test
    fun `null blank and extensionless names are rejected`() {
        assertFalse(MediaFormats.isSupportedMediaFile(null))
        assertFalse(MediaFormats.isSupportedMediaFile(""))
        assertFalse(MediaFormats.isSupportedMediaFile("   "))
        assertFalse(MediaFormats.isSupportedMediaFile("video-without-extension"))
        assertFalse(MediaFormats.isSupportedMediaFile("ends-with-dot."))
    }

    @Test
    fun `unsupported extensions are rejected`() {
        listOf("notes.txt", "archive.zip", "photo.jpg", "audio-cd.cda", "字幕.ass").forEach { name ->
            assertFalse("$name 不应被支持", MediaFormats.isSupportedMediaFile(name))
        }
    }

    @Test
    fun `mime type maps known video containers`() {
        assertEquals("video/mp4", MediaFormats.mimeTypeFor("a.mp4"))
        assertEquals("video/quicktime", MediaFormats.mimeTypeFor("a.MOV"))
        assertEquals("video/x-matroska", MediaFormats.mimeTypeFor("a.mkv"))
        assertEquals("video/mp2t", MediaFormats.mimeTypeFor("a.m2ts"))
        assertEquals("video/3gpp", MediaFormats.mimeTypeFor("a.3gp"))
    }

    @Test
    fun `mime type maps known audio containers`() {
        assertEquals("audio/mpeg", MediaFormats.mimeTypeFor("a.mp3"))
        assertEquals("audio/mp4", MediaFormats.mimeTypeFor("a.m4a"))
        assertEquals("audio/flac", MediaFormats.mimeTypeFor("a.flac"))
        assertEquals("audio/amr", MediaFormats.mimeTypeFor("a.amr"))
    }

    @Test
    fun `mime type is null for unsupported names`() {
        assertNull(MediaFormats.mimeTypeFor("a.txt"))
        assertNull(MediaFormats.mimeTypeFor(null))
        assertNull(MediaFormats.mimeTypeFor(""))
        assertNull(MediaFormats.mimeTypeFor("no-extension"))
    }
}
