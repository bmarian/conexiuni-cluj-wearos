package online.bmarian.bus.presentation.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import online.bmarian.bus.presentation.FitText

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

// Where the round screen is wide enough for the chip's corners.
val MAP_CHIP_TOP = 32.dp

@Composable
fun MapChip(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .widthIn(max = 164.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun ChipText(text: String, modifier: Modifier = Modifier) {
    FitText(text, modifier, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
}
