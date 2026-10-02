package com.example.mineavata.voice

import android.content.Context

/**
 * 语音唤醒的设置：唤醒提示词 + 监听总开关。
 *
 * 独立 prefs 文件（一个功能区一个文件，对齐 pet_prefs / storage_prefs 的既有约定）。
 * 开关是**整条语音监听**的总开关，与桌宠的总开关相互独立。
 */
object WakePrefs {

    private const val PREFS_NAME = "wake_prefs"
    private const val PREF_PHRASE = "phrase"
    private const val PREF_ENABLED = "enabled"

    /** 唤醒提示词；空串 = 未设置（此时永不命中） */
    fun getPhrase(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_PHRASE, "").orEmpty()

    fun setPhrase(context: Context, phrase: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_PHRASE, phrase).apply()
    }

    /** 语音监听总开关 */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_ENABLED, enabled).apply()
    }
}