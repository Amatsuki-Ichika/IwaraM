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
