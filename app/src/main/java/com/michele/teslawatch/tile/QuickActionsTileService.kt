package com.michele.teslawatch.tile

import android.content.Context
import java.security.SecureRandom
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.VERTICAL_ALIGN_CENTER
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.michele.teslawatch.BuildConfig
import com.michele.teslawatch.MainActivity
import com.michele.teslawatch.R
import com.michele.teslawatch.TeslaWatchApp
import com.michele.teslawatch.data.HomeConfig
import com.michele.teslawatch.data.TeslaState
import com.michele.teslawatch.data.TileAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val RES_VERSION = "7"

/** The system waits only a few seconds for the tile: past this, the last saved data is shown. */
private const val TILE_FETCH_TIMEOUT_MS = 2_500L

/** How long the outcome of a tile command stays in the caption. */
private const val ACTION_CAPTION_MS = 60_000L

private const val COLOR_ACTIVE = 0xFF0A84FF.toInt()
private const val COLOR_ALERT = 0xFFFF9F0A.toInt()
private const val COLOR_SURFACE = 0xFF3A3A3C.toInt()
private const val COLOR_GRAY = 0xFF8E8E93.toInt()
private const val COLOR_WHITE = 0xFFFFFFFF.toInt()

/**
 * Tile with battery, range, up to two gate buttons and fixed Lock / Unlock buttons.
 *
 * Buttons use LoadAction: the click comes back as a tile request carrying the clicked id, through
 * the tile binding that only the system can use. No exported activity, so no other app on the
 * watch can trigger these commands. Each drawn tile gets new ids (see [TileClicks]), so a click
 * runs once even if a later request repeats the same "last clicked" id.
 */
class QuickActionsTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clicks by lazy { TileClicks(applicationContext) }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> = Futures.immediateFuture(
        ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).build()
    )

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val repo = TeslaWatchApp.instance.repo
        val action = clicks.consume(requestParams.currentState.lastClickableId)
        if (action != null) {
            // Answer at once with "sending": the tile redraws again when the outcome is known
            repo.runTileAction(action)
            return Futures.immediateFuture(buildTile(repo.state.value))
        }
        val future = SettableFuture.create<TileBuilders.Tile>()
        scope.launch {
            withTimeoutOrNull(TILE_FETCH_TIMEOUT_MS) { repo.refresh(maxAgeMs = 30_000) }
            future.set(buildTile(repo.state.value))
        }
        return future
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildTile(state: TeslaState): TileBuilders.Tile {
        val seq = clicks.nextRender()
        val pct = state.batteryPct?.let { "$it%" } ?: "—"
        val range = state.range?.let { "$it ${state.rangeUnit}" } ?: "—"
        val now = System.currentTimeMillis()
        val (caption, captionColor) = when {
            state.lastAction != null && now - state.lastActionAt < ACTION_CAPTION_MS ->
                state.lastAction to (if (state.lastActionOk) COLOR_ACTIVE else COLOR_ALERT)
            state.error != null -> getString(R.string.stale_data) to COLOR_ALERT
            !state.dataValid -> getString(R.string.no_data_short) to COLOR_ALERT
            else -> BuildConfig.CAR_NAME.uppercase() to COLOR_GRAY
        }

        val gateButtons = listOfNotNull(
            HomeConfig.gate1?.let { pillButton(it.name, COLOR_ACTIVE, clicks.id(TileAction.GATE_1, seq)) },
            HomeConfig.gate2?.let { pillButton(it.name, COLOR_ACTIVE, clicks.id(TileAction.GATE_2, seq)) }
        )
        // Lock and Unlock never swap places, so a redraw can never turn a tap on Lock into an unlock.
        // Lock is orange while the car is known to be unlocked.
        val lockColor = if (state.dataValid && !state.doorsLocked) COLOR_ALERT else COLOR_SURFACE
        val lockRow = listOf(
            pillButton(getString(R.string.lock), lockColor, clicks.id(TileAction.LOCK, seq)),
            pillButton(getString(R.string.unlock), COLOR_SURFACE, clicks.id(TileAction.UNLOCK, seq))
        )

        val layout = LayoutElementBuilders.Layout.Builder().setRoot(
            Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                // Info header (tap -> open app)
                .addContent(
                    Box.Builder()
                        .setModifiers(
                            ModifiersBuilders.Modifiers.Builder()
                                .setClickable(clickableOpenApp())
                                .build()
                        )
                        .addContent(
                            Column.Builder()
                                .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                                .addContent(
                                    Text.Builder(this, caption)
                                        .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                                        .setColor(argb(captionColor))
                                        .setMaxLines(1)
                                        .build()
                                )
                                .addContent(
                                    Text.Builder(this, pct)
                                        .setTypography(Typography.TYPOGRAPHY_DISPLAY3)
                                        .setColor(argb(COLOR_WHITE))
                                        .build()
                                )
                                .addContent(
                                    Text.Builder(this, range)
                                        .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                                        .setColor(argb(COLOR_WHITE))
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
                .addContent(Spacer.Builder().setHeight(dp(6f)).build())
                // Gates on top, the two lock buttons below (narrower: the display is round)
                .apply {
                    if (gateButtons.isNotEmpty()) {
                        addContent(buttonRow(if (gateButtons.size > 1) 14f else 44f, gateButtons))
                        addContent(Spacer.Builder().setHeight(dp(4f)).build())
                    }
                }
                .addContent(buttonRow(28f, lockRow))
                .build()
        ).build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RES_VERSION)
            .setFreshnessIntervalMillis(60_000)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(layout)
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun buttonRow(
        horizontalInsetDp: Float,
        buttons: List<LayoutElementBuilders.LayoutElement>
    ): LayoutElementBuilders.LayoutElement {
        val row = Row.Builder()
            .setWidth(DimensionBuilders.expand())
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
        buttons.forEachIndexed { i, button ->
            if (i > 0) row.addContent(Spacer.Builder().setWidth(dp(4f)).build())
            row.addContent(button)
        }
        return Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(horizontalInsetDp))
                            .setEnd(dp(horizontalInsetDp))
                            .build()
                    ).build()
            )
            .addContent(row.build())
            .build()
    }

    private fun pillButton(label: String, bgColor: Int, clickableId: String): LayoutElementBuilders.LayoutElement =
        Box.Builder()
            .setWidth(DimensionBuilders.weight(1f))
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(clickableId)
                            .setOnClick(ActionBuilders.LoadAction.Builder().build())
                            .build()
                    )
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(bgColor))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(dp(14f)).build()
                            ).build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setTop(dp(8f))
                            .setBottom(dp(8f))
                            .setStart(dp(4f))
                            .setEnd(dp(4f))
                            .build()
                    ).build()
            )
            .addContent(
                Text.Builder(this, label)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setColor(argb(COLOR_WHITE))
                    .setMaxLines(1)
                    .build()
            )
            .build()

    private fun clickableOpenApp(): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId("open_app")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setClassName(MainActivity::class.java.name)
                            .setPackageName(packageName)
                            .build()
                    )
                    .build()
            )
            .build()
}

/**
 * Click ids are "<action>:<install epoch>:<render number>". Every drawn tile uses a new render number
 * and every id is executed at most once, so a request that repeats an old "last clicked" id does
 * nothing. The random epoch changes if the app data is cleared, so ids from before are never accepted.
 */
internal class TileClicks(context: Context) {
    private val prefs = context.getSharedPreferences("tile_clicks", Context.MODE_PRIVATE)

    private val epoch: String = synchronized(this) {
        prefs.getString(KEY_EPOCH, null) ?: java.lang.Long.toHexString(SecureRandom().nextLong()).also {
            prefs.edit().putString(KEY_EPOCH, it).commit()
        }
    }

    @Synchronized
    fun nextRender(): Long {
        val next = prefs.getLong(KEY_SEQ, 0L) + 1
        prefs.edit().putLong(KEY_SEQ, next).commit()
        return next
    }

    fun id(action: TileAction, seq: Long): String = "${prefix(action)}$epoch:$seq"

    /** Returns the action of a click that has not been executed yet, and marks it as executed. */
    @Synchronized
    fun consume(clickableId: String?): TileAction? {
        if (clickableId.isNullOrEmpty()) return null
        val action = TileAction.entries.firstOrNull { clickableId.startsWith(prefix(it)) } ?: return null
        val parts = clickableId.removePrefix(prefix(action)).split(':')
        if (parts.size != 2 || parts[0] != epoch) return null
        val seq = parts[1].toLongOrNull() ?: return null
        if (seq > prefs.getLong(KEY_SEQ, 0L)) return null
        val handled = prefs.getString(KEY_HANDLED, "").orEmpty().split(',').filter { it.isNotEmpty() }
        if (clickableId in handled) return null
        prefs.edit().putString(KEY_HANDLED, (handled + clickableId).takeLast(MAX_HANDLED).joinToString(",")).commit()
        return action
    }

    private companion object {
        const val KEY_SEQ = "seq"
        const val KEY_EPOCH = "epoch"
        const val KEY_HANDLED = "handled"
        const val MAX_HANDLED = 64

        fun prefix(action: TileAction) = action.name.lowercase() + ":"
    }
}
