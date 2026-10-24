package io.github.amatsukiichika.iwaram.data

import androidx.room.Entity
import androidx.room.Index

/**
 * 视频-标签多对多关联表。
 *
 * `tagId` 上建索引（v3 新增）：[VideoDao.observeAllTags] 按 `t.id = c.tagId` JOIN 本表，
 * 复合主键 `(videoUri, tagId)` 只覆盖按 videoUri 的方向，按 tagId 反查此前只能全表扫。
 */
@Entity(
    tableName = "video_tag_cross_ref",
    primaryKeys = ["videoUri", "tagId"],
    indices = [Index("tagId")]
)
data class VideoTagCrossRef(
    val videoUri: String,
    val tagId: String
)
