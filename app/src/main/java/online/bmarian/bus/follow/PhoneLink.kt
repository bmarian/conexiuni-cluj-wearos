package online.bmarian.bus.follow

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.concurrent.futures.await
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import online.bmarian.bus.BuildConfig
import online.bmarian.bus.Links
import online.bmarian.bus.R
import online.bmarian.bus.data.Api
import online.bmarian.bus.data.FavoritesSync
import online.bmarian.bus.presentation.EXTRA_OPEN_FOLLOWED
import online.bmarian.bus.presentation.MainActivity

/**
 * Talks to the phone app directly over the Wear OS data layer, like Maps puts the phone's
 * navigation on the watch. Both apps declare [CAPABILITY], so each only talks to a device
 * that has the other.
 */
object PhoneLink {
    const val CAPABILITY = "conexiuni_follow"
    const val PATH_STATE = "/follow/state"
    const val PATH_STOP = "/follow/stop"
    const val PATH_JOURNEY_STATE = "/journey/state"
    const val PATH_JOURNEY_STOP = "/journey/stop"
    const val PATH_JOURNEY_PLAN = "/journey/plan"
    const val PATH_JOURNEY_PLAN_REQUEST = "/journey/plan/request"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // A watch without a phone has no one to talk to; that's fine, it follows on its own.
    private suspend fun peers(context: Context): Set<Node> = runCatching {
        Wearable.getCapabilityClient(context).getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
    }.getOrDefault(emptySet())

    private suspend fun send(context: Context, path: String, data: ByteArray) {
        relay(path, data)
        val client = Wearable.getMessageClient(context)
        for (node in peers(context)) runCatching { client.sendMessage(node.id, path, data).await() }
    }

    // Debug builds only: every message also goes to the log, for the phone project's
    // tools/wear-relay.py to carry to a phone emulator that can't be paired.
    private const val RELAY_TAG = "WearRelay"

    internal fun relay(path: String, data: ByteArray) {
        if (BuildConfig.DEBUG) Log.d(RELAY_TAG, "$path ${Base64.encodeToString(data, Base64.NO_WRAP)}")
    }

    /** A message from the phone, whichever way it came. */
    internal fun onMessage(context: Context, path: String, data: ByteArray) {
        when (path) {
            PATH_STATE -> runCatching { Api.json.decodeFromString<FollowSync>(data.decodeToString()) }
                .getOrNull()
                ?.let { FollowManager.onRemoteState(context, it) }
            PATH_STOP -> FollowManager.onRemoteStop(context, data.decodeToString())
            PATH_JOURNEY_STATE -> runCatching { Api.json.decodeFromString<JourneySync>(data.decodeToString()) }
                .getOrNull()
                ?.let { JourneyMirror.onRemoteState(context, it) }
            PATH_JOURNEY_PLAN -> runCatching { Api.json.decodeFromString<Journey>(data.decodeToString()) }
                .getOrNull()
                ?.let { JourneyMirror.onRemotePlan(it) }
            FavoritesSync.PATH -> FavoritesSync.onRemoteJson(data.decodeToString())
        }
    }

    fun sendState(context: Context, sync: FollowSync) {
        val app = context.applicationContext
        scope.launch { send(app, PATH_STATE, Api.json.encodeToString(sync).encodeToByteArray()) }
    }

    fun sendStop(context: Context, id: String) {
        val app = context.applicationContext
        scope.launch { send(app, PATH_STOP, id.encodeToByteArray()) }
    }

    fun sendJourneyStop(context: Context, id: String) {
        val app = context.applicationContext
        scope.launch { send(app, PATH_JOURNEY_STOP, id.encodeToByteArray()) }
    }

    // Asked at most once a minute per journey: the phone may simply be out of reach.
    private var planAsked: Pair<String, Long>? = null

    fun requestJourneyPlan(context: Context, id: String) {
        val now = System.currentTimeMillis()
        val asked = planAsked
        if (asked != null && asked.first == id && now - asked.second < 60_000) return
        planAsked = id to now
        val app = context.applicationContext
        scope.launch { send(app, PATH_JOURNEY_PLAN_REQUEST, id.encodeToByteArray()) }
    }

    /**
     * Opens the same stop or line on the phone: in the phone app when it is there, otherwise on
     * the website. True once it was sent.
     */
    suspend fun openOnPhone(context: Context, path: String): Boolean {
        val app = context.applicationContext
        val helper = RemoteActivityHelper(app, Dispatchers.Default.asExecutor())
        fun view(uri: Uri) = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(uri)
        val sent = peers(app).count { node -> runCatching { helper.startRemoteActivity(view(Links.app(path)), node.id).await() }.isSuccess }
        if (sent > 0) return true
        return runCatching { helper.startRemoteActivity(view(Links.web(path))).await() }.isSuccess
    }
}

/**
 * Wakes the app when the phone starts, updates or stops following a bus, moves its journey on,
 * or changes the favorites.
 */
class PhoneListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        PhoneLink.onMessage(applicationContext, event.path, event.data)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        FavoritesSync.onDataChanged(applicationContext, events)
    }
}

private const val MIRROR_NOTIFICATION_ID = 9
private const val ENDED_NOTIFICATION_ID = 8
private const val ALERT_NOTIFICATION_ID = 10
private const val FOLLOW_CHANNEL = "follow"
private const val ALERT_CHANNEL = "follow_alerts"
private const val SOON_MINUTES = 2

/**
 * The phone's follow on the watch: the same countdown on the watch face, and the same buzz
 * before the bus comes, without a service of its own. The phone sends every change.
 */
object Mirror {
    // Buzzes already given for the follow on show, so each comes once.
    private var alerted: Pair<String, MutableSet<String>>? = null

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    internal fun channels(context: Context) {
        val manager = manager(context)
        if (manager.getNotificationChannel(FOLLOW_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(FOLLOW_CHANNEL, context.getString(R.string.follow_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        if (manager.getNotificationChannel(ALERT_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL, context.getString(R.string.follow_alerts_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 120, 120, 120)
                },
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun show(context: Context, state: FollowState, isNew: Boolean) {
        channels(context)
        val followed = state.followed
        val minutes = state.minutes
        val arrival = state.arrivalAt
        val title = "${followed.shortName} → ${followed.destination}"
        val builder = NotificationCompat.Builder(context, FOLLOW_CHANNEL)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle(title)
            .setContentText(
                when {
                    minutes == null -> followed.stopName
                    minutes <= 0 -> context.getString(R.string.follow_now)
                    else -> context.getString(R.string.follow_arriving, context.getString(R.string.eta_minutes, minutes))
                },
            )
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.stop_following), stopIntent(context, state.id))
        // Nobody to update it if the phone goes out of reach: it goes once the bus should have come.
        val left = (arrival ?: System.currentTimeMillis()) - System.currentTimeMillis()
        builder.setTimeoutAfter(left.coerceAtLeast(0) + 5 * 60_000L)
        val counting = minutes != null && minutes > 0 && arrival != null
        builder.setUsesChronometer(counting).setChronometerCountDown(counting).setShowWhen(counting)
        if (counting) builder.setWhen(arrival!!)

        val time: Status.Part = when {
            minutes == null -> Status.TextPart(followed.stopName)
            minutes <= 0 -> Status.TextPart(context.getString(R.string.now))
            else -> Status.TimerPart(arrival ?: System.currentTimeMillis())
        }
        OngoingActivity.Builder(context, MIRROR_NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_bus)
            .setTouchIntent(openApp(context))
            .setTitle(title)
            .setStatus(
                Status.Builder()
                    .addTemplate("#line# #time#")
                    .addPart("line", Status.TextPart(followed.shortName))
                    .addPart("time", time)
                    .build(),
            )
            .build()
            .apply(context)
        runCatching { manager(context).notify(MIRROR_NOTIFICATION_ID, builder.build()) }

        if (isNew || alerted?.first != state.id) alerted = state.id to mutableSetOf()
        if (minutes != null) {
            val given = alerted!!.second
            if (minutes in 1..SOON_MINUTES && given.add("soon")) alert(context, followed, minutes)
            if (minutes <= 0 && given.add("now")) {
                given += "soon"
                alert(context, followed, 0)
            }
        }
    }

    // The watch's own follow buzzes from its service; here a heads-up does it.
    @SuppressLint("MissingPermission")
    private fun alert(context: Context, followed: Followed, minutes: Int) {
        val text = if (minutes <= 0) context.getString(R.string.follow_now)
        else context.getString(R.string.follow_arriving, context.getString(R.string.eta_minutes, minutes))
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle("${followed.shortName} → ${followed.destination}")
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(3 * 60_000L)
            .setContentIntent(openApp(context))
            .build()
        runCatching { manager(context).notify(ALERT_NOTIFICATION_ID, notification) }
    }

    @SuppressLint("MissingPermission")
    fun end(context: Context, sync: FollowSync) {
        clear(context)
        val followed = sync.followed
        val message = when (sync.end) {
            "left" -> context.getString(R.string.follow_ended, followed.stopName)
            "none" -> context.getString(R.string.follow_no_bus)
            else -> return
        }
        channels(context)
        val done = NotificationCompat.Builder(context, FOLLOW_CHANNEL)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle("${followed.shortName} → ${followed.destination}")
            .setContentText(message)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000L)
            .build()
        runCatching { manager(context).notify(ENDED_NOTIFICATION_ID, done) }
    }

    fun clear(context: Context) {
        alerted = null
        manager(context).cancel(MIRROR_NOTIFICATION_ID)
        manager(context).cancel(ALERT_NOTIFICATION_ID)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 3,
        Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_FOLLOWED, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopIntent(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        context, 4,
        Intent(context, StopMirrorReceiver::class.java).putExtra(StopMirrorReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Stop on the phone's countdown: stops it on the phone too. */
class StopMirrorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        if (FollowManager.state.value?.id == id) return FollowManager.stop(context)
        // The app was closed since: the countdown is still up, and the phone still following.
        Mirror.clear(context)
        PhoneLink.sendStop(context, id)
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
