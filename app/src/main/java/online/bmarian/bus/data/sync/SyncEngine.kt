package online.bmarian.bus.data.sync

import android.os.Build
import kotlinx.serialization.json.JsonObject
import online.bmarian.bus.Services
import online.bmarian.bus.data.Cities
import online.bmarian.bus.data.FavoriteRoute
import online.bmarian.bus.data.sync.SyncRecords.Read
import online.bmarian.bus.data.sync.SyncVault.Base
import online.bmarian.bus.data.sync.SyncVault.Membership

/**
 * One sync run for the watch (protocol.md §5–§6), the phone app's SyncEngine cut down: the watch shows stops and lines
 * only, so it keeps a city's places and followed lines exactly as last agreed, and it leaves settings to the others.
 */
class SyncEngine(private val vault: SyncVault) {
    class Gone : Exception()

    private class EpochChanged : Exception()

    class Report(val devices: Int, val broken: Boolean)

    private interface Kind<T> {
        fun read(raw: String): Read<T>
        fun write(value: T): String
        fun local(base: T?): T?
        fun merge(base: T?, local: T, remote: T): T
        fun apply(value: T)
        fun empty(value: T): Boolean = false
    }

    private class Run(val m: Membership, val keys: SyncCrypto.ChainKeys, val bases: MutableMap<String, Base>) {
        var broken = false
    }

    private fun cityKind(slug: String) = object : Kind<SyncRecords.City> {
        override fun read(raw: String) = SyncRecords.readCity(raw)
        override fun write(value: SyncRecords.City) = SyncRecords.writeCity(value)
        override fun local(base: SyncRecords.City?): SyncRecords.City? {
            val (stops, routes) = Services.favorites.savedIn(slug)
            val local = SyncRecords.City(
                stops = stops.map(Int::toLong).distinct(),
                lines = routes.map { SyncRecords.Line(it.routeId.toLong(), it.direction) }.distinctBy(SyncRecords::lineId),
                places = base?.places.orEmpty(),
                followed = base?.followed.orEmpty(),
            )
            return local.takeIf { it.stops.size <= SyncRecords.CITY_CAP && it.lines.size <= SyncRecords.CITY_CAP }
        }
        override fun merge(base: SyncRecords.City?, local: SyncRecords.City, remote: SyncRecords.City) =
            SyncMerge.mergeCity(base ?: SyncRecords.emptyCity, local, remote).merged
        override fun apply(value: SyncRecords.City) =
            Services.favorites.replaceSynced(slug, value.stops.map(Long::toInt), value.lines.map { FavoriteRoute(it.route.toInt(), it.dir) })
        override fun empty(value: SyncRecords.City) = value.isEmpty
    }

    private fun devicesKind(run: Run) = object : Kind<SyncRecords.Devices> {
        override fun read(raw: String) = SyncRecords.readDevices(raw)
        override fun write(value: SyncRecords.Devices) = SyncRecords.writeDevices(value)
        override fun local(base: SyncRecords.Devices?) =
            SyncRecords.Devices(base?.devices.orEmpty().filter { it.id != run.m.device } + SyncRecords.Device(run.m.device, KIND, label(), run.m.devicePub))
        override fun merge(base: SyncRecords.Devices?, local: SyncRecords.Devices, remote: SyncRecords.Devices) =
            SyncRecords.Devices(SyncMerge.mergeDevices(base?.devices.orEmpty(), local.devices, remote.devices))
        override fun apply(value: SyncRecords.Devices) {}
    }

    private fun label(): String {
        val label = Build.MODEL.orEmpty().filter { !SyncRecords.forbidden(it.code) }.trim().take(40)
        return label.takeIf { SyncRecords.validText(it, 1, 40) } ?: "Wear OS"
    }

    private suspend fun fetchRecord(run: Run, name: String): Pair<Long, String>? {
        val (rev, body) = SyncApi.readRecord(run.m.member, name)
        val sealed = SyncCrypto.fromB64(body) ?: return null
        val plain = SyncCrypto.open(run.keys.data, sealed, SyncCrypto.recordAAD(run.m.chain, run.m.epoch, name, rev)) ?: return null
        return SyncCrypto.unpad(plain)?.let { rev to String(it, Charsets.UTF_8) }
    }

    private fun sealRecord(keys: SyncCrypto.ChainKeys, chain: String, epoch: Long, name: String, rev: Long, text: String): String {
        val plain = text.toByteArray(Charsets.UTF_8)
        val padded = SyncCrypto.pad(plain, SyncCrypto.bucketFor(plain.size))
        return SyncCrypto.toB64(SyncCrypto.seal(keys.data, padded, SyncCrypto.recordAAD(chain, epoch, name, rev)))
    }

    private suspend fun <T> syncRecord(run: Run, kind: Kind<T>, logical: String, name: String, remoteRev: Long) {
        val m = run.m
        var rev = remoteRev
        repeat(3) {
            val base = run.bases[logical]
            val sameEpoch = base?.epoch == m.epoch
            if (sameEpoch && rev < base!!.rev) {
                run.broken = true
                return
            }
            var remoteText: String? = null
            if (rev > 0) {
                if (sameEpoch && base!!.rev == rev) remoteText = base.text
                else {
                    val fetched = fetchRecord(run, name)
                    if (fetched == null) {
                        run.broken = true
                        return
                    }
                    rev = fetched.first
                    remoteText = fetched.second
                }
            }
            val remote = remoteText?.let(kind::read)
            if (remote != null && remote !is Read.Ok) {
                run.broken = remote != Read.Newer
                return
            }
            val baseValue = base?.let { (kind.read(it.text) as? Read.Ok)?.value }
            // Always the base: what the watch cannot show stays as last agreed, even when the server has nothing.
            val local = kind.local(baseValue) ?: return
            val merged = if (remote is Read.Ok) kind.merge(baseValue, local, remote.value) else local
            val mergedText = kind.write(merged)
            if (mergedText != kind.write(local)) kind.apply(merged)
            if (mergedText == remoteText) {
                run.bases[logical] = Base(mergedText, rev, m.epoch)
                return
            }
            if (remote == null && kind.empty(merged)) return
            try {
                val written = SyncApi.writeRecord(m.member, name, m.epoch, rev, sealRecord(run.keys, m.chain, m.epoch, name, rev + 1, mergedText))
                run.bases[logical] = Base(mergedText, written, m.epoch)
                return
            } catch (e: SyncHttpException) {
                if (e.status != 409 || e.error == "full") throw e
                if (e.number("epoch") != m.epoch) throw EpochChanged()
                rev = e.number("rev") ?: throw e
            }
        }
    }

    private suspend fun state(m: Membership): SyncApi.ChainState = try {
        SyncApi.chainState(m.member)
    } catch (e: SyncHttpException) {
        if (e.status == 401) {
            vault.forget()
            throw Gone()
        }
        throw e
    }

    private suspend fun catchUp(m: Membership, epoch: Long): Membership {
        val (wrapEpoch, body) = SyncApi.readWrap(m.member)
        val s = SyncCrypto.fromB64(body)?.takeIf { wrapEpoch == epoch }
            ?.let { SyncCrypto.unwrapSecret(m.deviceKey, it, SyncCrypto.wrapInfo(m.chain, epoch, m.device)) }
            ?: throw SyncHttpException(409, JsonObject(emptyMap()))
        val next = Membership(m.chain, epoch, m.device, m.token, s, m.deviceKey, m.devicePub)
        vault.save(next)
        return next
    }

    fun membership() = vault.membership()

    suspend fun syncOnce(): Report = try {
        syncPass()
    } catch (_: EpochChanged) {
        syncPass()
    }

    private suspend fun syncPass(): Report {
        var m = vault.membership() ?: throw Gone()
        var st = state(m)
        if (st.epoch != m.epoch) {
            m = catchUp(m, st.epoch)
            st = state(m)
        }
        val run = Run(m, SyncCrypto.chainKeys(m.s), vault.bases())
        val revs = st.records.associate { it.name to it.rev }
        suspend fun <T> step(logical: String, kind: Kind<T>) {
            val name = SyncCrypto.recordName(run.keys.names, logical)
            syncRecord(run, kind, logical, name, revs[name] ?: 0)
        }
        try {
            step("devices", devicesKind(run))
            for (city in Cities.list.value) step("city:${city.slug}", cityKind(city.slug))
        } finally {
            vault.saveBases(run.bases)
        }
        return Report(st.devices.size, run.broken)
    }

    private suspend fun register(s: ByteArray, send: suspend (String) -> SyncApi.Registered, chain: String?) {
        val keys = SyncCrypto.chainKeys(s)
        val device = SyncCrypto.newKeyPair()
        val registered = send(SyncCrypto.toB64(keys.join))
        vault.forget()
        vault.save(
            Membership(
                registered.chain ?: chain!!, registered.epoch, registered.device, registered.token,
                s, device.private, SyncCrypto.toB64(device.spki),
            ),
        )
    }

    suspend fun createChain(): SyncMessages.Secret {
        val s = SyncCrypto.random(SyncCrypto.KEY_SIZE)
        register(s, { SyncApi.createChain(it, KIND) }, null)
        val m = vault.membership()!!
        return SyncMessages.Secret(m.chain, m.epoch, SyncCrypto.toB64(s))
    }

    suspend fun joinChain(chain: String, s: String) {
        val secret = SyncCrypto.fromB64(s, SyncCrypto.KEY_SIZE) ?: throw IllegalArgumentException("secret")
        register(secret, { SyncApi.joinChain(chain, it, KIND) }, chain)
    }

    /** Leaves the chain; the favorites on the watch stay as they are. */
    suspend fun leave() {
        val m = vault.membership() ?: return
        try {
            val keys = SyncCrypto.chainKeys(m.s)
            val name = SyncCrypto.recordName(keys.names, "devices")
            val run = Run(m, keys, mutableMapOf())
            val fetched = runCatching { fetchRecord(run, name) }.getOrNull()
            val read = fetched?.second?.let(SyncRecords::readDevices) as? Read.Ok
            if (fetched != null && read != null) {
                val text = SyncRecords.writeDevices(SyncRecords.Devices(read.value.devices.filter { it.id != m.device }))
                SyncApi.writeRecord(m.member, name, m.epoch, fetched.first, sealRecord(keys, m.chain, m.epoch, name, fetched.first + 1, text))
            }
            SyncApi.leave(m.member)
        } catch (e: SyncHttpException) {
            if (e.status != 401 && e.status != 404) throw e
        }
        vault.forget()
    }

    private companion object {
        const val KIND = "wear"
    }
}
