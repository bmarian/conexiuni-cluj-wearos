package online.bmarian.bus.ui.follow

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.follow.FollowManager
import online.bmarian.bus.follow.Reminders
import online.bmarian.bus.follow.minutesUntil
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.Mode
import online.bmarian.bus.ui.EmphasizedDecelerate
import online.bmarian.bus.ui.Eta
import online.bmarian.bus.ui.FitText
import online.bmarian.bus.ui.LineBadge
import online.bmarian.bus.ui.clockText
import online.bmarian.bus.follow.FollowState
import online.bmarian.bus.ui.rememberClock
import java.time.LocalDateTime

private const val RING_MS = 785

// A full ring is the minutes a follow was first shown with.
private val firstMinutes = HashMap<String, Int>()

@Composable
fun FollowScreen(stopId: Int, routeId: Int, tripId: String, onEnded: () -> Unit) {
    val context = LocalContext.current
    val follows by FollowManager.follows.collectAsStateWithLifecycle()
    val reminders by Reminders.all.collectAsStateWithLifecycle()
    val nowMs by rememberClock()
    val at = listOf(stopId)
    val follow = follows.firstOrNull { it.matches(at, routeId, tripId) }
    val reminder = if (follow == null) reminders.firstOrNull { it.matches(at, routeId, tripId) } else null

    // A reminder becomes a follow in place.
    var seen by rememberSaveable { mutableStateOf(false) }
    val found = follow != null || reminder != null
    LaunchedEffect(found) {
        if (found) seen = true else if (seen) onEnded()
    }
    val followed = follow?.followed ?: reminder?.followed

    val minutes = follow?.minutesAt(nowMs) ?: reminder?.let { minutesUntil(it.atMs, nowMs) }
    val key = follow?.id ?: reminder?.id
    val start = if (key != null && minutes != null) firstMinutes.getOrPut(key) { minutes.coerceAtLeast(1) } else null
    val share = if (minutes != null && start != null) (minutes.toFloat() / start).coerceIn(0f, 1f) else 1f
    val ring = remember { Animatable(1f) }
    LaunchedEffect(share) { ring.animateTo(share, tween(RING_MS, easing = EmphasizedDecelerate)) }
    val mode = remember(followed?.stopId) { followed?.let { Services.repository.network.index.value?.look(it.stopId)?.mode } } ?: Mode.BUS

    ScreenScaffold {
        Box(Modifier.fillMaxSize()) {
            CircularProgressIndicator(progress = { ring.value }, modifier = Modifier.fillMaxSize())
            if (followed == null) return@Box
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LineBadge(followed.shortName, followed.color, height = 22.dp)
                    FitText(followed.destination, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
                if (minutes == null) Text("…", style = MaterialTheme.typography.numeralMedium)
                else Eta(
                    minutes,
                    follow?.live == true,
                    LocalDateTime.now(CLUJ_ZONE),
                    style = MaterialTheme.typography.numeralMedium,
                    mode = mode,
                    iconSize = 18.dp,
                    idle = MaterialTheme.colorScheme.onSurface,
                )
                val leaveAt = reminder?.leaveAt
                val arrives = stringResource(R.string.arrives_at_stop, followed.stopName)
                val status = when {
                    follow != null -> followStatus(follow, nowMs)
                    leaveAt != null -> stringResource(R.string.leave_at, clockText(leaveAt))
                    else -> arrives
                }
                FitText(status, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                val walk = follow?.walkMinutes?.takeIf { follow.leaveAt != null } ?: reminder?.walk?.takeIf { leaveAt != null }
                val detail = when {
                    walk != null -> stringResource(R.string.walk_to_stop, walk, followed.stopName)
                    status == arrives -> null
                    else -> arrives
                }
                if (detail != null) FitText(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                CompactButton(
                    onClick = {
                        follow?.let { FollowManager.stop(context, it.id) }
                        reminder?.let { Reminders.remove(context, it.id) }
                    },
                    modifier = Modifier.padding(top = 6.dp),
                    icon = { Icon(Icons.Rounded.Close, contentDescription = null, Modifier.size(16.dp)) },
                    label = { Text(stringResource(R.string.stop_following)) },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                )
            }
        }
    }
}

@Composable
fun followStatus(follow: FollowState, nowMs: Long): String {
    val leave = follow.leaveIn(nowMs)
    return when {
        leave == null -> stringResource(R.string.arrives_at_stop, follow.followed.stopName)
        leave <= 0 -> stringResource(R.string.leave_now)
        else -> stringResource(R.string.leave_in, stringResource(R.string.eta_minutes, leave))
    }
}
