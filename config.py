from __future__ import annotations

import json
import os
import shutil
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

DEFAULT_FFMPEG = r"D:\bilibilijj2\OrderEXE\ffmpeg.exe"
JIJIDOWN_CONFIG = r"D:\bilibilijj2\Config\Config.ini"
FALLBACK_DOWNLOAD_DIR = r"D:\桌面\歌曲\MP3\music new\歌词"


def get_app_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


def get_settings_path() -> Path:
    app_dir = get_app_dir()
    candidate = app_dir / "settings.json"
    try:
        app_dir.mkdir(parents=True, exist_ok=True)
        if candidate.exists() or os.access(app_dir, os.W_OK):
            return candidate
    except OSError:
        pass
    appdata = Path(os.environ.get("APPDATA", Path.home())) / "BiliMusicDownloader"
    appdata.mkdir(parents=True, exist_ok=True)
    return appdata / "settings.json"


def get_playlists_path() -> Path:
    return get_settings_path().parent / "playlists.json"


def get_resource_path(rel_path: str) -> Path:
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        candidate = Path(meipass) / rel_path
        if candidate.exists():
            return candidate
    return Path(__file__).resolve().parent / rel_path


def get_bundled_ffmpeg() -> str:
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        bundled = Path(meipass) / "ffmpeg.exe"
        if bundled.is_file():
            return str(bundled)
    return ""


def detect_ffmpeg() -> str:
    bundled = get_bundled_ffmpeg()
    if bundled:
        return bundled
    local_ff = get_app_dir() / "ffmpeg.exe"
    if local_ff.is_file():
        return str(local_ff)
    if Path(DEFAULT_FFMPEG).is_file():
        return DEFAULT_FFMPEG
    which_ff = shutil.which("ffmpeg")
    return which_ff or DEFAULT_FFMPEG


def detect_default_download_dir() -> str:
    cfg_path = Path(JIJIDOWN_CONFIG)
    if cfg_path.is_file():
        try:
            data = json.loads(cfg_path.read_text(encoding="utf-8-sig"))
            lab_dir = (data.get("lab_DownDir") or "").strip()
            if lab_dir:
                return lab_dir
        except Exception:
            pass
    if Path(FALLBACK_DOWNLOAD_DIR).exists():
        return FALLBACK_DOWNLOAD_DIR
    return str(Path.home() / "Music")


@dataclass
class AppSettings:
    download_dir: str = ""
    ffmpeg_path: str = ""
    rank_mode: str = "lossless_first"  # "lossless_first" | "views_first"
    conflict_policy: str = "rename"    # "rename" | "overwrite" | "skip"
    output_format: str = "original"    # "original" (M4A/FLAC) | "mp3" (320k)
    bitrate: str = "320k"
    sessdata: str = ""
    player_volume: int = 80            # 0..100
    play_mode: str = "list_loop"       # "order" | "list_loop" | "single_loop" | "shuffle"

    def ensure_defaults(self) -> "AppSettings":
        if not self.download_dir:
            self.download_dir = detect_default_download_dir()
        bundled = get_bundled_ffmpeg()
        if bundled and (not self.ffmpeg_path or not Path(self.ffmpeg_path).is_file() or self.ffmpeg_path == DEFAULT_FFMPEG):
            self.ffmpeg_path = bundled
        elif not self.ffmpeg_path or not Path(self.ffmpeg_path).is_file():
            detected = detect_ffmpeg()
            if detected:
                self.ffmpeg_path = detected
        if self.rank_mode not in ("lossless_first", "views_first"):
            self.rank_mode = "lossless_first"
        if self.conflict_policy not in ("rename", "overwrite", "skip"):
            self.conflict_policy = "rename"
        if self.output_format not in ("original", "mp3"):
            self.output_format = "original"
        if not self.bitrate:
            self.bitrate = "320k"
        self.player_volume = max(0, min(100, int(self.player_volume)))
        if self.play_mode not in ("order", "list_loop", "single_loop", "shuffle"):
            self.play_mode = "list_loop"
        return self


def load_settings() -> AppSettings:
    path = get_settings_path()
    if path.is_file():
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
            allowed = {k: v for k, v in raw.items() if k in AppSettings.__dataclass_fields__}
            return AppSettings(**allowed).ensure_defaults()
        except Exception:
            pass
    return AppSettings().ensure_defaults()


def save_settings(settings: AppSettings) -> Path:
    path = get_settings_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(asdict(settings), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return path
