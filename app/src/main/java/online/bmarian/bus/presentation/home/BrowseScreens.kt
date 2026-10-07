package online.bmarian.bus.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Text
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.logic.StopBrowse
import online.bmarian.bus.logic.browseStops
import online.bmarian.bus.logic.keyedStops
import online.bmarian.bus.logic.prefixLabel
import online.bmarian.bus.logic.sortedRoutes
import online.bmarian.bus.presentation.Heading
import online.bmarian.bus.presentation.ListRow
import online.bmarian.bus.presentation.ListScreen
import online.bmarian.bus.presentation.Note
import online.bmarian.bus.presentation.StopIcon
import online.bmarian.bus.presentation.theme.Palette
import online.bmarian.bus.presentation.theme.routeColor
import online.bmarian.bus.presentation.transformed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun TileRow(modifier: Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) { content() }
}

@Composable
private fun Tile(label: String, background: Color, width: Dp, height: Dp, onClick: () -> Unit, weight: FontWeight = FontWeight.Black) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 16.sp, fontWeight = weight, maxLines = 1)
    }
}

/** Every line as a tile in its colour; a tap opens it on the map, direction 0. */
@Composable
fun RoutesScreen(onOpenRoute: (Int) -> Unit) {
    val repository = Services.repository
    val routes by repository.routes.collectAsStateWithLifecycle()
    val failed by produceState(false) { value = !repository.refreshRoutes() }
    val rows = sortedRoutes(routes).chunked(3)

    ListScreen { spec ->
        item { Heading(stringResource(R.string.all_routes), transformed(spec)) }
        if (rows.isEmpty() && failed) item { Note(stringResource(R.string.load_failed), transformed(spec)) }
        items(rows.size) { i ->
            TileRow(transformed(spec)) {
                for (route in rows[i]) {
                    Tile(route.shortName, routeColor(route.color), 48.dp, 38.dp, onClick = { onOpenRoute(route.routeId) })
                }
            }
        }
    }
}

/** Stops by initial, then by the first two letters when an initial holds too many. */
@Composable
fun StopsScreen(letter: String?, prefix: String?, onBrowse: (letter: String, prefix: String?) -> Unit, onOpenStop: (Int) -> Unit) {
    val repository = Services.repository
    val stops by repository.stops.collectAsStateWithLifecycle()
    val failed by produceState(false) { value = !repository.refreshStops() }
    val screen by produceState<StopBrowse?>(null, stops, letter, prefix) {
        value = withContext(Dispatchers.Default) { browseStops(keyedStops(stops), letter, prefix) }
    }
    val heading = prefix?.let(::prefixLabel) ?: letter ?: stringResource(R.string.all_stops)

    ListScreen { spec ->
        item { Heading(heading, transformed(spec)) }
        if (stops.isEmpty() && failed) item { Note(stringResource(R.string.load_failed), transformed(spec)) }
        when (val current = screen) {
            is StopBrowse.Tiles -> {
                val rows = current.tiles.chunked(4)
                items(rows.size) { i ->
                    TileRow(transformed(spec)) {
                        for (tile in rows[i]) {
                            Tile(tile.label, Palette.Surface, 38.dp, 38.dp, onClick = { onBrowse(tile.letter, tile.prefix) }, weight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
            is StopBrowse.Stops -> items(current.stops.size, key = { current.stops[it].stopId }) { i ->
                val stop = current.stops[i]
                ListRow(stop.name, onClick = { onOpenStop(stop.stopId) }, modifier = transformed(spec), leading = { StopIcon() })
            }
            null -> {}
        }
    }
}
