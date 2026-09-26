package com.michele.teslawatch.data

import com.michele.teslawatch.BuildConfig

/** Entity IDs, resolved at build time from local.properties (see local.properties.example). */
object TeslaEntities {
    const val BATTERY = BuildConfig.ENTITY_BATTERY
    const val RANGE = BuildConfig.ENTITY_RANGE
    const val TEMP_INSIDE = BuildConfig.ENTITY_TEMP_INSIDE
    const val TEMP_OUTSIDE = BuildConfig.ENTITY_TEMP_OUTSIDE
    const val CHARGER_POWER = BuildConfig.ENTITY_CHARGER_POWER

    const val DOORS = BuildConfig.ENTITY_DOORS
    const val CHARGE_PORT_LATCH = BuildConfig.ENTITY_CHARGE_PORT_LATCH

    const val FRUNK = BuildConfig.ENTITY_FRUNK
    const val TRUNK = BuildConfig.ENTITY_TRUNK
    const val WINDOWS = BuildConfig.ENTITY_WINDOWS
    const val CHARGER_DOOR = BuildConfig.ENTITY_CHARGER_DOOR

    const val CLIMATE = BuildConfig.ENTITY_CLIMATE

    const val ONLINE = BuildConfig.ENTITY_ONLINE
    const val ASLEEP = BuildConfig.ENTITY_ASLEEP
    const val CHARGING = BuildConfig.ENTITY_CHARGING

    const val SENTRY = BuildConfig.ENTITY_SENTRY
    const val CHARGER_SWITCH = BuildConfig.ENTITY_CHARGER

    const val CHARGE_LIMIT = BuildConfig.ENTITY_CHARGE_LIMIT
    const val CHARGING_AMPS = BuildConfig.ENTITY_CHARGING_AMPS

    const val BTN_HORN = BuildConfig.ENTITY_HORN
    const val BTN_FLASH = BuildConfig.ENTITY_FLASH_LIGHTS
    const val BTN_WAKE = BuildConfig.ENTITY_WAKE_UP
    const val BTN_FORCE_UPDATE = BuildConfig.ENTITY_FORCE_UPDATE

    /** True if the entity is configured (optional ones can be set to "none" in local.properties). */
    fun isSet(id: String): Boolean = id.isNotBlank()

    /**
     * Entities read on every refresh: HA returns only these instead of every state.
     * Gates are included only to know whether they exist and are available.
     */
    val REFRESH: List<String> = (
        listOf(
            BATTERY, RANGE, TEMP_INSIDE, TEMP_OUTSIDE, CHARGER_POWER,
            DOORS, CHARGE_PORT_LATCH, FRUNK, TRUNK, WINDOWS, CHARGER_DOOR, CLIMATE,
            ONLINE, ASLEEP, CHARGING, SENTRY, CHARGE_LIMIT, CHARGING_AMPS
        ) + HomeConfig.lights.map { it.entityId } + HomeConfig.gates.map { it.entityId }
    ).filter { isSet(it) }.distinct()
}
