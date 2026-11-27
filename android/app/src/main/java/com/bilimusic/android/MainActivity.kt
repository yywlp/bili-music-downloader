package com.bilimusic.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bilimusic.android.core.AppPrefs
import com.bilimusic.android.core.AudioStorage
import com.bilimusic.android.core.BiliClient
import com.bilimusic.android.core.ConflictPolicy
import com.bilimusic.android.core.LocalAudioPlayer
import com.bilimusic.android.core.LocalTrack
import com.bilimusic.android.core.NeteaseImporter
import com.bilimusic.android.core.PlayMode
import com.bilimusic.android.core.PlaylistStore
import com.bilimusic.android.core.PrefsStore
import com.bilimusic.android.core.RankMode
import com.bilimusic.android.core.Ranker
import com.bilimusic.android.core.VideoCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

private val BiliPink = Color(0xFFFB7299)
private val BiliBlue = Color(0xFF23ADE5)

private val LightColors = lightColorScheme(
    primary = BiliPink,
    secondary = BiliBlue,
    background = Color(0xFFF3F5F9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF2F7),
)

private val DarkColors = darkColorScheme(
    primary = BiliPink,
    secondary = BiliBlue,
    background = Color(0xFF14161D),
    surface = Color(0xFF1E212B),
    surfaceVariant = Color(0xFF282C38),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefsStore = PrefsStore(this)
        val playlistStore = PlaylistStore(this)
        setContent {
            BiliMusicRoot(prefsStore = prefsStore, playlistStore = playlistStore)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BiliMusicRoot(prefsStore: PrefsStore, playlistStore: PlaylistStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var prefs by remember { mutableStateOf(prefsStore.load()) }
    val client = remember { BiliClient(sessdata = prefs.sessdata) }
    val player = remember { LocalAudioPlayer(context) }

    var selectedTab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }

    // Single search state
    var songQuery by remember { mutableStateOf("") }
    var artistQuery by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<VideoCandidate>>(emptyList()) }

    // Batch + NetEase import state
    var neteaseInput by remember { mutableStateOf("") }
    var batchSubFolder by remember { mutableStateOf("") }
    var useSubFolder by remember { mutableStateOf(true) }
    var strictMatch by remember { mutableStateOf(true) }
    var batchText by remember {
        mutableStateOf("# 每行一首歌（支持「歌名 - 歌手」），也可在上方直接导入网易云歌单：\n青花瓷 - 周杰伦\n兰亭序 - 周杰伦\n")
    }
    var batchLogs by remember { mutableStateOf("等待开始批量任务...\n音频将自动保存到手机 Music/BiliMusic/ 目录") }

    // Local music & playlists state
    var localTracks by remember { mutableStateOf<List<LocalTrack>>(emptyList()) }
    var playlistNames by remember { mutableStateOf(playlistStore.listNames()) }
    var activeFilter by remember { mutableStateOf("ALL") } // "ALL" | "FOLDER:xxx" | "PL:xxx"
    var currentQueue by remember { mutableStateOf<List<LocalTrack>>(emptyList()) }
    var currentTrack by remember { mutableStateOf<LocalTrack?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playMode by remember { mutableStateOf(PlayMode.LIST_LOOP) }
    var posMs by remember { mutableIntStateOf(0) }
    var durMs by remember { mutableIntStateOf(0) }

    // Task state
    var isBusy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("就绪：保存路径 Music/BiliMusic/（最高音质原流直出）") }
    var currentJob by remember { mutableStateOf<Job?>(null) }

    fun refreshLocalLibrary() {
        scope.launch {
            localTracks = withContext(Dispatchers.IO) { AudioStorage.scanLocalTracks(context) }
            playlistNames = playlistStore.listNames()
        }
    }

    fun playTrackFromQueue(track: LocalTrack, queue: List<LocalTrack>) {
        currentQueue = queue
        currentTrack = track
        player.play(track.uri)
        isPlaying = true
    }

    fun playAdjacent(delta: Int) {
        val q = currentQueue.ifEmpty { localTracks }
        if (q.isEmpty()) return
        val curIdx = q.indexOfFirst { it.uri == currentTrack?.uri }
        val nextIdx = when {
            playMode == PlayMode.SHUFFLE && q.size > 1 -> {
                var r = Random.nextInt(q.size)
                if (r == curIdx) r = (r + 1) % q.size
                r
            }
            curIdx < 0 -> 0
            else -> (curIdx + delta + q.size) % q.size
        }
        playTrackFromQueue(q[nextIdx], q)
    }

    LaunchedEffect(Unit) {
        refreshLocalLibrary()
        player.onCompleted = {
            scope.launch(Dispatchers.Main) {
                val q = currentQueue.ifEmpty { localTracks }
                when (playMode) {
                    PlayMode.SINGLE_LOOP -> currentTrack?.let { player.play(it.uri) }
                    PlayMode.LIST_LOOP, PlayMode.SHUFFLE -> playAdjacent(1)
                    PlayMode.SEQUENTIAL -> {
                        val idx = q.indexOfFirst { it.uri == currentTrack?.uri }
                        if (idx >= 0 && idx + 1 < q.size) playTrackFromQueue(q[idx + 1], q)
                        else isPlaying = false
                    }
                }
            }
        }
        player.onError = { msg ->
            scope.launch(Dispatchers.Main) {
                statusText = "播放出错：$msg"
                isPlaying = false
            }
        }
        player.onAutoPaused = {
            scope.launch(Dispatchers.Main) {
                isPlaying = false
                statusText = "蓝牙/耳机已断开，已自动暂停播放"
            }
        }
    }

    LaunchedEffect(currentTrack, isPlaying) {
        while (currentTrack != null) {
            posMs = player.currentPositionMs()
            durMs = player.durationMs()
            isPlaying = player.isPlaying()
            delay(450)
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    fun updatePrefs(newPrefs: AppPrefs) {
        prefs = newPrefs
        client.sessdata = newPrefs.sessdata
        prefsStore.save(newPrefs)
    }

    suspend fun downloadCandidate(
        c: VideoCandidate,
        songName: String,
        artistName: String,
        subFolder: String? = null,
    ): String {
        withContext(Dispatchers.Main) {
            statusText = "正在解析音轨：${c.title}"
            progress = 0.12f
        }
        val info = client.getVideoInfo(c.bvid)
        val stream = client.getBestAudioStream(c.bvid, info.cid)
        val labelName = songName.ifBlank { c.title }

        val saved = AudioStorage.saveAudioFile(
            context = context,
            songName = labelName,
            artist = artistName.ifBlank { c.author },
            isFlac = stream.isFlac,
            conflictPolicy = prefs.conflictPolicy,
            subFolder = subFolder,
        ) { out ->
            client.downloadToStream(stream, c.bvid, out) { done, total ->
                val p = if (total > 0) (0.15f + 0.85f * (done.toFloat() / total.toFloat())).coerceIn(0f, 1f) else 0.5f
                scope.launch(Dispatchers.Main) {
                    progress = p
                    val mb = done / (1024.0 * 1024.0)
                    statusText = "下载中：$labelName（%.2f MB）".format(mb)
                }
            }
        }

        return if (saved.skipped) {
            "已跳过（同名已存在）：${saved.displayName}"
        } else {
            "已保存：${saved.relativePath}${saved.displayName}"
        }
    }

    fun startSingleSearch(autoDownloadTop: Boolean) {
        val song = songQuery.trim()
        val artist = artistQuery.trim()
        if (song.isEmpty()) {
            statusText = "请先输入歌曲名"
            return
        }
        currentJob?.cancel()
        currentJob = scope.launch {
            isBusy = true
            progress = 0.08f
            statusText = "正在搜索「$song」并按纯单曲规则筛选..."
            try {
                val ranked = withContext(Dispatchers.IO) {
                    val raw = client.searchSongCandidates(song, artist, prefs.rankMode == RankMode.LOSSLESS_FIRST)
                    Ranker.rankCandidates(raw, song, artist, prefs.rankMode, limit = 15)
                }
                candidates = ranked
                if (ranked.isEmpty()) {
                    statusText = "未搜到相关结果，请换个关键词重试"
                    progress = 0f
                } else if (autoDownloadTop) {
                    val msg = withContext(Dispatchers.IO) {
                        downloadCandidate(ranked.first(), song, artist)
                    }
                    progress = 1f
                    statusText = msg
                    refreshLocalLibrary()
                } else {
                    progress = 0f
                    statusText = "已筛选出 ${ranked.size} 条纯单曲候选，点击歌曲展开「下载 MP3」按钮"
                }
            } catch (ce: CancellationException) {
                statusText = "任务已停止"
                progress = 0f
            } catch (e: Exception) {
                statusText = "出错：${e.message ?: "网络异常"}"
                progress = 0f
            } finally {
                isBusy = false
            }
        }
    }

    fun startDownloadItem(c: VideoCandidate) {
        if (isBusy) return
        currentJob = scope.launch {
            isBusy = true
            try {
                val msg = withContext(Dispatchers.IO) {
                    downloadCandidate(c, songQuery.trim(), artistQuery.trim())
                }
                progress = 1f
                statusText = msg
                refreshLocalLibrary()
            } catch (ce: CancellationException) {
                statusText = "下载已取消"
                progress = 0f
            } catch (e: Exception) {
                statusText = "下载失败：${e.message}"
                progress = 0f
            } finally {
                isBusy = false
            }
        }
    }

    fun startNeteaseImport() {
        val raw = neteaseInput.trim()
        if (raw.isEmpty()) {
            statusText = "请先粘贴网易云歌单分享文案、链接或歌单 ID"
            return
        }
        currentJob?.cancel()
        currentJob = scope.launch {
            isBusy = true
            progress = 0.1f
            statusText = "正在免登录解析网易云歌单..."
            try {
                val pl = withContext(Dispatchers.IO) {
                    NeteaseImporter.fetchPlaylist(raw) { resolved, total ->
                        scope.launch(Dispatchers.Main) {
                            progress = if (total > 0) (resolved.toFloat() / total.toFloat()).coerceIn(0.1f, 1f) else 0.5f
                            statusText = "正在展开网易云歌单：$resolved / $total 首..."
                        }
                    }
                }
                val lines = buildString {
                    append("# 网易云歌单：${pl.name}（共 ${pl.tracks.size} 首）\n")
                    pl.tracks.forEach { t ->
                        if (t.artist.isNotBlank()) append("${t.name} - ${t.artist}\n")
                        else append("${t.name}\n")
                    }
                }
                batchText = lines
                batchSubFolder = pl.name
                useSubFolder = true
                progress = 1f
                statusText = "已导入「${pl.name}」共 ${pl.tracks.size} 首，点击「开始批量下载」即可"
                batchLogs = "已解析网易云歌单：${pl.name}（${pl.tracks.size} 首）\n勾选「按歌单名建子文件夹」将存到 Music/BiliMusic/${Ranker.sanitizeFilename(pl.name)}/"
            } catch (ce: CancellationException) {
                statusText = "已取消解析"
                progress = 0f
            } catch (e: Exception) {
                statusText = "导入失败：${e.message}"
                progress = 0f
            } finally {
                isBusy = false
            }
        }
    }

    fun startBatchDownload() {
        val items = batchText.lines().mapNotNull { Ranker.parseBatchLine(it) }
        if (items.isEmpty()) {
            statusText = "批量歌单为空，请至少输入一首歌"
            return
        }
        val targetSub = if (useSubFolder) batchSubFolder.trim().takeIf { it.isNotEmpty() } else null
        currentJob?.cancel()
        currentJob = scope.launch {
            isBusy = true
            progress = 0f
            val logSb = StringBuilder()
            var okCount = 0
            var skipCount = 0
            var failCount = 0

            try {
                items.forEachIndexed { idx, (song, artist) ->
                    val prefix = "[${idx + 1}/${items.size}]"
                    logSb.append("$prefix 搜索「$song${if (artist.isNotEmpty()) " - $artist" else ""}」...\n")
                    batchLogs = logSb.toString()
                    statusText = "$prefix 正在处理：$song"
                    try {
                        val msg = withContext(Dispatchers.IO) {
                            val raw = client.searchSongCandidates(song, artist, prefs.rankMode == RankMode.LOSSLESS_FIRST)
                            val ranked = Ranker.rankCandidates(raw, song, artist, prefs.rankMode, limit = 5)
                            val top = ranked.firstOrNull()
                            if (top == null) {
                                throw IllegalStateException("未找到候选")
                            }
                            if (strictMatch && top.excluded) {
                                return@withContext "STRICT_SKIP:${top.excludeReason}"
                            }
                            downloadCandidate(top, song, artist, subFolder = targetSub)
                        }
                        if (msg.startsWith("STRICT_SKIP:")) {
                            skipCount++
                            logSb.append("   ↷ 严格匹配跳过（${msg.removePrefix("STRICT_SKIP:")}）\n\n")
                        } else {
                            okCount++
                            logSb.append("   ✓ $msg\n\n")
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        failCount++
                        logSb.append("   ✗ 失败：${e.message}\n\n")
                    }
                    batchLogs = logSb.toString()
                    progress = (idx + 1).toFloat() / items.size.toFloat()
                    if (idx + 1 < items.size) delay(450)
                }
                statusText = "批量完成：成功 $okCount / 跳过 $skipCount / 失败 $failCount"
                refreshLocalLibrary()
            } catch (ce: CancellationException) {
                logSb.append("\n[已手动停止剩余任务]\n")
                batchLogs = logSb.toString()
                statusText = "已停止：已完成 $okCount 首"
                refreshLocalLibrary()
            } finally {
                isBusy = false
            }
        }
    }

    MaterialTheme(colorScheme = if (prefs.darkTheme) DarkColors else LightColors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("BiliMusic", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            Text(
                                "纯单曲筛选 · 网易云歌单导入 · 本地音乐播放",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    actions = {
                        IconButton(onClick = { updatePrefs(prefs.copy(darkTheme = !prefs.darkTheme)) }) {
                            Icon(
                                imageVector = if (prefs.darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "切换主题",
                            )
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    },
                )
            },
            bottomBar = {
                Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    // Mini Audio Player Bar (visible when a track is loaded)
                    currentTrack?.let { track ->
                        MiniPlayerBar(
                            track = track,
                            isPlaying = isPlaying,
                            posMs = posMs,
                            durMs = durMs,
                            playMode = playMode,
                            onPrev = { playAdjacent(-1) },
                            onTogglePlay = { isPlaying = player.togglePause() },
                            onNext = { playAdjacent(1) },
                            onCycleMode = {
                                val all = PlayMode.entries
                                playMode = all[(all.indexOf(playMode) + 1) % all.size]
                            },
                            onSeek = { ms ->
                                posMs = ms
                                player.seekTo(ms)
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }

                    // Compact status & progress strip
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = BiliPink,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = statusText,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (isBusy) {
                                TextButton(
                                    onClick = { currentJob?.cancel() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text("停止", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    NavigationBar {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                            label = { Text("单曲搜选") },
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                            label = { Text("批量/网易云") },
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = {
                                selectedTab = 2
                                refreshLocalLibrary()
                            },
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                            label = { Text("本地音乐") },
                        )
                    }
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding),
            ) {
                when (selectedTab) {
                    0 -> SingleSearchTab(
                        songQuery = songQuery,
                        onSongChange = { songQuery = it },
                        artistQuery = artistQuery,
                        onArtistChange = { artistQuery = it },
                        rankMode = prefs.rankMode,
                        onRankModeChange = { updatePrefs(prefs.copy(rankMode = it)) },
                        candidates = candidates,
                        isBusy = isBusy,
                        onSearch = { startSingleSearch(autoDownloadTop = false) },
                        onQuickDownload = { startSingleSearch(autoDownloadTop = true) },
                        onDownloadCandidate = { startDownloadItem(it) },
                        onOpenBilibili = { bvid ->
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bilibili.com/video/$bvid/"))
                                )
                            }
                        },
                    )

                    1 -> BatchAndNeteaseTab(
                        neteaseInput = neteaseInput,
                        onNeteaseInputChange = { neteaseInput = it },
                        onImportNetease = { startNeteaseImport() },
                        batchSubFolder = batchSubFolder,
                        onSubFolderChange = { batchSubFolder = it },
                        useSubFolder = useSubFolder,
                        onUseSubFolderChange = { useSubFolder = it },
                        strictMatch = strictMatch,
                        onStrictMatchChange = { strictMatch = it },
                        batchText = batchText,
                        onBatchTextChange = { batchText = it },
                        batchLogs = batchLogs,
                        isBusy = isBusy,
                        onClear = { batchText = "" },
                        onStartBatch = { startBatchDownload() },
                    )

                    else -> LocalLibraryTab(
                        tracks = localTracks,
                        playlistNames = playlistNames,
                        playlistStore = playlistStore,
                        activeFilter = activeFilter,
                        onFilterChange = { activeFilter = it },
                        currentTrackUri = currentTrack?.uri,
                        onPlayTrack = { t, q -> playTrackFromQueue(t, q) },
                        onDeleteTrackFile = { track ->
                            if (currentTrack?.uri == track.uri) {
                                player.release()
                                currentTrack = null
                                isPlaying = false
                                posMs = 0
                                durMs = 0
                            }
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    AudioStorage.deleteLocalTrack(context, track.uri)
                                }
                                playlistStore.removeTrackFromAll(track.uri)
                                statusText = if (ok) "已删除本地文件：${track.title}" else "删除失败，请检查文件权限"
                                refreshLocalLibrary()
                            }
                        },
                        onRefresh = { refreshLocalLibrary() },
                        onPlaylistsChanged = { playlistNames = playlistStore.listNames() },
                    )
                }
            }
        }

        if (showSettings) {
            SettingsDialog(
                prefs = prefs,
                onDismiss = { showSettings = false },
                onSave = {
                    updatePrefs(it)
                    showSettings = false
                    statusText = "设置已保存"
                },
            )
        }
    }
}

@Composable
private fun MiniPlayerBar(
    track: LocalTrack,
    isPlaying: Boolean,
    posMs: Int,
    durMs: Int,
    playMode: PlayMode,
    onPrev: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onCycleMode: () -> Unit,
    onSeek: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${Ranker.formatDuration(posMs / 1000)} / ${Ranker.formatDuration(durMs / 1000)} · ${playMode.label}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCycleMode, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = when (playMode) {
                        PlayMode.SINGLE_LOOP -> Icons.Default.RepeatOne
                        PlayMode.SHUFFLE -> Icons.Default.Shuffle
                        else -> Icons.Default.Repeat
                    },
                    contentDescription = playMode.label,
                    tint = BiliBlue,
                    modifier = Modifier.size(19.dp),
                )
            }
            IconButton(onClick = onPrev, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "上一首", modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = onTogglePlay, modifier = Modifier.size(38.dp)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "播放/暂停",
                    tint = BiliPink,
                    modifier = Modifier.size(26.dp),
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.SkipNext, contentDescription = "下一首", modifier = Modifier.size(22.dp))
            }
        }
        if (durMs > 0) {
            Slider(
                value = posMs.coerceIn(0, durMs).toFloat(),
                onValueChange = { onSeek(it.toInt()) },
                valueRange = 0f..durMs.toFloat(),
                colors = SliderDefaults.colors(thumbColor = BiliPink, activeTrackColor = BiliPink),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SingleSearchTab(
    songQuery: String,
    onSongChange: (String) -> Unit,
    artistQuery: String,
    onArtistChange: (String) -> Unit,
    rankMode: RankMode,
    onRankModeChange: (RankMode) -> Unit,
    candidates: List<VideoCandidate>,
    isBusy: Boolean,
    onSearch: () -> Unit,
    onQuickDownload: () -> Unit,
    onDownloadCandidate: (VideoCandidate) -> Unit,
    onOpenBilibili: (String) -> Unit,
) {
    var selectedBvid by remember(candidates) { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = songQuery,
                        onValueChange = onSongChange,
                        label = { Text("歌曲名") },
                        placeholder = { Text("如：青花瓷") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f),
                    )
                    OutlinedTextField(
                        value = artistQuery,
                        onValueChange = onArtistChange,
                        label = { Text("歌手(选填)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RankMode.entries.forEach { mode ->
                        FilterChip(
                            selected = rankMode == mode,
                            onClick = { onRankModeChange(mode) },
                            label = { Text(mode.label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BiliBlue.copy(alpha = 0.16f),
                                selectedLabelColor = BiliBlue,
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = onSearch,
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("搜索候选")
                    }
                    Button(
                        onClick = onQuickDownload,
                        enabled = !isBusy,
                        modifier = Modifier.weight(1.25f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BiliPink),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("一键下载第1名")
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (candidates.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "输入歌名即可自动过滤合集/翻唱/鬼畜\n点击搜出的歌曲展开「下载 MP3」按钮",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                itemsIndexed(candidates) { idx, c ->
                    val isSelected = selectedBvid == c.bvid
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                BiliPink.copy(alpha = 0.08f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
                        border = when {
                            isSelected -> BorderStroke(1.8.dp, BiliPink)
                            idx == 0 -> BorderStroke(1.2.dp, BiliPink.copy(alpha = 0.45f))
                            else -> null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedBvid = if (isSelected) null else c.bvid
                            },
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = if (idx == 0 || isSelected) BiliPink else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp),
                                ) {
                                    Text(
                                        text = "#${idx + 1}",
                                        color = if (idx == 0 || isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = c.title,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            if (c.badges.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    c.badges.forEach { badge ->
                                        Surface(
                                            color = BiliBlue.copy(alpha = 0.14f),
                                            shape = RoundedCornerShape(5.dp),
                                        ) {
                                            Text(
                                                text = badge,
                                                color = BiliBlue,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(6.dp))
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = "UP: ${c.author}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${Ranker.formatPlayCount(c.play)}播放 · ${c.durationStr}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            AnimatedVisibility(visible = isSelected) {
                                Column {
                                    Spacer(Modifier.height(10.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        OutlinedButton(
                                            onClick = { onOpenBilibili(c.bvid) },
                                            shape = RoundedCornerShape(9.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            modifier = Modifier.height(36.dp),
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.OpenInNew,
                                                contentDescription = null,
                                                modifier = Modifier.size(15.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("去B站核对", fontSize = 12.sp)
                                        }
                                        Spacer(Modifier.weight(1f))
                                        Button(
                                            onClick = { onDownloadCandidate(c) },
                                            enabled = !isBusy,
                                            shape = RoundedCornerShape(9.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = BiliPink),
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                            modifier = Modifier.height(36.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.Download,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("下载 MP3", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchAndNeteaseTab(
    neteaseInput: String,
    onNeteaseInputChange: (String) -> Unit,
    onImportNetease: () -> Unit,
    batchSubFolder: String,
    onSubFolderChange: (String) -> Unit,
    useSubFolder: Boolean,
    onUseSubFolderChange: (Boolean) -> Unit,
    strictMatch: Boolean,
    onStrictMatchChange: (Boolean) -> Unit,
    batchText: String,
    onBatchTextChange: (String) -> Unit,
    batchLogs: String,
    isBusy: Boolean,
    onClear: () -> Unit,
    onStartBatch: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // NetEase Cloud Music Import Card
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("网易云歌单免登录导入", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = neteaseInput,
                        onValueChange = onNeteaseInputChange,
                        placeholder = { Text("粘贴网易云分享文案 / 链接 / 歌单ID", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = onImportNetease,
                        enabled = !isBusy,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BiliBlue),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导入", fontSize = 13.sp)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useSubFolder, onCheckedChange = onUseSubFolderChange)
                        Text("按歌单建子文件夹", fontSize = 12.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = strictMatch, onCheckedChange = onStrictMatchChange)
                        Text("严格匹配防乱下", fontSize = 12.sp)
                    }
                }
                if (useSubFolder) {
                    OutlinedTextField(
                        value = batchSubFolder,
                        onValueChange = onSubFolderChange,
                        label = { Text("子文件夹名（留空存默认目录）", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        OutlinedTextField(
            value = batchText,
            onValueChange = onBatchTextChange,
            label = { Text("批量歌单（每行一首，# 开头自动忽略）") },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = onClear,
                enabled = !isBusy,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("清空歌单")
            }
            Button(
                onClick = onStartBatch,
                enabled = !isBusy,
                modifier = Modifier.weight(1.4f),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BiliPink),
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("开始批量下载")
            }
        }

        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.75f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("执行日志", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = batchLogs,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

@Composable
private fun LocalLibraryTab(
    tracks: List<LocalTrack>,
    playlistNames: List<String>,
    playlistStore: PlaylistStore,
    activeFilter: String,
    onFilterChange: (String) -> Unit,
    currentTrackUri: String?,
    onPlayTrack: (LocalTrack, List<LocalTrack>) -> Unit,
    onDeleteTrackFile: (LocalTrack) -> Unit,
    onRefresh: () -> Unit,
    onPlaylistsChanged: () -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlName by remember { mutableStateOf("") }
    var renameTargetPl by remember { mutableStateOf<String?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var trackForPlaylist by remember { mutableStateOf<LocalTrack?>(null) }
    var trackToDelete by remember { mutableStateOf<LocalTrack?>(null) }

    val folders = remember(tracks) {
        tracks.map { it.folder }.distinct().sortedWith(compareBy({ it != "默认目录" }, { it }))
    }

    val filteredTracks = remember(tracks, activeFilter, playlistNames) {
        when {
            activeFilter == "ALL" -> tracks
            activeFilter.startsWith("FOLDER:") -> {
                val f = activeFilter.removePrefix("FOLDER:")
                tracks.filter { it.folder == f }
            }
            activeFilter.startsWith("PL:") -> {
                val pl = activeFilter.removePrefix("PL:")
                val uriSet = playlistStore.getTrackUris(pl).toSet()
                tracks.filter { it.uri in uriSet }
            }
            else -> tracks
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "本地曲库（${filteredTracks.size} 首）",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
            Row {
                TextButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("新建歌单", fontSize = 12.5.sp)
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新本地曲库")
                }
            }
        }

        // Filter chips: All + Folders + Custom Playlists
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item {
                FilterChip(
                    selected = activeFilter == "ALL",
                    onClick = { onFilterChange("ALL") },
                    label = { Text("全部 (${tracks.size})", fontSize = 12.sp) },
                )
            }
            items(folders) { folder ->
                val key = "FOLDER:$folder"
                FilterChip(
                    selected = activeFilter == key,
                    onClick = { onFilterChange(key) },
                    label = { Text(folder, fontSize = 12.sp) },
                )
            }
            items(playlistNames) { plName ->
                val key = "PL:$plName"
                FilterChip(
                    selected = activeFilter == key,
                    onClick = { onFilterChange(key) },
                    label = { Text("歌单·$plName", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BiliPink.copy(alpha = 0.16f),
                        selectedLabelColor = BiliPink,
                    ),
                )
            }
        }

        if (activeFilter.startsWith("PL:")) {
            val curPl = activeFilter.removePrefix("PL:")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    onClick = {
                        renameTargetPl = curPl
                        renameInput = curPl
                    },
                ) {
                    Text("重命名歌单", fontSize = 12.sp)
                }
                TextButton(
                    onClick = {
                        playlistStore.delete(curPl)
                        onFilterChange("ALL")
                        onPlaylistsChanged()
                    },
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("删除歌单「$curPl」", fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        if (filteredTracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无本地歌曲\n先去「单曲搜选」或「批量/网易云」下载几首吧",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(filteredTracks, key = { it.uri }) { track ->
                    val isCurrent = track.uri == currentTrackUri
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) BiliPink.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface,
                        ),
                        border = if (isCurrent) BorderStroke(1.5.dp, BiliPink) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlayTrack(track, filteredTracks) },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Icon(
                                imageVector = if (isCurrent) Icons.Default.PlayArrow else Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = if (isCurrent) BiliPink else BiliBlue,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${track.folder} · ${track.ext} · ${AudioStorage.formatSize(track.sizeBytes)}",
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (activeFilter.startsWith("PL:")) {
                                IconButton(onClick = { trackToDelete = track }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "移出或删除",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                IconButton(onClick = { trackForPlaylist = track }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.PlaylistAdd,
                                        contentDescription = "加入歌单",
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                IconButton(onClick = { trackToDelete = track }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除歌曲",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建自定义歌单", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newPlName,
                    onValueChange = { newPlName = it },
                    label = { Text("歌单名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (playlistStore.create(newPlName)) {
                            onPlaylistsChanged()
                            newPlName = ""
                            showCreateDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BiliPink),
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("取消") }
            },
        )
    }

    trackForPlaylist?.let { target ->
        AlertDialog(
            onDismissRequest = { trackForPlaylist = null },
            title = { Text("加入自定义歌单", fontWeight = FontWeight.Bold) },
            text = {
                if (playlistNames.isEmpty()) {
                    Text("还没有自定义歌单，请先点右上角「新建歌单」。")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        playlistNames.forEach { pl ->
                            OutlinedButton(
                                onClick = {
                                    playlistStore.addTrack(pl, target.uri)
                                    onPlaylistsChanged()
                                    trackForPlaylist = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("加入「$pl」")
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { trackForPlaylist = null }) { Text("关闭") }
            },
        )
    }

    renameTargetPl?.let { oldName ->
        AlertDialog(
            onDismissRequest = { renameTargetPl = null },
            title = { Text("重命名歌单", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("新歌单名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = renameInput.trim()
                        if (playlistStore.rename(oldName, trimmed)) {
                            onPlaylistsChanged()
                            if (activeFilter == "PL:$oldName") onFilterChange("PL:$trimmed")
                            renameTargetPl = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BiliPink),
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetPl = null }) { Text("取消") }
            },
        )
    }

    trackToDelete?.let { target ->
        val inCustomPl = activeFilter.startsWith("PL:")
        val curPlName = if (inCustomPl) activeFilter.removePrefix("PL:") else ""
        var alsoDeleteLocal by remember(target.uri, activeFilter) { mutableStateOf(!inCustomPl) }

        AlertDialog(
            onDismissRequest = { trackToDelete = null },
            title = { Text(if (inCustomPl) "从歌单移出歌曲" else "删除本地歌曲", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (inCustomPl) {
                            "确认将「${target.title}」从歌单「$curPlName」中移出吗？"
                        } else {
                            "确认删除歌曲「${target.title}」吗？"
                        },
                        fontSize = 13.5.sp,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { alsoDeleteLocal = !alsoDeleteLocal },
                    ) {
                        Checkbox(
                            checked = alsoDeleteLocal,
                            onCheckedChange = { alsoDeleteLocal = it },
                        )
                        Text(
                            text = "同时删除手机本地音频文件（并从所有歌单移除）",
                            fontSize = 12.5.sp,
                            color = if (alsoDeleteLocal) Color(0xFFDC2626) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (alsoDeleteLocal) {
                            onDeleteTrackFile(target)
                        } else if (inCustomPl) {
                            playlistStore.removeTrack(curPlName, target.uri)
                            onPlaylistsChanged()
                        } else {
                            playlistStore.removeTrackFromAll(target.uri)
                            onPlaylistsChanged()
                        }
                        trackToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (alsoDeleteLocal) Color(0xFFDC2626) else BiliPink,
                    ),
                ) {
                    Text(if (alsoDeleteLocal) "确认删除文件" else "仅移出歌单")
                }
            },
            dismissButton = {
                TextButton(onClick = { trackToDelete = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsDialog(
    prefs: AppPrefs,
    onDismiss: () -> Unit,
    onSave: (AppPrefs) -> Unit,
) {
    var sessdata by remember { mutableStateOf(prefs.sessdata) }
    var conflictPolicy by remember { mutableStateOf(prefs.conflictPolicy) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("应用设置", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("同名文件处理策略：", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConflictPolicy.entries.forEach { policy ->
                        FilterChip(
                            selected = conflictPolicy == policy,
                            onClick = { conflictPolicy = policy },
                            label = { Text(policy.label, fontSize = 12.sp) },
                        )
                    }
                }
                OutlinedTextField(
                    value = sessdata,
                    onValueChange = { sessdata = it },
                    label = { Text("SESSDATA Cookie（选填）") },
                    placeholder = { Text("填入可尝试获取更高码率/FLAC") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "下载文件自动存入手机 Music/BiliMusic/（支持歌单子文件夹），可在「本地音乐」直接播放。",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(prefs.copy(sessdata = sessdata.trim(), conflictPolicy = conflictPolicy)) },
                colors = ButtonDefaults.buttonColors(containerColor = BiliPink),
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
