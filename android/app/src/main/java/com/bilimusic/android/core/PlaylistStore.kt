package com.bilimusic.android.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PlaylistStore(context: Context) {
    private val file = File(context.filesDir, "playlists.json")
    private val playlists = LinkedHashMap<String, MutableList<String>>()

    init {
        load()
    }

    fun load() {
        playlists.clear()
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("playlists") ?: return
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isEmpty()) continue
                val tracks = mutableListOf<String>()
                val tArr = obj.optJSONArray("tracks")
                if (tArr != null) {
                    for (j in 0 until tArr.length()) {
                        val u = tArr.optString(j)
                        if (u.isNotBlank()) tracks += u
                    }
                }
                playlists[name] = tracks
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        playlists.forEach { (name, uris) ->
            val tArr = JSONArray()
            uris.forEach { tArr.put(it) }
            arr.put(JSONObject().put("name", name).put("tracks", tArr))
        }
        file.writeText(JSONObject().put("playlists", arr).toString(2), Charsets.UTF_8)
    }

    fun listNames(): List<String> = playlists.keys.toList()

    fun getTrackUris(name: String): List<String> = playlists[name]?.toList() ?: emptyList()

    fun create(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || clean in playlists) return false
        playlists[clean] = mutableListOf()
        save()
        return true
    }

    fun delete(name: String): Boolean {
        if (playlists.remove(name) == null) return false
        save()
        return true
    }

    fun rename(oldName: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isEmpty() || oldName !in playlists || clean in playlists) return false
        val rebuilt = LinkedHashMap<String, MutableList<String>>()
        playlists.forEach { (k, v) ->
            rebuilt[if (k == oldName) clean else k] = v
        }
        playlists.clear()
        playlists.putAll(rebuilt)
        save()
        return true
    }

    fun addTrack(playlistName: String, trackUri: String): Boolean {
        val list = playlists[playlistName] ?: return false
        if (trackUri in list) return false
        list += trackUri
        save()
        return true
    }

    fun removeTrack(playlistName: String, trackUri: String): Boolean {
        val list = playlists[playlistName] ?: return false
        val removed = list.remove(trackUri)
        if (removed) save()
        return removed
    }

    fun removeTrackFromAll(trackUri: String): Int {
        var count = 0
        playlists.values.forEach { list ->
            if (list.remove(trackUri)) count++
        }
        if (count > 0) save()
        return count
    }
}
