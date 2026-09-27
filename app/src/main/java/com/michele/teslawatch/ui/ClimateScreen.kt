package com.michele.teslawatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.michele.teslawatch.R
import com.michele.teslawatch.TeslaWatchApp
import com.michele.teslawatch.data.TeslaEntities
import com.michele.teslawatch.data.TeslaState
import com.michele.teslawatch.ui.theme.TeslaColors
import kotlinx.coroutines.launch

// Preset modes of the Tesla Custom Integration climate entity
private val presets = listOf(
    "normal" to R.string.preset_normal,
    "defrost" to R.string.preset_defrost,
    "keep" to R.string.preset_keep,
    "dog" to R.string.preset_dog,
    "camp" to R.string.preset_camp
)

@Composable
fun ClimateScreen(state: TeslaState) {
    val repo = TeslaWatchApp.instance.repo
    val scope = rememberCoroutineScope()
    // Start at the very top (car header fully visible): Wear OS would center the second item
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)

    // Limits and step come from the climate entity, so they follow HA's unit system (°C or °F)
    val min = state.climateMin ?: 15.0
    val max = state.climateMax ?: 28.0
    val step = state.climateStep?.takeIf { it > 0 } ?: 0.5
    val target = rememberStepper(state.climateTarget) { repo.climateSetTemp(it) }
    val shown = target.pending ?: state.climateTarget
    val windowsOpen = state.dataValid && state.windowsOpen

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            autoCentering = null,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp, start = 8.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { ScreenHeader(stringResource(R.string.header_climate), imageHeight = 60) }
            item { ActionBanner(state) }
            item { ErrorBanner(state.error) }
            item { TempReadout(state) }
            item {
                // Unknown target: no made-up starting value, the buttons do nothing
                TargetControl(
                    current = shown,
                    step = step,
                    onMinus = { shown?.let { v -> stepDouble(v, -step, min, max)?.let { target.pending = it } } },
                    onPlus = { shown?.let { v -> stepDouble(v, step, min, max)?.let { target.pending = it } } }
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Blue = climate is on; the label says what the tap does
                    ActionButton(
                        drawableRes = if (state.climateOn) R.drawable.ic_power_on else R.drawable.ic_power_off,
                        label = if (state.climateOn) stringResource(R.string.turn_off) else stringResource(R.string.turn_on),
                        background = if (state.climateOn) TeslaColors.Active else TeslaColors.SurfaceDim,
                        size = 56,
                        guardKey = state.climateOn,
                        onClick = { if (state.climateOn) repo.climateOff() else repo.climateOn() }
                    )
                    if (TeslaEntities.isSet(TeslaEntities.WINDOWS)) {
                        ActionButton(
                            drawableRes = R.drawable.ic_vent,
                            label = if (windowsOpen) stringResource(R.string.close_windows) else stringResource(R.string.vent_windows),
                            background = if (windowsOpen) TeslaColors.Alert else TeslaColors.SurfaceDim,
                            size = 56,
                            guardKey = windowsOpen,
                            onClick = { repo.ifCarDataValid { if (windowsOpen) closeWindows() else ventWindows() } }
                        )
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.preset),
                    fontSize = 10.sp,
                    color = TeslaColors.GrayLight,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            presets.forEach { (key, label) ->
                item {
                    val active = state.climatePreset == key
                    Chip(
                        label = stringResource(label),
                        value = if (active) "●" else "",
                        onClick = { scope.launch { repo.climateSetPreset(key) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun TempReadout(state: TeslaState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.inside), fontSize = 9.sp, color = TeslaColors.GrayLight)
            Text(
                state.tempInside?.let { "${"%.0f".format(it)}°" } ?: "—",
                fontSize = 18.sp, color = TeslaColors.White, fontWeight = FontWeight.Bold
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.outside), fontSize = 9.sp, color = TeslaColors.GrayLight)
            Text(
                state.tempOutside?.let { "${"%.0f".format(it)}°" } ?: "—",
                fontSize = 18.sp, color = TeslaColors.White, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun TargetControl(current: Double?, step: Double, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionButton(
            drawableRes = R.drawable.ic_minus,
            label = "−${"%.1f".format(step)}",
            size = 44,
            onClick = { onMinus() }
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.target), fontSize = 9.sp, color = TeslaColors.GrayLight)
            Text(
                current?.let { "${"%.1f".format(it)}°" } ?: "—",
                fontSize = 22.sp,
                color = TeslaColors.Alert,
                fontWeight = FontWeight.Bold
            )
        }
        ActionButton(
            drawableRes = R.drawable.ic_plus,
            label = "+${"%.1f".format(step)}",
            size = 44,
            onClick = { onPlus() }
        )
    }
}
