from __future__ import annotations

import json
import re
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Callable, List, Optional, Tuple

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

_HEADERS = {
    "User-Agent": USER_AGENT,
    "Referer": "https://music.163.com/",
    "Accept": "application/json, text/plain, */*",
}

_SHORT_URL_RE = re.compile(r"https?://(?:163cn\.tv|y\.music\.163\.com)/\S+", re.IGNORECASE)
_PLAYLIST_ID_RE = re.compile(r"(?:playlist\?[^#\s]*?id=|playlist/|id=)(\d{5,})", re.IGNORECASE)


@dataclass
class PlaylistTrack:
    song_id: int
    name: str
    artist: str


@dataclass
class PlaylistResult:
    playlist_id: str
    name: str
    tracks: List[PlaylistTrack]


def _resolve_short_url(url: str, timeout: int = 10) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.geturl()


def extract_netease_playlist_id(text: str, timeout: int = 10) -> Optional[str]:
    raw = (text or "").strip()
    if not raw:
        return None
    if raw.isdigit() and len(raw) >= 5:
        return raw

    m = _PLAYLIST_ID_RE.search(raw)
    if m:
        return m.group(1)

    short_m = _SHORT_URL_RE.search(raw)
    if short_m:
        short_url = short_m.group(0).rstrip(".,，。!！)）]】")
        try:
            final_url = _resolve_short_url(short_url, timeout=timeout)
            m2 = _PLAYLIST_ID_RE.search(final_url)
            if m2:
                return m2.group(1)
        except Exception:
            pass
    return None


def _parse_track_obj(item: dict) -> Optional[PlaylistTrack]:
    name = str(item.get("name") or "").strip()
    if not name:
        return None
    artists = item.get("ar") or item.get("artists") or []
    artist_names = [str(a.get("name") or "").strip() for a in artists if isinstance(a, dict) and a.get("name")]
    artist = " / ".join(artist_names[:2])
    return PlaylistTrack(
        song_id=int(item.get("id") or 0),
        name=name,
        artist=artist,
    )


def fetch_netease_playlist(
    text_or_url: str,
    progress_cb: Optional[Callable[[int, int], None]] = None,
    timeout: int = 15,
) -> PlaylistResult:
    pid = extract_netease_playlist_id(text_or_url, timeout=timeout)
    if not pid:
        raise ValueError("未能从输入中识别网易云歌单链接或 ID，请粘贴包含 playlist?id=... 的链接或分享文案。")

    detail_url = f"https://music.163.com/api/v6/playlist/detail?id={pid}&n=1000"
    req = urllib.request.Request(detail_url, headers=_HEADERS)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        payload = json.loads(resp.read().decode("utf-8", errors="replace"))

    if payload.get("code") != 200 or not payload.get("playlist"):
        raise RuntimeError(f"网易云歌单接口返回异常: code={payload.get('code')} msg={payload.get('message')}")

    pl = payload["playlist"]
    pl_name = str(pl.get("name") or f"网易云歌单_{pid}").strip()

    track_ids = [int(t["id"]) for t in (pl.get("trackIds") or []) if isinstance(t, dict) and t.get("id")]
    initial_tracks = pl.get("tracks") or []

    by_id: dict[int, PlaylistTrack] = {}
    for item in initial_tracks:
        parsed = _parse_track_obj(item)
        if parsed and parsed.song_id:
            by_id[parsed.song_id] = parsed

    if not track_ids:
        tracks = [t for item in initial_tracks if (t := _parse_track_obj(item))]
        return PlaylistResult(playlist_id=pid, name=pl_name, tracks=tracks)

    missing_ids = [sid for sid in track_ids if sid not in by_id]
    total = len(track_ids)
    resolved_so_far = len(by_id)
    if progress_cb:
        progress_cb(resolved_so_far, total)

    batch_size = 200
    for i in range(0, len(missing_ids), batch_size):
        chunk = missing_ids[i : i + batch_size]
        c_param = json.dumps([{"id": sid} for sid in chunk], separators=(",", ":"))
        form = urllib.parse.urlencode({"c": c_param}).encode("utf-8")
        req_batch = urllib.request.Request(
            "https://music.163.com/api/v3/song/detail",
            data=form,
            headers=_HEADERS,
        )
        with urllib.request.urlopen(req_batch, timeout=timeout) as resp:
            batch_data = json.loads(resp.read().decode("utf-8", errors="replace"))
        for item in batch_data.get("songs") or []:
            parsed = _parse_track_obj(item)
            if parsed and parsed.song_id:
                by_id[parsed.song_id] = parsed
        resolved_so_far = len(by_id)
        if progress_cb:
            progress_cb(min(resolved_so_far, total), total)

    ordered: List[PlaylistTrack] = [by_id[sid] for sid in track_ids if sid in by_id]
    return PlaylistResult(playlist_id=pid, name=pl_name, tracks=ordered)
