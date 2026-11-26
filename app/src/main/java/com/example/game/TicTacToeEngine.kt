package com.example.game

import kotlin.random.Random

enum class TicTacToePlayMode {
    FAMILY_ONLINE,
    SOLO_AI,
    PASS_AND_PLAY
}

enum class AiDifficulty {
    EASY,
    MEDIUM,
    HARD
}

enum class BoardSize(val dim: Int, val winLength: Int) {
    CLASSIC_3X3(3, 3),
    PRO_4X4(4, 4)
}

data class BoardEvaluation(
    val status: String, // "ACTIVE", "X_WON", "O_WON", "DRAW"
    val winningIndices: List<Int> = emptyList()
)

object TicTacToeEngine {

    private val lines3x3: List<List<Int>> = listOf(
        listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
        listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
        listOf(0, 4, 8), listOf(2, 4, 6)
    )

    private val lines4x4: List<List<Int>> = listOf(
        // Rows
        listOf(0, 1, 2, 3), listOf(4, 5, 6, 7), listOf(8, 9, 10, 11), listOf(12, 13, 14, 15),
        // Cols
        listOf(0, 4, 8, 12), listOf(1, 5, 9, 13), listOf(2, 6, 10, 14), listOf(3, 7, 11, 15),
        // Diagonals
        listOf(0, 5, 10, 15), listOf(3, 6, 9, 12)
    )

    fun linesFor(boardSize: BoardSize): List<List<Int>> = when (boardSize) {
        BoardSize.CLASSIC_3X3 -> lines3x3
        BoardSize.PRO_4X4 -> lines4x4
    }

    fun evaluate(board: List<String>, boardSize: BoardSize): BoardEvaluation {
        val lines = linesFor(boardSize)
        for (line in lines) {
            val first = board.getOrNull(line.first()).orEmpty()
            if (first.isNotEmpty() && line.all { board.getOrNull(it) == first }) {
                val status = if (first == "X") "X_WON" else "O_WON"
                return BoardEvaluation(status = status, winningIndices = line)
            }
        }
        val expectedCells = boardSize.dim * boardSize.dim
        val isDraw = board.size >= expectedCells && board.take(expectedCells).none { it.isEmpty() }
        return if (isDraw) BoardEvaluation("DRAW") else BoardEvaluation("ACTIVE")
    }

    fun pickAiMove(
        board: List<String>,
        boardSize: BoardSize,
        aiSymbol: String,
        difficulty: AiDifficulty,
        rng: Random = Random.Default
    ): Int {
        val totalCells = boardSize.dim * boardSize.dim
        val emptyCells = (0 until totalCells).filter { board.getOrNull(it).isNullOrEmpty() }
        if (emptyCells.isEmpty()) return -1

        val humanSymbol = if (aiSymbol == "X") "O" else "X"
        val lines = linesFor(boardSize)

        // Immediate winning move or immediate block helper
        fun findImmediateWin(symbol: String): Int? {
            for (idx in emptyCells) {
                val copy = board.toMutableList()
                copy[idx] = symbol
                if (evaluate(copy, boardSize).status == "${symbol}_WON") return idx
            }
            return null
        }

        return when (difficulty) {
            AiDifficulty.EASY -> {
                if (rng.nextFloat() < 0.35f) {
                    findImmediateWin(aiSymbol) ?: emptyCells[rng.nextInt(emptyCells.size)]
                } else {
                    emptyCells[rng.nextInt(emptyCells.size)]
                }
            }
            AiDifficulty.MEDIUM -> {
                findImmediateWin(aiSymbol)
                    ?: findImmediateWin(humanSymbol)
                    ?: if (rng.nextFloat() < 0.6f) {
                        bestStrategicMove(board, boardSize, aiSymbol, humanSymbol, lines, maxDepth = 3)
                    } else {
                        emptyCells[rng.nextInt(emptyCells.size)]
                    }
            }
            AiDifficulty.HARD -> {
                findImmediateWin(aiSymbol)
                    ?: findImmediateWin(humanSymbol)
                    ?: bestStrategicMove(
                        board = board,
                        boardSize = boardSize,
                        aiSymbol = aiSymbol,
                        humanSymbol = humanSymbol,
                        lines = lines,
                        maxDepth = if (boardSize == BoardSize.CLASSIC_3X3) 9 else 4
                    )
            }
        }
    }

    private fun bestStrategicMove(
        board: List<String>,
        boardSize: BoardSize,
        aiSymbol: String,
        humanSymbol: String,
        lines: List<List<Int>>,
        maxDepth: Int
    ): Int {
        val work = board.toMutableList()
        var bestScore = Int.MIN_VALUE
        var bestIdx = work.indexOfFirst { it.isEmpty() }

        // Prefer center cells first when scores tie for snappy, natural play
        val preferredOrder = if (boardSize == BoardSize.CLASSIC_3X3) {
            listOf(4, 0, 2, 6, 8, 1, 3, 5, 7)
        } else {
            listOf(5, 6, 9, 10, 0, 3, 12, 15, 1, 2, 4, 7, 8, 11, 13, 14)
        }

        for (idx in preferredOrder) {
            if (idx < work.size && work[idx].isEmpty()) {
                work[idx] = aiSymbol
                val score = minimax(
                    board = work,
                    boardSize = boardSize,
                    depth = 1,
                    maxDepth = maxDepth,
                    isMaximizing = false,
                    alphaInit = Int.MIN_VALUE,
                    betaInit = Int.MAX_VALUE,
                    aiSymbol = aiSymbol,
                    humanSymbol = humanSymbol,
                    lines = lines
                )
                work[idx] = ""
                if (score > bestScore) {
                    bestScore = score
                    bestIdx = idx
                }
            }
        }
        return bestIdx
    }

    private fun minimax(
        board: MutableList<String>,
        boardSize: BoardSize,
        depth: Int,
        maxDepth: Int,
        isMaximizing: Boolean,
        alphaInit: Int,
        betaInit: Int,
        aiSymbol: String,
        humanSymbol: String,
        lines: List<List<Int>>
    ): Int {
        val eval = evaluate(board, boardSize)
        when (eval.status) {
            "${aiSymbol}_WON" -> return 100 - depth
            "${humanSymbol}_WON" -> return depth - 100
            "DRAW" -> return 0
        }
        if (depth >= maxDepth) {
            return heuristicScore(board, aiSymbol, humanSymbol, lines)
        }

        var alpha = alphaInit
        var beta = betaInit

        if (isMaximizing) {
            var best = Int.MIN_VALUE
            for (i in board.indices) {
                if (board[i].isEmpty()) {
                    board[i] = aiSymbol
                    val s = minimax(board, boardSize, depth + 1, maxDepth, false, alpha, beta, aiSymbol, humanSymbol, lines)
                    board[i] = ""
                    if (s > best) best = s
                    if (best > alpha) alpha = best
                    if (beta <= alpha) break
                }
            }
            return best
        } else {
            var best = Int.MAX_VALUE
            for (i in board.indices) {
                if (board[i].isEmpty()) {
                    board[i] = humanSymbol
                    val s = minimax(board, boardSize, depth + 1, maxDepth, true, alpha, beta, aiSymbol, humanSymbol, lines)
                    board[i] = ""
                    if (s < best) best = s
                    if (best < beta) beta = best
                    if (beta <= alpha) break
                }
            }
            return best
        }
    }

    private fun heuristicScore(
        board: List<String>,
        aiSymbol: String,
        humanSymbol: String,
        lines: List<List<Int>>
    ): Int {
        var score = 0
        for (line in lines) {
            var aiCount = 0
            var humanCount = 0
            for (idx in line) {
                when (board[idx]) {
                    aiSymbol -> aiCount++
                    humanSymbol -> humanCount++
                }
            }
            if (aiCount > 0 && humanCount == 0) {
                score += aiCount * aiCount * 4
            } else if (humanCount > 0 && aiCount == 0) {
                score -= humanCount * humanCount * 4
            }
        }
        return score
    }
}
