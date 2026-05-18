package com.automation.voiceassistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.automation.voiceassistant.service.VoiceService

/**
 * Receives headset button events from the system when VoiceService is not running.
 * Routes the event to VoiceService so it can apply double-tap detection and toggle.
 */
class HeadsetButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN) return
        // Solo HEADSETHOOK y MEDIA_PLAY_PAUSE llegan por el framework de media buttons.
        // VOLUME_UP/DOWN van por el sistema de audio — nunca llegan aquí.
        if (event.keyCode != KeyEvent.KEYCODE_HEADSETHOOK &&
            event.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return

        // Forward to VoiceService — it will start if not running (START_STICKY)
        val serviceIntent = Intent(context, VoiceService::class.java).apply {
            action = Intent.ACTION_MEDIA_BUTTON
            putExtras(intent)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}

