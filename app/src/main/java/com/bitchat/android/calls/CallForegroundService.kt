package com.bitchat.android.calls

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the voice call alive while the app is in the
 * background and displays the appropriate notification:
 *
 *  - **Incoming call** → full-screen notification with ringtone/vibration + accept/reject buttons.
 *  - **Active call** → silent ongoing notification with mute/hang-up actions.
 *  - **Missed call** → brief informational notification (auto-dismissed after tap).
 *
 * The service is started/stopped by [CallManager] whenever a call transitions
 * into or out of the active range (Incoming, Connecting, Connected).
 *
 * It also acquires a partial [PowerManager.WakeLock] during calls to prevent the
 * CPU from sleeping mid-conversation.
 */
class CallForegroundService : Service() {

    companion object {
        private const val TAG = "CallForegroundService"
        private const val WAKELOCK_TAG = "bitchat:CallWakeLock"
        private const val WAKELOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L // 4 hours max

        const val ACTION_START_CALL = "com.bitchat.android.call.service.START"
        const val ACTION_STOP_CALL = "com.bitchat.android.call.service.STOP"

        /**
         * Start the service to manage notifications for the current call state.
         * The actual call state is observed from [CallManager.callState].
         */
        fun start(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_START_CALL
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start foreground service: ${e.message}")
                }
            } else {
                try {
                    context.startService(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service: ${e.message}")
                }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_STOP_CALL
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send stop intent: ${e.message}")
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var observeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isInForeground = false
    private var lastCallId: String? = null
    private var wasMissed = false

    override fun onCreate() {
        super.onCreate()
        CallNotificationHelper.createChannels(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_CALL -> {
                releaseWakeLock()
                cancelAllNotifications()
                stopService()
                return START_NOT_STICKY
            }
            CallNotificationHelper.ACTION_ACCEPT -> {
                val callManager = CallManager.getInstance(applicationContext)
                callManager.acceptCall()
                return START_STICKY
            }
            CallNotificationHelper.ACTION_REJECT -> {
                val callManager = CallManager.getInstance(applicationContext)
                callManager.rejectCall()
                return START_STICKY
            }
            CallNotificationHelper.ACTION_HANGUP -> {
                val callManager = CallManager.getInstance(applicationContext)
                callManager.hangUp()
                return START_STICKY
            }
            CallNotificationHelper.ACTION_MUTE -> {
                val callManager = CallManager.getInstance(applicationContext)
                callManager.toggleMute()
                return START_STICKY
            }
            else -> { /* ACTION_START_CALL or null */ }
        }

        acquireWakeLock()
        startObservingCallState()

        return START_STICKY
    }

    /**
     * Observe [CallManager.callState] and update the notification/wake-lock accordingly.
     */
    private fun startObservingCallState() {
        observeJob?.cancel()
        val callManager = CallManager.getInstance(applicationContext)
        observeJob = scope.launch {
            callManager.callState.collectLatest { state ->
                handleCallState(state, callManager)
            }
        }
    }

    private fun handleCallState(state: CallState, callManager: CallManager) {
        val nm = NotificationManagerCompat.from(applicationContext)

        when (state) {
            is CallState.Idle -> {
                // If the call was incoming and ended without being answered → missed call
                if (wasMissed) {
                    val notif = CallNotificationHelper.buildMissedCallNotification(
                        applicationContext,
                        peerID = lastCallId ?: "",
                        displayName = state.let {
                            // Idle has no display name; try to resolve from callManager
                            ""
                        }
                    )
                    // Only show missed notification if we tracked a missed call
                }
                wasMissed = false
                releaseWakeLock()
                cancelAllNotifications()
                stopService()
            }

            is CallState.Incoming -> {
                wasMissed = true
                lastCallId = state.callId
                val notif = CallNotificationHelper.buildIncomingCallNotification(
                    applicationContext,
                    callId = state.callId,
                    peerID = state.peerID,
                    displayName = state.displayName
                )
                promoteToForeground(notif, state.callId)
            }

            is CallState.Outgoing -> {
                wasMissed = false
                lastCallId = state.callId
                val notif = CallNotificationHelper.buildOngoingCallNotification(
                    applicationContext,
                    callId = state.callId,
                    peerID = state.peerID,
                    displayName = state.displayName,
                    isMuted = false,
                    isSpeakerOn = false
                )
                promoteToForeground(notif, state.callId)
                // Cancel any incoming notification
                nm.cancel(CallNotificationHelper.INCOMING_NOTIFICATION_ID)
            }

            is CallState.Connecting -> {
                wasMissed = false
                lastCallId = state.callId
                val notif = CallNotificationHelper.buildOngoingCallNotification(
                    applicationContext,
                    callId = state.callId,
                    peerID = state.peerID,
                    displayName = state.displayName,
                    isMuted = false,
                    isSpeakerOn = false
                )
                promoteToForeground(notif, state.callId)
                nm.cancel(CallNotificationHelper.INCOMING_NOTIFICATION_ID)
            }

            is CallState.Connected -> {
                wasMissed = false
                lastCallId = state.callId
                val notif = CallNotificationHelper.buildOngoingCallNotification(
                    applicationContext,
                    callId = state.callId,
                    peerID = state.peerID,
                    displayName = state.displayName,
                    isMuted = state.isMuted,
                    isSpeakerOn = state.isSpeakerOn
                )
                promoteToForeground(notif, state.callId)
                nm.cancel(CallNotificationHelper.INCOMING_NOTIFICATION_ID)
            }

            is CallState.Ended -> {
                // If the incoming call was rejected (not answered) → show missed
                if (wasMissed && state.reason == EndReason.REMOTE_HANGUP || state.reason == EndReason.NO_ANSWER) {
                    // Show missed call notification on a separate channel
                    val missedNotif = CallNotificationHelper.buildMissedCallNotification(
                        applicationContext,
                        peerID = state.peerID,
                        displayName = state.displayName
                    )
                    nm.notify(CallNotificationHelper.MISSED_NOTIFICATION_ID, missedNotif)
                }
                wasMissed = false

                // Remove ongoing/incoming notifications
                nm.cancel(CallNotificationHelper.INCOMING_NOTIFICATION_ID)
                nm.cancel(CallNotificationHelper.ONGOING_NOTIFICATION_ID)

                // Release wake lock and stop foreground
                releaseWakeLock()
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                } catch (_: Exception) {}
                isInForeground = false

                // Auto-dismiss missed notification after 8 seconds
                scope.launch {
                    delay(8000)
                    nm.cancel(CallNotificationHelper.MISSED_NOTIFICATION_ID)
                    stopService()
                }
            }
        }
    }

    private fun promoteToForeground(notification: Notification, callId: String) {
        val nm = NotificationManagerCompat.from(applicationContext)

        if (!isInForeground) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        CallNotificationHelper.ONGOING_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForeground(
                        CallNotificationHelper.ONGOING_NOTIFICATION_ID,
                        notification
                    )
                } else {
                    startForeground(
                        CallNotificationHelper.ONGOING_NOTIFICATION_ID,
                        notification
                    )
                }
                isInForeground = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground: ${e.message}")
                // Fallback: just show notification without foreground
                nm.notify(CallNotificationHelper.ONGOING_NOTIFICATION_ID, notification)
            }
        } else {
            // Already in foreground → just update the notification
            nm.notify(CallNotificationHelper.ONGOING_NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WakeLock: ${e.message}")
        }
        wakeLock = null
    }

    private fun cancelAllNotifications() {
        val nm = NotificationManagerCompat.from(applicationContext)
        nm.cancel(CallNotificationHelper.INCOMING_NOTIFICATION_ID)
        nm.cancel(CallNotificationHelper.ONGOING_NOTIFICATION_ID)
        nm.cancel(CallNotificationHelper.MISSED_NOTIFICATION_ID)
    }

    private fun stopService() {
        try {
            if (isInForeground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                isInForeground = false
            }
        } catch (_: Exception) {}
        observeJob?.cancel()
        observeJob = null
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        observeJob?.cancel()
        observeJob = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
