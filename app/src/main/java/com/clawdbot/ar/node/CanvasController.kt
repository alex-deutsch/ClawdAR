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

    // Support multiple WebViews for mirrored display
    private val webViews = mutableListOf<WebView>()

    @Volatile
    private var currentUrl: String? = null

    private val evalMutex = Mutex()

    /**
     * Attach a WebView to this controller.
     * Multiple WebViews can be attached for mirrored display.
     */
    fun attach(webView: WebView) {
        synchronized(webViews) {
            if (!webViews.contains(webView)) {
                webViews.add(webView)
                Log.d(TAG, "WebView attached (total: ${webViews.size})")
            }
        }
    }

    /**
     * Detach a WebView.
     */
    fun detach(webView: WebView) {
        synchronized(webViews) {
            webViews.remove(webView)
            Log.d(TAG, "WebView detached (remaining: ${webViews.size})")
        }
    }

    /**
     * Detach all WebViews.
     */
    fun detach() {
        synchronized(webViews) {
            webViews.clear()
            Log.d(TAG, "All WebViews detached")
        }
    }

    /**
     * Navigate to a URL (on all attached WebViews).
     */
    suspend fun navigate(url: String) = withContext(Dispatchers.Main) {
        synchronized(webViews) {
            if (webViews.isEmpty()) throw IllegalStateException("No WebView attached")
            currentUrl = url
            webViews.forEach { it.loadUrl(url) }
            Log.d(TAG, "Navigating to: $url (${webViews.size} WebViews)")
        }
    }

    /**
     * Load the default scaffold page.
     */
    suspend fun loadScaffold() {
        navigate(DEFAULT_SCAFFOLD_URL)
    }

    /**
     * Evaluate JavaScript on all WebViews and return result from first.
     */
    suspend fun eval(js: String): String = evalMutex.withLock {
        withContext(Dispatchers.Main) {
            val views = synchronized(webViews) { webViews.toList() }
            if (views.isEmpty()) throw IllegalStateException("No WebView attached")

            // Execute on all WebViews, return result from first
            var result: String = "null"
            views.forEachIndexed { index, wv ->
                suspendCancellableCoroutine<Unit> { continuation ->
                    wv.evaluateJavascript(js) { res ->
                        if (index == 0) result = res ?: "null"
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }
                }
            }
            result
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
     * Takes snapshot from first attached WebView.
     */
    suspend fun snapshotBase64(
        format: String = "jpeg",
        quality: Double = 0.8,
        maxWidth: Int? = null
    ): String = withContext(Dispatchers.Main) {
        val wv = synchronized(webViews) { webViews.firstOrNull() }
            ?: throw IllegalStateException("No WebView attached")

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

    /**
     * Show loading overlay with animated GIF.
     */
    suspend fun showLoading(text: String = "Thinking...") {
        val escapedText = text.replace("\"", "\\\"").replace("\n", "\\n")
        val js = """
            (function() {
                if (typeof globalThis.__clawdbot !== 'undefined' &&
                    typeof globalThis.__clawdbot.showLoading === 'function') {
                    globalThis.__clawdbot.showLoading("$escapedText");
                }
            })();
        """.trimIndent()

        eval(js)
    }

    /**
     * Hide loading overlay.
     */
    suspend fun hideLoading() {
        val js = """
            (function() {
                if (typeof globalThis.__clawdbot !== 'undefined' &&
                    typeof globalThis.__clawdbot.hideLoading === 'function') {
                    globalThis.__clawdbot.hideLoading();
                }
            })();
        """.trimIndent()

        eval(js)
    }
}
