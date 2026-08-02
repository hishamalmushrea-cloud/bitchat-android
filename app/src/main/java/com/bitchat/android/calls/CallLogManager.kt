package com.bitchat.android.calls

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Maintains a persistent log of voice calls (incoming, outgoing, missed) with
 * timestamps, peer info, duration, and end reason. Exposed as a [StateFlow]
 * so the UI (e.g. a call history screen) can reactively display recent calls.
 */
object CallLogManager {

    private const val LOG_FILE = "bitchat_call_log.json"
    private const val MAX_LOG_ENTRIES = 100

    private var appContext: Context? = null
    private val _callLog = MutableStateFlow<List<CallLogEntry>>(emptyList())
    val callLog: StateFlow<List<CallLogEntry>> = _callLog.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        loadLog()
    }

    /**
     * Record a completed call in the log.
     */
    fun logCall(entry: CallLogEntry) {
        val ctx = appContext ?: return
        val current = _callLog.value.toMutableList()
        current.add(0, entry) // newest first
        // Trim to max entries
        while (current.size > MAX_LOG_ENTRIES) {
            current.removeAt(current.size - 1)
        }
        _callLog.value = current.toList()
        saveLog(ctx, current)
    }

    /**
     * Build a [CallLogEntry] from a terminal [CallState.Ended] and record it.
     */
    fun logFromEndedState(
        state: CallState.Ended,
        callId: String?,
        startedAtMs: Long?,
        isIncoming: Boolean
    ) {
        val entry = CallLogEntry(
            callId = callId ?: "",
            peerID = state.peerID,
            displayName = state.displayName,
            direction = if (isIncoming) CallDirection.INCOMING else CallDirection.OUTGOING,
            outcome = when (state.reason) {
                EndReason.LOCAL_HANGUP, EndReason.REMOTE_HANGUP -> {
                    if (startedAtMs != null && System.currentTimeMillis() - startedAtMs > 3000)
                        CallOutcome.COMPLETED
                    else
                        CallOutcome.CANCELLED
                }
                EndReason.REMOTE_BUSY -> CallOutcome.BUSY
                EndReason.NO_ANSWER -> if (isIncoming) CallOutcome.MISSED else CallOutcome.NO_ANSWER
                EndReason.CONNECTION_FAILED -> CallOutcome.FAILED
                EndReason.NO_ROUTE -> CallOutcome.FAILED
                EndReason.ERROR -> CallOutcome.FAILED
            },
            durationMs = if (startedAtMs != null) System.currentTimeMillis() - startedAtMs else 0L,
            timestampMs = System.currentTimeMillis()
        )
        logCall(entry)
    }

    fun clearLog() {
        _callLog.value = emptyList()
        appContext?.let { ctx ->
            try {
                File(ctx.filesDir, LOG_FILE).delete()
            } catch (_: Exception) {}
        }
    }

    // ──────────────────────────── Persistence ────────────────────────────

    private fun loadLog() {
        val ctx = appContext ?: return
        try {
            val file = File(ctx.filesDir, LOG_FILE)
            if (!file.exists()) return
            val json = file.readText()
            val arr = JSONArray(json)
            val entries = mutableListOf<CallLogEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                entries.add(
                    CallLogEntry(
                        callId = obj.optString("callId", ""),
                        peerID = obj.optString("peerID", ""),
                        displayName = obj.optString("displayName", ""),
                        direction = CallDirection.valueOf(obj.optString("direction", "OUTGOING")),
                        outcome = CallOutcome.valueOf(obj.optString("outcome", "COMPLETED")),
                        durationMs = obj.optLong("durationMs", 0L),
                        timestampMs = obj.optLong("timestampMs", 0L)
                    )
                )
            }
            _callLog.value = entries
        } catch (e: Exception) {
            // Corrupt log file – start fresh
            _callLog.value = emptyList()
        }
    }

    private fun saveLog(ctx: Context, entries: List<CallLogEntry>) {
        try {
            val arr = JSONArray()
            entries.forEach { entry ->
                val obj = JSONObject().apply {
                    put("callId", entry.callId)
                    put("peerID", entry.peerID)
                    put("displayName", entry.displayName)
                    put("direction", entry.direction.name)
                    put("outcome", entry.outcome.name)
                    put("durationMs", entry.durationMs)
                    put("timestampMs", entry.timestampMs)
                }
                arr.put(obj)
            }
            File(ctx.filesDir, LOG_FILE).writeText(arr.toString())
        } catch (_: Exception) {}
    }
}

// ──────────────────────────── Data classes ────────────────────────────

data class CallLogEntry(
    val callId: String,
    val peerID: String,
    val displayName: String,
    val direction: CallDirection,
    val outcome: CallOutcome,
    val durationMs: Long,
    val timestampMs: Long
)

enum class CallDirection { INCOMING, OUTGOING }

enum class CallOutcome { COMPLETED, MISSED, CANCELLED, BUSY, NO_ANSWER, FAILED }
