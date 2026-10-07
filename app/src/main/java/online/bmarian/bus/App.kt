package online.bmarian.bus

import android.app.Application
import android.app.LocaleManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import online.bmarian.bus.complication.MainComplicationService
import online.bmarian.bus.data.Api
import online.bmarian.bus.data.Favorites
import online.bmarian.bus.data.Repository
import online.bmarian.bus.tile.MainTileService
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
        favorites = Favorites(app) { refreshSurfaces(app) }
        scope.launch { repository.loadCached() }
    }

    // The tile and the complication read the favorites, so they redraw when those change.
    fun refreshSurfaces(context: Context) {
        runCatching { TileService.getUpdater(context).requestUpdate(MainTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, MainComplicationService::class.java))
                .requestUpdateAll()
        }
    }

    fun setLanguage(context: Context, tag: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        if (manager.applicationLocales.toLanguageTags() == tag) return
        manager.applicationLocales = LocaleList.forLanguageTags(tag)
    }
}
