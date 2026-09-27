package com.michele.teslawatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import com.michele.teslawatch.R
import com.michele.teslawatch.TeslaWatchApp
import com.michele.teslawatch.data.TeslaEntities
import com.michele.teslawatch.data.TeslaState
import com.michele.teslawatch.ui.theme.TeslaColors

@Composable
fun TrunkScreen(state: TeslaState) {
    val repo = TeslaWatchApp.instance.repo
    // Start at the very top (car header fully visible): Wear OS would center the second item
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    // Placeholder data says "open": show nothing as open until the integration has real data
    val known = state.dataValid
    val frunkOpen = known && state.frunkOpen
    val trunkOpen = known && state.trunkOpen
    val portOpen = known && state.chargerDoorOpen
    val locked = known && state.doorsLocked

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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { ScreenHeader(stringResource(R.string.header_doors)) }
            item { ActionBanner(state) }
            item { ErrorBanner(state.error) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (TeslaEntities.isSet(TeslaEntities.FRUNK)) {
                        ActionButton(
                            drawableRes = R.drawable.ic_frunk,
                            label = stringResource(R.string.frunk),
                            background = if (frunkOpen) TeslaColors.Alert else TeslaColors.SurfaceDim,
                            onClick = { repo.ifCarDataValid { openFrunk() } }
                        )
                    }
                    ActionButton(
                        drawableRes = R.drawable.ic_trunk,
                        // Label = what the tap does
                        label = if (trunkOpen) stringResource(R.string.close_trunk) else stringResource(R.string.open_trunk),
                        background = if (trunkOpen) TeslaColors.Alert else TeslaColors.SurfaceDim,
                        guardKey = trunkOpen,
                        onClick = { repo.ifCarDataValid { if (trunkOpen) closeTrunk() else openTrunk() } }
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ActionButton(
                        drawableRes = if (!known || locked) R.drawable.ic_lock else R.drawable.ic_unlock,
                        label = if (locked) stringResource(R.string.unlock) else stringResource(R.string.lock),
                        background = if (!known || locked) TeslaColors.SurfaceDim else TeslaColors.Alert,
                        guardKey = known to state.doorsLocked,
                        // Icon = current state, label = what the tap does. Unknown state: lock, the only safe guess
                        onClick = { if (locked) repo.unlockDoors() else repo.lockDoors() }
                    )
                    if (TeslaEntities.isSet(TeslaEntities.CHARGER_DOOR)) ActionButton(
                        drawableRes = R.drawable.ic_charge_port,
                        label = if (portOpen) stringResource(R.string.charge_port_close) else stringResource(R.string.charge_port_open),
                        background = if (portOpen) TeslaColors.Alert else TeslaColors.SurfaceDim,
                        guardKey = portOpen,
                        onClick = { repo.ifCarDataValid { if (portOpen) closeChargePort() else openChargePort() } }
                    )
                }
            }
            if (TeslaEntities.isSet(TeslaEntities.WINDOWS)) item {
                Chip(
                    label = stringResource(R.string.windows),
                    value = when {
                        !known -> "—"
                        state.windowsOpen -> stringResource(R.string.windows_open)
                        else -> stringResource(R.string.windows_closed)
                    }
                )
            }
        }
    }
}
