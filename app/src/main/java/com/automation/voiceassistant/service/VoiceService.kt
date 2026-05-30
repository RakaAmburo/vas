package com.automation.voiceassistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.automation.voiceassistant.MainActivity
import com.automation.voiceassistant.network.OpenClawClient
import com.automation.voiceassistant.network.SoqueteClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID        = "voice_assistant_channel"
        const val NOTIFICATION_ID   = 1
        const val ACTION_START      = "START"
        const val ACTION_STOP       = "STOP"
        const val ACTION_KILL       = "KILL"
        const val ACTION_TOGGLE_MIC = "TOGGLE_MIC"
        /** Broadcast enviado a MainActivity cuando el estado activo cambia */
        const val ACTION_STATE      = "com.automation.voiceassistant.SERVICE_STATE"
        const val EXTRA_IS_ACTIVE   = "is_active"
        const val EXTRA_MIC_PAUSED  = "mic_paused"
        private const val DEFAULT_SEND_KEYWORD = "cambio"
        private const val DEFAULT_SOQUETE_KEYWORD = "zoquete"
        private const val PROCESSING_TIMEOUT_MS = 30_000L
    }

    // ── State machine ────────────────────────────────────────────────
    enum class ServiceState { IDLE, LISTENING, PROCESSING, SPEAKING, MIC_PAUSED }

    private var serviceState = ServiceState.IDLE
        set(value) {
            field = value
            log("→ ${value.name}")
            when (value) {
                ServiceState.IDLE       -> { updateNotification(""); updatePlaybackState(false) }
                ServiceState.LISTENING  -> { updateNotification("Escuchando..."); updatePlaybackState(true) }
                ServiceState.PROCESSING -> { updateNotification("Procesando..."); updatePlaybackState(true) }
                ServiceState.SPEAKING   -> { updateNotification("Respondiendo..."); updatePlaybackState(true) }
                ServiceState.MIC_PAUSED -> { updateNotification("Micrófono en pausa"); updatePlaybackState(true) }
            }
        }

    // ── Core components ──────────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val job   = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // ── TTS buffer — respuestas que llegan mientras el mic está escuchando ──
    private val ttsBuffer = ArrayDeque<String>()

    // ── Bluetooth headset ────────────────────────────────────────────
    private var mediaSession: MediaSessionCompat? = null



    /** Actualiza el PlaybackState del MediaSession.
     *  SIEMPRE usamos STATE_PLAYING — nunca STATE_PAUSED ni STATE_STOPPED.
     *  Razón: Android 10+ despacha media buttons (pantalla apagada) solo a la sesión
     *  con STATE_PLAYING más reciente. Si ponemos STATE_PAUSED en IDLE, un reproductor
     *  de música en STATE_PLAYING nos roba todos los eventos del auricular.
     *  Al permanecer en STATE_PLAYING nuestro servicio siempre recibe el botón primero. */
    private fun updatePlaybackState(@Suppress("UNUSED_PARAMETER") playing: Boolean) {
        val state = PlaybackStateCompat.STATE_PLAYING
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }

    // ── Keyword / text accumulation ──────────────────────────────────
    private val accumulatedText = StringBuilder()
    private var sendKeyword    = DEFAULT_SEND_KEYWORD
    private var soqueteKeyword = DEFAULT_SOQUETE_KEYWORD

    // ── Pairing retry ────────────────────────────────────────────────
    private var isServiceActive = false
        set(value) {
            field = value
            broadcastActive(value)
        }
    private var pairingPending  = false
    private var pairingHost     = ""
    private var pairingPort     = ""
    private var pairingToken    = ""

    // ── Lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        createNotificationChannel()
        initMediaSession()
        startForeground(NOTIFICATION_ID, buildNotification(""))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle headset button events routed here by HeadsetButtonReceiver.
        // Solo KEYCODE_HEADSETHOOK / KEYCODE_MEDIA_PLAY_PAUSE llegan por ACTION_MEDIA_BUTTON.
        // KEYCODE_VOLUME_UP/DOWN van por el sistema de audio — nunca llegan aquí.
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (event?.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> handleHeadsetButton()
                }
            }
            return START_STICKY
        }

        when (intent?.action) {
            ACTION_START -> {
                isServiceActive = true
                updateNotification("Conectando...")
                val prefs   = getSharedPreferences("vas_prefs", MODE_PRIVATE)
                sendKeyword    = prefs.getString("send_keyword", DEFAULT_SEND_KEYWORD) ?: DEFAULT_SEND_KEYWORD
                soqueteKeyword = prefs.getString("soquete_keyword", DEFAULT_SOQUETE_KEYWORD) ?: DEFAULT_SOQUETE_KEYWORD
                val host         = prefs.getString("host",  "") ?: ""
                val port         = prefs.getString("port",  "18789") ?: "18789"
                val token        = prefs.getString("token", "") ?: ""
                val soquetePort  = prefs.getString("soquete_port",  "18690") ?: "18690"
                val soqueteToken = prefs.getString("soquete_token", "") ?: ""
                launchConnect(host, port, token, soquetePort, soqueteToken)
            }
            ACTION_STOP       -> doStop()
            ACTION_KILL       -> doKill()
            ACTION_TOGGLE_MIC -> doToggleMic()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pairingPending  = false
        isServiceActive = false
        destroyRecognizer()
        tts?.stop()
        tts?.shutdown()
        mediaSession?.isActive = false
        mediaSession?.release()
        scope.launch { OpenClawClient.disconnect(); SoqueteClient.disconnect() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Connection ───────────────────────────────────────────────────

    private fun launchConnect(
        host: String, port: String, token: String,
        soquetePort: String = "", soqueteToken: String = ""
    ) {
        scope.launch {
            val result = OpenClawClient.connect(applicationContext, host, port, token) { msg, isError ->
                mainHandler.post { log(msg, isError) }
            }
            mainHandler.post {
                if (!isServiceActive) return@post
                when (result) {
                    is OpenClawClient.ConnectResult.Success -> {
                        if (soquetePort.isNotEmpty()) {
                            scope.launch {
                                SoqueteClient.connect(host, soquetePort, soqueteToken) { msg, isError ->
                                    mainHandler.post { log(msg, isError) }
                                }
                                // Register push handler for server-initiated notifications
                                SoqueteClient.pushHandler = { text ->
                                    mainHandler.post { deliverResponse(text, "[Soquete]") }
                                }
                            }
                        }
                        serviceState = ServiceState.LISTENING
                        initSpeechRecognizer()
                        startListening()
                    }
                    is OpenClawClient.ConnectResult.NotPaired -> {
                        log("Pairing requerido: ${result.requestId}", true)
                        pairingHost    = host
                        pairingPort    = port
                        pairingToken   = token
                        pairingPending = true
                        serviceState   = ServiceState.SPEAKING
                        speak("Aprueba el dispositivo en la Raspberry")
                    }
                    is OpenClawClient.ConnectResult.Error -> {
                        log("Error de conexión: ${result.message}", true)
                        updateNotification("Error de conexión")
                    }
                }
            }
        }
    }

    private fun doStop() {
        // NO detiene el servicio — solo desconecta y vuelve a IDLE.
        // El servicio sigue corriendo para mantener la MediaSession activa
        // y poder recibir el botón BT aunque la pantalla esté apagada.
        pairingPending  = false
        isServiceActive = false
        serviceState    = ServiceState.IDLE
        destroyRecognizer()
        accumulatedText.clear()
        ttsBuffer.clear()
        scope.launch { OpenClawClient.disconnect(); SoqueteClient.disconnect() }
        updateNotification("Listo — presiona el botón para iniciar")
    }

    /** Toggle mic — pausa y reanuda el reconocimiento de voz.
     *  El mic es INDEPENDIENTE de los WebSockets:
     *  - Pausar solo detiene el recognizer; los WS siguen activos.
     *  - Si llega una respuesta WS mientras el mic escucha (LISTENING) → se bufferiza.
     *  - Si llega mientras está en pausa (MIC_PAUSED) → se reproduce directamente.
     *  - Al reanudar desde MIC_PAUSED → vuelve a escuchar (el buffer ya se habrá reproducido). */
    private fun doToggleMic() {
        if (!isServiceActive) return
        when (serviceState) {
            ServiceState.MIC_PAUSED -> {
                // Reanudar — beep + delay antes de abrir el mic
                serviceState = ServiceState.LISTENING
                broadcastMicPaused(false)
                playMicOnBeep()
                mainHandler.postDelayed({
                    if (serviceState == ServiceState.LISTENING) {
                        initSpeechRecognizer()
                        startListeningNow()
                    }
                }, 350)
            }
            ServiceState.IDLE -> { /* no activo, ignorar */ }
            else -> {
                // LISTENING, PROCESSING o SPEAKING → solo para el recognizer, NO el WS
                destroyRecognizer()
                accumulatedText.clear()
                serviceState = ServiceState.MIC_PAUSED
                broadcastMicPaused(true)
                playPauseBeep()
            }
        }
    }

    /** Detiene el servicio completamente. Solo se llama desde ACTION_KILL. */
    private fun doKill() {
        pairingPending  = false
        isServiceActive = false
        destroyRecognizer()
        accumulatedText.clear()
        ttsBuffer.clear()
        scope.launch { OpenClawClient.disconnect(); SoqueteClient.disconnect() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Entrega una respuesta recibida del WS.
     *  - Si el mic está escuchando (LISTENING): bufferiza para no interrumpir.
     *  - Si el mic está en pausa (MIC_PAUSED): reproduce directamente por TTS.
     *  - En cualquier otro caso (PROCESSING, SPEAKING): reproduce normal. */
    private fun deliverResponse(response: String, tag: String) {
        log("$tag Respuesta: $response")
        when (serviceState) {
            ServiceState.LISTENING -> {
                // Mic ocupado escuchando — bufferizar para después
                ttsBuffer.addLast(response)
                log("$tag Respuesta bufferizada (mic escuchando)")
            }
            ServiceState.MIC_PAUSED -> {
                // Mic pausado — reproducir directamente, quedarse en MIC_PAUSED al terminar
                serviceState = ServiceState.SPEAKING
                speak(response)
            }
            else -> {
                serviceState = ServiceState.SPEAKING
                speak(response)
            }
        }
    }

    // ── Speech recognizer ────────────────────────────────────────────

    private fun initSpeechRecognizer() {
        destroyRecognizer()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onResults(results: Bundle?) {
                if (serviceState != ServiceState.LISTENING) return

                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()

                if (text.isNullOrBlank()) { startListening(); return }

                if (accumulatedText.isNotEmpty()) accumulatedText.append(" ")
                accumulatedText.append(text)

                val accumulated = accumulatedText.toString()
                val kwIndex     = accumulated.lowercase().indexOf(sendKeyword.lowercase())
                val soqueteIdx  = accumulated.lowercase().indexOf(soqueteKeyword.lowercase())

                if (kwIndex >= 0) {
                    val toSend = accumulated.substring(0, kwIndex).trim()
                    accumulatedText.clear()

                    if (toSend.isNotBlank()) {
                        playMicOffBeep()
                        log("[OpenClaw] Enviando: $toSend")
                        serviceState = ServiceState.PROCESSING
                        destroyRecognizer()
                        scope.launch {
                            val timeoutRunnable = Runnable {
                                if (serviceState == ServiceState.PROCESSING) {
                                    log("[OpenClaw] Timeout — sin respuesta en 30s", true)
                                    sendTelegramTimeout(toSend)
                                    if (serviceState == ServiceState.PROCESSING) {
                                        serviceState = ServiceState.LISTENING
                                        initSpeechRecognizer()
                                        startListening()
                                    }
                                }
                            }
                            mainHandler.postDelayed(timeoutRunnable, PROCESSING_TIMEOUT_MS)
                            val response = OpenClawClient.send(toSend)
                            mainHandler.post {
                                mainHandler.removeCallbacks(timeoutRunnable)
                                if (!isServiceActive) return@post
                                if (response == null) {
                                    log("[OpenClaw] Sin respuesta o error de conexión", true)
                                    serviceState = ServiceState.LISTENING
                                    initSpeechRecognizer()
                                    startListening()
                                } else {
                                    deliverResponse(response, "[OpenClaw]")
                                }
                            }
                        }
                    } else {
                        startListening()
                    }
                } else if (soqueteIdx >= 0) {
                    val toSend = accumulated.substring(0, soqueteIdx).trim()
                    accumulatedText.clear()

                    if (toSend.isNotBlank()) {
                        playMicOffBeep()
                        log("[Soquete] Enviando: $toSend")
                        serviceState = ServiceState.PROCESSING
                        destroyRecognizer()
                        scope.launch {
                            val timeoutRunnable = Runnable {
                                if (serviceState == ServiceState.PROCESSING) {
                                    log("[Soquete] Timeout — sin respuesta en 30s", true)
                                    sendTelegramTimeout(toSend)
                                    if (serviceState == ServiceState.PROCESSING) {
                                        serviceState = ServiceState.LISTENING
                                        initSpeechRecognizer()
                                        startListening()
                                    }
                                }
                            }
                            mainHandler.postDelayed(timeoutRunnable, PROCESSING_TIMEOUT_MS)
                            val response = SoqueteClient.send(toSend)
                            mainHandler.post {
                                mainHandler.removeCallbacks(timeoutRunnable)
                                if (!isServiceActive) return@post
                                if (response == null) {
                                    log("[Soquete] Sin respuesta o error de conexión", true)
                                    serviceState = ServiceState.LISTENING
                                    initSpeechRecognizer()
                                    startListening()
                                } else {
                                    deliverResponse(response, "[Soquete]")
                                }
                            }
                        }
                    } else {
                        startListening()
                    }
                } else {
                    startListening()
                }
            }

            override fun onError(error: Int) {
                if (serviceState == ServiceState.LISTENING) {
                    mainHandler.postDelayed({
                        if (serviceState == ServiceState.LISTENING) startListening()
                    }, 300)
                }
            }

            override fun onEndOfSpeech() {}
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    /** Emite beep y abre el mic con delay de 350ms para que el beep no sea captado. */
    private fun startListening() {
        if (serviceState != ServiceState.LISTENING) return
        playMicOnBeep()
        mainHandler.postDelayed({
            if (serviceState == ServiceState.LISTENING) startListeningNow()
        }, 350)
    }

    /** Abre el mic directamente sin beep ni delay. */
    private fun startListeningNow() {
        if (serviceState != ServiceState.LISTENING) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun destroyRecognizer() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    /** Beep corto — mic activado. */
    private fun playMicOnBeep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            mainHandler.postDelayed({ tg.release() }, 300)
        } catch (_: Exception) {}
    }

    /** Beep doble — mic desactivado (keyword detectada). */
    private fun playMicOffBeep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
            mainHandler.postDelayed({ tg.release() }, 300)
        } catch (_: Exception) {}
    }

    /** Beep de pausa — pulso largo. */
    private fun playPauseBeep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tg.startTone(ToneGenerator.TONE_CDMA_PIP, 300)
            mainHandler.postDelayed({ tg.release() }, 500)
        } catch (_: Exception) {}
    }

    /** Envía aviso por Telegram cuando se agota el timeout de PROCESSING. */
    private fun sendTelegramTimeout(originalMessage: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val msg = "[VoiceAssistant] Procesando tu mensaje (timeout 30s): \"\""
                Runtime.getRuntime().exec(arrayOf(
                    "python", "/home/pablo/repos/misc-tools/telegram.py",
                    "--message", msg
                ))
            } catch (_: Exception) {}
        }
    }

    // ── TTS ──────────────────────────────────────────────────────────

    private fun speak(text: String) {
        val clean = TtsTextCleaner.clean(text)
        if (clean.isBlank()) {
            mainHandler.post { onTtsDone() }
            return
        }
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "tts_done")
    }

    /** Lógica post-TTS centralizada. */
    private fun onTtsDone() {
        if (!isServiceActive) return
        when (serviceState) {
            ServiceState.SPEAKING -> {
                if (pairingPending) {
                    pairingPending = false
                    mainHandler.postDelayed({
                        if (isServiceActive) launchConnect(pairingHost, pairingPort, pairingToken)
                    }, 5_000)
                } else {
                    // Volver a escuchar
                    serviceState = ServiceState.LISTENING
                    initSpeechRecognizer()
                    startListening()
                }
            }
            ServiceState.MIC_PAUSED -> {
                // TTS terminó pero el mic está en pausa — no reactivar mic
                // Reproducir siguiente del buffer si hay
                if (ttsBuffer.isNotEmpty()) {
                    speak(ttsBuffer.removeFirst())
                }
                // si no hay más, quedarse en MIC_PAUSED
            }
            else -> { /* no hacer nada */ }
        }
    }

    /**
     * La lógica de limpieza de texto fue extraída a [TtsTextCleaner.clean].
     *
     * Cambios respecto a la versión anterior:
     * - Bug fix: la expresión para código inline usaba "$1" sin grupo de captura,
     *   lo que causaba una excepción en tiempo de ejecución y eliminaba el texto
     *   entre backticks en lugar de conservarlo. Corregido: `([^`\n]*)` → "$1".
     * - Se añade un paso extra para eliminar backticks sueltos que sobreviven al
     *   paso de código inline.
     * - El guion `-` fue removido del conjunto de símbolos eliminados para no
     *   quebrar palabras compuestas.
     * - La función fue movida a [TtsTextCleaner] para permitir tests unitarios
     *   sin dependencias de Android (35 tests, 0 fallos).
     */

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("es", "ES")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    mainHandler.post { onTtsDone() }
                }

                override fun onError(utteranceId: String?) {
                    mainHandler.post { onTtsDone() }
                }
            })
        }
    }

    // ── Bluetooth headset ────────────────────────────────────────────

    private fun initMediaSession() {
        val receiverComponent = ComponentName(this, com.automation.voiceassistant.receiver.HeadsetButtonReceiver::class.java)
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            component = receiverComponent
        }
        val mediaButtonPendingIntent = PendingIntent.getBroadcast(
            this, 0, mediaButtonIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSessionCompat(this, "VoiceAssistantSession", receiverComponent, mediaButtonPendingIntent).apply {
            @Suppress("DEPRECATION")
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setMediaButtonReceiver(mediaButtonPendingIntent)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    val event = mediaButtonEvent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (event?.action == KeyEvent.ACTION_DOWN) {
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_HEADSETHOOK,
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> handleHeadsetButton()
                        }
                    }
                    return true
                }
            })
            isActive = true
        }
        updatePlaybackState(playing = true)
    }

    private fun handleHeadsetButton() {
        toggleService()
    }

    private fun toggleService() {
        if (!isServiceActive) {
            isServiceActive = true
            updateNotification("Conectando...")
            val prefs        = getSharedPreferences("vas_prefs", MODE_PRIVATE)
            sendKeyword      = prefs.getString("send_keyword", DEFAULT_SEND_KEYWORD) ?: DEFAULT_SEND_KEYWORD
            soqueteKeyword   = prefs.getString("soquete_keyword", DEFAULT_SOQUETE_KEYWORD) ?: DEFAULT_SOQUETE_KEYWORD
            val host         = prefs.getString("host",  "") ?: ""
            val port         = prefs.getString("port",  "18789") ?: "18789"
            val token        = prefs.getString("token", "") ?: ""
            val soquetePort  = prefs.getString("soquete_port",  "18690") ?: "18690"
            val soqueteToken = prefs.getString("soquete_token", "") ?: ""
            launchConnect(host, port, token, soquetePort, soqueteToken)
        } else {
            doStop()
        }
    }

    // ── Notification ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Voice Assistant", NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Assistant")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ── State broadcast ──────────────────────────────────────────────

    private fun broadcastActive(active: Boolean) {
        val intent = Intent(ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_ACTIVE, active)
        }
        sendBroadcast(intent)
    }

    private fun broadcastMicPaused(paused: Boolean) {
        val intent = Intent(ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_ACTIVE, true)
            putExtra(EXTRA_MIC_PAUSED, paused)
        }
        sendBroadcast(intent)
    }

    // ── Log broadcast ────────────────────────────────────────────────

    private fun log(msg: String, isError: Boolean = false) {
        val intent = Intent("com.automation.voiceassistant.LOG")
        intent.setPackage(packageName)
        intent.putExtra("message", msg)
        intent.putExtra("isError", isError)
        sendBroadcast(intent)
    }
}