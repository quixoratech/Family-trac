package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.model.CallSession
import com.example.data.model.FamilyMember
import com.example.service.WebRtcCallState
import com.example.ui.i18n.stringsFor
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.StatusLiveGreen

@Composable
fun CallsScreen(
    language: String,
    currentUserId: String,
    members: List<FamilyMember>,
    calls: List<CallSession>,
    activeCallId: String?,
    callState: WebRtcCallState,
    callStatusMessage: String,
    callDurationSeconds: Long,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    onStartCall: (FamilyMember) -> Unit,
    onAcceptCall: (CallSession) -> Unit,
    onRejectCall: (CallSession) -> Unit,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    val strings = stringsFor(language)
    val context = LocalContext.current
    var pendingCallTarget by remember { mutableStateOf<FamilyMember?>(null) }
    var pendingAcceptCall by remember { mutableStateOf<CallSession?>(null) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingCallTarget?.let(onStartCall)
            pendingAcceptCall?.let(onAcceptCall)
        }
        pendingCallTarget = null
        pendingAcceptCall = null
    }

    val incomingRinging = calls.firstOrNull { it.calleeUid == currentUserId && it.status == "RINGING" }
    val activeCall = calls.firstOrNull { it.id == activeCallId }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (incomingRinging != null && activeCallId == null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PhoneInTalk, contentDescription = strings.incomingCallTitle, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(strings.incomingCallTitle, style = MaterialTheme.typography.titleMedium)
                                Text(incomingRinging.callerName, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    val hasMic = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasMic) onAcceptCall(incomingRinging)
                                    else {
                                        pendingAcceptCall = incomingRinging
                                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = StatusLiveGreen),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("accept_call_button")
                            ) { Text(strings.acceptCallBtn) }
                            Button(
                                onClick = { onRejectCall(incomingRinging) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("reject_call_button")
                            ) { Text(strings.rejectCallBtn) }
                        }
                    }
                }
            }
        }

        if (activeCall != null || callState != WebRtcCallState.IDLE) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val peerName = if (activeCall?.callerUid == currentUserId) activeCall.calleeName else activeCall?.callerName ?: "Family"
                        Text(peerName, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (callState == WebRtcCallState.CONNECTED) {
                                String.format("%02d:%02d", callDurationSeconds / 60, callDurationSeconds % 60)
                            } else {
                                callStatusMessage.ifBlank { callState.name }
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = MonoFontFamily,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            FilledTonalButton(
                                onClick = onToggleMute,
                                modifier = Modifier.testTag("call_mute_button")
                            ) {
                                Icon(if (isMuted) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = strings.muteLabel)
                                Spacer(Modifier.width(6.dp))
                                Text(strings.muteLabel)
                            }
                            FilledTonalButton(
                                onClick = onToggleSpeaker,
                                modifier = Modifier.testTag("call_speaker_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = strings.speakerLabel)
                                Spacer(Modifier.width(6.dp))
                                Text(strings.speakerLabel)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = onEndCall,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("end_call_button")
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = strings.endCallBtn)
                            Spacer(Modifier.width(8.dp))
                            Text(strings.endCallBtn)
                        }
                    }
                }
            }
        }

        item {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    strings.callRequiresInternet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        items(members.filter { it.uid != currentUserId }, key = { it.uid }) { member ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            member.displayName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(member.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(member.role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = {
                            val hasMic = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasMic) onStartCall(member)
                            else {
                                pendingCallTarget = member
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = activeCallId == null,
                        modifier = Modifier.testTag("call_member_${member.uid}")
                    ) {
                        Icon(Icons.Default.Call, contentDescription = strings.startVoiceCallBtn, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.startVoiceCallBtn)
                    }
                }
            }
        }

        if (calls.isNotEmpty()) {
            item {
                Text("Recent Calls", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
            }
            items(calls.take(10), key = { it.id }) { c ->
                val other = if (c.callerUid == currentUserId) c.calleeName else c.callerName
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(other, style = MaterialTheme.typography.bodyMedium)
                        Text(c.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
