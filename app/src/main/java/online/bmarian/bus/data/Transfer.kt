package online.bmarian.bus.data

import online.bmarian.bus.BuildConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Same format as frontend/src/utils/transferLink.ts. A scanned code carries a key after
// the #, which never reaches the server, and the sender seals the data with it.
object Transfer {
    private val random = SecureRandom()

    fun newKey(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun url(code: String, key: String) = "${BuildConfig.SITE_ORIGIN}/settings?send=$code#k=$key"

    fun spaced(code: String) = if (code.length == 6) "${code.take(3)} ${code.drop(3)}" else code

    /** Throws when the data is sealed and this key can't open it. */
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

    /** Null for anything that isn't a settings export. */
    fun describe(data: JsonElement): Summary? {
        val export = data as? JsonObject ?: return null
        val favorites = export["favorites"] as? JsonObject
        if (favorites == null && export["settings"] !is JsonObject) return null
        return Summary(
            stops = Favorites.parseStopIds(favorites?.get("stops")).size,
            lines = Favorites.parseFavoriteRoutes(favorites?.get("routes")).size,
        )
    }

    /** Only favorites and the language: the rest of the settings belong to the website. */
    fun localeOf(data: JsonElement): String? {
        val settings = (data as? JsonObject)?.get("settings") as? JsonObject ?: return null
        return (settings["locale"] as? JsonPrimitive)?.contentOrNull?.takeIf { it == "ro" || it == "en" }
    }
}
