package com.automation.voiceassistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
        const val CHANNEL_ID    = "voice_assistant_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START  = "START"
        const val ACTION_STOP   = "STOP"
        private const val DEFAULT_SEND_KEYWORD = "cambio"
    }

    // ── State machine ────────────────────────────────────────────────
    enum class ServiceState { IDLE, LISTENING, PROCESSING, SPEAKING }

    private var serviceState = ServiceState.IDLE
        set(value) {
            field = value
            log("→ ${value.name}")
            when (value) {
                ServiceState.IDLE       -> updateNotification("Detenido")
                ServiceState.LISTENING  -> updateNotification("Escuchando...")
                ServiceState.PROCESSING -> updateNotification("Procesando...")
                ServiceState.SPEAKING   -> updateNotification("Respondiendo...")
            }
        }

    // ── Core components ──────────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val job   = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // ── Keyword / text accumulation ──────────────────────────────────
    private val accumulatedText = StringBuilder()
    private var sendKeyword = DEFAULT_SEND_KEYWORD

    // ── Pairing retry ────────────────────────────────────────────────
    private var isServiceActive = false   // guard against stale coroutine callbacks
    private var pairingPending  = false
    private var pairingHost     = ""
    private var pairingPort     = ""
    private var pairingToken    = ""

    // ── Lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isServiceActive = true
                startForeground(NOTIFICATION_ID, buildNotification("Conectando..."))
                val prefs   = getSharedPreferences("vas_prefs", MODE_PRIVATE)
                sendKeyword = prefs.getString("send_keyword", DEFAULT_SEND_KEYWORD) ?: DEFAULT_SEND_KEYWORD
                val host    = prefs.getString("host",  "") ?: ""
                val port    = prefs.getString("port",  "18789") ?: "18789"
                val token   = prefs.getString("token", "") ?: ""
                launchConnect(host, port, token)
            }
            ACTION_STOP -> doStop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pairingPending  = false
        isServiceActive = false
        destroyRecognizer()
        tts?.stop()
        tts?.shutdown()
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
        pairingPending  = false
        isServiceActive = false
        serviceState    = ServiceState.IDLE
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
                if (serviceState != ServiceState.LISTENING) return

                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()

                if (text.isNullOrBlank()) { startListening(); return }

                // Accumulate with a space separator between recognizer batches
                if (accumulatedText.isNotEmpty()) accumulatedText.append(" ")
                accumulatedText.append(text)

                val accumulated  = accumulatedText.toString()
                val kwIndex      = accumulated.lowercase().indexOf(sendKeyword.lowercase())

                if (kwIndex >= 0) {
                    val toSend = accumulated.substring(0, kwIndex).trim()
                    accumulatedText.clear()

                    if (toSend.isNotBlank()) {
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
                if (serviceState == ServiceState.LISTENING) {
                    mainHandler.postDelayed({ if (serviceState == ServiceState.LISTENING) startListening() }, 300)
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

    private fun startListening() {
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

    // ── TTS ──────────────────────────────────────────────────────────

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_done")
    }

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

    // ── Notification ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Voice Assistant", NotificationManager.IMPORTANCE_LOW
        )
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

    // ── Log broadcast ────────────────────────────────────────────────

    private fun log(msg: String, isError: Boolean = false) {
        val intent = Intent("com.automation.voiceassistant.LOG")
        intent.setPackage(packageName)
        intent.putExtra("message", msg)
        intent.putExtra("isError", isError)
        sendBroadcast(intent)
    }
}