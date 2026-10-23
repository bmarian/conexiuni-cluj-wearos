package online.bmarian.bus.data.sync

import online.bmarian.bus.data.sync.SyncRecords.City
import online.bmarian.bus.data.sync.SyncRecords.Device
import online.bmarian.bus.data.sync.SyncRecords.Line
import online.bmarian.bus.data.sync.SyncRecords.Place
import online.bmarian.bus.data.sync.SyncRecords.Settings

/** The three-way merge of protocol.md §6. Port of utils/sync/merge.ts. */
object SyncMerge {
    /** The local value when only this side changed it since base; otherwise the remote one. */
    fun <T> pick(base: T?, local: T, remote: T, inBase: Boolean): T =
        if (inBase && local != base && remote == base) local else remote

    class Merged<T>(val merged: List<T>, val dropped: List<T>)

    fun <T> mergeList(base: List<T>, local: List<T>, remote: List<T>, id: (T) -> String, fields: (T?, T, T) -> T, cap: Int): Merged<T> {
        fun index(items: List<T>) = items.withIndex().associate { id(it.value) to it.index }
        val b = index(base)
        val l = index(local)
        val r = index(remote)
        fun kept(key: String) = (key in l && key in r) || (key in l && key !in b) || (key in r && key !in b)
        fun orderOf(items: List<T>, other: Map<String, Int>) = items.map(id).filter { it in other }
        val localMoved = orderOf(local, b) != orderOf(base, l)
        val remoteMoved = orderOf(remote, b) != orderOf(base, r)
        val (win, other) = if (localMoved && !remoteMoved) local to remote else remote to local
        val keys = win.map(id).filter(::kept).toMutableList()
        val placed = keys.toMutableSet()
        for (key in other.map(id)) if (kept(key) && placed.add(key)) keys += key
        val merged = keys.map { key ->
            val li = l[key]
            val ri = r[key]
            when {
                li != null && ri != null -> fields(b[key]?.let(base::get), local[li], remote[ri])
                li != null -> local[li]
                else -> remote[ri!!]
            }
        }.toMutableList()
        val dropped = mutableListOf<T>()
        var i = merged.size - 1
        while (i >= 0 && merged.size > cap) {
            if (id(merged[i]) !in r) dropped.add(0, merged.removeAt(i))
            i--
        }
        return Merged(merged, dropped)
    }

    private fun <T> same(@Suppress("UNUSED_PARAMETER") base: T?, @Suppress("UNUSED_PARAMETER") local: T, remote: T) = remote

    private fun mergePlace(base: Place?, local: Place, remote: Place) = remote.copy(
        name = pick(base?.name, local.name, remote.name, base != null),
        label = pick(base?.label, local.label, remote.label, base != null),
    )

    private fun mergeDevice(base: Device?, local: Device, remote: Device): Device {
        if (base == null) return remote
        return Device(
            remote.id,
            pick(base.kind, local.kind, remote.kind, true),
            pick(base.label, local.label, remote.label, true),
            pick(base.pub, local.pub, remote.pub, true),
        )
    }

    class CityMerge(val merged: City, val dropped: Int, val droppedStops: List<Long>, val droppedLines: List<Line>, val droppedPlaces: List<Place>, val droppedFollowed: List<String>)

    fun mergeCity(base: City, local: City, remote: City): CityMerge {
        val cap = SyncRecords.CITY_CAP
        val stops = mergeList(base.stops, local.stops, remote.stops, SyncRecords::stopId, ::same, cap)
        val lines = mergeList(base.lines, local.lines, remote.lines, SyncRecords::lineId, ::same, cap)
        val places = mergeList(base.places, local.places, remote.places, SyncRecords::placeId, ::mergePlace, cap)
        val followed = mergeList(base.followed, local.followed, remote.followed, { it }, ::same, cap)
        return CityMerge(
            City(stops.merged, lines.merged, places.merged, followed.merged),
            stops.dropped.size + lines.dropped.size + places.dropped.size + followed.dropped.size,
            stops.dropped, lines.dropped, places.dropped, followed.dropped,
        )
    }

    fun mergeSettings(base: Settings?, local: Settings, remote: Settings): Settings {
        if (base == null) return remote
        return Settings(
            pick(base.theme, local.theme, remote.theme, true),
            pick(base.locale, local.locale, remote.locale, true),
            pick(base.autoCenterOnMe, local.autoCenterOnMe, remote.autoCenterOnMe, true),
            pick(base.autoFitMap, local.autoFitMap, remote.autoFitMap, true),
            pick(base.showVehicleExtras, local.showVehicleExtras, remote.showVehicleExtras, true),
            pick(base.greenFriday, local.greenFriday, remote.greenFriday, true),
        )
    }

    fun mergeDevices(base: List<Device>, local: List<Device>, remote: List<Device>): List<Device> =
        mergeList(base, local, remote, SyncRecords::deviceId, ::mergeDevice, SyncRecords.DEVICE_CAP).merged
}
