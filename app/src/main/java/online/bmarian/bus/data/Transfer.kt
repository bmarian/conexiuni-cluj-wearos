package online.bmarian.bus.data

import online.bmarian.bus.BuildConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import online.bmarian.bus.data.Cities
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Same format as frontend/src/utils/transferLink.ts.
object Transfer {
    private val random = SecureRandom()

    fun newKey(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun url(code: String, key: String) = "${BuildConfig.SITE_ORIGIN}/settings?send=$code#k=$key"

    fun spaced(code: String) = if (code.length == 6) "${code.take(3)} ${code.drop(3)}" else code

    fun open(payload: JsonElement, key: String): JsonElement {
        val sealed = payload as? JsonObject ?: return payload
        if ((sealed["sealed"] as? JsonPrimitive)?.intOrNull != 1) return payload
        val iv = (sealed["iv"] as? JsonPrimitive)?.contentOrNull ?: return payload
        val data = (sealed["data"] as? JsonPrimitive)?.contentOrNull ?: return payload
        val decoder = Base64.getUrlDecoder()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(decoder.decode(key), "AES"), GCMParameterSpec(128, decoder.decode(iv)))
        return Api.json.parseToJsonElement(String(cipher.doFinal(decoder.decode(data)), Charsets.UTF_8))
    }

    data class Summary(val stops: Int, val lines: Int)

    /** Each city's favorites; an export without cities is Cluj's. */
    fun favoritesByCity(export: JsonObject): Map<String, JsonObject> {
        val cities = export["cities"] as? JsonObject
            ?: return mapOf(Cities.DEFAULT to (export["favorites"] as? JsonObject ?: JsonObject(emptyMap())))
        return cities.filter { (slug, entry) -> Cities.known(slug) && entry is JsonObject }
            .mapValues { ((it.value as JsonObject)["favorites"] as? JsonObject) ?: JsonObject(emptyMap()) }
    }

    fun describe(data: JsonElement): Summary? {
        val export = data as? JsonObject ?: return null
        if (export["favorites"] !is JsonObject && export["settings"] !is JsonObject) return null
        val all = favoritesByCity(export).values
        return Summary(
            stops = all.sumOf { Favorites.parseStopIds(it["stops"]).size },
            lines = all.sumOf { Favorites.parseFavoriteRoutes(it["routes"]).size },
        )
    }

    /** Only favorites and language; other settings are web-only. */
    fun localeOf(data: JsonElement): String? {
        val settings = (data as? JsonObject)?.get("settings") as? JsonObject ?: return null
        return (settings["locale"] as? JsonPrimitive)?.contentOrNull?.takeIf { it == "ro" || it == "en" }
    }
}
