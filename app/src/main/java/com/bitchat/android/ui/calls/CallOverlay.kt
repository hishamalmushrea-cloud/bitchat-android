package com.bitchat.android.ui.calls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.SignalCellularConnectedNoInternet0Bar
import androidx.compose.material.icons.filled.SignalCellularNull
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.bitchat.android.R
import com.bitchat.android.calls.CallState
import com.bitchat.android.calls.ConnectionQuality
import com.bitchat.android.calls.EndReason
import kotlinx.coroutines.delay

/**
 * Full-screen-ish overlay shown whenever a voice call is active (outgoing, incoming,
 * connecting, connected, or briefly on end). Rendered above the normal chat UI.
 *
 * Deliberately terse/utilitarian to match bitchat's terminal aesthetic rather than
 * imitating a stock phone dialer UI.
 */
@Composable
fun CallOverlay(
    callState: CallState,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    val visible = callState !is CallState.Idle
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f),
            color = Color.Black.copy(alpha = 0.92f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (callState) {
                    is CallState.Outgoing -> CallHeader(
                        name = callState.displayName,
                        statusText = stringResource(R.string.call_status_calling)
                    )
                    is CallState.Incoming -> CallHeader(
                        name = callState.displayName,
                        statusText = stringResource(R.string.call_status_incoming)
                    )
                    is CallState.Connecting -> CallHeader(
                        name = callState.displayName,
                        statusText = stringResource(R.string.call_status_connecting)
                    )
                    is CallState.Connected -> {
                        var elapsedSecs by remember(callState.callId) { mutableStateOf(0L) }
                        LaunchedEffect(callState.callId) {
                            while (true) {
                                elapsedSecs = (System.currentTimeMillis() - callState.connectedAtMs) / 1000
                                delay(1000)
                            }
                        }
                        val mm = elapsedSecs / 60
                        val ss = elapsedSecs % 60
                        CallHeader(
                            name = callState.displayName,
                            statusText = String.format("%02d:%02d", mm, ss)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ConnectionQualityBadge(quality = callState.connectionQuality)
                    }
                    is CallState.Ended -> CallHeader(
                        name = callState.displayName,
                        statusText = endReasonText(callState.reason)
                    )
                    CallState.Idle -> {}
                }

                Spacer(modifier = Modifier.height(48.dp))

                when (callState) {
                    is CallState.Incoming -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CallActionButton(
                                icon = Icons.Filled.CallEnd,
                                background = Color(0xFFFF3B30),
                                contentDescription = stringResource(R.string.cd_reject_call),
                                onClick = onReject
                            )
                            CallActionButton(
                                icon = Icons.Filled.Call,
                                background = Color(0xFF32D74B),
                                contentDescription = stringResource(R.string.cd_accept_call),
                                onClick = onAccept
                            )
                        }
                    }
                    is CallState.Outgoing, is CallState.Connecting -> {
                        CallActionButton(
                            icon = Icons.Filled.CallEnd,
                            background = Color(0xFFFF3B30),
                            contentDescription = stringResource(R.string.cd_end_call),
                            onClick = onHangUp
                        )
                    }
                    is CallState.Connected -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(32.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CallActionButton(
                                icon = if (callState.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                                background = if (callState.isMuted) Color(0xFF555555) else Color(0xFF2C2C2E),
                                contentDescription = stringResource(R.string.cd_toggle_mute),
                                onClick = onToggleMute,
                                size = 56.dp
                            )
                            CallActionButton(
                                icon = Icons.Filled.CallEnd,
                                background = Color(0xFFFF3B30),
                                contentDescription = stringResource(R.string.cd_end_call),
                                onClick = onHangUp
                            )
                            CallActionButton(
                                icon = if (callState.isSpeakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                                background = if (callState.isSpeakerOn) Color(0xFF0A84FF) else Color(0xFF2C2C2E),
                                contentDescription = stringResource(R.string.cd_toggle_speaker),
                                onClick = onToggleSpeaker,
                                size = 56.dp
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun CallHeader(name: String, statusText: String) {
    Text(
        text = name,
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = statusText,
        color = Color.White.copy(alpha = 0.7f),
        fontFamily = FontFamily.Monospace,
        fontSize = 16.sp
    )
}

@Composable
private fun CallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    background: Color,
    contentDescription: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 64.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .then(Modifier.clickableNoRipple(onClick)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(size * 0.45f)
        )
    }
}

@Composable
private fun ConnectionQualityBadge(quality: ConnectionQuality) {
    val (icon, label, color) = when (quality) {
        ConnectionQuality.EXCELLENT -> Triple(
            Icons.Filled.SignalCellular4Bar,
            stringResource(R.string.call_quality_excellent),
            Color(0xFF32D74B)
        )
        ConnectionQuality.GOOD -> Triple(
            Icons.Filled.SignalCellular4Bar,
            stringResource(R.string.call_quality_good),
            Color(0xFF30D158)
        )
        ConnectionQuality.POOR -> Triple(
            Icons.Filled.SignalCellularConnectedNoInternet0Bar,
            stringResource(R.string.call_quality_poor),
            Color(0xFFFF9F0A)
        )
        ConnectionQuality.FAILING -> Triple(
            Icons.Filled.SignalCellularConnectedNoInternet0Bar,
            stringResource(R.string.call_quality_failing),
            Color(0xFFFF453A)
        )
        ConnectionQuality.UNKNOWN -> Triple(
            Icons.Filled.SignalCellularNull,
            stringResource(R.string.call_quality_unknown),
            Color(0xFF8E8E93)
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            color = color.copy(alpha = 0.8f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun endReasonText(reason: EndReason): String = when (reason) {
    EndReason.LOCAL_HANGUP, EndReason.REMOTE_HANGUP -> stringResource(R.string.call_status_ended)
    EndReason.REMOTE_BUSY -> stringResource(R.string.call_status_busy)
    EndReason.NO_ANSWER -> stringResource(R.string.call_status_no_answer)
    EndReason.CONNECTION_FAILED -> stringResource(R.string.call_status_failed)
    EndReason.NO_ROUTE -> stringResource(R.string.call_status_no_route)
    EndReason.ERROR -> stringResource(R.string.call_status_failed)
}

// Small helper to avoid pulling in the full `combinedClickable`/ripple machinery for
// these simple circular buttons; keeps the file self-contained.
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    androidx.compose.foundation.clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
}
