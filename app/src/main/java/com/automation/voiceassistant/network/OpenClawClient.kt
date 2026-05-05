package com.automation.voiceassistant.network

import android.content.Context
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.KeyPairGenerator
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object OpenClawClient {

    // ── State ────────────────────────────────────────────────────────

    enum class ConnectionState { DISCONNECTED, CONNECTING, AUTHENTICATED, ERROR }

    sealed class ConnectResult {
        object Success : ConnectResult()
        data class NotPaired(val requestId: String) : ConnectResult()
        data class Error(val message: String) : ConnectResult()
    }

    @Volatile var state: ConnectionState = ConnectionState.DISCONNECTED
        private set

    private var ws: WebSocketClient? = null

    // Stored at connect() time — reused for auto-reconnect inside send()
    private var onLog: (String, Boolean) -> Unit = { _, _ -> }
    private var savedHost  = ""
    private var savedPort  = ""
    private var savedToken = ""
    private var privKey: EdDSAPrivateKey? = null
    private var pubKeyBytes: ByteArray?   = null
    private var deviceId = ""

    // Serialises send() calls (safety belt — state machine already prevents concurrent sends)
    private val sendLock = Any()

    // Swappable message routing: replaced before each connect / send operation
    @Volatile private var messageHandler: ((JSONObject) -> Unit)? = null
    @Volatile private var closeHandler:   ((Int, String?) -> Unit)? = null
    @Volatile private var errorHandler:   ((Exception?) -> Unit)?  = null

    // ── Helpers ──────────────────────────────────────────────────────

    private fun base64url(bytes: ByteArray) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun sha256hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun getOrCreateKeypair(context: Context): Pair<EdDSAPublicKey, EdDSAPrivateKey> {
        val prefs      = context.getSharedPreferences("vas_device", Context.MODE_PRIVATE)
        val storedPriv = prefs.getString("priv_key", null)
        val storedPub  = prefs.getString("pub_key",  null)

        if (storedPriv != null && storedPub != null) {
            val spec    = EdDSANamedCurveTable.getByName("ed25519")
            val privKey = EdDSAPrivateKey(net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec(
                Base64.getDecoder().decode(storedPriv), spec))
            val pubKey  = EdDSAPublicKey(net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec(
                Base64.getDecoder().decode(storedPub), spec))
            return Pair(pubKey, privKey)
        }

        val spec    = EdDSANamedCurveTable.getByName("ed25519")
        val kpg     = KeyPairGenerator()
        kpg.initialize(spec, java.security.SecureRandom())
        val kp      = kpg.generateKeyPair()
        val pubKey  = kp.public  as EdDSAPublicKey
        val privKey = kp.private as EdDSAPrivateKey

        prefs.edit()
            .putString("priv_key", Base64.getEncoder().encodeToString(privKey.seed))
            .putString("pub_key",  Base64.getEncoder().encodeToString(pubKey.abyte))
            .apply()

        return Pair(pubKey, privKey)
    }

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Connect and authenticate. Blocking — call from Dispatchers.IO.
     * Idempotent: returns Success immediately if already AUTHENTICATED.
     */
    fun connect(
        context: Context,
        host: String,
        port: String,
        token: String,
        onLog: (String, Boolean) -> Unit
    ): ConnectResult {
        synchronized(this) {
            if (state == ConnectionState.AUTHENTICATED) return ConnectResult.Success
            state = ConnectionState.CONNECTING
        }

        this.onLog      = onLog
        this.savedHost  = host
        this.savedPort  = port
        this.savedToken = token

        val (pubKeyObj, priv) = getOrCreateKeypair(context)
        privKey     = priv
        pubKeyBytes = pubKeyObj.abyte
        deviceId    = sha256hex(pubKeyBytes!!)

        return doConnect()
    }

    /**
     * Close the WebSocket and reset state. Safe to call when already disconnected.
     */
    fun disconnect() {
        messageHandler = null
        closeHandler   = null
        errorHandler   = null
        synchronized(this) {
            state = ConnectionState.DISCONNECTED
            ws?.close()
            ws = null
        }
    }

    /**
     * Send a message and block until the full streaming response is received.
     * Call from Dispatchers.IO. Auto-reconnects up to 3× on backoff before giving up.
     * Returns null on error or timeout.
     */
    fun send(message: String): String? {
        synchronized(sendLock) {
            if (state != ConnectionState.AUTHENTICATED) {
                if (!attemptReconnect()) return null
            }

            val currentWs = synchronized(this) { ws }
            if (currentWs == null || !currentWs.isOpen) {
                onLog("WS no disponible para enviar", true)
                return null
            }

            val result = StringBuilder()
            val latch  = CountDownLatch(1)

            messageHandler = { msg ->
                val event = msg.optString("event")
                if (event == "agent") {
                    val p      = msg.optJSONObject("payload")
                    val stream = p?.optString("stream")
                    if (stream == "assistant") {
                        val delta = p?.optJSONObject("data")?.optString("delta") ?: ""
                        if (delta.isNotEmpty()) result.append(delta)
                    } else if (stream == "lifecycle" &&
                        p?.optJSONObject("data")?.optString("phase") == "end") {
                        onLog("← respuesta completa", false)
                        latch.countDown()
                    }
                }
            }

            closeHandler = { code, _ ->
                onLog("WS cerrado durante send: code=$code", code != 1000)
                synchronized(this@OpenClawClient) { state = ConnectionState.DISCONNECTED }
                latch.countDown()
            }

            errorHandler = { ex ->
                onLog("WS error durante send: ${ex?.message}", true)
                synchronized(this@OpenClawClient) { state = ConnectionState.DISCONNECTED }
                latch.countDown()
            }

            currentWs.send(JSONObject().apply {
                put("type", "req"); put("id", "2"); put("method", "chat.send")
                put("params", JSONObject().apply {
                    put("sessionKey", "main")
                    put("message", message)
                    put("idempotencyKey", UUID.randomUUID().toString())
                })
            }.toString())

            val completed = latch.await(30, TimeUnit.SECONDS)
            messageHandler = null
            closeHandler   = null
            errorHandler   = null

            if (!completed) {
                onLog("Timeout esperando respuesta (30 s)", true)
                return null
            }

            val res = result.toString()
            return if (res.isBlank()) null else res
        }
    }

    // ── Private helpers ──────────────────────────────────────────────

    private fun attemptReconnect(): Boolean {
        onLog("No autenticado, intentando reconexión...", false)
        val delays = longArrayOf(1_000, 2_000, 4_000)
        for (i in delays.indices) {
            onLog("Reintento ${i + 1}/3...", false)
            synchronized(this) { state = ConnectionState.CONNECTING }
            if (doConnect() is ConnectResult.Success) return true
            if (i < delays.size - 1) Thread.sleep(delays[i])
        }
        synchronized(this) { state = ConnectionState.ERROR }
        onLog("Sin conexión tras 3 intentos", true)
        return false
    }

    private fun doConnect(): ConnectResult {
        // Clear stale handlers and close any existing WS
        messageHandler = null
        closeHandler   = null
        errorHandler   = null
        synchronized(this) { ws?.close(); ws = null }

        val latch = CountDownLatch(1)
        var connectResult: ConnectResult = ConnectResult.Error("Timeout")

        onLog("Conectando a ws://$savedHost:$savedPort", false)

        val newWs = object : WebSocketClient(URI("ws://$savedHost:$savedPort")) {
            override fun onOpen(handshake: ServerHandshake?) {
                onLog("WebSocket conectado", false)
            }
            override fun onMessage(raw: String) {
                try { messageHandler?.invoke(JSONObject(raw)) }
                catch (e: Exception) { onLog("Error procesando mensaje: ${e.message}", true) }
            }
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                onLog("WS Cerrado: code=$code reason='$reason' remote=$remote", code != 1000)
                synchronized(this@OpenClawClient) {
                    if (state == ConnectionState.AUTHENTICATED) state = ConnectionState.DISCONNECTED
                }
                closeHandler?.invoke(code, reason)
            }
            override fun onError(ex: Exception?) {
                onLog("WS Error: ${ex?.javaClass?.simpleName}: ${ex?.message}", true)
                errorHandler?.invoke(ex)
            }
        }

        // Handshake message handler
        messageHandler = { msg ->
            val type  = msg.optString("type")
            val event = msg.optString("event")
            when {
                event == "connect.challenge" -> {
                    onLog("Challenge recibido", false)
                    val nonce    = msg.getJSONObject("payload").getString("nonce")
                    val signedAt = System.currentTimeMillis()
                    val payload  = "v3|$deviceId|cli|cli|operator|operator.read,operator.write,operator.admin|$signedAt|$savedToken|$nonce|android|"
                    val engine   = EdDSAEngine()
                    engine.initSign(privKey)
                    engine.update(payload.toByteArray())
                    val signature = base64url(engine.sign())

                    newWs.send(JSONObject().apply {
                        put("type", "req"); put("id", "1"); put("method", "connect")
                        put("params", JSONObject().apply {
                            put("minProtocol", 3); put("maxProtocol", 3)
                            put("client", JSONObject().apply {
                                put("id", "cli"); put("version", "1.0.0")
                                put("platform", "android"); put("mode", "cli")
                            })
                            put("role", "operator")
                            put("scopes", org.json.JSONArray(listOf(
                                "operator.read", "operator.write", "operator.admin")))
                            put("auth", JSONObject().put("token", savedToken))
                            put("device", JSONObject().apply {
                                put("id", deviceId)
                                put("publicKey", base64url(pubKeyBytes!!))
                                put("signature", signature)
                                put("signedAt", signedAt)
                                put("nonce", nonce)
                            })
                        })
                    }.toString())
                }

                type == "res" && msg.optString("id") == "1" -> {
                    val error = msg.optJSONObject("error")
                    if (error != null) {
                        val code = error.optString("code")
                        connectResult = if (code == "NOT_PAIRED") {
                            val requestId = error.optJSONObject("details")?.optString("requestId") ?: ""
                            ConnectResult.NotPaired(requestId)
                        } else {
                            onLog("Error connect: $code", true)
                            ConnectResult.Error("Error: $code")
                        }
                        synchronized(this@OpenClawClient) { state = ConnectionState.DISCONNECTED }
                    } else {
                        onLog("Conectado OK", false)
                        synchronized(this@OpenClawClient) { state = ConnectionState.AUTHENTICATED }
                        connectResult = ConnectResult.Success
                    }
                    latch.countDown()
                }
            }
        }

        closeHandler = { code, _ ->
            if ((connectResult as? ConnectResult.Error)?.message == "Timeout") {
                connectResult = ConnectResult.Error("Conexión cerrada antes de autenticar: code=$code")
            }
            synchronized(this@OpenClawClient) {
                if (state == ConnectionState.CONNECTING) state = ConnectionState.DISCONNECTED
            }
            latch.countDown()
        }

        errorHandler = { ex ->
            connectResult = ConnectResult.Error(ex?.message ?: "Error desconocido")
            synchronized(this@OpenClawClient) { state = ConnectionState.DISCONNECTED }
            latch.countDown()
        }

        synchronized(this) { ws = newWs }
        newWs.connect()
        latch.await(15, TimeUnit.SECONDS)

        // Clear — next operation will install its own handlers
        messageHandler = null
        closeHandler   = null
        errorHandler   = null

        return connectResult
    }
}