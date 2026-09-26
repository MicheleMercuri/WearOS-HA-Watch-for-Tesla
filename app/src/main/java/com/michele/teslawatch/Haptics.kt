package com.michele.teslawatch

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Short vibrations: a tap, an ignored tap, a confirmed command, a failed or unconfirmed one.
 * Outcomes often arrive after the wrist is down: Android drops "touch" vibrations from apps that are
 * not in the foreground, so they use the notification and alarm usages, which are allowed.
 */
object Haptics {
    fun tick(ctx: Context) = vibrate(ctx, VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE), usage = null)

    /** Two very short ticks: the tap was ignored because the button had just changed state. */
    fun ignored(ctx: Context) = vibrate(ctx, VibrationEffect.createWaveform(longArrayOf(0, 15, 60, 15), -1), usage = null)

    fun success(ctx: Context) = vibrate(ctx, VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE), usage = Usage.NOTIFICATION)

    fun failure(ctx: Context) = vibrate(ctx, VibrationEffect.createWaveform(longArrayOf(0, 120, 100, 120), -1), usage = Usage.ALARM)

    private enum class Usage { NOTIFICATION, ALARM }

    private fun vibrate(ctx: Context, effect: VibrationEffect, usage: Usage?) {
        try {
            val vibrator = ctx.getSystemService(Vibrator::class.java) ?: return
            when {
                usage == null -> vibrator.vibrate(effect)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> vibrator.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(
                        if (usage == Usage.ALARM) VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_NOTIFICATION
                    )
                )
                else -> @Suppress("DEPRECATION") vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setUsage(if (usage == Usage.ALARM) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
            }
        } catch (_: Exception) {
        }
    }
}
