package io.github.amatsukiichika.iwaram.ui

import io.github.amatsukiichika.iwaram.data.VideoEntity
import io.github.amatsukiichika.iwaram.data.qualityLabel
import io.github.amatsukiichika.iwaram.data.qualityPlayRank
import io.github.amatsukiichika.iwaram.i18n.tr

/**
 * 本地库的一个**逻辑条目**：一支视频 + 它的全部源文件。
 *
 * 表里仍然是一行一个文件（见 [VideoEntity.localUri] 主键），这里只是把同一个 iwaraId
 * 的多份文件折成一条，让「本地库」显示成一个视频。折的是查询结果，不是表结构 ——
 * 所以不需要 Room 迁移，算错了也只是显示不对，不会弄坏用户数据。
 */
internal data class LocalSourceGroup(
    val key: String,
    /** 已按推荐播放顺序排好：档位高的在前，未知档位垫底。 */
    val sources: List<VideoEntity>
) {
    val primary: VideoEntity get() = sources.first()

    val sourceCount: Int get() = sources.size
}

/** 分组键：能认出 iwaraId 就按它归并；认不出的文件各自成组，绝不硬凑。 */
internal fun localSourceKey(video: VideoEntity): String =
    video.iwaraId?.takeIf { it.isNotBlank() } ?: video.localUri

/**
 * 源文件的推荐播放顺序。
 *
 * 档位降序（`Source` > `1080p` > … > `Preview`，认不出的排在已知档位之后），
 * 同档优先最近修改的那份（刚下载的通常更新），最后用文件名兜底保证顺序稳定
 * —— 稳定很重要：顺序一起变，播放器就可能每次打开都换一个源。
 */
internal val LOCAL_SOURCE_ORDER: Comparator<VideoEntity> =
    compareByDescending<VideoEntity> { qualityPlayRank(it.qualityLabel()) ?: Int.MIN_VALUE }
        .thenByDescending { it.fileModifiedAt }
        .thenBy { it.fileName }

/** 折组：输入全表，输出每个逻辑条目一组。保持首次出现的顺序。 */
internal fun groupLocalSources(rows: List<VideoEntity>): List<LocalSourceGroup> {
    if (rows.isEmpty()) return emptyList()
    val buckets = LinkedHashMap<String, MutableList<VideoEntity>>(rows.size)
    for (row in rows) buckets.getOrPut(localSourceKey(row)) { ArrayList(1) }.add(row)
    return buckets.map { (key, list) -> LocalSourceGroup(key, list.sortedWith(LOCAL_SOURCE_ORDER)) }
}

/**
 * 源文件在菜单/字幕里的显示名。
 *
 * 认不出档位时回退到「本地」而不是空串：菜单项没有文字在界面上就是一条空白，
 * 用户会以为是渲染坏了。
 */
internal fun VideoEntity.sourceDisplayName(): String = qualityLabel() ?: tr("本地", "Local")
