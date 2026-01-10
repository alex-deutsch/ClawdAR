package com.clawdbot.ar.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.clawdbot.ar.ClawdARApp
import com.clawdbot.ar.ConnectionState
import com.clawdbot.ar.ui.theme.ClawdARTheme
import com.ffalcon.mercury.android.sdk.touch.CommonTouchCallback
import com.ffalcon.mercury.android.sdk.touch.FlingArgs
import com.ffalcon.mercury.android.sdk.touch.TouchDispatcher
import com.ffalcon.mercury.android.sdk.touch.TouchDispatcherX3
import kotlinx.coroutines.launch

/**
 * Main AR Activity for ClawdAR.
 * Uses Mercury SDK's TouchDispatcherX3 for temple gesture support.
 */
class ARActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ARActivity"
    }

    private val app: ClawdARApp by lazy {
        application as ClawdARApp
    }

    private val runtime by lazy { app.runtime }

    // Mercury SDK touch dispatcher for temple gestures
    private lateinit var touchDispatcher: TouchDispatcherX3

    // UI state
    private var showSettings by mutableStateOf(false)
    private var inputText by mutableStateOf("")
    private var isVoiceMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on for AR experience
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Apply immersive mode
        applyImmersiveMode()

        // Initialize Mercury SDK touch dispatcher
        touchDispatcher = TouchDispatcherX3(TouchDispatcher.Source.Activity)

        setContent {
            ClawdARTheme {
                ARScreen()
            }
        }

        // Auto-connect on launch
        lifecycleScope.launch {
            runtime.connect()
        }
    }

    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    // Touch callback extending Mercury SDK's CommonTouchCallback
    private val touchCallback = object : CommonTouchCallback() {
        override fun onTPClick(): Boolean {
            Log.d(TAG, "Temple: Click")
            handleClick()
            return true
        }

        override fun onTPDoubleClick(): Boolean {
            Log.d(TAG, "Temple: Double-click")
            handleDoubleClick()
            return true
        }

        override fun onTPLongClick(): Boolean {
            Log.d(TAG, "Temple: Long-click")
            handleLongClick()
            return true
        }

        override fun onTPTripleClick() {
            Log.d(TAG, "Temple: Triple-click")
            handleTripleClick()
        }

        override fun onTPSlideForward(args: FlingArgs): Boolean {
            Log.d(TAG, "Temple: Slide forward")
            handleSlideForward()
            return true
        }

        override fun onTPSlideBackward(args: FlingArgs): Boolean {
            Log.d(TAG, "Temple: Slide backward")
            handleSlideBackward()
            return true
        }

        override fun onTPSlideUpwards(args: FlingArgs): Boolean {
            Log.d(TAG, "Temple: Slide up")
            return true
        }

        override fun onTPSlideDownwards(args: FlingArgs): Boolean {
            Log.d(TAG, "Temple: Slide down")
            return true
        }

        override fun onActionDown() {}
        override fun onActionUp() {}
    }

    // Dispatch touch events to Mercury SDK
    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        event?.let {
            touchDispatcher.onMotionEvent(it, touchCallback)
        }
        return super.dispatchTouchEvent(event)
    }

    // Dispatch key events (temple button presses)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        touchDispatcher.onKeyEvent(event, touchCallback)
        return super.dispatchKeyEvent(event)
    }

    // Gesture handlers
    private fun handleClick() {
        if (inputText.isNotEmpty()) {
            sendMessage(inputText)
            inputText = ""
        }
    }

    private fun handleDoubleClick() {
        isVoiceMode = !isVoiceMode
    }

    private fun handleLongClick() {
        showSettings = !showSettings
    }

    private fun handleTripleClick() {
        lifecycleScope.launch {
            runtime.disconnect()
            runtime.connect()
        }
    }

    private fun handleSlideForward() {
        lifecycleScope.launch {
            runtime.canvas.eval("window.scrollBy(0, 100)")
        }
    }

    private fun handleSlideBackward() {
        lifecycleScope.launch {
            runtime.canvas.eval("window.scrollBy(0, -100)")
        }
    }

    @Composable
    private fun ARScreen() {
        val connectionState by runtime.connectionState.collectAsState()
        val statusMessage by runtime.statusMessage.collectAsState()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Canvas WebView - main content area
            CanvasWebView(
                modifier = Modifier.fillMaxSize(),
                canvasController = runtime.canvas
            )

            // Status bar overlay at top
            StatusBar(
                connectionState = connectionState,
                statusMessage = statusMessage,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            )

            // Input area at bottom (when active)
            if (inputText.isNotEmpty() || isVoiceMode) {
                InputOverlay(
                    text = inputText,
                    isVoiceMode = isVoiceMode,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                )
            }

            // Settings panel
            if (showSettings) {
                SettingsPanel(
                    onDismiss = { showSettings = false },
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Gesture hint
            GestureHint(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
            )
        }
    }

    @Composable
    private fun StatusBar(
        connectionState: ConnectionState,
        statusMessage: String,
        modifier: Modifier = Modifier
    ) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x88000000))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val indicatorColor = when (connectionState) {
                ConnectionState.CONNECTED -> Color(0xFF00FF88)
                ConnectionState.CONNECTING -> Color(0xFFFFAA00)
                ConnectionState.DISCONNECTED -> Color(0xFFFF4444)
            }

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(indicatorColor)
            )

            Text(
                text = statusMessage,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }

    @Composable
    private fun InputOverlay(
        text: String,
        isVoiceMode: Boolean,
        modifier: Modifier = Modifier
    ) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xCC1A1A1A))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isVoiceMode) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF00AAFF))
                )
                Text(
                    text = "Listening...",
                    color = Color(0xFF00AAFF),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 16.sp
                )
            }
        }
    }

    @Composable
    private fun SettingsPanel(
        onDismiss: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Card(
            modifier = modifier
                .width(320.dp)
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xDD1A1A1A)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "ClawdAR Settings",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                HorizontalDivider(color = Color(0x33FFFFFF))

                val connectionState by runtime.connectionState.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Connection", color = Color(0xFFAAAAAA))
                    Text(
                        text = connectionState.name,
                        color = when (connectionState) {
                            ConnectionState.CONNECTED -> Color(0xFF00FF88)
                            ConnectionState.CONNECTING -> Color(0xFFFFAA00)
                            ConnectionState.DISCONNECTED -> Color(0xFFFF4444)
                        }
                    )
                }

                Button(
                    onClick = {
                        lifecycleScope.launch { runtime.connect() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00AAFF))
                ) {
                    Text("Reconnect")
                }

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close", color = Color.White)
                }
            }
        }
    }

    @Composable
    private fun GestureHint(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x44000000))
                .padding(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text("Temple Gestures", color = Color(0x88FFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Text("Click: Confirm", color = Color(0x66FFFFFF), fontSize = 9.sp)
            Text("2x: Voice", color = Color(0x66FFFFFF), fontSize = 9.sp)
            Text("Hold: Settings", color = Color(0x66FFFFFF), fontSize = 9.sp)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun CanvasWebView(
        modifier: Modifier = Modifier,
        canvasController: com.clawdbot.ar.node.CanvasController
    ) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    setBackgroundColor(android.graphics.Color.BLACK)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            Log.d(TAG, "Page loaded: $url")
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            Log.d(TAG, "JS: ${consoleMessage.message()}")
                            return true
                        }
                    }
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onA2UIAction(payloadJson: String) {
                            Log.d(TAG, "A2UI Action: $payloadJson")
                        }
                    }, "__clawdbotAndroid")

                    canvasController.attach(this)
                    loadUrl("file:///android_asset/scaffold.html")
                }
            },
            modifier = modifier,
            onRelease = { webView ->
                canvasController.detach()
                webView.destroy()
            }
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                handleClick()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (showSettings) {
                    showSettings = false
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun sendMessage(text: String) {
        lifecycleScope.launch {
            try {
                runtime.bridge.sendChatMessage(text)
                runtime.canvas.setStatus("Sent: $text", "info")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message: ${e.message}")
                runtime.canvas.setStatus("Send failed", "error")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runtime.cleanup()
    }
}
