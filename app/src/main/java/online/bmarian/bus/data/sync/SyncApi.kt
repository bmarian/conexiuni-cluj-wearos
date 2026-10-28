package online.bmarian.bus.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import online.bmarian.bus.data.Api
import java.io.IOException

class SyncHttpException(val status: Int, val body: JsonObject) : IOException("sync: status $status") {
    val error get() = (body["error"] as? JsonPrimitive)?.contentOrNull
    fun number(key: String) = (body[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
}

/** The /api/sync calls of protocol.md. Port of utils/sync/api.ts. */
object SyncApi {
    private val jsonType = "application/json".toMediaType()

    private suspend fun call(method: String, path: String, body: JsonElement? = null, headers: Map<String, String> = emptyMap()): JsonObject? {
        val builder = Request.Builder().url(Api.sharedUrl("sync$path")).cacheControl(CacheControl.FORCE_NETWORK)
        headers.forEach { (name, value) -> builder.header(name, value) }
        builder.method(method, body?.toString()?.toRequestBody(jsonType) ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody() else null)
        return Api.fetch(builder.build()) { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (!response.isSuccessful) throw SyncHttpException(response.code, parsed ?: JsonObject(emptyMap()))
            parsed
        }
    }

    class Slot(val code: String, val secret: String)
    class SlotRead(val pub: String?, val scanned: Boolean, val messages: List<String>)

    private fun pair(slot: Slot) = mapOf("X-Pair-Secret" to slot.secret)
    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

    suspend fun openSlot(commit: String): Slot =
        call("POST", "/pair", buildJsonObject { put("commit", commit) })!!.let { Slot(it.text("code"), it.text("secret")) }

    suspend fun claimSlot(code: String, pub: String, scanned: Boolean): Pair<String, String> =
        call("POST", "/pair/$code/claim", buildJsonObject { put("pub", pub); put("scanned", scanned) })!!.let { it.text("commit") to it.text("secret") }

    suspend fun readSlot(slot: Slot): SlotRead = call("GET", "/pair/${slot.code}", headers = pair(slot))!!.let { o ->
        SlotRead(
            (o["pub"] as? JsonPrimitive)?.contentOrNull,
            (o["scanned"] as? JsonPrimitive)?.contentOrNull == "true",
            o["messages"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    suspend fun revealKey(slot: Slot, pub: String) {
        call("PUT", "/pair/${slot.code}/pub", buildJsonObject { put("pub", pub) }, pair(slot))
    }

    suspend fun sendMessage(slot: Slot, message: String) {
        call("POST", "/pair/${slot.code}/messages", buildJsonObject { put("message", message) }, pair(slot))
    }

    suspend fun closeSlot(slot: Slot) {
        runCatching { call("DELETE", "/pair/${slot.code}", headers = pair(slot)) }
    }

    class Member(val chain: String, val token: String)
    class Registered(val chain: String?, val epoch: Long, val device: String, val token: String)
    class DeviceState(val id: String, val kind: String, val created: Long, val seen: Long)
    class RecordState(val name: String, val rev: Long)
    class ChainState(val epoch: Long, val device: String, val devices: List<DeviceState>, val records: List<RecordState>)

    private fun bearer(m: Member) = mapOf("Authorization" to "Bearer ${m.token}")
    private fun JsonObject.registered() =
        Registered((this["chain"] as? JsonPrimitive)?.contentOrNull, this["epoch"]!!.jsonPrimitive.long, text("device"), text("token"))

    suspend fun createChain(join: String, kind: String): Registered =
        call("POST", "/chains", buildJsonObject { put("join", join); put("kind", kind) })!!.registered()

    suspend fun joinChain(chain: String, join: String, kind: String): Registered =
        call("POST", "/chains/$chain/devices", buildJsonObject { put("join", join); put("kind", kind) })!!.registered()

    suspend fun chainState(m: Member): ChainState = call("GET", "/chains/${m.chain}", headers = bearer(m))!!.let { o ->
        ChainState(
            o["epoch"]!!.jsonPrimitive.long,
            o.text("device"),
            o["devices"]!!.jsonArray.map { it.jsonObject }.map {
                DeviceState(it.text("id"), it.text("kind"), it["created"]!!.jsonPrimitive.long, it["seen"]!!.jsonPrimitive.long)
            },
            o["records"]!!.jsonArray.map { it.jsonObject }.map { RecordState(it.text("name"), it["rev"]!!.jsonPrimitive.long) },
        )
    }

    suspend fun readRecord(m: Member, name: String): Pair<Long, String> =
        call("GET", "/chains/${m.chain}/records/$name", headers = bearer(m))!!.let { it["rev"]!!.jsonPrimitive.long to it.text("body") }

    suspend fun writeRecord(m: Member, name: String, epoch: Long, base: Long, body: String): Long =
        call(
            "PUT", "/chains/${m.chain}/records/$name",
            buildJsonObject { put("epoch", epoch); put("base", base); put("body", body) }, bearer(m),
        )!!["rev"]!!.jsonPrimitive.long

    suspend fun readWrap(m: Member): Pair<Long, String> =
        call("GET", "/chains/${m.chain}/wrap", headers = bearer(m))!!.let { it["epoch"]!!.jsonPrimitive.long to it.text("body") }

    suspend fun rotate(
        m: Member,
        epoch: Long,
        seen: List<RecordState>,
        join: String,
        remove: List<String>,
        records: List<Pair<String, String>>,
        wraps: List<Pair<String, String>>,
    ): Long =
        call("POST", "/chains/${m.chain}/rotate", buildJsonObject {
            put("epoch", epoch)
            put("seen", buildJsonArray { seen.forEach { add(buildJsonObject { put("name", it.name); put("rev", it.rev) }) } })
            put("join", join)
            put("remove", JsonArray(remove.map(::JsonPrimitive)))
            put("records", buildJsonArray { records.forEach { add(buildJsonObject { put("name", it.first); put("body", it.second) }) } })
            put("wraps", buildJsonArray { wraps.forEach { add(buildJsonObject { put("device", it.first); put("body", it.second) }) } })
        }, bearer(m))!!["epoch"]!!.jsonPrimitive.long

    suspend fun leave(m: Member) {
        call("DELETE", "/chains/${m.chain}/devices/self", headers = bearer(m))
    }

    suspend fun deleteChain(m: Member) {
        call("DELETE", "/chains/${m.chain}", headers = bearer(m))
    }
}
