package online.bmarian.bus.ui.home

import online.bmarian.bus.ui.StationIcon
import online.bmarian.bus.ui.StationLines
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.data.Recent
import online.bmarian.bus.data.Recents
import online.bmarian.bus.data.Route
import online.bmarian.bus.data.Stop
import online.bmarian.bus.logic.KeyedStop
import online.bmarian.bus.logic.NaturalOrder
import online.bmarian.bus.logic.keyedStops
import online.bmarian.bus.logic.sortedRoutes
import online.bmarian.bus.logic.stopSortKey
import online.bmarian.bus.ui.LineBadge
import online.bmarian.bus.ui.ListScreen
import online.bmarian.bus.ui.Loading
import online.bmarian.bus.ui.Message
import online.bmarian.bus.ui.SubHeader
import online.bmarian.bus.ui.Title
import online.bmarian.bus.ui.cascade
import online.bmarian.bus.ui.itemModifier
import online.bmarian.bus.ui.squish
import online.bmarian.bus.ui.surface

private const val SEARCH_LIMIT = 25

private fun stations(stops: List<Stop>): List<Stop> = Services.repository.network.index.value?.oneStopPerStation(stops) ?: stops

private fun TransformingLazyColumnScope.stopItems(stops: List<Stop>, spec: TransformationSpec, onOpenStop: (Stop) -> Unit) {
    items(stops.size, key = { "s-${stops[it].stopId}" }) { i ->
        val stop = stops[i]
        val look = Services.repository.network.index.value?.look(stop.stopId)
        val press = remember { MutableInteractionSource() }
        FilledTonalButton(
            onClick = { onOpenStop(stop) },
            modifier = itemModifier(spec).squish(press).cascade(i, "list"),
            interactionSource = press,
            transformation = surface(spec),
            icon = if (look == null) ({ StationIcon(null, size = 26.dp) }) else null,
            label = { Text(stop.name, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            secondaryLabel = if (look != null) ({ StationLines(look) }) else null,
        )
    }
}

private fun TransformingLazyColumnScope.routeItems(routes: List<Route>, spec: TransformationSpec, onOpenRoute: (Route) -> Unit) {
    items(routes.size, key = { "r-${routes[it].routeId}" }) { i ->
        val route = routes[i]
        val press = remember { MutableInteractionSource() }
        FilledTonalButton(
            onClick = { onOpenRoute(route) },
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
fun SearchScreen(query: String, onSearchAgain: () -> Unit, onOpenRoute: (Int) -> Unit, onOpenStop: (Int) -> Unit) {
    val repository = Services.repository
    val routes by repository.routes.collectAsStateWithLifecycle()
    val stops by repository.stops.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val results by produceState<Pair<List<Route>, List<Stop>>?>(null, routes, stops, query) {
        value = withContext(Dispatchers.Default) {
            val needle = stopSortKey(query)
            val compact = needle.replace(" ", "")
            val lines = sortedRoutes(routes).filter { stopSortKey(it.shortName).startsWith(compact) }
            val keyed = keyedStops(stations(stops)).filter { it.key.contains(needle) }
            val found = keyed.sortedWith(compareBy<KeyedStop> { !it.key.startsWith(needle) }.thenComparator { a, b -> NaturalOrder.compare(a.key, b.key) })
            lines.take(SEARCH_LIMIT) to found.take(SEARCH_LIMIT).map { it.stop }
        }
    }

    ListScreen { spec ->
        item { Title("“$query”", spec) }
        item {
            val press = remember { MutableInteractionSource() }
            FilledTonalButton(
                onClick = onSearchAgain,
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
            routeItems(lines, spec) { route ->
                Recents.add(context, Recent(routeId = route.routeId, label = route.longName, shortName = route.shortName, color = route.color))
                onOpenRoute(route.routeId)
            }
        }
        if (found.isNotEmpty()) {
            item { SubHeader(stringResource(R.string.stops), spec) }
            stopItems(found, spec) { stop ->
                Recents.add(context, Recent(stopId = stop.stopId, label = stop.name))
                onOpenStop(stop.stopId)
            }
        }
    }
}
