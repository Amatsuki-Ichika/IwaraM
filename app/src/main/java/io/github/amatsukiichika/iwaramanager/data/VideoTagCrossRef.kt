package io.github.amatsukiichika.iwaramanager.data

import androidx.room.Entity

/**
 * 视频-标签多对多关联表。
 */
@Entity(tableName = "video_tag_cross_ref", primaryKeys = ["videoUri", "tagId"])
data class VideoTagCrossRef(
    val videoUri: String,
    val tagId: String
)
