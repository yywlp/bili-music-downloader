package com.bilimusic.android.core

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

data class SavedAudioResult(
    val displayName: String,
    val relativePath: String,
    val skipped: Boolean = false,
)

data class LocalTrack(
    val uri: String,
    val title: String,
    val folder: String,
    val ext: String,
    val sizeBytes: Long,
    val dateAdded: Long,
)

object AudioStorage {
    private const val ROOT_FOLDER = "BiliMusic"
    private val AUDIO_EXTS = setOf("m4a", "mp3", "flac", "wav")

    fun formatSize(bytes: Long): String {
        return if (bytes >= 1024L * 1024L) {
            "%.2f MB".format(bytes / (1024.0 * 1024.0))
        } else {
            "${bytes / 1024L} KB"
        }
    }

    fun deleteLocalTrack(context: Context, trackUri: String): Boolean {
        return runCatching {
            val parsed = Uri.parse(trackUri)
            if (parsed.scheme == "content") {
                context.contentResolver.delete(parsed, null, null) > 0
            } else {
                val path = parsed.path ?: return false
                val f = File(path)
                !f.exists() || f.delete()
            }
        }.getOrDefault(false)
    }

    suspend fun saveAudioFile(
        context: Context,
        songName: String,
        artist: String,
        isFlac: Boolean,
        conflictPolicy: ConflictPolicy,
        subFolder: String? = null,
        writer: suspend (java.io.OutputStream) -> Unit,
    ): SavedAudioResult {
        val ext = if (isFlac) ".flac" else ".m4a"
        val mime = if (isFlac) "audio/flac" else "audio/mp4"
        val safeStem = Ranker.sanitizeFilename(songName)
        val cleanSub = subFolder?.trim()?.takeIf { it.isNotEmpty() }?.let { Ranker.sanitizeFilename(it) }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, safeStem, artist, ext, mime, conflictPolicy, cleanSub, writer)
        } else {
            saveViaLegacyFile(context, safeStem, ext, conflictPolicy, cleanSub, writer)
        }
    }

    fun scanLocalTracks(context: Context): List<LocalTrack> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            scanViaMediaStore(context)
        } else {
            scanViaLegacyFiles()
        }
    }

    private fun scanViaMediaStore(context: Context): List<LocalTrack> {
        val list = mutableListOf<LocalTrack>()
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("${Environment.DIRECTORY_MUSIC}/$ROOT_FOLDER%")
        val sort = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        runCatching {
            resolver.query(collection, projection, selection, args, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                while (c.moveToNext()) {
                    val displayName = c.getString(nameCol) ?: continue
                    val ext = displayName.substringAfterLast('.', "").lowercase()
                    if (ext !in AUDIO_EXTS) continue
                    val id = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    val relPath = (c.getString(pathCol) ?: "").trimEnd('/')
                    val prefix = "${Environment.DIRECTORY_MUSIC}/$ROOT_FOLDER"
                    val sub = relPath.removePrefix(prefix).trim('/')
                    val folder = if (sub.isEmpty()) "默认目录" else sub
                    list += LocalTrack(
                        uri = uri,
                        title = displayName.substringBeforeLast('.'),
                        folder = folder,
                        ext = ext.uppercase(),
                        sizeBytes = c.getLong(sizeCol),
                        dateAdded = c.getLong(dateCol),
                    )
                }
            }
        }
        return list
    }

    @Suppress("DEPRECATION")
    private fun scanViaLegacyFiles(): List<LocalTrack> {
        val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), ROOT_FOLDER)
        if (!root.exists()) return emptyList()
        val list = mutableListOf<LocalTrack>()
        root.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            val ext = f.extension.lowercase()
            if (ext !in AUDIO_EXTS) return@forEach
            val relParent = f.parentFile?.relativeToOrNull(root)?.path.orEmpty()
            val folder = if (relParent.isEmpty() || relParent == ".") "默认目录" else relParent
            list += LocalTrack(
                uri = Uri.fromFile(f).toString(),
                title = f.nameWithoutExtension,
                folder = folder,
                ext = ext.uppercase(),
                sizeBytes = f.length(),
                dateAdded = f.lastModified() / 1000L,
            )
        }
        return list.sortedByDescending { it.dateAdded }
    }

    private suspend fun saveViaMediaStore(
        context: Context,
        stem: String,
        artist: String,
        ext: String,
        mime: String,
        conflictPolicy: ConflictPolicy,
        subFolder: String?,
        writer: suspend (java.io.OutputStream) -> Unit,
    ): SavedAudioResult {
        val resolver = context.contentResolver
        val relPath = if (subFolder != null) {
            "${Environment.DIRECTORY_MUSIC}/$ROOT_FOLDER/$subFolder/"
        } else {
            "${Environment.DIRECTORY_MUSIC}/$ROOT_FOLDER/"
        }
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        fun findExisting(displayName: String) = resolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
            arrayOf(displayName, relPath),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

        var finalName = "$stem$ext"
        val existingId = findExisting(finalName)
        if (existingId != null) {
            when (conflictPolicy) {
                ConflictPolicy.SKIP -> return SavedAudioResult(finalName, relPath, skipped = true)
                ConflictPolicy.OVERWRITE -> {
                    resolver.delete(
                        collection,
                        "${MediaStore.Audio.Media._ID} = ?",
                        arrayOf(existingId.toString()),
                    )
                }
                ConflictPolicy.RENAME -> {
                    var idx = 2
                    while (findExisting("$stem ($idx)$ext") != null) {
                        idx++
                    }
                    finalName = "$stem ($idx)$ext"
                }
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, finalName)
            put(MediaStore.Audio.Media.TITLE, stem)
            if (artist.isNotBlank()) put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relPath)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("无法在 $relPath 创建音频文件")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                writer(out)
            } ?: throw IllegalStateException("无法打开音频输出流")

            val doneValues = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            resolver.update(uri, doneValues, null, null)
            return SavedAudioResult(finalName, relPath, skipped = false)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun saveViaLegacyFile(
        context: Context,
        stem: String,
        ext: String,
        conflictPolicy: ConflictPolicy,
        subFolder: String?,
        writer: suspend (java.io.OutputStream) -> Unit,
    ): SavedAudioResult {
        val baseDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), ROOT_FOLDER)
        val dir = if (subFolder != null) File(baseDir, subFolder) else baseDir
        dir.mkdirs()
        val relLabel = if (subFolder != null) "Music/$ROOT_FOLDER/$subFolder/" else "Music/$ROOT_FOLDER/"

        var target = File(dir, "$stem$ext")
        if (target.exists()) {
            when (conflictPolicy) {
                ConflictPolicy.SKIP -> return SavedAudioResult(target.name, relLabel, skipped = true)
                ConflictPolicy.OVERWRITE -> target.delete()
                ConflictPolicy.RENAME -> {
                    var idx = 2
                    while (File(dir, "$stem ($idx)$ext").exists()) idx++
                    target = File(dir, "$stem ($idx)$ext")
                }
            }
        }

        try {
            FileOutputStream(target).use { writer(it) }
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
            return SavedAudioResult(target.name, relLabel, skipped = false)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }
}
