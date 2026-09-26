package com.michele.teslawatch.data

import com.michele.teslawatch.BuildConfig

/** A smart home entity shown as a button; [name] is the label on the watch. */
data class HomeEntity(val slot: Int, val entityId: String, val name: String) {
    val domain: String get() = entityId.substringBefore('.')
}

/**
 * Optional smart home buttons, set in local.properties (home.gate_1, home.light_1, ...).
 * Gates are momentary actions (tile + app), lights are on/off switches (app only).
 * Each button stays bound to its slot, so gate_2 is always gate_2 even if gate_1 is empty.
 */
object HomeConfig {
    val gate1: HomeEntity? = entity(1, BuildConfig.HOME_GATE_1, BuildConfig.HOME_GATE_1_NAME, "Gate 1")
    val gate2: HomeEntity? = entity(2, BuildConfig.HOME_GATE_2, BuildConfig.HOME_GATE_2_NAME, "Gate 2")
    val light1: HomeEntity? = entity(1, BuildConfig.HOME_LIGHT_1, BuildConfig.HOME_LIGHT_1_NAME, "Light 1")
    val light2: HomeEntity? = entity(2, BuildConfig.HOME_LIGHT_2, BuildConfig.HOME_LIGHT_2_NAME, "Light 2")

    val gates: List<HomeEntity> = listOfNotNull(gate1, gate2)
    val lights: List<HomeEntity> = listOfNotNull(light1, light2)

    val isEmpty: Boolean get() = gates.isEmpty() && lights.isEmpty()

    private fun entity(slot: Int, id: String, name: String, fallback: String): HomeEntity? =
        if (id.isBlank()) null else HomeEntity(slot, id, name.ifBlank { fallback })
}
