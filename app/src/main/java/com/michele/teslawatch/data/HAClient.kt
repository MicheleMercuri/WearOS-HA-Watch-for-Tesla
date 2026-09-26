package com.michele.teslawatch.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** After a failed attempt on the local network only the public route is used for this long. */
private const val LAN_RETRY_MS = 60_000L

/**
 * Home Assistant REST client.
 *
 * Reads and commands use different OkHttp clients: commands never retry on their own (a retry after
 * HA received the request would pulse a gate twice) and wait long enough for HA to wake the car.
 *
 * On Wi-Fi, when [lanIp] is set, the client first connects straight to that address but still speaks
 * HTTPS to the public host name and checks its certificate: a different device answering on that IP
 * (for example on a hotel network) fails the TLS handshake before the token is ever sent. If the local
 * route fails before the request is written, the public URL is used.
 */
class HAClient(
    context: Context,
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    lanIp: String
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    // Marks the request as sent as soon as OkHttp starts writing its headers
    private val sendTracker = object : EventListener() {
        override fun requestHeadersStart(call: Call) {
            call.request().tag(SendState::class.java)?.sent = true
        }
    }

    private val readHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 })
        .eventListener(sendTracker)
        .build()

    // HA answers a service call only when it is done, which includes waking a sleeping car.
    // OkHttp must never send a command twice: no retries, no redirects, and no automatic re-send of a
    // "503 Retry-After: 0" (removing that header before OkHttp's follow-up logic sees it).
    private val commandHttp = readHttp.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.header("Retry-After") != null) response.newBuilder().removeHeader("Retry-After").build() else response
        }
        .build()

    private val lanDns: Dns? = run {
        val host = baseUrl.toHttpUrlOrNull()?.takeIf { it.isHttps }?.host
        if (lanIp.isBlank() || host == null) return@run null
        val lanAddress = InetAddress.getByAddress(host, lanIp.split('.').map { it.toInt().toByte() }.toByteArray())
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname.equals(host, ignoreCase = true)) listOf(lanAddress) else Dns.SYSTEM.lookup(hostname)
        }
    }

    // Same URLs and certificate check, but the public host name resolves to the local address.
    // An unreachable local host must be dropped quickly.
    private val lanReadHttp = lanDns?.let { readHttp.newBuilder().dns(it).connectTimeout(1_200, TimeUnit.MILLISECONDS).build() }
    private val lanCommandHttp = lanDns?.let { commandHttp.newBuilder().dns(it).connectTimeout(1_200, TimeUnit.MILLISECONDS).build() }

    private val jsonType = "application/json".toMediaType()

    @Volatile private var lanSkipUntil = 0L

    /** Last route that answered: "lan", "wan", or empty if no call has succeeded yet. */
    @Volatile var lastRoute: String = ""
        private set

    val hasUrl: Boolean get() = baseUrl.isNotBlank()

    /** POST /api/template: one call for every entity, but Home Assistant allows it only to administrators. */
    suspend fun renderTemplate(template: String): String {
        val body = buildJsonObject { put("template", JsonPrimitive(template)) }
            .toString().toRequestBody(jsonType)
        return execute("POST", "/api/template", body, command = false) { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HttpStatusException(resp.code, "POST /api/template ${resp.code}: ${text.take(120)}")
            text
        }
    }

    /** GET /api/states/<entity_id>, allowed to any user. Returns null if the entity does not exist. */
    suspend fun getState(entityId: String): JsonObject? =
        execute("GET", "/api/states/$entityId", null, command = false) { resp ->
            val text = resp.body?.string().orEmpty()
            when {
                resp.code == 404 -> null
                !resp.isSuccessful -> throw HttpStatusException(resp.code, "GET $entityId ${resp.code}: ${text.take(120)}")
                else -> json.parseToJsonElement(text).jsonObject
            }
        }

    suspend fun callService(
        domain: String,
        service: String,
        entityId: String? = null,
        extra: Map<String, JsonElement> = emptyMap()
    ) {
        val body = buildJsonObject {
            if (entityId != null) put("entity_id", JsonPrimitive(entityId))
            extra.forEach { (k, v) -> put(k, v) }
        }.toString().toRequestBody(jsonType)
        execute("POST", "/api/services/$domain/$service", body, command = true) { resp ->
            if (!resp.isSuccessful) {
                throw HttpStatusException(resp.code, "POST $domain.$service ${resp.code}: ${resp.body?.string().orEmpty().take(120)}")
            }
        }
    }

    private suspend fun <T> execute(
        method: String,
        path: String,
        body: RequestBody?,
        command: Boolean,
        handle: (Response) -> T
    ): T {
        if (!hasUrl) throw HAException("Home Assistant URL missing in local.properties")
        // With plain http the token travels in clear: send it only on Wi-Fi, never over mobile data
        if (baseUrl.startsWith("http://") && !onWifi()) throw HttpNeedsWifiException()
        val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: throw MissingTokenException()
        val lan = if (command) lanCommandHttp else lanReadHttp
        if (lan != null && System.currentTimeMillis() >= lanSkipUntil && onWifi()) {
            val sent = SendState()
            try {
                val result = call(lan, method, path, body, token, sent, handle)
                lastRoute = "lan"
                return result
            } catch (e: IOException) {
                lanSkipUntil = System.currentTimeMillis() + LAN_RETRY_MS
                // A command that may already have reached HA must not be repeated on another route
                if (command && sent.sent) throw CommandUnconfirmedException(e)
            }
        }
        val sent = SendState()
        try {
            val result = call(if (command) commandHttp else readHttp, method, path, body, token, sent, handle)
            lastRoute = "wan"
            return result
        } catch (e: IOException) {
            if (command && sent.sent) throw CommandUnconfirmedException(e)
            throw e
        }
    }

    private suspend fun <T> call(
        client: OkHttpClient,
        method: String,
        path: String,
        body: RequestBody?,
        token: String,
        sent: SendState,
        handle: (Response) -> T
    ): T {
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer $token")
            .method(method, body)
            .tag(SendState::class.java, sent)
            .build()
        val response = client.newCall(request).await()
        return withContext(Dispatchers.IO) { response.use(handle) }
    }

    private fun onWifi(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}

private class SendState {
    @Volatile var sent = false
}

/** Runs the call without blocking a thread and cancels it if the coroutine is cancelled. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

open class HAException(msg: String) : RuntimeException(msg)

class HttpStatusException(val code: Int, msg: String) : HAException(msg)

/** No token stored on the watch yet (see TokenProvider). */
class MissingTokenException : HAException("token missing")

/** ha.allow_http is set and the watch is not on Wi-Fi. */
class HttpNeedsWifiException : HAException("http only on Wi-Fi")

/** The command was written to Home Assistant but no answer arrived: it may or may not have run. */
class CommandUnconfirmedException(cause: IOException) : IOException(cause.javaClass.simpleName, cause)
