# 评估结论(逐项)

## 1. 拆分超 800 行大文件 —— 做(实际 11 个,不是 10 个)
实测 11 个超 800 行,最大 MainActivity.kt 1482 行。按风险分两档:
- **机械拆分(8 个)**:IwaraCommon(1409,五大块近零耦合)、IwaraOverlays(1144,6 个独立 overlay)、SettingsScreen(880,9 个分区 composable)、SuperResolution(872,3 个自包含顶层单元)、VideoViewModel(979,模型群 + 10 个顶层纯函数现成可移)、IwaraProfilePage(1370,通用分页四件套等三块)、LibraryScreen(856,模式列表群 + 对话框)、PlayerScreen(1004,尾部 8 个纯辅助)→ **同包整声明搬家**:import 不变、测试零改动、行为不变。
- **结构拆分(3 个)**:IwaraClientViewModel(按 8 个功能域拆 state-holder)、IwaraRepository(18 组 prefs 代理归一 + 扫描/缩略图外移)、MainActivity(`MainContent` 约 1124 行上帝作用域状态下沉,顺带解决审查 Top2)。

## 2. targetSdk 34→36 —— 做,放最后一个 PR
compileSdk 已 37.2,只需改 targetSdk。两个最大风险点已预排除:
- **edge-to-edge(35 强制)**:`MainActivity.kt:190` 已调 `enableEdgeToEdge()`,AppTopBar/AppBottomSheet/IwaraCommon 均正确消费 insets。
- **predictive back(36 默认开)**:manifest 已 `enableOnBackInvokedCallback="true"`,5 个文件用 BackHandler/OnBackPressedDispatcher。
真正要处理的:**dataSync 前台服务在 Android 15+ 有 6 小时/24h 限制**——DownloadService/ScanService 需覆写 `onTimeout()`(API 35+):持久化队列 → stopSelf → 发「已暂停」通知。之后 API 36 模拟器回归:登录/信息流/在线播放/下载与续传/扫描/全部设置/返回手势/横竖屏/键盘。
注:仅 GitHub Releases 分发,不受 Play targetSdk 政策约束;此项属「跟上生态 + 新系统兼容」。

## 3. validateDistributionUrl=false / CoC 邮箱 / FUNDING
- **validateDistributionUrl=false → 删**(并入小项 PR):初始提交的刻意配置(非 PR #14 引入),它是 `./gradlew wrapper` 重新生成时校验 distributionUrl 的开关,日常构建不走;官方 https 源下设 false 无收益、少一道防呆。同文件 `retries=0` 也是初始配置,本次不动。
- **CoC 举报邮箱:维持现状**(已确认)。GitHub 私有报告渠道 + SECURITY.md 已闭环,不公开个人邮箱。
- **FUNDING.yml:不加**(已确认)。

## 4. Retrofit 3 + OkHttp 5 —— 做,合并为一个 PR
当前 retrofit 2.11.0 / okhttp 4.12.0。合并升级合理:同属网络栈,分开要做两轮冒烟。升级面小:测试**无 MockWebServer 依赖**;API 层 37 处 suspend 接口 3.x 原生支持;包名仍是 `retrofit2`;converter-gson 同步升。预判适配点:OkHttp 5 `Response.body` 非空化 → 清理 OkHttpDataSource.kt / IwaraClient.kt 的 `body?.` 写法;Coil 2.7 与 OkHttp 5 二进制兼容,不动。版本号实施时取 Maven Central 最新 stable。按 AGENTS.md 用 debug 构建冒烟(登录/信息流/播放/下载)。

## 5. 审查发现的其他问题(已确认四项全修)
按严重度:①token 明文存 SharedPreferences(高)②MainContent 上帝作用域(中,由拆分解决)③videos/cross_ref 零索引(中)④约 20 处 catch(Throwable) 不重抛 CancellationException(中)⑤strings.xml 100% 硬编码(大工程,**单独立项不进本次**)⑥DownloadService 空队列进程常驻(低)⑦两处 SimpleDateFormat 隐患(低)⑧SAF 授权失败静默吞(低)。
总体评价:错误处理、协程纪律、Compose 优化意识明显高于平均(迁移回归测试、错误归一双轨文案、服务取消纪律都有专测),`strings.xml` 资源化是最值得单独立项的短板。

# 实施计划(5 阶段 / 13 个 PR,全部走 AGENTS.md PR 流程)

**阶段 1(4 个独立小 PR,可并行)**
1. `fix/repo-hygiene` — .gitignore 加 `.zcode/`;删 `validateDistributionUrl=false`;MainActivity SAF `runCatching` 加日志;DownloadService 空队列补 stopSelf(DownloadQueueInvariantTest 随语义微调);`IwaraCommon.timeAgoLabel` 与 `IwaraSecondaryFeeds.downloadTimeFormat` 换线程安全 DateTimeFormatter(文案不变)。
2. `fix/cancellation-propagation` — FeedPager.kt:125-128 + IwaraClientViewModel 约 20 处补 CE 原样上抛(参照文件内已正确的 4 处写法);补一个 FeedPager 取消传播测试。
3. `feat/encrypted-tokens` — 新增 `data/TokenCrypto.kt`:AndroidKeyStore AES-256-GCM,versioned 密文,KeyProvider 接口隔离以便 JVM 测试;IwaraSession 读写走加密层,读遇明文 → 加密回写平滑迁移;Keystore 异常降级明文 + 日志;`allowBackup=false` 语义不变。Robolectric 测迁移/回环;模拟器冒烟「登录 → 杀进程 → 重启仍登录」。
4. `feat/room-indexes` — VideoEntity 加 `Index(iwaraId)` 与 `(metaFetched, iwaraId)` 复合索引;VideoTagCrossRef 加 `Index(tagId)`;AppDatabase v2→v3 + MIGRATION_2_3(CREATE INDEX)+ schema json;新增迁移回归测试(仿 WatchHistoryMigrationTest);**不加外键**(手工清关联已有测试,避免重建表)。

**阶段 2(机械拆分 4 PR,同包移动、测试零改动)**
5. `feat/file-splitting-1` — IwaraCommon → ui/browse 下 5 文件(overlay 栈纯逻辑/网格卡片/筛选 chips/搜索回顶/文本格式化)。
6. `feat/file-splitting-2` — IwaraOverlays → 每 overlay 一文件(评论三件套、播放列表、图片查看、帖子详情、动作面板)。
7. `feat/file-splitting-3` — SettingsScreen(每分区一文件)+ SuperResolution(SuperRes/SuperResStatus/Anime4kProgram 三文件)。
8. `feat/file-splitting-4` — VideoViewModel(模型群 + 纯函数群外移)、IwaraProfilePage(分页四件套/Relations/Bio 小件)、LibraryScreen(列表群 + ManualLinkDialog)、PlayerScreen(尾部辅助群)。

**阶段 3(结构拆分 3 PR,各自单独)**
9. `feat/split-client-viewmodel`(PR 2 合并后做)— IwaraClientViewModel 按域拆 state-holder(会话/主 feed/搜索/评论/播放列表/作者页/点赞关注/播放下载),公开 API 不变,IwaraSubscriptionContractTest 等契约测试保持绿。
10. `feat/split-repository` — IwaraRepository 拆 PreferenceFlowDelegate(18 组代理归一)、FolderScanner(importFolder/rescanStoredRoot/collectVideoFiles)、ThumbnailGenerator;公开 API 不变,调用方零改动。
11. `feat/split-main-content` — MainContent 状态下沉(overlay 栈 push/pop、播放解析 startResolve/resolveAndPlay/playHistoryItem 收进 state-holder),播放/历史覆盖层区块外移为 composable;重组边界收窄(解决审查 Top2)。模拟器冒烟。

**阶段 4**
12. `feat/retrofit3-okhttp5` — toml 升 retrofit 3.x + okhttp 5.x + converter-gson;body 非空化适配;全量单测 + debug 冒烟(登录/信息流/播放/下载)。

**阶段 5**
13. `feat/targetsdk-36` — targetSdk=36;DownloadService/ScanService 加 `onTimeout()` 优雅停止;API 36 模拟器按回归清单实测;CHANGELOG/README 如需同步。

**每个 PR 通用步骤**:从最新 main 拉 `fix/` 或 `feat/` 分支 → 更新 CHANGELOG.md [Unreleased] → `./gradlew :app:test :app:assembleDebug :app:lintDebug` 全绿 → 推分支开 PR(Conventional Commit 标题)→ CI 绿 → squash-merge。新逻辑(token 迁移、Room 迁移、onTimeout、CE 传播)均配 JVM/Robolectric 测试,符合 AGENTS.md 测试要求。

# 备注
- `gh` 未安装且计划模式禁联网:远端动态(Releases、Actions 运行历史、open issues)本轮未能核实,实施阶段补查;本地 main 与 origin/main 完全同步,远端文件内容 == 本地。
- 建议后续(本次不做):strings.xml 全面资源化(独立项目)、下载历史/播放列表 JSON→Room、AGP 支持 compileSdk 37 后删 `android.suppressUnsupportedCompileSdk=37`、VideoCard 模型 remember、wrapper `retries=0` 是否恢复默认、ci.yml 可加 gradle wrapper validation 步骤。