package online.bmarian.bus.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.flow.MutableSharedFlow
import online.bmarian.bus.R
import online.bmarian.bus.follow.FollowState
import online.bmarian.bus.logic.CLUJ_ZONE
import java.time.LocalDateTime

/** Whether the watch is in always-on: dim, black and redrawn about once a minute. */
val LocalAmbient = compositionLocalOf { false }

/** Fires on each ambient update, so screens can move their clocks on. */
object AmbientTicks {
    val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

/** With the wrist down while following a bus: only the countdown, in white on black. */
@Composable
fun AmbientFollow(state: FollowState) {
    val followed = state.followed
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            Text(
                followed.shortName,
                modifier = Modifier
                    .border(1.5.dp, Color.White, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Text(
                "→ ${followed.destination}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val minutes = state.minutes
            if (minutes != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        etaText(minutes, LocalDateTime.now(CLUJ_ZONE)),
                        style = MaterialTheme.typography.numeralMedium,
                        color = Color.White,
                    )
                }
                Text(
                    stringResource(if (state.live) R.string.live else R.string.scheduled),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFBDBDBD),
                )
            }
        }
    }
}
