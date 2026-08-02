package com.bitchat.android.ui.media

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bitchat.android.features.media.ImageUtils
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImagePickerButton(
    modifier: Modifier = Modifier,
    onImageReady: (String) -> Unit
) {
    val context = LocalContext.current
    var capturedImagePath by remember { mutableStateOf<String?>(null) }

    // State for image preview sheet
    var pendingImagePaths by remember { mutableStateOf<List<String>?>(null) }

    // Allow selecting multiple images at once (up to 10) using the modern Photo Picker.
    val multiImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris: List<android.net.Uri> ->
        if (uris.isNotEmpty()) {
            val paths = uris.mapNotNull { uri ->
                ImageUtils.downscaleAndSaveToAppFiles(context, uri)
            }.filter { it.isNotBlank() }
            if (paths.isNotEmpty()) {
                pendingImagePaths = paths
            }
        }
    }

    // Fallback for devices/OS versions where the Photo Picker isn't available
    val legacyImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            val outPath = ImageUtils.downscaleAndSaveToAppFiles(context, uri)
            if (!outPath.isNullOrBlank()) {
                pendingImagePaths = listOf(outPath)
            }
        }
    }

    fun launchImagePicker() {
        try {
            if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) {
                multiImagePicker.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            } else {
                legacyImagePicker.launch("image/*")
            }
        } catch (e: Exception) {
            android.util.Log.w("ImagePickerButton", "Falling back to legacy image picker: ${e.message}")
            legacyImagePicker.launch("image/*")
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val path = capturedImagePath
        if (success && !path.isNullOrBlank()) {
            val outPath = com.bitchat.android.features.media.ImageUtils.downscalePathAndSaveToAppFiles(context, path)
            if (!outPath.isNullOrBlank()) {
                pendingImagePaths = listOf(outPath)
            }
            runCatching { File(path).delete() }
        } else {
            path?.let { runCatching { File(it).delete() } }
        }
        capturedImagePath = null
    }

    fun startCameraCapture() {
        try {
            val dir = File(context.filesDir, "images/outgoing").apply { mkdirs() }
            val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
            capturedImagePath = file.absolutePath
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            android.util.Log.e("ImagePickerButton", "Camera capture failed", e)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCameraCapture()
        }
    }

    // Show preview sheet when paths are pending
    pendingImagePaths?.let { paths ->
        ImagePreviewSheet(
            imagePaths = paths,
            onSend = { confirmedPaths ->
                confirmedPaths.forEach { onImageReady(it) }
                pendingImagePaths = null
            },
            onCancel = {
                // Clean up pending images when user cancels
                paths.forEach { path ->
                    runCatching { File(path).delete() }
                }
                pendingImagePaths = null
            }
        )
    }

    Box(
        modifier = modifier
            .size(32.dp)
            .combinedClickable(
                onClick = { launchImagePicker() },
                onLongClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        startCameraCapture()
                    } else {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.PhotoCamera,
            contentDescription = stringResource(com.bitchat.android.R.string.pick_image),
            tint = Color.Gray,
            modifier = Modifier.size(20.dp)
        )
    }
}
