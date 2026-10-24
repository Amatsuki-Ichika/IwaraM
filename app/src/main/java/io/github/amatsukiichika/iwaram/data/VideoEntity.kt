package io.github.amatsukiichika.iwaram.data

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
    val addedAt: Long
)
