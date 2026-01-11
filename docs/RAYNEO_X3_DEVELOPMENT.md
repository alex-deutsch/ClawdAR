# RayNeo X3 Pro AR Glasses Development Guide

Comprehensive guide for developing native Android apps for RayNeo X3 Pro AR glasses using the Mercury SDK.

## Table of Contents

1. [Overview](#overview)
2. [Hardware Specifications](#hardware-specifications)
3. [SDK Setup](#sdk-setup)
4. [Making Apps Native (Not Virtual)](#making-apps-native)
5. [Display & Mirroring](#display--mirroring)
6. [Temple Gesture Handling](#temple-gesture-handling)
7. [Audio & Microphone](#audio--microphone)
8. [Installing & Running Apps](#installing--running-apps)
9. [Common Issues & Solutions](#common-issues--solutions)
10. [Best Practices](#best-practices)

---

## Overview

The RayNeo X3 Pro are standalone Android AR glasses with:
- Dual micro-OLED displays (one per eye)
- Temple touchpad for gesture input
- Built-in microphone and speakers
- Qualcomm Snapdragon XR2 Gen 1 processor
- Android-based OS (RayNeo OS)

Apps can run in two modes:
1. **Virtual mode** (default): Apps run in a virtual Android environment with standard UI
2. **Native mode**: Apps render directly to the AR displays with full control

---

## Hardware Specifications

| Component | Specification |
|-----------|---------------|
| Display | Dual 1920x1080 micro-OLED (per eye) |
| Combined Resolution | 1280x480 (640x480 per eye in mirror mode) |
| Processor | Snapdragon XR2 Gen 1 |
| RAM | 6GB |
| Storage | 128GB |
| OS | Android 12 (RayNeo OS) |
| Input | Temple touchpad, voice, cameras |

---

## SDK Setup

### 1. Add Maven Repository

In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Mercury SDK repository
        maven {
            url = uri("https://pkgs.dev.azure.com/niconi/niconi/_packaging/niconi/maven/v1")
            credentials {
                username = "niconi"
                password = "YOUR_AZURE_DEVOPS_PAT"
            }
        }
    }
}
```

### 2. Add SDK Dependency

In `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.niconi.niconi:niconi-mercury-android-sdk:0.2.2")
}
```

### 3. Required Permissions

In `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.CAMERA" />
```

---

## Making Apps Native

By default, apps run in a virtual Android environment. To make your app run natively on the AR displays, you must configure the manifest properly.

### Required Manifest Configuration

```xml
<application
    android:icon="@mipmap/ic_launcher"
    android:label="@string/app_name"
    android:theme="@style/Theme.YourApp">

    <activity
        android:name=".ui.ARActivity"
        android:exported="true"
        android:screenOrientation="landscape"
        android:configChanges="orientation|screenSize|keyboardHidden"
        android:launchMode="singleTask">

        <!-- CRITICAL: This meta-data makes the app native -->
        <meta-data
            android:name="com.rayneo.app.type"
            android:value="native" />

        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>
</application>
```

### Key Points

1. **`com.rayneo.app.type` = `native`**: This is the critical setting that makes your app render directly to AR displays instead of running in the virtual environment.

2. **`screenOrientation="landscape"`**: AR glasses displays are landscape oriented.

3. **`launchMode="singleTask"`**: Prevents multiple instances.

4. **`configChanges`**: Handle orientation changes yourself to prevent activity recreation.

### Without Native Flag

Without the `com.rayneo.app.type=native` meta-data:
- App runs in a virtual Android window
- Standard Android UI is rendered
- No direct access to AR displays
- Gestures work differently

---

## Display & Mirroring

### Display Architecture

The RayNeo X3 Pro presents as a single 1280x480 display to Android:
- Left half (0-639) → Left eye
- Right half (640-1279) → Right eye

Each eye sees 640x480 pixels.

### Mirror Mode Implementation

To show the same content on both eyes, render your UI twice side-by-side:

```kotlin
@Composable
fun MirroredDisplay() {
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
            YourContent()
        }
        // Right eye content (identical)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            YourContent()
        }
    }
}
```

### WebView Mirroring

For WebView-based content, attach multiple WebViews to a controller:

```kotlin
class CanvasController {
    private val webViews = mutableListOf<WebView>()

    fun attach(webView: WebView) {
        synchronized(webViews) {
            webViews.add(webView)
        }
    }

    suspend fun eval(js: String): String {
        // Execute on ALL WebViews
        webViews.forEach { wv ->
            wv.evaluateJavascript(js) { }
        }
    }
}
```

### WebView Layout Fix

WebViews may not fill their container properly. Force MATCH_PARENT:

```kotlin
WebView(context).apply {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    // ... rest of config
}
```

### Immersive Mode

Hide system bars for full AR experience:

```kotlin
private fun applyImmersiveMode() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
}
```

---

## Temple Gesture Handling

### Gesture System Overview

The RayNeo X3 Pro has a touchpad on the right temple arm supporting:
- Single tap
- Double tap (RESERVED - opens system menu)
- Long press/Hold (RESERVED - goes back to launcher)
- Triple tap
- Swipe forward (toward front of glasses)
- Swipe backward (toward back of head)
- Swipe up
- Swipe down

### IMPORTANT: Reserved Gestures

**Double-tap and long-press are reserved for system functions!**
- Double-tap: Opens system settings/menu
- Long-press: Returns to launcher/home

Do NOT rely on these gestures in your app - they will trigger system actions.

### Mercury SDK TouchDispatcher

```kotlin
import com.ffalcon.mercury.android.sdk.touch.CommonTouchCallback
import com.ffalcon.mercury.android.sdk.touch.FlingArgs
import com.ffalcon.mercury.android.sdk.touch.TouchDispatcher
import com.ffalcon.mercury.android.sdk.touch.TouchDispatcherX3

class ARActivity : ComponentActivity() {

    private lateinit var touchDispatcher: TouchDispatcherX3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        touchDispatcher = TouchDispatcherX3(TouchDispatcher.Source.Activity)
    }

    // Touch callback implementation
    private val touchCallback = object : CommonTouchCallback() {
        override fun onTPClick(): Boolean {
            // Single tap - use this as primary action
            handleClick()
            return true
        }

        override fun onTPDoubleClick(): Boolean {
            // RESERVED FOR SYSTEM - avoid using
            return true
        }

        override fun onTPLongClick(): Boolean {
            // RESERVED FOR SYSTEM - avoid using
            return true
        }

        override fun onTPTripleClick() {
            // Triple tap - available for app use
            handleTripleClick()
        }

        override fun onTPSlideForward(args: FlingArgs): Boolean {
            // Swipe toward front of glasses
            handleSwipeForward()
            return true
        }

        override fun onTPSlideBackward(args: FlingArgs): Boolean {
            // Swipe toward back of head
            handleSwipeBackward()
            return true
        }

        override fun onTPSlideUpwards(args: FlingArgs): Boolean {
            handleSwipeUp()
            return true
        }

        override fun onTPSlideDownwards(args: FlingArgs): Boolean {
            handleSwipeDown()
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
}
```

### Recommended Gesture Mapping

| Gesture | Recommended Use |
|---------|-----------------|
| Single tap | Primary action (confirm, select, toggle) |
| Triple tap | Secondary action (refresh, reconnect) |
| Swipe forward | Navigate right, next item, scroll down |
| Swipe backward | Navigate left, previous item, scroll up |
| Swipe up | Volume up, dismiss |
| Swipe down | Volume down, show menu |

---

## Audio & Microphone

### Local Speech Recognition Issues

**Problem**: Android's built-in SpeechRecognizer does NOT work reliably on RayNeo glasses.

**Solution**: Use a cloud-based STT service like ElevenLabs.

### ElevenLabs STT Implementation

```kotlin
class ElevenLabsSTT(private val apiKey: String) {

    suspend fun transcribe(audioData: ByteArray): String? {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        // Create WAV file from PCM data
        val wavData = createWavFile(audioData, 16000, 1, 16)

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model_id", "scribe_v1")
            .addFormDataPart(
                "file",
                "audio.wav",
                wavData.toRequestBody("audio/wav".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/speech-to-text")
            .addHeader("xi-api-key", apiKey)
            .post(requestBody)
            .build()

        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    json.optString("text", null)
                } else {
                    null
                }
            }
        }
    }
}
```

### Audio Recording

```kotlin
class AudioStreamer(context: Context) {
    private var audioRecord: AudioRecord? = null
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    fun startRecording(): Boolean {
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize * 2
        )

        audioRecord?.startRecording()
        return true
    }

    fun stopRecording() {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
```

---

## Installing & Running Apps

### Prerequisites

1. Enable Developer Mode on glasses
2. Enable USB Debugging
3. Install ADB on your computer

### Enable Developer Mode

1. Go to Settings → About Device
2. Tap "Build Number" 7 times
3. Go back to Settings → Developer Options
4. Enable "USB Debugging"

### ADB Commands

```bash
# Check device connection
adb devices

# Install APK
adb install -r app-debug.apk

# Install with replacement (force)
adb install -r -d app-debug.apk

# Uninstall app
adb uninstall com.yourpackage.name

# View logs
adb logcat -s "YourTag"

# View all logs
adb logcat

# Launch app
adb shell am start -n com.yourpackage/.MainActivity

# Force stop app
adb shell am force-stop com.yourpackage

# Clear app data
adb shell pm clear com.yourpackage
```

### Gradle Build & Install

```bash
# Build debug APK
./gradlew assembleDebug

# Build and install in one command
./gradlew installDebug

# Clean build
./gradlew clean assembleDebug
```

### APK Location

After build, APK is at:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## Common Issues & Solutions

### Issue: App runs in virtual mode, not native

**Cause**: Missing native app type meta-data in manifest.

**Solution**: Add to your activity in AndroidManifest.xml:
```xml
<meta-data
    android:name="com.rayneo.app.type"
    android:value="native" />
```

### Issue: Content only shows on one eye

**Cause**: Not rendering content for both halves of the display.

**Solution**: Use a Row with two identical boxes, each with weight(1f):
```kotlin
Row(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f).fillMaxHeight()) { Content() }
    Box(Modifier.weight(1f).fillMaxHeight()) { Content() }
}
```

### Issue: WebView shows as thin line / doesn't fill space

**Cause**: WebView not getting proper layout params from Compose.

**Solution**: Explicitly set MATCH_PARENT layout params:
```kotlin
WebView(context).apply {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
}
```

### Issue: Double-tap/long-press triggers system action

**Cause**: These gestures are reserved by RayNeo OS.

**Solution**: Don't use double-tap or long-press for app functions. Use single tap, triple tap, and swipes instead.

### Issue: Speech recognition doesn't work

**Cause**: Android's SpeechRecognizer isn't supported on RayNeo glasses.

**Solution**: Use cloud-based STT (ElevenLabs, Google Cloud Speech, etc.).

### Issue: App icon shows default Android icon

**Cause**: Adaptive icon XML files in `mipmap-anydpi-v26` override PNG icons.

**Solution**: Either:
1. Delete the `mipmap-anydpi-v26` folder, OR
2. Create proper adaptive icon foreground/background drawables

```bash
rm -rf app/src/main/res/mipmap-anydpi-v26/
```

### Issue: TCP connection drops after idle

**Cause**: No keepalive mechanism.

**Solution**: Implement heartbeat ping:
```kotlin
private fun startHeartbeat() {
    scope.launch {
        while (isActive) {
            delay(30000) // 30 seconds
            sendPing()
        }
    }
}
```

### Issue: CSS selectors not matching in WebView

**Cause**: Using class selector (`.name`) instead of ID selector (`#name`) or vice versa.

**Solution**: Verify HTML element uses `id="name"` for `#name` selector or `class="name"` for `.name` selector.

---

## Best Practices

### UI Design for AR

1. **Large text**: Minimum 20sp for body text, 28sp+ for important info
2. **High contrast**: Use light text on dark backgrounds
3. **Simple layouts**: Avoid complex nested views
4. **Center important content**: Users focus on center of vision
5. **Avoid edges**: Content at screen edges is harder to see

### Performance

1. **Minimize recomposition**: Use `remember` and stable state
2. **Lazy loading**: Don't load all content at once
3. **Background processing**: Use coroutines for network/IO
4. **Efficient WebView**: Disable unnecessary features

### Gestures

1. **Single tap for primary action**: Most intuitive
2. **Swipe for navigation**: Forward/backward for next/previous
3. **Visual feedback**: Show which item is selected
4. **Avoid reserved gestures**: Don't use double-tap or long-press

### Network

1. **Implement reconnection logic**: Connections will drop
2. **Use heartbeats**: Keep long-lived connections alive
3. **Handle offline gracefully**: Show status to user
4. **Timeout appropriately**: Balance responsiveness vs reliability

---

## Project Structure Example

```
app/
├── src/main/
│   ├── java/com/yourpackage/
│   │   ├── YourApp.kt              # Application class
│   │   ├── NodeRuntime.kt          # Core orchestration
│   │   ├── ui/
│   │   │   └── ARActivity.kt       # Main AR activity
│   │   ├── bridge/
│   │   │   ├── BridgeSession.kt    # Network connection
│   │   │   └── BridgeDiscovery.kt  # Service discovery
│   │   ├── node/
│   │   │   └── CanvasController.kt # WebView management
│   │   ├── audio/
│   │   │   ├── AudioStreamer.kt    # Mic recording
│   │   │   └── ElevenLabsSTT.kt    # Speech-to-text
│   │   └── util/
│   │       └── SecurePrefs.kt      # Encrypted preferences
│   ├── assets/
│   │   ├── scaffold.html           # WebView content
│   │   └── loading.gif             # Animations
│   └── res/
│       ├── mipmap-*/               # App icons (all densities)
│       └── values/
│           └── strings.xml
└── build.gradle.kts
```

---

## Resources

- [RayNeo Developer Portal](https://developer.rayneo.com/)
- [Mercury SDK Documentation](https://developer.rayneo.com/docs/mercury-sdk)
- [Android AR Development](https://developer.android.com/ar)

---

*Last updated: January 2025*
*Based on development experience with ClawdAR project*
