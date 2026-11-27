package com.bilimusic.android.core

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class NeteaseTrack(
    val songId: Long,
    val name: String,
    val artist: String,
)

data class NeteasePlaylistResult(
    val playlistId: String,
    val name: String,
    val tracks: List<NeteaseTrack>,
)

object NeteaseImporter {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val SHORT_URL_RE = Regex("https?://(?:163cn\\.tv|y\\.music\\.163\\.com)/\\S+", RegexOption.IGNORE_CASE)
    private val PLAYLIST_ID_RE = Regex("(?:playlist\\?[^#\\s]*?id=|playlist/|id=)(\\d{5,})", RegexOption.IGNORE_CASE)

    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun extractPlaylistId(text: String): String? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        if (raw.all { it.isDigit() } && raw.length >= 5) return raw

        PLAYLIST_ID_RE.find(raw)?.groupValues?.getOrNull(1)?.let { return it }

        val shortMatch = SHORT_URL_RE.find(raw)?.value?.trimEnd('.', ',', '，', '。', '!', '！', ')', '）', ']', '】')
        if (!shortMatch.isNullOrBlank()) {
            runCatching {
                val req = Request.Builder().url(shortMatch).header("User-Agent", USER_AGENT).build()
                http.newCall(req).execute().use { resp ->
                    val finalUrl = resp.request.url.toString()
                    PLAYLIST_ID_RE.find(finalUrl)?.groupValues?.getOrNull(1)?.let { return it }
                }
            }
        }
        return null
    }

    private fun parseTrack(obj: JSONObject): NeteaseTrack? {
        val name = obj.optString("name").trim()
        if (name.isEmpty()) return null
        val ar = obj.optJSONArray("ar") ?: obj.optJSONArray("artists")
        val artists = mutableListOf<String>()
        if (ar != null) {
            for (i in 0 until minOf(2, ar.length())) {
                val aName = ar.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                if (aName.isNotEmpty()) artists += aName
            }
        }
        return NeteaseTrack(
            songId = obj.optLong("id", 0L),
            name = name,
            artist = artists.joinToString(" / "),
        )
    }

    fun fetchPlaylist(
        textOrUrl: String,
        onProgress: ((resolved: Int, total: Int) -> Unit)? = null,
    ): NeteasePlaylistResult {
        val pid = extractPlaylistId(textOrUrl)
            ?: throw IllegalArgumentException("未识别到网易云歌单链接或 ID，请粘贴分享文案、链接或歌单 ID")

        val detailReq = Request.Builder()
            .url("https://music.163.com/api/v6/playlist/detail?id=$pid&n=1000")
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://music.163.com/")
            .build()

        val payload = http.newCall(detailReq).execute().use { resp ->
            val body = resp.body?.string() ?: throw IllegalStateException("网易云响应为空")
            JSONObject(body)
        }

        if (payload.optInt("code", -1) != 200 || payload.optJSONObject("playlist") == null) {
            throw IllegalStateException("网易云接口异常: code=${payload.optInt("code")} ${payload.optString("message")}")
        }

        val pl = payload.getJSONObject("playlist")
        val plName = pl.optString("name").trim().ifEmpty { "网易云歌单_$pid" }

        val trackIdsArr = pl.optJSONArray("trackIds")
        val trackIds = mutableListOf<Long>()
        if (trackIdsArr != null) {
            for (i in 0 until trackIdsArr.length()) {
                val id = trackIdsArr.optJSONObject(i)?.optLong("id", 0L) ?: 0L
                if (id > 0L) trackIds += id
            }
        }

        val byId = LinkedHashMap<Long, NeteaseTrack>()
        val initialArr = pl.optJSONArray("tracks")
        val fallbackList = mutableListOf<NeteaseTrack>()
        if (initialArr != null) {
            for (i in 0 until initialArr.length()) {
                val obj = initialArr.optJSONObject(i) ?: continue
                val t = parseTrack(obj) ?: continue
                fallbackList += t
                if (t.songId > 0L) byId[t.songId] = t
            }
        }

        if (trackIds.isEmpty()) {
            return NeteasePlaylistResult(pid, plName, fallbackList)
        }

        val missingIds = trackIds.filter { it !in byId }
        val total = trackIds.size
        onProgress?.invoke(byId.size, total)

        val batchSize = 200
        var offset = 0
        while (offset < missingIds.size) {
            val chunk = missingIds.subList(offset, minOf(offset + batchSize, missingIds.size))
            val cArr = JSONArray()
            chunk.forEach { sid -> cArr.put(JSONObject().put("id", sid)) }

            val form = FormBody.Builder().add("c", cArr.toString()).build()
            val batchReq = Request.Builder()
                .url("https://music.163.com/api/v3/song/detail")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://music.163.com/")
                .post(form)
                .build()

            val batchResp = http.newCall(batchReq).execute().use { resp ->
                JSONObject(resp.body?.string() ?: "{}")
            }
            val songsArr = batchResp.optJSONArray("songs")
            if (songsArr != null) {
                for (i in 0 until songsArr.length()) {
                    val obj = songsArr.optJSONObject(i) ?: continue
                    val t = parseTrack(obj) ?: continue
                    if (t.songId > 0L) byId[t.songId] = t
                }
            }
            onProgress?.invoke(minOf(byId.size, total), total)
            offset += batchSize
        }

        val ordered = trackIds.mapNotNull { byId[it] }
        return NeteasePlaylistResult(pid, plName, ordered)
    }
}
