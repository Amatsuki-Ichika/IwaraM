package io.github.amatsukiichika.iwaram.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 下载历史 JSON 损坏时的留档行为（Low-30）。
 *
 * 旧实现把 Gson 失败静默吞成空表，而下一次 add/remove 会按空表重写主键 —— 一条被写坏的
 * 记录就永久丢掉整份历史（含 localUri / totalBytes）。现在读取失败仍返回空表（行为不变），
 * 但原文先备份到独立键，且**已有备份绝不覆盖**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadHistoryRecoveryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        prefs.edit().clear().commit()
    }

    @Test
    fun `corrupt history is backed up before a later write can erase it`() {
        val corrupt = """[{"videoId":"abc","title":"写到一半"""
        prefs.edit().putString("download_history_cache", corrupt).commit()
        val repo = IwaraRepository(context, db)

        // 行为不变：解析失败仍当作"没有历史"，下载列表不会因此崩溃。
        assertEquals(emptyList<DownloadHistoryEntry>(), repo.loadDownloadHistory())
        assertEquals(
            "无法解析的原文必须原样留档",
            corrupt,
            prefs.getString(DOWNLOAD_HISTORY_UNPARSED_KEY, null)
        )

        // 下一次写入会按空表重写主键 —— 备份必须仍然在，原文没有被永久丢掉。
        repo.addDownloadHistory(
            DownloadHistoryEntry("new", "新记录", "Source", 1L, false, "失败")
        )
        assertEquals(1, repo.loadDownloadHistory().size)
        assertEquals(
            "后续写入不得覆盖解析失败前的原文备份",
            corrupt,
            prefs.getString(DOWNLOAD_HISTORY_UNPARSED_KEY, null)
        )
    }

    @Test
    fun `an existing backup is never overwritten`() {
        prefs.edit()
            .putString(DOWNLOAD_HISTORY_UNPARSED_KEY, "第一次的原文")
            .putString("download_history_cache", "{broken-again")
            .commit()

        IwaraRepository(context, db).loadDownloadHistory()

        assertEquals(
            "已有备份必须先到先得，不能被第二次解析失败顶掉",
            "第一次的原文",
            prefs.getString(DOWNLOAD_HISTORY_UNPARSED_KEY, null)
        )
    }

    @Test
    fun `valid history leaves no backup behind`() {
        val repo = IwaraRepository(context, db)
        repo.addDownloadHistory(
            DownloadHistoryEntry("ok", "正常记录", "Source", 1L, true)
        )

        assertEquals(1, repo.loadDownloadHistory().size)
        assertNull(prefs.getString(DOWNLOAD_HISTORY_UNPARSED_KEY, null))
    }
}
