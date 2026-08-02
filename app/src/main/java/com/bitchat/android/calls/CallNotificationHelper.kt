package com.bitchat.android.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.bitchat.android.MainActivity
import com.bitchat.android.R

/**
 * Builds and posts notifications for the voice-call foreground service:
 *   - **Incoming call** – full-screen intent + high-priority ringtone/vibration.
 *   - **Ongoing call** – foreground-service notification with hang-up action.
 *   - **Missed call** – brief informational notification after the call ends.
 */
object CallNotificationHelper {

    const val INCOMING_CHANNEL_ID = "bitchat_call_incoming"
    const val ONGOING_CHANNEL_ID = "bitchat_call_ongoing"
    const val MISSED_CHANNEL_ID = "bitchat_call_missed"

    const val INCOMING_NOTIFICATION_ID = 20001
    const val ONGOING_NOTIFICATION_ID = 20002
    const val MISSED_NOTIFICATION_ID = 20003

    // Intent extras for call actions
    const val EXTRA_CALL_ACTION = "call_action"
    const val EXTRA_CALL_ID = "call_id"
    const val EXTRA_PEER_ID = "call_peer_id"

    const val ACTION_ACCEPT = "com.bitchat.android.call.ACCEPT"
    const val ACTION_REJECT = "com.bitchat.android.call.REJECT"
    const val ACTION_HANGUP = "com.bitchat.android.call.HANGUP"
    const val ACTION_MUTE = "com.bitchat.android.call.MUTE"

    // ──────────────────────────── Channel setup ────────────────────────────

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Incoming calls – high priority, plays sound + vibration
        val incoming = NotificationChannel(
            INCOMING_CHANNEL_ID,
            context.getString(R.string.call_channel_incoming),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.call_channel_incoming_desc)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        // Ongoing call – low priority, silent
        val ongoing = NotificationChannel(
            ONGOING_CHANNEL_ID,
            context.getString(R.string.call_channel_ongoing),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.call_channel_ongoing_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        // Missed call
        val missed = NotificationChannel(
            MISSED_CHANNEL_ID,
            context.getString(R.string.call_channel_missed),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.call_channel_missed_desc)
            setShowBadge(true)
        }

        nm.createNotificationChannels(listOf(incoming, ongoing, missed))
    }

    // ──────────────────────────── Notification builders ────────────────────────────

    /**
     * Full-screen, high-priority incoming call notification. Uses a full-screen intent
     * to light up the screen and optionally show an incoming-call activity.
     */
    fun buildIncomingCallNotification(
        context: Context,
        callId: String,
        peerID: String,
        displayName: String
    ): Notification {
        // Tap opens the app to the call screen
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(EXTRA_CALL_ACTION, ACTION_ACCEPT)
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_PEER_ID, peerID)
        }
        val openPending = PendingIntent.getActivity(
            context, callId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Accept action
        val acceptIntent = Intent(context, CallForegroundService::class.java).apply {
            action = ACTION_ACCEPT
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_PEER_ID, peerID)
        }
        val acceptPending = PendingIntent.getService(
            context, callId.hashCode() + 1, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Reject action
        val rejectIntent = Intent(context, CallForegroundService::class.java).apply {
            action = ACTION_REJECT
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_PEER_ID, peerID)
        }
        val rejectPending = PendingIntent.getService(
            context, callId.hashCode() + 2, rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Full-screen intent (launches the app to show incoming call UI)
        val fullScreenPending = PendingIntent.getActivity(
            context, callId.hashCode() + 3, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, INCOMING_CHANNEL_ID)
            .setContentTitle(displayName)
            .setContentText(context.getString(R.string.call_status_incoming))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPending)
            .setFullScreenIntent(fullScreenPending, true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.cd_reject_call),
                rejectPending
            )
            .addAction(
                android.R.drawable.ic_menu_call,
                context.getString(R.string.cd_accept_call),
                acceptPending
            )
            .build()
    }

    /**
     * Silent, ongoing notification shown during an active call. Includes a hang-up
     * action button so the user can end the call from the notification shade.
     */
    fun buildOngoingCallNotification(
        context: Context,
        callId: String,
        peerID: String,
        displayName: String,
        isMuted: Boolean,
        isSpeakerOn: Boolean
    ): Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val openPending = PendingIntent.getActivity(
            context, callId.hashCode() + 10, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Hang-up action
        val hangupIntent = Intent(context, CallForegroundService::class.java).apply {
            action = ACTION_HANGUP
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_PEER_ID, peerID)
        }
        val hangupPending = PendingIntent.getService(
            context, callId.hashCode() + 11, hangupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Mute toggle action
        val muteIntent = Intent(context, CallForegroundService::class.java).apply {
            action = ACTION_MUTE
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_PEER_ID, peerID)
        }
        val mutePending = PendingIntent.getService(
            context, callId.hashCode() + 12, muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteLabel = if (isMuted)
            context.getString(R.string.call_action_unmute)
        else
            context.getString(R.string.call_action_mute)

        val statusText = if (isMuted)
            context.getString(R.string.call_status_muted)
        else
            context.getString(R.string.call_status_active)

        return NotificationCompat.Builder(context, ONGOING_CHANNEL_ID)
            .setContentTitle(displayName)
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPending)
            .addAction(
                android.R.drawable.ic_btn_speak_more,
                muteLabel,
                mutePending
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.cd_end_call),
                hangupPending
            )
            .build()
    }

    /**
     * Brief notification shown after a missed incoming call. Tapping opens the app.
     */
    fun buildMissedCallNotification(
        context: Context,
        peerID: String,
        displayName: String
    ): Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val openPending = PendingIntent.getActivity(
            context, peerID.hashCode() + 20, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, MISSED_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.call_missed_title))
            .setContentText(displayName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openPending)
            .build()
    }
}
