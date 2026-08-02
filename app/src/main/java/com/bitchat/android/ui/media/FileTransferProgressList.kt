package com.bitchat.android.ui.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.services.FileTransferTracker

/**
 * Displays active file transfers with progress bars and cancel buttons.
 * Shown below the chat input area when transfers are in progress.
 */
@Composable
fun FileTransferProgressList(
    onCancelTransfer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val transfers by FileTransferTracker.transfers.collectAsState()

    AnimatedVisibility(
        visible = transfers.isNotEmpty(),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(transfers.entries.toList(), key = { it.key }) { (_, transfer) ->
                    FileTransferProgressItem(
                        transfer = transfer,
                        onCancel = { onCancelTransfer(transfer.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FileTransferProgressItem(
    transfer: FileTransferTracker.ActiveTransfer,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Status icon
        val statusIcon: ImageVector = when {
            transfer.isComplete -> Icons.Filled.CheckCircle
            transfer.errorMessage != null -> Icons.Filled.Error
            transfer.isCancelled -> Icons.Filled.Cancel
            else -> Icons.Filled.Cancel // Placeholder, will be replaced by progress
        }

        if (!transfer.isComplete && transfer.errorMessage == null && !transfer.isCancelled) {
            // Circular progress
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)) {
                CircularProgressIndicator(
                    progress = { transfer.progress },
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    strokeCap = StrokeCap.Round
                )
            }
        } else {
            Icon(
                imageVector = statusIcon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = when {
                    transfer.isComplete -> Color(0xFF32D74B)
                    transfer.errorMessage != null -> Color(0xFFFF3B30)
                    transfer.isCancelled -> Color(0xFF8E8E93)
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }

        // File name and progress text
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = transfer.fileName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (!transfer.isComplete && transfer.errorMessage == null && !transfer.isCancelled) {
                // Progress bar
                LinearProgressIndicator(
                    progress = { transfer.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round
                )
                Text(
                    text = "${transfer.progressPercent}% · ${formatBytes(transfer.sentBytes)} / ${formatBytes(transfer.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val statusText = when {
                    transfer.isComplete -> stringResource(R.string.transfer_complete)
                    transfer.isCancelled -> stringResource(R.string.transfer_cancelled)
                    transfer.errorMessage != null -> transfer.errorMessage
                    else -> ""
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        transfer.isComplete -> Color(0xFF32D74B)
                        transfer.isCancelled -> Color(0xFF8E8E93)
                        else -> Color(0xFFFF3B30)
                    }
                )
            }
        }

        // Cancel button (only for active transfers)
        if (!transfer.isComplete && transfer.errorMessage == null && !transfer.isCancelled) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Cancel,
                    contentDescription = stringResource(R.string.transfer_cancel),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
