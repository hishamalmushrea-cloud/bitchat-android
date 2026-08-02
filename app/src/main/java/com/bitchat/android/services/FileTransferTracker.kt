package com.bitchat.android.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the upload progress of media (images, files, voice notes) being sent.
 * Exposes a [StateFlow] of active transfers so the UI can display progress bars
 * and cancel buttons for in-flight sends.
 */
object FileTransferTracker {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    data class ActiveTransfer(
        val id: String,
        val fileName: String,
        val filePath: String,
        val totalBytes: Long,
        val sentBytes: Long = 0,
        val isComplete: Boolean = false,
        val isCancelled: Boolean = false,
        val errorMessage: String? = null
    ) {
        val progress: Float
            get() = if (totalBytes > 0) (sentBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

        val progressPercent: Int
            get() = (progress * 100).toInt()
    }

    private val _transfers = MutableStateFlow<Map<String, ActiveTransfer>>(emptyMap())
    val transfers: StateFlow<Map<String, ActiveTransfer>> = _transfers.asStateFlow()

    // Set of cancelled transfer IDs (checked before sending chunks)
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()

    fun startTransfer(id: String, fileName: String, filePath: String, totalBytes: Long) {
        val transfer = ActiveTransfer(
            id = id,
            fileName = fileName,
            filePath = filePath,
            totalBytes = totalBytes
        )
        _transfers.value = _transfers.value + (id to transfer)
    }

    fun updateProgress(id: String, sentBytes: Long) {
        val current = _transfers.value[id] ?: return
        _transfers.value = _transfers.value + (id to current.copy(sentBytes = sentBytes))
    }

    fun completeTransfer(id: String) {
        val current = _transfers.value[id] ?: return
        _transfers.value = _transfers.value + (id to current.copy(isComplete = true))
        // Auto-remove after a short delay
        scope.launch {
            delay(2000)
            removeTransfer(id)
        }
    }

    fun failTransfer(id: String, error: String) {
        val current = _transfers.value[id] ?: return
        _transfers.value = _transfers.value + (id to current.copy(errorMessage = error))
    }

    fun cancelTransfer(id: String) {
        cancelledIds.add(id)
        val current = _transfers.value[id] ?: return
        _transfers.value = _transfers.value + (id to current.copy(isCancelled = true))
    }

    fun removeTransfer(id: String) {
        _transfers.value = _transfers.value - id
        cancelledIds.remove(id)
    }

    fun isCancelled(id: String): Boolean = cancelledIds.contains(id)

    fun clearCompleted() {
        _transfers.value = _transfers.value.filter { !it.value.isComplete && !it.value.isCancelled }
    }

    fun clearAll() {
        _transfers.value = emptyMap()
        cancelledIds.clear()
    }
}
