package com.smsserver.model

data class Message(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val timestamp: Long,
    /** 1 = received (inbox), 2 = sent */
    val type: Int,
    val isRead: Boolean,
    val isMms: Boolean,
    /** Base64-encoded attachment data, null if text-only */
    val attachmentBase64: String? = null,
    /** MIME type of the attachment, e.g. "image/jpeg" */
    val attachmentMimeType: String? = null,
    val attachmentName: String? = null
)
