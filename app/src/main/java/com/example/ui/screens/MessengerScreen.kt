package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.PendingMessageEntity
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.FamilyMember
import com.example.service.VoiceRecorderPlayer
import com.example.ui.i18n.stringsFor
import com.example.ui.viewmodel.formatLastSeenForViewer
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun MessengerScreen(
    language: String,
    currentUserId: String,
    members: List<FamilyMember>,
    conversations: List<Conversation>,
    selectedConversationId: String,
    messages: List<ChatMessage>,
    pendingMessages: List<PendingMessageEntity>,
    isVoiceFocused: Boolean,
    isRecordingVoice: Boolean,
    playingMessageId: String?,
    voiceRecorderPlayer: VoiceRecorderPlayer,
    onSelectConversation: (String) -> Unit,
    onOpenDirectChat: (FamilyMember) -> Unit,
    onSendText: (String) -> Unit,
    onSendVoiceNote: (File, Long) -> Unit,
    onOpenTicTacToe: (FamilyMember?) -> Unit = {},
    isAdminViewer: Boolean = false,
    replyingTo: ChatMessage? = null,
    onSetReplyTarget: (ChatMessage?) -> Unit = {},
    onTypingChanged: (Boolean, String) -> Unit = { _, _ -> },
    onSendImageUri: (Uri) -> Unit = {},
    onSendDocumentUri: (Uri) -> Unit = {},
    onRetryPending: (String) -> Unit = {},
    onDeletePending: (String) -> Unit = {},
    onToggleReaction: (ChatMessage, String) -> Unit = { _, _ -> },
    onEditMessage: (ChatMessage, String) -> Unit = { _, _ -> },
    onDeleteForAll: (ChatMessage) -> Unit = {},
    onToggleConversationFlag: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onOpenDocument: (String, String, String) -> Unit = { _, _, _ -> }
) {
    val strings = stringsFor(language)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draftText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var editDraft by remember { mutableStateOf("") }
    var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    val recSeconds by voiceRecorderPlayer.recordingElapsedSeconds.collectAsStateWithLifecycle()
    val recAmplitudes by voiceRecorderPlayer.recordingAmplitudes.collectAsStateWithLifecycle()
    val playbackSpeed by voiceRecorderPlayer.playbackSpeed.collectAsStateWithLifecycle()
    val playbackProgress by voiceRecorderPlayer.playbackProgress.collectAsStateWithLifecycle()

    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceRecorderPlayer.startRecording()
            onTypingChanged(true, "RECORDING")
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onSendImageUri(uri)
    }

    val docPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onSendDocumentUri(uri)
    }

    val activeConversation = conversations.firstOrNull { it.id == selectedConversationId }
    val otherDirectMember = remember(activeConversation, members, currentUserId, selectedConversationId) {
        if (selectedConversationId == "conv_group") null
        else {
            val otherUid = activeConversation?.participantUids?.firstOrNull { it != currentUserId }
            members.firstOrNull { it.uid == otherUid }
        }
    }

    val sortedMembers = remember(members, conversations, currentUserId) {
        val dmByMember = members.filter { it.uid != currentUserId }.associateWith { mem ->
            val sorted = listOf(currentUserId, mem.uid).sorted()
            val dmId = "dm_${sorted[0].take(16)}_${sorted[1].take(16)}"
            conversations.firstOrNull { it.id == dmId }
        }
        members.filter { it.uid != currentUserId }
            .sortedByDescending { mem -> dmByMember[mem]?.pinnedByUids?.contains(currentUserId) == true }
    }

    val filteredMessages = remember(messages, searchQuery, isVoiceFocused) {
        messages.filter { msg ->
            val typeOk = if (isVoiceFocused) msg.type == "VOICE" else true
            val queryOk = searchQuery.isBlank() ||
                msg.text.contains(searchQuery, ignoreCase = true) ||
                msg.senderName.contains(searchQuery, ignoreCase = true) ||
                msg.fileName.orEmpty().contains(searchQuery, ignoreCase = true)
            typeOk && queryOk
        }
    }

    val listState = rememberLazyListState()
    val showScrollDown by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 4 && lastVisible < total - 2
        }
    }

    LaunchedEffect(filteredMessages.size, selectedConversationId) {
        if (filteredMessages.isNotEmpty()) {
            listState.animateScrollToItem((filteredMessages.size - 1).coerceAtLeast(0))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Conversation Switcher with Pin & Mute Badges
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                val groupConv = conversations.firstOrNull { it.id == "conv_group" }
                val pinned = groupConv?.pinnedByUids?.contains(currentUserId) == true
                FilterChip(
                    selected = selectedConversationId == "conv_group",
                    onClick = { onSelectConversation("conv_group") },
                    label = { Text(if (pinned) "📌 ${strings.familyGroupChat}" else strings.familyGroupChat) }
                )
            }
            items(sortedMembers, key = { it.uid }) { member ->
                val sorted = listOf(currentUserId, member.uid).sorted()
                val dmId = "dm_${sorted[0].take(16)}_${sorted[1].take(16)}"
                val conv = conversations.firstOrNull { it.id == dmId }
                val isPinned = conv?.pinnedByUids?.contains(currentUserId) == true
                val isMuted = conv?.mutedByUids?.contains(currentUserId) == true
                FilterChip(
                    selected = selectedConversationId == dmId,
                    onClick = { onOpenDirectChat(member) },
                    leadingIcon = { MemberAvatar(member.displayName, member.online, sizeDp = 22) },
                    label = {
                        Text(
                            buildString {
                                if (isPinned) append("📌 ")
                                append(member.displayName)
                                if (isMuted) append(" 🔇")
                            },
                            maxLines = 1
                        )
                    }
                )
            }
        }

        // Chat Header (Online / Last Seen / Typing + Pin / Mute actions)
        Surface(
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = otherDirectMember?.displayName ?: strings.familyGroupChat,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val presenceText = if (otherDirectMember != null) {
                        formatLastSeenForViewer(
                            member = otherDirectMember,
                            viewerIsAdmin = isAdminViewer,
                            conversationId = selectedConversationId
                        )
                    } else {
                        val typingNames = members.filter {
                            it.uid != currentUserId &&
                                it.typingConversationId == selectedConversationId &&
                                it.typingActivity != "NONE" &&
                                (System.currentTimeMillis() - it.typingUpdatedAtMs) in 0..8_000L
                        }.map { it.displayName }
                        if (typingNames.isNotEmpty()) "${typingNames.joinToString(", ")} typing..."
                        else "${members.size} members"
                    }
                    Text(
                        text = presenceText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (presenceText.contains("Typing") || presenceText.contains("Online"))
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val isPinned = activeConversation?.pinnedByUids?.contains(currentUserId) == true
                val isMuted = activeConversation?.mutedByUids?.contains(currentUserId) == true
                IconButton(
                    onClick = { onToggleConversationFlag(selectedConversationId, "pinnedByUids", !isPinned) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = if (isPinned) "Unpin chat" else "Pin chat",
                        tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = { onToggleConversationFlag(selectedConversationId, "mutedByUids", !isMuted) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.NotificationsOff,
                        contentDescription = if (isMuted) "Unmute chat" else "Mute chat",
                        tint = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Message Search
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(strings.searchMessagesHint) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = strings.searchMessagesHint) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag("message_search_input")
        )

        if (pendingMessages.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "${strings.offlineQueueBanner} (${pendingMessages.size})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // Message History with Date Separators & Scroll-to-latest
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (filteredMessages.isEmpty() && pendingMessages.isEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    if (isVoiceFocused) strings.recordVoiceHint else "Start a conversation with your family.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                items(filteredMessages.size, key = { idx -> filteredMessages[idx].id }) { idx ->
                    val msg = filteredMessages[idx]
                    val prev = filteredMessages.getOrNull(idx - 1)
                    val dateHeader = formatChatDateSeparator(msg.clientCreatedAtMs)
                    val prevDateHeader = prev?.let { formatChatDateSeparator(it.clientCreatedAtMs) }
                    if (dateHeader != prevDateHeader) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = dateHeader,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    val isMine = msg.senderUid == currentUserId
                    val isReadByOther = msg.readByUids.any { it != currentUserId }
                    RichMessageBubble(
                        message = msg,
                        isMine = isMine,
                        isPlaying = playingMessageId == msg.id,
                        playbackSpeed = playbackSpeed,
                        playbackProgress = playbackProgress,
                        receiptLabel = when {
                            isReadByOther -> strings.readLabel
                            msg.status == "DELIVERED" || msg.deliveredToUids.any { it != currentUserId } -> strings.deliveredLabel
                            else -> strings.sentLabel
                        },
                        isReadByOther = isReadByOther,
                        onPlayToggle = {
                            msg.voiceAudioBase64?.let { b64 ->
                                voiceRecorderPlayer.togglePlayback(msg.id, b64)
                            }
                        },
                        onCycleSpeed = { voiceRecorderPlayer.cyclePlaybackSpeed() },
                        onReply = { onSetReplyTarget(msg) },
                        onReact = { emoji -> onToggleReaction(msg, emoji) },
                        onEdit = {
                            editTarget = msg
                            editDraft = msg.text
                        },
                        onDeleteForAll = { onDeleteForAll(msg) },
                        onOpenImage = { bmp -> previewBitmap = bmp },
                        onOpenDocument = {
                            val b64 = msg.mediaBase64 ?: return@RichMessageBubble
                            onOpenDocument(msg.fileName ?: "document", msg.mimeType ?: "*/*", b64)
                        }
                    )
                }

                val syncedIds = filteredMessages.map { it.id }.toSet()
                items(pendingMessages.filter { it.id !in syncedIds }, key = { "pending_${it.id}" }) { p ->
                    PendingOfflineMessageBubble(
                        senderName = p.senderName,
                        text = p.text,
                        timestampMs = p.clientCreatedAtMs,
                        lastError = p.lastError,
                        queuedLabel = strings.queuedOfflineLabel,
                        onRetry = { onRetryPending(p.id) },
                        onRemove = { onDeletePending(p.id) }
                    )
                }
            }

            if (showScrollDown) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            val lastIdx = (filteredMessages.size - 1).coerceAtLeast(0)
                            listState.animateScrollToItem(lastIdx)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp)
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Scroll to latest")
                }
            }
        }

        // Reply Preview Bar
        replyingTo?.let { target ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Replying to ${target.senderName}: ${target.text.take(55)}",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onSetReplyTarget(null) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel reply", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Composer Row (Attachments + Live Voice Waveform + Text)
        Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Inline game action, kept beside the voice-message control like a chat attachment.
                IconButton(
                    onClick = { onOpenTicTacToe(otherDirectMember) },
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("chat_tic_tac_toe_button")
                ) {
                    Icon(
                        Icons.Default.GridOn,
                        contentDescription = "Play Tic-Tac-Toe",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    onClick = {
                        if (isRecordingVoice) {
                            val result = voiceRecorderPlayer.stopRecording()
                            onTypingChanged(false, "NONE")
                            if (result != null) onSendVoiceNote(result.first, result.second)
                        } else {
                            val hasAudio = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasAudio) {
                                voiceRecorderPlayer.startRecording()
                                onTypingChanged(true, "RECORDING")
                            } else {
                                audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isRecordingVoice) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primaryContainer
                        )
                        .testTag("record_voice_button")
                ) {
                    Icon(
                        if (isRecordingVoice) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isRecordingVoice) strings.stopRecordingLabel else strings.recordVoiceHint,
                        tint = if (isRecordingVoice) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                if (isRecordingVoice) {
                    Spacer(Modifier.width(8.dp))
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            "REC ${recSeconds}s",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(6.dp))
                        recAmplitudes.takeLast(14).forEach { amp ->
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height((8 + (amp * 20)).dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.error)
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            voiceRecorderPlayer.cancelRecording()
                            onTypingChanged(false, "NONE")
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel recording")
                    }
                } else {
                    IconButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("attach_image_button")
                    ) {
                        Icon(Icons.Default.Image, contentDescription = "Share photo")
                    }
                    IconButton(
                        onClick = { docPickerLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("attach_doc_button")
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Attach document")
                    }
                    OutlinedTextField(
                        value = draftText,
                        onValueChange = {
                            draftText = it
                            onTypingChanged(it.isNotBlank(), "TYPING")
                        },
                        placeholder = { Text(strings.typeMessageHint) },
                        maxLines = 3,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_message_input")
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = {
                            if (draftText.isNotBlank()) {
                                onSendText(draftText)
                                draftText = ""
                            }
                        },
                        enabled = draftText.isNotBlank(),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("send_message_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    editTarget?.let { msg ->
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("Edit Message") },
            text = {
                OutlinedTextField(
                    value = editDraft,
                    onValueChange = { editDraft = it },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onEditMessage(msg, editDraft)
                        editTarget = null
                    },
                    enabled = editDraft.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) { Text("Cancel") }
            }
        )
    }

    previewBitmap?.let { bmp ->
        Dialog(onDismissRequest = { previewBitmap = null }) {
            Surface(shape = MaterialTheme.shapes.large) {
                Box(modifier = Modifier.padding(8.dp)) {
                    Image(
                        bitmap = bmp,
                        contentDescription = "Full photo preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                            .clickable { previewBitmap = null }
                    )
                }
            }
        }
    }
}
