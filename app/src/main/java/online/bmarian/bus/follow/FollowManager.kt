package online.bmarian.bus.follow

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import online.bmarian.bus.data.Api

/** One departure at one stop: a line in one direction. */
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

data class FollowState(
    val followed: Followed,
    val minutes: Int? = null,
    val live: Boolean = false,
    val later: List<Int> = emptyList(),
)

/** The bus being followed, shared by the service, the app and the ambient screen. */
object FollowManager {
    internal val mutableState = MutableStateFlow<FollowState?>(null)
    val state: StateFlow<FollowState?> = mutableState

    fun isFollowing(stopId: Int, routeId: Int, tripId: String) = state.value?.followed?.let {
        it.stopId == stopId && it.routeId == routeId && it.tripId == tripId
    } == true

    fun start(context: Context, followed: Followed) {
        mutableState.value = FollowState(followed)
        val intent = Intent(context, FollowService::class.java)
            .putExtra(FollowService.EXTRA_FOLLOWED, Api.json.encodeToString(followed))
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        mutableState.value = null
        context.stopService(Intent(context, FollowService::class.java))
    }
}
