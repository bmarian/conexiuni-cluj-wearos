package com.example.conexiuniclujwearos.tile

import android.content.Context
import androidx.core.graphics.toColorInt
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.background
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.clip
import androidx.wear.protolayout.modifiers.padding
import androidx.wear.protolayout.modifiers.toProtoLayoutModifiers
import androidx.wear.protolayout.types.LayoutColor
import androidx.wear.protolayout.types.argb
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.RequestBuilders.ResourcesRequest
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.example.conexiuniclujwearos.R
import com.example.conexiuniclujwearos.Services
import com.example.conexiuniclujwearos.data.StopSnapshot
import com.example.conexiuniclujwearos.data.stopSnapshot
import com.example.conexiuniclujwearos.logic.Arrival
import com.example.conexiuniclujwearos.logic.CLUJ_ZONE
import com.example.conexiuniclujwearos.logic.StopDeparture
import com.example.conexiuniclujwearos.logic.formatMinutesFromNow
import com.example.conexiuniclujwearos.presentation.EXTRA_STOP_ID
import com.example.conexiuniclujwearos.presentation.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import java.time.Instant
import java.time.LocalDateTime

private const val RESOURCES_VERSION = "1"
private const val ROWS = 3
private const val FRESHNESS_MS = 60_000L
// Between refreshes the tile counts down on its own, one layout per minute.
private const val COUNTDOWN_MINUTES = 15

/** The first favorite stop's next departures. */
class MainTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        scope.future {
            val stopId = Services.favorites.stops.value.firstOrNull()
            val snapshot = stopId?.let { runCatching { stopSnapshot(it) }.getOrNull() }
            tile(this@MainTileService, requestParams, stopId, snapshot)
        }

    override fun onTileResourcesRequest(requestParams: ResourcesRequest): ListenableFuture<Resources> =
        Futures.immediateFuture(Resources.Builder().setVersion(RESOURCES_VERSION).build())

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

private fun openApp(context: Context, stopId: Int?) = clickable(
    ActionBuilders.LaunchAction.Builder()
        .setAndroidActivity(
            ActionBuilders.AndroidActivity.Builder()
                .setPackageName(context.packageName)
                .setClassName(MainActivity::class.java.name)
                .apply {
                    if (stopId != null) {
                        addKeyToExtraMapping(EXTRA_STOP_ID, ActionBuilders.AndroidIntExtra.Builder().setValue(stopId).build())
                    }
                }
                .build(),
        )
        .build(),
)

private fun tile(
    context: Context,
    request: RequestBuilders.TileRequest,
    stopId: Int?,
    snapshot: StopSnapshot?,
): TileBuilders.Tile {
    val timeline = TimelineBuilders.Timeline.Builder()
    if (snapshot == null) {
        val message = context.getString(if (stopId == null) R.string.tile_no_favorites else R.string.load_failed)
        timeline.addTimelineEntry(
            TimelineBuilders.TimelineEntry.Builder().setLayout(
                LayoutElementBuilders.Layout.Builder().setRoot(
                    materialScope(context, request.deviceConfiguration) {
                        primaryLayout(
                            titleSlot = { text(context.getString(R.string.app_name).layoutString) },
                            mainSlot = { text(message.layoutString, maxLines = 3) },
                            onClick = openApp(context, stopId),
                        )
                    },
                ).build(),
            ).build(),
        )
    } else {
        val start = snapshot.takenAtMs
        for (minute in 0 until COUNTDOWN_MINUTES) {
            val from = start + minute * 60_000L
            val entry = TimelineBuilders.TimelineEntry.Builder().setLayout(
                LayoutElementBuilders.Layout.Builder().setRoot(
                    materialScope(context, request.deviceConfiguration) { stopLayout(context, snapshot, minute) },
                ).build(),
            )
            if (minute > 0) {
                entry.setValidity(
                    TimelineBuilders.TimeInterval.Builder()
                        .setStartMillis(from)
                        .setEndMillis(if (minute == COUNTDOWN_MINUTES - 1) Long.MAX_VALUE else from + 60_000L)
                        .build(),
                )
            }
            timeline.addTimelineEntry(entry.build())
        }
    }
    return TileBuilders.Tile.Builder()
        .setResourcesVersion(RESOURCES_VERSION)
        .setFreshnessIntervalMillis(FRESHNESS_MS)
        .setTileTimeline(timeline.build())
        .build()
}

private fun MaterialScope.stopLayout(context: Context, snapshot: StopSnapshot, elapsed: Int): LayoutElementBuilders.LayoutElement {
    val reference = LocalDateTime.ofInstant(Instant.ofEpochMilli(snapshot.takenAtMs + elapsed * 60_000L), CLUJ_ZONE)
    val nowLabel = context.getString(R.string.now)
    val rows = snapshot.departures.mapNotNull { departure ->
        val times = departure.nextTimes.map { Arrival(it.minutes - elapsed, it.isLive) }.filter { it.minutes >= 0 }
        if (times.isEmpty()) null else departure to times
    }.take(ROWS)

    return primaryLayout(
        titleSlot = { text(snapshot.name.layoutString, maxLines = 1) },
        mainSlot = {
            if (rows.isEmpty()) {
                text(context.getString(R.string.no_departures).layoutString, maxLines = 2)
            } else {
                val column = LayoutElementBuilders.Column.Builder().setWidth(expand())
                for ((departure, times) in rows) column.addContent(departureRow(departure, times, reference, nowLabel))
                column.build()
            }
        },
        onClick = openApp(context, snapshot.stopId),
    )
}

private fun MaterialScope.departureRow(
    departure: StopDeparture,
    times: List<Arrival>,
    reference: LocalDateTime,
    nowLabel: String,
): LayoutElementBuilders.LayoutElement {
    val first = times.first()
    val badgeColor = runCatching { departure.color.toColorInt() }.getOrDefault(0xFF64748B.toInt())
    val badge = LayoutElementBuilders.Box.Builder()
        .setModifiers(LayoutModifier.background(badgeColor.argb).clip(6f).padding(5f, 1f).toProtoLayoutModifiers())
        .addContent(text(departure.shortName.layoutString, typography = Typography.LABEL_MEDIUM, color = LayoutColor(0xFFFFFFFF.toInt())))
        .build()
    val destination = LayoutElementBuilders.Box.Builder()
        .setWidth(expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
        .setModifiers(LayoutModifier.padding(6f, 0f).toProtoLayoutModifiers())
        .addContent(
            text(
                departure.longName.substringAfterLast(" - ").layoutString,
                typography = Typography.BODY_SMALL,
                maxLines = 1,
                alignment = LayoutElementBuilders.TEXT_ALIGN_START,
            ),
        )
        .build()
    val time = text(
        ((if (departure.isStatic) "~" else "") + formatMinutesFromNow(first.minutes, reference, nowLabel)).layoutString,
        typography = Typography.LABEL_LARGE,
        color = LayoutColor(if (departure.isStatic) 0xFFF1F5F9.toInt() else 0xFF34D399.toInt()),
    )
    return LayoutElementBuilders.Row.Builder()
        .setWidth(expand())
        .setHeight(wrap())
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .setModifiers(LayoutModifier.padding(0f, 2f).toProtoLayoutModifiers())
        .addContent(badge)
        .addContent(destination)
        .addContent(time)
        .build()
}
