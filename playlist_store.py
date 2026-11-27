from __future__ import annotations

import ctypes
import ctypes.wintypes
import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple

AUDIO_EXTENSIONS = {".m4a", ".mp3", ".flac", ".wav"}


def move_to_recycle_bin(file_path: str | Path) -> Tuple[bool, str]:
    """Safely move a file to Windows Recycle Bin using SHFileOperationW (fallback to unlink on non-Windows)."""
    p = Path(file_path)
    if not p.exists():
        return True, "文件已不存在"

    if os.name != "nt":
        try:
            p.unlink()
            return True, "OK"
        except Exception as exc:
            return False, str(exc)

    class SHFILEOPSTRUCTW(ctypes.Structure):
        _fields_ = [
            ("hwnd", ctypes.wintypes.HWND),
            ("wFunc", ctypes.wintypes.UINT),
            ("pFrom", ctypes.wintypes.LPCWSTR),
            ("pTo", ctypes.wintypes.LPCWSTR),
            ("fFlags", ctypes.c_ushort),
            ("fAnyOperationsAborted", ctypes.wintypes.BOOL),
            ("hNameMappings", ctypes.c_void_p),
            ("lpszProgressTitle", ctypes.wintypes.LPCWSTR),
        ]

    fo_delete = 0x0003
    fof_silent = 0x0004
    fof_noconfirmation = 0x0010
    fof_allowundo = 0x0040
    fof_noerrorui = 0x0400

    op = SHFILEOPSTRUCTW()
    op.wFunc = fo_delete
    # Double-null-terminated path list required by SHFileOperationW
    op.pFrom = str(p.resolve()) + "\0\0"
    op.fFlags = fof_allowundo | fof_noconfirmation | fof_silent | fof_noerrorui

    res = ctypes.windll.shell32.SHFileOperationW(ctypes.byref(op))
    if res == 0 and not p.exists():
        return True, "OK"
    return False, f"移入回收站失败 (code={res})"


@dataclass
class LocalTrack:
    path: str
    name: str
    folder: str
    ext: str
    size_bytes: int
    mtime: float


def scan_local_tracks(root_dir: str | Path) -> List[LocalTrack]:
    root = Path(root_dir)
    if not root.exists():
        return []
    tracks: List[LocalTrack] = []
    for p in root.rglob("*"):
        if not p.is_file():
            continue
        ext = p.suffix.lower()
        if ext not in AUDIO_EXTENSIONS:
            continue
        try:
            st = p.stat()
        except OSError:
            continue
        rel_parent = p.parent.relative_to(root)
        folder = "默认目录" if str(rel_parent) == "." else str(rel_parent)
        tracks.append(
            LocalTrack(
                path=str(p.resolve()),
                name=p.stem,
                folder=folder,
                ext=ext.lstrip(".").upper(),
                size_bytes=st.st_size,
                mtime=st.st_mtime,
            )
        )
    tracks.sort(key=lambda t: t.mtime, reverse=True)
    return tracks


def list_track_folders(tracks: List[LocalTrack]) -> List[str]:
    seen = set()
    folders: List[str] = []
    for t in tracks:
        if t.folder not in seen:
            seen.add(t.folder)
            folders.append(t.folder)
    folders.sort(key=lambda f: (0 if f == "默认目录" else 1, f))
    return folders


def format_size(size_bytes: int) -> str:
    if size_bytes >= 1024 * 1024:
        return f"{size_bytes / (1024 * 1024):.2f} MB"
    return f"{size_bytes / 1024:.0f} KB"


class PlaylistStore:
    def __init__(self, store_path: str | Path) -> None:
        self.store_path = Path(store_path)
        self._playlists: Dict[str, List[str]] = {}
        self.load()

    def load(self) -> None:
        self._playlists = {}
        if not self.store_path.is_file():
            return
        try:
            data = json.loads(self.store_path.read_text(encoding="utf-8"))
            for item in data.get("playlists") or []:
                name = str(item.get("name") or "").strip()
                tracks = [str(p) for p in (item.get("tracks") or []) if p]
                if name:
                    self._playlists[name] = tracks
        except Exception:
            self._playlists = {}

    def save(self) -> None:
        self.store_path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "playlists": [
                {"name": name, "tracks": paths}
                for name, paths in self._playlists.items()
            ]
        }
        self.store_path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    def list_playlists(self) -> List[str]:
        return list(self._playlists.keys())

    def create_playlist(self, name: str) -> bool:
        clean = name.strip()
        if not clean or clean in self._playlists:
            return False
        self._playlists[clean] = []
        self.save()
        return True

    def rename_playlist(self, old_name: str, new_name: str) -> bool:
        clean = new_name.strip()
        if not clean or old_name not in self._playlists or clean in self._playlists:
            return False
        rebuilt: Dict[str, List[str]] = {}
        for k, v in self._playlists.items():
            rebuilt[clean if k == old_name else k] = v
        self._playlists = rebuilt
        self.save()
        return True

    def delete_playlist(self, name: str) -> bool:
        if name not in self._playlists:
            return False
        del self._playlists[name]
        self.save()
        return True

    def add_tracks(self, playlist_name: str, track_paths: List[str]) -> int:
        if playlist_name not in self._playlists:
            return 0
        existing = self._playlists[playlist_name]
        existing_set = {str(Path(p).resolve()).lower() for p in existing}
        added = 0
        for p in track_paths:
            norm = str(Path(p).resolve())
            if norm.lower() not in existing_set:
                existing.append(norm)
                existing_set.add(norm.lower())
                added += 1
        if added > 0:
            self.save()
        return added

    def remove_tracks(self, playlist_name: str, track_paths: List[str]) -> int:
        if playlist_name not in self._playlists:
            return 0
        remove_set = {str(Path(p).resolve()).lower() for p in track_paths}
        old = self._playlists[playlist_name]
        kept = [p for p in old if str(Path(p).resolve()).lower() not in remove_set]
        removed = len(old) - len(kept)
        if removed > 0:
            self._playlists[playlist_name] = kept
            self.save()
        return removed

    def remove_tracks_from_all(self, track_paths: List[str]) -> int:
        remove_set = {str(Path(p).resolve()).lower() for p in track_paths}
        total_removed = 0
        for name, old in list(self._playlists.items()):
            kept = [p for p in old if str(Path(p).resolve()).lower() not in remove_set]
            diff = len(old) - len(kept)
            if diff > 0:
                self._playlists[name] = kept
                total_removed += diff
        if total_removed > 0:
            self.save()
        return total_removed

    def get_tracks(self, playlist_name: str, existing_only: bool = True) -> List[LocalTrack]:
        raw_paths = self._playlists.get(playlist_name) or []
        out: List[LocalTrack] = []
        for raw in raw_paths:
            p = Path(raw)
            if existing_only and not p.is_file():
                continue
            try:
                st = p.stat()
                size_b = st.st_size
                mtime = st.st_mtime
            except OSError:
                size_b = 0
                mtime = 0.0
            out.append(
                LocalTrack(
                    path=str(p.resolve()),
                    name=p.stem,
                    folder=p.parent.name or "本地",
                    ext=p.suffix.lstrip(".").upper(),
                    size_bytes=size_b,
                    mtime=mtime,
                )
            )
        return out
