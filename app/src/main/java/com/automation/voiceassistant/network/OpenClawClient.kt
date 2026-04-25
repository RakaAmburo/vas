package com.automation.voiceassistant.network

import android.content.Context
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.KeyPairGenerator
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object OpenClawClient {

    private fun base64url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun sha256hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun getOrCreateKeypair(context: Context): Pair<EdDSAPublicKey, EdDSAPrivateKey> {
        val prefs = context.getSharedPreferences("vas_device", Context.MODE_PRIVATE)
        val storedPriv = prefs.getString("priv_key", null)
        val storedPub  = prefs.getString("pub_key", null)

        if (storedPriv != null && storedPub != null) {
            val spec = EdDSANamedCurveTable.getByName("ed25519")
            val privBytes = Base64.getDecoder().decode(storedPriv)
            val pubBytes  = Base64.getDecoder().decode(storedPub)
            val privKey = EdDSAPrivateKey(net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec(privBytes, spec))
            val pubKey  = EdDSAPublicKey(net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec(pubBytes, spec))
            return Pair(pubKey, privKey)
        }

        val spec = EdDSANamedCurveTable.getByName("ed25519")
        val kpg = KeyPairGenerator()
        kpg.initialize(spec, java.security.SecureRandom())
        val kp = kpg.generateKeyPair()
        val pubKey  = kp.public as EdDSAPublicKey
        val privKey = kp.private as EdDSAPrivateKey

        prefs.edit()
            .putString("priv_key", Base64.getEncoder().encodeToString(privKey.seed))
            .putString("pub_key",  Base64.getEncoder().encodeToString(pubKey.abyte))
            .apply()

        return Pair(pubKey, privKey)
    }

    fun sendMessage(
        context: Context,
        host: String,
        port: String,
        token: String,
        message: String,
        onLog: (String, Boolean) -> Unit = { _, _ -> }
    ): String? {
        val result = StringBuilder()
        val latch = CountDownLatch(1)

        val (pubKeyObj, privKey) = getOrCreateKeypair(context)
        val pubKey   = pubKeyObj.abyte
        val deviceId = sha256hex(pubKey)

        onLog("Conectando a ws://$host:$port", false)

        val uri = URI("ws://$host:$port")
        val ws = object : WebSocketClient(uri) {
            override fun onOpen(handshake: ServerHandshake?) {
                onLog("WebSocket conectado", false)
            }

            override fun onMessage(raw: String) {
                val msg   = JSONObject(raw)
                val type  = msg.optString("type")
                val event = msg.optString("event")

                when {
                    event == "connect.challenge" -> {
                        onLog("Challenge recibido", false)
                        val nonce    = msg.getJSONObject("payload").getString("nonce")
                        val signedAt = System.currentTimeMillis()
                        val payload  = "v3|$deviceId|cli|cli|operator|operator.read,operator.write,operator.admin|$signedAt|$token|$nonce|android|"
                        val engine   = EdDSAEngine()
                        engine.initSign(privKey)
                        engine.update(payload.toByteArray())
                        val signature = base64url(engine.sign())

                        send(JSONObject().apply {
                            put("type", "req"); put("id", "1"); put("method", "connect")
                            put("params", JSONObject().apply {
                                put("minProtocol", 3); put("maxProtocol", 3)
                                put("client", JSONObject().apply {
                                    put("id", "cli"); put("version", "1.0.0")
                                    put("platform", "android"); put("mode", "cli")
                                })
                                put("role", "operator")
                                put("scopes", org.json.JSONArray(listOf("operator.read", "operator.write", "operator.admin")))
                                put("auth", JSONObject().put("token", token))
                                put("device", JSONObject().apply {
                                    put("id", deviceId)
                                    put("publicKey", base64url(pubKey))
                                    put("signature", signature)
                                    put("signedAt", signedAt)
                                    put("nonce", nonce)
                                })
                            })
                        }.toString())
                    }

                    type == "res" && msg.optString("id") == "1" -> {
                        val error = msg.optJSONObject("error")
                        if (error != null) {
                            val code = error.optString("code")
                            if (code == "NOT_PAIRED") {
                                val requestId = error.optJSONObject("details")?.optString("requestId") ?: ""
                                result.append("PAIRING:$requestId")
                            } else {
                                onLog("Error connect: $code", true)
                            }
                            latch.countDown()
                        } else {
                            onLog("Conectado OK", false)
                            send(JSONObject().apply {
                                put("type", "req"); put("id", "2"); put("method", "chat.send")
                                put("params", JSONObject().apply {
                                    put("sessionKey", "main")
                                    put("message", message)
                                    put("idempotencyKey", UUID.randomUUID().toString())
                                })
                            }.toString())
                        }
                    }

                    event == "agent" -> {
                        val p      = msg.optJSONObject("payload")
                        val stream = p?.optString("stream")
                        if (stream == "assistant") {
                            val delta = p.optJSONObject("data")?.optString("delta") ?: ""
                            if (delta.isNotEmpty()) result.append(delta)
                        } else if (stream == "lifecycle" &&
                            p?.optJSONObject("data")?.optString("phase") == "end") {
                            onLog("← closing!!", false)
                            latch.countDown()
                        }

                    }
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                onLog("WS Cerrado: code=$code reason='$reason' remote=$remote result='$result'", code != 1000)
                latch.countDown()
            }

            override fun onError(ex: Exception?) {
                onLog("WS Error: ${ex?.javaClass?.simpleName}: ${ex?.message}", true)
                latch.countDown()
            }
        }

        ws.connect()
        latch.await(30, TimeUnit.SECONDS)
        ws.close()

        val res = result.toString()
        return if (res.isBlank()) null else res
    }
}