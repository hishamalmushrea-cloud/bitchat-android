package com.bitchat.android.ui.media

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bitchat.android.R
import java.io.File

/**
 * Image preview sheet shown after selecting images. Allows the user to:
 *  - See all selected images as thumbnails in a horizontal row.
 *  - Remove individual images from the selection.
 *  - Confirm sending or cancel.
 *
 * @param imagePaths List of absolute file paths to the images to preview.
 * @param onSend Callback invoked with the confirmed list of paths (may be a subset after removals).
 * @param onCancel Callback invoked when the user cancels the preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePreviewSheet(
    imagePaths: List<String>,
    onSend: (List<String>) -> Unit,
    onCancel: () -> Unit
) {
    var paths by remember { mutableStateOf(imagePaths.toMutableList()) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.image_preview_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${paths.size} ${stringResource(R.string.image_preview_count_suffix)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Horizontal scroll of thumbnails
            if (paths.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.image_preview_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(paths, key = { it }) { path ->
                        ImagePreviewThumbnail(
                            path = path,
                            onRemove = {
                                paths = paths.filter { it != path }.toMutableList()
                            }
                        )
                    }
                }
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cancel
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.image_preview_cancel))
                }

                // Send
                Button(
                    onClick = { onSend(paths) },
                    modifier = Modifier.weight(1f),
                    enabled = paths.isNotEmpty()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.image_preview_send))
                }
            }
        }
    }
}

@Composable
private fun ImagePreviewThumbnail(
    path: String,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val fileSize = remember(path) {
        try { File(path).length() / 1024 } catch (_: Exception) { 0L }
    }
    val dimensions = remember(path) {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            "${opts.outWidth}×${opts.outHeight}"
        } catch (_: Exception) { "" }
    }

    Box(
        modifier = Modifier.size(100.dp)
    ) {
        // Image
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(File(path))
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
        )

        // File info overlay (bottom)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Color.Black.copy(alpha = 0.5f),
                    RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                )
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text(
                text = if (dimensions.isNotEmpty()) "$dimensions · ${fileSize}KB" else "${fileSize}KB",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
        }

        // Remove button (top-right)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(24.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.image_preview_remove),
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
