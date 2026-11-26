package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.data.model.FamilyCalendarEvent
import com.example.data.model.FamilyMember
import com.example.data.model.FamilyTask
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CalendarTabContent(
    currentUserId: String,
    isAdmin: Boolean,
    events: List<FamilyCalendarEvent>,
    onCreateEvent: (title: String, desc: String, startAtMs: Long, recurrence: String, reminderMins: Int) -> Unit,
    onDeleteEvent: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var daysAhead by remember { mutableIntStateOf(0) }
    var hourOfDay by remember { mutableIntStateOf(18) }
    var recurrence by remember { mutableStateOf("NONE") }
    var reminderMins by remember { mutableIntStateOf(30) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("hub_calendar_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Shared Family Calendar", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.testTag("add_calendar_event_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Event")
                }
            }
        }
        if (events.isEmpty()) {
            item { EmptyHubCard("No family events scheduled yet. Tap Add Event to plan together.") }
        } else {
            items(events, key = { it.id }) { ev ->
                val dateStr = remember(ev.startAtMs) {
                    SimpleDateFormat("EEE, dd MMM • hh:mm a", Locale.getDefault()).format(Date(ev.startAtMs))
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ev.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(dateStr, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            if (ev.description.isNotBlank()) {
                                Text(ev.description, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "By ${ev.createdByName} • Repeat: ${ev.recurrence} • Remind ${ev.reminderMinutesBefore}m",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isAdmin || ev.createdByUid == currentUserId) {
                            IconButton(onClick = { onDeleteEvent(ev.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete event")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("New Family Event") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(100) },
                        label = { Text("Event Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("calendar_title_input")
                    )
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it.take(300) },
                        label = { Text("Notes (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Day Offset", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "Today", 1 to "Tomorrow", 2 to "+2d", 7 to "+1w").forEach { (d, lbl) ->
                            item {
                                FilterChip(selected = daysAhead == d, onClick = { daysAhead = d }, label = { Text(lbl) })
                            }
                        }
                    }
                    Text("Time & Recurrence", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(9 to "9 AM", 14 to "2 PM", 18 to "6 PM", 20 to "8 PM").forEach { (h, lbl) ->
                            item {
                                FilterChip(selected = hourOfDay == h, onClick = { hourOfDay = h }, label = { Text(lbl) })
                            }
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("NONE", "DAILY", "WEEKLY", "MONTHLY").forEach { rec ->
                            item {
                                FilterChip(selected = recurrence == rec, onClick = { recurrence = rec }, label = { Text(rec) })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cal = java.util.Calendar.getInstance().apply {
                            add(java.util.Calendar.DAY_OF_YEAR, daysAhead)
                            set(java.util.Calendar.HOUR_OF_DAY, hourOfDay)
                            set(java.util.Calendar.MINUTE, 0)
                            set(java.util.Calendar.SECOND, 0)
                        }
                        onCreateEvent(title, desc, cal.timeInMillis, recurrence, reminderMins)
                        title = ""
                        desc = ""
                        showAddDialog = false
                    },
                    enabled = title.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun TasksTabContent(
    currentUserId: String,
    isAdmin: Boolean,
    members: List<FamilyMember>,
    tasks: List<FamilyTask>,
    onCreateTask: (title: String, desc: String, assignedTo: FamilyMember?, priority: String, dueAtMs: Long?) -> Unit,
    onToggleTask: (FamilyTask) -> Unit,
    onDeleteTask: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("MEDIUM") }
    var selectedAssignee by remember(members) { mutableStateOf<FamilyMember?>(members.firstOrNull()) }
    var dueDays by remember { mutableIntStateOf(1) }

    val nowMs = System.currentTimeMillis()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("hub_tasks_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Family Tasks & Chores", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.testTag("add_task_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Task")
                }
            }
        }
        if (tasks.isEmpty()) {
            item { EmptyHubCard("No family tasks yet. Assign groceries, bills, or school errands.") }
        } else {
            items(tasks, key = { it.id }) { t ->
                val isOverdue = !t.completed && t.dueAtMs != null && t.dueAtMs < nowMs
                val prioColor = when (t.priority) {
                    "HIGH" -> MaterialTheme.colorScheme.error
                    "LOW" -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.primary
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = t.completed, onCheckedChange = { onToggleTask(t) })
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = t.title,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        textDecoration = if (t.completed) TextDecoration.LineThrough else TextDecoration.None
                                    ),
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Surface(color = prioColor.copy(alpha = 0.14f), shape = MaterialTheme.shapes.small) {
                                    Text(
                                        t.priority,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = prioColor,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            if (t.description.isNotBlank()) {
                                Text(t.description, style = MaterialTheme.typography.bodySmall)
                            }
                            val dueStr = t.dueAtMs?.let {
                                SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(it))
                            } ?: "No due date"
                            Text(
                                text = "Assigned: ${t.assignedToName} • Due: $dueStr" + if (isOverdue) " (OVERDUE)" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isAdmin || t.createdByUid == currentUserId) {
                            IconButton(onClick = { onDeleteTask(t.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete task")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Create Family Task") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(100) },
                        label = { Text("Task Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("task_title_input")
                    )
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it.take(300) },
                        label = { Text("Details (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Assign To", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(members, key = { it.uid }) { m ->
                            FilterChip(
                                selected = selectedAssignee?.uid == m.uid,
                                onClick = { selectedAssignee = m },
                                label = { Text(m.displayName) }
                            )
                        }
                    }
                    Text("Priority & Due", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("LOW", "MEDIUM", "HIGH").forEach { p ->
                            FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p) })
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "Today", 1 to "Tomorrow", 3 to "+3d", 7 to "+1w").forEach { (d, lbl) ->
                            item {
                                FilterChip(selected = dueDays == d, onClick = { dueDays = d }, label = { Text(lbl) })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val dueMs = System.currentTimeMillis() + dueDays * 24L * 60L * 60L * 1000L
                        onCreateTask(title, desc, selectedAssignee, priority, dueMs)
                        title = ""
                        desc = ""
                        showAddDialog = false
                    },
                    enabled = title.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
    }
}
