package com.automation.voiceassistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.automation.voiceassistant.MainActivity
import com.automation.voiceassistant.network.OpenClawClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "voice_assistant_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isListening = false
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Escuchando..."))
                initSpeechRecognizer()
                startListening()
            }
            ACTION_STOP -> {
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: return
                updateNotification("Procesando: $text")
                sendToOpenClaw(text)
            }

            override fun onEndOfSpeech() {
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                if (error != SpeechRecognizer.ERROR_NO_MATCH) {
                    updateNotification("Error $error - reintentando...")
                }
                restartListening()
            }

            override fun onReadyForSpeech(params: Bundle?) { updateNotification("Escuchando...") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        if (isListening) return
        isListening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun restartListening() {
        android.os.Handler(mainLooper).postDelayed({ startListening() }, 500)
    }

    private fun stopListening() {
        isListening = false
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun sendToOpenClaw(text: String) {
        stopListening() // para el mic mientras procesa
        val prefs = getSharedPreferences("vas_prefs", MODE_PRIVATE)
        val host  = prefs.getString("host", "") ?: ""
        val port  = prefs.getString("port", "18789") ?: "18789"
        val token = prefs.getString("token", "") ?: ""

        log("Enviando: $text")

        scope.launch {
            val response = OpenClawClient.sendMessage(applicationContext, host, port, token, text) { msg, isError ->
                android.os.Handler(mainLooper).post { log(msg, isError) }
            }
            when {
                response == null -> {
                    log("Error de conexión", isError = true)
                    speak("Error de conexión")
                }
                response.startsWith("PAIRING:") -> {
                    val requestId = response.substringAfter(":")
                    log("Pairing: $requestId", isError = true)
                    speak("Aprueba el dispositivo en la Raspberry")
                }
                else -> {
                    log("Respuesta: $response")
                    speak(response)
                }
            }
            restartListening() // reactiva mic cuando termina
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("es", "ES")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Voice Assistant", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Assistant")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopListening()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun log(msg: String, isError: Boolean = false) {
        val intent = Intent("com.automation.voiceassistant.LOG")
        intent.setPackage(packageName)
        intent.putExtra("message", msg)
        intent.putExtra("isError", isError)
        sendBroadcast(intent)
    }
}