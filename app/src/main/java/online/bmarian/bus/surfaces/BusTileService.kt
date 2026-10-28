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
import androidx.wear.protolayout.material3.textEdgeButton
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
import online.bmarian.bus.R
import online.bmarian.bus.data.Links
import online.bmarian.bus.follow.FollowManager
import online.bmarian.bus.ui.EXTRA_FOLLOW_ID
import online.bmarian.bus.ui.EXTRA_LINK
import online.bmarian.bus.ui.EXTRA_OPEN_FOLLOWED
import online.bmarian.bus.ui.MainActivity
import online.bmarian.bus.ui.stop.destination
import online.bmarian.bus.ui.theme.ownHex
import kotlin.time.Duration.Companion.seconds

private const val ROWS_SHOWN = 2
private const val ROW_HEIGHT = 34f
private const val ROW_SPACING = 4f
private const val GRAY = 0xFF64748B.toInt()

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

class BusTileService : Material3TileService(allowDynamicTheme = false, defaultColorScheme = TILE_COLORS) {
    override suspend fun MaterialScope.tileResponse(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val nowMs = System.currentTimeMillis()
        val follows = FollowManager.follows.value.sortedBy { it.minutesAt(nowMs) ?: Int.MAX_VALUE }.take(ROWS_SHOWN)
        val near = nextNearby(context, ROWS_SHOWN - follows.size)
        val app = ComponentName(context, MainActivity::class.java)
        fun open(path: String) = launchAction(app, mapOf(EXTRA_LINK to stringExtra(Links.app(path).toString())))
        val rows = buildList {
            for (follow in follows) {
                val followed = follow.followed
                add(
                    tileRow(
                        id = "followed-${follow.id}",
                        action = launchAction(app, mapOf(EXTRA_OPEN_FOLLOWED to booleanExtra(true), EXTRA_FOLLOW_ID to stringExtra(follow.id))),
                        line = followed.shortName,
                        color = followed.color,
                        label = followed.destination,
                        time = follow.minutesAt(nowMs)?.let { etaLabel(context, it, nowMs, scheduled = !follow.live) },
                        highlighted = true,
                    ),
                )
            }
            for (departure in near?.departures.orEmpty()) {
                add(
                    tileRow(
                        id = "departure-${departure.tripId}",
                        action = open(Links.departure(near!!.stopId, departure.routeId, departure.tripId)),
                        line = departure.shortName,
                        color = departure.color,
                        label = departure.destination(),
                        time = etaLabel(
                            context, departure.nextTimes.firstOrNull()?.minutes ?: departure.minutesLeft, near.nowMs,
                            scheduled = departure.isStatic,
                        ),
                        highlighted = false,
                    ),
                )
            }
        }
        val title = when {
            near?.nearby == true -> R.string.near_you
            near != null -> R.string.saved_stops
            else -> R.string.app_name
        }
        val layout = primaryLayout(
            titleSlot = { text(context.getString(title).layoutString, color = colorScheme.onBackground) },
            mainSlot = {
                if (near == null && rows.isEmpty()) {
                    text(
                        context.getString(R.string.tile_empty).layoutString,
                        typography = Typography.BODY_MEDIUM,
                        color = colorScheme.onSurface,
                        maxLines = 3,
                    )
                } else {
                    val name = near?.let {
                        text(it.name.layoutString, typography = Typography.LABEL_MEDIUM, color = colorScheme.primary, maxLines = 1)
                    }
                    val spaced = listOfNotNull(name) + rows.flatMap { listOf(spacer(height = dp(ROW_SPACING)), it) }
                    column(*spaced.toTypedArray(), width = expand())
                }
            },
            bottomSlot = {
                textEdgeButton(onClick = clickable(open(Links.SEARCH), "search")) { text(context.getString(R.string.search).layoutString) }
            },
            onClick = clickable(launchAction(app), "app"),
        )
        return tile(timeline(timelineEntry(layout)), freshness = 60.seconds)
    }

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

    private fun MaterialScope.badge(line: String, color: String?): LayoutElement = box(
        text(line.layoutString, typography = Typography.LABEL_SMALL, color = 0xFFFFFFFF.argb),
        modifier = LayoutModifier
            .background(runCatching { ownHex(line, color)!!.toColorInt() }.getOrDefault(GRAY).argb)
            .clip(6f)
            .padding(horizontal = 5f, vertical = 1f),
    )
}
