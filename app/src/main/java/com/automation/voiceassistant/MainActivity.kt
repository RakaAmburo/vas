package com.automation.voiceassistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.automation.voiceassistant.service.VoiceService
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private val logs = mutableStateListOf<Pair<String, Boolean>>()

    // Estado activo accesible desde dispatchKeyEvent (AB Shutter 3) — observable por Compose
    private val isServiceActive = mutableStateOf(false)

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.automation.voiceassistant.LOG" -> {
                    val msg = intent.getStringExtra("message") ?: return
                    val isError = intent.getBooleanExtra("isError", false)
                    logs.add(0, Pair(msg, isError))
                    if (logs.size > 100) logs.removeLastOrNull()
                }
                VoiceService.ACTION_STATE -> {
                    // Sincroniza el botón visual con el estado real del servicio
                    isServiceActive.value = intent.getBooleanExtra(VoiceService.EXTRA_IS_ACTIVE, false)
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) startVoiceService()
        // isServiceActive se actualiza vía broadcast desde VoiceService
    }

    // QR scanner launcher usando zxing-android-embedded
    private var onQrScanned: ((String) -> Unit)? = null
    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanned ->
            onQrScanned?.invoke(scanned)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mantiene la pantalla encendida mientras la app esté abierta
        // → AB Shutter 3 (HID) funciona garantizado; el usuario baja el brillo al mínimo
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("vas_prefs", MODE_PRIVATE)
        registerReceiver(
            logReceiver,
            IntentFilter().apply {
                addAction("com.automation.voiceassistant.LOG")
                addAction(VoiceService.ACTION_STATE)
            },
            RECEIVER_NOT_EXPORTED
        )
        // Arranca el servicio en IDLE para que la MediaSession siempre esté activa
        // y el botón BT funcione con pantalla apagada desde el primer uso
        ContextCompat.startForegroundService(
            this,
            Intent(this, VoiceService::class.java)
        )
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                MainScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(logReceiver)
    }

    @Composable
    fun MainScreen() {
        val isActive by isServiceActive
        var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
        var port by remember { mutableStateOf(prefs.getString("port", "18789") ?: "18789") }
        var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
        var showConfig by remember { mutableStateOf(false) }
        var tokenVisible by remember { mutableStateOf(false) }

        // Callback para cuando el QR es escaneado — actualiza el state y prefs
        onQrScanned = { scanned ->
            token = scanned
            prefs.edit().putString("token", scanned).apply()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Voice Assistant", style = MaterialTheme.typography.headlineMedium)


            Button(
                onClick = {
                    if (isActive) {
                        stopVoiceService()
                        isServiceActive.value = false
                    } else {
                        if (checkPermissions()) {
                            startVoiceService()
                            isServiceActive.value = true
                        }
                    }
                },
                modifier = Modifier.size(120.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isActive) "STOP" else "START", style = MaterialTheme.typography.titleLarge)
            }

            TextButton(onClick = { showConfig = !showConfig }) {
                Text(if (showConfig) "Ocultar config" else "Configuración")
            }

            if (showConfig) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it; prefs.edit().putString("host", it).apply() },
                        label = { Text("IP Tailscale") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it; prefs.edit().putString("port", it).apply() },
                        label = { Text("Puerto") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Token con botón de QR
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it; prefs.edit().putString("token", it).apply() },
                            label = { Text("Token OpenClaw") },
                            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                    Icon(
                                        imageVector = if (tokenVisible) Icons.Default.LockOpen else Icons.Default.Lock,
                                        contentDescription = if (tokenVisible) "Ocultar token" else "Mostrar token",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val options = ScanOptions().apply {
                                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    setPrompt("Escanea el QR del token")
                                    setBeepEnabled(true)
                                    setBarcodeImageEnabled(false)
                                }
                                qrLauncher.launch(options)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCode,
                                contentDescription = "Escanear QR",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Historial", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { logs.clear() }) {
                    Text("Limpiar", fontSize = 12.sp)
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                state = rememberLazyListState()
            ) {
                items(logs) { (msg, isError) ->
                    Text(
                        text = msg,
                        color = if (isError) Color.Red else MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Divider(thickness = 0.5.dp)
                }
            }
        }
    }

    // ── AB Shutter 3 (BT HID) ───────────────────────────────────────
    // VOLUME_UP → toggle start/stop
    // VOLUME_DOWN → reservado para función futura
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (isServiceActive.value) {
                    stopVoiceService()
                    isServiceActive.value = false
                } else {
                    if (checkPermissions()) {
                        startVoiceService()
                        isServiceActive.value = true
                    }
                }
                return true  // consume: no sube el volumen
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // Reservado — no hace nada por ahora
                return true  // consume: no baja el volumen
            }
        }
        return super.onKeyDown(keyCode, event)
    }


    private fun checkPermissions(): Boolean {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (missing.isEmpty()) true
        else { permissionLauncher.launch(missing.toTypedArray()); false }
    }

    private fun startVoiceService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, VoiceService::class.java).apply { action = VoiceService.ACTION_START }
        )
    }

    private fun stopVoiceService() {
        startService(
            Intent(this, VoiceService::class.java).apply { action = VoiceService.ACTION_STOP }
        )
    }
}