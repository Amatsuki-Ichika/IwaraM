package io.github.amatsukiichika.iwaram.data

/* ---------- 下载服务的库记录入口（服务不直接开数据库，规则见 data/AGENTS.md） ---------- */

/*
 * 下载服务（DownloadService）过去直接拿 app.database.videoDao() 读写 4 个查询，
 * 绕过仓库：换表结构/加迁移时服务与 UI 会各自漏改，测试也拿不到统一入口。
 * 这里按 data/AGENTS.md「UI/服务不直接碰数据库，收成仓库函数」收口；函数名带 Row/Downloaded
 * 前缀是为了和 IwaraRepositoryVideos.kt 的 UI 视图函数区分（后者给 ViewModel 用）。
 */

/** 按 localUri（主键）取库记录；下载收尾判断「这份文件是否已经在库里」用。 */
suspend fun IwaraRepository.videoRowByUri(localUri: String): VideoEntity? = dao.byUri(localUri)

/**
 * 按 iwaraId 取一条库记录。历史脏数据可能让同一 iwaraId 有多行，这里沿用 DAO 的
 * 单条语义（LIMIT 1）；调用方必须自行确认这行指向的文件是否还存在、是否完整。
 */
suspend fun IwaraRepository.videoRowByIwaraId(iwaraId: String): VideoEntity? = dao.byIwaraId(iwaraId)

/** 写入下载得到的库记录（主键 localUri：新文件新增、已入库的文件更新）。 */
suspend fun IwaraRepository.upsertDownloadedVideo(video: VideoEntity) {
    dao.upsert(video)
}

/** 删除被新记录顶替的旧行（连同标签关联）；磁盘文件与封面由调用方按各自语义处理。 */
suspend fun IwaraRepository.deleteReplacedVideoRow(video: VideoEntity) {
    dao.deleteVideoWithTags(video)
}
