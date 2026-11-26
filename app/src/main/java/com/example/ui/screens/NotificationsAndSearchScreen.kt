package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.AppNotificationEntity
import com.example.data.model.ChatMessage
import com.example.data.model.FamilyCalendarEvent
import com.example.data.model.FamilyMember
import com.example.data.model.FamilyNotice
import com.example.data.model.FamilyNotificationModel
import com.example.data.model.FamilyTask
import com.example.data.model.FamilyVaultFile
import com.example.data.model.PairedDevice
import com.example.ui.viewmodel.GlobalSearchKind
import com.example.ui.viewmodel.GlobalSearchResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class MergedAlertItem(
    val key: String,
    val cloudId: String?,
    val localId: String?,
    val title: String,
    val body: String,
    val category: String,
    val seen: Boolean,
    val timestampMs: Long
)

@Composable
fun NotificationsCenterScreen(
    cloudNotifications: List<FamilyNotificationModel>,
    localNotifications: List<AppNotificationEntity>,
    onMarkSeen: (cloudId: String?, localId: String?) -> Unit,
    onMarkAllSeen: () -> Unit
) {
    val merged = remember(cloudNotifications, localNotifications) {
        val cItems = cloudNotifications.map { n ->
            MergedAlertItem(
                key = "cloud_${n.id}",
                cloudId = n.id,
                localId = null,
                title = n.title,
                body = n.body,
                category = n.type,
                seen = n.seen,
                timestampMs = n.createdAt?.toDate()?.time ?: System.currentTimeMillis()
            )
        }
        val lItems = localNotifications.map { n ->
            MergedAlertItem(
                key = "local_${n.id}",
                cloudId = null,
                localId = n.id,
                title = n.title,
                body = n.body,
                category = n.category,
                seen = n.seen,
                timestampMs = n.createdAtMs
            )
        }
        (cItems + lItems).sortedByDescending { it.timestampMs }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("notifications_center_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Notification Center", style = MaterialTheme.typography.titleLarge)
                if (merged.any { !it.seen }) {
                    OutlinedButton(
                        onClick = onMarkAllSeen,
                        modifier = Modifier.testTag("mark_all_seen_button")
                    ) {
                        Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Mark all seen")
                    }
                }
            }
        }
        if (merged.isEmpty()) {
            item { EmptyHubCard("All quiet! Messages, notices, calls, tasks & sync alerts appear here.") }
        } else {
            items(merged, key = { it.key }) { item ->
                val dateStr = remember(item.timestampMs) {
                    SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(item.timestampMs))
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (!item.seen) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = null,
                            tint = if (!item.seen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                                    Text(item.category, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                                }
                            }
                            Text(item.body, style = MaterialTheme.typography.bodySmall)
                            Text(dateStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!item.seen) {
                            TextButton(onClick = { onMarkSeen(item.cloudId, item.localId) }) {
                                Text("Seen")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GlobalSearchScreen(
    isAdmin: Boolean,
    messages: List<ChatMessage>,
    members: List<FamilyMember>,
    devices: List<PairedDevice>,
    tasks: List<FamilyTask>,
    calendarEvents: List<FamilyCalendarEvent>,
    notices: List<FamilyNotice>,
    vaultFiles: List<FamilyVaultFile>,
    onNavigateResult: (GlobalSearchResult) -> Unit
) {
    var query by remember { mutableStateOf("") }

    val results = remember(query, isAdmin, messages, members, devices, tasks, calendarEvents, notices, vaultFiles) {
        val q = query.trim()
        if (q.isBlank()) emptyList()
        else buildList {
            members.filter { it.displayName.contains(q, true) }.forEach { m ->
                add(GlobalSearchResult("mem_${m.uid}", GlobalSearchKind.MEMBER, m.displayName, m.role, m.lastSeenAtMs, "chats"))
            }
            if (isAdmin) {
                devices.filter { it.deviceName.contains(q, true) || it.ownerName.contains(q, true) }.forEach { d ->
                    add(GlobalSearchResult("dev_${d.id}", GlobalSearchKind.DEVICE, d.deviceName, "${d.ownerName} • ${d.networkType}", d.lastSeenAtMs, "devices"))
                }
            }
            messages.filter { !it.deletedForAll && (it.text.contains(q, true) || it.fileName.orEmpty().contains(q, true)) }.take(25).forEach { m ->
                add(GlobalSearchResult("msg_${m.id}", GlobalSearchKind.MESSAGE, m.senderName, m.text, m.clientCreatedAtMs, "chats"))
            }
            tasks.filter { it.title.contains(q, true) || it.description.contains(q, true) }.forEach { t ->
                add(GlobalSearchResult("tsk_${t.id}", GlobalSearchKind.TASK, t.title, "Assigned: ${t.assignedToName}", t.dueAtMs ?: 0L, "family_hub"))
            }
            calendarEvents.filter { it.title.contains(q, true) || it.description.contains(q, true) }.forEach { e ->
                add(GlobalSearchResult("cal_${e.id}", GlobalSearchKind.CALENDAR, e.title, e.description, e.startAtMs, "family_hub"))
            }
            notices.filter { it.title.contains(q, true) || it.body.contains(q, true) }.forEach { n ->
                add(GlobalSearchResult("not_${n.id}", GlobalSearchKind.NOTICE, n.title, n.body, 0L, "family_hub"))
            }
            vaultFiles.filter { it.title.contains(q, true) || it.fileName.contains(q, true) }.forEach { f ->
                add(GlobalSearchResult("vf_${f.id}", GlobalSearchKind.VAULT, f.title, "${f.folder} • ${f.fileName}", 0L, "family_hub"))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("global_search_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search messages, members, tasks, calendar, notices, vault...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("global_search_input")
        )

        if (query.isBlank()) {
            EmptyHubCard(
                if (isAdmin) "Search across authorized chats, members, devices, tasks, events, notices, and vault."
                else "Search across your chats, family members, tasks, events, notices, and vault."
            )
        } else if (results.isEmpty()) {
            EmptyHubCard("No matching items found for \"$query\".")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { it.id }) { res ->
                    Card(
                        onClick = { onNavigateResult(res) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "${res.kind.name} • ${res.title}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (res.subtitle.isNotBlank()) {
                                Text(res.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
    }
}
