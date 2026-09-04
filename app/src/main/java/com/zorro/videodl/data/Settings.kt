package com.zorro.videodl.data

import android.content.Context
import com.zorro.videodl.core.Quality

/** Small, synchronous preference bag — nothing here is worth a DataStore. */
class Settings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var defaultQuality: Quality
        get() = runCatching { Quality.valueOf(prefs.getString(KEY_QUALITY, null) ?: "") }
            .getOrDefault(Quality.P1080)
        set(value) = prefs.edit().putString(KEY_QUALITY, value.name).apply()

    /** Read the clipboard on resume and offer whatever link is sitting there. */
    var clipboardWatch: Boolean
        get() = prefs.getBoolean(KEY_CLIPBOARD, true)
        set(value) = prefs.edit().putBoolean(KEY_CLIPBOARD, value).apply()

    /** Start downloading a detected link without asking first. */
    var clipboardAutoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTOSTART, value).apply()

    /** Links already offered, so the same clipboard entry is not re-suggested forever. */
    var lastHandledClip: String?
        get() = prefs.getString(KEY_LAST_CLIP, null)
        set(value) = prefs.edit().putString(KEY_LAST_CLIP, value).apply()

    private companion object {
        const val KEY_QUALITY = "default_quality"
        const val KEY_CLIPBOARD = "clipboard_watch"
        const val KEY_AUTOSTART = "clipboard_autostart"
        const val KEY_LAST_CLIP = "last_clip"
    }
}
