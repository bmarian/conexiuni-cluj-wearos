package online.bmarian.bus.follow

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.data.Api
import online.bmarian.bus.data.LiveState
import online.bmarian.bus.data.LiveStream
import online.bmarian.bus.data.StopInfo
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.StopDeparture
import online.bmarian.bus.logic.stopDepartures
import online.bmarian.bus.presentation.EXTRA_FOLLOW_ID
import online.bmarian.bus.presentation.EXTRA_OPEN_FOLLOWED
import online.bmarian.bus.presentation.MainActivity
import java.time.Instant
import java.time.LocalDateTime

private const val FOLLOW_CHANNEL = "follow"
private const val TICK_MS = 15_000L
private const val LIVE_WAIT_MS = 6_000L
private const val STOP_INFO_REFRESH_MS = 120_000L
private const val SOON_MINUTES = 2
private const val GIVE_UP_MS = 90 * 60_000L

/** Follows every local bus in [FollowManager.follows], each on its own card. */
class FollowService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, Job>()
    private val latest = mutableMapOf<String, FollowState>()
    // OngoingActivity re-posts these builders on status changes.
    private val builders = mutableMapOf<String, NotificationCompat.Builder>()
    private val ongoing = mutableMapOf<String, OngoingActivity>()
    // The follow whose card keeps the service in the foreground.
    private var foreground: String? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Stopped from the app: it leaves the list.
        scope.launch {
            FollowManager.follows.collect { list ->
                val ids = list.mapTo(HashSet()) { it.id }
                jobs.keys.filter { it !in ids }.forEach { finish(it, "stopped") }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_STOP) {
            intent.getStringExtra(EXTRA_ID)?.let { FollowManager.stop(this, it) }
            return START_NOT_STICKY
        }
        val followed = intent?.getStringExtra(EXTRA_FOLLOWED)
            ?.let { runCatching { Api.json.decodeFromString<Followed>(it) }.getOrNull() }
        val id = intent?.getStringExtra(EXTRA_ID)
        if (followed != null && id != null) begin(followed, id)
        if (jobs.isEmpty()) {
            // Stopped before it started; the system still wants it in the foreground once.
            createChannel()
            ServiceCompat.startForeground(this, FollowManager.slot("placeholder"), baseNotification(null, "").build(), foregroundType())
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        for ((id, state) in latest) {
            PhoneLink.sendState(this, state.toSync(end = "stopped"))
            FollowManager.ended(id)
        }
        super.onDestroy()
    }

    private val manager get() = getSystemService(NotificationManager::class.java)

    private fun begin(followed: Followed, id: String) {
        if (id in jobs) return
        // Redelivered after the process was killed: the list starts empty.
        if (FollowManager.follows.value.none { it.id == id }) FollowManager.mutableFollows.update { it + FollowState(followed, id = id) }
        val state = FollowManager.follows.value.first { it.id == id }
        latest[id] = state
        PhoneLink.sendState(this, state.toSync())

        createChannel()
        val slot = FollowManager.slot(id)
        val base = baseNotification(followed, id)
        describe(base, followed, null, 0)
        builders[id] = base
        ongoing[id] = OngoingActivity.Builder(this, slot, base)
            .setStaticIcon(R.drawable.ic_bus)
            .setTouchIntent(openApp(followed, id))
            .setTitle("${followed.shortName} → ${followed.destination}")
            .setStatus(status(followed, null, 0))
            .build()
            .also { it.apply(this) }
        val shown = foreground
        if (shown == null) {
            ServiceCompat.startForeground(this, slot, base.build(), foregroundType())
            foreground = id
        } else {
            // Every start request has to be answered with startForeground.
            ServiceCompat.startForeground(this, FollowManager.slot(shown), builders.getValue(shown).build(), foregroundType())
            manager.notify(slot, base.build())
        }
        jobs[id] = scope.launch { track(followed, id) }
    }

    private suspend fun track(followed: Followed, id: String) = coroutineScope {
        val repository = Services.repository
        val startedAt = System.currentTimeMillis()
        val info = MutableStateFlow(repository.cachedStopInfo(followed.stopId)?.only(followed.routeId))
        // Nothing is judged before the first live snapshot, so a bus the timetable still shows as due isn't taken as arrived.
        val live = MutableStateFlow<LiveState?>(null)
        val ready = MutableStateFlow(false)
        val clock = MutableStateFlow(startedAt)

        launch {
            while (true) {
                runCatching { info.value = repository.stopInfo(followed.stopId).only(followed.routeId) }
                delay(STOP_INFO_REFRESH_MS)
            }
        }
        launch {
            LiveStream.live(listOf(followed.stopId), emptyList()).collect {
                live.value = it
                ready.value = true
            }
        }
        launch {
            delay(LIVE_WAIT_MS)
            ready.value = true
        }
        launch {
            while (true) {
                delay(TICK_MS)
                clock.value = System.currentTimeMillis()
            }
        }

        val progress = Progress()
        combine(info.filterNotNull(), live, ready.filter { it }, clock) { stop, state, _, nowMs ->
            withContext(Dispatchers.Default) {
                val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), CLUJ_ZONE)
                stopDepartures(stop, state, now, nowMs)
                    .firstOrNull { it.routeId == followed.routeId && it.tripId == followed.tripId } to nowMs
            }
        }.collect { (departure, nowMs) ->
            if (nowMs - startedAt > GIVE_UP_MS) return@collect finish(id, "stopped")
            update(followed, id, departure, nowMs, progress)
        }
    }

    private class Progress {
        var lastMinutes: Int? = null
        var buzzedSoon = false
        var arrived = false
    }

    @SuppressLint("MissingPermission")
    private fun update(followed: Followed, id: String, departure: StopDeparture?, nowMs: Long, progress: Progress) {
        val first = departure?.nextTimes?.firstOrNull()
        if (first == null) return finish(id, "none")
        val minutes = first.minutes
        // A jump after the bus was close means it passed, even if no reading caught it at 0.
        if (progress.buzzedSoon && minutes > (progress.lastMinutes ?: 0) + 2) {
            return finish(id, "left")
        }
        // Only changes after the first reading buzz: the user just asked.
        val opening = progress.lastMinutes == null
        if (minutes <= SOON_MINUTES && !progress.buzzedSoon) {
            progress.buzzedSoon = true
            if (!opening && minutes > 0) Buzz.play(this, Buzz.SOON)
        }
        if (minutes == 0 && !progress.arrived) {
            progress.arrived = true
            if (!opening) Buzz.play(this, Buzz.NOW)
        }
        progress.lastMinutes = minutes

        val live = !departure.isStatic
        val arrival = nowMs + minutes * 60_000L
        if (id !in jobs) return
        val state = FollowState(followed, minutes, live, departure.nextTimes.drop(1).map { it.minutes }, id, arrival)
        latest[id] = state
        FollowManager.put(state)
        PhoneLink.sendState(this, state.toSync())
        builders[id]?.let { describe(it, followed, minutes, arrival) }
        ongoing[id]?.update(this, status(followed, minutes, arrival))
    }

    private fun finish(id: String, reason: String) {
        val job = jobs.remove(id) ?: return
        latest.remove(id)?.let { PhoneLink.sendState(this, it.toSync(end = reason)) }
        builders.remove(id)
        ongoing.remove(id)
        FollowManager.ended(id)
        val next = jobs.keys.firstOrNull()
        when {
            next == null -> {
                foreground = null
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(lastStartId)
            }
            // Moving the service to another card cancels this one.
            foreground == id -> {
                foreground = next
                ServiceCompat.startForeground(this, FollowManager.slot(next), builders.getValue(next).build(), foregroundType())
            }
            else -> manager.cancel(FollowManager.slot(id))
        }
        job.cancel()
    }

    private fun foregroundType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

    private fun baseNotification(followed: Followed?, id: String) = NotificationCompat.Builder(this, FOLLOW_CHANNEL)
        .setSmallIcon(R.drawable.ic_bus)
        .setContentTitle(followed?.let { "${it.shortName} → ${it.destination}" })
        .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(openApp(followed, id))
        .addAction(0, getString(R.string.stop_following), stopIntent(id))

    private fun describe(builder: NotificationCompat.Builder, followed: Followed, minutes: Int?, arrivalMs: Long) {
        builder.setContentText(
            when {
                minutes == null -> followed.stopName
                minutes == 0 -> getString(R.string.follow_now)
                else -> getString(R.string.follow_arriving, getString(R.string.eta_minutes, minutes))
            },
        )
        val counting = minutes != null && minutes > 0
        builder.setUsesChronometer(counting).setChronometerCountDown(counting).setShowWhen(counting)
        if (counting) builder.setWhen(arrivalMs)
    }

    private fun status(followed: Followed, minutes: Int?, arrivalMs: Long): Status {
        val time: Status.Part = when {
            minutes == null -> Status.TextPart(followed.stopName)
            minutes == 0 -> Status.TextPart(getString(R.string.now))
            else -> Status.TimerPart(arrivalMs)
        }
        return Status.Builder()
            .addTemplate("#line# #time#")
            .addPart("line", Status.TextPart(followed.shortName))
            .addPart("time", time)
            .build()
    }

    private fun openApp(followed: Followed?, id: String): PendingIntent = PendingIntent.getActivity(
        this, FollowManager.slot(id),
        Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN_FOLLOWED, followed != null).putExtra(EXTRA_FOLLOW_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopIntent(id: String): PendingIntent = PendingIntent.getService(
        this, FollowManager.slot(id),
        Intent(this, FollowService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(FOLLOW_CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(FOLLOW_CHANNEL, getString(R.string.follow_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val EXTRA_FOLLOWED = "followed"
        const val EXTRA_ID = "id"
        const val ACTION_STOP = "online.bmarian.bus.STOP_FOLLOWING"
    }
}

private fun StopInfo.only(routeId: Int) = copy(shapesInfo = shapesInfo.filter { it.routeId == routeId })
