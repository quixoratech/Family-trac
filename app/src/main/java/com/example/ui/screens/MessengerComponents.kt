package com.example.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "🙏", "😮")

fun decodeThumbnailBitmap(base64: String?): ImageBitmap? {
    if (base64.isNullOrBlank()) return null
    return runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = 2
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.getOrNull()
}

fun formatChatDateSeparator(timestampMs: Long): String {
    val msgCal = Calendar.getInstance().apply { timeInMillis = timestampMs }
    val todayCal = Calendar.getInstance()
    val yestCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        msgCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
            msgCal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR) -> "Today"
        msgCal.get(Calendar.YEAR) == yestCal.get(Calendar.YEAR) &&
            msgCal.get(Calendar.DAY_OF_YEAR) == yestCal.get(Calendar.DAY_OF_YEAR) -> "Yesterday"
        else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(timestampMs))
    }
}

@Composable
fun MemberAvatar(name: String, isOnline: Boolean, sizeDp: Int = 36) {
    val initials = name.trim().split(" ").filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifBlank { "F" }
    Box(modifier = Modifier.size(sizeDp.dp)) {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                initials,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
        }
        if (isOnline) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(com.example.ui.theme.StatusLiveGreen)
            )
        }
    }
}

@Composable
fun RichMessageBubble(
    message: ChatMessage,
    isMine: Boolean,
    isPlaying: Boolean,
    playbackSpeed: Float,
    playbackProgress: Float,
    receiptLabel: String,
    isReadByOther: Boolean,
    onPlayToggle: () -> Unit,
    onCycleSpeed: () -> Unit,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
    onEdit: () -> Unit,
    onDeleteForAll: () -> Unit,
    onOpenImage: (ImageBitmap) -> Unit,
    onOpenDocument: () -> Unit
) {
    val timeStr = remember(message.clientCreatedAtMs) {
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(message.clientCreatedAtMs))
    }
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isMine) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (!isMine) {
                            Text(
                                text = message.senderName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        if (!message.deletedForAll) {
                            Box {
                                IconButton(
                                    onClick = { showMenu = true },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = "Message options",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                        QUICK_REACTIONS.forEach { emoji ->
                                            Text(
                                                text = emoji,
                                                modifier = Modifier
                                                    .clickable {
                                                        onReact(emoji)
                                                        showMenu = false
                                                    }
                                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Reply") },
                                        leadingIcon = { Icon(Icons.Default.Reply, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            onReply()
                                        }
                                    )
                                    if (isMine && message.type == "TEXT") {
                                        DropdownMenuItem(
                                            text = { Text("Edit") },
                                            onClick = {
                                                showMenu = false
                                                onEdit()
                                            }
                                        )
                                    }
                                    if (isMine) {
                                        DropdownMenuItem(
                                            text = { Text("Delete for all") },
                                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                            onClick = {
                                                showMenu = false
                                                onDeleteForAll()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Quoted Reply Block
                    if (!message.replyToPreview.isNullOrBlank() && !message.deletedForAll) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        ) {
                            Text(
                                text = "↩ ${message.replyToPreview}",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (message.deletedForAll) {
                        Text(
                            text = "This message was deleted",
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        when (message.type) {
                            "VOICE" -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = onPlayToggle, modifier = Modifier.size(44.dp)) {
                                        Icon(
                                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = "Play voice note"
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Voice note • ${((message.voiceDurationMs ?: 0L) / 1000L).coerceAtLeast(1)}s",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        LinearProgressIndicator(
                                            progress = { if (isPlaying) playbackProgress else 0f },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    TextButton(onClick = onCycleSpeed) {
                                        Text("${playbackSpeed}x", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            "IMAGE" -> {
                                val bmp = remember(message.id, message.mediaBase64) {
                                    decodeThumbnailBitmap(message.mediaBase64)
                                }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = message.fileName ?: "Photo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(170.dp)
                                            .clip(MaterialTheme.shapes.small)
                                            .clickable { onOpenImage(bmp) }
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }
                                if (message.text.isNotBlank() && message.text != message.fileName) {
                                    Text(message.text, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            "DOCUMENT" -> {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(onClick = onOpenDocument)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.AttachFile, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                message.fileName ?: "Document",
                                                style = MaterialTheme.typography.labelLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            val kb = ((message.fileSizeBytes ?: 0L) / 1024L).coerceAtLeast(1L)
                                            Text("${kb} KB • Tap to open", style = MaterialTheme.typography.labelSmall)
                                        }
                                        Icon(Icons.Default.OpenInNew, contentDescription = "Open document", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                            else -> {
                                Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (message.editedAtMs != null && !message.deletedForAll) {
                            Text(
                                text = "Edited •",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = timeStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isMine) {
                            val icon = if (message.status == "DELIVERED" || isReadByOther) Icons.Default.DoneAll else Icons.Default.Done
                            Icon(
                                icon,
                                contentDescription = receiptLabel,
                                modifier = Modifier.size(14.dp),
                                tint = if (isReadByOther) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = receiptLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (message.reactions.isNotEmpty() && !message.deletedForAll) {
                val grouped = message.reactions.values.groupingBy { it }.eachCount()
                Surface(
                    shape = MaterialTheme.shapes.small,
                    tonalElevation = 2.dp,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        grouped.forEach { (emoji, count) ->
                            Text(
                                text = if (count > 1) "$emoji $count" else emoji,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PendingOfflineMessageBubble(
    senderName: String,
    text: String,
    timestampMs: Long,
    lastError: String?,
    queuedLabel: String,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    val timeStr = remember(timestampMs) {
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timestampMs))
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (!lastError.isNullOrBlank()) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
            ),
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                if (!lastError.isNullOrBlank()) {
                    Text(
                        "Failed: ${lastError.take(80)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        if (!lastError.isNullOrBlank()) Icons.Default.ErrorOutline else Icons.Default.Schedule,
                        contentDescription = queuedLabel,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        "$timeStr • ${if (!lastError.isNullOrBlank()) "Failed" else queuedLabel}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    IconButton(onClick = onRetry, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry", modifier = Modifier.size(15.dp))
                    }
                    IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}
