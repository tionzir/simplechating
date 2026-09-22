package com.smiplechating

import android.content.Context
import androidx.preference.PreferenceManager

/** 统一读写偏好设置（与 res/xml/settings_prefs.xml 的 key 一一对应） */
object Prefs {
    const val KEY_SERVER_BASE = "server_base"
    const val KEY_SERVER_TIMEOUT = "server_timeout"
    const val KEY_USE_LOCAL_ONLY = "use_local_only"
    const val KEY_WATCH_QQ = "watch_qq"
    const val KEY_WATCH_WECHAT = "watch_wechat"
    const val KEY_MAX_MESSAGES = "max_messages"
    const val KEY_AUTO_TRIGGER = "auto_trigger"
    const val KEY_FLOAT_BALL = "float_ball_enabled"
    const val KEY_FLOAT_ALPHA = "float_ball_alpha"

    fun sp(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun serverBase(ctx: Context): String =
        sp(ctx).getString(KEY_SERVER_BASE, "http://127.0.0.1:8765") ?: "http://127.0.0.1:8765"

    fun timeoutMs(ctx: Context): Long =
        (sp(ctx).getString(KEY_SERVER_TIMEOUT, "8000") ?: "8000").toLongOrNull() ?: 8000L

    fun localOnly(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_USE_LOCAL_ONLY, true)

    fun watchQq(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_WATCH_QQ, true)

    fun watchWechat(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_WATCH_WECHAT, true)

    fun maxMessages(ctx: Context): Int =
        (sp(ctx).getString(KEY_MAX_MESSAGES, "12") ?: "12").toIntOrNull() ?: 12

    fun autoTrigger(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO_TRIGGER, true)

    fun floatBallEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_FLOAT_BALL, true)

    fun floatAlpha(ctx: Context): Int = sp(ctx).getInt(KEY_FLOAT_ALPHA, 90)
}
