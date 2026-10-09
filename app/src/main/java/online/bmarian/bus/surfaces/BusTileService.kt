package online.bmarian.bus.surfaces

import android.content.ComponentName
import androidx.core.graphics.toColorInt
import androidx.wear.protolayout.ActionBuilders.LaunchAction
import androidx.wear.protolayout.ActionBuilders.booleanExtra
import androidx.wear.protolayout.ActionBuilders.launchAction
import androidx.wear.protolayout.ActionBuilders.stringExtra
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_START
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.TEXT_ALIGN_START
import androidx.wear.protolayout.LayoutElementBuilders.VERTICAL_ALIGN_CENTER
import androidx.wear.protolayout.layout.box
import androidx.wear.protolayout.layout.column
import androidx.wear.protolayout.layout.row
import androidx.wear.protolayout.layout.spacer
import androidx.wear.protolayout.material3.ColorScheme
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.background
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.clip
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.modifiers.padding
import androidx.wear.protolayout.types.argb
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.tile
import androidx.wear.tiles.timeline
import androidx.wear.tiles.timelineEntry
import online.bmarian.bus.Links
import online.bmarian.bus.R
import online.bmarian.bus.follow.FollowManager
import online.bmarian.bus.presentation.EXTRA_LINK
import online.bmarian.bus.presentation.EXTRA_OPEN_FOLLOWED
import online.bmarian.bus.presentation.MainActivity
import kotlin.time.Duration.Companion.seconds

// Three rows is what a round screen has room for under the title: the followed bus takes one.
private const val ROWS_SHOWN = 3
private const val ROW_HEIGHT = 34f
private const val ROW_SPACING = 4f
private const val GRAY = 0xFF64748B.toInt()

// The app's own emerald, as on its screens.
private val TILE_COLORS = ColorScheme(
    primary = 0xFF63DBA6.argb,
    onPrimary = 0xFF003824.argb,
    primaryContainer = 0xFF005236.argb,
    onPrimaryContainer = 0xFF82F8C1.argb,
    surfaceContainer = 0xFF1B231F.argb,
    onSurface = 0xFFDEE4DF.argb,
    onSurfaceVariant = 0xFFBFC9C1.argb,
    background = 0xFF000000.argb,
    onBackground = 0xFFDEE4DF.argb,
)

/**
 * The tile: the followed bus, then the next departure at the first favorite stops, like the Maps
 * tile puts Home and Work. A row opens its bus or stop in the app.
 */
class BusTileService : Material3TileService(allowDynamicTheme = false, defaultColorScheme = TILE_COLORS) {
    override suspend fun MaterialScope.tileResponse(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val follow = FollowManager.state.value
        val stops = nextAtFavoriteStops(if (follow != null) ROWS_SHOWN - 1 else ROWS_SHOWN)
        val app = ComponentName(context, MainActivity::class.java)
        val rows = buildList {
            if (follow != null) {
                val followed = follow.followed
                add(
                    tileRow(
                        id = "followed",
                        action = launchAction(app, mapOf(EXTRA_OPEN_FOLLOWED to booleanExtra(true))),
                        line = followed.shortName,
                        color = followed.color,
                        label = followed.destination,
                        time = follow.minutes?.let { etaLabel(context, it, System.currentTimeMillis(), scheduled = !follow.live) },
                        highlighted = true,
                    ),
                )
            }
            for (stop in stops) {
                val departure = stop.departure
                add(
                    tileRow(
                        id = "stop-${stop.stopId}",
                        action = launchAction(app, mapOf(EXTRA_LINK to stringExtra(Links.app(Links.stop(stop.stopId)).toString()))),
                        line = departure?.shortName,
                        color = departure?.color,
                        label = stop.name,
                        // Worked out from the timetable alone, so always marked as such.
                        time = stop.minutes?.let { etaLabel(context, it, stop.nowMs, scheduled = true) },
                        highlighted = false,
                    ),
                )
            }
        }
        val layout = primaryLayout(
            titleSlot = { text(context.getString(R.string.app_name).layoutString, color = colorScheme.onBackground) },
            mainSlot = {
                if (rows.isEmpty()) {
                    text(
                        context.getString(R.string.tile_empty).layoutString,
                        typography = Typography.BODY_MEDIUM,
                        color = colorScheme.onSurface,
                        maxLines = 3,
                    )
                } else {
                    val spaced = rows.flatMapIndexed { i, row -> if (i == 0) listOf(row) else listOf(spacer(height = dp(ROW_SPACING)), row) }
                    column(*spaced.toTypedArray(), width = expand())
                }
            },
            onClick = clickable(launchAction(app), "app"),
        )
        return tile(timeline(timelineEntry(layout)), freshness = 60.seconds)
    }

    // One tappable line: the bus's badge, where it is, and when it comes.
    private fun MaterialScope.tileRow(
        id: String,
        action: LaunchAction,
        line: String?,
        color: String?,
        label: String,
        time: String?,
        highlighted: Boolean,
    ): LayoutElement {
        val container = if (highlighted) colorScheme.primaryContainer else colorScheme.surfaceContainer
        val content = if (highlighted) colorScheme.onPrimaryContainer else colorScheme.onSurface
        val parts = buildList {
            if (line != null) {
                add(badge(line, color))
                add(spacer(width = dp(6f)))
            }
            add(
                box(
                    text(label.layoutString, typography = Typography.LABEL_MEDIUM, color = content, alignment = TEXT_ALIGN_START),
                    width = expand(),
                    horizontalAlignment = HORIZONTAL_ALIGN_START,
                ),
            )
            add(spacer(width = dp(6f)))
            add(text((time ?: "—").layoutString, typography = Typography.LABEL_MEDIUM, color = if (highlighted) content else colorScheme.primary))
        }
        return row(
            *parts.toTypedArray(),
            width = expand(),
            height = dp(ROW_HEIGHT),
            verticalAlignment = VERTICAL_ALIGN_CENTER,
            modifier = LayoutModifier
                .background(container)
                .clip(ROW_HEIGHT / 2)
                .padding(horizontal = 12f, vertical = 0f)
                .clickable(action, id)
                .contentDescription(listOfNotNull(line, label, time).joinToString(", ")),
        )
    }

    // The line number in its CTP colour, like the badges in the app.
    private fun MaterialScope.badge(line: String, color: String?): LayoutElement = box(
        text(line.layoutString, typography = Typography.LABEL_SMALL, color = 0xFFFFFFFF.argb),
        modifier = LayoutModifier
            .background(runCatching { color!!.toColorInt() }.getOrDefault(GRAY).argb)
            .clip(6f)
            .padding(horizontal = 5f, vertical = 1f),
    )
}
