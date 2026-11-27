package io.github.amatsukiichika.iwaram.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 观看历史的 DAO。风格对齐 VideoDao：观察用 Flow、单发用 suspend。 */
@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY lastWatchAt DESC")
    fun observeAll(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE videoKey = :key LIMIT 1")
    suspend fun byKey(key: String): WatchHistoryEntity?

    /**
     * 进度更新 = **单条原子 UPDATE**（返回命中行数）。
     *
     * 不能用「byKey 读整行 → REPLACE 写回」：`historyScope` 是 Dispatchers.IO 多线程池，
     * 两次进度写的读-改-写可以交错，旧快照后落库就会把进度回退；行若在两步之间被删，
     * REPLACE 还会把它复活。单条 UPDATE 命中 0 行时自然什么也不做（不造新行）。
     * [durationMs] <= 0 表示这次拿不到总时长，保留原值。
     */
    @Query(
        """UPDATE watch_history
           SET positionMs = :positionMs,
               durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END,
               lastWatchAt = :now
           WHERE videoKey = :key"""
    )
    suspend fun updateProgress(key: String, positionMs: Long, durationMs: Long, now: Long): Int

    /** 有多少条观看历史把 [thumbnail] 当封面引用（删库行/扫描收尾删封面文件前必须查）。 */
    @Query("SELECT COUNT(*) FROM watch_history WHERE thumbnail = :thumbnail")
    suspend fun countByThumbnail(thumbnail: String): Int

    /** videoKey 唯一索引冲突时 REPLACE = 同视频重看原地覆盖（id 会换新，其它地方一律用 videoKey 定位）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM watch_history")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM watch_history")
    suspend fun count(): Int

    /** 单条 SQL 裁掉最旧 n 条（逐条 select id + deleteById 时每条一个事务，开销更大）。 */
    @Query(
        """DELETE FROM watch_history WHERE id IN (
               SELECT id FROM watch_history ORDER BY lastWatchAt ASC LIMIT :n
           )"""
    )
    suspend fun deleteOldest(n: Int)
}
