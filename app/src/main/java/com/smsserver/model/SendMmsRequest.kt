package com.smsserver.model

data class SendMmsRequest(
    val address: String,
    val body: String = "",
    /** Base64-encoded image/attachment data */
    val attachmentBase64: String? = null,
    /** MIME type of the attachment, e.g. "image/jpeg" */
    val attachmentMimeType: String? = null,
    /** Optional filename for the attachment */
    val attachmentName: String? = null
)
