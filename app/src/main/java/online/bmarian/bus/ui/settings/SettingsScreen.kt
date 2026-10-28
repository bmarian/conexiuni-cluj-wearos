package online.bmarian.bus.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import online.bmarian.bus.BuildConfig
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.data.Cities
import online.bmarian.bus.data.MAP_CREDITS
import online.bmarian.bus.data.ColorSync
import online.bmarian.bus.data.sync.Sync
import online.bmarian.bus.ui.ListScreen
import online.bmarian.bus.ui.Title
import online.bmarian.bus.ui.contentModifier
import online.bmarian.bus.ui.itemModifier
import online.bmarian.bus.ui.squish
import online.bmarian.bus.ui.surface

private val ROW_MIN_HEIGHT = 52.dp

@Composable
fun SettingsScreen(onSync: () -> Unit) {
    val cities by Cities.list.collectAsStateWithLifecycle()
    val city by Cities.current.collectAsStateWithLifecycle()
    val colorful by ColorSync.colorful.collectAsStateWithLifecycle()
    val status by Services.sync.status.collectAsStateWithLifecycle()
    val phoneApp by produceState<Boolean?>(null) { value = Services.sync.phoneHasApp() }
    val info = remember(cities, city) { cities.firstOrNull { it.slug == city } }
    val cityName = info?.name ?: city

    ListScreen { spec ->
        item { Title(stringResource(R.string.settings), spec) }
        item { InfoRow(stringResource(R.string.city), cityName, spec) }
        item { InfoRow(stringResource(R.string.colorful_lines), stringResource(if (colorful) R.string.setting_on else R.string.setting_off), spec) }
        item {
            val press = remember { MutableInteractionSource() }
            FilledTonalButton(
                onClick = onSync,
                modifier = itemModifier(spec).squish(press),
                interactionSource = press,
                transformation = surface(spec),
                icon = { Icon(Icons.Rounded.Sync, contentDescription = null) },
                label = { Text(stringResource(R.string.sync_title)) },
                secondaryLabel = {
                    Text(
                        stringResource(
                            when {
                                status == Sync.Status.On -> R.string.sync_value_on
                                phoneApp == true -> R.string.sync_value_phone
                                else -> R.string.sync_value_off
                            },
                        ),
                    )
                },
            )
        }
        item {
            Column(contentModifier(spec).padding(top = 6.dp, start = 16.dp, end = 16.dp)) {
                Text(
                    info?.creditsText ?: MAP_CREDITS,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.InfoRow(label: String, value: String, spec: TransformationSpec) {
    Column(
        contentModifier(spec)
            .heightIn(min = ROW_MIN_HEIGHT)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
