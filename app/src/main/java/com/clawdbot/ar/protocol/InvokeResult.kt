package com.clawdbot.ar.protocol

/**
 * Result of an invoke command execution.
 */
data class InvokeResult(
    val ok: Boolean,
    val payloadJson: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    companion object {
        fun ok(payloadJson: String? = null) = InvokeResult(
            ok = true,
            payloadJson = payloadJson
        )

        fun error(code: String, message: String) = InvokeResult(
            ok = false,
            errorCode = code,
            errorMessage = message
        )
    }
}
