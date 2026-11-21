package io.github.amatsukiichika.iwaram.ui

import io.github.amatsukiichika.iwaram.data.VideoEntity
import io.github.amatsukiichika.iwaram.i18n.tr

/**
 * 本地条目的删除计划 —— 「从库移除」与「彻底删除」共用的纯逻辑。
 *
 * 拆成纯函数是为了让"先删文件还是先删行"有唯一定义、可以 JVM 单测：
 * 删文件的失败方式（SAF 拒绝、文件已丢、权限被撤）在生产里很难复现，但顺序错了就是
 * 用户以为文件删了、实际上还在磁盘上，或者反过来 —— 数据损坏，不是显示问题。
 */
internal sealed interface LocalDeletionPlan {
    /** 只删库记录，磁盘文件与封面都保留（重新扫描可找回）。 */
    data class DeleteRowOnly(val video: VideoEntity) : LocalDeletionPlan

    /** 先删文件、再删记录与封面；文件删不掉就整体中止。 */
    data class DeleteRowAndFiles(val video: VideoEntity) : LocalDeletionPlan

    /** 拒绝执行；[reason] 是可直接展示给用户的原因。 */
    data class Abort(val reason: String) : LocalDeletionPlan
}

/**
 * 计算一条本地视频的删除计划。
 *
 * [playingLocalUri] 是播放页当前正在播放的本地地址（没有在播时传 null）。只有**真的要删文件**
 * 时才拦截正在播放的那一份：只删记录不会动磁盘文件，播放不受影响。
 * 其它版本（同一条逻辑视频的其它源文件）不在 [video] 里，天然不会被这次删除波及。
 */
internal fun planLocalDeletion(
    video: VideoEntity,
    deleteFiles: Boolean,
    playingLocalUri: String?
): LocalDeletionPlan {
    if (deleteFiles && playingLocalUri == video.localUri) {
        return LocalDeletionPlan.Abort(
            tr("该视频正在播放，请先退出播放页", "This video is playing; exit the player first")
        )
    }
    return if (deleteFiles) {
        LocalDeletionPlan.DeleteRowAndFiles(video)
    } else {
        LocalDeletionPlan.DeleteRowOnly(video)
    }
}

/**
 * 按 [plan] 执行删除，返回 null 表示成功，非 null 是给用户看的失败原因。
 *
 * 顺序是 **先文件、后行**：文件删不掉就中止、行保留 —— 反过来的话用户以为已经删掉了，
 * 重新扫描又把文件收回来，等于删了个寂寞；而"文件没了、行还在"至多是一条陈旧记录。
 *
 * [deleteFile] / [deleteRow] 由调用方注入（Repository 的 suspend 函数），本函数因此可以在
 * JVM 单测里用两个 lambda 覆盖全部分支。
 */
internal suspend fun executeLocalDeletion(
    plan: LocalDeletionPlan,
    deleteFile: suspend (VideoEntity) -> String?,
    deleteRow: suspend (VideoEntity) -> Unit
): String? = when (plan) {
    is LocalDeletionPlan.Abort -> plan.reason
    is LocalDeletionPlan.DeleteRowOnly -> {
        deleteRow(plan.video)
        null
    }
    is LocalDeletionPlan.DeleteRowAndFiles -> {
        val failure = deleteFile(plan.video)
        if (failure != null) {
            failure
        } else {
            deleteRow(plan.video)
            null
        }
    }
}

/**
 * 批量执行 [executeLocalDeletion]（播放页的"删除选中的源文件"走这条）。
 *
 * 任何一条失败就停下并返回原因：失败点之前的行已经连文件一起删掉了，失败点与之后还没处理的
 * 源全部保留。`先文件后行` 的顺序在这里同样成立，所以不存在"文件还在、行却没了"的中间态。
 */
internal suspend fun executeLocalDeletionBatch(
    videos: List<VideoEntity>,
    deleteFiles: Boolean,
    playingLocalUri: String?,
    deleteFile: suspend (VideoEntity) -> String?,
    deleteRow: suspend (VideoEntity) -> Unit
): String? {
    for (video in videos) {
        val failure = executeLocalDeletion(
            plan = planLocalDeletion(video, deleteFiles, playingLocalUri),
            deleteFile = deleteFile,
            deleteRow = deleteRow
        )
        if (failure != null) return failure
    }
    return null
}

