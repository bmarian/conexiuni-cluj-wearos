package com.example.conexiuniclujwearos.presentation.pair

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import com.example.conexiuniclujwearos.R
import com.example.conexiuniclujwearos.Services
import com.example.conexiuniclujwearos.data.Api
import com.example.conexiuniclujwearos.data.Transfer
import com.example.conexiuniclujwearos.data.TransferLink
import com.example.conexiuniclujwearos.presentation.ActionRow
import com.example.conexiuniclujwearos.presentation.FloatButton
import com.example.conexiuniclujwearos.presentation.Heading
import com.example.conexiuniclujwearos.presentation.Icons
import com.example.conexiuniclujwearos.presentation.ListScreen
import com.example.conexiuniclujwearos.presentation.Note
import com.example.conexiuniclujwearos.presentation.theme.Palette
import com.example.conexiuniclujwearos.presentation.transformed
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val POLL_MS = 2000L
private const val DONE_PAUSE_MS = 1500L

enum class PairState { Loading, Waiting, Review, Done, Expired, Failed, Broken }

// Shows a code, waits for another device to send its export to it, and holds what arrived
// until it is confirmed: anyone who guesses a waiting code can send too.
class PairViewModel : ViewModel() {
    val state = MutableStateFlow(PairState.Loading)
    val link = MutableStateFlow<TransferLink?>(null)
    val summary = MutableStateFlow<Transfer.Summary?>(null)
    var key = ""
        private set
    private var received: JsonElement? = null
    private var job: Job? = null

    init {
        start()
    }

    fun start() {
        job?.cancel()
        release()
        state.value = PairState.Loading
        job = viewModelScope.launch {
            val created = try {
                Api.createTransfer()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.value = PairState.Failed
                return@launch
            }
            key = Transfer.newKey()
            link.value = created
            state.value = PairState.Waiting
            poll(created, System.currentTimeMillis() + created.expiresIn * 1000L)
        }
    }

    private suspend fun poll(current: TransferLink, expiresAt: Long) {
        while (state.value == PairState.Waiting) {
            delay(POLL_MS)
            if (System.currentTimeMillis() > expiresAt) {
                state.value = PairState.Expired
                return
            }
            // Offline for a moment: the next poll tries again.
            val result = runCatching { Api.readTransfer(current) }.getOrNull() ?: continue
            when (result) {
                Api.TransferRead.Waiting -> {}
                Api.TransferRead.Gone -> state.value = PairState.Expired
                is Api.TransferRead.Received -> receive(current, result.data)
            }
        }
    }

    private fun receive(from: TransferLink, payload: JsonElement) {
        Services.scope.launch { Api.deleteTransfer(from) }
        link.value = null
        received = runCatching { Transfer.open(payload, key) }.getOrNull()
        summary.value = received?.let(Transfer::describe)
        state.value = if (summary.value != null) PairState.Review else PairState.Broken
    }

    fun accept(context: Context) {
        val data = received as? JsonObject
        if (state.value != PairState.Review || data == null) return
        Services.favorites.import(data["favorites"] as? JsonObject ?: JsonObject(emptyMap()))
        state.value = PairState.Done
        context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        Transfer.localeOf(data)?.let { Services.setLanguage(context, it) }
    }

    private fun release() {
        link.value?.let { Services.scope.launch { Api.deleteTransfer(it) } }
        link.value = null
        summary.value = null
        received = null
    }

    override fun onCleared() {
        job?.cancel()
        release()
    }
}

@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    }
    Canvas(modifier) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Palette.Deep, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

@Composable
fun PairScreen(onDone: () -> Unit, vm: PairViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()

    // The code has to stay up while the other device scans or types it.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(state) {
        if (state == PairState.Done) {
            delay(DONE_PAUSE_MS)
            onDone()
        }
    }

    ListScreen { spec ->
        when (state) {
            PairState.Waiting -> link?.let { current ->
                item {
                    Box(
                        modifier = transformed(spec).padding(top = 4.dp).size(112.dp).clip(RoundedCornerShape(10.dp))
                            .background(Color.White).padding(8.dp),
                    ) {
                        QrCode(Transfer.url(current.code, vm.key), Modifier.size(96.dp))
                    }
                }
                item {
                    Text(
                        Transfer.spaced(current.code),
                        modifier = transformed(spec).fillMaxWidth().padding(top = 6.dp),
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                    )
                }
                item { Note(stringResource(R.string.transfer_hint), transformed(spec)) }
            }
            PairState.Review -> summary?.let { counts ->
                item { Heading(stringResource(R.string.pair_review), transformed(spec)) }
                item {
                    Column(transformed(spec).fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.count_line, stringResource(R.string.favorite_stops), counts.stops), color = Palette.Action, fontSize = 14.sp)
                        Text(stringResource(R.string.count_line, stringResource(R.string.favorite_routes), counts.lines), color = Palette.Action, fontSize = 14.sp)
                    }
                }
                item {
                    Box(transformed(spec).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        FloatButton(stringResource(R.string.pair_copy), onClick = { vm.accept(context) })
                    }
                }
                item { ActionRow(stringResource(R.string.cancel), onClick = onDone, modifier = transformed(spec)) }
            }
            PairState.Done -> {
                item {
                    Box(transformed(spec).fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(56.dp).clip(CircleShape).background(Palette.AccentWash), contentAlignment = Alignment.Center) {
                            Icon(Icons.Check, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(28.dp))
                        }
                    }
                }
                item { Heading(stringResource(R.string.pair_done), transformed(spec)) }
            }
            PairState.Expired, PairState.Failed, PairState.Broken -> {
                val message = when (state) {
                    PairState.Expired -> R.string.transfer_expired
                    PairState.Failed -> R.string.transfer_failed
                    else -> R.string.transfer_broken
                }
                item { Note(stringResource(message), transformed(spec)) }
                item { ActionRow(stringResource(R.string.transfer_new_code), onClick = vm::start, modifier = transformed(spec)) }
            }
            PairState.Loading -> item { Note(stringResource(R.string.transfer_loading), transformed(spec)) }
        }
    }
}
