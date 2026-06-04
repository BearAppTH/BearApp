package app.bear.store

import android.app.Application
import android.content.Context

class BearApplication : Application() {

    override fun attachBaseContext(base: Context) {
        val lang = base.getSharedPreferences("bear_prefs", Context.MODE_PRIVATE)
            .getString("lang", PrefsManager.LANG_TH) ?: PrefsManager.LANG_TH
        super.attachBaseContext(LocaleHelper.applyLocale(base, lang))
    }

    override fun onCreate() {
        super.onCreate()
        PrefsManager(this).applyTheme()
    }
}
