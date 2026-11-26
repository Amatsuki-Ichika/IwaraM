package io.github.amatsukiichika.iwaram.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MP4 顶层结构校验（[walkMp4]）与自动重下判定的契约测试。
 *
 * 用例全部用合成字节驱动，不碰真实文件、不依赖 Android；旧实现没有这个校验文件，
 * 这些断言在新代码出现前编译不过，正是"在旧代码上会失败"的证明。
 */
class MediaIntegrityTest {

    // ---- 合法结构 ----

    @Test
    fun `accepts a valid mp4 layout`() {
        val bytes = sampleMp4()
        assertEquals(
            IntegrityVerdict.Ok,
            walkMp4(bytes.size.toLong(), bytes.size.toLong(), bytesReader(bytes))
        )
    }

    @Test
    fun `accepts filler boxes before ftyp`() {
        val bytes = box("free", ByteArray(8)) + sampleMp4()
        assertEquals(IntegrityVerdict.Ok, walkMp4(bytes.size.toLong(), null, bytesReader(bytes)))
    }

    @Test
    fun `accepts a 64 bit largesize box`() {
        val bytes = box("ftyp", ByteArray(16)) + box("moov", ByteArray(16)) +
            largeBox("mdat", ByteArray(32))
        assertEquals(IntegrityVerdict.Ok, walkMp4(bytes.size.toLong(), null, bytesReader(bytes)))
    }

    @Test
    fun `accepts an mdat box that extends to end of file`() {
        // size == 0 表示"延伸到文件末尾"，只允许出现在最后一个 box。
        val bytes = box("ftyp", ByteArray(16)) + box("moov", ByteArray(16)) +
            zeroSizedBox("mdat", ByteArray(40))
        assertEquals(IntegrityVerdict.Ok, walkMp4(bytes.size.toLong(), null, bytesReader(bytes)))
    }

    // ---- 长度账 ----

    @Test
    fun `rejects a file shorter than the expected size`() {
        val bytes = sampleMp4()
        assertEquals(
            IntegrityVerdict.Broken(BrokenReason.Truncated, "文件长度 ${bytes.size} 小于期望 ${bytes.size + 10L}"),
            walkMp4(bytes.size.toLong(), bytes.size + 10L, bytesReader(bytes))
        )
    }

    @Test
    fun `rejects a file longer than the expected size`() {
        val bytes = sampleMp4()
        assertEquals(
            IntegrityVerdict.Broken(BrokenReason.ExtraBytes, "文件长度 ${bytes.size} 大于期望 ${bytes.size - 10L}"),
            walkMp4(bytes.size.toLong(), bytes.size - 10L, bytesReader(bytes))
        )
    }

    @Test
    fun `reports empty file as truncated`() {
        assertEquals(
            IntegrityVerdict.Broken(BrokenReason.Truncated, "文件长度为 0"),
            walkMp4(0L, null) { _, _ -> error("空文件不该被读取") }
        )
    }

    // ---- 内容嗅探 ----

    @Test
    fun `rejects an html error page`() {
        val bytes = "<html><body>403 Forbidden</body></html>".toByteArray(Charsets.UTF_8)
        assertEquals(
            IntegrityVerdict.Broken(BrokenReason.NotMp4, "文件内容像文本（HTML/JSON 错误页），不是 MP4"),
            walkMp4(bytes.size.toLong(), null, bytesReader(bytes))
        )
    }

    @Test
    fun `rejects a json error body`() {
        val bytes = """{"error":"forbidden"}""".toByteArray(Charsets.UTF_8)
        val verdict = walkMp4(bytes.size.toLong(), null, bytesReader(bytes))
        assertEquals(BrokenReason.NotMp4, (verdict as IntegrityVerdict.Broken).reason)
    }

    // ---- box 结构 ----

    @Test
    fun `rejects a file whose first box is not ftyp`() {
        val bytes = box("moov", ByteArray(16)) + box("ftyp", ByteArray(16)) + box("mdat", ByteArray(16))
        assertEquals(
            IntegrityVerdict.Broken(BrokenReason.NotMp4, "首个 box 是 moov，不是 ftyp"),
            walkMp4(bytes.size.toLong(), null, bytesReader(bytes))
        )
    }

    @Test
    fun `reports missing moov box`() {
        val bytes = box("ftyp", ByteArray(16)) + box("mdat", ByteArray(24))
        assertEquals(
            IntegrityVerdict.Broken(BrokenReason.NoMoov, "缺少 moov box"),
            walkMp4(bytes.size.toLong(), null, bytesReader(bytes))
        )
    }

    @Test
    fun `reports missing mdat box`() {
        val bytes = box("ftyp", ByteArray(16)) + box("moov", ByteArray(24))
        assertEquals(
            IntegrityVerdict.Broken(BrokenReason.NoMdat, "缺少 mdat box"),
            walkMp4(bytes.size.toLong(), null, bytesReader(bytes))
        )
    }

    @Test
    fun `reports a file with two moov boxes`() {
        val bytes = box("ftyp", ByteArray(16)) + box("moov", ByteArray(8)) +
            box("moov", ByteArray(8)) + box("mdat", ByteArray(16))
        assertEquals(
            IntegrityVerdict.Broken(BrokenReason.NoMoov, "moov 出现了 2 次（应唯一）"),
            walkMp4(bytes.size.toLong(), null, bytesReader(bytes))
        )
    }

    @Test
    fun `reports a box that overflows the file`() {
        val bytes = sampleMp4().copyOf()
        // 把第一个 ftyp 的 size 改成一个远超文件长度的值。
        u32(4096).copyInto(bytes, 0)
        assertEquals(
            IntegrityVerdict.Broken(BrokenReason.BoxOverflow, "ftyp 越界：box 结束于 4096，文件长 ${bytes.size}"),
            walkMp4(bytes.size.toLong(), null, bytesReader(bytes))
        )
    }

    @Test
    fun `reports a box shorter than its own header`() {
        val bytes = box("ftyp", ByteArray(8)) + declaredSizeHeader("moov", 4) + ByteArray(24)
        assertEquals(
            IntegrityVerdict.Broken(BrokenReason.BoxOverflow, "moov 的 size=4 小于 box 头长度"),
            walkMp4(bytes.size.toLong(), null, bytesReader(bytes))
        )
    }

    @Test
    fun `reports a largesize that cannot hold its own header`() {
        val bytes = box("ftyp", ByteArray(8)) + u32(1) + "mdat".toByteArray(Charsets.US_ASCII) + u64(8L)
        assertEquals(
            IntegrityVerdict.Broken(BrokenReason.BoxOverflow, "mdat 的 largesize=8 小于 box 头长度"),
            walkMp4(bytes.size.toLong(), null, bytesReader(bytes))
        )
    }

    @Test
    fun `reports a truncated trailing box header`() {
        val bytes = sampleMp4() + byteArrayOf(0, 0, 0, 0)
        assertEquals(
            IntegrityVerdict.Broken(BrokenReason.Truncated, "末尾剩 4 字节，不足一个 box 头"),
            walkMp4(bytes.size.toLong(), null, bytesReader(bytes))
        )
    }

    // ---- IO 失败 ----

    @Test
    fun `reports unknown when reading fails`() {
        assertEquals(
            IntegrityVerdict.Unknown,
            walkMp4(1024L, null) { _, _ -> null }
        )
    }

    // ---- 自动重下判定 ----

    @Test
    fun `redownloads a broken file the first time`() {
        assertTrue(shouldAutoRedownload(IntegrityVerdict.Broken(BrokenReason.Truncated), false))
    }

    @Test
    fun `does not redownload a broken file twice`() {
        assertFalse(shouldAutoRedownload(IntegrityVerdict.Broken(BrokenReason.Truncated), true))
    }

    @Test
    fun `does not redownload an unknown verdict`() {
        assertFalse(shouldAutoRedownload(IntegrityVerdict.Unknown, false))
    }

    @Test
    fun `does not redownload a healthy file`() {
        assertFalse(shouldAutoRedownload(IntegrityVerdict.Ok, false))
    }

    @Test
    fun `does not redownload a file without a download record`() {
        assertFalse(shouldAutoRedownload(IntegrityVerdict.NoDownloadRecord, false))
    }

    // ---- 核对对象：只认本应用下载的文件 ----

    @Test
    fun `integrity check has no target without a download record`() {
        // 扫描导入或手动关联的文件没有下载记录：以前会回退到库行地址并做 MP4 检查，
        // 结果把 mkv/webm 判成损坏并删除。没有记录就必须没有核对对象。
        assertNull(integrityCheckEntry(emptyList(), "vid"))
    }

    @Test
    fun `integrity check ignores failed download records`() {
        assertNull(integrityCheckEntry(listOf(history(success = false, uri = "content://failed")), "vid"))
    }

    @Test
    fun `integrity check ignores a successful record without a file address`() {
        assertNull(integrityCheckEntry(listOf(history(success = true, uri = null)), "vid"))
    }

    @Test
    fun `integrity check ignores a record the app did not write`() {
        // 快捷路径会把"本地库已有"的文件写进下载记录；那份文件可能是用户导入的。
        assertNull(
            integrityCheckEntry(
                listOf(history(success = true, uri = "content://imported", downloadedByApp = false)),
                "vid"
            )
        )
    }

    @Test
    fun `integrity check uses the file named by the successful download record`() {
        val entry = history(success = true, uri = "content://downloaded")
        assertEquals(entry, integrityCheckEntry(listOf(entry), "vid"))
    }

    @Test
    fun `labels a missing download record for diagnostics`() {
        assertEquals("no-download-record", integrityVerdictNote(IntegrityVerdict.NoDownloadRecord))
    }

    @Test
    fun `labels verdicts for download history diagnostics`() {
        assertEquals("ok", integrityVerdictNote(IntegrityVerdict.Ok))
        assertEquals("unknown", integrityVerdictNote(IntegrityVerdict.Unknown))
        assertEquals(
            "broken:boxoverflow",
            integrityVerdictNote(IntegrityVerdict.Broken(BrokenReason.BoxOverflow))
        )
    }

    // ---- 合成数据 ----

    private fun history(
        success: Boolean,
        uri: String?,
        downloadedByApp: Boolean = true
    ) = DownloadHistoryEntry(
        videoId = "vid",
        title = "title",
        quality = "source",
        time = 0L,
        success = success,
        localUri = uri,
        downloadedByApp = downloadedByApp
    )

    private fun sampleMp4(): ByteArray =
        box("ftyp", ByteArray(16)) + box("moov", ByteArray(24)) + box("mdat", ByteArray(64))

    /** 按偏移读取：超出末尾时返回较短数组，与真实文件读取语义一致。 */
    private fun bytesReader(bytes: ByteArray): (Long, Int) -> ByteArray? = { offset, size ->
        require(offset >= 0)
        if (offset >= bytes.size) {
            ByteArray(0)
        } else {
            val from = offset.toInt()
            bytes.copyOfRange(from, minOf(bytes.size, from + size))
        }
    }

    private fun box(type: String, payload: ByteArray = ByteArray(0)): ByteArray =
        u32(8 + payload.size) + type.toByteArray(Charsets.US_ASCII) + payload

    private fun largeBox(type: String, payload: ByteArray = ByteArray(0)): ByteArray {
        val total = 16L + payload.size
        return u32(1) + type.toByteArray(Charsets.US_ASCII) + u64(total) + payload
    }

    private fun zeroSizedBox(type: String, payload: ByteArray): ByteArray =
        u32(0) + type.toByteArray(Charsets.US_ASCII) + payload

    private fun declaredSizeHeader(type: String, declaredSize: Int): ByteArray =
        u32(declaredSize) + type.toByteArray(Charsets.US_ASCII)

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    private fun u64(value: Long): ByteArray = ByteArray(8) { i ->
        ((value ushr (56 - 8 * i)) and 0xFF).toByte()
    }
}
