import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ---------------------------------------------------------------------------
// Configuration: everything personal lives in local.properties (never committed).
// See local.properties.example for every key.
// The token is NOT compiled into the app: ./gradlew provisionToken sends it to the watch.
// ---------------------------------------------------------------------------

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) InputStreamReader(FileInputStream(f), Charsets.UTF_8).use { load(it) }
}

fun prop(key: String): String = localProps.getProperty(key, "").trim()

fun configError(msg: String): Nothing = throw GradleException("local.properties: $msg")

/** Quotes a value for a BuildConfig String field; control characters are refused. */
fun quoted(key: String, v: String): String {
    if (v.any { it.isISOControl() }) configError("$key contains a control character")
    return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

val entityIdRegex = Regex("^[a-z_]+\\.[a-z0-9_]+$")

fun requireEntityId(value: String, key: String, allowedDomains: Set<String>) {
    if (!entityIdRegex.matches(value)) configError("$key = '$value' is not a valid entity_id")
    val domain = value.substringBefore('.')
    if (domain !in allowedDomains) {
        configError("$key = '$value': domain '$domain' not supported here, use one of $allowedDomains")
    }
}

// Tesla entities. Defaults follow the naming of the Tesla Custom Integration (HACS),
// where <prefix> is the car name slug; any single entity can be overridden with entity.<key>.
val teslaEntities = linkedMapOf(
    "battery" to "sensor.%s_battery",
    "range" to "sensor.%s_range",
    "temp_inside" to "sensor.%s_temperature_inside",
    "temp_outside" to "sensor.%s_temperature_outside",
    "charger_power" to "sensor.%s_charger_power",
    "doors" to "lock.%s_doors",
    "charge_port_latch" to "lock.%s_charge_port_latch",
    "frunk" to "cover.%s_frunk",
    "trunk" to "cover.%s_trunk",
    "windows" to "cover.%s_windows",
    "charger_door" to "cover.%s_charger_door",
    "climate" to "climate.%s_hvac_climate_system",
    "online" to "binary_sensor.%s_online",
    "asleep" to "binary_sensor.%s_asleep",
    "charging" to "binary_sensor.%s_charging",
    "sentry" to "switch.%s_sentry_mode",
    "charger" to "switch.%s_charger",
    "charge_limit" to "number.%s_charge_limit",
    "charging_amps" to "number.%s_charging_amps",
    "horn" to "button.%s_horn",
    "flash_lights" to "button.%s_flash_lights",
    "wake_up" to "button.%s_wake_up",
    "force_update" to "button.%s_force_data_update"
)

/** Extra domains accepted for an override (other integrations report charging as an enum sensor). */
val alternativeDomains = mapOf("charging" to setOf("sensor"))

/** Entities that can be switched off with entity.<key>=none: their buttons disappear. */
val optionalEntities = setOf(
    "asleep", "force_update", "charger_power", "temp_inside", "temp_outside", "sentry",
    "horn", "flash_lights", "windows", "charge_port_latch", "charger_door", "frunk"
)

// Optional smart home buttons: two momentary "gates" (tile + app) and two on/off lights (app)
val gateDomains = setOf("button", "input_button", "script", "switch", "cover", "lock")
val lightDomains = setOf("switch", "light", "input_boolean", "fan")

val knownKeys: Set<String> = buildSet {
    addAll(listOf("sdk.dir", "ha.url", "ha.token", "ha.lan_ip", "ha.allow_http", "ha.read_mode", "car.name", "tesla.prefix"))
    teslaEntities.keys.forEach { add("entity.$it") }
    for (kind in listOf("gate", "light")) for (n in 1..2) {
        add("home.${kind}_$n")
        add("home.${kind}_$n.name")
    }
}
val unknownKeys = localProps.stringPropertyNames().filter { it !in knownKeys }.sorted()
if (unknownKeys.isNotEmpty()) configError("unknown keys $unknownKeys (see local.properties.example)")

val allowHttp = prop("ha.allow_http").equals("true", ignoreCase = true)
val haUrl = prop("ha.url").trimEnd('/')
val haLanIp = prop("ha.lan_ip")

if (haUrl.isEmpty()) {
    logger.warn("TeslaWatch: ha.url not set in local.properties, the app will build but cannot reach Home Assistant")
} else {
    val uri = runCatching { URI(haUrl) }.getOrNull() ?: configError("ha.url = '$haUrl' is not a valid URL")
    val scheme = uri.scheme?.lowercase()
    if (scheme !in setOf("http", "https")) configError("ha.url = '$haUrl' must start with https:// (or http://)")
    if (uri.host.isNullOrEmpty()) {
        if (!uri.rawAuthority.isNullOrEmpty()) {
            configError("ha.url = '$haUrl': the host name has characters a URL cannot contain (for example '_')")
        }
        configError("ha.url = '$haUrl' must look like https://host or https://host:port")
    }
    if (uri.rawAuthority.endsWith(":") || (uri.port != -1 && uri.port !in 1..65535)) {
        configError("ha.url = '$haUrl' has an invalid port")
    }
    if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) {
        configError("ha.url = '$haUrl' must be only scheme://host[:port], without a path such as /lovelace")
    }
    if (scheme == "http" && !allowHttp) {
        configError("ha.url uses plain http: the token would travel unencrypted. Use https, or set ha.allow_http=true if you accept that")
    }
}
val haUrlNormalized = if (haUrl.isEmpty()) "" else {
    val uri = URI(haUrl)
    uri.scheme.lowercase() + "://" + uri.rawAuthority
}

if (haLanIp.isNotEmpty()) {
    if (!haUrlNormalized.startsWith("https://")) {
        configError("ha.lan_ip needs an https ha.url: the certificate check is what makes the local shortcut safe")
    }
    val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(haLanIp)
    if (ipv4 == null || ipv4.groupValues.drop(1).any { it.toInt() > 255 }) {
        configError("ha.lan_ip = '$haLanIp' must be a plain IPv4 address, e.g. 192.168.0.10")
    }
}

val readMode = prop("ha.read_mode").lowercase().ifEmpty { "auto" }
if (readMode !in setOf("auto", "states")) configError("ha.read_mode = '$readMode' must be auto or states")

val teslaPrefix = prop("tesla.prefix").ifEmpty { "model3" }
if (!Regex("^[a-z0-9_]+$").matches(teslaPrefix)) configError("tesla.prefix = '$teslaPrefix' must be lowercase letters, digits or _")

android {
    namespace = "com.michele.teslawatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.michele.teslawatch"
        minSdk = 30
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"

        buildConfigField("String", "HA_URL", quoted("ha.url", haUrlNormalized))
        buildConfigField("String", "HA_LAN_IP", quoted("ha.lan_ip", haLanIp))
        buildConfigField("String", "HA_READ_MODE", quoted("ha.read_mode", readMode))
        buildConfigField("String", "CAR_NAME", quoted("car.name", prop("car.name").ifEmpty { "Tesla" }))

        teslaEntities.forEach { (key, pattern) ->
            val override = prop("entity.$key")
            val id = when {
                override.equals("none", ignoreCase = true) -> {
                    if (key !in optionalEntities) configError("entity.$key cannot be none (optional keys: $optionalEntities)")
                    ""
                }
                override.isNotEmpty() -> override
                else -> pattern.format(teslaPrefix)
            }
            if (id.isNotEmpty()) {
                requireEntityId(id, "entity.$key", setOf(pattern.substringBefore('.')) + alternativeDomains[key].orEmpty())
            }
            buildConfigField("String", "ENTITY_${key.uppercase()}", quoted("entity.$key", id))
        }

        for ((kind, domains) in listOf("gate" to gateDomains, "light" to lightDomains)) {
            for (n in 1..2) {
                val key = "home.${kind}_$n"
                val id = prop(key)
                if (id.isNotEmpty()) requireEntityId(id, key, domains)
                buildConfigField("String", "HOME_${kind.uppercase()}_$n", quoted(key, id))
                buildConfigField("String", "HOME_${kind.uppercase()}_${n}_NAME", quoted("$key.name", prop("$key.name")))
            }
        }

        // Plain http is refused unless explicitly allowed (see network_security_config*.xml)
        manifestPlaceholders["networkSecurityConfig"] =
            if (allowHttp) "@xml/network_security_config_allow_http" else "@xml/network_security_config"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded personal app: signed with the local debug key so a release installs over a debug build
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// ---------------------------------------------------------------------------
// Token provisioning: sends ha.token to the watch over adb, into the app's private,
// Keystore-encrypted storage. Run it once after the first install (and after a new token).
//   ./gradlew provisionToken            (uses the only connected device, or ANDROID_SERIAL)
//   ./gradlew clearToken                (erases the token from the watch)
// ---------------------------------------------------------------------------

/** adb as the user runs it: the ADB variable, then the one on PATH, then the Android SDK's. */
fun adbExecutable(): String {
    System.getenv("ADB")?.takeIf { it.isNotBlank() }?.let { return it }
    val exe = if (System.getProperty("os.name").lowercase().contains("win")) "adb.exe" else "adb"
    val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, exe) }.firstOrNull { it.isFile }
    if (onPath != null) return onPath.absolutePath
    val sdk = prop("sdk.dir").ifEmpty { System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "" }
    val candidate = File(sdk, "platform-tools/$exe")
    return if (candidate.isFile) candidate.absolutePath else exe
}

/**
 * Runs a command on the watch through adb. [input] goes to its standard input, so a secret never
 * appears in a command line (on the PC or on the watch) nor in the system's broadcast history.
 */
fun runAdbShell(command: String, input: String? = null): String {
    val process = ProcessBuilder(adbExecutable(), "shell", command).redirectErrorStream(true).start()
    process.outputStream.use { out -> if (input != null) out.write((input + "\n").toByteArray()) }
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor()
    return output
}

val tokenUri = "content://com.michele.teslawatch.token"

tasks.register("provisionToken") {
    group = "tesla watch"
    description = "Sends ha.token from local.properties to the watch (adb, over USB or Wireless debugging)."
    doLast {
        val token = prop("ha.token")
        val jwt = Regex("^[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}$")
        if (!jwt.matches(token)) {
            configError("ha.token is missing or is not a Home Assistant long-lived token (three parts separated by dots, no quotes)")
        }
        // Send it only to this app: installed on the device, and owner of the token provider
        val owner = runAdbShell("dumpsys package com.michele.teslawatch")
        if (!owner.contains("com.michele.teslawatch.token")) {
            throw GradleException("HA Watch for Tesla is not installed on the connected device (or it is an old version). Install it first.")
        }
        // The shell reads the token from stdin; the token's characters need no quoting
        val out = runAdbShell("read -r t; content call --uri $tokenUri --method set --arg \$t", input = token)
        if (!out.contains("result=stored")) {
            throw GradleException("The watch did not store the token. adb said:\n" + out.replace(token, "<token>"))
        }
        println("Token stored on the watch.")
    }
}

tasks.register("clearToken") {
    group = "tesla watch"
    description = "Erases the Home Assistant token from the watch (adb)."
    doLast {
        val out = runAdbShell("content call --uri $tokenUri --method clear")
        if (!out.contains("result=cleared")) throw GradleException("The watch did not confirm. adb said:\n$out")
        println("Token erased from the watch.")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    // Wear Compose
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear.compose:compose-navigation:1.4.0")

    // Activity + lifecycle
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Wear tiles + complications
    implementation("androidx.wear.tiles:tiles:1.4.0")
    implementation("androidx.wear.tiles:tiles-material:1.4.0")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-expression:1.4.2")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")

    // Splash + wear helpers
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.wear:wear:1.3.0")

    // Installs the Compose baseline profiles even when the app is sideloaded with adb (faster cold start)
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    // Guava for ListenableFuture (used by Tile service)
    implementation("com.google.guava:guava:33.3.1-android")
}
