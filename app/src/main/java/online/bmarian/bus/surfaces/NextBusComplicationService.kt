package online.bmarian.bus.surfaces

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import online.bmarian.bus.Links
import online.bmarian.bus.R
import online.bmarian.bus.follow.FollowManager
import online.bmarian.bus.follow.soonest
import online.bmarian.bus.presentation.EXTRA_FOLLOW_ID
import online.bmarian.bus.presentation.EXTRA_OPEN_FOLLOWED
import online.bmarian.bus.presentation.MainActivity
import java.time.Instant
import java.util.concurrent.TimeUnit

class NextBusComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val type = request.complicationType
        FollowManager.follows.value.soonest()?.let { follow ->
            val followed = follow.followed
            return data(type, followed.shortName, followed.destination, follow.arrivalAt, scheduled = !follow.live, tap = openFollowed(follow.id))
        }
        val next = nextAtFavoriteStops(1).firstOrNull() ?: return data(type, null, null, null, scheduled = false, tap = openApp())
        val at = next.minutes?.let { next.nowMs + it * 60_000L }
        return data(type, next.departure?.shortName, next.name, at, scheduled = true, tap = openStop(next.stopId))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val name = plain(getString(R.string.next_bus))
        val minutes = getString(R.string.eta_minutes, 5)
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(minutes), name)
                .setTitle(plain("25"))
                .setMonochromaticImage(icon())
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(plain("25 · $minutes"), name)
                .setTitle(name)
                .setMonochromaticImage(icon())
                .build()
            else -> null
        }
    }

    private fun data(
        type: ComplicationType,
        line: String?,
        where: String?,
        at: Long?,
        scheduled: Boolean,
        tap: PendingIntent,
    ): ComplicationData? {
        // A ^ would read as a placeholder.
        val about = listOfNotNull(line, where).joinToString(", ").replace("^", "^^")
        val description = if (at != null) countdown(at, TimeDifferenceStyle.WORDS_SINGLE_UNIT, "$about ^1")
        else plain(about.ifEmpty { getString(R.string.next_bus) })
        val icon = icon()
        val mark = if (scheduled) "~" else ""
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                if (at != null) countdown(at, TimeDifferenceStyle.SHORT_SINGLE_UNIT, "$mark^1") else plain("—"),
                description,
            )
                .setTitle(line?.let(::plain))
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                when {
                    at == null -> plain(line ?: "—")
                    line == null -> countdown(at, TimeDifferenceStyle.SHORT_WORDS_SINGLE_UNIT, "$mark^1")
                    else -> countdown(at, TimeDifferenceStyle.SHORT_WORDS_SINGLE_UNIT, "${line.replace("^", "^^")} · $mark^1")
                },
                description,
            )
                .setTitle(where?.let(::plain))
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            else -> null
        }
    }

    private fun plain(text: String) = PlainComplicationText.Builder(text).build()

    private fun icon() = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_bus)).build()

    private fun countdown(at: Long, style: TimeDifferenceStyle, text: String): ComplicationText =
        TimeDifferenceComplicationText.Builder(style, CountDownTimeReference(Instant.ofEpochMilli(at)))
            .setMinimumTimeUnit(TimeUnit.MINUTES)
            .setText(text)
            .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 8,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openFollowed(id: String): PendingIntent = PendingIntent.getActivity(
        this, 9,
        Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN_FOLLOWED, true).putExtra(EXTRA_FOLLOW_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openStop(stopId: Int): PendingIntent = PendingIntent.getActivity(
        this, 10,
        Intent(Intent.ACTION_VIEW, Links.app(Links.stop(stopId)), this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
