package online.bmarian.bus

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import online.bmarian.bus.data.Api
import online.bmarian.bus.data.Cities
import online.bmarian.bus.data.CitySync
import online.bmarian.bus.data.ColorSync
import online.bmarian.bus.data.Favorites
import online.bmarian.bus.data.FavoritesSync
import online.bmarian.bus.data.LanguageSync
import online.bmarian.bus.data.sync.Sync
import online.bmarian.bus.data.Repository
import online.bmarian.bus.data.UserLocation
import online.bmarian.bus.follow.Reminders
import online.bmarian.bus.surfaces.updateSurfacesOnChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

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
    lateinit var sync: Sync
        private set

    fun init(app: Application) {
        Api.init(app)
        Cities.init(app)
        repository = Repository(app)
        favorites = Favorites(app) { city, stops, routes, updatedAt -> FavoritesSync.publish(app, city, stops, routes, updatedAt) }
        sync = Sync(app)
        scope.launch {
            repository.loadCached()
            repository.refreshNetwork(now = true)
        }
        scope.launch { openOnCity(app) }
        // Records are per city, so it waits for the city list.
        scope.launch {
            Cities.list.first { it.isNotEmpty() }
            sync.start(scope)
            sync.syncNow()
        }
        FavoritesSync.pull(app)
        LanguageSync.pull(app)
        CitySync.pull(app)
        ColorSync.pull(app)
        Reminders.init(app)
        scope.launch { updateSurfacesOnChange(app) }
    }

    /** Shows [slug]: its bundle and favorites. */
    fun showCity(slug: String) {
        if (slug == Cities.current.value) return
        repository.use(slug)
        favorites.use(slug)
        Cities.show(slug)
        scope.launch {
            repository.loadCached()
            repository.refreshNetwork(now = true)
        }
    }

    // Without a word from the phone, the nearest city to the last fix; none is asked for here.
    private suspend fun openOnCity(context: Context) {
        val shown = Cities.current.value
        Cities.reload()
        if (Cities.current.value == shown) showCity(Cities.resolve(UserLocation.recent(context, city = null)))
    }

    fun setLanguage(context: Context, tag: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        if (manager.applicationLocales.toLanguageTags() == tag) return
        manager.applicationLocales = LocaleList.forLanguageTags(tag)
    }
}
