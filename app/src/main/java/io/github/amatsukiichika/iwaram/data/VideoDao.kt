package io.github.amatsukiichika.iwaram.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** videos 表的最小投影（导入判重 / 老条目补 ID / 文件修改日期回填用）。 */
data class VideoRefRow(
    val localUri: String,
    val iwaraId: String?,
    val fileName: String,
    val fileModifiedAt: Long
)

@Dao
interface VideoDao {

    @Query("SELECT * FROM videos ORDER BY createdAt DESC, addedAt DESC")
    fun observeAll(): Flow<List<VideoEntity>>

    @Query(
        """SELECT DISTINCT t.* FROM tags t
           JOIN video_tag_cross_ref c ON t.id = c.tagId"""
    )
    fun observeAllTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM videos WHERE localUri = :uri")
    suspend fun byUri(uri: String): VideoEntity?

    @Query("SELECT * FROM videos")
    suspend fun getAll(): List<VideoEntity>

    /**
     * 待补元数据的条目 —— 下推到 SQL。
     * 若走 [getAll] 把**整张表**（含缩略图路径、tagsCsv）读进内存再在 Kotlin 里 filter，
     * 每次刷新都要付一次全表读取 + 全表对象构造。
     */
    @Query("SELECT * FROM videos WHERE metaFetched = 0 AND iwaraId IS NOT NULL")
    suspend fun getMissingMetadata(): List<VideoEntity>

    @Query("SELECT localUri FROM videos")
    suspend fun getAllUris(): List<String>

    /**
     * 导入判重 + 老条目补 ID + 修改日期回填只需要这四个字段 —— 不必把整行
     * （含缩略图路径、tagsCsv）都读出来。
     */
    @Query("SELECT localUri, iwaraId, fileName, fileModifiedAt FROM videos")
    suspend fun getAllRefs(): List<VideoRefRow>

    /**
     * 给「已入库但当时没识别出 ID」的老条目补上 ID，并重置为待补元数据。
     * 用途见 [IwaraRepository.importFolder] 的老条目补救分支。
     */
    @Query("UPDATE videos SET iwaraId = :iwaraId, metaFetched = 0 WHERE localUri = :localUri")
    suspend fun setIwaraId(localUri: String, iwaraId: String)

    /** 回填单个条目的文件修改日期（值不变时不调用 —— 见扫描循环的判等跳过）。 */
    @Query("UPDATE videos SET fileModifiedAt = :mtime WHERE localUri = :localUri")
    suspend fun setFileModifiedAt(localUri: String, mtime: Long)

    /**
     * 批量回填文件修改日期 —— 单个事务一次提交，Room 只发**一次**失效通知
     * （旧库首扫回填可达千行，逐行 UPDATE 会让 observeAll 重发射上千次）。
     */
    @Transaction
    suspend fun setFileModifiedAtBatch(items: List<Pair<String, Long>>) {
        for ((uri, mtime) in items) setFileModifiedAt(uri, mtime)
    }

    @Query("SELECT * FROM videos WHERE iwaraId = :iwaraId LIMIT 1")
    suspend fun byIwaraId(iwaraId: String): VideoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(video: VideoEntity)

    /**
     * 批量 upsert —— 单次 @Insert 自带整批一个事务，Room 只发**一次**
     * 失效通知（observeAll 重发射一次），避免逐文件 upsert 的"千级库扫描 = 千次全链路重算"。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(videos: List<VideoEntity>)

    /** 仅删除库记录（不删文件）；请优先用 [deleteVideoWithTags] 一并清标签关联。 */
    @Delete
    suspend fun delete(video: VideoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTags(tags: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRefs(refs: List<VideoTagCrossRef>)

    @Query("DELETE FROM video_tag_cross_ref WHERE videoUri = :videoUri")
    suspend fun clearCrossRefs(videoUri: String)

    /**
     * 复合写（原子）：upsert 视频 → 清旧标签关联 → 重建标签与关联。
     * 中途崩溃不会产生孤儿 cross-ref 或"半更新"的条目；调用方不得再在事务外
     * 直接调用 [clearCrossRefs]。
     */
    @Transaction
    suspend fun upsertVideoWithTags(video: VideoEntity, tags: List<TagEntity>, refs: List<VideoTagCrossRef>) {
        upsert(video)
        clearCrossRefs(video.localUri)
        if (tags.isNotEmpty()) {
            upsertTags(tags)
            insertCrossRefs(refs)
        }
    }

    /** 复合删（原子）：清标签关联 + 删库记录（不删磁盘文件）。 */
    @Transaction
    suspend fun deleteVideoWithTags(video: VideoEntity) {
        clearCrossRefs(video.localUri)
        delete(video)
    }

    @Query("SELECT COUNT(*) FROM videos")
    suspend fun count(): Int
}
