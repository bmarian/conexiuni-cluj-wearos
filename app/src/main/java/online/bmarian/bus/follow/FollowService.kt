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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.data.Api
import online.bmarian.bus.data.StopInfo
import online.bmarian.bus.data.Vehicle
import online.bmarian.bus.data.VehicleStream
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.StopDeparture
import online.bmarian.bus.logic.computeStopDepartures
import online.bmarian.bus.presentation.EXTRA_OPEN_FOLLOWED
import online.bmarian.bus.presentation.MainActivity
import java.time.Instant
import java.util.UUID
import java.time.LocalDateTime

private const val NOTIFICATION_ID = 7
private const val FOLLOW_CHANNEL = "follow"
private const val TICK_MS = 15_000L
private const val LIVE_WAIT_MS = 6_000L
private const val STOP_INFO_REFRESH_MS = 120_000L
private const val SOON_MINUTES = 2
private const val GIVE_UP_MS = 90 * 60_000L

class FollowService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var ongoing: OngoingActivity? = null
    // OngoingActivity re-posts this builder on status changes.
    private var builder: NotificationCompat.Builder? = null
    private var current: FollowState? = null
    private var ended = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (FollowManager.state.value?.id == current?.id) FollowManager.mutableState.value = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val followed = intent?.getStringExtra(EXTRA_FOLLOWED)
            ?.let { runCatching { Api.json.decodeFromString<Followed>(it) }.getOrNull() }
            ?: FollowManager.state.value?.followed
        if (followed == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(EXTRA_ID) ?: FollowManager.state.value?.id?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        val state = FollowManager.state.value?.takeIf { it.id == id && !it.remote } ?: FollowState(followed, id = id)
        FollowManager.mutableState.value = state
        current = state
        ended = false
        PhoneLink.sendState(this, state.toSync())

        createChannel()
        val base = baseNotification(followed)
        describe(base, followed, null, 0)
        builder = base
        ongoing = OngoingActivity.Builder(this, NOTIFICATION_ID, base)
            .setStaticIcon(R.drawable.ic_bus)
            .setTouchIntent(openApp(followed))
            .setTitle("${followed.shortName} → ${followed.destination}")
            .setStatus(status(followed, null, 0))
            .build()
            .also { it.apply(this) }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, base.build(), foregroundType())
        job?.cancel()
        job = scope.launch { track(followed, id) }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        val state = current
        if (state != null && !ended) PhoneLink.sendState(this, state.toSync(end = "stopped"))
        // A newer follow from the phone may have replaced this one.
        if (state != null && FollowManager.state.value?.id == state.id) FollowManager.mutableState.value = null
        super.onDestroy()
    }

    private suspend fun track(followed: Followed, id: String) = coroutineScope {
        val repository = Services.repository
        val startedAt = System.currentTimeMillis()
        val info = MutableStateFlow(repository.cachedStopInfo(followed.stopId)?.only(followed.routeId))
        val shapes = runCatching { repository.shapes(listOf(followed.tripId)) }.getOrDefault(emptyMap())
        // Null until the first live snapshot, so a bus the timetable still shows as due isn't taken as arrived.
        val vehicles = MutableStateFlow<Map<String, List<Vehicle>>?>(null)
        val clock = MutableStateFlow(startedAt)

        launch {
            while (true) {
                runCatching { info.value = repository.stopInfo(followed.stopId).only(followed.routeId) }
                delay(STOP_INFO_REFRESH_MS)
            }
        }
        launch { VehicleStream.vehicles(listOf(followed.tripId)).collect { vehicles.value = it } }
        launch {
            delay(LIVE_WAIT_MS)
            if (vehicles.value == null) vehicles.value = emptyMap()
        }
        launch {
            while (true) {
                delay(TICK_MS)
                clock.value = System.currentTimeMillis()
            }
        }

        val progress = Progress()
        combine(info.filterNotNull(), vehicles.filterNotNull(), clock) { stop, buses, nowMs ->
            withContext(Dispatchers.Default) {
                val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), CLUJ_ZONE)
                computeStopDepartures(stop, shapes, buses, now, nowMs)
                    .firstOrNull { it.routeId == followed.routeId && it.tripId == followed.tripId } to nowMs
            }
        }.collect { (departure, nowMs) ->
            if (nowMs - startedAt > GIVE_UP_MS) return@collect end("stopped")
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
        if (first == null) return end("none")
        val minutes = first.minutes
        // A jump after the bus was close means it passed, even if no reading caught it at 0.
        if (progress.buzzedSoon && minutes > (progress.lastMinutes ?: 0) + 2) {
            return end("left")
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
        val state = FollowState(followed, minutes, live, departure.nextTimes.drop(1).map { it.minutes }, id, arrival)
        current = state
        if (FollowManager.state.value?.id != id) return
        FollowManager.mutableState.value = state
        PhoneLink.sendState(this, state.toSync())
        builder?.let { describe(it, followed, minutes, arrival) }
        ongoing?.update(this, status(followed, minutes, arrival))
    }

    private fun end(reason: String) {
        ended = true
        current?.let { PhoneLink.sendState(this, it.toSync(end = reason)) }
        if (FollowManager.state.value?.id == current?.id) FollowManager.mutableState.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        job?.cancel()
    }

    private fun foregroundType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

    private fun baseNotification(followed: Followed) = NotificationCompat.Builder(this, FOLLOW_CHANNEL)
        .setSmallIcon(R.drawable.ic_bus)
        .setContentTitle("${followed.shortName} → ${followed.destination}")
        .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(openApp(followed))
        .addAction(0, getString(R.string.stop_following), stopIntent())

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

    private fun openApp(followed: Followed?): PendingIntent = PendingIntent.getActivity(
        this, 1,
        Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN_FOLLOWED, followed != null),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this, 2,
        Intent(this, FollowService::class.java).setAction(ACTION_STOP),
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
