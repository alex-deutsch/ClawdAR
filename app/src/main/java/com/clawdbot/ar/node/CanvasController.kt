package com.clawdbot.ar.node

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Controls the WebView canvas for AI visual output.
 * Supports A2UI (Agent-to-UI) rendering.
 */
class CanvasController {

    companion object {
        private const val TAG = "CanvasController"
        private const val DEFAULT_SCAFFOLD_URL = "file:///android_asset/scaffold.html"
    }

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var currentUrl: String? = null

    private val evalMutex = Mutex()

    /**
     * Attach a WebView to this controller.
     */
    fun attach(webView: WebView) {
        this.webView = webView
        Log.d(TAG, "WebView attached")
    }

    /**
     * Detach the WebView.
     */
    fun detach() {
        this.webView = null
        Log.d(TAG, "WebView detached")
    }

    /**
     * Navigate to a URL.
     */
    suspend fun navigate(url: String) = withContext(Dispatchers.Main) {
        val wv = webView ?: throw IllegalStateException("WebView not attached")
        currentUrl = url
        wv.loadUrl(url)
        Log.d(TAG, "Navigating to: $url")
    }

    /**
     * Load the default scaffold page.
     */
    suspend fun loadScaffold() {
        navigate(DEFAULT_SCAFFOLD_URL)
    }

    /**
     * Evaluate JavaScript and return result.
     */
    suspend fun eval(js: String): String = evalMutex.withLock {
        withContext(Dispatchers.Main) {
            val wv = webView ?: throw IllegalStateException("WebView not attached")

            suspendCancellableCoroutine { continuation ->
                wv.evaluateJavascript(js) { result ->
                    if (continuation.isActive) {
                        continuation.resume(result ?: "null")
                    }
                }
            }
        }
    }

    /**
     * Push A2UI messages to the canvas.
     */
    suspend fun pushA2UIMessages(messagesJson: String) {
        val escapedJson = messagesJson
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        val js = """
            (function() {
                try {
                    if (typeof globalThis.clawdbotA2UI !== 'undefined' &&
                        typeof globalThis.clawdbotA2UI.applyMessages === 'function') {
                        globalThis.clawdbotA2UI.applyMessages("$escapedJson");
                        return "ok";
                    } else {
                        return "A2UI not available";
                    }
                } catch (e) {
                    return "Error: " + e.message;
                }
            })();
        """.trimIndent()

        val result = eval(js)
        Log.d(TAG, "A2UI push result: $result")
    }

    /**
     * Reset A2UI canvas.
     */
    suspend fun resetA2UI() {
        val js = """
            (function() {
                try {
                    if (typeof globalThis.clawdbotA2UI !== 'undefined' &&
                        typeof globalThis.clawdbotA2UI.reset === 'function') {
                        globalThis.clawdbotA2UI.reset();
                        return "ok";
                    } else {
                        return "A2UI not available";
                    }
                } catch (e) {
                    return "Error: " + e.message;
                }
            })();
        """.trimIndent()

        eval(js)
        Log.d(TAG, "A2UI reset")
    }

    /**
     * Take a snapshot of the canvas as base64.
     */
    suspend fun snapshotBase64(
        format: String = "jpeg",
        quality: Double = 0.8,
        maxWidth: Int? = null
    ): String = withContext(Dispatchers.Main) {
        val wv = webView ?: throw IllegalStateException("WebView not attached")

        // Capture WebView to bitmap
        val bitmap = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        wv.draw(canvas)

        // Scale if needed
        val scaledBitmap = if (maxWidth != null && bitmap.width > maxWidth) {
            val scale = maxWidth.toFloat() / bitmap.width
            val newHeight = (bitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } else {
            bitmap
        }

        // Compress to bytes
        val outputStream = ByteArrayOutputStream()
        val compressFormat = if (format == "png") {
            Bitmap.CompressFormat.PNG
        } else {
            Bitmap.CompressFormat.JPEG
        }
        val compressQuality = (quality * 100).toInt().coerceIn(1, 100)

        scaledBitmap.compress(compressFormat, compressQuality, outputStream)

        if (scaledBitmap !== bitmap) {
            scaledBitmap.recycle()
        }

        // Encode to base64
        Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Set status message on scaffold.
     */
    suspend fun setStatus(text: String, type: String = "info") {
        val escapedText = text.replace("\"", "\\\"").replace("\n", "\\n")
        val js = """
            (function() {
                if (typeof globalThis.__clawdbot !== 'undefined' &&
                    typeof globalThis.__clawdbot.setStatus === 'function') {
                    globalThis.__clawdbot.setStatus("$escapedText", "$type");
                }
            })();
        """.trimIndent()

        eval(js)
    }
}
