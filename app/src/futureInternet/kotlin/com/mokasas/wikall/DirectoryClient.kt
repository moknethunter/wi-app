package com.mokasas.wikall

import java.security.PrivateKey
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal data class InternetIce(val url: String, val username: String, val credential: String)
internal data class MissedCall(val id: UUID, val number: String, val at: Long, val pushed: Boolean)

internal fun directoryUrl(value: String) = value.trim().toHttpUrlOrNull()?.takeIf {
    it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null &&
        it.encodedPath == "/"
}?.newBuilder()?.encodedPath("/v1/socket")?.build()

internal class DirectoryClient(
    endpoint: String,
    private val realm: String,
    private val number: String,
    private val key: PrivateKey,
    private val ready: (List<InternetIce>) -> Unit,
    private val signal: (Signal) -> Unit,
    private val missed: (MissedCall) -> Unit,
    private val closed: () -> Unit,
) : AutoCloseable {
    private val ended = AtomicBoolean()
    private val socket = http.newWebSocket(Request.Builder().url(checkNotNull(directoryUrl(endpoint))).build(),
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (ended.get()) return
                runCatching {
                    require(text.length <= 16_384)
                    val message = Json.parseToJsonElement(text).jsonObject
                    when (val kind = message.string("type")) {
                        "CHALLENGE" -> {
                            val challenge = checkNotNull(message.string("challenge")).also { require(validNonce(it)) }
                            val proof = "Wi-Call directory authentication 1\n$realm\n$number\n$challenge".encodeToByteArray()
                            val signature = Wire.encode(Wire.signature(key, proof))
                            webSocket.send(buildJsonObject {
                                put("type", "AUTH")
                                put("realm", realm)
                                put("number", number)
                                put("signature", signature)
                            }.toString())
                        }
                        "READY" -> ready(message.getValue("ice").jsonArray.take(8).map {
                            val item = it.jsonObject
                            InternetIce(checkNotNull(item.string("url")),
                                item.string("username").orEmpty(), item.string("credential").orEmpty())
                        })
                        "MISSED" -> {
                            val caller = checkNotNull(message.string("number"))
                            require(validNumber(caller))
                            missed(MissedCall(UUID.fromString(message.string("id")), caller,
                                message.getValue("at").jsonPrimitive.long, message["pushed"]?.jsonPrimitive?.booleanOrNull == true))
                        }
                        else -> signal(Signal(SignalType.valueOf(checkNotNull(kind)), UUID.fromString(message.string("id")),
                            message.string("payload"), message.string("number")))
                    }
                }.onFailure {
                    webSocket.cancel()
                    end()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                end()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = end()
        }
    )

    fun send(signal: Signal) = sendJson {
        put("type", signal.type.name)
        put("id", signal.id.toString())
        signal.number?.let { put("number", it) }
        signal.payload?.let { put("payload", it) }
    }

    fun roster(entries: List<Enrollment>) = sendJson {
        put("type", "ROSTER")
        put("entries", buildJsonObject {
            entries.filterNot(Enrollment::revoked).forEach { put(it.number, it.signing) }
        })
    }

    fun acknowledge(id: UUID) = sendJson {
        put("type", "ACK")
        put("id", id.toString())
    }

    fun pushToken(token: String) = sendJson {
        put("type", "PUSH")
        put("token", token)
    }

    private fun sendJson(build: JsonObjectBuilder.() -> Unit): Boolean =
        !ended.get() && socket.send(buildJsonObject(build).toString())

    private fun end() {
        if (ended.compareAndSet(false, true)) closed()
    }

    override fun close() {
        if (ended.compareAndSet(false, true)) socket.cancel()
    }

    private companion object {
        val http = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
        fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.contentOrNull
    }
}
