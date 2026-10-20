package online.bmarian.bus.follow

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import online.bmarian.bus.R
import online.bmarian.bus.data.Api
import online.bmarian.bus.data.Cities
import online.bmarian.bus.data.UserLocation
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.justNotify
import online.bmarian.bus.logic.leadMinutes
import online.bmarian.bus.logic.tellAt
import online.bmarian.bus.logic.walkMinutes
import online.bmarian.bus.presentation.EXTRA_FOLLOW
import online.bmarian.bus.presentation.MainActivity
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val REMINDER_CHANNEL = "reminders"
private const val ALERT_CHANNEL = "reminder_alerts"
private const val REARM_SLACK_MS = 2 * 60_000L
private const val MISSED_MS = 2 * 60_000L
private const val LOCATE_MS = 5_000L
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/** The next bus of [followed] at [atMs], [lat]/[lon] its stop; [walk] and [leaveAt] once the heads-up has gone out. */
@Serializable
data class Reminder(
    val id: String,
    val followed: Followed,
    val lat: Double,
    val lon: Double,
    val atMs: Long,
    val walk: Int? = null,
    val leaveAt: Long? = null,
) {
    /** The bus at any of a station's platforms. */
    fun matches(stopIds: Collection<Int>, routeId: Int, tripId: String, city: String = Cities.current.value) =
        followed.city == city && followed.stopId in stopIds && followed.routeId == routeId && followed.tripId == tripId
}

/** What a reminder says: when to go by, to go now, or, without a fresh fix or at the stop, to get ready. */
enum class Nudge { LeaveBy, LeaveNow, GetReady }

enum class Reminded { Following, Reminder }

/** The phone's reminder rules for the watch's own button. */
object Reminders {
    private val mutable = MutableStateFlow<List<Reminder>>(emptyList())
    val all: StateFlow<List<Reminder>> = mutable

    private fun prefs(context: Context) = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    fun init(context: Context) {
        mutable.value = prefs(context).getString("all", null)
            ?.let { runCatching { Api.json.decodeFromString<List<Reminder>>(it) }.getOrNull() }
            .orEmpty()
        arm(context)
    }

    /** Alarms don't survive a restart of the watch, and exact ones can be allowed later: each one looks again. */
    internal fun arm(context: Context) {
        for (reminder in all.value) {
            val leaveAt = reminder.leaveAt
            if (leaveAt != null) schedule(context, leaveAt, pending(context, reminder.id, ReminderReceiver.ACTION_LEAVE)) else fire(context, reminder.id)
        }
    }

    /** At the stop, or too late to walk there, the bus is followed now; otherwise the user is reminded when to leave for it. */
    suspend fun remind(context: Context, followed: Followed, lat: Double, lon: Double, minutes: Int): Reminded {
        val city = followed.city
        val fix = withTimeoutOrNull(LOCATE_MS) { UserLocation.updates(context, city = city).filterNotNull().first() }?.also { UserLocation.save(context, it) }
            ?: UserLocation.recent(context, city)
        val walk = fix?.let { walkMinutes(it.lat, it.lon, lat, lon) }
        if (justNotify(walk, minutes)) {
            FollowManager.start(context, followed)
            return Reminded.Following
        }
        val reminder = Reminder(UUID.randomUUID().toString(), followed, lat, lon, System.currentTimeMillis() + minutes * 60_000L)
        put(context, reminder)
        fire(context, reminder.id)
        // Without exact alarms the time to leave can come late.
        if (!exactAllowed(context)) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        return Reminded.Reminder
    }

    private fun exactAllowed(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun remove(context: Context, id: String) {
        val reminder = all.value.firstOrNull { it.id == id } ?: return
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(pending(context, id, ReminderReceiver.ACTION_FIRE))
        alarms.cancel(pending(context, id, ReminderReceiver.ACTION_LEAVE))
        context.getSystemService(NotificationManager::class.java).cancel(slot(reminder.followed))
        mutable.update { list -> list.filterNot { it.id == id } }
        save(context)
    }

    /** Times the walk from the last fresh fix and looks again later, or speaks. */
    internal fun fire(context: Context, id: String) {
        val reminder = all.value.firstOrNull { it.id == id && it.leaveAt == null } ?: return
        val now = System.currentTimeMillis()
        if (reminder.atMs < now - MISSED_MS) return remove(context, id)
        val walk = UserLocation.recent(context, reminder.followed.city)?.let { walkMinutes(it.lat, it.lon, reminder.lat, reminder.lon) }
        val wakeAt = tellAt(reminder.atMs, walk)
        if (wakeAt > now + REARM_SLACK_MS) return schedule(context, wakeAt, pending(context, id, ReminderReceiver.ACTION_FIRE))
        val leaveAt = reminder.atMs - leadMinutes(walk) * 60_000L
        val kind = when {
            walk == null || walk == 0 -> Nudge.GetReady
            leaveAt > now + REARM_SLACK_MS -> Nudge.LeaveBy
            else -> Nudge.LeaveNow
        }
        if (kind == Nudge.LeaveBy) {
            // Kept until it's time to leave, so it can still be called off.
            put(context, reminder.copy(walk = walk, leaveAt = leaveAt))
            schedule(context, leaveAt, pending(context, id, ReminderReceiver.ACTION_LEAVE))
        } else {
            remove(context, id)
        }
        notify(context, reminder, kind, walk, leaveAt)
    }

    internal fun leave(context: Context, id: String) {
        val reminder = all.value.firstOrNull { it.id == id } ?: return
        remove(context, id)
        if (reminder.atMs < System.currentTimeMillis() - MISSED_MS) return
        notify(context, reminder, Nudge.LeaveNow, reminder.walk, reminder.leaveAt ?: System.currentTimeMillis())
    }

    private fun put(context: Context, reminder: Reminder) {
        mutable.update { list -> if (list.any { it.id == reminder.id }) list.map { if (it.id == reminder.id) reminder else it } else list + reminder }
        save(context)
    }

    private fun save(context: Context) {
        prefs(context).edit { putString("all", Api.json.encodeToString(all.value)) }
    }

    private fun schedule(context: Context, wakeAt: Long, pending: PendingIntent) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (exactAllowed(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeAt, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeAt, pending)
        }
    }

    private fun pending(context: Context, id: String, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java)
            .setAction(action)
            .setData("reminder://$id/$action".toUri())
            .putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // Fixed across restarts: the time to leave is often posted by a new process.
    private fun slot(followed: Followed) = "reminder-${followed.stopId}-${followed.tripId}".hashCode()

    private fun notify(context: Context, reminder: Reminder, kind: Nudge, walk: Int?, leaveAt: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannels(context, manager)
        val followed = reminder.followed
        val nowMs = System.currentTimeMillis()
        val minutes = ((reminder.atMs - nowMs) / 60_000).toInt().coerceAtLeast(0)
        val bus = if (minutes >= 60) context.getString(R.string.follow_at_clock, clock(reminder.atMs))
        else context.getString(R.string.follow_in, context.getString(R.string.eta_minutes, minutes))
        val text = if (walk == null || kind == Nudge.GetReady) context.getString(R.string.leave_bus, followed.shortName, bus)
        else context.getString(R.string.leave_detail, followed.shortName, bus, context.getString(R.string.eta_minutes, walk))
        val title = when (kind) {
            Nudge.LeaveBy -> context.getString(R.string.leave_by, clock(leaveAt))
            Nudge.LeaveNow -> context.getString(R.string.leave_now)
            Nudge.GetReady -> context.getString(R.string.get_ready, clock(reminder.atMs))
        }
        // A tap follows the bus from the app, so the follow may start.
        val open = PendingIntent.getActivity(
            context, slot(followed),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_FOLLOW, Api.json.encodeToString(followed)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, if (kind == Nudge.LeaveBy) REMINDER_CHANNEL else ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter((reminder.atMs - nowMs).coerceAtLeast(0) + 5 * 60_000L)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(slot(followed), notification) }
    }

    private fun ensureChannels(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(REMINDER_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(REMINDER_CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        if (manager.getNotificationChannel(ALERT_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL, context.getString(R.string.reminder_alerts_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = Buzz.NOW
                },
            )
        }
    }

    private fun clock(ms: Long) = CLOCK.format(Instant.ofEpochMilli(ms).atZone(CLUJ_ZONE))
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Exact alarms were allowed or taken away: set them all again.
        if (intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return Reminders.arm(context.applicationContext)
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        when (intent.action) {
            ACTION_FIRE -> Reminders.fire(context.applicationContext, id)
            ACTION_LEAVE -> Reminders.leave(context.applicationContext, id)
        }
    }

    companion object {
        const val ACTION_FIRE = "online.bmarian.bus.REMINDER"
        const val ACTION_LEAVE = "online.bmarian.bus.LEAVE"
        const val EXTRA_ID = "id"
    }
}

/** Alarms don't survive a restart; starting the app sets them again. */
class RemindersRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
