package online.bmarian.bus.presentation.setup

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import online.bmarian.bus.R
import online.bmarian.bus.data.LanguageSync
import online.bmarian.bus.data.UserLocation
import online.bmarian.bus.follow.JourneyMirror
import online.bmarian.bus.follow.Mirror
import online.bmarian.bus.presentation.ListScreen
import online.bmarian.bus.presentation.Message
import online.bmarian.bus.presentation.Title
import online.bmarian.bus.presentation.contentModifier
import online.bmarian.bus.presentation.itemModifier
import online.bmarian.bus.presentation.surface

enum class SetupStep { Language, Notifications, Location }

object Setup {
    private fun prefs(context: Context) = context.getSharedPreferences("setup", Context.MODE_PRIVATE)

    fun notificationsAllowed(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun firstRun(context: Context): List<SetupStep> =
        if (prefs(context).getBoolean("done", false)) emptyList()
        else buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(SetupStep.Language)
            if (!notificationsAllowed(context)) add(SetupStep.Notifications)
            if (!UserLocation.permitted(context)) add(SetupStep.Location)
        }

    fun finish(context: Context) = prefs(context).edit { putBoolean("done", true) }

    internal fun markAsked(context: Context, step: SetupStep) = prefs(context).edit { putBoolean("asked_${step.name}", true) }

    internal fun blocked(activity: Activity, step: SetupStep): Boolean {
        val permission = when (step) {
            SetupStep.Notifications ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else return false
            SetupStep.Location -> Manifest.permission.ACCESS_FINE_LOCATION
            SetupStep.Language -> return false
        }
        return prefs(activity).getBoolean("asked_${step.name}", false) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }
}

private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

@Composable
fun SetupScreen(steps: List<SetupStep>, onDone: () -> Unit) {
    val context = LocalContext.current
    var index by rememberSaveable(steps) { mutableIntStateOf(0) }
    fun next() {
        if (index < steps.lastIndex) index++ else onDone()
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Mirror.reshow(context)
            JourneyMirror.reshow(context)
        }
        next()
    }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { next() }
    val step = steps.getOrNull(index) ?: return
    val activity = remember(context) { context.findActivity() }
    val blocked = activity != null && Setup.blocked(activity, step)

    fun allow() {
        Setup.markAsked(context, step)
        when {
            blocked -> {
                openSettings(context, step)
                next()
            }
            step == SetupStep.Notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            step == SetupStep.Notifications -> next()
            else -> location.launch(LOCATION_PERMISSIONS)
        }
    }
    BackHandler(onBack = ::next)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (step == SetupStep.Language) {
            LanguageStep { tag ->
                LanguageSync.choose(context, tag)
                next()
            }
        } else key(step) {
            val (icon, title, body) = when (step) {
                SetupStep.Notifications -> Triple(Icons.Rounded.NotificationsActive, R.string.setup_notifications_title, R.string.setup_notifications_body)
                else -> Triple(Icons.Rounded.MyLocation, R.string.setup_location_title, R.string.setup_location_body)
            }
            ListScreen { spec ->
                item {
                    Box(contentModifier(spec).padding(top = 4.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(icon, contentDescription = null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                item { Title(stringResource(title), spec) }
                item { Message(stringResource(body), spec) }
                item {
                    Button(
                        onClick = ::allow,
                        modifier = itemModifier(spec),
                        transformation = surface(spec),
                        label = {
                            Text(
                                stringResource(if (blocked) R.string.setup_open_settings else R.string.setup_allow),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                        },
                    )
                }
                item {
                    Box(contentModifier(spec), contentAlignment = Alignment.Center) {
                        CompactButton(onClick = ::next, colors = ButtonDefaults.childButtonColors(), label = { Text(stringResource(R.string.setup_not_now)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageStep(onPick: (String) -> Unit) {
    ListScreen { spec ->
        item {
            Box(contentModifier(spec).padding(top = 4.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Translate, contentDescription = null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item { Title(stringResource(R.string.setup_language_title), spec) }
        for ((tag, name) in listOf("ro" to "Română", "en" to "English")) {
            item {
                Button(
                    onClick = { onPick(tag) },
                    modifier = itemModifier(spec),
                    transformation = surface(spec),
                    label = { Text(name, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                )
            }
        }
    }
}

private fun openSettings(context: Context, step: SetupStep) {
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
    val intent = if (step == SetupStep.Notifications) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else details
    runCatching { context.startActivity(intent) }.onFailure { runCatching { context.startActivity(details) } }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
