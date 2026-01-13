# ClawdBot Client Protocol Documentation

This document describes how to connect to ClawdBot gateway, send text/images, handle voice input, and receive streaming AI responses.

---

## Table of Contents
1. [Gateway Connection](#1-gateway-connection)
2. [Protocol Overview](#2-protocol-overview)
3. [Handshake & Authentication](#3-handshake--authentication)
4. [Sending Chat Messages](#4-sending-chat-messages)
5. [Sending Images](#5-sending-images)
6. [Receiving AI Responses](#6-receiving-ai-responses)
7. [Voice Input with ElevenLabs STT](#7-voice-input-with-elevenlabs-stt)
8. [Audio Recording Specifications](#8-audio-recording-specifications)
9. [Connection Management](#9-connection-management)

---

## 1. Gateway Connection

### Discovery (mDNS/Bonjour)
The gateway advertises itself via mDNS:
- **Service Type:** `_clawdbot-bridge._tcp.`
- **Protocol:** DNS-SD

### Direct Connection
- **Transport:** TCP socket
- **Default Port:** `18790` (but discovered via mDNS)
- **Message Format:** JSON, newline-delimited (NDJSON)
- **Encoding:** UTF-8

### Connection Parameters
```
Connect Timeout: 8000ms
Read Timeout: 0 (infinite, long-lived connection)
TCP Options: tcpNoDelay=true, keepAlive=true
```

---

## 2. Protocol Overview

All messages are JSON objects sent as single lines (terminated by `\n`).

### Message Types

| Type | Direction | Description |
|------|-----------|-------------|
| `hello` | Client → Server | Initial handshake |
| `hello-ok` | Server → Client | Handshake accepted |
| `pair-request` | Client → Server | Request pairing (if not paired) |
| `pair-ok` | Server → Client | Pairing successful |
| `error` | Server → Client | Error response |
| `req` | Client → Server | RPC request |
| `res` | Server → Client | RPC response |
| `event` | Both | Event notification |
| `ping` | Client → Server | Heartbeat |
| `pong` | Server → Client | Heartbeat response |
| `tick` | Server → Client | Server heartbeat |

---

## 3. Handshake & Authentication

### Step 1: Send Hello
```json
{
  "type": "hello",
  "nodeId": "<unique-device-id>",
  "displayName": "My iOS App",
  "platform": "ios",
  "version": "1.0.0",
  "capabilities": ["canvas", "canvas.a2ui"],
  "commands": [
    "canvas.navigate",
    "canvas.eval",
    "canvas.snapshot",
    "canvas.a2ui.push",
    "canvas.a2ui.pushJSONL",
    "canvas.a2ui.reset"
  ],
  "token": "<optional-saved-token>"
}
```

### Step 2: Receive Response

**Success (already paired):**
```json
{
  "type": "hello-ok",
  "token": "<auth-token>"
}
```

**Not paired:**
```json
{
  "type": "error",
  "code": "NOT_PAIRED",
  "message": "Device not paired"
}
```

### Step 3: Pairing (if needed)
If you receive `NOT_PAIRED`, send:
```json
{
  "type": "pair-request",
  "nodeId": "<unique-device-id>",
  "displayName": "My iOS App",
  "platform": "ios",
  "version": "1.0.0",
  "capabilities": ["canvas", "canvas.a2ui"],
  "commands": [...]
}
```

The user must approve on the gateway. Then you'll receive:
```json
{
  "type": "pair-ok",
  "token": "<auth-token>"
}
```

**Important:** Save the `token` for future connections.

---

## 4. Sending Chat Messages

### Subscribe to Chat Events (Required)
Before sending messages, subscribe to receive responses:
```json
{
  "type": "event",
  "event": "chat.subscribe",
  "payloadJSON": "{\"sessionKey\":\"<your-session-key>\"}"
}
```

The `sessionKey` should be a persistent UUID for conversation continuity.

### Send Text Message
```json
{
  "type": "req",
  "id": "<unique-request-id>",
  "method": "chat.send",
  "paramsJSON": "{\"sessionKey\":\"<session-key>\",\"message\":\"Hello, what can you help me with?\",\"idempotencyKey\":\"<unique-key>\"}"
}
```

### Parameters (inside paramsJSON)
| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `sessionKey` | string | Yes | Persistent session identifier |
| `message` | string | Yes | The user's message |
| `idempotencyKey` | string | Yes | Unique key to prevent duplicates |
| `thinking` | string | No | Thinking level/mode |
| `deliver` | boolean | No | Whether to deliver the message |
| `attachments` | array | No | Image attachments (see below) |
| `timeoutMs` | number | No | Request timeout |

---

## 5. Sending Images

### Image Attachment Format
```json
{
  "type": "req",
  "id": "<request-id>",
  "method": "chat.send",
  "paramsJSON": "{\"sessionKey\":\"<session-key>\",\"message\":\"What do you see in this image?\",\"idempotencyKey\":\"<key>\",\"attachments\":[{\"type\":\"image\",\"mimeType\":\"image/jpeg\",\"fileName\":\"photo.jpg\",\"content\":\"data:image/jpeg;base64,<BASE64_DATA>\"}]}"
}
```

### Attachment Object
```json
{
  "type": "image",
  "mimeType": "image/jpeg",
  "fileName": "photo.jpg",
  "content": "data:image/jpeg;base64,/9j/4AAQSkZJRg..."
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | string | Yes | Always `"image"` |
| `mimeType` | string | Yes | `"image/jpeg"`, `"image/png"`, etc. |
| `fileName` | string | Yes | Filename with extension |
| `content` | string | Yes | Data URL: `data:<mimeType>;base64,<data>` |

### Image Guidelines
- **Max file size:** 5MB
- **Recommended resolution:** 1024px max dimension
- **JPEG quality:** 85% for good balance
- **Supported formats:** JPEG, PNG, GIF, WebP

---

## 6. Receiving AI Responses

### Response Types

#### RPC Response (immediate acknowledgment)
```json
{
  "type": "res",
  "id": "<request-id>",
  "ok": true
}
```

Or error:
```json
{
  "type": "res",
  "id": "<request-id>",
  "ok": false,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "invalid chat.send params: ..."
  }
}
```

#### Streaming AI Response (via events)
```json
{
  "type": "event",
  "event": "agent",
  "payloadJSON": "{\"runId\":\"<run-id>\",\"stream\":\"assistant\",\"data\":{\"text\":\"Here is my response so far...\"}}"
}
```

### Agent Event Payload Structure
```json
{
  "runId": "<unique-run-id>",
  "stream": "assistant",
  "data": {
    "text": "Streaming text content..."
  }
}
```

Or with content blocks (for images):
```json
{
  "runId": "<run-id>",
  "stream": "assistant",
  "data": {
    "content": [
      {"type": "text", "text": "Here's what I found:"},
      {"type": "image", "data": "<base64>", "mimeType": "image/png"}
    ]
  }
}
```

### Content Block Types
| Type | Fields | Description |
|------|--------|-------------|
| `text` | `text` | Plain text content |
| `image` | `data`, `mimeType` or `url` | Image (base64 or URL) |

### Chat State Events
```json
{
  "type": "event",
  "event": "chat",
  "payloadJSON": "{\"runId\":\"<run-id>\",\"state\":\"final\"}"
}
```

States:
- `final` - Response complete
- `error` - Error occurred (check `errorMessage`)
- `aborted` - Response was aborted

---

## 7. Voice Input with ElevenLabs STT

### API Details
- **Endpoint:** `https://api.elevenlabs.io/v1/speech-to-text`
- **Method:** POST
- **Content-Type:** `multipart/form-data`

### Request Headers
```
xi-api-key: <your-elevenlabs-api-key>
Content-Type: multipart/form-data; boundary=<boundary>
Accept: application/json
```

### Request Body (multipart/form-data)
```
--<boundary>
Content-Disposition: form-data; name="file"; filename="audio.wav"
Content-Type: audio/wav

<WAV_BINARY_DATA>
--<boundary>
Content-Disposition: form-data; name="model_id"

scribe_v1
--<boundary>--
```

### Response
```json
{
  "text": "The transcribed text from the audio",
  "language_code": "en",
  "language_probability": 0.95
}
```

### Timeouts
- Connect timeout: 30 seconds
- Read timeout: 60 seconds

---

## 8. Audio Recording Specifications

### Recording Format (for ElevenLabs)
| Parameter | Value |
|-----------|-------|
| Sample Rate | 16000 Hz |
| Channels | 1 (Mono) |
| Bit Depth | 16-bit |
| Format | PCM (raw), converted to WAV for API |

### PCM to WAV Conversion
The raw PCM audio must be wrapped in a WAV header:

```
WAV Header (44 bytes):
├── RIFF Header (12 bytes)
│   ├── "RIFF" (4 bytes)
│   ├── File size - 8 (4 bytes, little-endian)
│   └── "WAVE" (4 bytes)
├── fmt Subchunk (24 bytes)
│   ├── "fmt " (4 bytes)
│   ├── Subchunk size: 16 (4 bytes, little-endian)
│   ├── Audio format: 1 (PCM) (2 bytes, little-endian)
│   ├── Num channels: 1 (2 bytes, little-endian)
│   ├── Sample rate: 16000 (4 bytes, little-endian)
│   ├── Byte rate: 32000 (4 bytes, little-endian)
│   ├── Block align: 2 (2 bytes, little-endian)
│   └── Bits per sample: 16 (2 bytes, little-endian)
└── data Subchunk
    ├── "data" (4 bytes)
    ├── Data size (4 bytes, little-endian)
    └── PCM audio data...
```

### Swift WAV Header Example
```swift
func pcmToWav(pcmData: Data, sampleRate: Int = 16000, channels: Int = 1, bitsPerSample: Int = 16) -> Data {
    let byteRate = sampleRate * channels * bitsPerSample / 8
    let blockAlign = channels * bitsPerSample / 8
    let dataSize = pcmData.count
    let fileSize = 36 + dataSize

    var wav = Data()

    // RIFF header
    wav.append("RIFF".data(using: .ascii)!)
    wav.append(UInt32(fileSize).littleEndianData)
    wav.append("WAVE".data(using: .ascii)!)

    // fmt subchunk
    wav.append("fmt ".data(using: .ascii)!)
    wav.append(UInt32(16).littleEndianData)      // Subchunk1Size
    wav.append(UInt16(1).littleEndianData)       // AudioFormat (PCM)
    wav.append(UInt16(channels).littleEndianData)
    wav.append(UInt32(sampleRate).littleEndianData)
    wav.append(UInt32(byteRate).littleEndianData)
    wav.append(UInt16(blockAlign).littleEndianData)
    wav.append(UInt16(bitsPerSample).littleEndianData)

    // data subchunk
    wav.append("data".data(using: .ascii)!)
    wav.append(UInt32(dataSize).littleEndianData)
    wav.append(pcmData)

    return wav
}
```

### Voice Activity Detection
RMS threshold of ~500 is used to detect voice activity:
```swift
func calculateRMS(samples: [Int16]) -> Int {
    let sum = samples.reduce(0.0) { $0 + Double($1) * Double($1) }
    return Int(sqrt(sum / Double(samples.count)))
}

let hasVoice = rms > 500
```

### Recording Limits
- **Max duration:** 30 seconds
- **Max buffer size:** 960,000 bytes (30s × 16000Hz × 2 bytes)
- **Chunk size:** ~100ms (3,200 bytes)

---

## 9. Connection Management

### Heartbeat
Send every 30 seconds to keep connection alive:
```json
{"type": "ping"}
```

Expected response:
```json
{"type": "pong", "id": ""}
```

### Reconnection Strategy
- **Base delay:** 350ms
- **Max delay:** 8000ms
- **Multiplier:** 1.7× per attempt
- **Formula:** `delay = min(350 * 1.7^attempt, 8000)`

### Important Connection Tips
1. Separate read job from reconnect job to avoid self-cancellation
2. Enable TCP keepalive for long-lived connections
3. Handle `tick` events from server (server-side heartbeat)
4. Persist `sessionKey` and `token` across app restarts

---

## Quick Reference

### Minimal Flow
1. **Connect** to gateway via TCP
2. **Send hello** with device info
3. **Receive hello-ok** (or pair if needed)
4. **Subscribe** to chat events
5. **Send chat.send** with message (and optional attachments)
6. **Listen** for `agent` events (streaming response)
7. **Detect** `chat` event with `state: "final"`
8. **Send heartbeat** every 30s

### API Keys Required
- **ElevenLabs:** For speech-to-text (STT)
  - Get from: https://elevenlabs.io/
  - Model: `scribe_v1`

---

## Example: Complete iOS Request Flow

```swift
// 1. Connect and handshake
socket.connect(host: "192.168.1.100", port: 18790)
socket.send("""
{"type":"hello","nodeId":"\(deviceId)","displayName":"My iPhone","platform":"ios","version":"1.0.0","capabilities":["canvas"],"commands":[],"token":"\(savedToken ?? "")"}
""")

// 2. Wait for hello-ok, save token

// 3. Subscribe to chat
socket.send("""
{"type":"event","event":"chat.subscribe","payloadJSON":"{\\"sessionKey\\":\\"\(sessionKey)\\"}"}
""")

// 4. Send message with image
let imageData = capturedImage.jpegData(compressionQuality: 0.85)!
let base64 = imageData.base64EncodedString()
socket.send("""
{"type":"req","id":"\(UUID().uuidString)","method":"chat.send","paramsJSON":"{\\"sessionKey\\":\\"\(sessionKey)\\",\\"message\\":\\"What is this?\\",\\"idempotencyKey\\":\\"\(UUID().uuidString)\\",\\"attachments\\":[{\\"type\\":\\"image\\",\\"mimeType\\":\\"image/jpeg\\",\\"fileName\\":\\"photo.jpg\\",\\"content\\":\\"data:image/jpeg;base64,\(base64)\\"}]}"}
""")

// 5. Listen for streaming response
// Parse "event" messages with event="agent" for text updates
// Look for "chat" event with state="final" to know when done
```
