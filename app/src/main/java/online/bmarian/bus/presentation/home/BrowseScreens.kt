package online.bmarian.bus.presentation.home

import online.bmarian.bus.presentation.StationIcon
import online.bmarian.bus.presentation.StationLines
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.data.Route
import online.bmarian.bus.data.Stop
import online.bmarian.bus.logic.NaturalOrder
import online.bmarian.bus.logic.StopBrowse
import online.bmarian.bus.logic.browseStops
import online.bmarian.bus.logic.keyedStops
import online.bmarian.bus.logic.prefixLabel
import online.bmarian.bus.logic.sortedRoutes
import online.bmarian.bus.logic.stopSortKey
import online.bmarian.bus.presentation.LineBadge
import online.bmarian.bus.presentation.ListScreen
import online.bmarian.bus.presentation.Loading
import online.bmarian.bus.presentation.Message
import online.bmarian.bus.presentation.SubHeader
import online.bmarian.bus.presentation.Title
import online.bmarian.bus.presentation.cascade
import online.bmarian.bus.presentation.contentModifier
import online.bmarian.bus.presentation.itemModifier
import online.bmarian.bus.presentation.squish
import online.bmarian.bus.presentation.surface
import online.bmarian.bus.presentation.theme.lineColor

private const val SEARCH_LIMIT = 25

private fun stations(stops: List<Stop>): List<Stop> = Services.repository.network.index.value?.oneStopPerStation(stops) ?: stops

private fun TransformingLazyColumnScope.stopItems(stops: List<Stop>, spec: TransformationSpec, onOpenStop: (Int) -> Unit) {
    items(stops.size, key = { "s-${stops[it].stopId}" }) { i ->
        val stop = stops[i]
        val look = Services.repository.network.index.value?.look(stop.stopId)
        val press = remember { MutableInteractionSource() }
        FilledTonalButton(
            onClick = { onOpenStop(stop.stopId) },
            modifier = itemModifier(spec).squish(press).cascade(i, "list"),
            interactionSource = press,
            transformation = surface(spec),
            icon = { StationIcon(look, size = 26.dp) },
            label = { Text(stop.name, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            secondaryLabel = if (look != null) ({ StationLines(look) }) else null,
        )
    }
}

private fun TransformingLazyColumnScope.routeItems(routes: List<Route>, spec: TransformationSpec, onOpenRoute: (Int) -> Unit) {
    items(routes.size, key = { "r-${routes[it].routeId}" }) { i ->
        val route = routes[i]
        val press = remember { MutableInteractionSource() }
        FilledTonalButton(
            onClick = { onOpenRoute(route.routeId) },
            modifier = itemModifier(spec).squish(press).cascade(i, "list"),
            interactionSource = press,
            transformation = surface(spec),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LineBadge(route.shortName, route.color)
                Text(route.longName, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun RoutesScreen(onOpenRoute: (Int) -> Unit) {
    val repository = Services.repository
    val routes by repository.routes.collectAsStateWithLifecycle()
    val failed by produceState(false) { value = !repository.refreshNetwork() }
    val rows = sortedRoutes(routes).chunked(3)

    ListScreen { spec ->
        item { Title(stringResource(R.string.all_routes), spec) }
        if (rows.isEmpty()) item { if (failed) Message(stringResource(R.string.load_failed), spec) else Loading(spec) }
        items(rows.size) { i ->
            Row(contentModifier(spec), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (route in rows[i]) {
                    Button(
                        onClick = { onOpenRoute(route.routeId) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = lineColor(route.shortName, route.color), contentColor = Color.White),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(route.shortName, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
                repeat(3 - rows[i].size) { Row(Modifier.weight(1f)) {} }
            }
        }
    }
}

@Composable
fun StopsScreen(letter: String?, prefix: String?, onBrowse: (letter: String, prefix: String?) -> Unit, onOpenStop: (Int) -> Unit) {
    val repository = Services.repository
    val stops by repository.stops.collectAsStateWithLifecycle()
    val failed by produceState(false) { value = !repository.refreshNetwork() }
    val screen by produceState<StopBrowse?>(null, stops, letter, prefix) {
        value = withContext(Dispatchers.Default) { browseStops(keyedStops(stations(stops)), letter, prefix) }
    }
    val heading = prefix?.let(::prefixLabel) ?: letter ?: stringResource(R.string.all_stops)

    ListScreen { spec ->
        item { Title(heading, spec) }
        if (stops.isEmpty()) item { if (failed) Message(stringResource(R.string.load_failed), spec) else Loading(spec) }
        when (val current = screen) {
            is StopBrowse.Tiles -> {
                val rows = current.tiles.chunked(3)
                items(rows.size) { i ->
                    Row(contentModifier(spec), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (tile in rows[i]) {
                            FilledTonalButton(
                                onClick = { onBrowse(tile.letter, tile.prefix) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(tile.label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            }
                        }
                        repeat(3 - rows[i].size) { Row(Modifier.weight(1f)) {} }
                    }
                }
            }
            is StopBrowse.Stops -> stopItems(current.stops, spec, onOpenStop)
            null -> {}
        }
    }
}

@Composable
fun SearchScreen(query: String, onSearch: (String) -> Unit, onOpenRoute: (Int) -> Unit, onOpenStop: (Int) -> Unit) {
    val repository = Services.repository
    val routes by repository.routes.collectAsStateWithLifecycle()
    val stops by repository.stops.collectAsStateWithLifecycle()
    val search = rememberSearchInput(onSearch)
    val results by produceState<Pair<List<Route>, List<Stop>>?>(null, routes, stops, query) {
        value = withContext(Dispatchers.Default) {
            val needle = stopSortKey(query)
            val compact = needle.replace(" ", "")
            val lines = sortedRoutes(routes).filter { stopSortKey(it.shortName).startsWith(compact) }
            val keyed = keyedStops(stations(stops)).filter { it.key.contains(needle) }
            val found = keyed.sortedWith(compareBy<online.bmarian.bus.logic.KeyedStop> { !it.key.startsWith(needle) }.thenComparator { a, b -> NaturalOrder.compare(a.key, b.key) })
            lines.take(SEARCH_LIMIT) to found.take(SEARCH_LIMIT).map { it.stop }
        }
    }

    ListScreen { spec ->
        item { Title("“$query”", spec) }
        item {
            val press = remember { MutableInteractionSource() }
            FilledTonalButton(
                onClick = search,
                modifier = itemModifier(spec).squish(press),
                interactionSource = press,
                transformation = surface(spec),
                icon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                label = { Text(stringResource(R.string.search_again)) },
            )
        }
        val (lines, found) = results ?: run {
            item { Loading(spec) }
            return@ListScreen
        }
        if (lines.isEmpty() && found.isEmpty()) item { Message(stringResource(R.string.no_results, query), spec) }
        if (lines.isNotEmpty()) {
            item { SubHeader(stringResource(R.string.lines), spec) }
            routeItems(lines, spec, onOpenRoute)
        }
        if (found.isNotEmpty()) {
            item { SubHeader(stringResource(R.string.stops), spec) }
            stopItems(found, spec, onOpenStop)
        }
    }
}
