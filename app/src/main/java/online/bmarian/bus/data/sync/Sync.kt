package online.bmarian.bus.data.sync

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.tasks.await
import online.bmarian.bus.Services
import online.bmarian.bus.follow.PhoneLink
import java.io.IOException

// Collects a burst of edits into one write.
private const val AFTER_EDIT_MS = 2000L

/** When the watch syncs on its own: only while no phone with the app is paired; with one, it follows the phone. */
class Sync(private val context: Context) {
    enum class Status { Unknown, Off, On }

    val engine = SyncEngine(SyncVault(context))
    private val _status = MutableStateFlow(Status.Unknown)
    val status: StateFlow<Status> = _status
    val devices = MutableStateFlow(0)
    val broken = MutableStateFlow(false)
    val removed = MutableStateFlow(false)
    private val running = Mutex()
    private var again = false

    /** A paired phone that has the app, whether or not it is in reach now. */
    suspend fun phoneHasApp(): Boolean = runCatching {
        Wearable.getCapabilityClient(context).getCapability(PhoneLink.CAPABILITY, CapabilityClient.FILTER_ALL).await().nodes.isNotEmpty()
    }.getOrDefault(false)

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        _status.value = if (engine.membership() != null) Status.On else Status.Off
        scope.launch {
            combine(Services.favorites.stops, Services.favorites.routes) { _, _ -> }.drop(1).debounce(AFTER_EDIT_MS).collect { syncNow() }
        }
    }

    suspend fun syncNow() {
        if (_status.value != Status.On) return
        if (!running.tryLock()) {
            again = true
            return
        }
        try {
            do {
                again = false
                val report = engine.syncOnce()
                devices.value = report.devices
                broken.value = report.broken
            } while (again)
        } catch (_: SyncEngine.Gone) {
            _status.value = Status.Off
            removed.value = true
        } catch (_: SyncHttpException) {
            broken.value = true
        } catch (_: IOException) {
            // Offline: the next start or edit tries again.
        } finally {
            running.unlock()
        }
    }

    suspend fun joined() {
        removed.value = false
        broken.value = false
        _status.value = Status.On
        syncNow()
    }

    suspend fun leave() {
        running.lock()
        try {
            engine.leave()
            _status.value = Status.Off
        } finally {
            running.unlock()
        }
    }
}
