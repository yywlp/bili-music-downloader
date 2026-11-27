package com.bilimusic.android.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

enum class RankMode(val label: String) {
    LOSSLESS_FIRST("无损优先"),
    VIEWS_FIRST("播放量优先"),
}

enum class ConflictPolicy(val label: String) {
    RENAME("自动重命名"),
    SKIP("跳过已存在"),
    OVERWRITE("覆盖同名"),
}

data class VideoCandidate(
    val bvid: String,
    var title: String,
    val author: String,
    val play: Long,
    val durationSec: Int,
    var durationStr: String,
    val aid: Long = 0L,
    val description: String = "",
    val tag: String = "",
    var score: Double = 0.0,
    var badges: List<String> = emptyList(),
    var excluded: Boolean = false,
    var excludeReason: String = "",
)

object Ranker {
    val LOSSLESS_KEYWORDS = listOf(
        "hi-res", "hires", "无损", "flac", "母带", "24bit",
        "sq音质", "高音质", "超高音质", "杜比全景声",
    )

    val PURE_SONG_KEYWORDS = listOf(
        "原唱", "原版", "纯享", "动态歌词", "歌词版", "完整版", "官方", "mv",
    )

    val HARD_EXCLUDE_KEYWORDS = listOf(
        "合集", "歌单", "串烧", "循环", "1小时", "一小时", "30分钟",
        "教程", "教学", "讲解", "解说", "盘点", "鬼畜",
        "伴奏", "纯音乐", "无人声", "钢琴版", "吉他版", "古筝版", "二胡版", "笛子版",
        "翻唱", "cover", "舞蹈", "宅舞", "编舞",
        "现场", "live", "饭拍", "演唱会", "录音棚大声听", "百万豪装",
        "dj", "慢摇", "电音", "加速", "降调", "0.8x", "1.2x", "1.5x", "变速",
        "粤语版", "ai翻唱", "ai翻", "ai演唱", "minecraft", "我的世界",
    )

    val SOFT_PENALTY_KEYWORDS = listOf(
        "改编", "混剪", "剪辑", "剧情", "二创", "燃剪",
        "踩点", "卡点", "bgm", "戏腔版", "合作", "琵琶",
    )

    private val HTML_TAG_RE = Regex("<[^>]+>")
    private val NON_WORD_RE = Regex("[^\\p{L}\\p{N}]+")
    private val INVALID_FILENAME_RE = Regex("[\\\\/:*?\"<>|\\x00-\\x1f]")
    private val DJ_RE = Regex("(?<![a-z])dj(?![a-z])")
    private val LIVE_RE = Regex("(?<![a-z])live(?![a-z])")
    private val COVER_RE = Regex("(?<![a-z])cover(?![a-z])")

    fun stripHtml(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val noTags = HTML_TAG_RE.replace(text, "")
        return noTags
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()
    }

    fun normalizeForMatch(text: String?): String {
        return NON_WORD_RE.replace(stripHtml(text).lowercase(), "")
    }

    fun parseDuration(duration: String?): Int {
        val s = duration?.trim().orEmpty()
        if (s.isEmpty()) return 0
        s.toIntOrNull()?.let { return max(0, it) }
        val parts = s.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    fun formatDuration(seconds: Int): String {
        val safe = max(0, seconds)
        val h = safe / 3600
        val m = (safe % 3600) / 60
        val s = safe % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun formatPlayCount(play: Long): String {
        return when {
            play >= 100_000_000L -> "%.2f亿".format(play / 100_000_000.0)
            play >= 10_000L -> "%.1f万".format(play / 10_000.0)
            else -> play.toString()
        }
    }

    fun sanitizeFilename(name: String): String {
        val cleaned = INVALID_FILENAME_RE.replace(name.trim(), "_")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.')
        return cleaned.ifEmpty { "untitled" }
    }

    fun parseBatchLine(line: String): Pair<String, String>? {
        val text = line.trim()
        if (text.isEmpty() || text.startsWith("#")) return null
        val seps = listOf(" - ", " — ", "—", "｜", "|", "\t")
        for (sep in seps) {
            val idx = text.indexOf(sep)
            if (idx > 0) {
                val left = text.substring(0, idx).trim()
                val right = text.substring(idx + sep.length).trim()
                if (left.isNotEmpty()) return left to right
            }
        }
        return text to ""
    }

    private fun titleMatchesSong(title: String, songName: String): Boolean {
        val normSong = normalizeForMatch(songName)
        if (normSong.isEmpty()) return true
        val normTitle = normalizeForMatch(title)
        if (normSong in normTitle) return true
        val tokens = songName.split(Regex("[\\s\\-_/]+"))
            .map { normalizeForMatch(it) }
            .filter { it.isNotEmpty() }
        return tokens.size > 1 && tokens.all { it in normTitle }
    }

    fun rankCandidates(
        candidates: Iterable<VideoCandidate>,
        songName: String,
        artist: String = "",
        mode: RankMode = RankMode.LOSSLESS_FIRST,
        limit: Int = 15,
    ): List<VideoCandidate> {
        val queryLower = "$songName $artist".lowercase()
        val normArtist = normalizeForMatch(artist)

        val dedup = LinkedHashMap<String, VideoCandidate>()
        for (c in candidates) {
            if (c.bvid.isBlank()) continue
            val existing = dedup[c.bvid]
            if (existing == null || c.play > existing.play) {
                dedup[c.bvid] = c.copy()
            }
        }

        val valid = mutableListOf<VideoCandidate>()
        val excludedList = mutableListOf<VideoCandidate>()

        for (c in dedup.values) {
            val cleanTitle = stripHtml(c.title)
            c.title = cleanTitle
            c.durationStr = formatDuration(c.durationSec)
            val badges = mutableListOf<String>()
            c.excluded = false
            c.excludeReason = ""

            val titleLower = cleanTitle.lowercase()
            val metaLower = "$titleLower ${c.tag.lowercase()}"

            if (!titleMatchesSong(cleanTitle, songName)) {
                c.excluded = true
                c.excludeReason = "标题不含歌名"
                c.score = -1000.0
                excludedList += c
                continue
            }

            if (c.durationSec < 90) {
                c.excluded = true
                c.excludeReason = "时长过短"
                c.score = -900.0
                excludedList += c
                continue
            }

            if (c.durationSec > 480 && listOf("小时", "合集", "完整", "加长").none { it in queryLower }) {
                c.excluded = true
                c.excludeReason = "时长过长(疑似合集)"
                c.score = -900.0
                excludedList += c
                continue
            }

            var hitNeg: String? = null
            for (kw in HARD_EXCLUDE_KEYWORDS) {
                if (kw in queryLower) continue
                val matched = when (kw) {
                    "dj" -> DJ_RE.containsMatchIn(titleLower)
                    "live" -> LIVE_RE.containsMatchIn(titleLower)
                    "cover" -> COVER_RE.containsMatchIn(titleLower)
                    else -> kw in titleLower
                }
                if (matched) {
                    hitNeg = kw
                    break
                }
            }

            if (hitNeg != null) {
                c.excluded = true
                c.excludeReason = "排除词:$hitNeg"
                c.score = -500.0 + log10(max(c.play, 1L).toDouble()) * 5.0
                excludedList += c
                continue
            }

            val playWeight = if (mode == RankMode.VIEWS_FIRST) 18.0 else 12.0
            var score = log10(max(c.play, 1L).toDouble()) * playWeight

            if (c.play >= 500_000L) badges += "高播放"

            val isLossless = LOSSLESS_KEYWORDS.any { it in metaLower }
            if (isLossless) {
                score += if (mode == RankMode.LOSSLESS_FIRST) 28.0 else 1.5
                badges += "无损/Hi-Res"
            }

            val pureHits = PURE_SONG_KEYWORDS.filter { it in titleLower }
            if (pureHits.isNotEmpty()) {
                score += min(6.0, pureHits.size * 2.5)
                if (pureHits.any { it in listOf("动态歌词", "歌词版", "纯享") }) {
                    badges += "歌词/纯享"
                } else if ("mv" in pureHits || "官方" in pureHits) {
                    badges += "官方/MV"
                }
            }

            if (c.durationSec in 140..340) score += 3.0

            if (normArtist.isNotEmpty()) {
                val normTitle = normalizeForMatch(cleanTitle)
                val normAuthor = normalizeForMatch(c.author)
                if (normArtist in normTitle || normArtist in normAuthor) {
                    score += 12.0
                    if (normAuthor == normArtist) {
                        score += 5.0
                        if ("原唱号" !in badges) badges += "原唱号"
                    }
                } else {
                    score -= 10.0
                }
            }

            val softHits = SOFT_PENALTY_KEYWORDS.filter { it in titleLower && it !in queryLower }
            if (softHits.isNotEmpty()) {
                score -= min(24.0, softHits.size * 12.0)
            }

            c.score = round(score * 100.0) / 100.0
            c.badges = badges
            valid += c
        }

        valid.sortWith(compareByDescending<VideoCandidate> { it.score }.thenByDescending { it.play })
        if (valid.isNotEmpty()) return valid.take(limit)

        excludedList.sortWith(compareByDescending<VideoCandidate> { it.score }.thenByDescending { it.play })
        excludedList.forEach { it.badges = listOf("兜底(${it.excludeReason})") }
        return excludedList.take(limit)
    }
}
