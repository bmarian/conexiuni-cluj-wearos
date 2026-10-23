package online.bmarian.bus.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.security.PrivateKey
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What this device keeps to stay in its chain. The secret, token and device key are sealed with a key that never leaves
 * the Android Keystore; the file is left out of backups, and a copy restored elsewhere cannot be opened, so it counts as
 * not syncing.
 */
class SyncVault(context: Context) {
    class Membership(
        val chain: String,
        val epoch: Long,
        val device: String,
        val token: String,
        val s: ByteArray,
        val deviceKey: PrivateKey,
        val devicePub: String,
    ) {
        val member get() = SyncApi.Member(chain, token)
    }

    @Serializable
    private class Stored(val chain: String, val epoch: Long, val device: String, val token: String, val s: String, val deviceKey: String, val devicePub: String)

    /** The last version of a record both sides agreed on, by logical name. */
    @Serializable
    class Base(val text: String, val rev: Long, val epoch: Long)

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun sealLocal(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        return SyncCrypto.toB64(cipher.iv + cipher.doFinal(plain))
    }

    private fun openLocal(text: String): ByteArray? = runCatching {
        val sealed = SyncCrypto.fromB64(text)!!
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, sealed, 0, 12)) }
            .doFinal(sealed, 12, sealed.size - 12)
    }.getOrNull()

    fun membership(): Membership? {
        val sealed = prefs.getString(MEMBERSHIP, null) ?: return null
        val stored = openLocal(sealed)?.let { runCatching { Json.decodeFromString<Stored>(String(it, Charsets.UTF_8)) }.getOrNull() }
        if (stored == null) {
            forget()
            return null
        }
        return Membership(
            stored.chain, stored.epoch, stored.device, stored.token, SyncCrypto.fromB64(stored.s)!!,
            SyncCrypto.privateFromPkcs8(SyncCrypto.fromB64(stored.deviceKey)!!), stored.devicePub,
        )
    }

    fun save(m: Membership) {
        val stored = Stored(m.chain, m.epoch, m.device, m.token, SyncCrypto.toB64(m.s), SyncCrypto.toB64(m.deviceKey.encoded), m.devicePub)
        prefs.edit(commit = true) { putString(MEMBERSHIP, sealLocal(Json.encodeToString(stored).toByteArray(Charsets.UTF_8))) }
    }

    fun bases(): MutableMap<String, Base> =
        prefs.getString(BASES, null)?.let { runCatching { Json.decodeFromString<Map<String, Base>>(it) }.getOrNull() }?.toMutableMap() ?: mutableMapOf()

    fun saveBases(bases: Map<String, Base>) = prefs.edit(commit = true) { putString(BASES, Json.encodeToString(bases)) }

    fun forget() = prefs.edit(commit = true) { clear() }

    private companion object {
        // Excluded from backups in res/xml/data_extraction_rules.xml and backup_rules.xml.
        const val FILE = "sync"
        const val ALIAS = "cai-sync"
        const val MEMBERSHIP = "membership"
        const val BASES = "bases"
    }
}
