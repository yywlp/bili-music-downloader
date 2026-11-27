# BiliMusic Android · 原生安卓纯单曲音频工具

基于 **Kotlin + Jetpack Compose (Material 3) + OkHttp + Android MediaStore** 构建的轻量级原生安卓 App（APK 约 6 MB），完整移植桌面版 `BiliMusic` 的核心算法与体验。

---

## 核心特性

1. **纯单曲智能过滤与打分引擎** (`Ranker.kt`)
   - 自动排除 40+ 干扰词（合集、串烧、1小时循环、鬼畜、伴奏、翻唱、DJ、AI翻唱、加速/降调等）。
   - 支持 **无损优先** 与 **播放量优先** 一键切换，自动提取 `Hi-Res` / `无损` / `高播放` / `原唱号` 徽章。
2. **原生最高音质直出（免重型 FFmpeg）**
   - 直接提取 B站 DASH 最高码率 `.m4a`（如提供 FLAC 音轨则自动保存 `.flac`）。
   - 通过 Android `MediaStore` 自动存入手机 `Music/BiliMusic/` 目录，**Android 10+ 免授存储权限**，系统音乐、网易云音乐、QQ 音乐本地扫描和车载系统均可直接播放。
3. **单曲搜选 + 批量歌单队列**
   - 支持粘贴多行 `歌名 - 歌手` 清单（自动忽略 `#` 注释行），后台协程队列顺序执行，实时进度条与日志展示，随时可停止任务。
4. **Material 3 浅色 / 深色双主题**
   - B站樱花粉 + 天空蓝配色，右上角一键切换深浅皮肤；支持配置 `SESSDATA`（选填）与同名冲突策略（自动重命名 / 跳过 / 覆盖）。

---

## 零环境 3 分钟云端打包 APK（GitHub Actions）

你的电脑**不需要安装 Android Studio 或 Android SDK**：

1. 在 GitHub 新建一个仓库（**Private 私有仓库即可**，免费版每月有 2,000 分钟 Actions 额度）。
2. 在本目录执行推送：
   ```bash
   git remote add origin https://github.com/<你的用户名>/<仓库名>.git
   git branch -M main
   git push -u origin main
   ```
3. 打开 GitHub 仓库顶部的 **Actions** 页面 → 点进最新的 `Build Android APK` 任务（约 2–3 分钟自动跑完单元测试并打包）。
4. 在任务最底部的 **Artifacts** 区域下载 `BiliMusic-Android-APK`，解压得到 `BiliMusic-Android-v1.0.0.apk`，传到安卓手机直接安装即可。

---

## 项目结构

```text
03-bili-music-android/
├── .github/workflows/build-apk.yml          # GitHub Actions 自动单测 + 打 APK
├── app/
│   ├── build.gradle.kts                     # Android + Compose + OkHttp 依赖配置
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   └── java/com/bilimusic/android/
│       │       ├── MainActivity.kt          # Material 3 手机端界面（单曲/批量/设置）
│       │       └── core/
│       │           ├── Ranker.kt            # 纯单曲过滤与多维度打分引擎
│       │           ├── BiliClient.kt        # WBI 签名、搜索与 DASH 音频流下载
│       │           ├── AudioStorage.kt      # MediaStore / 旧版存储适配
│       │           └── PrefsStore.kt        # 本地配置持久化
│       └── test/java/com/bilimusic/android/
│           └── RankerTest.kt                # JUnit 4 单元测试
├── build.gradle.kts
└── settings.gradle.kts
```
