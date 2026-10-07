package com.example.conexiuniclujwearos.presentation.map

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.conexiuniclujwearos.BuildConfig
import com.example.conexiuniclujwearos.data.Api
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/** CARTO dark tiles, kept decoded in memory and on disk through the HTTP cache. */
object TileLoader {
    private val memory = LruCache<String, ImageBitmap>(64)
    private val inFlight = HashSet<String>()
    private val failed = HashMap<String, Long>()

    // Bumped whenever a tile arrives, so the map redraws.
    val version = mutableIntStateOf(0)

    private fun key(z: Int, x: Int, y: Int) = "$z/$x/$y"

    private fun url(z: Int, x: Int, y: Int): String {
        val sub = "abcd"[(x + y).mod(4)]
        val query = if (BuildConfig.CARTO_KEY.isNotEmpty()) "?key=${BuildConfig.CARTO_KEY}" else ""
        return "https://$sub.basemaps.cartocdn.com/dark_all/$z/$x/$y.png$query"
    }

    fun cached(z: Int, x: Int, y: Int): ImageBitmap? = memory.get(key(z, x, y))

    fun request(scope: CoroutineScope, z: Int, x: Int, y: Int) {
        val key = key(z, x, y)
        if (memory.get(key) != null || key in inFlight) return
        failed[key]?.let { if (System.currentTimeMillis() - it < 15_000) return }
        inFlight += key
        scope.launch {
            val image = withContext(Dispatchers.IO) {
                runCatching {
                    Api.client.newCall(Request.Builder().url(url(z, x, y)).build()).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val bytes = response.body?.bytes() ?: return@use null
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }
                }.getOrNull()
            }
            inFlight -= key
            if (image != null) {
                memory.put(key, image)
                failed -= key
                version.intValue++
            } else {
                failed[key] = System.currentTimeMillis()
            }
        }
    }
}
