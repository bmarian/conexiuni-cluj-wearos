package online.bmarian.bus.logic

private const val WORDS_RANK = 3

private const val ONE_EDIT_LETTERS = 4
private const val TWO_EDITS_LETTERS = 8

private val STREET_KINDS = mapOf(
    "strada" to "strada", "str" to "strada",
    "bulevardul" to "bulevardul", "bulevard" to "bulevardul", "bd" to "bulevardul", "bld" to "bulevardul", "blvd" to "bulevardul", "bdul" to "bulevardul",
    "calea" to "calea", "cal" to "calea",
    "aleea" to "aleea", "al" to "aleea",
    "piata" to "piata", "pta" to "piata",
    "soseaua" to "soseaua", "sos" to "soseaua",
    "splaiul" to "splaiul", "spl" to "splaiul",
    "intrarea" to "intrarea", "intr" to "intrarea",
    "pasajul" to "pasajul",
    "drumul" to "drumul",
)

private val WORD = Regex("[\\p{L}\\p{N}]+")
private val CONTRACTED = Regex("(?<![\\p{L}\\p{N}])(\\p{L}{1,2})-(\\p{L}{2,3})(?![\\p{L}\\p{N}])")
private val DIGIT = Regex("\\p{N}")

private fun allowedEdits(letters: Int) = if (letters >= TWO_EDITS_LETTERS) 2 else if (letters >= ONE_EDIT_LETTERS) 1 else 0

private fun queryWords(query: String): List<String> {
    val words = WORD.findAll(query.replace("b-dul", "bulevardul").replace("p-ta", "piata")).map { STREET_KINDS[it.value] ?: it.value }.toList()
    val naming = words.filter { it !in STREET_KINDS.values }
    return naming.ifEmpty { words }
}

/** Port of editDistance. */
fun editDistance(a: String, b: String, limit: Int): Int {
    if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
    var before = IntArray(b.length + 1)
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        var lowest = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var edits = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) edits = minOf(edits, before[j - 2] + 1)
            current[j] = edits
            lowest = minOf(lowest, edits)
        }
        if (lowest > limit) return limit + 1
        before = previous
        previous = current
    }
    return previous[b.length]
}

private fun misspells(word: String, nameWord: String): Boolean {
    val allowed = allowedEdits(word.length)
    if (allowed == 0 || DIGIT.containsMatchIn(word)) return false
    return editDistance(word, nameWord, allowed) <= allowed || editDistance(word, nameWord.take(word.length), allowed) <= allowed
}

/** Port of nameMatches. */
fun nameMatches(name: String, query: String, typos: Boolean = false): Boolean {
    val words = queryWords(query)
    if (words.isEmpty()) return false
    val found = WORD.findAll(name).map { it.value to (name.getOrNull(it.range.last + 1) == '.') }.toList()
    val contracted = CONTRACTED.findAll(name).toList()
    var whole = false
    for (word in words) {
        val full = found.any { it.first.startsWith(word) } ||
            contracted.any { word.startsWith(it.groupValues[1]) && word.endsWith(it.groupValues[2]) } ||
            (typos && found.any { !it.second && misspells(word, it.first) })
        if (full) whole = true else if (found.none { it.second && word.startsWith(it.first) }) return false
    }
    return whole
}

class Ranked<T>(val item: T, val rank: Int)

/** Port of matching. */
fun <T> matching(items: List<T>, nameOf: (T) -> String, query: String): List<Ranked<T>> {
    val exact = items.mapNotNull { item ->
        val name = nameOf(item)
        val rank = stopMatchRank(name, query)
        when {
            rank >= 0 -> Ranked(item, rank)
            nameMatches(name, query) -> Ranked(item, WORDS_RANK)
            else -> null
        }
    }
    return exact.ifEmpty { items.filter { nameMatches(nameOf(it), query, typos = true) }.map { Ranked(it, WORDS_RANK) } }
}
