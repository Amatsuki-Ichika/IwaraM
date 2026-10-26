package io.github.amatsukiichika.iwaram.data

import kotlinx.coroutines.flow.Flow

/* ---------- 本地库只读视图与删除（把 UI 对 DAO 的依赖收进仓库） ---------- */

/**
 * 本地库全部行的观察流。
 *
 * 存在的理由不是"多一层转发"：`data/AGENTS.md` 的硬规则是 **UI 不碰数据库**。
 * 让 ViewModel 直接调 DAO 时，这条规则只是口号；收成仓库的扩展函数后，规则字面成立，
 * 换存储实现（或给查询加分页）也只有一个入口要改。
 */
fun IwaraRepository.observeVideos(): Flow<List<VideoEntity>> = dao.observeAll()

/** 标签表的观察流（作者/标签筛选用）。 */
fun IwaraRepository.observeTags(): Flow<List<TagEntity>> = dao.observeAllTags()

/**
 * 删除一条本地库记录（连同标签关联），**不动磁盘文件**。
 * 主键是 localUri，所以调用方传哪一行就删哪一行 —— 多源共存时这是"删掉某一个源"的语义。
 */
suspend fun IwaraRepository.deleteVideo(video: VideoEntity) = dao.deleteVideoWithTags(video)
