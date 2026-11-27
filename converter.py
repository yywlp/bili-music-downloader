from __future__ import annotations

import os
import re
import subprocess
from pathlib import Path
from typing import Tuple

_INVALID_FILENAME_RE = re.compile(r'[\\/:*?"<>|\x00-\x1f]')


def sanitize_filename(name: str) -> str:
    cleaned = _INVALID_FILENAME_RE.sub("_", (name or "").strip())
    cleaned = re.sub(r"\s+", " ", cleaned).strip(" .")
    return cleaned or "untitled"


def truncate_utf8_stem(stem: str, ext: str = ".mp3", max_bytes: int = 200) -> str:
    """Ensure (stem + ext) encoded in UTF-8 never exceeds max_bytes (Android limit is 255)."""
    ext_bytes = len(ext.encode("utf-8"))
    budget = max(16, max_bytes - ext_bytes)
    out_chars = []
    used = 0
    for ch in stem:
        b = len(ch.encode("utf-8"))
        if used + b > budget:
            break
        out_chars.append(ch)
        used += b
    result = "".join(out_chars).strip(" .")
    return result or "song"


def resolve_output_path(
    out_dir: str | Path,
    song_name: str,
    conflict_policy: str = "rename",
    ext: str = ".mp3",
) -> Tuple[Path, bool]:
    """
    Resolve final MP3 path.
    Returns (target_path, skipped).
    """
    directory = Path(out_dir)
    directory.mkdir(parents=True, exist_ok=True)

    base_stem = truncate_utf8_stem(sanitize_filename(song_name), ext=ext, max_bytes=200)
    candidate = directory / f"{base_stem}{ext}"

    if not candidate.exists():
        return candidate, False

    if conflict_policy == "overwrite":
        return candidate, False
    if conflict_policy == "skip":
        return candidate, True

    # Default: "rename" -> 歌曲名 (2).mp3, 歌曲名 (3).mp3 ...
    index = 2
    while True:
        suffix = f" ({index})"
        trimmed = truncate_utf8_stem(base_stem, ext=f"{suffix}{ext}", max_bytes=200)
        numbered = directory / f"{trimmed}{suffix}{ext}"
        if not numbered.exists():
            return numbered, False
        index += 1


def _subprocess_kwargs() -> dict:
    kwargs: dict = {}
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW
    return kwargs


def convert_to_mp3(
    ffmpeg_path: str | Path,
    input_path: str | Path,
    output_path: str | Path,
    title: str = "",
    artist: str = "",
    bitrate: str = "320k",
) -> Tuple[bool, str]:
    ff = Path(ffmpeg_path)
    if not ff.is_file():
        return False, f"未找到 ffmpeg.exe: {ff}"

    cmd = [
        str(ff),
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        str(input_path),
        "-vn",
        "-c:a",
        "libmp3lame",
        "-b:a",
        bitrate,
    ]
    if title:
        cmd += ["-metadata", f"title={title}"]
    if artist:
        cmd += ["-metadata", f"artist={artist}"]
    cmd.append(str(output_path))

    proc = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        **_subprocess_kwargs(),
    )
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout or "").strip()
        return False, f"ffmpeg 转码失败 (code={proc.returncode}): {err}"

    out = Path(output_path)
    if not out.is_file() or out.stat().st_size == 0:
        return False, "转码完成但输出 MP3 文件为空"
    return True, "OK"


def probe_mp3_summary(ffmpeg_path: str | Path, mp3_path: str | Path) -> str:
    ff = Path(ffmpeg_path)
    if not ff.is_file():
        return ""
    proc = subprocess.run(
        [str(ff), "-hide_banner", "-i", str(mp3_path)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        **_subprocess_kwargs(),
    )
    text = (proc.stderr or "") + "\n" + (proc.stdout or "")
    dur_m = re.search(r"Duration:\s*([0-9:.]+).*?bitrate:\s*([0-9]+\s*kb/s)", text)
    if dur_m:
        return f"时长 {dur_m.group(1)} · {dur_m.group(2)}"
    return ""
