package com.michele.teslawatch.data

import android.content.Context
import com.michele.teslawatch.BuildConfig
import com.michele.teslawatch.Haptics
import com.michele.teslawatch.R
import com.michele.teslawatch.config.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap

/** Tile buttons: each one always sends the same command. */
enum class TileAction { LOCK, UNLOCK, GATE_1, GATE_2 }

class TeslaRepository(context: Context, private val tokens: TokenStore) {

    private val appContext = context.applicationContext

    val client = HAClient(
        context = appContext,
        baseUrl = BuildConfig.HA_URL,
        tokenProvider = { tokens.get() },
        lanIp = BuildConfig.HA_LAN_IP
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = appContext.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE)
    private val modePrefs = appContext.getSharedPreferences(MODE_FILE, Context.MODE_PRIVATE)

    /** Application scope: commands keep running if the screen that started them goes away. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val refreshLock = Mutex()
    @Volatile private var followUpJob: Job? = null
    private val verifyJobs = ConcurrentHashMap<String, Job>()
    private val entityLocks = ConcurrentHashMap<String, Mutex>()

    /** Entities with a command that was sent but not answered: HA may still be running it. */
    private val pendingUntil = ConcurrentHashMap<String, Long>()

    private val gateGuard = Any()
    private val gateCooldownUntil = HashMap<Int, Long>()
    private val gateInFlight = HashSet<Int>()

    /**
     * Fingerprint of a token that Home Assistant refused (401/403). Automatic refreshes stop until the
     * token changes or the user asks for a test: every refused call counts as a failed login in HA,
     * which can end in an IP ban.
     */
    @Volatile private var refusedToken: String? = null

    init {
        // A refusal survives process restarts: otherwise every restart would send the refused token again
        refusedToken = modePrefs.getString(KEY_REFUSED, null)
    }

    private fun setRefused(fingerprint: String?) {
        refusedToken = fingerprint
        modePrefs.edit().apply { if (fingerprint == null) remove(KEY_REFUSED) else putString(KEY_REFUSED, fingerprint) }.apply()
    }

    /** True if Home Assistant refused the token now stored: no call is made with it until it changes or a test is forced. */
    private fun tokenRefused(): Boolean = tokens.fingerprint()?.let { it == refusedToken } == true

    /** How the last refresh read the states: "template", "states" or empty. */
    @Volatile var readMode: String = ""
        private set

    /** Called when the outcome of a command is known, so the tile can redraw. */
    @Volatile var onActionResult: (() -> Unit)? = null

    // Starts from the last saved state, so tile and app do not show "—" after Android kills the process
    private val _state = MutableStateFlow(loadCache())
    val state: StateFlow<TeslaState> = _state.asStateFlow()

    /**
     * Refreshes the state; skips the call to HA if the last successful refresh is younger than [maxAgeMs].
     * After a 401/403 only a [force]d refresh (Settings > Test, new token) contacts HA again.
     */
    suspend fun refresh(maxAgeMs: Long = 0L, force: Boolean = false): TeslaState = refreshLock.withLock {
        val current = _state.value
        val fingerprint = tokens.fingerprint()
        if (!force && fingerprint != null && fingerprint == refusedToken) return@withLock current
        if (maxAgeMs > 0 && current.error == null &&
            System.currentTimeMillis() - current.lastSuccess < maxAgeMs
        ) {
            return@withLock current
        }
        try {
            val snapshot = if (useTemplate(fingerprint)) {
                try {
                    readWithTemplate().also { readMode = "template" }
                } catch (e: HttpStatusException) {
                    if (e.code != 401) throw e
                    // The template API is admin-only: remember it for this token, then read entity by entity
                    if (fingerprint != null) modePrefs.edit().putString(KEY_STATES_FOR, fingerprint).apply()
                    readPerEntity().also { readMode = "states" }
                }
            } else {
                readPerEntity().also { readMode = "states" }
            }
            if (refusedToken != null) setRefused(null)
            // Merge into the latest state, so a command outcome written meanwhile is not lost
            val fresh = _state.updateAndGet { parse(snapshot, it) }
            saveCache(fresh)
            fresh
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is HttpStatusException && (e.code == 401 || e.code == 403)) setRefused(fingerprint)
            _state.updateAndGet { it.copy(error = describe(e), lastUpdate = System.currentTimeMillis()) }
        }
    }

    fun onTokenChanged() {
        setRefused(null)
        scope.launch {
            refresh(force = true)
            onActionResult?.invoke()
        }
    }

    private fun useTemplate(fingerprint: String?): Boolean =
        BuildConfig.HA_READ_MODE != "states" &&
            (fingerprint == null || modePrefs.getString(KEY_STATES_FOR, null) != fingerprint)

    // --- Reading ---

    /** Entity states (null = the entity does not exist) plus the few attributes the app needs. */
    private class Snapshot(val states: Map<String, String?>, val attrs: Map<String, JsonElement?>)

    private suspend fun readWithTemplate(): Snapshot {
        val root = json.parseToJsonElement(client.renderTemplate(REFRESH_TEMPLATE)).jsonObject
        val states = root["s"]?.jsonObject.orEmpty().mapValues { (it.value as? JsonPrimitive)?.contentOrNull }
        return Snapshot(states, root["a"]?.jsonObject.orEmpty())
    }

    /** GET /api/states/<id> for every entity: works with a non-admin token. */
    private suspend fun readPerEntity(): Snapshot = coroutineScope {
        val ids = TeslaEntities.REFRESH
        // One call first: a refused token fails here once, not in a burst of parallel failed logins
        val first = ids.first() to client.getState(ids.first())
        val rest = ids.drop(1).map { id -> async { id to client.getState(id) } }.awaitAll()
        val objects = (listOf(first) + rest).toMap()
        fun attr(id: String, name: String): JsonElement? =
            if (!TeslaEntities.isSet(id)) null else objects[id]?.get("attributes")?.let { it as? JsonObject }?.get(name)
        Snapshot(
            states = objects.mapValues { (it.value?.get("state") as? JsonPrimitive)?.contentOrNull },
            attrs = mapOf(
                "t" to attr(TeslaEntities.CLIMATE, "temperature"),
                "p" to attr(TeslaEntities.CLIMATE, "preset_mode"),
                "cmin" to attr(TeslaEntities.CLIMATE, "min_temp"),
                "cmax" to attr(TeslaEntities.CLIMATE, "max_temp"),
                "cstep" to attr(TeslaEntities.CLIMATE, "target_temp_step"),
                "lmin" to attr(TeslaEntities.CHARGE_LIMIT, "min"),
                "lmax" to attr(TeslaEntities.CHARGE_LIMIT, "max"),
                "amin" to attr(TeslaEntities.CHARGING_AMPS, "min"),
                "amax" to attr(TeslaEntities.CHARGING_AMPS, "max"),
                "ru" to attr(TeslaEntities.RANGE, "unit_of_measurement")
            )
        )
    }

    private fun parse(snap: Snapshot, previous: TeslaState): TeslaState {
        val states = snap.states
        fun st(id: String): String? = if (TeslaEntities.isSet(id)) states[id] else null
        fun num(id: String): Double? = st(id)?.toDoubleOrNull()
        fun attrNum(key: String): Double? = (snap.attrs[key] as? JsonPrimitive)?.doubleOrNull
        fun attrText(key: String): String? = (snap.attrs[key] as? JsonPrimitive)?.contentOrNull
        fun available(gate: HomeEntity?): Boolean? = gate?.let { g -> st(g.entityId).let { it != null && it != "unavailable" } }

        val missing = TeslaEntities.REFRESH.filter { states.containsKey(it) && states[it] == null }
        val climate = st(TeslaEntities.CLIMATE)
        val now = System.currentTimeMillis()
        return previous.copy(
            batteryPct = num(TeslaEntities.BATTERY)?.toInt(),
            range = num(TeslaEntities.RANGE)?.toInt(),
            rangeUnit = attrText("ru") ?: "km",
            tempInside = num(TeslaEntities.TEMP_INSIDE),
            tempOutside = num(TeslaEntities.TEMP_OUTSIDE),
            climateOn = climate != null && climate != "off" && climate != "unknown" && climate != "unavailable",
            climateTarget = attrNum("t"),
            climatePreset = attrText("p"),
            climateMin = attrNum("cmin"),
            climateMax = attrNum("cmax"),
            climateStep = attrNum("cstep"),
            doorsLocked = st(TeslaEntities.DOORS) == "locked",
            doorsState = st(TeslaEntities.DOORS),
            frunkOpen = st(TeslaEntities.FRUNK) == "open",
            trunkOpen = st(TeslaEntities.TRUNK) == "open",
            windowsOpen = st(TeslaEntities.WINDOWS) == "open",
            chargePortOpen = st(TeslaEntities.CHARGE_PORT_LATCH) == "unlocked",
            chargerDoorOpen = st(TeslaEntities.CHARGER_DOOR) == "open",
            // binary_sensor (on) or, with other integrations, an enum sensor (charging)
            charging = st(TeslaEntities.CHARGING)?.lowercase() in setOf("on", "charging"),
            chargerPowerKw = num(TeslaEntities.CHARGER_POWER),
            chargeLimit = num(TeslaEntities.CHARGE_LIMIT)?.toInt(),
            chargeLimitMin = attrNum("lmin")?.toInt(),
            chargeLimitMax = attrNum("lmax")?.toInt(),
            chargingAmps = num(TeslaEntities.CHARGING_AMPS)?.toInt(),
            chargingAmpsMin = attrNum("amin")?.toInt(),
            chargingAmpsMax = attrNum("amax")?.toInt(),
            sentryOn = st(TeslaEntities.SENTRY) == "on",
            online = st(TeslaEntities.ONLINE) == "on",
            asleep = st(TeslaEntities.ASLEEP) == "on",
            dataValid = num(TeslaEntities.BATTERY) != null,
            lastUpdate = now,
            lastSuccess = now,
            // A wrong tesla.prefix or entity.* override must not look like "all fine"
            error = if (missing.isEmpty()) null else appContext.getString(R.string.err_missing, missing.size, missing.first()),
            light1On = HomeConfig.light1?.let { st(it.entityId) == "on" } ?: false,
            light2On = HomeConfig.light2?.let { st(it.entityId) == "on" } ?: false,
            gate1Available = available(HomeConfig.gate1),
            gate2Available = available(HomeConfig.gate2)
        )
    }

    private fun loadCache(): TeslaState =
        cache.getString(CACHE_KEY, null)
            ?.let { runCatching { json.decodeFromString<TeslaState>(it) }.getOrNull() }
            ?: TeslaState()

    private fun saveCache(s: TeslaState) {
        cache.edit().putString(CACHE_KEY, json.encodeToString(s)).apply()
    }

    private fun describe(e: Exception): String = when {
        e is MissingTokenException -> appContext.getString(R.string.err_no_token)
        e is HttpNeedsWifiException -> appContext.getString(R.string.err_http_wifi)
        e is HttpStatusException && e.code == 401 -> appContext.getString(R.string.err_401)
        e is HttpStatusException && e.code == 403 -> appContext.getString(R.string.err_403)
        e is CommandUnconfirmedException -> appContext.getString(R.string.err_unconfirmed)
        else -> buildString {
            append(e.javaClass.simpleName)
            e.message?.let { append(": ").append(it.take(100)) }
        }
    }

    // --- Commands ---

    private enum class Outcome { OK, UNCONFIRMED, FAILED, REFUSED }

    /**
     * Sends one service call in the application scope, one at a time per entity.
     * [expect] (lock/unlock) is checked against states read after the command.
     */
    private suspend fun command(
        domain: String,
        service: String,
        entityId: String,
        extra: Map<String, JsonElement> = emptyMap(),
        expect: ((TeslaState) -> Boolean)? = null,
        confirmed: Int = 0,
        notConfirmed: Int = 0
    ): Outcome = scope.async {
        if (!TeslaEntities.isSet(entityId)) return@async Outcome.REFUSED
        if (tokenRefused()) {
            // Every refused call counts as a failed login in HA: do not send any until the token changes
            reportAction(appContext.getString(R.string.err_401), ok = false)
            return@async Outcome.REFUSED
        }
        entityLocks.getOrPut(entityId) { Mutex() }.withLock {
            // Checked inside the lock, so a command queued behind an unanswered one is refused too
            if (System.currentTimeMillis() < (pendingUntil[entityId] ?: 0L)) {
                reportAction(appContext.getString(R.string.err_pending), ok = false)
                return@withLock Outcome.REFUSED
            }
            // Foreground until the command and its follow-up reads are done (followUp ends it)
            KeepAlive.begin(appContext)
            val sentAt = System.currentTimeMillis()
            try {
                client.callService(domain, service, entityId, extra)
                followUp(entityId, sentAt, expect, confirmed, notConfirmed, clearsPending = false)
                Outcome.OK
            } catch (e: CancellationException) {
                KeepAlive.end()
                throw e
            } catch (e: Exception) {
                if (e !is CommandUnconfirmedException && !(e is HttpStatusException && e.code in 500..599)) {
                    KeepAlive.end()
                    reportAction(describe(e), ok = false)
                    return@withLock Outcome.FAILED
                }
                // No answer, or a 5xx from HA or a proxy after the call reached it: it may have run.
                // Say so, hold this entity and check the state anyway
                reportAction(appContext.getString(R.string.err_unconfirmed), ok = false)
                pendingUntil[entityId] = System.currentTimeMillis() + PENDING_MS
                followUp(entityId, sentAt, expect, confirmed, notConfirmed, clearsPending = true)
                Outcome.UNCONFIRMED
            }
        }
    }.await()

    /**
     * HA updates the Tesla entities a few seconds after a command: read again at 1.5 s, 5 s and 12 s.
     * With [expect], report whether the car really reached the expected state, judging only reads that
     * succeeded after the command was sent (a failed read returns the old state, which proves nothing).
     */
    private fun followUp(
        entityId: String,
        sentAt: Long,
        expect: ((TeslaState) -> Boolean)?,
        confirmed: Int,
        notConfirmed: Int,
        clearsPending: Boolean
    ) {
        val job = scope.launch {
            for (wait in longArrayOf(1_500, 3_500, 7_000)) {
                delay(wait)
                val s = refresh()
                if (s.lastSuccess < sentAt) continue
                if (expect != null && s.dataValid && expect(s)) {
                    Haptics.success(appContext)
                    reportAction(appContext.getString(confirmed), ok = true)
                    // The car did what was asked: the "no answer" hold is no longer needed
                    if (clearsPending) pendingUntil.remove(entityId)
                    return@launch
                }
            }
            if (expect != null) reportAction(appContext.getString(notConfirmed), ok = false)
            // Not confirmed: the hold stays until PENDING_MS, the time HA may still need
        }
        // Runs even if the job is cancelled before it starts
        job.invokeOnCompletion { KeepAlive.end() }
        when {
            expect != null -> verifyJobs.put(entityId, job)?.cancel()
            !clearsPending -> {
                followUpJob?.cancel()
                followUpJob = job
            }
        }
    }

    /** Records the outcome of a command: shown for a minute in the app and on the tile, vibration on failure. */
    private fun reportAction(message: String, ok: Boolean) {
        if (!ok) Haptics.failure(appContext)
        val s = _state.updateAndGet {
            it.copy(lastAction = message, lastActionOk = ok, lastActionAt = System.currentTimeMillis())
        }
        saveCache(s)
        onActionResult?.invoke()
    }

    /**
     * Doors, trunk, frunk, charge port and windows are covers that the Tesla integration drives
     * as toggles: with placeholder data a "close" can open. Without real data they are refused.
     */
    suspend fun ifCarDataValid(block: suspend TeslaRepository.() -> Boolean): Boolean {
        if (!_state.value.dataValid) {
            reportAction(appContext.getString(R.string.no_data), ok = false)
            return false
        }
        return block()
    }

    suspend fun lockDoors(): Boolean = command(
        "lock", "lock", TeslaEntities.DOORS,
        expect = { it.doorsState == "locked" },
        confirmed = R.string.lock_confirmed, notConfirmed = R.string.lock_not_confirmed
    ) == Outcome.OK

    /** Confirmed only by an explicit "unlocked": unknown, unavailable or jammed are not an unlock. */
    suspend fun unlockDoors(): Boolean = command(
        "lock", "unlock", TeslaEntities.DOORS,
        expect = { it.doorsState == "unlocked" },
        confirmed = R.string.unlock_confirmed, notConfirmed = R.string.unlock_not_confirmed
    ) == Outcome.OK

    suspend fun climateOn() = command("climate", "set_hvac_mode", TeslaEntities.CLIMATE,
        mapOf("hvac_mode" to JsonPrimitive("heat_cool"))) == Outcome.OK
    suspend fun climateOff() = command("climate", "set_hvac_mode", TeslaEntities.CLIMATE,
        mapOf("hvac_mode" to JsonPrimitive("off"))) == Outcome.OK
    suspend fun climateSetTemp(t: Double) = command("climate", "set_temperature", TeslaEntities.CLIMATE,
        mapOf("temperature" to JsonPrimitive(t))) == Outcome.OK
    suspend fun climateSetPreset(p: String) = command("climate", "set_preset_mode", TeslaEntities.CLIMATE,
        mapOf("preset_mode" to JsonPrimitive(p))) == Outcome.OK

    suspend fun openFrunk() = command("cover", "open_cover", TeslaEntities.FRUNK) == Outcome.OK
    suspend fun openTrunk() = command("cover", "open_cover", TeslaEntities.TRUNK) == Outcome.OK
    suspend fun closeTrunk() = command("cover", "close_cover", TeslaEntities.TRUNK) == Outcome.OK
    suspend fun ventWindows() = command("cover", "open_cover", TeslaEntities.WINDOWS) == Outcome.OK
    suspend fun closeWindows() = command("cover", "close_cover", TeslaEntities.WINDOWS) == Outcome.OK
    suspend fun openChargePort() = command("cover", "open_cover", TeslaEntities.CHARGER_DOOR) == Outcome.OK
    suspend fun closeChargePort() = command("cover", "close_cover", TeslaEntities.CHARGER_DOOR) == Outcome.OK
    suspend fun unlockChargeCable() = command("lock", "unlock", TeslaEntities.CHARGE_PORT_LATCH) == Outcome.OK

    suspend fun honk() = command("button", "press", TeslaEntities.BTN_HORN) == Outcome.OK
    suspend fun flashLights() = command("button", "press", TeslaEntities.BTN_FLASH) == Outcome.OK
    private suspend fun forceUpdate() = command("button", "press", TeslaEntities.BTN_FORCE_UPDATE) == Outcome.OK

    /** Wakes the car, waits until it is online (30 s at most) and only then asks for fresh data. */
    suspend fun wakeUpAndUpdate(): Boolean = scope.async {
        KeepAlive.begin(appContext)
        try {
            if (command("button", "press", TeslaEntities.BTN_WAKE) != Outcome.OK) return@async false
            repeat(10) {
                delay(3_000)
                val s = refresh()
                if (s.online && !s.asleep) {
                    return@async if (TeslaEntities.isSet(TeslaEntities.BTN_FORCE_UPDATE)) forceUpdate() else true
                }
            }
            false
        } finally {
            KeepAlive.end()
        }
    }.await()

    suspend fun sentryOn() = command("switch", "turn_on", TeslaEntities.SENTRY) == Outcome.OK
    suspend fun sentryOff() = command("switch", "turn_off", TeslaEntities.SENTRY) == Outcome.OK

    suspend fun chargeStart() = command("switch", "turn_on", TeslaEntities.CHARGER_SWITCH) == Outcome.OK
    suspend fun chargeStop() = command("switch", "turn_off", TeslaEntities.CHARGER_SWITCH) == Outcome.OK

    suspend fun setChargeLimit(pct: Int) = command("number", "set_value", TeslaEntities.CHARGE_LIMIT,
        mapOf("value" to JsonPrimitive(pct))) == Outcome.OK
    suspend fun setChargingAmps(a: Int) = command("number", "set_value", TeslaEntities.CHARGING_AMPS,
        mapOf("value" to JsonPrimitive(a))) == Outcome.OK

    // --- Smart home ---

    /**
     * Momentary action: press a button, run a script, switch a relay on (it needs an auto-off),
     * open a cover or unlock a lock. While a call for the same gate is running, and for
     * [GATE_COOLDOWN_MS] after it ended, further taps are refused (not queued): on step-by-step
     * controllers a second pulse stops or reverses the gate.
     */
    suspend fun activateGate(gate: HomeEntity): Boolean = scope.async {
        val available = if (gate.slot == 1) _state.value.gate1Available else _state.value.gate2Available
        if (available == false) {
            reportAction(appContext.getString(R.string.gate_unavailable, gate.name), ok = false)
            return@async false
        }
        val allowed = synchronized(gateGuard) {
            val busy = gate.slot in gateInFlight || System.currentTimeMillis() < (gateCooldownUntil[gate.slot] ?: 0L)
            if (!busy) gateInFlight.add(gate.slot)
            !busy
        }
        if (!allowed) {
            reportAction(appContext.getString(R.string.gate_cooldown, gate.name), ok = false)
            return@async false
        }
        try {
            val outcome = when (gate.domain) {
                "button", "input_button" -> command(gate.domain, "press", gate.entityId)
                "cover" -> command("cover", "open_cover", gate.entityId)
                "lock" -> command("lock", "unlock", gate.entityId)
                else -> command(gate.domain, "turn_on", gate.entityId)
            }
            // The pause starts when the call has ended: sent, or possibly sent
            if (outcome == Outcome.OK || outcome == Outcome.UNCONFIRMED) {
                synchronized(gateGuard) { gateCooldownUntil[gate.slot] = System.currentTimeMillis() + GATE_COOLDOWN_MS }
            }
            if (outcome == Outcome.OK) {
                Haptics.success(appContext)
                reportAction(appContext.getString(R.string.gate_sent, gate.name), ok = true)
            }
            outcome == Outcome.OK
        } finally {
            synchronized(gateGuard) { gateInFlight.remove(gate.slot) }
        }
    }.await()

    /** Sends the state the user asked for, so a stale view repeats a command instead of undoing it. */
    suspend fun setLight(light: HomeEntity, on: Boolean) =
        command(light.domain, if (on) "turn_on" else "turn_off", light.entityId) == Outcome.OK

    /** Runs a tile button in the application scope: the tile redraws when the outcome is known. */
    fun runTileAction(action: TileAction) {
        reportAction(appContext.getString(R.string.sending), ok = true)
        scope.launch {
            when (action) {
                TileAction.LOCK -> lockDoors()
                TileAction.UNLOCK -> unlockDoors()
                TileAction.GATE_1 -> HomeConfig.gate1?.let { activateGate(it) }
                TileAction.GATE_2 -> HomeConfig.gate2?.let { activateGate(it) }
            }
            onActionResult?.invoke()
        }
    }

    private companion object {
        const val CACHE_FILE = "tesla_state_cache"
        const val CACHE_KEY = "state"
        const val MODE_FILE = "read_mode"
        const val KEY_STATES_FOR = "states_for_token"
        const val KEY_REFUSED = "refused_token"
        const val GATE_COOLDOWN_MS = 10_000L

        /** Longer than the command timeout plus the follow-up reads. */
        const val PENDING_MS = 90_000L

        /**
         * A single POST /api/template returning {"s": {entity: state or null if missing}, "a": {attributes}}.
         * Entity IDs are validated at build time (lowercase, digits, _ and one dot), so they cannot break the template.
         */
        val REFRESH_TEMPLATE: String = buildString {
            append("{\"s\":{")
            TeslaEntities.REFRESH.forEachIndexed { i, id ->
                if (i > 0) append(',')
                append('"').append(id).append("\":{{ (states('").append(id)
                    .append("') if expand('").append(id).append("') | count > 0 else none) | tojson }}")
            }
            append("},\"a\":{")
            val attrs = listOf(
                "t" to (TeslaEntities.CLIMATE to "temperature"),
                "p" to (TeslaEntities.CLIMATE to "preset_mode"),
                "cmin" to (TeslaEntities.CLIMATE to "min_temp"),
                "cmax" to (TeslaEntities.CLIMATE to "max_temp"),
                "cstep" to (TeslaEntities.CLIMATE to "target_temp_step"),
                "lmin" to (TeslaEntities.CHARGE_LIMIT to "min"),
                "lmax" to (TeslaEntities.CHARGE_LIMIT to "max"),
                "amin" to (TeslaEntities.CHARGING_AMPS to "min"),
                "amax" to (TeslaEntities.CHARGING_AMPS to "max"),
                "ru" to (TeslaEntities.RANGE to "unit_of_measurement")
            )
            attrs.forEachIndexed { i, (key, source) ->
                if (i > 0) append(',')
                append('"').append(key).append("\":{{ state_attr('").append(source.first)
                    .append("', '").append(source.second).append("') | tojson }}")
            }
            append("}}")
        }
    }
}
