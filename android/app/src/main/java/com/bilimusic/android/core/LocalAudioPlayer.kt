package com.bilimusic.android.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.core.content.ContextCompat

enum class PlayMode(val label: String) {
    LIST_LOOP("列表循环"),
    SINGLE_LOOP("单曲循环"),
    SHUFFLE("随机播放"),
    SEQUENTIAL("顺序播放"),
}

class LocalAudioPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private var noisyRegistered = false
    var onCompleted: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onAutoPaused: (() -> Unit)? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (pause()) {
                    onAutoPaused?.invoke()
                }
            }
        }
    }

    private fun registerNoisyReceiver() {
        if (noisyRegistered) return
        runCatching {
            ContextCompat.registerReceiver(
                context,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyRegistered) return
        runCatching {
            context.unregisterReceiver(noisyReceiver)
        }
        noisyRegistered = false
    }

    fun play(uriString: String) {
        releasePlayerOnly()
        registerNoisyReceiver()
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(context, Uri.parse(uriString))
                setOnPreparedListener { it.start() }
                setOnCompletionListener { onCompleted?.invoke() }
                setOnErrorListener { _, what, extra ->
                    onError?.invoke("播放异常 ($what/$extra)")
                    true
                }
                prepareAsync()
            }
            mediaPlayer = mp
        } catch (e: Exception) {
            onError?.invoke(e.message ?: "无法播放该音频")
        }
    }

    fun isPlaying(): Boolean = runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false)

    fun pause(): Boolean {
        val mp = mediaPlayer ?: return false
        return runCatching {
            if (mp.isPlaying) {
                mp.pause()
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    fun togglePause(): Boolean {
        val mp = mediaPlayer ?: return false
        return runCatching {
            if (mp.isPlaying) {
                mp.pause()
                false
            } else {
                registerNoisyReceiver()
                mp.start()
                true
            }
        }.getOrDefault(false)
    }

    fun currentPositionMs(): Int = runCatching { mediaPlayer?.currentPosition ?: 0 }.getOrDefault(0)

    fun durationMs(): Int = runCatching { mediaPlayer?.duration ?: 0 }.getOrDefault(0)

    fun seekTo(ms: Int) {
        runCatching { mediaPlayer?.seekTo(ms.coerceAtLeast(0)) }
    }

    private fun releasePlayerOnly() {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
    }

    fun release() {
        unregisterNoisyReceiver()
        releasePlayerOnly()
    }
}
