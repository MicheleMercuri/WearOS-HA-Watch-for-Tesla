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
import com.michele.teslawatch.data.HomeConfig
import com.michele.teslawatch.data.TeslaState
import com.michele.teslawatch.ui.theme.TeslaColors

/** Smart home buttons from local.properties: gates (momentary) and lights (on/off). */
@Composable
fun HomeScreen(state: TeslaState) {
    val repo = TeslaWatchApp.instance.repo
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp, start = 8.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.header_home),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TeslaColors.GrayLight,
                    letterSpacing = 2.sp
                )
            }
            item { ActionBanner(state) }
            item { ErrorBanner(state.error) }
            if (HomeConfig.gates.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HomeConfig.gates.forEach { gate ->
                            ActionButton(
                                drawableRes = if (gate.slot == 1) R.drawable.ic_gate else R.drawable.ic_gate_ped,
                                label = gate.name,
                                background = if (gate.slot == 1) TeslaColors.Active else TeslaColors.SurfaceDim,
                                onClick = { repo.activateGate(gate) }
                            )
                        }
                    }
                }
            }
            if (HomeConfig.lights.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HomeConfig.lights.forEach { light ->
                            val on = if (light.slot == 1) state.light1On else state.light2On
                            ActionButton(
                                drawableRes = R.drawable.ic_light,
                                label = light.name,
                                background = if (on) TeslaColors.Active else TeslaColors.SurfaceDim,
                                guardKey = on,
                                // Ask for the opposite of what is shown: a stale view repeats, never undoes
                                onClick = { repo.setLight(light, !on) }
                            )
                        }
                    }
                }
            }
        }
    }
}
