package com.bilimusic.android.core

import android.content.Context

data class AppPrefs(
    val sessdata: String = "",
    val rankMode: RankMode = RankMode.LOSSLESS_FIRST,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.RENAME,
    val darkTheme: Boolean = false,
)

class PrefsStore(context: Context) {
    private val sp = context.getSharedPreferences("bilimusic_prefs", Context.MODE_PRIVATE)

    fun load(): AppPrefs {
        val mode = runCatching {
            RankMode.valueOf(sp.getString("rank_mode", RankMode.LOSSLESS_FIRST.name)!!)
        }.getOrDefault(RankMode.LOSSLESS_FIRST)

        val policy = runCatching {
            ConflictPolicy.valueOf(sp.getString("conflict_policy", ConflictPolicy.RENAME.name)!!)
        }.getOrDefault(ConflictPolicy.RENAME)

        return AppPrefs(
            sessdata = sp.getString("sessdata", "").orEmpty(),
            rankMode = mode,
            conflictPolicy = policy,
            darkTheme = sp.getBoolean("dark_theme", false),
        )
    }

    fun save(prefs: AppPrefs) {
        sp.edit()
            .putString("sessdata", prefs.sessdata)
            .putString("rank_mode", prefs.rankMode.name)
            .putString("conflict_policy", prefs.conflictPolicy.name)
            .putBoolean("dark_theme", prefs.darkTheme)
            .apply()
    }
}
