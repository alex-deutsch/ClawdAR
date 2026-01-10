package com.clawdbot.ar.bridge

import android.content.Context
import android.util.Log
import com.clawdbot.ar.protocol.InvokeResult
import com.clawdbot.ar.util.SecurePrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.math.pow

/**
 * Manages TCP connection to ClawdBot gateway.
 * Implements the bridge protocol with JSON newline-delimited messages.
 */
class BridgeSession(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BridgeSession"
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 0 // infinite for long-lived connection
        private const val RECONNECT_BASE_DELAY_MS = 350L
        private const val RECONNECT_MAX_DELAY_MS = 8000L
        private const val RECONNECT_MULTIPLIER = 1.7
        private const val HEARTBEAT_INTERVAL_MS = 30000L // Send heartbeat every 30s
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = SecurePrefs(context)

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var connectionJob: Job? = null
    private var heartbeatJob: Job? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Incoming commands from gateway: (id, command, paramsJson)
    private val _incomingCommands = MutableSharedFlow<Triple<String, String, String?>>()
    val incomingCommands: SharedFlow<Triple<String, String, String?>> = _incomingCommands.asSharedFlow()

    // Chat events from gateway: streaming text updates
    private val _chatEvents = MutableSharedFlow<ChatEvent>()
    val chatEvents: SharedFlow<ChatEvent> = _chatEvents.asSharedFlow()

    // Chat event types
    sealed class ChatEvent {
        data class AgentText(val runId: String, val text: String) : ChatEvent()
        data class ChatState(val runId: String?, val state: String, val errorMessage: String?) : ChatEvent()
    }

    private var currentEndpoint: BridgeEndpoint? = null
    private var reconnectAttempt = 0

    /**
     * Connect to a discovered endpoint.
     */
    suspend fun connect(endpoint: BridgeEndpoint) {
        currentEndpoint = endpoint
        reconnectAttempt = 0
        connectInternal(endpoint.host, endpoint.port)
    }

    /**
     * Connect to a specific host and port.
     */
    suspend fun connectTo(host: String, port: Int) {
        currentEndpoint = BridgeEndpoint(host, port, "Manual")
        reconnectAttempt = 0
        connectInternal(host, port)
    }

    private suspend fun connectInternal(host: String, port: Int) = withContext(Dispatchers.IO) {
        disconnect()

        try {
            Log.d(TAG, "Connecting to $host:$port...")

            socket = Socket().apply {
                tcpNoDelay = true
                keepAlive = true // Enable TCP keepalive
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                soTimeout = READ_TIMEOUT_MS
            }

            reader = BufferedReader(InputStreamReader(socket!!.getInputStream(), Charsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(socket!!.getOutputStream(), Charsets.UTF_8))

            // Send hello message
            sendHello()

            // Start reading messages
            connectionJob = scope.launch(Dispatchers.IO) {
                readLoop()
            }

            // Start heartbeat to keep connection alive
            startHeartbeat()

            // Note: _isConnected will be set to true when we receive hello-ok or pair-ok
            reconnectAttempt = 0
            Log.d(TAG, "Socket connected to $host:$port, waiting for handshake...")

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            _isConnected.value = false
            scheduleReconnect()
            throw e
        }
    }

    /**
     * Start periodic heartbeat to keep connection alive.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (_isConnected.value) {
                    try {
                        sendHeartbeat()
                    } catch (e: Exception) {
                        Log.w(TAG, "Heartbeat failed: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Send a heartbeat ping to gateway.
     */
    private suspend fun sendHeartbeat() {
        val ping = buildJsonObject {
            put("type", "ping")
        }
        sendMessage(ping.toString())
        Log.d(TAG, "Heartbeat sent")
    }

    private suspend fun sendHello() {
        val hello = buildJsonObject {
            put("type", "hello")
            put("nodeId", prefs.instanceId.value)
            put("displayName", prefs.displayName.value)
            put("platform", "android-ar")
            put("version", "1.0.0")
            putJsonArray("capabilities") {
                add("canvas")
                add("canvas.a2ui")
            }
            putJsonArray("commands") {
                add("canvas.navigate")
                add("canvas.eval")
                add("canvas.snapshot")
                add("canvas.a2ui.push")
                add("canvas.a2ui.pushJSONL")
                add("canvas.a2ui.reset")
            }
            prefs.bridgeToken.value?.let { token ->
                put("token", token)
            }
        }

        sendMessage(hello.toString())
    }

    private suspend fun readLoop() {
        try {
            while (currentCoroutineContext().isActive) {
                val line = reader?.readLine() ?: break
                if (line.isBlank()) continue

                Log.d(TAG, "Received: $line")
                try {
                    handleMessage(line)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling message: ${e.message}", e)
                }
            }
            Log.d(TAG, "Read loop ended normally")
        } catch (e: Exception) {
            Log.e(TAG, "Read loop error: ${e.message}", e)
        } finally {
            Log.d(TAG, "Read loop finally block, setting disconnected")
            _isConnected.value = false
            scheduleReconnect()
        }
    }

    private suspend fun handleMessage(line: String) {
        val msg = json.parseToJsonElement(line).jsonObject
        val type = msg["type"]?.jsonPrimitive?.content ?: return

        Log.d(TAG, "Handling message type: $type")

        when (type) {
            "hello-ok" -> {
                Log.d(TAG, "Hello acknowledged, connection established")
                // Extract and store token if provided
                msg["token"]?.jsonPrimitive?.content?.let { token ->
                    prefs.setBridgeToken(token)
                }
                _isConnected.value = true
            }

            "pair-ok" -> {
                Log.d(TAG, "Pairing successful")
                msg["token"]?.jsonPrimitive?.content?.let { token ->
                    prefs.setBridgeToken(token)
                }
                _isConnected.value = true
            }

            "error" -> {
                val code = msg["code"]?.jsonPrimitive?.content
                val message = msg["message"]?.jsonPrimitive?.content
                Log.e(TAG, "Error from gateway: $code - $message")

                if (code == "NOT_PAIRED") {
                    // Need to pair - send pair request
                    sendPairRequest()
                }
            }

            "invoke" -> {
                val id = msg["id"]?.jsonPrimitive?.content ?: return
                val command = msg["command"]?.jsonPrimitive?.content ?: return
                val paramsJson = msg["paramsJSON"]?.jsonPrimitive?.content

                Log.d(TAG, "Received invoke: $command")
                _incomingCommands.emit(Triple(id, command, paramsJson))
            }

            "event" -> {
                val event = msg["event"]?.jsonPrimitive?.content
                val payloadJson = msg["payloadJSON"]?.jsonPrimitive?.content
                Log.d(TAG, "Received event: $event, payload: $payloadJson")

                when (event) {
                    "agent" -> {
                        // Streaming AI response text
                        if (payloadJson != null) {
                            try {
                                val payload = json.parseToJsonElement(payloadJson).jsonObject
                                val runId = payload["runId"]?.jsonPrimitive?.content ?: return
                                val stream = payload["stream"]?.jsonPrimitive?.content
                                val data = payload["data"]?.jsonObject

                                if (stream == "assistant" && data != null) {
                                    val text = data["text"]?.jsonPrimitive?.content
                                    if (text != null) {
                                        Log.d(TAG, "Agent text update: $text")
                                        _chatEvents.emit(ChatEvent.AgentText(runId, text))
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing agent event: ${e.message}")
                            }
                        }
                    }

                    "chat" -> {
                        // Chat state changes (final, error, aborted)
                        if (payloadJson != null) {
                            try {
                                val payload = json.parseToJsonElement(payloadJson).jsonObject
                                val runId = payload["runId"]?.jsonPrimitive?.content
                                val state = payload["state"]?.jsonPrimitive?.content ?: return
                                val errorMessage = payload["errorMessage"]?.jsonPrimitive?.content

                                Log.d(TAG, "Chat state: $state for run $runId")
                                _chatEvents.emit(ChatEvent.ChatState(runId, state, errorMessage))
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing chat event: ${e.message}")
                            }
                        }
                    }
                }
            }

            "res" -> {
                val id = msg["id"]?.jsonPrimitive?.content
                val ok = msg["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                val payload = msg["payloadJSON"]?.jsonPrimitive?.content
                Log.d(TAG, "Received response: id=$id, ok=$ok, payload=$payload")
            }

            "pong" -> {
                // Gateway responded to our ping
                Log.d(TAG, "Received pong")
            }

            "tick" -> {
                // Gateway heartbeat tick - connection is alive
                Log.d(TAG, "Received tick")
            }
        }
    }

    private suspend fun sendPairRequest() {
        val request = buildJsonObject {
            put("type", "pair-request")
            put("nodeId", prefs.instanceId.value)
            put("displayName", prefs.displayName.value)
            put("platform", "android-ar")
            put("version", "1.0.0")
            putJsonArray("capabilities") {
                add("canvas")
                add("canvas.a2ui")
            }
            putJsonArray("commands") {
                add("canvas.navigate")
                add("canvas.eval")
                add("canvas.snapshot")
                add("canvas.a2ui.push")
                add("canvas.a2ui.pushJSONL")
                add("canvas.a2ui.reset")
            }
        }

        sendMessage(request.toString())
        Log.d(TAG, "Pair request sent - waiting for approval on gateway")
    }

    /**
     * Send response to an invoke command.
     */
    suspend fun sendResponse(id: String, result: InvokeResult) {
        val response = buildJsonObject {
            put("type", "response")
            put("id", id)
            if (result.ok) {
                result.payloadJson?.let { put("payloadJSON", it) }
            } else {
                putJsonObject("error") {
                    put("code", result.errorCode ?: "UNKNOWN")
                    put("message", result.errorMessage ?: "Unknown error")
                }
            }
        }

        sendMessage(response.toString())
    }

    /**
     * Send event to gateway.
     */
    suspend fun sendEvent(event: String, payloadJson: String?) {
        val msg = buildJsonObject {
            put("type", "event")
            put("event", event)
            payloadJson?.let { put("payloadJSON", it) }
        }

        sendMessage(msg.toString())
    }

    // Session key for chat - persisted across app restarts
    private val chatSessionKey: String by lazy {
        prefs.getChatSessionKey() ?: UUID.randomUUID().toString().also {
            prefs.setChatSessionKey(it)
        }
    }

    /**
     * Subscribe to chat events for our session.
     * Must be called to receive agent/chat events.
     */
    suspend fun subscribeToChatEvents() {
        val event = buildJsonObject {
            put("type", "event")
            put("event", "chat.subscribe")
            put("payloadJSON", buildJsonObject {
                put("sessionKey", chatSessionKey)
            }.toString())
        }

        sendMessage(event.toString())
        Log.d(TAG, "Subscribed to chat events for session: $chatSessionKey")
    }

    /**
     * Send a chat message to gateway.
     */
    suspend fun sendChatMessage(text: String): String {
        // Ensure we're subscribed to receive response events
        subscribeToChatEvents()

        val requestId = UUID.randomUUID().toString()
        val idempotencyKey = UUID.randomUUID().toString()

        val request = buildJsonObject {
            put("type", "req")
            put("id", requestId)
            put("method", "chat.send")
            put("paramsJSON", buildJsonObject {
                put("sessionKey", chatSessionKey)
                put("message", text)
                put("idempotencyKey", idempotencyKey)
            }.toString())
        }

        sendMessage(request.toString())
        return requestId
    }

    /**
     * Start audio streaming session with gateway.
     * Returns a session ID for the audio stream.
     */
    suspend fun startAudioStream(): String {
        val sessionId = UUID.randomUUID().toString()

        val request = buildJsonObject {
            put("type", "req")
            put("id", sessionId)
            put("method", "audio.startStream")
            put("paramsJSON", buildJsonObject {
                put("format", "pcm")
                put("sampleRate", 16000)
                put("channels", 1)
                put("bitsPerSample", 16)
            }.toString())
        }

        sendMessage(request.toString())
        return sessionId
    }

    /**
     * Send an audio chunk to the gateway.
     */
    suspend fun sendAudioChunk(
        sessionId: String,
        chunkIndex: Long,
        audioBase64: String,
        isFinal: Boolean = false
    ) {
        val chunk = buildJsonObject {
            put("type", "audio-chunk")
            put("sessionId", sessionId)
            put("chunkIndex", chunkIndex)
            put("data", audioBase64)
            put("isFinal", isFinal)
        }

        sendMessage(chunk.toString())
    }

    /**
     * End the audio streaming session.
     */
    suspend fun endAudioStream(sessionId: String) {
        val request = buildJsonObject {
            put("type", "req")
            put("id", UUID.randomUUID().toString())
            put("method", "audio.endStream")
            put("paramsJSON", buildJsonObject {
                put("sessionId", sessionId)
            }.toString())
        }

        sendMessage(request.toString())
    }

    private suspend fun sendMessage(message: String) = withContext(Dispatchers.IO) {
        try {
            writer?.apply {
                write(message)
                newLine()
                flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send error: ${e.message}")
            _isConnected.value = false
        }
    }

    private fun scheduleReconnect() {
        val endpoint = currentEndpoint ?: return

        connectionJob?.cancel()
        connectionJob = scope.launch {
            val delay = (RECONNECT_BASE_DELAY_MS *
                    RECONNECT_MULTIPLIER.pow(reconnectAttempt.toDouble()))
                .toLong()
                .coerceAtMost(RECONNECT_MAX_DELAY_MS)

            Log.d(TAG, "Scheduling reconnect in ${delay}ms (attempt $reconnectAttempt)")
            delay(delay)
            reconnectAttempt++

            try {
                connectInternal(endpoint.host, endpoint.port)
            } catch (e: Exception) {
                // Will schedule another reconnect
            }
        }
    }

    /**
     * Disconnect from gateway.
     */
    fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        connectionJob?.cancel()
        connectionJob = null

        try {
            writer?.close()
            reader?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing connection: ${e.message}")
        }

        writer = null
        reader = null
        socket = null
        _isConnected.value = false
    }
}
