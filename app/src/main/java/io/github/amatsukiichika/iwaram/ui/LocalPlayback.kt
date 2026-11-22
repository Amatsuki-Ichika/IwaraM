package io.github.amatsukiichika.iwaram.ui

/**
 * 播放页当前挂着的本地文件地址（进程级）。
 *
 * 播放器实例建在播放页内部、随页面销毁，仓库里没有全局播放状态；详情区是播放页打开期间
 * 本层唯一稳定存在的入口，因此由 [io.github.amatsukiichika.iwaram.ui.DetailsSection]
 * 在进入时登记、离开时清空。删除逻辑据此拒绝删除正在播放的那一份文件。
 *
 * 存 [io.github.amatsukiichika.iwaram.data.VideoEntity.localUri] 而不是标题：
 * 同一支视频的不同版本共用一个标题，按标题判会误拦其它版本。
 */
internal object LocalPlayback {
    @Volatile
    var currentUri: String? = null
}
