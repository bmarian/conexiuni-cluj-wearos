package online.bmarian.bus.follow

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import online.bmarian.bus.data.Api
import java.util.UUID
import kotlin.math.roundToInt

/** Shared with the phone app. */
@Serializable
data class Followed(
    val stopId: Int,
    val stopName: String,
    val routeId: Int,
    val tripId: String,
    val shortName: String,
    val color: String,
    val destination: String,
)

/** Wire format shared with the phone app. */
@Serializable
data class FollowSync(
    val id: String,
    val followed: Followed,
    val minutes: Int? = null,
    val live: Boolean = false,
    val later: List<Int> = emptyList(),
    // Epoch ms.
    val arrivalAt: Long? = null,
    // "left", "none" or "stopped" once over.
    val end: String? = null,
    // Buzz for this update.
    val alert: Boolean = false,
    // Epoch ms to set off for the stop, while the follow waits for the user to go.
    val leaveAt: Long? = null,
    val walkMinutes: Int? = null,
    // Epoch ms to go by, from the heads-up until it's time to leave.
    val leaveBy: Long? = null,
)

data class FollowState(
    val followed: Followed,
    val minutes: Int? = null,
    val live: Boolean = false,
    val later: List<Int> = emptyList(),
    val id: String = "",
    val arrivalAt: Long? = null,
    val remote: Boolean = false,
    val leaveAt: Long? = null,
    val walkMinutes: Int? = null,
    val leaveBy: Long? = null,
) {
    fun toSync(end: String? = null) =
        FollowSync(id, followed, minutes, live, later, arrivalAt, end, leaveAt = leaveAt, walkMinutes = walkMinutes, leaveBy = leaveBy)

    fun matches(stopId: Int, routeId: Int, tripId: String) =
        followed.stopId == stopId && followed.routeId == routeId && followed.tripId == tripId

    /** Minutes until it's time to set off, rounded like the phone's. */
    fun leaveIn(nowMs: Long = System.currentTimeMillis()): Int? = leaveAt?.let { ((it - nowMs) / 60_000.0).roundToInt().coerceAtLeast(0) }
}

private const val FIRST_SLOT = 100

/** The bus due first, for places with room for one. */
fun List<FollowState>.soonest() = minByOrNull { it.leaveAt?.let { at -> ((at - System.currentTimeMillis()) / 60_000).toInt() } ?: it.minutes ?: Int.MAX_VALUE }

object FollowManager {
    private val slots = mutableMapOf<String, Int>()

    /** Each follow's card gets its own notification id. */
    @Synchronized
    internal fun slot(id: String): Int = slots.getOrPut(id) { FIRST_SLOT + slots.size }

    internal val mutableFollows = MutableStateFlow<List<FollowState>>(emptyList())
    val follows: StateFlow<List<FollowState>> = mutableFollows

    fun find(stopId: Int, routeId: Int, tripId: String) = follows.value.firstOrNull { it.matches(stopId, routeId, tripId) }

    fun start(context: Context, followed: Followed) {
        if (find(followed.stopId, followed.routeId, followed.tripId) != null) return
        val id = UUID.randomUUID().toString()
        mutableFollows.update { it + FollowState(followed, id = id) }
        val intent = Intent(context, FollowService::class.java)
            .putExtra(FollowService.EXTRA_FOLLOWED, Api.json.encodeToString(followed))
            .putExtra(FollowService.EXTRA_ID, id)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context, id: String) {
        val current = follows.value.firstOrNull { it.id == id } ?: return
        mutableFollows.update { list -> list.filterNot { it.id == id } }
        // A local one: the service drops it when it leaves the list.
        if (current.remote) {
            Mirror.clear(context, id)
            PhoneLink.sendStop(context, id)
        }
    }

    internal fun put(state: FollowState) {
        mutableFollows.update { list -> list.map { if (it.id == state.id) state else it } }
    }

    internal fun ended(id: String) {
        mutableFollows.update { list -> list.filterNot { it.id == id } }
    }

    internal fun onRemoteState(context: Context, sync: FollowSync) {
        if (sync.end != null) {
            if (follows.value.any { it.id == sync.id && it.remote }) {
                ended(sync.id)
                Mirror.clear(context, sync.id)
            }
            return
        }
        if (follows.value.any { it.id == sync.id && !it.remote }) return
        val state = FollowState(
            sync.followed, sync.minutes, sync.live, sync.later, sync.id, sync.arrivalAt,
            remote = true, leaveAt = sync.leaveAt, walkMinutes = sync.walkMinutes, leaveBy = sync.leaveBy,
        )
        mutableFollows.update { list -> if (list.any { it.id == sync.id }) list.map { if (it.id == sync.id) state else it } else list + state }
        Mirror.show(context, state, sync.alert)
    }

    internal fun onRemoteStop(context: Context, id: String) {
        if (follows.value.any { it.id == id && !it.remote }) stop(context, id)
    }
}
