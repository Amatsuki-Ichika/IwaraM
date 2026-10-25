package io.github.amatsukiichika.iwaram.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条本地视频记录。以 localUri 为主键（SAF 的 content:// URI 字符串）。
 *
 * 索引（v3 新增，此前两类高频查询都是全表扫）：
 * - `iwaraId` 单列：[VideoDao.byIwaraId]（每次下载查重）按 `iwaraId = ?` 精确匹配；
 * - `(metaFetched, iwaraId)` 复合：[VideoDao.getMissingMetadata]（扫描/补元数据的高频查询）
 *   按 `metaFetched = 0 AND iwaraId IS NOT NULL` 过滤，大库上 metaFetched=0 的行占比很小。
 */
@Entity(
    tableName = "videos",
    indices = [
        Index("iwaraId"),
        Index(value = ["metaFetched", "iwaraId"])
    ]
)
data class VideoEntity(
    @PrimaryKey val localUri: String,
    val fileName: String,
    val iwaraId: String?,
    val title: String,
    val author: String?,
    val authorId: String?,
    val createdAt: Long?,
    val viewCount: Int?,
    val likeCount: Int?,
    val commentCount: Int?,
    val rating: String?,
    val thumbnailUrl: String?,
    val localThumbnailPath: String?,
    val relPath: String?,
    val tagsCsv: String?,
    val metaFetched: Boolean,
    val addedAt: Long,
    /**
     * 文件修改日期（epoch ms；0 = 未知，部分 SAF provider 不上报）。
     *
     * 「下载时间」排序的排序键（此前只有入库时刻 [addedAt]，最近一次扫描导入的一批
     * —— 往往是未匹配元数据的本地文件 —— 会整批置顶）。取不到修改日期的行在排序中
     * 恒垫底、组内退回 [addedAt]（见 VideoSorter 的两段式比较器）。
     * 写入点：扫描导入（[IwaraRepositoryScan.importFolder]）与下载完成（DownloadService）。
     */
    @ColumnInfo(defaultValue = "0")
    val fileModifiedAt: Long = 0L
)
