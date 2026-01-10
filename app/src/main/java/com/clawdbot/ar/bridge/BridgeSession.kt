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
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = SecurePrefs(context)

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var connectionJob: Job? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Incoming commands from gateway: (id, command, paramsJson)
    private val _incomingCommands = MutableSharedFlow<Triple<String, String, String?>>()
    val incomingCommands: SharedFlow<Triple<String, String, String?>> = _incomingCommands.asSharedFlow()

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

            _isConnected.value = true
            reconnectAttempt = 0
            Log.d(TAG, "Connected to $host:$port")

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            _isConnected.value = false
            scheduleReconnect()
            throw e
        }
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

                try {
                    handleMessage(line)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling message: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Read loop error: ${e.message}")
        } finally {
            _isConnected.value = false
            scheduleReconnect()
        }
    }

    private suspend fun handleMessage(line: String) {
        val msg = json.parseToJsonElement(line).jsonObject
        val type = msg["type"]?.jsonPrimitive?.content ?: return

        when (type) {
            "hello-ok" -> {
                Log.d(TAG, "Hello acknowledged, connection established")
                // Extract and store token if provided
                msg["token"]?.jsonPrimitive?.content?.let { token ->
                    prefs.setBridgeToken(token)
                }
            }

            "pair-ok" -> {
                Log.d(TAG, "Pairing successful")
                msg["token"]?.jsonPrimitive?.content?.let { token ->
                    prefs.setBridgeToken(token)
                }
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
                Log.d(TAG, "Received event: $event")
                // Handle events like voicewake.changed, etc.
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

    /**
     * Send a chat message to gateway.
     */
    suspend fun sendChatMessage(text: String): String {
        val requestId = UUID.randomUUID().toString()

        val request = buildJsonObject {
            put("type", "req")
            put("id", requestId)
            put("method", "chat.send")
            put("paramsJSON", buildJsonObject {
                put("text", text)
            }.toString())
        }

        sendMessage(request.toString())
        return requestId
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
