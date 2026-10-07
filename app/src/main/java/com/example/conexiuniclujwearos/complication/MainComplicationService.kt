package com.example.conexiuniclujwearos.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.example.conexiuniclujwearos.R
import com.example.conexiuniclujwearos.Services
import com.example.conexiuniclujwearos.data.stopSnapshot
import com.example.conexiuniclujwearos.presentation.EXTRA_STOP_ID
import com.example.conexiuniclujwearos.presentation.MainActivity
import java.time.Instant
import java.util.concurrent.TimeUnit

/** The next departure at the first favorite stop, counting down on the watch face. */
class MainComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        return shortText(PlainComplicationText.Builder("25 4m").build(), "25", null)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val stopId = Services.favorites.stops.value.firstOrNull() ?: return NoDataComplicationData()
        val snapshot = runCatching { stopSnapshot(stopId) }
            .onFailure { android.util.Log.w("Conexiuni", "complication failed", it) }
            .getOrNull() ?: return NoDataComplicationData()
        // Favorite lines first, like the stop screen.
        val next = snapshot.departures.firstOrNull() ?: return NoDataComplicationData()
        val arrival = Instant.ofEpochMilli(snapshot.takenAtMs + next.minutesLeft * 60_000L)
        val countdown = TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountDownTimeReference(arrival))
            .setMinimumTimeUnit(TimeUnit.MINUTES)
            // Many faces show only the text, so the line goes in it too.
            .setText("${next.shortName} ^1")
            .build()
        return shortText(countdown, next.shortName, stopId)
    }

    private fun shortText(text: androidx.wear.watchface.complications.data.ComplicationText, title: String, stopId: Int?) =
        ShortTextComplicationData.Builder(text, PlainComplicationText.Builder(getString(R.string.complication_label)).build())
            .setTitle(PlainComplicationText.Builder(title).build())
            .setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_bus)).build())
            .setTapAction(
                PendingIntent.getActivity(
                    this,
                    stopId ?: 0,
                    Intent(this, MainActivity::class.java).apply {
                        if (stopId != null) putExtra(EXTRA_STOP_ID, stopId)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
}
