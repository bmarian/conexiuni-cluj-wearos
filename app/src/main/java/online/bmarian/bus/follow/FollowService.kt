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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import java.time.LocalDateTime

private const val NOTIFICATION_ID = 7
private const val ENDED_NOTIFICATION_ID = 8
private const val FOLLOW_CHANNEL = "follow"
private const val TICK_MS = 15_000L
private const val STOP_INFO_REFRESH_MS = 120_000L
// Buzz this many minutes before the bus, and again when it's due.
private const val SOON_MINUTES = 2
// Nobody waits this long for one bus; a forgotten follow shouldn't drain the battery.
private const val GIVE_UP_MS = 90 * 60_000L

/**
 * Keeps one departure's countdown live while the app is closed: an ongoing activity on the
 * watch face, a buzz before the bus comes, and it ends itself once the bus has left.
 */
class FollowService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var ongoing: OngoingActivity? = null
    // The ongoing activity re-posts this same builder whenever its status changes.
    private var builder: NotificationCompat.Builder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            FollowManager.mutableState.value = null
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
        if (FollowManager.state.value?.followed != followed) FollowManager.mutableState.value = FollowState(followed)

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
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, base.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        job?.cancel()
        job = scope.launch { track(followed) }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        if (FollowManager.state.value != null) FollowManager.mutableState.value = null
        super.onDestroy()
    }

    private suspend fun track(followed: Followed) = coroutineScope {
        val repository = Services.repository
        val startedAt = System.currentTimeMillis()
        val info = MutableStateFlow(repository.cachedStopInfo(followed.stopId)?.only(followed.routeId))
        val shapes = runCatching { repository.shapes(listOf(followed.tripId)) }.getOrDefault(emptyMap())
        val vehicles = MutableStateFlow<Map<String, List<Vehicle>>>(emptyMap())
        val clock = MutableStateFlow(startedAt)

        launch {
            while (true) {
                runCatching { info.value = repository.stopInfo(followed.stopId).only(followed.routeId) }
                delay(STOP_INFO_REFRESH_MS)
            }
        }
        launch { VehicleStream.vehicles(listOf(followed.tripId)).collect { vehicles.value = it } }
        launch {
            while (true) {
                delay(TICK_MS)
                clock.value = System.currentTimeMillis()
            }
        }

        val progress = Progress()
        combine(info.filterNotNull(), vehicles, clock) { stop, buses, nowMs ->
            withContext(Dispatchers.Default) {
                val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), CLUJ_ZONE)
                computeStopDepartures(stop, shapes, buses, now, nowMs)
                    .firstOrNull { it.routeId == followed.routeId && it.tripId == followed.tripId } to nowMs
            }
        }.collect { (departure, nowMs) ->
            if (nowMs - startedAt > GIVE_UP_MS) return@collect end(followed, null)
            update(followed, departure, nowMs, progress)
        }
    }

    // What has already happened to this bus, so each buzz comes once and the end is noticed.
    private class Progress {
        var lastMinutes: Int? = null
        var buzzedSoon = false
        var arrived = false
    }

    @SuppressLint("MissingPermission")
    private fun update(followed: Followed, departure: StopDeparture?, nowMs: Long, progress: Progress) {
        val first = departure?.nextTimes?.firstOrNull()
        if (first == null) return end(followed, getString(R.string.follow_no_bus))
        val minutes = first.minutes
        // Once it has come, the list moves on to the next run: that bus has left.
        if (progress.arrived && minutes > (progress.lastMinutes ?: 0) + 2) {
            return end(followed, getString(R.string.follow_ended, followed.stopName))
        }
        if (minutes <= SOON_MINUTES && !progress.buzzedSoon && minutes > 0) {
            progress.buzzedSoon = true
            buzz(longArrayOf(0, 120, 120, 120))
        }
        if (minutes == 0 && !progress.arrived) {
            progress.arrived = true
            progress.buzzedSoon = true
            buzz(longArrayOf(0, 400, 150, 400, 150, 400))
        }
        progress.lastMinutes = minutes

        val live = !departure.isStatic
        FollowManager.mutableState.value = FollowState(followed, minutes, live, departure.nextTimes.drop(1).map { it.minutes })
        val arrival = nowMs + minutes * 60_000L
        builder?.let { describe(it, followed, minutes, arrival) }
        ongoing?.update(this, status(followed, minutes, arrival))
    }

    @SuppressLint("MissingPermission")
    private fun end(followed: Followed, message: String?) {
        if (message != null) {
            val done = NotificationCompat.Builder(this, FOLLOW_CHANNEL)
                .setSmallIcon(R.drawable.ic_bus)
                .setContentTitle("${followed.shortName} → ${followed.destination}")
                .setContentText(message)
                .setContentIntent(openApp(null))
                .setAutoCancel(true)
                .setTimeoutAfter(5 * 60_000L)
                .build()
            getSystemService(NotificationManager::class.java).notify(ENDED_NOTIFICATION_ID, done)
        }
        FollowManager.mutableState.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        job?.cancel()
    }

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

    private fun buzz(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(FOLLOW_CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(FOLLOW_CHANNEL, getString(R.string.follow_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val EXTRA_FOLLOWED = "followed"
        const val ACTION_STOP = "online.bmarian.bus.STOP_FOLLOWING"
    }
}

private fun StopInfo.only(routeId: Int) = copy(shapesInfo = shapesInfo.filter { it.routeId == routeId })
