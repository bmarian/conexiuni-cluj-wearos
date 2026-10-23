package online.bmarian.bus.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Pairing messages (protocol.md §2.6), held to the same strict, canonical rules as records. Port of utils/sync/messages.ts. */
object SyncMessages {
    sealed interface Message
    data class Hello(val kind: String, val label: String, val syncs: Boolean, val devices: Int) : Message
    data object Ready : Message
    data class Secret(val chain: String, val epoch: Long, val s: String) : Message

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun write(m: Message): String = when (m) {
        is Hello -> "{\"t\":\"hello\",\"kind\":${quote(m.kind)},\"label\":${quote(m.label)},\"syncs\":${m.syncs},\"devices\":${m.devices}}"
        Ready -> "{\"t\":\"ready\"}"
        is Secret -> "{\"t\":\"secret\",\"chain\":${quote(m.chain)},\"epoch\":${m.epoch},\"s\":${quote(m.s)}}"
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it == Math.floor(it) }?.toLong()

    private fun check(m: JsonObject): Message? = when (m.string("t")) {
        "ready" -> Ready.takeIf { m.size == 1 }
        "hello" -> {
            val kind = m.string("kind")?.takeIf { it in SyncRecords.KINDS }
            val label = m.string("label")?.takeIf { SyncRecords.validText(it, 1, 40) }
            val syncs = (m["syncs"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            val devices = m.long("devices")
            if (m.size != 5 || kind == null || label == null || syncs == null || devices == null) null
            else if (if (syncs) devices !in 1..SyncRecords.DEVICE_CAP.toLong() else devices != 0L) null
            else Hello(kind, label, syncs, devices.toInt())
        }
        "secret" -> {
            val chain = m.string("chain")?.takeIf { SyncCrypto.fromB64(it, SyncCrypto.ID_SIZE) != null }
            val epoch = m.long("epoch")?.takeIf { it >= 1 }
            val s = m.string("s")?.takeIf { SyncCrypto.fromB64(it, SyncCrypto.KEY_SIZE) != null }
            if (m.size != 4 || chain == null || epoch == null || s == null) null else Secret(chain, epoch, s)
        }
        else -> null
    }

    fun read(raw: String): Message? = runCatching {
        val message = check(Json.parseToJsonElement(raw) as? JsonObject ?: return null)
        message?.takeIf { write(it) == raw }
    }.getOrNull()
}
