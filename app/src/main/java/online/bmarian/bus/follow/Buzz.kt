package online.bmarian.bus.follow

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Wear OS doesn't alert for ongoing notifications, so follow alerts vibrate directly. */
internal object Buzz {
    val SOON = longArrayOf(0, 120, 120, 120)
    val NOW = longArrayOf(0, 400, 150, 400, 150, 400)

    fun play(context: Context, pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}
