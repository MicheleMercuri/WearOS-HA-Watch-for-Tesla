package com.michele.teslawatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import com.michele.teslawatch.data.TeslaState
import com.michele.teslawatch.ui.theme.TeslaColors

private const val LIMIT_STEP = 5
private const val AMPS_STEP = 1

@Composable
fun ChargingScreen(state: TeslaState) {
    val repo = TeslaWatchApp.instance.repo
    val listState = rememberScalingLazyListState()

    // Values chosen with +/- leave as one call with the final value; limits come from the number entities
    val limit = rememberStepper(state.chargeLimit) { repo.setChargeLimit(it) }
    val amps = rememberStepper(state.chargingAmps) { repo.setChargingAmps(it) }
    val limitShown = limit.pending ?: state.chargeLimit
    val ampsShown = amps.pending ?: state.chargingAmps
    val limitMin = state.chargeLimitMin ?: 50
    val limitMax = state.chargeLimitMax ?: 100
    // The Tesla Custom Integration declares 0 A as minimum: never go below 5 A from the watch
    val ampsMin = maxOf(state.chargingAmpsMin ?: 5, 5)
    val ampsMax = maxOf(state.chargingAmpsMax ?: 32, ampsMin)

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp, start = 8.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { ScreenHeader(stringResource(R.string.header_charging)) }
            item { ActionBanner(state) }
            item { ErrorBanner(state.error) }
            item { StatusRow(state) }
            item {
                Chip(
                    label = stringResource(R.string.battery),
                    value = "${state.batteryPct ?: "—"}% · ${state.range ?: "—"} ${state.rangeUnit}"
                )
            }
            item {
                ActionButton(
                    drawableRes = if (state.charging) R.drawable.ic_power_off else R.drawable.ic_power_on,
                    label = if (state.charging) stringResource(R.string.stop) else stringResource(R.string.start),
                    background = if (state.charging) TeslaColors.Alert else TeslaColors.Active,
                    size = 64,
                    guardKey = state.charging,
                    onClick = { if (state.charging) repo.chargeStop() else repo.chargeStart() }
                )
            }
            item {
                // Unknown value: show "—" and ignore the taps instead of starting from a made-up number
                StepperRow(
                    label = stringResource(R.string.limit),
                    value = limitShown?.let { "$it %" } ?: "—",
                    onMinus = { limitShown?.let { v -> stepInt(v, -LIMIT_STEP, limitMin, limitMax)?.let { limit.pending = it } } },
                    onPlus = { limitShown?.let { v -> stepInt(v, LIMIT_STEP, limitMin, limitMax)?.let { limit.pending = it } } }
                )
            }
            item {
                StepperRow(
                    label = stringResource(R.string.amps),
                    value = ampsShown?.let { "$it A" } ?: "—",
                    onMinus = { ampsShown?.let { v -> stepInt(v, -AMPS_STEP, ampsMin, ampsMax)?.let { amps.pending = it } } },
                    onPlus = { ampsShown?.let { v -> stepInt(v, AMPS_STEP, ampsMin, ampsMax)?.let { amps.pending = it } } }
                )
            }
        }
    }
}

@Composable
private fun StatusRow(state: TeslaState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.power), fontSize = 9.sp, color = TeslaColors.GrayLight)
            Text(
                state.chargerPowerKw?.let { "${"%.1f".format(it)} kW" } ?: "—",
                fontSize = 14.sp, color = TeslaColors.White, fontWeight = FontWeight.Bold
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.state), fontSize = 9.sp, color = TeslaColors.GrayLight)
            Text(
                if (state.charging) stringResource(R.string.charging_active) else stringResource(R.string.charging_stopped),
                fontSize = 14.sp,
                color = if (state.charging) TeslaColors.Active else TeslaColors.Gray,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionButton(drawableRes = R.drawable.ic_minus, label = "−", size = 40, onClick = { onMinus() })
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 9.sp, color = TeslaColors.GrayLight)
            Text(value, fontSize = 16.sp, color = TeslaColors.White, fontWeight = FontWeight.Bold)
        }
        ActionButton(drawableRes = R.drawable.ic_plus, label = "+", size = 40, onClick = { onPlus() })
    }
}
