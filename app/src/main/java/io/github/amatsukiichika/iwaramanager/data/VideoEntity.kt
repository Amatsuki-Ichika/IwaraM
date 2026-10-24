package io.github.amatsukiichika.iwaramanager.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条本地视频记录。以 localUri 为主键（SAF 的 content:// URI 字符串）。
 */
@Entity(tableName = "videos")
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
