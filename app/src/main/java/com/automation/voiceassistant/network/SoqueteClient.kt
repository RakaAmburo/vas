package com.automation.voiceassistant.network

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Cliente WebSocket para Soquete.
 * Protocolo de handshake:
 *   1. Al conectar → enviar {"key": "<token>"}
 *   2. Servidor responde {"msg": "authenticated"}
 *   3. Enviar mensajes con {"msg": "<texto>"} → responde {"msg": "<respuesta>"}
 *
 * Push notifications:
 *   - Mensajes espontáneos del servidor (sin send() en curso) → pushHandler
 */
object SoqueteClient {

    enum class ConnectionState { DISCONNECTED, CONNECTING, AUTHENTICATED, ERROR }

    sealed class ConnectResult {
        object Success : ConnectResult()
        data class Error(val message: String) : ConnectResult()
    }

    @Volatile var state: ConnectionState = ConnectionState.DISCONNECTED
        private set

    private var ws: WebSocketClient? = null
    private val sendLock = Any()

    private var onLog: (String, Boolean) -> Unit = { _, _ -> }
    private var savedHost  = ""
    private var savedPort  = ""
    private var savedToken = ""

    @Volatile private var messageHandler: ((JSONObject) -> Unit)? = null
    @Volatile private var closeHandler:   ((Int, String?) -> Unit)? = null
    @Volatile private var errorHandler:   ((Exception?) -> Unit)?  = null

    /** Invocado cuando llega un mensaje push del servidor (fuera de un send()). */
    @Volatile var pushHandler: ((String) -> Unit)? = null

    fun connect(
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
        return doConnect()
    }

    fun disconnect() {
        messageHandler = null
        closeHandler   = null
        errorHandler   = null
        pushHandler    = null
        synchronized(this) {
            state = ConnectionState.DISCONNECTED
            ws?.close()
            ws = null
        }
    }

    /**
     * Envía un mensaje y espera la respuesta (máx 30 s).
     * Auto-reconecta hasta 3× si no está autenticado.
     */
    fun send(message: String): String? {
        synchronized(sendLock) {
            if (state != ConnectionState.AUTHENTICATED) {
                if (!attemptReconnect()) return null
            }

            val currentWs = synchronized(this) { ws }
            if (currentWs == null || !currentWs.isOpen) {
                onLog("[Soquete] WS no disponible para enviar", true)
                return null
            }

            val result = StringBuilder()
            val latch  = CountDownLatch(1)

            messageHandler = { msg ->
                val text = msg.optString("msg").takeIf { it.isNotEmpty() }
                if (text != null) {
                    result.append(text)
                    onLog("[Soquete] ← respuesta recibida", false)
                    latch.countDown()
                } else if (msg.has("error")) {
                    onLog("[Soquete] Error del servidor: ${msg.optString("error")}", true)
                    latch.countDown()
                }
            }

            closeHandler = { code, _ ->
                onLog("[Soquete] WS cerrado durante send: code=$code", code != 1000)
                synchronized(this@SoqueteClient) { state = ConnectionState.DISCONNECTED }
                latch.countDown()
            }

            errorHandler = { ex ->
                onLog("[Soquete] WS error durante send: ${ex?.message}", true)
                synchronized(this@SoqueteClient) { state = ConnectionState.DISCONNECTED }
                latch.countDown()
            }

            currentWs.send(JSONObject().put("msg", message).toString())

            val completed = latch.await(30, TimeUnit.SECONDS)
            messageHandler = null
            closeHandler   = null
            errorHandler   = null

            if (!completed) {
                onLog("[Soquete] Timeout esperando respuesta (30 s)", true)
                return null
            }

            val res = result.toString()
            return if (res.isBlank()) null else res
        }
    }

    private fun attemptReconnect(): Boolean {
        onLog("[Soquete] No autenticado, intentando reconexión...", false)
        val delays = longArrayOf(1_000, 2_000, 4_000)
        for (i in delays.indices) {
            onLog("[Soquete] Reintento ${i + 1}/3...", false)
            synchronized(this) { state = ConnectionState.CONNECTING }
            if (doConnect() is ConnectResult.Success) return true
            if (i < delays.size - 1) Thread.sleep(delays[i])
        }
        synchronized(this) { state = ConnectionState.ERROR }
        onLog("[Soquete] Sin conexión tras 3 intentos", true)
        return false
    }

    private fun doConnect(): ConnectResult {
        messageHandler = null
        closeHandler   = null
        errorHandler   = null
        synchronized(this) { ws?.close(); ws = null }

        val latch = CountDownLatch(1)
        var connectResult: ConnectResult = ConnectResult.Error("Timeout")

        onLog("[Soquete] Conectando a ws://$savedHost:$savedPort", false)

        val newWs = object : WebSocketClient(URI("ws://$savedHost:$savedPort")) {
            override fun onOpen(handshake: ServerHandshake?) {
                onLog("[Soquete] Conectado, autenticando...", false)
                // Paso 1: enviar clave
                send(JSONObject().put("key", savedToken).toString())
            }
            override fun onMessage(raw: String) {
                try {
                    val json = JSONObject(raw)
                    if (messageHandler != null) {
                        // send() en curso o handshake — handler normal
                        messageHandler?.invoke(json)
                    } else {
                        // Mensaje push espontáneo del servidor
                        val text = json.optString("msg").takeIf { it.isNotEmpty() }
                        if (text != null) {
                            onLog("[Soquete] Push recibido: $text", false)
                            pushHandler?.invoke(text)
                        }
                    }
                } catch (e: Exception) { onLog("[Soquete] Error procesando mensaje: ${e.message}", true) }
            }
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                onLog("[Soquete] WS Cerrado: code=$code reason='$reason'", code != 1000)
                synchronized(this@SoqueteClient) {
                    if (state != ConnectionState.DISCONNECTED) state = ConnectionState.DISCONNECTED
                }
                closeHandler?.invoke(code, reason)
                latch.countDown()
            }
            override fun onError(ex: Exception?) {
                onLog("[Soquete] WS Error: ${ex?.javaClass?.simpleName}: ${ex?.message}", true)
                connectResult = ConnectResult.Error(ex?.message ?: "Error desconocido")
                synchronized(this@SoqueteClient) { state = ConnectionState.DISCONNECTED }
                errorHandler?.invoke(ex)
                latch.countDown()
            }
        }

        // Handler del handshake: espera {"msg": "authenticated"}
        messageHandler = { msg ->
            val msgText = msg.optString("msg")
            when {
                msgText == "authenticated" -> {
                    onLog("[Soquete] Autenticado OK", false)
                    synchronized(this@SoqueteClient) { state = ConnectionState.AUTHENTICATED }
                    connectResult = ConnectResult.Success
                    latch.countDown()
                }
                msg.has("error") -> {
                    onLog("[Soquete] Auth rechazada: ${msg.optString("error")}", true)
                    connectResult = ConnectResult.Error("Auth rechazada: ${msg.optString("error")}")
                    synchronized(this@SoqueteClient) { state = ConnectionState.DISCONNECTED }
                    latch.countDown()
                }
                else -> {
                    onLog("[Soquete] Respuesta inesperada en handshake: $msgText", true)
                    connectResult = ConnectResult.Error("Respuesta inesperada: $msgText")
                    synchronized(this@SoqueteClient) { state = ConnectionState.DISCONNECTED }
                    latch.countDown()
                }
            }
        }

        closeHandler = { code, _ ->
            if ((connectResult as? ConnectResult.Error)?.message == "Timeout") {
                connectResult = ConnectResult.Error("Conexión cerrada antes de autenticar: code=$code")
            }
            synchronized(this@SoqueteClient) {
                if (state == ConnectionState.CONNECTING) state = ConnectionState.DISCONNECTED
            }
            latch.countDown()
        }

        errorHandler = { ex ->
            connectResult = ConnectResult.Error(ex?.message ?: "Error desconocido")
            synchronized(this@SoqueteClient) { state = ConnectionState.DISCONNECTED }
            latch.countDown()
        }

        synchronized(this) { ws = newWs }
        newWs.connect()
        latch.await(15, TimeUnit.SECONDS)

        messageHandler = null
        closeHandler   = null
        errorHandler   = null

        return connectResult
    }
}