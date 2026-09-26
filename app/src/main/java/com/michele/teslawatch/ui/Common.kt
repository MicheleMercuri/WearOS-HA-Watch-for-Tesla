package com.michele.teslawatch.ui

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.michele.teslawatch.BuildConfig
import com.michele.teslawatch.Haptics
import com.michele.teslawatch.R
import com.michele.teslawatch.TeslaWatchApp
import com.michele.teslawatch.data.TeslaState
import com.michele.teslawatch.ui.theme.TeslaColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** The +/- buttons send the value once, this long after the last tap. */
const val STEPPER_SEND_DELAY_MS = 1_000L

/** How long the chosen value stays on screen if HA does not confirm it. */
const val STEPPER_CONFIRM_TIMEOUT_MS = 20_000L

/** How long the outcome of a command stays visible. */
const val ACTION_BANNER_MS = 60_000L

/** Taps on a two-state button are ignored for this long after its state changes under the finger. */
const val STATE_CHANGE_GUARD_MS = 1_200L

private const val MIN_BUSY_MS = 250L

@Composable
fun ScreenScaffold(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TeslaColors.Black),
        contentAlignment = Alignment.Center
    ) { content() }
}

/**
 * Round button. For two-state buttons pass [guardKey] (the state the label depends on): a tap that
 * lands within [STATE_CHANGE_GUARD_MS] of a change of that state, or of the screen opening, is ignored
 * (with a double tick), so a refresh that flips the label just before the tap cannot turn "lock" into
 * "unlock". Scrolling a button out of view and back does not count as a change.
 */
@Composable
fun ActionButton(
    icon: ImageVector? = null,
    drawableRes: Int? = null,
    label: String,
    tint: Color = TeslaColors.White,
    background: Color = TeslaColors.SurfaceDim,
    size: Int = 56,
    guardKey: Any? = null,
    onClick: suspend () -> Unit
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    // Checked synchronously: two taps in the same frame must not both launch
    val inFlight = remember { AtomicBoolean(false) }
    // Saveable: survives the lazy list recycling the item, so only a real state change re-arms the guard
    var keyChangedAt by rememberSaveable { mutableLongStateOf(0L) }
    var lastKey by rememberSaveable { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current

    if (guardKey != null) {
        LaunchedEffect(guardKey) {
            val key = guardKey.toString()
            if (key != lastKey) {
                keyChangedAt = SystemClock.uptimeMillis()
                lastKey = key
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(if (busy) TeslaColors.Gray else background)
                .clickable(enabled = !busy) {
                    if (guardKey != null && SystemClock.uptimeMillis() - keyChangedAt < STATE_CHANGE_GUARD_MS) {
                        Haptics.ignored(ctx)
                        return@clickable
                    }
                    if (!inFlight.compareAndSet(false, true)) return@clickable
                    busy = true
                    Haptics.tick(ctx)
                    scope.launch {
                        val start = SystemClock.uptimeMillis()
                        // The button stays grey until the command has really finished
                        try {
                            onClick()
                        } finally {
                            val remaining = MIN_BUSY_MS - (SystemClock.uptimeMillis() - start)
                            if (remaining > 0) delay(remaining)
                            busy = false
                            inFlight.set(false)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = label, tint = tint)
            } else if (drawableRes != null) {
                Icon(painter = painterResource(drawableRes), contentDescription = label, tint = tint)
            }
        }
        Spacer(Modifier.size(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = TeslaColors.GrayLight,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun Chip(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(TeslaColors.SurfaceDim)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, color = TeslaColors.GrayLight, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(6.dp))
            Text(value, fontSize = 13.sp, color = TeslaColors.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ScreenHeader(title: String, imageHeight: Int = 54) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TeslaColors.GrayLight,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.size(2.dp))
        Image(
            painter = painterResource(R.drawable.ic_model3),
            contentDescription = BuildConfig.CAR_NAME,
            modifier = Modifier
                .fillMaxWidth()
                .height(imageHeight.dp)
        )
    }
}

/** Outcome of the last command, for a minute: blue if it went well, orange if not. */
@Composable
fun ActionBanner(state: TeslaState) {
    val message = state.lastAction ?: return
    // Hides itself after a minute even if nothing else redraws the screen
    var expired by remember(state.lastActionAt) {
        mutableStateOf(System.currentTimeMillis() - state.lastActionAt > ACTION_BANNER_MS)
    }
    LaunchedEffect(state.lastActionAt) {
        val left = ACTION_BANNER_MS - (System.currentTimeMillis() - state.lastActionAt)
        if (left > 0) delay(left)
        expired = true
    }
    if (expired) return
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (state.lastActionOk) TeslaColors.ActiveDark else TeslaColors.AlertDark)
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(message, fontSize = 10.sp, color = TeslaColors.White)
    }
}

@Composable
fun ErrorBanner(msg: String?) {
    if (!msg.isNullOrBlank()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(TeslaColors.AlertDark)
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(msg, fontSize = 10.sp, color = TeslaColors.White)
        }
    }
}

/**
 * A value changed with +/-. The command leaves once, [STEPPER_SEND_DELAY_MS] after the last tap, in
 * the repository scope: leaving the screen sends a pending value instead of dropping it, and calls
 * for the same entity run one after the other.
 */
class StepperState<T : Any>(private val scope: CoroutineScope, private val send: suspend (T) -> Unit) {
    var pending by mutableStateOf<T?>(null)
    internal var server: T? = null
    private var sent: T? = null

    fun flush() {
        val v = pending ?: return
        // Compare with what the car will end up with: going back to the old value must be sent too
        if (v != (sent ?: server)) {
            sent = v
            scope.launch { send(v) }
        }
    }

    internal fun reset() {
        pending = null
        sent = null
    }
}

@Composable
fun <T : Any> rememberStepper(server: T?, send: suspend (T) -> Unit): StepperState<T> {
    val stepper = remember { StepperState(TeslaWatchApp.instance.repo.scope, send) }
    stepper.server = server
    LaunchedEffect(stepper.pending) {
        if (stepper.pending == null) return@LaunchedEffect
        delay(STEPPER_SEND_DELAY_MS)
        stepper.flush()
        delay(STEPPER_CONFIRM_TIMEOUT_MS)
        stepper.reset()
    }
    LaunchedEffect(server) {
        if (server != null && server == stepper.pending) stepper.reset()
    }
    DisposableEffect(Unit) { onDispose { stepper.flush() } }
    return stepper
}

/**
 * One +/- step that never moves the value the wrong way: at or past a limit the tap does nothing,
 * and a value outside the limits (set elsewhere) can only move back towards them.
 */
fun stepInt(value: Int, delta: Int, min: Int, max: Int): Int? = when {
    min > max -> null
    delta > 0 && value >= max -> null
    delta < 0 && value <= min -> null
    else -> (value + delta).coerceIn(min, max)
}

fun stepDouble(value: Double, delta: Double, min: Double, max: Double): Double? = when {
    min > max -> null
    delta > 0 && value >= max -> null
    delta < 0 && value <= min -> null
    else -> ((value + delta).coerceIn(min, max) * 10).roundToInt() / 10.0
}
