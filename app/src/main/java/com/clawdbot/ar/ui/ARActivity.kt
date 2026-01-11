package com.clawdbot.ar.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.clawdbot.ar.R
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
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.clawdbot.ar.ClawdARApp
import com.clawdbot.ar.ConnectionState
import com.clawdbot.ar.audio.AudioStreamer
import com.clawdbot.ar.audio.ElevenLabsSTT
import com.clawdbot.ar.camera.CameraCapture
import com.clawdbot.ar.ui.theme.ClawdARTheme
import com.ffalcon.mercury.android.sdk.touch.CommonTouchCallback
import com.ffalcon.mercury.android.sdk.touch.FlingArgs
import com.ffalcon.mercury.android.sdk.touch.TouchDispatcher
import com.ffalcon.mercury.android.sdk.touch.TouchDispatcherX3
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

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

    // Speech recognition (local)
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var useLocalSpeech = false  // Flag for whether local speech recognition is available

    // Audio streaming with ElevenLabs STT
    private var audioStreamer: AudioStreamer? = null
    private var audioStreamJob: Job? = null
    private var elevenLabsSTT: ElevenLabsSTT? = null

    // Camera capture
    private var cameraCapture: CameraCapture? = null
    private var isCameraInitialized = false

    // ElevenLabs API key
    private val elevenLabsApiKey = "sk_bf29f6d10be2ae94e488c865eea47633cd6a15a006d687e1"

    // UI state
    private var showSettings by mutableStateOf(false)
    private var inputText by mutableStateOf("")
    private var isVoiceMode by mutableStateOf(false)
    private var voiceStatus by mutableStateOf("Tap to speak")
    private var isCameraMode by mutableStateOf(false)
    private var cameraStatus by mutableStateOf("Tap to capture")

    // Bottom menu state: 0 = MIC, 1 = Camera, 2 = Settings
    private var selectedMenuIndex by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on for AR experience
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Apply immersive mode
        applyImmersiveMode()

        // Initialize Mercury SDK touch dispatcher
        touchDispatcher = TouchDispatcherX3(TouchDispatcher.Source.Activity)

        // Initialize speech recognizer
        initSpeechRecognizer()

        setContent {
            ClawdARTheme {
                // Mirror content for AR glasses (full content on each eye)
                // Left half of screen → left eye, right half → right eye
                // Each Box gets 50% width (640px on 1280px screen)
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    // Left eye content
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        ARScreen()
                    }
                    // Right eye content (mirror of left)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        ARScreen()
                    }
                }
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

    private fun initSpeechRecognizer() {
        // Use ElevenLabs for speech-to-text (local speech recognition doesn't work on AR glasses)
        useLocalSpeech = false
        Log.d(TAG, "Using ElevenLabs STT for voice input")
        voiceStatus = "Voice ready"

        // Initialize audio streamer
        audioStreamer = AudioStreamer(this)
        Log.d(TAG, "AudioStreamer initialized")

        // Initialize ElevenLabs STT
        elevenLabsSTT = ElevenLabsSTT(elevenLabsApiKey)
        Log.d(TAG, "ElevenLabs STT initialized")

        // Skip local speech recognizer initialization
        return

        /*
        // Original local speech recognition code (disabled - doesn't work on RayNeo glasses)
        Log.d(TAG, "Creating local SpeechRecognizer...")
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Speech: Ready for speech")
                    voiceStatus = "Listening..."
                    isListening = true
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Speech: Beginning of speech")
                    voiceStatus = "Hearing you..."
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.d(TAG, "Speech: End of speech")
                    voiceStatus = "Processing..."
                    isListening = false
                }

                override fun onError(error: Int) {
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                        SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        else -> "Error: $error"
                    }
                    Log.e(TAG, "Speech error: $errorMsg")
                    voiceStatus = errorMsg
                    isListening = false

                    // Auto-restart if still in voice mode (for continuous listening)
                    if (isVoiceMode && error != SpeechRecognizer.ERROR_AUDIO) {
                        startListening()
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val recognizedText = matches?.firstOrNull() ?: ""
                    Log.d(TAG, "Speech result: $recognizedText")

                    if (recognizedText.isNotEmpty()) {
                        voiceStatus = "Sending: $recognizedText"
                        sendMessage(recognizedText)
                    }

                    // Continue listening if still in voice mode
                    if (isVoiceMode) {
                        startListening()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull() ?: ""
                    if (partial.isNotEmpty()) {
                        voiceStatus = partial
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        */
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            voiceStatus = "Requesting mic..."
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }

        if (useLocalSpeech) {
            // Use local speech recognition
            startLocalSpeechRecognition()
        } else {
            // Use audio streaming to gateway
            startAudioStreaming()
        }
    }

    private fun startLocalSpeechRecognition() {
        if (speechRecognizer == null) {
            Log.e(TAG, "SpeechRecognizer is null, reinitializing...")
            initSpeechRecognizer()
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            voiceStatus = "Starting..."
            Log.d(TAG, "Starting local speech recognition...")
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognition: ${e.message}", e)
            voiceStatus = "Failed: ${e.message}"
        }
    }

    private fun startAudioStreaming() {
        Log.d(TAG, "Starting audio recording for ElevenLabs STT...")

        val streamer = audioStreamer ?: run {
            Log.e(TAG, "AudioStreamer not initialized")
            voiceStatus = "Audio error"
            return
        }

        if (streamer.isRecording.value) {
            Log.w(TAG, "Already recording audio")
            return
        }

        // Start capturing audio
        if (!streamer.startRecording()) {
            voiceStatus = "Mic unavailable"
            return
        }

        // Observe audio status
        audioStreamJob = lifecycleScope.launch {
            streamer.status.collectLatest { status ->
                voiceStatus = status
            }
        }

        isListening = true
        Log.d(TAG, "Audio recording started")
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            100 -> {
                // Microphone permission
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Mic permission granted, starting listening")
                    startListening()
                } else {
                    Log.e(TAG, "Mic permission denied")
                    voiceStatus = "Mic denied"
                    isVoiceMode = false
                }
            }
            101 -> {
                // Camera permission
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Camera permission granted, capturing photo")
                    captureAndSendPhoto()
                } else {
                    Log.e(TAG, "Camera permission denied")
                    cameraStatus = "Camera denied"
                    isCameraMode = false
                }
            }
        }
    }

    private fun stopListening() {
        Log.d(TAG, "Stopping listening (useLocalSpeech=$useLocalSpeech)")

        if (useLocalSpeech) {
            // Stop local speech recognition
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop speech recognition: ${e.message}")
            }
        } else {
            // Stop audio streaming
            stopAudioStreaming()
        }

        isListening = false
        voiceStatus = "Tap to speak"
    }

    private fun stopAudioStreaming() {
        Log.d(TAG, "Stopping audio recording and transcribing...")

        audioStreamJob?.cancel()
        audioStreamJob = null

        val streamer = audioStreamer ?: return
        streamer.stopRecording()

        // Get buffered audio and transcribe
        val audioData = streamer.getBufferedAudio()
        if (audioData.isEmpty()) {
            Log.w(TAG, "No audio data recorded")
            voiceStatus = "No audio"
            return
        }

        voiceStatus = "Transcribing..."
        Log.d(TAG, "Transcribing ${audioData.size} bytes of audio...")

        lifecycleScope.launch {
            try {
                val stt = elevenLabsSTT ?: run {
                    Log.e(TAG, "ElevenLabs STT not initialized")
                    voiceStatus = "STT error"
                    return@launch
                }

                val transcription = stt.transcribe(audioData)

                if (transcription.isNullOrBlank()) {
                    Log.w(TAG, "No transcription returned")
                    voiceStatus = "No speech detected"
                } else {
                    Log.d(TAG, "Transcription: $transcription")
                    voiceStatus = "Sending..."
                    sendMessage(transcription)
                    voiceStatus = "Sent: $transcription"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed: ${e.message}", e)
                voiceStatus = "Transcription failed"
            }
        }

        // Clear buffer for next recording
        streamer.clearBuffer()
    }

    /**
     * Capture a photo and send it to the AI assistant.
     */
    private fun captureAndSendPhoto() {
        Log.d(TAG, "captureAndSendPhoto called")

        // Check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            cameraStatus = "Requesting camera..."
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
            return
        }

        isCameraMode = true
        cameraStatus = "Initializing..."

        lifecycleScope.launch {
            try {
                // Initialize camera if needed
                if (cameraCapture == null) {
                    cameraCapture = CameraCapture(this@ARActivity)
                }

                if (!isCameraInitialized) {
                    cameraStatus = "Starting camera..."
                    val initialized = cameraCapture?.initialize(this@ARActivity)
                    if (initialized != true) {
                        Log.e(TAG, "Failed to initialize camera")
                        cameraStatus = "Camera init failed"
                        isCameraMode = false
                        return@launch
                    }
                    isCameraInitialized = true
                }

                // Capture photo
                cameraStatus = "Capturing..."
                val result = cameraCapture?.capturePhoto()

                if (result == null) {
                    Log.e(TAG, "Photo capture failed")
                    cameraStatus = "Capture failed"
                    isCameraMode = false
                    return@launch
                }

                Log.d(TAG, "Photo captured: ${result.width}x${result.height}, ${result.base64.length} chars")
                cameraStatus = "Sending..."

                // Show the photo on canvas with loading indicator
                runtime.setCurrentUserMessage("[Photo captured]")
                val msgJson = """[{"type":"text","content":"You: [Photo sent]"},{"type":"text","content":"Analyzing image..."}]"""
                runtime.canvas.pushA2UIMessages(msgJson)

                // Send to AI with a default prompt
                runtime.bridge.sendChatMessageWithImage(
                    text = "What do you see in this image?",
                    imageBase64 = result.base64,
                    imageMimeType = result.mimeType
                )

                cameraStatus = "Sent!"
                runtime.canvas.setStatus("Photo sent", "info")
                Log.d(TAG, "Photo sent to AI")

            } catch (e: Exception) {
                Log.e(TAG, "Camera capture error: ${e.message}", e)
                cameraStatus = "Error: ${e.message}"
                runtime.canvas.setStatus("Camera error", "error")
            } finally {
                // Reset camera mode after a delay
                kotlinx.coroutines.delay(2000)
                isCameraMode = false
                cameraStatus = "Tap to capture"
            }
        }
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
        Log.d(TAG, "Click: selectedMenuIndex=$selectedMenuIndex, isVoiceMode=$isVoiceMode, isCameraMode=$isCameraMode")

        when (selectedMenuIndex) {
            0 -> {
                // MIC selected - toggle voice recording
                if (isVoiceMode) {
                    // Stop recording
                    stopListening()
                    isVoiceMode = false
                } else {
                    // Start recording
                    isVoiceMode = true
                    startListening()
                }
            }
            1 -> {
                // Camera selected - capture photo
                captureAndSendPhoto()
            }
            2 -> {
                // Settings selected - toggle settings panel
                showSettings = !showSettings
            }
        }
    }

    private fun handleDoubleClick() {
        // Reserved for system - do nothing
        Log.d(TAG, "Double-click: reserved for system")
    }

    private fun handleLongClick() {
        // Reserved for system - do nothing
        Log.d(TAG, "Long-click: reserved for system")
    }

    private fun handleTripleClick() {
        // Triple-click: reconnect (useful for debugging)
        Log.d(TAG, "Triple-click: reconnecting")
        lifecycleScope.launch {
            runtime.canvas.setStatus("Reconnecting...", "info")
            runtime.disconnect()
            runtime.connect()
        }
    }

    private fun handleSlideForward() {
        // Swipe forward: move selection right
        Log.d(TAG, "Slide forward: next menu item")
        if (selectedMenuIndex < 2) {
            selectedMenuIndex++
        }
    }

    private fun handleSlideBackward() {
        // Swipe backward: move selection left
        Log.d(TAG, "Slide backward: previous menu item")
        if (selectedMenuIndex > 0) {
            selectedMenuIndex--
        }
    }

    /**
     * Mirrors content to both lenses by rendering two identical side-by-side views.
     * This matches how BaseMirrorActivity in Mercury SDK works.
     */
    @Composable
    private fun MirroredDisplay(content: @Composable () -> Unit) {
        Row(
            modifier = Modifier.fillMaxSize()
        ) {
            // Left lens
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                content()
            }
            // Right lens
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                content()
            }
        }
    }

    @Composable
    private fun ARScreen() {
        val connectionState by runtime.connectionState.collectAsState()
        val statusMessage by runtime.statusMessage.collectAsState()
        val isLoading by runtime.isLoading.collectAsState()
        val hasContent by runtime.hasContent.collectAsState()

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

            // FireClawd animation - center when no content, bottom-left when content showing
            FireClawdAnimation(
                isPlaying = isLoading,
                showThinkingText = isLoading,
                isCompact = hasContent,
                modifier = Modifier.align(
                    if (hasContent) Alignment.BottomStart else Alignment.Center
                ).then(
                    if (hasContent) Modifier.padding(start = 16.dp, bottom = 70.dp) else Modifier
                )
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
                    voiceStatusText = voiceStatus,
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

            // Bottom menu bar
            BottomMenuBar(
                selectedIndex = selectedMenuIndex,
                isVoiceActive = isVoiceMode,
                voiceStatus = voiceStatus,
                isCameraActive = isCameraMode,
                cameraStatusText = cameraStatus,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
            )
        }
    }

    @Composable
    private fun FireClawdAnimation(
        isPlaying: Boolean,
        showThinkingText: Boolean,
        isCompact: Boolean = false,
        modifier: Modifier = Modifier
    ) {
        val compositionResult = rememberLottieComposition(
            LottieCompositionSpec.RawRes(R.raw.flame)
        )
        val progress by animateLottieCompositionAsState(
            composition = compositionResult.value,
            iterations = LottieConstants.IterateForever,
            isPlaying = isPlaying
        )

        // Log composition state for debugging
        LaunchedEffect(compositionResult.isLoading, compositionResult.isFailure) {
            Log.d("FireClawdAnimation", "Lottie loading=${compositionResult.isLoading}, failure=${compositionResult.isFailure}, error=${compositionResult.error}")
        }

        val animationSize = if (isCompact) 60.dp else 150.dp
        val textSize = if (isCompact) 14.sp else 24.sp

        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LottieAnimation(
                composition = compositionResult.value,
                progress = { if (isPlaying) progress else 0f },
                modifier = Modifier.size(animationSize)
            )
            if (showThinkingText) {
                Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))
                Text(
                    text = "Thinking...",
                    color = Color(0xFFFF6B35),
                    fontSize = textSize,
                    fontWeight = FontWeight.Medium
                )
            }
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
                    .size(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(indicatorColor)
            )

            Text(
                text = statusMessage,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }

    @Composable
    private fun InputOverlay(
        text: String,
        isVoiceMode: Boolean,
        voiceStatusText: String,
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
                    text = voiceStatusText,
                    color = Color(0xFF00AAFF),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 28.sp
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
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )

                HorizontalDivider(color = Color(0x33FFFFFF))

                val connectionState by runtime.connectionState.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Connection", color = Color(0xFFAAAAAA), fontSize = 22.sp)
                    Text(
                        text = connectionState.name,
                        fontSize = 22.sp,
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
    private fun BottomMenuBar(
        selectedIndex: Int,
        isVoiceActive: Boolean,
        voiceStatus: String,
        isCameraActive: Boolean,
        cameraStatusText: String,
        modifier: Modifier = Modifier
    ) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xDD1A1A1A))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // MIC button
            MenuIcon(
                icon = "🎤",
                label = if (isVoiceActive) voiceStatus else "Voice",
                isSelected = selectedIndex == 0,
                isActive = isVoiceActive,
                activeColor = Color(0xFF00AAFF)
            )

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(32.dp)
                    .background(Color(0x44FFFFFF))
            )

            // Camera button
            MenuIcon(
                icon = "📷",
                label = if (isCameraActive) cameraStatusText else "Photo",
                isSelected = selectedIndex == 1,
                isActive = isCameraActive,
                activeColor = Color(0xFF00FF88)
            )

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(32.dp)
                    .background(Color(0x44FFFFFF))
            )

            // Settings button
            MenuIcon(
                icon = "⚙️",
                label = "Settings",
                isSelected = selectedIndex == 2,
                isActive = false,
                activeColor = Color(0xFFFF6B35)
            )
        }
    }

    @Composable
    private fun MenuIcon(
        icon: String,
        label: String,
        isSelected: Boolean,
        isActive: Boolean,
        activeColor: Color
    ) {
        val backgroundColor = when {
            isActive -> activeColor.copy(alpha = 0.3f)
            isSelected -> Color(0x44FFFFFF)
            else -> Color.Transparent
        }
        val textColor = when {
            isActive -> activeColor
            isSelected -> Color.White
            else -> Color(0x88FFFFFF)
        }

        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(backgroundColor)
                .then(
                    if (isSelected) Modifier.padding(1.dp) else Modifier
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = icon,
                fontSize = 28.sp
            )
            Text(
                text = label,
                color = textColor,
                fontSize = 16.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
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
                    // Force WebView to fill its parent
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        // Force WebView to use its actual width, not device width
                        useWideViewPort = false
                        loadWithOverviewMode = false
                    }
                    setBackgroundColor(android.graphics.Color.BLACK)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            Log.d(TAG, "Page started loading: $url")
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            Log.d(TAG, "Page loaded: $url, WebView size: ${view?.width}x${view?.height}")
                        }
                        override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                            Log.e(TAG, "WebView error: $errorCode - $description at $failingUrl")
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
                canvasController.detach(webView)
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
                Log.d(TAG, "sendMessage called with: $text")

                // Track current user message (also triggers loading state)
                runtime.setCurrentUserMessage(text)

                // Show the message on canvas with "thinking" indicator
                val msgJson = """[{"type":"text","content":"You: $text"},{"type":"text","content":"Thinking..."}]"""
                Log.d(TAG, "Pushing to canvas: $msgJson")
                runtime.canvas.pushA2UIMessages(msgJson)
                Log.d(TAG, "Canvas push completed")

                runtime.bridge.sendChatMessage(text)
                runtime.canvas.setStatus("Sent", "info")
                Log.d(TAG, "Message sent to gateway: $text")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message: ${e.message}", e)
                runtime.canvas.setStatus("Send failed", "error")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        speechRecognizer = null
        audioStreamer?.release()
        audioStreamer = null
        cameraCapture?.release()
        cameraCapture = null
        runtime.cleanup()
    }
}
