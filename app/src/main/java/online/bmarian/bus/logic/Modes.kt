package online.bmarian.bus.logic

/** Port of utils/modes.ts. Biggest first: a station shows the biggest thing that stops there, and lists group in this order. */
enum class Mode { METRO, RAIL, TRAM, TROLLEYBUS, BUS }

fun modeOf(routeType: Int): Mode = when (routeType) {
    1, 12 -> Mode.METRO
    2 -> Mode.RAIL
    0, 5 -> Mode.TRAM
    11 -> Mode.TROLLEYBUS
    else -> Mode.BUS
}

fun mainMode(routeTypes: Iterable<Int>): Mode = routeTypes.map(::modeOf).minOrNull() ?: Mode.BUS

/** By mode, then as the numbers read. */
fun <T> byModeThenNumber(type: (T) -> Int, shortName: (T) -> String): Comparator<T> =
    compareBy<T> { modeOf(type(it)) }.thenComparator { a, b -> NaturalOrder.compare(shortName(a), shortName(b)) }

fun <T> groupByMode(lines: List<T>, type: (T) -> Int, shortName: (T) -> String): List<Pair<Mode, List<T>>> =
    lines.sortedWith(byModeThenNumber(type, shortName)).groupBy { modeOf(type(it)) }.toList()

/** Port of stopMatchRank, both normalized: 0 the name starts with the query, 1 the stop's own name has it, 2 only the part telling namesakes apart does, -1 no match. */
fun stopMatchRank(name: String, query: String): Int {
    val at = name.indexOf(query)
    if (at < 0) return -1
    if (at == 0) return 0
    val own = name.indexOf(" (")
    return if (own < 0 || at < own) 1 else 2
}
