# BiliMusic · Bilibili 纯单曲 MP3 自动筛选与下载器

<p align="center">
  <img src="assets/app.png" alt="BiliMusic Icon" width="112" />
</p>

一个轻量级 Windows 桌面工具，只需输入**歌曲名**（支持单首搜选或粘贴批量歌单），即可自动在 Bilibili 搜索、筛选高播放量且标注无损/Hi-Res 的纯单曲视频，拉取最高可用音频流并通过 `ffmpeg` 转为 **320 kbps MP3**，自动写入 ID3 曲名/歌手元数据，并直接命名为 **`歌曲名.mp3`**（规避超长文件名导致安卓 QQ 传输失败的问题）。

## 核心特性

- **智能单曲筛选打分**：
  - 自动剔除无关热梗视频、超长歌单/合集、DJ 魔改、翻唱、舞蹈、教学解说、录音棚外录等噪声；
  - 支持 **无损优先** 与 **播放量优先** 两种排序模式一键切换；
  - 展示筛选后的 Top 候选（推荐标签、标题、UP主、播放量、时长、BV号），支持双击下载或右键在浏览器核对原视频。
- **单曲一键下载 + 批量歌单排队**：
  - 支持「一键搜并下载第 1 名」；
  - 批量模式支持每行输入 `歌名` 或 `歌名 - 歌手`，自动排队下载并实时输出日志。
- **规范安全命名**：
  - 默认直接命名为 `歌曲名.mp3`，清洗非法字符并严格控制 UTF-8 字节长度 ≤ 200 字节；
  - 支持重名 **自动编号**（默认）、**直接覆盖**、**跳过** 三种策略。
- **现代桌面 UI**：
  - 基于 `CustomTkinter` 打造，支持 **浅色 / 深色模式** 一键切换；
  - 支持打包为内置 `ffmpeg.exe` 的 **100% 独立绿色单文件 `.exe`**。

## 快速运行（源码方式）

要求 Python 3.10+ 及可用的 `ffmpeg`：

```powershell
pip install -r requirements.txt
python app.py
```

## 打包为独立单文件 `.exe`

在 Windows PowerShell 中运行：

```powershell
.\build_exe.ps1
```

构建完成后会在 `dist\BiliMusicDownloader.exe` 生成独立单文件可执行程序。

## 运行单元测试

```powershell
python -m unittest discover -s tests -v
```

## 项目结构

```text
bili-music-downloader/
├── app.py                # CustomTkinter 桌面主界面
├── bili_client.py        # B站会话初始化、WBI 签名搜索与音频流下载
├── ranker.py             # 纯单曲筛选与打分引擎
├── converter.py          # ffmpeg 转码、ID3 元数据写入与安全命名
├── config.py             # 配置管理与资源路径探测
├── assets/               # 应用图标 (app.png / app.ico)
├── tests/                # 单元测试
├── build_exe.ps1         # PyInstaller 单文件打包脚本
├── requirements.txt      # Python 依赖
└── 启动音乐下载器.bat       # 源码无黑框快速启动脚本
```

## 免责声明

本工具仅用于个人学习与离线音频整理，请尊重音乐版权与创作者权益，勿用于任何商业用途。
