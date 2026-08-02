package com.bitchat.android.ui.calls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R
import com.bitchat.android.calls.CallDirection
import com.bitchat.android.calls.CallLogEntry
import com.bitchat.android.calls.CallLogManager
import com.bitchat.android.calls.CallOutcome
import java.text.SimpleDateFormat
import java.util.*

/**
 * Displays the call history log. Shows recent calls with direction icons,
 * outcome labels, duration, and timestamps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallLogScreen(
    onNavigateBack: () -> Unit,
    onStartCall: (peerID: String) -> Unit
) {
    val callLog by CallLogManager.callLog.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.call_log_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                },
                actions = {
                    if (callLog.isNotEmpty()) {
                        IconButton(onClick = { CallLogManager.clearLog() }) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.call_log_clear)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (callLog.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.call_log_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(callLog, key = { "${it.callId}_${it.timestampMs}" }) { entry ->
                    CallLogItem(
                        entry = entry,
                        onCall = { onStartCall(entry.peerID) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CallLogItem(
    entry: CallLogEntry,
    onCall: () -> Unit
) {
    val (icon: ImageVector, iconColor: Color) = when (entry.direction) {
        CallDirection.INCOMING -> when (entry.outcome) {
            CallOutcome.COMPLETED -> Icons.Filled.CallReceived to Color(0xFF32D74B)
            CallOutcome.MISSED -> Icons.Filled.CallMissed to Color(0xFFFF3B30)
            else -> Icons.Filled.CallReceived to Color(0xFF8E8E93)
        }
        CallDirection.OUTGOING -> when (entry.outcome) {
            CallOutcome.COMPLETED -> Icons.Filled.CallMade to Color(0xFF32D74B)
            CallOutcome.FAILED, CallOutcome.NO_ANSWER, CallOutcome.BUSY -> Icons.Filled.CallMade to Color(0xFFFF9F0A)
            CallOutcome.CANCELLED -> Icons.Filled.CallMade to Color(0xFF8E8E93)
            else -> Icons.Filled.CallMade to Color(0xFF0A84FF)
        }
    }

    val outcomeText = when (entry.outcome) {
        CallOutcome.COMPLETED -> stringResource(R.string.call_log_completed)
        CallOutcome.MISSED -> stringResource(R.string.call_log_missed)
        CallOutcome.CANCELLED -> stringResource(R.string.call_log_cancelled)
        CallOutcome.BUSY -> stringResource(R.string.call_log_busy)
        CallOutcome.NO_ANSWER -> stringResource(R.string.call_log_no_answer)
        CallOutcome.FAILED -> stringResource(R.string.call_log_failed)
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Direction icon
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .then(
                        Modifier.background_compat(iconColor.copy(alpha = 0.15f))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Name + outcome
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = outcomeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = iconColor
                    )
                    if (entry.durationMs > 3000) {
                        Text(
                            text = "· ${formatDuration(entry.durationMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Timestamp
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = timeFormat.format(Date(entry.timestampMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = dateFormat.format(Date(entry.timestampMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            // Call back button (only for completed/missed calls)
            if (entry.outcome == CallOutcome.COMPLETED || entry.outcome == CallOutcome.MISSED) {
                IconButton(onClick = onCall, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Call,
                        contentDescription = null,
                        tint = Color(0xFF32D74B),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSecs = ms / 1000
    val mm = totalSecs / 60
    val ss = totalSecs % 60
    return if (mm > 0) "${mm}m ${ss}s" else "${ss}s"
}

// Helper to avoid compose import conflicts
private fun Modifier.background_compat(color: Color): Modifier =
    this.then(androidx.compose.foundation.background(color))
