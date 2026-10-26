package online.bmarian.bus.presentation.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import online.bmarian.bus.R
import online.bmarian.bus.data.Cities
import online.bmarian.bus.data.Recent
import online.bmarian.bus.data.Recents
import online.bmarian.bus.presentation.LineBadge
import online.bmarian.bus.presentation.ListScreen
import online.bmarian.bus.presentation.SubHeader
import online.bmarian.bus.presentation.cascade
import online.bmarian.bus.presentation.contentModifier
import online.bmarian.bus.presentation.itemModifier
import online.bmarian.bus.presentation.squish
import online.bmarian.bus.presentation.surface

private val FIELD_HEIGHT = 52.dp

@Composable
fun SearchStartScreen(onSearch: (String) -> Unit, onOpenStop: (Int) -> Unit, onOpenRoute: (Int) -> Unit) {
    val context = LocalContext.current
    val city by Cities.current.collectAsStateWithLifecycle()
    LaunchedEffect(city) { Recents.load(context) }
    val recents by Recents.all.collectAsStateWithLifecycle()

    ListScreen { spec ->
        item { SearchField(spec, onSearch) }
        if (recents.isNotEmpty()) item { SubHeader(stringResource(R.string.recent), spec) }
        items(recents.size, key = { "${recents[it].stopId}-${recents[it].routeId}" }) { i ->
            RecentRow(recents[i], i, spec, onOpenStop, onOpenRoute)
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.SearchField(spec: TransformationSpec, onSearch: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var typing by remember { mutableStateOf(false) }
    BackHandler(enabled = typing) {
        keyboard?.hide()
        focusManager.clearFocus()
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    Row(
        contentModifier(spec)
            .heightIn(min = FIELD_HEIGHT)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { typing = it.isFocused },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    if (query.isNotBlank()) {
                        // Wear's full-screen keyboard otherwise stays over the results.
                        keyboard?.hide()
                        focusManager.clearFocus()
                        onSearch(query.trim())
                    }
                },
            ),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(stringResource(R.string.search_label), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    field()
                }
            },
        )
    }
}

@Composable
private fun TransformingLazyColumnItemScope.RecentRow(
    recent: Recent,
    index: Int,
    spec: TransformationSpec,
    onOpenStop: (Int) -> Unit,
    onOpenRoute: (Int) -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    FilledTonalButton(
        onClick = { recent.stopId?.let(onOpenStop) ?: recent.routeId?.let(onOpenRoute) },
        modifier = itemModifier(spec).squish(press).cascade(index, "recent"),
        interactionSource = press,
        transformation = surface(spec),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shortName = recent.shortName
            if (recent.routeId != null && shortName != null) LineBadge(shortName, recent.color.orEmpty(), height = 22.dp)
            else Icon(Icons.Rounded.Place, contentDescription = null, Modifier.size(20.dp))
            Text(recent.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
