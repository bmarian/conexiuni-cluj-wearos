package online.bmarian.bus.follow

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import online.bmarian.bus.Links
import online.bmarian.bus.R
import online.bmarian.bus.presentation.MainActivity

/**
 * The step-by-step trip the phone app runs, as it sends it after every change. The watch only
 * shows it. The phone app sends the same JSON.
 */
@Serializable
data class JourneySync(
    val id: String,
    // Where the journey goes.
    val title: String,
    // The current instruction, already in the phone's language.
    val step: String,
    val detail: String? = null,
    // The line number for a badge, when a bus is involved, in its colour as the API gives it.
    val line: String? = null,
    val color: String? = null,
    // When the countdown reaches zero, in epoch milliseconds.
    val arrivalAt: Long? = null,
    // Moves on with each step: one buzz.
    val stepIndex: Int = 0,
    // A step that needs the user, like getting off next or the bus being here: a stronger buzz.
    val alert: Boolean = false,
    // The journey is over.
    val end: Boolean = false,
    // Version 2: what the step is (StepType), the leg under way, stops left on the bus, when
    // you get there and how much of the trip is behind you (0 to 1). Older phones leave them out.
    val stepType: String? = null,
    val legIndex: Int = 0,
    val stopsLeft: Int? = null,
    val totalStops: Int? = null,
    val etaAt: Long? = null,
    val progress: Float? = null,
    // Where the phone is, for the map when the watch has no position of its own.
    val lat: Double? = null,
    val lon: Double? = null,
    val bearing: Float? = null,
)

private const val JOURNEY_NOTIFICATION_ID = 11
private const val STEP_NOTIFICATION_ID = 12
private const val FOLLOW_CHANNEL = "follow"
private const val ALERT_CHANNEL = "follow_alerts"
private const val URGENT_CHANNEL = "journey_alerts"
// Nobody updates it once the phone is out of reach: it goes this long after the last word,
// or this long after the time it was counting down to, whichever is later.
private const val STALE_MS = 15 * 60_000L
private const val PAST_ARRIVAL_MS = 10 * 60_000L

/**
 * The phone's journey on the watch, like Maps puts its navigation there: the current step on the
 * watch face, and a buzz each time it moves on, stronger when the step needs the user.
 */
object JourneyMirror {
    private val mutableState = MutableStateFlow<JourneySync?>(null)
    val state: StateFlow<JourneySync?> = mutableState

    // The whole trip, for the journey screen's map; the phone sends it at the start.
    private val mutablePlan = MutableStateFlow<Journey?>(null)
    val plan: StateFlow<Journey?> = mutablePlan

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var expiry: Job? = null

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    @Synchronized
    internal fun onRemoteState(context: Context, sync: JourneySync) {
        val current = state.value
        if (sync.end) {
            if (current == null || current.id == sync.id) clear(context)
            return
        }
        mutableState.value = sync
        // Opened late, or the plan got lost on the way: ask the phone for it again.
        if (plan.value?.id != sync.id) PhoneLink.requestJourneyPlan(context, sync.id)
        val now = System.currentTimeMillis()
        show(context, sync, now)
        // The first step of a new journey was just seen on the phone; only the ones after buzz.
        val moved = if (current?.id == sync.id) current.stepIndex != sync.stepIndex else sync.alert
        if (moved) alert(context, sync)
        expiry?.cancel()
        expiry = scope.launch {
            delay(staleAfter(sync, now))
            if (state.value === sync) clear(context)
        }
    }

    @Synchronized
    internal fun onRemotePlan(journey: Journey) {
        mutablePlan.value = journey
    }

    /** End pressed on the watch: it ends on the phone too. */
    @Synchronized
    fun end(context: Context, id: String) {
        if (state.value.let { it == null || it.id == id }) clear(context)
        PhoneLink.sendJourneyStop(context, id)
    }

    @Synchronized
    fun clear(context: Context) {
        expiry?.cancel()
        expiry = null
        mutableState.value = null
        mutablePlan.value = null
        manager(context).cancel(JOURNEY_NOTIFICATION_ID)
        manager(context).cancel(STEP_NOTIFICATION_ID)
    }

    private fun staleAfter(sync: JourneySync, now: Long) =
        maxOf(STALE_MS, (sync.arrivalAt ?: 0) + PAST_ARRIVAL_MS - now)

    private fun channels(context: Context) {
        Mirror.channels(context)
        val manager = manager(context)
        if (manager.getNotificationChannel(URGENT_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(URGENT_CHANNEL, context.getString(R.string.journey_alerts_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 150, 400, 150, 400)
                },
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun show(context: Context, sync: JourneySync, now: Long) {
        channels(context)
        val arrival = sync.arrivalAt?.takeIf { it > now }
        val builder = NotificationCompat.Builder(context, FOLLOW_CHANNEL)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle(sync.step)
            .setContentText(sync.detail)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.end_journey), endIntent(context, sync.id))
            .setTimeoutAfter(staleAfter(sync, now))
            .setUsesChronometer(arrival != null)
            .setChronometerCountDown(arrival != null)
            .setShowWhen(arrival != null)
        if (arrival != null) builder.setWhen(arrival)

        val time: Status.Part = if (arrival != null) Status.TimerPart(arrival) else Status.TextPart(sync.step)
        val status = Status.Builder()
        val line = sync.line
        if (line != null) {
            status.addTemplate("#line# #time#").addPart("line", Status.TextPart(line))
        } else {
            status.addTemplate("#time#")
        }
        OngoingActivity.Builder(context, JOURNEY_NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_bus)
            .setTouchIntent(openApp(context))
            .setTitle(sync.title)
            .setStatus(status.addPart("time", time).build())
            .build()
            .apply(context)
        runCatching { manager(context).notify(JOURNEY_NOTIFICATION_ID, builder.build()) }
    }

    // A heads-up for the new step; its channel decides how hard it buzzes.
    @SuppressLint("MissingPermission")
    private fun alert(context: Context, sync: JourneySync) {
        val notification = NotificationCompat.Builder(context, if (sync.alert) URGENT_CHANNEL else ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle(sync.step)
            .setContentText(sync.detail)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(3 * 60_000L)
            .setContentIntent(openApp(context))
            .build()
        runCatching { manager(context).notify(STEP_NOTIFICATION_ID, notification) }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 5,
        Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Links.app(Links.JOURNEY)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun endIntent(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        context, 6,
        Intent(context, EndJourneyReceiver::class.java).putExtra(EndJourneyReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** End on the journey's notification. */
class EndJourneyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        JourneyMirror.end(context, id)
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
