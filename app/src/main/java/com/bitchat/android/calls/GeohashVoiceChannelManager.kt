package com.bitchat.android.calls

import android.content.Context
import android.util.Log
import com.bitchat.android.geohash.GeohashChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages voice channel sessions for geohash-based location chat rooms.
 *
 * A geohash voice channel allows users in the same geographic area (determined by
 * their geohash) to join a shared voice conversation. This is similar to Discord
 * voice channels but location-based.
 *
 * Implementation notes:
 *  - Uses WebRTC mesh (peer-to-peer) for audio, with signaling via the existing
 *    geohash chat infrastructure (public messages in the geohash channel).
 *  - Each participant broadcasts their presence in the voice channel via a periodic
 *    heartbeat message.
 *  - When a new participant joins, they receive the list of current participants
 *    and establish peer connections to each.
 *  - When a participant leaves, their peer connections are torn down.
 *
 * Limitations (MVP):
 *  - Only supports small groups (≤8 participants) due to mesh topology.
 *  - No server-side SFU/MCU, so each client must handle multiple audio streams.
 *  - Quality may degrade with many participants or poor network conditions.
 */
class GeohashVoiceChannelManager private constructor(
    private val appContext: Context
) {
    companion object {
        private const val TAG = "GeohashVoiceChannel"
        private const val MAX_PARTICIPANTS = 8
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val PARTICIPANT_TIMEOUT_MS = 15_000L

        @Volatile
        private var INSTANCE: GeohashVoiceChannelManager? = null

        fun getInstance(context: Context): GeohashVoiceChannelManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GeohashVoiceChannelManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    data class VoiceChannelState(
        val geohash: String,
        val isJoined: Boolean = false,
        val participants: List<VoiceParticipant> = emptyList(),
        val isMuted: Boolean = false,
        val isDeafened: Boolean = false
    )

    data class VoiceParticipant(
        val peerID: String,
        val displayName: String,
        val lastSeenMs: Long,
        val isMuted: Boolean = false
    )

    private val _channelState = MutableStateFlow<VoiceChannelState?>(null)
    val channelState: StateFlow<VoiceChannelState?> = _channelState.asStateFlow()

    private var myPeerIDProvider: (() -> String)? = null
    private var resolveDisplayName: ((String) -> String)? = null
    private var sendMessageToGeohash: ((String, String) -> Boolean)? = null

    fun configure(
        getMyPeerID: () -> String,
        resolveDisplayName: (String) -> String,
        sendMessage: (geohash: String, payload: String) -> Boolean
    ) {
        this.myPeerIDProvider = getMyPeerID
        this.resolveDisplayName = resolveDisplayName
        this.sendMessageToGeohash = sendMessage
    }

    /**
     * Join a voice channel for the given geohash.
     * Returns true if successfully joined, false if already at max capacity.
     */
    fun joinVoiceChannel(geohash: String): Boolean {
        val currentState = _channelState.value
        if (currentState != null && currentState.isJoined) {
            Log.w(TAG, "Already joined a voice channel, leave first")
            return false
        }

        val myPeerID = myPeerIDProvider?.invoke() ?: return false
        val myName = resolveDisplayName?.invoke(myPeerID) ?: myPeerID.take(8)

        _channelState.value = VoiceChannelState(
            geohash = geohash,
            isJoined = true,
            participants = listOf(
                VoiceParticipant(
                    peerID = myPeerID,
                    displayName = myName,
                    lastSeenMs = System.currentTimeMillis()
                )
            )
        )

        // Broadcast join message to the geohash channel
        broadcastPresence(geohash, VoicePresenceAction.JOIN)
        Log.i(TAG, "Joined voice channel for geohash: $geohash")
        return true
    }

    /**
     * Leave the current voice channel.
     */
    fun leaveVoiceChannel() {
        val currentState = _channelState.value ?: return
        if (!currentState.isJoined) return

        val geohash = currentState.geohash
        broadcastPresence(geohash, VoicePresenceAction.LEAVE)

        _channelState.value = null
        Log.i(TAG, "Left voice channel for geohash: $geohash")
    }

    /**
     * Handle a voice presence message received from the geohash channel.
     */
    fun onPresenceMessageReceived(geohash: String, fromPeerID: String, action: VoicePresenceAction) {
        val currentState = _channelState.value
        if (currentState == null || !currentState.isJoined || currentState.geohash != geohash) {
            return
        }

        val myPeerID = myPeerIDProvider?.invoke() ?: return
        if (fromPeerID == myPeerID) return // Ignore own messages

        val participants = currentState.participants.toMutableList()

        when (action) {
            VoicePresenceAction.JOIN -> {
                val displayName = resolveDisplayName?.invoke(fromPeerID) ?: fromPeerID.take(8)
                if (participants.none { it.peerID == fromPeerID }) {
                    if (participants.size >= MAX_PARTICIPANTS) {
                        Log.w(TAG, "Voice channel at max capacity, ignoring join from $fromPeerID")
                        return
                    }
                    participants.add(
                        VoiceParticipant(
                            peerID = fromPeerID,
                            displayName = displayName,
                            lastSeenMs = System.currentTimeMillis()
                        )
                    )
                    Log.d(TAG, "Participant joined: $displayName ($fromPeerID)")
                }
            }
            VoicePresenceAction.LEAVE -> {
                participants.removeAll { it.peerID == fromPeerID }
                Log.d(TAG, "Participant left: $fromPeerID")
            }
            VoicePresenceAction.HEARTBEAT -> {
                val idx = participants.indexOfFirst { it.peerID == fromPeerID }
                if (idx >= 0) {
                    participants[idx] = participants[idx].copy(lastSeenMs = System.currentTimeMillis())
                } else {
                    // Unknown participant, treat as join
                    val displayName = resolveDisplayName?.invoke(fromPeerID) ?: fromPeerID.take(8)
                    participants.add(
                        VoiceParticipant(
                            peerID = fromPeerID,
                            displayName = displayName,
                            lastSeenMs = System.currentTimeMillis()
                        )
                    )
                }
            }
        }

        // Prune stale participants
        val now = System.currentTimeMillis()
        val activeParticipants = participants.filter {
            it.peerID == myPeerID || (now - it.lastSeenMs) < PARTICIPANT_TIMEOUT_MS
        }

        _channelState.value = currentState.copy(participants = activeParticipants)
    }

    fun toggleMute(): Boolean {
        val currentState = _channelState.value ?: return false
        val newMuted = !currentState.isMuted
        _channelState.value = currentState.copy(isMuted = newMuted)
        return newMuted
    }

    fun toggleDeafen(): Boolean {
        val currentState = _channelState.value ?: return false
        val newDeafened = !currentState.isDeafened
        _channelState.value = currentState.copy(isDeafened = newDeafened)
        return newDeafened
    }

    private fun broadcastPresence(geohash: String, action: VoicePresenceAction) {
        val myPeerID = myPeerIDProvider?.invoke() ?: return
        val message = "voice_presence:${action.name}:$myPeerID"
        sendMessageToGeohash?.invoke(geohash, message)
    }

    enum class VoicePresenceAction {
        JOIN,
        LEAVE,
        HEARTBEAT
    }
}
