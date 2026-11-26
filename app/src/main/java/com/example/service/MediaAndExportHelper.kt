package com.example.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

data class PreparedMediaPayload(
    val file: File,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val base64: String
)

object MediaAndExportHelper {
    const val MAX_IMAGE_BYTES = 350_000
    const val MAX_DOC_BYTES = 550_000

    fun compressImageToPayload(context: Context, uri: Uri): Result<PreparedMediaPayload> = runCatching {
        val resolver = context.contentResolver
        val displayName = queryFileName(context, uri)?.take(120) ?: "photo_${System.currentTimeMillis()}.jpg"

        // Pass 1: inspect bounds without loading full bitmap into 3GB-constrained RAM
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val maxDim = 1080
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
            sample *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
            ?: throw IllegalArgumentException("Could not read selected image.")

        val baos = ByteArrayOutputStream()
        var quality = 82
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        while (baos.size() > MAX_IMAGE_BYTES && quality > 30) {
            baos.reset()
            quality -= 12
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        }
        bitmap.recycle()

        val bytes = baos.toByteArray()
        if (bytes.size > MAX_IMAGE_BYTES) {
            throw IllegalArgumentException("Compressed image exceeds ${MAX_IMAGE_BYTES / 1000} KB limit.")
        }

        val dir = File(context.cacheDir, "chat_media").apply { mkdirs() }
        val outFile = File(dir, "img_${UUID.randomUUID().toString().take(12)}.jpg")
        outFile.writeBytes(bytes)

        PreparedMediaPayload(
            file = outFile,
            fileName = if (displayName.endsWith(".jpg", true) || displayName.endsWith(".jpeg", true)) displayName else "$displayName.jpg",
            mimeType = "image/jpeg",
            sizeBytes = bytes.size.toLong(),
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        )
    }

    fun readDocumentWithinLimit(context: Context, uri: Uri): Result<PreparedMediaPayload> = runCatching {
        val resolver = context.contentResolver
        val fileName = queryFileName(context, uri)?.take(140) ?: "document_${System.currentTimeMillis()}"
        val mimeType = (resolver.getType(uri) ?: "application/octet-stream").take(120)
        val rawBytes = resolver.openInputStream(uri)?.use { input ->
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                total += read
                if (total > MAX_DOC_BYTES) {
                    throw IllegalArgumentException("File exceeds ${MAX_DOC_BYTES / 1000} KB free cloud storage limit.")
                }
                buf.write(chunk, 0, read)
            }
            buf.toByteArray()
        } ?: throw IllegalArgumentException("Unable to open selected document.")

        if (rawBytes.isEmpty()) {
            throw IllegalArgumentException("Selected file is empty.")
        }

        val dir = File(context.cacheDir, "chat_media").apply { mkdirs() }
        val outFile = File(dir, "doc_${UUID.randomUUID().toString().take(12)}")
        outFile.writeBytes(rawBytes)

        PreparedMediaPayload(
            file = outFile,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = rawBytes.size.toLong(),
            base64 = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
        )
    }

    fun openBase64Document(context: Context, fileName: String, mimeType: String, base64: String): Result<Unit> = runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val dir = File(context.cacheDir, "shared_docs").apply { mkdirs() }
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "document" }
        val file = File(dir, safeName)
        file.writeBytes(bytes)
        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(viewIntent, "Open $safeName").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun shareCsvReport(context: Context, fileName: String, title: String, csvContent: String): Result<Unit> = runCatching {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val file = File(dir, safeName)
        file.writeText(csvContent, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, csvContent.take(2000))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }
}
