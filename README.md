# SMS Webhook Server

An Android app (targeting Android 8.0+ / API 26+, optimised for Android 14) that exposes SMS and MMS messaging as an always-on local REST API.  
Connect any remote system to your phone via HTTP to read conversations, send/receive SMS, and send/receive MMS images.

---

## Features

| Feature | Details |
|---|---|
| 📡 Always-on HTTP server | Runs as a foreground service, survives app close |
| 🌍 External IP Discovery | Automatically fetches and displays your external IP for remote access setup |
| 🔒 API key auth | Bearer-token authentication on every request |
| 💬 SMS read | List all conversation threads and their messages |
| ✉️ SMS send | POST to send an SMS to any number |
| 🖼️ MMS read | Retrieve MMS messages with base64-encoded attachments |
| 📷 MMS send | POST with a base64 image to send an MMS |
| 🔔 Incoming webhooks | Incoming SMS are forwarded to your configured URL |
| 🔄 Boot auto-start | Service restarts automatically after device reboot |

---

## Building & Installing

### Requirements
- **Android Studio** Ladybug or newer
- **JDK 17**

### Steps
1. Clone this repository.
2. Open it in Android Studio (`File → Open`).
3. Android Studio will sync Gradle and download dependencies.
4. Build and run on your Android device (`Run → Run 'app'`).

---

## First-time Setup

1. Open the **SMS Webhook Server** app.
2. Your **API Key** is shown and auto-generated on first launch. Tap **Copy** to copy it.
3. Optionally change the **Server Port** (default: `8080`) and tap **Save**.
4. Flip the **Webhook Server** toggle to **ON**.
5. Grant the SMS and Notification permission prompts.
6. The server is now running. Both local and external URLs are displayed.

---

## REST API Reference

All endpoints except `GET /api/status` require:
```
Authorization: Bearer <api_key>
```

### `GET /api/status`
Health check — no authentication required.

**Response:**
```json
{ "status": "running", "version": "1.0", "timestamp": 1700000000000 }
```

---

### `GET /api/conversations`
List all SMS/MMS conversation threads, sorted by most recent.

**Response:**
```json
[
  {
    "threadId": 1,
    "address": "+14155552671",
    "snippet": "On my way!",
    "timestamp": 1700000000000,
    "messageCount": 42,
    "unreadCount": 2,
    "hasMms": false
  }
]
```

---

### `GET /api/messages?threadId=N[&limit=50&offset=0&includeMms=true]`
Get messages in a conversation thread.

| Param | Type | Default | Description |
|---|---|---|---|
| `threadId` | long | required | Thread ID from `/api/conversations` |
| `limit` | int | 50 | Max messages to return (1–200) |
| `offset` | int | 0 | Pagination offset |
| `includeMms` | bool | true | Include MMS messages |

**Response:**
```json
[
  {
    "id": 101,
    "threadId": 1,
    "address": "+14155552671",
    "body": "On my way!",
    "timestamp": 1700000000000,
    "type": 1,
    "isRead": true,
    "isMms": false
  }
]
```
`type`: `1` = received (inbox), `2` = sent.

---

### `POST /api/sms`
Send an SMS message.

**Body:**
```json
{ "address": "+14155552671", "body": "Hello from the webhook!" }
```

**Response:**
```json
{ "success": true }
```

---

### `POST /api/mms`
Send an MMS message with an optional image/attachment.

**Body:**
```json
{
  "address": "+14155552671",
  "body": "Check this out!",
  "attachmentBase64": "<base64-encoded image bytes>",
  "attachmentMimeType": "image/jpeg",
  "attachmentName": "photo.jpg"
}
```

**Response:**
```json
{ "success": true }
```

---

### `GET /api/messages/{id}/attachment`
Retrieve a specific MMS part (attachment) by its part ID.

**Response:**
```json
{
  "partId": 202,
  "mimeType": "image/jpeg",
  "dataBase64": "<base64 data>",
  "sizeBytes": 45678
}
```

---

### `PUT /api/config/webhook`
Configure the webhook URL for incoming SMS notifications.

**Body:**
```json
{ "url": "https://your-server.example.com/incoming-sms" }
```

When an SMS arrives, the app will `POST` to this URL with:
```json
{
  "event": "incoming_sms",
  "data": {
    "address": "+14155552671",
    "body": "Hello!",
    "timestamp": 1700000000000
  }
}
```
The same `Authorization: Bearer <api_key>` header is included on outgoing webhook calls.

**Response:**
```json
{ "success": true, "webhookUrl": "https://your-server.example.com/incoming-sms" }
```

---

### `GET /api/config`
Get the current server configuration.

**Response:**
```json
{ "webhookUrl": "https://your-server.example.com/incoming-sms", "port": 8080 }
```

---

## Permissions Required

| Permission | Purpose |
|---|---|
| `READ_SMS`, `SEND_SMS`, `RECEIVE_SMS` | Core SMS functionality |
| `RECEIVE_MMS` | Detect incoming MMS |
| `INTERNET`, `ACCESS_WIFI_STATE` | Run HTTP server, display IP address |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Keep server alive |
| `POST_NOTIFICATIONS` | Show persistent server status notification |
| `RECEIVE_BOOT_COMPLETED` | Auto-start after reboot |

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│  MainActivity  (UI: start/stop, show URL & API key)     │
└───────────────────────┬─────────────────────────────────┘
                        │ startForegroundService
┌───────────────────────▼─────────────────────────────────┐
│  WebhookService  (ForegroundService, keeps server alive)│
│   └── SmsHttpServer (NanoHTTPD on port 8080)            │
│         ├── GET  /api/conversations  → SmsHelper        │
│         ├── GET  /api/messages       → SmsHelper+MmsHelper│
│         ├── POST /api/sms            → SmsHelper        │
│         └── POST /api/mms            → MmsHelper        │
│                                         └── MmsPduBuilder│
└─────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────┐
│  SmsReceiver  (BroadcastReceiver)                       │
│   Incoming SMS → HTTP POST to configured webhook URL    │
└─────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────┐
│  BootReceiver  (BroadcastReceiver)                      │
│   BOOT_COMPLETED → auto-start WebhookService            │
└─────────────────────────────────────────────────────────┘
```

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| NanoHTTPD (org.nanohttpd) | 2.3.1 | Embedded HTTP server |
| Gson | 2.10.1 | JSON serialization |
| AndroidX Core, AppCompat | latest | Android support |
| Material Components | 1.11.0 | UI |
