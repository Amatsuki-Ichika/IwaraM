# IwaraM

[![CI](https://github.com/Amatsuki-Ichika/IwaraM/actions/workflows/ci.yml/badge.svg)](https://github.com/Amatsuki-Ichika/IwaraM/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Amatsuki-Ichika/IwaraM?sort=semver)](https://github.com/Amatsuki-Ichika/IwaraM/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-yellow.svg)](LICENSE)
[![Min SDK](https://img.shields.io/badge/minSdk-26-blue.svg)](https://developer.android.com/about/versions/oreo)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)

[English](README.en.md) · 简体中文

[iwara.tv](https://iwara.tv) 的非官方 Android 客户端，Kotlin + Jetpack Compose 编写：浏览站点内容、
维护可搜索的本地库，并支持可选的 GPU 超分辨率播放。

> **与 iwara.tv 无任何隶属关系。** 本项目是社区独立客户端。站点可能包含成人内容，
> 使用前请阅读 [DISCLAIMER.md](DISCLAIMER.md)。

## 功能

- **在线浏览** —— 热门/最新信息流、标签浏览、搜索、作者页。
- **本地库** —— 导入并扫描媒体目录、抓取元数据，再按文件夹 / 作者 / 日期 / 标签浏览，
  支持搜索与排序。
- **播放** —— 左右滑动定位、双击定位、全屏、清晰度选择、从上次进度续播。
- **超分辨率** —— 可选的 Anime4K 着色器模型，运行在 GPU 上。
- **下载** —— 后台下载并支持断点续传。
- **观看历史** —— 自动记录，一键续播。
- **收藏与播放列表** —— 浏览并管理账号下的收藏与播放列表。
- **内容筛选** —— 针对标题与标签的全局包含/排除规则，以及 R18 显示开关。
- **Material 3 界面** —— 浅色/深色主题与可选主题色，顶栏/底栏可选磨砂玻璃材质。

## 安装

前往 [Releases 页面](https://github.com/Amatsuki-Ichika/IwaraM/releases/latest) 下载最新 APK 安装。
构建目标为 **arm64-v8a**、**Android 8.0（API 26）** 及以上。

测试版（RC）也会发布在 [Releases 页面](https://github.com/Amatsuki-Ichika/IwaraM/releases)并标注为
pre-release；上面的下载链接始终指向最新的正式版。

每个版本都用同一张证书签名，因此新版本可以直接覆盖安装旧版本；由他人签名的包则不能——
若你之前用的是别的签名包，请先卸载。校验下载文件的完整性与签名见 [SECURITY.md](SECURITY.md)。

## 从源码构建

前置条件：

- JDK 17
- Android SDK：平台 `android-37.2`、build-tools `36.0.0`

```bash
# 让 Gradle 找到你的 SDK（local.properties 已被 git 忽略）
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :app:test            # 单元测试
./gradlew :app:assembleDebug   # Debug APK
./gradlew :app:assembleRelease # 签名 Release APK，需要 keystore.properties，见 CONTRIBUTING.md
```

版本号以 `gradle.properties` 里的 `app.versionCode` / `app.versionName` 为唯一来源，也可在构建时覆盖：
`./gradlew assembleRelease -Papp.versionName=0.2.0`。

## 架构

单 Activity 的 Compose 应用，配手写的覆盖层栈；数据层由 Repository 封装 Room 与 Retrofit，
各页面各自持有 ViewModel —— 有意不引入 DI 框架与 Navigation 库。模块地图与代码约定见
[docs/architecture.md](docs/architecture.md)。

## 参与贡献

欢迎提交 issue 与 PR，请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。本项目遵循
[贡献者公约](CODE_OF_CONDUCT.md)。

## 许可

[MIT](LICENSE) © IwaraM contributors。

打包的 Anime4K 着色器由其作者以 MIT 许可发布；完整的三方组件清单见 [NOTICE.md](NOTICE.md)。

## 致谢

- [Anime4K](https://github.com/bloc97/Anime4K)（bloc97）—— 超分辨率着色器。
- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Kyant0）—— 磨砂玻璃栏所用的背景效果。
