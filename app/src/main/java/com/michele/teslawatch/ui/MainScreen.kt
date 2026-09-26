package com.michele.teslawatch.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import com.michele.teslawatch.BuildConfig
import com.michele.teslawatch.R
import com.michele.teslawatch.TeslaWatchApp
import com.michele.teslawatch.data.HomeConfig
import com.michele.teslawatch.data.HomeEntity
import com.michele.teslawatch.data.TeslaEntities
import com.michele.teslawatch.data.TeslaState
import com.michele.teslawatch.ui.theme.TeslaColors

@Composable
fun MainScreen(
    state: TeslaState,
    onClimate: () -> Unit,
    onTrunk: () -> Unit,
    onCharging: () -> Unit,
    onHome: () -> Unit,
    onSettings: () -> Unit
) {
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp, start = 8.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { HeaderCar(state) }
            item { BatteryBar(state) }
            item { ActionBanner(state) }
            item { ErrorBanner(state.error) }
            item { RowLockClimate(state, onClimate) }
            item { RowTrunkCharge(state, onTrunk, onCharging) }
            if (TeslaEntities.isSet(TeslaEntities.CHARGER_DOOR) || TeslaEntities.isSet(TeslaEntities.CHARGE_PORT_LATCH)) {
                item { RowChargePort(state) }
            }
            if (TeslaEntities.isSet(TeslaEntities.BTN_HORN) || TeslaEntities.isSet(TeslaEntities.BTN_FLASH)) {
                item { RowHornFlash() }
            }
            item { RowSentryWake(state) }
            (HomeConfig.gate1 ?: HomeConfig.gate2)?.let { gate -> item { RowGateQuick(gate) } }
            item { FooterNav(onHome, onSettings) }
        }
    }
}

@Composable
private fun HeaderCar(state: TeslaState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = BuildConfig.CAR_NAME.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TeslaColors.GrayLight,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(2.dp))
        Image(
            painter = painterResource(R.drawable.ic_model3),
            contentDescription = BuildConfig.CAR_NAME,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        )
        val status = when {
            state.charging -> stringResource(R.string.status_charging)
            state.asleep -> stringResource(R.string.status_asleep)
            state.online -> stringResource(R.string.status_online)
            else -> stringResource(R.string.status_offline)
        }
        // The lock state is written here; the lock button below is labelled with its action
        val lockState = when {
            !state.dataValid -> null
            state.doorsState == "locked" -> stringResource(R.string.locked)
            state.doorsState == "unlocked" -> stringResource(R.string.unlocked)
            else -> stringResource(R.string.lock_unknown)
        }
        Text(
            text = if (lockState == null) status else "$status · $lockState",
            fontSize = 10.sp,
            color = if (state.online) TeslaColors.Active else TeslaColors.Gray
        )
        if (!state.dataValid) {
            Text(
                text = stringResource(R.string.no_data),
                fontSize = 10.sp,
                color = TeslaColors.Alert
            )
        }
    }
}

@Composable
private fun BatteryBar(state: TeslaState) {
    val pct = state.batteryPct
    val color = when {
        pct == null -> TeslaColors.White
        pct >= 50 -> TeslaColors.Active
        pct >= 20 -> TeslaColors.White
        else -> TeslaColors.Alert
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${state.batteryPct ?: "—"}%",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "${state.range ?: "—"} ${state.rangeUnit}",
                    fontSize = 14.sp,
                    color = TeslaColors.White,
                    fontWeight = FontWeight.Medium
                )
                if (state.chargerPowerKw != null && state.charging) {
                    Text(
                        text = "+${"%.1f".format(state.chargerPowerKw)} kW",
                        fontSize = 10.sp,
                        color = TeslaColors.Active
                    )
                }
            }
        }
    }
}

@Composable
private fun RowLockClimate(state: TeslaState, onClimate: () -> Unit) {
    val repo = TeslaWatchApp.instance.repo
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val locked = state.dataValid && state.doorsLocked
        ActionButton(
            // Icon = current state, label = what the tap does. Unknown state: Lock, the only safe guess
            drawableRes = if (!state.dataValid || state.doorsLocked) R.drawable.ic_lock else R.drawable.ic_unlock,
            label = if (locked) stringResource(R.string.unlock) else stringResource(R.string.lock),
            background = if (!state.dataValid || state.doorsLocked) TeslaColors.SurfaceDim else TeslaColors.Alert,
            guardKey = state.dataValid to state.doorsLocked,
            onClick = { if (locked) repo.unlockDoors() else repo.lockDoors() }
        )
        ActionButton(
            drawableRes = R.drawable.ic_climate,
            label = stringResource(R.string.climate),
            background = if (state.climateOn) TeslaColors.Active else TeslaColors.SurfaceDim,
            onClick = { onClimate() }
        )
    }
}

@Composable
private fun RowTrunkCharge(state: TeslaState, onTrunk: () -> Unit, onCharging: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionButton(
            drawableRes = R.drawable.ic_trunk,
            label = stringResource(R.string.doors),
            onClick = { onTrunk() }
        )
        ActionButton(
            drawableRes = R.drawable.ic_charging,
            label = stringResource(R.string.charge),
            background = if (state.charging) TeslaColors.Active else TeslaColors.SurfaceDim,
            onClick = { onCharging() }
        )
    }
}

@Composable
private fun RowChargePort(state: TeslaState) {
    val repo = TeslaWatchApp.instance.repo
    val portOpen = state.dataValid && state.chargerDoorOpen
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (TeslaEntities.isSet(TeslaEntities.CHARGER_DOOR)) {
            ActionButton(
                drawableRes = R.drawable.ic_charge_port,
                label = if (portOpen) stringResource(R.string.charge_port_close) else stringResource(R.string.charge_port_open),
                background = if (portOpen) TeslaColors.Alert else TeslaColors.SurfaceDim,
                guardKey = portOpen,
                onClick = { repo.ifCarDataValid { if (portOpen) closeChargePort() else openChargePort() } }
            )
        }
        if (TeslaEntities.isSet(TeslaEntities.CHARGE_PORT_LATCH)) {
            ActionButton(
                drawableRes = R.drawable.ic_charge_cable,
                label = stringResource(R.string.unlock_cable),
                onClick = { repo.unlockChargeCable() }
            )
        }
    }
}

@Composable
private fun RowHornFlash() {
    val repo = TeslaWatchApp.instance.repo
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (TeslaEntities.isSet(TeslaEntities.BTN_HORN)) {
            ActionButton(
                drawableRes = R.drawable.ic_horn,
                label = stringResource(R.string.horn),
                onClick = { repo.honk() }
            )
        }
        if (TeslaEntities.isSet(TeslaEntities.BTN_FLASH)) {
            ActionButton(
                drawableRes = R.drawable.ic_flash,
                label = stringResource(R.string.flash),
                onClick = { repo.flashLights() }
            )
        }
    }
}

@Composable
private fun RowSentryWake(state: TeslaState) {
    val repo = TeslaWatchApp.instance.repo
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (TeslaEntities.isSet(TeslaEntities.SENTRY)) {
            // Blue = Sentry is on; the label says what the tap does
            ActionButton(
                drawableRes = R.drawable.ic_sentry,
                label = if (state.sentryOn) stringResource(R.string.sentry_turn_off) else stringResource(R.string.sentry_turn_on),
                background = if (state.sentryOn) TeslaColors.Active else TeslaColors.SurfaceDim,
                guardKey = state.sentryOn,
                onClick = { if (state.sentryOn) repo.sentryOff() else repo.sentryOn() }
            )
        }
        ActionButton(
            drawableRes = R.drawable.ic_wake,
            label = stringResource(R.string.wake),
            onClick = { repo.wakeUpAndUpdate() }
        )
    }
}

@Composable
private fun RowGateQuick(gate: HomeEntity) {
    val repo = TeslaWatchApp.instance.repo
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ActionButton(
            drawableRes = R.drawable.ic_gate,
            label = gate.name,
            background = TeslaColors.Active,
            size = 56,
            onClick = { repo.activateGate(gate) }
        )
    }
}

@Composable
private fun FooterNav(onHome: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The home screen exists only if some smart home entity is configured
        if (!HomeConfig.isEmpty) {
            ActionButton(
                drawableRes = R.drawable.ic_home,
                label = stringResource(R.string.home),
                size = 40,
                onClick = { onHome() }
            )
        }
        ActionButton(
            drawableRes = R.drawable.ic_settings,
            label = stringResource(R.string.settings),
            size = 40,
            onClick = { onSettings() }
        )
    }
}
