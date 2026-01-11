---
name: rayneo-x3-dev
description: Development guide for RayNeo X3 Pro AR glasses using Mercury SDK
triggers:
  - rayneo
  - ar glasses
  - mercury sdk
  - x3 pro
  - temple gestures
---

# RayNeo X3 Pro AR Development Skill

You are helping develop an Android app for RayNeo X3 Pro AR glasses using the Mercury SDK. Apply the following knowledge and best practices.

## Critical Configuration

### Making Apps Native (Not Virtual)

Apps must include this meta-data in AndroidManifest.xml to run natively on AR displays:

```xml
<activity android:name=".YourActivity">
    <meta-data
        android:name="com.rayneo.app.type"
        android:value="native" />
</activity>
```

Without this, apps run in a virtual Android environment, not directly on AR displays.

## Display Architecture

- Screen is 1280x480 total (640x480 per eye)
- Left half (0-639) renders to left eye
- Right half (640-1279) renders to right eye
- **Must render content twice** for mirrored display:

```kotlin
Row(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f).fillMaxHeight()) { Content() }
    Box(Modifier.weight(1f).fillMaxHeight()) { Content() }
}
```

## Temple Gesture Handling

### RESERVED GESTURES - DO NOT USE:
- **Double-tap**: Opens system settings
- **Long-press/Hold**: Returns to launcher

### Available Gestures:
- Single tap - primary action
- Triple tap - secondary action
- Swipe forward/backward - navigation
- Swipe up/down - available

### Mercury SDK Implementation:

```kotlin
import com.ffalcon.mercury.android.sdk.touch.CommonTouchCallback
import com.ffalcon.mercury.android.sdk.touch.TouchDispatcher
import com.ffalcon.mercury.android.sdk.touch.TouchDispatcherX3

class ARActivity : ComponentActivity() {
    private lateinit var touchDispatcher: TouchDispatcherX3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        touchDispatcher = TouchDispatcherX3(TouchDispatcher.Source.Activity)
    }

    private val touchCallback = object : CommonTouchCallback() {
        override fun onTPClick(): Boolean {
            // Single tap - USE THIS
            return true
        }
        override fun onTPDoubleClick(): Boolean {
            // RESERVED - avoid
            return true
        }
        override fun onTPLongClick(): Boolean {
            // RESERVED - avoid
            return true
        }
        override fun onTPSlideForward(args: FlingArgs): Boolean {
            // Swipe forward - USE THIS
            return true
        }
        override fun onTPSlideBackward(args: FlingArgs): Boolean {
            // Swipe backward - USE THIS
            return true
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        event?.let { touchDispatcher.onMotionEvent(it, touchCallback) }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        touchDispatcher.onKeyEvent(event, touchCallback)
        return super.dispatchKeyEvent(event)
    }
}
```

## Common Issues & Solutions

### WebView doesn't fill container
Force MATCH_PARENT layout params:
```kotlin
WebView(context).apply {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
}
```

### Speech recognition doesn't work
Android's SpeechRecognizer is NOT supported. Use cloud STT like ElevenLabs:
```kotlin
// POST to https://api.elevenlabs.io/v1/speech-to-text
// with model_id: "scribe_v1" and audio file
```

### App icon shows default
Delete `mipmap-anydpi-v26/` folder - adaptive icon XMLs override PNG icons.

### TCP connections drop
Implement heartbeat ping every 30 seconds.

## SDK Setup

build.gradle.kts:
```kotlin
dependencies {
    implementation("com.niconi.niconi:niconi-mercury-android-sdk:0.2.2")
}
```

settings.gradle.kts:
```kotlin
maven {
    url = uri("https://pkgs.dev.azure.com/niconi/niconi/_packaging/niconi/maven/v1")
}
```

## UI Best Practices

- Minimum 20sp font for body text, 28sp+ for important info
- High contrast: light text on dark backgrounds
- Keep content centered - edges are hard to see
- Use immersive mode to hide system bars

## Reference

See full documentation: `docs/RAYNEO_X3_DEVELOPMENT.md` in project root.
