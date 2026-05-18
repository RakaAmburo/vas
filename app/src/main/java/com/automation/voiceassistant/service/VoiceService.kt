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
        /** Broadcast enviado a MainActivity cuando el estado activo cambia */
        const val ACTION_STATE      = "com.automation.voiceassistant.SERVICE_STATE"
        const val EXTRA_IS_ACTIVE   = "is_active"
        private const val DEFAULT_SEND_KEYWORD = "cambio"
    }

    // ── State machine ────────────────────────────────────────────────
    enum class ServiceState { IDLE, LISTENING, PROCESSING, SPEAKING }

    private var serviceState = ServiceState.IDLE
        set(value) {
            field = value
            log("→ ${value.name}")
            when (value) {
                ServiceState.IDLE       -> { updateNotification(""); updatePlaybackState(false) }
                ServiceState.LISTENING  -> { updateNotification("Escuchando..."); updatePlaybackState(true) }
                ServiceState.PROCESSING -> { updateNotification("Procesando..."); updatePlaybackState(true) }
                ServiceState.SPEAKING   -> { updateNotification("Respondiendo..."); updatePlaybackState(true) }
            }
        }

    // ── Core components ──────────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val job   = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // ── Audio (beep suppression) ─────────────────────────────────────
    private lateinit var audioManager: AudioManager
    private var savedSystemVol = -1
    private var savedRingVol   = -1
    private var savedMusicVol  = -1

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
    private var sendKeyword = DEFAULT_SEND_KEYWORD

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
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
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
                sendKeyword = prefs.getString("send_keyword", DEFAULT_SEND_KEYWORD) ?: DEFAULT_SEND_KEYWORD
                val host    = prefs.getString("host",  "") ?: ""
                val port    = prefs.getString("port",  "18789") ?: "18789"
                val token   = prefs.getString("token", "") ?: ""
                launchConnect(host, port, token)
            }
            ACTION_STOP -> doStop()
            ACTION_KILL -> doKill()
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
        scope.launch { OpenClawClient.disconnect() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Connection ───────────────────────────────────────────────────

    private fun launchConnect(host: String, port: String, token: String) {
        scope.launch {
            val result = OpenClawClient.connect(applicationContext, host, port, token) { msg, isError ->
                mainHandler.post { log(msg, isError) }
            }
            mainHandler.post {
                if (!isServiceActive) return@post
                when (result) {
                    is OpenClawClient.ConnectResult.Success -> {
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
                        // Retry is triggered from TTS onDone when pairingPending == true
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
        scope.launch { OpenClawClient.disconnect() }
        updateNotification("Listo — presiona el botón para iniciar")
    }

    /** Detiene el servicio completamente. Solo se llama desde ACTION_KILL. */
    private fun doKill() {
        pairingPending  = false
        isServiceActive = false
        destroyRecognizer()
        accumulatedText.clear()
        scope.launch { OpenClawClient.disconnect() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Speech recognizer ────────────────────────────────────────────

    private fun initSpeechRecognizer() {
        destroyRecognizer()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onResults(results: Bundle?) {
                // Restore all muted streams — both start and end beeps have now fired
                restoreVolumes()

                if (serviceState != ServiceState.LISTENING) return

                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()

                if (text.isNullOrBlank()) { startListening(); return }

                // Accumulate with a space separator between recognizer batches
                if (accumulatedText.isNotEmpty()) accumulatedText.append(" ")
                accumulatedText.append(text)

                val accumulated = accumulatedText.toString()
                val kwIndex     = accumulated.lowercase().indexOf(sendKeyword.lowercase())

                if (kwIndex >= 0) {
                    val toSend = accumulated.substring(0, kwIndex).trim()
                    accumulatedText.clear()

                    if (toSend.isNotBlank()) {
                        // Short confirmation beep so the user knows the keyword was detected
                        playKeywordBeep()
                        log("Enviando: $toSend")
                        serviceState = ServiceState.PROCESSING
                        destroyRecognizer()
                        scope.launch {
                            val response = OpenClawClient.send(toSend)
                            mainHandler.post {
                                if (!isServiceActive) return@post
                                if (response == null) {
                                    log("Sin respuesta o error de conexión", true)
                                    serviceState = ServiceState.LISTENING
                                    initSpeechRecognizer()
                                    startListening()
                                } else {
                                    log("Respuesta: $response")
                                    serviceState = ServiceState.SPEAKING
                                    speak(response)
                                }
                            }
                        }
                    } else {
                        // Keyword with no preceding text — restart silently
                        startListening()
                    }
                } else {
                    // No keyword yet — keep accumulating; restart recognizer immediately (no beep)
                    startListening()
                }
            }

            override fun onError(error: Int) {
                // Restore all muted streams
                restoreVolumes()
                if (serviceState == ServiceState.LISTENING) {
                    mainHandler.postDelayed({
                        if (serviceState == ServiceState.LISTENING) startListening()
                    }, 300)
                }
            }

            override fun onEndOfSpeech() {}  // STREAM_MUSIC already muted from startListening()

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        if (serviceState != ServiceState.LISTENING) return
        // Mute STREAM_SYSTEM and STREAM_RING to suppress the "start listening" beep
        muteRecognizerStreams()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    /** Silences the streams the SpeechRecognizer uses for its start AND end beeps. */
    private fun muteRecognizerStreams() {
        try {
            if (savedSystemVol < 0) savedSystemVol = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
            if (savedRingVol   < 0) savedRingVol   = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            if (savedMusicVol  < 0) savedMusicVol  = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_RING,   0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,  0, 0)
        } catch (_: Exception) {}
    }

    /** Restores all muted streams to their pre-mute levels. */
    private fun restoreVolumes() {
        try {
            if (savedSystemVol >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, savedSystemVol, 0)
                savedSystemVol = -1
            }
            if (savedRingVol >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_RING, savedRingVol, 0)
                savedRingVol = -1
            }
            if (savedMusicVol >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVol, 0)
                savedMusicVol = -1
            }
            // Also ensure STREAM_MUSIC adjust-mute is cleared (belt + suspenders)
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: Exception) {}
    }

    private fun destroyRecognizer() {
        restoreVolumes()
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    /** Short confirmation beep on STREAM_NOTIFICATION when the send keyword is detected. */
    private fun playKeywordBeep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
            mainHandler.postDelayed({ tg.release() }, 400)
        } catch (_: Exception) {}
    }

    // ── TTS ──────────────────────────────────────────────────────────

    private fun speak(text: String) {
        val clean = TtsTextCleaner.clean(text)
        if (clean.isBlank()) {
            // Nothing left to say after cleaning — skip straight back to listening
            mainHandler.post {
                if (isServiceActive && serviceState == ServiceState.SPEAKING) {
                    serviceState = ServiceState.LISTENING
                    initSpeechRecognizer()
                    startListening()
                }
            }
            return
        }
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "tts_done")
    }

    /**
     * La lógica de limpieza de texto fue extraída a [TtsTextCleaner.clean].
     *
     * Cambios respecto a la versión anterior:
     * - Bug fix: la expresión para código inline usaba `"$1"` sin grupo de captura,
     *   lo que causaba una excepción en tiempo de ejecución y eliminaba el texto
     *   entre backticks en lugar de conservarlo. Corregido: `` `([^`\n]*)` `` → `"$1"`.
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
                    mainHandler.post {
                        if (!isServiceActive || serviceState != ServiceState.SPEAKING) return@post
                        if (pairingPending) {
                            pairingPending = false
                            // Retry connect after 5 s to give the user time to approve pairing
                            mainHandler.postDelayed({
                                if (isServiceActive) launchConnect(pairingHost, pairingPort, pairingToken)
                            }, 5_000)
                        } else {
                            serviceState = ServiceState.LISTENING
                            initSpeechRecognizer()
                            startListening()
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                    mainHandler.post {
                        if (!isServiceActive || serviceState != ServiceState.SPEAKING) return@post
                        serviceState = ServiceState.LISTENING
                        initSpeechRecognizer()
                        startListening()
                    }
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
                    // Solo KEYCODE_HEADSETHOOK y KEYCODE_MEDIA_PLAY_PAUSE llegan aquí.
                    // KEYCODE_VOLUME_UP/DOWN NO llegan nunca por MediaSession — van por
                    // el sistema de audio y no se pueden interceptar con pantalla apagada.
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
        // Establecemos STATE_PLAYING inmediatamente — ver comentario en updatePlaybackState().
        updatePlaybackState(playing = true)
    }

    private fun handleHeadsetButton() {
        toggleService()
    }

    private fun toggleService() {
        if (!isServiceActive) {
            isServiceActive = true
            updateNotification("Conectando...")
            val prefs   = getSharedPreferences("vas_prefs", MODE_PRIVATE)
            sendKeyword = prefs.getString("send_keyword", DEFAULT_SEND_KEYWORD) ?: DEFAULT_SEND_KEYWORD
            val host    = prefs.getString("host",  "") ?: ""
            val port    = prefs.getString("port",  "18789") ?: "18789"
            val token   = prefs.getString("token", "") ?: ""
            launchConnect(host, port, token)
        } else {
            doStop()
        }
    }

    // ── Notification ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Voice Assistant", NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)          // sin punto en el ícono de la app
            setSound(null, null)         // sin sonido
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

    /** Notifica a MainActivity si el servicio está activo (conectando/escuchando) o en IDLE. */
    private fun broadcastActive(active: Boolean) {
        val intent = Intent(ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_ACTIVE, active)
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