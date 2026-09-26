package com.michele.teslawatch.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.michele.teslawatch.MainActivity
import com.michele.teslawatch.R
import com.michele.teslawatch.TeslaWatchApp

class BatteryComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val pct = "80%"
        val desc = PlainComplicationText.Builder(getString(R.string.complication_battery_label)).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(pct).build(),
                contentDescription = desc
            ).build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 80f, min = 0f, max = 100f,
                contentDescription = desc
            ).setText(PlainComplicationText.Builder(pct).build()).build()
            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val app = TeslaWatchApp.instance
        val s = runCatching { app.repo.refresh(maxAgeMs = 60_000) }.getOrNull()
        val pct = s?.batteryPct ?: app.repo.state.value.batteryPct
        val label = pct?.let { "$it%" } ?: "—"
        val desc = PlainComplicationText.Builder(getString(R.string.complication_battery_label)).build()

        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(label).build(),
                contentDescription = desc
            ).setTapAction(tapIntent()).build()

            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = (pct ?: 0).toFloat(),
                min = 0f, max = 100f,
                contentDescription = desc
            )
                .setText(PlainComplicationText.Builder(label).build())
                .setTapAction(tapIntent())
                .build()

            else -> null
        }
    }

    private fun tapIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            component = ComponentName(this@BatteryComplicationService, MainActivity::class.java)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
