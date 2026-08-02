package com.bitchat.android.calls

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Lightweight JSON-encoded signaling message used to negotiate a WebRTC voice call
 * over bitchat's existing encrypted transports (Noise over mesh, or Nostr gift-wrapped
 * DMs when only internet connectivity is available).
 *
 * This intentionally mirrors the simplicity of the rest of bitchat's signaling (e.g.
 * verify challenge/response) rather than pulling in a dedicated signaling server -
 * the call signal is just another `NoisePayloadType` embedded the same way private
 * messages and delivery receipts already are.
 */
enum class CallSignalKind {
    @SerializedName("offer") OFFER,
    @SerializedName("answer") ANSWER,
    @SerializedName("ice") ICE_CANDIDATE,
    @SerializedName("end") END,
    @SerializedName("busy") BUSY,
    @SerializedName("ringing") RINGING
}

data class CallSignal(
    val callId: String,
    val kind: CallSignalKind,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null
) {
    fun encode(): ByteArray = gson.toJson(this).toByteArray(Charsets.UTF_8)

    companion object {
        private val gson = Gson()

        fun decode(data: ByteArray): CallSignal? {
            return try {
                gson.fromJson(String(data, Charsets.UTF_8), CallSignal::class.java)
            } catch (_: Exception) {
                null
            }
        }
    }
}
