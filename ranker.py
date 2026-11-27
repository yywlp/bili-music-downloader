from __future__ import annotations

import html
import math
import re
from dataclasses import dataclass, field
from typing import Iterable, List

LOSSLESS_KEYWORDS = [
    "hi-res",
    "hires",
    "无损",
    "flac",
    "母带",
    "24bit",
    "sq音质",
    "高音质",
    "超高音质",
    "杜比全景声",
]

PURE_SONG_KEYWORDS = [
    "原唱",
    "原版",
    "纯享",
    "动态歌词",
    "歌词版",
    "完整版",
    "官方",
    "mv",
]

HARD_EXCLUDE_KEYWORDS = [
    "合集",
    "歌单",
    "串烧",
    "循环",
    "1小时",
    "一小时",
    "30分钟",
    "教程",
    "教学",
    "讲解",
    "解说",
    "盘点",
    "鬼畜",
    "伴奏",
    "纯音乐",
    "无人声",
    "钢琴版",
    "吉他版",
    "古筝版",
    "二胡版",
    "笛子版",
    "翻唱",
    "cover",
    "舞蹈",
    "宅舞",
    "编舞",
    "现场",
    "live",
    "饭拍",
    "演唱会",
    "录音棚大声听",
    "百万豪装",
    "dj",
    "慢摇",
    "电音",
    "加速",
    "降调",
    "0.8x",
    "1.2x",
    "1.5x",
    "变速",
    "粤语版",
    "ai翻唱",
    "ai翻",
    "ai演唱",
    "minecraft",
    "我的世界",
]

SOFT_PENALTY_KEYWORDS = [
    "改编",
    "混剪",
    "剪辑",
    "剧情",
    "二创",
    "燃剪",
    "踩点",
    "卡点",
    "bgm",
    "戏腔版",
    "合作",
    "琵琶",
]

MUSIC_GOOD_TYPENAMES = {
    "mv",
    "音乐综合",
    "原创音乐",
    "音乐分享官",
}

MUSIC_EXCLUDE_TYPENAMES = {
    "翻唱",
    "音乐现场",
    "乐评盘点",
    "演奏",
    "音乐教学",
    "鬼畜调教",
    "音mad",
}

_HTML_TAG_RE = re.compile(r"<[^>]+>")
_NON_WORD_RE = re.compile(r"[^\w\u4e00-\u9fff]+", re.UNICODE)


@dataclass
class VideoCandidate:
    bvid: str
    title: str
    author: str
    play: int
    duration_sec: int
    duration_str: str
    aid: int = 0
    description: str = ""
    tag: str = ""
    typename: str = ""
    score: float = 0.0
    badges: List[str] = field(default_factory=list)
    excluded: bool = False
    exclude_reason: str = ""


def strip_html(text: str) -> str:
    if not text:
        return ""
    return html.unescape(_HTML_TAG_RE.sub("", text)).strip()


def normalize_for_match(text: str) -> str:
    cleaned = strip_html(text).lower()
    return _NON_WORD_RE.sub("", cleaned)


def parse_duration(duration: str | int) -> int:
    if isinstance(duration, int):
        return max(0, duration)
    s = str(duration or "").strip()
    if not s:
        return 0
    if s.isdigit():
        return int(s)
    parts = s.split(":")
    try:
        nums = [int(p) for p in parts]
    except ValueError:
        return 0
    if len(nums) == 2:
        return nums[0] * 60 + nums[1]
    if len(nums) == 3:
        return nums[0] * 3600 + nums[1] * 60 + nums[2]
    return 0


def format_duration(seconds: int) -> str:
    seconds = max(0, int(seconds))
    m, s = divmod(seconds, 60)
    h, m = divmod(m, 60)
    if h > 0:
        return f"{h:02d}:{m:02d}:{s:02d}"
    return f"{m:02d}:{s:02d}"


def format_play_count(play: int) -> str:
    if play >= 100_000_000:
        return f"{play / 100_000_000:.2f}亿"
    if play >= 10_000:
        return f"{play / 10_000:.1f}万"
    return str(play)


def _title_matches_song(title: str, song_name: str) -> bool:
    norm_song = normalize_for_match(song_name)
    if not norm_song:
        return True
    norm_title = normalize_for_match(title)
    if norm_song in norm_title:
        return True
    tokens = [normalize_for_match(t) for t in re.split(r"[\s\-_/]+", song_name) if t.strip()]
    tokens = [t for t in tokens if t]
    if len(tokens) > 1 and all(t in norm_title for t in tokens):
        return True
    return False


def rank_candidates(
    candidates: Iterable[VideoCandidate],
    song_name: str,
    artist: str = "",
    mode: str = "lossless_first",
    limit: int = 15,
    allow_fallback: bool = True,
) -> List[VideoCandidate]:
    """Filter, score, and rank Bilibili video candidates for a single song."""
    query_lower = f"{song_name} {artist}".lower()
    norm_artist = normalize_for_match(artist)

    dedup: dict[str, VideoCandidate] = {}
    for c in candidates:
        if not c.bvid:
            continue
        existing = dedup.get(c.bvid)
        if existing is None or c.play > existing.play:
            dedup[c.bvid] = c

    valid: List[VideoCandidate] = []
    excluded_list: List[VideoCandidate] = []

    for c in dedup.values():
        clean_title = strip_html(c.title)
        c.title = clean_title
        c.duration_str = format_duration(c.duration_sec)
        c.badges = []
        c.excluded = False
        c.exclude_reason = ""

        title_lower = clean_title.lower()
        meta_lower = f"{title_lower} {c.tag.lower()}"
        tname_lower = (c.typename or "").strip().lower()

        # 0. Partition check: reject non-music or cover/live partitions unless user explicitly asked
        if tname_lower:
            if tname_lower in MUSIC_EXCLUDE_TYPENAMES:
                allow_this = (
                    (tname_lower == "翻唱" and ("翻唱" in query_lower or "cover" in query_lower))
                    or (tname_lower == "音乐现场" and ("现场" in query_lower or "live" in query_lower))
                    or (tname_lower == "演奏" and any(k in query_lower for k in ("钢琴", "吉他", "演奏", "纯音乐")))
                )
                if not allow_this:
                    c.excluded = True
                    c.exclude_reason = f"分区排除:{c.typename}"
                    c.score = -600.0 + math.log10(max(c.play, 1)) * 5.0
                    excluded_list.append(c)
                    continue
            elif tname_lower not in MUSIC_GOOD_TYPENAMES:
                has_music_signal = any(
                    kw in meta_lower
                    for kw in (LOSSLESS_KEYWORDS + ["歌曲", "原唱", "纯享", "动态歌词", "歌词版", "mv", "mtv"])
                )
                if not has_music_signal:
                    c.excluded = True
                    c.exclude_reason = f"非音乐分区:{c.typename}"
                    c.score = -800.0 + math.log10(max(c.play, 1)) * 2.0
                    excluded_list.append(c)
                    continue

        # 1. Hard check: title must contain song name
        if not _title_matches_song(clean_title, song_name):
            c.excluded = True
            c.exclude_reason = "标题不含歌名"
            c.score = -1000.0
            excluded_list.append(c)
            continue

        # 2. Hard check: duration range (90s ~ 480s)
        if c.duration_sec < 90:
            c.excluded = True
            c.exclude_reason = "时长过短"
            c.score = -900.0
            excluded_list.append(c)
            continue
        if c.duration_sec > 480 and not any(k in query_lower for k in ("小时", "合集", "完整", "加长")):
            c.excluded = True
            c.exclude_reason = "时长过长(疑似合集)"
            c.score = -900.0
            excluded_list.append(c)
            continue

        # 3. Hard check: strong negative keywords (unless explicitly requested in query)
        hit_neg = None
        for kw in HARD_EXCLUDE_KEYWORDS:
            if kw in query_lower:
                continue
            if kw == "dj":
                if re.search(r"(?<![a-z])dj(?![a-z])", title_lower):
                    hit_neg = kw
                    break
            elif kw == "live":
                if re.search(r"(?<![a-z])live(?![a-z])", title_lower):
                    hit_neg = kw
                    break
            elif kw == "cover":
                if re.search(r"(?<![a-z])cover(?![a-z])", title_lower):
                    hit_neg = kw
                    break
            elif kw in title_lower:
                hit_neg = kw
                break

        if hit_neg:
            c.excluded = True
            c.exclude_reason = f"排除词:{hit_neg}"
            c.score = -500.0 + math.log10(max(c.play, 1)) * 5.0
            excluded_list.append(c)
            continue

        # 4. Positive scoring
        play_weight = 18.0 if mode == "views_first" else 12.0
        score = math.log10(max(c.play, 1)) * play_weight

        if tname_lower in MUSIC_GOOD_TYPENAMES:
            score += 10.0

        if c.play >= 500_000:
            c.badges.append("高播放")

        is_lossless = any(kw in meta_lower for kw in LOSSLESS_KEYWORDS)
        if is_lossless:
            score += 28.0 if mode == "lossless_first" else 1.5
            c.badges.append("无损/Hi-Res")

        pure_hits = [kw for kw in PURE_SONG_KEYWORDS if kw in title_lower]
        if pure_hits:
            score += min(6.0, len(pure_hits) * 2.5)
            if any(k in pure_hits for k in ("动态歌词", "歌词版", "纯享")):
                c.badges.append("歌词/纯享")
            elif "mv" in pure_hits or "官方" in pure_hits:
                c.badges.append("官方/MV")

        if 140 <= c.duration_sec <= 340:
            score += 3.0

        if norm_artist:
            norm_title = normalize_for_match(clean_title)
            norm_author = normalize_for_match(c.author)
            if norm_artist in norm_title or norm_artist in norm_author:
                score += 12.0
                if norm_author == norm_artist:
                    score += 5.0
                    if "原唱号" not in c.badges:
                        c.badges.append("原唱号")
            else:
                score -= 10.0

        soft_hits = [kw for kw in SOFT_PENALTY_KEYWORDS if kw in title_lower and kw not in query_lower]
        if soft_hits:
            score -= min(24.0, len(soft_hits) * 12.0)

        c.score = round(score, 2)
        valid.append(c)

    valid.sort(key=lambda x: (x.score, x.play), reverse=True)
    if valid:
        return valid[:limit]

    if not allow_fallback:
        return []

    # Fallback: if everything was filtered out, return best excluded items marked with reason
    excluded_list.sort(key=lambda x: (x.score, x.play), reverse=True)
    for c in excluded_list:
        c.badges = [f"兜底({c.exclude_reason})"]
    return excluded_list[:limit]
