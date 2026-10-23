package online.bmarian.bus.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal
import java.math.RoundingMode

/** The record rules of protocol.md §4: a strict reader and the canonical writer. Same as the phone app's data/sync/SyncRecords.kt. */
object SyncRecords {
    const val CITY_CAP = 50
    const val DEVICE_CAP = 10
    private const val MAX_INT = Int.MAX_VALUE.toLong()
    val LAT = 43.6..48.3
    val LON = 20.2..29.8
    val KINDS = listOf("web", "android", "wear")

    data class Origin(val name: String, val lat: Double, val lon: Double)
    data class Place(val name: String, val label: String, val lat: Double, val lon: Double, val origin: Origin? = null)
    data class Line(val route: Long, val dir: String)
    data class City(val stops: List<Long>, val lines: List<Line>, val places: List<Place>, val followed: List<String>) {
        val isEmpty get() = stops.isEmpty() && lines.isEmpty() && places.isEmpty() && followed.isEmpty()
    }
    data class Device(val id: String, val kind: String, val label: String, val pub: String)
    data class Devices(val devices: List<Device>)
    data class Settings(
        val theme: String,
        val locale: String,
        val autoCenterOnMe: Boolean,
        val autoFitMap: Boolean,
        val showVehicleExtras: Boolean,
        val greenFriday: Boolean,
    )

    val emptyCity = City(emptyList(), emptyList(), emptyList(), emptyList())

    /** Newer: a later version this device leaves alone. */
    sealed interface Read<out T> {
        data class Ok<T>(val value: T) : Read<T>
        data object Refused : Read<Nothing>
        data object Newer : Read<Nothing>
    }

    private class Refuse : Exception()

    private fun refuse(): Nothing = throw Refuse()

    private val strict = Json

    fun forbidden(cp: Int) = cp <= 0x1f || cp in 0x7f..0x9f || cp == 0x061c || cp == 0x200b || cp == 0x200e || cp == 0x200f ||
        cp in 0x2028..0x202e || cp == 0x2060 || cp in 0x2066..0x2069 || cp == 0xfeff || cp == 0xfffd || cp == 0xfffe || cp == 0xffff ||
        cp in 0xd800..0xdfff

    fun blankEnd(cp: Int) = cp == 0x20 || cp == 0xa0 || cp == 0x1680 || cp in 0x2000..0x200a || cp == 0x202f || cp == 0x205f || cp == 0x3000

    fun validText(value: String, min: Int, max: Int): Boolean {
        val points = value.codePoints().toArray()
        if (points.size !in min..max || points.any(::forbidden)) return false
        return points.isEmpty() || (!blankEnd(points.first()) && !blankEnd(points.last()))
    }

    private fun text(e: JsonElement?, min: Int, max: Int): String {
        val p = e as? JsonPrimitive ?: refuse()
        if (!p.isString || !validText(p.content, min, max)) refuse()
        return p.content
    }

    private fun number(e: JsonElement?): Double {
        val p = e as? JsonPrimitive ?: refuse()
        if (p.isString) refuse()
        val value = p.content.toDoubleOrNull() ?: refuse()
        if (!value.isFinite()) refuse()
        return value
    }

    private fun integer(e: JsonElement?, lo: Long, hi: Long): Long {
        val value = number(e)
        if (value != Math.floor(value) || value < lo || value > hi) refuse()
        return value.toLong()
    }

    fun roundCoordinate(x: Double) = Math.floor(x * 1e6 + 0.5) / 1e6

    private fun coordinate(e: JsonElement?, range: ClosedFloatingPointRange<Double>): Double {
        val value = number(e)
        if (value !in range || roundCoordinate(value) != value) refuse()
        return value
    }

    private fun bool(e: JsonElement?): Boolean {
        val p = e as? JsonPrimitive ?: refuse()
        if (p.isString) refuse()
        return p.booleanOrNull ?: refuse()
    }

    private fun list(e: JsonElement?, cap: Int): JsonArray {
        val array = e as? JsonArray ?: refuse()
        if (array.size > cap) refuse()
        return array
    }

    private fun obj(e: JsonElement?, vararg keys: String): JsonObject {
        val o = e as? JsonObject ?: refuse()
        if (o.size != keys.size || keys.any { it !in o }) refuse()
        return o
    }

    private fun <T> unique(items: List<T>, id: (T) -> String) {
        if (items.map(id).toSet().size != items.size) refuse()
    }

    fun stopId(id: Long) = id.toString()
    fun lineId(l: Line) = "${l.route}/${l.dir}"
    fun placeId(p: Place) = listOf(p.lat, p.lon, p.origin?.lat ?: "", p.origin?.lon ?: "").joinToString(",")
    fun deviceId(d: Device) = d.id

    /** Parses, checks the rules and the canonical form; the version comes first, so a later shape is newer, not refused. */
    private fun <T> read(raw: String, parse: (JsonElement) -> T, write: (T) -> String): Read<T> = try {
        val record = strict.parseToJsonElement(raw)
        if (integer((record as? JsonObject)?.get("v"), 1, MAX_INT) > 1) Read.Newer
        else {
            val value = parse(record)
            if (write(value) == raw) Read.Ok(value) else Read.Refused
        }
    } catch (_: Refuse) {
        Read.Refused
    } catch (_: IllegalArgumentException) {
        Read.Refused
    }

    private fun parseCity(record: JsonElement): City {
        val m = obj(record, "v", "stops", "lines", "places", "followed")
        val stops = list(m["stops"], CITY_CAP).map { integer(it, 1, MAX_INT) }
        val lines = list(m["lines"], CITY_CAP).map { l ->
            val pair = list(l, 2)
            val dir = (pair.getOrNull(1) as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (pair.size != 2 || (dir != "0" && dir != "1")) refuse()
            Line(integer(pair[0], 1, MAX_INT), dir)
        }
        val places = list(m["places"], CITY_CAP).map { p ->
            val f = list(p, 7)
            if (f.size != 4 && f.size != 7) refuse()
            Place(
                name = text(f[0], 1, 100),
                label = text(f[1], 0, 40),
                lat = coordinate(f[2], LAT),
                lon = coordinate(f[3], LON),
                origin = if (f.size == 7) Origin(text(f[4], 1, 100), coordinate(f[5], LAT), coordinate(f[6], LON)) else null,
            )
        }
        val followed = list(m["followed"], CITY_CAP).map { text(it, 1, 16) }
        unique(stops, ::stopId)
        unique(lines, ::lineId)
        unique(places, ::placeId)
        unique(followed) { it }
        return City(stops, lines, places, followed)
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun coord(x: Double): String = BigDecimal(x).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun <T> joined(items: List<T>, write: (T) -> String) = items.joinToString(",", "[", "]", transform = write)

    private fun writePlace(p: Place): String {
        val base = listOf(quote(p.name), quote(p.label), coord(p.lat), coord(p.lon))
        val origin = p.origin?.let { listOf(quote(it.name), coord(it.lat), coord(it.lon)) }.orEmpty()
        return (base + origin).joinToString(",", "[", "]")
    }

    fun writeCity(r: City): String =
        "{\"v\":1,\"stops\":" + joined(r.stops, ::stopId) +
            ",\"lines\":" + joined(r.lines) { "[${it.route},${quote(it.dir)}]" } +
            ",\"places\":" + joined(r.places, ::writePlace) +
            ",\"followed\":" + joined(r.followed, ::quote) + "}"

    fun readCity(raw: String): Read<City> = read(raw, ::parseCity, ::writeCity)

    private val ID_TEXT = Regex("^[A-Za-z0-9_-]{21}[AQgw]$")

    private fun parseDevices(record: JsonElement): Devices {
        val m = obj(record, "v", "devices")
        val devices = list(m["devices"], DEVICE_CAP).map { item ->
            val d = obj(item, "id", "kind", "label", "pub")
            val id = (d["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(ID_TEXT::matches) ?: refuse()
            val kind = (d["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in KINDS } ?: refuse()
            val pub = (d["pub"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: refuse()
            SyncCrypto.fromB64(pub, SyncCrypto.SPKI_SIZE)?.let(SyncCrypto::publicKey) ?: refuse()
            Device(id, kind, text(d["label"], 1, 40), pub)
        }
        unique(devices, ::deviceId)
        return Devices(devices)
    }

    fun writeDevices(r: Devices): String = "{\"v\":1,\"devices\":" + joined(r.devices) {
        "{\"id\":${quote(it.id)},\"kind\":${quote(it.kind)},\"label\":${quote(it.label)},\"pub\":${quote(it.pub)}}"
    } + "}"

    fun readDevices(raw: String): Read<Devices> = read(raw, ::parseDevices, ::writeDevices)

    private fun parseSettings(record: JsonElement): Settings {
        val m = obj(record, "v", "theme", "locale", "autoCenterOnMe", "autoFitMap", "showVehicleExtras", "regions")
        val theme = (m["theme"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in listOf("light", "dark", "system") } ?: refuse()
        val locale = (m["locale"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it == "ro" || it == "en" } ?: refuse()
        val cluj = obj(obj(m["regions"], "cluj")["cluj"], "showGreenFriday")
        return Settings(theme, locale, bool(m["autoCenterOnMe"]), bool(m["autoFitMap"]), bool(m["showVehicleExtras"]), bool(cluj["showGreenFriday"]))
    }

    fun writeSettings(r: Settings): String =
        "{\"v\":1,\"theme\":${quote(r.theme)},\"locale\":${quote(r.locale)},\"autoCenterOnMe\":${r.autoCenterOnMe}" +
            ",\"autoFitMap\":${r.autoFitMap},\"showVehicleExtras\":${r.showVehicleExtras}" +
            ",\"regions\":{\"cluj\":{\"showGreenFriday\":${r.greenFriday}}}}"

    fun readSettings(raw: String): Read<Settings> = read(raw, ::parseSettings, ::writeSettings)
}
