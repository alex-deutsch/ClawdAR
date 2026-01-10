package com.clawdbot.ar.bridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Discovers ClawdBot gateway on local network using mDNS/Bonjour.
 */
class BridgeDiscovery(private val context: Context) {

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    companion object {
        private const val TAG = "BridgeDiscovery"
        private const val SERVICE_TYPE = "_clawdbot-bridge._tcp."
        private const val DISCOVERY_TIMEOUT_MS = 5000L
    }

    /**
     * Discover ClawdBot gateways on the local network.
     * Returns list of discovered endpoints.
     */
    suspend fun discover(): List<BridgeEndpoint> = withContext(Dispatchers.IO) {
        val endpoints = mutableListOf<BridgeEndpoint>()

        try {
            withTimeout(DISCOVERY_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val discoveryListener = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(regType: String) {
                            Log.d(TAG, "Discovery started for $regType")
                        }

                        override fun onServiceFound(service: NsdServiceInfo) {
                            Log.d(TAG, "Service found: ${service.serviceName}")
                            // Resolve the service to get host/port
                            resolveService(service) { endpoint ->
                                endpoint?.let { endpoints.add(it) }
                                if (endpoints.isNotEmpty() && continuation.isActive) {
                                    try {
                                        nsdManager.stopServiceDiscovery(this)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Error stopping discovery: ${e.message}")
                                    }
                                    continuation.resume(Unit)
                                }
                            }
                        }

                        override fun onServiceLost(service: NsdServiceInfo) {
                            Log.d(TAG, "Service lost: ${service.serviceName}")
                        }

                        override fun onDiscoveryStopped(serviceType: String) {
                            Log.d(TAG, "Discovery stopped for $serviceType")
                        }

                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                            Log.e(TAG, "Discovery start failed: $errorCode")
                            if (continuation.isActive) {
                                continuation.resume(Unit)
                            }
                        }

                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                            Log.e(TAG, "Discovery stop failed: $errorCode")
                        }
                    }

                    continuation.invokeOnCancellation {
                        try {
                            nsdManager.stopServiceDiscovery(discoveryListener)
                        } catch (e: Exception) {
                            Log.w(TAG, "Error stopping discovery on cancel: ${e.message}")
                        }
                    }

                    nsdManager.discoverServices(
                        SERVICE_TYPE,
                        NsdManager.PROTOCOL_DNS_SD,
                        discoveryListener
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.d(TAG, "Discovery timeout, found ${endpoints.size} endpoints")
        } catch (e: Exception) {
            Log.e(TAG, "Discovery error: ${e.message}")
        }

        endpoints
    }

    private fun resolveService(
        serviceInfo: NsdServiceInfo,
        callback: (BridgeEndpoint?) -> Unit
    ) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                callback(null)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return callback(null)
                val port = serviceInfo.port
                val displayName = extractDisplayName(serviceInfo)

                Log.d(TAG, "Resolved: $displayName at $host:$port")

                callback(
                    BridgeEndpoint(
                        host = host,
                        port = port,
                        displayName = displayName,
                        serviceName = serviceInfo.serviceName
                    )
                )
            }
        })
    }

    private fun extractDisplayName(serviceInfo: NsdServiceInfo): String {
        // Try to extract display name from TXT records or service name
        val serviceName = serviceInfo.serviceName ?: "ClawdBot Gateway"

        // Unescape Bonjour name encoding (e.g., \032 -> space)
        return serviceName
            .replace(Regex("\\\\(\\d{3})")) { match ->
                val code = match.groupValues[1].toIntOrNull() ?: return@replace match.value
                code.toChar().toString()
            }
            .trim()
    }
}
