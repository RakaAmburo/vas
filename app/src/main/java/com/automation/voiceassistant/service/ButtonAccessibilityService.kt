package com.automation.voiceassistant.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * Intercepts VOLUME_UP and VOLUME_DOWN from the AB Shutter 3 (BT HID) globally,
 * including when the screen is off or the device is locked.
 *
 * Uses a WakeLock to ensure the CPU stays awake long enough to dispatch
 * the intent to VoiceService when the screen is off.
 *
 * The user must enable this service once in:
 *   Settings → Accessibility → Voice Assistant → Botones Shutter
 *
 * VOLUME_UP  → START VoiceService
 * VOLUME_DOWN → STOP  VoiceService
 */
class ButtonAccessibilityService : AccessibilityService() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VoiceAssistant:ButtonWakeLock"
        )
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                withWakeLock {
                    val intent = Intent(this, VoiceService::class.java).apply {
                        action = VoiceService.ACTION_START
                    }
                    ContextCompat.startForegroundService(this, intent)
                }
                true  // consume — no sube el volumen
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                withWakeLock {
                    val intent = Intent(this, VoiceService::class.java).apply {
                        action = VoiceService.ACTION_STOP
                    }
                    startService(intent)
                }
                true  // consume — no baja el volumen
            }
            else -> false
        }
    }

    /**
     * Adquiere un WakeLock por 2 segundos — suficiente para que el intent
     * llegue a VoiceService aunque la pantalla esté apagada.
     */
    private inline fun withWakeLock(block: () -> Unit) {
        wakeLock?.acquire(2_000L)
        try {
            block()
        } finally {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
}

