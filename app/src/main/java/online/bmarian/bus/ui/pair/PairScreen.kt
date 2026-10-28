package online.bmarian.bus.ui.pair

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.ConfirmationDialogDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SuccessConfirmationDialog
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.curvedText
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import online.bmarian.bus.BuildConfig
import online.bmarian.bus.R
import online.bmarian.bus.Services
import online.bmarian.bus.data.sync.Sync
import online.bmarian.bus.data.sync.SyncApi
import online.bmarian.bus.data.sync.SyncCrypto
import online.bmarian.bus.data.sync.SyncHttpException
import online.bmarian.bus.data.sync.SyncMessages
import online.bmarian.bus.ui.ListScreen
import online.bmarian.bus.ui.Loading
import online.bmarian.bus.ui.Message
import online.bmarian.bus.ui.contentModifier
import online.bmarian.bus.ui.itemModifier
import online.bmarian.bus.ui.squish
import online.bmarian.bus.ui.surface
import online.bmarian.bus.ui.theme.Palette
import java.io.IOException

private const val POLL_MS = 2000L

enum class PairState { Loading, Showing, Connecting, Confirm, Waiting, Done, Expired, Failed, Broken, Both }

/** The watch shows a code; the phone or computer enters it (protocol.md §2, the D side of the phone's SyncPairingViewModel). */
class PairViewModel : ViewModel() {
    val state = MutableStateFlow(PairState.Loading)
    val code = MutableStateFlow("")
    val url = MutableStateFlow("")
    val check = MutableStateFlow("")
    val peer = MutableStateFlow<SyncMessages.Hello?>(null)

    private class Pairing(val slot: SyncApi.Slot, val keys: SyncCrypto.KeyPair, val k: ByteArray) {
        var t: ByteArray? = null
        var read = 0
        var mine: SyncMessages.Hello? = null
        var giving = false
        var confirmed = false
        var peerReady = false
        var sentSecret = false
    }

    private var pairing: Pairing? = null
    private var job: Job? = null

    fun start() {
        job?.cancel()
        release()
        state.value = PairState.Loading
        peer.value = null
        job = viewModelScope.launch {
            try {
                val keys = SyncCrypto.newKeyPair()
                val k = SyncCrypto.random(16)
                val slot = SyncApi.openSlot(SyncCrypto.toB64(SyncCrypto.sha256(keys.spki)))
                val p = Pairing(slot, keys, k)
                pairing = p
                code.value = slot.code
                url.value = "${BuildConfig.SITE_ORIGIN}/settings?pair=${slot.code}#k=${SyncCrypto.toB64(k)}"
                state.value = PairState.Showing
                while (pairing === p && poll(p)) delay(POLL_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                state.value = PairState.Failed
            } catch (_: Exception) {
                fail(PairState.Broken)
            }
        }
    }

    private fun fail(why: PairState) {
        release()
        state.value = why
    }

    /** False once pairing ended. */
    private suspend fun poll(p: Pairing): Boolean {
        val read = try {
            SyncApi.readSlot(p.slot)
        } catch (e: SyncHttpException) {
            if (e.status != 404) throw e
            pairing = null
            state.value = if (p.sentSecret) PairState.Done else if (p.t != null) PairState.Broken else PairState.Expired
            return false
        } catch (_: IOException) {
            return true
        }
        if (p.t == null && read.pub != null) {
            val other = SyncCrypto.fromB64(read.pub, SyncCrypto.SPKI_SIZE)?.takeIf { SyncCrypto.publicKey(it) != null }
                ?: return false.also { fail(PairState.Broken) }
            SyncApi.revealKey(p.slot, SyncCrypto.toB64(p.keys.spki))
            val t = SyncCrypto.pairKey(SyncCrypto.ecdh(p.keys.private, other), if (read.scanned) p.k else null, p.slot.code, other, p.keys.spki)
            p.t = t
            check.value = SyncCrypto.checkNumber(t)
            val sync = Services.sync
            val member = sync.engine.membership()
            val mine = SyncMessages.Hello("wear", label(), member != null, if (member != null) maxOf(sync.devices.value, 1) else 0)
            p.mine = mine
            state.value = PairState.Connecting
            send(p, mine)
        }
        val t = p.t ?: return true
        while (p.read < read.messages.size) {
            val sealed = SyncCrypto.fromB64(read.messages[p.read++])
            val plain = sealed?.let { SyncCrypto.open(t, it, SyncCrypto.pairAAD(p.slot.code, 'E')) }
            val message = plain?.let(SyncCrypto::unpad)?.let { SyncMessages.read(String(it, Charsets.UTF_8)) }
                ?: return false.also { fail(PairState.Broken) }
            handle(p, message)
            if (pairing !== p) return false
        }
        return true
    }

    private fun label() = android.os.Build.MODEL.orEmpty().trim().take(40).ifEmpty { "Wear OS" }

    private suspend fun send(p: Pairing, message: SyncMessages.Message) {
        val plain = SyncCrypto.pad(SyncMessages.write(message).toByteArray(Charsets.UTF_8), SyncCrypto.PAIR_PADDED)
        SyncApi.sendMessage(p.slot, SyncCrypto.toB64(SyncCrypto.seal(p.t!!, plain, SyncCrypto.pairAAD(p.slot.code, 'D'))))
    }

    private suspend fun handle(p: Pairing, message: SyncMessages.Message) {
        when (message) {
            is SyncMessages.Hello -> {
                if (peer.value != null) return fail(PairState.Broken)
                peer.value = message
                if (p.mine!!.syncs && message.syncs) return fail(PairState.Both)
                p.giving = p.mine!!.syncs
                state.value = PairState.Confirm
            }
            SyncMessages.Ready -> {
                if (!p.giving || p.peerReady) return fail(PairState.Broken)
                p.peerReady = true
                give(p)
            }
            is SyncMessages.Secret -> {
                if (p.giving || !p.confirmed) return fail(PairState.Broken)
                Services.sync.engine.joinChain(message.chain, message.s)
                Services.scope.launch { SyncApi.closeSlot(p.slot) }
                pairing = null
                Services.sync.joined()
                state.value = PairState.Done
            }
        }
    }

    private suspend fun give(p: Pairing) {
        if (!p.giving || !p.confirmed || !p.peerReady || p.sentSecret) return
        p.sentSecret = true
        val m = Services.sync.engine.membership() ?: return fail(PairState.Broken)
        send(p, SyncMessages.Secret(m.chain, m.epoch, SyncCrypto.toB64(m.s)))
    }

    fun confirm() {
        val p = pairing ?: return
        if (state.value != PairState.Confirm) return
        p.confirmed = true
        state.value = PairState.Waiting
        viewModelScope.launch {
            try {
                if (p.giving) give(p) else send(p, SyncMessages.Ready)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fail(PairState.Broken)
            }
        }
    }

    private fun release() {
        pairing?.let { p -> Services.scope.launch { SyncApi.closeSlot(p.slot) } }
        pairing = null
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

/** With the phone app on the paired phone, favorites already follow it; otherwise the watch joins sync itself. */
@Composable
fun PairScreen(onDone: () -> Unit) {
    val sync = Services.sync
    val status by sync.status.collectAsStateWithLifecycle()
    var phoneApp by remember { mutableStateOf<Boolean?>(null) }
    var pairing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { phoneApp = sync.phoneHasApp() }
    // Once pairing starts it stays on screen, so the finished pairing can say so.
    val showPairing = pairing || (status != Sync.Status.On && status != Sync.Status.Unknown)
    LaunchedEffect(showPairing) { if (showPairing) pairing = true }
    when {
        phoneApp == null || status == Sync.Status.Unknown -> ListScreen { spec -> item { Loading(spec) } }
        phoneApp == true -> ListScreen { spec -> item { Message(stringResource(R.string.sync_through_phone), spec) } }
        showPairing -> PairingScreen(onDone)
        else -> SyncingScreen()
    }
}

@Composable
private fun SyncingScreen() {
    val scope = rememberCoroutineScope()
    val broken by Services.sync.broken.collectAsStateWithLifecycle()
    ListScreen { spec ->
        item { Message(stringResource(if (broken) R.string.sync_problem else R.string.sync_on), spec) }
        item {
            val press = remember { MutableInteractionSource() }
            FilledTonalButton(
                onClick = { scope.launch { runCatching { Services.sync.leave() } } },
                modifier = itemModifier(spec).squish(press),
                interactionSource = press,
                transformation = surface(spec),
                icon = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null) },
                label = { Text(stringResource(R.string.sync_turn_off)) },
            )
        }
    }
}

@Composable
private fun PairingScreen(onDone: () -> Unit, vm: PairViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val code by vm.code.collectAsStateWithLifecycle()
    val url by vm.url.collectAsStateWithLifecycle()
    val check by vm.check.collectAsStateWithLifecycle()
    val peer by vm.peer.collectAsStateWithLifecycle()
    val removed by Services.sync.removed.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { if (vm.state.value == PairState.Loading) vm.start() }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    fun spaced(text: String) = if (text.length == 6) "${text.take(3)} ${text.drop(3)}" else text

    ListScreen { spec ->
        when (state) {
            PairState.Showing, PairState.Connecting, PairState.Waiting -> {
                if (removed) item { Message(stringResource(R.string.sync_removed), spec) }
                item {
                    Box(contentModifier(spec), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(112.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp)) {
                            QrCode(url, Modifier.size(96.dp))
                        }
                    }
                }
                item {
                    Text(
                        spaced(code),
                        modifier = contentModifier(spec).padding(top = 4.dp),
                        style = MaterialTheme.typography.numeralSmall,
                        textAlign = TextAlign.Center,
                    )
                }
                item { Message(stringResource(if (state == PairState.Showing) R.string.sync_hint else R.string.sync_connecting), spec) }
            }
            PairState.Expired, PairState.Failed, PairState.Broken, PairState.Both -> {
                val message = when (state) {
                    PairState.Expired -> R.string.sync_expired
                    PairState.Failed -> R.string.sync_failed
                    PairState.Both -> R.string.sync_both
                    else -> R.string.sync_broken
                }
                item { Message(stringResource(message), spec) }
                item {
                    val press = remember { MutableInteractionSource() }
                    FilledTonalButton(
                        onClick = vm::start,
                        modifier = itemModifier(spec).squish(press),
                        interactionSource = press,
                        transformation = surface(spec),
                        icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                        label = { Text(stringResource(R.string.sync_new_code)) },
                    )
                }
            }
            else -> item { Loading(spec) }
        }
    }

    AlertDialog(
        visible = state == PairState.Confirm && peer != null,
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.sync_confirm_title, peer?.label.orEmpty())) },
        text = {
            Text(
                spaced(check) + "\n" + stringResource(R.string.sync_confirm_check),
                textAlign = TextAlign.Center,
            )
        },
        confirmButton = { AlertDialogDefaults.ConfirmButton(onClick = vm::confirm) },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = onDone) },
    )

    val doneText = stringResource(R.string.sync_done)
    val doneStyle = ConfirmationDialogDefaults.curvedTextStyle
    LaunchedEffect(state) {
        if (state == PairState.Done) {
            context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
    SuccessConfirmationDialog(
        visible = state == PairState.Done,
        onDismissRequest = onDone,
        curvedText = { curvedText(doneText, style = doneStyle) },
    )
}
