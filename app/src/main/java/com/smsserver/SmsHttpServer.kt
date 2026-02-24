package com.smsserver

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.smsserver.model.Conversation
import com.smsserver.model.Message
import com.smsserver.model.SendMmsRequest
import com.smsserver.model.SendSmsRequest
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Embedded HTTP server that exposes SMS/MMS functionality via a REST API.
 *
 * All endpoints (except GET /api/status) require an Authorization header:
 *   Authorization: Bearer <apiKey>
 *
 * API endpoints:
 *   GET  /api/status                      – server health check (no auth required)
 *   GET  /api/conversations               – list all SMS/MMS threads
 *   GET  /api/messages?threadId=N[&limit=N&offset=N] – messages in a thread
 *   POST /api/sms                         – send SMS  { address, body }
 *   POST /api/mms                         – send MMS  { address, body, attachmentBase64, attachmentMimeType, attachmentName }
 *   GET  /api/messages/{id}/attachment    – fetch MMS part as base64
 *   PUT  /api/config/webhook              – set incoming-message webhook URL { url }
 *   GET  /api/config                      – get current configuration
 */
class SmsHttpServer(
    private val context: Context,
    private val apiKey: String,
    port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {

    companion object {
        const val DEFAULT_PORT = 8080
        private const val MIME_JSON = "application/json"
        private const val TAG = "SmsHttpServer"

        private const val PREF_WEBHOOK_URL = "webhook_url"

        /** Maximum allowed request body size: 10 MB (covers large MMS attachments sent as base64). */
        private const val MAX_REQUEST_BODY_BYTES = 10 * 1024 * 1024

        // Rate Limiting Config
        private const val RATE_LIMIT_MAX_REQUESTS = 30
        private const val RATE_LIMIT_WINDOW_MS = 60_000L
    }

    private val gson: Gson = GsonBuilder().create()
    private val prefsManager by lazy {
        PrefsManager(context)
    }

    // Rate limiter state: IP Address -> Pair<Timestamp of window start, Request count in window>
    private val rateLimiterMap = ConcurrentHashMap<String, Pair<Long, AtomicInteger>>()

    // -----------------------------------------------------------------------
    // Request routing
    // -----------------------------------------------------------------------

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        val method = session.method

        Log.d(TAG, "${method.name} $uri")

        // Health-check does not require auth
        if (method == Method.GET && uri == "/api/status") {
            return handleStatus()
        }

        // All other endpoints require a valid API key
        if (!isAuthorized(session)) {
            return jsonResponse(Status.UNAUTHORIZED, mapOf("error" to "Unauthorized"))
        }

        // Apply rate limiting
        val clientIp = session.headers["remote-addr"] ?: "unknown"
        if (isRateLimited(clientIp)) {
            Log.w(TAG, "Rate limit exceeded for IP: $clientIp")
            return jsonResponse(Status.BAD_REQUEST, mapOf("error" to "Too Many Requests. Try again later."))
        }

        return try {
            when {
                method == Method.GET && uri == "/api/conversations" ->
                    handleGetConversations()

                method == Method.GET && uri == "/api/messages" ->
                    handleGetMessages(session)

                method == Method.POST && uri == "/api/sms" ->
                    handleSendSms(session)

                method == Method.POST && uri == "/api/mms" ->
                    handleSendMms(session)

                method == Method.GET && uri.matches(Regex("/api/messages/\\d+/attachment")) ->
                    handleGetAttachment(uri)

                method == Method.PUT && uri == "/api/config/webhook" ->
                    handleSetWebhook(session)

                method == Method.GET && uri == "/api/config" ->
                    handleGetConfig()

                else -> jsonResponse(Status.NOT_FOUND, mapOf("error" to "Not found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ${method.name} $uri", e)
            jsonResponse(
                Status.INTERNAL_ERROR,
                mapOf("error" to "Internal server error: ${e.message}")
            )
        }
    }

    // -----------------------------------------------------------------------
    // Handlers
    // -----------------------------------------------------------------------

    private fun handleStatus(): Response {
        return jsonResponse(
            Status.OK,
            mapOf(
                "status" to "running",
                "version" to "1.0",
                "timestamp" to System.currentTimeMillis()
            )
        )
    }

    private fun handleGetConversations(): Response {
        val conversations: List<Conversation> = SmsHelper.getConversations(context)
        return jsonResponse(Status.OK, conversations)
    }

    private fun handleGetMessages(session: IHTTPSession): Response {
        val params = session.parameters
        val threadId = params["threadId"]?.firstOrNull()?.toLongOrNull()
            ?: return jsonResponse(Status.BAD_REQUEST, mapOf("error" to "threadId parameter required"))

        val limit = params["limit"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val offset = params["offset"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val includeMms = params["includeMms"]?.firstOrNull()?.lowercase() != "false"

        val smsMessages = SmsHelper.getMessages(context, threadId, limit, offset)
        val allMessages: List<Message> = if (includeMms) {
            val mmsMessages = MmsHelper.getMessages(context, threadId, limit, offset)
            (smsMessages + mmsMessages).sortedByDescending { it.timestamp }
        } else {
            smsMessages
        }

        return jsonResponse(Status.OK, allMessages)
    }

    private fun handleSendSms(session: IHTTPSession): Response {
        val body = readBody(session)
        val request = try {
            gson.fromJson(body, SendSmsRequest::class.java)
        } catch (e: Exception) {
            return jsonResponse(Status.BAD_REQUEST, mapOf("error" to "Invalid JSON body"))
        }

        if (request.address.isBlank()) {
            return jsonResponse(Status.BAD_REQUEST, mapOf("error" to "address is required"))
        }
        if (request.body.isBlank()) {
            return jsonResponse(Status.BAD_REQUEST, mapOf("error" to "body is required"))
        }

        val success = SmsHelper.sendSms(context, request.address, request.body)
        return if (success) {
            jsonResponse(Status.OK, mapOf("success" to true))
        } else {
            jsonResponse(Status.INTERNAL_ERROR, mapOf("success" to false, "error" to "Failed to send SMS"))
        }
    }

    private fun handleSendMms(session: IHTTPSession): Response {
        val body = readBody(session)
        val request = try {
            gson.fromJson(body, SendMmsRequest::class.java)
        } catch (e: Exception) {
            return jsonResponse(Status.BAD_REQUEST, mapOf("error" to "Invalid JSON body"))
        }

        if (request.address.isBlank()) {
            return jsonResponse(Status.BAD_REQUEST, mapOf("error" to "address is required"))
        }

        val attachmentBytes = request.attachmentBase64?.let {
            try {
                Base64.decode(it, Base64.DEFAULT)
            } catch (e: Exception) {
                return jsonResponse(Status.BAD_REQUEST, mapOf("error" to "Invalid base64 in attachmentBase64"))
            }
        }

        val success = MmsHelper.sendMms(
            context = context,
            address = request.address,
            body = request.body,
            attachmentData = attachmentBytes,
            attachmentMimeType = request.attachmentMimeType,
            attachmentName = request.attachmentName
        )
        return if (success) {
            jsonResponse(Status.OK, mapOf("success" to true))
        } else {
            jsonResponse(Status.INTERNAL_ERROR, mapOf("success" to false, "error" to "Failed to send MMS"))
        }
    }

    private fun handleGetAttachment(uri: String): Response {
        // uri = /api/messages/{id}/attachment
        val partId = uri.removePrefix("/api/messages/").removeSuffix("/attachment").toLongOrNull()
            ?: return jsonResponse(Status.BAD_REQUEST, mapOf("error" to "Invalid message ID"))

        val result = MmsHelper.getAttachmentBytes(context, partId)
            ?: return jsonResponse(Status.NOT_FOUND, mapOf("error" to "Attachment not found"))

        val (bytes, mimeType) = result
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return jsonResponse(
            Status.OK,
            mapOf(
                "partId" to partId,
                "mimeType" to mimeType,
                "dataBase64" to encoded,
                "sizeBytes" to bytes.size
            )
        )
    }

    private fun handleSetWebhook(session: IHTTPSession): Response {
        val body = readBody(session)
        val map = try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(body, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            return jsonResponse(Status.BAD_REQUEST, mapOf("error" to "Invalid JSON body"))
        }

        val url = (map["url"] as? String) ?: ""
        prefsManager.webhookUrl = url

        return jsonResponse(Status.OK, mapOf("success" to true, "webhookUrl" to url))
    }

    private fun handleGetConfig(): Response {
        val webhookUrl = prefsManager.webhookUrl ?: ""
        return jsonResponse(
            Status.OK,
            mapOf(
                "webhookUrl" to webhookUrl,
                "port" to listeningPort
            )
        )
    }

    // -----------------------------------------------------------------------
    // Utility helpers
    // -----------------------------------------------------------------------

    private fun isRateLimited(clientIp: String): Boolean {
        val now = System.currentTimeMillis()
        var bucket = rateLimiterMap[clientIp]

        if (bucket == null || (now - bucket.first) > RATE_LIMIT_WINDOW_MS) {
            bucket = Pair(now, AtomicInteger(1))
            rateLimiterMap[clientIp] = bucket
            return false
        }

        val currentCount = bucket.second.incrementAndGet()
        return currentCount > RATE_LIMIT_MAX_REQUESTS
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val auth = session.headers["authorization"] ?: return false
        return auth == "Bearer $apiKey"
    }

    private fun readBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength > MAX_REQUEST_BODY_BYTES) {
            throw IllegalArgumentException("Request body too large ($contentLength bytes, max $MAX_REQUEST_BODY_BYTES)")
        }
        return if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var bytesRead = 0
            while (bytesRead < contentLength) {
                val n = session.inputStream.read(buf, bytesRead, contentLength - bytesRead)
                if (n < 0) break
                bytesRead += n
            }
            String(buf, 0, bytesRead, Charsets.UTF_8)
        } else {
            // Fallback: parse POST params
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            files["postData"] ?: ""
        }
    }

    private fun jsonResponse(status: Status, body: Any): Response {
        val json = gson.toJson(body)
        return newFixedLengthResponse(status, MIME_JSON, json)
    }

    /**
     * Returns the stored webhook URL, or null if not configured.
     */
    fun getWebhookUrl(): String? {
        val url = prefsManager.webhookUrl
        return if (url.isNullOrBlank()) null else url
    }
}
