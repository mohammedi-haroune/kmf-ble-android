package com.juncehome.lifepo4ble.ui.components

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.juncehome.lifepo4ble.AppLog

/**
 * Pure decision for "remove animations". Android's accessibility toggle sets
 * Settings.Global.ANIMATOR_DURATION_SCALE to 0; any positive scale keeps animations on.
 */
object MotionPolicy {
    /** Returns true when decorative animations (pulse, wave, blink) should run. */
    fun animationsEnabled(animatorDurationScale: Float?): Boolean {
        if (animatorDurationScale == null || animatorDurationScale.isNaN()) {
            return true
        }
        return animatorDurationScale > 0f
    }

    fun reduceMotion(animatorDurationScale: Float?): Boolean = !animationsEnabled(animatorDurationScale)
}

/** Reads the current global animator duration scale; null when it cannot be read. */
fun readAnimatorDurationScale(context: Context): Float? =
    try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (error: SecurityException) {
        AppLog.w("ReduceMotion: cannot read ANIMATOR_DURATION_SCALE: ${error.message}")
        null
    }

/**
 * True when the user turned animations off. Observes the setting so toggling it while the app
 * is open takes effect without a restart. Always false in @Preview.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    if (LocalInspectionMode.current) {
        return false
    }
    val context = LocalContext.current.applicationContext
    var reduceMotion by remember {
        val scale = readAnimatorDurationScale(context)
        val initial = MotionPolicy.reduceMotion(scale)
        AppLog.d("ReduceMotion: initial animatorDurationScale=$scale reduceMotion=$initial")
        mutableStateOf(initial)
    }
    DisposableEffect(context) {
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val scale = readAnimatorDurationScale(context)
                val next = MotionPolicy.reduceMotion(scale)
                if (next != reduceMotion) {
                    AppLog.d("ReduceMotion: animatorDurationScale=$scale reduceMotion=$next")
                }
                reduceMotion = next
            }
        }
        context.contentResolver.registerContentObserver(uri, false, observer)
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
    return reduceMotion
}

/**
 * Provided by KmfBleTheme. Components read `LocalReduceMotion.current` to decide whether to
 * run decorative animations.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }
