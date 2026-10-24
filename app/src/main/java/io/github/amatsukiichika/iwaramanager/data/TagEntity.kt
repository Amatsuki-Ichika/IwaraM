package io.github.amatsukiichika.iwaramanager.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * iwara 标签。id 即 iwara 返回的 tag id（本质是标签名/slug）。
 */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String
)
