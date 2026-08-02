package com.bitchat.android.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID

/**
 * Manages a single 1:1 WebRTC voice call over bitchat's existing encrypted transports.
 *
 * Features:
 *  - Signaling (offer/answer/ICE/end/busy) via Noise over mesh or Nostr DM.
 *  - Configurable TURN server for NAT traversal.
 *  - Auto-reconnect with exponential backoff on ICE disconnection.
 *  - Connection quality tracking (EXCELLENT → FAILING).
 *  - Call logging for history (incoming/outgoing/missed/duration).
 */
class CallManager private constructor(
    private val appContext: Context
) {
    companion object {
        private const val TAG = "CallManager"
        private const val RING_TIMEOUT_MS = 45_000L
        private const val AUDIO_TRACK_ID = "bitchat-audio0"
        private const val LOCAL_STREAM_ID = "bitchat-stream0"
        private const val DISCONNECT_GRACE_MS = 5_000L

        @Volatile private var INSTANCE: CallManager? = null

        fun getInstance(context: Context): CallManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CallManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        // Default STUN servers (always used, TURN added on top when configured)
        private val DEFAULT_ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val settings by lazy { CallSettingsManager.getInstance(appContext) }

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    var sendSignal: ((peerID: String, payload: ByteArray) -> Boolean)? = null
    var resolveDisplayName: ((peerID: String) -> String)? = null

    private var myPeerIDProvider: (() -> String)? = null
    fun configure(
        getMyPeerID: () -> String,
        sendSignal: (peerID: String, payload: ByteArray) -> Boolean,
        resolveDisplayName: (peerID: String) -> String
    ) {
        this.myPeerIDProvider = getMyPeerID
        this.sendSignal = sendSignal
        this.resolveDisplayName = resolveDisplayName
        CallLogManager.initialize(appContext)
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var currentCallId: String? = null
    private var currentPeerID: String? = null
    private var pendingRemoteIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private var ringTimeoutJob: Job? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var reconnectJob: Job? = null
    private var disconnectGraceJob: Job? = null

    private var isMuted = false
    private var isSpeakerOn = false
    private var callStartedAtMs: Long? = null
    private var callIsIncoming: Boolean = false
    private var currentQuality = ConnectionQuality.UNKNOWN
    private var reconnectAttempts = 0
    private var isReconnecting = false

    private fun audioManager(): AudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun buildIceServers(): List<PeerConnection.IceServer> {
        val servers = DEFAULT_ICE_SERVERS.toMutableList()
        if (settings.isTurnConfigured()) {
            try {
                val turnServer = PeerConnection.IceServer.builder(settings.turnServerUri)
                    .setUsername(settings.turnUsername)
                    .setPassword(settings.turnPassword)
                    .createIceServer()
                servers.add(turnServer)
                Log.d(TAG, "TURN server added: ${settings.turnServerUri}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build TURN server: ${e.message}")
            }
        }
        return servers
    }

    private fun ensureFactory(): PeerConnectionFactory? {
        peerConnectionFactory?.let { return it }
        return try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .createInitializationOptions()
            )
            val adm = JavaAudioDeviceModule.builder(appContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()

            val factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .createPeerConnectionFactory()
            peerConnectionFactory = factory
            factory
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebRTC PeerConnectionFactory: ${e.message}", e)
            null
        }
    }

    // MARK: - Public call control API

    fun startCall(peerID: String): Boolean {
        if (_callState.value !is CallState.Idle) {
            Log.w(TAG, "startCall ignored - a call is already in progress")
            return false
        }
        val factory = ensureFactory() ?: return false
        val callId = UUID.randomUUID().toString()
        val displayName = resolveDisplayName?.invoke(peerID) ?: peerID.take(8)

        currentCallId = callId
        currentPeerID = peerID
        remoteDescriptionSet = false
        pendingRemoteIce.clear()
        callIsIncoming = false
        callStartedAtMs = System.currentTimeMillis()
        reconnectAttempts = 0
        isReconnecting = false
        currentQuality = ConnectionQuality.UNKNOWN

        val pc = createPeerConnection(factory, peerID) ?: run {
            teardownInternal()
            return false
        }
        peerConnection = pc
        attachLocalAudio(factory, pc)

        _callState.value = CallState.Outgoing(callId, peerID, displayName)
        CallForegroundService.start(appContext)
        requestAudioFocus()

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        val signal = CallSignal(callId = callId, kind = CallSignalKind.OFFER, sdp = sdp.description)
                        dispatchSignal(peerID, signal, onFailure = {
                            scope.launch { endCall(EndReason.NO_ROUTE) }
                        })
                    }
                }, sdp)
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "createOffer failed: $error")
                scope.launch { endCall(EndReason.ERROR) }
            }
        }, MediaConstraints())

        startRingTimeout()
        return true
    }

    fun acceptCall() {
        val state = _callState.value
        if (state !is CallState.Incoming) {
            Log.w(TAG, "acceptCall ignored - no incoming call")
            return
        }
        val factory = peerConnectionFactory ?: ensureFactory() ?: return
        val pc = peerConnection ?: run {
            Log.e(TAG, "acceptCall: missing peer connection")
            endCall(EndReason.ERROR)
            return
        }
        attachLocalAudio(factory, pc)
        cancelRingTimeout()
        callStartedAtMs = System.currentTimeMillis()

        pc.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        val callId = currentCallId ?: return
                        val peerID = currentPeerID ?: return
                        val signal = CallSignal(callId = callId, kind = CallSignalKind.ANSWER, sdp = sdp.description)
                        dispatchSignal(peerID, signal, onFailure = {
                            scope.launch { endCall(EndReason.NO_ROUTE) }
                        })
                        _callState.value = CallState.Connecting(callId, peerID, state.displayName)
                        requestAudioFocus()
                        flushPendingRemoteIce()
                    }
                }, sdp)
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "createAnswer failed: $error")
                endCall(EndReason.ERROR)
            }
        }, MediaConstraints())
    }

    fun rejectCall() {
        val state = _callState.value
        if (state !is CallState.Incoming) return
        val callId = currentCallId
        val peerID = currentPeerID
        if (callId != null && peerID != null) {
            dispatchSignal(peerID, CallSignal(callId = callId, kind = CallSignalKind.BUSY))
        }
        finishCall(peerID = state.peerID, displayName = state.displayName, reason = EndReason.LOCAL_HANGUP)
    }

    fun hangUp() {
        endCall(EndReason.LOCAL_HANGUP)
    }

    fun toggleMute(): Boolean {
        isMuted = !isMuted
        localAudioTrack?.setEnabled(!isMuted)
        syncConnectedState()
        return isMuted
    }

    fun toggleSpeaker(): Boolean {
        isSpeakerOn = !isSpeakerOn
        try {
            val am = audioManager()
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (isSpeakerOn) {
                    am.availableCommunicationDevices
                        .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?.let { am.setCommunicationDevice(it) }
                } else {
                    am.availableCommunicationDevices
                        .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                        ?.let { am.setCommunicationDevice(it) }
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = isSpeakerOn
            }
        } catch (e: Exception) {
            Log.w(TAG, "toggleSpeaker failed: ${e.message}")
        }
        syncConnectedState()
        return isSpeakerOn
    }

    // MARK: - Incoming signaling

    fun onSignalReceived(peerID: String, rawPayload: ByteArray) {
        val signal = CallSignal.decode(rawPayload) ?: run {
            Log.w(TAG, "Failed to decode call signal from $peerID")
            return
        }
        scope.launch { handleSignal(peerID, signal) }
    }

    private fun handleSignal(peerID: String, signal: CallSignal) {
        when (signal.kind) {
            CallSignalKind.OFFER -> handleOffer(peerID, signal)
            CallSignalKind.ANSWER -> handleAnswer(peerID, signal)
            CallSignalKind.ICE_CANDIDATE -> handleRemoteIce(peerID, signal)
            CallSignalKind.END -> handleRemoteEnd(peerID, signal)
            CallSignalKind.BUSY -> handleRemoteBusy(peerID, signal)
            CallSignalKind.RINGING -> {}
        }
    }

    private fun handleOffer(peerID: String, signal: CallSignal) {
        val sdp = signal.sdp ?: return
        val current = _callState.value

        if (current !is CallState.Idle) {
            if (currentPeerID != peerID || currentCallId != signal.callId) {
                dispatchSignal(peerID, CallSignal(callId = signal.callId, kind = CallSignalKind.BUSY))
            }
            return
        }

        val factory = ensureFactory() ?: run {
            dispatchSignal(peerID, CallSignal(callId = signal.callId, kind = CallSignalKind.BUSY))
            return
        }

        currentCallId = signal.callId
        currentPeerID = peerID
        remoteDescriptionSet = false
        pendingRemoteIce.clear()
        callIsIncoming = true
        callStartedAtMs = System.currentTimeMillis()
        reconnectAttempts = 0
        isReconnecting = false
        currentQuality = ConnectionQuality.UNKNOWN

        val pc = createPeerConnection(factory, peerID) ?: run {
            teardownInternal()
            return
        }
        peerConnection = pc

        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                flushPendingRemoteIce()
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setRemoteDescription(offer) failed: $error")
                endCall(EndReason.ERROR)
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))

        val displayName = resolveDisplayName?.invoke(peerID) ?: peerID.take(8)
        _callState.value = CallState.Incoming(signal.callId, peerID, displayName)
        CallForegroundService.start(appContext)
        startRingTimeout()
    }

    private fun handleAnswer(peerID: String, signal: CallSignal) {
        if (peerID != currentPeerID || signal.callId != currentCallId) return
        val pc = peerConnection ?: return
        val sdp = signal.sdp ?: return
        cancelRingTimeout()
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                flushPendingRemoteIce()
                val displayName = resolveDisplayName?.invoke(peerID) ?: peerID.take(8)
                _callState.value = CallState.Connecting(signal.callId, peerID, displayName)
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setRemoteDescription(answer) failed: $error")
                endCall(EndReason.ERROR)
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun handleRemoteIce(peerID: String, signal: CallSignal) {
        if (peerID != currentPeerID || signal.callId != currentCallId) return
        val candidate = signal.candidate ?: return
        val ice = IceCandidate(signal.sdpMid, signal.sdpMLineIndex ?: 0, candidate)
        val pc = peerConnection
        if (pc != null && remoteDescriptionSet) {
            try { pc.addIceCandidate(ice) } catch (e: Exception) { Log.w(TAG, "addIceCandidate failed: ${e.message}") }
        } else {
            pendingRemoteIce.add(ice)
        }
    }

    private fun handleRemoteEnd(peerID: String, signal: CallSignal) {
        if (peerID != currentPeerID || signal.callId != currentCallId) return
        endCall(EndReason.REMOTE_HANGUP)
    }

    private fun handleRemoteBusy(peerID: String, signal: CallSignal) {
        if (peerID != currentPeerID || signal.callId != currentCallId) return
        endCall(EndReason.REMOTE_BUSY)
    }

    // MARK: - PeerConnection plumbing

    private fun createPeerConnection(factory: PeerConnectionFactory, peerID: String): PeerConnection? {
        val iceServers = buildIceServers()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }
        return try {
            factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "ICE connection state -> $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            reconnectAttempts = 0
                            isReconnecting = false
                            cancelDisconnectGrace()
                            updateQuality(ConnectionQuality.EXCELLENT)
                            markConnected()
                        }
                        PeerConnection.IceConnectionState.CHECKING -> {
                            updateQuality(ConnectionQuality.UNKNOWN)
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            if (settings.isAutoReconnectEnabled() && _callState.value is CallState.Connected) {
                                attemptReconnect()
                            } else {
                                endCall(EndReason.CONNECTION_FAILED)
                            }
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            updateQuality(ConnectionQuality.POOR)
                            startDisconnectGrace()
                        }
                        PeerConnection.IceConnectionState.CLOSED -> {}
                        else -> {}
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    if (_callState.value is CallState.Connected) {
                        if (receiving) {
                            updateQuality(ConnectionQuality.EXCELLENT)
                        } else {
                            updateQuality(ConnectionQuality.POOR)
                        }
                    }
                }

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate == null) return
                    val callId = currentCallId ?: return
                    dispatchSignal(
                        peerID,
                        CallSignal(
                            callId = callId,
                            kind = CallSignalKind.ICE_CANDIDATE,
                            candidate = candidate.sdp,
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex
                        )
                    )
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {
                    stream?.audioTracks?.forEach { it.setEnabled(true) }
                }
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    val track = receiver?.track()
                    if (track is org.webrtc.AudioTrack) {
                        track.setEnabled(true)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "createPeerConnection failed: ${e.message}", e)
            null
        }
    }

    private fun updateQuality(quality: ConnectionQuality) {
        if (quality == currentQuality) return
        currentQuality = quality
        syncConnectedState()
    }

    private fun attachLocalAudio(factory: PeerConnectionFactory, pc: PeerConnection) {
        if (localAudioTrack != null) return
        try {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            }
            val source = factory.createAudioSource(constraints)
            val track = factory.createAudioTrack(AUDIO_TRACK_ID, source)
            track.setEnabled(!isMuted)
            localAudioSource = source
            localAudioTrack = track
            pc.addTrack(track, listOf(LOCAL_STREAM_ID))
        } catch (e: Exception) {
            Log.e(TAG, "attachLocalAudio failed: ${e.message}", e)
        }
    }

    private fun flushPendingRemoteIce() {
        val pc = peerConnection ?: return
        if (!remoteDescriptionSet) return
        val pending = pendingRemoteIce.toList()
        pendingRemoteIce.clear()
        pending.forEach { candidate ->
            try { pc.addIceCandidate(candidate) } catch (e: Exception) { Log.w(TAG, "addIceCandidate (queued) failed: ${e.message}") }
        }
    }

    private fun markConnected() {
        val callId = currentCallId ?: return
        val peerID = currentPeerID ?: return
        val current = _callState.value
        if (current is CallState.Connected) {
            // Already connected, just update quality
            syncConnectedState()
            return
        }
        val displayName = resolveDisplayName?.invoke(peerID) ?: peerID.take(8)
        _callState.value = CallState.Connected(
            callId = callId,
            peerID = peerID,
            displayName = displayName,
            connectedAtMs = callStartedAtMs ?: System.currentTimeMillis(),
            isMuted = isMuted,
            isSpeakerOn = isSpeakerOn,
            connectionQuality = currentQuality
        )
    }

    private fun syncConnectedState() {
        val current = _callState.value
        if (current is CallState.Connected) {
            _callState.value = current.copy(
                isMuted = isMuted,
                isSpeakerOn = isSpeakerOn,
                connectionQuality = currentQuality
            )
        }
    }

    // MARK: - Auto-reconnect

    private fun startDisconnectGrace() {
        cancelDisconnectGrace()
        disconnectGraceJob = scope.launch {
            delay(DISCONNECT_GRACE_MS)
            // If still disconnected after grace period, try to reconnect
            val state = _callState.value
            if (state is CallState.Connected || state is CallState.Connecting) {
                if (settings.isAutoReconnectEnabled()) {
                    attemptReconnect()
                }
            }
        }
    }

    private fun cancelDisconnectGrace() {
        disconnectGraceJob?.cancel()
        disconnectGraceJob = null
    }

    private fun attemptReconnect() {
        val maxAttempts = settings.maxReconnectAttempts
        if (reconnectAttempts >= maxAttempts) {
            Log.w(TAG, "Max reconnect attempts ($maxAttempts) reached, ending call")
            endCall(EndReason.CONNECTION_FAILED)
            return
        }

        reconnectAttempts++
        isReconnecting = true
        val delayMs = settings.getReconnectDelay(reconnectAttempts)
        Log.d(TAG, "Reconnect attempt $reconnectAttempts/$maxAttempts in ${delayMs}ms")

        updateQuality(ConnectionQuality.FAILING)

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            val pc = peerConnection
            if (pc != null) {
                try {
                    // Restart ICE to try new candidates
                    pc.restartIce()
                    // Create and send a new offer
                    pc.createOffer(object : SimpleSdpObserver() {
                        override fun onCreateSuccess(sdp: SessionDescription?) {
                            if (sdp == null) return
                            pc.setLocalDescription(object : SimpleSdpObserver() {
                                override fun onSetSuccess() {
                                    val callId = currentCallId ?: return
                                    val peerID = currentPeerID ?: return
                                    val signal = CallSignal(callId = callId, kind = CallSignalKind.OFFER, sdp = sdp.description)
                                    dispatchSignal(peerID, signal)
                                }
                            }, sdp)
                        }
                        override fun onCreateFailure(error: String?) {
                            Log.e(TAG, "Reconnect createOffer failed: $error")
                            if (reconnectAttempts < maxAttempts) {
                                attemptReconnect()
                            } else {
                                endCall(EndReason.CONNECTION_FAILED)
                            }
                        }
                    }, MediaConstraints())
                } catch (e: Exception) {
                    Log.e(TAG, "Reconnect restartIce failed: ${e.message}")
                    if (reconnectAttempts < maxAttempts) {
                        attemptReconnect()
                    } else {
                        endCall(EndReason.CONNECTION_FAILED)
                    }
                }
            }
        }
    }

    private fun dispatchSignal(peerID: String, signal: CallSignal, onFailure: (() -> Unit)? = null) {
        try {
            val ok = sendSignal?.invoke(peerID, signal.encode()) ?: false
            if (!ok) {
                Log.w(TAG, "dispatchSignal: no route to $peerID for ${signal.kind}")
                onFailure?.invoke()
            }
        } catch (e: Exception) {
            Log.e(TAG, "dispatchSignal failed: ${e.message}")
            onFailure?.invoke()
        }
    }

    private fun startRingTimeout() {
        cancelRingTimeout()
        ringTimeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            val state = _callState.value
            if (state is CallState.Outgoing || state is CallState.Incoming) {
                endCall(EndReason.NO_ANSWER)
            }
        }
    }

    private fun cancelRingTimeout() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
    }

    private fun endCall(reason: EndReason) {
        val state = _callState.value
        val peerID = currentPeerID
        val displayName = when (state) {
            is CallState.Outgoing -> state.displayName
            is CallState.Incoming -> state.displayName
            is CallState.Connecting -> state.displayName
            is CallState.Connected -> state.displayName
            else -> peerID?.take(8) ?: "?"
        }
        if (state !is CallState.Idle && state !is CallState.Ended && peerID != null && currentCallId != null &&
            reason == EndReason.LOCAL_HANGUP
        ) {
            dispatchSignal(peerID, CallSignal(callId = currentCallId!!, kind = CallSignalKind.END))
        }
        finishCall(peerID = peerID ?: "", displayName = displayName, reason = reason)
    }

    private fun finishCall(peerID: String, displayName: String, reason: EndReason) {
        cancelRingTimeout()
        cancelDisconnectGrace()
        reconnectJob?.cancel()
        reconnectJob = null

        // Log the call
        val endedState = CallState.Ended(peerID = peerID, displayName = displayName, reason = reason)
        CallLogManager.logFromEndedState(
            state = endedState,
            callId = currentCallId,
            startedAtMs = callStartedAtMs,
            isIncoming = callIsIncoming
        )

        teardownInternal()
        _callState.value = endedState
        scope.launch {
            delay(2500)
            if (_callState.value is CallState.Ended) {
                _callState.value = CallState.Idle
            }
        }
    }

    private fun teardownInternal() {
        try { peerConnection?.close() } catch (_: Exception) {}
        try { peerConnection?.dispose() } catch (_: Exception) {}
        try { localAudioSource?.dispose() } catch (_: Exception) {}
        peerConnection = null
        localAudioTrack = null
        localAudioSource = null
        currentCallId = null
        currentPeerID = null
        pendingRemoteIce.clear()
        remoteDescriptionSet = false
        isMuted = false
        isSpeakerOn = false
        currentQuality = ConnectionQuality.UNKNOWN
        callStartedAtMs = null
        reconnectAttempts = 0
        isReconnecting = false
        releaseAudioFocus()
        try {
            audioManager().mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
    }

    private fun requestAudioFocus() {
        try {
            val am = audioManager()
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .build()
                audioFocusRequest = request
                am.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    { },
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "requestAudioFocus failed: ${e.message}")
        }
    }

    private fun releaseAudioFocus() {
        try {
            val am = audioManager()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
        audioFocusRequest = null
    }
}

private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
