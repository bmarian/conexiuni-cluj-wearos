package online.bmarian.bus.data.sync

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The sealing and key steps of the website's backend/devicesync/protocol.md. */
object SyncCrypto {
    const val SPKI_SIZE = 91
    const val KEY_SIZE = 32
    const val ID_SIZE = 16
    const val PAIR_PADDED = 512
    const val WRAP_SIZE = SPKI_SIZE + 12 + KEY_SIZE + 16
    val BUCKETS = listOf(1024, 4096, 16384, 65536)

    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun random(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    fun toB64(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** Strict base64url: no padding, no other alphabet, no stray bits; null unless exactly [size] bytes when given. */
    fun fromB64(text: String, size: Int? = null): ByteArray? {
        if (!text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) return null
        val bytes = runCatching { decoder.decode(text) }.getOrNull() ?: return null
        if (toB64(bytes) != text || (size != null && bytes.size != size)) return null
        return bytes
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** RFC 5869; an empty salt is 32 zero bytes, which Java's key spec needs spelled out. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, n: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(n)
        var block = ByteArray(0)
        var done = 0
        var counter = 1
        while (done < n) {
            block = hmac(prk, block + info + byteArrayOf(counter++.toByte()))
            val take = minOf(block.size, n - done)
            System.arraycopy(block, 0, out, done, take)
            done += take
        }
        return out
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, n: Int) = hkdf(ikm, salt, info.toByteArray(Charsets.UTF_8), n)

    class KeyPair(val private: PrivateKey, val spki: ByteArray)

    fun newKeyPair(): KeyPair {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
        return KeyPair(pair.private, pair.public.encoded)
    }

    fun privateFromPkcs8(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

    /** A P-256 point in SubjectPublicKeyInfo form, on the curve; null otherwise. */
    fun publicKey(spki: ByteArray): ECPublicKey? {
        if (spki.size != SPKI_SIZE) return null
        val key = runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) as ECPublicKey }.getOrNull() ?: return null
        if (key.params.curve.field.fieldSize != 256 || key.params.curve.b != B) return null
        val x = key.w.affineX
        val y = key.w.affineY
        if (x.signum() < 0 || x >= P || y.signum() < 0 || y >= P) return null
        val left = y.modPow(BigInteger.valueOf(2), P)
        val right = x.modPow(BigInteger.valueOf(3), P).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P)
        return key.takeIf { left == right }
    }

    fun ecdh(own: PrivateKey, otherSpki: ByteArray): ByteArray {
        val other = publicKey(otherSpki) ?: throw IllegalArgumentException("not a P-256 key")
        return KeyAgreement.getInstance("ECDH").apply {
            init(own)
            doPhase(other, true)
        }.generateSecret()
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: String): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad.toByteArray(Charsets.UTF_8))
        }

    fun seal(key: ByteArray, plain: ByteArray, aad: String, nonce: ByteArray = random(12)): ByteArray =
        nonce + gcm(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plain)

    /** Null when the key, the associated data or the bytes are not the ones it was sealed with. */
    fun open(key: ByteArray, sealed: ByteArray, aad: String): ByteArray? {
        if (sealed.size < 28) return null
        return try {
            gcm(Cipher.DECRYPT_MODE, key, sealed.copyOfRange(0, 12), aad).doFinal(sealed, 12, sealed.size - 12)
        } catch (_: AEADBadTagException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun pad(plain: ByteArray, size: Int): ByteArray =
        ByteBuffer.allocate(size).putInt(plain.size).put(plain).array()

    fun unpad(padded: ByteArray): ByteArray? {
        if (padded.size < 4) return null
        val n = ByteBuffer.wrap(padded).int
        if (n < 0 || n > padded.size - 4) return null
        for (i in 4 + n until padded.size) if (padded[i] != 0.toByte()) return null
        return padded.copyOfRange(4, 4 + n)
    }

    fun bucketFor(n: Int): Int = BUCKETS.firstOrNull { n + 4 <= it } ?: 0

    fun pairKey(shared: ByteArray, k: ByteArray?, code: String, pe: ByteArray, pd: ByteArray): ByteArray =
        hkdf(shared, k ?: ByteArray(0), "cai-sync/v1/pair|$code|".toByteArray(Charsets.UTF_8) + pe + pd, 32)

    fun checkNumber(t: ByteArray): String {
        val n = ByteBuffer.wrap(hkdf(t, ByteArray(0), "cai-sync/v1/check", 4)).int.toLong() and 0xffffffffL
        return (n % 1_000_000).toString().padStart(6, '0')
    }

    fun pairAAD(code: String, sender: Char) = "cai-sync/v1/pair-message|$code|$sender"

    class ChainKeys(val join: ByteArray, val data: ByteArray, val names: ByteArray)

    fun chainKeys(s: ByteArray): ChainKeys {
        val salt = "cai-sync/v1".toByteArray(Charsets.UTF_8)
        return ChainKeys(hkdf(s, salt, "cai-sync/v1/join", 32), hkdf(s, salt, "cai-sync/v1/data", 32), hkdf(s, salt, "cai-sync/v1/names", 32))
    }

    fun recordName(names: ByteArray, logical: String): String = toB64(hmac(names, logical.toByteArray(Charsets.UTF_8)).copyOf(ID_SIZE))

    fun recordAAD(chain: String, epoch: Long, name: String, rev: Long) = "cai-sync/v1/record|$chain|$epoch|$name|$rev"

    fun wrapInfo(chain: String, epoch: Long, device: String) = "cai-sync/v1/wrap|$chain|$epoch|$device"

    fun wrapSecret(deviceSpki: ByteArray, s: ByteArray, info: String, ephemeral: KeyPair = newKeyPair(), nonce: ByteArray = random(12)): ByteArray {
        val w = hkdf(ecdh(ephemeral.private, deviceSpki), ephemeral.spki, info, 32)
        return ephemeral.spki + seal(w, s, info, nonce)
    }

    fun unwrapSecret(device: PrivateKey, body: ByteArray, info: String): ByteArray? {
        if (body.size != WRAP_SIZE) return null
        val px = body.copyOf(SPKI_SIZE)
        return runCatching { open(hkdf(ecdh(device, px), px, info, 32), body.copyOfRange(SPKI_SIZE, body.size), info) }.getOrNull()
    }
}
