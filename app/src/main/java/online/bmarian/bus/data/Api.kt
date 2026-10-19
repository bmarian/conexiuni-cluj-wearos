package online.bmarian.bus.data

import android.content.Context
import online.bmarian.bus.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpException(val code: Int) : IOException("HTTP $code")

object Api {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }

    lateinit var client: OkHttpClient
        private set

    fun init(context: Context) {
        client = OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "http"), 30L * 1024 * 1024))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "ConexiuniClujWear/${BuildConfig.VERSION_NAME}").build())
            }
            .build()
    }

    fun url(path: String, city: String = Cities.current.value) = BuildConfig.API_BASE + "$city/" + path

    /** Not under a city: the city list and transfers. */
    fun sharedUrl(path: String) = BuildConfig.API_BASE + path

    private suspend fun send(request: Request, via: OkHttpClient = client): Response = suspendCancellableCoroutine { cont ->
        val call = via.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, _, _ -> response.close() }
            }
        })
    }

    private suspend fun <T> fetch(request: Request, via: OkHttpClient = client, read: (Response) -> T): T {
        val response = send(request, via)
        try {
            return withContext(Dispatchers.IO) { read(response) }
        } finally {
            response.close()
        }
    }

    suspend fun text(url: String, fresh: Boolean): String {
        val request = Request.Builder().url(url).apply { if (fresh) cacheControl(CacheControl.FORCE_NETWORK) }.build()
        return fetch(request) { response ->
            if (!response.isSuccessful) throw HttpException(response.code)
            response.body?.string() ?: ""
        }
    }

    suspend inline fun <reified T> get(path: String, city: String = Cities.current.value): T {
        val text = text(url(path, city), fresh = false)
        return withContext(Dispatchers.Default) { json.decodeFromString<T>(text) }
    }

    suspend inline fun <reified T> getFresh(path: String, city: String = Cities.current.value): T {
        val text = text(url(path, city), fresh = true)
        return withContext(Dispatchers.Default) { json.decodeFromString<T>(text) }
    }

    suspend inline fun <reified T> getShared(path: String): T {
        val text = text(sharedUrl(path), fresh = true)
        return withContext(Dispatchers.Default) { json.decodeFromString<T>(text) }
    }

    suspend fun shapePolylines(shapeIds: List<String>, city: String): Map<String, String> =
        get("shapes?format=polyline&shape_ids=${shapeIds.joinToString(",")}", city)

    suspend fun networkVersion(city: String): NetworkVersion = getFresh("network/version", city)

    private val bundleClient: OkHttpClient by lazy { client.newBuilder().cache(null).build() }

    suspend fun networkText(city: String): String = fetch(Request.Builder().url(url("network", city)).build(), bundleClient) { response ->
        if (!response.isSuccessful) throw HttpException(response.code)
        response.body?.string() ?: ""
    }

    suspend fun createTransfer(): TransferLink {
        val request = Request.Builder().url(sharedUrl("transfer")).post(ByteArray(0).toRequestBody()).build()
        return fetch(request) { response ->
            if (!response.isSuccessful) throw HttpException(response.code)
            json.decodeFromString(response.body?.string() ?: "")
        }
    }

    sealed interface TransferRead {
        data object Waiting : TransferRead
        data object Gone : TransferRead
        class Received(val data: JsonElement) : TransferRead
    }

    suspend fun readTransfer(link: TransferLink): TransferRead {
        val request = Request.Builder().url(sharedUrl("transfer/${link.code}"))
            .header("X-Link-Secret", link.secret)
            .header("Cache-Control", "no-store")
            .build()
        return fetch(request) { response ->
            when {
                response.code == 404 -> TransferRead.Gone
                !response.isSuccessful -> throw HttpException(response.code)
                response.code == 204 -> TransferRead.Waiting
                else -> TransferRead.Received(json.parseToJsonElement(response.body?.string() ?: ""))
            }
        }
    }

    suspend fun deleteTransfer(link: TransferLink) {
        runCatching {
            send(Request.Builder().url(sharedUrl("transfer/${link.code}")).delete().header("X-Link-Secret", link.secret).build()).close()
        }
    }
}
