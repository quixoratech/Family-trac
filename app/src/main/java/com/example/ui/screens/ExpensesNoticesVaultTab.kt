package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.FamilyCalendarEvent
import com.example.data.model.FamilyExpense
import com.example.data.model.FamilyMember
import com.example.data.model.FamilyNotice
import com.example.data.model.FamilyTask
import com.example.data.model.FamilyVaultFile
import com.example.ui.i18n.stringsFor
import com.example.ui.viewmodel.computeExpenseCategoryTotals
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val EXPENSE_CATEGORIES = listOf("Groceries", "Bills", "Transport", "Education", "Medical", "Other")
private val VAULT_FOLDERS = listOf("IDs", "Medical", "School", "Home", "General")

@Composable
fun FamilyHubScreen(
    language: String,
    currentUserId: String,
    isAdmin: Boolean,
    members: List<FamilyMember>,
    calendarEvents: List<FamilyCalendarEvent>,
    tasks: List<FamilyTask>,
    expenses: List<FamilyExpense>,
    notices: List<FamilyNotice>,
    vaultFiles: List<FamilyVaultFile>,
    initialTab: Int = 0,
    onCreateEvent: (String, String, Long, String, Int) -> Unit,
    onDeleteEvent: (String) -> Unit,
    onCreateTask: (String, String, FamilyMember?, String, Long?) -> Unit,
    onToggleTask: (FamilyTask) -> Unit,
    onDeleteTask: (String) -> Unit,
    onCreateExpense: (String, String, Double, FamilyMember?, String) -> Unit,
    onDeleteExpense: (String) -> Unit,
    onExportExpenses: () -> Unit,
    onCreateNotice: (String, String, Boolean) -> Unit,
    onToggleNoticePin: (FamilyNotice) -> Unit,
    onMarkNoticeRead: (String) -> Unit,
    onDeleteNotice: (String) -> Unit,
    onUploadVaultFile: (Uri, String, String, List<String>) -> Unit,
    onOpenVaultFile: (FamilyVaultFile) -> Unit,
    onDeleteVaultFile: (String) -> Unit
) {
    val strings = stringsFor(language)
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab) }
    val tabs = listOf(strings.hubCalendar, strings.hubTasks, strings.hubExpenses, strings.hubNotices, strings.hubVault)

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tabs.size) { idx ->
                FilterChip(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    label = { Text(tabs[idx]) },
                    modifier = Modifier.testTag("hub_tab_$idx")
                )
            }
        }
        when (selectedTab) {
            0 -> CalendarTabContent(currentUserId, isAdmin, calendarEvents, onCreateEvent, onDeleteEvent)
            1 -> TasksTabContent(currentUserId, isAdmin, members, tasks, onCreateTask, onToggleTask, onDeleteTask)
            2 -> ExpensesTabContent(currentUserId, isAdmin, members, expenses, onCreateExpense, onDeleteExpense, onExportExpenses)
            3 -> NoticesTabContent(currentUserId, isAdmin, notices, onCreateNotice, onToggleNoticePin, onMarkNoticeRead, onDeleteNotice)
            4 -> VaultTabContent(currentUserId, isAdmin, members, vaultFiles, onUploadVaultFile, onOpenVaultFile, onDeleteVaultFile)
        }
    }
}

@Composable
private fun ExpensesTabContent(
    currentUserId: String,
    isAdmin: Boolean,
    members: List<FamilyMember>,
    expenses: List<FamilyExpense>,
    onCreateExpense: (String, String, Double, FamilyMember?, String) -> Unit,
    onDeleteExpense: (String) -> Unit,
    onExportExpenses: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(EXPENSE_CATEGORIES.first()) }
    var notes by remember { mutableStateOf("") }
    var paidBy by remember(members) { mutableStateOf(members.firstOrNull { it.uid == currentUserId }) }

    val monthlyByCat = remember(expenses) { computeExpenseCategoryTotals(expenses, currentMonthOnly = true) }
    val monthlyTotal = monthlyByCat.values.sum()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("hub_expenses_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("This Month's Expenses", style = MaterialTheme.typography.labelMedium)
                            Text("৳${"%.0f".format(monthlyTotal)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = onExportExpenses) {
                                Icon(Icons.Default.Download, contentDescription = "Export CSV", modifier = Modifier.size(16.dp))
                            }
                            Button(
                                onClick = { showAddDialog = true },
                                modifier = Modifier.testTag("add_expense_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Add ৳")
                            }
                        }
                    }
                    if (monthlyTotal > 0.0) {
                        monthlyByCat.entries.sortedByDescending { it.value }.forEach { (cat, amt) ->
                            val frac = (amt / monthlyTotal).toFloat().coerceIn(0f, 1f)
                            Column {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(cat, style = MaterialTheme.typography.labelSmall)
                                    Text("৳${amt.toInt()}", style = MaterialTheme.typography.labelSmall)
                                }
                                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
        if (expenses.isEmpty()) {
            item { EmptyHubCard("No expenses recorded yet. Track groceries, bills, & travel in BDT (৳).") }
        } else {
            items(expenses, key = { it.id }) { exp ->
                val dateStr = remember(exp.expenseDateMs) {
                    SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(exp.expenseDateMs))
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(exp.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${exp.category} • Paid by ${exp.paidByName} • $dateStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (exp.notes.isNotBlank()) Text(exp.notes, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "৳${"%.0f".format(exp.amountBdt)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (isAdmin || exp.createdByUid == currentUserId) {
                            IconButton(onClick = { onDeleteExpense(exp.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete expense")
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
            title = { Text("Add Family Expense (BDT)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(100) },
                        label = { Text("Item / Description") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("expense_title_input")
                    )
                    OutlinedTextField(
                        value = amountStr,
                        onValueChange = { amountStr = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text("Amount (৳ BDT)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("expense_amount_input")
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(EXPENSE_CATEGORIES) { c ->
                            FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(members, key = { it.uid }) { m ->
                            FilterChip(
                                selected = paidBy?.uid == m.uid,
                                onClick = { paidBy = m },
                                label = { Text("Paid: ${m.displayName}") }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it.take(200) },
                        label = { Text("Notes (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                val amt = amountStr.toDoubleOrNull() ?: 0.0
                Button(
                    onClick = {
                        onCreateExpense(title, category, amt, paidBy, notes)
                        title = ""
                        amountStr = ""
                        notes = ""
                        showAddDialog = false
                    },
                    enabled = title.isNotBlank() && amt > 0.0
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun NoticesTabContent(
    currentUserId: String,
    isAdmin: Boolean,
    notices: List<FamilyNotice>,
    onCreateNotice: (String, String, Boolean) -> Unit,
    onTogglePin: (FamilyNotice) -> Unit,
    onMarkRead: (String) -> Unit,
    onDeleteNotice: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var pinned by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("hub_notices_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Family Notice Board", style = MaterialTheme.typography.titleMedium)
                if (isAdmin) {
                    Button(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.testTag("add_notice_button")
                    ) {
                        Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Publish")
                    }
                }
            }
        }
        if (notices.isEmpty()) {
            item { EmptyHubCard("No family notices posted yet.") }
        } else {
            items(notices, key = { it.id }) { n ->
                val isRead = n.readByUids.contains(currentUserId)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (n.pinned) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (n.pinned) {
                                Icon(Icons.Default.PushPin, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(n.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            if (!isRead) {
                                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                                    Text("NEW", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                        }
                        Text(n.body, style = MaterialTheme.typography.bodyMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "By ${n.authorName} • Read by ${n.readByUids.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row {
                                if (!isRead) {
                                    TextButton(onClick = { onMarkRead(n.id) }) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Acknowledge")
                                    }
                                }
                                if (isAdmin) {
                                    IconButton(onClick = { onTogglePin(n) }) {
                                        Icon(Icons.Default.PushPin, contentDescription = "Toggle pin")
                                    }
                                    IconButton(onClick = { onDeleteNotice(n.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete notice")
                                    }
                                }
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
            title = { Text("Publish Family Notice") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(100) },
                        label = { Text("Notice Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it.take(1000) },
                        label = { Text("Message") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = pinned, onCheckedChange = { pinned = it })
                        Text("Pin to top of board")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onCreateNotice(title, body, pinned)
                        title = ""
                        body = ""
                        showAddDialog = false
                    },
                    enabled = title.isNotBlank() && body.isNotBlank()
                ) { Text("Publish") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun VaultTabContent(
    currentUserId: String,
    isAdmin: Boolean,
    members: List<FamilyMember>,
    files: List<FamilyVaultFile>,
    onUploadVaultFile: (Uri, String, String, List<String>) -> Unit,
    onOpenVaultFile: (FamilyVaultFile) -> Unit,
    onDeleteVaultFile: (String) -> Unit
) {
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var docTitle by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(VAULT_FOLDERS.first()) }
    var selectedUids by remember(members) { mutableStateOf(members.map { it.uid }.toSet()) }
    var folderFilter by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingUri = uri
    }

    val visibleFiles = remember(files, folderFilter) {
        if (folderFilter == null) files else files.filter { it.folder == folderFilter }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("hub_vault_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Secure Family Vault", style = MaterialTheme.typography.titleMedium)
                    Text("PDFs/Images <= 550 KB • Per-member access", style = MaterialTheme.typography.labelSmall)
                }
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.testTag("upload_vault_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Upload")
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(selected = folderFilter == null, onClick = { folderFilter = null }, label = { Text("All") })
                }
                items(VAULT_FOLDERS) { f ->
                    FilterChip(selected = folderFilter == f, onClick = { folderFilter = f }, label = { Text(f) })
                }
            }
        }
        if (visibleFiles.isEmpty()) {
            item { EmptyHubCard("No shared vault documents here yet.") }
        } else {
            items(visibleFiles, key = { it.id }) { f ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(f.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${f.folder} • ${f.fileName} • ${(f.fileSizeBytes / 1024L).coerceAtLeast(1L)} KB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onOpenVaultFile(f) }) {
                            Icon(Icons.Default.OpenInNew, contentDescription = "Open file")
                        }
                        if (isAdmin || f.uploadedByUid == currentUserId) {
                            IconButton(onClick = { onDeleteVaultFile(f.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete file")
                            }
                        }
                    }
                }
            }
        }
    }

    pendingUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingUri = null },
            title = { Text("Save to Family Vault") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = docTitle,
                        onValueChange = { docTitle = it.take(100) },
                        label = { Text("Document Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(VAULT_FOLDERS) { f ->
                            FilterChip(selected = folder == f, onClick = { folder = f }, label = { Text(f) })
                        }
                    }
                    Text("Visible To:", style = MaterialTheme.typography.labelMedium)
                    members.forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = m.uid in selectedUids,
                                onCheckedChange = { checked ->
                                    selectedUids = if (checked) selectedUids + m.uid else selectedUids - m.uid
                                }
                            )
                            Text(m.displayName, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUploadVaultFile(uri, docTitle, folder, selectedUids.toList())
                        docTitle = ""
                        pendingUri = null
                    }
                ) { Text("Upload") }
            },
            dismissButton = { TextButton(onClick = { pendingUri = null }) { Text("Cancel") } }
        )
    }
}
