package com.bitchat.android.calls

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [CallSignal] encoding/decoding to ensure the signaling
 * serialization remains stable across versions.
 */
class CallSignalTest {

    @Test
    fun `encode and decode OFFER signal round-trips correctly`() {
        val original = CallSignal(
            callId = "test-call-123",
            kind = CallSignalKind.OFFER,
            sdp = "v=0\r\no=- 123456 1 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
        )
        val encoded = original.encode()
        val decoded = CallSignal.decode(encoded)

        assertNotNull(decoded)
        assertEquals(original.callId, decoded!!.callId)
        assertEquals(original.kind, decoded.kind)
        assertEquals(original.sdp, decoded.sdp)
        assertNull(decoded.candidate)
        assertNull(decoded.sdpMid)
        assertNull(decoded.sdpMLineIndex)
    }

    @Test
    fun `encode and decode ICE_CANDIDATE signal with all fields`() {
        val original = CallSignal(
            callId = "call-abc",
            kind = CallSignalKind.ICE_CANDIDATE,
            candidate = "candidate:1 1 udp 2130706431 192.168.1.100 54321 typ host",
            sdpMid = "audio",
            sdpMLineIndex = 0
        )
        val encoded = original.encode()
        val decoded = CallSignal.decode(encoded)

        assertNotNull(decoded)
        assertEquals(original.callId, decoded!!.callId)
        assertEquals(original.kind, decoded.kind)
        assertEquals(original.candidate, decoded.candidate)
        assertEquals(original.sdpMid, decoded.sdpMid)
        assertEquals(original.sdpMLineIndex, decoded.sdpMLineIndex)
    }

    @Test
    fun `encode and decode ANSWER signal`() {
        val original = CallSignal(
            callId = "call-xyz",
            kind = CallSignalKind.ANSWER,
            sdp = "v=0\r\no=- 789 1 IN IP4 0.0.0.0\r\n"
        )
        val decoded = CallSignal.decode(original.encode())

        assertNotNull(decoded)
        assertEquals(CallSignalKind.ANSWER, decoded!!.kind)
        assertEquals("v=0\r\no=- 789 1 IN IP4 0.0.0.0\r\n", decoded.sdp)
    }

    @Test
    fun `encode and decode END signal has no sdp or candidate`() {
        val original = CallSignal(
            callId = "call-end",
            kind = CallSignalKind.END
        )
        val decoded = CallSignal.decode(original.encode())

        assertNotNull(decoded)
        assertEquals(CallSignalKind.END, decoded!!.kind)
        assertNull(decoded.sdp)
        assertNull(decoded.candidate)
    }

    @Test
    fun `encode and decode BUSY signal`() {
        val original = CallSignal(
            callId = "call-busy",
            kind = CallSignalKind.BUSY
        )
        val decoded = CallSignal.decode(original.encode())

        assertNotNull(decoded)
        assertEquals(CallSignalKind.BUSY, decoded!!.kind)
    }

    @Test
    fun `encode and decode RINGING signal`() {
        val original = CallSignal(
            callId = "call-ring",
            kind = CallSignalKind.RINGING
        )
        val decoded = CallSignal.decode(original.encode())

        assertNotNull(decoded)
        assertEquals(CallSignalKind.RINGING, decoded!!.kind)
    }

    @Test
    fun `decode returns null for invalid data`() {
        assertNull(CallSignal.decode("not json".toByteArray()))
        assertNull(CallSignal.decode("{}".toByteArray()))  // Missing required fields
        assertNull(CallSignal.decode(ByteArray(0)))
    }

    @Test
    fun `decode returns null for completely random bytes`() {
        val randomBytes = ByteArray(100) { it.toByte() }
        assertNull(CallSignal.decode(randomBytes))
    }

    @Test
    fun `encoded payload is valid UTF-8 JSON`() {
        val signal = CallSignal(
            callId = "test",
            kind = CallSignalKind.OFFER,
            sdp = "test sdp"
        )
        val encoded = String(signal.encode(), Charsets.UTF_8)
        // Should be parseable as JSON and contain expected fields
        assertTrue(encoded.contains("\"callId\""))
        assertTrue(encoded.contains("\"kind\""))
        assertTrue(encoded.contains("\"offer\""))
        assertTrue(encoded.contains("\"sdp\""))
    }

    @Test
    fun `ICE_CANDIDATE with null sdpMLineIndex defaults to null`() {
        val original = CallSignal(
            callId = "test",
            kind = CallSignalKind.ICE_CANDIDATE,
            candidate = "candidate:test",
            sdpMid = "0",
            sdpMLineIndex = null
        )
        val decoded = CallSignal.decode(original.encode())
        assertNotNull(decoded)
        assertNull(decoded!!.sdpMLineIndex)
    }

    @Test
    fun `all CallSignalKind values are represented in JSON`() {
        for (kind in CallSignalKind.values()) {
            val signal = CallSignal(callId = "test-$kind", kind = kind)
            val decoded = CallSignal.decode(signal.encode())
            assertNotNull("Failed to decode kind: $kind", decoded)
            assertEquals(kind, decoded!!.kind)
        }
    }

    @Test
    fun `large SDP payload round-trips correctly`() {
        // Simulate a realistic large SDP
        val largeSdp = buildString {
            append("v=0\r\n")
            append("o=- ${System.currentTimeMillis()} 1 IN IP4 0.0.0.0\r\n")
            append("s=-\r\n")
            append("t=0 0\r\n")
            append("a=group:BUNDLE 0\r\n")
            repeat(20) { i ->
                append("a=msid-semantic: WMS stream$i\r\n")
            }
            append("m=audio 9 UDP/TLS/RTP/SAVPF 111 103 104\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("a=rtcp:9 IN IP4 0.0.0.0\r\n")
            append("a=ice-ufrag:verylongiceusernamestring\r\n")
            append("a=ice-pwd:verylongicepasswordstring\r\n")
            append("a=fingerprint:sha-256 AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99\r\n")
        }

        val original = CallSignal(
            callId = "large-sdp-call",
            kind = CallSignalKind.OFFER,
            sdp = largeSdp
        )
        val decoded = CallSignal.decode(original.encode())

        assertNotNull(decoded)
        assertEquals(largeSdp, decoded!!.sdp)
    }
}
