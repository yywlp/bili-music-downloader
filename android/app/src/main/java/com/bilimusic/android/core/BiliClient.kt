package com.bilimusic.android.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.OutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class VideoInfo(
    val bvid: String,
    val aid: Long,
    val cid: Long,
    val title: String,
    val author: String,
    val duration: Int,
)

data class AudioStreamInfo(
    val id: Int,
    val bandwidth: Long,
    val codecs: String,
    val url: String,
    val backupUrls: List<String>,
    val isFlac: Boolean,
)

private class MemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val key = "bilibili.com"
        val list = store.getOrPut(key) { mutableListOf() }
        synchronized(list) {
            for (c in cookies) {
                list.removeAll { it.name == c.name }
                list.add(c)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val list = store["bilibili.com"] ?: return emptyList()
        synchronized(list) {
            return list.toList()
        }
    }

    fun putManual(name: String, value: String) {
        val cookie = Cookie.Builder()
            .name(name)
            .value(value)
            .domain("bilibili.com")
            .path("/")
            .build()
        val list = store.getOrPut("bilibili.com") { mutableListOf() }
        synchronized(list) {
            list.removeAll { it.name == name }
            list.add(cookie)
        }
    }

    fun headerString(sessdata: String): String {
        val list = store["bilibili.com"]?.let { synchronized(it) { it.toList() } } ?: emptyList()
        val map = LinkedHashMap<String, String>()
        list.forEach { map[it.name] = it.value }
        if (sessdata.isNotBlank()) {
            map["SESSDATA"] = sessdata.trim()
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}

class BiliClient(
    @Volatile var sessdata: String = "",
) {
    private val cookieJar = MemoryCookieJar()
    private val http = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var initialized = false

    @Volatile
    private var wbiKeys: Pair<String, String>? = null

    private val initLock = Any()

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val MIXIN_KEY_ENC_TAB = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
        )

        private fun getMixinKey(orig: String): String {
            val sb = StringBuilder()
            for (idx in MIXIN_KEY_ENC_TAB) {
                if (idx < orig.length) sb.append(orig[idx])
                if (sb.length == 32) break
            }
            return sb.toString()
        }

        private fun md5Hex(input: String): String {
            val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun urlEncode(value: String): String {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        }

        fun encodeWbiQuery(params: Map<String, String>, imgKey: String, subKey: String): String {
            val mixinKey = getMixinKey(imgKey + subKey)
            val sorted = TreeMap<String, String>()
            params.forEach { (k, v) -> sorted[k] = v }
            sorted["wts"] = (System.currentTimeMillis() / 1000L).toString()

            val filtered = sorted.mapValues { (_, v) ->
                v.filterNot { it in "!'()*" }
            }
            val query = filtered.entries.joinToString("&") { (k, v) ->
                "${urlEncode(k)}=${urlEncode(v)}"
            }
            val wRid = md5Hex(query + mixinKey)
            return "$query&w_rid=$wRid"
        }
    }

    private fun buildRequest(url: String, referer: String = "https://www.bilibili.com/"): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        val cookieStr = cookieJar.headerString(sessdata)
        if (cookieStr.isNotBlank()) {
            builder.header("Cookie", cookieStr)
        }
        return builder.build()
    }

    private fun getJson(url: String, referer: String = "https://www.bilibili.com/"): JSONObject {
        http.newCall(buildRequest(url, referer)).execute().use { resp ->
            val body = resp.body?.string() ?: throw IllegalStateException("空响应体 (HTTP ${resp.code})")
            return JSONObject(body)
        }
    }

    fun ensureSession() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            runCatching {
                http.newCall(
                    Request.Builder()
                        .url("https://www.bilibili.com/")
                        .header("User-Agent", USER_AGENT)
                        .build()
                ).execute().use { it.body?.bytes() }
            }

            runCatching {
                val spi = getJson("https://api.bilibili.com/x/frontend/finger/spi")
                if (spi.optInt("code", -1) == 0) {
                    val data = spi.optJSONObject("data")
                    data?.optString("b_3")?.takeIf { it.isNotBlank() }?.let { cookieJar.putManual("buvid3", it) }
                    data?.optString("b_4")?.takeIf { it.isNotBlank() }?.let { cookieJar.putManual("buvid4", it) }
                }
            }

            runCatching {
                val nav = getJson("https://api.bilibili.com/x/web-interface/nav")
                val wbi = nav.optJSONObject("data")?.optJSONObject("wbi_img")
                val imgUrl = wbi?.optString("img_url").orEmpty()
                val subUrl = wbi?.optString("sub_url").orEmpty()
                if (imgUrl.isNotBlank() && subUrl.isNotBlank()) {
                    val imgKey = imgUrl.substringAfterLast("/").substringBefore(".")
                    val subKey = subUrl.substringAfterLast("/").substringBefore(".")
                    wbiKeys = imgKey to subKey
                }
            }
            initialized = true
        }
    }

    private fun searchOnce(keyword: String, order: String = "click", page: Int = 1): List<VideoCandidate> {
        ensureSession()
        val baseParams = mapOf(
            "search_type" to "video",
            "keyword" to keyword,
            "order" to order,
            "page" to page.toString(),
        )

        var data: JSONObject? = null
        val keys = wbiKeys
        if (keys != null) {
            runCatching {
                val query = encodeWbiQuery(baseParams, keys.first, keys.second)
                val resp = getJson(
                    "https://api.bilibili.com/x/web-interface/wbi/search/type?$query",
                    referer = "https://search.bilibili.com/",
                )
                if (resp.optInt("code", -1) == 0) {
                    data = resp.optJSONObject("data")
                }
            }
        }

        if (data == null) {
            val query = baseParams.entries.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }
            val resp = getJson(
                "https://api.bilibili.com/x/web-interface/search/type?$query",
                referer = "https://search.bilibili.com/",
            )
            if (resp.optInt("code", -1) != 0) {
                throw IllegalStateException("B站搜索异常: code=${resp.optInt("code")} ${resp.optString("message")}")
            }
            data = resp.optJSONObject("data")
        }

        val items = data?.optJSONArray("result") ?: return emptyList()
        val out = mutableListOf<VideoCandidate>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val bvid = item.optString("bvid")
            if (bvid.isBlank()) continue
            val durStr = item.optString("duration", "0:00")
            val durSec = Ranker.parseDuration(durStr)
            val play = item.optString("play", "0").toLongOrNull() ?: item.optLong("play", 0L)
            out += VideoCandidate(
                bvid = bvid,
                aid = item.optLong("aid", 0L),
                title = Ranker.stripHtml(item.optString("title")),
                author = Ranker.stripHtml(item.optString("author")),
                play = play,
                durationSec = durSec,
                durationStr = durStr,
                description = Ranker.stripHtml(item.optString("description")),
                tag = item.optString("tag"),
            )
        }
        return out
    }

    fun searchSongCandidates(
        songName: String,
        artist: String = "",
        losslessFirst: Boolean = true,
    ): List<VideoCandidate> {
        val song = songName.trim()
        val art = artist.trim()
        if (song.isEmpty()) return emptyList()

        val primaryKw = listOf(song, art).filter { it.isNotEmpty() }.joinToString(" ")
        val results = mutableListOf<VideoCandidate>()
        results += searchOnce(primaryKw, order = "click", page = 1)

        if (losslessFirst) {
            val losslessKw = if (art.isNotEmpty()) "$song $art 无损" else "$song 无损"
            runCatching {
                results += searchOnce(losslessKw, order = "click", page = 1)
            }
        }
        return results
    }

    fun getVideoInfo(bvid: String): VideoInfo {
        ensureSession()
        val url = "https://api.bilibili.com/x/web-interface/view?bvid=${urlEncode(bvid)}"
        val resp = getJson(url, referer = "https://www.bilibili.com/video/$bvid/")
        if (resp.optInt("code", -1) != 0) {
            throw IllegalStateException("获取视频信息失败: ${resp.optString("message")}")
        }
        val data = resp.optJSONObject("data") ?: throw IllegalStateException("视频数据为空")
        return VideoInfo(
            bvid = data.optString("bvid", bvid),
            aid = data.optLong("aid", 0L),
            cid = data.optLong("cid", 0L),
            title = data.optString("title"),
            author = data.optJSONObject("owner")?.optString("name").orEmpty(),
            duration = data.optInt("duration", 0),
        )
    }

    fun getBestAudioStream(bvid: String, cid: Long): AudioStreamInfo {
        ensureSession()
        val url = "https://api.bilibili.com/x/player/playurl?bvid=${urlEncode(bvid)}&cid=$cid&fnval=4048&fnver=0&fourk=1"
        val resp = getJson(url, referer = "https://www.bilibili.com/video/$bvid/")
        if (resp.optInt("code", -1) != 0) {
            throw IllegalStateException("获取音频流失败: ${resp.optString("message")}")
        }
        val dash = resp.optJSONObject("data")?.optJSONObject("dash")
            ?: throw IllegalStateException("未找到 DASH 音频流")

        val flacAudio = dash.optJSONObject("flac")?.optJSONObject("audio")
        if (flacAudio != null) {
            val fUrl = flacAudio.optString("baseUrl").ifBlank { flacAudio.optString("base_url") }
            if (fUrl.isNotBlank()) {
                return AudioStreamInfo(
                    id = flacAudio.optInt("id", 30251),
                    bandwidth = flacAudio.optLong("bandwidth", 0L),
                    codecs = flacAudio.optString("codecs", "flac"),
                    url = fUrl,
                    backupUrls = parseStringArray(flacAudio, "backupUrl", "backup_url"),
                    isFlac = true,
                )
            }
        }

        val audios = dash.optJSONArray("audio")
        if (audios == null || audios.length() == 0) {
            throw IllegalStateException("未找到可用的音频流")
        }

        var bestObj = audios.getJSONObject(0)
        var bestBw = bestObj.optLong("bandwidth", 0L)
        for (i in 1 until audios.length()) {
            val cur = audios.getJSONObject(i)
            val bw = cur.optLong("bandwidth", 0L)
            if (bw > bestBw) {
                bestObj = cur
                bestBw = bw
            }
        }

        val mainUrl = bestObj.optString("baseUrl").ifBlank { bestObj.optString("base_url") }
        return AudioStreamInfo(
            id = bestObj.optInt("id", 0),
            bandwidth = bestBw,
            codecs = bestObj.optString("codecs"),
            url = mainUrl,
            backupUrls = parseStringArray(bestObj, "backupUrl", "backup_url"),
            isFlac = false,
        )
    }

    private fun parseStringArray(obj: JSONObject, key1: String, key2: String): List<String> {
        val arr = obj.optJSONArray(key1) ?: obj.optJSONArray(key2) ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotBlank()) add(s)
            }
        }
    }

    suspend fun downloadToStream(
        stream: AudioStreamInfo,
        bvid: String,
        output: OutputStream,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        val urls = (listOf(stream.url) + stream.backupUrls).filter { it.isNotBlank() }
        val referer = "https://www.bilibili.com/video/$bvid/"
        var lastError: Exception? = null

        for (url in urls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", referer)
                    .build()

                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw IllegalStateException("音频流为空")
                    val total = body.contentLength()
                    val buf = ByteArray(65536)
                    var downloaded = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            onProgress(downloaded, total)
                        }
                    }
                    output.flush()
                }
                return
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException("音频下载失败: ${lastError?.message}")
    }
}
