package com.smsserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MmsPduBuilder].
 *
 * These tests validate the binary PDU structure produced by the builder
 * without requiring an Android device or emulator.
 */
class MmsPduBuilderTest {

    companion object {
        // MMS header field codes (OMA MMS Encoding spec Table 3)
        private const val FIELD_MESSAGE_TYPE = 0x8C
        private const val FIELD_TRANSACTION_ID = 0x98
        private const val FIELD_MMS_VERSION = 0x8D
        private const val FIELD_DATE = 0x85
        private const val FIELD_TO = 0x97
        private const val FIELD_FROM = 0x89
        private const val FIELD_CONTENT_TYPE = 0x84

        private const val MESSAGE_TYPE_SEND_REQ = 0x80
        private const val MMS_VERSION_1_2 = 0x92
        private const val INSERT_ADDRESS_TOKEN = 0x81
    }

    @Test
    fun `buildSendRequest returns non-empty byte array`() {
        val pdu = MmsPduBuilder.buildSendRequest(
            to = "+14155552671",
            text = "Hello, World!",
            imageData = null,
            imageMimeType = null
        )
        assertNotNull(pdu)
        assertTrue("PDU must not be empty", pdu.isNotEmpty())
    }

    @Test
    fun `buildSendRequest starts with Message-Type header`() {
        val pdu = MmsPduBuilder.buildSendRequest(
            to = "+14155552671",
            text = "Test",
            imageData = null,
            imageMimeType = null
        )
        assertEquals("First byte must be Message-Type field code", FIELD_MESSAGE_TYPE, pdu[0].toInt() and 0xFF)
        assertEquals("Second byte must be M-Send.req value", MESSAGE_TYPE_SEND_REQ, pdu[1].toInt() and 0xFF)
    }

    @Test
    fun `buildSendRequest includes correct MMS version`() {
        val pdu = MmsPduBuilder.buildSendRequest(
            to = "+14155552671",
            text = "Test",
            imageData = null,
            imageMimeType = null
        )

        val versionIdx = findFieldIndex(pdu, FIELD_MMS_VERSION)
        assertTrue("PDU must contain MMS-Version field", versionIdx >= 0)
        assertEquals(
            "MMS version must be 1.2",
            MMS_VERSION_1_2,
            pdu[versionIdx + 1].toInt() and 0xFF
        )
    }

    @Test
    fun `buildSendRequest includes From Insert-Address-Token`() {
        val pdu = MmsPduBuilder.buildSendRequest(
            to = "+14155552671",
            text = "Test",
            imageData = null,
            imageMimeType = null
        )
        val fromIdx = findFieldIndex(pdu, FIELD_FROM)
        assertTrue("PDU must contain From field", fromIdx >= 0)
        // From field: 0x89 + value-length(1) + INSERT_ADDRESS_TOKEN(0x81)
        assertEquals(
            "From value must be Insert-Address-Token",
            INSERT_ADDRESS_TOKEN,
            pdu[fromIdx + 2].toInt() and 0xFF
        )
    }

    @Test
    fun `buildSendRequest formats PLMN address correctly`() {
        val pdu = MmsPduBuilder.buildSendRequest(
            to = "+14155552671",
            text = "Hello",
            imageData = null,
            imageMimeType = null
        )
        val pduStr = String(pdu, Charsets.ISO_8859_1)
        assertTrue(
            "PDU must contain PLMN-formatted To address",
            pduStr.contains("+14155552671/TYPE=PLMN")
        )
    }

    @Test
    fun `buildSendRequest with image produces larger PDU than text-only`() {
        val textOnly = MmsPduBuilder.buildSendRequest(
            to = "+14155552671",
            text = "Hello",
            imageData = null,
            imageMimeType = null
        )
        val imageBytes = ByteArray(1024) { it.toByte() }
        val withImage = MmsPduBuilder.buildSendRequest(
            to = "+14155552671",
            text = "Hello",
            imageData = imageBytes,
            imageMimeType = "image/jpeg"
        )
        assertTrue(
            "PDU with image attachment must be larger than text-only PDU",
            withImage.size > textOnly.size
        )
    }

    @Test
    fun `buildSendRequest handles empty text gracefully`() {
        val pdu = MmsPduBuilder.buildSendRequest(
            to = "+14155552671",
            text = null,
            imageData = null,
            imageMimeType = null
        )
        assertNotNull(pdu)
        assertTrue(pdu.isNotEmpty())
    }

    @Test
    fun `buildSendRequest handles email address with RFC822 type`() {
        val pdu = MmsPduBuilder.buildSendRequest(
            to = "user@example.com",
            text = "Hello",
            imageData = null,
            imageMimeType = null
        )
        val pduStr = String(pdu, Charsets.ISO_8859_1)
        assertTrue(
            "PDU must contain RFC822-formatted To address for email",
            pduStr.contains("user@example.com/TYPE=RFC822")
        )
    }

    // --- Helpers ---

    /** Finds the index of [fieldCode] in [pdu], or -1 if not found. */
    private fun findFieldIndex(pdu: ByteArray, fieldCode: Int): Int {
        for (i in pdu.indices) {
            if (pdu[i].toInt() and 0xFF == fieldCode) {
                return i
            }
        }
        return -1
    }
}
