package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.FamilyMember
import com.example.data.model.GameSession
import com.example.game.AiDifficulty
import com.example.game.BoardSize
import com.example.game.TicTacToeEngine
import com.example.game.TicTacToePlayMode
import com.example.ui.i18n.stringsFor
import kotlinx.coroutines.delay

@Composable
fun TicTacToeScreen(
    language: String,
    currentUserId: String,
    members: List<FamilyMember>,
    games: List<GameSession>,
    selectedGameId: String?,
    onInviteMember: (FamilyMember) -> Unit,
    onSelectGame: (String) -> Unit,
    onMakeMove: (GameSession, Int) -> Unit,
    onRestartGame: (String) -> Unit,
    preferredOpponentUid: String? = null
) {
    val strings = stringsFor(language)
    var playMode by remember { mutableStateOf(TicTacToePlayMode.FAMILY_ONLINE) }
    var autoInviteHandled by remember(preferredOpponentUid) { mutableStateOf(false) }

    LaunchedEffect(preferredOpponentUid, members) {
        if (!autoInviteHandled && !preferredOpponentUid.isNullOrBlank()) {
            val opponent = members.firstOrNull { it.uid == preferredOpponentUid }
            if (opponent != null && opponent.uid != currentUserId) {
                autoInviteHandled = true
                onInviteMember(opponent)
            }
        }
    }

    // Local game configuration & state (shared for Solo AI & Pass-and-Play)
    var boardSize by remember { mutableStateOf(BoardSize.CLASSIC_3X3) }
    var aiDifficulty by remember { mutableStateOf(AiDifficulty.MEDIUM) }
    var humanSymbol by remember { mutableStateOf("X") }

    // Solo AI state
    var aiBoard by remember { mutableStateOf(List(9) { "" }) }
    var aiTurn by remember { mutableStateOf("X") }
    var aiHumanScore by remember { mutableIntStateOf(0) }
    var aiBotScore by remember { mutableIntStateOf(0) }
    var aiDrawScore by remember { mutableIntStateOf(0) }
    var aiRoundCounted by remember { mutableStateOf(false) }

    // Pass & Play state
    var localBoard by remember { mutableStateOf(List(9) { "" }) }
    var localTurn by remember { mutableStateOf("X") }
    var localXScore by remember { mutableIntStateOf(0) }
    var localOScore by remember { mutableIntStateOf(0) }
    var localDrawScore by remember { mutableIntStateOf(0) }
    var localRoundCounted by remember { mutableStateOf(false) }

    fun resetAiBoard(newSize: BoardSize = boardSize) {
        val cells = newSize.dim * newSize.dim
        aiBoard = List(cells) { "" }
        aiTurn = "X"
        aiRoundCounted = false
    }

    fun resetLocalBoard(newSize: BoardSize = boardSize) {
        val cells = newSize.dim * newSize.dim
        localBoard = List(cells) { "" }
        localTurn = "X"
        localRoundCounted = false
    }

    val aiEval = remember(aiBoard, boardSize) { TicTacToeEngine.evaluate(aiBoard, boardSize) }
    val aiSymbol = if (humanSymbol == "X") "O" else "X"

    // Automated AI move when it's AI's turn in Solo AI mode
    LaunchedEffect(playMode, aiBoard, aiTurn, aiDifficulty, boardSize, humanSymbol) {
        if (playMode == TicTacToePlayMode.SOLO_AI && aiEval.status == "ACTIVE" && aiTurn == aiSymbol) {
            delay(320L)
            val chosen = TicTacToeEngine.pickAiMove(
                board = aiBoard,
                boardSize = boardSize,
                aiSymbol = aiSymbol,
                difficulty = aiDifficulty
            )
            if (chosen in aiBoard.indices && aiBoard[chosen].isEmpty()) {
                val updated = aiBoard.toMutableList()
                updated[chosen] = aiSymbol
                aiBoard = updated
                aiTurn = humanSymbol
            }
        }
    }

    // Score bookkeeping for Solo AI
    LaunchedEffect(aiEval.status) {
        if (!aiRoundCounted && aiEval.status != "ACTIVE") {
            aiRoundCounted = true
            when (aiEval.status) {
                "${humanSymbol}_WON" -> aiHumanScore++
                "${aiSymbol}_WON" -> aiBotScore++
                "DRAW" -> aiDrawScore++
            }
        }
    }

    val localEval = remember(localBoard, boardSize) { TicTacToeEngine.evaluate(localBoard, boardSize) }

    // Score bookkeeping for Pass & Play
    LaunchedEffect(localEval.status) {
        if (!localRoundCounted && localEval.status != "ACTIVE") {
            localRoundCounted = true
            when (localEval.status) {
                "X_WON" -> localXScore++
                "O_WON" -> localOScore++
                "DRAW" -> localDrawScore++
            }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(playMode) {
        listState.scrollToItem(0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Mode Selector Row (always accessible at top)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = playMode == TicTacToePlayMode.FAMILY_ONLINE,
                    onClick = { playMode = TicTacToePlayMode.FAMILY_ONLINE },
                    label = { Text(strings.tttModeOnline) },
                    leadingIcon = { Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("ttt_mode_family_online")
                )
            }
            item {
                FilterChip(
                    selected = playMode == TicTacToePlayMode.SOLO_AI,
                    onClick = { playMode = TicTacToePlayMode.SOLO_AI },
                    label = { Text(strings.tttModeAi) },
                    leadingIcon = { Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("ttt_mode_solo_ai")
                )
            }
            item {
                FilterChip(
                    selected = playMode == TicTacToePlayMode.PASS_AND_PLAY,
                    onClick = { playMode = TicTacToePlayMode.PASS_AND_PLAY },
                    label = { Text(strings.tttModePassPlay) },
                    leadingIcon = { Icon(Icons.Default.People, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("ttt_mode_pass_and_play")
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

        when (playMode) {
            TicTacToePlayMode.FAMILY_ONLINE -> {
                val activeGame = games.firstOrNull { it.id == selectedGameId } ?: games.firstOrNull()

                item {
                    val onlineStats = remember(games, currentUserId) {
                        com.example.ui.viewmodel.computeTicTacToeOnlineStats(games, currentUserId)
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Text("Wins: ${onlineStats.wins}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text("Draws: ${onlineStats.draws}", style = MaterialTheme.typography.labelLarge)
                            Text("Losses: ${onlineStats.losses}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                            Text("Active: ${onlineStats.active}", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                item {
                    Text(strings.inviteMemberLabel, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(members.filter { it.uid != currentUserId }, key = { it.uid }) { member ->
                            OutlinedButton(
                                onClick = { onInviteMember(member) },
                                modifier = Modifier.testTag("invite_ttt_${member.uid}")
                            ) {
                                Icon(Icons.Default.GridOn, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(member.displayName)
                            }
                        }
                    }
                }

                if (activeGame != null) {
                    item {
                        val mySymbol = if (activeGame.playerXUid == currentUserId) "X" else "O"
                        val isMyTurn = activeGame.status == "ACTIVE" && activeGame.currentTurn == mySymbol
                        val onlineEval = TicTacToeEngine.evaluate(activeGame.board, BoardSize.CLASSIC_3X3)
                        val statusBanner = when (activeGame.status) {
                            "X_WON" -> if (activeGame.playerXUid == currentUserId) strings.youWonLabel else strings.youLostLabel
                            "O_WON" -> if (activeGame.playerOUid == currentUserId) strings.youWonLabel else strings.youLostLabel
                            "DRAW" -> strings.drawLabel
                            else -> if (isMyTurn) "${strings.yourTurnLabel} ($mySymbol)" else "${strings.opponentTurnLabel} (${activeGame.currentTurn})"
                        }

                        TicTacToeBoardCard(
                            title = "${activeGame.playerXName} (X) vs ${activeGame.playerOName} (O)",
                            statusText = statusBanner,
                            highlightBanner = isMyTurn,
                            boardSize = BoardSize.CLASSIC_3X3,
                            board = activeGame.board,
                            winningIndices = onlineEval.winningIndices,
                            canTapCell = isMyTurn,
                            onCellTap = { idx -> onMakeMove(activeGame, idx) },
                            showRestart = activeGame.status != "ACTIVE",
                            restartLabel = strings.restartGameBtn,
                            onRestart = { onRestartGame(activeGame.id) }
                        )
                    }
                }

                if (games.isNotEmpty()) {
                    item {
                        Text(strings.matchHistoryLabel, style = MaterialTheme.typography.titleMedium)
                    }
                    items(games, key = { it.id }) { g ->
                        Card(
                            onClick = { onSelectGame(g.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${g.playerXName} vs ${g.playerOName}", style = MaterialTheme.typography.bodyMedium)
                                Text(g.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            TicTacToePlayMode.SOLO_AI -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Board size selector
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = boardSize == BoardSize.CLASSIC_3X3,
                                    onClick = {
                                        boardSize = BoardSize.CLASSIC_3X3
                                        resetAiBoard(BoardSize.CLASSIC_3X3)
                                    },
                                    label = { Text(strings.tttClassic3x3) },
                                    modifier = Modifier.testTag("ttt_size_3x3")
                                )
                                FilterChip(
                                    selected = boardSize == BoardSize.PRO_4X4,
                                    onClick = {
                                        boardSize = BoardSize.PRO_4X4
                                        resetAiBoard(BoardSize.PRO_4X4)
                                    },
                                    label = { Text(strings.tttPro4x4) },
                                    modifier = Modifier.testTag("ttt_size_4x4")
                                )
                            }

                            // AI Difficulty selector
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = aiDifficulty == AiDifficulty.EASY,
                                    onClick = {
                                        aiDifficulty = AiDifficulty.EASY
                                        resetAiBoard()
                                    },
                                    label = { Text(strings.tttEasy) },
                                    modifier = Modifier.testTag("ttt_diff_easy")
                                )
                                FilterChip(
                                    selected = aiDifficulty == AiDifficulty.MEDIUM,
                                    onClick = {
                                        aiDifficulty = AiDifficulty.MEDIUM
                                        resetAiBoard()
                                    },
                                    label = { Text(strings.tttMedium) },
                                    modifier = Modifier.testTag("ttt_diff_medium")
                                )
                                FilterChip(
                                    selected = aiDifficulty == AiDifficulty.HARD,
                                    onClick = {
                                        aiDifficulty = AiDifficulty.HARD
                                        resetAiBoard()
                                    },
                                    label = { Text(strings.tttHard) },
                                    modifier = Modifier.testTag("ttt_diff_hard")
                                )
                            }

                            // Play as X / O
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(strings.tttPlayAs, style = MaterialTheme.typography.labelLarge)
                                FilterChip(
                                    selected = humanSymbol == "X",
                                    onClick = {
                                        humanSymbol = "X"
                                        resetAiBoard()
                                    },
                                    label = { Text("X") },
                                    modifier = Modifier.testTag("ttt_side_x")
                                )
                                FilterChip(
                                    selected = humanSymbol == "O",
                                    onClick = {
                                        humanSymbol = "O"
                                        resetAiBoard()
                                    },
                                    label = { Text("O") },
                                    modifier = Modifier.testTag("ttt_side_o")
                                )
                            }
                        }
                    }
                }

                item {
                    ScoreboardCard(
                        title = strings.tttScoreboard,
                        leftLabel = "You ($humanSymbol)",
                        leftScore = aiHumanScore,
                        drawLabel = strings.drawLabel,
                        drawScore = aiDrawScore,
                        rightLabel = "AI ($aiSymbol)",
                        rightScore = aiBotScore,
                        resetLabel = strings.tttResetScore,
                        onResetScore = {
                            aiHumanScore = 0
                            aiBotScore = 0
                            aiDrawScore = 0
                            resetAiBoard()
                        }
                    )
                }

                item {
                    val isHumanTurn = aiEval.status == "ACTIVE" && aiTurn == humanSymbol
                    val statusBanner = when (aiEval.status) {
                        "${humanSymbol}_WON" -> strings.youWonLabel
                        "${aiSymbol}_WON" -> strings.tttAiWon
                        "DRAW" -> strings.drawLabel
                        else -> if (isHumanTurn) "${strings.yourTurnLabel} ($humanSymbol)" else strings.tttAiThinking
                    }
                    TicTacToeBoardCard(
                        title = "${strings.tttModeAi} (${aiDifficulty.name} • ${boardSize.dim}×${boardSize.dim})",
                        statusText = statusBanner,
                        highlightBanner = isHumanTurn,
                        boardSize = boardSize,
                        board = aiBoard,
                        winningIndices = aiEval.winningIndices,
                        canTapCell = isHumanTurn,
                        onCellTap = { idx ->
                            if (aiBoard.getOrNull(idx).isNullOrEmpty() && isHumanTurn) {
                                val next = aiBoard.toMutableList()
                                next[idx] = humanSymbol
                                aiBoard = next
                                aiTurn = aiSymbol
                            }
                        },
                        showRestart = true,
                        restartLabel = strings.restartGameBtn,
                        onRestart = { resetAiBoard() }
                    )
                }
            }

            TicTacToePlayMode.PASS_AND_PLAY -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = boardSize == BoardSize.CLASSIC_3X3,
                                onClick = {
                                    boardSize = BoardSize.CLASSIC_3X3
                                    resetLocalBoard(BoardSize.CLASSIC_3X3)
                                },
                                label = { Text(strings.tttClassic3x3) },
                                modifier = Modifier.testTag("ttt_size_3x3")
                            )
                            FilterChip(
                                selected = boardSize == BoardSize.PRO_4X4,
                                onClick = {
                                    boardSize = BoardSize.PRO_4X4
                                    resetLocalBoard(BoardSize.PRO_4X4)
                                },
                                label = { Text(strings.tttPro4x4) },
                                modifier = Modifier.testTag("ttt_size_4x4")
                            )
                        }
                    }
                }

                item {
                    ScoreboardCard(
                        title = strings.tttScoreboard,
                        leftLabel = "Player X",
                        leftScore = localXScore,
                        drawLabel = strings.drawLabel,
                        drawScore = localDrawScore,
                        rightLabel = "Player O",
                        rightScore = localOScore,
                        resetLabel = strings.tttResetScore,
                        onResetScore = {
                            localXScore = 0
                            localOScore = 0
                            localDrawScore = 0
                            resetLocalBoard()
                        }
                    )
                }

                item {
                    val isActive = localEval.status == "ACTIVE"
                    val statusBanner = when (localEval.status) {
                        "X_WON" -> strings.tttPlayerXWon
                        "O_WON" -> strings.tttPlayerOWon
                        "DRAW" -> strings.drawLabel
                        else -> if (localTurn == "X") strings.tttPlayerXTurn else strings.tttPlayerOTurn
                    }
                    TicTacToeBoardCard(
                        title = "${strings.tttModePassPlay} (${boardSize.dim}×${boardSize.dim})",
                        statusText = statusBanner,
                        highlightBanner = isActive,
                        boardSize = boardSize,
                        board = localBoard,
                        winningIndices = localEval.winningIndices,
                        canTapCell = isActive,
                        onCellTap = { idx ->
                            if (localBoard.getOrNull(idx).isNullOrEmpty() && isActive) {
                                val next = localBoard.toMutableList()
                                next[idx] = localTurn
                                localBoard = next
                                localTurn = if (localTurn == "X") "O" else "X"
                            }
                        },
                        showRestart = true,
                        restartLabel = strings.restartGameBtn,
                        onRestart = { resetLocalBoard() }
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun ScoreboardCard(
    title: String,
    leftLabel: String,
    leftScore: Int,
    drawLabel: String,
    drawScore: Int,
    rightLabel: String,
    rightScore: Int,
    resetLabel: String,
    onResetScore: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                TextButton(
                    onClick = onResetScore,
                    modifier = Modifier.testTag("ttt_reset_score")
                ) {
                    Text(resetLabel)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(leftScore.toString(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text(leftLabel, style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(drawScore.toString(), style = MaterialTheme.typography.headlineSmall)
                    Text(drawLabel, style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(rightScore.toString(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.secondary)
                    Text(rightLabel, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun TicTacToeBoardCard(
    title: String,
    statusText: String,
    highlightBanner: Boolean,
    boardSize: BoardSize,
    board: List<String>,
    winningIndices: List<Int>,
    canTapCell: Boolean,
    onCellTap: (Int) -> Unit,
    showRestart: Boolean,
    restartLabel: String,
    onRestart: () -> Unit
) {
    val cellSize = if (boardSize == BoardSize.CLASSIC_3X3) 86.dp else 66.dp
    val fontSize = if (boardSize == BoardSize.CLASSIC_3X3) 32.sp else 24.sp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 460.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Surface(
                color = if (highlightBanner) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (highlightBanner) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Spacer(Modifier.height(16.dp))

            for (row in 0 until boardSize.dim) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    for (col in 0 until boardSize.dim) {
                        val idx = row * boardSize.dim + col
                        val cell = board.getOrNull(idx).orEmpty()
                        val isWinCell = idx in winningIndices
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (isWinCell) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(
                                width = if (isWinCell) 2.5.dp else 1.5.dp,
                                color = if (isWinCell) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier
                                .size(cellSize)
                                .aspectRatio(1f)
                                .clickable(enabled = canTapCell && cell.isEmpty()) {
                                    onCellTap(idx)
                                }
                                .testTag("ttt_cell_$idx")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = cell,
                                    fontSize = fontSize,
                                    fontWeight = FontWeight.Bold,
                                    color = if (cell == "X") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            }

            if (showRestart) {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onRestart,
                    modifier = Modifier.testTag("restart_ttt_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = restartLabel)
                    Spacer(Modifier.width(6.dp))
                    Text(restartLabel)
                }
            }
        }
    }
}
