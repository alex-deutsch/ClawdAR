package com.clawdbot.ar

import android.content.Context
import com.clawdbot.ar.bridge.BridgeSession
import com.clawdbot.ar.bridge.BridgeDiscovery
import com.clawdbot.ar.node.CanvasController
import com.clawdbot.ar.protocol.InvokeResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/**
 * Core runtime orchestrator for ClawdAR.
 * Manages bridge connection, canvas, and command routing.
 */
class NodeRuntime(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true }

    // Bridge connection
    val bridge = BridgeSession(context, scope)
    val discovery = BridgeDiscovery(context)

    // Canvas controller
    val canvas = CanvasController()

    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Status message
    private val _statusMessage = MutableStateFlow("Ready")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    init {
        // Observe bridge connection state
        scope.launch {
            bridge.isConnected.collect { connected ->
                _connectionState.value = if (connected) {
                    ConnectionState.CONNECTED
                } else {
                    ConnectionState.DISCONNECTED
                }
            }
        }

        // Handle incoming commands from gateway
        scope.launch {
            bridge.incomingCommands.collect { (id, command, paramsJson) ->
                handleCommand(id, command, paramsJson)
            }
        }
    }

    /**
     * Start discovery and connect to gateway.
     */
    suspend fun connect() {
        _connectionState.value = ConnectionState.CONNECTING
        _statusMessage.value = "Discovering gateway..."

        try {
            // Try to discover gateway on local network
            val endpoints = discovery.discover()
            if (endpoints.isNotEmpty()) {
                val endpoint = endpoints.first()
                _statusMessage.value = "Connecting to ${endpoint.displayName}..."
                bridge.connect(endpoint)
            } else {
                _statusMessage.value = "No gateway found"
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        } catch (e: Exception) {
            _statusMessage.value = "Connection failed: ${e.message}"
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    /**
     * Connect to a specific host:port.
     */
    suspend fun connectTo(host: String, port: Int) {
        _connectionState.value = ConnectionState.CONNECTING
        _statusMessage.value = "Connecting to $host:$port..."

        try {
            bridge.connectTo(host, port)
        } catch (e: Exception) {
            _statusMessage.value = "Connection failed: ${e.message}"
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    /**
     * Disconnect from gateway.
     */
    fun disconnect() {
        bridge.disconnect()
        _statusMessage.value = "Disconnected"
    }

    /**
     * Handle incoming command from gateway.
     */
    private suspend fun handleCommand(id: String, command: String, paramsJson: String?) {
        val result = try {
            when (command) {
                "canvas.navigate" -> {
                    val params = paramsJson?.let { json.decodeFromString<CanvasNavigateParams>(it) }
                    canvas.navigate(params?.url ?: "about:blank")
                    InvokeResult.ok()
                }

                "canvas.eval" -> {
                    val params = paramsJson?.let { json.decodeFromString<CanvasEvalParams>(it) }
                    val result = canvas.eval(params?.js ?: "")
                    InvokeResult.ok(result)
                }

                "canvas.snapshot" -> {
                    val params = paramsJson?.let { json.decodeFromString<CanvasSnapshotParams>(it) }
                    val base64 = canvas.snapshotBase64(
                        format = params?.format ?: "jpeg",
                        quality = params?.quality ?: 0.8,
                        maxWidth = params?.maxWidth
                    )
                    InvokeResult.ok(buildJsonObject {
                        put("base64", base64)
                        put("mimeType", if (params?.format == "png") "image/png" else "image/jpeg")
                    }.toString())
                }

                "canvas.a2ui.push", "canvas.a2ui.pushJSONL" -> {
                    val params = paramsJson?.let { json.decodeFromString<A2UIPushParams>(it) }
                    params?.messages?.let { messages ->
                        canvas.pushA2UIMessages(messages)
                    }
                    InvokeResult.ok()
                }

                "canvas.a2ui.reset" -> {
                    canvas.resetA2UI()
                    InvokeResult.ok()
                }

                else -> {
                    InvokeResult.error("UNKNOWN_COMMAND", "Unknown command: $command")
                }
            }
        } catch (e: Exception) {
            InvokeResult.error("EXECUTION_ERROR", e.message ?: "Unknown error")
        }

        // Send response back to gateway
        bridge.sendResponse(id, result)
    }

    fun cleanup() {
        scope.cancel()
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

// Protocol data classes
@kotlinx.serialization.Serializable
data class CanvasNavigateParams(val url: String)

@kotlinx.serialization.Serializable
data class CanvasEvalParams(val js: String)

@kotlinx.serialization.Serializable
data class CanvasSnapshotParams(
    val format: String? = null,
    val quality: Double? = null,
    val maxWidth: Int? = null
)

@kotlinx.serialization.Serializable
data class A2UIPushParams(val messages: String)
