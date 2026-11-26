package com.example.data.remote

import android.content.Context
import com.example.R
import com.example.data.model.CallSession
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.GameSession
import com.example.data.model.IceCandidateModel
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

class CommunicationRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User must be signed in.")

    fun observeConversations(familyId: String, userId: String): Flow<List<Conversation>> = flow {
        require(familyId.isNotBlank() && userId.isNotBlank())
        val path = "families/$familyId/conversations"
        emitAll(
            db.collection("families").document(familyId).collection("conversations")
                .whereArrayContains("participantUids", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(Conversation::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.lastMessageAtMs }
                }
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(unwrapped, OperationType.LIST, path)
                    }
                    throw unwrapped
                }
        )
    }

    suspend fun getOrCreateDirectConversation(
        familyId: String,
        otherUid: String,
        otherName: String,
        myName: String
    ): Result<String> {
        val myUid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        val sorted = listOf(myUid, otherUid).sorted()
        val convId = "dm_${sorted[0].take(16)}_${sorted[1].take(16)}"
        val convRef = db.collection("families").document(familyId).collection("conversations").document(convId)
        return try {
            val snap = convRef.get().await()
            if (!snap.exists()) {
                val payload = mapOf(
                    "familyId" to familyId,
                    "type" to "DIRECT",
                    "title" to "$myName & $otherName".take(100),
                    "participantUids" to sorted,
                    "lastMessageText" to "",
                    "lastMessageType" to "TEXT",
                    "lastMessageSenderUid" to myUid,
                    "lastMessageAtMs" to System.currentTimeMillis(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                convRef.set(payload).await()
            }
            Result.success(convId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, convRef.path)
            Result.failure(e)
        }
    }

    fun observeMessages(familyId: String, conversationId: String, userId: String): Flow<List<ChatMessage>> = flow {
        require(familyId.isNotBlank() && conversationId.isNotBlank() && userId.isNotBlank())
        val path = "families/$familyId/conversations/$conversationId/messages"
        emitAll(
            db.collection("families").document(familyId)
                .collection("conversations").document(conversationId)
                .collection("messages")
                .whereArrayContains("participantUids", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(ChatMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.clientCreatedAtMs }
                }
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(unwrapped, OperationType.LIST, path)
                    }
                    throw unwrapped
                }
        )
    }

    suspend fun sendMessageIdempotent(
        messageId: String,
        familyId: String,
        conversationId: String,
        senderName: String,
        participantUids: List<String>,
        type: String,
        text: String,
        voiceAudioBase64: String? = null,
        voiceDurationMs: Long? = null,
        mediaBase64: String? = null,
        fileName: String? = null,
        mimeType: String? = null,
        fileSizeBytes: Long? = null,
        replyToMessageId: String? = null,
        replyToPreview: String? = null,
        clientCreatedAtMs: Long = System.currentTimeMillis()
    ): Result<Unit> {
        val convRef = db.collection("families").document(familyId).collection("conversations").document(conversationId)
        val msgRef = convRef.collection("messages").document(messageId)
        return try {
            val uid = requireUserId()
            val cleanParticipants = (participantUids + uid).distinct().take(20)
            val msgPayload = mapOf(
                "conversationId" to conversationId,
                "familyId" to familyId,
                "senderUid" to uid,
                "senderName" to senderName.take(80),
                "participantUids" to cleanParticipants,
                "type" to type,
                "text" to text.take(2000),
                "voiceAudioBase64" to voiceAudioBase64,
                "voiceDurationMs" to voiceDurationMs,
                "mediaBase64" to mediaBase64,
                "fileName" to fileName?.take(160),
                "mimeType" to mimeType?.take(120),
                "fileSizeBytes" to fileSizeBytes,
                "replyToMessageId" to replyToMessageId?.take(128),
                "replyToPreview" to replyToPreview?.take(200),
                "deletedForAll" to false,
                "clientCreatedAtMs" to clientCreatedAtMs,
                "status" to "SENT",
                "deliveredToUids" to listOf(uid),
                "readByUids" to listOf(uid),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>

            val preview = when (type) {
                "VOICE" -> "Voice message (${((voiceDurationMs ?: 0L) / 1000L)}s)"
                "IMAGE" -> if (text.isNotBlank()) "📷 $text".take(120) else "📷 Photo"
                "DOCUMENT" -> "📄 ${fileName ?: "Document"}".take(120)
                else -> text.take(120)
            }

            try {
                db.runBatch { batch ->
                    batch.set(msgRef, msgPayload)
                    batch.update(
                        convRef,
                        mapOf(
                            "lastMessageText" to preview,
                            "lastMessageType" to type,
                            "lastMessageSenderUid" to uid,
                            "lastMessageAtMs" to clientCreatedAtMs,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val alreadyExists = runCatching {
                    val existing = msgRef.get().await()
                    existing.exists() && existing.getString("senderUid") == uid
                }.getOrDefault(false)
                if (!alreadyExists) throw e
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, msgRef.path)
            Result.failure(e)
        }
    }

    suspend fun markMessageDelivered(familyId: String, conversationId: String, messageId: String): Result<Unit> {
        val msgRef = db.collection("families").document(familyId)
            .collection("conversations").document(conversationId)
            .collection("messages").document(messageId)
        return runCatching {
            val uid = requireUserId()
            msgRef.update(
                mapOf(
                    "status" to "DELIVERED",
                    "deliveredToUids" to FieldValue.arrayUnion(uid),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }.map { }
    }

    suspend fun markMessageRead(familyId: String, conversationId: String, messageId: String): Result<Unit> {
        val msgRef = db.collection("families").document(familyId)
            .collection("conversations").document(conversationId)
            .collection("messages").document(messageId)
        return try {
            val uid = requireUserId()
            msgRef.update(
                mapOf(
                    "status" to "READ",
                    "deliveredToUids" to FieldValue.arrayUnion(uid),
                    "readByUids" to FieldValue.arrayUnion(uid),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, msgRef.path)
            Result.failure(e)
        }
    }

    suspend fun toggleReaction(
        familyId: String,
        conversationId: String,
        message: ChatMessage,
        emoji: String
    ): Result<Unit> {
        val msgRef = db.collection("families").document(familyId)
            .collection("conversations").document(conversationId)
            .collection("messages").document(message.id)
        return try {
            val uid = requireUserId()
            val next = message.reactions.toMutableMap()
            if (next[uid] == emoji) next.remove(uid) else next[uid] = emoji
            msgRef.update(
                mapOf(
                    "reactions" to next,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, msgRef.path)
            Result.failure(e)
        }
    }

    suspend fun editMessageText(
        familyId: String,
        conversationId: String,
        messageId: String,
        newText: String
    ): Result<Unit> {
        val clean = newText.trim().take(2000)
        if (clean.isBlank()) return Result.failure(IllegalArgumentException("Message cannot be empty"))
        val msgRef = db.collection("families").document(familyId)
            .collection("conversations").document(conversationId)
            .collection("messages").document(messageId)
        return try {
            requireUserId()
            msgRef.update(
                mapOf(
                    "text" to clean,
                    "editedAtMs" to System.currentTimeMillis(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, msgRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteMessageForAll(
        familyId: String,
        conversationId: String,
        messageId: String
    ): Result<Unit> {
        val msgRef = db.collection("families").document(familyId)
            .collection("conversations").document(conversationId)
            .collection("messages").document(messageId)
        return try {
            requireUserId()
            msgRef.update(
                mapOf(
                    "text" to "This message was deleted",
                    "deletedForAll" to true,
                    "editedAtMs" to System.currentTimeMillis(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, msgRef.path)
            Result.failure(e)
        }
    }

    suspend fun toggleConversationFlag(
        familyId: String,
        conversationId: String,
        fieldName: String, // pinnedByUids | mutedByUids | archivedByUids
        enable: Boolean
    ): Result<Unit> {
        val convRef = db.collection("families").document(familyId)
            .collection("conversations").document(conversationId)
        return try {
            val uid = requireUserId()
            val op = if (enable) FieldValue.arrayUnion(uid) else FieldValue.arrayRemove(uid)
            convRef.update(
                mapOf(
                    fieldName to op,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, convRef.path)
            Result.failure(e)
        }
    }

    // --- CALL SIGNALING ---
    fun observeCalls(familyId: String, userId: String): Flow<List<CallSession>> = flow {
        require(familyId.isNotBlank() && userId.isNotBlank())
        val path = "families/$familyId/calls"
        emitAll(
            db.collection("families").document(familyId).collection("calls")
                .whereArrayContains("participantUids", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(CallSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.startedAtMs }
                }
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(unwrapped, OperationType.LIST, path)
                    }
                    throw unwrapped
                }
        )
    }

    suspend fun createCallSession(
        familyId: String,
        callerName: String,
        calleeUid: String,
        calleeName: String,
        offerSdp: String
    ): Result<String> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        val callId = "call_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val callRef = db.collection("families").document(familyId).collection("calls").document(callId)
        return try {
            val payload = mapOf(
                "familyId" to familyId,
                "callerUid" to uid,
                "callerName" to callerName.take(80),
                "calleeUid" to calleeUid,
                "calleeName" to calleeName.take(80),
                "participantUids" to listOf(uid, calleeUid),
                "status" to "RINGING",
                "offerSdp" to offerSdp.take(30000),
                "startedAtMs" to System.currentTimeMillis(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            callRef.set(payload).await()
            Result.success(callId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, callRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateCallStatus(
        familyId: String,
        callId: String,
        status: String,
        answerSdp: String? = null,
        endedAtMs: Long? = null
    ): Result<Unit> {
        val callRef = db.collection("families").document(familyId).collection("calls").document(callId)
        return try {
            requireUserId()
            val updates = mutableMapOf<String, Any>(
                "status" to status,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (answerSdp != null) updates["answerSdp"] = answerSdp.take(30000)
            if (endedAtMs != null) updates["endedAtMs"] = endedAtMs
            callRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, callRef.path)
            Result.failure(e)
        }
    }

    suspend fun sendIceCandidate(
        familyId: String,
        callId: String,
        participantUids: List<String>,
        sdpMid: String,
        sdpMLineIndex: Int,
        candidate: String
    ): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        val candRef = db.collection("families").document(familyId)
            .collection("calls").document(callId)
            .collection("candidates").document()
        return try {
            val payload = mapOf(
                "senderUid" to uid,
                "participantUids" to participantUids,
                "sdpMid" to sdpMid.take(128),
                "sdpMLineIndex" to sdpMLineIndex.toLong(),
                "candidate" to candidate.take(2000),
                "createdAt" to FieldValue.serverTimestamp()
            )
            candRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, candRef.path)
            Result.failure(e)
        }
    }

    fun observeIceCandidates(familyId: String, callId: String, userId: String): Flow<List<IceCandidateModel>> = flow {
        require(familyId.isNotBlank() && callId.isNotBlank() && userId.isNotBlank())
        val path = "families/$familyId/calls/$callId/candidates"
        emitAll(
            db.collection("families").document(familyId)
                .collection("calls").document(callId)
                .collection("candidates")
                .whereArrayContains("participantUids", userId)
                .snapshots()
                .map { snap -> snap.toObjects(IceCandidateModel::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(unwrapped, OperationType.LIST, path)
                    }
                    throw unwrapped
                }
        )
    }

    // --- MULTIPLAYER TIC-TAC-TOE ---
    fun observeGames(familyId: String, userId: String): Flow<List<GameSession>> = flow {
        require(familyId.isNotBlank() && userId.isNotBlank())
        val path = "families/$familyId/games"
        emitAll(
            db.collection("families").document(familyId).collection("games")
                .whereArrayContains("participantUids", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(GameSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.updatedAt?.toDate()?.time ?: 0L }
                }
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(unwrapped, OperationType.LIST, path)
                    }
                    throw unwrapped
                }
        )
    }

    suspend fun createGameSession(
        familyId: String,
        myName: String,
        opponentUid: String,
        opponentName: String
    ): Result<String> {
        val myUid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        val gameId = "ttt_" + UUID.randomUUID().toString().replace("-", "").take(14)
        val gameRef = db.collection("families").document(familyId).collection("games").document(gameId)
        return try {
            val payload = mapOf(
                "familyId" to familyId,
                "playerXUid" to myUid,
                "playerXName" to myName.take(80),
                "playerOUid" to opponentUid,
                "playerOName" to opponentName.take(80),
                "participantUids" to listOf(myUid, opponentUid),
                "board" to List(9) { "" },
                "currentTurn" to "X",
                "status" to "ACTIVE",
                "moveCount" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            gameRef.set(payload).await()
            Result.success(gameId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, gameRef.path)
            Result.failure(e)
        }
    }

    suspend fun submitTicTacToeMove(
        familyId: String,
        game: GameSession,
        cellIndex: Int
    ): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        if (cellIndex !in 0..8) return Result.failure(IllegalArgumentException("Invalid cell"))
        if (game.status != "ACTIVE") return Result.failure(IllegalStateException("Game already finished"))
        val mySymbol = when (uid) {
            game.playerXUid -> "X"
            game.playerOUid -> "O"
            else -> return Result.failure(IllegalStateException("Not a participant"))
        }
        if (game.currentTurn != mySymbol) return Result.failure(IllegalStateException("Not your turn"))
        if (game.board.getOrNull(cellIndex).orEmpty().isNotEmpty()) {
            return Result.failure(IllegalStateException("Cell already occupied"))
        }

        val newBoard = game.board.toMutableList()
        newBoard[cellIndex] = mySymbol
        val newMoveCount = game.moveCount + 1L
        val winner = detectWinner(newBoard)
        val nextStatus = when {
            winner == "X" -> "X_WON"
            winner == "O" -> "O_WON"
            newMoveCount >= 9L -> "DRAW"
            else -> "ACTIVE"
        }
        val winnerUid = when (winner) {
            "X" -> game.playerXUid
            "O" -> game.playerOUid
            else -> null
        }
        val nextTurn = if (mySymbol == "X") "O" else "X"

        val gameRef = db.collection("families").document(familyId).collection("games").document(game.id)
        val moveId = "m_${newMoveCount}_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val moveRef = gameRef.collection("moves").document(moveId)
        return try {
            val gameUpdates = mapOf(
                "board" to newBoard,
                "currentTurn" to nextTurn,
                "status" to nextStatus,
                "winnerUid" to winnerUid,
                "moveCount" to newMoveCount,
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>

            val movePayload = mapOf(
                "gameId" to game.id,
                "playerUid" to uid,
                "symbol" to mySymbol,
                "cellIndex" to cellIndex.toLong(),
                "moveNumber" to newMoveCount,
                "participantUids" to game.participantUids,
                "createdAt" to FieldValue.serverTimestamp()
            )
            db.runBatch { batch ->
                batch.update(gameRef, gameUpdates)
                batch.set(moveRef, movePayload)
            }.await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, gameRef.path)
            Result.failure(e)
        }
    }

    suspend fun restartGame(familyId: String, gameId: String): Result<Unit> {
        val gameRef = db.collection("families").document(familyId).collection("games").document(gameId)
        return try {
            requireUserId()
            gameRef.update(
                mapOf(
                    "board" to List(9) { "" },
                    "currentTurn" to "X",
                    "status" to "ACTIVE",
                    "winnerUid" to "",
                    "moveCount" to 0L,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, gameRef.path)
            Result.failure(e)
        }
    }

    companion object {
        fun detectWinner(board: List<String>): String? {
            if (board.size != 9) return null
            val lines = arrayOf(
                intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
                intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
                intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)
            )
            for (line in lines) {
                val a = board[line[0]]
                if (a.isNotEmpty() && a == board[line[1]] && a == board[line[2]]) {
                    return a
                }
            }
            return null
        }
    }
}
