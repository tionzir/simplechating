package com.jev.chat

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** 统一读写设置（与 SettingsActivity 的 preference key 对应） */
object Prefs {
    const val KEY_SERVER_URL = "pref_server_url"
    const val KEY_AUTO = "pref_auto"
    const val KEY_TARGET_QQ = "pref_target_qq"
    const val KEY_TARGET_WX = "pref_target_wx"
    const val KEY_DEBOUNCE = "pref_debounce"
    const val KEY_CANDIDATES = "pref_candidates"

    const val DEFAULT_URL = "http://127.0.0.1:8765"

    fun get(ctx: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(ctx)

    fun serverUrl(ctx: Context): String =
        get(ctx).getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL

    fun auto(ctx: Context): Boolean = get(ctx).getBoolean(KEY_AUTO, true)
    fun listenQQ(ctx: Context): Boolean = get(ctx).getBoolean(KEY_TARGET_QQ, true)
    fun listenWX(ctx: Context): Boolean = get(ctx).getBoolean(KEY_TARGET_WX, true)
    fun debounce(ctx: Context): Long =
        (get(ctx).getString(KEY_DEBOUNCE, "800") ?: "800").toLongOrNull() ?: 800L
    fun candidates(ctx: Context): Int =
        (get(ctx).getString(KEY_CANDIDATES, "3") ?: "3").toIntOrNull() ?: 3
}
