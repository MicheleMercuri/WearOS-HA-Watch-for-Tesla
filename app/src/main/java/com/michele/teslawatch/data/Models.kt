package com.michele.teslawatch.data

import kotlinx.serialization.Serializable

@Serializable
data class TeslaState(
    val batteryPct: Int? = null,
    val range: Int? = null,
    /** Unit of the range sensor as reported by Home Assistant ("km" or "mi"). */
    val rangeUnit: String = "km",
    val tempInside: Double? = null,
    val tempOutside: Double? = null,
    val climateOn: Boolean = false,
    val climateTarget: Double? = null,
    val climatePreset: String? = null,
    /** Target temperature limits and step from the climate entity (in HA's unit system). */
    val climateMin: Double? = null,
    val climateMax: Double? = null,
    val climateStep: Double? = null,
    val doorsLocked: Boolean = false,
    /** Raw state of the lock entity ("locked", "unlocked", "unknown", "unavailable", ...), null if missing. */
    val doorsState: String? = null,
    val frunkOpen: Boolean = false,
    val trunkOpen: Boolean = false,
    val windowsOpen: Boolean = false,
    val chargePortOpen: Boolean = false,
    val chargerDoorOpen: Boolean = false,
    val charging: Boolean = false,
    val chargerPowerKw: Double? = null,
    val chargeLimit: Int? = null,
    val chargeLimitMin: Int? = null,
    val chargeLimitMax: Int? = null,
    val chargingAmps: Int? = null,
    val chargingAmpsMin: Int? = null,
    val chargingAmpsMax: Int? = null,
    val sentryOn: Boolean = false,
    val online: Boolean = false,
    val asleep: Boolean = false,
    /**
     * False when the Tesla integration has no real data (e.g. restarted while the car sleeps):
     * its entities then hold placeholders such as trunk "open" and doors "unknown".
     */
    val dataValid: Boolean = false,
    /** Last refresh attempt, successful or not. */
    val lastUpdate: Long = 0L,
    /** Last successful refresh: 0 if data never arrived. */
    val lastSuccess: Long = 0L,
    val error: String? = null,
    /** On/off state of the light_1 and light_2 slots (null = not configured). */
    val light1On: Boolean = false,
    val light2On: Boolean = false,
    /** Whether the gate entities exist and are not "unavailable" (null = not known yet). */
    val gate1Available: Boolean? = null,
    val gate2Available: Boolean? = null,
    /** Outcome of the last command, shown briefly on the tile. */
    val lastAction: String? = null,
    val lastActionOk: Boolean = true,
    val lastActionAt: Long = 0L
)
