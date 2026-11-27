from __future__ import annotations

import hashlib
import http.cookiejar
import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple

from ranker import VideoCandidate, parse_duration, strip_html

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

MIXIN_KEY_ENC_TAB = [
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
    27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
    37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
    22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
]


def _get_mixin_key(orig: str) -> str:
    return "".join(orig[i] for i in MIXIN_KEY_ENC_TAB if i < len(orig))[:32]


def _enc_wbi(params: Dict[str, str | int], img_key: str, sub_key: str) -> Dict[str, str]:
    mixin_key = _get_mixin_key(img_key + sub_key)
    curr_time = int(time.time())
    signed: Dict[str, str] = {k: str(v) for k, v in params.items()}
    signed["wts"] = str(curr_time)
    signed = dict(sorted(signed.items()))
    filtered = {
        k: "".join(ch for ch in str(v) if ch not in "!'()*")
        for k, v in signed.items()
    }
    query = urllib.parse.urlencode(filtered)
    w_rid = hashlib.md5((query + mixin_key).encode("utf-8")).hexdigest()
    filtered["w_rid"] = w_rid
    return filtered


class BiliClient:
    def __init__(self, sessdata: str = "", timeout: int = 15) -> None:
        self.timeout = timeout
        self.sessdata = sessdata.strip()
        self.cookie_jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cookie_jar)
        )
        self._initialized = False
        self._wbi_keys: Optional[Tuple[str, str]] = None
        self._lock = threading.Lock()

    def set_sessdata(self, sessdata: str) -> None:
        self.sessdata = sessdata.strip()

    def _build_headers(self, referer: str = "https://www.bilibili.com/") -> Dict[str, str]:
        headers = {
            "User-Agent": USER_AGENT,
            "Referer": referer,
            "Accept": "application/json, text/plain, */*",
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        }
        if self.sessdata:
            existing = "; ".join(f"{c.name}={c.value}" for c in self.cookie_jar)
            cookie_val = f"SESSDATA={self.sessdata}"
            headers["Cookie"] = f"{existing}; {cookie_val}" if existing else cookie_val
        return headers

    def _get_json(self, url: str, referer: str = "https://www.bilibili.com/") -> dict:
        req = urllib.request.Request(url, headers=self._build_headers(referer))
        with self.opener.open(req, timeout=self.timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            return json.loads(raw)

    def ensure_session(self) -> None:
        with self._lock:
            if self._initialized:
                return
            try:
                req = urllib.request.Request(
                    "https://www.bilibili.com/",
                    headers={"User-Agent": USER_AGENT},
                )
                with self.opener.open(req, timeout=self.timeout) as resp:
                    resp.read(4096)
            except Exception:
                pass

            try:
                spi = self._get_json("https://api.bilibili.com/x/frontend/finger/spi")
                if spi.get("code") == 0:
                    data = spi.get("data") or {}
                    for name, key in (("buvid3", "b_3"), ("buvid4", "b_4")):
                        val = data.get(key)
                        if val:
                            cookie = http.cookiejar.Cookie(
                                version=0,
                                name=name,
                                value=val,
                                port=None,
                                port_specified=False,
                                domain=".bilibili.com",
                                domain_specified=True,
                                domain_initial_dot=True,
                                path="/",
                                path_specified=True,
                                secure=False,
                                expires=None,
                                discard=True,
                                comment=None,
                                comment_url=None,
                                rest={},
                            )
                            self.cookie_jar.set_cookie(cookie)
            except Exception:
                pass

            try:
                nav = self._get_json("https://api.bilibili.com/x/web-interface/nav")
                wbi = (nav.get("data") or {}).get("wbi_img") or {}
                img_url = wbi.get("img_url") or ""
                sub_url = wbi.get("sub_url") or ""
                if img_url and sub_url:
                    img_key = img_url.rsplit("/", 1)[-1].split(".")[0]
                    sub_key = sub_url.rsplit("/", 1)[-1].split(".")[0]
                    self._wbi_keys = (img_key, sub_key)
            except Exception:
                pass

            self._initialized = True

    def _search_once(
        self,
        keyword: str,
        order: str = "click",
        page: int = 1,
        tids: Optional[int] = None,
    ) -> List[VideoCandidate]:
        self.ensure_session()
        base_params: Dict[str, str | int] = {
            "search_type": "video",
            "keyword": keyword,
            "order": order,
            "page": page,
        }
        if tids is not None:
            base_params["tids"] = tids
        data = None
        if self._wbi_keys:
            try:
                signed = _enc_wbi(base_params, self._wbi_keys[0], self._wbi_keys[1])
                url = "https://api.bilibili.com/x/web-interface/wbi/search/type?" + urllib.parse.urlencode(signed)
                resp = self._get_json(url, referer="https://search.bilibili.com/")
                if resp.get("code") == 0:
                    data = resp.get("data") or {}
            except Exception:
                data = None

        if data is None:
            url = "https://api.bilibili.com/x/web-interface/search/type?" + urllib.parse.urlencode(base_params)
            resp = self._get_json(url, referer="https://search.bilibili.com/")
            if resp.get("code") != 0:
                raise RuntimeError(f"B站搜索接口返回异常: code={resp.get('code')} msg={resp.get('message')}")
            data = resp.get("data") or {}

        items = data.get("result") or []
        out: List[VideoCandidate] = []
        for item in items:
            if not isinstance(item, dict):
                continue
            bvid = item.get("bvid") or ""
            if not bvid:
                continue
            dur_str = str(item.get("duration") or "0:00")
            dur_sec = parse_duration(dur_str)
            try:
                play = int(item.get("play") or 0)
            except (ValueError, TypeError):
                play = 0
            out.append(
                VideoCandidate(
                    bvid=bvid,
                    aid=int(item.get("aid") or 0),
                    title=strip_html(str(item.get("title") or "")),
                    author=strip_html(str(item.get("author") or "")),
                    play=play,
                    duration_sec=dur_sec,
                    duration_str=dur_str,
                    description=strip_html(str(item.get("description") or "")),
                    tag=str(item.get("tag") or ""),
                    typename=str(item.get("typename") or ""),
                )
            )
        return out

    def search_song_candidates(
        self,
        song_name: str,
        artist: str = "",
        lossless_first: bool = True,
    ) -> List[VideoCandidate]:
        song_name = song_name.strip()
        artist = artist.strip()
        if not song_name:
            return []

        primary_kw = f"{song_name} {artist}".strip()
        results: List[VideoCandidate] = []

        # 1. Music partition (tids=3) sorted by most views & default relevance
        for order in ("click", "totalrank"):
            try:
                results.extend(self._search_once(primary_kw, order=order, page=1, tids=3))
            except Exception:
                pass

        # 2. Lossless-specific relevance search (catches Hi-Res uploads even for common idiom titles)
        if lossless_first:
            lossless_kw = f"{song_name} {artist} 无损".strip() if artist else f"{song_name} 无损"
            try:
                results.extend(self._search_once(lossless_kw, order="totalrank", page=1))
            except Exception:
                pass

        # 3. Fallback to site-wide relevance search only if music partition returned nothing
        if not results:
            results.extend(self._search_once(primary_kw, order="totalrank", page=1))
        return results

    def get_video_info(self, bvid: str) -> dict:
        self.ensure_session()
        url = f"https://api.bilibili.com/x/web-interface/view?bvid={urllib.parse.quote(bvid)}"
        resp = self._get_json(url, referer=f"https://www.bilibili.com/video/{bvid}/")
        if resp.get("code") != 0:
            raise RuntimeError(f"获取视频信息失败: {resp.get('message')}")
        data = resp.get("data") or {}
        return {
            "bvid": data.get("bvid", bvid),
            "aid": int(data.get("aid") or 0),
            "cid": int(data.get("cid") or 0),
            "title": str(data.get("title") or ""),
            "author": str((data.get("owner") or {}).get("name") or ""),
            "duration": int(data.get("duration") or 0),
        }

    def get_best_audio_stream(self, bvid: str, cid: int) -> dict:
        self.ensure_session()
        url = (
            f"https://api.bilibili.com/x/player/playurl?"
            f"bvid={urllib.parse.quote(bvid)}&cid={cid}&fnval=4048&fnver=0&fourk=1"
        )
        resp = self._get_json(url, referer=f"https://www.bilibili.com/video/{bvid}/")
        if resp.get("code") != 0:
            raise RuntimeError(f"获取音频流地址失败: {resp.get('message')}")
        dash = (resp.get("data") or {}).get("dash") or {}

        flac = dash.get("flac") or {}
        flac_audio = flac.get("audio")
        if isinstance(flac_audio, dict) and (flac_audio.get("baseUrl") or flac_audio.get("base_url")):
            return {
                "id": flac_audio.get("id", 30251),
                "bandwidth": int(flac_audio.get("bandwidth") or 0),
                "codecs": str(flac_audio.get("codecs") or "flac"),
                "url": flac_audio.get("baseUrl") or flac_audio.get("base_url"),
                "backup_urls": flac_audio.get("backupUrl") or flac_audio.get("backup_url") or [],
                "is_flac": True,
            }

        audios = dash.get("audio") or []
        if not audios:
            raise RuntimeError("未找到可用的 DASH 音频流")

        best = max(audios, key=lambda a: int(a.get("bandwidth") or 0))
        return {
            "id": best.get("id", 0),
            "bandwidth": int(best.get("bandwidth") or 0),
            "codecs": str(best.get("codecs") or ""),
            "url": best.get("baseUrl") or best.get("base_url"),
            "backup_urls": best.get("backupUrl") or best.get("backup_url") or [],
            "is_flac": False,
        }

    def download_stream(
        self,
        stream_info: dict,
        bvid: str,
        dest_path: Path,
        progress_cb: Optional[Callable[[int, int], None]] = None,
        cancel_event: Optional[threading.Event] = None,
    ) -> Path:
        urls = [stream_info["url"]] + list(stream_info.get("backup_urls") or [])
        urls = [u for u in urls if u]
        dest_path.parent.mkdir(parents=True, exist_ok=True)
        referer = f"https://www.bilibili.com/video/{bvid}/"

        last_err: Optional[Exception] = None
        for idx, url in enumerate(urls):
            try:
                req = urllib.request.Request(
                    url,
                    headers={
                        "User-Agent": USER_AGENT,
                        "Referer": referer,
                    },
                )
                with self.opener.open(req, timeout=self.timeout * 2) as resp:
                    total = int(resp.headers.get("Content-Length") or 0)
                    downloaded = 0
                    with open(dest_path, "wb") as f:
                        while True:
                            if cancel_event and cancel_event.is_set():
                                raise InterruptedError("用户已取消下载")
                            chunk = resp.read(65536)
                            if not chunk:
                                break
                            f.write(chunk)
                            downloaded += len(chunk)
                            if progress_cb:
                                progress_cb(downloaded, total)
                return dest_path
            except InterruptedError:
                if dest_path.exists():
                    dest_path.unlink(missing_ok=True)
                raise
            except Exception as exc:
                last_err = exc
                if idx == len(urls) - 1:
                    break
        raise RuntimeError(f"下载音频流失败: {last_err}")
