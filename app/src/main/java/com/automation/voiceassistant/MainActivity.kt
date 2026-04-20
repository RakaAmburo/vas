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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.automation.voiceassistant.service.VoiceService

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private val logs = mutableStateListOf<Pair<String, Boolean>>() // text, isError

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra("message") ?: return
            val isError = intent.getBooleanExtra("isError", false)
            logs.add(0, Pair(msg, isError))
            if (logs.size > 100) logs.removeLastOrNull()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) startVoiceService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("vas_prefs", MODE_PRIVATE)
        registerReceiver(logReceiver, IntentFilter("com.automation.voiceassistant.LOG"),
            RECEIVER_NOT_EXPORTED)
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
        var isActive by remember { mutableStateOf(false) }
        var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
        var port by remember { mutableStateOf(prefs.getString("port", "18789") ?: "18789") }
        var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
        var showConfig by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Voice Assistant", style = MaterialTheme.typography.headlineMedium)

            Button(
                onClick = {
                    if (isActive) { stopVoiceService(); isActive = false }
                    else { if (checkPermissions()) { startVoiceService(); isActive = true } }
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
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(value = host, onValueChange = { host = it; prefs.edit().putString("host", it).apply() },
                        label = { Text("IP Tailscale") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = port, onValueChange = { port = it; prefs.edit().putString("port", it).apply() },
                        label = { Text("Puerto") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = token, onValueChange = { token = it; prefs.edit().putString("token", it).apply() },
                        label = { Text("Token OpenClaw") }, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                }
            }

            // Historial
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Historial", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { logs.clear() }) { Text("Limpiar", fontSize = 12.sp) }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                state = rememberLazyListState(),
                reverseLayout = false
            ) {
                items(logs) { (msg, isError) ->
                    Text(
                        text = msg,
                        color = if (isError) Color.Red else MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Divider(thickness = 0.5.dp)
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        return if (missing.isEmpty()) true else { permissionLauncher.launch(missing.toTypedArray()); false }
    }

    private fun startVoiceService() {
        ContextCompat.startForegroundService(this, Intent(this, VoiceService::class.java).apply { action = VoiceService.ACTION_START })
    }

    private fun stopVoiceService() {
        startService(Intent(this, VoiceService::class.java).apply { action = VoiceService.ACTION_STOP })
    }
}