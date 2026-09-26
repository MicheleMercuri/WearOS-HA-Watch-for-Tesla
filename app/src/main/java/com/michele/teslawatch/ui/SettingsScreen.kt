package com.michele.teslawatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.michele.teslawatch.BuildConfig
import com.michele.teslawatch.R
import com.michele.teslawatch.TeslaWatchApp
import com.michele.teslawatch.ui.theme.TeslaColors

/** Read-only view of the configuration, plus a connection test. */
@Composable
fun SettingsScreen() {
    val app = TeslaWatchApp.instance
    val repo = app.repo
    val listState = rememberScalingLazyListState()

    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf(repo.client.lastRoute) }
    var readMode by remember { mutableStateOf(repo.readMode) }

    val notSet = stringResource(R.string.not_set)
    val okFormat = stringResource(R.string.test_ok)
    val koFormat = stringResource(R.string.test_ko)
    val token = app.tokens.get().orEmpty()

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
                    stringResource(R.string.header_settings),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TeslaColors.GrayLight,
                    letterSpacing = 2.sp
                )
            }
            item { Chip(label = stringResource(R.string.setting_url), value = BuildConfig.HA_URL.ifBlank { notSet }) }
            item { Chip(label = stringResource(R.string.setting_lan), value = BuildConfig.HA_LAN_IP.ifBlank { notSet }) }
            item {
                Chip(
                    label = stringResource(R.string.setting_token),
                    value = if (token.isBlank()) stringResource(R.string.token_missing_short) else "••••${token.takeLast(4)}"
                )
            }
            item {
                Chip(
                    label = stringResource(R.string.setting_read),
                    value = when (readMode) {
                        "template" -> stringResource(R.string.read_template)
                        "states" -> stringResource(R.string.read_states)
                        else -> "—"
                    }
                )
            }
            item {
                Chip(
                    label = stringResource(R.string.setting_route),
                    value = when (route) {
                        "lan" -> stringResource(R.string.route_lan)
                        "wan" -> stringResource(R.string.route_wan)
                        else -> "—"
                    }
                )
            }
            item {
                ActionButton(
                    drawableRes = R.drawable.ic_refresh,
                    label = stringResource(R.string.test),
                    size = 52,
                    onClick = {
                        testResult = null
                        val s = repo.refresh(force = true)
                        route = repo.client.lastRoute
                        readMode = repo.readMode
                        testOk = s.error == null
                        testResult = if (testOk) okFormat.format(s.batteryPct?.toString() ?: "—")
                        else koFormat.format(s.error)
                    }
                )
            }
            item {
                testResult?.let {
                    Text(
                        it,
                        fontSize = 11.sp,
                        color = if (testOk) TeslaColors.Active else TeslaColors.Alert
                    )
                }
            }
        }
    }
}
