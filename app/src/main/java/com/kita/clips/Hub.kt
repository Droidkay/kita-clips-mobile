package com.kita.clips

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

enum class Status { OFFLINE, CONNECTING, ONLINE, ERROR }

data class Settings(
    val server: String = "",
    val room: String = "",
    val device: String = "",
    val autoCopy: Boolean = true,
    val background: Boolean = true,
) {
    val paired: Boolean
        get() = server.isNotBlank() && Crypto.normalize(room).length == Crypto.CODE_LEN
}

/**
 * Single source of truth for the app: settings, local clip history and the relay connection.
 * Lives in the application process so the UI, the foreground service and the capture activity share it.
 */
object Hub {
    private const val MAX_LOCAL_CLIPS = 300
    private const val MAX_TEXT = 100_000

    private lateinit var appCtx: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var clipFile: File
    private var inited = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    val settings = MutableStateFlow(Settings())
    val clips = MutableStateFlow<List<Clip>>(emptyList())
    val status = MutableStateFlow(Status.OFFLINE)
    val peers = MutableStateFlow(0)

    private var socket: WebSocket? = null
    private var activeKey: SecretKeySpec? = null
    private var generation = 0
    private var attempt = 0
    private var wanted = false
    private var reconnectJob: Job? = null
    private val pending = ConcurrentLinkedQueue<Clip>()

    /** Last text that came from / went to the system clipboard; prevents sync echo loops. */
    @Volatile
    private var lastText: String? = null

    @Synchronized
    fun init(ctx: Context) {
        if (inited) return
        inited = true
        appCtx = ctx.applicationContext
        prefs = appCtx.getSharedPreferences("kita_clips", Context.MODE_PRIVATE)
        clipFile = File(appCtx.filesDir, "clips.json")

        settings.value = Settings(
            server = prefs.getString("server", "") ?: "",
            room = prefs.getString("room", "") ?: "",
            device = prefs.getString("device", null) ?: Build.MODEL,
            autoCopy = prefs.getBoolean("autoCopy", true),
            background = prefs.getBoolean("background", true),
        )
        scope.launch {
            clips.value = ClipStore.load(clipFile)
            if (settings.value.paired) connect()
        }
    }

    fun saveSettings(new: Settings) {
        val old = settings.value
        val cleaned = new.copy(
            server = new.server.trim(),
            room = Crypto.normalize(new.room),
            device = new.device.trim().ifEmpty { Build.MODEL },
        )
        prefs.edit()
            .putString("server", cleaned.server)
            .putString("room", cleaned.room)
            .putString("device", cleaned.device)
            .putBoolean("autoCopy", cleaned.autoCopy)
            .putBoolean("background", cleaned.background)
            .apply()
        settings.value = cleaned

        val connectionChanged = old.server != cleaned.server || old.room != cleaned.room
        if (!cleaned.paired) {
            disconnect()
        } else if (connectionChanged || status.value != Status.ONLINE) {
            scope.launch { connect() }
        }
    }

    // ---------------------------------------------------------------- connection

    fun connect() {
        val s = settings.value
        if (!s.paired) return
        wanted = true
        reconnectJob?.cancel()
        val key = try {
            Crypto.key(s.room)
        } catch (_: Exception) {
            status.value = Status.ERROR
            return
        }
        activeKey = key

        val url = try {
            Request.Builder().url(Crypto.wsUrl(s.server, Crypto.roomId(s.room))).build()
        } catch (_: IllegalArgumentException) {
            status.value = Status.ERROR
            return
        }

        val gen = ++generation
        socket?.cancel()
        status.value = Status.CONNECTING
        socket = http.newWebSocket(url, Listener(gen, key))
    }

    /** Safe to call from the main thread: key derivation happens on a background thread. */
    fun ensureConnected() {
        if (settings.value.paired && status.value == Status.OFFLINE) scope.launch { connect() }
    }

    fun disconnect() {
        wanted = false
        reconnectJob?.cancel()
        generation++
        socket?.close(1000, null)
        socket = null
        status.value = Status.OFFLINE
        peers.value = 0
    }

    private fun connectionLost(gen: Int) {
        if (gen != generation) return
        status.value = Status.OFFLINE
        peers.value = 0
        if (!wanted || !settings.value.paired) return
        val wait = minOf(30_000L, 1_000L shl minOf(attempt, 5))
        attempt++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait)
            connect()
        }
    }

    private class Listener(val gen: Int, val key: SecretKeySpec) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (gen != generation) return
            attempt = 0
            status.value = Status.ONLINE
            while (true) {
                val clip = pending.poll() ?: break
                sendNow(webSocket, key, clip)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation) return
            handleMessage(text, key)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = connectionLost(gen)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = connectionLost(gen)
    }

    // ---------------------------------------------------------------- incoming

    private fun decode(o: JSONObject, key: SecretKeySpec): Clip? = try {
        val payload = JSONObject(Crypto.decrypt(key, o.getString("blob")))
        Clip(
            id = o.getString("id"),
            text = payload.getString("t"),
            ts = o.getLong("ts"),
            device = payload.optString("d", "?"),
        )
    } catch (_: Exception) {
        null // wrong key or corrupted: ignore
    }

    private fun handleMessage(text: String, key: SecretKeySpec) {
        val msg = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        when (msg.optString("type")) {
            "history" -> {
                val arr: JSONArray = msg.optJSONArray("clips") ?: return
                val incoming = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { decode(it, key) }
                }
                mergeClips(incoming)
            }

            "clip" -> {
                val clip = msg.optJSONObject("clip")?.let { decode(it, key) } ?: return
                if (clips.value.any { it.id == clip.id }) return
                mergeClips(listOf(clip))
                if (settings.value.autoCopy) copyToSystem(clip.text)
            }

            "delete" -> {
                val id = msg.optString("id")
                clips.update { list -> list.filter { it.id != id } }
                persist()
            }

            "clear" -> {
                clips.value = emptyList()
                persist()
            }

            "peers" -> peers.value = msg.optInt("count", 0)
        }
    }

    private fun mergeClips(incoming: List<Clip>) {
        clips.update { current ->
            val known = current.mapTo(HashSet()) { it.id }
            (current + incoming.filter { it.id !in known })
                .sortedByDescending { it.ts }
                .take(MAX_LOCAL_CLIPS)
        }
        persist()
    }

    // ---------------------------------------------------------------- outgoing

    /** Adds a clip to local history and (if paired) sends it to the room. Returns false if it was a duplicate/empty. */
    fun addLocal(text: String): Boolean {
        if (text.isBlank()) return false
        val t = if (text.length > MAX_TEXT) text.substring(0, MAX_TEXT) else text
        if (t == lastText) return false
        if (clips.value.firstOrNull()?.text == t) return false
        lastText = t

        val clip = Clip(UUID.randomUUID().toString(), t, System.currentTimeMillis(), settings.value.device)
        mergeClips(listOf(clip))
        if (settings.value.paired) {
            val ws = socket
            val key = activeKey
            if (ws != null && key != null && status.value == Status.ONLINE) {
                scope.launch { sendNow(ws, key, clip) }
            } else {
                pending.add(clip)
            }
        }
        return true
    }

    private fun sendNow(ws: WebSocket, key: SecretKeySpec, clip: Clip) {
        try {
            val blob = Crypto.encrypt(
                key,
                JSONObject().put("t", clip.text).put("d", clip.device).toString(),
            )
            ws.send(
                JSONObject()
                    .put("type", "clip")
                    .put("id", clip.id)
                    .put("ts", clip.ts)
                    .put("blob", blob)
                    .toString(),
            )
        } catch (_: Exception) {
            pending.add(clip)
        }
    }

    fun delete(id: String) {
        clips.update { list -> list.filter { it.id != id } }
        persist()
        socket?.send(JSONObject().put("type", "delete").put("id", id).toString())
    }

    fun clearAll() {
        clips.value = emptyList()
        persist()
        socket?.send(JSONObject().put("type", "clear").toString())
    }

    // ---------------------------------------------------------------- clipboard

    /** Writes text to the system clipboard (allowed from the background). */
    fun copyToSystem(text: String) {
        lastText = text
        main.post {
            try {
                val cm = appCtx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Kita Clips", text))
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    /**
     * Reads the system clipboard and records/sends it. Android only allows reading while one of our
     * windows has focus (or on some devices, while our service is alive), so callers use it from the
     * UI, the invisible capture activity, or the clipboard listener (best effort).
     */
    fun captureClipboard(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            val clip = cm.primaryClip ?: return false
            if (clip.itemCount == 0) return false
            // Password managers flag secrets as sensitive; never sync those.
            val sensitive = clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) ?: false
            if (sensitive) return false
            val text = clip.getItemAt(0).coerceToText(ctx)?.toString() ?: return false
            addLocal(text)
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun persist() {
        val snapshot = clips.value
        scope.launch { ClipStore.save(clipFile, snapshot) }
    }
}
