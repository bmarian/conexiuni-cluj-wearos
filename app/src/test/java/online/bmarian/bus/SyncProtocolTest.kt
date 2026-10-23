package online.bmarian.bus

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import online.bmarian.bus.data.sync.SyncCrypto
import online.bmarian.bus.data.sync.SyncMerge
import online.bmarian.bus.data.sync.SyncRecords
import online.bmarian.bus.data.sync.SyncRecords.Read
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Copies of the website's backend/devicesync/testdata, made and checked by its vectors_test.go; every client runs them.
class SyncProtocolTest {
    private fun read(name: String): JsonObject =
        Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("sync/$name")!!.bufferedReader().readText()).jsonObject

    private val vectors = read("vectors.json")
    private val cases = read("cases.json")

    private fun JsonElement.str(key: String) = jsonObject[key]!!.jsonPrimitive.content
    private fun b(text: String) = SyncCrypto.fromB64(text)!!
    private fun key(pair: JsonElement) = SyncCrypto.privateFromPkcs8(b(pair.str("pkcs8")))

    @Test
    fun derivesThePairingChannel() {
        val p = vectors["pair"]!!
        val d = p.jsonObject["d"]!!
        val e = p.jsonObject["e"]!!
        assertEquals(p.str("shared"), SyncCrypto.toB64(SyncCrypto.ecdh(key(d), b(e.str("spki")))))
        assertEquals(p.str("shared"), SyncCrypto.toB64(SyncCrypto.ecdh(key(e), b(d.str("spki")))))
        assertEquals(p.str("commit"), SyncCrypto.toB64(SyncCrypto.sha256(b(d.str("spki")))))
        val ownSpki = SyncCrypto.newKeyPair().spki
        assertEquals(SyncCrypto.SPKI_SIZE, ownSpki.size)
        assertTrue(SyncCrypto.publicKey(ownSpki) != null)
        for ((mode, k) in listOf("scanned" to b(p.str("k")), "typed" to null)) {
            val m = p.jsonObject[mode]!!
            val t = SyncCrypto.pairKey(b(p.str("shared")), k, p.str("code"), b(e.str("spki")), b(d.str("spki")))
            assertEquals(m.str("t"), SyncCrypto.toB64(t))
            assertEquals(m.str("check"), SyncCrypto.checkNumber(t))
            for (message in m.jsonObject["messages"]!!.jsonArray) {
                val sealed = SyncCrypto.seal(t, SyncCrypto.pad(message.str("json").toByteArray(), message.jsonObject["bucket"]!!.jsonPrimitive.int), message.str("aad"), b(message.str("nonce")))
                assertEquals(message.str("body"), SyncCrypto.toB64(sealed))
                assertEquals(message.str("json"), String(SyncCrypto.unpad(SyncCrypto.open(t, b(message.str("body")), message.str("aad"))!!)!!))
            }
        }
    }

    @Test
    fun derivesChainKeysAndSealsRecords() {
        val c = vectors["chain"]!!
        val keys = SyncCrypto.chainKeys(b(c.str("s")))
        assertEquals(listOf(c.str("join"), c.str("data"), c.str("names")), listOf(keys.join, keys.data, keys.names).map(SyncCrypto::toB64))
        assertEquals(c.str("joinHash"), SyncCrypto.toB64(SyncCrypto.sha256(keys.join)))
        for ((logical, stored) in c.jsonObject["recordNames"]!!.jsonObject) assertEquals(stored.jsonPrimitive.content, SyncCrypto.recordName(keys.names, logical))
        for (r in c.jsonObject["records"]!!.jsonArray) {
            val sealed = SyncCrypto.seal(keys.data, SyncCrypto.pad(r.str("json").toByteArray(), r.jsonObject["bucket"]!!.jsonPrimitive.int), r.str("aad"), b(r.str("nonce")))
            assertEquals(r.str("body"), SyncCrypto.toB64(sealed))
            val read = when (r.str("name")) {
                "settings" -> SyncRecords.readSettings(r.str("json"))
                "devices" -> SyncRecords.readDevices(r.str("json"))
                else -> SyncRecords.readCity(r.str("json"))
            }
            assertTrue(r.str("name"), read is Read.Ok)
        }
    }

    @Test
    fun wrapsANewSecretForOneDevice() {
        val w = vectors["wrap"]!!
        val ephemeral = SyncCrypto.KeyPair(key(w.jsonObject["ephemeral"]!!), b(w.jsonObject["ephemeral"]!!.str("spki")))
        val body = SyncCrypto.wrapSecret(b(w.jsonObject["deviceKey"]!!.str("spki")), b(w.str("s")), w.str("info"), ephemeral, b(w.str("nonce")))
        assertEquals(w.str("body"), SyncCrypto.toB64(body))
        assertArrayEquals(b(w.str("s")), SyncCrypto.unwrapSecret(key(w.jsonObject["deviceKey"]!!), body, w.str("info")))
        assertNull(SyncCrypto.unwrapSecret(key(vectors["pair"]!!.jsonObject["d"]!!), body, w.str("info")))
    }

    private fun outcome(kind: String, raw: String): String = when (val read = when (kind) {
        "city" -> SyncRecords.readCity(raw)
        "devices" -> SyncRecords.readDevices(raw)
        else -> SyncRecords.readSettings(raw)
    }) {
        is Read.Ok -> "ok"
        Read.Newer -> "newer"
        Read.Refused -> "refuse"
    }

    @Test
    fun recordCases() {
        for (c in cases["records"]!!.jsonArray) {
            assertEquals("${c.str("kind")}: ${c.str("about")}", c.str("outcome"), outcome(c.str("kind"), c.str("json")))
        }
    }

    private fun <T> value(read: Read<T>): T = (read as Read.Ok).value

    private fun JsonElement.textOrNull(key: String) = jsonObject[key]?.takeIf { it != JsonNull }?.jsonPrimitive?.content

    @Test
    fun mergeCases() {
        for (c in cases["merges"]!!.jsonArray) {
            val about = "${c.str("kind")}: ${c.str("about")}"
            when (c.str("kind")) {
                "city" -> {
                    val result = SyncMerge.mergeCity(
                        c.textOrNull("base")?.let { value(SyncRecords.readCity(it)) } ?: SyncRecords.emptyCity,
                        value(SyncRecords.readCity(c.str("local"))),
                        value(SyncRecords.readCity(c.str("remote"))),
                    )
                    assertEquals(about, c.str("merged"), SyncRecords.writeCity(result.merged))
                    val dropped = c.jsonObject["dropped"]!!.jsonObject
                    assertEquals(about, (dropped["stops"] as? JsonArray)?.map { it.jsonPrimitive.content.toLong() } ?: emptyList<Long>(), result.droppedStops)
                    assertEquals(about, dropped.keys.filter { it != "stops" }.isEmpty(), result.dropped == result.droppedStops.size)
                }
                "settings" -> assertEquals(
                    about, c.str("merged"),
                    SyncRecords.writeSettings(SyncMerge.mergeSettings(
                        c.textOrNull("base")?.let { value(SyncRecords.readSettings(it)) },
                        value(SyncRecords.readSettings(c.str("local"))),
                        value(SyncRecords.readSettings(c.str("remote"))),
                    )),
                )
                else -> assertEquals(
                    about, c.str("merged"),
                    SyncRecords.writeDevices(SyncRecords.Devices(SyncMerge.mergeDevices(
                        c.textOrNull("base")?.let { value(SyncRecords.readDevices(it)).devices }.orEmpty(),
                        value(SyncRecords.readDevices(c.str("local"))).devices,
                        value(SyncRecords.readDevices(c.str("remote"))).devices,
                    ))),
                )
            }
        }
    }
}
