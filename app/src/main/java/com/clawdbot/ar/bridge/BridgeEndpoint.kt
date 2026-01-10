package com.clawdbot.ar.bridge

/**
 * Represents a discovered ClawdBot gateway endpoint.
 */
data class BridgeEndpoint(
    val host: String,
    val port: Int,
    val displayName: String,
    val serviceName: String? = null,
    val lanHost: String? = null,
    val tailnetDns: String? = null
) {
    override fun toString(): String = "$displayName ($host:$port)"
}
