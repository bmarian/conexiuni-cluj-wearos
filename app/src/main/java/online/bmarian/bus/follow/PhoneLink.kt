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
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import online.bmarian.bus.BuildConfig
import online.bmarian.bus.Links
import online.bmarian.bus.R
import online.bmarian.bus.data.Api
import online.bmarian.bus.data.CitySync
import online.bmarian.bus.data.FavoritesSync
import online.bmarian.bus.data.LanguageSync
import online.bmarian.bus.presentation.EXTRA_FOLLOW_ID
import online.bmarian.bus.presentation.EXTRA_OPEN_FOLLOWED
import online.bmarian.bus.presentation.MainActivity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// The phone's own fix takes up to LOCATE_MS; the round trip gets as long again.
private const val PHONE_WALK_MS = 2 * LOCATE_MS

@Serializable
data class WalkRequest(val id: String, val city: String, val lat: Double, val lon: Double)

@Serializable
data class WalkAnswer(val id: String, val walk: Int? = null, val at: Long = 0)

object PhoneLink {
    const val CAPABILITY = "conexiuni_follow"
    const val PATH_STATE = "/follow/state"
    const val PATH_STOP = "/follow/stop"
    const val PATH_JOURNEY_STATE = "/journey/state"
    const val PATH_JOURNEY_STOP = "/journey/stop"
    const val PATH_JOURNEY_PLAN = "/journey/plan"
    const val PATH_JOURNEY_PLAN_REQUEST = "/journey/plan/request"
    const val PATH_WALK_REQUEST = "/follow/walk/request"
    const val PATH_WALK = "/follow/walk"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun peers(context: Context): Set<Node> = runCatching {
        Wearable.getCapabilityClient(context).getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
    }.getOrDefault(emptySet())

    private suspend fun send(context: Context, path: String, data: ByteArray) {
        relay(path, data)
        val client = Wearable.getMessageClient(context)
        for (node in peers(context)) runCatching { client.sendMessage(node.id, path, data).await() }
    }

    // Debug only: logged for the phone project's tools/wear-relay.py.
    private const val RELAY_TAG = "WearRelay"

    internal fun relay(path: String, data: ByteArray) {
        if (BuildConfig.DEBUG) Log.d(RELAY_TAG, "$path ${Base64.encodeToString(data, Base64.NO_WRAP)}")
    }

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
            PATH_WALK -> runCatching { Api.json.decodeFromString<WalkAnswer>(data.decodeToString()) }
                .getOrNull()
                ?.let { walks[it.id]?.complete(it) }
        }
    }

    private val walks = ConcurrentHashMap<String, CompletableDeferred<WalkAnswer>>()

    /** The walk to [lat]/[lon] from the phone, asked only while it's beside the watch. */
    suspend fun walkFromPhone(context: Context, city: String, lat: Double, lon: Double): WalkAnswer? {
        val nearby = peers(context).filter { it.isNearby }
        if (nearby.isEmpty() && !BuildConfig.DEBUG) return null
        val request = WalkRequest(UUID.randomUUID().toString(), city, lat, lon)
        val answer = CompletableDeferred<WalkAnswer>().also { walks[request.id] = it }
        try {
            val data = Api.json.encodeToString(request).encodeToByteArray()
            relay(PATH_WALK_REQUEST, data)
            val client = Wearable.getMessageClient(context)
            for (node in nearby) runCatching { client.sendMessage(node.id, PATH_WALK_REQUEST, data).await() }
            return withTimeoutOrNull(PHONE_WALK_MS) { answer.await() }
        } finally {
            walks.remove(request.id)
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

    private var planAsked: Pair<String, Long>? = null

    fun requestJourneyPlan(context: Context, id: String) {
        val now = System.currentTimeMillis()
        val asked = planAsked
        if (asked != null && asked.first == id && now - asked.second < 60_000) return
        planAsked = id to now
        val app = context.applicationContext
        scope.launch { send(app, PATH_JOURNEY_PLAN_REQUEST, id.encodeToByteArray()) }
    }

    suspend fun openOnPhone(context: Context, path: String): Boolean {
        val app = context.applicationContext
        val helper = RemoteActivityHelper(app, Dispatchers.Default.asExecutor())
        fun view(uri: Uri) = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(uri)
        val sent = peers(app).count { node -> runCatching { helper.startRemoteActivity(view(Links.phone(path)), node.id).await() }.isSuccess }
        if (sent > 0) return true
        return runCatching { helper.startRemoteActivity(view(Links.web(path))).await() }.isSuccess
    }
}

class PhoneListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        PhoneLink.onMessage(applicationContext, event.path, event.data)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        FavoritesSync.onDataChanged(applicationContext, events)
        LanguageSync.onDataChanged(applicationContext, events)
        CitySync.onDataChanged(applicationContext, events)
    }
}

object Mirror {
    // Messages can arrive twice; one buzz per alert.
    private val buzzed = ArrayDeque<String>()

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    internal fun channels(context: Context) {
        val manager = manager(context)
        if (manager.getNotificationChannel(FOLLOW_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(FOLLOW_CHANNEL, context.getString(R.string.follow_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    /** [alert] is the phone's buzz for this update. */
    @SuppressLint("MissingPermission")
    fun show(context: Context, state: FollowState, alert: Boolean) {
        channels(context)
        val now = (state.minutes ?: 1) <= 0
        val key = "${state.id}:${state.minutes}:${state.leaveAt != null}"
        if (alert && key !in buzzed) {
            buzzed.addLast(key)
            if (buzzed.size > 8) buzzed.removeFirst()
            Buzz.play(context, if (now) Buzz.NOW else Buzz.SOON)
        }
        val followed = state.followed
        val nowMs = System.currentTimeMillis()
        val card = card(context, state, nowMs)
        val title = "${followed.shortName} → ${followed.destination}"
        val builder = NotificationCompat.Builder(context, FOLLOW_CHANNEL)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle(title)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(context, state.id))
            .addAction(0, context.getString(R.string.stop_following), stopIntent(context, state.id))
            .show(card)
        val left = (state.arrivalAt ?: nowMs) - nowMs
        builder.setTimeoutAfter(left.coerceAtLeast(0) + 5 * 60_000L)

        OngoingActivity.Builder(context, FollowManager.slot(state.id), builder)
            .setStaticIcon(R.drawable.ic_bus)
            .setTouchIntent(openApp(context, state.id))
            .setTitle(title)
            .setStatus(card.status(followed.shortName))
            .build()
            .apply(context)
        runCatching { manager(context).notify(FollowManager.slot(state.id), builder.build()) }
    }

    fun reshow(context: Context) {
        FollowManager.follows.value.filter { it.remote }.forEach { show(context, it, alert = false) }
    }

    fun clear(context: Context, id: String) {
        manager(context).cancel(FollowManager.slot(id))
    }

    fun end(context: Context, id: String, followed: Followed, reason: String?) {
        val done = doneCard(context, followed, reason) ?: return clear(context, id)
        runCatching { manager(context).notify(FollowManager.slot(id), done) }
    }

    private fun openApp(context: Context, id: String): PendingIntent = PendingIntent.getActivity(
        context, FollowManager.slot(id),
        Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_FOLLOWED, true).putExtra(EXTRA_FOLLOW_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopIntent(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        context, FollowManager.slot(id),
        Intent(context, StopMirrorReceiver::class.java).putExtra(StopMirrorReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class StopMirrorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        if (FollowManager.follows.value.any { it.id == id }) return FollowManager.stop(context, id)
        Mirror.clear(context, id)
        PhoneLink.sendStop(context, id)
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
