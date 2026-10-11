package online.bmarian.bus

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import online.bmarian.bus.data.Api
import online.bmarian.bus.data.Favorites
import online.bmarian.bus.data.FavoritesSync
import online.bmarian.bus.data.LanguageSync
import online.bmarian.bus.data.Repository
import online.bmarian.bus.surfaces.updateSurfacesOnChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Services.init(this)
    }
}

object Services {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var repository: Repository
        private set
    lateinit var favorites: Favorites
        private set

    fun init(app: Application) {
        Api.init(app)
        repository = Repository(app)
        favorites = Favorites(app) { stops, routes, updatedAt -> FavoritesSync.publish(app, stops, routes, updatedAt) }
        scope.launch {
            repository.loadCached()
            repository.refreshNetwork(now = true)
        }
        FavoritesSync.pull(app)
        LanguageSync.pull(app)
        scope.launch { updateSurfacesOnChange(app) }
    }

    fun setLanguage(context: Context, tag: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        if (manager.applicationLocales.toLanguageTags() == tag) return
        manager.applicationLocales = LocaleList.forLanguageTags(tag)
    }
}
