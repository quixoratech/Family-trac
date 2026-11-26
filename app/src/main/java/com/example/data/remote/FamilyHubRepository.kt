package com.example.data.remote

import android.content.Context
import com.example.R
import com.example.data.local.NetworkEventEntity
import com.example.data.local.NetworkSessionEntity
import com.example.data.model.FamilyCalendarEvent
import com.example.data.model.FamilyExpense
import com.example.data.model.FamilyNotice
import com.example.data.model.FamilyNotificationModel
import com.example.data.model.FamilyTask
import com.example.data.model.FamilyVaultFile
import com.example.data.model.NetworkEventModel
import com.example.data.model.NetworkSessionModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class FamilyHubRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUid(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User must be signed in.")

    // --- NETWORK ACTIVITY EVENTS & SESSIONS ---
    suspend fun uploadNetworkEventIdempotent(event: NetworkEventEntity): Result<Unit> {
        val ref = db.collection("families").document(event.familyId)
            .collection("network_events").document(event.id)
        return try {
            val uid = requireUid()
            val payload = mapOf(
                "familyId" to event.familyId,
                "adminUid" to event.adminUid,
                "ownerUid" to uid,
                "deviceId" to event.deviceId,
                "deviceName" to event.deviceName.take(80),
                "eventType" to event.eventType,
                "networkType" to event.networkType,
                "connectionState" to event.connectionState.take(40),
                "internetAvailable" to event.internetAvailable,
                "eventTimestampMs" to event.eventTimestampMs,
                "detectedAtMs" to event.detectedAtMs,
                "timezoneId" to event.timezoneId.take(64),
                "timezoneOffsetMinutes" to event.timezoneOffsetMinutes.toLong(),
                "sessionId" to event.sessionId?.take(128),
                "details" to event.details?.take(300),
                "uploadedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>
            try {
                ref.set(payload).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val exists = runCatching { ref.get().await().exists() }.getOrDefault(false)
                if (!exists) throw e
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path)
            Result.failure(e)
        }
    }

    suspend fun upsertNetworkSession(session: NetworkSessionEntity): Result<Unit> {
        val ref = db.collection("families").document(session.familyId)
            .collection("network_sessions").document(session.id)
        return try {
            val uid = requireUid()
            val snap = ref.get().await()
            if (!snap.exists()) {
                val payload = mapOf(
                    "familyId" to session.familyId,
                    "adminUid" to session.adminUid,
                    "ownerUid" to uid,
                    "deviceId" to session.deviceId,
                    "deviceName" to session.deviceName.take(80),
                    "networkType" to session.networkType,
                    "startedAtMs" to session.startedAtMs,
                    "endedAtMs" to session.endedAtMs,
                    "durationMs" to session.durationMs,
                    "status" to session.status,
                    "lastVerifiedAtMs" to session.lastVerifiedAtMs,
                    "interruptionReason" to session.interruptionReason?.take(200),
                    "timezoneId" to session.timezoneId.take(64),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null } as Map<String, Any>
                ref.set(payload).await()
            } else {
                val updates = mutableMapOf<String, Any>(
                    "status" to session.status,
                    "lastVerifiedAtMs" to session.lastVerifiedAtMs,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                session.endedAtMs?.let { updates["endedAtMs"] = it }
                session.durationMs?.let { updates["durationMs"] = it }
                session.interruptionReason?.take(200)?.let { updates["interruptionReason"] = it }
                ref.update(updates).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, ref.path)
            Result.failure(e)
        }
    }

    fun observeAdminNetworkEvents(familyId: String, adminUid: String): Flow<List<NetworkEventModel>> = flow {
        val path = "families/$familyId/network_events"
        emitAll(
            db.collection("families").document(familyId).collection("network_events")
                .whereEqualTo("adminUid", adminUid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(NetworkEventModel::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.detectedAtMs }
                }
                .catch { err ->
                    val root = unwrapFirestoreError(err)
                    if (auth.currentUser?.uid == adminUid) handleFirestoreError(root, OperationType.LIST, path)
                    throw root
                }
        )
    }

    fun observeAdminNetworkSessions(familyId: String, adminUid: String): Flow<List<NetworkSessionModel>> = flow {
        val path = "families/$familyId/network_sessions"
        emitAll(
            db.collection("families").document(familyId).collection("network_sessions")
                .whereEqualTo("adminUid", adminUid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(NetworkSessionModel::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.startedAtMs }
                }
                .catch { err ->
                    val root = unwrapFirestoreError(err)
                    if (auth.currentUser?.uid == adminUid) handleFirestoreError(root, OperationType.LIST, path)
                    throw root
                }
        )
    }

    // --- CALENDAR ---
    fun observeCalendarEvents(familyId: String): Flow<List<FamilyCalendarEvent>> = flow {
        emitAll(
            db.collection("families").document(familyId).collection("calendar_events")
                .snapshots()
                .map { snap ->
                    snap.toObjects(FamilyCalendarEvent::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.startAtMs }
                }
                .catch { err -> throw unwrapFirestoreError(err) }
        )
    }

    suspend fun createCalendarEvent(
        familyId: String,
        title: String,
        description: String,
        startAtMs: Long,
        endAtMs: Long?,
        recurrence: String,
        reminderMinutesBefore: Int,
        participantUids: List<String>,
        creatorName: String
    ): Result<Unit> {
        val uid = try { requireUid() } catch (e: Exception) { return Result.failure(e) }
        val id = "cal_" + UUID.randomUUID().toString().replace("-", "").take(14)
        val ref = db.collection("families").document(familyId).collection("calendar_events").document(id)
        return try {
            val participants = (participantUids + uid).distinct().take(20)
            val payload = mapOf(
                "familyId" to familyId,
                "title" to title.trim().take(120),
                "description" to description.trim().take(500),
                "startAtMs" to startAtMs,
                "endAtMs" to endAtMs,
                "recurrence" to recurrence,
                "reminderMinutesBefore" to reminderMinutesBefore.coerceIn(0, 10080).toLong(),
                "participantUids" to participants,
                "createdByUid" to uid,
                "createdByName" to creatorName.take(80),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>
            ref.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path)
            Result.failure(e)
        }
    }

    suspend fun deleteCalendarEvent(familyId: String, eventId: String): Result<Unit> = runCatching {
        db.collection("families").document(familyId).collection("calendar_events").document(eventId).delete().await()
    }

    // --- TASKS ---
    fun observeTasks(familyId: String): Flow<List<FamilyTask>> = flow {
        emitAll(
            db.collection("families").document(familyId).collection("tasks")
                .snapshots()
                .map { snap ->
                    snap.toObjects(FamilyTask::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedWith(compareBy<FamilyTask> { it.completed }.thenBy { it.dueAtMs ?: Long.MAX_VALUE })
                }
                .catch { err -> throw unwrapFirestoreError(err) }
        )
    }

    suspend fun createTask(
        familyId: String,
        title: String,
        description: String,
        assignedToUid: String,
        assignedToName: String,
        priority: String,
        dueAtMs: Long?,
        creatorName: String
    ): Result<Unit> {
        val uid = try { requireUid() } catch (e: Exception) { return Result.failure(e) }
        val id = "task_" + UUID.randomUUID().toString().replace("-", "").take(14)
        val ref = db.collection("families").document(familyId).collection("tasks").document(id)
        return try {
            val payload = mapOf(
                "familyId" to familyId,
                "title" to title.trim().take(120),
                "description" to description.trim().take(500),
                "assignedToUid" to assignedToUid,
                "assignedToName" to assignedToName.take(80),
                "priority" to priority,
                "dueAtMs" to dueAtMs,
                "completed" to false,
                "createdByUid" to uid,
                "createdByName" to creatorName.take(80),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>
            ref.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path)
            Result.failure(e)
        }
    }

    suspend fun toggleTaskCompletion(familyId: String, task: FamilyTask): Result<Unit> {
        val uid = try { requireUid() } catch (e: Exception) { return Result.failure(e) }
        val ref = db.collection("families").document(familyId).collection("tasks").document(task.id)
        val nextDone = !task.completed
        return try {
            val updates = if (nextDone) {
                mapOf(
                    "completed" to true,
                    "completedAtMs" to System.currentTimeMillis(),
                    "completedByUid" to uid,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            } else {
                mapOf(
                    "completed" to false,
                    "completedAtMs" to 0L,
                    "completedByUid" to "",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            }
            ref.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path)
            Result.failure(e)
        }
    }

    suspend fun deleteTask(familyId: String, taskId: String): Result<Unit> = runCatching {
        db.collection("families").document(familyId).collection("tasks").document(taskId).delete().await()
    }

    // --- EXPENSES (BDT) ---
    fun observeExpenses(familyId: String): Flow<List<FamilyExpense>> = flow {
        emitAll(
            db.collection("families").document(familyId).collection("expenses")
                .snapshots()
                .map { snap ->
                    snap.toObjects(FamilyExpense::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.expenseDateMs }
                }
                .catch { err -> throw unwrapFirestoreError(err) }
        )
    }

    suspend fun createExpense(
        familyId: String,
        title: String,
        category: String,
        amountBdt: Double,
        paidByUid: String,
        paidByName: String,
        expenseDateMs: Long,
        notes: String
    ): Result<Unit> {
        val uid = try { requireUid() } catch (e: Exception) { return Result.failure(e) }
        val id = "exp_" + UUID.randomUUID().toString().replace("-", "").take(14)
        val ref = db.collection("families").document(familyId).collection("expenses").document(id)
        return try {
            val payload = mapOf(
                "familyId" to familyId,
                "title" to title.trim().take(120),
                "category" to category.trim().take(60),
                "amountBdt" to amountBdt.coerceIn(0.01, 100_000_000.0),
                "paidByUid" to paidByUid,
                "paidByName" to paidByName.take(80),
                "expenseDateMs" to expenseDateMs,
                "notes" to notes.trim().take(300),
                "createdByUid" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            ref.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path)
            Result.failure(e)
        }
    }

    suspend fun deleteExpense(familyId: String, expenseId: String): Result<Unit> = runCatching {
        db.collection("families").document(familyId).collection("expenses").document(expenseId).delete().await()
    }

    // --- NOTICE BOARD ---
    fun observeNotices(familyId: String): Flow<List<FamilyNotice>> = flow {
        emitAll(
            db.collection("families").document(familyId).collection("notices")
                .snapshots()
                .map { snap ->
                    snap.toObjects(FamilyNotice::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedWith(compareByDescending<FamilyNotice> { it.pinned }.thenByDescending { it.createdAt?.toDate()?.time ?: 0L })
                }
                .catch { err -> throw unwrapFirestoreError(err) }
        )
    }

    suspend fun createNotice(
        familyId: String,
        title: String,
        body: String,
        pinned: Boolean,
        expiresAtMs: Long?,
        authorName: String
    ): Result<Unit> {
        val uid = try { requireUid() } catch (e: Exception) { return Result.failure(e) }
        val id = "not_" + UUID.randomUUID().toString().replace("-", "").take(14)
        val ref = db.collection("families").document(familyId).collection("notices").document(id)
        return try {
            val payload = mapOf(
                "familyId" to familyId,
                "title" to title.trim().take(120),
                "body" to body.trim().take(1500),
                "pinned" to pinned,
                "expiresAtMs" to expiresAtMs,
                "readByUids" to listOf(uid),
                "authorUid" to uid,
                "authorName" to authorName.take(80),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>
            ref.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path)
            Result.failure(e)
        }
    }

    suspend fun markNoticeRead(familyId: String, noticeId: String): Result<Unit> {
        val uid = try { requireUid() } catch (e: Exception) { return Result.failure(e) }
        val ref = db.collection("families").document(familyId).collection("notices").document(noticeId)
        return runCatching {
            ref.update(
                mapOf(
                    "readByUids" to FieldValue.arrayUnion(uid),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }.map { }
    }

    suspend fun toggleNoticePinned(familyId: String, notice: FamilyNotice): Result<Unit> = runCatching {
        db.collection("families").document(familyId).collection("notices").document(notice.id)
            .update(
                mapOf(
                    "pinned" to !notice.pinned,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
    }.map { }

    suspend fun deleteNotice(familyId: String, noticeId: String): Result<Unit> = runCatching {
        db.collection("families").document(familyId).collection("notices").document(noticeId).delete().await()
    }

    // --- SECURE FILE VAULT ---
    fun observeVaultFiles(familyId: String, userId: String, isAdmin: Boolean): Flow<List<FamilyVaultFile>> = flow {
        val col = db.collection("families").document(familyId).collection("vault_files")
        val query = if (isAdmin) col else col.whereArrayContains("allowedUids", userId)
        emitAll(
            query.snapshots()
                .map { snap ->
                    snap.toObjects(FamilyVaultFile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                }
                .catch { err -> throw unwrapFirestoreError(err) }
        )
    }

    suspend fun uploadVaultFile(
        familyId: String,
        title: String,
        folder: String,
        fileName: String,
        mimeType: String,
        fileSizeBytes: Long,
        fileBase64: String,
        uploadedByName: String,
        allowedUids: List<String>
    ): Result<Unit> {
        val uid = try { requireUid() } catch (e: Exception) { return Result.failure(e) }
        val id = "vf_" + UUID.randomUUID().toString().replace("-", "").take(14)
        val ref = db.collection("families").document(familyId).collection("vault_files").document(id)
        return try {
            val payload = mapOf(
                "familyId" to familyId,
                "title" to title.trim().take(120),
                "folder" to folder.trim().ifBlank { "General" }.take(60),
                "fileName" to fileName.take(160),
                "mimeType" to mimeType.take(120),
                "fileSizeBytes" to fileSizeBytes,
                "fileBase64" to fileBase64,
                "uploadedByUid" to uid,
                "uploadedByName" to uploadedByName.take(80),
                "allowedUids" to (allowedUids + uid).distinct().take(20),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            ref.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path)
            Result.failure(e)
        }
    }

    suspend fun deleteVaultFile(familyId: String, fileId: String): Result<Unit> = runCatching {
        db.collection("families").document(familyId).collection("vault_files").document(fileId).delete().await()
    }

    // --- CLOUD NOTIFICATIONS ---
    fun observeNotifications(familyId: String, userId: String): Flow<List<FamilyNotificationModel>> = flow {
        emitAll(
            db.collection("families").document(familyId).collection("notifications")
                .whereEqualTo("targetUid", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(FamilyNotificationModel::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                }
                .catch { err -> throw unwrapFirestoreError(err) }
        )
    }

    suspend fun sendTargetedNotification(
        familyId: String,
        targetUid: String,
        title: String,
        body: String,
        type: String
    ): Result<Unit> {
        val uid = try { requireUid() } catch (e: Exception) { return Result.failure(e) }
        val id = "nt_" + UUID.randomUUID().toString().replace("-", "").take(14)
        val ref = db.collection("families").document(familyId).collection("notifications").document(id)
        return runCatching {
            ref.set(
                mapOf(
                    "familyId" to familyId,
                    "targetUid" to targetUid,
                    "senderUid" to uid,
                    "title" to title.take(120),
                    "body" to body.take(400),
                    "type" to type,
                    "seen" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }.map { }
    }

    suspend fun markNotificationSeen(familyId: String, notifId: String): Result<Unit> = runCatching {
        db.collection("families").document(familyId).collection("notifications").document(notifId)
            .update("seen", true)
            .await()
    }.map { }
}
