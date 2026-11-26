package com.example.service

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeHelper {
    private const val MIN_QR_SIZE_PX = 64
    private const val MAX_QR_SIZE_PX = 2048

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
        return generateQrBitmapWithDiagnostics(content, sizePx).bitmap
    }

    fun generateQrBitmapWithDiagnostics(
        content: String,
        sizePx: Int = 512,
        attemptId: String = QrDiagnosticLogger.newAttemptId()
    ): QrBitmapGenerationResult {
        val totalStart = SystemClock.elapsedRealtime()
        val trimmed = content.trim()

        if (trimmed.isEmpty()) {
            val msg = "QR payload is empty; cannot encode pairing code."
            QrDiagnosticLogger.logStage(
                attemptId = attemptId,
                stage = QrDiagnosticStage.PAYLOAD_VALIDATION,
                status = "FAIL",
                message = msg,
                payloadLength = 0,
                requestedSizePx = sizePx
            )
            return QrBitmapGenerationResult(
                bitmap = null,
                errorMessage = msg,
                diagnosticReport = QrDiagnosticLogger.formatReport(attemptId),
                attemptId = attemptId
            )
        }

        if (sizePx !in MIN_QR_SIZE_PX..MAX_QR_SIZE_PX) {
            val msg = "Invalid QR bitmap size ($sizePx px). Must be between $MIN_QR_SIZE_PX and $MAX_QR_SIZE_PX."
            QrDiagnosticLogger.logStage(
                attemptId = attemptId,
                stage = QrDiagnosticStage.PAYLOAD_VALIDATION,
                status = "FAIL",
                message = msg,
                payloadLength = trimmed.length,
                requestedSizePx = sizePx
            )
            return QrBitmapGenerationResult(
                bitmap = null,
                errorMessage = msg,
                diagnosticReport = QrDiagnosticLogger.formatReport(attemptId),
                attemptId = attemptId
            )
        }

        QrDiagnosticLogger.logStage(
            attemptId = attemptId,
            stage = QrDiagnosticStage.PAYLOAD_VALIDATION,
            status = "OK",
            message = "Validated QR payload format",
            payloadLength = trimmed.length,
            requestedSizePx = sizePx
        )

        val encodeStart = SystemClock.elapsedRealtime()
        val matrix = try {
            val m = QRCodeWriter().encode(trimmed, BarcodeFormat.QR_CODE, sizePx, sizePx)
            if (m.width <= 0 || m.height <= 0) {
                throw IllegalStateException("ZXing returned empty BitMatrix (${m.width}x${m.height})")
            }
            m
        } catch (t: Throwable) {
            val elapsed = SystemClock.elapsedRealtime() - encodeStart
            val msg = "QR matrix encoding failed: ${t.message ?: t.javaClass.simpleName}"
            QrDiagnosticLogger.logStage(
                attemptId = attemptId,
                stage = QrDiagnosticStage.MATRIX_ENCODE,
                status = "FAIL",
                message = msg,
                payloadLength = trimmed.length,
                requestedSizePx = sizePx,
                durationMs = elapsed,
                error = t
            )
            return QrBitmapGenerationResult(
                bitmap = null,
                errorMessage = msg,
                diagnosticReport = QrDiagnosticLogger.formatReport(attemptId),
                attemptId = attemptId
            )
        }

        val encodeElapsed = SystemClock.elapsedRealtime() - encodeStart
        QrDiagnosticLogger.logStage(
            attemptId = attemptId,
            stage = QrDiagnosticStage.MATRIX_ENCODE,
            status = "OK",
            message = "Encoded BitMatrix ${matrix.width}x${matrix.height}",
            payloadLength = trimmed.length,
            requestedSizePx = sizePx,
            durationMs = encodeElapsed
        )

        val bitmapStart = SystemClock.elapsedRealtime()
        return try {
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)
            var darkCount = 0
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    val isDark = matrix[x, y]
                    if (isDark) darkCount++
                    pixels[offset + x] = if (isDark) Color.BLACK else Color.WHITE
                }
            }
            if (darkCount == 0 || darkCount == pixels.size) {
                throw IllegalStateException("QR BitMatrix contrast check failed (darkModules=$darkCount/${pixels.size})")
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
            val bitmapElapsed = SystemClock.elapsedRealtime() - bitmapStart
            val totalElapsed = SystemClock.elapsedRealtime() - totalStart
            QrDiagnosticLogger.logStage(
                attemptId = attemptId,
                stage = QrDiagnosticStage.BITMAP_ALLOCATION,
                status = "OK",
                message = "Allocated RGB_565 bitmap ${bitmap.width}x${bitmap.height} ($darkCount dark modules)",
                payloadLength = trimmed.length,
                requestedSizePx = sizePx,
                durationMs = bitmapElapsed
            )
            QrDiagnosticLogger.logStage(
                attemptId = attemptId,
                stage = QrDiagnosticStage.RENDER_COMPLETE,
                status = "OK",
                message = "QR bitmap ready for display",
                payloadLength = trimmed.length,
                requestedSizePx = sizePx,
                durationMs = totalElapsed
            )
            QrBitmapGenerationResult(
                bitmap = bitmap,
                errorMessage = null,
                diagnosticReport = QrDiagnosticLogger.formatReport(attemptId),
                attemptId = attemptId
            )
        } catch (oom: OutOfMemoryError) {
            val elapsed = SystemClock.elapsedRealtime() - bitmapStart
            QrDiagnosticLogger.logStage(
                attemptId = attemptId,
                stage = QrDiagnosticStage.BITMAP_ALLOCATION,
                status = "WARN",
                message = "OutOfMemoryError at ${sizePx}px; attempting 256px fallback",
                payloadLength = trimmed.length,
                requestedSizePx = sizePx,
                durationMs = elapsed,
                error = oom
            )
            if (sizePx > 256) {
                return generateQrBitmapWithDiagnostics(trimmed, 256, attemptId)
            }
            val msg = "Device memory too low to allocate QR bitmap."
            QrBitmapGenerationResult(
                bitmap = null,
                errorMessage = msg,
                diagnosticReport = QrDiagnosticLogger.formatReport(attemptId),
                attemptId = attemptId
            )
        } catch (t: Throwable) {
            val elapsed = SystemClock.elapsedRealtime() - bitmapStart
            val msg = "QR bitmap allocation failed: ${t.message ?: t.javaClass.simpleName}"
            QrDiagnosticLogger.logStage(
                attemptId = attemptId,
                stage = QrDiagnosticStage.BITMAP_ALLOCATION,
                status = "FAIL",
                message = msg,
                payloadLength = trimmed.length,
                requestedSizePx = sizePx,
                durationMs = elapsed,
                error = t
            )
            QrBitmapGenerationResult(
                bitmap = null,
                errorMessage = msg,
                diagnosticReport = QrDiagnosticLogger.formatReport(attemptId),
                attemptId = attemptId
            )
        }
    }

    fun decodeYuvQr(
        yuvBytes: ByteArray,
        rowStride: Int,
        width: Int,
        height: Int
    ): String? {
        return runCatching {
            val dataWidth = maxOf(rowStride, width)
            val source = PlanarYUVLuminanceSource(yuvBytes, dataWidth, height, 0, 0, width, height, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = MultiFormatReader().decode(bitmap)
            result.text
        }.getOrNull()
    }

    /**
     * Supports raw 8-char codes or "FAMILYTRACK:CODE" payload format.
     */
    fun extractPairingCode(rawQrText: String): String {
        val trimmed = rawQrText.trim()
        return if (trimmed.startsWith("FAMILYTRACK:", ignoreCase = true)) {
            trimmed.substringAfter(":").trim().uppercase()
        } else {
            trimmed.uppercase()
        }
    }
}
