package online.bmarian.bus.presentation.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** The buttons and chips over a map; a tap on the map hides or shows them. */
@Stable
class MapChrome {
    var shown by mutableStateOf(true)

    fun toggle() {
        shown = !shown
    }
}

@Composable
fun rememberMapChrome() = remember { MapChrome() }

@Composable
fun MapControls(chrome: MapChrome, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(chrome.shown, modifier, enter = fadeIn(), exit = fadeOut()) { content() }
}
