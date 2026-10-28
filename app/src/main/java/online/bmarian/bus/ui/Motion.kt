package online.bmarian.bus.ui

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings

/** The system's animator duration scale, public only from Android 13. */
fun animatorDurationScale(context: Context): Float =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ValueAnimator.getDurationScale()
    else Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
