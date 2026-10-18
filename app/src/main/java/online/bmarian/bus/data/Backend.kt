package online.bmarian.bus.data

import android.content.Context
import android.content.SharedPreferences
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import online.bmarian.bus.BuildConfig

/**
 * The server is moving from one city at /api/… to many at /api/{city}/…. Each request goes where the server of the day
 * answers: only the new server lists its cities, and a path the server no longer knows means it changed since we last looked.
 */
object Backend : Interceptor {
    const val CITY = "cluj"
    var base = BuildConfig.API_BASE
    // Site-wide on the new server, not under a city.
    private val shared = listOf("cities", "transfer")

    private lateinit var prefs: SharedPreferences
    @Volatile private var perCity: Boolean? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("backend", Context.MODE_PRIVATE)
        if (prefs.contains("perCity")) perCity = prefs.getBoolean("perCity", false)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val path = url.removePrefix(base)
        if (path.length == url.length) return chain.proceed(request)
        val layout = perCity ?: synchronized(this) { perCity ?: probe(chain) } ?: return chain.proceed(request)
        val response = chain.proceed(route(request, path, layout))
        if (response.code != 404) return response
        // A path the server does not know: it may have moved since we last looked. The answer is read first, as a
        // call cannot hold one response open while it makes another.
        val answer = response.body?.let { it.bytes().toResponseBody(it.contentType()) }
        val now = synchronized(this) { probe(chain) }
        if (now == null || now == layout) return response.newBuilder().body(answer).build()
        return chain.proceed(route(request, path, now))
    }

    fun route(request: Request, path: String, perCity: Boolean): Request {
        if (!perCity || shared.any { path == it || path.startsWith("$it/") || path.startsWith("$it?") }) return request
        return request.newBuilder().url(base + "$CITY/" + path).build()
    }

    private fun probe(chain: Interceptor.Chain): Boolean? {
        val probe = Request.Builder().url(base + "cities").cacheControl(CacheControl.FORCE_NETWORK)
        chain.request().header("User-Agent")?.let { probe.header("User-Agent", it) }
        val found = runCatching {
            chain.proceed(probe.build()).use { decide(it.code, it.body?.string().orEmpty()) }
        }.getOrNull() ?: return null
        perCity = found
        if (::prefs.isInitialized) prefs.edit().putBoolean("perCity", found).apply()
        return found
    }

    fun decide(code: Int, body: String): Boolean? = when {
        code == 200 && body.trimStart().startsWith("[") -> true
        code == 404 -> false
        else -> null
    }
}
