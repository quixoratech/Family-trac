package com.example.service

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class QrDiagnosticStage {
    CODE_REQUEST_START,
    AUTH_AND_FAMILY_CHECK,
    FIRESTORE_WRITE_START,
    FIRESTORE_WRITE_RESULT,
    PAYLOAD_VALIDATION,
    MATRIX_ENCODE,
    BITMAP_ALLOCATION,
    RENDER_COMPLETE,
    ERROR
}

data class QrDiagnosticEntry(
    val id: String,
    val attemptId: String,
    val timestampMs: Long,
    val stage: QrDiagnosticStage,
    val status: String,
    val message: String,
    val payloadLength: Int? = null,
    val requestedSizePx: Int? = null,
    val durationMs: Long? = null,
    val errorType: String? = null,
    val errorStackSnippet: String? = null
)

data class QrBitmapGenerationResult(
    val bitmap: Bitmap?,
    val errorMessage: String?,
    val diagnosticReport: String,
    val attemptId: String
)

/**
 * Diagnostic logger for the end-to-end QR pairing code generation pipeline
 * (request -> Firestore write -> payload validation -> ZXing BitMatrix -> Bitmap creation).
 */
object QrDiagnosticLogger {
    private const val TAG = "QrDiagnostic"
    private const val MAX_ENTRIES = 60

    private val lock = Any()
    private val ringBuffer = ArrayDeque<QrDiagnosticEntry>(MAX_ENTRIES)
    private val _entries = MutableStateFlow<List<QrDiagnosticEntry>>(emptyList())
    val entries: StateFlow<List<QrDiagnosticEntry>> = _entries.asStateFlow()

    fun newAttemptId(): String = "qr_" + UUID.randomUUID().toString().replace("-", "").take(8)

    fun logStage(
        attemptId: String,
        stage: QrDiagnosticStage,
        status: String,
        message: String,
        payloadLength: Int? = null,
        requestedSizePx: Int? = null,
        durationMs: Long? = null,
        error: Throwable? = null
    ) {
        val now = System.currentTimeMillis()
        val entry = QrDiagnosticEntry(
            id = UUID.randomUUID().toString(),
            attemptId = attemptId,
            timestampMs = now,
            stage = stage,
            status = status,
            message = message,
            payloadLength = payloadLength,
            requestedSizePx = requestedSizePx,
            durationMs = durationMs,
            errorType = error?.javaClass?.simpleName,
            errorStackSnippet = error?.stackTraceToString()?.take(600)
        )

        val logLine = buildString {
            append("[").append(attemptId).append("] ")
            append(stage.name).append(" (").append(status).append("): ").append(message)
            if (payloadLength != null) append(" | payloadLen=").append(payloadLength)
            if (requestedSizePx != null) append(" | sizePx=").append(requestedSizePx)
            if (durationMs != null) append(" | ").append(durationMs).append("ms")
            if (error != null) append(" | err=").append(error.javaClass.simpleName).append(": ").append(error.message)
        }

        when {
            status.equals("FAIL", ignoreCase = true) || stage == QrDiagnosticStage.ERROR ->
                Log.e(TAG, logLine, error)
            status.equals("WARN", ignoreCase = true) ->
                Log.w(TAG, logLine)
            else ->
                Log.d(TAG, logLine)
        }

        synchronized(lock) {
            while (ringBuffer.size >= MAX_ENTRIES) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(entry)
            _entries.value = ringBuffer.toList()
        }
    }

    fun getEntriesForAttempt(attemptId: String? = null): List<QrDiagnosticEntry> {
        val snapshot = synchronized(lock) { ringBuffer.toList() }
        return if (attemptId.isNullOrBlank()) snapshot else snapshot.filter { it.attemptId == attemptId }
    }

    fun formatReport(attemptId: String? = null): String {
        val list = getEntriesForAttempt(attemptId)
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val maxMb = runtime.maxMemory() / (1024L * 1024L)
        val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

        return buildString {
            appendLine("QR Generation Diagnostics")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
            appendLine("Heap: ${usedMb}MB / ${maxMb}MB max")
            if (list.isEmpty()) {
                appendLine("No QR generation events recorded yet.")
            } else {
                list.takeLast(20).forEach { e ->
                    val ts = formatter.format(Date(e.timestampMs))
                    append("[$ts][${e.attemptId}] ${e.stage.name} -> ${e.status}: ${e.message}")
                    if (e.durationMs != null) append(" (${e.durationMs}ms)")
                    if (e.errorType != null) append(" [${e.errorType}]")
                    appendLine()
                }
            }
        }.trim()
    }

    fun clear() {
        synchronized(lock) {
            ringBuffer.clear()
            _entries.value = emptyList()
        }
    }
}
