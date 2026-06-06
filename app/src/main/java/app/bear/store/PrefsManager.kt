package app.bear.store

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class PrefsManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "bear_prefs"
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT  = 1
        const val THEME_DARK   = 2
        const val LANG_TH = "th"
        const val LANG_EN = "en"
    }

    var themeMode: Int
        get() = prefs.getInt("theme", THEME_SYSTEM)
        set(v) { prefs.edit().putInt("theme", v).apply() }

    var language: String
        get() = prefs.getString("lang", LANG_TH) ?: LANG_TH
        set(v) { prefs.edit().putString("lang", v).apply() }

    var autoUpdate: Boolean
        get() = prefs.getBoolean("auto_update", false)
        set(v) { prefs.edit().putBoolean("auto_update", v).apply() }

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (themeMode) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK  -> AppCompatDelegate.MODE_NIGHT_YES
                else        -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
