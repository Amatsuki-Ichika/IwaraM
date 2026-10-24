# IwaraM 文档补全：把「所有改动必须走 PR」写入仓库文档

依据交接文档（C:\Users\ASUS\Desktop\IwaraM-文档补全交接.md）执行。已核实：仓库 HEAD 为 262e1d3、工作区干净（仅未跟踪的 `.zcode/`），AGENTS.md / CONTRIBUTING.md 的章节与行号与交接文档描述一致，可直接套用其给出的内容。

## 修改内容（共 2 个文件，均为英文，LF + 末尾换行）

### 1. AGENTS.md — 在 `## Commands` 之后、`## Layout` 之前插入「Workflow」章节

内容照抄交接文档 §6.1（说明 main 受分支保护、直推被拒、必须走 PR、本地三件套、Conventional Commit 标题 + squash merge、无依赖机器人需手动升级并实测、发版打 `v*` tag 须等于 versionName 并指向 CONTRIBUTING.md 的 Releasing 一节）。

### 2. CONTRIBUTING.md — 三处小改

- 「Pull requests」章节（L29-38）开头加第一条：直推 main 被分支规则禁止，必须开 PR，CI 绿后方可合并。
- 把 L37 的 "Run the CI workflow locally if you can..." 替换为明确的本地三件套：`./gradlew :app:test :app:assembleDebug :app:lintDebug`。
- 「Dependencies」章节（L41-44）末尾加一句：依赖更新机器人已禁用，更新手动应用并用 debug 构建验证（交接文档 §5 将「无 bot」列为本次应补的事实）。

不改 `.github/pull_request_template.md`（交接文档明确「无需改动」），不动 CHANGELOG（文档改动非用户可见），不提交 `.zcode/`。

## 交付流程（该修改本身也走 PR，按交接文档 §7 约束）

1. `git checkout main && git pull`，从 main 开分支 `docs/pr-workflow`
2. 应用上述两文件修改，确认行尾为 LF 且有末尾换行（.editorconfig 约束）
3. 本地跑全量检查：`./gradlew :app:test :app:assembleDebug :app:lintDebug`（纯文档改动也应全绿）
4. 提交，Conventional Commits 风格：`docs: document required PR workflow and manual dependency updates`
5. `git push -u origin docs/pr-workflow`，创建 PR（标题与提交信息一致；优先用 `gh`，若本机 Git Bash 无 gh 则尝试完整路径/其他方式，再不行给出 `https://github.com/Amatsuki-Ichika/IwaraM/pull/new/docs/pr-workflow` 由网页创建）
6. 等待 CI 的 `build` 检查变绿（约 4-6 分钟），Squash and merge（0 个必需批准，作者可自行合并），删除远端分支，本地切回 main 拉取

## 验收标准（交接文档 §7）

- `grep -n "Workflow" AGENTS.md` 命中新章节
- CONTRIBUTING.md「Pull requests」第一条为直推禁令
- 本地三件套全绿
- PR 合并后 main 上新增一条 `docs:` 提交且 CI 绿

## 风险与说明

- 直推 main 会被服务端拒绝，这是预期行为；全程不尝试绕过，也不在文档中提 bypass。
- 若 `gh` 不可用且无法自动创建/合并 PR，则停在「分支已推送 + 给出建 PR 链接」，向用户说明剩余两步（建 PR、等绿后 squash merge）需要网页操作。