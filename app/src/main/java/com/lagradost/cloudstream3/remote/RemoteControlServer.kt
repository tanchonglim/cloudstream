package com.lagradost.cloudstream3.remote

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.UnsafeSSL
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.insecureApp
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.initClient
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.result.START_ACTION_RESUME_LATEST
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local network server that lets the "CloudStream Remote" phone app control this device.
 *
 * - Discovery: registered over mDNS / NSD as [SERVICE_TYPE].
 * - Pairing: the phone asks for a code, a 6 digit code is shown on the TV and the phone
 *   exchanges it for a long random token. Only a hash of each token is stored.
 * - Everything except `/api/info` and pairing requires `Authorization: Bearer <token>`.
 *
 * The server only runs when the user enables it in Settings > General > Phone remote control.
 */
object RemoteControlServer {
    private const val TAG = "RemoteControlServer"
    const val SERVICE_TYPE = "_cloudstream._tcp."
    const val DEFAULT_PORT = 8765
    const val PROTOCOL_VERSION = 1
    const val ENABLED_KEY = "remote_control_enabled_key"
    internal const val TOKENS_KEY = "remote_control_tokens_key"

    private const val PAIR_CODE_TTL_MS = 2 * 60 * 1000L
    private const val MAX_PAIR_FAILURES = 5
    private const val PAIR_LOCKOUT_MS = 60 * 1000L
    private const val SEARCH_TIMEOUT_MS = 30_000L

    @Volatile private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private var nsdManager: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var appContext: Context? = null

    @Volatile var port: Int = 0
        private set

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    private val random = SecureRandom()
    private val pairLock = Any()
    private var pairCode: String? = null
    private var pairCodeExpires = 0L
    private var pairFailures = 0
    private var pairLockedUntil = 0L
    private var pairDialog: AlertDialog? = null

    // Strong reference, SharedPreferences only keeps weak references to listeners
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == ENABLED_KEY) {
            val ctx = appContext ?: return@OnSharedPreferenceChangeListener
            if (prefs.getBoolean(ENABLED_KEY, false)) start(ctx) else stop()
        }
    }

    /** Call once from the main activity; starts or stops the server following the setting. */
    fun init(context: Context) {
        val ctx = context.applicationContext
        if (appContext == null) {
            appContext = ctx
            PreferenceManager.getDefaultSharedPreferences(ctx)
                .registerOnSharedPreferenceChangeListener(prefListener)
        }
        if (PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(ENABLED_KEY, false)) {
            start(ctx)
        }
    }

    @Synchronized
    fun start(context: Context) {
        if (isRunning) return
        appContext = context.applicationContext
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(DEFAULT_PORT))
            }
        } catch (_: Throwable) {
            // Port taken, let the OS pick one. Discovery advertises the real port.
            try {
                ServerSocket(0)
            } catch (t: Throwable) {
                logError(t)
                return
            }
        }
        serverSocket = socket
        port = socket.localPort
        val pool = Executors.newFixedThreadPool(8)
        executor = pool
        Thread({ acceptLoop(socket, pool) }, "cs-remote-accept").apply { isDaemon = true }.start()
        registerNsd(context.applicationContext)
        Log.i(TAG, "Remote control server listening on port $port")
    }

    @Synchronized
    fun stop() {
        unregisterNsd()
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        executor?.shutdownNow()
        executor = null
        port = 0
    }

    /** Local IPv4 addresses, used to show the user where the server can be reached. */
    fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    } catch (_: Throwable) {
        emptyList()
    }

    fun deviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.distinct()
            .joinToString(" ").replaceFirstChar { it.uppercase() }.ifBlank { "CloudStream" }

    /** Forget every paired phone. */
    fun revokeAll(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit { remove(TOKENS_KEY) }
    }

    fun pairedCount(context: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(context).getStringSet(TOKENS_KEY, emptySet())?.size ?: 0

    // ---------------- networking ----------------

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            } catch (t: Throwable) {
                logError(t)
                continue
            }
            try {
                pool.execute { handleClient(client) }
            } catch (_: Throwable) {
                client.close()
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { s ->
            s.soTimeout = 15_000
            val response = try {
                val request = MiniHttp.readRequest(s.getInputStream(), s.inetAddress?.hostAddress ?: "")
                    ?: return
                route(request)
            } catch (e: MiniHttp.HttpException) {
                error(e.status, e.message ?: "Error")
            } catch (t: Throwable) {
                logError(t)
                error(500, t.message ?: "Internal error")
            }
            try {
                MiniHttp.writeResponse(s.getOutputStream(), response)
            } catch (_: Throwable) {
            }
        }
    }

    private fun json(obj: Any, status: Int = 200) = MiniHttp.Response(status, obj.toString())
    private fun ok() = json(JSONObject().put("ok", true))
    private fun error(status: Int, message: String) =
        json(JSONObject().put("ok", false).put("error", message), status)

    private fun body(request: MiniHttp.Request): JSONObject = try {
        if (request.body.isBlank()) JSONObject() else JSONObject(request.body)
    } catch (_: Throwable) {
        throw MiniHttp.HttpException(400, "Body must be JSON")
    }

    private fun requireContext(): Context =
        appContext ?: throw MiniHttp.HttpException(503, "Not ready")

    private fun route(request: MiniHttp.Request): MiniHttp.Response {
        if (request.method == "OPTIONS") return MiniHttp.Response(204, "")

        when (request.path) {
            "/api/info" -> return json(infoJson(request))
            "/api/pair/start" -> return startPairing()
            "/api/pair" -> return completePairing(body(request))
        }

        if (!isAuthorized(request)) return error(401, "Not paired")

        return when (request.method to request.path) {
            "GET" to "/api/providers" -> json(providersJson())
            "GET" to "/api/search" -> json(search(request.query["q"].orEmpty(), request.query["provider"]))
            "POST" to "/api/open" -> open(body(request))
            "POST" to "/api/key" -> key(body(request))
            "POST" to "/api/text" -> text(body(request))
            "GET" to "/api/settings" -> json(RemoteSettingsCatalog.listJson(requireContext()))
            "POST" to "/api/settings" -> writeSetting(body(request))
            "POST" to "/api/restart" -> {
                recreateActivity(); ok()
            }

            "POST" to "/api/unpair" -> {
                removeToken(request); ok()
            }

            else -> error(404, "Unknown endpoint ${request.method} ${request.path}")
        }
    }

    private fun infoJson(request: MiniHttp.Request) = JSONObject().apply {
        put("app", "CloudStream")
        put("protocol", PROTOCOL_VERSION)
        put("version", BuildConfig.VERSION_NAME)
        put("device", deviceName())
        put("paired", isAuthorized(request))
    }

    // ---------------- pairing ----------------

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun tokenOf(request: MiniHttp.Request): String? =
        request.header("authorization")?.removePrefix("Bearer")?.trim()?.takeIf { it.isNotEmpty() }

    private fun isAuthorized(request: MiniHttp.Request): Boolean {
        val token = tokenOf(request) ?: return false
        val ctx = appContext ?: return false
        val tokens = PreferenceManager.getDefaultSharedPreferences(ctx).getStringSet(TOKENS_KEY, emptySet())
            ?: return false
        return tokens.contains(sha256(token))
    }

    private fun removeToken(request: MiniHttp.Request) {
        val token = tokenOf(request) ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val tokens = prefs.getStringSet(TOKENS_KEY, emptySet()).orEmpty() - sha256(token)
        prefs.edit { putStringSet(TOKENS_KEY, tokens) }
    }

    private fun startPairing(): MiniHttp.Response {
        val code = synchronized(pairLock) {
            val now = System.currentTimeMillis()
            if (now < pairLockedUntil) return error(429, "Too many attempts, try again later")
            val code = (random.nextInt(900_000) + 100_000).toString()
            pairCode = code
            pairCodeExpires = now + PAIR_CODE_TTL_MS
            code
        }
        showPairDialog(code)
        return ok()
    }

    private fun completePairing(body: JSONObject): MiniHttp.Response {
        val given = body.optString("code").trim()
        synchronized(pairLock) {
            val now = System.currentTimeMillis()
            if (now < pairLockedUntil) return error(429, "Too many attempts, try again later")
            val expected = pairCode
            if (expected == null || now > pairCodeExpires) return error(409, "No pairing in progress")
            if (!MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())) {
                if (++pairFailures >= MAX_PAIR_FAILURES) {
                    pairFailures = 0
                    pairCode = null
                    pairLockedUntil = now + PAIR_LOCKOUT_MS
                    dismissPairDialog()
                }
                return error(403, "Wrong code")
            }
            pairCode = null
            pairFailures = 0
        }

        val token = ByteArray(32).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val tokens = prefs.getStringSet(TOKENS_KEY, emptySet()).orEmpty() + sha256(token)
        prefs.edit(commit = true) { putStringSet(TOKENS_KEY, tokens) }

        dismissPairDialog()
        val name = body.optString("name").ifBlank { "phone" }.take(60)
        CommonActivity.showToast(appContext?.getString(R.string.remote_control_paired_toast, name))
        return json(JSONObject().put("ok", true).put("token", token).put("device", deviceName()))
    }

    private fun showPairDialog(code: String) {
        val activity = CommonActivity.activity ?: return
        activity.runOnUiThread {
            try {
                pairDialog?.dismiss()
                pairDialog = AlertDialog.Builder(activity)
                    .setTitle(R.string.remote_control_pair_title)
                    .setMessage(activity.getString(R.string.remote_control_pair_message, code.chunked(3).joinToString(" ")))
                    .setNegativeButton(R.string.cancel) { d, _ ->
                        synchronized(pairLock) { pairCode = null }
                        d.dismiss()
                    }
                    .show()
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }

    private fun dismissPairDialog() {
        val dialog = pairDialog ?: return
        pairDialog = null
        CommonActivity.activity?.runOnUiThread {
            try {
                dialog.dismiss()
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------- search & open ----------------

    private fun searchableApis(context: Context): List<MainAPI> {
        val settings = context.getApiSettings()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val defaultTypes = TvType.entries.filter { it != TvType.NSFW }.map { it.ordinal.toString() }.toSet()
        val preferredTypes = (prefs.getStringSet(context.getString(R.string.prefer_media_type_key), defaultTypes)
            ?.ifEmpty { defaultTypes } ?: defaultTypes).mapNotNull { it.toIntOrNull() }.toSet()

        return apis.withLock { apis.toList() }.filter { api ->
            settings.contains(api.name) && api.supportedTypes.any { preferredTypes.contains(it.ordinal) }
        }
    }

    private fun providersJson(): JSONObject {
        val arr = JSONArray()
        searchableApis(requireContext()).forEach { api ->
            arr.put(JSONObject().apply {
                put("name", api.name)
                put("lang", api.lang)
                put("types", JSONArray(api.supportedTypes.map { it.name }))
            })
        }
        return JSONObject().put("providers", arr)
    }

    private fun SearchResponse.toJson() = JSONObject().apply {
        put("name", name)
        put("url", url)
        put("apiName", apiName)
        type?.let { put("type", it.name) }
        posterUrl?.let { put("posterUrl", it) }
        posterHeaders?.takeIf { it.isNotEmpty() }?.let { put("posterHeaders", JSONObject(it)) }
        quality?.let { put("quality", it.name) }
        when (val r = this@toJson) {
            is MovieSearchResponse -> r.year
            is TvSeriesSearchResponse -> r.year
            is AnimeSearchResponse -> r.year
            else -> null
        }?.let { put("year", it) }
    }

    private fun search(query: String, provider: String?): JSONObject {
        if (query.isBlank()) throw MiniHttp.HttpException(400, "Missing q")
        val ctx = requireContext()
        val targets = searchableApis(ctx).filter { provider == null || it.name == provider }
        if (provider != null && targets.isEmpty()) throw MiniHttp.HttpException(404, "Unknown provider")

        val results = runBlocking {
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                targets.amap { api ->
                    val res = try {
                        APIRepository(api).search(query, 1)
                    } catch (t: Throwable) {
                        logError(t)
                        null
                    }
                    api.name to ((res as? Resource.Success)?.value?.items ?: emptyList())
                }
            } ?: emptyList()
        }

        val groups = JSONArray()
        results.filter { it.second.isNotEmpty() }.forEach { (name, items) ->
            groups.put(JSONObject().put("provider", name).put("items", JSONArray(items.map { it.toJson() })))
        }
        return JSONObject().put("query", query).put("results", groups)
    }

    private fun currentMainActivity(): FragmentActivity? =
        CommonActivity.activity as? MainActivity

    /** Brings CloudStream to the front, it must already be running for this to be allowed. */
    private fun bringToFront(context: Context) {
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (t: Throwable) {
            logError(t)
        }
    }

    private fun open(body: JSONObject): MiniHttp.Response {
        val url = body.optString("url").ifBlank { throw MiniHttp.HttpException(400, "Missing url") }
        val apiName = body.optString("apiName").ifBlank { throw MiniHttp.HttpException(400, "Missing apiName") }
        val name = body.optString("name")
        val autoplay = body.optBoolean("autoplay", false)

        val ctx = requireContext()
        bringToFront(ctx)
        val activity = currentMainActivity() ?: return error(503, "CloudStream is not open on the TV")
        activity.loadResult(url, apiName, name, if (autoplay) START_ACTION_RESUME_LATEST else 0)
        return ok()
    }

    // ---------------- remote keys ----------------

    private val keyCodes = mapOf(
        "up" to KeyEvent.KEYCODE_DPAD_UP,
        "down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "left" to KeyEvent.KEYCODE_DPAD_LEFT,
        "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "center" to KeyEvent.KEYCODE_DPAD_CENTER,
        "enter" to KeyEvent.KEYCODE_ENTER,
        "back" to KeyEvent.KEYCODE_BACK,
        "menu" to KeyEvent.KEYCODE_MENU,
        "play_pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
        "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
        "stop" to KeyEvent.KEYCODE_MEDIA_STOP,
        "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
        "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        "fast_forward" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        "rewind" to KeyEvent.KEYCODE_MEDIA_REWIND,
        "delete" to KeyEvent.KEYCODE_DEL,
        "search" to KeyEvent.KEYCODE_SEARCH,
    )

    private val instrumentation by lazy { Instrumentation() }

    private fun key(body: JSONObject): MiniHttp.Response {
        val name = body.optString("key")
        val code = keyCodes[name] ?: throw MiniHttp.HttpException(400, "Unknown key $name")
        if (CommonActivity.activity == null) return error(503, "CloudStream is not open on the TV")
        // Instrumentation injects into our own windows through the normal input pipeline,
        // so focus navigation behaves exactly like a physical remote. No extra permission needed.
        try {
            instrumentation.sendKeyDownUpSync(code)
        } catch (t: Throwable) {
            // Fallback: hand the event straight to the activity
            val activity = CommonActivity.activity ?: return error(503, "Not available")
            activity.runOnUiThread {
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
        }
        return ok()
    }

    private fun text(body: JSONObject): MiniHttp.Response {
        val value = body.optString("text")
        if (value.isEmpty()) return ok()
        if (CommonActivity.activity == null) return error(503, "CloudStream is not open on the TV")
        try {
            instrumentation.sendStringSync(value)
        } catch (t: Throwable) {
            logError(t)
            return error(500, "Could not type text")
        }
        return ok()
    }

    // ---------------- settings ----------------

    private fun writeSetting(body: JSONObject): MiniHttp.Response {
        val key = body.optString("key")
        if (!body.has("value")) throw MiniHttp.HttpException(400, "Missing value")
        val value = body.get("value").let { if (it == JSONObject.NULL) null else it }
        val ctx = requireContext()
        val recreate = RemoteSettingsCatalog.write(ctx, key, value)

        if (key == "dns_key") {
            try {
                app.initClient(ctx, ignoreSSL = false)
                @OptIn(UnsafeSSL::class)
                insecureApp.initClient(ctx, ignoreSSL = true)
            } catch (t: Throwable) {
                logError(t)
            }
        }
        if (recreate && body.optBoolean("applyNow", true)) recreateActivity()
        return json(JSONObject().put("ok", true).put("recreated", recreate))
    }

    private fun recreateActivity() {
        val activity: Activity = CommonActivity.activity ?: return
        activity.runOnUiThread {
            try {
                activity.recreate()
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }

    // ---------------- discovery ----------------

    private fun registerNsd(context: Context) {
        try {
            val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            val info = NsdServiceInfo().apply {
                serviceName = "CloudStream on ${deviceName()}"
                serviceType = SERVICE_TYPE
                port = this@RemoteControlServer.port
                setAttribute("protocol", PROTOCOL_VERSION.toString())
                setAttribute("version", BuildConfig.VERSION_NAME)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "Registered ${serviceInfo.serviceName}")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NSD registration failed $errorCode")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdManager = manager
            nsdListener = listener
        } catch (t: Throwable) {
            logError(t)
        }
    }

    private fun unregisterNsd() {
        val manager = nsdManager ?: return
        val listener = nsdListener ?: return
        try {
            manager.unregisterService(listener)
        } catch (_: Throwable) {
        }
        nsdManager = null
        nsdListener = null
    }
}
