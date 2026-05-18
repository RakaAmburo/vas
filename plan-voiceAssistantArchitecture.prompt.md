# VoiceAssistant — Architecture & Roadmap

---

> ## 🚫 REGLA DE ORO PARA EL ASISTENTE DE IA
>
> **PROHIBIDO modificar código existente que no haya sido explícitamente pedido por el usuario.**
>
> Esto incluye:
> - NO reescribir funciones que ya funcionan aunque parezcan "mejorables"
> - NO eliminar ni modificar comentarios ya existentes en el código
> - NO condensar ni simplificar código que el usuario ya aprobó
> - NO "aprovechar" una edición para limpiar o refactorizar otras partes del archivo
>
> Al usar herramientas de edición: usar `// ...existing code...` para TODO el código
> que no sea parte del cambio solicitado. Si hay duda, NO tocar.
>
> Motivo: el usuario pierde trabajo y tiempo cuando se revierten cambios aprobados sin aviso.

---

---

## ⛔ Regla de oro para el asistente de código

**NUNCA modificar código existente que no haya sido explícitamente pedido.**

- Si una tarea requiere editar un archivo, solo tocar las líneas exactas que corresponden al cambio solicitado.
- Está prohibido reformatear, simplificar, eliminar comentarios, renombrar variables o alterar funciones que ya funcionan, aunque parezcan "mejorables".
- Si el tool de edición aplica un cambio más amplio del esperado, **revertirlo inmediatamente** antes de continuar.
- En caso de duda sobre el alcance del cambio, preguntar antes de editar.

El motivo: cambios no solicitados en código funcional pueden romper comportamiento existente sin que el usuario lo note, generando pérdida de tiempo y trabajo.

---


## Estado actual (baseline)

- `OpenClawClient` (object singleton) — conecta WS, autentica y envía en un solo bloque
  bloqueante (`CountDownLatch`). Se conecta y desconecta en cada mensaje.
- `VoiceService` — servicio foreground Android, usa `SpeechRecognizer` nativo +
  `TextToSpeech` nativo. Llama a `OpenClawClient.sendMessage()` por coroutine en cada voz.
  El recognizer nativo tiene un timeout de ~60 segundos tras el cual para y reinicia, lo que
  genera un pitido molesto y rompe el flujo conversacional.
- `MainActivity` — UI Compose. Arranca/para `VoiceService` vía Intent. Recibe logs vía
  BroadcastReceiver. Almacena configuración (host, puerto, token) en `SharedPreferences`.

---

## Flujo conversacional objetivo

El sistema debe comportarse como una conversación continua:

1. El usuario pulsa **START** → el servicio conecta al WS y activa el micrófono.
2. El usuario habla libremente, sin límite de tiempo.
3. Al terminar su intervención dice una **palabra clave de envío** (ej.: *"cambio"*).
4. El sistema acumula todo el texto reconocido hasta esa palabra clave, lo envía al WS y
   espera la respuesta.
5. La respuesta se reproduce por TTS.
6. Al terminar el TTS, el micrófono vuelve a activarse automáticamente → vuelta al paso 2.
7. El usuario pulsa **STOP** → el servicio desconecta el WS y para.

Mientras se espera la respuesta del WS (`isProcessing = true`) el micrófono está desactivado,
por lo que **no existe condición de carrera en el escenario de un solo WS**.

---

## Fase 1 — Conexión persistente en OpenClawClient

### Objetivo
Separar el ciclo de vida de la conexión WS del envío de mensajes, y mantener la conexión
abierta durante toda la sesión en lugar de reconectar en cada mensaje.

### Cambios en `OpenClawClient.kt`

**Estado interno (campos privados):**
- `ws: WebSocketClient?`
- `state: ConnectionState` (enum: `DISCONNECTED`, `CONNECTING`, `AUTHENTICATED`, `ERROR`)
- `onLog: (String, Boolean) -> Unit` — fijado en `connect()`, reutilizado en `send()`
- `privKey`, `pubKey`, `deviceId` — calculados una vez en `connect()`
- `host`, `port`, `token`, `context` — guardados en `connect()` para usarlos en reconexión
  automática sin que el caller tenga que volver a pasarlos
- `sendMutex: Mutex` — garantiza que solo hay un `send()` en vuelo a la vez

**Tabla de transiciones de estado:**

| Estado actual | Evento | Estado siguiente |
|---|---|---|
| `DISCONNECTED` | `connect()` llamado | `CONNECTING` |
| `CONNECTING` | handshake OK | `AUTHENTICATED` |
| `CONNECTING` | error / timeout / NOT_PAIRED | `DISCONNECTED` |
| `AUTHENTICATED` | `disconnect()` llamado | `DISCONNECTED` |
| `AUTHENTICATED` | WS caído detectado en `send()` | `CONNECTING` (intento reconexión) |
| `CONNECTING` (reintento) | agota reintentos | `ERROR` |
| `ERROR` | `connect()` llamado explícitamente | `CONNECTING` |
| `AUTHENTICATED` | `connect()` llamado (ya conectado) | no-op, retorna `Success` |

**API pública:**

```kotlin
// Llamar siempre desde Dispatchers.IO — bloquea hasta completar el handshake
fun connect(
    context: Context,
    host: String,
    port: String,
    token: String,
    onLog: (String, Boolean) -> Unit
): ConnectResult

sealed class ConnectResult {
    object Success : ConnectResult()
    data class NotPaired(val requestId: String) : ConnectResult()
    data class Error(val message: String) : ConnectResult()
}
```
- Realiza handshake completo: `connect.challenge` → `connect req` → `res id=1`
- Guarda `onLog` y credenciales como campos privados
- Bloquea con `CountDownLatch`, timeout 15 s
- Si `state == AUTHENTICATED` al llamar, retorna `Success` inmediatamente (idempotente)

```kotlin
fun disconnect()
```
- Cierra WS con código 1000, resetea `state` a `DISCONNECTED`
- Seguro llamarlo aunque ya esté desconectado (no-op)

```kotlin
// Llamar desde Dispatchers.IO — bloquea hasta recibir la respuesta completa del stream
fun send(message: String): String?
```
- Adquiere `sendMutex` → garantiza un solo mensaje en vuelo
- Si `state != AUTHENTICATED`: intenta reconexión automática (ver abajo) antes de enviar
- Envía `chat.send`, espera evento `lifecycle end` con `CountDownLatch`, timeout 30 s
- Retorna el texto acumulado del stream, o `null` si error/timeout
- Nota: `send()` es una función bloqueante (no `suspend`) llamada desde `Dispatchers.IO`,
  igual que `connect()`, para mantener consistencia en el modelo de threading

**Reconexión automática (interna a `send()`):**
- Si `state != AUTHENTICATED`, reintenta `connect()` hasta 3 veces
- Backoff exponencial: 1 s, 2 s, 4 s entre intentos
- Si agota reintentos: loguea `"Sin conexión tras 3 intentos"` con `isError=true`,
  cambia `state` a `ERROR`, retorna `null`
- El caller (`VoiceService`) recibe `null` y reactiva el recognizer para que el usuario
  pueda seguir usando la voz mientras el sistema está en `ERROR`

**Thread safety:**
- `state` es `@Volatile` para lecturas baratas
- Modificaciones de `state` y acceso a `ws` dentro de bloques `@Synchronized` en el propio `object`
- `sendMutex` (kotlinx `Mutex`) serializa las llamadas a `send()`

### Cambios en `VoiceService.kt`

**Estado del servicio — máquina de estados explícita:**

Los flags booleanos sueltos (`isListening`, `isProcessing`) se reemplazan por un único enum
`ServiceState` que hace el ciclo completamente observable y fácil de loguear/testear:

```
IDLE ──(connect OK)──► LISTENING
                           │
                    (keyword detectada
                     + texto acumulado)
                           │
                           ▼
                       PROCESSING ──(send OK, respuesta recibida)──► SPEAKING
                           │                                             │
                    (send null /                               (TTS onDone)
                     error WS)                                           │
                           │                                             ▼
                           └──────────────────────────────────────► LISTENING
```

- `IDLE` → servicio arrancado, esperando resultado de `connect()`
- `LISTENING` → recognizer activo, acumulando texto en `StringBuilder`
- `PROCESSING` → keyword detectada, `send()` en curso en `Dispatchers.IO`, micro off
- `SPEAKING` → TTS reproduciendo respuesta, micro off

Cada transición loguea el cambio de estado con `log("Estado: $state")`, lo que hace el
flujo completamente visible en el historial de `MainActivity`.

**`onCreate()`:**
- Inicializa TTS
- `state = IDLE`

**`ACTION_START` en `onStartCommand()`:**
1. `startForeground()` con notificación "Conectando..."
2. Lee prefs: `host`, `port`, `token`
3. Lanza coroutine en `Dispatchers.IO` → llama `OpenClawClient.connect(...)`
4. Publica resultado en `mainHandler`:
   - `Success` → `state = LISTENING`, actualiza notificación a "Escuchando",
     `initSpeechRecognizer()` + `startListening()`
   - `NotPaired(requestId)` → loguea + `speak("Aprueba el dispositivo en la Raspberry")`;
     al terminar TTS reintenta `connect()` automáticamente (loop cada 5 s hasta éxito o STOP)
   - `Error(msg)` → loguea con `isError=true`; notificación "Error de conexión"

**Ciclo principal (gestionado por el recognizer):**

```
onResults(text):
  acumular texto en StringBuilder (+ espacio separador)
  si texto contiene keyword:
    extraer texto sin keyword
    si texto.isNotBlank():
      state = PROCESSING
      coroutine(IO) { 
        val response = OpenClawClient.send(accumulatedText)
        mainHandler {
          if response == null:
            log("Error", isError=true); state = LISTENING; reiniciar recognizer
          else:
            state = SPEAKING; speak(response)
        }
      }
      limpiar StringBuilder
    si no (keyword sin texto previo):
      ignorar, reiniciar recognizer
  si no contiene keyword:
    reiniciar recognizer inmediatamente (sin pitido)

onDone (TTS):
  state = LISTENING
  initSpeechRecognizer() + startListening()

onError (recognizer):
  si state == LISTENING: reiniciar recognizer (la conexión sigue viva)
```

**`ACTION_STOP` en `onStartCommand()`:**
1. `state = IDLE`
2. `destroyRecognizer()`
3. `OpenClawClient.disconnect()`
4. `stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf()`

**`onDestroy()`:**
- Llama `OpenClawClient.disconnect()` además de destruir recognizer y TTS
  (cubre el caso en que Android mata el proceso sin pasar por `ACTION_STOP`)


**Keyword trigger (reemplaza timeout del recognizer nativo):**
- Se acumula texto en un `StringBuilder` por turno de escucha
- Al detectar la palabra clave de envío (ej.: *"cambio"*, configurable en prefs como `send_keyword`)
  en el resultado de STT, se elimina la keyword del texto y se llama `sendToOpenClaw(accumulatedText)`
- Si no hay keyword, el texto se acumula y el recognizer reinicia inmediatamente sin pitido
- El recognizer ya no depende del timeout de 60 s para reiniciar
- La keyword se busca de forma insensible a mayúsculas/acentos para evitar fallos de reconocimiento
  (ej.: "Cambio", "cambio", "CAMBIO" son equivalentes)

> ⚠️ **Edge case conocido:** Android `SpeechRecognizer` fuerza un resultado cada vez que
> detecta silencio (~2-5 s), no cuando el usuario lo decide. Si el usuario hace una pausa
> natural en medio de una frase, el recognizer cierra ese batch y abre uno nuevo. El texto
> acumulado entre batches puede tener una pequeña discontinuidad (palabra cortada o espacio
> extra). En la práctica es tolerable para el uso conversacional previsto, pero hay que
> asegurarse de añadir un espacio entre fragmentos al acumular en el `StringBuilder`.

### Cambios en `MainActivity.kt`

- `startVoiceService()` — sin cambios
- `stopVoiceService()` — sin cambios
- **No hay cambios en `MainActivity` para Fase 1**
- Añadir campo `send_keyword` en la sección de configuración (Fase 1.5, opcional)

---

## Feature — Control por auricular Bluetooth (doble toque)

### Objetivo
Arrancar y parar el servicio con un doble toque en el botón del auricular Bluetooth,
sin necesidad de tocar el teléfono.

### Cómo funciona en Android
Los auriculares Bluetooth envían pulsaciones del botón físico como eventos de teclado de
media a través del sistema Android. Los keycodes relevantes son:
- `KeyEvent.KEYCODE_HEADSETHOOK` — botón central de auriculares con cable y BT clásico
- `KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE` — botón play/pause en auriculares BT modernos
  (A2DP / HFP)

Android enruta estos eventos al componente que tenga prioridad de audio activa. La forma
recomendada de capturarlos es registrar un `MediaSession` activo.

### Diseño

**En `VoiceService`** (el lugar natural, ya que es un servicio foreground de audio):

```kotlin
private lateinit var mediaSession: MediaSessionCompat

// En onCreate():
mediaSession = MediaSessionCompat(this, "VoiceAssistantSession").apply {
    setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)
    setCallback(object : MediaSessionCompat.Callback() {
        override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
            val event = mediaButtonEvent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (event?.action == KeyEvent.ACTION_DOWN) handleHeadsetButton()
            return true
        }
    })
    isActive = true
}

// En onDestroy():
mediaSession.isActive = false
mediaSession.release()
```

**Detección de doble toque:**
```kotlin
private var lastButtonPressMs = 0L
private val DOUBLE_TAP_WINDOW_MS = 400L

private fun handleHeadsetButton() {
    val now = System.currentTimeMillis()
    if (now - lastButtonPressMs < DOUBLE_TAP_WINDOW_MS) {
        // doble toque detectado
        lastButtonPressMs = 0L
        toggleService()   // START si IDLE, STOP si cualquier otro estado
    } else {
        lastButtonPressMs = now
        // primer toque — esperar posible segundo
    }
}
```

**`toggleService()`:**
- Si `state == IDLE` → inicia conexión y escucha (equivalente a pulsar START en la UI)
- Si `state != IDLE` → desconecta y para (equivalente a pulsar STOP en la UI)

### Consideraciones
- **Compatibilidad**: funciona con la mayoría de auriculares BT (HFP/HSP). Algunos
  auriculares BT LE (BLE Audio) pueden no enviar `KEYCODE_HEADSETHOOK`; en ese caso
  envían `KEYCODE_MEDIA_PLAY_PAUSE`. Manejar ambos en `onMediaButtonEvent`.
- **Single tap libre**: el toque simple no hace nada (solo marca el timestamp). Se puede
  asignar otra acción futura si se desea (ej.: repetir última respuesta TTS).
- **Conflicto con apps de música**: el `MediaSession` activo captura los eventos con
  prioridad mientras el servicio está corriendo. Al parar el servicio, `isActive = false`
  devuelve el control a otras apps.
- **No requiere permisos adicionales**: los eventos de media button están disponibles
  sin permisos extra a partir de Android 5+.

### Archivos afectados
- `VoiceService.kt` — añadir `MediaSessionCompat`, lógica de doble toque y `toggleService()`
- **No requiere cambios en `MainActivity.kt`**



## Fase 2 — Soporte multi-WebSocket

### Objetivo y motivación

OpenClaw solo soporta el patrón **pregunta → esperar respuesta** (request/response síncrono
desde el punto de vista del cliente). Esto impide recibir eventos asíncronos iniciados por
el servidor (ej.: notificaciones, resultados de procesos largos desacoplados de la pregunta).

Para cubrir esto se añade un **segundo WebSocket independiente** ("Centurion" u otro sistema
configurable) que puede:
- Recibir eventos asíncronos del servidor en cualquier momento.
- Enviar mensajes a un sistema distinto de OpenClaw, activado por una **keyword de enrutado**
  diferente (ej.: *"centurion"*, configurable).

### Condición de carrera multi-WS y resolución

Se pueden dar dos situaciones de concurrencia:

**A) Dos respuestas WS llegan casi simultáneamente:**
1. **Cola TTS serializada** — todas las respuestas pasan por una única cola FIFO antes de
   llegar a `TextToSpeech.speak()`. No se superponen.
2. **Prefijo de fuente** — cada conexión tiene un `speakPrefix: String` configurable
   (ej.: `"Galadriel dice: "` para OpenClaw, `"Centurion dice: "` para el segundo WS).
   El usuario distingue el origen por la voz.

**B) Llega un mensaje async mientras el usuario está hablando (STT activo):**
- `VoiceService` expone un flag `isListening: Boolean` observable.
- El listener de mensajes entrantes (`onMessage`) comprueba ese flag:
  - Si `isListening == true` → el mensaje se encola en la cola TTS pendiente sin
    interrumpir al usuario. En cuanto el STT termine su ciclo (ya sea por keyword o por
    pausa natural) y el micrófono se desactive, la cola TTS se drena normalmente.
  - Si `isListening == false` → se encola y reproduce de inmediato.
- En ambos casos el mensaje se reproduce con el `speakPrefix` correspondiente, de modo que
  el usuario sabe de qué fuente proviene antes de escuchar el contenido.
- Android `SpeechRecognizer` expone `onBeginningOfSpeech()` / `onEndOfSpeech()` que permiten
  detectar exactamente cuándo hay voz activa. El flag `isListening` se pone a `true` en
  `onBeginningOfSpeech()` y a `false` en `onEndOfSpeech()` / `onError()` / `onResults()`.

### Diseño

**Refactorizar `OpenClawClient` de `object` a `class` → `OpenClawConnection`:**

```kotlin
class OpenClawConnection(
    val id: String,                        // ej: "openclaw", "centurion"
    val mode: ConnectionMode,              // SEND_RECEIVE, RECEIVE_ONLY, SEND_ONLY
    val speakPrefix: String = "",          // prefijo TTS para respuestas de esta conexión
    val sendKeyword: String = "cambio",    // keyword que activa el envío por esta conexión
)
```

**Autenticación por conexión:**
- `"openclaw"` — usa el handshake actual (challenge/EdDSA signature)
- `"centurion"` u otros — protocolo de auth propio a definir al implementar;
  la clase base expone un método `authenticate()` sobreescribible

**Manager singleton `WebSocketManager`:**

```kotlin
object WebSocketManager {
    fun register(connection: OpenClawConnection)
    fun connect(id: String, context: Context, onLog: (String, Boolean) -> Unit): ConnectResult
    fun disconnect(id: String)
    fun disconnectAll()
    fun send(id: String, message: String): String?
    fun setMessageListener(id: String, listener: (String) -> Unit) // para RECEIVE_ONLY / BIDIRECTIONAL
}
```

> ⚠️ **Gap pendiente — protocolo de fin de respuesta por conexión:** El `send()` actual
> sabe que la respuesta terminó cuando recibe el evento `lifecycle end`, que es específico
> del protocolo OpenClaw. Para conexiones no-OpenClaw (ej.: Centurion), la señal de
> "respuesta completa" será diferente (campo JSON, close del WS, timeout, etc.).
> Al implementar Fase 2 habrá que añadir en `OpenClawConnection` un mecanismo de detección
> de fin de respuesta configurable por conexión, por ejemplo:
> ```kotlin
> val responseCompletionStrategy: ResponseCompletionStrategy
> // LIFECYCLE_END (OpenClaw), WS_CLOSE, TIMEOUT, CUSTOM(predicate)
> ```
> Esto no bloquea el diseño actual pero debe resolverse antes de implementar `send()` en
> conexiones no-OpenClaw.

**Enrutado por keyword en `VoiceService`:**
- Al detectar keyword en el texto acumulado, se busca qué `OpenClawConnection` tiene esa
  `sendKeyword` registrada y se llama `WebSocketManager.send(connection.id, text)`
- Si ninguna conexión tiene esa keyword → loguea advertencia, no envía

**Listener de mensajes entrantes asíncronos:**
- Conexiones con `mode = RECEIVE_ONLY` o `BIDIRECTIONAL` registran un `onMessage` listener
- Al recibir un mensaje, `VoiceService` lo encola en la cola TTS con el `speakPrefix` de
  esa conexión

### Cambios en `VoiceService.kt` (Fase 2)
- Usa `WebSocketManager` en lugar de `OpenClawClient` directamente
- `ACTION_START` conecta todas las conexiones registradas en `WebSocketManager`
- `ACTION_STOP` llama `WebSocketManager.disconnectAll()`
- `onDestroy()` también llama `WebSocketManager.disconnectAll()`

---

## Fase 3 — Desacoplamiento del motor de voz

### Objetivo
Permitir sustituir `SpeechRecognizer` nativo y `TextToSpeech` nativo de Android por otras
implementaciones (Whisper, servicios cloud, etc.) sin tocar `VoiceService` ni el resto de la app.

### Interfaz `SpeechEngine`

```kotlin
interface SpeechEngine {
    fun init(locale: Locale, onReady: () -> Unit)
    fun startListening(
        onSpeechStart: () -> Unit,          // equivalente a onBeginningOfSpeech
        onSpeechEnd: () -> Unit,            // equivalente a onEndOfSpeech
        onPartialResult: (String) -> Unit,  // texto parcial mientras se habla (feedback visual)
        onResult: (String) -> Unit,         // texto final reconocido
        onError: (Int) -> Unit
    )
    fun stopListening()
    fun destroy()
}
```
- `onSpeechStart` / `onSpeechEnd` son los callbacks que `VoiceService` usa para actualizar
  el flag `isListening` y gestionar la cola TTS async. Cada engine es responsable de
  dispararlos con el mecanismo que tenga disponible.
- `onPartialResult` es opcional (puede ser no-op) pero permite feedback visual futuro.
- `locale` parametrizado para soporte multiidioma.

**`AndroidSpeechEngine`:**
- `onSpeechStart` ← `RecognitionListener.onBeginningOfSpeech()`
- `onSpeechEnd` ← `RecognitionListener.onEndOfSpeech()`
- `onPartialResult` ← `RecognitionListener.onPartialResults()`
- `onResult` ← `RecognitionListener.onResults()`
- Comportamiento nativo, sin dependencias adicionales.

**`WhisperSpeechEngine` (futuro):**
- Whisper (local vía ONNX/llama.cpp o API remota) no tiene streaming en tiempo real ni
  callbacks de actividad de voz. Trabaja sobre chunks de audio pregrabados.
- Para disparar `onSpeechStart` / `onSpeechEnd` necesita un **VAD (Voice Activity Detection)**
  externo que analice el stream de audio del micrófono en tiempo real:
  - **Silero VAD** (recomendado) — modelo ligero ONNX, funciona on-device en Android,
    buena precisión, latencia ~10 ms por chunk.
  - **WebRTC VAD** — disponible como librería C/JNI, más simple pero menos preciso.
  - **Energy-based VAD** — umbral de RMS, opción de fallback si las anteriores son
    inviables; menos robusto en entornos ruidosos.
- Flujo `WhisperSpeechEngine`:
  1. VAD detecta inicio de voz → dispara `onSpeechStart`
  2. Se graba audio en buffer hasta que VAD detecta silencio
  3. VAD detecta fin de voz → dispara `onSpeechEnd`
  4. Buffer de audio se envía a Whisper (local o API)
  5. Transcripción recibida → dispara `onResult`
- La elección de VAD es interna al engine; `VoiceService` no sabe ni le importa cuál se usa.

> ⚠️ **`onPartialResult` no disponible en Whisper:** Whisper no hace transcripción parcial
> nativa. Simularlo requeriría chunked streaming significativamente más complejo. Para
> `WhisperSpeechEngine`, `onPartialResult` será siempre no-op. El feedback visual en tiempo
> real no estará disponible con este engine.

> ⚠️ **Estrategia de implementación recomendada:** Arrancar `WhisperSpeechEngine` usando
> la **API remota** (OpenAI Whisper API o compatible). Es más simple, valida el concepto
> end-to-end y no requiere dependencias nativas complejas. Dejar Whisper local (vía
> `whisper.cpp` JNI) como opción futura. Tabla de complejidad:

| Opción | Complejidad | Dependencia extra | Latencia | Offline |
|---|---|---|---|---|
| Whisper API remota | Baja | Ninguna (HTTP) | ~1-3 s (red) | ❌ |
| Whisper local (ONNX) | Media-Alta | ONNX Runtime (~20 MB) + modelo (~75-300 MB) | ~1-3 s on-device | ✅ |
| Whisper local (whisper.cpp JNI) | Alta | Build nativo JNI, CMake | ~0.5-2 s | ✅ |
| **faster-whisper en RPi 5** | Media | Servidor Python en RPi (~50 líneas) | ~1.5-3 s (LAN/internet) | ✅ (en casa) |
| **Deepgram Nova-3 API** | Baja | API key + OkHttp WS | ~300 ms | ❌ |

**`DeepgramSpeechEngine` — análisis y limitaciones:**
- Streaming WebSocket en tiempo real: transcripciones parciales llegando continuamente, sin VAD propio.
- Sin bips, sin timeouts, sin dependencia de Google.
- La lógica de keyword (`"cambio"`) en `VoiceService` no cambia.
- **Problema de costo con el patrón de uso real:** Deepgram cobra por tiempo de audio enviado,
  incluyendo silencios. El flujo previsto (activar → pensar → hablar → "cambio") puede incluir
  largos períodos de silencio que se cobran igual. VAD mitigaría esto (~30 líneas con RMS sobre
  `AudioRecord`) pero añade complejidad.
- **Conclusión:** Deepgram es ideal si el usuario habla de forma continua y fluida. No es óptimo
  si hay pausas largas de reflexión entre activar y hablar. Para ese patrón, `faster-whisper`
  en la RPi (audio enviado solo al detectar fin de turno) es más económico y predecible.

**Selección de engine:**
- En `VoiceService.onCreate()` según pref `speech_engine` ("android", "whisper", "deepgram", …)
- La pref `speech_engine` se añadirá a la sección de configuración de `MainActivity` cuando
  se implemente cualquier engine alternativo.

### Interfaz `TtsEngine`

```kotlin
interface TtsEngine {
    fun init(locale: Locale, onReady: () -> Unit)
    fun enqueue(text: String, onDone: () -> Unit)  // cola interna, no superpone
    fun stop()
    fun shutdown()
}
```
- `enqueue()` reemplaza `speak()` y gestiona la cola internamente
- `AndroidTtsEngine` — wrapper del `TextToSpeech` nativo actual

### Cambios en `VoiceService.kt` (Fase 3)
- Eliminar referencias directas a `SpeechRecognizer` y `TextToSpeech`
- Campos `speechEngine: SpeechEngine` y `ttsEngine: TtsEngine` inyectados en `onCreate()`
- `VoiceService` queda como orquestador puro: gestiona estados, keywords, WS y engines
  sin acoplarse a ninguna implementación concreta

---

## Orden de implementación sugerido

| Fase | Archivos clave | Prioridad |
|------|---|---|
| 1 — Conexión persistente + keyword trigger | `OpenClawClient.kt`, `VoiceService.kt` | Alta (ahora) |
| 1.5 — Keyword configurable en UI | `MainActivity.kt` | Baja (opcional) |
| 2 — Multi-WebSocket + cola TTS | `WebSocketManager.kt` (nuevo), `OpenClawConnection.kt` (nuevo), `VoiceService.kt` | Media |
| 3 — Motor de voz desacoplado | `SpeechEngine.kt`, `TtsEngine.kt`, `AndroidSpeechEngine.kt`, `AndroidTtsEngine.kt`, `VoiceService.kt` | Media-Baja |

---

## Decisiones técnicas cerradas

| Decisión | Elección | Motivo |
|---|---|---|
| `onLog` callback | Fijado en `connect()`, reutilizado en `send()` | Ligado a la sesión, no al mensaje |
| Paradigma de threading | Funciones bloqueantes (`CountDownLatch`) llamadas desde `Dispatchers.IO` | Consistencia; evita mezclar suspend y blocking |
| Thread safety `state`/`ws` | `@Volatile` + bloques `@Synchronized` | `@Volatile` cubre lecturas; `@Synchronized` cubre operaciones compuestas |
| Serialización de `send()` | `kotlinx.coroutines.sync.Mutex` | Evita dos mensajes en vuelo simultáneos |
| Reconexión automática | Hasta 3 reintentos, backoff 1/2/4 s, luego `ERROR` | Recuperación rápida sin bloquear indefinidamente |
| Recuperación de estado `ERROR` | `VoiceService` reactiva recognizer; usuario puede seguir hablando | No fuerza STOP/START manual |
| Post-pairing retry | Loop con delay 5 s hasta `Success` o `ACTION_STOP` | Experiencia fluida sin intervención del usuario |
| Condición de carrera multi-WS | Cola TTS serializada + prefijo de fuente por voz | Simple, audible, sin complejidad adicional |
| Locale en engines | Parámetro en `init()` de las interfaces | Preparado para multiidioma sin romper la interfaz |

---

## Viabilidad y riesgos por fase

| Fase | Viabilidad | Riesgo / Observación |
|---|---|---|
| 1 — Conexión persistente | ✅ Alta | Refactor directo sobre código funcional |
| 1 — Keyword trigger | ✅ Alta | Edge case de pausas naturales → gaps en texto acumulado (tolerable; añadir espacio entre batches) |
| 2 — Multi-WebSocket / cola TTS | ✅ Alta | Implementación limpia; flag `isListening` necesita `@Volatile` |
| 2 — Protocolo fin de respuesta no-OpenClaw | 🟡 Media | Gap de diseño pendiente; bloquea `send()` en conexiones no-OpenClaw hasta que se defina `ResponseCompletionStrategy` |
| 3 — `AndroidSpeechEngine` | ✅ Alta | Wrapper trivial |
| 3 — `WhisperSpeechEngine` API remota | ✅ Alta | Simple; requiere internet; latencia ~1-3 s |
| 3 — `WhisperSpeechEngine` local ONNX | 🟡 Media | ONNX Runtime ~20 MB; modelos ~75-300 MB; latencia aceptable en mid-range |
| 3 — `WhisperSpeechEngine` local JNI | 🔴 Alta | Build nativo complejo; no recomendado como primera opción |
| 3 — `onPartialResult` con Whisper | ❌ No viable | Whisper no soporta parciales nativos; será no-op en `WhisperSpeechEngine` |

---

## Feature — Métodos alternativos de activación START/STOP

### Contexto
El doble toque en auricular BT (`MediaSession`) no es fiable en todos los dispositivos
(depende del perfil HFP/A2DP del auricular y de si el sistema enruta los eventos correctamente).
Se documentan aquí las alternativas para evaluar e implementar según necesidad.

### Opción A — Botón en la notificación persistente ✅ Recomendada inmediata

**Cómo funciona:** La notificación foreground ya existe. Se añade una action "STOP" (y opcionalmente "START" cuando está parada) con un `PendingIntent` que dispara el intent correspondiente a `VoiceService`.

**Pros:**
- Sin permisos adicionales
- Sin dependencias nuevas
- ~15 líneas de código en `buildNotification()`
- Accesible desde el panel de notificaciones sin desbloquear el teléfono

**Contras:**
- Requiere bajar el panel de notificaciones (un gesto)

**Implementación:**
```kotlin
// En buildNotification() — añadir action según estado
val stopPi = PendingIntent.getService(
    this, 1,
    Intent(this, VoiceService::class.java).apply { action = ACTION_STOP },
    PendingIntent.FLAG_IMMUTABLE
)
NotificationCompat.Builder(this, CHANNEL_ID)
    // ...existing...
    .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
    .build()
```

**Archivos afectados:** solo `VoiceService.kt` (`buildNotification()`)

---

### Opción B — Sacudir el teléfono (Shake) 🟡 Media complejidad

**Cómo funciona:** `SensorManager` + `TYPE_ACCELEROMETER`. Se calcula la magnitud del vector de aceleración; si supera un umbral (~2.5g) durante >100ms se considera shake. Dos shakes en <1s = toggle.

**Pros:**
- Sin permisos adicionales
- Funciona con teléfono en el bolsillo
- Muy natural como gesto "de emergencia"

**Contras:**
- Puede activarse accidentalmente al correr o en transporte
- Consume batería (sensor activo continuamente mientras el servicio corre)
- El umbral necesita calibración por dispositivo

**Implementación:**
```kotlin
// En VoiceService — registrar sensor en onCreate(), desregistrar en onDestroy()
private lateinit var sensorManager: SensorManager
private var lastShakeMs = 0L

// SensorEventListener.onSensorChanged():
val magnitude = sqrt(x*x + y*y + z*z) - SensorManager.GRAVITY_EARTH
if (magnitude > 25f) {  // ~2.5g threshold
    val now = System.currentTimeMillis()
    if (now - lastShakeMs < 1000) toggleService()
    lastShakeMs = now
}
```

**Archivos afectados:** `VoiceService.kt`
**Permiso necesario:** ninguno

---

### Opción C — Quick Settings Tile 🟡 Media complejidad

**Cómo funciona:** `TileService` — aparece en el panel de ajustes rápidos (junto a WiFi, linterna, etc.). El usuario lo añade una vez y desde entonces un toque activa/para el servicio.

**Pros:**
- Accesible con un swipe desde cualquier pantalla, incluso bloqueada
- Muy visible — muestra el estado actual (activo/inactivo) con icono
- La solución más ergonómica para uso frecuente

**Contras:**
- El usuario tiene que añadir el tile manualmente la primera vez (Settings → Edit tiles)
- Requiere un archivo nuevo `VoiceAssistantTile.kt` + entrada en `AndroidManifest.xml`
- Android 7+ (API 24)

**Implementación:**
```kotlin
class VoiceAssistantTile : TileService() {
    override fun onClick() {
        val isRunning = // comprobar si VoiceService está corriendo
        if (isRunning) {
            startService(Intent(this, VoiceService::class.java).apply { action = VoiceService.ACTION_STOP })
            qsTile.state = Tile.STATE_INACTIVE
        } else {
            ContextCompat.startForegroundService(this,
                Intent(this, VoiceService::class.java).apply { action = VoiceService.ACTION_START })
            qsTile.state = Tile.STATE_ACTIVE
        }
        qsTile.updateTile()
    }
}
```

**Archivos afectados:** `VoiceAssistantTile.kt` (nuevo), `AndroidManifest.xml`
**Permiso necesario:** ninguno

---

### Opción D — Widget en pantalla de inicio 🔴 Más trabajo

**Cómo funciona:** `AppWidgetProvider` — icono/botón en la pantalla de inicio que el usuario añade como widget.

**Pros:** acceso directo desde la pantalla de inicio

**Contras:** requiere layout XML, `AppWidgetProvider`, `AppWidgetManager`, entrada en manifest y metadata XML. Más código que el tile con menos ventajas.

**Recomendación:** preferir Tile (Opción C) sobre Widget para este caso de uso.

---

### Opción E — Auricular BT (estado actual) 🔴 No fiable en todos los dispositivos

Ver sección **Feature — Control por auricular Bluetooth (doble toque)**.
El problema es que Android no garantiza el enrutado de `KEYCODE_HEADSETHOOK` /
`KEYCODE_MEDIA_PLAY_PAUSE` a nuestra `MediaSession` en todos los fabricantes/modelos.
Algunos auriculares BT LE (BLE Audio) directamente no emiten estos eventos.

---

### Orden de implementación sugerido para activación alternativa

| Prioridad | Opción | Esfuerzo | Fiabilidad |
|---|---|---|---|
| 1 | A — Botón en notificación | Muy bajo (~15 líneas) | ✅ 100% fiable |
| 2 | C — Quick Settings Tile | Medio (~50 líneas + manifest) | ✅ 100% fiable |
| 3 | B — Shake | Medio (~40 líneas) | 🟡 Depende de umbral |
| 4 | D — Widget | Alto | ✅ Fiable pero menos cómodo |
| 5 | E — Auricular BT | Ya implementado | 🔴 Depende del dispositivo/auricular |

---

## 📋 Historial de sesiones — cambios implementados

### Sesión 1 — TtsTextCleaner + bug fix de regex

**Problema:** `cleanForTts` usaba `` `([^`\n]*)` `` → `"$1"` sin grupo de captura declarado, lo que producía una excepción en runtime y eliminaba el texto entre backticks en lugar de conservarlo.

**Cambios:**
- Nuevo archivo: `app/src/main/java/com/automation/voiceassistant/service/TtsTextCleaner.kt`
  - Extraída la función `cleanForTts` de `VoiceService` a un objeto standalone para poder testarla sin dependencias Android
  - Bug fix: `` `([^`\n]*)` `` → `"$1"` (grupo de captura correcto)
  - Paso extra para eliminar backticks sueltos residuales
  - El guion `-` removido del set de símbolos eliminados para no romper palabras compuestas
- `VoiceService.kt`: `speak()` usa `TtsTextCleaner.clean(text)`, documentado con KDoc
- Tests: 35 unit tests en `TtsTextCleanerTest.kt`, todos verdes

---

### Sesión 2 — AB Shutter 3: captura de botón BT

**Objetivo:** Usar el AB Shutter 3 (BT HID) para START/STOP sin tocar el teléfono.

**AB Shutter 3 tiene dos botones:**
- Button 1 (modo iOS) → `KEYCODE_HEADSETHOOK` → va por MediaSession
- Button 2 (modo Android) → `KEYCODE_VOLUME_UP` → va por sistema de audio/input

**Cambios:**
- `VoiceService.kt`:
  - `MediaSessionCompat` inicializado en `onCreate()` con `FLAG_HANDLES_MEDIA_BUTTONS`
  - `onMediaButtonEvent()` maneja `KEYCODE_HEADSETHOOK`, `KEYCODE_MEDIA_PLAY_PAUSE`, `KEYCODE_VOLUME_UP`, `KEYCODE_VOLUME_DOWN`
  - `handleHeadsetButton()` con doble-tap detection (400ms window) → `toggleService()`
  - `onStartCommand` también procesa `Intent.ACTION_MEDIA_BUTTON` (ruteado desde `HeadsetButtonReceiver`)
- `receiver/HeadsetButtonReceiver.kt`: agregados `KEYCODE_VOLUME_UP` y `KEYCODE_VOLUME_DOWN` al filtro
- `MainActivity.kt`:
  - `isServiceActive` cambiado de `var Boolean` a `mutableStateOf(false)` para que Compose recomponga
  - `onKeyDown()` override para capturar VOLUME_UP/DOWN cuando la app está en primer plano
  - `isAccessibilityEnabled()` helper

---

### Sesión 3 — ButtonAccessibilityService (pantalla apagada)

**Problema:** `onKeyDown` no funciona con pantalla apagada. Solución: `AccessibilityService` con `flagRequestFilterKeyEvents`.

**Archivos nuevos:**
- `service/ButtonAccessibilityService.kt`:
  - Extiende `AccessibilityService`
  - `onKeyEvent()` intercepta `KEYCODE_VOLUME_UP` → `ACTION_START` y `KEYCODE_VOLUME_DOWN` → `ACTION_STOP`
  - `PARTIAL_WAKE_LOCK` de 2 segundos para asegurar que el intent llega aunque el CPU esté dormido
  - `onServiceConnected()` inicializa el WakeLock
- `res/xml/accessibility_service_config.xml`:
  - `flagRequestFilterKeyEvents`
  - `accessibilityFeedbackType="feedbackAllMask"` (necesario para algunos OEM para funcionar con pantalla apagada)
  - `canRequestFilterKeyEvents="true"`
- `AndroidManifest.xml`:
  - Declaración del servicio con `BIND_ACCESSIBILITY_SERVICE` y metadata del XML
  - `WAKE_LOCK` permission
- `MainActivity.kt`:
  - Banner de error si la accesibilidad no está activada, con botón "Abrir Accesibilidad" y "Ya lo activé ✓"

**Nota:** El usuario (Realme/ColorOS) necesita activar el servicio en: Settings → Additional Settings → Accessibility → Installed Services → Botones Shutter.

**Resultado en Realme:** El `AccessibilityService` con `flagRequestFilterKeyEvents` NO recibe `KEYCODE_VOLUME_UP/DOWN` con pantalla apagada en Realme. Es una restricción del fabricante irresolvible desde código.

---

### Sesión 4 — Rediseño: servicio persistente + MediaSession siempre activa

**Diagnóstico:** El problema raíz era que al hacer STOP, `VoiceService` llamaba `stopSelf()`, destruyendo la `MediaSession`. Sin `MediaSession` activa, Android no sabe a quién enviar el evento del botón BT con pantalla apagada.

**Solución:** Patrón de apps de música — `VoiceService` **nunca se destruye completamente**; al hacer STOP solo desconecta el WebSocket y queda en estado IDLE con la `MediaSession` activa.

**Cambios en `VoiceService.kt`:**
- `onCreate()`: llama `startForeground()` inmediatamente (IDLE), ya no espera `ACTION_START`
- `doStop()`: ya NO llama `stopSelf()` ni `stopForeground()`. Solo desconecta WS y vuelve a IDLE
- `doKill()` (nuevo): detiene el servicio completamente; llamado solo por `ACTION_KILL`
- `ACTION_KILL` agregado al companion object
- `handleHeadsetButton()`: simplificado a un toggle de un solo toque (sin doble-tap) — más fiable
- `initMediaSession()`:
  - Usa `MediaSessionCompat(context, tag, receiverComponent, pendingIntent)` constructor
  - Llama `setMediaButtonReceiver(pendingIntent)` — persiste el registro aunque la sesión se destruya
  - `FLAG_HANDLES_MEDIA_BUTTONS or FLAG_HANDLES_TRANSPORT_CONTROLS`
  - `PlaybackState = STATE_PAUSED` (no `STATE_STOPPED`) — Android solo entrega media buttons a sesiones en PAUSED o PLAYING
- `updatePlaybackState(playing: Boolean)`: actualiza el estado dinámicamente según `serviceState`
- `serviceState` setter: llama `updatePlaybackState()` en cada transición
- Notificación IDLE: "Listo — presiona el botón para iniciar"
- `isServiceActive`: convertido a field con setter que hace `broadcastActive()`
- `broadcastActive(active: Boolean)`: envía `ACTION_STATE` broadcast con `EXTRA_IS_ACTIVE`
- `ACTION_STATE`, `EXTRA_IS_ACTIVE`: nuevas constantes en companion object
- Canal de notificación: `IMPORTANCE_MIN` (sin ícono en status bar, silencioso, al fondo de la bandeja)

**Cambios en `MainActivity.kt`:**
- `onCreate()`: arranca el servicio sin acción (`startForegroundService` con intent vacío) para que la `MediaSession` se registre desde el primer uso
- `logReceiver` ampliado: además de `LOG`, escucha `VoiceService.ACTION_STATE` para sincronizar `isServiceActive.value` con el estado real del servicio
- `IntentFilter` registra tanto `LOG` como `ACTION_STATE`
- `permissionLauncher`: comentario aclarando que `isServiceActive` se actualiza vía broadcast

**HeadsetButtonReceiver:** prioridad aumentada a `Integer.MAX_VALUE` (2147483647) para ganar a cualquier otra app de música.

**Resultado:** Con pantalla apagada la notificación persiste ("Listo..."), la `MediaSession` existe, pero el botón BT (AB Shutter 3 HID) aún no funciona con pantalla apagada en Realme.

---

### ⚠️ Estado abierto — Pantalla apagada + AB Shutter 3 en Realme

**Conclusión actual:** El AB Shutter 3 es un dispositivo **BT HID puro**. En Realme/ColorOS, los eventos de teclado BT HID con pantalla apagada son bloqueados por el kernel/driver BT del fabricante **antes de llegar a Android**. Esto es irresolvible desde código de aplicación.

**Lo que sí funciona:**
- Button 1 (iOS/HEADSETHOOK) con pantalla **encendida** → MediaSession ✅
- Button 2 (Android/VOLUME_UP) con pantalla **encendida** → onKeyDown / AccessibilityService ✅
- Botón visual START/STOP en la app → siempre funciona ✅
- Botón de notificación (por implementar, Opción A del plan) → funcionaría ✅

**Para diagnosticar si los eventos llegan con pantalla apagada:**
```bash
adb logcat -s InputReader:* InputDispatcher:* MediaSessionService:* *:S 2>&1
# Apagar pantalla, presionar botón, ver si aparece algo
```

**Alternativa más fiable para pantalla apagada:**
- Usar un auricular BT con perfil **HFP/A2DP** (cualquier auricular de llamadas). Su botón play/pause usa AVRCP que tiene tratamiento especial en el kernel Android y sí despierta el dispositivo. Enviaría `KEYCODE_HEADSETHOOK` o `KEYCODE_MEDIA_PLAY_PAUSE` que ya están manejados por nuestra `MediaSession`.
