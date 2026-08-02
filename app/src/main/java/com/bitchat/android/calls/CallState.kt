package com.bitchat.android.calls

/**
 * UI-facing state machine for a WebRTC voice call.
 *
 * Only one call is supported at a time (mirrors a simple phone-style UX). The manager
 * that owns this state is responsible for enforcing that transitions only happen from
 * valid predecessor states.
 */
sealed class CallState {
    /** No call in progress. */
    object Idle : CallState()

    /** We initiated a call and are waiting for the remote peer to answer. */
    data class Outgoing(
        val callId: String,
        val peerID: String,
        val displayName: String
    ) : CallState()

    /** A remote peer sent us an offer; waiting for local user to accept/reject. */
    data class Incoming(
        val callId: String,
        val peerID: String,
        val displayName: String
    ) : CallState()

    /** SDP/ICE exchanged, waiting for the media transport (DTLS/ICE) to finish connecting. */
    data class Connecting(
        val callId: String,
        val peerID: String,
        val displayName: String
    ) : CallState()

    /** Media is flowing. */
    data class Connected(
        val callId: String,
        val peerID: String,
        val displayName: String,
        val connectedAtMs: Long,
        val isMuted: Boolean = false,
        val isSpeakerOn: Boolean = false,
        val connectionQuality: ConnectionQuality = ConnectionQuality.UNKNOWN
    ) : CallState()

    /** Terminal, transient state shown briefly before returning to Idle. */
    data class Ended(
        val peerID: String,
        val displayName: String,
        val reason: EndReason
    ) : CallState()
}

enum class EndReason {
    LOCAL_HANGUP,
    REMOTE_HANGUP,
    REMOTE_BUSY,
    NO_ANSWER,
    CONNECTION_FAILED,
    NO_ROUTE,
    ERROR
}

/**
 * Quality of the voice connection, based on ICE state and optional RTT/packet-loss
 * metrics if available from the WebRTC stack.
 */
enum class ConnectionQuality {
    /** Connection just established, metrics not yet available. */
    UNKNOWN,
    /** ICE connected, low latency, no packet loss. */
    EXCELLENT,
    /** ICE connected, moderate latency or minor retransmissions. */
    GOOD,
    /** ICE connected but experiencing noticeable quality issues. */
    POOR,
    /** Connection is failing or very unstable. */
    FAILING
}
