package com.example.conexiuniclujwearos.logic

import com.example.conexiuniclujwearos.data.Route
import com.example.conexiuniclujwearos.data.Stop
import java.text.Normalizer
import java.util.Locale

// 800 stops is far too many to scroll with a bezel, so they are picked by initial first,
// and by the first two letters when one initial holds more than this.
const val SPLIT_OVER = 40
// Metropolitan stops are named "M-Name", "M - Name" or "M Name" and filed under the name.
private val METRO_PREFIX = Regex("""^M(\s*-\s*|\s+)(?=\S)""")
// CTP writes squares both as "P-ța" and "Piața"; both belong under "Pi".
private val SQUARE_PREFIX = Regex("""^P-ța\b""")
private val COMBINING_MARKS = Regex("[\\u0300-\\u036f]")

fun stopSortKey(name: String): String {
    val plain = name.replace(METRO_PREFIX, "").replace(SQUARE_PREFIX, "Piața")
    return Normalizer.normalize(plain, Normalizer.Form.NFD).replace(COMBINING_MARKS, "").uppercase(Locale.ROOT)
}

fun initialOf(key: String): String = key.firstOrNull()?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"

/**
 * Like the browser's localeCompare with numeric: true on these keys: spaces, then
 * punctuation, then numbers by value, then letters. java.text.Collator skips spaces and
 * punctuation altogether, which put "A. VLAICU" after "ACADEMIA".
 */
object NaturalOrder : Comparator<String> {
    // The root collation order of the punctuation and symbols that show up in names.
    private const val PUNCTUATION = """_-,;:!?.'"()[]{}@*/\&#%`^+<=>|~${'$'}"""

    private fun rank(c: Char): Int = when {
        c.isWhitespace() -> 0
        c.isDigit() -> 20_000
        c.isLetter() -> 30_000 + c.uppercaseChar().code
        else -> PUNCTUATION.indexOf(c).let { if (it >= 0) 100 + it else 1_000 + c.code }
    }

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var endA = i
                while (endA < a.length && a[endA].isDigit()) endA++
                var endB = j
                while (endB < b.length && b[endB].isDigit()) endB++
                val result = a.substring(i, endA).toBigInteger().compareTo(b.substring(j, endB).toBigInteger())
                if (result != 0) return result
                i = endA
                j = endB
                continue
            }
            val result = rank(a[i]).compareTo(rank(b[j]))
            if (result != 0) return result
            i++
            j++
        }
        return (a.length - i).compareTo(b.length - j)
    }
}

fun sortedRoutes(routes: List<Route>): List<Route> = routes.sortedWith(compareBy(NaturalOrder) { it.shortName })

class KeyedStop(val stop: Stop, val key: String)

fun keyedStops(stops: List<Stop>): List<KeyedStop> =
    stops.map { KeyedStop(it, stopSortKey(it.name)) }.sortedWith(compareBy(NaturalOrder) { it.key })

class BrowseTile(val label: String, val letter: String, val prefix: String?)

sealed interface StopBrowse {
    class Tiles(val tiles: List<BrowseTile>) : StopBrowse
    class Stops(val stops: List<Stop>) : StopBrowse
}

fun browseStops(keyed: List<KeyedStop>, letter: String?, prefix: String?): StopBrowse {
    if (prefix != null) return StopBrowse.Stops(keyed.filter { it.key.startsWith(prefix) }.map { it.stop })
    if (letter != null) {
        val inLetter = keyed.filter { initialOf(it.key) == letter }
        if (inLetter.size <= SPLIT_OVER) return StopBrowse.Stops(inLetter.map { it.stop })
        val prefixes = inLetter.map { it.key.take(2) }.distinct()
        return StopBrowse.Tiles(prefixes.map { BrowseTile(prefixLabel(it), letter, it) })
    }
    val letters = keyed.map { initialOf(it.key) }.distinct()
        .sortedWith { a, b -> if (a == "#") 1 else if (b == "#") -1 else a.compareTo(b) }
    return StopBrowse.Tiles(letters.map { BrowseTile(it, it, null) })
}

fun prefixLabel(prefix: String): String = prefix.take(1) + prefix.drop(1).lowercase(Locale.ROOT)
