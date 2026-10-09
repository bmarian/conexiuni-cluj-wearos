package online.bmarian.bus.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.ConfirmationDialogDefaults
import androidx.wear.compose.material3.FailureConfirmationDialog
import androidx.wear.compose.material3.OpenOnPhoneDialog
import androidx.wear.compose.material3.OpenOnPhoneDialogDefaults
import androidx.wear.compose.material3.confirmationDialogCurvedText
import androidx.wear.compose.material3.openOnPhoneDialogCurvedText
import kotlinx.coroutines.launch
import online.bmarian.bus.R
import online.bmarian.bus.follow.PhoneLink

@Composable
fun rememberOpenOnPhone(path: String): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sent by remember { mutableStateOf<Boolean?>(null) }

    val openText = OpenOnPhoneDialogDefaults.text
    val openStyle = OpenOnPhoneDialogDefaults.curvedTextStyle
    OpenOnPhoneDialog(
        visible = sent == true,
        onDismissRequest = { sent = null },
        curvedText = { openOnPhoneDialogCurvedText(openText, openStyle) },
    ) {
        OpenOnPhoneDialogDefaults.Icon()
    }
    val failedText = stringResource(R.string.phone_unreachable)
    val failedStyle = ConfirmationDialogDefaults.curvedTextStyle
    FailureConfirmationDialog(
        visible = sent == false,
        onDismissRequest = { sent = null },
        curvedText = { confirmationDialogCurvedText(failedText, failedStyle) },
    )
    return remember(path) { { scope.launch { sent = PhoneLink.openOnPhone(context, path) } } }
}
