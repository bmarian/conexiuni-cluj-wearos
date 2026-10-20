package online.bmarian.bus.follow

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.Status
import online.bmarian.bus.Links
import online.bmarian.bus.R
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.presentation.EXTRA_LINK
import online.bmarian.bus.presentation.MainActivity
import java.time.Instant
import java.time.format.DateTimeFormatter

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private const val DONE_SHOWN_MS = 2 * 60_000L
internal const val FOLLOW_CHANNEL = "follow"

/** What a follow's card says, the time it counts down to, and the watch face's text when there's none. */
internal class Card(val text: String, val countTo: Long?, val chip: String)

internal fun card(context: Context, state: FollowState, nowMs: Long): Card {
    val followed = state.followed
    val minutes = state.minutes ?: return Card(followed.stopName, null, followed.stopName)
    // Still waiting for the user to set off.
    val leaveIn = state.leaveIn(nowMs)
    if (leaveIn != null && leaveIn <= 0) return context.getString(R.string.leave_now).let { Card(it, null, it) }
    if (leaveIn != null) {
        val text = state.leaveBy?.let { context.getString(R.string.leave_by, CLOCK.format(Instant.ofEpochMilli(it).atZone(CLUJ_ZONE))) }
            ?: context.getString(R.string.leave_in, context.getString(R.string.eta_minutes, leaveIn))
        return Card(text, state.leaveAt, text)
    }
    if (minutes <= 0) return Card(context.getString(R.string.follow_now), null, context.getString(R.string.now))
    return Card(
        context.getString(R.string.follow_arriving, context.getString(R.string.eta_minutes, minutes)),
        state.arrivalAt,
        context.getString(R.string.now),
    )
}

internal fun NotificationCompat.Builder.show(card: Card): NotificationCompat.Builder {
    val counting = card.countTo != null
    setContentText(card.text).setUsesChronometer(counting).setChronometerCountDown(counting).setShowWhen(counting)
    card.countTo?.let(::setWhen)
    return this
}

internal fun Card.status(line: String): Status = Status.Builder()
    .addTemplate("#line# #time#")
    .addPart("line", Status.TextPart(line))
    .addPart("time", countTo?.let { Status.TimerPart(it) } ?: Status.TextPart(chip))
    .build()

/** How a follow ended, posted in the card's place for a while. Null when it should just go. */
internal fun doneCard(context: Context, followed: Followed, reason: String?): Notification? {
    val title = when (reason) {
        "left" -> context.getString(R.string.follow_done, followed.stopName)
        "none" -> context.getString(R.string.follow_no_bus)
        else -> return null
    }
    val open = PendingIntent.getActivity(
        context, followed.stopId,
        Intent(context, MainActivity::class.java).putExtra(EXTRA_LINK, Links.app(Links.stop(followed.stopId)).toString()),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return NotificationCompat.Builder(context, FOLLOW_CHANNEL)
        .setSmallIcon(R.drawable.ic_bus)
        .setContentTitle("${followed.shortName} → ${followed.destination}")
        .setContentText(title)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setAutoCancel(true)
        .setTimeoutAfter(DONE_SHOWN_MS)
        .setContentIntent(open)
        .build()
}
