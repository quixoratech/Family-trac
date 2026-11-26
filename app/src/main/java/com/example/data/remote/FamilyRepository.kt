package com.example.data.remote

import android.content.Context
import android.os.Build
import com.example.R
import com.example.data.model.FamilyGroup
import com.example.data.model.FamilyMember
import com.example.data.model.LocationRecord
import com.example.data.model.PairedDevice
import com.example.data.model.PairingCode
import com.example.data.model.UserProfile
import com.example.service.QrDiagnosticLogger
import com.example.service.QrDiagnosticStage
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.Date
import java.util.UUID

data class PairingCodeGenerationOutcome(
    val pairingCode: PairingCode,
    val syncedToCloud: Boolean,
    val pendingWriteTask: com.google.android.gms.tasks.Task<Void>? = null
)

data class FamilyQuerySnapshot(
    val families: List<FamilyGroup>,
    val isFromCache: Boolean
)

class FamilyRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val random = SecureRandom()

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
        require(userId.isNotBlank())
        val docRef = db.collection("users").document(userId)
        emitAll(
            docRef.snapshots()
                .map { it.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(unwrapped, OperationType.GET, docRef.path)
                    }
                    throw unwrapped
                }
        )
    }

    suspend fun upsertUserProfile(
        role: String,
        language: String,
        familyId: String? = null,
        deviceId: String? = null,
        lastSeenPrivacy: String? = null
    ): Result<Unit> {
        val user = auth.currentUser ?: return Result.failure(IllegalStateException("Not signed in"))
        val uid = user.uid
        val docRef = db.collection("users").document(uid)
        return try {
            val existing = docRef.get().await()
            if (!existing.exists()) {
                val payload = mapOf(
                    "userId" to uid,
                    "displayName" to (user.displayName?.take(80)?.ifBlank { null } ?: "Family Member"),
                    "email" to user.email?.take(160),
                    "photoUrl" to user.photoUrl?.toString()?.take(500),
                    "role" to role,
                    "familyId" to familyId,
                    "deviceId" to deviceId,
                    "language" to language,
                    "lastSeenPrivacy" to (lastSeenPrivacy ?: "EVERYONE"),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null } as Map<String, Any>
                docRef.set(payload).await()
            } else {
                val updates = mutableMapOf<String, Any>(
                    "displayName" to (user.displayName?.take(80)?.ifBlank { null } ?: "Family Member"),
                    "role" to role,
                    "language" to language,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                user.email?.take(160)?.let { updates["email"] = it }
                user.photoUrl?.toString()?.take(500)?.let { updates["photoUrl"] = it }
                if (!familyId.isNullOrBlank()) updates["familyId"] = familyId
                if (!deviceId.isNullOrBlank()) updates["deviceId"] = deviceId
                if (!lastSeenPrivacy.isNullOrBlank()) updates["lastSeenPrivacy"] = lastSeenPrivacy
                docRef.update(updates).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeMyFamilies(
        userId: String,
        onTransientError: ((Throwable) -> Unit)? = null,
        onRecovered: (() -> Unit)? = null
    ): Flow<List<FamilyGroup>> = observeMyFamiliesWithMetadata(userId, onTransientError, onRecovered)
        .map { it.families }

    fun observeMyFamiliesWithMetadata(
        userId: String,
        onTransientError: ((Throwable) -> Unit)? = null,
        onRecovered: (() -> Unit)? = null
    ): Flow<FamilyQuerySnapshot> = flow {
        require(userId.isNotBlank())
        val path = "families"
        emitAll(
            db.collection(path)
                .whereArrayContains("memberUids", userId)
                .snapshots(MetadataChanges.INCLUDE)
                .map { snap ->
                    FamilyQuerySnapshot(
                        families = snap.toObjects(FamilyGroup::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE),
                        isFromCache = snap.metadata.isFromCache
                    )
                }
                .retryTransientFirestore(
                    tag = "ObserveFamilies",
                    onTransientError = onTransientError,
                    onRecovered = onRecovered
                )
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(unwrapped, OperationType.LIST, path)
                    }
                    throw unwrapped
                }
        )
    }

    suspend fun createFamilyAsAdmin(
        familyName: String,
        deviceId: String,
        language: String
    ): Result<FamilyGroup> {
        val user = auth.currentUser ?: return Result.failure(IllegalStateException("Not signed in"))
        val uid = user.uid
        val trimmedName = familyName.trim().take(80)
        if (trimmedName.isBlank()) {
            return Result.failure(IllegalArgumentException("Family name is required"))
        }
        val familyId = "fam_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val familyRef = db.collection("families").document(familyId)
        val memberRef = familyRef.collection("members").document(uid)
        val deviceRef = familyRef.collection("devices").document(deviceId)
        val groupConvRef = familyRef.collection("conversations").document("conv_group")

        return try {
            val displayName = user.displayName?.take(80)?.ifBlank { null } ?: "Family Admin"
            val modelName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80)
            val nowMs = System.currentTimeMillis()

            val familyPayload = mapOf(
                "adminUid" to uid,
                "adminEmail" to user.email?.take(160),
                "name" to trimmedName,
                "memberUids" to listOf(uid),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>

            val memberPayload = mapOf(
                "uid" to uid,
                "familyId" to familyId,
                "displayName" to displayName,
                "photoUrl" to user.photoUrl?.toString()?.take(500),
                "role" to "ADMIN",
                "deviceId" to deviceId,
                "online" to true,
                "joinedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>

            val devicePayload = mapOf(
                "familyId" to familyId,
                "adminUid" to uid,
                "ownerUid" to uid,
                "ownerName" to displayName,
                "deviceName" to "$displayName's Admin Phone".take(80),
                "deviceModel" to modelName.ifBlank { "Android Device" },
                "role" to "ADMIN",
                "consentGranted" to true,
                "consentTimestamp" to FieldValue.serverTimestamp(),
                "locationSharingEnabled" to false,
                "locationPermissionGranted" to false,
                "backgroundPermissionGranted" to false,
                "batteryPercent" to 100L,
                "networkType" to "WIFI",
                "online" to true,
                "revoked" to false,
                "lastSeenAtMs" to nowMs,
                "lastSyncAtMs" to nowMs,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )

            val groupConvPayload = mapOf(
                "familyId" to familyId,
                "type" to "GROUP",
                "title" to "$trimmedName Chat".take(100),
                "participantUids" to listOf(uid),
                "lastMessageText" to "Family group created",
                "lastMessageType" to "TEXT",
                "lastMessageSenderUid" to uid,
                "lastMessageAtMs" to nowMs,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )

            db.runBatch { batch ->
                batch.set(familyRef, familyPayload)
                batch.set(memberRef, memberPayload)
                batch.set(deviceRef, devicePayload)
                batch.set(groupConvRef, groupConvPayload)
            }.await()

            upsertUserProfile(role = "ADMIN", language = language, familyId = familyId, deviceId = deviceId)

            Result.success(
                FamilyGroup(
                    id = familyId,
                    adminUid = uid,
                    adminEmail = user.email,
                    name = trimmedName,
                    memberUids = listOf(uid)
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, familyRef.path)
            Result.failure(e)
        }
    }

    suspend fun generatePairingCode(familyId: String, familyName: String): Result<PairingCode> {
        return generatePairingCodeWithStatus(familyId, familyName).map { it.pairingCode }
    }

    suspend fun generatePairingCodeWithStatus(
        familyId: String,
        familyName: String,
        serverAckTimeoutMs: Long = 6_000L,
        attemptId: String = QrDiagnosticLogger.newAttemptId(),
        fallbackAdminUid: String = ""
    ): Result<PairingCodeGenerationOutcome> {
        val liveUid = auth.currentUser?.uid
        val uid = liveUid ?: fallbackAdminUid.takeIf { it.isNotBlank() } ?: try {
            requireUserId()
        } catch (e: Exception) {
            QrDiagnosticLogger.logStage(
                attemptId = attemptId,
                stage = QrDiagnosticStage.AUTH_AND_FAMILY_CHECK,
                status = "FAIL",
                message = "No active Firebase user for pairing code creation",
                error = e
            )
            return Result.failure(e)
        }
        if (familyId.isBlank()) {
            val err = IllegalStateException("Family session is not ready yet. Please retry.")
            QrDiagnosticLogger.logStage(
                attemptId = attemptId,
                stage = QrDiagnosticStage.AUTH_AND_FAMILY_CHECK,
                status = "FAIL",
                message = err.message ?: "Blank familyId",
                error = err
            )
            return Result.failure(err)
        }
        QrDiagnosticLogger.logStage(
            attemptId = attemptId,
            stage = QrDiagnosticStage.AUTH_AND_FAMILY_CHECK,
            status = if (liveUid != null) "OK" else "WARN",
            message = "familyId=$familyId, liveFirebaseAuth=${liveUid != null}"
        )

        val cleanFamilyName = familyName.trim().ifBlank { "My Family" }.take(80)
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val codeBuilder = StringBuilder("FT")
        repeat(6) { codeBuilder.append(alphabet[random.nextInt(alphabet.length)]) }
        val code = codeBuilder.toString()
        val expiresAt = Timestamp(Date(System.currentTimeMillis() + 15 * 60 * 1000L))
        val docRef = db.collection("pairing_codes").document(code)

        return try {
            val payload = mapOf(
                "code" to code,
                "familyId" to familyId,
                "adminUid" to uid,
                "familyName" to cleanFamilyName,
                "status" to "ACTIVE",
                "expiresAt" to expiresAt,
                "createdAt" to FieldValue.serverTimestamp()
            )
            val startMs = android.os.SystemClock.elapsedRealtime()
            QrDiagnosticLogger.logStage(
                attemptId = attemptId,
                stage = QrDiagnosticStage.FIRESTORE_WRITE_START,
                status = "OK",
                message = "Writing pairing_codes/$code"
            )
            val writeTask = docRef.set(payload)
            val syncedWithinTimeout = withTimeoutOrNull(serverAckTimeoutMs) {
                writeTask.await()
                true
            } == true
            val elapsedMs = android.os.SystemClock.elapsedRealtime() - startMs
            QrDiagnosticLogger.logStage(
                attemptId = attemptId,
                stage = QrDiagnosticStage.FIRESTORE_WRITE_RESULT,
                status = if (syncedWithinTimeout) "OK" else "WARN",
                message = if (syncedWithinTimeout) {
                    "Cloud ACK received for $code"
                } else {
                    "Cloud ACK timed out after ${serverAckTimeoutMs}ms; keeping offline-queued code $code"
                },
                durationMs = elapsedMs
            )
            val model = PairingCode(
                id = code,
                code = code,
                familyId = familyId,
                adminUid = uid,
                familyName = cleanFamilyName,
                status = "ACTIVE",
                expiresAt = expiresAt
            )
            Result.success(
                PairingCodeGenerationOutcome(
                    pairingCode = model,
                    syncedToCloud = syncedWithinTimeout,
                    pendingWriteTask = if (syncedWithinTimeout) null else writeTask
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            QrDiagnosticLogger.logStage(
                attemptId = attemptId,
                stage = QrDiagnosticStage.ERROR,
                status = "FAIL",
                message = "Firestore pairing_codes write failed: ${e.message ?: e.javaClass.simpleName}",
                error = e
            )
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeActivePairingCodes(adminUid: String): Flow<List<PairingCode>> = flow {
        require(adminUid.isNotBlank())
        emitAll(
            db.collection("pairing_codes")
                .whereEqualTo("adminUid", adminUid)
                .snapshots()
                .map { snap -> snap.toObjects(PairingCode::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .retryTransientFirestore("ObservePairingCodes")
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser?.uid == adminUid) {
                        handleFirestoreError(unwrapped, OperationType.LIST, "pairing_codes")
                    }
                    throw unwrapped
                }
        )
    }

    suspend fun lookupPairingCode(rawCode: String): Result<PairingCode> {
        val cleanCode = rawCode.trim().uppercase()
        if (!cleanCode.matches(Regex("^[A-Z0-9]{6,16}$"))) {
            return Result.failure(IllegalArgumentException("Invalid pairing code format"))
        }
        val docRef = db.collection("pairing_codes").document(cleanCode)
        return try {
            val snap = docRef.get().await()
            if (!snap.exists()) {
                return Result.failure(IllegalArgumentException("Pairing code not found"))
            }
            val parsed = snap.toObject(PairingCode::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: return Result.failure(IllegalStateException("Malformed pairing code"))
            if (parsed.status != "ACTIVE") {
                return Result.failure(IllegalStateException("Pairing code has already been ${parsed.status.lowercase()}"))
            }
            val expMs = parsed.expiresAt?.toDate()?.time ?: 0L
            if (expMs <= System.currentTimeMillis()) {
                return Result.failure(IllegalStateException("Pairing code has expired. Ask Admin for a new code."))
            }
            Result.success(parsed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun joinFamilyWithPairingCode(
        pairingCode: PairingCode,
        deviceId: String,
        customDeviceName: String,
        language: String
    ): Result<Unit> {
        val user = auth.currentUser ?: return Result.failure(IllegalStateException("Not signed in"))
        val uid = user.uid
        val familyId = pairingCode.familyId
        val codeRef = db.collection("pairing_codes").document(pairingCode.code)
        val familyRef = db.collection("families").document(familyId)
        val memberRef = familyRef.collection("members").document(uid)
        val deviceRef = familyRef.collection("devices").document(deviceId)

        return try {
            val displayName = user.displayName?.take(80)?.ifBlank { null } ?: "Family Member"
            val modelName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80)
            val nowMs = System.currentTimeMillis()

            val memberPayload = mapOf(
                "uid" to uid,
                "familyId" to familyId,
                "displayName" to displayName,
                "photoUrl" to user.photoUrl?.toString()?.take(500),
                "role" to "FAMILY",
                "deviceId" to deviceId,
                "online" to true,
                "joinedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>

            val devicePayload = mapOf(
                "familyId" to familyId,
                "adminUid" to pairingCode.adminUid,
                "ownerUid" to uid,
                "ownerName" to displayName,
                "deviceName" to customDeviceName.trim().ifBlank { modelName }.take(80),
                "deviceModel" to modelName.ifBlank { "Android Device" },
                "role" to "FAMILY",
                "consentGranted" to false,
                "locationSharingEnabled" to false,
                "locationPermissionGranted" to false,
                "backgroundPermissionGranted" to false,
                "batteryPercent" to 100L,
                "networkType" to "WIFI",
                "online" to true,
                "revoked" to false,
                "lastSeenAtMs" to nowMs,
                "lastSyncAtMs" to nowMs,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )

            db.runBatch { batch ->
                batch.update(
                    codeRef,
                    mapOf(
                        "status" to "USED",
                        "usedByUid" to uid,
                        "usedByDeviceId" to deviceId,
                        "usedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.update(
                    familyRef,
                    mapOf(
                        "memberUids" to FieldValue.arrayUnion(uid),
                        "lastPairingCode" to pairingCode.code,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(memberRef, memberPayload)
                batch.set(deviceRef, devicePayload)
            }.await()

            upsertUserProfile(role = "FAMILY", language = language, familyId = familyId, deviceId = deviceId)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, familyRef.path)
            Result.failure(e)
        }
    }

    fun observeFamilyMembers(familyId: String): Flow<List<FamilyMember>> = flow {
        require(familyId.isNotBlank())
        val path = "families/$familyId/members"
        emitAll(
            db.collection("families").document(familyId).collection("members")
                .snapshots()
                .map { snap -> snap.toObjects(FamilyMember::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .retryTransientFirestore("ObserveMembers")
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser != null) {
                        handleFirestoreError(unwrapped, OperationType.LIST, path)
                    }
                    throw unwrapped
                }
        )
    }

    fun observeAdminDevices(familyId: String, adminUid: String): Flow<List<PairedDevice>> = flow {
        require(familyId.isNotBlank() && adminUid.isNotBlank())
        val path = "families/$familyId/devices"
        emitAll(
            db.collection("families").document(familyId).collection("devices")
                .whereEqualTo("adminUid", adminUid)
                .snapshots()
                .map { snap -> snap.toObjects(PairedDevice::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .retryTransientFirestore("ObserveAdminDevices")
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser?.uid == adminUid) {
                        handleFirestoreError(unwrapped, OperationType.LIST, path)
                    }
                    throw unwrapped
                }
        )
    }

    fun observeOwnDevice(familyId: String, ownerUid: String): Flow<List<PairedDevice>> = flow {
        require(familyId.isNotBlank() && ownerUid.isNotBlank())
        val path = "families/$familyId/devices"
        emitAll(
            db.collection("families").document(familyId).collection("devices")
                .whereEqualTo("ownerUid", ownerUid)
                .snapshots()
                .map { snap -> snap.toObjects(PairedDevice::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .retryTransientFirestore("ObserveOwnDevice")
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser?.uid == ownerUid) {
                        handleFirestoreError(unwrapped, OperationType.LIST, path)
                    }
                    throw unwrapped
                }
        )
    }

    suspend fun updateOwnDeviceStatus(
        familyId: String,
        deviceId: String,
        consentGranted: Boolean,
        locationSharingEnabled: Boolean,
        locationPermissionGranted: Boolean,
        backgroundPermissionGranted: Boolean,
        batteryPercent: Int,
        networkType: String,
        online: Boolean,
        lastLatitude: Double? = null,
        lastLongitude: Double? = null,
        lastAccuracyMeters: Double? = null,
        lastLocationAtMs: Long? = null,
        lastSyncAtMs: Long? = null,
        networkConsentGranted: Boolean? = null,
        networkMonitoringEnabled: Boolean? = null,
        currentWifiAvailable: Boolean? = null,
        currentCellularAvailable: Boolean? = null,
        internetAvailable: Boolean? = null,
        currentNetworkSessionId: String? = null,
        currentSessionStartedAtMs: Long? = null,
        lastNetworkEventType: String? = null,
        lastNetworkEventAtMs: Long? = null,
        lastActiveAtMs: Long? = null
    ): Result<Unit> {
        val docRef = db.collection("families").document(familyId).collection("devices").document(deviceId)
        return try {
            requireUserId()
            val nowMs = System.currentTimeMillis()
            val health = when {
                batteryPercent <= 15 -> "LOW_BATTERY"
                !locationPermissionGranted -> "PERM_MISSING"
                else -> "GOOD"
            }
            val updates = mutableMapOf<String, Any>(
                "androidVersion" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})".take(40),
                "consentGranted" to consentGranted,
                "locationSharingEnabled" to locationSharingEnabled,
                "locationPermissionGranted" to locationPermissionGranted,
                "backgroundPermissionGranted" to backgroundPermissionGranted,
                "batteryPercent" to batteryPercent.coerceIn(0, 100).toLong(),
                "networkType" to networkType,
                "deviceHealth" to health,
                "online" to online,
                "lastSeenAtMs" to nowMs,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (consentGranted) updates["consentTimestamp"] = FieldValue.serverTimestamp()
            if (networkConsentGranted != null) updates["networkConsentGranted"] = networkConsentGranted
            if (networkMonitoringEnabled != null) updates["networkMonitoringEnabled"] = networkMonitoringEnabled
            if (currentWifiAvailable != null) updates["currentWifiAvailable"] = currentWifiAvailable
            if (currentCellularAvailable != null) updates["currentCellularAvailable"] = currentCellularAvailable
            if (internetAvailable != null) updates["internetAvailable"] = internetAvailable
            if (currentNetworkSessionId != null) updates["currentNetworkSessionId"] = currentNetworkSessionId.take(128)
            if (currentSessionStartedAtMs != null) updates["currentSessionStartedAtMs"] = currentSessionStartedAtMs
            if (lastNetworkEventType != null) updates["lastNetworkEventType"] = lastNetworkEventType.take(64)
            if (lastNetworkEventAtMs != null) updates["lastNetworkEventAtMs"] = lastNetworkEventAtMs
            if (lastActiveAtMs != null) updates["lastActiveAtMs"] = lastActiveAtMs
            if (lastLatitude != null && lastLongitude != null) {
                updates["lastLatitude"] = lastLatitude
                updates["lastLongitude"] = lastLongitude
                if (lastAccuracyMeters != null) updates["lastAccuracyMeters"] = lastAccuracyMeters
                if (lastLocationAtMs != null) updates["lastLocationAtMs"] = lastLocationAtMs
            }
            if (lastSyncAtMs != null) {
                updates["lastSyncAtMs"] = lastSyncAtMs
            }
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun renameDevice(familyId: String, deviceId: String, newName: String): Result<Unit> {
        val clean = newName.trim().take(80)
        if (clean.isBlank()) return Result.failure(IllegalArgumentException("Device name cannot be empty"))
        val docRef = db.collection("families").document(familyId).collection("devices").document(deviceId)
        return try {
            requireUserId()
            docRef.update(
                mapOf(
                    "deviceName" to clean,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun setDeviceRevoked(familyId: String, deviceId: String, revoked: Boolean): Result<Unit> {
        val docRef = db.collection("families").document(familyId).collection("devices").document(deviceId)
        return try {
            requireUserId()
            docRef.update(
                mapOf(
                    "revoked" to revoked,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun removeDevice(familyId: String, deviceId: String): Result<Unit> {
        val docRef = db.collection("families").document(familyId).collection("devices").document(deviceId)
        return try {
            requireUserId()
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun revokeDevice(familyId: String, deviceId: String): Result<Unit> =
        setDeviceRevoked(familyId, deviceId, true)

    suspend fun updateMemberPresence(
        familyId: String,
        online: Boolean,
        lastSeenPrivacy: String? = null,
        availability: String? = null,
        typingConversationId: String? = null,
        typingActivity: String? = null,
        lastSyncAtMs: Long? = null
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val ref = db.collection("families").document(familyId).collection("members").document(uid)
        return try {
            val nowMs = System.currentTimeMillis()
            val updates = mutableMapOf<String, Any>(
                "online" to online,
                "lastSeenAtMs" to nowMs,
                "lastActiveAtMs" to nowMs,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (lastSeenPrivacy != null) updates["lastSeenPrivacy"] = lastSeenPrivacy
            if (availability != null) updates["availability"] = availability
            if (typingConversationId != null) updates["typingConversationId"] = typingConversationId.take(128)
            if (typingActivity != null) {
                updates["typingActivity"] = typingActivity
                updates["typingUpdatedAtMs"] = nowMs
            }
            if (lastSyncAtMs != null) updates["lastSyncAtMs"] = lastSyncAtMs
            ref.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadLocationRecordIdempotent(
        recordId: String,
        familyId: String,
        adminUid: String,
        deviceId: String,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double,
        altitudeMeters: Double?,
        speedMps: Double?,
        batteryPercent: Int,
        networkType: String,
        source: String,
        recordedAtMs: Long
    ): Result<Unit> {
        val docRef = db.collection("families").document(familyId).collection("locations").document(recordId)
        return try {
            val uid = requireUserId()
            val payload = mapOf(
                "familyId" to familyId,
                "adminUid" to adminUid,
                "ownerUid" to uid,
                "deviceId" to deviceId,
                "latitude" to latitude,
                "longitude" to longitude,
                "accuracyMeters" to accuracyMeters,
                "altitudeMeters" to altitudeMeters,
                "speedMps" to speedMps,
                "batteryPercent" to batteryPercent.coerceIn(0, 100).toLong(),
                "networkType" to networkType,
                "source" to source,
                "recordedAtMs" to recordedAtMs,
                "syncedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>
            try {
                docRef.set(payload).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val alreadyUploaded = runCatching {
                    val existing = docRef.get().await()
                    existing.exists() && existing.getString("ownerUid") == uid
                }.getOrDefault(false)
                if (!alreadyUploaded) throw e
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeSevenDayLocationHistory(familyId: String, adminUid: String): Flow<List<LocationRecord>> = flow {
        require(familyId.isNotBlank() && adminUid.isNotBlank())
        val path = "families/$familyId/locations"
        val sevenDaysAgoMs = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        emitAll(
            db.collection("families").document(familyId).collection("locations")
                .whereEqualTo("adminUid", adminUid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(LocationRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .filter { it.recordedAtMs >= sevenDaysAgoMs }
                        .sortedBy { it.recordedAtMs }
                }
                .retryTransientFirestore("ObserveLocationHistory")
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser?.uid == adminUid) {
                        handleFirestoreError(unwrapped, OperationType.LIST, path)
                    }
                    throw unwrapped
                }
        )
    }

    suspend fun ensureGroupConversationMember(familyId: String, memberUids: List<String>) {
        val groupRef = db.collection("families").document(familyId).collection("conversations").document("conv_group")
        try {
            val snap = groupRef.get().await()
            if (snap.exists()) {
                val current = (snap.get("participantUids") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                val merged = (current + memberUids).distinct().take(20)
                if (merged.size > current.size) {
                    groupRef.update(
                        mapOf(
                            "participantUids" to merged,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }
        } catch (_: Exception) {
            // Non-fatal sync helper
        }
    }
}
