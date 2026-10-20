package online.bmarian.bus.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.ConfirmationDialogDefaults
import androidx.wear.compose.material3.SuccessConfirmationDialog
import androidx.wear.compose.material3.curvedText
import kotlinx.coroutines.launch
import online.bmarian.bus.R
import online.bmarian.bus.follow.FollowManager
import online.bmarian.bus.follow.FollowState
import online.bmarian.bus.follow.Followed
import online.bmarian.bus.follow.Reminded
import online.bmarian.bus.follow.Reminder
import online.bmarian.bus.follow.Reminders

/** A bus to be told about: [minutes] away at the stop at [lat]/[lon], or followed right away without them. */
class RemindTarget(val followed: Followed, val lat: Double? = null, val lon: Double? = null, val minutes: Int? = null)

class Reminding(val follow: FollowState?, val reminder: Reminder?) {
    val on get() = follow != null || reminder != null
}

fun reminding(follows: List<FollowState>, reminders: List<Reminder>, stopIds: Collection<Int>, routeId: Int, tripId: String) = Reminding(
    follows.firstOrNull { it.matches(stopIds, routeId, tripId) },
    reminders.firstOrNull { it.matches(stopIds, routeId, tripId) },
)

@Composable
fun rememberRemind(): (Reminding, () -> RemindTarget?) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<(() -> RemindTarget?)?>(null) }
    var done by remember { mutableStateOf<Reminded?>(null) }

    fun go(target: () -> RemindTarget?) {
        val wanted = target() ?: return
        val app = context.applicationContext
        scope.launch {
            done = if (wanted.lat == null || wanted.lon == null || wanted.minutes == null) {
                FollowManager.start(app, wanted.followed)
                Reminded.Following
            } else {
                Reminders.remind(app, wanted.followed, wanted.lat, wanted.lon, wanted.minutes)
            }
        }
    }
    // POST_NOTIFICATIONS needs a runtime grant on Wear OS 4+.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.let(::go)
        pending = null
    }

    val text = stringResource(if (done == Reminded.Reminder) R.string.remind_saved else R.string.following)
    val style = ConfirmationDialogDefaults.curvedTextStyle
    SuccessConfirmationDialog(
        visible = done != null,
        onDismissRequest = { done = null },
        curvedText = { curvedText(text, style = style) },
    )

    return { current, target ->
        when {
            current.on -> {
                current.follow?.let { FollowManager.stop(context, it.id) }
                current.reminder?.let { Reminders.remove(context, it.id) }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                pending = target
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> go(target)
        }
    }
}
