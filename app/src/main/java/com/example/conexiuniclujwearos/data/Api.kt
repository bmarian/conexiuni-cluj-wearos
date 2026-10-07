package com.example.conexiuniclujwearos.data

import android.content.Context
import com.example.conexiuniclujwearos.BuildConfig
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
import java.net.URLEncoder
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

    fun url(path: String) = BuildConfig.API_BASE + path

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private suspend fun send(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
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

    // The callback comes with the headers; the body is still on its way, so it is read off
    // the main thread.
    private suspend fun <T> fetch(request: Request, read: (Response) -> T): T {
        val response = send(request)
        try {
            return withContext(Dispatchers.IO) { read(response) }
        } finally {
            response.close()
        }
    }

    suspend fun getText(path: String): String = fetch(Request.Builder().url(url(path)).build()) { response ->
        if (!response.isSuccessful) throw HttpException(response.code)
        response.body?.string() ?: ""
    }

    // The watch CPU is slow, so parsing stays off the main thread.
    suspend inline fun <reified T> get(path: String): T {
        val text = getText(path)
        return withContext(Dispatchers.Default) { json.decodeFromString<T>(text) }
    }

    // Whatever the HTTP cache holds, however old, without touching the network.
    suspend fun cachedText(path: String): String? = runCatching {
        fetch(Request.Builder().url(url(path)).cacheControl(CacheControl.FORCE_CACHE).build()) { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()

    suspend inline fun <reified T> cached(path: String): T? {
        val text = cachedText(path) ?: return null
        return withContext(Dispatchers.Default) { runCatching { json.decodeFromString<T>(text) }.getOrNull() }
    }

    fun stopInfoPath(stopId: Int) = "stop_info?stop_id=$stopId"
    fun timetablePath(shortName: String) = "timetable?route_short_name=${encode(shortName)}"
    fun stopTimesPath(shortName: String) = "stop_times?route_short_name=${encode(shortName)}"

    suspend fun routesText(): String = getText("routes")
    suspend fun stopsText(): String = getText("stops")
    suspend fun stopInfo(stopId: Int): StopInfo = get(stopInfoPath(stopId))
    suspend fun timetable(shortName: String): Timetable = get(timetablePath(shortName))
    suspend fun stopTimes(shortName: String): List<StopTime> = get(stopTimesPath(shortName))
    suspend fun shapes(shapeIds: List<String>): List<ShapePoint> = get("shapes?shape_ids=${shapeIds.joinToString(",")}")
    suspend fun vehicles(tripIds: List<String>): List<Vehicle> = get("vehicles?trip_ids=${tripIds.joinToString(",")}")

    suspend fun createTransfer(): TransferLink {
        val request = Request.Builder().url(url("transfer")).post(ByteArray(0).toRequestBody()).build()
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
        val request = Request.Builder().url(url("transfer/${link.code}"))
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
            send(Request.Builder().url(url("transfer/${link.code}")).delete().header("X-Link-Secret", link.secret).build()).close()
        }
    }
}
