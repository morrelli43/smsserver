package com.smsserver

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Builds a WAP/MMS M-Send.req PDU suitable for use with
 * [android.telephony.SmsManager.sendMultimediaMessage].
 *
 * References:
 *  - OMA-TS-MMS_ENC-V1_3 (MMS Encapsulation Protocol)
 *  - WAP-230-WSP (WAP Binary XML / WSP encoding)
 */
object MmsPduBuilder {

    // -----------------------------------------------------------------------
    // MMS header field codes (OMA MMS Encoding Table 3, 0x80 | code)
    // -----------------------------------------------------------------------
    private const val FIELD_CONTENT_TYPE = 0x84.toByte()
    private const val FIELD_DATE = 0x85.toByte()
    private const val FIELD_FROM = 0x89.toByte()
    private const val FIELD_MESSAGE_TYPE = 0x8C.toByte()
    private const val FIELD_MMS_VERSION = 0x8D.toByte()
    private const val FIELD_TO = 0x97.toByte()
    private const val FIELD_TRANSACTION_ID = 0x98.toByte()

    // -----------------------------------------------------------------------
    // Message type values
    // -----------------------------------------------------------------------
    private const val MESSAGE_TYPE_SEND_REQ = 0x80.toByte()

    // -----------------------------------------------------------------------
    // MMS version (1.2)
    // -----------------------------------------------------------------------
    private const val MMS_VERSION_1_2 = 0x92.toByte()

    // -----------------------------------------------------------------------
    // From field: Insert-Address-Token (let carrier fill in sender address)
    // -----------------------------------------------------------------------
    private const val INSERT_ADDRESS_TOKEN = 0x81.toByte()

    // -----------------------------------------------------------------------
    // WSP well-known content-type codes (WAP-230 Table 40)
    // multipart/mixed = 0x23  → short integer 0x80|0x23 = 0xA3
    // -----------------------------------------------------------------------
    private const val CT_MULTIPART_MIXED = 0xA3.toByte()

    // Part content-type short forms (WAP-230 Table 40: well-known media types)
    // Short-integer encoding = 0x80 | code
    private val WELL_KNOWN_MIME = mapOf(
        "text/plain" to 0x83.toByte(),  // code 0x03 → 0x80|0x03 = 0x83
        "image/gif" to 0x9C.toByte(),   // code 0x1C → 0x80|0x1C = 0x9C
        "image/jpeg" to 0x9E.toByte(),  // code 0x1E → 0x80|0x1E = 0x9E
        "image/jpg" to 0x9E.toByte(),
        "image/tiff" to 0x9D.toByte()   // code 0x1D → 0x80|0x1D = 0x9D
        // image/png has no well-known code in WAP-230; it will use string encoding
    )

    // UTF-8 charset: IANA code 106 (0x6A) encoded as WAP short-integer = 0x80|0x6A = 0xEA
    private const val CHARSET_UTF8 = 0xEA.toByte()

    private data class Part(
        val contentType: String,
        val data: ByteArray,
        val contentId: String
    )

    /**
     * Builds a binary MMS PDU (M-Send.req) ready to be written to a file and
     * passed to [SmsManager.sendMultimediaMessage].
     *
     * @param to             E.164 phone number of the recipient
     * @param text           Optional text body
     * @param imageData      Optional raw image bytes
     * @param imageMimeType  MIME type of the image (e.g. "image/jpeg")
     */
    fun buildSendRequest(
        to: String,
        text: String?,
        imageData: ByteArray?,
        imageMimeType: String?
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // ------------------------------------------------------------------
        // PDU headers
        // ------------------------------------------------------------------

        // Message-Type: M-Send.req
        out.write(FIELD_MESSAGE_TYPE.toInt())
        out.write(MESSAGE_TYPE_SEND_REQ.toInt())

        // Transaction-ID (unique per request)
        val transactionId = UUID.randomUUID().toString().replace("-", "").take(16)
        out.write(FIELD_TRANSACTION_ID.toInt())
        writeNullTerminatedString(out, transactionId)

        // MMS-Version: 1.2
        out.write(FIELD_MMS_VERSION.toInt())
        out.write(MMS_VERSION_1_2.toInt())

        // Date (seconds since Unix epoch)
        val dateSecs = System.currentTimeMillis() / 1000L
        out.write(FIELD_DATE.toInt())
        writeLongInteger(out, dateSecs)

        // To: <address>/TYPE=PLMN
        out.write(FIELD_TO.toInt())
        writeNullTerminatedString(out, formatAddress(to))

        // From: Insert-Address-Token
        out.write(FIELD_FROM.toInt())
        writeValueLength(out, 1)
        out.write(INSERT_ADDRESS_TOKEN.toInt())

        // ------------------------------------------------------------------
        // Build multipart body parts
        // ------------------------------------------------------------------
        val parts = mutableListOf<Part>()

        if (!text.isNullOrEmpty()) {
            parts += Part(
                contentType = "text/plain",
                data = text.toByteArray(Charsets.UTF_8),
                contentId = "<text>"
            )
        }

        if (imageData != null && !imageMimeType.isNullOrEmpty()) {
            parts += Part(
                contentType = imageMimeType.lowercase(),
                data = imageData,
                contentId = "<image>"
            )
        }

        // ------------------------------------------------------------------
        // Content-Type header: multipart/mixed
        // ------------------------------------------------------------------
        out.write(FIELD_CONTENT_TYPE.toInt())
        // value-length = 1 (just the short-form media type byte)
        writeValueLength(out, 1)
        out.write(CT_MULTIPART_MIXED.toInt())

        // ------------------------------------------------------------------
        // Multipart body
        // ------------------------------------------------------------------
        writeMultipart(out, parts)

        return out.toByteArray()
    }

    // -----------------------------------------------------------------------
    // Multipart encoding
    // -----------------------------------------------------------------------

    private fun writeMultipart(out: ByteArrayOutputStream, parts: List<Part>) {
        // nEntries
        writeUintVar(out, parts.size)

        for (part in parts) {
            val headersOut = ByteArrayOutputStream()
            writePartHeaders(headersOut, part)
            val headers = headersOut.toByteArray()

            // HeadersLength (uintvar)
            writeUintVar(out, headers.size)
            // DataLength (uintvar)
            writeUintVar(out, part.data.size)
            // Headers
            out.write(headers)
            // Data
            out.write(part.data)
        }
    }

    private fun writePartHeaders(out: ByteArrayOutputStream, part: Part) {
        // Encode Content-Type for this part.
        // Per WAP-230: Content-type = Constrained-media | Content-general-form
        //
        // Constrained-media:
        //   - Short-integer (0x80-0xFF): well-known type code with MSB set — no length prefix
        //   - Extension-media: null-terminated string — no length prefix
        //
        // Content-general-form:
        //   - Value-length + Media-type [+ Parameters]
        //   Required when parameters (e.g. charset) must be included.

        val wellKnown = WELL_KNOWN_MIME[part.contentType]

        when {
            part.contentType == "text/plain" -> {
                // Use Content-general-form to include charset=UTF-8 parameter.
                // Value = media-type(0x83) + charset-token(0x81) + charset-value(0xEA for UTF-8)
                val ctBody = byteArrayOf(
                    0x83.toByte(),  // text/plain short-integer
                    0x81.toByte(),  // "charset" well-known-parameter-token (code 0x01 → 0x81)
                    CHARSET_UTF8    // UTF-8 short-integer (code 106 → 0xEA)
                )
                writeValueLength(out, ctBody.size)
                out.write(ctBody)
            }
            wellKnown != null -> {
                // Constrained-media: well-known type written as a single short-integer byte.
                // No value-length prefix — the high bit (0x80) signals it's a short-integer.
                out.write(wellKnown.toInt() and 0xFF)
            }
            else -> {
                // Constrained-media: extension-media (null-terminated ASCII string).
                // No value-length prefix.
                writeNullTerminatedString(out, part.contentType)
            }
        }

        // Content-ID header (0xC0 = 0x80|0x40 – WSP header field for Content-ID)
        // In WAP, Content-ID header field code is 0x40, short form 0xC0
        out.write(0xC0)
        writeNullTerminatedString(out, part.contentId)
    }

    // -----------------------------------------------------------------------
    // WAP encoding helpers
    // -----------------------------------------------------------------------

    /**
     * Writes a null-terminated ISO-8859-1 string.
     * If the first byte would be >= 0x80 (ambiguous with short integer),
     * we prepend a Quote byte (0x7F) per the WAP spec.
     */
    private fun writeNullTerminatedString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.ISO_8859_1)
        if (bytes.isNotEmpty() && bytes[0].toInt() and 0xFF >= 0x80) {
            out.write(0x7F)  // Quote byte
        }
        out.write(bytes)
        out.write(0)  // null terminator
    }

    /**
     * Writes a Long as a WAP long-integer (1-byte length + N bytes big-endian).
     * Maximum 4 bytes for dates (seconds since epoch fits in 4 bytes until 2038).
     */
    private fun writeLongInteger(out: ByteArrayOutputStream, value: Long) {
        val bytes = mutableListOf<Byte>()
        var v = value
        while (v > 0) {
            bytes.add(0, (v and 0xFF).toByte())
            v = v shr 8
        }
        if (bytes.isEmpty()) bytes.add(0)
        out.write(bytes.size)
        bytes.forEach { out.write(it.toInt() and 0xFF) }
    }

    /**
     * Writes a WAP value-length (uintvar if < 31, else 31 + uintvar).
     */
    private fun writeValueLength(out: ByteArrayOutputStream, length: Int) {
        if (length < 31) {
            out.write(length)
        } else {
            out.write(31)
            writeUintVar(out, length)
        }
    }

    /**
     * Writes an unsigned integer as a WAP uintvar (variable-length quantity).
     * Each byte uses bits 0-6 for data and bit 7 as continuation flag.
     */
    private fun writeUintVar(out: ByteArrayOutputStream, value: Int) {
        val bytes = mutableListOf<Int>()
        var v = value
        bytes.add(v and 0x7F)
        v = v ushr 7
        while (v > 0) {
            bytes.add(0, (v and 0x7F) or 0x80)
            v = v ushr 7
        }
        bytes.forEach { out.write(it) }
    }

    /**
     * Formats a phone number as an MMS address string.
     * E.g. "+14155552671" becomes "+14155552671/TYPE=PLMN"
     */
    private fun formatAddress(address: String): String {
        val clean = address.trim().replace(" ", "")
        return if (clean.contains("@")) {
            // Email address
            "$clean/TYPE=RFC822"
        } else {
            "$clean/TYPE=PLMN"
        }
    }
}
